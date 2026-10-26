package velle

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

// ── The read protocol (Reference §22, the composed query; OQ51 R5, R7, R10) ────
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
 * is the person the read is for (Reference §22, "The actor"): with one, the
 * answer holds only the records the root's visibility clauses admit to them;
 * null is the engineer's own view of the store, unfiltered.
 */
data class Query(
    val root: String,
    val where: Expr? = null,
    val orderBy: List<OrderDatum> = emptyList(),
    val page: Page? = null,
    val viewer: Ref<*>? = null,
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
 * an ordering datum the caller sees (Reference §5).
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
 * (Hydration.kt, the superset contract), orders, and cuts the page.
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
                out.add(Diagnostic("F1", "'$root' is a transient act: an input to the state, never readable (Reference §4)"))
            root in model.shapes -> {}
            root in model.refinements -> {
                if (model.basesOf(root).any { it in model.transients })
                    out.add(Diagnostic("F1", "'$root' refines a transient act: never readable (Reference §4)"))
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
        val viewer = q.viewer?.let(system::idOf) // a reference, so a query object outlives the scope that runs it
        val cond = RefName(q.root, q.where)
        // a cross-base union (Reference §9) has no one table: every operand's instances, then the predicate
        val base = model.baseOf(q.root)
            ?: return model.basesOf(q.root).flatMap { system.idsOf(it) }
                .filter { system.evaluator.memberOfRefExpr(it, cond) && system.visibleTo(viewer, it) }
        val filter = system.queryCompiler().filterFor(cond)
        // a viewer's query admits only the records the shape's visibility clauses admit
        // (Reference §22, "The actor"): the authoritative check, in memory, after the
        // pre-filter's superset
        return system.idsOfCandidates(base, filter)
            .filter { system.evaluator.memberOfRefExpr(it, cond) && system.visibleTo(viewer, it) }
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

    fun sum(q: Query, member: String): BigDecimal = (sumValue(q, member) as Value.VNum).v

    /** The sum of [member] over the matching rows: a number, or a value of the member's
     *  extension type through its declared `sum` (Reference §5; refused with E3 without one). */
    fun sumValue(q: Query, member: String): Value {
        val m = model.membersOf(q.root)[member]
        val bare = m?.type?.let { (it as? VType.Optional)?.inner ?: it }
        val ext = bare?.let { model.extTypeOf(it) }
        val extra = when {
            m == null -> listOf(Diagnostic("F1", "sum of '$member' — '${q.root}' has no member '$member'"))
            ext != null -> if (ext.summableOrNull == null)
                listOf(Diagnostic("E3", "sum of '$member' — '${model.specNameOf(ext)}' (${ext::class.qualifiedName}) declares no sum"))
                else emptyList()
            bare !is VType.Num ->
                listOf(Diagnostic("F2", "sum of '$member' — ${q.root}.$member is not a number"))
            else -> emptyList()
        }
        checkedUnordered(q, extra)
        if (ext != null) {
            var acc: Any = ext.summableOrNull!!.zero
            for (id in matching(q)) {
                val v = system.evaluator.readMember(id, q.root, member)
                if (v is Value.VExt) acc = ext.sumAny(acc, v.v)
            }
            return Value.VExt(ext, acc)
        }
        var total = BigDecimal.ZERO
        for (id in matching(q)) {
            val v = system.evaluator.readMember(id, q.root, member)
            if (v is Value.VNum) total = total.add(v.v)
        }
        return Value.VNum(total)
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
    /** Identity: the referenced instance is [v] (a reference, or a model object, which is one). */
    infix fun eq(v: Keyed): Expr = Binary("==", pathExpr(), RefLit(v))
    infix fun neq(v: Keyed): Expr = Binary("!=", pathExpr(), RefLit(v))
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
    /** `member in [a, b]` (Reference §10): equal to one of the listed values. */
    fun oneOf(vararg v: T): Expr = Binary("in", pathExpr(), ListLit(v.map { lit(it) }))
    /** `member between a and b` (Reference §10): both bounds included. */
    fun between(lo: T, hi: T): Expr = Binary("and", Binary(">=", pathExpr(), lit(lo)), Binary("<=", pathExpr(), lit(hi)))
    val ascending: OrderDatum get() = OrderDatum(fieldPath.single(), descending = false)
    val descending: OrderDatum get() = OrderDatum(fieldPath.single(), descending = true)
}

class TextField(path: List<String>) : ScalarField<String>(path) {
    override fun lit(v: String): Expr = TextLit(v)
    /** The text operators (Reference §10): exact and case-sensitive. */
    infix fun contains(v: String): Expr = Binary("contains", pathExpr(), TextLit(v))
    infix fun startsWith(v: String): Expr = Binary("starts with", pathExpr(), TextLit(v))
    infix fun endsWith(v: String): Expr = Binary("ends with", pathExpr(), TextLit(v))
    /** `like`: `%` any run, `_` any one character, `\` escapes either. */
    infix fun like(pattern: String): Expr = Binary("like", pathExpr(), TextLit(pattern))
    /** `matches`: a regular expression the whole text must match. */
    infix fun matches(regex: String): Expr = Binary("matches", pathExpr(), TextLit(regex))
    /** `member is empty` (Reference §10): absent, or nothing but whitespace. */
    fun isEmpty(): Expr = IsExpr(pathExpr(), "empty")
    fun isNotEmpty(): Expr = IsExpr(pathExpr(), "notEmpty")
    /** `lowercase(member)`, for case-folded comparison the author spells visibly. */
    val lowercase: TextFunction get() = TextFunction("lowercase", fieldPath)
    val uppercase: TextFunction get() = TextFunction("uppercase", fieldPath)
    val trim: TextFunction get() = TextFunction("trim", fieldPath)
    /** `length(member)`, compared as a whole number. */
    val length: PartOf get() = PartOf("length", fieldPath)
}

/** A text function of a member (`lowercase(member)`, `uppercase`, `trim`) as the left operand of a text comparison. */
class TextFunction internal constructor(private val name: String, private val path: List<String>) {
    private fun call(): Expr = FunCall(name, listOf(PathExpr("this", path.map { Seg(it, false) })))
    infix fun eq(v: String): Expr = Binary("==", call(), TextLit(v))
    infix fun contains(v: String): Expr = Binary("contains", call(), TextLit(v))
    infix fun startsWith(v: String): Expr = Binary("starts with", call(), TextLit(v))
    infix fun endsWith(v: String): Expr = Binary("ends with", call(), TextLit(v))
    infix fun like(pattern: String): Expr = Binary("like", call(), TextLit(pattern))
    infix fun matches(regex: String): Expr = Binary("matches", call(), TextLit(regex))
}

/** A whole-number part of a member (`year(member)`, `length(member)`, ...) as the left operand of a comparison. */
class PartOf internal constructor(private val name: String, private val path: List<String>) {
    private fun call(): Expr = FunCall(name, listOf(PathExpr("this", path.map { Seg(it, false) })))
    infix fun eq(v: Int): Expr = Binary("==", call(), ValueLit(Value.num(v)))
    infix fun neq(v: Int): Expr = Binary("!=", call(), ValueLit(Value.num(v)))
    infix fun lt(v: Int): Expr = Binary("<", call(), ValueLit(Value.num(v)))
    infix fun lte(v: Int): Expr = Binary("<=", call(), ValueLit(Value.num(v)))
    infix fun gt(v: Int): Expr = Binary(">", call(), ValueLit(Value.num(v)))
    infix fun gte(v: Int): Expr = Binary(">=", call(), ValueLit(Value.num(v)))
    fun oneOf(vararg v: Int): Expr = Binary("in", call(), ListLit(v.map { ValueLit(Value.num(it)) }))
    fun between(lo: Int, hi: Int): Expr = Binary("and", Binary(">=", call(), ValueLit(Value.num(lo))), Binary("<=", call(), ValueLit(Value.num(hi))))
}

class NumField(path: List<String>) : ScalarField<Number>(path) {
    override fun lit(v: Number): Expr = ValueLit(Value.num(v))
}

/** A `uuid` member (Reference §5): equality only. */
class UuidField(path: List<String>) : Fields(path) {
    infix fun eq(v: java.util.UUID): Expr = Binary("==", pathExpr(), ValueLit(Value.VUuid(v)))
    infix fun neq(v: java.util.UUID): Expr = Binary("!=", pathExpr(), ValueLit(Value.VUuid(v)))
}

/** A `time` member (Reference §5): a time of day, ordered. */
class TimeField(path: List<String>) : ScalarField<java.time.LocalTime>(path) {
    override fun lit(v: java.time.LocalTime): Expr = ValueLit(Value.VTime(v))
    val hour: PartOf get() = PartOf("hour", fieldPath)
    val minute: PartOf get() = PartOf("minute", fieldPath)
}

/** A `bytes` member (Reference §5): equal by content, ordered byte by byte. */
class BytesField(path: List<String>) : ScalarField<ByteArray>(path) {
    override fun lit(v: ByteArray): Expr = ValueLit(Value.VBytes(v))
}

/** A `duration` member (Reference §5): ordered, like a number. */
class DurationField(path: List<String>) : ScalarField<java.time.Duration>(path) {
    override fun lit(v: java.time.Duration): Expr = ValueLit(Value.VDuration.ofJava(v))
}

class BoolField(path: List<String>) : Fields(path) {
    infix fun eq(v: Boolean): Expr = Binary("==", pathExpr(), BoolLit(v))
    /** The member itself as a predicate (`where { active() }`). */
    operator fun invoke(): Expr = pathExpr()
}

class DateField(path: List<String>) : ScalarField<LocalDate>(path) {
    override fun lit(v: LocalDate): Expr = ValueLit(Value.VDate(v))
    /** The parts of the date (Reference §5); `dayOfWeek` counts Monday as 1 and Sunday as 7. */
    val year: PartOf get() = PartOf("year", fieldPath)
    val month: PartOf get() = PartOf("month", fieldPath)
    val day: PartOf get() = PartOf("day", fieldPath)
    val dayOfWeek: PartOf get() = PartOf("dayOfWeek", fieldPath)
}

class DateTimeField(path: List<String>) : ScalarField<Instant>(path) {
    override fun lit(v: Instant): Expr = ValueLit(Value.VDateTime(v))
    /** The parts of the moment, read in the system's zone (Reference §5). */
    val year: PartOf get() = PartOf("year", fieldPath)
    val month: PartOf get() = PartOf("month", fieldPath)
    val day: PartOf get() = PartOf("day", fieldPath)
    val dayOfWeek: PartOf get() = PartOf("dayOfWeek", fieldPath)
    val hour: PartOf get() = PartOf("hour", fieldPath)
    val minute: PartOf get() = PartOf("minute", fieldPath)
}

/**
 * A member of a registered extension type (Reference §5): equality tests, under the
 * type's own `equal`, and membership of a `many`. [qualifiedName] names the type in
 * the registry (`velle.finance.Money`); [T] is its Kotlin class.
 */
open class ExtField<T : Any>(path: List<String>, qualifiedName: String) : Fields(path) {
    internal val type: velle.ext.VelleType<*> = TypeRegistry.default[qualifiedName]
        ?: throw IllegalStateException("no registered type '$qualifiedName' is on the classpath")
    protected fun lit(v: T): Expr = ValueLit(Value.VExt(type, v))
    infix fun eq(v: T): Expr = Binary("==", pathExpr(), lit(v))
    infix fun neq(v: T): Expr = Binary("!=", pathExpr(), lit(v))
}

/** A member of an extension type that declares an ordering: the ordered comparisons and
 *  the order datums as well. The codegen chooses this class only for an ordered type, so
 *  a query ordering by an unordered one does not compile. */
class OrderedExtField<T : Any>(path: List<String>, qualifiedName: String) : ExtField<T>(path, qualifiedName) {
    infix fun lt(v: T): Expr = Binary("<", pathExpr(), lit(v))
    infix fun lte(v: T): Expr = Binary("<=", pathExpr(), lit(v))
    infix fun gt(v: T): Expr = Binary(">", pathExpr(), lit(v))
    infix fun gte(v: T): Expr = Binary(">=", pathExpr(), lit(v))
    val ascending: OrderDatum get() = OrderDatum(fieldPath.single(), descending = false)
    val descending: OrderDatum get() = OrderDatum(fieldPath.single(), descending = true)
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
class TypedQuery<F : Fields, T>(
    val system: VelleSystem,
    val query: Query,
    private val fields: F,
    private val materialize: (Long) -> T,
) {
    private fun with(q: Query) = TypedQuery(system, q, fields, materialize)

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
    fun list(): List<T> = system.scope { system.query(query).ids.map(materialize) }

    /** The page the query names (or everything, with no next cursor, when it names none). */
    fun fetch(): QueryPage<T> = system.scope { system.query(query).let { QueryPage(it.ids.map(materialize), it.next) } }

    fun count(): Int = system.scope { system.count(query) }

    fun sum(member: F.() -> NumField): BigDecimal = system.scope { system.sum(query, fields.member().fieldPath.single()) }

    /** The sum of a member of an extension type, as that type; refused when the type declares no `sum`. */
    @Suppress("UNCHECKED_CAST")
    fun <V : Any> sumOf(member: F.() -> ExtField<V>): V =
        system.scope { (system.sumValue(query, fields.member().fieldPath.single()) as Value.VExt).v as V }
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
