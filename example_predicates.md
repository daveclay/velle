# Stress test: formalizing predicate syntax

`TODO.md` flagged this as a gap with no home: the expression language used inside `where`/`requires`/`visible to ... where` — comparisons, `and`/`or`/`not`, `is`, `exists`, `count`, `sum`, relationship traversal — has only ever been used by example, never specified as its own grammar with defined precedence and semantics. Negation and disjunction surfaced as real ambiguities in `break_velle.md` #6 with nowhere to be resolved.

Same method as `break_velle.md`/`example_refinements.md`: don't invent syntax speculatively, look at every predicate already written across the docs, find where it's inconsistent or silently depends on an unstated rule, and resolve each with a worked case — new mechanism only if a real case forces it.

## Inventory: every distinct predicate form used so far

Pulled verbatim from `LANGUAGE.md`, `example_invoice_payment.md`, `example_refinements.md`, `break_velle.md`:

- Comparisons: `balance > 0`, `due < today`, `escalations >= 1`, `priority == "high"`, `response.outcome = "approved"` (note: two different equality spellings already in the wild)
- Boolean composition: `and` (everywhere), `not` (`not exists Receipt for this`, `not (this is SettledInvoice)`) — `or` never actually appears in a worked example, despite being named in `TODO.md` as expected grammar
- `is`: `assignee is none`, `response is some`, `corrections is not empty`, `alert is UnacknowledgedAlert`, `basedOn is SupersededLabResult`, `this is FlaggedCustomer`
- `exists`: `exists Receipt for this`, `exists PharmacistVerification for this`, `not exists AccountFlagResolved for this`
- Aggregates: `count(invoices where OverdueInvoice) >= 3`, `count(ward.admissions where ActiveAdmission) < ward.totalBeds`, `sum(payments, amount)`
- Relationship traversal (dot access): `response.outcome`, `this.recordedOn`, `ward.admissions`, `patient.vitalReadings`
- `for` reused as a query expression, not just an effect clause: `(NurseVerification for this).nurse`, `(GracePeriod for this).endsOn`
- Date/duration arithmetic: `today - 7 days`, `now + 14 days`, `this.recordedOn - 10 minutes`
- Nested `where` with implicit scoping: `patient.vitalReadings where LowReading and recordedOn >= (this.recordedOn - 10 minutes) and recordedOn <= this.recordedOn`

## 1. Equality: `==` vs `=`

Both spellings appear for the same thing: `priority == "high"` (`example_refinements.md`) vs. `response.outcome = "approved"` and bare `outcome = "approved"` (`example_invoice_payment.md`). This isn't a design question, just drift — but it collides with existing meaning: `=` is already claimed at the shape-definition level (`shape X = Y where ...` means "is defined as"). Reusing it for value equality inside a predicate risks reading `shape X = Y where a = b` as three uses of the same token for two different relations (definitional equality vs. value equality).

**Resolution:** standardize on `==` for value equality inside predicates; `=` stays reserved for shape definition. The `example_invoice_payment.md` occurrences (`response.outcome = "approved"`, `outcome = "approved"`) are pre-existing drift to fix, not a live design choice.

>

## 2. `or` — never actually exercised

Every worked predicate so far is a conjunction. `or` is named in `TODO.md` as expected grammar but no stress test has actually forced it. Constructing one deliberately, extending the `SupportTicket` mixin set from `example_refinements.md`:

```
shape NeedsAttention = Overdue or Escalated
```

A ticket needing attention if *either* condition holds, not necessarily both — a genuinely common alerting shape (`or` across mixins, same combinator position as `and`). This composes exactly like `and` did: desugars to disjunction of the operands' predicates, same base-shape-compatibility guardrail (`Overdue or SuccessfulCharge` should still be a compile error — unrelated shapes, meaningless to combine either way).

**Precedence**, previously unstated because only one operator was ever in play: `and` binds tighter than `or`, matching the near-universal convention (`&&`/`||`, SQL). `not` binds tighter than both. So:

```
A and B or C   ≡   (A and B) or C
not A and B    ≡   (not A) and B
```

