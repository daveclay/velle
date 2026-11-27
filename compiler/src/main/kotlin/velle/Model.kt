package velle

import velle.spec.PathResolver
import velle.spec.PathStart
import velle.spec.Scope
import velle.spec.ShapeNarrowing

import velle.ext.VelleFunction
import velle.ext.VelleType

/**
 * Resolved-spec model: symbol tables, refinement→base resolution, member lookup
 * (including refinement properties and inferred inverse relationships), and the
 * read/write summaries the validator's checks (`validation/`) consume.
 *
 * Inverse relationships (Reference §6): `LineItem.invoice: one Invoice` gives
 * `Invoice` a derived collection member named decapitalize(LineItem) + "s" =
 * `lineItems`. The naming rule is a convention the Reference uses by example
 * (`invoices`, `payments`) without stating it — flagged for the docs.
 */
class Model(rawDecls: List<Decl>, baseRegistry: TypeRegistry = TypeRegistry.default) {

    /** The types the spec declares by their words, `Role is one of ...` (Reference §5), by name. */
    val enums: Map<String, EnumType> = rawDecls.filterIsInstance<EnumDecl>().distinctBy { it.name }
        .filter { !TypeRegistry.isBuiltin(it.name) }
        .associate { declared -> declared.name to EnumType(declared.name, declared.words.distinct()) }

    /** The classpath's registry with the spec's own declared types beside the libraries' (Reference §5):
     *  one registry, so every consumer dispatching on it reads a declared type as it reads `Money`. */
    val registry: TypeRegistry =
        if (enums.isEmpty()) baseRegistry
        else TypeRegistry(baseRegistry.byQualifiedName.values + enums.values, baseRegistry.registeredFunctions + enumReaders())

    /** One reading function per declared type, `role(text)`, leaving out one that would take a builtin's name (E1, below). */
    private fun enumReaders(): List<VelleFunction> =
        enums.values.filter { readerNameFree(it.name) }.map { EnumReader(it) }

    private fun readerNameFree(typeName: String): Boolean {
        val reader = EnumReader.readerName(typeName)
        return reader !in TypeRegistry.BUILTIN_FUNCTION_NAMES && reader !in TypeRegistry.RESERVED_FUNCTION_NAMES
    }

    /** The `is` clauses resolved (Reference §9, "Sharing a field across shapes"): takers carry
     *  the taken members, and each taken shape is a static union over its takers. */
    private val shared = SharedShapes(rawDecls)

    /** The shapes other shapes take with `is`, by name, as declared; none has records of its own. */
    val sharedShapes: Map<String, ShapeDecl> get() = shared.taken

    private val allDecls: List<Decl> = shared.rewritten

    val diagnostics = mutableListOf<Diagnostic>()

    /** The spec's `uses` lines (Reference §5, extension types). */
    val uses: List<UsesDecl> = rawDecls.filterIsInstance<UsesDecl>()

    /** The declarations, with `many X` read as an owned set of values where `X` is a
     *  registered type and not a shape: the parser cannot tell `many Tag` from `many Customer`. */
    val decls: List<Decl>

    /** The registered type each name in scope resolves to: `uses` aliases and picks first,
     *  then every registered type under its own name unless a `uses` line renamed it or
     *  two libraries register the name. Built by [resolveTypeScope]. */
    val typeScope: Map<String, VelleType<*>>

    /** Names two registered types share with no `uses` line picking one: naming one is E1. */
    private val ambiguousTypeNames: Map<String, List<VelleType<*>>>

    /** The function each name a spec calls resolves to, built as [typeScope] is: `uses` first,
     *  then every builtin and registered function under its own name. */
    val functionScope: Map<String, VelleFunction>
    private val ambiguousFunctionNames: Map<String, List<VelleFunction>>

    /** The spec's name for a registered type, for diagnostics and generated prose. */
    fun specNameOf(type: VelleType<*>): String =
        typeScope.entries.firstOrNull { it.value === type }?.key ?: type.qualifiedName

    /** The registered type behind a [VType.Ext], or the type a stored field of [t] holds. */
    fun extTypeOf(t: VType): VelleType<*>? = when (t) {
        is VType.Ext -> registry[t.qualifiedName]
        is VType.Optional -> extTypeOf(t.inner)
        is VType.CollS -> typeScope[t.name]
        else -> null
    }

    val shapes = linkedMapOf<String, ShapeDecl>()
    val refinements = linkedMapOf<String, RefinementDecl>()
    /** The unions narrowing leaves behind, by their spelling, never generated; see [residualOf]. */
    private val residuals = linkedMapOf<String, RefinementDecl>()
    val rules = linkedMapOf<String, RuleDecl>()
    val nevers = mutableListOf<NeverDecl>()
    /** externally committable shapes — the `act` declarations (Reference §22) */
    val exposed = linkedSetOf<String>()

    /** The transient shapes: every `transient act`, an input to the state and not a member
     *  of it (Reference §4), and every part of such an act's closure, which arrives with
     *  the act and is removed with it (Reference §6, "Inline part creation"). */
    val transients = mutableSetOf<String>()

    /** For each transient shape, the transient act whose closure it belongs to: the act
     *  itself for an act, the act for a part. Two shapes of one closure may reference
     *  each other; nothing outside the closure may reference either. */
    val transientClosureOf = mutableMapOf<String, String>()

    /** How a diagnostic names a transient shape: "act" for the act, "part" for a part of its closure. */
    fun transientKind(shape: String): String = if (transientClosureOf[shape] == shape) "act" else "part"

    /** Whether both shapes fall in one transient act's closure. */
    fun sameTransientClosure(one: String, other: String): Boolean =
        transientClosureOf[one] != null && transientClosureOf[one] == transientClosureOf[other]

    /** raw `with` declarations per act, resolved lazily by [closures] */
    private val withDecls = mutableMapOf<String, List<WithEntry>>()

    /** the spec-wide `visible to` default, declared at the top level (Reference §22) */
    private var visibilityDefaultDecl: VisibleClause? = null

    init {
        diagnostics.addAll(registry.diagnostics)
        diagnostics.addAll(shared.diagnostics)
        val (scope, ambiguous) = resolveTypeScope()
        typeScope = scope
        ambiguousTypeNames = ambiguous
        val (fscope, fambiguous) = resolveFunctionScope()
        functionScope = fscope
        ambiguousFunctionNames = fambiguous
        val shapeNames = allDecls.mapNotNull { (it as? ShapeDecl)?.name ?: (it as? RefinementDecl)?.name }.toSet()
        fun manyOfType(t: TypeRef, tolerates: String? = null): TypeRef =
            if (t is RelType && t.many && t.shape !in shapeNames && t.shape in scope)
                ScalarType(t.shape, optional = false, many = true, ordered = tolerates == "duplication")
            else t
        fun fix(m: Member): Member = when (m) {
            is StoredProp -> m.copy(type = manyOfType(m.type, m.tolerates))
            is DerivedProp -> m.copy(type = m.type?.let { manyOfType(it) })
            else -> m
        }
        val lookups = KeyLookups(allDecls.map { d -> when (d) {
            is ShapeDecl -> d.copy(members = d.members.map(::fix))
            is RefinementDecl -> d.copy(members = d.members.map(::fix))
            else -> d
        } })
        diagnostics.addAll(lookups.diagnostics)
        decls = lookups.rewritten
        for (d in decls) when (d) {
            is UsesDecl -> {}
            is EnumDecl -> checkEnumDecl(d)
            is ShapeDecl -> {
                if (!register(d.name)) continue
                shapes[d.name] = d
                if (d.exposed) exposed.add(d.name)
                if (d.transient) { transients.add(d.name); transientClosureOf[d.name] = d.name }
                if (d.with.isNotEmpty()) withDecls[d.name] = d.with
            }
            is RefinementDecl -> if (register(d.name)) refinements[d.name] = d
            is RuleDecl ->
                if (rules.put(d.name, d) != null)
                    error("F1", "duplicate rule '${d.name}'")
            is NeverDecl -> nevers.add(d)
            is VisibilityDefault -> {
                if (visibilityDefaultDecl != null) error("F3", "a second spec-wide 'visible to' — the default is written once")
                visibilityDefaultDecl = d.clause
            }
        }
        // an act's `by <Refinement>` is the role gate in the header (Reference §22,
        // "The actor"): `act X by Administrator` means `by <base>` plus
        // `X requires actor to be Administrator`, one desugaring, so the door,
        // the rejection data, and every analysis see the requirement a body
        // line would have stated. A base shape or static union after `by` is
        // type-checked at the boundary and gates nothing here.
        for ((name, shape) in shapes) {
            val a = shape.actor ?: continue
            if (a.shape !in refinements || isStaticUnion(a.shape)) continue
            val predicate = IsExpr(PathExpr(a.name), "refinement", a.shape, toBe = true)
            nevers.add(NeverDecl(RefName(name, complementOf(predicate)), requirement = Requirement(name, predicate),
                fromActorClause = true))
        }
        // a key (Reference §21, "Keys") is the count `never` an author wrote by hand
        // before: one emitted per key, quoting the key, so the door, the rejection
        // data, and every analysis see the prohibition the word stands for
        for ((name, shape) in shapes) for (key in shape.keys()) keyNever(name, shape, key)?.let(nevers::add)
    }

