# OQ36 — The universal-transaction contract, stated precisely

**Status:** open — the guarantees are relied on everywhere and written down nowhere normative
**In plain terms:** exactly which guarantees does Velle assume of the engineer's storage and transaction layer? Every correctness proof rests on them.
**Opened by:** `investigate_runtime.md` §2 (re-homed here)

---

Velle assumes one "universal" transaction per envelope: resolver reads see a consistent snapshot; the mutation set — capture channel included — lands atomically; and concurrent commits are serialized against conflicting state (`investigate_runtime.md` §2, §3, §6, §7). The engineer realizes the guarantee — trivially with one DB transaction, with real work when an envelope spans multiple DBs, API calls, and files (distributed-transaction territory Velle stays out of). If the low-level code hits an error, keeping the black-box state of the Velle system coherent is the engineer's job.

What's owed: the contract stated precisely in the normative docs, because proofs rest on it — the confluence and one-writer analyses (OQ16), relevance gating's soundness argument ("an untouched invariant that held at the last transaction end still holds," which needs "state changes only through commits"), and the capture channel's atomicity. How the engineer delivers the guarantees is theirs; *what* the guarantees are must be pinned.