Mixed `and`/`or` in the same expression should still be required to use explicit parens when it's not a simple left-to-right chain — not enforced by the grammar, but a style rule worth stating, the same way `not (this is SettledInvoice)` already parenthesizes defensively rather than relying on readers to know `not` binds to the whole trailing expression vs. just the next atom.

Note this is orthogonal to the provenance blind spot in `break_velle.md` #6 — that was about **capturing evidence for an aggregate that a disjunction contributed to**, resolved separately (explicit `basedOn`, not inferred). This section is purely about `or`'s own syntax and precedence, which is a real gap independent of that resolution.

>

## 3. `is` — one operator or three?

Every use of `is` so far falls into one of three shapes:

- optionality: `assignee is none`, `response is some`
- collection emptiness: `corrections is not empty`
- refinement membership: `alert is UnacknowledgedAlert`, `basedOn is SupersededLabResult`, `this is FlaggedCustomer`

**Resolution:** these aren't three operators that happen to share a keyword — `is` is one operator, "is this currently classified as," and its right-hand side determines which classification system is being tested:

- a reserved optionality token (`none` / `some`) — valid only when the left side is an optional field (`?`) or a to-one relationship that can be absent
- a reserved collection token (`empty` / `not empty`) — valid only when the left side is a `many` relationship or collection-typed expression
- a Shape name — valid whenever the left side's shape and the named shape share a base (same compatibility rule `and`/`or` already use), and it means ordinary refinement-predicate evaluation

Nothing new needed — this is documentation, not new syntax. Worth stating explicitly in `LANGUAGE.md` as three sanctioned right-hand forms of one keyword, so a future predicate doesn't invent a fourth meaning for `is` by accident.

Checked against existing typing rule: `assignee is none` and `response is none` are both applying `none`/`some` to optional fields already declared with `?` in their shapes (`assignee: one User?`, `response: ...?` implied by `PendingChargeAttempt = ChargeAttempt where response is none`) — consistent, no exception found.

>

## 4. `for` as a query expression — cardinality is unstated

`Receipt for invoice` and `AuditLogEntry for order` were introduced purely as *effect* syntax — associating a newly produced instance with its subject. But two places reuse it as a *query* expression that must resolve to a single existing instance:

```
Administration for this administeredBy: (NurseVerification for this).nurse administeredOn: now
...
shape InGracePeriod = Customer where exists GracePeriod for this and today <= (GracePeriod for this).endsOn
```

Both assume exactly one `NurseVerification`/`GracePeriod` exists for the subject. Nothing states what happens if that's false (zero — should already be excluded by the surrounding `exists`/refinement, so probably fine) or, more importantly, if more than one exists (a `MedicationOrder` re-verified after a correction, a `Customer` who's been through two separate grace periods historically). This is the same missing "selection/ordering" primitive flagged in `break_velle.md` #2 (picking "the earliest of the 3" readings) — a real gap, not yet forced to a resolution by any single example, but two examples now silently lean on it.

**Options, not resolved here:**
- Compiler requires the relationship to be structurally provably at-most-one (e.g. a `produces`-guarded shape can't have a second instance for the same subject by construction) — `for`-as-expression only typechecks when that's provable, else compile error demanding an explicit selector.
- Introduce an explicit selector (`latest(...)`, `first(...)`) and make bare `X for Y` a compile error whenever more than one instance could exist — consistent with the project's "fail closed on undeclared cases" instinct from `break_velle.md` #5's visibility question.
- Silent "most recent wins" default — inconsistent with how the project has handled every other silent-default temptation so far (visibility, consecutive-vs-windowed counting); flagging it only to rule it out, not as a real candidate.

Leaving unresolved — this is a genuine new mechanism (the first selection/ordering primitive), not a naming or precedence fix, and deserves its own decision rather than being bundled into the syntax cleanup this doc is otherwise doing.

>

## 5. `this` binding across nested `where`

The windowed-BP example is the only predicate so far with two levels of `where`:

```
shape ThirdConsecutiveLowReading = VitalReading where
    systolicBP < 90
    and count(patient.vitalReadings where LowReading and recordedOn >= (this.recordedOn - 10 minutes) and recordedOn <= this.recordedOn) >= 3
```

It works, but only because of an unstated rule: `this` is bound once, to the outermost shape being defined (`VitalReading`, the reading under test), and does **not** rebind inside the nested `where` reached through `count(...)`. The nested `where`'s own subject (each candidate reading in `patient.vitalReadings`) is instead referred to by bare unqualified field names (`recordedOn`, matched against `LowReading`'s own `systolicBP < 90`). So the convention actually in use is: **unqualified field names always mean the innermost collection-filter's element; `this` always means the original outer subject, regardless of nesting depth.**

