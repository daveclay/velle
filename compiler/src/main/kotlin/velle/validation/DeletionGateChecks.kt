package velle.validation

import velle.*

// ── V27: the deletion gate, `undeletable` (OQ37-R1/R9) ───────────────────
//
// `undeletable` scopes deletion permission to a state exactly as `frozen`
// scopes write permission — the same fail-closed disjointness proof
// (DeleteScope.kt), the same fix-it idiom (partition the deleter onto the
// deletable subset; refusal lands as data). A gate no rule could trip is
// dead machinery (A2).

internal fun Validator.checkUndeletable() {
    for ((refinementName, refinement) in model.refinements) {
        if (refinement.members.none { member -> member is UndeletableClause }) continue
        val base = model.baseOf(refinementName) ?: continue
        val gatedSites = model.deleteSites.filter { site -> site.shape == base }
        for (site in gatedSites) {
            if (deleteExcludes(site, refinementName)) continue
            diagnostics.add(Diagnostic("V27", "${deleteSiteLabel(site)}, undeletable while a member of " +
                "'$refinementName' — the trigger does not provably exclude membership; partition the " +
                "deleter onto the deletable subset so refusal lands as data (OQ37; the " +
                "Reference §8 \"Frozen fields\" idiom, aimed at existence)"))
        }
        if (gatedSites.isEmpty() && model.deleteSites.isNotEmpty())
            diagnostics.add(Diagnostic("A2", "'$refinementName' declares `undeletable` but no rule deletes " +
                "'$base' — the gate refuses nothing (dead machinery)", advisory = true))
    }
}
