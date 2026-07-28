# State, Time & Mutation — consolidated findings

*(Consolidated 7/27: `investigate_time.md` is merged into this document — state, time, and mutation turned out to be one discussion. Findings only; superseded debates are pruned. Git history has the derivations.)*

## The problem, in one sentence

**How does state change impact the truth of the system design's shapes and rules?** A Velle description quantifies over *now* — but capture, `produces` guards, `latest`, and rule firing all depend on *moments and memory*, and mutation is what exposes the difference. Pulling that thread forced: two axioms, a state/effect stratification, one new primitive (capture, now living as refinement properties), a mutation-policy vocabulary, a taxonomy of the questions mutation obliges the compiler to raise — and the truth ladder (below), which is the consolidated answer to the question.

## Vocabulary

Terms this document leans on throughout:

- **Act** — a shape instance that arrives from *outside* the system: a user's request, a payment from a bank's feed, the scheduler's tick. An act is ordinary data with a declared shape — `ArchiveRequest { invoice: one Invoice, requestedBy: one User }` is an act shape, and one user archiving one invoice is one instance of it. "Act" names a *role*, not a syntax category: any shape whose instances originate outside the system is an act. Under inertness (below), acts are the only source of state change.
- **Commit** — the atomic application of one act to the state: the act instance is added to the data, and everything that follows from its presence — derived values, memberships, captures, rule reactions — takes effect as a single observable step.
- **Act-entered refinement** — a refinement an instance can only enter because a specific act was committed, because its predicate tests for the existence of an act instance. `shape ArchivedInvoice = Invoice where exists ArchiveRequest for this`: the only way any invoice becomes `ArchivedInvoice` is that someone committed an `ArchiveRequest` referencing it.
- **Drift-entered refinement** — a refinement an instance can enter as a *side consequence* of a commit that never mentioned it. `shape OverdueInvoice = Invoice where balance > 0 and due < today` becomes true for an invoice when a scheduler act's commit observes that time has passed, or when some other act changed the balance. No act "makes" an invoice overdue.
- Whether a refinement is act-entered or drift-entered is **derived by the compiler from its predicate**, never declared by a human.

## Two axioms