Checked against the example: `recordedOn >= (this.recordedOn - 10 minutes)` — `recordedOn` unqualified = the inner candidate reading, `this.recordedOn` = the outer reading being tested. Holds.

**Edge case not yet forced by any example:** nothing so far has needed to name the inner collection element explicitly (e.g. a self-join comparing two different inner readings against each other, three levels deep). If that comes up, "unqualified = innermost" stops being enough and needs an actual bound name (`as` or similar). Flagging, not inventing — same discipline as #4.

**Resolution for now:** state the rule above explicitly in `LANGUAGE.md` (it's real and load-bearing today, just never written down) and leave the deeper nested-binding question open until a real example needs it.

>

## 6. `count`/`sum` signature mismatch — resolves as no mismatch

`count(invoices where OverdueInvoice)` takes one argument (a collection expression); `sum(payments, amount)` takes two (collection, field). Looked like an inconsistency in how the two aggregates use `where`.

**Resolution:** there isn't one, because `where` was never part of either call's syntax — `invoices where OverdueInvoice` is already a complete, independently valid collection expression (a relationship filtered by a refinement), and both aggregates simply take a collection expression as their first argument. `count` only needs the collection; `sum` additionally needs to know which field to add, hence the second argument. That's arity following from what each aggregate fundamentally computes, not an inconsistency in how filtering works.

This means `sum(payments where SuccessfulPayment, amount)` should already be valid today even though no example has tried it — worth stating as the general call shape rather than leaving `sum` looking unfiltered by omission:

```
count(<collection-expr>)
sum(<collection-expr>, <field>)
```

>

## 7. Multi-hop traversal: naming intermediate bindings (`as`)

The deeper structural question underneath #5: every predicate so far implicitly relies on exactly two binding positions — `this` (always the outermost subject) and a bare unqualified field name (always the *innermost* collection element in the nearest `where`). That's enough for everything written so far because nothing has gone past two hops. It breaks down at three: `Customer` → `invoices` → `payments`, needing to reference the *invoice* in the middle — not the customer (`this` skips straight past it to the root) and not the payment (bare names now mean the innermost level). There's no position left to put a reference to the middle hop.

**Resolution:** an optional `as <name>` binding on a collection expression, usable anywhere `where` filters a collection:

```
shape CustomerWithBadInvoice =
  Customer where exists (
    invoices as inv where
      inv is OverdueInvoice
      and count(inv.payments where FailedPayment) >= 1
  )
```

`inv` names the middle rung so the nested `payments` filter (still using the ordinary bare-name-means-innermost convention for `FailedPayment`'s own fields) can be reached via `inv.payments`, while the aggregate's own predicate keeps referring to *its* innermost element (`FailedPayment`) unqualified, same as every `count(collection where Refinement)` before it.

This is additive, not a replacement: `invoices where OverdueInvoice` (no `as`) keeps working exactly as before for one- and two-hop predicates — `as` only earns its keep once a predicate needs to reach back to a hop that bare names and `this` can't address. The cleanest way to state the whole scheme: **`this` is just the always-present, built-in alias for the root subject; `as` lets a predicate introduce additional named aliases for any collection-filter scope it opens.** One mechanism (named binding), two ways to get one (implicit for the root, explicit via `as` for anything nested), rather than two unrelated ideas.

**Scoping:** an alias is visible within the predicate it's declared over and any predicate nested inside that scope (the same lexical-scoping shape as a SQL correlated subquery) — it doesn't leak back out to the enclosing scope or across to a sibling `as` binding declared elsewhere in the same predicate.

**Resolves the open edge case from #5** (naming an inner collection element explicitly, three levels deep) — that was exactly this gap, just not yet given a syntax.

**Still not covered, flagging rather than inventing:**
- **Sibling joins** — binding two *different*, unrelated collection paths in the same predicate (true SQL multi-table join, e.g. "this invoice's customer has a support ticket that's also overdue," joining two paths that only share the customer, not a straight chain). No worked example has forced this yet; revisit if one does rather than guessing at comma-separated multi-binding syntax now.
- **This is not the `for`-as-expression cardinality problem from #4.** `as` names a scope for filtering — it says nothing about pulling a single matched instance back out to the surrounding expression as a value (what `(NurseVerification for this).nurse` tries to do today). Different problem, still open.

>

## 8. Prior-art check: Datalog

Transliterated several ICU predicates from `break_velle.md` directly into Datalog (Soufflé-style) to see whether Velle's predicate grammar is missing anything a mature relational-logic formalism already had to solve.

```prolog
lowReading(R) :- vitalReading(R, _, Systolic, _), Systolic < 90.

thirdConsecutiveLowReading(R) :-
    vitalReading(R, Patient, Systolic, T), Systolic < 90,
    3 <= count : { R2 : lowReading(R2), vitalReading(R2, Patient, _, T2),
                        T2 >= T - 600, T2 <= T }.

fullyVerified(Order) :-
    pharmacistVerification(_, Order, _, _),
    nurseVerification(_, Order, _, _).

administrationAllowed(Order) :-
    fullyVerified(Order),
    !administration(_, Order, _, _).
```

Three things came out of this worth keeping, none of them syntax changes:

- **Stratified negation gives a formal name to the negation blind spot from `break_velle.md` #6.** Datalog with unrestricted `not` is unsound; the fix is organizing rules into layers ("strata") so a negated relation is fully computed before anything negates it. This doesn't retroactively solve the provenance problem (a witness-free absence is still witness-free), but it's a real, checkable criterion — "is this refinement's dependency graph stratifiable" — that the compiler's exhaustiveness/overlap-checking goal (`LANGUAGE.md` `## Refinements`) could formalize against, rather than relying on intuition that a given `not exists`/`not (this is ...)` predicate is well-founded.
- **The concurrency hazard from `break_velle.md` #4 reappears identically in live Datalog, which independently confirms that resolution was correct.** Pure Datalog's fixpoint semantics assume a *static* fact set — "concurrent write" isn't a concept at that layer. The double-fire/starvation hazard only reappears once Datalog runs incrementally over a *live, mutating* store, which is exactly Velle's situation — and it's precisely why systems that do this for real (Datomic, LogicBlox) bolt their own transaction semantics on top rather than relying on the logic alone. Evidence that "safe + live `produces` realization is a compiler/runtime obligation, not a language gap" generalizes beyond Velle specifically.
- **Datalog's positional-tuple facts are hard to read** (`pharmacistVerification(_, Order, _, _)` — three placeholders for columns the rule doesn't use) — confirms, rather than challenges, Velle's shapes-as-named-records design: a predicate here only ever names the fields it uses (`exists PharmacistVerification for this` says nothing about the shape's other fields). Worth stating as a design principle behind the grammar, not just a stylistic preference: named-field access over positional arguments, same register as preferring `and`/`or`/`is` over symbolic operators.

