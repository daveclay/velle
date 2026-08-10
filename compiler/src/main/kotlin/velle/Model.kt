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
    /** shape name → mechanism, from inline and standalone expose declarations */
    val exposed = linkedMapOf<String, String>()

    init {
        for (d in decls) when (d) {
            is ShapeDecl -> {
                if (!register(d.name)) continue
                shapes[d.name] = d
                d.exposedVia?.let { exposed[d.name] = it }
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
                else -> exposed[d.shape] = d.mechanism
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
            // inferred inverse collections (README §6)
            for ((otherName, other) in shapes) {
                for (m in other.members) {
                    if (m is StoredProp && m.type is RelType && !m.type.many && m.type.shape == scope) {
                        val inverse = otherName.replaceFirstChar { it.lowercase() } + "s"
                        out.putIfAbsent(inverse, MemberInfo(inverse, scope, VType.Coll(otherName), stored = false))
                    }
                }
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

    fun typeOf(t: TypeRef): VType = when (t) {
        is ScalarType -> when (t.name) {
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

    private fun collectRefExpr(expr: RefExpr, s: ReadSummary) {
        when (expr) {
            is RefName -> {
                if (expr.name in refinements) s.absorb(predicateSummary(expr.name))
                expr.where?.let { collectExpr(it, baseOfExpr(expr) ?: return, s) }
            }
            is RefNot -> collectRefExpr(expr.inner, s)
            is RefAnd -> { collectRefExpr(expr.left, s); collectRefExpr(expr.right, s) }
            is RefOr -> { collectRefExpr(expr.left, s); collectRefExpr(expr.right, s) }
        }
    }

    /** Collect reads of an expression evaluated with `subject` as its scope. */
    fun collectExpr(e: Expr, subject: String, s: ReadSummary) {
        when (e) {
            is TodayLit, is NowLit -> s.readsTime = true
            is PathExpr -> collectPath(e, subject, s)
            is UnaryMinus -> collectExpr(e.inner, subject, s)
            is Binary -> { collectExpr(e.left, subject, s); collectExpr(e.right, subject, s) }
            is NotExpr -> collectExpr(e.inner, subject, s)
            is IfExpr -> { collectExpr(e.condition, subject, s); collectExpr(e.thenExpr, subject, s); collectExpr(e.elseExpr, subject, s) }
            is IsExpr -> {
                collectExpr(e.subject, subject, s)
                e.refinement?.let { if (it in refinements) s.absorb(predicateSummary(it)) }
            }
            is ExistsExpr -> {
                e.shape?.let { s.existsShapes.add(it) }
                e.forExpr?.let { collectExpr(it, subject, s) }
                e.collection?.let { collectCollection(it, subject, s) }
            }
            is AggCall -> collectCollection(e.collection, subject, s)
            is FunCall -> e.args.forEach { collectExpr(it, subject, s) }
            is SingularFor -> { s.existsShapes.add(e.shape); collectExpr(e.forExpr, subject, s) }
            is Access -> collectExpr(e.target, subject, s)
            is ShapeForSource -> { s.existsShapes.add(e.shape); collectExpr(e.forExpr, subject, s) }
            else -> {}
        }
    }

    private fun collectCollection(c: CollectionExpr, subject: String, s: ReadSummary) {
        var elementScope = subject
        for (b in c.bindings) {
            when (val src = b.source) {
                is PathExpr -> {
                    if (src.root in shapes || src.root in refinements) {
                        s.existsShapes.add(baseOf(src.root) ?: src.root)
                        if (src.root in refinements) s.absorb(predicateSummary(src.root))
                        elementScope = baseOf(src.root) ?: subject
                    } else {
                        collectPath(src, subject, s)
                        pathElementShape(src, subject)?.let { elementScope = it }
                    }
                }
                is ShapeForSource -> {
                    s.existsShapes.add(src.shape)
                    collectExpr(src.forExpr, subject, s)
                    elementScope = baseOf(src.shape) ?: subject
                }
                else -> collectExpr(src, subject, s)
            }
        }
        c.where?.let { collectExpr(it, elementScope, s) }
    }

    private fun collectPath(p: PathExpr, subject: String, s: ReadSummary) {
        var scope: String? = if (p.root == "this") subject else null
        if (scope == null) {
            if (p.root in shapes || p.root in refinements) {
                // bare refinement name as a membership atom
                if (p.root in refinements) s.absorb(predicateSummary(p.root))
                return
            }
            val m = membersOf(subject)[p.root] ?: return
            record(subject, m, s)
            scope = m.type.instanceShape()
        }
        for (seg in p.segs) {
            val sc = scope ?: return
            val m = membersOf(sc)[seg.name] ?: return
            record(sc, m, s)
            scope = m.type.instanceShape()
        }
    }

    private fun record(scope: String, m: MemberInfo, s: ReadSummary) {
        if (m.stored) s.fields.add(m.owner to m.name)
        m.derived?.let { d ->
            val key = m.owner to m.name
            if (s.derivedSeen.add(key)) collectExpr(d.expr, m.owner, s)
        }
    }

    fun pathElementShape(p: PathExpr, subject: String): String? {
        var scope: String? = if (p.root == "this") subject
            else membersOf(subject)[p.root]?.type?.let { it.instanceShape() ?: (it as? VType.Coll)?.shape }
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
)

sealed interface VType {
    data class Num(val name: String) : VType
    data object Text : VType
    data object Bool : VType
    data object DateT : VType
    data object DateTimeT : VType
    data object Id : VType
    data class Inst(val shape: String) : VType
    data class Coll(val shape: String) : VType
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

    fun absorb(other: ReadSummary) {
        fields.addAll(other.fields)
        existsShapes.addAll(other.existsShapes)
        if (other.readsTime) readsTime = true
    }
}

data class Diagnostic(val code: String, val message: String, val advisory: Boolean = false) {
    override fun toString() = "[$code] $message"
}
