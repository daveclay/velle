package velle.validation

import velle.*
import velle.ext.Operator
import velle.ext.Ordered
import velle.ext.VelleType
import java.math.BigDecimal

// ── operator lines and comparisons (Reference §5; OQ65 D2, D4; checks F2, E3) ──

/**
 * The operator line an arithmetic expression with an extension operand uses, from the
 * registry's table (OQ65): `balance * 2` matches `(Money, *, decimal)`, `balance + 10`
 * matches `(Money, +, Money)` and reads `10` as Money. Diagnoses (E3) a line no type
 * declares and (F2) a constant more than one line could type. Null when untyped.
 */
internal fun Validator.operatorLine(arithmetic: Binary, leftType: VType?, rightType: VType?, scope: String): Operator<*, *, *>? {
    val leftExtension = extOf(leftType)
    val rightExtension = extOf(rightType)
    model.types.operatorLine(arithmetic, leftType, rightType)?.let { line ->
        readConstantsBesideLine(arithmetic, line, leftExtension, rightExtension)
        return line
    }
    val leftConstant = constantOf(arithmetic.left)
    val rightConstant = constantOf(arithmetic.right)
    return when {
        leftExtension != null && rightConstant != null -> lineTypingConstant(arithmetic, leftExtension, rightConstant, ConstantSide.RIGHT)
        rightExtension != null && leftConstant != null -> lineTypingConstant(arithmetic, rightExtension, leftConstant, ConstantSide.LEFT)
        else -> lineBetweenTypes(arithmetic, leftType, rightType, leftExtension ?: rightExtension)
    }
}

/** The constant beside a line the model found reads as the type the line names. */
private fun Validator.readConstantsBesideLine(arithmetic: Binary, line: Operator<*, *, *>, leftExtension: VelleType<*>?, rightExtension: VelleType<*>?) {
    val leftConstant = constantOf(arithmetic.left)
    val rightConstant = constantOf(arithmetic.right)
    if (leftExtension != null && rightConstant != null) readConstantAs(rightConstant, line.right, arithmetic.right)
    if (rightExtension != null && leftConstant != null) readConstantAs(leftConstant, line.left, arithmetic.left)
}

/** Reads [constant], written as [at], as the registered type [typeName] names, when there is one. */
private fun Validator.readConstantAs(constant: Pair<BigDecimal?, String?>, typeName: String, at: Expr) {
    model.registry[typeName]?.let { type -> checkConstantAs(constant, type, at) }
}

/** The side of an arithmetic expression a constant stands on, beside an operand of an extension type. */
private enum class ConstantSide(val word: String) {
    LEFT("left") {
        override fun typeNameIn(line: Operator<*, *, *>) = line.left
        override fun of(arithmetic: Binary) = arithmetic.left
    },
    RIGHT("right") {
        override fun typeNameIn(line: Operator<*, *, *>) = line.right
        override fun of(arithmetic: Binary) = arithmetic.right
    };

    /** The type an operator line gives the operand on this side. */
    abstract fun typeNameIn(line: Operator<*, *, *>): String
    abstract fun of(arithmetic: Binary): Expr
}

/** The one line of [extension]'s operator that reads [constant] on [side]: E3 when no
 *  line does, F2 when more than one could. */
private fun Validator.lineTypingConstant(
    arithmetic: Binary,
    extension: VelleType<*>,
    constant: Pair<BigDecimal?, String?>,
    side: ConstantSide,
): Operator<*, *, *>? {
    val lines = linesReadingConstant(arithmetic, extension, constant, side)
    return when (lines.size) {
        0 -> refuseOperator(arithmetic, "${describe(extension)} declares no '${arithmetic.op}' taking a constant on the ${side.word}; " +
            "declare the operator on the type, or write a field or typed value instead of the constant")
        1 -> lines.single().also { line -> readConstantAs(constant, side.typeNameIn(line), side.of(arithmetic)) }
        else -> refuseAmbiguousConstant(arithmetic, lines.map { line -> side.typeNameIn(line) })
    }
}

