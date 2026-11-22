package velle.validation

import velle.*

// ── value operands: the expressions a computed value is made from ────────

/** The operands of an expression that computes its value from them alone:
 *  arithmetic, logic, a branch, a text's pieces, a function's arguments.
 *  Empty for a literal, a path, and every expression that reads other
 *  records (`exists`, aggregates, selectors, accesses, walks); the walks
 *  over expressions treat those themselves. */
internal fun valueOperands(expr: Expr): List<Expr> = when (expr) {
    is Binary -> listOf(expr.left, expr.right)
    is NotExpr -> listOf(expr.inner)
    is UnaryMinus -> listOf(expr.inner)
    is IfExpr -> listOf(expr.condition, expr.thenExpr, expr.elseExpr)
    is Interpolation -> expr.pieces
    is FunCall -> expr.args
    else -> emptyList()
}
