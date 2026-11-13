package velle

import velle.ext.VelleType
import java.math.BigDecimal

/**
 * What a type reading runs in: what `this` names when it is not the scope itself, the `as`
 * aliases in scope with the element scope each names, the `for each` names bound to values,
 * and the paths a governing condition has shown present (Reference §10, narrowing).
 */
data class TypeEnv(
    val thisScope: String? = null,
    val aliases: Map<String, String> = emptyMap(),
    val scalarAliases: Map<String, VType> = emptyMap(),
    val present: Set<List<String>> = emptySet(),
    val narrowed: Map<List<String>, String> = emptyMap(),
) {
    fun with(narrowing: Narrowing): TypeEnv =
        if (narrowing.present.isEmpty() && narrowing.narrowed.isEmpty()) this
        else copy(present = present + narrowing.present, narrowed = narrowed + narrowing.narrowed)
}

/** What a governing condition shows (Reference §10): the paths present, and the paths narrowed to a shape. */
data class Narrowing(val present: Set<List<String>> = emptySet(), val narrowed: Map<List<String>, String> = emptyMap()) {
    operator fun plus(other: Narrowing) = Narrowing(present + other.present, narrowed + other.narrowed)
    infix fun both(other: Narrowing) = Narrowing(present intersect other.present, narrowed.filter { (path, shape) -> other.narrowed[path] == shape })
    companion object { val NONE = Narrowing() }
}

/**
 * The type an expression makes, read off the model (Reference §5, §7, §10; checks.md F2).
 * One reading serves the validator's checks, the resolution of a property that states no
 * type (Reference §7), and every reader of a member's type. The reading is null when the
 * model gives no type: an unresolved name, `empty` or a list outside the position that
 * types it, a function whose result depends on an untyped argument. Nothing here reports
 * a diagnostic; the validator says what a null means where it meets one.
 */
class ExprTypes(private val model: Model) {

    /** `none`: an absence whose type the context supplies. */
    val absent: VType = VType.Optional(VType.Unknown)

    /** `empty`: a collection whose element the context supplies (Reference §6). */
    val emptyCollection: VType = VType.Coll(EMPTY_ELEMENT)

    fun of(expr: Expr, scope: String, env: TypeEnv = TypeEnv()): VType? = when (expr) {
        is TextLit -> VType.Text
        // a text literal with pieces is absent when a piece is (Reference §5)
        is Interpolation -> if (expr.pieces.any { of(it, scope, env) is VType.Optional }) VType.Optional(VType.Text) else VType.Text
        is IntLit, is DecLit -> VType.Num("decimal")
        is BoolLit -> VType.Bool
        is TodayLit -> VType.DateT
        is NowLit -> VType.DateTimeT
        is DurationLit -> VType.DurationT
        is NoneLit -> absent
        is EmptyLit -> emptyCollection
        is ListLit, is RefLit, is ShapeForSource -> null
        is ValueLit -> when (expr.value) {
            is Value.VText -> VType.Text
            is Value.VNum -> VType.Num("decimal")
            is Value.VBool -> VType.Bool
            is Value.VDate -> VType.DateT
            is Value.VDateTime -> VType.DateTimeT
            is Value.VUuid -> VType.UuidT
            is Value.VDuration -> VType.DurationT
            is Value.VTime -> VType.TimeT
            is Value.VBytes -> VType.BytesT
            is Value.VExt -> VType.Ext(expr.value.type.qualifiedName)
            else -> null
        }
        is UnaryMinus -> of(expr.inner, scope, env)
        // a registered function's result, from the signature its arguments match (Reference §5)
        is FunCall -> (resolveCall(expr, scope, env) as? FunctionResolver.Outcome.Ok)?.result?.takeIf { it != VType.Unknown }
        is AggCall -> aggregateType(expr, scope, env)
        is Binary -> if (expr.op in ARITHMETIC_OPS) arithmeticType(expr, scope, env) else VType.Bool
        is NotExpr, is IsExpr, is ExistsExpr -> VType.Bool
        is IfExpr -> join(
            of(expr.thenExpr, scope, env.with(narrowingOf(expr.condition, holds = true, scope, env))),
            of(expr.elseExpr, scope, env.with(narrowingOf(expr.condition, holds = false, scope, env))),
        )
        is WalkExpr -> model.walkType(expr, env.thisScope ?: scope)
        is PathExpr -> pathType(expr, scope, env)
        is Access -> accessType(expr, scope, env)
        is SetExpr -> recordElement(expr.collection, scope, env)?.let { VType.Coll(it) } ?: valueCollectionType(expr.collection, scope, env)
        is SingularFor -> VType.Optional(VType.Inst(expr.shape))
    }

