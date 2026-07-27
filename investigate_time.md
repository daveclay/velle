# Time, Mutation & Refinement Properties — consolidated findings

*(Rewritten 7/27: this file records current findings only. The debate history — the original `expr on Refinement` proposal, its retracted `produces`-desugar, the dead once-ness legality rule, the capture-location question — is pruned; consult git history for the derivations.)*

## The problem this file answers

`currentTotal: Money = sum(lineItems, amount)` is a live calculation and should stay one. What was missing was a way to say the same calculation *frozen at a moment* (`totalWhenIssued`) without importing runtime storage concepts. Pulling that thread forced answers about mutation, re-entry, exit, and where state ends and effects begin.

## Capture: the primitive

**Settled semantics.** A captured value is *present iff the instance is currently a member of the refinement; its value is the expression as evaluated at the moment the current membership began.* Absent before entry, fixed during membership, retracted on exit, re-captured on re-entry. It is a function of (current membership, when this membership began) — fully coherent under mutation, and transient-safe: a membership that comes and goes leaves no anomaly. For a monotone refinement this degenerates to "evaluated once, fixed forever."

**A genuinely new primitive, not sugar.** Capture cannot desugar to a hidden `rule ... on R produces` evidence shape: produced facts are effect-layer — durable, and deleting one makes the description lie about the world — while a capture *must* retract on exit. You can't build a thing that must be deleted out of a thing that must never be. Capture is the first construct in Velle whose stored state tracks membership — strictly more than a predicate, strictly less than history.

**Capture anchors the clock.** Capturing `now`/`today` yields the entry moment. Occurrence timestamps fall out with no implicit system timestamp — the same stance already taken for `latest`/`first`.

**Capture freezes transitively — and that's a distinct concept from ledger reconstruction.** A capture closes over its entire live dependency graph at the entry instant (through `carrier.currentSurcharge`, through `latest(...)` on other shapes' collections — everything). The sharp edge that proves the concept is real: if someone later *backdates* a fact (a fuel surcharge with `effectiveOn` before the quote date), the captured value and the ledger reconstruction (`latest(surcharges where effectiveOn <= quotedOn by effectiveOn)`) now disagree — and both are right, because they answer different Product Owner questions: *what we told the customer then* (capture) vs. *what we now believe was true then* (ledger). Both forms must exist; neither is a redundant spelling of the other.

## Capture's home: refinement properties (resolved 7/27)

Capture is spelled as a **property on the refinement**, not as an `on R`-anchored property on the base shape. The earlier base-shape spelling (`totalWhenIssued: Money = sum(lineItems, amount) on Issued` on `Invoice`) is retired. Now in README §7:

```
shape ArchivedInvoice = Invoice where exists ArchiveRequest for this {
    captured archivedBy: one User = (ArchiveRequest for this).requestedBy
    captured archivedOn: Date = today
}
```

What the positional form settles:

- **Presence typing is ordinary field typing.** `ArchivedInvoice` simply *has* `archivedBy`; `Invoice` simply doesn't. The old narrowing obligation ("capture-on-R implies present-when-R, even though R's predicate never mentions it") stops being a special compiler favor and becomes what fields already mean. `is ArchivedInvoice` narrows exactly the way `is some` does, licensing access.
- **The `captured` marker is required.** In body position a bare `= expr` is a live derivation — refinement properties come in the same two kinds as base-shape properties (derived and captured), and the two must read differently. (`captured` was held in reserve as a variant during the original design; the body position is what finally forces it.)
- **Both old anchor guardrails dissolve syntactically.** "The anchor must name a refinement of the containing shape" — there is no anchor to misname; the property is *on* the refinement. "The anchor must not be a schedule" — a schedule isn't a refinement and has no body to put a property in. Neither rule needs stating anymore.
- **Scattering is correct, not a cost.** The earlier objection to the body form — "a shape's full data scatters across declarations" — inverted on inspection: `archivedBy` was never `Invoice` data. The base shape's declaration stays a clean statement of what every instance has; each refinement's body states what membership adds. The pollution was the alternative: optional fields on the base, secretly correlated with a state.