**One genuine open question surfaced, not resolved:** Datalog supports recursive rules natively (e.g. transitive closure); nothing in Velle has tried this. `break_velle.md` #3's escalation chain (nurse → doctor → attending) is recursive *in effect* but modeled as a chain of distinct shape instances (each `Escalation` anchored `via schedule 15 minutes after Escalation`), not a single self-referential definition. A true recursive refinement would need a shape referencing *either* an `Alert` or a prior `Escalation` as its anchor — a sum type, which nothing in Velle has needed before. Left open pending a real forcing example rather than speculating a new construct now.

>

## 9. Recursion: unbounded-depth chains break the "compose named shapes" pattern — resolved

Revisits `break_velle.md` #3's claim that *"Multi-level escalation chains (nurse → doctor → attending) compose with zero additional mechanism: each level's Escalation is just the next level's anchor."* True only under a hidden assumption: the number of levels is small and known when the spec is written, so a human is willing to hand-declare a distinct shape and rule per level (`Escalation1`, `Escalation2`, ...). Same shape of hidden assumption as #4's first-pass "the `and` combinator solves rendezvous for free" — plausible until pushed on with a case the original framing didn't consider.

**The forcing case:** a hospital's actual on-call chain is admin-configured data, not a fixed count — different departments/shifts may have a different number of tiers, and it can change without a respec:

