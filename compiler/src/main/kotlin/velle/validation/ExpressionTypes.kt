package velle.validation

import velle.*
import velle.ext.Ordered
import velle.ext.VelleType
import java.math.BigDecimal

// ── the types of expressions, as the checks read them (Reference §5) ────────
//
// The model reads an expression's type (`ExprTypes`); the checks read it here,
// with the validator's scope state: what `this` names, the aliases, and the
// paths the branch being checked has narrowed.

/** The spec's name for a value type, or null when [type] is a record, a collection of
 *  records, or unknown: what may not stand as the subject of `(Shape for expr)`. */
internal fun Validator.valueKind(type: VType?): String? = when (type) {
    null, VType.Unknown, VType.Id, is VType.Inst, is VType.Coll -> null
    is VType.Optional -> valueKind(type.inner)
    is VType.Num -> "a ${type.name}"
    VType.Text -> "a text"
    VType.Bool -> "a boolean"
    VType.DateT -> "a Date"
    VType.DateTimeT -> "a DateTime"
    VType.UuidT -> "a uuid"
    VType.DurationT -> "a duration"
    VType.TimeT -> "a time"
    VType.BytesT -> "bytes"
    is VType.Ext -> "a ${type.qualifiedName}"
    is VType.CollS -> "a many of ${type.name}"
}

/** The validator's scope state as a [TypeEnv] for the model's type reading: what `this` names,
 *  the aliases, and the paths the branch being checked has narrowed (V11). */
internal fun Validator.typeEnv(): TypeEnv = TypeEnv(
    thisScope, scopeByAlias.toMap(), scalarTypeByAlias.toMap(),
    narrowed = scopeByNarrowedPath.toMap(),
).with(ruleNarrowing).with(branchNarrowing)

/** The type of an expression as the model reads it (`ExprTypes`), with its optionality
 *  taken off, as the kind checks here want it; null when the model gives none or the
 *  expression is `none`. [declaredTypeOf] is where the optionality matters. */
internal fun Validator.scalarTypeOf(expression: Expr, scope: String): VType? =
    model.types.of(expression, scope, typeEnv())?.takeIf { type -> type != model.types.absent }?.let { type -> ExprTypes.bare(type) }

internal fun Validator.valueCollectionType(collection: CollectionExpr, scope: String): VType.CollS? =
    model.types.valueCollectionType(collection, scope, typeEnv())

/** The builtin types whose values have an order (Reference §5), besides the numbers. */
private val ORDERED_BUILTIN_TYPES: Set<VType> =
    setOf(VType.Text, VType.DateT, VType.DateTimeT, VType.DurationT, VType.TimeT, VType.BytesT)

internal fun Validator.isOrderable(type: VType): Boolean {
    val bare = (type as? VType.Optional)?.inner ?: type
    return bare is VType.Num || bare in ORDERED_BUILTIN_TYPES ||
        (bare is VType.Ext && model.registry[bare.qualifiedName] is Ordered<*>)
}

// ── extension types in expressions (Reference §5; OQ65 D2, D4; checks E3) ─────────

/** A bare constant: its number or its text, or null for anything else. */
internal fun Validator.constantOf(expression: Expr): Pair<BigDecimal?, String?>? = velle.constantOf(expression)

internal fun Validator.registryName(type: VType?): String? = velle.registryName(type)

internal fun Validator.extOf(type: VType?): VelleType<*>? = velle.extOf(type, model.registry)

/** A type's name as this spec writes it, with its Kotlin class when it is an extension type, for an E3 sentence. */
internal fun Validator.describe(type: VelleType<*>) = when {
    type is EnumType -> "'${model.specNameOf(type)}'"
    model.registry.vtypeOf(type) is VType.Ext -> "'${model.specNameOf(type)}' (${type::class.qualifiedName})"
    else -> "'${type.name}'"
}

/** The sentence for an ordered use of a type declared by its words (Reference §5): the words have no order. */
internal fun Validator.noOrder(type: EnumType, what: String) = "the words of '${model.specNameOf(type)}' have no order, so $what is not defined on them"

/** Whether a constant of this form can be read as the type [name] names, builtin or registered. */
internal fun Validator.readsConstant(name: String, constant: Pair<BigDecimal?, String?>): Boolean =
    velle.readsConstant(name, constant, model.registry)

/** A constant where [type] is expected: F2 when the type reads no such constant or
 *  its reader refuses this one (OQ65 D2). Returns whether it read. */
internal fun Validator.checkConstantAs(constant: Pair<BigDecimal?, String?>, type: VelleType<*>, at: Expr): Boolean =
    when (val read = type.readLiteral(constant.first, constant.second)) {
        is LiteralRead.Ok -> true
        is LiteralRead.NoReader -> {
            diagnostics.add(Diagnostic("F2", "'${Printer.expr(at)}' — ${describe(type)} reads no ${read.form} constant; " +
                "compare with a field of that type, or add a `literals` reader to the type"))
            false
        }
        is LiteralRead.Rejected -> {
            diagnostics.add(Diagnostic("F2", "'${Printer.expr(at)}' — ${describe(type)} rejects this constant: ${read.reason}"))
            false
        }
    }

/** Whether [type] declares a reader for the form of [constant], a number or a text. */
internal fun Validator.readsForm(type: VelleType<*>, constant: Pair<BigDecimal?, String?>): Boolean = velle.readsForm(type, constant)

/** The word for a side's kind: its builtin keyword, `integer` for an integer field, `number` for a number constant. */
internal fun Validator.kindWord(side: Expr, type: VType?): String = when {
    type is VType.Num -> if (constantOf(side) != null) "number" else type.name
    else -> registryName(type) ?: "value of another type"
}

/** The word with its article: "a text", "an integer", "a uuid" (said as a word), "bytes" (no article). */
internal fun Validator.withArticle(word: String) = when {
    word == "bytes" -> word
    word.first() in "aeio" -> "an $word"
    else -> "a $word"
}
