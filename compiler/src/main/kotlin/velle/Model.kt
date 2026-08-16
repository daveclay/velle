package velle

/**
 * Resolved-spec model: symbol tables, refinement→base resolution, member lookup
 * (including refinement properties and inferred inverse relationships), and the
 * read/write summaries the checks in Validator.kt consume.
 *
 * Inverse relationships (README §6): `LineItem.invoice: one Invoice` gives
 * `Invoice` a derived collection member named decapitalize(LineItem) + "s" =
 * `lineItems`. The naming rule is a convention the README uses by example
 * (`invoices`, `payments`) without stating it — flagged for the docs.
 */
class Model(val decls: List<Decl>) {

    val diagnostics = mutableListOf<Diagnostic>()

    val shapes = linkedMapOf<String, ShapeDecl>()
    val refinements = linkedMapOf<String, RefinementDecl>()
    val rules = linkedMapOf<String, RuleDecl>()
    val nevers = mutableListOf<NeverDecl>()
    /** externally committable shapes, from inline and standalone expose declarations */
    val exposed = linkedSetOf<String>()

    /** shapes exposed `transient` — inputs to the state, not members of it (README §4) */
    val transients = mutableSetOf<String>()

    init {
        for (d in decls) when (d) {
            is ShapeDecl -> {
                if (!register(d.name)) continue
                shapes[d.name] = d
                if (d.exposed) exposed.add(d.name)
                if (d.transient) transients.add(d.name)
            }
            is RefinementDecl -> if (register(d.name)) refinements[d.name] = d
            is RuleDecl ->
                if (rules.put(d.name, d) != null)
                    error("F1", "duplicate rule '${d.name}'")
            is NeverDecl -> nevers.add(d)
            is ExposeDecl -> {} // second pass, after all shapes exist
        }
        for (d in decls) if (d is ExposeDecl) {
            when {
                d.shape !in shapes -> error("F1", "expose names unknown shape '${d.shape}'")
                d.shape in exposed -> error("F1", "shape '${d.shape}' is exposed twice")
                else -> {
                    exposed.add(d.shape)
                    if (d.transient) transients.add(d.shape)
                }
            }
        }
    }

    private fun register(name: String): Boolean {
        if (name in shapes || name in refinements) {
            error("F1", "duplicate shape/refinement name '$name'")
            return false
        }
        return true
    }

    fun error(code: String, message: String) = diagnostics.add(Diagnostic(code, message))

    // ── refinement → base shape ──────────────────────────────────────────────

    private val baseCache = mutableMapOf<String, String?>()

    /** Base shape name for a shape or refinement name; null (with F1) if unresolvable. */
    fun baseOf(name: String): String? = baseCache.getOrPut(name) {
        when {
            name in shapes -> name
            name in refinements -> baseOfExpr(refinements.getValue(name).expr)
            else -> { error("F1", "unknown shape or refinement '$name'"); null }
        }
    }

    fun baseOfExpr(expr: RefExpr): String? = when (expr) {
        is RefName -> baseOf(expr.name)
        is RefNot -> baseOfExpr(expr.inner)
        is RefAnd -> commonBase(expr.left, expr.right)
        is RefOr -> commonBase(expr.left, expr.right)
    }

    private fun commonBase(l: RefExpr, r: RefExpr): String? {
        val lb = baseOfExpr(l) ?: return baseOfExpr(r)
        val rb = baseOfExpr(r) ?: return lb
        if (lb != rb) error("F2", "composed refinements must share a base shape ($lb vs $rb)")
        return lb
    }

    // ── member lookup ────────────────────────────────────────────────────────

    /**
     * Members visible on a shape-or-refinement scope: for a base shape, its own
     * declared members plus inferred inverse collections; for a refinement, its
     * own body members plus everything visible on its operands (union — README §9's
     * `or`-restriction is a check's concern, not lookup's).
     */
    fun membersOf(scope: String): Map<String, MemberInfo> =
        memberCache.getOrPut(scope) { computeMembers(scope) }

    private val memberCache = mutableMapOf<String, Map<String, MemberInfo>>()

