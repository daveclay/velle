package velle.validation

import velle.*

// ── rule disjointness: can two rules never hold for one subject at once? ─
//
// Two rules are provably disjoint when no commit kind affects both of their
// conditions (distinct bases), or when one condition asserts a conjunct the
// other negates. The conjunct lists here are also what the guard, freeze,
// and co-firing analyses compare.

internal fun Validator.firesOnCommit(rule: RuleDecl) =
    rule.preposition == null || "commit" in rule.triggers

/** The rules that run inside the act's own transaction: fired at its commit, not after it. */
internal fun Validator.onCommitRules(): List<RuleDecl> =
    model.rules.values.filter { it.preposition != "after" && firesOnCommit(it) }

/** Can one commit fire both rules? Coarse: any commit kind affecting both. */
private fun Validator.canShareCommit(first: RuleDecl, second: RuleDecl): Boolean =
    possibleCommits.any { commit -> affects(commit, first) && affects(commit, second) }

internal fun Validator.provablyDisjoint(first: RuleDecl, second: RuleDecl): Boolean {
    val firstBases = model.basesOfExpr(first.condition)
    val secondBases = model.basesOfExpr(second.condition)
    if (firstBases.isEmpty() || secondBases.isEmpty()) return false
    // distinct bases are disjoint by construction; a union overlaps each operand (Reference §9)
    if (firstBases != secondBases) return !(canShareCommit(first, second))
    val firstConjuncts = conditionConjuncts(first.condition)
    val secondConjuncts = conditionConjuncts(second.condition)
    // syntactic complement: one side asserts P, the other `not P`
    return firstConjuncts.any { firstConjunct ->
        secondConjuncts.any { secondConjunct ->
            secondConjunct == NotExpr(firstConjunct) || firstConjunct == NotExpr(secondConjunct)
        }
    }
}

/** The conjuncts a condition requires: a refinement's inherited ones, then its own `where`'s. */
internal fun Validator.conditionConjuncts(condition: RefExpr): List<Expr> = when (condition) {
    is RefName -> {
        val inherited = model.refinements[condition.name]?.let { conditionConjuncts(it.expr) } ?: emptyList()
        inherited + (condition.where?.let { predicateConjuncts(it) } ?: emptyList())
    }
    is RefAnd -> conditionConjuncts(condition.left) + conditionConjuncts(condition.right)
    else -> emptyList()
}

internal fun Validator.predicateConjuncts(predicate: Expr): List<Expr> =
    if (predicate is Binary && predicate.op == "and") predicateConjuncts(predicate.left) + predicateConjuncts(predicate.right)
    else listOf(predicate)