    /** The shape's keys (Reference §21, "Keys"), both spellings, in declaration order. */
    fun keysOf(shape: String): List<UniqueKey> = shapes[shape]?.keys().orEmpty()

    /**
     * The count `never` a key stands for: `never (S where count(S where f == this.f and ...) > 1)`,
     * with `f is some` guarding each optional field on both sides so absent values do not
     * collide. Null, with a V35 diagnostic, when the key names a field that is not a stored
     * value of the shape.
     */
    private fun keyNever(shapeName: String, shape: ShapeDecl, key: UniqueKey): NeverDecl? {
        val declaration = if (key.onField)
            Printer.member(shape.members.filterIsInstance<StoredProp>().single { it.name == key.fields.single() })
        else Printer.member(key)
        val stored = shape.members.filterIsInstance<StoredProp>().associateBy { it.name }
        for (field in key.fields) {
            val prop = stored[field]
            val fault = when {
                prop == null && shape.members.any { memberName(it) == field } ->
                    "which is not a stored field: a key is over values the committer supplies"
                prop == null -> "which $shapeName does not declare"
                prop.type is ScalarType && prop.type.many || prop.type is RelType && prop.type.many ->
                    "which is a collection: a key is over single values"
                else -> null
            } ?: continue
            error("V35", "'$declaration' on $shapeName names '$field', $fault (Reference §21)")
            return null
        }
        val optional = key.fields.filter { isOptional(stored.getValue(it).type) }
        val sibling = PathExpr(shapeName)
        val sameValues = key.fields.map { field ->
            val same = Binary("==", PathExpr(field), PathExpr("this", listOf(Seg(field, viaQdot = false))))
            if (field in optional) Binary("and", IsExpr(PathExpr(field), "some"), same) else same
        }
        val count = Binary(">", AggCall("count", CollectionExpr(listOf(Binding(sibling)), conjunction(sameValues))), IntLit(1))
        val guards = optional.map { IsExpr(PathExpr(it), "some") }
        return NeverDecl(RefName(shapeName, conjunction(guards + count)), keyDeclaration = declaration)
    }

    private fun memberName(member: Member): String? = when (member) {
        is StoredProp -> member.name
        is DerivedProp -> member.name
        is TimestampProp -> member.name
        is ActorProp -> member.name
        else -> null
    }

    private fun isOptional(type: TypeRef): Boolean = when (type) {
        is ScalarType -> type.optional
        is RelType -> type.optional
    }

    private fun conjunction(terms: List<Expr>): Expr = terms.reduce { left, right -> Binary("and", left, right) }

    /** The actor clause of an act, or null (Reference §22, "The actor"). */
    fun actorOf(shape: String): ActorProp? = shapes[shape]?.actor

    // ── visibility (Reference §22, "The actor"; OQ33 §5.7–§5.10) ─────────────

    /** The spec-wide `visible to` default, or null. */
    val visibilityDefault: VisibleClause? get() = visibilityDefaultDecl

    /** A spec has authorization once any act declares `by` or any `visible to` is written:
     *  from then on every readable shape declares its visibility (checks V32). */
    val hasAuthorization: Boolean by lazy {
        visibilityDefault != null || shapes.values.any { s ->
            s.actor != null || s.members.any { it is VisibleClause || fieldClausesOf(it).isNotEmpty() }
        } || refinements.values.any { r -> r.members.any { fieldClausesOf(it).isNotEmpty() } }
    }

    fun fieldClausesOf(m: Member): List<VisibleClause> = when (m) {
        is StoredProp -> m.visible
        is DerivedProp -> m.visible
        is TimestampProp -> m.visible
        else -> emptyList()
    }

    /** A base shape's own record-level clauses, as written (empty when it says nothing). */
    fun ownRecordVisibility(shape: String): List<VisibleClause> =
        shapes[shape]?.members?.filterIsInstance<VisibleClause>().orEmpty()

    /** The record-level clauses that apply to a base shape: its own, else the spec-wide
     *  default, else none — none meaning visible to every viewer. A shape's own clauses
     *  replace the default rather than add to it. A refinement takes its base's. */
    fun recordVisibility(scope: String): List<VisibleClause> {
        val base = baseOf(scope) ?: scope
        val own = ownRecordVisibility(base)
        if (own.isNotEmpty()) return own
        return listOfNotNull(visibilityDefault)
    }

    /** Every base shape a `visible to` clause (or the default) names a viewer by. */
    val viewerShapes: Set<String> by lazy {
        val named = mutableListOf<String>()
        visibilityDefault?.shape?.let { named.add(it) }
        shapes.values.forEach { s -> s.members.forEach { m ->
            if (m is VisibleClause) m.shape?.let { named.add(it) }
            fieldClausesOf(m).forEach { c -> c.shape?.let { named.add(it) } }
        } }
        refinements.values.forEach { r -> r.members.forEach { m -> fieldClausesOf(m).forEach { c -> c.shape?.let { named.add(it) } } } }
        named.flatMap { basesOf(it) }.toSet()
    }

    private fun register(name: String): Boolean {
        if (name in shapes || name in refinements) {
            error("F1", "duplicate shape/refinement name '$name'")
            return false
        }
        typeScope[name]?.let { t ->
            if (t is EnumType) error("F1", "'$name' is both a shape and a type declared with '$name is one of ...'; rename one of them")
            else error("E1", "'$name' is both a shape in this spec and the registered type '${t.qualifiedName}'; " +
                "add `uses ${t.qualifiedName} as <Name>` to call the type something else here")
        }
        return true
    }

