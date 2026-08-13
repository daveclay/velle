# OQ28 — Bootstrap/backfill triggers

**Status:** open — not designed
**In plain terms:** when a rule is added to a live system, what makes it apply once to each already-existing instance it would have matched?

---

"For each *existing* member, once, immediately" when a rule is added to a live system; deferred by the retired `example_rules.md` and previously tracked only as a TODO line. Adjacent to schedule definition (OQ34) and the tick law, but distinct: it is about first deployment against pre-existing state, where entry commits already happened before the rule existed.
