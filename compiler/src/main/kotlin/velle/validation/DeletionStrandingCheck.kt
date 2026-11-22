package velle.validation

import velle.*

// ── V28: deletion stranding, the V17 mirror (OQ37) ───────────────────────
//
// Transaction stranding: nothing after the deleting transaction's close
// may read the deleted instance. A `when leaving` rule with an `after
// commit` boundary or a tick backstop can be handed a deletion's leaver —
// a subject that does not survive to its firing. Durable reactions hang
// off the outcome record the deleting commit produced.

internal fun Validator.checkDeleteStranding() {
    for (rule in model.rules.values) {
        if (!rule.leaving) continue
        if (!firesAfterTheCommit(rule)) continue
        // the deletion-side forms are transaction-bound by construction
        // (OQ37-R14): the deleted subject does not survive to a later firing,
        // and a record kept to be read later is the deletion memory R5 forbids
        if (rule.trigger.onDeletion) reportDeletionSideFiringLater(rule)
        else checkDeletersStrandingLeaver(rule)
    }
}

/** Whether [rule] fires behind the `after commit` boundary or on a schedule. */
private fun firesAfterTheCommit(rule: RuleDecl): Boolean =
    rule.preposition == "after" || rule.triggers.any { trigger -> trigger != "commit" }

private fun Validator.reportDeletionSideFiringLater(rule: RuleDecl) {
    diagnostics.add(Diagnostic("V28", "rule '${rule.name}' — 'when ${rule.trigger.keyword.trim()}' is " +
        "transaction-bound: the deleted subject does not survive to an after-commit firing or a " +
        "tick (Reference §13). React on commit as a last reader, or hang the durable reaction off " +
        "the outcome record the deleting commit produces"))
}

/** Each delete that can hand the later-firing `when leaving` [rule] a leaver that no longer exists. */
private fun Validator.checkDeletersStrandingLeaver(rule: RuleDecl) {
    if (model.deleteSites.isEmpty()) return
    val base = model.baseOfExpr(rule.condition) ?: return
    val leftRefinement = (rule.condition as? RefName)?.name
    for (site in model.deleteSites) {
        if (site.shape != base) continue
        if (leftRefinement != null && leftRefinement != base && deleteExcludes(site, leftRefinement)) continue
        diagnostics.add(Diagnostic("V28", "${deleteSiteLabel(site)}, and '${rule.name}' reads the " +
            "leaver ${if (rule.preposition == "after") "after commit" else "at a tick"} — a " +
            "deleted instance does not survive the transaction boundary the rule declares " +
            "(OQ37; the V17 mirror). React on-commit as a last reader, or hang the durable " +
            "reaction off the outcome record the deleting commit produces"))
    }
}
