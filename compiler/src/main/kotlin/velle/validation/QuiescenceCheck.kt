package velle.validation

import velle.*

// ── V16: quiescence (condition-graph cycles broken by disarms) ───────────

internal fun Validator.checkQuiescence() {
    val commitRules = model.rules.values.filter { rule -> firesOnCommit(rule) || rule.preposition == "after" }
    val rulesFiredBy = commitRules.associateWith { rule -> rulesFiredByCommitsOf(rule, commitRules) }
    for (cascade in cyclesIn(rulesFiredBy)) {
        if (!cascadeBroken(cascade)) diagnostics.add(Diagnostic("V16", cascadeMessage(cascade)))
    }
}

/** The [commitRules] a commit that [rule] produces can make fire. */
private fun Validator.rulesFiredByCommitsOf(rule: RuleDecl, commitRules: List<RuleDecl>): List<RuleDecl> {
    val produced = possibleCommits.filter { commit -> commit.byRule === rule }
    return commitRules.filter { fired -> produced.any { commit -> affects(commit, fired) } }
}

/** Some rule of [cascade] makes its own condition false: a guard its body disarms, or it deletes its subject. */
private fun Validator.cascadeBroken(cascade: List<RuleDecl>): Boolean =
    cascade.any { rule -> guardAtoms(rule).any { atom -> disarmed(rule, atom) } || deletesSubject(rule) }

/** V16: rules that each fire from what the one before creates, with no guard among them. */
private fun Validator.cascadeMessage(rules: List<RuleDecl>): String {
    val names = rules.map { rule -> "'${rule.name}'" }
    val fix = "so it fires once per record (Reference §18)"
    return when (rules.size) {
        1 -> "rule ${names.single()} fires from what it creates itself, round and round, and never makes " +
            "its own condition false; guard it $fix"
        2 -> "rules ${names[0]} and ${names[1]} each fire from what the other creates, round and round, " +
            "and neither makes its own condition false; guard one of them $fix"
        else -> "rules ${names.dropLast(1).joinToString(", ")}, and ${names.last()} each fire from what the " +
            "one before creates, round and round, and none makes its own condition false; guard one of them $fix"
    }
}