```
shape OnCallTier {
    chain: one OnCallChain
    order: integer
    role: one Role
    nextTier: one OnCallTier?     // none if this is the top of the chain
}

shape Escalation {
    alert: one Alert
    escalatedTo: one OnCallTier
    escalatedOn: DateTime
}
```

"Has this alert been escalated all the way to the top of its chain with nobody acknowledging it" looks like it requires walking `escalatedTo.nextTier.nextTier...` an unknown number of times — unknown because `nextTier` is runtime data, not something a spec author can enumerate as `Escalation1`...`EscalationN`.

### Resolution, part 1: the escalation *process* doesn't need recursion at all

Working through the actual rule that has to fire at each timeout, generalized to any tier:

```
shape Escalation {
    alert: one Alert
    escalatedFrom: one Escalation?      // none for the first tier; the prior Escalation for every tier after
    escalatedTo: one OnCallTier
    escalatedOn: DateTime
}

shape EscalationTimeoutCheck via schedule escalatedTo.role.timeoutMinutes after Escalation {
    escalation: one Escalation
    checkedOn: DateTime
}

shape UnresolvedEscalationTimeoutCheck = EscalationTimeoutCheck
    where escalation.alert is UnacknowledgedAlert
    and escalation.escalatedTo.nextTier is some

rule EscalateToNextTier on UnresolvedEscalationTimeoutCheck produces Escalation {
    Escalation for escalation
        alert: escalation.alert
        escalatedTo: escalation.escalatedTo.nextTier
        escalatedOn: now
}
```

