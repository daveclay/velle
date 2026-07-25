# Stress test: separating `trigger` from `rule`

`README.md` §10 already states the principle: a rule declares *what* effect corresponds to *what* refinement, not *when* or *how* that correspondence gets checked — mechanism (write-time check, scheduled sweep, event stream, or a mix of these for the same rule) is a compiling concern, not part of what the rule means. The syntax hasn't caught up to that yet: every rule declared so far still bakes exactly one trigger, in exactly one position, directly into the rule's own header or footer — prefix `on Refinement` or postfix `on Schedule`, never both, never more than one. If the same rule can legitimately be triggered more than one way, the rule declaration shouldn't be the thing that owns "when" at all.

`break_velle.md` already has a sketch of the fix, sitting next to the old form for comparison:

```
rule FlagOverdueAccounts {
    each FlaggedCustomer produces AccountFlag {
        AccountFlag for this basedOn: (this.invoices where OverdueInvoice) flaggedOn: now
    }
}

trigger FlagOverdueAccounts on Daily
trigger FlagOverdueAccounts when FlaggedCustomer is created
```

Same method as `example_predicates.md`: don't invent syntax speculatively, work through concrete cases already in the docs, and resolve each question a worked case actually forces.

## Inventory: every distinct rule/trigger form used so far

- Prefix, data-driven: `rule SendReceipt on SettledInvoice { ... }` — `README.md` §10
- Prefix, data-driven, with a `where`-narrowed refinement to react to: `rule ResolveFlagIfCleared { each Customer where exists ActiveAccountFlag for this and not (this is FlaggedCustomer) produces AccountFlagResolved { ... } } on Daily` — `example_invoice_payment.md` #5
- Postfix, schedule-driven, alone: `rule FlagOverdueAccounts { each FlaggedCustomer produces AccountFlag { ... } } on Daily` — `README.md` §14/§15
- Postfix, schedule-driven, comma list: `on Daily, Hourly` — `README.md` §15
- The new, not-yet-formalized sketch: no `on` anywhere in the `rule` block at all, one or more standalone `trigger <RuleName> on <Schedule>` / `trigger <RuleName> when <Refinement> is created` declarations elsewhere — `break_velle.md` (most recent edit)

## 1. Why the current syntax doesn't match the stated semantics

§10's contract is symmetric across mechanisms — write-time check, sweep, event stream, or a mix, all equally valid, all meaning the same thing. But the grammar today only has room for one `on`, attached to one rule, in one of two fixed positions. That's not neutral among mechanisms; it's a hard commitment to exactly one, chosen at the moment the rule is written. Wanting `FlagOverdueAccounts` to run both eagerly (react the instant a customer newly qualifies) and as a safety-net sweep (catch anything a missed event left stale) is exactly the "mix of these for the same rule" case §10 already claims is fine — and today's syntax has no way to write it at all without duplicating the entire rule body under two different names.

**Resolution:** pull triggering out of `rule` into its own top-level declaration, referencing the rule by name. A rule becomes purely a name plus a body (the effect); a `trigger` declaration is a separate, independent statement that wires a name to a way of invoking it. Zero, one, or several `trigger` declarations can point at the same rule name — the cardinality mismatch (one rule, one `on`) simply goes away, because triggering is no longer a property of the rule at all.

## 2. Does `trigger` retire prefix/postfix `on`, or does inline `on` survive as sugar?

The overwhelmingly common case — `rule SendReceipt on SettledInvoice { Receipt for invoice sentOn: now }` — has exactly one trigger and always will. Forcing every such rule to split into two lines (`rule SendReceipt { ... }` plus a standalone `trigger SendReceipt when SettledInvoice is created`) is the same shape of ceremony `example_composition_depth.md` flagged for `as`: requiring the general mechanism when nothing is actually ambiguous or plural leaks the computer layer into the logic layer for no payoff in the common case.

**Resolution: inline `on` stays, as sugar for the single-trigger case, and desugars to exactly the same underlying model a standalone `trigger` produces.**

```
rule SendReceipt on SettledInvoice {
    Receipt for invoice sentOn: now
}
```

is exactly

