package velle.validation

import velle.*

// ── V26 and V29: what joins a `never`'s inductive proof ──────────────────
//
// A constraint or guarantee holds after every commit only if every kind of
// state change keeps it. V26 adds the deleters (OQ37); V29 adds the
// firings with no caller. A refusal, a prohibition over a transient act
// (Reference §21), binds the caller at the request and nothing afterwards:
// no act instance exists at a tick or behind the after-commit boundary for
// the firing to leave in the forbidden state, so neither check reads it.

/** The base shape [never] forbids records of, null when it has none. */
private fun Validator.forbiddenBase(never: NeverDecl): String? = model.baseOfExpr(never.target)

/** Whether [never] is a refusal: a prohibition over a transient act. */
internal fun Validator.isRefusal(never: NeverDecl): Boolean =
    forbiddenBase(never)?.let { base -> base in model.transients } == true

/** The base shapes [summary] reads. */
private fun Validator.consultedBases(summary: ReadSummary): Set<String> =
    summary.touchedShapes().mapNotNull { shape -> model.baseOf(shape) }.toSet()

// V26 — deleters join every invariant's inductive proof (OQ37): deleting
// an instance of the `never`'s own base only shrinks the forbidden set —
// safe; deleting an instance of any *other* shape the predicate consults
// can flip membership either way, and v0 cannot prove the invariant holds
// across it — fail closed.
internal fun Validator.checkNeverDeleters() {
    if (model.deleteSites.isEmpty()) return
    for (never in model.nevers) {
        if (isRefusal(never)) continue
        val summary = model.summaryOfRefExpr(never.target)
        val consultedOtherBases = consultedBases(summary) - setOfNotNull(forbiddenBase(never))
        for (site in model.deleteSites) {
            if (site.shape !in consultedOtherBases && !summary.opaque) continue
            diagnostics.add(Diagnostic("V26", "${deleteSiteLabel(site)}, and the '${never.keyword}' " +
                "(${Printer.prohibitionBody(never)}) reads '${site.shape}', so the delete could leave the books " +
                "in the forbidden state, which the compiler cannot rule out; condition '${site.rule.name}' " +
                "so it cannot, or put the books right in the same commit (Reference §21)"))
        }
    }
}

// V29 — a firing with no caller joins every constraint's inductive proof.
// A rule on a schedule, or behind the `after commit` boundary, fires in a
// transaction of its own with nobody asking: a constraint that rejects
// the firing has no caller to answer, the rule's guard never disarms, and
// the same subject is refused at every tick. The spec instructs and
// forbids the same record. A record the firing creates whose shape the
// prohibition consults (its own base included: creation can grow the
// forbidden set, where deletion only shrinks it, V26) is the case, and v0
// cannot prove the rule's condition excludes it — fail closed, the V10
// posture. A commit-triggered rule is not here: its rejection answers the
// act that triggered it, and the caller is told (Reference §21).
internal fun Validator.checkUncalledCreations() {
    for (never in model.nevers) {
        if (isRefusal(never)) continue
        val summary = model.summaryOfRefExpr(never.target)
        val consultedBasesWithOwn = consultedBases(summary) + setOfNotNull(forbiddenBase(never))
        for (rule in model.rules.values) checkUncalledCreationsOf(rule, never, consultedBasesWithOwn, summary.opaque)
    }
}

/** The schedules [rule] fires on, empty for a rule fired only by a commit. */
private fun schedulesOf(rule: RuleDecl): List<String> = rule.triggers.filter { trigger -> trigger != "commit" }

/** V29 for one [rule]: each record it creates with nobody asking whose base [never] reads. */
private fun Validator.checkUncalledCreationsOf(rule: RuleDecl, never: NeverDecl, consultedBases: Set<String>, readsOpaquely: Boolean) {
    val schedules = schedulesOf(rule)
    val firesWithNoCaller = schedules.isNotEmpty() || rule.preposition == "after"
    if (!firesWithNoCaller) return
    for (creation in rule.body.statements().filterIsInstance<Creation>()) {
        val createdBase = model.baseOf(creation.shape) ?: creation.shape
        if (createdBase !in consultedBases && !readsOpaquely) continue
        val moment = if (schedules.isNotEmpty()) "on ${schedules.joinToString(", ")}" else "after commit"
        diagnostics.add(Diagnostic("V29", "rule '${rule.name}' creates a '${creation.shape}' $moment with nobody " +
            "asking, and the '${never.keyword}' (${Printer.prohibitionBody(never)}) reads '${creation.shape}', so the " +
            "firing could be refused at every tick with no caller to tell; condition '${rule.name}' so " +
            "it cannot create a '${creation.shape}' the prohibition forbids, put the books right within the " +
            "firing, or retract the prohibition (Reference §21)"))
    }
}
