package velle

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

// ── The read protocol (README §22, the composed query; OQ51 R5, R7, R10) ────
//
// A query is a value: a root (a shape or refinement name), an optional filter
// written as the spec's own predicate AST over the root's members, a required
// order, and an optional page. The runtime answers it from a read-only
// snapshot (evaluation.md, "A read is a read-only snapshot") with the
// instances' session handles in the requested order plus a cursor; the typed
// builder the codegen emits maps the handles to the shape's generated Kotlin
// data classes. Nothing in this file names a business shape: it is the
// framework's contract, spec-agnostic, like the store protocol in Hydration.kt.

/**
 * One read request. [where] is a predicate over the members of [root] in the
 * spec's predicate language; [orderBy] is required (R10) — one or more orderable
 * members with a direction; [page] is a window over the ordered rows; [viewer]
 * is the slot visibility will fill (OQ33; R8: present, unused).
 */
data class Query(
    val root: String,
    val where: Expr? = null,
    val orderBy: List<OrderDatum> = emptyList(),
    val page: Page? = null,
    val viewer: Long? = null,
)

/** One order datum: a member of the root and a direction. */
data class OrderDatum(val member: String, val descending: Boolean = false)

/** A window over the ordered rows: at most [size] rows after [after]. */
data class Page(val size: Int, val after: Cursor? = null) {
    init { require(size > 0) { "page size must be positive" } }
}

/**
 * Where a page ended (R2): the last row's order datums plus its store key as
 * the hidden tie-break, so the next page starts after a pair that tells every
 * row apart. Opaque to callers — pass it back as [Page.after]. The key is never
 * an ordering datum the caller sees (README §5).
 */
class Cursor internal constructor(internal val datums: List<Value>, internal val key: Comparable<Any>) {
    override fun toString() = "Cursor(${datums.joinToString()}; key=$key)"
}

/** The generic answer: handles in order, and the cursor for the next page when more rows follow. */
data class QueryResult(val ids: List<Long>, val next: Cursor?)

/** A query the checks refuse: the diagnostics name what is wrong, in the validator's terms. */
class QueryRefused(val diagnostics: List<Diagnostic>) :
    IllegalArgumentException("query refused: " + diagnostics.joinToString("; ") { it.message })

/**
 * Answers queries over a [VelleSystem]: checks the request against the model,
 * runs the filter through the pre-filter compiler and the one evaluator
 * (investigate_runtime.md §6), orders, and cuts the page.
 */
internal class QueryEngine(private val system: VelleSystem) {

    private val model get() = system.model
    private val validator by lazy { Validator(model) }

    /** The checks a query must pass, as diagnostics; empty means it runs. */
    fun check(q: Query): List<Diagnostic> {
        val root = q.root
        val out = mutableListOf<Diagnostic>()
        when {
            root in model.transients ->
                out.add(Diagnostic("F1", "'$root' is a transient act: an input to the state, never readable (README §4)"))
            root in model.shapes -> {}
            root in model.refinements -> {
                if (model.basesOf(root).any { it in model.transients })
                    out.add(Diagnostic("F1", "'$root' refines a transient act: never readable (README §4)"))
                if (model.isCrossBase(root))
                    out.add(Diagnostic("F1", "'$root' is a cross-base union: query each operand (README §9)"))
            }
            else -> out.add(Diagnostic("F1", "unknown shape or refinement '$root'"))
        }
        if (out.isNotEmpty()) return out
        q.where?.let { out.addAll(validator.checkQueryPredicate(it, root)) }
        out.addAll(validator.checkQueryOrder(q.orderBy.map { it.member }, root))
        return out
    }

    private fun checked(q: Query) {
        val diags = check(q)
        if (diags.isNotEmpty()) throw QueryRefused(diags)
    }

    /** Every handle the filter admits, in no particular order, from a fresh snapshot. */
    private fun matching(q: Query): List<Long> {
        system.openReadSnapshot()
        val cond = RefName(q.root, q.where)
        val base = model.baseOf(q.root) ?: q.root
        val filter = system.queryCompiler().filterFor(cond)
        return system.idsOfCandidates(base, filter).filter { system.evaluator.memberOfRefExpr(it, cond) }
    }

    private class Keyed(val id: Long, val datums: List<Value>, val key: Comparable<Any>)

    private fun keyed(q: Query, id: Long): Keyed = Keyed(
        id,
        q.orderBy.map { system.evaluator.readMember(id, q.root, it.member) },
        system.orderKeyOf(id),
    )

    /** The order: each datum in its direction, absent values last, then the hidden key ascending. */
    private fun comparator(q: Query): Comparator<Keyed> = Comparator { a, b ->
        for ((i, d) in q.orderBy.withIndex()) {
            val c = system.evaluator.orderCompare(a.datums[i], b.datums[i])
            if (c != 0) return@Comparator if (d.descending) -c else c
        }
        a.key.compareTo(b.key)
    }