    /** What a `Role is one of ...` declaration must satisfy (Reference §5): one declaration per
     *  name, no builtin's name, each word once, and no two words sharing a Kotlin entry name. */
    private fun checkEnumDecl(d: EnumDecl) {
        if (TypeRegistry.isBuiltin(d.name)) { error("E1", "'${d.name} is one of ...': '${d.name}' is a builtin type name (Reference §5)"); return }
        val declared = enums.getValue(d.name)
        if (!readerNameFree(d.name))
            error("E1", "'${d.name} is one of ...': its reading function '${EnumReader.readerName(d.name)}' is a builtin function name; choose another name")
        if (declared.words != d.words) {
            if (d.words.size != d.words.distinct().size)
                d.words.groupBy { it }.filterValues { it.size > 1 }.keys.forEach { word -> error("F1", "'${d.name} is one of ...' lists \"$word\" twice") }
            else error("F1", "'${d.name}' is declared twice with 'is one of'; declare it once")
        }
        declared.words.groupBy { declared.entryName(it) }.filterValues { it.size > 1 }.values.forEach { words ->
            error("F1", "'${d.name} is one of ...': ${words.joinToString(" and ") { "\"$it\"" }} would be one entry, '${declared.entryName(words.first())}', in the generated Kotlin; spell one of them differently")
        }
    }

    // ── extension types (Reference §5; OQ65) ─────────────────────────────────

    private fun resolveTypeScope(): Pair<Map<String, VelleType<*>>, Map<String, List<VelleType<*>>>> {
        val scope = linkedMapOf<String, VelleType<*>>()
        val picked = mutableSetOf<String>()
        for (declared in enums.values) { scope[declared.name] = declared; picked.add(declared.qualifiedName) }
        for (u in uses) {
            val t = registry[u.qualified]
            if (t == null) {
                if (registry.function(u.qualified) != null) continue // a function; resolveFunctionScope reads it
                error("F1", "`uses ${u.qualified}`: no registered type or function has that name" +
                    (registry.named(u.qualified.substringAfterLast('.')).takeIf { it.isNotEmpty() }
                        ?.let { c -> "; the registry has ${c.joinToString { "'${it.qualifiedName}'" }}" } ?: ""))
                continue
            }
            if (TypeRegistry.isBuiltin(u.name))
                error("E1", "`uses ${u.qualified} as ${u.name}`: '${u.name}' is a builtin type name (Reference §5)")
            val prior = scope.put(u.name, t)
            if (prior != null && prior !== t)
                error("E1", "two `uses` lines bring '${prior.qualifiedName}' and '${t.qualifiedName}' into scope as '${u.name}'")
            picked.add(t.qualifiedName)
        }
        val ambiguous = linkedMapOf<String, List<VelleType<*>>>()
        for ((name, types) in registry.byQualifiedName.values.filter { it.qualifiedName !in picked }.groupBy { it.name }) {
            if (name in scope) continue // a `uses` line owns the name
            if (types.size == 1) scope[name] = types.single()
            else ambiguous[name] = types
        }
        return scope to ambiguous
    }

    private fun resolveFunctionScope(): Pair<Map<String, VelleFunction>, Map<String, List<VelleFunction>>> {
        val scope = linkedMapOf<String, VelleFunction>()
        val picked = mutableSetOf<String>()
        for (u in uses) {
            val f = registry.function(u.qualified) ?: continue
            if (u.name in TypeRegistry.BUILTIN_FUNCTION_NAMES)
                error("E1", "`uses ${u.qualified} as ${u.name}`: '${u.name}' is a builtin function name (Reference §5)")
            val prior = scope.put(u.name, f)
            if (prior != null && prior !== f)
                error("E1", "two `uses` lines bring '${prior.qualifiedName}' and '${f.qualifiedName}' into scope as '${u.name}'")
            picked.add(f.qualifiedName)
        }
        val ambiguous = linkedMapOf<String, List<VelleFunction>>()
        for ((name, fns) in registry.functions.values.filter { it.qualifiedName !in picked }.groupBy { it.name }) {
            if (name in scope) continue
            if (fns.size == 1) scope[name] = fns.single()
            else ambiguous[name] = fns
        }
        return scope to ambiguous
    }

    /** Resolves the name a call uses (Reference §5): a builtin or registered function, with
     *  F2/E1 and null when nothing in scope has the name. */
    fun resolveFunctionName(name: String, at: String): VelleFunction? {
        functionScope[name]?.let { return it }
        ambiguousFunctionNames[name]?.let { fns ->
            error("E1", "$at: '$name' names ${fns.size} registered functions (${fns.joinToString { "'${it.qualifiedName}'" }}); " +
                "add `uses <one of them>` to pick one")
            return null
        }
        error("F2", "$at: no builtin or registered function is named '$name' (Reference §5)")
        return null
    }

    /** Resolves a name in type position that is not a builtin (Reference §5): a registered
     *  type, with F1/E1 and null when nothing in scope has the name. */
    fun resolveTypeName(name: String, at: String): VelleType<*>? {
        typeScope[name]?.let { return it }
        ambiguousTypeNames[name]?.let { types ->
            error("E1", "$at: '$name' names ${types.size} registered types (${types.joinToString { "'${it.qualifiedName}'" }}); " +
                "add `uses <one of them>` to pick one")
            return null
        }
        if (name in shapes || name in refinements)
            error("F1", "$at: '$name' is a shape; a field holding a record is `one $name` or `many $name` (Reference §6)")
        else
            error("F1", "$at: no builtin or registered type is named '$name' (Reference §5)")
        return null
    }

    /** Every registered type a stored field of the spec holds (the types a store must bind, E4). */
    val storedExtensionTypes: Set<VelleType<*>> by lazy {
        shapes.values.flatMap { s -> s.members.filterIsInstance<StoredProp>() }
            .mapNotNull { (it.type as? ScalarType)?.let { st -> typeScope[st.name] } }.toSet()
    }

    fun error(code: String, message: String) = diagnostics.add(Diagnostic(code, message))

    // ── refinement → base shape ──────────────────────────────────────────────

    private val basesCache = java.util.concurrent.ConcurrentHashMap<String, Set<String>>()

    /**
     * The base shapes a shape-or-refinement's members are drawn from. One for a
     * base shape or an ordinary refinement; several for a cross-base union —
     * `or` composed across unrelated shapes (Reference §9). Empty,
     * with F1, when a name is unknown.
     */
    fun basesOf(name: String): Set<String> = basesCache[name] ?: when {
        name in shapes -> setOf(name)
        name in residuals -> basesOfExpr(residuals.getValue(name).expr)
        name in refinements -> {
            basesCache[name] = emptySet() // break cycles; V14 reports them
            basesOfExpr(refinements.getValue(name).expr)
        }
        else -> { error("F1", "unknown shape or refinement '$name'"); emptySet() }
    }.also { basesCache[name] = it } // a plain put: the cycle guard above must not win

    fun basesOfExpr(expr: RefExpr): Set<String> = when (expr) {
        is RefName -> basesOf(expr.name)
        is RefNot -> basesOfExpr(expr.inner)
        is RefAnd -> {
            val l = basesOfExpr(expr.left)
            val r = basesOfExpr(expr.right)
            val both = l intersect r
            // distinct bases are disjoint by construction: an instance has one
            // base, so `Car and Bicycle` has no members (Reference §9)
            if (both.isEmpty() && l.isNotEmpty() && r.isNotEmpty())
                diagnostics.add(Diagnostic("A2", "'${Printer.refExpr(expr)}' is provably empty — " +
                    "its operands have no base shape in common, and an instance has exactly one base", advisory = true))
            both
        }
        is RefOr -> basesOfExpr(expr.left) + basesOfExpr(expr.right)
    }

    /** The single base shape of a shape or ordinary refinement; null for a
     *  cross-base union (use [basesOf]) or an unresolvable name (F1). */
    fun baseOf(name: String): String? = if (name in sharedShapes) null else basesOf(name).singleOrNull()

    /** Whether [name] is a shape or a refinement the spec declares. Unlike [basesOf], asking
     *  about any other name reports nothing. */
    fun namesShapeOrRefinement(name: String): Boolean = name in shapes || name in residuals || name in refinements

    /** The single base shape of a shape or refinement the spec declares, as [baseOf] gives it; null for
     *  any other name, without reporting it. A name the author wrote and the spec does not declare is
     *  reported where it is written, so an analysis that meets it asks this and reports nothing again. */
    fun declaredBaseOf(name: String): String? = if (namesShapeOrRefinement(name)) baseOf(name) else null

    fun baseOfExpr(expr: RefExpr): String? = basesOfExpr(expr).singleOrNull()

    /** A refinement whose members span more than one base shape. */
    fun isCrossBase(name: String): Boolean = name in sharedShapes || basesOf(name).size > 1

    /**
     * A static union: a named refinement that is a pure `or` of base shapes (or
     * of static unions) with no predicate anywhere. Its member set is fixed by
     * declarations, never by data, so it is stable enough to be a field's
     * target type (Reference §6, the static-union field-type rule).
     */
    fun isStaticUnion(name: String): Boolean =
        name in sharedShapes || name in residuals || refinements[name]?.let { pureOr(it.expr) && basesOf(name).size > 1 } == true

    /**
     * The operands of a static union a test did not name (Reference §10): `target is not Post`
     * over `Content = Post or Photo or Clip` leaves `Photo or Clip`. One operand left is that
     * operand; more are a union of their own, held here and never generated, that narrowing
     * reads through; null when [named] is not an operand or nothing is left.
     */
    fun residualOf(union: String, named: String): String? {
        if (!isStaticUnion(union)) return null
        val rest = basesOf(union) - basesOf(named).takeIf { named in basesOf(union) || isStaticUnion(named) }.orEmpty()
        if (named !in basesOf(union) && !isStaticUnion(named)) return null
        if (rest.isEmpty() || rest.size == basesOf(union).size) return null
        if (rest.size == 1) return rest.single()
        val name = rest.joinToString(" or ")
        residuals.getOrPut(name) { RefinementDecl(name, rest.map { RefName(it) }.reduce<RefExpr, RefExpr> { l, r -> RefOr(l, r) }) }
        return name
    }


    private fun pureOr(e: RefExpr): Boolean = when (e) {
        is RefName -> e.where == null && (e.name in shapes || isStaticUnion(e.name))
        is RefOr -> pureOr(e.left) && pureOr(e.right)
        else -> false
    }

    /** The static union a cross-base refinement's subjects are drawn from,
     *  following `shape X = Union where ...` chains; null when there is none. */
    fun unionRootOf(name: String): String? = when {
        isStaticUnion(name) -> name
        else -> (refinements[name]?.expr as? RefName)?.name?.takeIf { it != name }?.let { unionRootOf(it) }
    }

    /** The scope a value of this type is viewed through: a base shape, or the
     *  static union it is drawn from (the generated view's name); null for a
     *  cross-base composite that chains to no static union. */
    fun viewScopeOf(name: String): String? =
        if (name in shapes) name else baseOf(name) ?: unionRootOf(name)

    /** Does a field declared `one <declared>` accept an instance of [actual]?
     *  Equal names, or an operand of the declared static union. */
    fun typeAccepts(declared: String, actual: String): Boolean =
        declared == actual || actual in basesOf(declared)

    /** The stored to-one fields of [shape] that accept a record of [targetShape]: the fields
     *  `Shape for <record>` may name (Reference §10, "Testing for related records with exists").
     *  Over a shape several shapes take with `is`, or a static union, a field counts only when
     *  every operand takes it from one declaration (Reference §9), so the test covers every
     *  taker through the one field; two takers each declaring a field of the type share none. */
    fun typeMatchedFields(shape: String, targetShape: String): List<String> =
        membersOf(shape).values
            .filter { member -> member.stored && !member.actor && !member.transient }
            .filter { member -> member.type.instanceShape()?.let { declared -> typeAccepts(declared, targetShape) } == true }
            .map { member -> member.name }

    /**
     * The fields that hold a record of [target] (Reference §6, "A required reference holds its
     * target"): every stored, required `one` field of a kept shape whose type accepts it. A field
     * typed with a union holds a record of any operand. An optional field, one declared
     * `? initially required`, and a `many` field hold nothing: they let go when the record is deleted.
     */
    fun holdingFields(target: String): List<HoldingField> {
        val targetBases = basesOf(target)
        return shapes.entries
            .filter { (shapeName, _) -> shapeName !in transients }
            .flatMap { (shapeName, shape) -> shape.members.filterIsInstance<StoredProp>().mapNotNull { field -> holdingField(shapeName, field, targetBases) } }
    }

    private fun holdingField(shapeName: String, field: StoredProp, targetBases: Set<String>): HoldingField? {
        val reference = field.type as? RelType ?: return null
        if (reference.many || reference.optional) return null
        if (targetBases.none { targetBase -> typeAccepts(reference.shape, targetBase) }) return null
        return HoldingField(shapeName, field.name, reference.shape)
    }

    /** The shape of the record an `is held` test is written on, null when its subject is not a record. */
    fun heldSubjectShape(test: IsExpr, subject: String, aliases: Map<String, String> = emptyMap(), root: String = subject): String? =
        heldSubjectShape(test, Scope.of(subject, aliases, root))

    private fun heldSubjectShape(test: IsExpr, scope: Scope): String? =
        (test.subject as? PathExpr)?.let { path -> paths.resolve(path, scope).endType?.instanceShape() }

    // ── member lookup ────────────────────────────────────────────────────────

    /**
     * Members visible on a shape-or-refinement scope: for a base shape, its own
     * declared members plus inferred inverse collections; for a refinement, its
     * own body members plus everything visible on its operands (union — Reference §9's
     * `or`-restriction is a check's concern, not lookup's).
     */
    /** True when a member of some shape or refinement holds the scalar [t]: bare, optional,
     *  or as the element of a `many` of values. The generators import a Kotlin class only when so. */
    fun usesScalar(t: VType): Boolean = (shapes.keys + refinements.keys).any { sc ->
        membersOf(sc).values.any { m ->
            val bare = (m.type as? VType.Optional)?.inner ?: m.type
            bare == t || (bare is VType.CollS && scalarOf(bare.name) == t)
        }
    }

    fun membersOf(scope: String): Map<String, MemberInfo> =
        memberCache[scope] ?: computeMembers(scope).also { memberCache[scope] = it }

    private val memberCache = java.util.concurrent.ConcurrentHashMap<String, Map<String, MemberInfo>>()

    private fun computeMembers(scope: String): Map<String, MemberInfo> {
        val out = linkedMapOf<String, MemberInfo>()
        fun add(scopeName: String, m: Member) {
            // a member taken from another shape with `is` is that shape's one declaration
            // on every taker (Reference §9, "Sharing a field across shapes")
            val owner = m.declaredOn ?: scopeName
            when (m) {
                is StoredProp -> out[m.name] = MemberInfo(m.name, owner, typeOf(m.type), stored = true, visible = m.visible, transient = m.transient)
                // a derived property that states no type holds `Unknown` until every member is in,
                // then takes its expression's type (resolveUntyped below)
                is DerivedProp -> out[m.name] =
                    MemberInfo(m.name, owner, m.type?.let { typeOf(it) } ?: VType.Unknown, stored = false, derived = m, captured = m.captured, visible = m.visible)
                is TimestampProp -> out[m.name] =
                    MemberInfo(m.name, owner, VType.DateTimeT, stored = false, timestamp = true, visible = m.visible)
                is VisibleClause -> {}
                // the actor: a stored, language-populated reference typed by the
                // clause's shape — a refinement there types reads in the act's
                // own scope, where the gate has held (Reference §22, "The actor")
                is ActorProp -> out[m.name] =
                    MemberInfo(m.name, owner, VType.Inst(m.shape), stored = true, actor = true)
                is FrozenClause -> {}
                UndeletableClause -> {}
                is UniqueKey -> {}
            }
        }
        shapes[scope]?.let { shape ->
            shape.members.forEach { add(scope, it) }
            // inferred inverse collections (Reference §6) — from the declared side's
            // `one` (a one-to-many's child pointer) or bare `many` (an m2m edge
            // set). None from a transient shape onto a shape outside its closure:
            // the collection would be a read of instances that are not kept
            // (Reference §4, "Transient acts"). Within one closure the inverse is
            // how the act reads its own parts (Reference §6). Two declared sides at
            // the same target make the decapitalize-and-pluralize name ambiguous —
            // no inverse is inferred, and use sites demand declared derived views
            // (Reference §6; the `Transfer` example).
            for ((otherName, other) in shapes) {
                if (otherName in transients && !sameTransientClosure(otherName, scope)) continue
                // a field taken from another shape infers its inverse under that shape's name, below
                inferInverse(scope, otherName, other.members.filter { it.declaredOn == null }, out)
            }
            // a field declared on a taken shape is one declaration, so it infers one inverse
            // named from the taken shape and spanning every taker (Reference §9, "Sharing a
            // field across shapes"); none when a taker is transient and outside this scope's
            // closure, as for any transient shape; within one closure the act reads every
            // kind of its parts through it (Reference §6)
            for ((takenName, takenShape) in sharedShapes) {
                if (basesOf(takenName).any { taker -> taker in transients && !sameTransientClosure(taker, scope) }) continue
                inferInverse(scope, takenName, takenShape.members, out)
            }
            out["id"] = MemberInfo("id", scope, VType.Id, stored = false)
            return resolveUntyped(scope, out)
        }
        (refinements[scope] ?: residuals[scope])?.let { r ->
            if (isCrossBase(scope)) {
                // a cross-base union exposes only what every operand inherits from
                // ONE declaration (Reference §9, unchanged): `id`, and the
                // inverses inferred through a field typed with the union. Two
                // same-named declarations on different bases are never shared.
                val operandMaps = collectOperandScopes(r.expr).map { membersOf(it) }
                val names = operandMaps.map { it.keys }.reduce { a, b -> a intersect b }
                for (n in names) {
                    val infos = operandMaps.map { it.getValue(n) }
                    val first = infos.first()
                    val shared = infos.all { it.sameDeclarationAs(first) }
                    if (!shared) continue
                    // over a taken shape, a taker's own member is never shared, even with one taker
                    if (scope in sharedShapes && first.inverse == null && first.owner !in sharedShapes) continue
                    if (scope in sharedShapes && first.inverse != null && !inverseCovers(first.inverse, scope)) continue
                    out[n] = if (first.inverse != null) first.copy(owner = scope) else first
                }
                out["id"] = MemberInfo("id", scope, VType.Id, stored = false)
            } else {
                collectOperandScopes(r.expr).forEach { operand -> out.putAll(membersOf(operand)) }
            }
            r.members.forEach { add(scope, it) }
            return resolveUntyped(scope, out)
        }
        return out
    }

    /** The inverse collection [declaringName]'s one field targeting [scope] infers onto it, if
     *  exactly one does (Reference §6). A field typed with a static union targets every operand
     *  (Reference §9): `Comment.target: one Content` puts `comments` on each operand. */
    private fun inferInverse(scope: String, declaringName: String, members: List<Member>, out: LinkedHashMap<String, MemberInfo>) {
        val targeting = members.filterIsInstance<StoredProp>()
            .filter { (it.type as? RelType)?.shape?.let { t -> typeAccepts(t, scope) } == true }
        if (targeting.size != 1) return
        val field = targeting.single()
        val inverse = declaringName.replaceFirstChar { it.lowercase() } + "s"
        out.putIfAbsent(inverse, MemberInfo(inverse, scope, VType.Coll(declaringName), stored = false,
            inverse = InverseInfo(declaringName, field.name, (field.type as RelType).many)))
    }

    /** Every member of [scope] with its type: a derived property that states none takes its
     *  expression's (Reference §7). The map is cached as it stands first, so the readings can
     *  see the typed members while the untyped ones resolve. */
    private fun resolveUntyped(scope: String, out: LinkedHashMap<String, MemberInfo>): Map<String, MemberInfo> {
        val untyped = out.values.filter { it.derived?.type == null && it.derived != null }
        if (untyped.isEmpty()) return out
        memberCache[scope] = out
        for (info in untyped) out[info.name] = info.copy(type = resolvedDerivedType(info.owner, info.derived!!))
        return out
    }

    private val derivedTypeCache = HashMap<Pair<String, String>, VType>()
    private val derivedResolving = LinkedHashSet<Pair<String, String>>()

    /** The properties found to take their type from one another with none declaring it, so the
     *  validator says nothing more about them. */
    val untypedByCycle = HashSet<Pair<String, String>>()

    /** The type of a derived or captured property that states none: its expression's, read once
     *  (Reference §7). `Unknown` when the expression gives none, or when the property takes its
     *  type from itself through other untyped properties; the cycle is F2, reported once. */
    fun resolvedDerivedType(owner: String, prop: DerivedProp): VType {
        val key = owner to prop.name
        derivedTypeCache[key]?.let { return it }
        if (key in derivedResolving) { reportCycle(derivedResolving.dropWhile { it != key }); return VType.Unknown }
        derivedResolving.add(key)
        // `empty` and `none` take their type from a declaration, so they give an untyped property none
        val type = try {
            types.of(prop.expr, owner, TypeEnv(thisScope = owner))?.takeIf { it != types.emptyCollection && it != types.absent } ?: VType.Unknown
        } finally { derivedResolving.remove(key) }
        derivedTypeCache[key] = type
        return type
    }

    private fun reportCycle(cycle: List<Pair<String, String>>) {
        if (!untypedByCycle.addAll(cycle) && cycle.all { it in untypedByCycle }) return
        val names = cycle.map { (owner, name) -> "'$owner.$name'" }
        val message = if (names.size == 1) "${names.single()} takes its type from itself and declares none; declare it"
            else "${names.dropLast(1).joinToString(", ")} and ${names.last()} each take the type of the next and none declares one; declare a type on one of them"
        diagnostics.add(Diagnostic("F2", message))
    }

    /** The one reading of an expression's type (checks.md F2). */
    val types: ExprTypes by lazy { ExprTypes(this) }

    /** What each path in the spec names (Reference §9, §10): the one answer every part reads. */
    val paths: PathResolver by lazy { PathResolver(this) }

    /** What an `is` test shows about the shape of a path's record (Reference §10). */
    val narrowing: ShapeNarrowing by lazy { ShapeNarrowing(this, paths) }


    /** The order a new record's `initially` values are computed in (Reference §5). */
    val startingValues: StartingValues by lazy { StartingValues(this) }

    /** What the prohibitions the rules cannot move say about presence (Reference §21). */
    val presenceFacts: PresenceFacts by lazy { PresenceFacts(decls) }

    /** The function dispatch (OQ66): a call resolves to the signature its arguments match. */
    val functionResolver: FunctionResolver by lazy { FunctionResolver(registry) { c -> scalarOf(c.name) } }

    /** Whether the field behind an inverse is typed so that every record of [scope] is a target:
     *  with the taken shape itself, or with a union of every taker. */
    private fun inverseCovers(inverse: InverseInfo, scope: String): Boolean {
        val declaring = shapes[inverse.shape] ?: sharedShapes[inverse.shape] ?: return false
        val fieldType = (declaring.members.filterIsInstance<StoredProp>().firstOrNull { it.name == inverse.field }?.type as? RelType)?.shape
            ?: return false
        return fieldType == scope || basesOf(scope).all { typeAccepts(fieldType, it) }
    }

    private fun MemberInfo.sameDeclarationAs(o: MemberInfo): Boolean = name == o.name && when {
        inverse != null || o.inverse != null -> inverse == o.inverse
        else -> owner == o.owner
    }

    private fun collectOperandScopes(expr: RefExpr): List<String> = when (expr) {
        is RefName -> if (expr.name in shapes || expr.name in refinements) listOf(expr.name) else emptyList()
        is RefNot -> collectOperandScopes(expr.inner)
        is RefAnd -> collectOperandScopes(expr.left) + collectOperandScopes(expr.right)
        is RefOr -> collectOperandScopes(expr.left) + collectOperandScopes(expr.right)
    }

    // ── the exposure closure (Reference §6, "Inline part creation"; checks V22) ─

    /**
     * Resolved closure per act: each `with` entry pinned to its part
     * shape and language-populated back-reference. Resolution errors land in
     * [diagnostics] as V22 the first time this is read (Validator forces it).
     */
    val closures: Map<String, List<ClosureEdge>> by lazy {
        val resolved = withDecls.entries.associate { (shape, entries) -> shape to resolveClosureLevel(shape, entries) }
        markTransientParts(resolved)
        resolved
    }

    /** The closure is resolved as soon as the model is built, so every reader sees the parts
     *  of a transient act's closure as transient shapes. */
    init { closures }

    /** The parts of a transient act's closure are transient with it (Reference §6): they
     *  join [transients] under the act, and the members computed before the marking
     *  are recomputed against it. */
    private fun markTransientParts(resolved: Map<String, List<ClosureEdge>>) {
        fun partsOf(edge: ClosureEdge): List<String> = basesOf(edge.partShape).toList() + edge.children.flatMap(::partsOf)
        for ((act, edges) in resolved) {
            if (act !in transients) continue
            for (part in edges.flatMap(::partsOf)) {
                transients.add(part)
                transientClosureOf[part] = act
            }
        }
        memberCache.clear()
    }

    private fun resolveClosureLevel(scope: String, entries: List<WithEntry>): List<ClosureEdge> {
        entries.groupBy { it.name }.filterValues { it.size > 1 }.keys.forEach {
            error("V22", "'act ... with': closure edge '$it' declared twice on '$scope'")
        }
        return entries.distinctBy { it.name }.mapNotNull { resolveClosureEdge(scope, it) }
    }

    private fun resolveClosureEdge(scope: String, entry: WithEntry): ClosureEdge? {
        val m = membersOf(scope)[entry.name]
        val fix = "a closure edge must be the inverse of a declared `one` field, or a declared `one` field " +
            "whose value may arrive nested (Reference §6)"
        // a declared `one` field named in `with`: the reference may be supplied by
        // value, the pointed-at record nested inside this one (Reference §6, OQ63 §6.1)
        if (m != null && m.stored && !m.actor && m.inverse == null && m.type.instanceShape() != null)
            return resolveByValueEdge(scope, entry, m.type.instanceShape()!!)
        val edge = when {
            m == null -> {
                error("V22", "'act ... with ${entry.name}': '$scope' has no such collection or `one` field — $fix")
                null
            }
            m.inverse != null ->
                if (m.inverse.many) {
                    error("V22", "'act ... with ${entry.name}': the inverse of a declared `many` " +
                        "(an m2m edge set) cannot ride a closure — its members are existing instances, " +
                        "named by reference; $fix")
                    null
                } else if (m.inverse.shape in sharedShapes) {
                    // the back-reference is declared on a shape several shapes take with `is`
                    // (Reference §9), so the parts are of every taker, each saying its shape
                    // when it arrives, as a value at a union-typed field does (Reference §6)
                    return resolveMixedPartEdge(scope, entry, m.inverse.shape, m.inverse.field)
                } else m.inverse.shape to m.inverse.field
            m.derived != null -> recognizedInverse(scope, m.derived).also {
                if (it == null) error("V22", "'act ... with ${entry.name}': $fix — " +
                    "an arbitrary-predicate view cannot ride a closure")
            }
            else -> {
                error("V22", "'act ... with ${entry.name}': '${entry.name}' is not a collection view or a `one` field of '$scope' — $fix")
                null
            }
        } ?: return null
        val (partShape, backRef) = edge
        if (partShape in transients) {
            error("V22", "'act ... with ${entry.name}': part shape '$partShape' is a transient act — " +
                "closure parts are durable records created at the commit")
            return null
        }
        return ClosureEdge(entry.name, partShape, backRef, resolveClosureLevel(partShape, entry.children), owner = scope)
    }

    /**
     * The inverse of a back-reference declared on [taken], a shape several shapes take with
     * `is` (Reference §9), named in `with`: one edge whose parts are of every taker, each
     * arriving with its shape named. The entry's own `with` children are resolved on each
     * taker that has them, and each child edge records its [ClosureEdge.owner].
     */
    private fun resolveMixedPartEdge(scope: String, entry: WithEntry, taken: String, backRef: String): ClosureEdge? {
        val takers = basesOf(taken)
        takers.filter { it in transients }.forEach {
            error("V22", "'act ... with ${entry.name}': part shape '$it' is a transient act — " +
                "closure parts are durable records created at the commit")
            return null
        }
        val children = resolveChildrenPerOperand(scope, entry, taken, takers)
        return ClosureEdge(entry.name, taken, backRef, children, owner = scope)
    }

    /** The children of a `with` entry whose parts or value may be of several [operands]: each child
     *  resolved on every operand that has it, owned by that operand. */
    private fun resolveChildrenPerOperand(scope: String, entry: WithEntry, target: String, operands: Collection<String>): List<ClosureEdge> {
        entry.children.groupBy { it.name }.filterValues { it.size > 1 }.keys.forEach {
            error("V22", "'act ... with': closure edge '$it' declared twice under '$scope.${entry.name}'")
        }
        val children = mutableListOf<ClosureEdge>()
        for (child in entry.children.distinctBy { it.name }) {
            val owners = operands.filter { membersOf(it).containsKey(child.name) }
            if (owners.isEmpty()) {
                val none = if (target in sharedShapes) "no shape that is a '$target'" else "no operand of '$target'"
                error("V22", "'act ... with ${entry.name} with { ${child.name} }': $none has '${child.name}'")
                continue
            }
            for (owner in owners) resolveClosureEdge(owner, child)?.let { children.add(it) }
        }
        return children
    }

    /**
     * A `one` field named in `with` (Reference §6, "a reference supplied by value"):
     * the committer may nest a new [target] record at the field instead of naming
     * an existing one. For a union-typed field the nested value is one of the
     * operands, so the entry's own `with` children are resolved against each
     * operand that has them, and each child edge records its [ClosureEdge.owner].
     */
    private fun resolveByValueEdge(scope: String, entry: WithEntry, target: String): ClosureEdge? {
        val operands = basesOf(target)
        if (operands.isEmpty() || operands.any { it !in shapes }) {
            error("V22", "'act ... with ${entry.name}': '$scope.${entry.name}' targets '$target', which is not a shape " +
                "or a static union of shapes, so no value can arrive there")
            return null
        }
        operands.filter { it in transients }.forEach {
            error("V22", "'act ... with ${entry.name}': '$it' is a transient act — a value arriving nested is a durable record created at the commit")
            return null
        }
        val children = resolveChildrenPerOperand(scope, entry, target, operands)
        return ClosureEdge(entry.name, target, backRef = "", children = children, byValue = true, owner = scope)
    }

    /**
     * Every shape a commit of [act] may create (Reference §6; OQ63 §6.2.2): the
     * act, its parts, the operands of its `one` fields named in `with`, recursively.
     */
    fun closureShapes(act: String): Set<String> {
        val out = linkedSetOf(act)
        fun visit(e: ClosureEdge) {
            out.addAll(basesOf(e.partShape))
            e.children.forEach { visit(it) }
        }
        closures[act].orEmpty().forEach { visit(it) }
        return out
    }

    /**
     * The stored relationship fields of [shape], `one` or `many`, arriving at a
     * level of [act]'s closure declared with [edges] and pointed at by [backRef],
     * that may reference a new record elsewhere in the same act (Reference §6, "a
     * reference to a value in the same act"): neither a by-value edge, nor the
     * back-reference, nor the actor, and typed by a shape some closure shape
     * satisfies. A `many` field's elements are each a handle or a new value.
     */
    fun siblingFields(act: String, shape: String, edges: List<ClosureEdge>, backRef: String?): List<StoredProp> {
        val decl = shapes[shape] ?: return emptyList()
        val byValue = edges.filter { it.byValue }.map { it.prop }.toSet()
        // the act itself is reached by a part's back-reference, never by a sibling reference
        val created = closureShapes(act) - act
        return decl.members.filterIsInstance<StoredProp>().filter { p ->
            val t = p.type as? RelType ?: return@filter false
            p.name !in byValue && p.name != backRef && created.any { typeAccepts(t.shape, it) }
        }
    }

    /** The recognized inverse form `(P where field == this)` (Reference §6, the
     *  `Transfer` example): the view pins the language-populated back-reference. */
    private fun recognizedInverse(scope: String, d: DerivedProp): Pair<String, String>? {
        val set = d.expr as? SetExpr ?: return null
        val b = set.collection.bindings.singleOrNull() ?: return null
        val src = b.source as? PathExpr ?: return null
        if (src.segs.isNotEmpty() || src.root !in shapes) return null
        val where = set.collection.where as? Binary ?: return null
        if (where.op != "==") return null
        val sides = listOf(where.left, where.right).filterIsInstance<PathExpr>()
        if (sides.size != 2) return null
        val fieldSide = sides.find { it.root != "this" && it.segs.isEmpty() } ?: return null
        if (sides.find { it.root == "this" && it.segs.isEmpty() } == null) return null
        val part = shapes[src.root] ?: return null
        val f = part.members.filterIsInstance<StoredProp>().find { it.name == fieldSide.root } ?: return null
        val t = f.type as? RelType ?: return null
        if (t.many || t.shape != scope) return null
        return src.root to f.name
    }

    // ── delete sites (OQ37): every deleter of every shape, statically ────────

    /** Every `delete` statement in the spec, with its target resolved to the
     *  base shape it deletes. Literal static paths keep this derivable — the
     *  whole-spec compiler knows every deleter of every shape, exactly as it
     *  knows every writer. Unresolvable targets are dropped here (the
     *  validator reports them as F1). */
    val deleteSites: List<DeleteSite> by lazy {
        rules.values.flatMap { rule ->
            val scope = (rule.condition as? RefName)?.name
                ?.takeIf { it in shapes || it in refinements }
                ?: baseOfExpr(rule.condition)
                ?: return@flatMap emptyList()
            rule.body.filterIsInstance<DeleteStmt>().flatMap { stmt ->
                val shapes =
                    if (stmt.target.root == "this" && stmt.target.segs.isEmpty()) basesOf(scope)
                    else pathElementShape(stmt.target, scope)?.let { basesOf(it) } ?: emptySet()
                shapes.map { DeleteSite(rule, stmt, it) }
            }
        }
    }

    /** Base shapes some rule deletes — the set every persistence-leaning proof
     *  (monotone `exists`, one-way state flow, anti-monotone predicates) must
     *  consult before assuming facts are immortal. */
    val deletedShapes: Set<String> by lazy { deleteSites.map { it.shape }.toSet() }

    /** The capture-persistence problems this spec poses a store, one per
     *  capture-carrying refinement (CaptureSchema's kdoc states the contract). */
    val captureSchemas: List<CaptureSchema> by lazy {
        refinements.mapNotNull { (name, r) ->
            val props = r.members.filterIsInstance<DerivedProp>().filter { it.captured }
            if (props.isEmpty()) return@mapNotNull null
            val base = baseOf(name) ?: return@mapNotNull null
            CaptureSchema(name, base, props.map { CaptureSchema.Prop(it.name, membersOf(name).getValue(it.name).type) })
        }
    }

    fun typeOf(t: TypeRef): VType = when (t) {
        is ScalarType -> if (t.many) VType.CollS(t.name, ordered = t.ordered) else when (t.name) {
            "text" -> VType.Text
            "integer", "long", "decimal", "double" -> VType.Num(t.name)
            "boolean" -> VType.Bool
            "Date" -> VType.DateT
            "DateTime" -> VType.DateTimeT
            "uuid" -> VType.UuidT
            "duration" -> VType.DurationT
            "time" -> VType.TimeT
            "bytes" -> VType.BytesT
            else -> typeScope[t.name]?.let { VType.Ext(it.qualifiedName) } ?: VType.Unknown
        }.let { if (t.optional) VType.Optional(it) else it }
        is RelType ->
            if (t.many) VType.Coll(t.shape)
            else VType.Inst(t.shape).let { if (t.optional) VType.Optional(it) else it }
    }

    // ── predicate summaries (what a refinement's predicate reads) ────────────

    /**
     * Transitive read summary of a shape-or-refinement's membership predicate:
     * stored fields read (through derived properties), shapes tested via
     * `exists`/`for`-queries/selectors, and whether `today`/`now` is consulted.
     */
    fun predicateSummary(name: String): ReadSummary =
        summaryCache.getOrPut(name) {
            val s = ReadSummary()
            summaryCache[name] = s // break cycles; V14 reports them separately
            refinements[name]?.let { collectRefExpr(it.expr, s) }
            s
        }

    private val summaryCache = java.util.concurrent.ConcurrentHashMap<String, ReadSummary>()

    /** Read summary of an arbitrary refinement expression (a rule's condition, a
     *  never's target) — [predicateSummary]'s sibling for unnamed expressions. */
    fun summaryOfRefExpr(expr: RefExpr): ReadSummary = ReadSummary().also { collectRefExpr(expr, it) }

    private fun collectRefExpr(expr: RefExpr, s: ReadSummary) {
        when (expr) {
            is RefName -> {
                if (expr.name in refinements) s.absorb(predicateSummary(expr.name))
                expr.where?.let { collectExpr(it, if (expr.name in refinements || expr.name in shapes) expr.name else baseOfExpr(expr) ?: return, s) }
            }
            is RefNot -> collectRefExpr(expr.inner, s)
            is RefAnd -> { collectRefExpr(expr.left, s); collectRefExpr(expr.right, s) }
            is RefOr -> { collectRefExpr(expr.left, s); collectRefExpr(expr.right, s) }
        }
    }

    /** Collect reads of an expression evaluated with `subject` as its scope.
     *  [aliases] maps `as`-bound names to their element scopes (Reference §10). */
    fun collectExpr(e: Expr, subject: String, s: ReadSummary, aliases: Map<String, String> = emptyMap(), root: String = subject) =
        collect(e, Scope.of(subject, aliases, root), s)

    /** Collect the reads of [expression] read at [scope]. */
    private fun collect(expression: Expr, scope: Scope, summary: ReadSummary) {
        when (expression) {
            is TodayLit, is NowLit -> summary.readsTime = true
            is PathExpr -> collectPath(expression, scope, summary)
            is UnaryMinus -> collect(expression.inner, scope, summary)
            is Binary -> collectBinary(expression, scope, summary)
            is NotExpr -> collect(expression.inner, scope, summary)
            // a branch reads what its test narrowed to (Reference §10): `target.tags` after `target is Post`
            is IfExpr -> {
                collect(expression.condition, scope, summary)
                collect(expression.thenExpr, scope.narrowedBy(narrowedBy(expression.condition, holds = true, scope)), summary)
                collect(expression.elseExpr, scope.narrowedBy(narrowedBy(expression.condition, holds = false, scope)), summary)
            }
            is Interpolation -> expression.pieces.forEach { piece -> collect(piece, scope, summary) }
            is IsExpr -> collectIs(expression, scope, summary)
            is ExistsExpr -> collectExists(expression, scope, summary)
            is AggCall -> collectAgg(expression, scope, summary)
            is FunCall -> expression.args.forEach { argument -> collect(argument, scope, summary) }
            is SingularFor -> { consultShape(expression.shape, summary); collectFor(expression.shape, expression.forExpr, scope, summary) }
            is Access -> collectAccess(expression, scope, summary)
            is ShapeForSource -> { consultShape(expression.shape, summary); collectFor(expression.shape, expression.forExpr, scope, summary) }
            is SetExpr -> collectCollection(expression.collection, scope, summary)
            is WalkExpr -> collectWalk(expression, scope, summary)
            else -> {}
        }
    }

    /** `a and b` reads `b` where `a` holds, so what `a` narrows is in force over `b` (Reference §10). */
    private fun collectBinary(expression: Binary, scope: Scope, summary: ReadSummary) {
        collect(expression.left, scope, summary)
        val rightScope = if (expression.op == "and") scope.narrowedBy(narrowedBy(expression.left, holds = true, scope)) else scope
        collect(expression.right, rightScope, summary)
    }

    private fun collectIs(test: IsExpr, scope: Scope, summary: ReadSummary) {
        collect(test.subject, scope, summary)
        test.refinement?.let { named -> if (named in refinements) summary.absorb(predicateSummary(named)) }
        if (test.kind == "held") collectHolders(heldSubjectShape(test, scope), summary)
    }

    private fun collectExists(test: ExistsExpr, scope: Scope, summary: ReadSummary) {
        test.shape?.let { shape -> consultShape(shape, summary) }
        test.shape?.let { shape -> test.forExpr?.let { forExpr -> collectFor(shape, forExpr, scope, summary) } }
        test.collection?.let { collection -> collectCollection(collection, scope, summary) }
    }

    /** `(Fine for this).loan` and `latest(...).holder`: the target's reads, then each field after it. */
    private fun collectAccess(access: Access, scope: Scope, summary: ReadSummary) {
        val start: String? = when (val target = access.target) {
            is AggCall -> collectAgg(target, scope, summary).takeIf { target.name == "latest" || target.name == "first" }
            is SingularFor -> {
                consultShape(target.shape, summary)
                collectFor(target.shape, target.forExpr, scope, summary)
                target.shape
            }
            else -> { collect(target, scope, summary); null }
        }
        val resolved = paths.resolveAfter(start, access.segs.map { seg -> seg.name })
        resolved.steps.forEach { step -> record(step.readOn, step.member, summary) }
        if (!resolved.resolves) summary.opaque = true
    }

    // a walk reads its relationship on every record it reaches: the field at
    // shape granularity, which is what the summary records; the condition is
    // read with each reached record as its subject
    private fun collectWalk(walk: WalkExpr, scope: Scope, summary: ReadSummary) {
        val relationship = membersOf(scope.element)[walk.rel]
        if (relationship == null) { summary.opaque = true; return }
        record(scope.element, relationship, summary)
        walk.until?.let { condition -> collect(condition, Scope(scope.element, narrowed = scope.narrowed), summary) }
    }

    /** `x is held` reads every field that can hold `x`, and whether a record of each holding shape exists. */
    private fun collectHolders(heldShape: String?, summary: ReadSummary) {
        if (!summary.includesHeldReads) return
        if (heldShape == null) { summary.opaque = true; return }
        for (holding in holdingFields(heldShape)) {
            summary.existsShapes.add(holding.shape)
            summary.fields.add(holding.shape to holding.field)
        }
    }

    /** `Shape for <record>` reads the record expression and the field of [shape] that `for`
     *  matches (Reference §10): which records it finds is that field's value on each of them.
     *  Every field that could match is recorded, so a spec V13 refuses for matching several
     *  still reads each. When the record's shape cannot be told, or no field of [shape] takes
     *  it, which records the read finds cannot be attributed, and the summary may read anything. */
    private fun collectFor(shape: String, forExpr: Expr, scope: Scope, summary: ReadSummary) {
        collect(forExpr, scope, summary)
        val recordShape = forRecordShape(forExpr, scope)
        val matchedFields = recordShape?.let { basesOf(it) }.orEmpty().flatMap { recordBase -> typeMatchedFields(shape, recordBase) }.distinct()
        if (matchedFields.isEmpty()) {
            summary.opaque = true
            return
        }
        for (matchedField in matchedFields) membersOf(shape)[matchedField]?.let { member -> record(shape, member, summary) }
    }

    /** The shape of the one record after `for`: a path's end, the shape a `(Shape for ...)` finds,
     *  the element `latest` or `first` picks, and a field read after either; null for anything else. */
    internal fun forRecordShape(forExpr: Expr, subject: String, aliases: Map<String, String>, root: String): String? =
        forRecordShape(forExpr, Scope.of(subject, aliases, root))

    private fun forRecordShape(forExpr: Expr, scope: Scope): String? = when (forExpr) {
        is PathExpr -> paths.resolve(forExpr, scope).endShape
        is SingularFor -> forExpr.shape
        is AggCall -> pickedElementShape(forExpr, scope)
        is Access -> accessRecordShape(forExpr, scope)
        else -> null
    }

    private fun pickedElementShape(selection: AggCall, scope: Scope): String? =
        if (selection.name == "latest" || selection.name == "first") collectCollection(selection.collection, scope, ReadSummary())
        else null

    /** The shape `(Fine for this).loan` or `first(...).holder` reaches: the target's record, then each field's. */
    private fun accessRecordShape(access: Access, scope: Scope): String? {
        val start = when (val target = access.target) {
            is SingularFor -> target.shape
            is AggCall -> pickedElementShape(target, scope)
            else -> null
        }
        return paths.resolveAfter(start, access.segs.map { seg -> seg.name }).endType?.instanceShape()
    }

    private fun consultShape(name: String, s: ReadSummary) {
        s.existsShapes.add(name)
        if (name in refinements) s.absorb(predicateSummary(name))
    }

    /** Collects an aggregate's reads (collection, sum's selected field, and the
     *  selectors' `by` datums); returns the element scope, null when unresolvable. */
    private fun collectAgg(aggregate: AggCall, scope: Scope, summary: ReadSummary): String? {
        val element = collectCollection(aggregate.collection, scope, summary)
        for (fieldName in listOfNotNull(aggregate.field) + aggregate.orderBy) {
            val member = element?.let { membersOf(it)[fieldName] }
            if (member != null) record(element, member, summary) else summary.opaque = true
        }
        return element
    }

    /** Returns the last binding's element scope (the innermost scope the shared
     *  `where` evaluates bare names against), null when it can't be resolved. */
    private fun collectCollection(collection: CollectionExpr, scope: Scope, summary: ReadSummary): String? {
        var elementScope: String? = null
        var bindingScope = scope
        for (binding in collection.bindings) {
            val bound = collectBinding(binding, bindingScope, summary)
            bindingScope = bound.scope
            if (bound.element != null) elementScope = bound.element
        }
        // the shared `where` resolves bare names against the innermost element,
        // while `this` keeps naming the outermost subject (Reference §10)
        collection.where?.let { where -> collect(where, bindingScope.inside(elementScope ?: scope.element), summary) }
        // a filter that names a refinement, `(claims where PaidClaim)`, holds that refinement's members
        return collection.where?.let { where -> elementScope?.let { membershipAtomOf(where, it) } } ?: elementScope
    }

    /** What a binding of a collection reads, the element its records are, and the scope with its `as` name bound. */
    private class Bound(val element: String?, val scope: Scope)

    private fun collectBinding(binding: Binding, scope: Scope, summary: ReadSummary): Bound {
        val element: String? = when (val source = binding.source) {
            is PathExpr -> collectPathSource(source, binding, scope, summary) ?: return valueBinding(source, binding, scope, summary)
            is ShapeForSource -> {
                consultShape(source.shape, summary)
                collectFor(source.shape, source.forExpr, scope, summary)
                source.shape
            }
            is WalkExpr -> {
                collect(source, scope, summary)
                walkTarget(source, scope.element).also { target -> if (target == null) summary.opaque = true }
            }
            else -> { collect(source, scope, summary); summary.opaque = true; null }
        }
        val alias = binding.alias
        return Bound(element, if (alias != null && element != null) scope.withRecordName(alias, element) else scope)
    }

    /** A source path's reads and the shape of its records; null when its records are not records. */
    private fun collectPathSource(source: PathExpr, binding: Binding, scope: Scope, summary: ReadSummary): String? {
        if ((source.root in shapes || source.root in refinements) && source.segs.isEmpty()) {
            summary.existsShapes.add(baseOf(source.root) ?: source.root)
            if (source.root in refinements) summary.absorb(predicateSummary(source.root))
            return source.root
        }
        collectPath(source, scope, summary)
        return paths.resolve(source, scope).endShape
    }

    /** A `many` of values filtered by naming each value (Reference §5): the name is a value. */
    private fun valueBinding(source: PathExpr, binding: Binding, scope: Scope, summary: ReadSummary): Bound {
        val alias = binding.alias
        if (alias != null && paths.resolve(source, scope).endType is VType.CollS) return Bound(null, scope.withValueName(alias))
        summary.opaque = true
        return Bound(null, scope)
    }

    /** The refinement a filter names as a bare conjunct, `where PaidClaim` or `where PaidClaim and ...`,
     *  when every record of [element] it holds is one of its bases; the element is then read as it (Reference §10). */
    fun membershipAtomOf(where: Expr, element: String): String? =
        andConjuncts(where).firstNotNullOfOrNull { c ->
            (c as? PathExpr)?.takeIf { it.segs.isEmpty() && it.root in refinements && it.root != element }?.root
                ?.takeIf { basesOf(element).let { b -> b.isNotEmpty() && b.all { base -> base in basesOf(it) } } }
        }

    private fun collectPath(path: PathExpr, scope: Scope, summary: ReadSummary) {
        val resolved = paths.resolve(path, scope)
        // a bare refinement name is a membership atom: it reads what the refinement's predicate reads
        (resolved.start as? PathStart.ShapeName)?.let { named -> if (named.name in refinements) summary.absorb(predicateSummary(named.name)) }
        resolved.steps.forEach { step -> record(step.readOn, step.member, summary) }
        if (!resolved.resolves) summary.opaque = true
    }

    // ── narrowing carried into the read summary (Reference §10) ──────────────

    /** What [cond] shows where it holds or fails, read at [scope] (Reference §10). */
    private fun narrowedBy(cond: Expr, holds: Boolean, scope: Scope): Map<List<String>, String> = narrowing.shownBy(cond, holds, scope)

    /**
     * The base shapes whose records a read or a write of [member] lands on when it is read on a record of
     * [scope] (Reference §9): a field declared on a shape others take with `is` lands on the takers [scope]
     * can be, the shapes a write and a new record name.
     */
    fun recordsHolding(scope: String, member: MemberInfo): Set<String> =
        (basesOrSelf(scope) intersect basesOrSelf(member.owner)).ifEmpty { setOf(member.owner) }

    /** The base shapes whose records [name] holds; a name the spec does not declare stands for itself. */
    private fun basesOrSelf(name: String): Set<String> =
        if (namesShapeOrRefinement(name)) basesOf(name) else setOf(name)

    private fun andConjuncts(e: Expr): List<Expr> =
        if (e is Binary && e.op == "and") andConjuncts(e.left) + andConjuncts(e.right) else listOf(e)

    private fun record(scope: String, m: MemberInfo, s: ReadSummary) {
        val readBases = recordsHolding(scope, m)
        if (m.stored) readBases.forEach { base -> s.fields.add(base to m.name) }
        // an inferred inverse is the declared side read from the other end: which records it
        // holds is the reference field each of them stores (Reference §6)
        m.inverse?.let { declaredSide -> basesOrSelf(declaredSide.shape).forEach { base -> s.fields.add(base to declaredSide.field) } }
        if (m.timestamp) readBases.forEach { base -> s.collFields.add(base to m.name) }
        (m.type as? VType.Coll)?.let { collection -> s.collShapes.addAll(basesOrSelf(collection.shape)) }
        m.derived?.let { d ->
            val key = m.owner to m.name
            if (s.derivedSeen.add(key)) collect(d.expr, Scope(m.owner), s)
        }
    }

    /** The element scope of a collection expression, or null when it cannot be resolved. */
    fun collectionElement(c: CollectionExpr, subject: String, aliases: Map<String, String> = emptyMap()): String? =
        collectCollection(c, Scope.of(subject, aliases, subject), ReadSummary())

    /** The element scope of a collection expression read at [scope], or null when it cannot be resolved. */
    fun collectionElement(c: CollectionExpr, scope: Scope): String? = collectCollection(c, scope, ReadSummary())

    /** The scalar type a `many <scalar>` member's elements have. */
    fun scalarOf(name: String): VType = typeOf(ScalarType(name, optional = false))

    /** The shape of the records a walk reaches: the target of the relationship it
     *  follows on [subject]; null when [WalkExpr.rel] is not a relationship there. */
    fun walkTarget(e: WalkExpr, subject: String): String? =
        membersOf(subject)[e.rel]?.type?.let { it.instanceShape() ?: (it as? VType.Coll)?.shape }

    /** The type a walk's value has: the reached set, or, with `until`, the one record found or none. */
    fun walkType(e: WalkExpr, subject: String): VType? = walkTarget(e, subject)?.let {
        if (e.until != null) VType.Optional(VType.Inst(it)) else VType.Coll(it)
    }

    fun pathElementShape(p: PathExpr, subject: String, aliases: Map<String, String> = emptyMap(), root: String = subject): String? =
        paths.resolve(p, Scope.of(subject, aliases, root)).endShape

    /** The declared type at the end of a path: the record a bare `this` or alias names, else the
     *  last member's type; null when a step does not resolve or the root is a value name. */
    fun pathEndType(p: PathExpr, subject: String, aliases: Map<String, String> = emptyMap(), root: String = subject): VType? =
        paths.resolve(p, Scope.of(subject, aliases, root)).endType
}