    /** The signature a call matches, or why none does; null when the name resolves to no function. */
    fun resolveCall(expr: FunCall, scope: String, env: TypeEnv): FunctionResolver.Outcome? {
        val fn = model.functionScope[expr.name] ?: return null
        val args = expr.args.map { FunctionResolver.Arg(of(it, scope, env), FunctionResolver.isConstant(it)) }
        return model.functionResolver.resolve(fn, Printer.expr(expr), args) { Printer.expr(expr.args[it]) }
    }

    private fun aggregateType(expr: AggCall, scope: String, env: TypeEnv): VType? = when (expr.name) {
        "count" -> VType.Num("integer")
        "sum" -> if (expr.field == null) valueCollectionType(expr.collection, scope, env)?.let { model.scalarOf(it.name) }
            else recordElement(expr.collection, scope, env)?.let { element -> model.membersOf(element)[expr.field]?.let(::memberType) }?.let(::bare)
        // a selector is the one record chosen, or absent when the collection is empty (Reference §10)
        "first", "latest" -> recordElement(expr.collection, scope, env)?.let { VType.Optional(VType.Inst(it)) }
        else -> null
    }

    /** The shape of the records a collection expression holds; null for a `many` of values or an unresolved source. */
    private fun recordElement(collection: CollectionExpr, scope: String, env: TypeEnv): String? =
        model.collectionElement(collection, env.thisScope ?: scope, env.aliases)?.takeIf { it in model.shapes || it in model.refinements }

    /** The `many` of values a collection expression names: one bare path to one, or that path
     *  filtered by naming each value, `(options as o where ...)` (Reference §5); null otherwise. */
    fun valueCollectionType(collection: CollectionExpr, scope: String, env: TypeEnv): VType.CollS? {
        val binding = collection.bindings.singleOrNull() ?: return null
        if (collection.where != null && binding.alias == null) return null
        val src = binding.source as? PathExpr ?: return null
        if (src.segs.isEmpty() && (src.root in model.shapes || src.root in model.refinements)) return null
        return bareOrNull(of(src, scope, env)) as? VType.CollS
    }

    /** Arithmetic: numbers stay numbers; an extension type's operator line names its result;
     *  durations move dates and moments (Reference §5). An absent operand makes the result absent. */
    private fun arithmeticType(expr: Binary, scope: String, env: TypeEnv): VType? {
        val leftRead = of(expr.left, scope, env); val rightRead = of(expr.right, scope, env)
        val left = bareOrNull(leftRead); val right = bareOrNull(rightRead)
        val result = when {
            left is VType.Ext || right is VType.Ext -> operatorResult(expr, left, right)
            left is VType.Num && right is VType.Num -> VType.Num("decimal")
            // a declared `many` grows and shrinks by `+` and `-` (Reference §6): the collection stays what it was
            expr.op in setOf("+", "-") && left == emptyCollection && (right is VType.Coll || right is VType.CollS) -> right
            expr.op in setOf("+", "-") && (left is VType.Coll || left is VType.CollS) -> left
            expr.op in setOf("+", "-") && (left == VType.DateT || left == VType.DateTimeT || left == VType.TimeT) && right == VType.DurationT -> left
            expr.op in setOf("+", "-") && left == VType.DurationT && right == VType.DurationT -> VType.DurationT
            expr.op == "-" && left == VType.DateT && right == VType.DateT -> VType.DurationT
            expr.op == "-" && left == VType.DateTimeT && right == VType.DateTimeT -> VType.DurationT
            expr.op == "-" && left == VType.TimeT && right == VType.TimeT -> VType.DurationT
            else -> null
        } ?: return null
        return if (leftRead is VType.Optional || rightRead is VType.Optional) VType.Optional(result) else result
    }

