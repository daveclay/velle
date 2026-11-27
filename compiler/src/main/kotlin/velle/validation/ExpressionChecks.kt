package velle.validation

import velle.*

// ── expressions: names and kinds (checks F1, F2) ────────────────────────────
//
// One walk over an expression, shared by predicates and values. Each kind of
// expression checks its own parts: the names it reads resolve (F1), and the
// values it combines are of kinds that meet (F2). A conjunction, a
// disjunction, and a conditional narrow what their later parts read.

/** Checks [body] with `this` naming a record of [scope]: a walk's condition is asked of each record reached. */
internal inline fun Validator.withThisScope(scope: String, body: () -> Unit) {
    val outer = thisScope
    thisScope = scope
    try { body() } finally { thisScope = outer }
}

/** Name-resolution walk shared by predicates and values (F1, F2 closed list). */
internal fun Validator.checkExpr(expression: Expr, scope: String, allowGenerator: Boolean = false) {
    val outer = thisScope
    if (outer == null) thisScope = scope
    try { checkExprIn(expression, scope, allowGenerator) } finally { thisScope = outer }
}

/** Whether [name] names a shape or a refinement of the spec. */
internal fun Validator.isShapeOrRefinement(name: String): Boolean = name in model.shapes || name in model.refinements

private fun Validator.checkExprIn(expression: Expr, scope: String, allowGenerator: Boolean) {
    when (expression) {
        is PathExpr -> checkPath(expression, scope, allowGenerator)
        is UnaryMinus -> checkExpr(expression.inner, scope)
        is ListLit -> expression.items.forEach { item -> checkExpr(item, scope) }
        is Binary -> checkBinary(expression, scope)
        is ValueLit, is RefLit -> {}
        is Interpolation -> checkPieces(expression, scope)
        is NotExpr -> checkExpr(expression.inner, scope)
        is IfExpr -> checkConditional(expression, scope)
        is IsExpr -> checkIsTest(expression, scope)
        is ExistsExpr -> checkExists(expression, scope)
        is AggCall -> checkAggregate(expression, scope)
        is FunCall -> checkCall(expression, scope)
        is SingularFor -> checkSingularFor(expression, scope)
        is Access -> checkAccess(expression, scope)
        is ShapeForSource -> checkShapeForSource(expression, scope)
        is SetExpr -> checkCollection(expression.collection, scope)
        is WalkExpr -> checkWalk(expression, scope)
        else -> {}
    }
}

/** A path resolves, and each dot after a step that may be absent is licensed (V11). A bare
 *  `randomUUID` is a generator, read where [allowGenerator] says one may stand. */
private fun Validator.checkPath(path: PathExpr, scope: String, allowGenerator: Boolean) {
    if (allowGenerator && path.root == "randomUUID" && path.segs.isEmpty()) return
    resolvePath(path, scope)
    checkDotsLicensed(path, scope)
}

/** Both sides of a binary expression, the right one under what the left shows, then the kinds the operator takes. */
private fun Validator.checkBinary(binary: Binary, scope: String) {
    checkExpr(binary.left, scope)
    checkRightSide(binary, scope)
    checkOperandKinds(binary, scope)
}

private fun Validator.checkRightSide(binary: Binary, scope: String) {
    when (binary.op) {
        // a conjunction narrows what follows it (Reference §10)
        "and" -> withNarrowing(narrowingsOf(binary.left, scope)) {
            withPresence(binary.left, holds = true, scope) { checkExpr(binary.right, scope) }
        }
        // the right side of an `or` is read only where its left side failed (Reference §10)
        "or" -> withNarrowing(complementNarrowingsOf(binary.left, scope)) {
            withPresence(binary.left, holds = false, scope) { checkExpr(binary.right, scope) }
        }
        else -> checkExpr(binary.right, scope)
    }
}

/** The condition, then each branch under what the condition shows where it holds and where it fails. */
private fun Validator.checkConditional(conditional: IfExpr, scope: String) {
    val condition = conditional.condition
    checkExpr(condition, scope)
    withNarrowing(narrowingsOf(condition, scope)) {
        withPresence(condition, holds = true, scope) { checkExpr(conditional.thenExpr, scope) }
    }
    withNarrowing(complementNarrowingsOf(condition, scope)) {
        withPresence(condition, holds = false, scope) { checkExpr(conditional.elseExpr, scope) }
    }
}

private fun Validator.checkIsTest(test: IsExpr, scope: String) {
    checkExpr(test.subject, scope)
    if (test.kind == "empty" || test.kind == "notEmpty") checkEmptinessSubject(test, scope)
    if (test.kind == "held") checkHeldSubject(test, scope)
    test.refinement?.let { refinement -> checkRefinementCanHold(test, refinement, scope) }
}

/** `is empty` reads a collection or a text (Reference §10). */
private fun Validator.checkEmptinessSubject(test: IsExpr, scope: String) {
    val subjectType = bareScalarTypeOf(test.subject, scope) ?: return
    if (subjectType == VType.Text || subjectType is VType.Coll || subjectType is VType.CollS) return
    val spelledTest = if (test.kind == "empty") "empty" else "not empty"
    diagnostics.add(Diagnostic("F2", "'${Printer.expr(test)}' — 'is $spelledTest' tests a collection or a text, " +
        "but '${Printer.expr(test.subject)}' is a ${registryName(subjectType) ?: "value of another type"}"))
}