**Inertness (the fundamental one).** *The system never does anything itself.* Every state change originates in an external act — typically a user's, but agnostic to the actor. The scheduler is not a mechanism inside the system; it is just another external actor, and a tick is just another act arriving from it (README §16 already said this locally — "a scheduled tick is conceptually a shape instance like any other" — inertness promotes that sentence to a principle). Between acts, nothing happens. The system is a pure reactor: its entire behavior is `state' = commit(state, act)`, and its history is a fold of external acts over an initial state.

**One state.** The whole system — treated as a black box, databases included — is a single state. Its history is a sequence of commits, each atomic and totally ordered as described. The database is not *the* state tree; it is inside the box, one materialization among possible ones. All threading, locking, and synchronization machinery is compilation machinery discharging this axiom — and every deliberate weakening of it must appear in the spec as a declared observability allowance, never as a silent engineering choice.

The one-state axiom is not naive, and the strongest argument comes from the machinery itself: **serializability** — the gold-standard correctness criterion for transaction processing — is *defined* as "equivalent to some serial execution against a single consistent state." Locks, MVCC, WAL, isolation levels, optimistic concurrency, idempotency keys: their entire correctness story is how well they impersonate the simple model. The industry already agreed the simple model is the spec; it just never let anyone write specs in it. Velle adopts as its description level the exact model the proofs were already written against.

Consequences of inertness:

- **Provenance is well-founded.** Every piece of state has a finite `why` chain terminating at an external act. Nothing is self-caused.
- **Background behavior is unsayable.** Retries, expirations, nightly jobs — anything that looks like the system acting spontaneously — must trace to an external act (usually the scheduler's) or it does not exist in the description.

**No second state tree.** The classic concurrency bugs — read-modify-write races, lost updates, stale checks — happen when application code *forks the tree*: reads state into local variables (the heap: a second, private, un-synchronized state tree), decides against the copy, writes back after the tree has moved. Velle structurally cannot express the fork: refinements are predicates over current data, captures are anchored to commits, an act's consequences are stated over the tree itself, and there is no ambient execution context to smuggle a copy through. The races engineers spend careers on are unrepresentable at the description level — which is what makes them a **compilation obligation** rather than a shared burden. (React is the useful comparison here — not a template: its real contribution was killing the second state tree, recomputing the derived copy from the single source. The backend heap-and-cache layer is the DOM of server systems, and Velle's derived layer is already the cure written down. Where React and Velle disagree — fire-and-forget effects vs. guarded, evidence-producing ones — Velle's existing design wins.)

## Commits

**All change enters through acts — there is no raw mutation.** Every earlier problem case began with an off-stage write ("a user deletes `issued`"). Under the commit model that sentence is unsayable: there is only an `UnissueRequest` act, committed or refused. "Model the deletion as a fact" graduates from advice to structure. This also gives `forbidden` its subject: what is refused is a **commit** — the act *is* the attempt, recorded as data, refusal is a refinement of the act shape (errors-are-refinements), and the lien is checked at one nameable moment instead of hovering over every field write.

**There is only one kind of commit point: an external act arriving.** The language's two trigger positions distinguish what a *rule reacts to* — a membership change (prefix `on`) vs. an act from the scheduler, referenced by name (postfix `on`, e.g. `on Daily`) — not two kinds of commit. No commit has an empty delta: the act itself is data entering the tree, and its arrival *is* the change. That arrival is also what forces observation — the semantic justification for README §16's stance that purely time-dependent refinements are only re-checked on schedule. `today` changing at midnight is not an event; a commit observing it is, and the only way a commit happens at 7am is that the scheduler's act arrived at 7am.

**This inverts the entry-transient question from mechanism to declaration.** If memberships are functions of *committed* states, a membership "exists" only if some commit exhibited it. The midnight/6am late-fee ambiguity dissolves: it was never an engineer's event-stream-vs-sweep choice; it is a spec-level question of *which acts arrive* — and "we observe overdue-ness daily" is a sentence a Product Owner already says, now meaning "the scheduler sends a `Daily` act." Only the spelling remains open.

**State change = membership delta (the CRUD-agnostic meaning).** Insert, update, delete are verbs about a *store*. A Velle state change is a delta of *truth*: between commit N and N+1, the memberships that begin, the memberships that end, and the captures that fire. That delta is the meaning of an act, and it is computable (the blast-radius analysis, below). Whether a delta compiles to an INSERT, an UPDATE, an event-log append, or a tombstone is materialization — the compile phase's business (README §1). "Delete" at the description level is a transition where something stops holding, which is why it keeps turning into compensating facts. Act-entered refinements (see Vocabulary) exhibit the purest form: `ArchivedInvoice`'s predicate is `exists ArchiveRequest for this`, so committing an `ArchiveRequest` *is* the membership change for the invoice it references — zero field writes anywhere.

**Three boundaries of the one-state axiom:**

1. **The tree ends at the effect boundary.** Sent emails and external charges aren't in the tree and can't roll back with it. Evidence shapes are the tree's record of the world outside it; the exactly-once contract on `produces` across that boundary is the compiler's problem (outbox patterns and the like), invisible to the description.
2. **Distribution is materialization.** The spec describes one tree; one Postgres vs. a saga-coordinated fleet is a runtime decision — with relaxations of commit atomicity legal only where the spec permits them.
3. **Isolation is derived, not picked.** Engineers choose isolation levels by folklore, silently deciding business questions. Under the commit model the PO declares which intermediate states may never be observable (`never Impossible`-style declarations — the commit *is* the definition of the observable step), and the compiler buys exactly as much consistency as the declared constraints require.

## Capture: state-layer memory

**Settled semantics.** A captured value is *present iff the instance is currently a member of the refinement; its value is the expression as evaluated at the moment the current membership began.* Absent before entry, fixed during membership, retracted on exit, re-captured on re-entry. Transient-safe; for a monotone refinement it degenerates to "evaluated once, fixed forever."

**A genuinely new primitive, not sugar.** Capture cannot desugar to a hidden `rule ... produces` evidence shape: produced facts are effect-layer (durable; deletion lies about the world), while a capture *must* retract on exit. You can't build a thing that must be deleted out of a thing that must never be. Capture is the first construct whose stored state tracks membership — strictly more than a predicate, strictly less than history.

**Capture anchors the clock.** Capturing `now`/`today` yields the entry moment — occurrence timestamps with no implicit system timestamp.

**Capture freezes transitively — and is a distinct concept from ledger reconstruction.** A capture closes over its entire live dependency graph at the entry instant. The proof both concepts are real: backdate a fact (a fuel surcharge with `effectiveOn` before the quote date) and the captured value and the ledger reconstruction (`latest(surcharges where effectiveOn <= quotedOn by effectiveOn)`) disagree — correctly, because they answer different Product Owner questions: *what we told the customer then* (capture) vs. *what we now believe was true then* (ledger). Neither is a redundant spelling of the other.

**Capture's home: refinement properties** (spec now in README §7). The essentials: properties on refinements come in exactly two kinds — derived (live) and `captured` (the marker is required; bare `= expr` in body position is a live derivation) — with **no third "assigned" kind**: data that looks "supplied" from outside (`archivedBy: one User`) is really a capture *from an act*. There is no ambient context, so the `User` must be reachable through the data — which forces the archiving action itself to exist as a shape instance (`ArchiveRequest`, the act) that the capture expression reads. Presence typing is ordinary field typing (`is R` narrows like `is some`). The **entry-evaluability guardrail**: a capture's expression must be provably evaluable at entry — every reference guaranteed by the refinement's own predicate or unconditionally present on the base. **Drift-entered vs. act-entered is derived by the compiler from the predicate**, never declared. Cross-refinement reads live on intersections (`Quoted and Delivered { priceDrift ... }`). Terminology settled in passing: these stay *refinements*, not "derived shapes" — "derived" already means recomputed-from-current-data, which captured properties are precisely not; "derived shape" stays reserved for Mapping (translation into a new instance) vs. refinement (narrower view of the same instance).

## The state/effect stratification

The load-bearing structure. Everything downstream of a refinement divides into layers with opposite lifecycle disciplines:

- **Derived state** (`= expr`, memberships): never retracted — *recomputed*. Being a function of current state is the definition of the layer.
- **Captured state**: retracts on exit, re-captures on re-entry — memory of the current membership only.
- **Effects** (produced shapes and the external actions they stand for): history. Not recomputable; deleting evidence makes the description lie; the only coherent correction is a compensating fact.

| | `captured` property (state) | `rule ... produces E` (effect) |
|---|---|---|
| on exit | retracts | persists — deletion would lie about the world |
| on re-entry | re-captures freely | doesn't re-fire under a lifetime guard; re-fires only if evidence is scoped to a reified occurrence |
| when wrong | recompute / retract | compensate |

**Effects should witness captures, not live derivations.** A capture is frozen for the membership's duration, so an effect that reads it has inputs that cannot drift *while the premise holds*. The only event that can falsify an effect's inputs is membership exit — a single nameable moment, where `on leaving R` sits. This collapses mutation policy from "any write to any transitive input" to "exit from the named premise" — a question a PO can actually be asked.

**The boundary is one-way in each direction.** `on R` fires the effect and copies captures forward into evidence; `on leaving R` fires the policy and reads evidence back. State crosses only at entry; only evidence crosses back at exit.

## Mutation policy (promoted to README §12)

**Velle never demanded immutability** — it's a requirement of the constructs that need *memory* (guards, capture, `latest`), not a language principle. When mutation would falsify the premise of an already-fired effect, the resolution is a declared policy per *(property × witnessed effect)*, attached to the producing rule via an `on leaving` clause — and POs already say all three in the wild:

1. **`stands`** — "the quote is the quote; prices drift." The effect is history; divergence is expected and meaningful.
2. **`forbidden`** — "you can't edit line items on an issued invoice." The commit that would cause the exit is refused while the evidence exists.
3. **`compensate X`** — "invoices are never edited — voided and reissued." The exit produces a compensating fact, scoped to the evidence it corrects.

**Immutability is a lien held by effects, not a property of data.** Nothing freezes `lineItems`; what freezes it is that `IssuedNotification` witnessed a value derived from it and the PO chose `forbidden`. The lien is acquired when evidence is produced and lifts if it's compensated away. Monotonicity is *derived*, not declared: a refinement is monotone exactly when every commit that could cause exit is forbidden — or (below) when no act can write the referenced fields at all.

**`on leaving R` is a genuinely new trigger** — entering the complement can't express it (*became* vs. *always was*); only a member can leave. **Exit rules read evidence only**: at exit, R's captured properties have already retracted — that's capture's semantics doing its job, and a destructor-style "last look" would break the invariant everything else paid for. Evidence — which copied the captures at witness time — is the only survivor. Compile guardrail: a rule `on leaving R` must not read R's properties; the compiler rejects the read and points at the evidence.

## Mutation relocates the ledger — it doesn't eliminate it

If any rule, guard, capture, or `latest` depends on history and the store mutates in place, a correct compilation *must synthesize* the history the spec didn't describe: entry/exit logs, occurrence identities, write-path transition detection, snapshot-consistent evaluation, atomic check-then-write. This is the industry's actual trajectory — mutable rows grow audit tables, triggers, and CDC streams because history-dependent behavior forces the event log back into existence, unnamed. The design axis is not *immutability: yes/no* but: **is history part of the description, or part of the implementation?** Relocated into the implementation, the ledger exists but the spec can't name it — `why` can't cite it, POs can't query it, retention is decided by nobody. Velle's position: **mutation is fine wherever no construct remembers; wherever history is load-bearing, it must be reified or the refinement provably monotone.**

**The reissue example, compressed.** Use case: an invoice is issued and the customer is notified (`rule NotifyCustomer on Issued produces IssuedNotification`, with `Issued` defined over a mutable `issued: Date?` field); the invoice is then un-issued, a line item is edited, and it is re-issued at a new total. The lifetime guard either suppresses the second notification (customer acts on a stale $500 email for a $650 invoice) or the evidence was deleted (the system denies an email the customer holds). Both wrong, because the guard is scoped to the invoice's *lifetime* while the business meaning is per-*issuance* — and the issuance isn't a thing in the spec, just an overwritten date field. Reify the occurrence (`Issuance` facts, guards scoped `for issuance`) and every problem dissolves without deleting anything.

## The truth ladder: how a commit impacts each kind of statement

The central question, answered in one structure. Every statement in a Velle spec sits on exactly one rung, and each rung has a fully determined response to state change. That determinacy *is* the coherence guarantee; the rungs where determinacy runs out are exactly where the compiler must stop and ask a human.

1. **Timeless statements** — shape and refinement *definitions*, rules as declarations. Claims about every possible state; no commit affects their truth. Only *spec edits* do, and those are handled by whole-spec re-validation (README §1).
2. **State-dependent truths** — derived properties and memberships. True by construction at every commit: recomputed, never maintained. A commit cannot make them false, only different.
3. **Membership-anchored truths** — captured properties. True relative to the *current* membership: the commit that causes exit retracts them, re-entry re-captures. Maintained by lifecycle, not recomputation — and just as automatic.
4. **Historical truths** — evidence and the effects it stands for. True about the state that was *witnessed*; no commit can make them false. But a commit can falsify their **premise**, and the divergence between record-of-then and state-of-now is the one thing truth maintenance cannot resolve — it is not a truth question but a business question, which is why it requires a declared policy (stands / forbidden / compensate) and why silence there is a hole in the spec.
5. **Meta-truths** — compiler proofs: monotonicity, at-most-one, exhaustiveness/partition, entry-evaluability, guard exactly-once. Quantified over all states *reachable given the act roster*. No individual commit can break them — but adding or changing an *act* re-opens them, and re-verification must report connectedly (the new act and every proof it voids, in one diagnostic).

So "does this mutation make the design incoherent?" always has a determinate answer by rung: 1–2 are safe by construction, 3 is safe by lifecycle, 4 is where the PO owes a policy, 5 is where a spec change triggers re-proof. The mutation taxonomy (next) is this ladder applied case-by-case, and the three compiler responses are its verdicts: **silence** (rungs 2–3), **demand a declaration** (rung 4), **report a broken proof** (rung 5).

## The mutation taxonomy (7/27)

*Where* a mutated property is referenced determines what goes questionable and what the compiler should say. Under inertness, "P can be mutated" means precisely: **some declared act's post-state can change P** — the mutation surface of the system *is the act roster*. Compiling walks it: for each act, for each field it can write, trace the references and classify.

1. **Read only by derived properties → silence.** Truth maintenance is automatic and total. Asking would be noise, and a tool that cries wolf here teaches people to ignore it. This is the contrast class for everything below.
2. **In a rule's condition, causing *entry*.** An act (say `CorrectDueDate`) fixes a mistyped `due` date, and the fixed date happens to be in the past — the invoice enters `Overdue` and the late-fee rule fires because of a *data correction*. **Resolved (7/27): correction-vs-change is not a language distinction — it is act vocabulary the Product Owner declares.** An act is an act; if corrections and extensions should behave differently, they are *different acts* (`CorrectDueDate`, `ExtendDueDate`), possibly with identical postconditions — and because acts are facts in the tree, rules and refinements react to *which one occurred*: exclude corrected invoices from the late-fee refinement (`... and not exists CorrectDueDate for this`), or compensate a fee in reaction to a correction. The compiler's whole job here is case 7's conversation — surface that this act's writes reach that rule — and the PO answers with act and rule design. (On "the fee should have been assessed weeks ago": the committed history is what the system *knew* — capture-vs-ledger, above. A correction adds knowledge; it never rewrites past reactions. Any retroactive obligation is an ordinary rule reacting to the correction act.)
3. **In a rule's condition, causing *exit*.** Captures retract (automatic — but the PO should *see* it); evidence has a falsified premise → stands / forbidden / compensate owed per *(property × witnessed effect)*. Under the act-roster model the list of owed policies is finite and enumerable, not speculative.
4. **In a rule's *body* but not its condition — drift without a hook.** A line item is edited *while the invoice is still Issued*; the sent email diverges from current state with no membership change to hang a policy on. This is why effects should witness captures. Compiler move: flag any rule body reading a live mutable value not frozen by a capture — freeze it, or declare the drift acceptable.
5. **What a guard is scoped *through* — re-parenting.** An act reassigns `invoice.customer` from customer A to customer B: the receipt still exists, the guard still suppresses, but the evidence was about the A-era invoice; aggregates like `sum(invoices, balance)` silently move between the two customers. The evidence didn't change and the instance didn't change — the *path between them* did. No vocabulary. New design surface.
6. **An ordering property — the operative past rewrites.** Mutating `receivedOn` (or backdating) flips what `latest(... by ...)` selects, shifting everything derived from it with no membership change to announce it. Backdating is a legitimate concept (capture-vs-ledger, above), but ordering properties are a distinguished reference class: mutating one rewrites *which past is operative*.
7. **Action at a distance — memberships flip on other shapes.** Editing `customer.tier` exits `VipCustomer`, which flips `ReferredByVip` on `Referral` instances, firing or stranding rules the act never mentioned. Each hop is cases 2/3; the conversation is different: *did you know this act reaches that rule?* The blast radius is a `why`-style query ("every rule reachable from the fields this act writes"), and diagnostics must be connected — name the act and the distant rule together (README §18's connected-diagnostic principle).
8. **Oscillation — mutation makes a refinement re-enterable.** `due` moves out and back; entry rules face the §10/§11 "once" contradiction live: per-lifetime guards suppress, per-occurrence guards fire, compensations stack. The compiler can detect rules on refinements whose predicates reference act-writable fields and force the question: which "once" did you mean? Occurrence reification stops being optional here.
9. **Proof invalidation.** `(ArchiveRequest for this)` was legal because at-most-one was provable; `Issued` was sweepable because provably monotone; the partition declaration (future) proves exhaustiveness. **Every compile-time proof is conditional on the mutation surface.** Adding one act that writes a relevant field silently voids proofs elsewhere — same effects-at-a-distance shape as §18's field-ambiguity example, same treatment: report the new act and every proof it voids as one connected diagnostic.

**Mutability is derived, not declared.** No act writes `issued` ⇒ `issued` is immutable ⇒ `Issued` monotone ⇒ no policies owed, sweeps legal, proofs unconditional. Adding an act is what triggers re-verification — compiling-as-validation exactly.

**The compiler has three kinds of response**, and the taxonomy sorts every case into one:

- **Silence** (case 1) — automatic truth maintenance, no human needed.
- **Demand a declaration** (cases 2, 3, 4, 7, 8) — a business question exists that the spec can't answer and an engineer must not answer by default. The compile error *is* the PO/Engineer conversation, scheduled. (For case 2 the demanded declaration is not a new construct — it is the act and rule design itself: which acts exist and how rules relate to them.)
- **Report a broken proof, connectedly** (cases 5, 6, 9) — the mutation falsifies something the spec elsewhere depends on; the diagnostic names both ends.

## The post-state grammar: a worked sketch (7/27)

The direction, made concrete. An act shape may declare an `after:` clause stating **what is true after the act commits** — a postcondition, never a sequence of instructions. For each conjunct of the postcondition, the compiler does one of exactly two things: **verifies** it (proves the act's own arrival makes it true, so nothing needs computing) or **derives the write** (computes the single field change that makes it true). Each case below states the business use case, what the example is meant to show, and where it falls short.

**Case 1 — entry by act existence: the claim is verified; nothing is written.**

*Use case:* a user issues an invoice. Issuing is what makes an invoice count as issued, and the total at that moment must be remembered.

```
shape IssueRequest {
    invoice: one Invoice
    after: invoice is Issued
}

