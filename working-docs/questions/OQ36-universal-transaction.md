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

Velle evaluates each transaction in memory and delegates all persistence to the engineer through two surfaces: resolver reads hydrate the evaluation, and the commit callback lands the mutation set. The **universal transaction** is the contract those two surfaces must jointly honor, per envelope. Velle never verifies these guarantees at runtime — it cannot; they concern behavior across processes and stores it never sees. They are **assumptions the static proofs spend**: every analysis that makes a validated spec trustworthy is a theorem with these clauses as premises, and an implementation that breaks a clause silently voids every proof that spends it — the spec stays green while its guarantees are gone. How the engineer delivers the guarantees is entirely theirs: trivial with one database transaction per envelope; real engineering when an envelope spans multiple databases, API calls, and files (distributed-transaction territory Velle stays out of). When low-level code fails mid-envelope, keeping the black-box state coherent is the engineer's job — and the clauses below are the definition of coherent.

**U1 — Snapshot.** Every resolver read within one envelope is answered from a single consistent state: the settled end-state of the transaction serialized immediately before this one (U3). No read ever observes another envelope's intermediate state. The runtime is built against this clause — fetches are memoized for the envelope and never re-issued, and the envelope's own writes are served from its working set, so a resolver that answers two questions from two different moments within one envelope is in breach even if each answer was individually true. A tick's envelope opens a fresh snapshot of settled state (a previous envelope's working set is not a substitute once another process may have written).

**U2 — Atomicity.** The envelope's mutation set — the act's create, every rule-fired create and assign, and the capture channel's upserts and retractions, delivered as one payload — becomes durable as a unit or not at all. An error anywhere inside the envelope (evaluation, the transaction-end `never` check, or the callback's own writes — the callback runs *inside* the envelope) must leave no trace observable through the resolvers, ever. A refusal is earlier still: boundary validation precedes the envelope, and nothing begins.

**U3 — Serialization.** The system behaves as if transactions ran one at a time: the observable history is equivalent to some total order of transactions, each reading (U1) its predecessor's settled end-state, and that order respects real time — an envelope acknowledged before another is submitted serializes before it, which is what last-in-wins *means* (README §12: "the admin's override stands until the customer corrects it again"). Snapshot isolation alone is not sufficient: two concurrent envelopes that each read `not exists DepositApplication for this` and both fire is a double deposit — where one envelope's writes intersect another's reads, they must serialize, and that read-write case is exactly what common weaker isolation levels permit. Whether a per-spec analysis could license weaker isolation for specs that never spend the vulnerable clause is calibration territory in OQ16's family; the contract as stated is the fail-closed default.

**U4 — Permanence.** Once an envelope is reported accepted, its effects are durable: every later snapshot reflects them. Boundary semantics rest here — an `after commit` firing begins only after its triggering transaction is durable, so its own envelope serializes after the trigger's (U3) and sees its effects; and the guard apparatus assumes committed witnesses are never lost — a `DepositApplication` that vanishes is a double-applied deposit at the next backstop tick. Cross-process guards and cross-tick memory are this clause read across runtimes: the data *is* the memory, so losing the data is forgetting the firing happened.

**U5 — No side doors.** Between one transaction's end and the next one's snapshot, Velle-visible state does not change: every mutation to the data behind the resolvers enters as a commit through the generated surface. Reading is free — querying commits nothing (README §4) — but a write that bypasses the commit surface voids every proof over the data it touches: relevance gating spends this clause outright ("an untouched invariant that held at the last transaction end still holds"), and every inductive `never` proof is induction over commits, so a side-door write is an unconsidered case. Legacy data mapped in wasn't born behind the guardrail — README §21 already states the validation obligation at the mapping — and the same caveat generalizes: the contract governs state from the moment it is Velle's.

### What each clause carries

The map from clause to the proofs that spend it — what breaks, specifically, when a clause is violated:

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