/** The lines of [extension]'s operator whose type on [side] reads [constant]. */
private fun Validator.linesReadingConstant(
    arithmetic: Binary,
    extension: VelleType<*>,
    constant: Pair<BigDecimal?, String?>,
    side: ConstantSide,
): List<Operator<*, *, *>> {
    val candidateLines =
        if (side == ConstantSide.RIGHT) model.registry.operatorsFrom(extension.qualifiedName, arithmetic.op)
        else model.registry.operatorsTo(extension.qualifiedName, arithmetic.op)
    return candidateLines.filter { line -> readsConstant(side.typeNameIn(line), constant) }
}

/** F2: the constant could be read as any of [typeNames], one per line that reads it. */
private fun Validator.refuseAmbiguousConstant(arithmetic: Binary, typeNames: List<String>): Operator<*, *, *>? {
    diagnostics.add(Diagnostic("F2", "'${Printer.expr(arithmetic)}' — the constant could be " +
        "${typeNames.joinToString(" or ") { typeName -> "a ${model.registry.describe(typeName)}" }}; " +
        "write a field or a typed value instead"))
    return null
}

/** The line two typed operands meet on, or E3 naming [extension] as where to declare it. */
private fun Validator.lineBetweenTypes(arithmetic: Binary, leftType: VType?, rightType: VType?, extension: VelleType<*>?): Operator<*, *, *>? {
    val leftName = registryName(leftType) ?: return null
    val rightName = registryName(rightType) ?: return null
    return model.registry.operator(leftName, arithmetic.op, rightName)
        ?: refuseOperator(arithmetic, "no type declares '${leftName.substringAfterLast('.')} ${arithmetic.op} ${rightName.substringAfterLast('.')}'; " +
            "${describe(extension!!)} is where to declare it")
}

private fun Validator.refuseOperator(arithmetic: Binary, message: String): Operator<*, *, *>? {
    diagnostics.add(Diagnostic("E3", "'${Printer.expr(arithmetic)}' — $message"))
    return null
}

/**
 * A comparison's two sides are one type (Reference §5; checks.md F2): a constant reads
 * as the other side's type through that type's literal reader, so `opensAt < "09:00"`
 * reads the text as a time and `balance < 0` reads `0` as a Money, and a constant the
 * type does not read is refused at the constant. Two values of different kinds never
 * meet: a comparison never reads a text as a number or a date; `number(value)` is where
 * a text is read. An extension type is named with its Kotlin class, since the fix for a
 * missing reader or ordering is there (E3).
 */
internal fun Validator.checkComparison(comparison: Binary, scope: String) {
    val leftType = bareScalarTypeOf(comparison.left, scope)
    val rightType = bareScalarTypeOf(comparison.right, scope)
    if (checkCollectionBesideOne(comparison, leftType, rightType)) return
    val leftExtension = extOf(leftType)
    val rightExtension = extOf(rightType)
    if (leftExtension == null && rightExtension == null) {
        checkBuiltinComparison(comparison, leftType, rightType)
        return
    }
    checkExtensionComparison(comparison, leftType, rightType, leftExtension, rightExtension)
}

/** The half of [checkComparison] where a side is of an extension type: ordered where `<` asks it to be,
 *  and the other side of the same type or a constant it reads. */
private fun Validator.checkExtensionComparison(
    comparison: Binary,
    leftType: VType?,
    rightType: VType?,
    leftExtension: VelleType<*>?,
    rightExtension: VelleType<*>?,
) {
    if (comparison.op in ORDERING_OPERATORS)
        listOfNotNull(leftExtension, rightExtension).distinct().forEach { extension -> checkExtensionOrdered(comparison, extension) }
    when {
        leftExtension != null && rightExtension != null -> if (leftExtension !== rightExtension)
            diagnostics.add(Diagnostic("F2", "'${Printer.expr(comparison)}' — compares a ${describe(leftExtension)} with a ${describe(rightExtension)}, two different types"))
        leftExtension != null -> checkSideBesideExtension(comparison, leftExtension, comparison.right, rightType)
        rightExtension != null -> checkSideBesideExtension(comparison, rightExtension, comparison.left, leftType)
    }
}

