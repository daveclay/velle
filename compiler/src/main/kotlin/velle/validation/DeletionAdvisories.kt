package velle.validation

import velle.*

// ── A6 and A2: advisories on deletion triggers (OQ37-R17/R18/R19) ────────
//
// A6 warns when a delete reaches the state of a bare `when leaving` rule,
// which does not fire at deletion. A2 flags the half of a combined
// trigger, `when leaving not deleted` or `when leaving or deleted`, that
// can never matter. Both read the shared delete scope (DeleteScope.kt).

// A6 (advisory, OQ37-R18) — a deleter reaches a bare `when leaving` rule's
// state. Not an error: the rule's meaning is defined (the predicate flipped,
// never deletion), so nothing is ambiguous — but an author who meant "for
// any reason" gets a rule that silently stops at deletion. The explicit
// forms never draw it: the form is the statement.
internal fun Validator.checkDeleterReachesBareLeaving() {
    if (model.deleteSites.isEmpty()) return
    for (rule in model.rules.values) {
        if (rule.trigger != Trigger.LEAVING) continue
        val base = model.baseOfExpr(rule.condition) ?: continue
        val leftRefinement = (rule.condition as? RefName)?.name
        if (leftRefinement == base) continue // the bare-shape edge is V3's
        adviseOnDeletersReaching(rule, base, leftRefinement)
    }
}

/** An A6 advisory for each delete of a [base] record whose scope does not exclude [leftRefinement]. */
private fun Validator.adviseOnDeletersReaching(rule: RuleDecl, base: String, leftRefinement: String?) {
    val leftState = leftRefinement ?: base
    for (site in model.deleteSites) {
        if (site.shape != base) continue
        if (leftRefinement != null && deleteExcludes(site, leftRefinement)) continue
        diagnostics.add(Diagnostic("A6", "${deleteSiteLabel(site)}, which can reach '$leftState' — the state " +
            "'${rule.name}' leaves. A bare 'when leaving' fires only when the predicate flips, never at " +
            "deletion: if the rule means \"for any reason\", it silently stops there. State the cause — " +
            "'when leaving not deleted' (the transition only), 'when leaving or deleted' (either), or a " +
            "separate 'when deleted' rule — or partition the deleter to provably exclude " +
            "'$leftState' (Reference §13)", advisory = true))
    }
}

// A2 (advisory) — dead trigger halves (OQ37-R17/R19): a `not deleted`
// qualifier where no deleter can reach the state refuses nothing, and the
// `leaving` half of a combined form over a bare shape can never fire.
internal fun Validator.checkDeadTriggerHalves() {
    for (rule in model.rules.values) {
        val base = model.baseOfExpr(rule.condition) ?: continue
        val leftRecords = rule.condition as? RefName
        when (rule.trigger) {
            Trigger.LEAVING_NOT_DELETED -> adviseOnDeadNotDeleted(rule, base, leftRecords)
            Trigger.LEAVING_OR_DELETED -> adviseOnDeadLeavingHalf(rule, base, leftRecords)
            else -> {}
        }
    }
}

/** `when leaving not deleted` where no delete can reach the left state: the qualifier refuses nothing. */
private fun Validator.adviseOnDeadNotDeleted(rule: RuleDecl, base: String, leftRecords: RefName?) {
    val reachingSites = model.deleteSites.filter { site -> site.shape == base && !excludesLeftRefinement(site, base, leftRecords) }
    if (reachingSites.isNotEmpty()) return
    diagnostics.add(Diagnostic("A2", "rule '${rule.name}' says 'not deleted', but no rule's delete " +
        "can reach '${leftRecords?.name ?: base}' — the qualifier refuses nothing (dead machinery); " +
        "plain 'when leaving' means the same here (Reference §13)", advisory = true))
}

/** Whether [site]'s scope excludes the refinement [leftRecords] names, when it names one other than [base]. */
private fun Validator.excludesLeftRefinement(site: DeleteSite, base: String, leftRecords: RefName?): Boolean =
    leftRecords != null && leftRecords.name != base && deleteExcludes(site, leftRecords.name)

/** `when leaving or deleted` over a bare kept shape: there is no predicate to flip, so only deletion fires it. */
private fun Validator.adviseOnDeadLeavingHalf(rule: RuleDecl, base: String, leftRecords: RefName?) {
    if (leftRecords == null || leftRecords.where != null) return
    if (leftRecords.name !in model.shapes || base in model.transients) return
    diagnostics.add(Diagnostic("A2", "rule '${rule.name}' — the 'leaving' half is inert: '$base' is a " +
        "bare shape with no predicate to flip, so only deletion fires this rule; " +
        "'when deleted $base' says the same (dead machinery, Reference §13)", advisory = true))
}