/** `x is R` never holds when the type of `x` and [refinement] have no base shape in common. */
private fun Validator.checkRefinementCanHold(test: IsExpr, refinement: String, scope: String) {
    val refinementBases = model.basesOf(refinement)
    val subjectType = (test.subject as? PathExpr)?.let { subject -> pathTypeName(subject, scope) } ?: return
    if (refinementBases.isEmpty()) return
    val subjectBases = model.basesOf(subjectType)
    if (subjectBases.isEmpty() || (subjectBases intersect refinementBases).isNotEmpty()) return
    diagnostics.add(Diagnostic("F2", "'${Printer.expr(test)}' — a '$subjectType' can never be a " +
        "member of '$refinement' (no base shape in common)"))
}

private fun Validator.checkExists(exists: ExistsExpr, scope: String) {
    exists.forExpr?.let { subject -> checkExpr(subject, scope) }
    exists.shape?.let { shape -> checkExistsShape(exists, shape, scope) }
    exists.collection?.let { collection -> checkCollection(collection, scope) }
}

/** `exists Shape for x`: the shape is known, and a field of it takes the record after `for`. */
private fun Validator.checkExistsShape(exists: ExistsExpr, shape: String, scope: String) {
    if (!isShapeOrRefinement(shape)) {
        diagnostics.add(Diagnostic("F1", "exists names unknown shape '$shape'"))
        return
    }
    exists.forExpr?.let { subject -> checkForTypeMatch(shape, subject, scope, "exists $shape for ...") }
}

private fun Validator.checkAggregate(aggregate: AggCall, scope: String) {
    checkCollection(
        aggregate.collection, scope, sumField = aggregate.field,
        orderBy = aggregate.orderBy.takeIf { aggregate.name == "latest" || aggregate.name == "first" }
    )
    // `sum(values)` with no field reads a `many` of numbers or durations (Reference §5)
    if (aggregate.name == "sum" && aggregate.field == null) checkSumOfValues(aggregate, scope)
}

private fun Validator.checkSumOfValues(sum: AggCall, scope: String) {
    val valueCollection = valueCollectionType(sum.collection, scope)
    val elementType = valueCollection?.let { model.scalarOf(it.name) }
    when {
        valueCollection == null ->
            diagnostics.add(Diagnostic("F2", "'${Printer.expr(sum)}' — a sum over records names the field to sum: sum(items, amount) (Reference §10)"))
        sum.collection.where != null ->
            diagnostics.add(Diagnostic("F2", "'${Printer.expr(sum)}' — a sum adds a whole `many` of values; derive the values that pass as a `many` of their own and sum that (Reference §5)"))
        elementType !is VType.Num && elementType != VType.DurationT ->
            diagnostics.add(Diagnostic("F2", "'${Printer.expr(sum)}' — a sum adds numbers or durations, but these values are ${valueCollection.name}"))
    }
}

private fun Validator.checkCall(call: FunCall, scope: String) {
    call.args.forEach { argument -> checkExpr(argument, scope) }
    checkFunctionCall(call, scope)
}

/** `Shape for x` as a collection source: one field of the shape takes the record `x`. */
private fun Validator.checkShapeForSource(source: ShapeForSource, scope: String) {
    checkExpr(source.forExpr, scope)
    if (isShapeOrRefinement(source.shape))
        checkForTypeMatch(source.shape, source.forExpr, scope, "'${Printer.expr(source)}'")
}

/** `(Shape for x)`: the shape is known, one of its fields takes `x`, and `x` is a record. */
private fun Validator.checkSingularFor(singular: SingularFor, scope: String) {
    checkExpr(singular.forExpr, scope)
    if (!isShapeOrRefinement(singular.shape)) {
        diagnostics.add(Diagnostic("F1", "unknown shape '${singular.shape}'"))
        return
    }
    val matchedFields = typeMatchedFields(singular.shape, singular.forExpr, scope)
    // a field typed with a union matches an operand's record too (Reference §14)
    if (matchedFields.size > 1) {
        refuseSingularForMatchingSeveralFields(singular, matchedFields)
        return
    }
    val valueAfterFor = valueKind(scalarTypeOf(singular.forExpr, scope))
    if (valueAfterFor != null) {
        refuseValueAfterFor(singular, valueAfterFor)
        return
    }
    checkForTypeMatch(singular.shape, singular.forExpr, scope, "'${Printer.expr(singular)}'")
}

private fun Validator.refuseSingularForMatchingSeveralFields(singular: SingularFor, matchedFields: Collection<String>) {
    val shape = singular.shape
    diagnostics.add(Diagnostic("V13", "'($shape for ${Printer.expr(singular.forExpr)})' — 'for' matches " +
        "${matchedFields.joinToString(" and ") { field -> "'$shape.$field'" }}, " +
        "and a singular reference names one field; select the record through the field meant, " +
        "first($shape where <field> == ${Printer.expr(singular.forExpr)} by <field>) (Reference §14)"))
}

/** The subject after `for` is a record (Reference §10); a value here means the author wants
 *  a record found by one of its fields, which is a selection. */
private fun Validator.refuseValueAfterFor(singular: SingularFor, kind: String) {
    val subject = Printer.expr(singular.forExpr)
    diagnostics.add(Diagnostic("F2", "'(${singular.shape} for $subject)' — the subject after 'for' is a record, and " +
        "'$subject' is $kind; to find the record by a value, select it: " +
        "first(${singular.shape} where <field> == $subject by <field>) (Reference §10)"))
}

private fun Validator.checkAccess(access: Access, scope: String) {
    checkExpr(access.target, scope)
    checkDotsLicensed(access, scope)
    adviseOnQuestionMarkAfterSelection(access)
}
