package velle.validation

import velle.*

// ── V8: folds ────────────────────────────────────────────────────────────

internal fun Validator.checkFolds() {
    for (write in writes) {
        val fold = foldAssignment(write) ?: continue
        val facts = foldFacts(write.member.type, fold.value)
        if (facts?.idempotent == true) continue
        val toleration = (model.shapes[write.owner]?.members
            ?.filterIsInstance<StoredProp>()?.find { field -> field.name == write.member.name })?.tolerates
        checkFoldFiresOnce(write, toleration)
        checkFoldOrder(write, facts, toleration)
    }
}

/** The assignment behind [write] when its value reads the field it writes, a fold; null otherwise. */
private fun Validator.foldAssignment(write: Write): Assignment? {
    val scope = subjectScope(write.rule) ?: return null
    val assignment = write.rule.body.filterIsInstance<Assignment>().find { assignment -> assignment.target == write.target } ?: return null
    val valueReads = ReadSummary().also { summary -> model.collectExpr(assignment.value, scope, summary) }
    return assignment.takeIf { (write.owner to write.member.name) in valueReads.fields }
}

/**
 * The step's two facts (Reference §19): `max`/`min` are idempotent and commute for any
 * ordered type; `+`/`-` commute; on a `many` they are also idempotent (adding a value
 * twice is adding it once) unless it `tolerates duplication`, where `+` appends, so
 * the order of steps shows and a repeated step shows; an extension type's operator
 * declares its own (OQ65).
 */
private fun Validator.foldFacts(fieldType: VType, step: Expr): TypeRegistry.FoldFacts? = when (step) {
    // a function's own two claims (`velle.ext.VelleFunction`): `max`/`min` commute and repeat harmlessly
    is FunCall -> model.functionScope[step.name]?.let { function ->
        TypeRegistry.FoldFacts(commutes = function.foldStepsCommute, idempotent = function.foldIdempotent)
    }
    is Binary -> extOf(fieldType)?.let { extensionType -> model.registry.foldFacts(extensionType.qualifiedName, step.op) }
        ?: builtinOperatorFacts(fieldType, step.op)
    else -> null
}

private fun builtinOperatorFacts(fieldType: VType, op: String): TypeRegistry.FoldFacts? = when {
    op !in setOf("+", "-") -> null
    fieldType is VType.CollS && fieldType.ordered -> TypeRegistry.FoldFacts(commutes = false, idempotent = false)
    fieldType is VType.CollS || fieldType is VType.Coll -> TypeRegistry.FoldFacts(commutes = true, idempotent = true)
    else -> TypeRegistry.FoldFacts(commutes = true, idempotent = false)
}

/** A fold whose rule can fire twice for one record folds that record in twice, unless the field tolerates duplication. */
private fun Validator.checkFoldFiresOnce(write: Write, toleration: String?) {
    val rule = write.rule
    val onSchedule = rule.triggers.any { trigger -> trigger != "commit" }
    val guarded = guardAtoms(rule).any { atom -> disarmed(rule, atom) }
    val duplicationExposed = (rule.preposition == "after" || onSchedule) && !guarded
    if (duplicationExposed && toleration != "duplication")
        diagnostics.add(Diagnostic("V8", "'${write.owner}.${write.member.name}' is computed from its own value by rule " +
            "'${rule.name}', which can fire more than once for one record, so the same record could be " +
            "folded in twice; guard the rule so it fires once per record (Reference §18), derive " +
            "'${write.member.name}' from the records instead, add a scheduled rule that recomputes it, " +
            "or declare '${write.member.name}' with 'tolerates duplication' (Reference §19)"))
}

/** A fold on a schedule meets its records in no order, so its steps must commute, unless the field tolerates reordering. */
private fun Validator.checkFoldOrder(write: Write, facts: TypeRegistry.FoldFacts?, toleration: String?) {
    val onSchedule = write.rule.triggers.any { trigger -> trigger != "commit" }
    // +/- folds commute with each other ((x-a)-b == (x-b)-a); only genuinely
    // order-dependent forms (streaks, resets) fail this
    val orderSafe = facts?.commutes == true
    if (onSchedule && !orderSafe && toleration != "reordering")
        diagnostics.add(Diagnostic("V8", "'${write.owner}.${write.member.name}' is computed from its own value by rule " +
            "'${write.rule.name}', which runs on a schedule, and the records one run handles have no order, " +
            "so the result depends on which is folded in first; compute it from the records in order, " +
            "as 'streakAfter' does from its predecessor in Reference §19, or declare " +
            "'${write.member.name}' with 'tolerates reordering'"))
}
