package velle.validation

import velle.*

// ── fresh-instance entry: can a record join a refinement as it is created? ─

/** Can a freshly created base instance be a member of [refinement] at its
 *  own creation commit? Not if the predicate carries a positive `exists`
 *  conjunct correlated to the subject over a shape the same commit does
 *  not create — nothing can reference the fresh instance yet (the V12/V18
 *  family's fresh-instance argument, aimed at consult analysis). */
internal fun Validator.freshCannotEnter(refinement: String, coCreated: Set<String>): Boolean {
    if (refinement !in model.refinements) return false
    return positiveCorrelatedExistsTargets(conditionConjuncts(RefName(refinement)))
        .any { it !in coCreated }
}

/** Targets of positive `exists T for this` / `exists (T where f == this)`
 *  conjuncts — memberships a fresh, not-yet-referencable instance cannot
 *  have. */
internal fun Validator.positiveCorrelatedExistsTargets(conjuncts: List<Expr>): List<String> =
    conjuncts.filterIsInstance<ExistsExpr>() // positive only: `not exists` is a NotExpr
        .filter { isCorrelatedToThis(it) }
        .mapNotNull { existsTargetBase(it) }

private fun isCorrelatedToThis(exists: ExistsExpr): Boolean =
    exists.forExpr == PathExpr("this") ||
        exists.collection?.where?.let { comparesToThis(it) } == true

/** Is [condition] an equality with `this` on one side, or a conjunction holding one? */
private fun comparesToThis(condition: Expr): Boolean = when {
    condition is Binary && condition.op == "and" -> comparesToThis(condition.left) || comparesToThis(condition.right)
    condition is Binary -> condition.op == "==" && (condition.left == PathExpr("this") || condition.right == PathExpr("this"))
    else -> false
}

/** The base of the shape an `exists` quantifies: its sugar shape, else its
 *  collection's first source when that is a bare shape name. */
private fun Validator.existsTargetBase(exists: ExistsExpr): String? =
    exists.shape?.let { model.baseOf(it) } ?: collectionSourceBase(exists.collection)

private fun Validator.collectionSourceBase(collection: CollectionExpr?): String? {
    val source = collection?.bindings?.firstOrNull()?.source as? PathExpr ?: return null
    return if (source.segs.isEmpty()) model.baseOf(source.root) else null
}