shape Issued = Invoice where exists IssueRequest for this {
    captured issuedOn: DateTime = now
    captured totalWhenIssued: Money = total
}
```

`IssueRequest` is the act — the user's issuing action, recorded as data, with a field pointing at the invoice being issued. `Issued`'s predicate tests whether an `IssueRequest` referencing the invoice exists. So when an `IssueRequest` instance commits, that instance's own presence in the data is what makes the predicate true for its invoice — no field on `Invoice` is written, and there is nothing for the compiler to compute.

*Intent of the example:* show that `after:` here is a **verified claim, not a command**. The Product Owner reads "committing an `IssueRequest` makes its invoice `Issued`" as a sentence in the spec; the compiler proves it holds. The payoff comes later: the day someone edits `Issued`'s predicate so that committing an `IssueRequest` no longer suffices, this clause *fails to verify*, and the diagnostic names the predicate edit and this clause together (README §1's connected diagnostics).

*Shortcoming:* none for entry — this is the well-behaved case the rest of the sketch is measured against.

**Case 2 — value change: the claim is invertible; the write is derived.**

*Use case:* a customer's email address was mistyped and support corrects it. Second use case: an approval is withdrawn, so the approver field must be cleared.

```
shape CorrectEmail {
    customer: one Customer
    corrected: text
    after: customer.email == corrected
}