/** `<` and the rest over an extension type: the type declares an ordering, and words have none. */
private fun Validator.checkExtensionOrdered(comparison: Binary, extension: VelleType<*>) {
    if (extension is EnumType)
        diagnostics.add(Diagnostic("F2", "'${Printer.expr(comparison)}' — ${noOrder(extension, "'${comparison.op}'")}; compare with '==', '!=', or 'in' (Reference §5)"))
    else if (extension !is Ordered<*>)
        diagnostics.add(Diagnostic("E3", "'${Printer.expr(comparison)}' — ${describe(extension)} declares no ordering, so '${comparison.op}' is not defined on it; " +
            "implement `Ordered` on the type, or compare a field that is ordered"))
}

/** The side beside an extension operand is a constant the extension reads, or is refused when its type is known. */
private fun Validator.checkSideBesideExtension(comparison: Binary, extension: VelleType<*>, otherSide: Expr, otherType: VType?) {
    val constant = constantOf(otherSide)
    if (constant != null) {
        checkConstantAs(constant, extension, otherSide)
        return
    }
    if (otherType == null) return
    diagnostics.add(Diagnostic("F2", "'${Printer.expr(comparison)}' — compares a ${describe(extension)} with '${Printer.expr(otherSide)}', which is not one"))
}

private fun isCollectionType(type: VType): Boolean = type is VType.Coll || type is VType.CollS

/**
 * A collection beside a single value (Reference §10; checks.md F2): `inheritedBy == this`
 * compares a `many S` with one record and is never true, since a collection is not a
 * record; whether the record is one of them is `this in inheritedBy`. A collection has no
 * order, so `<` and the rest are refused the same way. Two collections are not this check.
 * Returns whether it refused.
 */
private fun Validator.checkCollectionBesideOne(comparison: Binary, leftType: VType?, rightType: VType?): Boolean {
    if (leftType == null || rightType == null) return false
    val leftIsCollection = isCollectionType(leftType)
    if (leftIsCollection == isCollectionType(rightType)) return false
    val collectionSide = if (leftIsCollection) comparison.left else comparison.right
    val singleSide = if (leftIsCollection) comparison.right else comparison.left
    val singleType = if (leftIsCollection) rightType else leftType
    diagnostics.add(Diagnostic("F2", collectionBesideOneMessage(comparison, Printer.expr(collectionSide), singleSide, singleType)))
    return true
}

private fun Validator.collectionBesideOneMessage(comparison: Binary, collection: String, singleSide: Expr, singleType: VType): String {
    val singleWord = if (singleType is VType.Inst) "a record" else withArticle(kindWord(singleSide, singleType))
    val sentence = "'${Printer.expr(comparison)}' — compares the collection '$collection' with $singleWord; "
    if (comparison.op != "==" && comparison.op != "!=") return sentence + "a collection has no order"
    val membership = if (comparison.op == "!=") "${Printer.expr(singleSide)} not in $collection" else "${Printer.expr(singleSide)} in $collection"
    return sentence + "a collection is not compared with one value. Write '$membership' to ask whether it is one of them"
}

/** The builtin half of [checkComparison]: the two kinds agree, the numbers being one kind,
 *  or the one constant among them reads as the other side's type. */
private fun Validator.checkBuiltinComparison(comparison: Binary, leftType: VType?, rightType: VType?) {
    val leftReader = model.registry.typeFor(leftType) ?: return
    val rightReader = model.registry.typeFor(rightType) ?: return
    if (leftType == rightType || (leftType is VType.Num && rightType is VType.Num)) return
    if (readOneConstantAsOtherSide(comparison, leftReader, rightReader)) return
    diagnostics.add(Diagnostic("F2", "'${Printer.expr(comparison)}' — ${mismatch(comparison.left, leftType, comparison.right, rightType)}"))
}

