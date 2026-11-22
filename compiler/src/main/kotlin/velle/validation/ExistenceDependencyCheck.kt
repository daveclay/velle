package velle.validation

import velle.*

// ── V25: existence-dependency (OQ37) ─────────────────────────────────────
//
// Existence is spent in proofs (OQ37's genuinely new check). Two
// v0 slices: (a) guard witnesses — a `not exists W` conjunct on any rule's
// condition makes W's records load-bearing: deleting one re-arms the guard
// and the rule re-fires (the double-apply hazard); (b) singularity proofs —
// a `(R for ...)` reference relies on the member the guard maintains;
// deleting it mid-episode strands the reference. Discharge is the shared
// disjointness prover only (DeleteScope.kt); not `tolerates`-signable
// (ruled): intentional re-triggering is reversal-as-data, cleanup is
// provable disjointness or OQ27 retention.

internal fun Validator.checkExistenceDependency() {
    if (model.deleteSites.isEmpty()) return
    for (rule in model.rules.values) {
        for (conjunct in conditionConjuncts(rule.condition)) {
            val witness = notExistsWitness(conjunct) ?: continue
            checkDeletesOfGuardWitness(rule, witness)
        }
    }
    forEachSpecExpr { spot, expr ->
        if (expr is SingularFor) checkDeletesOfSingularRead(spot, expr)
    }
}

/** The shape or refinement W that a `not exists W ...` [conjunct] reads, or null for any other conjunct. */
private fun Validator.notExistsWitness(conjunct: Expr): String? {
    val existsTest = (conjunct as? NotExpr)?.inner as? ExistsExpr ?: return null
    existsTest.shape?.let { shape -> return shape }
    val source = existsTest.collection?.bindings?.firstOrNull()?.source as? PathExpr ?: return null
    val namesShape = source.segs.isEmpty() && (source.root in model.shapes || source.root in model.refinements)
    return source.root.takeIf { namesShape }
}

/** (a) A delete of a [witness] record that [rule]'s `not exists` guard reads re-arms the guard. */
private fun Validator.checkDeletesOfGuardWitness(rule: RuleDecl, witness: String) {
    val base = model.baseOf(witness) ?: return
    for (site in model.deleteSites) {
        if (site.shape != base) continue
        if (deleteExcludes(site, witness)) continue
        diagnostics.add(Diagnostic("V25", "${deleteSiteLabel(site)}, and rule '${rule.name}' fires only while " +
            "none exists ('not exists $witness ...'), so the delete would make '${rule.name}' fire " +
            "again for the same record; keep the evidence of '${rule.name}' as a field on the record " +
            "instead, condition '${site.rule.name}' so it never deletes one the guard reads, or " +
            "record the reversal as data if the rule is meant to fire again (Reference §18)"))
    }
}

/** (b) A delete of the one record a `(R for ...)` read at [spot] relies on strands the read. */
private fun Validator.checkDeletesOfSingularRead(spot: String, singularRead: SingularFor) {
    val base = model.baseOf(singularRead.shape) ?: return
    for (site in model.deleteSites) {
        if (site.shape != base) continue
        if (singularRead.shape != base && deleteExcludes(site, singularRead.shape)) continue
        diagnostics.add(Diagnostic("V25", "${deleteSiteLabel(site)}, and $spot reads '(${singularRead.shape} for ...)' as " +
            "the one record, so the delete could remove it while the read still needs it; condition " +
            "'${site.rule.name}' so it deletes only records that are not in '${singularRead.shape}', or keep " +
            "the record (Reference §12)"))
    }
}
