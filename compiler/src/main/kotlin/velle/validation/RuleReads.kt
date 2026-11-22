package velle.validation

import velle.*

// ── rule reads: what a rule's condition and body read ────────────────────

/** Everything a rule reads: its condition, and the values and routes its body evaluates. */
internal fun Validator.ruleReads(rule: RuleDecl): ReadSummary = ruleReadsByRuleName.getOrPut(rule.name) {
    val reads = ReadSummary()
    val scope = subjectScope(rule)
    if (scope != null) {
        collectConditionReads(rule, scope, reads)
        val aliases = ruleAliases(rule)
        rule.body.statements().flatMap { statementReadExpressions(it) }
            .forEach { model.collectExpr(it, scope, reads, aliases) }
    }
    reads
}

private fun Validator.collectConditionReads(rule: RuleDecl, scope: String, reads: ReadSummary) {
    val condition = rule.condition
    if (condition !is RefName) {
        collectComposite(condition, reads)
        return
    }
    if (condition.name in model.refinements) reads.absorb(model.predicateSummary(condition.name))
    condition.where?.let { model.collectExpr(it, scope, reads, ruleAliases(rule)) }
}

/** The expressions a body statement evaluates: an assigned value, a
 *  creation's field values, a loop's sources, a delete's target route. */
internal fun statementReadExpressions(statement: BodyItem): List<Expr> = when (statement) {
    is Assignment -> listOf(statement.value)
    is Creation -> statement.fields.map { it.value }
    is ForEach -> statement.sourceExprs()
    is DeleteStmt -> listOf(statement.target)
    ThenMarker -> emptyList()
}

/** Collects what a composite condition reads into [reads]: each named
 *  refinement's predicate, and each `where` over the scope it names. */
internal fun Validator.collectComposite(condition: RefExpr, reads: ReadSummary) {
    when (condition) {
        is RefName -> collectRefNameReads(condition, reads)
        is RefNot -> collectComposite(condition.inner, reads)
        is RefAnd -> { collectComposite(condition.left, reads); collectComposite(condition.right, reads) }
        is RefOr -> { collectComposite(condition.left, reads); collectComposite(condition.right, reads) }
    }
}

private fun Validator.collectRefNameReads(refName: RefName, reads: ReadSummary) {
    if (refName.name in model.refinements) reads.absorb(model.predicateSummary(refName.name))
    val where = refName.where ?: return
    // the predicate reads the named scope's members: over a union, what every operand shares (Reference §9)
    val whereScope =
        if (refName.name in model.shapes || refName.name in model.refinements) refName.name
        else model.baseOfExpr(refName) ?: return
    model.collectExpr(where, whereScope, reads)
}

internal fun Validator.conditionSummary(rule: RuleDecl): ReadSummary = conditionSummaryByRuleName.getOrPut(rule.name) {
    val reads = ReadSummary()
    collectComposite(rule.condition, reads)
    reads
}