    fun run(q: Query): QueryResult {
        checked(q)
        val cmp = comparator(q)
        var rows = matching(q).map { keyed(q, it) }.sortedWith(cmp)
        val page = q.page ?: return QueryResult(rows.map { it.id }, null)
        page.after?.let { c ->
            require(c.datums.size == q.orderBy.size) { "cursor does not belong to this query's order" }
            val mark = Keyed(-1, c.datums, c.key)
            rows = rows.filter { cmp.compare(it, mark) > 0 }
        }
        val window = rows.take(page.size)
        val next = if (rows.size > page.size) window.last().let { Cursor(it.datums, it.key) } else null
        return QueryResult(window.map { it.id }, next)
    }

    /** The checks for an aggregate: everything but the order, which a count or sum does not need. */
    private fun checkedUnordered(q: Query, extra: List<Diagnostic> = emptyList()) {
        val diags = check(q.copy(orderBy = emptyList()))
            .filterNot { it.message.startsWith("a query names its order") } + extra
        if (diags.isNotEmpty()) throw QueryRefused(diags)
    }

    fun count(q: Query): Int {
        checkedUnordered(q)
        return matching(q).size
    }

    fun sum(q: Query, member: String): BigDecimal {
        val m = model.membersOf(q.root)[member]
        val extra = when {
            m == null -> listOf(Diagnostic("F1", "sum of '$member' — '${q.root}' has no member '$member'"))
            ((m.type as? VType.Optional)?.inner ?: m.type) !is VType.Num ->
                listOf(Diagnostic("F2", "sum of '$member' — ${q.root}.$member is not a number"))
            else -> emptyList()
        }
        checkedUnordered(q, extra)
        var total = BigDecimal.ZERO
        for (id in matching(q)) {
            val v = system.evaluator.readMember(id, q.root, member)
            if (v is Value.VNum) total = total.add(v.v)
        }
        return total
    }
}

// ── The typed builder's vocabulary (what the codegen's fields classes extend) ─

/** A member path from the query's root; the root itself is the empty path. */
open class Fields(val fieldPath: List<String> = emptyList()) {
    internal fun pathExpr(): Expr = PathExpr("this", fieldPath.map { Seg(it, false) })
    fun isNone(): Expr = IsExpr(pathExpr(), "none")
    fun isSome(): Expr = IsExpr(pathExpr(), "some")
    /** Membership of a refinement, for a reference or the root (`customer is VipCustomer`). */
    fun isA(refinement: String): Expr = IsExpr(pathExpr(), "refinement", refinement)
    /** Identity: the referenced instance is [v] (a view handle or a data class carrying its id). */
    infix fun eq(v: View): Expr = Binary("==", pathExpr(), ValueLit(Value.VRef(v.id)))
    infix fun neq(v: View): Expr = Binary("!=", pathExpr(), ValueLit(Value.VRef(v.id)))
}

/** A scalar member: comparable, and an order datum in either direction. */
abstract class ScalarField<T : Any>(path: List<String>) : Fields(path) {
    protected abstract fun lit(v: T): Expr
    infix fun eq(v: T): Expr = Binary("==", pathExpr(), lit(v))
    infix fun neq(v: T): Expr = Binary("!=", pathExpr(), lit(v))
    infix fun lt(v: T): Expr = Binary("<", pathExpr(), lit(v))
    infix fun lte(v: T): Expr = Binary("<=", pathExpr(), lit(v))
    infix fun gt(v: T): Expr = Binary(">", pathExpr(), lit(v))
    infix fun gte(v: T): Expr = Binary(">=", pathExpr(), lit(v))
    val ascending: OrderDatum get() = OrderDatum(fieldPath.single(), descending = false)
    val descending: OrderDatum get() = OrderDatum(fieldPath.single(), descending = true)
}

class TextField(path: List<String>) : ScalarField<String>(path) {
    override fun lit(v: String): Expr = TextLit(v)
    /** The text operators (README §10): exact and case-sensitive. */
    infix fun contains(v: String): Expr = Binary("contains", pathExpr(), TextLit(v))
    infix fun startsWith(v: String): Expr = Binary("starts with", pathExpr(), TextLit(v))
    infix fun endsWith(v: String): Expr = Binary("ends with", pathExpr(), TextLit(v))
    /** `like`: `%` any run, `_` any one character, `\` escapes either. */
    infix fun like(pattern: String): Expr = Binary("like", pathExpr(), TextLit(pattern))
    /** `lowercase(member)`, for case-folded comparison the author spells visibly. */
    val lowercase: LowercaseText get() = LowercaseText(fieldPath)
}

