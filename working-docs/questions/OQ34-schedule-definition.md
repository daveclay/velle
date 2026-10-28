# OQ34 — Schedule definition (post-v0 re-derivation)

**Status:** deferred — post-v0; Reference §22 carries the item, two details lived only in a TODO paragraph and now live here
**In plain terms:** how a spec defines when ticks happen — including timeouts anchored to an event and durations read from data rather than written as literals.

---

Reference §22 has the item. When the construct lands it defines *when* the tick functions are called, never what they do — schedule names already transpile to tick functions the engineer's real scheduler calls (Reference §22). Two details beyond the §22 item:

- **Event-anchored timeouts** — working sketch: `via schedule <duration> after <Shape>`.
- **Data-derived durations** — not just literals: `escalatedTo.role.timeoutMinutes`; the grammar position must accept an expression. Both realistic specs reached for it (peptides S13, pharmacy P12): a collection task's due date was meant to be seven days after treatment from a protocol's interval, and `treatedAt + interval days` is not grammatical, since a duration takes a literal count only (`grammar.md`, DurationLiteral); so the spec cannot say "collect every N days after treatment" with N as data, and the admin states each due date instead, the interval living in whatever tool the admin uses. Pharmacy's auto-refill cannot say "when the days' supply is nearly used up" and says twenty-five days. The task shape is arguably the better model (a due date is a decision, not a formula), but the limitation decided it.

- **A missed run** (from OQ71 D4, 2026-10-28) — whether a run that did not happen, because nothing was running at the moment, is owed and made up at the next start. Dave's direction: this is what the schedule means, so the spec says it, not a deployment property. Measuring "missed" needs the cadence (the schedule's next moment after the latest `Tick` row, Reference §17, against the clock), so it lands with the cadence here. Until then the starter does nothing and an engineer ticks by hand at startup.

Adjacent but distinct: bootstrap/backfill (OQ28) is about first deployment against pre-existing state, not tick cadence.
