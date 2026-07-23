# Session handoff — Velle rules design

Written so a fresh Claude session with zero prior context can pick this up. Delete once its content has been absorbed into the permanent docs (`LANGUAGE.md`, `example_rules.md`, `TODO.md`).

## What Velle is

Dave's declarative system-design language. Core idea: "a condition is a shape" — instead of `if`/`else` branching, mutable state, and function calls, you declare `shape`s (typed records), `where`-refinements (named, pure predicate subsets of a shape — never triggers), and `rule`s (named reactions: given a refinement, produce evidence). Philosophy: Velle *describes*, it doesn't *execute* — declarative spec-writing and runtime/execution are two separate phases, and "compiling" mostly means validating that the spec is internally coherent (strongly-typed, unambiguous), not building an executable. See `LANGUAGE.md` §1 (Principles) and §2 (Philosophy) for the canonical statement of this.

`LANGUAGE.md` is the settled reference doc, now with all 17 top-level sections numbered (§1–§17) so they can be cited by number. `example_predicates.md` is a fully-resolved stress-test/derivation doc for the boolean predicate grammar used inside `where`/`requires` — treat it as closed. `TODO.md` tracks what's settled-but-not-yet-synced vs. genuinely open, split into "Language & structure" vs. "Compiling Velle into code" per Principle 1.

## Current focus: formalizing `rule`

Mirrors how predicates got formalized: work through real/plausible cases in a stress-test doc (`example_rules.md`), resolve gaps one at a time, then sync findings into `LANGUAGE.md`.

### Already drafted in `example_rules.md` (not yet synced to `LANGUAGE.md`)

