# OQ34 — Schedule definition (post-v0 re-derivation)

**Status:** deferred — post-v0; README §22 carries the item, two details lived only in a TODO paragraph and now live here
**In plain terms:** how a spec defines when ticks happen — including timeouts anchored to an event and durations read from data rather than written as literals.

---

README §22 has the item. When the construct lands it defines *when* the tick functions are called, never what they do — schedule names already transpile to tick functions the engineer's real scheduler calls (`investigate_runtime.md` §1). Two details beyond the §22 item:

- **Event-anchored timeouts** — working sketch: `via schedule <duration> after <Shape>`.
- **Data-derived durations** — not just literals: `escalatedTo.role.timeoutMinutes`; the grammar position must accept an expression.

Adjacent but distinct: bootstrap/backfill (OQ28) is about first deployment against pre-existing state, not tick cadence.