    /** The result of the operator line an expression with an extension operand uses (OQ65):
     *  `balance * 2` matches `(Money, *, decimal)`. Null when no single line matches. */
    fun operatorResult(expr: Binary, left: VType?, right: VType?): VType? = operatorLine(expr, left, right)?.let { typeNamed(it.result, model.registry) }

    /** The one operator line [expr] matches, by both operand types or by one type and the constant
     *  beside it; null when none or more than one does. The validator says which. */
    fun operatorLine(expr: Binary, left: VType?, right: VType?): velle.ext.Operator<*, *, *>? {
        val registry = model.registry
        val lc = constantOf(expr.left); val rc = constantOf(expr.right)
        val ln = registryName(left); val rn = registryName(right)
        val lext = extOf(left, registry); val rext = extOf(right, registry)
        return when {
            lext != null && rc != null -> registry.operatorsFrom(lext.qualifiedName, expr.op).filter { readsConstant(it.right, rc, registry) }.singleOrNull()
            rext != null && lc != null -> registry.operatorsTo(rext.qualifiedName, expr.op).filter { readsConstant(it.left, lc, registry) }.singleOrNull()
            ln != null && rn != null -> registry.operator(ln, expr.op, rn)
            else -> null
        }
    }

    private fun pathType(path: PathExpr, scope: String, outer: TypeEnv): VType? {
        if (path.root in outer.scalarAliases) return if (path.segs.isEmpty()) outer.scalarAliases[path.root] else null
        val env = outer.with(narrowingOf(outer.thisScope ?: scope))
        val start: String? = if (path.root in env.aliases) env.aliases[path.root] else env.thisScope ?: scope
        if (path.root == "this" || path.root in env.aliases) return along(start, path.segs, emptyList(), optional = false, env)
        val rootMember = start?.let { model.membersOf(it)[path.root] } ?: return null
        return along(rootMember, path.segs, listOf(path.root), optional = false, env)
    }

    private fun accessType(expr: Access, scope: String, env: TypeEnv): VType? {
        val target = of(expr.target, scope, env) ?: return null
        if (target is VType.Coll) return collectionAccessType(target, expr.segs, env)
        return along(bare(target).instanceShape(), expr.segs, emptyList(), optional = target is VType.Optional, env)
    }

    /** A field read off every record of a set denotation, `(Component where ...).source`. */
    private fun collectionAccessType(target: VType.Coll, segs: List<Seg>, env: TypeEnv): VType? =
        along(target.shape, segs, emptyList(), optional = false, env)?.let { asCollection(it) }

    /** The type at the end of [segs] read from [shape], the record itself when there are none;
     *  absent when a step is optional and not shown present; a step narrowed to a shape reads there. */
    private fun along(shape: String?, segs: List<Seg>, prefix: List<String>, optional: Boolean, env: TypeEnv): VType? {
        var current = (env.narrowed[prefix] ?: shape) ?: return null
        if (segs.isEmpty()) return VType.Inst(current).let { if (optional) VType.Optional(it) else it }
        var type: VType? = null
        var absent = optional
        var throughMany = false
        val names = prefix.toMutableList()
        for (seg in segs) {
            val member = model.membersOf(current)[seg.name] ?: return null
            type = memberType(member).takeIf { it != VType.Unknown } ?: return null
            names.add(seg.name)
            if (type is VType.Optional && names !in env.present && names !in env.narrowed) absent = true
            if (seg === segs.last()) {
                if (names in env.narrowed) type = VType.Inst(env.narrowed.getValue(names))
                break
            }
            // a step through a `many`, `origin.components.source`, reads the field off every record
            val collectionElement = (bare(type) as? VType.Coll)?.shape
            if (collectionElement != null) throughMany = true
            current = env.narrowed[names] ?: bare(type).instanceShape() ?: collectionElement ?: return null
        }
        val read = type?.let { if (absent) VType.Optional(bare(it)) else bare(it) } ?: return null
        return if (throughMany) asCollection(read) else read
    }

    /** A field read off every record of a collection is the collection of what the field holds:
     *  records give a `many` of their shape, values a `many` of the value type. */
    private fun asCollection(read: VType): VType? = when (val element = bare(read)) {
        is VType.Inst -> VType.Coll(element.shape)
        is VType.Coll, is VType.CollS -> element
        else -> registryName(element)?.let { VType.CollS(it) }
    }