/** A required `one` field [field] of [shape], declared with type [declaredType], that holds the record it points at. */
data class HoldingField(val shape: String, val field: String, val declaredType: String)

data class MemberInfo(
    val name: String,
    val owner: String,
    val type: VType,
    val stored: Boolean,
    val derived: DerivedProp? = null,
    val captured: Boolean = false,
    val timestamp: Boolean = false,
    /** The act's actor (Reference §22): language-populated at the commit, never supplied or assigned. */
    val actor: Boolean = false,
    /** Field-level visibility clauses (Reference §22): empty means visible wherever the record is. */
    val visible: List<VisibleClause> = emptyList(),
    /** Set on an inferred inverse collection: the declared side it is a view of (Reference §6). */
    val inverse: InverseInfo? = null,
    /** A transient field of a kept act (Reference §4): in the input, not in the record; never stored. */
    val transient: Boolean = false,
)

/** The declared side an inferred inverse reads: [shape].[field], to-one or an m2m `many`. */
data class InverseInfo(val shape: String, val field: String, val many: Boolean)

/** One `delete` statement, resolved: [rule] deletes an instance of [shape] via [stmt]. */
data class DeleteSite(val rule: RuleDecl, val stmt: DeleteStmt, val shape: String)

/** One resolved closure edge (Reference §6, "Inline part creation"): the act
 *  commit takes [prop] as inline [partShape] values; [backRef] is the part's
 *  language-populated pointer at the enclosing level. The closure graph is a
 *  tree by construction — entries only name inverse edges of their level. */