```
rule SendReceipt {
    Receipt for invoice sentOn: now
}
trigger SendReceipt when SettledInvoice is created
```

— not two different mechanisms, one sugared spelling of the other. Postfix `on Schedule` desugars the same way, into `trigger <RuleName> on <Schedule>`. Nothing about `## 14`/`## 15`'s existing examples needs to change in spirit, only in what they're understood to desugar to.

**Corollary — inline `on` and standalone `trigger` compose, they don't conflict.** Since every trigger for a given rule name is additive (resolved fully in #3, below), there's no ambiguity in also writing a standalone `trigger FlagOverdueAccounts on Daily` for a rule that already carries no inline `on` at all, or even (if a later, unrelated change needs to add a second path to an already-`on`-sugared rule) leaving the original inline `on` as-is and bolting on one more standalone `trigger` beside it. The two forms were never competing mechanisms — inline `on` is just a `trigger` written in a shorthand position. A rule is never required to "pick a style"; it only accumulates however many triggers it actually has, however they're spelled.

## 3. Multiple triggers for one rule — confirmed as OR, and confirmed safe

```
rule FlagOverdueAccounts {
    each FlaggedCustomer produces AccountFlag {
        AccountFlag for this basedOn: (this.invoices where OverdueInvoice) flaggedOn: now
    }
}

trigger FlagOverdueAccounts on Daily
trigger FlagOverdueAccounts when FlaggedCustomer is created
```

Two independent paths to the same effect: a `Daily` sweep, and an immediate reaction the moment some customer's own data change makes them newly a `FlaggedCustomer`. Multiple `trigger`s for one rule are a disjunction, not a conjunction — "run this rule's body whenever *any* of its triggers fire," never "wait for all of them." A rule with two triggers is not a rendezvous/join condition (`break_velle.md` #4, which is about a single firing needing evidence from two different producers before it may proceed) — it's the same effect reachable by two unrelated doors.

This composes with zero new mechanism because `produces` was already required to be safe under concurrent, independent firings (`break_velle.md` #4's "blanket obligation," `README.md`'s Compiled guardrails). If the `Daily` sweep and the eager event-driven path both happen to notice the same customer and both attempt to fire, `produces AccountFlag`'s guard already guarantees only one `AccountFlag` results — the same guarantee that already had to hold for two independent human writers, now covering two independent triggers on the same rule instead. Nothing about supporting multiple triggers per rule adds a *new* safety obligation; it just means an existing one (produces-is-safe-under-concurrency) gets exercised by a new kind of concurrency (trigger-level, not just writer-level).

## 4. Why `on Schedule` but `when Refinement is created` — deliberately different words, not drift

`README.md` §10 already establishes that prefix and postfix `on` "don't read as the same kind of thing even though both mechanically react to a shape existing" — the position difference was standing in for a real conceptual difference (reacting to a data condition vs. reacting to a schedule tick). Once triggering moves out of position-based sugar into an explicit keyword, that same distinction has to be carried by the keyword instead:

- `trigger Name on Schedule` — reacts to a named schedule tick, a thing that exists independent of any particular shape's data (`Daily`, `Hourly`).
- `trigger Name when Refinement is created` — reacts to some shape instance newly satisfying a refinement; "is created" reads this as an event (an instance becoming a member of the refined set) rather than a poll of current state, consistent with refinements already being pure, timeless predicates (§7) that something else has to notice changing.

Keeping two different keywords rather than collapsing to one (`trigger Name on X` for both) preserves the same "don't let two different kinds of thing read identically" intent §10 already committed to — just relocated from syntax position to a keyword choice.

## 5. Can a rule have zero triggers?

Not exercised by any real case yet, and not invented here. A `rule` block with no inline `on` and no standalone `trigger` pointing at it is inert — it can never fire, since nothing declares when it should. That's very likely a compile error (dead code: a rule that's structurally unreachable, the same category of thing as an unreferenced refinement might or might not be flagged as), but nothing in the docs has forced a real case that needs this decided. Flagging rather than resolving.

## Toward a formal grammar