/** `lowercase(member)` as the left operand of a text comparison. */
class LowercaseText internal constructor(private val path: List<String>) {
    private fun call(): Expr = FunCall("lowercase", listOf(PathExpr("this", path.map { Seg(it, false) })))
    infix fun eq(v: String): Expr = Binary("==", call(), TextLit(v))
    infix fun contains(v: String): Expr = Binary("contains", call(), TextLit(v))
    infix fun startsWith(v: String): Expr = Binary("starts with", call(), TextLit(v))
    infix fun endsWith(v: String): Expr = Binary("ends with", call(), TextLit(v))
    infix fun like(pattern: String): Expr = Binary("like", call(), TextLit(pattern))
}

class NumField(path: List<String>) : ScalarField<Number>(path) {
    override fun lit(v: Number): Expr = ValueLit(Value.num(v))
}

class BoolField(path: List<String>) : Fields(path) {
    infix fun eq(v: Boolean): Expr = Binary("==", pathExpr(), BoolLit(v))
    /** The member itself as a predicate (`where { active() }`). */
    operator fun invoke(): Expr = pathExpr()
}

class DateField(path: List<String>) : ScalarField<LocalDate>(path) {
    override fun lit(v: LocalDate): Expr = ValueLit(Value.VDate(v))
}

class DateTimeField(path: List<String>) : ScalarField<Instant>(path) {
    override fun lit(v: Instant): Expr = ValueLit(Value.VDateTime(v))
}

/** A reference to a shape the codegen has no fields class for (a union): identity tests only. */
class RefField(path: List<String>) : Fields(path)

infix fun Expr.and(other: Expr): Expr = Binary("and", this, other)
infix fun Expr.or(other: Expr): Expr = Binary("or", this, other)
fun not(e: Expr): Expr = NotExpr(e)

/** One page of a typed query: the instances as data classes, and the cursor for the next page. */
data class QueryPage<T>(val items: List<T>, val next: Cursor?)

/**
 * The typed query the codegen emits one subclass of per shape and refinement:
 * a [Query] under construction, the root's fields object for the lambdas, and
 * the materializer from a handle to the shape's data class (R5).
 */
abstract class TypedQuery<F : Fields, T>(
    val system: VelleSystem,
    val query: Query,
    protected val fields: F,
    private val materialize: (Long) -> T,
) {
    protected abstract fun with(q: Query): TypedQuery<F, T>

    /** Narrows the query; a second `where` is a conjunction with the first. */
    fun where(predicate: F.() -> Expr): TypedQuery<F, T> {
        val p = fields.predicate()
        return with(query.copy(where = query.where?.let { Binary("and", it, p) } ?: p))
    }

    /** Appends an order datum; the first call is the primary order. */
    fun orderBy(datum: F.() -> OrderDatum): TypedQuery<F, T> =
        with(query.copy(orderBy = query.orderBy + fields.datum()))

    fun thenBy(datum: F.() -> OrderDatum): TypedQuery<F, T> = orderBy(datum)

    fun page(size: Int, after: Cursor? = null): TypedQuery<F, T> =
        with(query.copy(page = Page(size, after)))

    /** The checks, without running: empty means the query is well-formed against the spec. */
    fun check(): List<Diagnostic> = system.check(query)

    /** Every matching instance, in order, as data classes. */
    fun list(): List<T> = system.query(query).ids.map(materialize)

    /** The page the query names (or everything, with no next cursor, when it names none). */
    fun fetch(): QueryPage<T> = system.query(query).let { QueryPage(it.ids.map(materialize), it.next) }

    fun count(): Int = system.count(query)

    fun sum(member: F.() -> NumField): BigDecimal = system.sum(query, fields.member().fieldPath.single())
}

/**
 * Queries registered beside the client (R9): the engineer writes a query with
 * the typed builder and registers it under a name; [validate] is the check at
 * the engineer's build, naming each registered query a spec change broke.
 */
class QueryRegistry {
    private val entries = linkedMapOf<String, TypedQuery<*, *>>()

    fun <Q : TypedQuery<*, *>> register(name: String, query: Q): Q {
        require(name !in entries) { "query '$name' is already registered" }
        entries[name] = query
        return query
    }

    val names: List<String> get() = entries.keys.toList()

    operator fun get(name: String): TypedQuery<*, *> =
        entries[name] ?: throw IllegalArgumentException("no registered query '$name'")

    /** Every registered query's diagnostics, keyed by name; empty when all are well-formed. */
    fun validate(): Map<String, List<Diagnostic>> =
        entries.mapValues { it.value.check() }.filterValues { it.isNotEmpty() }
}