data class ClosureEdge(
    val prop: String,
    val partShape: String,
    val backRef: String,
    val children: List<ClosureEdge> = emptyList(),
    /** A `one` field named in `with` (Reference §6; OQ63 §6.1): the committer supplies the
     *  reference by value, one [partShape] record nested at [prop]; no back-reference. For a
     *  union-typed field [partShape] is the union, and [children] carry the edges resolved on
     *  each operand, each marked with its [owner]. A part edge whose [partShape] is a shape
     *  several shapes take with `is` (Reference §9) is read the same way: its parts are of
     *  every taker, and [children] are resolved per taker. `Model.basesOf(partShape)` is the
     *  concrete shapes of either. */
    val byValue: Boolean = false,
    /** The shape this edge was resolved on: the enclosing level, or, for a child of a
     *  union-typed by-value edge, the operand that declares it. */
    val owner: String = "",
) {
    /** The children that apply when the nested or part record is of [shape]. */
    fun childrenFor(shape: String): List<ClosureEdge> = children.filter { it.owner == shape }
}

sealed interface VType {
    data class Num(val name: String) : VType
    /** A registered extension type (Reference §5; OQ65), by `namespace.name`. */
    data class Ext(val qualifiedName: String) : VType
    data object Text : VType
    data object Bool : VType
    data object DateT : VType
    data object DateTimeT : VType
    /** A `uuid` (Reference §5): `java.util.UUID`, equality only, no ordering. */
    data object UuidT : VType
    /** A `duration` (Reference §5): a span of time, `java.time.Duration` on the surface. */
    data object DurationT : VType
    /** A `time` (Reference §5): a time of day with no date, `java.time.LocalTime`. */
    data object TimeT : VType
    /** `bytes` (Reference §5): opaque binary data, `ByteArray`; equal by content, ordered byte by byte. */
    data object BytesT : VType
    data object Id : VType
    data class Inst(val shape: String) : VType
    data class Coll(val shape: String) : VType
    /** An owned collection of scalar values (`many text`, Reference §5, "Collections of
     *  values"): each value once, or, when [ordered] (`tolerates duplication`), kept as
     *  given, duplicates and order included. */
    data class CollS(val name: String, val ordered: Boolean = false) : VType
    data class Optional(val inner: VType) : VType
    data object Unknown : VType

