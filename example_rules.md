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

