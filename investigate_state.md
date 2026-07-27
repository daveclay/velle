# State, Time & Mutation — consolidated findings

*(Consolidated 7/27: `investigate_time.md` is merged into this document — state, time, and mutation turned out to be one discussion. Findings only; superseded debates are pruned. Git history has the derivations.)*

## The problem, in one sentence

A Velle description quantifies over *now* — but capture, `produces` guards, `latest`, and rule firing all depend on *moments and memory*, and mutation is what exposes the difference. Pulling that thread forced: two axioms, a state/effect stratification, one new primitive (capture, now living as refinement properties), a mutation-policy vocabulary, and a taxonomy of the questions mutation obliges the compiler to raise.

## Two axioms

**Inertness (the fundamental one).** *The system never does anything itself.* Every state change originates in an external act — typically a user's, but agnostic to the actor. The scheduler is not a mechanism inside the system; it is just another external actor, and a tick is just another act arriving from it (README §16 already said this locally — "a scheduled tick is conceptually a shape instance like any other" — inertness promotes that sentence to a principle). Between acts, nothing happens. The system is a pure reactor: its entire behavior is `state' = commit(state, act)`, and its history is a fold of external acts over an initial state.

**One state.** The whole system — treated as a black box, databases included — is a single state. Its history is a sequence of commits, each atomic and totally ordered as described. The database is not *the* state tree; it is inside the box, one materialization among possible ones. All threading, locking, and synchronization machinery is compilation machinery discharging this axiom — and every deliberate weakening of it must appear in the spec as a declared observability allowance, never as a silent engineering choice.

The one-state axiom is not naive, and the strongest argument comes from the machinery itself: **serializability** — the gold-standard correctness criterion for transaction processing — is *defined* as "equivalent to some serial execution against a single consistent state." Locks, MVCC, WAL, isolation levels, optimistic concurrency, idempotency keys: their entire correctness story is how well they impersonate the simple model. The industry already agreed the simple model is the spec; it just never let anyone write specs in it. Velle adopts as its description level the exact model the proofs were already written against.

Consequences of inertness:

- **Provenance is well-founded.** Every piece of state has a finite `why` chain terminating at an external act. Nothing is self-caused.
- **Background behavior is unsayable.** Retries, expirations, nightly jobs — anything that looks like the system acting spontaneously — must trace to an external act (usually the scheduler's) or it does not exist in the description.

**No second state tree.** The classic concurrency bugs — read-modify-write races, lost updates, stale checks — happen when application code *forks the tree*: reads state into local variables (the heap: a second, private, un-synchronized state tree), decides against the copy, writes back after the tree has moved. Velle structurally cannot express the fork: refinements are predicates over current data, captures are anchored to commits, act outputs are expressed over the tree itself, and there is no ambient execution context to smuggle a copy through. The races engineers spend careers on are unrepresentable at the description level — which is what makes them a **compilation obligation** rather than a shared burden. (React is the useful comparison here — not a template: its real contribution was killing the second state tree, recomputing the derived copy from the single source. The backend heap-and-cache layer is the DOM of server systems, and Velle's derived layer is already the cure written down. Where React and Velle disagree — fire-and-forget effects vs. guarded, evidence-producing ones — Velle's existing design wins.)

## Commits

**All change enters through acts — there is no raw mutation.** Every earlier problem case began with an off-stage write ("a user deletes `issued`"). Under the commit model that sentence is unsayable: there is only an `UnissueRequest` act, committed or refused. "Model the deletion as a fact" graduates from advice to structure. This also gives `forbidden` its subject: what is refused is a **commit** — the act is the reified *tries*, refusal is a refinement of the act (errors-are-refinements), and the lien is checked at one nameable moment instead of hovering over every field write.

**There is only one kind of commit point: an external act arriving.** The language's two trigger positions distinguish what a *rule reacts to* — a membership change (prefix `on`) vs. a named schedule-act (postfix `on`) — not two kinds of commit. No commit has an empty delta: the act itself is data entering the tree, and its arrival *is* the change. That arrival is also what forces observation — the semantic justification for README §16's stance that purely time-dependent refinements are only re-checked on schedule. `today` changing at midnight is not an event; a commit observing it is, and the only way a commit happens at 7am is that the scheduler's act arrived at 7am.

**This inverts the entry-transient question from mechanism to declaration.** If memberships are functions of *committed* states, a membership "exists" only if some commit exhibited it. The midnight/6am late-fee ambiguity dissolves: it was never an engineer's event-stream-vs-sweep choice; it is a spec-level question of *which acts arrive* — and "we observe overdue-ness daily" is a sentence a Product Owner already says, now meaning "the scheduler sends a `Daily` act." Only the spelling remains open.

**State change = membership delta (the CRUD-agnostic meaning).** Insert, update, delete are verbs about a *store*. A Velle state change is a delta of *truth*: between commit N and N+1, the memberships that begin, the memberships that end, and the captures that fire. That delta is the meaning of an act, and it is computable (the blast-radius analysis, below). Whether a delta compiles to an INSERT, an UPDATE, an event-log append, or a tombstone is materialization — the compile phase's business (README §1). "Delete" at the description level is a transition where something stops holding, which is why it keeps turning into compensating facts. Act-entered refinements exhibit the purest form: `ArchivedInvoice`'s predicate is `exists ArchiveRequest for this`, so committing the act *is* the membership change — zero field writes anywhere.

**Three boundaries of the one-state axiom:**

1. **The tree ends at the effect boundary.** Sent emails and external charges aren't in the tree and can't roll back with it. Evidence shapes are the tree's record of the world outside it; the exactly-once contract on `produces` across that boundary is the compiler's problem (outbox patterns and the like), invisible to the description.
2. **Distribution is materialization.** The spec describes one tree; one Postgres vs. a saga-coordinated fleet is a runtime decision — with relaxations of commit atomicity legal only where the spec permits them.
3. **Isolation is derived, not picked.** Engineers choose isolation levels by folklore, silently deciding business questions. Under the commit model the PO declares which intermediate states may never be observable (`never Impossible`-style declarations — the commit *is* the definition of the observable step), and the compiler buys exactly as much consistency as the declared constraints require.

## Capture: state-layer memory

**Settled semantics.** A captured value is *present iff the instance is currently a member of the refinement; its value is the expression as evaluated at the moment the current membership began.* Absent before entry, fixed during membership, retracted on exit, re-captured on re-entry. Transient-safe; for a monotone refinement it degenerates to "evaluated once, fixed forever."

**A genuinely new primitive, not sugar.** Capture cannot desugar to a hidden `rule ... produces` evidence shape: produced facts are effect-layer (durable; deletion lies about the world), while a capture *must* retract on exit. You can't build a thing that must be deleted out of a thing that must never be. Capture is the first construct whose stored state tracks membership — strictly more than a predicate, strictly less than history.

**Capture anchors the clock.** Capturing `now`/`today` yields the entry moment — occurrence timestamps with no implicit system timestamp.

**Capture freezes transitively — and is a distinct concept from ledger reconstruction.** A capture closes over its entire live dependency graph at the entry instant. The proof both concepts are real: backdate a fact (a fuel surcharge with `effectiveOn` before the quote date) and the captured value and the ledger reconstruction (`latest(surcharges where effectiveOn <= quotedOn by effectiveOn)`) disagree — correctly, because they answer different Product Owner questions: *what we told the customer then* (capture) vs. *what we now believe was true then* (ledger). Neither is a redundant spelling of the other.

**Capture's home: refinement properties** (spec now in README §7). The essentials: properties on refinements come in exactly two kinds — derived (live) and `captured` (the marker is required; bare `= expr` in body position is a live derivation) — with **no third "assigned" kind**: "supplied" data (`archivedBy: one User`) reduces to capture from a reified act, because there is no ambient context and a capture can only reach data through the tree. Presence typing is ordinary field typing (`is R` narrows like `is some`). The **entry-evaluability guardrail**: a capture's expression must be provably evaluable at entry — every reference guaranteed by the refinement's own predicate or unconditionally present on the base. **Drift-entered vs. act-entered is derived by the compiler from the predicate**, never declared. Cross-refinement reads live on intersections (`Quoted and Delivered { priceDrift ... }`). Terminology settled in passing: these stay *refinements*, not "derived shapes" — "derived" already means recomputed-from-current-data, which captured properties are precisely not; "derived shape" stays reserved for Mapping (translation into a new instance) vs. refinement (narrower view of the same instance).

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

**The reissue example, compressed.** `rule NotifyCustomer on Issued produces IssuedNotification` with a mutable `issued: Date?`: un-issue, edit, re-issue — and the lifetime guard either suppresses the second notification (customer acts on a stale $500 email for a $650 invoice) or the evidence was deleted (the system denies an email the customer holds). Both wrong, because the guard is scoped to the invoice's *lifetime* while the business meaning is per-*issuance* — and the issuance isn't a thing in the spec, just an overwritten date field. Reify the occurrence (`Issuance` facts, guards scoped `for issuance`) and every problem dissolves without deleting anything.

## The mutation taxonomy (7/27)

*Where* a mutated property is referenced determines what goes questionable and what the compiler should say. Under inertness, "P can be mutated" means precisely: **some declared act's post-state can change P** — the mutation surface of the system *is the act roster*. Compiling walks it: for each act, for each field it can write, trace the references and classify.

1. **Read only by derived properties → silence.** Truth maintenance is automatic and total. Asking would be noise, and a tool that cries wolf here teaches people to ignore it. This is the contrast class for everything below.
2. **In a rule's condition, causing *entry*.** Correcting a mistyped `due` makes an invoice `Overdue`; the late-fee rule fires because of a *data correction*. The missing distinction: was the act a **correction** ("it was always due Jan 5; we recorded it wrong") or a **change** ("we extended the due date")? A change deserves fresh reactions; a correction arguably means past non-reactions were wrong. No vocabulary exists for an act to declare which it is. New design surface.
3. **In a rule's condition, causing *exit*.** Captures retract (automatic — but the PO should *see* it); evidence has a falsified premise → stands / forbidden / compensate owed per *(property × witnessed effect)*. Under the act-roster model the list of owed policies is finite and enumerable, not speculative.
4. **In a rule's *body* but not its condition — drift without a hook.** A line item is edited *while the invoice is still Issued*; the sent email diverges from current state with no membership change to hang a policy on. This is why effects should witness captures. Compiler move: flag any rule body reading a live mutable value not frozen by a capture — freeze it, or declare the drift acceptable.
5. **What a guard is scoped *through* — re-parenting.** `invoice.customer` reassigned from A to B: the receipt still exists, the guard still suppresses, but the evidence was about the A-era invoice; aggregates silently move between parents. The evidence didn't change and the instance didn't change — the *path between them* did. No vocabulary. New design surface.
6. **An ordering property — the operative past rewrites.** Mutating `receivedOn` (or backdating) flips what `latest(... by ...)` selects, shifting everything derived from it with no membership change to announce it. Backdating is a legitimate concept (capture-vs-ledger, above), but ordering properties are a distinguished reference class: mutating one rewrites *which past is operative*.
7. **Action at a distance — memberships flip on other shapes.** Editing `customer.tier` exits `VipCustomer`, which flips `ReferredByVip` on `Referral` instances, firing or stranding rules the act never mentioned. Each hop is cases 2/3; the conversation is different: *did you know this act reaches that rule?* The blast radius is a `why`-style query ("every rule reachable from the fields this act writes"), and diagnostics must be connected — name the act and the distant rule together (README §18's connected-diagnostic principle).
8. **Oscillation — mutation makes a refinement re-enterable.** `due` moves out and back; entry rules face the §10/§11 "once" contradiction live: per-lifetime guards suppress, per-occurrence guards fire, compensations stack. The compiler can detect rules on refinements whose predicates reference act-writable fields and force the question: which "once" did you mean? Occurrence reification stops being optional here.
9. **Proof invalidation.** `(ArchiveRequest for this)` was legal because at-most-one was provable; `Issued` was sweepable because provably monotone; the partition declaration (future) proves exhaustiveness. **Every compile-time proof is conditional on the mutation surface.** Adding one act that writes a relevant field silently voids proofs elsewhere — same effects-at-a-distance shape as §18's field-ambiguity example, same treatment: report the new act and every proof it voids as one connected diagnostic.

**Mutability is derived, not declared.** No act writes `issued` ⇒ `issued` is immutable ⇒ `Issued` monotone ⇒ no policies owed, sweeps legal, proofs unconditional. Adding an act is what triggers re-verification — compiling-as-validation exactly.

**The compiler has three kinds of response**, and the taxonomy sorts every case into one:

- **Silence** (case 1) — automatic truth maintenance, no human needed.
- **Demand a declaration** (cases 2, 3, 4, 8) — a business question exists that the spec can't answer and an engineer must not answer by default. The compile error *is* the PO/Engineer conversation, scheduled.
- **Report a broken proof, connectedly** (cases 5, 6, 9) — the mutation falsifies something the spec elsewhere depends on; the diagnostic names both ends.

## Open questions

### New design surface

- **Correction vs. change** (taxonomy case 2). An act needs to declare whether it corrects the record or changes the world; downstream reactions legitimately differ; no vocabulary proposed.
- **Relationship re-parenting** (taxonomy case 5). What evidence, guards, and aggregates mean when the path between instances is re-wired. No vocabulary proposed.
- **The post-state grammar** — the central gap. The reducer language barely exists: README §17 has `+=` and nothing else; "set `issued` to none" has no spelling. Direction worth pursuing: **membership vocabulary, not field-write vocabulary** — an act declares what is true after (`after: invoice is ArchivedInvoice`), and the compiler derives the delta. Entry via act-existence already works this way for free; the hard half is **exit** ("make it stop being true"), which runs straight into the monotone-`exists` problem.

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

Open, by weight: the post-state grammar (central — it carries the exit problem), correction-vs-change and re-parenting (new vocabulary needed), occurrence reification (carries the "once" contradiction and the `that` ambiguity), then the spellings and contracts (policy default, commit points, observability, retention, compensate form, grouping). Next after the grammar work: the state partition declaration.