    /** The type at the end of the member [member] then [segs]. */
    private fun along(member: MemberInfo, segs: List<Seg>, prefix: List<String>, optional: Boolean, env: TypeEnv): VType? {
        val type = memberType(member).takeIf { it != VType.Unknown } ?: return null
        val absent = optional || (type is VType.Optional && prefix !in env.present && prefix !in env.narrowed)
        val narrowedTo = env.narrowed[prefix]
        if (segs.isEmpty()) return (narrowedTo?.let { VType.Inst(it) } ?: bare(type)).let { if (absent) VType.Optional(it) else it }
        val collectionElement = (bare(type) as? VType.Coll)?.shape
        val read = along(narrowedTo ?: bare(type).instanceShape() ?: collectionElement, segs, prefix, absent, env) ?: return null
        return if (collectionElement != null) asCollection(read) else read
    }

    /**
     * Whether an expression may be absent by declaration (Reference §7; checks.md F2): an optional
     * path not shown present, `none`, a function result that is optional, through arithmetic and
     * the branches of a conditional. A selection that matches nothing, `first`, `latest`,
     * `(Shape for expr)`, a walk with `until`, is absent by data, which Reference §14 leaves to
     * the runtime, so it does not count.
     */
    fun absenceDeclared(expr: Expr, scope: String, env: TypeEnv): Boolean = when (expr) {
        is NoneLit -> true
        is PathExpr -> of(expr, scope, env) is VType.Optional
        is Access -> expr.segs.any { it.viaQdot } || absenceDeclared(expr.target, scope, env)
        // a function is absent when an argument is, or when its signature says so, `number(text)`
        is FunCall -> expr.args.any { absenceDeclared(it, scope, env) } ||
            (resolveCall(expr, scope, env) as? FunctionResolver.Outcome.Ok)?.signature?.optionalResult == true
        is UnaryMinus -> absenceDeclared(expr.inner, scope, env)
        is Binary -> expr.op in ARITHMETIC_OPS && (absenceDeclared(expr.left, scope, env) || absenceDeclared(expr.right, scope, env))
        is IfExpr -> absenceDeclared(expr.thenExpr, scope, env.with(narrowingOf(expr.condition, holds = true, scope, env))) ||
            absenceDeclared(expr.elseExpr, scope, env.with(narrowingOf(expr.condition, holds = false, scope, env)))
        else -> false
    }

    private val narrowingCache = HashMap<String, Narrowing>()

    /** What a refinement's predicate shows for every member read in its scope (Reference §8):
     *  `Kind where parentName is some` makes `parentName` present, `Comment where target is Post`
     *  reads `target` as a `Post`. A refinement over another inherits what it shows; an `or`
     *  keeps only what both sides show. */
    fun narrowingOf(scope: String): Narrowing = narrowingCache.getOrPut(scope) {
        model.refinements[scope]?.let { narrowingOf(it.expr, scope) } ?: Narrowing.NONE
    }

    /** What a rule's condition shows for its body (Reference §10): the inline `where` of the trigger. */
    fun narrowingOf(expr: RefExpr, scope: String): Narrowing = when (expr) {
        is RefName -> (if (expr.name in model.refinements) narrowingOf(expr.name) else Narrowing.NONE) +
            (expr.where?.let { narrowingOf(it, holds = true, scope, TypeEnv(thisScope = scope)) } ?: Narrowing.NONE)
        is RefAnd -> narrowingOf(expr.left, scope) + narrowingOf(expr.right, scope)
        is RefOr -> narrowingOf(expr.left, scope) both narrowingOf(expr.right, scope)
        is RefNot -> Narrowing.NONE
    }

    /**
     * What a condition shows in the branch where it [holds] or in the one where it fails
     * (Reference §10): `x is some` present where it holds, `x is none` present where it fails,
     * and with the path every step on the way to it, since `site?.name` holding a text means
     * `site` holds a record; `x is Post` read as a `Post` where it holds, and, over a static
     * union of two operands, as the other operand where it fails. A conjunction adds up where
     * it holds.
     */
    fun narrowingOf(cond: Expr, holds: Boolean, scope: String, env: TypeEnv): Narrowing = when {
        cond is Binary && cond.op == "and" && holds -> narrowingOf(cond.left, true, scope, env) + narrowingOf(cond.right, true, scope, env)
        cond is IsExpr && cond.subject is PathExpr -> {
            val path = namesOf(cond.subject)
            when {
                cond.kind == "some" && holds || cond.kind == "none" && !holds -> Narrowing(present = stepsOf(path))
                cond.kind == "refinement" && holds -> Narrowing(narrowed = mapOf(path to cond.refinement!!))
                cond.kind == "refinement" -> complementOf(cond.subject, cond.refinement!!, scope, env)?.let { Narrowing(narrowed = mapOf(path to it)) } ?: Narrowing.NONE
                else -> Narrowing.NONE
            }
        }
        else -> Narrowing.NONE
    }