shape RetractApproval {
    invoice: one Invoice
    after: invoice.approvedBy is none
}
```

Unlike case 1, neither postcondition is satisfied by the act's mere existence — `customer.email` has to actually change. But each claim has exactly **one** delta that satisfies it: an equality between a writable stored field and a value the act supplies determines the write (`email` becomes `corrected`); `is none` on a writable optional determines the clearing. The compiler derives these writes from the claims.

*Intent of the example:* show that "update" and "clear" — including the long-missing "set `issued` to none" spelling — are both expressible as postconditions: the spec states the truth that must hold after the commit, not the operation that brings it about.

*Shortcoming:* this only works when the claim is invertible (one unique satisfying delta). What happens at the limits is case 4.

**Case 3 — collection change: no grammar needed at all.**

*Use case:* recording a payment against an invoice — the use case behind README §17's original `output: invoice with payments += payment` example.

There is no example block because there is nothing left to write. Inverse relationships are inferred (README §5): `invoice.payments` is *derived* from each `Payment`'s own `invoice` field. A `Payment` is itself an act — money arrived from outside — and committing a `Payment` whose `invoice` field is set already changes what `invoice.payments` contains, by derivation, the same way a membership changes. The `+=` was describing a write the data model performs automatically.

*Intent of the example:* show that most collection "mutations" dissolve under the commit model — they were never writes.

*Shortcoming:* if genuinely stored, non-inverse collections exist anywhere, they would still need a spelling — but they may simply not need to exist.

**Case 4 — the illegal claim: the error is the design working.**

*Use case:* deliberately broken — an act that tries to declare an invoice overdue by fiat.

```
shape MakeOverdue {
    invoice: one Invoice
    after: invoice is Overdue        -- COMPILE ERROR
}
```

`Overdue = Invoice where balance > 0 and due < today` is drift-entered (see Vocabulary): its predicate is inequalities over a derived value and the clock. No unique delta makes it true — should the compiler lower `due`? raise `balance`? wait for time to pass? The claim is neither satisfied by the act's existence (case 1) nor invertible (case 2), so it is illegal.

*Intent of the example:* establish the legality rule — **you can't command drift**. An `after` conjunct must be one of: a membership the act's own existence entails; an equality on a writable field with a supplied value; `is none`/`is some` on a writable optional. Inequalities, aggregates, `latest`, and derived properties are none of these.

*Why the error is right:* the fix is to state what actually changes, and the error can say so in Product Owner terms — "you can't command an invoice to be overdue; you can change what it owes or when it's due."

**Case 5 — exit: the claim exposes the open problem, then occurrence pairing solves it.**

*Use case:* archiving with undo. A user archives an invoice; later someone unarchives it; it may be archived again after that.

First, the shortcoming, deliberately exposed. With the archive definition used elsewhere in these docs —

```
shape ArchivedInvoice = Invoice where exists ArchiveRequest for this
```

— membership is *permanent*: an `ArchiveRequest`, once committed, exists forever (facts are never deleted), so no later commit can ever make `exists ArchiveRequest for this` false. An unarchive act declaring `after: invoice is not ArchivedInvoice` is a compile error — the postcondition is unsatisfiable. That is exactly the "exit from act-entered refinements" open problem (README §18), and the `after` clause is where it surfaces: at spec-writing time, as a diagnostic, instead of as a runtime surprise.

The fix is to redefine archived-ness as "archived, *and not unarchived since*" — pairing each `ArchiveRequest` with any later `UnarchiveRequest`:

```
shape ArchivedInvoice = Invoice where
    exists ArchiveRequest for this
    and not exists UnarchiveRequest for this newer than that ArchiveRequest {
    captured archivedBy: one User = (latest ArchiveRequest for this).requestedBy
}

