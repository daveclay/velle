# State as Commits — investigation

*(Started 7/27. Continues from `investigate_time.md` — the state/effect stratification, capture, refinement properties, and reified acts are all assumed here.)*

## The question

State change as a top-level concern. React entered as a **comparison, not a template** — the analogy's real content is treating a backend system as agnostic to its own conventions (threading, the database) the way React made UI agnostic to DOM manipulation. Treat the system *including its databases* as a black box: what is inside is one single state that can be committed to. The enormous effort poured into threading and synchronization machinery is, on this view, "really complicated machinery to ensure a consistent single state tree." And state change in Velle should be agnostic to insert/delete — less about CRUD.

## Two axioms

**Inertness (the fundamental one).** *The system never does anything itself.* Every state change originates in an external act — typically a user's, but agnostic to the actor. The scheduler is not a mechanism inside the system; it is just another external actor, and a tick is just another act arriving from it (README §16 already said this locally — "a scheduled tick is conceptually a shape instance like any other, the same category as a `Payment` arriving" — inertness promotes that sentence to a principle). Between acts, nothing happens. The system is a pure reactor: its entire behavior is `state' = commit(state, act)`, and its history is a fold of external acts over an initial state.

**One state.** The whole black box — databases included — is a single state. Its history is a sequence of commits, each atomic and totally ordered as described. The database is not *the* state tree; it is inside the box, one materialization among possible ones. All threading, locking, and synchronization machinery is compilation machinery discharging this axiom — and every deliberate weakening of it must appear in the spec as a declared observability allowance, never as a silent engineering choice.

The one-state axiom is not a naive simplification, and the strongest argument comes from the machinery itself: **serializability** — the gold-standard correctness criterion for transaction processing — is *defined* as "equivalent to some serial execution against a single consistent state." Locks, MVCC, WAL, isolation levels, optimistic concurrency, idempotency keys: their entire correctness story is how well they impersonate the simple model. The industry already agreed the simple model is the spec; it just never let anyone write specs in it. Velle adopts as its description level the exact model the proofs were already written against.

Two consequences of inertness worth naming:

- **Provenance is well-founded.** Every piece of state has a finite `why` chain terminating at an external act. Nothing is self-caused; rules and cascades are reactions *within* a chain that always bottoms out at the boundary of the box.
- **Background behavior is unsayable.** Retries, expirations, nightly jobs — anything that looks like the system acting spontaneously — must trace to an external act (usually the scheduler's) or it does not exist in the description.

## The mapping (a comparison, not a design template)

| React | Backend reality | Velle |
|---|---|---|
| state | the database | shapes (stored data) |
| render — pure function of state | (usually missing: hand-synced caches, denormalized copies) | derived properties, refinement memberships — recomputed, never written |
| `setState` / dispatched action | write endpoint / transaction script | an act shape arriving (README §17 input shapes) |
| reducer — computes next state | hand-written UPDATE logic | the act's output clause (post-state grammar — open, below) |
| commit — batched, atomic, never half-observed | the transaction | **the commit — this investigation's primitive** |
| `useEffect` after commit | triggers, outbox consumers, event handlers | rules `on` / `on leaving`, firing against committed state |
| "never mutate state directly" | (violated constantly — the heap) | all change enters through acts (below) |

React's actual historical contribution wasn't components — it was **killing the second state tree**. jQuery-era bugs were the DOM (a mutable derived copy) drifting out of sync with app state; React's answer was "stop hand-synchronizing the copy; recompute it from the single source." The backend heap-and-cache layer is the DOM of server systems. Same disease, same cure — and Velle's derived layer is already the cure written down.

Where the two disagree, Velle's existing design wins, not React's: Velle's effects are evidence-producing and guarded where React's are fire-and-forget, and Velle has an effect layer (durable history) that React has no equivalent of. The comparison earns its keep on the state side only.

## No second state tree — why the concurrency bugs are unrepresentable

The database mostly *does* maintain a consistent single tree. The classic concurrency bugs — read-modify-write races, lost updates, stale checks — happen when application code **forks the tree**: reads state into local variables, decides against the copy, writes back after the tree has moved. The heap is a second, private, un-synchronized state tree, and nearly every threading war story is the two trees disagreeing.

Velle structurally cannot express the fork: refinements are predicates over current data, captures are anchored to commits, act outputs are expressed over the tree itself, and there is no ambient execution context to smuggle a copy through. ("No ambient context" — README §7 — looked like a provenance rule when it was written; this is its concurrency payoff.) The races engineers spend careers on are unrepresentable at the description level — which is precisely what makes them a **compilation obligation** rather than a shared burden.

## All change enters through acts

React's discipline — no direct writes, everything through the dispatch channel — becomes a Velle principle: **there is no raw mutation.** Every problem case in `investigate_time.md` began with an off-stage write ("a user deletes `issued`"). Under the commit model that sentence is unsayable: there is no user reaching into the data; there is only an `UnissueRequest` act, committed or refused. The earlier investigation kept *arriving* at "model the deletion as a fact" as advice; state-as-commits makes it structural.

This also finally gives `forbidden` its subject — resolving the "what does forbidden reject?" open question's hardest part. What is refused is a **commit**. The act is the reified *tries*; refusal is a refinement of the act (the errors-are-refinements pattern); the lien is checked at one nameable moment instead of hovering over every field write. (Still unworked: whether the compiler derives the refused refinement from the `forbidden` clauses the act would break — carried over from `investigate_time.md`.)

## Commit points: there is only one kind

Commits have exactly one source: **an external act arriving.** A scheduled tick is not a second kind — the scheduler is an external actor and its ticks are ordinary acts (inertness, above). The language's two trigger positions distinguish what a *rule reacts to* — a membership change (prefix `on`) vs. a named schedule-act (postfix `on`) — not two kinds of commit.

No commit has an empty delta: the act itself is data entering the tree, and its arrival *is* the change. That arrival is also what forces observation — the semantic justification for README §16's existing stance that purely time-dependent refinements are only re-checked on schedule. `today` changing at midnight is not an event; a commit observing it is, and the only way a commit happens at 7am is that the scheduler's act arrived at 7am.

**This inverts the entry-transient question from mechanism to declaration.** If memberships are functions of *committed* states, the system's history is the sequence of commits, and a membership "exists" only if some commit exhibited it. The midnight/6am late-fee ambiguity (`investigate_time.md`, entry-side transients) dissolves: it was never an engineer's event-stream-vs-sweep choice; it is a spec-level question of *which acts arrive* — and "we observe overdue-ness daily" is a sentence a Product Owner already says, now meaning "the scheduler sends a `Daily` act." The mechanism ambiguity README §10 worried about becomes a declaration about the act roster. What remains to design is only the spelling (which acts — scheduled ones included — constitute a refinement's observation points; no syntax proposed).

## State change = membership delta (the CRUD-agnostic meaning)

Insert, update, delete are verbs about a *store*. A Velle state change is a delta of *truth*: between commit N and N+1, the set of memberships that begin, the set that end, and the captures that fire. **That delta is the meaning of an act.** It is also computable — it is literally the blast-radius analysis already sketched in `investigate_time.md`'s mutation-policy section.

Whether a given delta compiles to an INSERT, an UPDATE, an event-log append, or a tombstone is materialization — the compile phase's business (README §1). "Delete" at the description level is just a transition where something stops holding, which is why it kept turning into compensating facts every time the earlier investigation looked at it directly.

Act-entered refinements already exhibit the purest form of this: `ArchivedInvoice`'s predicate is `exists ArchiveRequest for this`, so committing the act *is* the membership change — zero field writes anywhere. The commit model generalizes what that example does for free.

## Boundaries of the single tree

Three honest limits on the axiom, each already provided for:

1. **The tree ends at the effect boundary.** Sent emails and external charges aren't in the tree and can't roll back with it — exactly the state/effect stratification. Evidence shapes are the tree's record of the world outside it; the exactly-once contract on `produces` across that boundary is where the compiler earns its keep (outbox patterns and the like are *its* problem, invisible to the description). The axiom governs state; effects remain history that commits can never un-happen.

2. **Distribution is materialization.** Microservices shard the tree, but that's a realization decision. The spec describes one tree; whether the compiler emits one Postgres or a saga-coordinated fleet is a runtime decision — with the consequence that relaxations of commit atomicity are legal only where the spec permits them.

3. **Total order costs money, and the spec is where the discount gets authorized.** Real systems run weaker isolation because serializability is expensive — and today an engineer picks the isolation level by folklore, silently deciding business questions (can a report ever show a half-applied transfer?). Under the commit model that flips: the PO declares which intermediate states may never be observable (`never Impossible` — `investigate_time.md`, now subsumed: the commit *is* the definition of the observable step), and the isolation level is **derived** — the compiler buys exactly as much consistency as the declared constraints require. Isolation becomes an output of compilation instead of an input from vibes.

## What this resolves (cross-references)

- **Atomicity granularity** (`investigate_time.md`) — subsumed. The commit is the observable step; `never`-style declarations become *allowances/prohibitions on observability*, not a bolt-on annotation.
- **Entry-side transients** (`investigate_time.md`, README §10's mechanism-independence worry) — inverted into a declaration of commit/observation points. Only the spelling remains.
- **What `forbidden` rejects** (`investigate_time.md`) — the *tries/user/rejected* vocabulary gap closes: an act is the attempt, refusal of its commit is a refinement of the act. The derive-the-refusal-predicate sub-question remains.
- **The un-issuing hole** — closed structurally: no raw mutation exists to make it.
- **Concurrency vocabulary** — established as *not belonging in the language at all*. The spec states the axiom plus observability constraints plus effect-boundary policies; everything else is the compiler discharging the axiom.

## Open design problems

1. **The post-state grammar** — the real gap, promoted from `investigate_time.md`'s "output clause" loose end to the central design problem of this investigation. The reducer language barely exists: README §17 has `+=` and nothing else; "set `issued` to none" has no spelling. Direction worth pursuing: the honest post-state language may be **membership vocabulary, not field-write vocabulary** — an act declares what is true after (`after: invoice is ArchivedInvoice`), and the compiler derives the delta. Entry via act-existence already works this way for free; the hard half is **exit** — "make it stop being true" runs straight into the monotone-`exists` problem (README §18, exit from act-entered refinements) and its occurrence-pairing/policy fork.
2. **Commit grouping** — can a PO say "these acts commit as one step"? No forcing example yet; deliberately deferred until one appears.
3. **Observability-allowance syntax** — the generalization of `never`: declaring which weakenings of atomicity/ordering are permitted (and which anomalies are business-visible). No spelling proposed.
4. **Commit-point spelling** — how the spec declares which acts (scheduled ones included) constitute a refinement's observation points (the transient question's remaining surface syntax).
5. **The state partition declaration** (README §18) — unchanged and still next: `states of Invoice = Draft | Issued | Paid | Voided`. The commit model supplies *when* transitions happen (at commits) and *what* they mean (deltas); the partition asserts the state space they move through. Refinement properties give states their data; acts give transitions their payloads; mutation policies bound which transitions are legal; the partition is the remaining assertion.

## Status

Settled here: the two axioms — inertness (the system never does anything itself; the scheduler is just another external actor; the system is a fold of external acts over an initial state) and one state (the black box, databases included, as a single committed state), with serializability as the justification that the simple model is the industry's own spec language; all-change-through-acts (no raw mutation); one kind of commit point (an external act arriving — no commit is empty, the act's arrival is the delta); state change defined as membership delta, with CRUD as materialization; well-founded provenance and the unsayability of background behavior; the three boundaries of the one-state axiom (effects, distribution, derived isolation); and the resolution or reframing of four prior open questions (atomicity, transients, `forbidden`'s subject, un-issuing).

Open, by weight: the post-state grammar (central — and it carries the exit problem with it), then spellings (commit points, observability allowances), then commit grouping when a forcing example arrives. The state partition declaration remains the next investigation after this one's grammar work.