**There is no third property kind.** "Supplied" data (`archivedBy: one User` — a value no expression over the invoice's own fields could produce) reduces to *capture from a reified act*. Velle has no ambient execution context — no "current user", no request-scoped magic — so a capture can only reach data through the data graph, which forces the act carrying the data to exist as a shape (`ArchiveRequest`) before the refinement can capture from it. Two independent pressures converge on the same requirement — captures need a data source, and re-entry needs occurrence identity (below) — which is the design telling us reified acts are right, not a workaround.

**Entry-evaluability guardrail (new).** A captured property's expression must be provably evaluable at the moment membership begins: every reference in it must be guaranteed by the refinement's own predicate, or be unconditionally present on the base shape. `(ArchiveRequest for this)` is legal above precisely because the predicate asserts `exists ArchiveRequest for this` — the predicate narrows the capture expression, the same machinery by which `is some` licenses `.`. A capture reading something its predicate doesn't guarantee is a compile error. Same family as the retired anchor guardrails, but this one survives because it's about *evaluability*, not position.

**Drift-entered vs. act-entered is derived, not declared.** A refinement whose captures need nothing beyond the base shape's own data (`captured balanceWhenOverdue: Money = balance` on `OverdueInvoice`) can be entered by drift; one whose predicate requires an act-fact can only be entered by that act occurring. The compiler classifies each refinement from its predicate — compiling-as-validation, not a new declaration.

**Cross-refinement reads live on intersections.** A property reading captures of two different refinements (`priceDrift = billedTotal - quotedTotal`) belongs on `Quoted and Delivered`, where both operands' properties are in scope and provably present — not on the base shape, where both reads would be secretly optional.

**Terminology, settled in passing:** these stay *refinements*, not "derived shapes." "Derived" already means recomputed-from-current-data (§6), and captured properties are precisely *not* that; "refinement" names the subset-of-base relationship all the machinery (`is`, narrowing, `and`/`or`, exhaustiveness) hangs on; and "derived shape" should stay reserved for Mapping when it arrives — a *translation into a new instance* rather than a *narrower view of the same instance*.

## The state/effect stratification

The load-bearing structure of the whole investigation. Everything downstream of a refinement divides into layers with opposite lifecycle disciplines:

- **Derived state** (`= expr`, refinement memberships): never retracted, *recomputed* — being a function of current state is the definition of the layer.
- **Captured state** (`captured ... = expr`): retracts on exit, re-captures on re-entry — memory of the current membership only.
- **Effects** (produced shapes and the external actions they stand for): history. Not a function of current state and not recomputable. Deleting evidence makes the description lie (the email *was* sent); the only coherent correction is a compensating fact — accounting's answer, arrived at as logical necessity.

| | `captured` property (state) | `rule ... produces E` (effect) |
|---|---|---|
| layer | state | effect |
| on exit | retracts | persists — deletion would lie about the world |
| on re-entry | re-captures freely | doesn't re-fire under a lifetime guard; re-fires only if evidence is scoped to a reified occurrence |
| when wrong | recompute / retract | compensate |

**Effects should witness captures, not live derivations.** Because a capture is frozen for the duration of the membership, an effect that reads it (`NotifyCustomer` reading `totalWhenIssued`) has inputs that cannot drift *while the premise holds* — editing a line item on a still-issued invoice doesn't break provenance. The only event that can falsify an effect's inputs is membership exit — a single nameable moment, which is exactly where `on leaving R` sits. This collapses the mutation-policy problem from "any write to any transitive input" to "exit from the named premise" — a question a PO can actually be asked.

**The boundary is one-way in each direction.** `on R` fires the effect and copies captures forward into evidence; `on leaving R` fires the policy and reads evidence back. State crosses the boundary only at entry; only evidence crosses back at exit. That is what keeps the description coherent under mutation.

## Mutation and policy (promoted to README §12)

**Velle never demanded immutability.** Refinements quantify over *now*; §17's `invoice with payments += payment` is an in-place update. Immutability is a requirement of the specific constructs that need **memory** — `produces`-as-guard, capture, `latest` — not a language principle.

**The three policies.** When mutation would falsify the premise of an already-fired effect, the resolution is a declared policy per *(property × witnessed effect)*, attached to the producing rule via an `on leaving` clause — and POs already say all three in the wild:

1. **`stands`** — "the quote is the quote; prices drift." The effect is history; divergence is expected and meaningful.
2. **`forbidden`** — "you can't edit line items on an issued invoice." The mutation is rejected while the evidence exists.
3. **`compensate X`** — "invoices are never edited — voided and reissued." The exit produces a compensating fact, scoped to the evidence it corrects.

**Immutability is a lien held by effects, not a property of data.** Nothing freezes `lineItems`; what freezes it is that `IssuedNotification` witnessed a value derived from it and the PO chose `forbidden`. The lien is acquired when evidence is produced and lifts if it's compensated away. Asked "is this field immutable?", a PO can't answer; asked "who has acted on this field's value, and do we owe them anything if it changes?", they can. Monotonicity is *derived*, not declared: a refinement is monotone exactly when every mutation that could cause exit is forbidden.

**`on leaving R` is a genuinely new trigger.** Entering the complement can't express it — a never-issued invoice also "enters" `NotIssued`; *became* and *always was* are indistinguishable from current data. Only a member can leave, so the trigger is inherently transitional. **Exit rules read evidence only**: at the moment of exit, R's captured properties have already retracted (that's capture's semantics doing its job, not a bug to work around with a destructor-style "last look"). Evidence is the only survivor of the exit, so evidence — which copied the captures at witness time — is what exit rules and compensations read. Compile guardrail: a rule `on leaving R` must not read properties of R; the compiler should reject the read and point at the evidence instead.

## Mutation relocates the ledger — it doesn't eliminate it

If any rule, guard, capture, or `latest` depends on history and the store mutates in place, a correct compilation *must synthesize* the history the spec didn't describe: entry/exit logs, occurrence identities, write-path transition detection (sweeps are wrong for non-monotone refinements), snapshot-consistent evaluation, atomic check-then-write for guards. This is the industry's actual trajectory — mutable rows grow audit tables, triggers, and CDC streams because history-dependent behavior forces the event log back into existence, unnamed.

So the design axis is not *immutability: yes/no* but: **is history part of the description, or part of the implementation?** Relocated into the implementation, the ledger exists but the spec can't name it — `why` can't cite it, POs can't query it, retention is decided by nobody. Velle's coherent position: **mutation is fine wherever no construct remembers; wherever history is load-bearing, it must be reified or the refinement provably monotone.**

**The reissue example, compressed.** `rule NotifyCustomer on Issued produces IssuedNotification` with a mutable `issued: Date?`: un-issue, edit, re-issue, and the lifetime guard either suppresses the second notification (customer acts on a stale $500 email for a $650 invoice) or the evidence was deleted to keep the description true (the system denies an email the customer holds). Both wrong — because the guard is scoped to the invoice's *lifetime* while the business meaning is per-*issuance*, and the issuance isn't a thing in the spec, just an overwritten date field. Reify the occurrence (`Issuance` facts, guards scoped `for issuance`) and every problem dissolves without deleting anything. Corollary: when a deletion has downstream meaning, model it as a fact (`InvoiceRetraction`) with ordinary describable reactions, not as a mutation whose cascade lives in hidden machinery.

## Open questions

*(Struck as resolved: correction policy → the stands/forbidden/compensate trio; exit events → `on leaving R`, README §12; capture's location → refinement properties, README §7; capture once-ness and re-entry → capture's settled semantics.)*

### Policy machinery

**The default question.** When a producing rule declares no `on leaving` clause, does silence mean *stands* (semantically honest — that's what evidence does anyway — but the PO never confirmed it) or a compile hole (methodologically honest per compiling-as-validation, but noisy: every producing rule on any leavable refinement demands a clause)? Note the strict reading's cost depends on how much of the spec has already declared `forbidden` — provable monotonicity removes the obligation.

**What `forbidden` actually rejects.** *(Largely resolved 7/27 in `investigate_state.md`: what is refused is a commit; the act is the reified "tries," refusal is a refinement of the act. The derive-the-refusal-predicate sub-question below remains.)* "The mutation is rejected" contains a *tries*, a *user*, and a *rejected* — none of which exist in the language, which otherwise describes only what *is*. The plausible landing reifies the attempt (an `UnissueRequest` input shape with `RefusedUnissue`/`AppliedUnissue` refinements, the errors-are-refinements pattern) — but hand-written, the refusal predicate restates the lien and the two can drift. Unworked: whether the compiler *derives* the refused refinement from the `forbidden` clauses the request would break, and what an applied request's output clause looks like ("set `issued` to none" has no spelling; §17 only has `+=`).

### Occurrences and time

**Occurrence reification.** The `Issuance` fix presupposes `Issuance` facts, but nothing creates them: a recorder rule's own `produces` guard is lifetime-scoped, so it can't record re-occurrences — the recorder has the disease it exists to cure. Breaking the circularity needs something natively once-per-entry, e.g. `occurrence Issuance of Issued { issuedOn: issued, total: sum(lineItems, amount) }` — no syntax settled. This also carries the still-unfixed README §10/§11 contradiction ("once per newly-satisfying" prose vs. the lifetime guard): it resolves whichever way this lands.

**Exit from act-entered refinements** *(new 7/27)*. `exists ArchiveRequest for this` is monotone — facts persist, so nothing can ever leave `ArchivedInvoice` and un-archiving is inexpressible. Exit requires either pairing occurrences in the predicate (`... and not exists Unarchival` newer than the matched request — needing occurrence ordering/scoping vocabulary) or a mutable field plus a declared policy. Both expressible; which is idiomatic is unsettled, and it's really the occurrence question again: is a state transition a mutation with policy, or an append-only chain of occurrence facts whose latest entry determines current state?

**Entry-side transients.** *(Reframed 7/27 in `investigate_state.md`: memberships are functions of committed states, so a membership exists only if some commit exhibited it — the mechanism choice becomes a declaration of commit/observation points, and only the spelling remains open.)* An invoice crosses `due` at midnight and is paid at 6am: an event-stream compilation fires the late fee, a 7am sweep fires nothing — both satisfy §10's contract as written, so a business outcome is decided by an engineer's mechanism choice. The missing spelling is the PO's intent: `on Overdue` vs. `on Overdue however briefly` vs. `on Overdue observed Daily` — none proposed seriously yet. (The exit side needs no such spelling: a transient too brief to produce evidence has nothing to compensate.)

### Compilation contracts

**Atomicity granularity.** *(Subsumed 7/27 by `investigate_state.md`'s commit axiom: the commit is the definition of the observable step, and `never`-style declarations become observability allowances/prohibitions; only the spelling remains.)* Capture-implies-presence makes `shape Impossible = Invoice where issued is none and totalWhenIssued exists` provably memberless, yet a non-atomic runtime can still *show* one between the write and the retraction. Missing: which provably-empty refinements must be *observably* empty at every instant — candidate spelling `never Impossible`, which states transaction boundaries without ever saying the word transaction.

**Retention of synthesized history.** Guards and compensations read evidence forever; retention obligations (audits, GDPR) delete it. Erase the evidence and "exactly once per issuance" silently goes false; keep it and the system violates a business rule the spec can't state. Candidate: retention as a first-class declaration on evidence shapes (`retained 7 years`), propagated by the compiler to whatever hidden history (entry/exit logs) serves the same constructs.

### Syntax loose ends

**The desugared `compensate` form.** The general exit rule needs a `where` guard on a rule and a `that` binding naming the matched evidence (`original: that IssuedNotification`) — neither in the grammar — and after two issuances, *which* evidence `that` means is resolvable only if evidence is occurrence-scoped (ties to occurrence reification).

**State partition declaration** *(new 7/27 — the next investigation)*. Refinement properties give states their data; reified acts give transitions their payloads; mutation policies bound which transitions are legal. What's missing is asserting that a set of refinements *partitions* a shape — mutually exclusive, jointly exhaustive ("an invoice is always in exactly one of Draft, Issued, Paid, Voided"). Candidate spelling `states of Invoice = Draft | Issued | Paid | Voided`, invoking the exhaustiveness/overlap check §7 already names as a compiler goal — an assertion to verify, not a new semantic mechanism.

## Status

Settled: capture's semantics (present iff member, value as of current membership's start) and its home as `captured` properties on refinement bodies (README §7), with the entry-evaluability guardrail, `is R` narrowing as ordinary field typing, no-ambient-context / reified acts, and drift-vs-act classification derived from predicates. Settled earlier and still standing: transitive freezing and the capture-vs-ledger distinction; the state/effect stratification and its table; the mutation-policy trio with immutability as a lien (README §12); `on leaving R` with evidence-only reads (README §12); effects-witness-captures.

Still open, by weight: occurrence reification (load-bearing — carries the §10/§11 "once" contradiction, the `that` ambiguity, and act-entered exit) and what `forbidden` rejects (may force a new kind of statement into the language). Then the contracts to add rather than designs to find: the policy default, entry transients, atomicity granularity, retention. Next investigation: the state partition declaration.