Plus one non-recursive rule for the first hop (`Alert` → first-tier `Escalation`, as originally written in `break_velle.md` #3). Two things resolve this with zero new mechanism:

- **`escalatedFrom: one Escalation?` is the same self-referential-lineage idiom as `LabResult.supersedes`** from `break_velle.md` #6 — not new, a reused pattern.
- **The `produces` guard scopes correctly for free.** `for escalation` guards "not exists Escalation for *this specific prior Escalation*" — correctly per-hop, since `EscalationTimeoutCheck` is one-shot per `Escalation` instance, the same "event-anchored schedule is inherently once" guarantee already established for `AlertTimeoutCheck`.

The reason this doesn't need recursion: the chain "recurses" through real wall-clock time, not through a single predicate evaluation. Each hop is a separate `produces`/schedule firing reacting to the previous one. `escalation.escalatedTo.nextTier is some` only ever looks one hop ahead to decide whether to keep going — it never needs to know how deep the chain is. This corrects the framing above, which conflated "the escalation process needs to walk an unbounded chain" with "a single predicate needs to walk an unbounded chain" — only a temporally-unfolding *process* was actually being described, and Velle already had the mechanism for that (`produces` + event-anchored `schedule` + self-reference).

### Resolution, part 2: the actual gap, stated at the right layer

Stripped of the on-call-chain dressing, the whole remaining problem is this minimal case — and it generalizes beyond pure self-reference to any circular shape dependency (`Foo → Foo` is just the one-shape instance of the same pattern `Foo → Bar → Foo` would be):

```
shape Foo {
    parent: Foo?
}
```

Every concrete example encountered in this doc is exactly this, with a different field name and a domain meaning layered on top: `LabResult.supersedes` (`break_velle.md` #6), `Escalation.escalatedFrom` (§9, part 1), `OnCallTier.nextTier` (this section).

Velle's own question stops at whether this can be *declaratively stated* — not at how it would run (`LANGUAGE.md` `## Principles`). Once stated that way, there's no gap to close: nothing in the grammar (`## Toward a formal grammar`, below) prohibits a `path` from traversing through a relationship back to its own shape, or a derived property's formula from referencing that same property one hop away. "The ultimate ancestor of this `Foo`" is just an ordinary conditional derived property that happens to reference itself through `parent`:

```
shape Foo {
    parent: Foo?
    root: Foo? = none if parent is none else (parent if parent.root is none else parent.root)
}
```

No new keyword, no new grammar rule — `and`/`is`/dot-traversal/conditional derived properties already permit exactly this. Predicates were never disallowed from being self-referential; nothing in the language needed to change to make this legal to *write*.

**What does shift, per `LANGUAGE.md` `## Principles`: correctly executing a self-referential definition becomes a compiler obligation, not a language construct.** This joins the same family already established for `produces` (safety + liveness under concurrent writers, `break_velle.md` #4) and `requires` (atomicity mechanism unspecified, human states the invariant): the human writes the declarative definition; the compiler is responsible for figuring out how to evaluate it correctly — fixed-point iteration, one-shot computation at creation, incremental materialization, whatever fits the target — without that decision ever surfacing in the spec. Termination isn't even a separate concern to bolt on: under the same capture-don't-mutate modeling discipline that makes a cycle structurally impossible (below), the chain is a finite, acyclic structure by construction, so any correct evaluation strategy necessarily terminates. The one thing worth stating as a compiler obligation explicitly (`TODO.md`'s compiled-guardrails catalog): correctly and efficiently evaluate self-referential shape/property definitions, the same way it's already obligated to realize `produces` safely and `requires` atomically.

**Cycle-freedom is a consequence of a modeling discipline, not a runtime property — this part of the earlier draft still holds.** If a human chooses to model `Foo.parent` the same way `break_velle.md` #6 modeled `LabResult.supersedes` — captured once, when the instance is created, never revised — then a cycle is a state that cannot arise from that model at all: an edge can only reference something that already existed, so no chain of `parent` references can loop back on itself. That's a structural consequence of a declared modeling choice, the same status as any other consequence of how shapes and relationships are declared — legitimately part of the spec, not a claim about execution.

This closes the fork the previous draft left open. It isn't "add a recursive construct" (nothing to add) or "treat it as an escape hatch" (nothing hand-written or AI-generated needed) — it's the third, already-familiar shape this project keeps arriving at: ordinary declarative syntax, plus a new item on the compiler's obligation list.

One small new pattern worth noting, not a problem: typing `root`/`rootFoo` as `Foo?` (or a refinement of it) rather than requiring a separate lookup follows naturally from refinements already being ordinary subtypes.

**Separate loose end surfaced by the rule in Part 1, not part of the recursion question:** `via schedule escalatedTo.role.timeoutMinutes after Escalation` needs the schedule duration itself to be data-derived, not a literal like `10 minutes`. Adds to the already-open "scheduling framework mechanism" item in `TODO.md` rather than closing it.

>

## Toward a formal grammar

Pulling every resolution above together, informally (not a real BNF, just shaped like one):

```
predicate    := disjunction
disjunction  := conjunction ("or" conjunction)*
conjunction  := negation ("and" negation)*
negation     := "not"? atom
atom         := comparison | isExpr | existsExpr | "(" predicate ")"

comparison   := expr ("==" | "!=" | "<" | "<=" | ">" | ">=") expr
isExpr       := expr "is" ("none" | "some" | "empty" | "not empty" | ShapeName)
existsExpr   := "exists" ShapeName "for" expr

expr         := path (("+" | "-") duration)?
path         := ("this" | Identifier) ("." Identifier)*
             | aggregateCall
             | "(" ShapeName "for" expr ")"   -- cardinality unresolved, see #4

aggregateCall := "count" "(" collectionExpr ")"
              | "sum" "(" collectionExpr "," Identifier ")"
collectionExpr := path ("as" Identifier)? ("where" predicate)?

duration     := IntegerLiteral ("seconds"|"minutes"|"hours"|"days"|"weeks")
```

Open items this grammar deliberately leaves as `-- unresolved` comments rather than papering over: `for`-as-expression cardinality (#4), and the deeper nested-scope binding beyond one level (#5, edge case).

Not yet propagated into `LANGUAGE.md` — only #1 (equality) and #6 (count/sum) are pure cleanups; #2, #3, #5, #7, #9 add real content to the reference (#9's is a compiler-obligations note, not new grammar); #4 and #7's sibling-join case are the remaining genuinely open design questions, not syntax fixes.

>
