# Evidence mutation policies: `stands`, `forbidden`, `compensate`

An audit of README §13's mutation-policy triple. Provenance note: these constructs were proposed during design sessions (AI-suggested), not introduced from PO discussions — this file records what problem each exists to solve, what each buys, and whether each survives the language's own precedents (the no-sugar reasoning that retired `produces`, README §18; validation-rejection-as-data, `investigate_commits.md`). No new open questions are minted here; the analysis lands in existing threads — OQ7 (re-derivation of §13, `compensate`'s desugaring), OQ17 (rejection scope), OQ18 (mid-cascade external effects), OQ20 (is commit-refusal primitive).

**Outcome, recorded up front:** both keywords dissolve, and the policy clause with them. `compensate` is retired in favor of the evidence-subject entry-rule pattern (adopted — README §13, "The compensation pattern"). `forbidden` is re-derived as `frozen`, a refinement-body write-gate ("The crack in `forbidden`" onward, below) — its exit-gate semantics fail on monotone states, and the write-gate reformulation needs no commit-rejection at all. `stands` becomes the behavior of writing nothing. The first half of this file records the case *for* the constructs as originally framed; the second half is what re-derivation did to that case.

## The shared problem: evidence outlives its premise

A rule fires because a condition held, and its effect escapes — a `ServiceSuspension` fact is produced, a `Receipt` is sent, service is actually turned off in the world. Then the instance *leaves* the refinement: the account is paid up, the invoice's balance moves. The premise that justified the evidence has been falsified, but the evidence — and whatever real-world effect it witnessed — is still there.

One conventional answer — **delete the record** — Velle forecloses on principle: no delete primitive, produced facts are permanent (README §4), so erasure is off the table. The other — **nothing happens** — is *not* inherently wrong: evidence accumulating as untouched history is the ledger pattern (README §12), and "the record needs no response" is exactly the answer `stands` names. So the triple is not patching a hole in the language. It enumerates the answers a Product Owner gives when asked what a falsified premise means for this evidence, and makes the chosen answer visible in the spec. Whether declaring one is mandatory or `stands` is the silent default is the §21 loose end (OQ7) — and the `compensate` analysis below cuts against mandatory, since a spec with no declared policy can be complete and correct as written.

**`stands` is the baseline, not machinery.** The evidence is history and stays true on its own terms — "the quote is the quote; prices drift." No reaction, nothing to check. It exists in the vocabulary so that choosing it is visible: *this PO considered the falsified-premise case and decided the record needs no response*.

## `forbidden`: immutability as a lien, not a field attribute

**The problem.** "You can't edit line items on an issued invoice" is *state-dependent* immutability: the same field is freely editable in Draft and frozen after issuance. Conventional tools express immutability at the wrong granularity — `final`/`readonly` freeze a field always — so the real rule ends up as validation code repeated in every endpoint that can touch an invoice or a line item. The failure mode is the path you forgot, especially the *indirect* one: a `VoidPayment` names only a payment, yet applying it moves the invoice's derived balance and exits `SettledInvoice`, violating the freeze from two relationships away. Per-endpoint validation does not catch that reliably.

**The reframe.** `forbidden` makes immutability a *lien held by evidence*: the invoice isn't frozen because a field says so — it's frozen because a `Receipt` exists that witnessed it settled, and while that evidence stands, any commit whose consequences would cause the exit is rejected. The lien is acquired when the evidence is produced and lifted if the evidence is compensated away.

```
rule SendReceipt when SettledInvoice {
    Receipt for invoice sentOn: now
    when leaving SettledInvoice: forbidden
}
```

The declaration lives in exactly one place, and enforcement composes with machinery the language already has: the compiler's derived trigger sets (README §11) statically know every commit — direct or cascaded — that can move any datum `SettledInvoice`'s predicate reads, so the `VoidPayment`-two-hops-away case is caught at compile time. That derivation is the construct's real payoff: the author declares the freeze once, on the evidence; the compiler derives the set of writers it gates, and a writer added next year is gated automatically.

**The tension.** `forbidden` is the *only* construct in the language that rejects a commit. Everything else treats "invalid" as data — the `ApplicableVoid`/`RefusedVoid` partition lands the act, skips the consequence, and records a refusal fact (`investigate_commits.md`, "Validation rejection is data"). `forbidden` instead unwinds a transaction, opening questions nothing else needed answered: what exactly unwinds, what the committer is told, whether rejection can be partial (OQ17), and whether commit-refusal is primitive at all or derivable from reified refusal (OQ20). The hand-written alternative — Applicable/Refused partitions on every act shape that could cause the exit — is expressible today, but requires the author to *enumerate* the exit-causing acts, which is exactly the knowledge the derived trigger sets hold and the human doesn't. The lien inverts that. This is why `forbidden` is more than sugar: dropping it doesn't reduce to spelled-out machinery, it reduces to machinery the author can't reliably spell.

*This verdict is overturned below ("The crack in `forbidden`"): the enumeration argument confused who must enumerate, and the exit-gate semantics fail on the construct's own flagship example.*

## `compensate`: sugar connecting evidence to its exit

**The setup.** A spec with no policy declared — just an ordinary entry rule whose effect escapes into the world:

```
shape Account {
    balance: Money
}

shape Delinquent = Account where balance < 0

rule SuspendService when Delinquent {
    ServiceSuspension from { account: this, suspendedOn: now }
}
```

An account goes to −\$40 on March 3rd. The commit enters `Delinquent`, the rule fires, a `ServiceSuspension` lands — and downstream of that evidence, service is actually shut off in the world (the external effect the witness exists to record, README §18). On March 10th a payment lands: the account is back to +$10, the same commit-local transition in reverse — the account *leaves* `Delinquent`.

Is this spec broken? **No — and that matters.** As written it is legitimate design twice over. If `ServiceSuspension` is historical record — the business wants suspensions recorded and quantifiable (`count(ServiceSuspension where account == this)`), with restoration outside this spec's concern — the spec is complete, and its implicit policy is `stands`. And if the business *does* want service restored, the language already expresses that with no new construct: a hand-written `RestoreService when leaving Delinquent` rule alongside this one. There is no problem with *Velle* here — no missing expressiveness, and no diagnostic the language owes, because whether restoration belongs in the model is a business decision, not an incoherence (flexible, not restrictive — README §2). What remains is much narrower: when the author does pair a counter-act with the evidence, writing that exit rule *correctly* involves correlation bookkeeping that is easy to get wrong.

**The hand-written pairing, and why it's subtle.** The author who wants restoration writes the exit rule:

```
rule RestoreService when leaving Delinquent {
    -- must only fire if a suspension actually happened for THIS membership,
    -- must not fire twice, must name WHICH suspension it compensates
    ...
}
```

Three correlation obligations hide in that body:

- **Evidence scoping** — a membership too brief for the entry rule to fire produced no `ServiceSuspension`, so its exit must compensate nothing; a naive `when leaving Delinquent` fires anyway and "restores" a service that was never cut.
- **Per-evidence guarding** — repeated episodes (delinquent in March, again in September) need each exit matched to its own episode's evidence, not re-compensating March's.
- **Singular selection** — the body must reference *the* matching suspension, which requires the at-most-one proof (README §10's `for`-query rule, discharged by a guard elsewhere — the whole-spec singularity proof).

All mechanical, all easy to get wrong.

**The re-derivation: make the evidence the subject.** But the three obligations are not intrinsic to compensation — they are artifacts of pointing the rule at the *account*, which forces the body to go find the evidence. Point the rule at the evidence instead and all three dissolve into machinery the language already has:

```
shape ServiceSuspension {
    account: one Account
    suspendedOn: DateTime
}

shape ServiceRestoration {
    suspension: one ServiceSuspension
    restoredOn: DateTime
}

shape UncompensatedSuspension = ServiceSuspension where not exists ServiceRestoration for this

shape RestorableSuspension = UncompensatedSuspension where not account is Delinquent

rule RestoreService when RestorableSuspension {
    ServiceRestoration from { suspension: this, restoredOn: now }
}
```

This is an ordinary guarded entry rule — no `when leaving` anywhere. The account's exit from `Delinquent` is observed as the suspension's *entry* into `RestorableSuspension`: the commit that raises the balance is exactly the commit at which the suspension's predicate flips true, so the firing moment is the same one `when leaving Delinquent` names (drift is commit-mediated; the compiler derives the trigger set from the writers of `balance`, README §11). Checking the obligations:

- **Evidence scoping** — dissolved by construction: the rule's subject *is* the evidence. No suspension, no instance, no firing. A membership too brief for the entry rule to observe (a tick-cadence `SuspendService`, say) compensates nothing — not because a guard says so, but because there is nothing to be the rule's subject.
- **Per-evidence guarding** — `not exists ServiceRestoration for this` is the canonical §18 guard, per suspension: March's compensated suspension can never re-fire; September's episode is a new instance with its own membership.
- **Singular selection** — dissolved: there is nothing to select. `this` is the suspension; the mapping needs no `for`-query and no at-most-one proof.

And the settled machinery composes for free: the disarm proof holds (the body produces the `ServiceRestoration` that falsifies `UncompensatedSuspension`'s predicate — "this rule provably exits its trigger state"), and because the trigger is a dischargeable *data state* rather than a transition, the durability apparatus is available on demand — `when RestorableSuspension after commit, Hourly` is legal and self-healing, which no transition-triggered rule can be (the transition law: a transition is not data — `investigate_commits.md`). The rule even reads as the PO's sentence: *an uncompensated suspension whose account is no longer delinquent gets restored.*

Three remarks fall out:

- **Compensation never needs `when leaving`.** §13 argues `leaving` is irreducible because current state can't distinguish "restored" from "never delinquent" — but compensation is precisely the case where it *can*: the uncompensated evidence is durable memory that the membership happened. Wherever there is evidence to compensate, the exit is reconstructible from current state, and an entry rule over the evidence is the natural spelling; wherever there is no evidence, there is nothing to compensate. `when leaving`'s irreducibility argument survives only for reactions that need no evidence — and those are exactly the ones `compensate` was never for.
- **The data-state spelling is strictly more correct at boundaries.** With a tick-cadence or `after commit` entry rule, a suspension can land *after* the account has already recovered — the exit transition predates the evidence, so a transition-scoped compensation can never fire for it, and the suspension is stranded uncompensated forever. The data-state spelling doesn't care when the exit happened: such a suspension is born already a member of `RestorableSuspension`, and restoration fires at its own creation commit. The sugar as §13 describes it reproduces the exit-rule semantics; the re-derivation quietly fixes them.
- **The episode pattern admits the same rewrite.** README §20's `CloseDelinquencyEpisode` is a `when leaving Delinquent` rule whose body selects `(OpenDelinquencyFlag where account == this)` under the whole-spec singularity proof. Re-derived as `when (OpenDelinquencyFlag where not account is Delinquent)`, the selection and its proof dissolve the same way. Whether that becomes the idiomatic spelling is a §20 calibration question, but it means the evidence-subject formulation is a general pattern, not a trick for this example.

**The construct, measured against the re-derivation.** The clause declares the pairing at the point the evidence is produced:

```
rule SuspendService when Delinquent {
    ServiceSuspension from { account: this, suspendedOn: now }
    when leaving Delinquent: compensate ServiceRestoration
}
```

README §13 describes its desugaring as "conceptually a dedicated `when leaving` rule that fires only for instances whose evidence exists" — that is, the sugar generates the *subtle* spelling, the one whose obligations had to be enumerated above, complete with the stranded-evidence hole at boundaries. The re-derivation shows the desugaring target that actually discharges everything is not an exit rule at all but the evidence-subject entry rule. So if `compensate` survives, its canonical desugaring should be *that* — and the clause is then pure surface: it saves two refinement declarations and relocates the sentence to the entry rule ("restoration follows suspension" reads at the suspension). What it costs is §18's precedent, plus the trait that retired `produces`: the clause names only the compensating shape (`compensate ServiceRestoration`) and leaves the correlation refinements implicit — assumed correlation, hidden where the re-derivation had to make it explicit.

**What it is, then: sugar — over a pattern that no longer needs it.** Before the re-derivation, the defensible value was a checked correlation over a subtle hand-written exit rule. After it, the pattern `compensate` abbreviates is two refinements and one ordinary guarded rule — every line settled §18 machinery with its own diagnostics already defined, and nothing left for the sugar to check that the disarm proof and guard analysis don't already cover. The remaining value is brevity and locality, which is precisely the trade "No guard sugar" already ruled on: the correlation refinements *are* the business rule (per-suspension? per-account-ever? time-windowed?), and a keyword that assumes the 1:1 case hides that decision. OQ7's two exits sharpen accordingly: survive as checked sugar whose canonical desugaring is the evidence-subject entry rule, or retire, with the README documenting the pattern the way §20 documents episodes — "accepted, not sugared." It also entangles with OQ18 — mid-cascade external failures reaching for `compensate` as a recovery policy is a heavier job than the clause was designed for.

## The crack in `forbidden`: exit-gate vs write-gate

`forbidden` as defined is an **exit-gate** — "while the evidence exists, any change that would cause *the exit* is rejected" — but the business sentence it illustrates is a **write-gate**, and the two only coincide for some refinements. Model the flagship example: the natural spelling of issued-ness is act-entered, `IssuedInvoice = Invoice where exists Issuance for this`. That predicate is *monotone* — facts persist, nothing can ever cause exit (the same observation as README §21's "exit from act-entered refinements"). So a lien on leaving `IssuedInvoice` forbids **nothing**: editing line items doesn't move the invoice out of the refinement, the exit never threatens, the lien never engages — and the business rule is violated with the lien standing right there. The construct's own flagship example fails under its own definition.

The exit-gate works only when the refinement's predicate is a *function of the data being frozen*: `SettledInvoice = Invoice where balance == 0` reads `balance`, which derives from line items, so a line-item edit would cause the exit and the lien catches it — even from two relationships away. So `forbidden` as defined protects *the truth of a predicate*; the PO's sentence freezes *data in a state*. Different constraints:

- **Exit-gate** (as defined): protects a derived condition from being falsified. Meaningful only on non-monotone, data-driven refinements. Value-dependent — whether a given write falsifies the predicate depends on the values involved — so enforcement is inherently a runtime evaluation.
- **Write-gate** (as meant): while in state X, writes to named fields are rejected. Indifferent to monotonicity — which matters, because act-entered states like `Issued` are exactly the states businesses freeze things in.

And the write-gate cannot be recovered from existing machinery: "field changed while a member" is a transition-shaped fact, not current state — so it can't be a refinement, can't be a `never`-invariant over observable state (OQ19), and the transition law says it exists only at the commit that caused it. It is a genuinely new kind of statement — a *conditional write permission* — the same category-of-one that one-writer occupies: one-writer says who may write; this says when writing is legal at all.

## `frozen`: state-scoped immutability in refinement-body position

**The spelling.** A `frozen` clause in the refinement body — the body already holds "what membership adds" (derived and captured properties, README §8); this adds a constraint on base-shape stored fields rather than a property:

```
shape IssuedInvoice = Invoice where exists Issuance for this {
    frozen lineItems, billingAddress
}
```

Reads as the PO's sentence: *an issued invoice's line items are frozen.* A bare `frozen` (no list) means every stored field of the base shape — "you can't edit an issued invoice, period" — and auto-extends to fields added later, the same virtue as derived trigger sets (README §11). The listed form narrows deliberately. Only stored fields are eligible: derived properties were never assignable, and captured properties are fixed by definition.

**The semantics: pre-state membership gates the write.** A write to a frozen field is illegal at any commit where the instance is a member in the commit's **pre-state**. That one choice settles the edges: the *entering* commit may still write (member only in post-state — a rule reacting to `Issuance` can normalize a field in the same commit that freezes it), and once in, nothing may write until membership ends. Non-monotone predicates give thawing for free — leave the state, the freeze lifts.

**The check is static — the part that makes it Velle.** Every write to stored state is a rule assignment, and assignment targets are literal static paths (README §12, the hard requirement). So the compiler already knows every writer of every field, and the check is the one-writer question re-aimed: *can this writer's trigger coincide with membership in the freezing refinement?* Same disjointness analysis, same fail-closed stance, same connected whole-spec diagnostic naming both sides: "`ApplyLineItemEdit` writes `lineItems`, which `IssuedInvoice` freezes, and its trigger is not provably disjoint from membership." The fix is the already-settled rejection-as-data idiom, and the diagnostic can spell it: partition the act — `ApplicableEdit = LineItemEdit where not invoice is IssuedInvoice` / `RefusedEdit = ...` — so the edit rule hangs off `ApplicableEdit` (now provably disjoint) and the refusal lands as a fact the caller reads back (`investigate_commits.md`, "Validation rejection is data").

Note the OQ20 consequence: for the immutability use case, **no commit-refusal is needed at all**. The evidence-lien contemplated unwinding transactions; the write-gate needs only static proofs plus mandated act partitions — everything lands as data. The dead-machinery diagnostics come along too: a freeze on a field no rule ever writes is advisory noise ("serves no writer" — the dead-tolerance shape, README §19), and under a future `states of` partition, a field frozen in every state is "never writable — did you mean it isn't assignable at all?"

**Freeze depth is declared, not inferred.** "Can't edit line items" really means the `LineItem` instances too. Rather than a magic transitive deep-freeze, the boundary stays a visible per-shape decision — a conditioned refinement on the related shape:

```
shape LockedLineItem = LineItem where invoice is IssuedInvoice {
    frozen price, quantity
}
```

Each hop of the freeze is its own business call (issuing freezes the line item's price; it doesn't freeze the product's name) — the "No guard sugar" ethos applied to reach.

## `forbidden` dissolves into `frozen`

The evidence-lien is just a freeze on an evidence-entered refinement — the evidence linkage lives *in the predicate*:

```
shape ReceiptedInvoice = Invoice where exists Receipt for this {
    frozen lineItems
}
```

And "the lien is lifted if the evidence is compensated" needs no lien vocabulary either — it's a non-monotone predicate:

```
shape ReceiptedInvoice = Invoice where
    exists Receipt for this and not exists ReceiptVoid for this {
    frozen lineItems
}
```

Void the receipt, the freeze thaws. Provenance survives — `why` reads the predicate: "frozen because a receipt stands, unvoided." So `when leaving X: forbidden` retires on the same argument that retired `compensate`: the keyword's content decomposes into refinement machinery, and what the keyword *hid* (which fields, how deep, what lifts the freeze) becomes visible predicate-and-list content.

The earlier irreducibility verdict fails on both of its legs. The enumeration argument — "the author can't reliably spell the exit-causing writers" — confused who must enumerate: writers are statically known either way, so under `frozen` the compiler enumerates them exactly as it would have under `forbidden`; the author declares once on the refinement and fixes each flagged writer under guidance. And the commit-rejection machinery `forbidden` required — the unwind, OQ17's what-is-the-committer-told, OQ20's is-refusal-primitive — turns out to be unnecessary for the use case entirely.

The exit-gate residue — "reject any change that would falsify predicate P" as opposed to "reject writes to these fields" — is value-dependent, needs runtime evaluation, and no PO sentence demands it: POs freeze *things*, not truth values. The compiler knows P's read-set anyway (impact analysis read backward), so where an author reaches for predicate-protection, the diagnostic can propose the field list.

**Decision: `forbidden` retires; `frozen` is the spelling.** Its natural long-term home is the `states of` partition (README §21) — "editable in Draft, frozen in Issued" as one declaration per state alongside legal transitions — and the refinement-body spelling migrates in without conflict when partitions get designed, since a partition is a set of refinements.

## Where that leaves them

Both keywords dissolve, and the policy-clause construct dissolves with them — §13's "mutation policy on evidence" clause has nothing left to declare.

**`forbidden`** — the audit's first pass called it irreducible; the re-derivation overturned that on both legs (exit-gate semantics that fail on monotone states, and commit-rejection machinery the write-gate never needed). **Decision: retired in favor of `frozen`** — state-scoped immutability declared in refinement-body position, checked statically by the one-writer disjointness machinery, with act-shaped writers routed to rejection-as-data partitions. What was genuinely irreducible was the *capability* — conditional write permission, a category-of-one like one-writer — and `frozen` expresses it with no new runtime behavior.

**`compensate`** solves no problem with the language: the unpaired spec is legitimate design (a ledger, or `stands` in effect), and the paired design is fully reproducible by hand — better than reproducible, since the evidence-subject entry rule (`RestoreService when RestorableSuspension`) discharges every obligation with settled machinery *and* fixes the stranded-evidence hole the exit-rule semantics carry at boundaries. What the keyword abbreviated was two refinements and one guarded rule — saving two declarations at the price of hiding the correlation decision, the shape of question the `produces` precedent answered once already.

**Decision: retired.** The README documents the evidence-subject entry-rule pattern in §13 ("The compensation pattern"), the way §20 documents episodes — accepted, not sugared. Nothing needed salvaging: the pattern is ordinary machinery, and its diagnostics (guard, disarm, one-writer) were already defined.

**`stands`** — with `compensate` retired and `forbidden` moved into refinement bodies, there is no policy clause left for `stands` to attach to. It was always the behavior of silence, and the `compensate` analysis already established that silence is legitimate (the bare ledger is complete and correct as written). The keyword names nothing and goes with the clause; the §21 undeclared-policy question ("default `stands`, or compile error?") dissolves rather than resolves — there is no declaration to omit.

## What remains open (no new numbers; open threads live in `investigate_commits.md`)

- **OQ7** — the rest of §13's re-derivation under commit-local transitions (what an exit rule may read), with `when leaving`'s necessary territory narrowed by this file to evidence-free reactions.
- **OQ18** — whether mid-cascade external-effect failure may reuse the compensation pattern, or needs its own policy.
- **OQ20** — whether any business case requires that a well-shaped act *not enter the state* (the compliance/data-retention "we may not store this request" cases).
- **`frozen` × `states of`** (a home, not a new OQ) — the interaction with the partition declaration when that gets designed (README §21 — per-state write permissions beside legal transitions, and the field-frozen-in-every-state diagnostic).
