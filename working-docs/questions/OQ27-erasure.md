# OQ27 — Erasure and retention

**Status:** open — framed, not designed
**In plain terms:** how does a system built on "facts don't un-happen" satisfy real-world obligations to erase data — retention windows, right-to-be-forgotten?
**Opened by:** `investigate-transient.md` (its closing section, moved here)
**See:** README §4 (no delete primitive) · `investigate_runtime.md` §7 (capture retraction — the contract's one sanctioned delete, for memory, not records)

---

Velle's "delete has no primitive" stance (README §4) is about *description*: facts don't un-happen, and a spec that erases records lies. But real systems owe **erasure** — retention windows, right-to-be-forgotten — and that obligation is about *storage*, not description: "an edit occurred" can remain true in the model while its payload ceases to be physically retrievable. That places retention/erasure with compilation (the same layer as "which database"), plausibly as declared policy the transpiler enforces (annotations on shapes/fields; crypto-shredding or hard deletion as mechanism). What the language owes is at most the policy vocabulary — and a check that no derivation or guard depends on data the policy allows to vanish.

Transient acts (README §4) solve the *request-payload* slice by construction — a transient act's payload never persists, which is often the GDPR-sensitive part. The durable-record slice (retention on facts the model keeps) remains its own question. Not designed.
