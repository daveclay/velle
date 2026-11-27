package velle.spec

import velle.Binary
import velle.Expr

// ── Conditions, split (Reference §9) ─────────────────────────────────────────
//
// A condition such as `Loan where member.name == "late" and dueOn < today` holds when
// each of its parts joined by `and` holds. Every part of the compiler that reads a
// condition part by part splits it here, so that two parts cannot split it differently.

/** The parts of [condition] joined by `and`, in order; none when there is no condition. */
fun partsJoinedByAnd(condition: Expr?): List<Expr> = partsJoinedBy("and", condition)

/** The parts of [condition] joined by `or`, in order; none when there is no condition. */
fun partsJoinedByOr(condition: Expr?): List<Expr> = partsJoinedBy("or", condition)

private fun partsJoinedBy(operator: String, condition: Expr?): List<Expr> = when {
    condition == null -> emptyList()
    condition is Binary && condition.op == operator -> partsJoinedBy(operator, condition.left) + partsJoinedBy(operator, condition.right)
    else -> listOf(condition)
}