    companion object

    fun instanceShape(): String? = when (this) {
        is Inst -> shape
        is Optional -> inner.instanceShape()
        else -> null
    }
}

/** The alias scope of a `for each` name bound to numbers or scalar values
 *  (Reference §14): a value, never a record, so no member resolves on it. */
const val VALUE_ALIAS = "<value>"

/** [includesHeldReads] is false for the one question that sets them aside: which reads make a `never` a guarantee (checks.md V10). */
class ReadSummary(val includesHeldReads: Boolean = true) {
    /** (base shape, stored field) pairs the predicate/expression reads, a field taken with `is`
     *  under each shape that takes it; reading an inferred inverse reads the reference field on
     *  its declared side */
    val fields = mutableSetOf<Pair<String, String>>()
    /** shapes whose existence/instances are consulted (exists, selectors, for-queries) */
    val existsShapes = mutableSetOf<String>()
    var readsTime = false
    internal val derivedSeen = mutableSetOf<Pair<String, String>>()

    // The three members below serve the runtime's relevance analysis (which
    // commits can change this predicate's value — ConditionWatchers.kt); the
    // validator checks deliberately keep reading only the sets above.

    /** shapes whose instance sets are consulted through relationship collections
     *  (inferred inverses, declared `many` fields) — a create of one of these can
     *  change the predicate without touching any recorded field */
    val collShapes = mutableSetOf<String>()
    /** reads outside [fields]' vocabulary: aggregate-selected fields and
     *  timestamp fields (never assignable, but advanced by `on update`) */
    val collFields = mutableSetOf<Pair<String, String>>()
    /** set when the walker could not attribute a read — consumers needing
     *  soundness must then treat the summary as "may read anything" */
    var opaque = false

    fun absorb(other: ReadSummary) {
        fields.addAll(other.fields)
        existsShapes.addAll(other.existsShapes)
        if (other.readsTime) readsTime = true
        collShapes.addAll(other.collShapes)
        collFields.addAll(other.collFields)
        if (other.opaque) opaque = true
    }

    /** Every shape whose data this summary consults, at shape granularity. */
    fun touchedShapes(): Set<String> =
        existsShapes + collShapes + fields.map { it.first } + collFields.map { it.first }
}

data class Diagnostic(val code: String, val message: String, val advisory: Boolean = false) {
    override fun toString() = "[$code] $message"
}