```
ruleDecl     := "rule" Identifier ("on" RefinementName)? "{" ruleBody "}"
                -- inline "on" is sugar: "rule" Identifier "on" R "{" body "}"
                --   ≡ "rule" Identifier "{" body "}"  +  "trigger" Identifier "when" R "is" "created"

triggerDecl  := "trigger" Identifier ( "on" ScheduleList | "when" RefinementName "is" "created" )
ScheduleList := Identifier ("," Identifier)*
                -- postfix "on Schedule" / "on Daily, Hourly" after a rule body is the same sugar,
                --   desugaring to one or more "trigger" Identifier "on" Schedule declarations

ruleBody     := (effectStatement | eachProduces)+
```

A rule name may have any number of `triggerDecl`s pointing at it (inline-sugared, standalone, or both) — cardinality is additive, never conflicting, per #2–#3 above. Zero triggers is unresolved (#5).

## Not yet touched by this pass

- Whether `trigger` should also cover a "for each existing member of a refinement, once, immediately" bootstrap case (e.g. running `FlagOverdueAccounts` retroactively when the rule is first introduced against already-overdue accounts) — different question from ongoing triggering, not raised by any case here.
- `requires`, `## 12`/`## 13`'s `for`/`from` overlap, and the schedule-*definition* mechanism itself (what `Daily` actually is) are all separate, already-tracked gaps in `TODO.md` — untouched here, since none of them bear on the `trigger`/`rule` split specifically.

---

# Stress test: DRY across rule bodies (`mapping`)

> **SUPERSEDED — `mapping` was dissolved; see "Dissolved: `mapping` is not a construct" below.** This section and its `A`–`E` sequel are kept as the derivation trail that led to the dissolution: the DRY problem, the candidate constructs, the surface-syntax exploration. Its "Resolution:" statements describe a `mapping` construct that **no longer exists** — everything here decomposes into a shape + relationships + derived properties + occurrence-references. Read it as history, not current design.

A separate thread from the `trigger`/`rule` split above, sharing the same method. Two unrelated rules can need an identical effect fragment. `InitiateCharge` (reacting to a new `Order`) and a hypothetically extended `ReleaseInventory` (reacting to a `FailedCharge`) both need to write the same `AuditLogEntry`, differing only in the source expression for `order`:

```
rule InitiateCharge on Order produces ChargeAttempt for order {
    AuditLogEntry from { order: this, loggedOn: now }
    then
    ChargeAttempt from { order: this, requestedOn: now }
}

rule ReleaseInventory on FailedCharge produces InventoryRelease for order {
    AuditLogEntry from { order: this.order, loggedOn: now }
    then
    InventoryRelease from { order: this.order, releasedOn: now }
}
```

The `AuditLogEntry from { order: ..., loggedOn: now }` fragment is pure copy-paste. There's no mechanism today to name it once and reuse it. Crucially, the fix must *not* be one rule referencing another — that's forbidden as a durable design principle (rule-to-rule reference reintroduces function-call/stack semantics, exactly the computer-mechanics layer Velle exists to replace; see persistent memory `feedback_velle_no_rule_chaining`). The reusable unit has to be *smaller than a rule*. This is the long-parked **Mapping** goal — `README.md` §17 lists it as unfinished, and §12 already notes `produces`/`from` "is a small inline Mapping." What's never been designed is a *named, reusable* one.

## 1. Candidate — reuse §16 function-shapes (`output:`) — rejected

Reuse §16 by giving a shape an `output:` and invoking it inside a rule body:

```
shape LogAudit {
    order: one Order
    output: AuditLogEntry from { order: order, loggedOn: now }
}
```

**Rejected**, on a type-level ground (not efficiency). §16's `output` is a **derived property** (§6), and a derived property is by its own definition a recomputed *view* over facts that already exist — it can never *originate* a new, independently-identified fact. Only `rule` + a rule-body effect statement originates facts. So invoking `LogAudit` could make an `AuditLogEntry` a computable *value* reachable by traversal, but never a genuine independent fact that other rules/refinements can query directly (`exists AuditLogEntry for this`) — which is exactly what the use case needs.

An earlier framing of this rejection ("it leaves redundant rows in storage") was **wrong** and worth recording as wrong: that smuggles in an execution/storage-layer concern Velle explicitly has no opinion about (`## 1. Principles`). The real objection is purely the type-level one above.

