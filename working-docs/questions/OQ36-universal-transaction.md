# OQ36 — The universal-transaction contract, stated precisely

**Status:** open — draft contract below (2026-08-16), pending review; settles by promotion into `evaluation.md`
**In plain terms:** exactly which guarantees does Velle assume of the engineer's storage and transaction layer? Every correctness proof rests on them.
**Opened by:** `investigate_runtime.md` §2 (re-homed here)

---

Velle assumes one "universal" transaction per envelope: resolver reads see a consistent snapshot; the mutation set — capture channel included — lands atomically; and concurrent commits are serialized against conflicting state (`investigate_runtime.md` §2, §3, §6, §7). The engineer realizes the guarantee — trivially with one DB transaction, with real work when an envelope spans multiple DBs, API calls, and files (distributed-transaction territory Velle stays out of). If the low-level code hits an error, keeping the black-box state of the Velle system coherent is the engineer's job.

What's owed: the contract stated precisely in the normative docs, because proofs rest on it — the confluence and one-writer analyses (OQ16), relevance gating's soundness argument ("an untouched invariant that held at the last transaction end still holds," which needs "state changes only through commits"), and the capture channel's atomicity. How the engineer delivers the guarantees is theirs; *what* the guarantees are must be pinned.

---

## Draft contract

**Proposed home:** a new section in `evaluation.md`, after "Commits and transactions." The universal transaction is semantics, not store mechanics: v0's in-memory runtime satisfies it trivially (one process, one thread, no store — [S1]), and the hydrating runtime (`investigate_runtime.md` §2–§7) is the first realization where the engineer must actually deliver it. `checks.md` gains nothing — no clause is validator-checkable, which is the point: these are the premises the checks' theorems rest on, not checks themselves. On promotion, README §11 ("Transactions and `after commit`") and §22's runtime paragraph get a one-line pointer.

### The universal transaction

Velle evaluates each transaction in memory and delegates all persistence to the engineer through two surfaces: resolver reads hydrate the evaluation, and the commit callback lands the mutation set. The **universal transaction** is the contract those two surfaces must jointly honor, per envelope. It is written for the engineer implementing a store: each clause states the guarantee in one sentence, then says what it means in practice.

Velle never verifies these guarantees at runtime — it cannot; they concern behavior across processes and stores it never sees. That is what makes the contract worth reading carefully: **a broken clause fails nothing visibly.** The spec still validates, the tests still pass — but the things the validator proved (no double-applied deposits, invariants that hold, rules firing exactly when they should) quietly stop being true. How you deliver the guarantees is entirely yours: **one `SERIALIZABLE` database transaction per envelope delivers all five trivially**, and that should be the default choice until measurement says otherwise. An envelope that spans multiple databases, API calls, or files is distributed-transaction territory, and Velle stays out of it — coherence there is your engineering. The clauses below are the definition of coherent.

**U1 — Snapshot.**
*The guarantee: every resolver read within one envelope is answered from a single consistent state — the settled end-state of the transaction serialized immediately before this one (U3).*

In practice: while Velle processes one commit, every question it asks your store must be answered *from the same frozen moment*. If it asks two questions and someone else's transaction lands between your two answers, each answer was true at *some* moment — just not the same one, and that is a breach even though neither answer was individually wrong. The straightforward implementation: open one database transaction with consistent-read isolation when the envelope begins, and answer every resolver call from it. The runtime meets you halfway — it never asks the same question twice in one envelope (fetches are memoized), and it reads the envelope's own in-progress writes from memory — so you never serve mid-envelope state, and must not. A tick is the same deal: each tick opens a fresh look at settled state.

**U2 — Atomicity.**
*The guarantee: the envelope's mutation set becomes durable as one unit, or not at all.*

In practice: at the close of the envelope, your commit callback receives everything the transaction did as one payload — the act's insert, every rule-produced insert and update, and the capture writes and deletes. Write all of it or none of it. If anything goes wrong — your database throws, a constraint fires, a connection dies — the whole envelope must disappear and the error propagate; the callback runs *inside* the envelope, so throwing from it *is* the rollback signal, and the caller gets the error. No later envelope may ever observe half a payload. With one database this is just `BEGIN … COMMIT`/`ROLLBACK`; with storage spanning systems, making failure leave no visible half-state is your job. One thing you never see: refused acts — boundary validation runs before the envelope exists, so a refusal reaches your store as nothing at all.

**U3 — Serialization.**
*The guarantee: the observable history is equivalent to some order in which transactions ran one at a time, each reading (U1) its predecessor's settled end-state — with real time respected between conflicting transactions.*

