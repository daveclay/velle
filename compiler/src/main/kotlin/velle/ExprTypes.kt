package velle

import velle.spec.PathStart
import velle.spec.ResolvedPath
import velle.spec.Scope
import velle.spec.Step

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
    /** The tests the scope and the governing conditions establish, keyed as [PresenceFacts.testKey] keys them. */
    val established: Set<String> = emptySet(),
) {
    fun with(narrowing: Narrowing): TypeEnv =
        if (narrowing.isEmpty()) this
        else copy(present = present + narrowing.present, narrowed = narrowed + narrowing.narrowed, established = established + narrowing.established)
}

/** What a governing condition shows (Reference §10): the paths present, the paths narrowed to a shape,
 *  and the tests established, keyed as [PresenceFacts.testKey] keys them, which a prohibition's condition is matched against. */
data class Narrowing(
    val present: Set<List<String>> = emptySet(),
    val narrowed: Map<List<String>, String> = emptyMap(),
    val established: Set<String> = emptySet(),
) {
    operator fun plus(other: Narrowing) = Narrowing(present + other.present, narrowed + other.narrowed, established + other.established)
    infix fun both(other: Narrowing) = Narrowing(
        present intersect other.present,
        narrowed.filter { (path, shape) -> other.narrowed[path] == shape },
        established intersect other.established,
    )
    fun isEmpty() = present.isEmpty() && narrowed.isEmpty() && established.isEmpty()

    /** The same presence and narrowing kept under [name], as a `for each` name carries what its
     *  source shows about each element: `newKey` becomes `name.newKey`, and a path the filter's own
     *  `as` name [alias] roots moves under [name]. The tests established are dropped, since a
     *  prohibition's facts are read for `this` alone. */
    fun under(name: String, alias: String?): Narrowing {
        fun rekey(path: List<String>) = if (alias != null && path.firstOrNull() == alias) listOf(name) + path.drop(1) else listOf(name) + path
        return Narrowing(present.mapTo(linkedSetOf(), ::rekey), narrowed.mapKeys { (path, _) -> rekey(path) })
    }

    companion object {
        val NONE = Narrowing()

        /** A path and every path on the way to it: `site.region.name` with `site.region` and `site`. */
        fun stepsOf(path: List<String>): Set<List<String>> = path.indices.mapTo(linkedSetOf()) { last -> path.take(last + 1) }
    }
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
        is FunCall -> callType(expr, scope, env)
        is AggCall -> aggregateType(expr, scope, env)
        is Binary -> if (expr.op in ARITHMETIC_OPS) arithmeticType(expr, scope, env) else VType.Bool
        is NotExpr, is IsExpr, is ExistsExpr -> VType.Bool
        is IfExpr -> join(
            of(expr.thenExpr, scope, env.with(narrowingOf(expr.condition, holds = true, scope, env))),
            of(expr.elseExpr, scope, env.with(narrowingOf(expr.condition, holds = false, scope, env))),
        )
        // a walk follows a relationship of the record a bare name is a member of, the innermost element
        is WalkExpr -> model.walkType(expr, scope)
        is PathExpr -> pathType(expr, scope, env)
        is Access -> accessType(expr, scope, env)
        is SetExpr -> recordElement(expr.collection, scope, env)?.let { VType.Coll(it) } ?: valueCollectionType(expr.collection, scope, env)
        is SingularFor -> selectedRecord(expr, expr.shape, scope, env)
    }

    /** A call's result, from the signature its arguments match. A result absent only over an empty
     *  collection is present where a test shows the collection is not empty (Reference §10). */
    private fun callType(call: FunCall, scope: String, env: TypeEnv): VType? {
        val resolved = resolveCall(call, scope, env) as? FunctionResolver.Outcome.Ok ?: return null
        val result = resolved.result.takeIf { it != VType.Unknown } ?: return null
        return if (absentOnlyOverEmptyShown(call, resolved, scope, env)) bare(result) else result
    }

    /** Whether [call] is absent only when its collection is empty, and that collection is shown not empty. */
    private fun absentOnlyOverEmptyShown(call: FunCall, resolved: FunctionResolver.Outcome.Ok, scope: String, env: TypeEnv): Boolean {
        if (!resolved.signature.absentOnlyWhenEmpty || resolved.argTypes.any { it is VType.Optional }) return false
        val collection = collectionTested(call.args.singleOrNull() ?: return false) ?: return false
        return notEmptyPath(collection, scope, env) in scopeEnv(env, scope).present
    }

    // ── a selection shown present (Reference §10) ────────────────────────────────

    /** The one record a selection chooses: absent when nothing matched, unless a test shows something does. */
    private fun selectedRecord(selection: Expr, shape: String, scope: String, env: TypeEnv): VType =
        VType.Inst(shape).let { record -> if (selectionShown(selection, scope, env)) record else VType.Optional(record) }

    /** Whether a test in [env] or the scope's own condition shows [selection] matched: an `is some` on the
     *  selection as written, or a test that the collection it selects from is not empty. */
    private fun selectionShown(selection: Expr, scope: String, env: TypeEnv): Boolean {
        val present = scopeEnv(env, scope).present
        if (readRoot(selection) in present) return true
        val collection = collectionSelectedFrom(selection) ?: return false
        return notEmptyPath(collection, scope, env) in present
    }

    /** The collection a selection chooses its record from; null when [expr] is not a selection. */
    fun collectionSelectedFrom(expr: Expr): CollectionExpr? = when {
        expr is AggCall && (expr.name == "first" || expr.name == "latest") -> expr.collection
        expr is SingularFor -> CollectionExpr(listOf(Binding(ShapeForSource(expr.shape, expr.forExpr))))
        else -> null
    }

    /** The collection an `is empty` test, an `exists`, or a function over a `many` reads; null for anything else. */
    private fun collectionTested(expr: Expr): CollectionExpr? = when (expr) {
        is PathExpr -> CollectionExpr(listOf(Binding(expr)))
        is SetExpr -> expr.collection
        is ExistsExpr -> expr.collection ?: CollectionExpr(listOf(Binding(ShapeForSource(expr.shape!!, expr.forExpr!!))))
        else -> null
    }

    /** The name the steps of a read through a non-path expression are kept under, as an alias keeps
     *  those of a path: the expression as written, `first(upload.rows by key)`. */
    private fun readRoot(target: Expr): List<String> = listOf(Printer.expr(Access(target, emptyList())))

    /**
     * The entry a collection shown not empty is kept under in [TypeEnv.present], beside the paths
     * shown present. A test and a selection meet by spelling: a relationship by its path, with or
     * without `this`, and any other collection as written. A bare name inside a filter over
     * another shape names that shape's member, and is kept apart from the subject's.
     */
    private fun notEmptyPath(collection: CollectionExpr, scope: String, env: TypeEnv): List<String> {
        val relationship = collection.bindings.singleOrNull()?.takeIf { it.alias == null && collection.where == null }?.source as? PathExpr
        val spelled = relationship?.let { relationshipSpelled(it, scope, env) } ?: Printer.expr(SetExpr(collection))
        return listOf("not empty: $spelled")
    }

    private fun relationshipSpelled(relationship: PathExpr, scope: String, env: TypeEnv): String {
        val names = PresenceFacts.namesOf(relationship).joinToString(".")
        val readOffSubject = relationship.root == "this" || relationship.root in env.aliases
        val insideFilterOverAnotherShape = env.thisScope != null && env.thisScope != scope
        return if (readOffSubject || !insideFilterOverAnotherShape) names else "$scope's $names"
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
        "first", "latest" -> recordElement(expr.collection, scope, env)?.let { element -> selectedRecord(expr, element, scope, env) }
        else -> null
    }

    /** The shape of the records a collection expression holds; null for a `many` of values or an unresolved source. */
    private fun recordElement(collection: CollectionExpr, scope: String, env: TypeEnv): String? =
        model.collectionElement(collection, readScope(scope, env))?.takeIf { it in model.shapes || it in model.refinements }

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
        val env = scopeEnv(outer, scope)
        val resolved = model.paths.resolve(path, readScope(scope, env))
        if (!resolved.resolves) return null
        return when (val start = resolved.start) {
            is PathStart.Subject, is PathStart.RecordName -> typeAfterRecord(start, resolved, env)
            is PathStart.Element -> typeAlong(resolved.steps, emptyList(), optional = false, env)
            else -> null
        }
    }

    /** The [Scope] a read at [element] in [env] is resolved at: `this` is the outer subject, an alias its binding. */
    private fun readScope(element: String, env: TypeEnv): Scope =
        Scope.of(element, env.aliases, env.thisScope ?: element)
            .copy(valueNames = env.scalarAliases.keys)
            .narrowedBy(env.narrowed)

    /** A path that starts at a record, `this` or an alias: the record itself when no name follows, narrowed when a test narrowed it. */
    private fun typeAfterRecord(start: PathStart, resolved: ResolvedPath, env: TypeEnv): VType? {
        val prefix = if (start is PathStart.RecordName) listOf(start.name) else emptyList()
        if (resolved.steps.isNotEmpty()) return typeAlong(resolved.steps, prefix, optional = false, env)
        return resolved.endType?.instanceShape()?.let { shape -> VType.Inst(shape) }
    }

    /**
     * The type at the end of [steps], read after the names [prefix]: absent when a step is optional and
     * not shown present; a step narrowed to a shape reads as that shape; a step through a `many`,
     * `origin.components.source`, reads the field off every record, a collection of what it holds.
     */
    private fun typeAlong(steps: List<Step>, prefix: List<String>, optional: Boolean, env: TypeEnv): VType? {
        var type: VType? = null
        var absent = optional
        var throughMany = false
        val names = prefix.toMutableList()
        for (step in steps) {
            type = memberType(step.member).takeIf { it != VType.Unknown } ?: return null
            names.add(step.name)
            if (type is VType.Optional && names !in env.present && names !in env.narrowed) absent = true
            if (step === steps.last()) {
                if (names in env.narrowed) type = VType.Inst(env.narrowed.getValue(names))
                break
            }
            if (bare(type) is VType.Coll) throughMany = true
        }
        val read = type?.let { if (absent) VType.Optional(bare(it)) else bare(it) } ?: return null
        return if (throughMany) asCollection(read) else read
    }

    /** The environment a read in [scope] sees: what the scope's own `where` shows, and what the
     *  prohibitions the rules cannot move show there given the tests established (Reference §21). */
    private fun scopeEnv(outer: TypeEnv, scope: String): TypeEnv {
        val name = outer.thisScope ?: scope
        val env = outer.with(narrowingOf(name))
        val shown = model.presenceFacts.presentIn(name, env.established)
        return if (shown.isEmpty()) env else env.with(Narrowing(present = shown))
    }

    /** The names a path's present and narrowed steps are kept under: the alias with them when the
     *  path starts at one, `r.field` under `r`, and the steps alone when it starts at `this`. */
    private fun aliasPrefix(path: PathExpr, env: TypeEnv): List<String> = if (path.root in env.aliases) listOf(path.root) else emptyList()

    /**
     * The first step of [path] read with a plain dot while it may be absent (Reference §10; checks.md
     * V11): a member declared optional that no test in [env] shows present, with `.` after it; null
     * when every dot of the path is licensed, or the path reads nothing the model knows.
     */
    fun untestedStep(path: PathExpr, scope: String, outer: TypeEnv): List<String>? {
        if (path.root in outer.scalarAliases) return null
        val env = scopeEnv(outer, scope)
        val startsAtRecord = path.root == "this" || path.root in env.aliases
        val start: String? = when {
            path.root in env.aliases -> env.aliases[path.root]
            path.root == "this" -> env.thisScope ?: scope
            else -> scope
        }
        val steps = if (startsAtRecord) path.segs else listOf(Seg(path.root, viaQdot = false)) + path.segs
        return untestedStepAmong(steps, start, aliasPrefix(path, env), env)
    }

    /**
     * The same for a read through a selection or another non-path expression (Reference §10): the
     * dot directly after it needs no test, the rest of the path being absent when it matched
     * nothing, and every step after that is checked as a path's is.
     */
    fun untestedStepAfter(access: Access, scope: String, outer: TypeEnv): List<String>? {
        val target = bareOrNull(of(access.target, scope, outer)) as? VType.Inst ?: return null
        return untestedStepAmong(access.segs, target.shape, readRoot(access.target), accessEnv(access, scope, outer))
    }

    /** The first of [steps], read from [start] and kept under [prefix], that a plain dot follows while it may be absent. */
    private fun untestedStepAmong(steps: List<Seg>, start: String?, prefix: List<String>, env: TypeEnv): List<String>? {
        var current = start
        val names = prefix.toMutableList()
        var mayBeAbsent = false
        for (step in steps) {
            if (mayBeAbsent && !step.viaQdot) return names.toList()
            val member = model.membersOf(current ?: return null)[step.name] ?: return null
            names.add(step.name)
            val type = memberType(member)
            mayBeAbsent = type is VType.Optional && names !in env.present && names !in env.narrowed
            current = env.narrowed[names] ?: bare(type).instanceShape() ?: (bare(type) as? VType.Coll)?.shape
        }
        return null
    }

    private fun accessType(expr: Access, scope: String, env: TypeEnv): VType? {
        val target = of(expr.target, scope, env) ?: return null
        if (target is VType.Coll) return collectionAccessType(target, expr.segs, env)
        return along(bare(target).instanceShape(), expr.segs, readRoot(expr.target), optional = target is VType.Optional, accessEnv(expr, scope, env))
    }

    /** Whether a step of [expr] after its target is declared optional and not shown present: the
     *  read is then absent by declaration, whatever its target is (Reference §7). */
    private fun stepAbsentByDeclaration(expr: Access, scope: String, env: TypeEnv): Boolean {
        val target = bareOrNull(of(expr.target, scope, env)) as? VType.Inst ?: return false
        return along(target.shape, expr.segs, readRoot(expr.target), optional = false, accessEnv(expr, scope, env)) is VType.Optional
    }

    /** What the steps after [expr]'s target are read under: the scope's own condition, and, after a
     *  selection, what the filter of its collection shows about the record selected,
     *  `first(rows where newKey is some by key).newKey` (Reference §10). */
    private fun accessEnv(expr: Access, scope: String, outer: TypeEnv): TypeEnv {
        val env = scopeEnv(outer, scope)
        val collection = collectionSelectedFrom(expr.target) ?: return env
        val element = recordElement(collection, scope, outer) ?: return env
        return env.with(elementNarrowing(collection, element, readRoot(expr.target).single(), env.aliases.keys))
    }

    /**
     * What a collection shows about each of its elements, kept under [name] (Reference §14): the
     * element scope's own `where` when it is a refinement, and the tests the collection's filter
     * writes on the element. A test the filter writes on the outer subject, `this.note is some`,
     * or on an outer `as` name, says nothing about the element and is left out.
     */
    fun elementNarrowing(collection: CollectionExpr?, element: String, name: String, outerAliases: Set<String>): Narrowing {
        val filterAlias = collection?.bindings?.singleOrNull()?.alias
        val elementTests = PresenceFacts.conjuncts(collection?.where).filter { conjunct ->
            val root = testedRoot(conjunct)
            root != null && root != "this" && (root == filterAlias || root !in outerAliases)
        }
        val filter = elementTests.fold(Narrowing.NONE) { shown, test -> shown + narrowingOf(test, holds = true, element, TypeEnv(thisScope = element)) }
        return (narrowingOf(element) + filter).under(name, filterAlias)
    }

    /** The root name of the path an `is` test reads, through a `not`; null for any other test. */
    private fun testedRoot(conjunct: Expr): String? = when (conjunct) {
        is IsExpr -> (conjunct.subject as? PathExpr)?.root
        is NotExpr -> testedRoot(conjunct.inner)
        else -> null
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
     * path not shown present, a field or step declared optional read off any record, `none`, a
     * function result that is optional, through arithmetic and the branches of a conditional.
     *
     * A selection that matches nothing, `first`, `latest`, `(Shape for expr)`, is absent by data.
     * With [selectionsCount], as for a derived or captured property, one no test shows to match
     * counts; without, as for a `from` block and an assignment, Reference §14 leaves it to the
     * runtime. The `?.` directly after a selection is the plain dot and counts for nothing.
     */
    fun absenceDeclared(expr: Expr, scope: String, env: TypeEnv, selectionsCount: Boolean = false): Boolean {
        fun absent(part: Expr, partEnv: TypeEnv = env) = absenceDeclared(part, scope, partEnv, selectionsCount)
        return when (expr) {
            is NoneLit -> true
            is PathExpr -> of(expr, scope, env) is VType.Optional
            is Access -> absent(expr.target) || stepAbsentByDeclaration(expr, scope, env)
            is AggCall, is SingularFor -> selectionsCount && collectionSelectedFrom(expr) != null && of(expr, scope, env) is VType.Optional
            // a function is absent when an argument is, or when its signature says so, `number(text)`
            is FunCall -> expr.args.any { absent(it) } || resultAbsentByDeclaration(expr, scope, env)
            is UnaryMinus -> absent(expr.inner)
            is Binary -> expr.op in ARITHMETIC_OPS && (absent(expr.left) || absent(expr.right))
            is IfExpr -> absent(expr.thenExpr, env.with(narrowingOf(expr.condition, holds = true, scope, env))) ||
                absent(expr.elseExpr, env.with(narrowingOf(expr.condition, holds = false, scope, env)))
            else -> false
        }
    }

    /** The first selection [expr] reads that no test shows to match, through the reads, calls,
     *  arithmetic, and branches [absenceDeclared] goes through; null when there is none. */
    fun unshownSelection(expr: Expr, scope: String, env: TypeEnv): Expr? = when (expr) {
        is Access -> unshownSelection(expr.target, scope, env)
        is AggCall, is SingularFor -> expr.takeIf { collectionSelectedFrom(it) != null && of(it, scope, env) is VType.Optional }
        is FunCall -> expr.args.firstNotNullOfOrNull { unshownSelection(it, scope, env) }
        is UnaryMinus -> unshownSelection(expr.inner, scope, env)
        is Binary -> if (expr.op in ARITHMETIC_OPS) unshownSelection(expr.left, scope, env) ?: unshownSelection(expr.right, scope, env) else null
        is IfExpr -> unshownSelection(expr.thenExpr, scope, env.with(narrowingOf(expr.condition, holds = true, scope, env)))
            ?: unshownSelection(expr.elseExpr, scope, env.with(narrowingOf(expr.condition, holds = false, scope, env)))
        else -> null
    }

    /** The test that shows [selection] matches, as a spec writes it: `exists (Entry where key == this.entryKey)`. */
    fun existsTestFor(selection: Expr): Expr? {
        val collection = collectionSelectedFrom(selection) ?: return null
        val related = collection.bindings.singleOrNull()?.takeIf { collection.where == null }?.source as? ShapeForSource
        return if (related != null) ExistsExpr(shape = related.shape, forExpr = related.forExpr) else ExistsExpr(collection = collection)
    }

    private fun resultAbsentByDeclaration(call: FunCall, scope: String, env: TypeEnv): Boolean {
        val resolved = resolveCall(call, scope, env) as? FunctionResolver.Outcome.Ok ?: return false
        return resolved.signature.optionalResult && !absentOnlyOverEmptyShown(call, resolved, scope, env)
    }

    private val narrowingCache = HashMap<String, Narrowing>()

    /** What a refinement's predicate shows for every member read in its scope (Reference §8):
     *  `Kind where parentName is some` makes `parentName` present, `Comment where target is Post`
     *  reads `target` as a `Post`. A refinement over another inherits what it shows; an `or`
     *  keeps only what both sides show. */
    fun narrowingOf(scope: String): Narrowing {
        narrowingCache[scope]?.let { return it }
        // a predicate's own `is not` test asks the path's type, which asks this scope's narrowing:
        // while it is being computed, the scope narrows nothing
        if (!narrowingInProgress.add(scope)) return Narrowing.NONE
        val narrowing = try { model.refinements[scope]?.let { narrowingOf(it.expr, scope) } ?: Narrowing.NONE } finally { narrowingInProgress.remove(scope) }
        narrowingCache[scope] = narrowing
        return narrowing
    }

    private val narrowingInProgress = mutableSetOf<String>()

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
    fun narrowingOf(cond: Expr, holds: Boolean, scope: String, env: TypeEnv): Narrowing =
        testNarrowingOf(cond, holds, scope, env) + selectionNarrowingOf(cond, holds, scope, env)

    /**
     * What one test shows about collections and selections (Reference §10): `C is empty` failing,
     * `C is not empty` holding, and `exists C` holding show `C` not empty, so a selection over `C`
     * matched; `is some` holding on a selection, or on a read through one, shows that read and
     * every step on the way to it present.
     */
    private fun selectionNarrowingOf(cond: Expr, holds: Boolean, scope: String, env: TypeEnv): Narrowing = when {
        cond is NotExpr && cond.inner is ExistsExpr -> selectionNarrowingOf(cond.inner, !holds, scope, env)
        cond is ExistsExpr && holds -> notEmptyNarrowing(cond, scope, env)
        cond is IsExpr && saysNotEmpty(cond, holds) -> notEmptyNarrowing(cond.subject, scope, env)
        cond is IsExpr && cond.subject !is PathExpr && saysPresent(cond, holds) -> presentReadNarrowing(cond.subject, scope, env)
        else -> Narrowing.NONE
    }

    private fun saysNotEmpty(test: IsExpr, holds: Boolean) = if (holds) test.kind == "notEmpty" else test.kind == "empty"

    private fun saysPresent(test: IsExpr, holds: Boolean) = if (holds) test.kind == "some" else test.kind == "none"

    private fun notEmptyNarrowing(tested: Expr, scope: String, env: TypeEnv): Narrowing =
        collectionTested(tested)?.let { collection -> Narrowing(present = setOf(notEmptyPath(collection, scope, env))) } ?: Narrowing.NONE

    /** `first(rows by key).parent is some` shows the selection, and `parent` read off it, present;
     *  a selection shown present shows its collection not empty. */
    private fun presentReadNarrowing(read: Expr, scope: String, env: TypeEnv): Narrowing {
        val target = (read as? Access)?.target ?: read
        val steps = readRoot(target) + ((read as? Access)?.segs?.map { seg -> seg.name } ?: emptyList())
        val selected = collectionSelectedFrom(target)?.let { collection -> setOf(notEmptyPath(collection, scope, env)) } ?: emptySet()
        return Narrowing(present = Narrowing.stepsOf(steps) + selected)
    }

    private fun testNarrowingOf(cond: Expr, holds: Boolean, scope: String, env: TypeEnv): Narrowing = when {
        // `x is not A` holding is `x is A` failing (Reference §10)
        cond is NotExpr && cond.inner is IsExpr -> narrowingOf(cond.inner, !holds, scope, env)
        cond is Binary && cond.op == "and" && holds -> narrowingOf(cond.left, true, scope, env) + narrowingOf(cond.right, true, scope, env)
        // an `or` failing is both sides failing
        cond is Binary && cond.op == "or" && !holds -> narrowingOf(cond.left, false, scope, env) + narrowingOf(cond.right, false, scope, env)
        // a comparison holding shows every path it reads present, since an absent value equals nothing,
        // is in nothing, and satisfies no ordered comparison (Reference §10); `!=` failing is `==` holding
        cond is Binary && (cond.op in PRESENCE_SHOWING_COMPARISONS && holds || cond.op == "!=" && !holds) ->
            Narrowing(present = pathsCompared(cond, scope, env), established = if (holds) setOf(PresenceFacts.testKey(cond)) else emptySet())
        // `x not in [...]` failing is `x in [...]` holding
        cond is NotExpr && cond.inner is Binary && cond.inner.op == "in" -> narrowingOf(cond.inner, !holds, scope, env)
        cond is IsExpr && cond.subject is PathExpr -> {
            val path = namesOf(cond.subject)
            when {
                cond.kind == "some" && holds || cond.kind == "none" && !holds ->
                    Narrowing(present = Narrowing.stepsOf(path), established = setOf(PresenceFacts.testKey(IsExpr(cond.subject, "some"))))
                cond.kind == "some" || cond.kind == "none" ->
                    Narrowing(established = setOf(PresenceFacts.testKey(IsExpr(cond.subject, "none"))))
                cond.kind == "refinement" -> refinementNarrowing(cond, holds, scope, env)
                else -> Narrowing.NONE
            }
        }
        holds -> Narrowing(established = setOf(PresenceFacts.testKey(cond)))
        else -> Narrowing.NONE
    }

    /** Every step of every path a comparison holding shows present: `attr?.kind == "relation"` shows `attr` and
     *  `attr.kind`. Two absent values compare equal (Reference §10), so an equality shows a side present only
     *  when the other side cannot be absent; `in` and the ordered comparisons fail on any absent operand. */
    private fun pathsCompared(comparison: Binary, scope: String, env: TypeEnv): Set<List<String>> {
        val equality = comparison.op == "==" || comparison.op == "!="
        fun shown(side: Expr, other: Expr) = side is PathExpr && !(equality && absenceDeclared(other, scope, env))
        return listOf(comparison.left to comparison.right, comparison.right to comparison.left)
            .filter { (side, other) -> shown(side, other) }
            .flatMapTo(linkedSetOf()) { (side, _) -> Narrowing.stepsOf(namesOf(side as PathExpr)) }
    }

    /** `x is Post` read as a `Post` where it holds, and over a union as the rest where it fails (`velle.spec.ShapeNarrowing`). */
    private fun refinementNarrowing(test: IsExpr, holds: Boolean, scope: String, env: TypeEnv): Narrowing {
        val narrowed = model.narrowing.shownBy(test, holds, readScope(scope, scopeEnv(env, scope)))
        if (narrowed.isEmpty()) return Narrowing.NONE
        return Narrowing(narrowed = narrowed, established = if (holds) setOf(PresenceFacts.testKey(test)) else emptySet())
    }

    /** A member's type: its declaration's, or, for a derived property that states none, its expression's (Reference §7). */
    fun memberType(member: MemberInfo): VType =
        member.derived?.takeIf { it.type == null }?.let { model.resolvedDerivedType(member.owner, it) } ?: member.type

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
        /** The comparisons that hold only on present operands (Reference §10). */
        val PRESENCE_SHOWING_COMPARISONS = setOf("==", "in", "<", "<=", ">", ">=")
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