## 2. Candidate — a `mapping` construct — favored

A named, parameterized *value template*: a record literal with holes, never itself instantiated or persisted, evaluated inline wherever referenced.

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

The mapping never claims to originate a fact on its own — the enclosing rule's effect statement (`AuditLogEntry from ...`) still does that. So "only `rule` + a rule-body effect creates facts" stays true without exception. It's the same category of pure, non-triggered, non-effectful thing as a derived property or `count`/`sum`, safe for the same reasons.

## 3. Why `mapping` is the right shape — it's the named form of `from { }`

The decisive framing: **a `mapping` is to `from { }` exactly what a refinement is to an inline `where`.** The language already commits to this "name-it-or-inline-it" duality twice, and `mapping` is just its missing third corner:

- a predicate is written inline (`invoices where balance > 0`) or named as a refinement (`shape OverdueInvoice = Invoice where ...`);
- §12 already states `from { }` "is what `produces` was always doing conceptually — a small inline Mapping — made visible in the syntax," and §17 parks "Mapping" as unfinished.

So `from { order: this, loggedOn: now }` **is already an anonymous mapping.** Candidate 2 isn't a new kind of thing — it names the *name-it* half of a duality whose *inline* half already ships. The grammar move is correspondingly tiny: `from` stops special-casing braces and takes *a record-valued expression*, of which the `{ }` literal and `MappingName(args)` are two forms:

```
effectStatement := ShapeName "from" recordExpr
recordExpr      := "{" (field ":" value)* "}"   -- inline literal (today)
                 | MappingName "(" args ")"       -- named mapping call (new)
```

This is a *simplification* of today's grammar (which special-cased `from { }`), not an addition — a good sign the construct is real rather than bolted on.

**This is also the crisp reason candidate 2 survives where candidate 1 failed:** a mapping has *no syntactic ability to originate a fact* — no `produces`, no trigger, no effect, no `output`, no identity. It has no way to originate a fact *at all* (`## 12` sharpens exactly where a mapping call may and may not appear); the effect statement that adopts the record originates the fact, precisely as with an inline literal today. The "only rule + produces creates facts" invariant holds **by construction**, not by argument — which is what candidate 1 (a `shape` that *looked* invocable and standalone) could never offer.

## 4. Retire inline `from { }`, or coexist? — coexist

**Resolution: coexist**, and by the same principle already applied to inline `where` vs. named refinement, and to the `as` / single-trigger ceremony rulings above (`## 2`, `## 3`). You never force someone to name a one-off. `as`/`from` retired *earlier forms* because those were worse spellings of the *same* thing; inline-vs-named is not that — it's a genuine duality where both sides earn their keep. Forcing every one-off record to be a named `mapping` would reintroduce exactly the "leak the general mechanism into the logic layer for no payoff in the common case" cost this doc has now rejected twice.

## 5. Can a mapping reference another mapping? — yes

**Resolution: yes**, the same way refinements compose (§8) and derived properties chain (§6). A mapping field's value is an ordinary `## 9` value expression, and a record-valued expression is a legal value, so a field may be `AuditLog(x)`. This isn't a bonus feature — nested mapping-in-mapping *is* the DTO→domain translation §17 originally wanted (a nested API payload becoming a nested domain record). The only constraint is the one §6 already imposes on self-referential derived properties: it must stay pure and terminating. A mapping reference cycle is that same compiler obligation, not a new language rule.

## 6. Zero params? — allowed, degenerate, unmotivated

**Resolution: allowed, no special rule either way.** `mapping DefaultAudit = { loggedOn: now }` is well-formed, and even non-constant — it reads ambient `now`, the same as any rule body or derived property does. Ambient `now` is evaluated at the *use site's* firing time, not at mapping-definition time, so two rules calling the same mapping get different `loggedOn` values — params plus ambient `now` are a mapping's only inputs (`## 11`). It simply isn't *forced* by a case, and a zero-param mapping is nearly a named constant, which Velle hasn't needed. Treat it like "can a function take zero arguments": trivially yes, nothing to design.

## 7. The boundary with §16 must be drawn explicitly