shape UnarchiveRequest {
    invoice: one Invoice
    after: invoice is not ArchivedInvoice
}
```

Now committing an `UnarchiveRequest` is itself the fact that falsifies the predicate — exit works by case 1's mechanism again: the claim is *verified* (the act's own arrival achieves it), nothing is written, and a later re-archival re-enters the refinement with fresh captures.

*Intent of the example:* show that exit-as-postcondition is expressible; that the compiler correctly rejects it when the predicate makes exit impossible; and that the fix is a *predicate* redesign, not new act machinery.

*Shortcoming:* `newer than that ArchiveRequest` is invented syntax — the occurrence-ordering vocabulary the reification open question already demands. This example is why that vocabulary is load-bearing.

**The finding:** the grammar wants only **two mechanisms** — *verified* claims (the act's existence entails the postcondition: entry, and exit under occurrence pairing) and *derived* writes (invertible equalities) — and everything CRUD-shaped either reduces to one of them or is illegal for a reason a Product Owner can understand. Multiple conjuncts in one `after` are unordered (ordering is compilation's, same as `then`-less effects); contradictory conjuncts are a compile error. And **an act's meaning is not exhausted by its delta**: two acts may declare identical postconditions and remain different facts — downstream rules and refinements distinguish them by which act exists (see taxonomy case 2).

**Wobbliest parts:** the `newer than that X` ordering syntax is pure invention; and whether invertible equalities should also get `with` sugar (`customer with email: corrected`) for POs who prefer that reading — same semantics either way. (`after` vs. `output` is *not* a tension: §17's `output` was a captured thought, never a settled principle — `after` simply replaces it. Whether the function-style "shape with a result" pattern needs any distinct construct at all, or reduces to acts plus `produces`, can be decided when Mapping is designed.)

## Open questions

### New design surface

*(Struck 7/27: correction-vs-change — resolved as PO-declared act vocabulary, not a language distinction; see taxonomy case 2.)*

- **Relationship re-parenting** (taxonomy case 5). What evidence, guards, and aggregates mean when the path between instances is re-wired. No vocabulary proposed.
- **The post-state grammar** — the central gap, now with a worked sketch (previous section): `after:` assertions, two mechanisms only (verified act-existence, derived invertible writes), "you can't command drift" as the legality rule, exit falling out under occurrence pairing. `after` replaces §17's `output`, which was provisional. What remains open from the sketch: the occurrence-ordering syntax (`newer than that X` is invented) and optional `with` sugar for invertible equalities.

### Occurrences and "once"

- **Occurrence reification.** The `Issuance` fix presupposes `Issuance` facts, but a recorder rule's own `produces` guard is lifetime-scoped — it can't record re-occurrences; the recorder has the disease it cures. Needs something natively once-per-entry, e.g. `occurrence Issuance of Issued { issuedOn: issued, total: sum(lineItems, amount) }` — no syntax settled. Carries the unfixed README §10/§11 contradiction ("once per newly-satisfying" prose vs. the lifetime guard), and taxonomy case 8 makes it mandatory wherever a condition references act-writable fields.
- **Exit from act-entered refinements** (also README §18). `exists ArchiveRequest for this` is monotone — nothing can ever leave. Exit needs occurrence pairing in the predicate (`... and not exists Unarchival` newer than the matched request — occurrence ordering vocabulary) or a mutable field plus policy. Which is idiomatic is unsettled; really the occurrence question again: is a state transition a mutation with policy, or an append-only chain whose latest entry determines current state?

### Policy machinery

- **The default question.** When a producing rule declares no `on leaving` clause: silence = `stands` (semantically honest, but the PO never confirmed it) or a compile hole (methodologically honest, but noisy)? The strict reading's cost now depends on the act roster — provable immutability of the referenced fields removes the obligation entirely.
- **`forbidden`'s remainder.** Resolved: what is refused is a commit; the act is the reified attempt; refusal is a refinement of the act. Unworked: whether the compiler *derives* the refused refinement from the `forbidden` liens the act would break (hand-written, the refusal predicate duplicates the lien and the two can drift), and what an applied request's output clause looks like.

### Spellings and contracts

- **Commit-point spelling** — how the spec declares which acts (scheduled ones included) constitute a refinement's observation points (the transient question's remaining surface).
- **Observability allowances** — the generalization of `never Impossible`: declaring which provably-empty refinements must be observably empty, and which weakenings of atomicity/ordering are permitted. Isolation derivation depends on it.
- **Retention.** Guards and compensations read evidence forever; retention obligations (audits, GDPR) delete it. Erase and "exactly once per issuance" silently goes false; keep and the system violates a business rule the spec can't state. Candidate: `retained 7 years` on evidence shapes, propagated by the compiler to whatever synthesized history serves the same constructs.
- **The desugared `compensate` form.** Needs a `where` guard on a rule and a `that` binding naming the matched evidence (`original: that IssuedNotification`) — neither in the grammar — and *which* evidence `that` means is resolvable only if evidence is occurrence-scoped.
- **Commit grouping** — can a PO say "these acts commit as one step"? Deferred until a forcing example appears.

### Next investigation

- **The state partition declaration** (README §18): `states of Invoice = Draft | Issued | Paid | Voided` — mutually exclusive, jointly exhaustive, invoking the exhaustiveness/overlap check §7 already names as a compiler goal. The commit model supplies *when* transitions happen (at commits) and *what* they mean (membership deltas); refinement properties give states their data; acts give transitions their payloads; mutation policies bound which transitions are legal; the partition is the remaining assertion.

## Status

Settled: the two axioms (inertness — the system as a fold of external acts; one state — the black box as a single committed state, serializability as its justification); all-change-through-acts with one kind of commit point; state change as membership delta, CRUD as materialization; capture's semantics and its home as refinement properties (README §7); the state/effect stratification and its table; the mutation-policy trio with immutability as a lien and `on leaving R` with evidence-only reads (README §12); mutation-relocates-the-ledger; the nine-case mutation taxonomy with mutability derived from the act roster and the three compiler response kinds.

Also settled (7/27): the truth ladder — the consolidated answer to "how does state change impact the truth of the design": five rungs (timeless / state-dependent / membership-anchored / historical / meta), each with a determined response to a commit, with rung 4 the only place a human owes a policy and rung 5 the only place a spec change re-opens proofs. And sketched: the post-state grammar — `after:` postconditions with exactly two mechanisms (verified act-existence assertions, derived writes from invertible equalities), the "can't command drift" legality rule, collections dissolving into inferred inverses, exit reducing to a verified assertion under occurrence pairing, and `after` replacing §17's provisional `output`.

Also resolved (7/27): correction-vs-change — not a language distinction but PO-declared act vocabulary. An act is an act; corrections and changes that should behave differently are different acts, distinguishable downstream because acts are facts (an act's meaning is not exhausted by its delta). The committed history is what the system knew; corrections add knowledge rather than rewriting past reactions, and retroactive obligations are ordinary rules on the correction act.

Open, by weight: the post-state grammar's residue (occurrence-ordering syntax — `newer than that X` — and `with` sugar), relationship re-parenting (new vocabulary needed), occurrence reification (carries the "once" contradiction and the `that` ambiguity, and now the ordering syntax the exit sketch leans on), then the spellings and contracts (policy default, commit points, observability, retention, compensate form, grouping). Next after the grammar work: the state partition declaration.
