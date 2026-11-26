# OQ28 — Bootstrap/backfill triggers

**Status:** open — not designed
**In plain terms:** when a rule is added to a live system, what makes it apply once to each already-existing instance it would have matched?

---

"For each *existing* member, once, immediately" when a rule is added to a live system; deferred by the retired `example_rules.md` and previously tracked only as a TODO line. Adjacent to schedule definition (OQ34) and the tick law, but distinct: it is about first deployment against pre-existing state, where entry commits already happened before the rule existed.

## Work items

Moved from OQ170 on 2026-11-24. Each pins what the runtime does today when a running system's records meet an edited spec. The runtime looks for a record entering or leaving a refinement only when a commit changes data the condition reads (`compiler/src/main/kotlin/velle/ConditionWatchers.kt`, `canChangeUnder`; Reference §11), so a record that becomes a member because the spec changed has no commit that made it one.

- [ ] Write a test that starts a system on `examples/billing/billing.velle`, stores an archived overdue invoice and a paid invoice, restarts it over the same store on a spec where `ActionableOverdue` is `OverdueInvoice and not IssuedInvoice` and the condition of `PaidInvoice` is rewritten, and commits to each record, pinning whether rule `SendReceipt` (fires on entering `PaidInvoice`) and rule `NoteUnarchival` (fires on leaving `ArchivedInvoice`) fire for a record whose membership the spec change alone altered, and what a value captured at entry holds for it. A lead, read from the code on 2026-11-26 and tested by nothing: `compiler/src/main/kotlin/velle/CommitProcessor.kt` takes each refinement's members before and after a commit, both under the spec that is running, and fires entry and exit rules only for the difference, so a record that already meets an edited condition would be a member before its next commit and would never be seen entering; a rule that also runs on a schedule would still reach it at a tick. What happens at the restart itself was not read.
- [ ] Write the same kind of test for a constraint added over stored records that break it (are they found, is the next commit touching one refused) and for a required field added to a stored shape (what value the stored records have for it; OQ154 covers the same record reached by a row changed outside Velle).