There are now *two* function-looking constructs, and a reader will ask why. The distinction is real but subtle enough that §16 and the mapping section have to point at each other and say "not this":

- a §16 **function-shape**'s `output:` is a **derived view** — it returns a *shape*, often an updated existing instance (`invoice with payments += payment`). It's a computed query over facts that already exist, and it *is* a shape (has identity, could be instantiated).
- a **mapping** returns a *bare record with no identity and no named target shape*. It becomes an `AuditLogEntry` only when a `from` adopts it. It cannot be persisted or invoked on its own.

## 8. Structural typing vs. named output shape — lean structural (flag)

`mapping AuditLog(order: Order) = { ... }` doesn't declare that it builds an `AuditLogEntry`; the `from` site's totality check (§12) does. **Leaning: structural / checked-at-use-site**, with an *optional* `:: AuditLogEntry` annotation available when you want the mapping validated independently of any use site — double colon, since single `:` is already the field/param separator (`order: Order`) — parallel to `produces ... for <field>` being inferred-but-overridable. Structural matches how inline `from { }` is already checked and is more reusable (one mapping could feed any field-compatible shape). Its cost: an unused or single-use mapping is only ever checked against that one site — the same limitation inline `from { }` has today. **Flagging, not fully resolved** — no case yet forces a mapping to be validated independent of a use site.

## 9. Extension / spread — separate, unforced (flag)

