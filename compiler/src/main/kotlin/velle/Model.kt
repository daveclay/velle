package velle

import velle.ext.VelleType

/**
 * Resolved-spec model: symbol tables, refinement→base resolution, member lookup
 * (including refinement properties and inferred inverse relationships), and the
 * read/write summaries the checks in Validator.kt consume.
 *
 * Inverse relationships (Reference §6): `LineItem.invoice: one Invoice` gives
 * `Invoice` a derived collection member named decapitalize(LineItem) + "s" =
 * `lineItems`. The naming rule is a convention the Reference uses by example
 * (`invoices`, `payments`) without stating it — flagged for the docs.
 */
class Model(rawDecls: List<Decl>, val registry: TypeRegistry = TypeRegistry.default) {

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
    val rules = linkedMapOf<String, RuleDecl>()
    val nevers = mutableListOf<NeverDecl>()
    /** externally committable shapes — the `act` declarations (Reference §22) */
    val exposed = linkedSetOf<String>()

    /** shapes declared `transient act` — inputs to the state, not members of it (Reference §4) */
    val transients = mutableSetOf<String>()

    /** raw `with` declarations per act, resolved lazily by [closures] */
    private val withDecls = mutableMapOf<String, List<WithEntry>>()

    /** the spec-wide `visible to` default, declared at the top level (Reference §22) */
    private var visibilityDefaultDecl: VisibleClause? = null

    init {
        diagnostics.addAll(registry.diagnostics)
        val (scope, ambiguous) = resolveTypeScope()
        typeScope = scope
        ambiguousTypeNames = ambiguous
        val shapeNames = rawDecls.mapNotNull { (it as? ShapeDecl)?.name ?: (it as? RefinementDecl)?.name }.toSet()
        fun manyOfType(t: TypeRef): TypeRef =
            if (t is RelType && t.many && t.shape !in shapeNames && t.shape in scope) ScalarType(t.shape, optional = false, many = true) else t
        fun fix(m: Member): Member = when (m) {
            is StoredProp -> m.copy(type = manyOfType(m.type))
            is DerivedProp -> m.copy(type = manyOfType(m.type))
            else -> m
        }
        decls = rawDecls.map { d -> when (d) {
            is ShapeDecl -> d.copy(members = d.members.map(::fix))
            is RefinementDecl -> d.copy(members = d.members.map(::fix))
            else -> d
        } }
        for (d in decls) when (d) {
            is UsesDecl -> {}
            is ShapeDecl -> {
                if (!register(d.name)) continue
                shapes[d.name] = d
                if (d.exposed) exposed.add(d.name)
                if (d.transient) transients.add(d.name)
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
    }

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
            error("E1", "'$name' is both a shape in this spec and the registered type '${t.qualifiedName}'; " +
                "add `uses ${t.qualifiedName} as <Name>` to call the type something else here")
        }
        return true
    }

    // ── extension types (Reference §5; OQ65) ─────────────────────────────────