    private fun computeMembers(scope: String): Map<String, MemberInfo> {
        val out = linkedMapOf<String, MemberInfo>()
        fun add(owner: String, m: Member) {
            when (m) {
                is StoredProp -> out[m.name] = MemberInfo(m.name, owner, typeOf(m.type), stored = true)
                is DerivedProp -> out[m.name] =
                    MemberInfo(m.name, owner, typeOf(m.type), stored = false, derived = m, captured = m.captured)
                is TimestampProp -> out[m.name] =
                    MemberInfo(m.name, owner, VType.DateTimeT, stored = false, timestamp = true)
                is FrozenClause -> {}
            }
        }
        shapes[scope]?.let { shape ->
            shape.members.forEach { add(scope, it) }
            // inferred inverse collections (README §6) — from the declared side's
            // `one` (a one-to-many's child pointer) or bare `many` (an m2m edge
            // set). None from a transient act: the collection would be a read of
            // instances that are not kept (README §4, "Transient acts"). Two
            // declared sides at the same target make the decapitalize-and-
            // pluralize name ambiguous — no inverse is inferred, and use sites
            // demand declared derived views (README §6; the `Transfer` example).
            for ((otherName, other) in shapes) {
                if (otherName in transients) continue
                val targeting = other.members.filterIsInstance<StoredProp>()
                    .filter { (it.type as? RelType)?.shape == scope }
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
            collectOperandScopes(r.expr).forEach { operand -> out.putAll(membersOf(operand)) }
            r.members.forEach { add(scope, it) }
            return out
        }
        return out
    }

    private fun collectOperandScopes(expr: RefExpr): List<String> = when (expr) {
        is RefName -> if (expr.name in shapes || expr.name in refinements) listOf(expr.name) else emptyList()
        is RefNot -> collectOperandScopes(expr.inner)
        is RefAnd -> collectOperandScopes(expr.left) + collectOperandScopes(expr.right)
        is RefOr -> collectOperandScopes(expr.left) + collectOperandScopes(expr.right)
    }

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
            else -> VType.Unknown
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
     *  [aliases] maps `as`-bound names to their element scopes (README §10). */
    fun collectExpr(e: Expr, subject: String, s: ReadSummary, aliases: Map<String, String> = emptyMap()) {
        when (e) {
            is TodayLit, is NowLit -> s.readsTime = true
            is PathExpr -> collectPath(e, subject, s, aliases)
            is UnaryMinus -> collectExpr(e.inner, subject, s, aliases)
            is Binary -> { collectExpr(e.left, subject, s, aliases); collectExpr(e.right, subject, s, aliases) }
            is NotExpr -> collectExpr(e.inner, subject, s, aliases)
            is IfExpr -> { collectExpr(e.condition, subject, s, aliases); collectExpr(e.thenExpr, subject, s, aliases); collectExpr(e.elseExpr, subject, s, aliases) }
            is IsExpr -> {
                collectExpr(e.subject, subject, s, aliases)
                e.refinement?.let { if (it in refinements) s.absorb(predicateSummary(it)) }
            }
            is ExistsExpr -> {
                e.shape?.let { consultShape(it, s) }
                e.forExpr?.let { collectExpr(it, subject, s, aliases) }
                e.collection?.let { collectCollection(it, subject, s, aliases) }
            }
            is AggCall -> collectAgg(e, subject, s, aliases)
            is FunCall -> e.args.forEach { collectExpr(it, subject, s, aliases) }
            is SingularFor -> { consultShape(e.shape, s); collectExpr(e.forExpr, subject, s, aliases) }
            is Access -> {
                val start: String? = when (val t = e.target) {
                    is AggCall -> collectAgg(t, subject, s, aliases)
                        .takeIf { t.name == "latest" || t.name == "first" }
                    is SingularFor -> {
                        consultShape(t.shape, s)
                        collectExpr(t.forExpr, subject, s, aliases)
                        t.shape
                    }
                    else -> { collectExpr(t, subject, s, aliases); null }
                }
                walkSegs(start, e.segs, s)
            }
            is ShapeForSource -> { consultShape(e.shape, s); collectExpr(e.forExpr, subject, s, aliases) }
            is SetExpr -> collectCollection(e.collection, subject, s, aliases)
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
    private fun collectAgg(e: AggCall, subject: String, s: ReadSummary, aliases: Map<String, String>): String? {
        val elem = collectCollection(e.collection, subject, s, aliases)
        for (f in listOfNotNull(e.field) + e.orderBy) {
            val m = elem?.let { membersOf(it)[f] }
            if (m != null) record(elem, m, s) else s.opaque = true
        }
        return elem
    }

    /** Returns the last binding's element scope (the innermost scope the shared
     *  `where` evaluates bare names against), null when it can't be resolved. */
    private fun collectCollection(c: CollectionExpr, subject: String, s: ReadSummary, aliases: Map<String, String> = emptyMap()): String? {
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
                        collectPath(src, subject, s, env)
                        bScope = pathElementShape(src, subject, env)
                        if (bScope == null) s.opaque = true
                    }
                }
                is ShapeForSource -> {
                    consultShape(src.shape, s)
                    collectExpr(src.forExpr, subject, s, env)
                    bScope = src.shape
                }
                else -> { collectExpr(src, subject, s, env); s.opaque = true }
            }
            if (b.alias != null && bScope != null) env = env + (b.alias to bScope)
            if (bScope != null) elementScope = bScope
        }
        c.where?.let { collectExpr(it, elementScope ?: subject, s, env) }
        return elementScope
    }

    private fun collectPath(p: PathExpr, subject: String, s: ReadSummary, aliases: Map<String, String> = emptyMap()) {
        val scope: String? = when {
            p.root == "this" -> subject
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

    fun pathElementShape(p: PathExpr, subject: String, aliases: Map<String, String> = emptyMap()): String? {
        var scope: String? = when {
            p.root == "this" -> subject
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
    /** Set on an inferred inverse collection: the declared side it is a view of (README §6). */
    val inverse: InverseInfo? = null,
)

/** The declared side an inferred inverse reads: [shape].[field], to-one or an m2m `many`. */
data class InverseInfo(val shape: String, val field: String, val many: Boolean)

sealed interface VType {
    data class Num(val name: String) : VType
    data object Text : VType
    data object Bool : VType
    data object DateT : VType
    data object DateTimeT : VType
    data object Id : VType
    data class Inst(val shape: String) : VType
    data class Coll(val shape: String) : VType
    /** An owned collection of scalar values (`many text`, README §6). */
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