Separating **`trigger`** from **`rule`**: a `rule` becomes purely a name + effect body; a separate top-level `trigger <RuleName> on <Schedule>` / `trigger <RuleName> when <Refinement> is created` declaration wires up when it fires. Resolved:
- Multiple `trigger`s per rule = **OR** (independent paths to the same effect, not a join/rendezvous), and this is safe for free because `produces`'s guard already had to be safe under concurrent firings for unrelated reasons.
- Inline `rule X on Y { }` survives as **sugar**, desugaring to `rule X { }` + `trigger X when Y is created` — it composes with standalone `trigger` declarations rather than competing with them (a rule accumulates however many triggers it has, however they're spelled).
- Postfix `on Schedule` similarly desugars to `trigger X on Schedule`.
- Deliberately kept `on Schedule` vs. `when Refinement is created` as two different keywords (not collapsed to one), preserving the existing "these shouldn't read as the same kind of thing" intent that used to live in prefix/postfix position.
- Open, not resolved: can a rule have zero triggers (dead code — probably a compile error, not forced by any real case yet).

This work isn't yet propagated into `LANGUAGE.md` §10/§14/§15 — still needs that sync pass, same as predicates did.

### Live discussion thread (not yet written into any doc — this is the part you'd otherwise lose)

Framing rules as **"behavior relationships between shapes"** (parallel to `one`/`many` as *structural* relationships), stress-testing that framing before tackling the separate, larger problem of connecting Velle down to real implementation (request parsing, DB writes, etc. — deliberately deferred, out of scope for this thread).

Three candidate axes to pull on: **trigger cardinality** (does a rule ever genuinely need N independent instances as joint input, or does every apparent multi-source case reduce to "one shape's refinement defined over related shapes"? — not yet explored), **rule-to-rule chaining** (explored, see below), **inverse relationships / provenance** (explored, resolved: dead end — see below).

**Inverse relationships / provenance — resolved as a dead end.** `one`/`many` gets a free inverse because the relationship is 1:1 at the *type* level. "Which rule produced this instance" isn't like that — multiple different rules can plausibly `produces` the same shape type, so there's no single static arrow to invert; it's a per-*instance* runtime/execution-history fact, not a per-*type* structural fact. This is *why* `why`/provenance is correctly parked in `LANGUAGE.md` §17 as a tooling/source-map concern rather than a language construct — not just "not done yet," but structurally can't be derived the way `invoices` off `Invoice.customer` is. If a specific "which rule" fact ever matters at the business level, the existing fix already covers it: state it as an ordinary explicit field (same as `basedOn` for aggregate provenance, `break_velle.md` #6), don't try to infer it generically.

**Rule-to-rule chaining — resolved as forbidden, explicit design principle (saved to persistent memory as `feedback_velle_no_rule_chaining.md`).** Dave's stated reasoning: allowing one rule to directly reference/invoke another would reintroduce function-call/stack semantics — exactly the computer-mechanics abstraction layer Velle exists to replace. Every rule-to-rule-*looking* case (compensating/rollback rules, "extend this rule for a more specific case") already resolves without direct reference: an independent rule reacting to a different produced shape, or a sharper refinement getting its own rule.

**DRY across rule bodies — the live open problem.** Concrete forcing case: `InitiateCharge` and (a hypothetically extended) `ReleaseInventory` both need to write an identical `AuditLogEntry from { order: ..., loggedOn: now }` fragment, differing only in the source expression for `order`. No mechanism today lets you name that once and reuse it across unrelated rules — pure copy-paste. This is explicitly *not* to be solved via rule-to-rule reference (see above); the fix has to be a smaller-than-a-rule reusable unit. Ties into the long-deferred **Mapping** construct (`LANGUAGE.md` mentions `produces`/`from` is already "a small inline Mapping," but a *named, reusable* Mapping has never been designed).

Two candidates were worked through:

- **Candidate 1 — reuse `LANGUAGE.md` §16 (Inputs and Outputs / function-shapes with `output:`).** E.g. `shape LogAudit { order: one Order, output: AuditLogEntry from { order: order, loggedOn: now } }`, invoked as `LogAudit for this` inside a rule body. **Rejected**, on a corrected/sharpened logical (not efficiency) ground: `output` is a *derived property* (§6), and derived properties are, by their own existing definition, recomputed *views* over facts that already exist — they can never *originate* a new independently-identified fact. Only `rule` + `produces` originates facts. So invoking `LogAudit` can make `AuditLogEntry` a computable *value* reachable by traversal, but can never make it a genuine independent fact other rules/refinements can query directly (`exists AuditLogEntry for this`) — which is exactly what the original use case needs. (An earlier, wrong framing of this rejection was in terms of "leaves junk/redundant rows in storage" — Dave correctly called that out as smuggling in an execution/storage-layer concern Velle explicitly has no opinion about, per Principle 1. The real objection is the type-level one above: only `rule`+`produces` creates facts; a derived-property-shaped mechanism can't be asked to do that job without secretly becoming a rule invoked from inside another rule — which is already excluded.)
- **Candidate 2 — a new, non-`shape` `mapping` construct** — a named, parameterized *value template* (record literal with holes), never itself instantiated or persisted, evaluated inline wherever referenced:

  ```
  mapping AuditLog(order: Order) = {
      order: order
      loggedOn: now
  }

  rule InitiateCharge on Order produces ChargeAttempt for order {
      AuditLogEntry from AuditLog(this)
      then
      ChargeAttempt from { order: this, requestedOn: now }
  }

  rule ReleaseInventory on FailedCharge produces InventoryRelease for order {
      AuditLogEntry from AuditLog(this.order)
      then
      InventoryRelease from { order: this.order, releasedOn: now }
  }
  ```

  Currently the favored direction: it never claims to originate a fact on its own (the enclosing rule's own `produces`/`from` still does that), so "only `rule`+`produces` creates facts" stays true without exception — same category of thing as a derived property or `count`/`sum`, safe for the same reason those already are (pure, non-triggered, non-effectful). **Not yet finalized or written into any permanent doc.**

## Immediate next steps (pick up here)

1. Decide whether candidate 2 (`mapping`) is the final answer, or keep pressure-testing it (e.g.: can a `mapping` reference another `mapping`? Can it take zero params? What's its relationship to the pre-existing anonymous `from { }` inline form — does `mapping` retire it the way `as`/`from` retired earlier forms, or coexist?).
2. Once settled, write it up properly in `example_rules.md` (same derivation style as the rest of that doc) and then sync into `LANGUAGE.md` (new numbered section, probably adjacent to §11/§13 `produces`/`then`, or folded into §16 Inputs and Outputs' unresolved-Mapping note).
3. Separately, still pending: sync `example_rules.md`'s already-settled `trigger`/`rule` split into `LANGUAGE.md` §10/§14/§15 (drafted, not propagated).
4. Still open, not yet explored: the trigger-cardinality axis (does a rule ever need N independent instances as joint input, or does it always reduce to one shape's refinement over related shapes).
5. `TODO.md` has not been updated with any of this session's rules-work yet — needs a pass once the above settles, same as was done for predicates.

## Relevant persistent memory (auto-loaded every session, not just this file)

- `feedback_velle_no_rule_chaining.md` — the rule-to-rule prohibition above, now saved as a durable project principle.
- `feedback_velle_syntax_register.md`, `feedback_velle_doc_examples.md`, `feedback_velle_focus_language_not_examples.md`, `project_velle_design.md` — pre-existing, still in force (word-based/business-readable syntax over math notation; every doc example must be self-contained; prioritize language design over fixing example bugs; general Velle design history).