    /** Over a static union of exactly two operands, the operand a path is when it is not [named]. */
    private fun complementOf(path: PathExpr, named: String, scope: String, env: TypeEnv): String? {
        val declared = (bareOrNull(of(path, scope, env)) as? VType.Inst)?.shape ?: return null
        if (!model.isStaticUnion(declared)) return null
        val operands = model.basesOf(declared)
        if (operands.size != 2 || named !in operands) return null
        return (operands - named).single()
    }

    /** A member's type: its declaration's, or, for a derived property that states none, its expression's (Reference §7). */
    fun memberType(member: MemberInfo): VType =
        member.derived?.takeIf { it.type == null }?.let { model.resolvedDerivedType(member.owner, it) } ?: member.type

    /** A path and every path on the way to it: `site.region.name` with `site.region` and `site`. */
    private fun stepsOf(path: List<String>): Set<List<String>> = path.indices.mapTo(linkedSetOf()) { last -> path.take(last + 1) }

    private fun namesOf(path: PathExpr): List<String> = if (path.root == "this") path.segs.map { it.name } else listOf(path.root) + path.segs.map { it.name }

    /** The one type two branches share (Reference §7): the same type; a number of either kind; a
     *  record or a collection of the shape that admits both; absent when either branch may be. */
    fun join(first: VType?, second: VType?): VType? {
        if (first == null || second == null) return null
        if (first == absent && second == absent) return absent
        if (first == absent) return VType.Optional(bare(second))
        if (second == absent) return VType.Optional(bare(first))
        val left = bare(first); val right = bare(second)
        val joined = when {
            left == right -> left
            left is VType.Num && right is VType.Num -> VType.Num("decimal")
            left == emptyCollection && (right is VType.Coll || right is VType.CollS) -> right
            right == emptyCollection && (left is VType.Coll || left is VType.CollS) -> left
            left is VType.Inst && right is VType.Inst -> admitting(left.shape, right.shape)?.let { VType.Inst(it) }
            left is VType.Coll && right is VType.Coll -> admitting(left.shape, right.shape)?.let { VType.Coll(it) }
            else -> null
        } ?: return null
        return if (first is VType.Optional || second is VType.Optional) VType.Optional(joined) else joined
    }

    private fun admitting(one: String, other: String): String? = when {
        admits(one, other) -> one
        admits(other, one) -> other
        else -> null
    }

    /** Whether a declaration over [declared] holds a record of [actual] (Reference §8, §9): the same
     *  name, an operand of the declared static union, or a refinement whose every base the declaration admits. */
    fun admits(declared: String, actual: String): Boolean =
        declared == actual || model.typeAccepts(declared, actual) ||
            model.basesOf(actual).let { bases -> bases.isNotEmpty() && bases.all { it == declared || model.typeAccepts(declared, it) } }

    companion object {
        val ARITHMETIC_OPS = setOf("+", "-", "*", "/", "%")
        /** The element name of [emptyCollection], a name no shape has. */
        const val EMPTY_ELEMENT = "<empty>"

        /** A type with its optionality taken off. */
        fun bare(type: VType): VType = (type as? VType.Optional)?.inner ?: type
        fun bareOrNull(type: VType?): VType? = type?.let { bare(it) }
    }
}

