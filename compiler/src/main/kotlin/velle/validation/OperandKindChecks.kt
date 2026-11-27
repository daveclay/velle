package velle.validation

import velle.*

// ── the kinds an operator takes (Reference §5, §10; checks F2) ──────────────
//
// Each binary operator takes operands of the kinds it is defined on: the text
// operators take texts, `in` and `contains` take a value of the collection's
// element, `<` and the rest take values with an order, and arithmetic takes
// numbers or an extension type's declared operator lines.

internal val ORDERING_OPERATORS = setOf("<", "<=", ">", ">=")

private val COMPARISON_OPERATORS = setOf("==", "!=") + ORDERING_OPERATORS

private val ARITHMETIC_OPERATORS = setOf("+", "-", "*", "/", "%")

/** The type of [expression] as [scalarTypeOf] reads it, with any remaining optionality taken off. */
internal fun Validator.bareScalarTypeOf(expression: Expr, scope: String): VType? =
    scalarTypeOf(expression, scope)?.let { type -> (type as? VType.Optional)?.inner ?: type }

/** The kinds of a binary expression's two sides against what its operator is defined on. */
internal fun Validator.checkOperandKinds(binary: Binary, scope: String) {
    checkOperatorOwnOperands(binary, scope)
    checkListOnlyRightOfIn(binary)
    // an extension type takes part only through what it declares (Reference §5; OQ65)
    checkOperandsHaveOrder(binary, scope)
    when (binary.op) {
        in COMPARISON_OPERATORS -> checkComparison(binary, scope)
        in ARITHMETIC_OPERATORS -> checkArithmetic(binary, scope)
    }
}

/** The text operators take text on both sides (Reference §10; OQ51 R1); `values contains x`
 *  over a `many` of values takes an element (Reference §5); `in` has its own check. */
private fun Validator.checkOperatorOwnOperands(binary: Binary, scope: String) {
    val leftCollection = scalarTypeOf(binary.left, scope) as? VType.CollS
    when {
        binary.op == "contains" && leftCollection != null -> checkContainedElement(binary, leftCollection, scope)
        binary.op in TEXT_OPERATORS -> checkTextOperands(binary, scope)
        binary.op == "in" -> checkIn(binary, scope)
    }
}

private fun Validator.checkContainedElement(binary: Binary, leftCollection: VType.CollS, scope: String) {
    val elementType = model.scalarOf(leftCollection.name)
    val rightType = scalarTypeOf(binary.right, scope) ?: return
    if (rightType == elementType || (rightType is VType.Num && elementType is VType.Num)) return
    // a constant beside an extension element reads through the type (OQ65 D2); otherwise types must agree
    if (elementType is VType.Ext && constantOf(binary.right) != null) return
    diagnostics.add(Diagnostic("F2", "'${Printer.expr(binary)}' — '${Printer.expr(binary.left)}' holds '${leftCollection.name}' values, " +
        "but '${Printer.expr(binary.right)}' is not one"))
}

private fun Validator.checkTextOperands(binary: Binary, scope: String) {
    for (side in listOf(binary.left, binary.right)) {
        if (binary.op == "matches" && side === binary.right) continue // checkMatchesPattern says what the right side may be
        val sideType = scalarTypeOf(side, scope) ?: continue
        if (sideType != VType.Text)
            diagnostics.add(Diagnostic("F2", "'${Printer.expr(binary)}' — '${binary.op}' compares text, but '${Printer.expr(side)}' is not text"))
    }
    if (binary.op == "matches") checkMatchesPattern(binary, scope)
}

private fun Validator.checkListOnlyRightOfIn(binary: Binary) {
    if (binary.op == "in" || (binary.left !is ListLit && binary.right !is ListLit)) return
    diagnostics.add(Diagnostic("F2", "'${Printer.expr(binary)}' — a list of values `[a, b]` belongs only on the right of `in` (Reference §10)"))
}

/** A `uuid` and a `boolean` have no order (Reference §5): `==` and `!=` only. */
private fun Validator.checkOperandsHaveOrder(binary: Binary, scope: String) {
    if (binary.op !in ORDERING_OPERATORS) return
    for (side in listOf(binary.left, binary.right)) {
        val sideType = bareScalarTypeOf(side, scope)
        if (sideType == VType.UuidT || sideType == VType.Bool)
            diagnostics.add(Diagnostic("F2", "'${Printer.expr(binary)}' — a ${registryName(sideType)} has no order; only `==` and `!=` apply"))
    }
}

private fun Validator.checkArithmetic(binary: Binary, scope: String) {
    val leftType = scalarTypeOf(binary.left, scope)
    val rightType = scalarTypeOf(binary.right, scope)
    val scaledDuration = if (binary.op in SCALING_OPERATORS) durationOperand(binary, scope) else null
    when {
        leftType is VType.Ext || rightType is VType.Ext -> operatorLine(binary, leftType, rightType, scope)
        binary.op == "%" -> checkRemainderOperands(binary, scope)
        // a duration is not scaled (Reference §5): `2 * (returnedOn - dueOn)` is refused, not multiplied
        scaledDuration != null -> refuseScaledDuration(binary, scaledDuration)
        // a text takes part in no arithmetic (Reference §5): `name + "_unit"` is refused, not joined
        isTextBesideBuiltin(leftType, rightType) ->
            diagnostics.add(Diagnostic("F2", "'${Printer.expr(binary)}' — '${binary.op}' is not defined on a text; " +
                "a text made from other values is a text literal with pieces, ${interpolationFor(binary)}"))
    }
}

