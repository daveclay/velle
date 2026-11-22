package velle.validation

import velle.*

// ── refinement mentions (the definition graph's refinement edges, and
//    "which refinements does this rule watch" for V15) ────────────────────

/** The refinements [refinementExpr] names, directly or inside a `where`. */
internal fun Validator.refinementsMentionedIn(refinementExpr: RefExpr): Set<String> =
    mutableSetOf<String>().also { mentioned -> collectRefinementMentions(refinementExpr, mentioned) }

/** The refinements [expr] names: read as a path root, narrowed to with `is`, or queried. */
internal fun Validator.refinementsMentionedIn(expr: Expr): Set<String> =
    mutableSetOf<String>().also { mentioned -> collectRefinementMentions(expr, mentioned) }

private fun Validator.collectRefinementMentions(refinementExpr: RefExpr, mentioned: MutableSet<String>) {
    when (refinementExpr) {
        is RefName -> {
            if (refinementExpr.name in model.refinements) mentioned.add(refinementExpr.name)
            collectRefinementMentions(refinementExpr.where, mentioned)
        }
        is RefNot -> collectRefinementMentions(refinementExpr.inner, mentioned)
        is RefAnd -> { collectRefinementMentions(refinementExpr.left, mentioned); collectRefinementMentions(refinementExpr.right, mentioned) }
        is RefOr -> { collectRefinementMentions(refinementExpr.left, mentioned); collectRefinementMentions(refinementExpr.right, mentioned) }
    }
}

private fun Validator.collectRefinementMentions(expr: Expr?, mentioned: MutableSet<String>) {
    expr ?: return
    fun add(name: String?) { if (name != null && name in model.refinements) mentioned.add(name) }
    when (expr) {
        is PathExpr -> add(expr.root)
        is UnaryMinus -> collectRefinementMentions(expr.inner, mentioned)
        is Binary -> { collectRefinementMentions(expr.left, mentioned); collectRefinementMentions(expr.right, mentioned) }
        is NotExpr -> collectRefinementMentions(expr.inner, mentioned)
        is IfExpr -> {
            collectRefinementMentions(expr.condition, mentioned)
            collectRefinementMentions(expr.thenExpr, mentioned)
            collectRefinementMentions(expr.elseExpr, mentioned)
        }
        is Interpolation -> expr.pieces.forEach { piece -> collectRefinementMentions(piece, mentioned) }
        is IsExpr -> { add(expr.refinement); collectRefinementMentions(expr.subject, mentioned) }
        is ExistsExpr -> {
            add(expr.shape)
            collectRefinementMentions(expr.forExpr, mentioned)
            expr.collection?.let { collection -> collectRefinementMentions(collection, mentioned) }
        }
        is AggCall -> collectRefinementMentions(expr.collection, mentioned)
        is FunCall -> expr.args.forEach { argument -> collectRefinementMentions(argument, mentioned) }
        is SingularFor -> { add(expr.shape); collectRefinementMentions(expr.forExpr, mentioned) }
        is ShapeForSource -> { add(expr.shape); collectRefinementMentions(expr.forExpr, mentioned) }
        is SetExpr -> collectRefinementMentions(expr.collection, mentioned)
        is WalkExpr -> collectRefinementMentions(expr.until, mentioned)
        else -> {}
    }
}

private fun Validator.collectRefinementMentions(collection: CollectionExpr, mentioned: MutableSet<String>) {
    collection.bindings.forEach { binding -> collectRefinementMentions(binding.source, mentioned) }
    collectRefinementMentions(collection.where, mentioned)
}

/** Refinements whose membership this rule's condition depends on, closed
 *  transitively over refinement definitions: if the condition mentions T
 *  and T's definition mentions R, a flip of R flips T. */
internal fun Validator.watchedRefinements(rule: RuleDecl): Set<String> = watchedRefinementsByRuleName.getOrPut(rule.name) {
    val watched = mutableSetOf<String>()
    val pending = ArrayDeque(refinementsMentionedIn(rule.condition))
    while (pending.isNotEmpty()) {
        val refinement = pending.removeFirst()
        if (watched.add(refinement)) pending.addAll(refinementMentionGraph[refinement].orEmpty())
    }
    watched
}

internal fun Validator.buildRefinementMentionGraph(): Map<String, Set<String>> =
    model.refinements.mapValues { (_, refinement) -> refinementsMentionedIn(refinement.expr) }