/** When exactly one side is a constant and the other side's type reads its form, reads it as that
 *  type. Returns whether it did. */
private fun Validator.readOneConstantAsOtherSide(comparison: Binary, leftReader: VelleType<*>, rightReader: VelleType<*>): Boolean {
    val leftConstant = constantOf(comparison.left)
    val rightConstant = constantOf(comparison.right)
    if (rightConstant != null && leftConstant == null && readsForm(leftReader, rightConstant)) {
        checkConstantAs(rightConstant, leftReader, comparison.right)
        return true
    }
    if (leftConstant != null && rightConstant == null && readsForm(rightReader, leftConstant)) {
        checkConstantAs(leftConstant, rightReader, comparison.left)
        return true
    }
    return false
}

/**
 * Why two builtin kinds do not meet, and what to write: a text field beside a number, a
 * `Date`, or a `DateTime` is read by the function of that type, absent when the text is
 * not one, or declared under the type; beside a `time` or a `uuid` it is declared.
 */
internal fun Validator.mismatch(left: Expr, leftType: VType?, right: Expr, rightType: VType?): String {
    val sentence = mismatchSentence(left, leftType, right, rightType)
    // what to write, when the text is a field and the other side is a value of another kind
    if (constantOf(left) != null || constantOf(right) != null) return sentence
    return when {
        leftType == VType.Text -> sentence + textFieldAdvice(left, right, rightType)
        rightType == VType.Text -> sentence + textFieldAdvice(right, left, leftType)
        else -> sentence
    }
}

/** That the two kinds do not meet, said from the side that would have had to be read as the other. */
private fun Validator.mismatchSentence(left: Expr, leftType: VType?, right: Expr, rightType: VType?): String {
    val leftKind = kindWord(left, leftType)
    val rightKind = kindWord(right, rightType)
    // the side that would have had to be read as the other: the constant, else the text, else the left
    val readIsRight = constantOf(right) != null || (constantOf(left) == null && rightType == VType.Text && leftType != VType.Text)
    return "compares the $leftKind '${Printer.expr(left)}' with ${withArticle(rightKind)}; " +
        if (readIsRight) "${withArticle(rightKind)} is not compared as ${withArticle(leftKind)}" else "${withArticle(leftKind)} is not compared as ${withArticle(rightKind)}"
}

/** What to write when the text field [textSide] meets [otherSide], of [otherType]: the function that
 *  reads a number, a `Date`, or a `DateTime` out of a text, else the declaration; empty when neither fits. */
private fun Validator.textFieldAdvice(textSide: Expr, otherSide: Expr, otherType: VType?): String {
    val text = Printer.expr(textSide)
    val declaration = "declare '$text' as ${withArticle(kindWord(otherSide, otherType))}"
    val reader = when (otherType) {
        is VType.Num -> "number($text), which is absent when the text is not a number"
        VType.DateT -> "date($text), which is absent when the text is not a date"
        VType.DateTimeT -> "datetime($text), which is absent when the text is not a moment"
        VType.TimeT, VType.UuidT -> return ". ${declaration.replaceFirstChar { it.uppercase() }}"
        else -> return ""
    }
    return ". Write $reader, or $declaration on a shape of its own"
}

/** A value written to a field of an extension type is of that type: a constant it reads,
 *  or an expression of the type (OQ65 D2). Builtin-typed targets are not checked here. */
internal fun Validator.checkExtWrite(target: VType, value: Expr, scope: String, at: String) {
    val targetExtension = extOf(target) ?: return
    constantOf(value)?.let { constant -> checkConstantAs(constant, targetExtension, value); return }
    val valueType = scalarTypeOf(value, scope) ?: return
    val valueExtension = extOf(valueType)
    if (valueExtension !== targetExtension)
        diagnostics.add(Diagnostic("F2", "$at — assigns ${valueExtension?.let { describe(it) } ?: "'${Printer.expr(value)}'"} to a field of ${describe(targetExtension)}"))
}