/** A type as a spec declares it: `text`, `one Entry?`, `many Money` (Reference §5, §6). */
fun Model.spell(type: VType): String = when (type) {
    is VType.Optional -> spell(type.inner) + "?"
    is VType.Num -> type.name
    VType.Text -> "text"
    VType.Bool -> "boolean"
    VType.DateT -> "Date"
    VType.DateTimeT -> "DateTime"
    VType.UuidT -> "uuid"
    VType.DurationT -> "duration"
    VType.TimeT -> "time"
    VType.BytesT -> "bytes"
    is VType.Ext -> registry[type.qualifiedName]?.let { specNameOf(it) } ?: type.qualifiedName
    is VType.Inst -> "one ${type.shape}"
    is VType.Coll -> if (type.shape == ExprTypes.EMPTY_ELEMENT) "many ..." else "many ${type.shape}"
    is VType.CollS -> "many ${type.name}"
    VType.Id -> "id"
    VType.Unknown -> "?"
}

/** A type in the words a diagnostic uses: "a text", "an Entry", "a many of Money", "a Date or none". */
fun Model.words(type: VType): String = when (type) {
    is VType.Optional -> words(type.inner) + " or none"
    is VType.Num -> article(type.name)
    VType.Text -> "a text"
    VType.Bool -> "a boolean"
    VType.DateT -> "a Date"
    VType.DateTimeT -> "a DateTime"
    VType.UuidT -> "a uuid"
    VType.DurationT -> "a duration"
    VType.TimeT -> "a time"
    VType.BytesT -> "bytes"
    is VType.Ext -> article(registry[type.qualifiedName]?.let { specNameOf(it) } ?: type.qualifiedName)
    is VType.Inst -> article(type.shape)
    is VType.Coll -> if (type.shape == ExprTypes.EMPTY_ELEMENT) "an empty collection" else "a many of ${type.shape}"
    is VType.CollS -> "a many of ${type.name}"
    VType.Id -> "an id"
    VType.Unknown -> "a value of no known type"
}

private fun article(name: String): String = (if (name.first().lowercaseChar() in "aeiou") "an " else "a ") + name

/** The number or the text a constant expression is; null when the expression is not a constant. */
fun constantOf(expr: Expr): Pair<BigDecimal?, String?>? = when (expr) {
    is IntLit -> BigDecimal(expr.value) to null
    is DecLit -> BigDecimal(expr.text) to null
    is UnaryMinus -> constantOf(expr.inner)?.let { (number, _) -> number?.negate()?.let { it to null } }
    is TextLit -> null to expr.value
    else -> null
}

/** The registry's name for a type: a builtin keyword or `namespace.name`; null when unknown. */
fun registryName(type: VType?): String? = when (val bare = ExprTypes.bareOrNull(type)) {
    is VType.Ext -> bare.qualifiedName
    is VType.Num -> "decimal"
    VType.Text -> "text"
    VType.Bool -> "boolean"
    VType.DateT -> "Date"
    VType.DateTimeT -> "DateTime"
    VType.UuidT -> "uuid"
    VType.DurationT -> "duration"
    VType.TimeT -> "time"
    VType.BytesT -> "bytes"
    else -> null
}

/** The type a builtin keyword or a registered name stands for; null when neither. */
fun typeNamed(name: String, registry: TypeRegistry): VType? = when (name) {
    in TypeRegistry.NUMBER_NAMES -> VType.Num(name)
    "text" -> VType.Text
    "boolean" -> VType.Bool
    "Date" -> VType.DateT
    "DateTime" -> VType.DateTimeT
    "uuid" -> VType.UuidT
    "duration" -> VType.DurationT
    "time" -> VType.TimeT
    "bytes" -> VType.BytesT
    else -> registry[name]?.let { VType.Ext(it.qualifiedName) }
}

/** The registered extension type behind a type, optional or not; null for a builtin. */
fun extOf(type: VType?, registry: TypeRegistry): VelleType<*>? =
    ExprTypes.bareOrNull(type)?.let { if (it is VType.Ext) registry[it.qualifiedName] else null }

/** Whether the type [name] names, builtin or registered, declares a reader for the form of [constant]. */
fun readsConstant(name: String, constant: Pair<BigDecimal?, String?>, registry: TypeRegistry): Boolean =
    (registry.builtins[name] ?: registry[name])?.let { readsForm(it, constant) } ?: false

/** Whether [type] declares a reader for the form [constant] is, a number or a text. */
fun readsForm(type: VelleType<*>, constant: Pair<BigDecimal?, String?>): Boolean =
    if (constant.first != null) type.literals.fromNumber != null else type.literals.fromText != null