Nesting (a mapping field valued by another mapping, `## 5`) is in. But "`AuditLog` plus one extra field" — a flat *extension* like `AuditLog(this) with severity: "high"` (reusing §16's `with`) or a record spread — is a *different* composition, and no worked case forces it. Per this doc's own method, **left open** rather than invented speculatively.

## 10. Is `mapping` even forced yet? — methodology check

Recorded honestly: the forcing case is thin — one pair of rules sharing one two-field fragment. The `## 3` reframe makes `mapping` **coherent** (the missing corner of a duality the language already commits to), but coherent is not the same as **forced**, and this repo's discipline is "don't invent syntax speculatively; resolve what a worked case actually forces." So the most defensible outcome may not be "promote `mapping` to a numbered `README.md` section now," but "record the resolved *design* — the `from`-takes-a-record-expr generalization (`## 3`) and the §16 boundary (`## 7`) included — in §17's Mapping note, and promote it the moment a second, independent DRY case appears." Flagging the promotion decision itself as the open call.

## 11. What's in scope inside a mapping body — no `this`

The `## 1`–`## 10` pass never said what names a mapping body can see, and the answer is load-bearing. A rule body and a §6 derived property both carry an *implicit subject* — `this`, the instance being reacted to or the shape the property hangs off. A mapping has neither a trigger nor a host shape, so **a mapping body has no `this`.** Its scope is exactly its declared params plus ambient (`now`, `## 6`) — nothing else.

```
mapping AuditLog(order: Order) = {
    order: order        -- `order` is the param; there is no ambient `this` to reach
    loggedOn: now       -- `now` is the only ambient name in scope
}
```

This is precisely why `ReleaseInventory` (`## 2`) has to pass `AuditLog(this.order)` explicitly rather than the mapping fishing the order out of the triggering instance itself — the triggering instance simply isn't a name the mapping can see.

**Resolution: a mapping body's scope is its params plus ambient `now`, and nothing else — in particular, no `this`.** This isn't a limitation to apologize for; it's the structural line between a mapping and a rule. A construct with no `this` *and* no `produces` cannot be a disguised rule, which tightens `## 3`'s "safe by construction" claim from an argument into a scoping fact: there is no name in a mapping body through which it could reach out and act on the world.

## 12. Where a mapping call may appear — any record-valued position, not only `from`

`## 3` said a mapping "can only appear in the `from` slot," but `## 5` (a mapping field valued by another mapping) already contradicts that — a nested mapping call sits in a *field-value* position, not a `from` slot:

```
mapping AuditLog(order: Order) = {
    order: order
    loggedOn: now
}

mapping DetailedAudit(order: Order, reason: Text) = {
    entry: AuditLog(order)      -- a mapping call as a field value — not a `from` slot
    reason: reason
}
```

Both can't be literally true. The precise rule: **a mapping call is an ordinary record-valued value expression (§9), legal wherever a record value is legal** — as a field value inside another mapping (`## 5`), or in the `from` slot of an effect statement. What a mapping *lacks* is any way to **originate a fact**, and that ban is the absence of `produces` / trigger / identity (`## 3`, `## 11`), *not* a restriction to one syntactic slot. `## 3`'s "only appears in the `from` slot" was shorthand for the fact-origination ban and is superseded by this: a mapping call flows freely as a value; it just never *lands* as a fact except where an effect statement's `from` adopts its record.

## 13. The one-record ceiling — sharing a *block* of effects is out of scope, by design

Every case so far shares one record. What if two rules share *two* facts — say both must write an `AuditLogEntry` **and** fire a `Notification`?

```
rule InitiateCharge on Order produces ChargeAttempt for order {
    AuditLogEntry from AuditLog(this)
    Notification  from Notify(this)
    then
    ChargeAttempt from { order: this, requestedOn: now }
}

rule ReleaseInventory on FailedCharge produces InventoryRelease for order {
    AuditLogEntry from AuditLog(this.order)
    Notification  from Notify(this.order)
    then
    InventoryRelease from { order: this.order, releasedOn: now }
}
```

Each shared *record* is already named (`AuditLog`, `Notify`) — fully DRY at record granularity, one mapping per fact. What stays duplicated is the *pairing*: the fact that an audit entry and a notification always fire together. A mapping cannot name that, and **deliberately** cannot — naming a pairing means naming a sub-body of effect statements, which is rule-fragment reuse, forbidden by `feedback_velle_no_rule_chaining` (it reintroduces the call/stack semantics Velle exists to replace).

**Resolution: a mapping is exactly one record → one `from` → one fact.** Block-level sharing isn't a missing mapping feature to design later; it lands squarely in the territory the no-rule-chaining principle already excludes. That the ceiling falls exactly on the forbidden line is a confirmation the granularity is right, not a gap: the reusable-unit-smaller-than-a-rule is a *record*, and a record is a single fact's worth of data — never a sequence of effects.

## Toward a formal grammar

```
mappingDecl  := "mapping" Identifier "(" params ")" ("::" ShapeName)? "=" recordExpr
                -- params may be empty (## 6); "::" output-shape annotation optional (## 8)
params       := (Identifier ":" TypeName ("," Identifier ":" TypeName)*)?

recordExpr   := "{" (Identifier ":" valueExpr)* "}"   -- inline literal, unchanged
              | MappingName "(" args ")"                -- named mapping call
args         := (valueExpr ("," valueExpr)*)?
valueExpr    := ... (§9 value expression) | recordExpr  -- a record value (incl. a mapping call) is
                                                        --   a legal field value, so mappings nest (## 5, ## 12)

effectStatement := ShapeName "from" recordExpr         -- "from" now takes any recordExpr (## 3)
                                                        --   a mapping call originates a fact ONLY here,
                                                        --   never by appearing as a nested value (## 12)
```

A mapping is pure, non-triggered, non-effectful, and never originates a fact — it only supplies the record an effect statement's `from` adopts. Reference cycles are a compiler obligation (`## 5`), the same as self-referential derived properties (§6).

## Not yet touched by this pass

- Extension/spread of a mapping (`## 9`) — a flat "reuse plus one more field" form, unforced.
- Whether a mapping should be promoted to its own `README.md` section or kept as a resolved sketch in §17 until a second forcing case (`## 10`).
- Whether the optional output-shape annotation (`## 8`) is worth its weight before a case wants a mapping validated independent of use.
- Whether a mapping *param* may be `one`/`many`/refinement-typed rather than a plain shape/scalar (`params := Identifier ":" TypeName` above leaves `TypeName` unspecified) — brushed by `## 9`'s extension flag, forced by no case yet. Every worked case passes a single shape (`order: Order`).

---

# Dissolved: `mapping` is not a construct

Everything the `## 1`–`## 13` / `A`–`E` threads built a `mapping` construct to do decomposes, with nothing left over, into things Velle already has — **a shape, relationships its producing rule wires up, and derived properties (§6)** — plus one genuinely new thing that is *temporal*, not transformational, and gets its own thread below (**occurrence**). So there is no `mapping` construct. This section records why, and why that is the right answer rather than a shortcut.

## The diagnosis — a mapping was a homeless derived property

A `mapping` read as code because it was the one construct describing a *transformation* (inputs → a new record) rather than a state or a relationship — a verb. And a verb with no subject is a function, which is exactly what "it's not a function" kept failing to deny.

But Velle already contains this computation and it does *not* read as code: the **derived property** (§6). `Invoice.total: sum of items.amount` is, field-for-field, the same kind of thing as a mapping line — a value defined in terms of other values. Nobody calls it a function, because it is *anchored to a shape*: a timeless property of the Invoice. A mapping was a derived property **evicted from its shape**. The homelessness was the whole code-smell.

Re-anchor it and the transformation disappears. Give the produced shape a relationship to what it is made from, and every pass-through, rename, and compute becomes an ordinary derived property reaching *through that relationship*:

```
-- not a mapping; just a shape whose fields are derived properties
shape OrderSnapshot {
    order:        one Order                -- the relationship the producing rule establishes
    customerName: order.customer.name      -- derived property
    total:        order.total              -- derived property
    ...
}
```

`customerName: order.customer.name` is the same construct as `Invoice.total: sum of …` — a derived property over a relationship. Nothing is being transformed; the fields are just *properties*.

## The residue was `now`, and `now` was the only imperative token left

Push every field through the re-anchoring and only two kinds resist:
- `loggedOn: now` — not derivable from any source; an ambient value that exists only at the instant of birth.
- any field meant to be *historical* — a snapshot's `total` should be the value *as it was*, not a live view that drifts.

Both are the same thing: **capture** — freezing a value at the moment of birth. That was the entire irreducible residue of "mapping." Not transformation. Capture.

And capture was an illusion created by one token: `now`, meaning "read the clock at execution time" — the sole genuinely imperative instruction in the language. Replace it with a *reference to the moment an event occurred* — "the moment this was reconciled," "when the payment was made" — and the timestamp stops being a captured runtime value and becomes a reference to an **occurrence**, which is a fact like any other: permanent and timeless once true. Nothing is frozen because nothing was ever live. `reconciledOn` was always "the moment of that event"; we were spelling it `now` and letting the runtime fill it in.

Crucially this **dissolves the mutability question rather than answering it.** Whether the runtime stores a snapshot, recomputes, or versions is exactly the detail Velle is entitled to defer — a derived property is timelessly true whenever it is evaluated. Capture only looked necessary because `now` smuggled a runtime moment into a fact.

## The proof — the two hard cases, with no `mapping`

(`when this occurred` is illustrative of the concept, not proposed syntax — the occurrence thread deliberately fixes no surface yet.)

```
shape AuditLogEntry {
    order:    one Order            -- established by the producing rule
    loggedOn: when this occurred   -- a reference to this fact's own moment
}
```
```
shape LedgerEntry {
    payment:      one Payment            -- established by the rule, from its trigger
    exchangeRate: one ExchangeRate       -- established by the rule, the one it looked up
    baseAmount:   payment.amount times exchangeRate.rate   -- derived property
    convertedOn:  when this occurred
}
```

A shape, relationships the **rule** wires up, derived properties, and moment-references — no transformation, no `from … as`, no bare pass-through, no capture. `baseAmount` stays correct not because it is frozen but because a payment's amount and a historical rate are *event-facts that never change* — immutability isn't decreed, it is what those nouns are.

## What each old thread became

- **pass-through / rename / compute** → derived properties over a source relationship. (A rename is a derived property with a different name than its source — and still visibly the *vocabulary-impedance smell* it always was: it exists only because two shapes disagree on a name.)
- **the source subject(s), single or multi, traversed or looked-up** → relationships the producing rule establishes. This dissolves the entire `## 5`–`## 13` / `C` multi-subject apparatus at once: a shape has as many source relationships as it has, and "how many subjects," "lookup vs. traversal," "same-shape aliasing," and the call-site question are all just *the rule* deciding what to relate the new fact to. There is nothing left for a mapping to arbitrate.
- **capture (`now`, historical values)** → occurrence-references (next thread).

## The philosophical result

**A mapping was never a construct; it was a symptom.** It was the one place the language forced the question "should this value track its source, or freeze?" — which is the mutability question wearing a costume. Everywhere else you can stay agnostic about mutability; the moment you snapshot, you must take a stand, and a `mapping` was the recipe you wrote to paper over not having taken it. Nounify time — make the *moment* a fact — and the question dissolves, the recipe evaporates, and what is left is shapes, relationships, derived properties, and a new first-class citizen: **when things occur.**

The cost, carried into the next thread: once time is a noun, every value is implicitly *as of* a moment.

---

# New thread: occurrence — the *when* of the system (concept, not yet syntax)

The residue of the mapping dissolution is a single new idea: **occurrence** — *when* a thing happens or becomes true, treated as a first-class fact. It sits alongside the others as a distinct kind:

- **shapes** — *what* exists
- **relationships** (`one`/`many`) — *how* facts connect
- **refinements** — *which* facts qualify
- **rules** — *what* follows from *what*
- **occurrence** — *when* things occur

Per this repo's method the thread names and scopes the concept and records what forces it; it does **not** invent syntax (`when this occurred` above was illustrative).

## It is the genus of things already scattered through the docs

Occurrence isn't bolted on — three constructs already in play are all faces of it, previously unnamed:

- `trigger X when FlaggedCustomer is created` — a rule **reacts to** an occurrence (a fact entering a refinement).
- `on Daily` — a schedule **generates** recurring occurrences.
- `loggedOn: now` → "when this occurred" — a field **refers to** an occurrence.

So the `trigger`/`rule` stress-test at the top of this file was already building the *reactive* half of this concept without naming the genus; timestamp fields are its *referential* half. Unifying them is most of the work.

## What forces it

1. The mapping dissolution: capture had to land somewhere, and it lands here.
2. Unification: triggers, schedules, and timestamps are three uses of one idea; leaving them un-unified is the same "syntax hasn't caught up to the semantics" gap the `trigger`/`rule` split opened with.

## Open questions (recording, not resolving)

- **"As of a moment" depth — the load-bearing one.** Being worked in `example_time.md`. Nounifying time makes every derived value implicitly *as of* some moment. `loggedOn` (a fact's own moment) is trivial; a genuinely-changing quantity is not. Forcing case: an `Order`'s `total` while line items are still being added — is `total` a live sum, or "the sum as of when this occurred"? Current standing (see that doc): **live by default, pinned where a mutable source must be frozen; the freeze/live choice is per *read*, not per shape** (so `record`/`entity` is not a Velle concept), and if freezing surfaces at all its locus is **relationship membership as of an occurrence**, not a scalar operator. Open hunt: is every freeze-point nameable as such a membership? If yes this closes with no new scalar syntax.
- **What kinds of occurrence there are, and whether they are one kind.** A fact's origination ("when this was created"), a fact entering a refinement ("when it became a `FlaggedCustomer`"), a schedule tick (`Daily`), an externally-asserted event ("when the payment was made" — is that just the `Payment` fact's origination, or a separately declared event?). Candidate: all are occurrences of one kind; not yet tested.
- **Reference vs. reaction — one thing or two?** Timestamps *refer to* occurrences; triggers *react to* them. Leaning: one concept consumed two ways, mirroring how a refinement is both a queryable set and a trigger condition. Unconfirmed.
- **Is `now` ever irreducible?** Is there a legitimate "the actual wall-clock instant of execution" that is *not* expressible as "the moment of some event" (a genuinely external / nondeterministic reading)? If so, `now` survives as a narrow primitive rather than fully dissolving. Flag.
- **Ties to parked items.** §17 provenance and the executable-vs-spec fork both live here — provenance only means something if occurrences persist and accumulate rather than overwrite, which is the same immutable-leaning world the "as of" answer relies on.

## Not yet touched

- Any surface syntax for referring to, reacting to, or scheduling occurrences (deliberately deferred).
- Whether "occurrence" is the right *name* (event / moment / when are alternatives).
- Whether derived properties need explicit temporal qualification at all, or whether the common cases (own-moment references, naturally-immutable event-facts) cover enough that "as of" rarely surfaces.