In practice: "as if one at a time" does **not** mean single-threaded. Two envelopes that touch entirely different data may run fully in parallel — no observer can tell, and the contract doesn't care. The obligation is only between envelopes that touch the *same* data: those must take turns, and the one acknowledged first goes first (that is all "last-in-wins" ever meant). The subtle part: "touching the same data" includes *checks*, not just writes. Velle's guard idiom reads "no `DepositApplication` exists for this deposit yet," then creates one. Run two envelopes for the same account concurrently under plain snapshot isolation and *both* see "none exists yet" — both fire, and the deposit applies twice. That failure mode (write skew) is exactly what popular defaults like Postgres `REPEATABLE READ` permit, so **snapshot isolation is not sufficient**. Two honest implementations: `SERIALIZABLE` isolation on one database, which buys the whole clause outright; or a lock on the entity the envelope revolves around — `SELECT … FOR UPDATE` on the account row, a per-account mutex, a partition key — so same-account envelopes serialize and different accounts run in parallel. Knowing *what value to lock on* is exactly what [OQ40](OQ40-serialization-domains.md) exists to answer from the spec itself: the goal is a generated contract that tells you "deposits serialize per `account`" instead of leaving you to re-derive it from the rule graph.

**U4 — Permanence.**
*The guarantee: once an envelope is reported accepted, its effects are durable — every later snapshot reflects them.*

In practice: "accepted" must mean *it is in the store the next read hits, and it stays there*. Velle's entire recovery story is built on data: a `DepositApplication` row is the only memory that a deposit was applied. If that row vanishes — a cache that evicts, an acknowledgment sent before the write is actually durable, a restore from a stale backup — the hourly backstop sweep sees an unapplied deposit and applies it again. The same holds across processes: a second runtime hydrating your store must find everything the first one committed, because guard rows, episode flags, and capture rows are how it knows what already happened. This clause is also what `after commit` rules stand on — their firing begins only after the triggering transaction is durable, so they always see its effects.

**U5 — No side doors.**
*The guarantee: between one transaction's end and the next one's snapshot, Velle-visible state does not change — every mutation to the data behind the resolvers enters as a commit through the generated surface.*

In practice: **reading is always safe** — query the tables backing Velle shapes with your own SQL as much as you like; reads commit nothing. Writing is the door that must stay shut: a hand-run `UPDATE`, a migration script, an admin console, another service writing the same tables — each changes data with no rules fired, no invariants checked, no guards observing. Everything Velle proved assumed changes arrive as commits; a side-door write is a case none of that analysis considered, and nothing will flag it — the data is simply wrong in ways the spec promised were impossible. Your own tables *beside* the Velle-backed ones are none of the contract's business. When outside data must flow in, it enters as commits through the exposed surface, or through a mapping that carries its validation obligation (README §21).

### What each clause carries

The language-side layer, for the reader who works on Velle rather than against it: every static analysis that makes a validated spec trustworthy is a theorem with these clauses as premises. The map from clause to the proofs that spend it — what specifically stops being true when a clause is violated:

| clause | spent by |
|---|---|
| U1 Snapshot | sibling-commutation/confluence analyses (all of a commit's firings read one state); relevance gating; the transaction-end `never` check (a settled world to check) |
| U2 Atomicity | disarm soundness (mutation and witness are statements of one body — one payload); the capture last-reader contract (capture read and retraction land in one envelope); fan-out atomicity (README §20, all-or-nothing batches) |
| U3 Serialization | one-writer at transaction scope; last-in-wins as the cross-commit order; guard soundness under concurrency (no two envelopes both see the guard armed) |
| U4 Permanence | `after commit` + backstop healing; intent-before-effect (the intent durably precedes the effect's envelope); cross-process guards and episodes |
| U5 No side doors | relevance gating; inductive `never` proofs; every derived trigger set (a side-door write fires no rules — drift stops being commit-mediated) |

### What the contract deliberately excludes

- **Mechanism.** One DB transaction, sagas over three stores, a file plus an API — Velle never knows. The contract is the *what*; §2's line stands: the *how* is the engineer's, permanently.
- **The world/state gap.** No clause makes an external effect atomic with its record — nothing can (README §11, "the gap between the world and the state is irreducible"). The contract governs state; the gap is answered above the contract by intent-before-effect, `after commit`, and `tolerates loss`.
- **The pre-filter.** `fetchCandidates` over-returning is always legal — a performance surface under the superset contract (`investigate_runtime.md` §6), orthogonal to correctness. Only `fetchCaptures` sits on the correctness side of that line (§7's deliberate asymmetry), and its guarantees are U2/U4's.
- **Refusal handling.** Wrapper code may log, rate-limit, or audit refused acts in the engineer's own tables — that data is theirs, not Velle-visible state, and U5 doesn't touch it.
