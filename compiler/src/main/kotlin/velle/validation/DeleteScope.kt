package velle.validation

import velle.*

// ── V23–V28: delete (OQ37), the scope of a delete ────────────────────────
//
// Delete is instance-granularity mutation whose legality is derived from
// what the spec proves *using* the instance's existence — never banned by
// category. The boundary principle (Reference §12 "Deletion";
// checks.md V23–V28): a delete errors when it can break
// something the spec proves or declares — never merely because state
// changes; recalculation is drift, the design working. The disjointness
// prover is deliberately the shared refinement-overlap one — syntactic
// conjunct complements — and nothing finer (ruled conservative,
// 2026-08-14): no window arithmetic, no lifetime analysis. The accepted
// false positive's discharge is restructure, never a signature (guard
// re-arming is not `tolerates`-signable — ruled, 2026-08-14).
//
// This file holds what the delete checks share: which records a delete
// can reach, and how a diagnostic names the delete.

/** Whether [target] is `this` itself, the deleting rule's own subject. */
internal fun isTheSubjectItself(target: PathExpr): Boolean = target.root == "this" && target.segs.isEmpty()

/** The conjuncts provably constraining which instances a delete site can
 *  target: for `delete this`, the deleting rule's own condition; for a
 *  path target, the predicates of every refinement the condition asserts
 *  of that path (`<path> is R` conjuncts). Empty means "any instance". */
private fun Validator.deleteScopeConjuncts(site: DeleteSite): List<Expr> {
    val target = site.stmt.target
    val ruleConditionConjuncts = conditionConjuncts(site.rule.condition)
    if (isTheSubjectItself(target)) return ruleConditionConjuncts
    return ruleConditionConjuncts.flatMap { conjunct -> refinementConjunctsAsserted(conjunct, target) }
}

/** The predicate conjuncts of the refinement [conjunct] asserts of [target] (`<target> is R`), or none. */
private fun Validator.refinementConjunctsAsserted(conjunct: Expr, target: PathExpr): List<Expr> {
    val isTest = conjunct as? IsExpr ?: return emptyList()
    if (isTest.kind != "refinement" || isTest.subject != target) return emptyList()
    return isTest.refinement?.let { refinementName -> refPredicateConjuncts(RefName(refinementName)) } ?: emptyList()
}

/** The shared prover, aimed at deletion: the delete scope provably excludes
 *  membership in [name] when a scope conjunct is the syntactic complement
 *  of one of [name]'s predicate conjuncts. A base shape has no conjuncts,
 *  so nothing excludes it — fail closed. */
internal fun Validator.deleteExcludes(site: DeleteSite, name: String): Boolean {
    val membershipConjuncts = refPredicateConjuncts(RefName(name)) ?: return false
    if (membershipConjuncts.isEmpty()) return false
    val scopeConjuncts = deleteScopeConjuncts(site)
    // the explicit spelling: a `not (<target path> is Name)` conjunct on the condition
    val conditionSaysNotMember = conditionConjuncts(site.rule.condition)
        .any { conjunct -> conjunct == NotExpr(IsExpr(site.stmt.target, "refinement", name)) }
    return conditionSaysNotMember || scopeConjuncts.any { scopeConjunct ->
        membershipConjuncts.any { membershipConjunct -> areComplements(scopeConjunct, membershipConjunct) }
    }
}

/** Whether one of [first] and [second] is `not` the other, spelled exactly. */
private fun areComplements(first: Expr, second: Expr): Boolean = first == NotExpr(second) || second == NotExpr(first)

/** Does this rule's body delete its own subject? Deleting `this` leaves
 *  every trigger state — the structural disarm (V2/V16). */
internal fun Validator.deletesSubject(rule: RuleDecl): Boolean =
    rule.body.any { it is DeleteStmt && it.target == PathExpr("this") }

/** How a diagnostic names a delete: the rule, the shape, and the statement as written. */
internal fun Validator.deleteSiteLabel(site: DeleteSite) =
    "rule '${site.rule.name}' deletes a '${site.shape}' ('delete ${Printer.expr(site.stmt.target)}')"