private fun Validator.checkRemainderOperands(binary: Binary, scope: String) {
    for (side in listOf(binary.left, binary.right)) {
        val sideType = scalarTypeOf(side, scope)
        if (sideType != null && sideType !is VType.Num)
            diagnostics.add(Diagnostic("F2", "'${Printer.expr(binary)}' — '%' takes numbers, but '${Printer.expr(side)}' is not a number"))
    }
}

private val SCALING_OPERATORS = setOf("*", "/")

/** The first side of [binary] that is a duration, or null when neither is. */
private fun Validator.durationOperand(binary: Binary, scope: String): Expr? =
    listOf(binary.left, binary.right).firstOrNull { side -> bareScalarTypeOf(side, scope) == VType.DurationT }

private fun Validator.refuseScaledDuration(binary: Binary, duration: Expr) {
    diagnostics.add(Diagnostic("F2", "'${Printer.expr(binary)}' — '${binary.op}' is not defined on a duration; " +
        "read it as a number first, 'days(${Printer.expr(duration)})' (Reference §5)"))
}

private fun Validator.isTextBesideBuiltin(leftType: VType?, rightType: VType?): Boolean =
    (leftType == VType.Text && model.registry.typeFor(rightType) != null) ||
        (rightType == VType.Text && model.registry.typeFor(leftType) != null)

/** `x in [a, b]` / `x in values` (Reference §10): the right side lists values of the left side's type. */
internal fun Validator.checkIn(membership: Binary, scope: String) {
    val leftType = bareScalarTypeOf(membership.left, scope)
    when (val right = membership.right) {
        is ListLit -> right.items.forEach { item -> checkListedValue(membership, item, leftType, scope) }
        else -> checkInCollection(membership, right, leftType, scope)
    }
}

/** Whether a value of [candidateType], written as [candidate], is a value of [leftType]: the same
 *  type, any number beside a number, or a constant beside an extension type. Unknown types agree. */
private fun Validator.agreesWithLeft(leftType: VType?, candidateType: VType?, candidate: Expr): Boolean =
    leftType == null || candidateType == null || candidateType == leftType ||
        (candidateType is VType.Num && leftType is VType.Num) ||
        (leftType is VType.Ext && constantOf(candidate) != null)

private fun Validator.checkListedValue(membership: Binary, item: Expr, leftType: VType?, scope: String) {
    if (!agreesWithLeft(leftType, scalarTypeOf(item, scope), item)) {
        diagnostics.add(Diagnostic("F2", "'${Printer.expr(membership)}' — '${Printer.expr(item)}' is not a ${registryName(leftType) ?: "value of the left side's type"}"))
        return
    }
    // a constant listed beside an extension type reads as the type (Reference §5): "boss" beside a Role is F2
    if (leftType !is VType.Ext) return
    val constant = constantOf(item) ?: return
    extOf(leftType)?.let { extension -> checkConstantAs(constant, extension, item) }
}

private fun Validator.checkInCollection(membership: Binary, right: Expr, leftType: VType?, scope: String) {
    when (val collectionType = scalarTypeOf(right, scope)) {
        is VType.Coll -> checkRecordInRecords(membership, right, collectionType, leftType, scope)
        is VType.CollS ->
            if (!agreesWithLeft(leftType, model.scalarOf(collectionType.name), right))
                diagnostics.add(Diagnostic("F2", "'${Printer.expr(membership)}' — '${Printer.expr(right)}' holds '${collectionType.name}' values, but '${Printer.expr(membership.left)}' is not one"))
        else -> diagnostics.add(Diagnostic("F2", "'${Printer.expr(membership)}' — 'in' takes a list of values `[a, b]`, a `many` of values, or a collection of records on the right (Reference §10)"))
    }
}

/** `record in records` (Reference §7, "Walks"): the left side is a record of the collection's shape. */
private fun Validator.checkRecordInRecords(membership: Binary, right: Expr, collectionType: VType.Coll, leftType: VType?, scope: String) {
    val leftShape = (membership.left as? PathExpr)?.let { left -> pathTypeName(left, scope) }
    if (leftType != null && leftType !is VType.Inst) {
        diagnostics.add(Diagnostic("F2", "'${Printer.expr(membership)}' — '${Printer.expr(right)}' holds records, but '${Printer.expr(membership.left)}' is not one"))
        return
    }
    if (leftShape == null || (model.basesOf(leftShape) intersect model.basesOf(collectionType.shape)).isNotEmpty()) return
    diagnostics.add(Diagnostic("F2", "'${Printer.expr(membership)}' — '${Printer.expr(right)}' holds '${collectionType.shape}' records, but '${Printer.expr(membership.left)}' is a '$leftShape'"))
}