    private fun resolveTypeScope(): Pair<Map<String, VelleType<*>>, Map<String, List<VelleType<*>>>> {
        val scope = linkedMapOf<String, VelleType<*>>()
        val picked = mutableSetOf<String>()
        for (u in uses) {
            val t = registry[u.qualified]
            if (t == null) {
                error("F1", "`uses ${u.qualified}`: no registered type has that name" +
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

    private val basesCache = mutableMapOf<String, Set<String>>()

    /**
     * The base shapes a shape-or-refinement's members are drawn from. One for a
     * base shape or an ordinary refinement; several for a cross-base union —
     * `or` composed across unrelated shapes (Reference §9). Empty,
     * with F1, when a name is unknown.
     */
    fun basesOf(name: String): Set<String> = basesCache.getOrPut(name) {
        when {
            name in shapes -> setOf(name)
            name in refinements -> {
                basesCache[name] = emptySet() // break cycles; V14 reports them
                basesOfExpr(refinements.getValue(name).expr)
            }
            else -> { error("F1", "unknown shape or refinement '$name'"); emptySet() }
        }
    }

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
    fun baseOf(name: String): String? = basesOf(name).singleOrNull()

    fun baseOfExpr(expr: RefExpr): String? = basesOfExpr(expr).singleOrNull()

    /** A refinement whose members span more than one base shape. */
    fun isCrossBase(name: String): Boolean = basesOf(name).size > 1

    /**
     * A static union: a named refinement that is a pure `or` of base shapes (or
     * of static unions) with no predicate anywhere. Its member set is fixed by
     * declarations, never by data, so it is stable enough to be a field's
     * target type (Reference §6, the static-union field-type rule).
     */
    fun isStaticUnion(name: String): Boolean =
        refinements[name]?.let { pureOr(it.expr) && basesOf(name).size > 1 } == true

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

    // ── member lookup ────────────────────────────────────────────────────────

    /**
     * Members visible on a shape-or-refinement scope: for a base shape, its own
     * declared members plus inferred inverse collections; for a refinement, its
     * own body members plus everything visible on its operands (union — Reference §9's
     * `or`-restriction is a check's concern, not lookup's).
     */
    fun membersOf(scope: String): Map<String, MemberInfo> =
        memberCache.getOrPut(scope) { computeMembers(scope) }

    private val memberCache = mutableMapOf<String, Map<String, MemberInfo>>()

    private fun computeMembers(scope: String): Map<String, MemberInfo> {
        val out = linkedMapOf<String, MemberInfo>()
        fun add(owner: String, m: Member) {
            when (m) {
                is StoredProp -> out[m.name] = MemberInfo(m.name, owner, typeOf(m.type), stored = true, visible = m.visible)
                is DerivedProp -> out[m.name] =
                    MemberInfo(m.name, owner, typeOf(m.type), stored = false, derived = m, captured = m.captured, visible = m.visible)
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
            }
        }
        shapes[scope]?.let { shape ->
            shape.members.forEach { add(scope, it) }
            // inferred inverse collections (Reference §6) — from the declared side's
            // `one` (a one-to-many's child pointer) or bare `many` (an m2m edge
            // set). None from a transient act: the collection would be a read of
            // instances that are not kept (Reference §4, "Transient acts"). Two
            // declared sides at the same target make the decapitalize-and-
            // pluralize name ambiguous — no inverse is inferred, and use sites
            // demand declared derived views (Reference §6; the `Transfer` example).
            for ((otherName, other) in shapes) {
                if (otherName in transients) continue
                // a field typed with a static union targets every operand (Reference §9):
                // `Comment.target: one Content` puts `comments` on each operand
                val targeting = other.members.filterIsInstance<StoredProp>()
                    .filter { (it.type as? RelType)?.shape?.let { t -> typeAccepts(t, scope) } == true }
                if (targeting.size != 1) continue
                val f = targeting.single()
                val inverse = otherName.replaceFirstChar { it.lowercase() } + "s"
                out.putIfAbsent(inverse, MemberInfo(inverse, scope, VType.Coll(otherName), stored = false,
                    inverse = InverseInfo(otherName, f.name, (f.type as RelType).many)))
            }
            out["id"] = MemberInfo("id", scope, VType.Id, stored = false)
            return out
        }
        refinements[scope]?.let { r ->
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
                    out[n] = if (first.inverse != null) first.copy(owner = scope) else first
                }
                out["id"] = MemberInfo("id", scope, VType.Id, stored = false)
            } else {
                collectOperandScopes(r.expr).forEach { operand -> out.putAll(membersOf(operand)) }
            }
            r.members.forEach { add(scope, it) }
            return out
        }
        return out
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
        withDecls.entries.associate { (shape, entries) ->
            if (shape in transients) {
                error("V22", "transient act '$shape' with a closure is refused — " +
                    "the combination is open (OQ43); commit the request without inline parts")
                return@associate shape to emptyList()
            }
            shape to resolveClosureLevel(shape, entries)
        }
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
        entry.children.groupBy { it.name }.filterValues { it.size > 1 }.keys.forEach {
            error("V22", "'act ... with': closure edge '$it' declared twice under '$scope.${entry.name}'")
        }
        val children = mutableListOf<ClosureEdge>()
        for (child in entry.children.distinctBy { it.name }) {
            val owners = operands.filter { membersOf(it).containsKey(child.name) }
            if (owners.isEmpty()) {
                error("V22", "'act ... with ${entry.name} with { ${child.name} }': no operand of '$target' has '${child.name}'")
                continue
            }
            for (owner in owners) resolveClosureEdge(owner, child)?.let { children.add(it) }
        }
        return ClosureEdge(entry.name, target, backRef = "", children = children, byValue = true, owner = scope)
    }

    /**
     * Every shape a commit of [act] may create (Reference §6; OQ63 §6.2.2): the
     * act, its parts, the operands of its `one` fields named in `with`, recursively.
     */
    fun closureShapes(act: String): Set<String> {
        val out = linkedSetOf(act)
        fun visit(e: ClosureEdge) {
            if (e.byValue) out.addAll(basesOf(e.partShape)) else out.add(e.partShape)
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
            CaptureSchema(name, base, props.map { CaptureSchema.Prop(it.name, typeOf(it.type)) })
        }
    }

    fun typeOf(t: TypeRef): VType = when (t) {
        is ScalarType -> if (t.many) VType.CollS(t.name) else when (t.name) {
            "text" -> VType.Text
            "integer", "long", "decimal", "double" -> VType.Num(t.name)
            "boolean" -> VType.Bool
            "Date" -> VType.DateT
            "DateTime" -> VType.DateTimeT
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

    private val summaryCache = mutableMapOf<String, ReadSummary>()

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
    fun collectExpr(e: Expr, subject: String, s: ReadSummary, aliases: Map<String, String> = emptyMap(), root: String = subject) {
        when (e) {
            is TodayLit, is NowLit -> s.readsTime = true
            is PathExpr -> collectPath(e, subject, s, aliases, root)
            is UnaryMinus -> collectExpr(e.inner, subject, s, aliases, root)
            is Binary -> { collectExpr(e.left, subject, s, aliases, root); collectExpr(e.right, subject, s, aliases, root) }
            is NotExpr -> collectExpr(e.inner, subject, s, aliases, root)
            is IfExpr -> { collectExpr(e.condition, subject, s, aliases, root); collectExpr(e.thenExpr, subject, s, aliases, root); collectExpr(e.elseExpr, subject, s, aliases, root) }
            is IsExpr -> {
                collectExpr(e.subject, subject, s, aliases, root)
                e.refinement?.let { if (it in refinements) s.absorb(predicateSummary(it)) }
            }
            is ExistsExpr -> {
                e.shape?.let { consultShape(it, s) }
                e.forExpr?.let { collectExpr(it, subject, s, aliases, root) }
                e.collection?.let { collectCollection(it, subject, s, aliases, root) }
            }
            is AggCall -> collectAgg(e, subject, s, aliases, root)
            is FunCall -> e.args.forEach { collectExpr(it, subject, s, aliases, root) }
            is SingularFor -> { consultShape(e.shape, s); collectExpr(e.forExpr, subject, s, aliases, root) }
            is Access -> {
                val start: String? = when (val t = e.target) {
                    is AggCall -> collectAgg(t, subject, s, aliases, root)
                        .takeIf { t.name == "latest" || t.name == "first" }
                    is SingularFor -> {
                        consultShape(t.shape, s)
                        collectExpr(t.forExpr, subject, s, aliases, root)
                        t.shape
                    }
                    else -> { collectExpr(t, subject, s, aliases, root); null }
                }
                walkSegs(start, e.segs, s)
            }
            is ShapeForSource -> { consultShape(e.shape, s); collectExpr(e.forExpr, subject, s, aliases, root) }
            is SetExpr -> collectCollection(e.collection, subject, s, aliases, root)
            else -> {}
        }
    }

    /** A shape-or-refinement's instances are consulted: membership in a consulted
     *  *refinement* also depends on everything its own predicate reads. */
    private fun consultShape(name: String, s: ReadSummary) {
        s.existsShapes.add(name)
        if (name in refinements) s.absorb(predicateSummary(name))
    }

    /** Collects an aggregate's reads (collection, sum's selected field, and the
     *  selectors' `by` datums); returns the element scope, null when unresolvable. */
    private fun collectAgg(e: AggCall, subject: String, s: ReadSummary, aliases: Map<String, String>, root: String = subject): String? {
        val elem = collectCollection(e.collection, subject, s, aliases, root)
        for (f in listOfNotNull(e.field) + e.orderBy) {
            val m = elem?.let { membersOf(it)[f] }
            if (m != null) record(elem, m, s) else s.opaque = true
        }
        return elem
    }

    /** Returns the last binding's element scope (the innermost scope the shared
     *  `where` evaluates bare names against), null when it can't be resolved. */
    private fun collectCollection(c: CollectionExpr, subject: String, s: ReadSummary, aliases: Map<String, String> = emptyMap(), root: String = subject): String? {
        var elementScope: String? = null
        var env = aliases
        for (b in c.bindings) {
            var bScope: String? = null
            when (val src = b.source) {
                is PathExpr -> {
                    if ((src.root in shapes || src.root in refinements) && src.segs.isEmpty()) {
                        s.existsShapes.add(baseOf(src.root) ?: src.root)
                        if (src.root in refinements) s.absorb(predicateSummary(src.root))
                        bScope = src.root
                    } else {
                        collectPath(src, subject, s, env, root)
                        bScope = pathElementShape(src, subject, env, root)
                        if (bScope == null) s.opaque = true
                    }
                }
                is ShapeForSource -> {
                    consultShape(src.shape, s)
                    collectExpr(src.forExpr, subject, s, env, root)
                    bScope = src.shape
                }
                else -> { collectExpr(src, subject, s, env, root); s.opaque = true }
            }
            if (b.alias != null && bScope != null) env = env + (b.alias to bScope)
            if (bScope != null) elementScope = bScope
        }
        // the shared `where` resolves bare names against the innermost element,
        // while `this` keeps naming the outermost subject (Reference §10)
        c.where?.let { collectExpr(it, elementScope ?: subject, s, env, root) }
        return elementScope
    }

    private fun collectPath(p: PathExpr, subject: String, s: ReadSummary, aliases: Map<String, String> = emptyMap(), root: String = subject) {
        val scope: String? = when {
            p.root == "this" -> root
            aliases[p.root] == VALUE_ALIAS -> { if (p.segs.isNotEmpty()) s.opaque = true; return }
            p.root in aliases -> aliases.getValue(p.root)
            p.root in shapes || p.root in refinements -> {
                // bare refinement name as a membership atom
                if (p.root in refinements) s.absorb(predicateSummary(p.root))
                if (p.segs.isNotEmpty()) s.opaque = true
                return
            }
            else -> {
                val m = membersOf(subject)[p.root]
                if (m == null) { s.opaque = true; return }
                record(subject, m, s)
                m.type.instanceShape()
            }
        }
        walkSegs(scope, p.segs, s)
    }

    private fun walkSegs(start: String?, segs: List<Seg>, s: ReadSummary) {
        var scope = start
        for (seg in segs) {
            val sc = scope
            if (sc == null) { s.opaque = true; return }
            val m = membersOf(sc)[seg.name]
            if (m == null) { s.opaque = true; return }
            record(sc, m, s)
            scope = m.type.instanceShape()
        }
    }

    private fun record(scope: String, m: MemberInfo, s: ReadSummary) {
        if (m.stored) s.fields.add(m.owner to m.name)
        if (m.timestamp) s.collFields.add(m.owner to m.name)
        (m.type as? VType.Coll)?.let { s.collShapes.add(it.shape) }
        m.derived?.let { d ->
            val key = m.owner to m.name
            if (s.derivedSeen.add(key)) collectExpr(d.expr, m.owner, s)
        }
    }

    /** The element scope of a collection expression, or null when it cannot be resolved. */
    fun collectionElement(c: CollectionExpr, subject: String, aliases: Map<String, String> = emptyMap()): String? =
        collectCollection(c, subject, ReadSummary(), aliases)

    /** The scalar type a `many <scalar>` member's elements have. */
    fun scalarOf(name: String): VType = typeOf(ScalarType(name, optional = false))

    fun pathElementShape(p: PathExpr, subject: String, aliases: Map<String, String> = emptyMap(), root: String = subject): String? {
        var scope: String? = when {
            p.root == "this" -> root
            aliases[p.root] == VALUE_ALIAS -> return null
            p.root in aliases -> aliases.getValue(p.root)
            else -> membersOf(subject)[p.root]?.type?.let { it.instanceShape() ?: (it as? VType.Coll)?.shape }
        }
        for (seg in p.segs) {
            val m = membersOf(scope ?: return null)[seg.name] ?: return null
            scope = m.type.instanceShape() ?: (m.type as? VType.Coll)?.shape
        }
        return scope
    }
}

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
     *  each operand, each marked with its [owner]. */
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
    data object Id : VType
    data class Inst(val shape: String) : VType
    data class Coll(val shape: String) : VType
    /** An owned collection of scalar values (`many text`, Reference §6). */
    data class CollS(val name: String) : VType
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

class ReadSummary {
    /** (owner shape, stored field) pairs the predicate/expression reads */
    val fields = mutableSetOf<Pair<String, String>>()
    /** shapes whose existence/instances are consulted (exists, selectors, for-queries) */
    val existsShapes = mutableSetOf<String>()
    var readsTime = false
    internal val derivedSeen = mutableSetOf<Pair<String, String>>()

    // The three members below serve the runtime's relevance analysis (which
    // commits can change this predicate's value — Runtime.kt, hydration); the
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
