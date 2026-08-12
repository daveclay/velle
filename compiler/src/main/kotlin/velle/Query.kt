package velle

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * The query IR (investigate_runtime.md §2): a compiled *pre-filter* over one
 * shape's stored rows, handed to the engineer's resolver so a scan read can
 * return a candidate subset instead of the whole table. The contract is
 * strictly one-directional — the authoritative predicate always implies the
 * filter, so the filter selects a candidate *superset* and the runtime
 * re-checks the real predicate in memory on what comes back. A resolver may
 * weaken further (up to ignoring the filter entirely and returning everything);
 * it must never return fewer rows than the filter matches.
 *
 * Semantics over a row (needed where SQL and in-memory evaluation differ):
 * an absent (NULL) column satisfies `!=` and fails `==` and every ordered
 * comparison — matching the runtime's `VNone` behavior. A renderer translating
 * to SQL must preserve this (`col IS NULL OR col != ?`).
 */
sealed interface QF {
    data object True : QF
    data object False : QF
    data class And(val l: QF, val r: QF) : QF
    data class Or(val l: QF, val r: QF) : QF
    data class Not(val inner: QF) : QF

    /** The subject row's stored (or timestamp) column [field] compared to a
     *  constant; op is one of `==`, `!=`, `<`, `<=`, `>`, `>=`. */
    data class Cmp(val field: String, val op: String, val value: QConst) : QF

    /** `is none` / `is some` on an optional column ([isNull] = the `none` side). */
    data class NullCheck(val field: String, val isNull: Boolean) : QF

    /** Some instance of [shape] (rows of that table) references the subject row
     *  through its to-one [refField] and satisfies [inner]; [refField] null means
     *  an uncorrelated existence test over the whole table. */
    data class Exists(val shape: String, val refField: String?, val inner: QF) : QF

    /** The subject row's to-one [field] references a [shape] row satisfying
     *  [inner]; absent reference → false (both polarities' safe reading). */
    data class RelPred(val field: String, val shape: String, val inner: QF) : QF
}

/** Constant leaves, in the model's scalar vocabulary. */
sealed interface QConst {
    data class QNum(val v: BigDecimal) : QConst
    data class QText(val v: String) : QConst
    data class QBool(val v: Boolean) : QConst
    data class QDate(val v: LocalDate) : QConst
    data class QDateTime(val v: Instant) : QConst
}

/**
 * Compiles refinement predicates to [QF] pre-filters, conservatively.
 *
 * Every construct the compiler can't express degrades by *polarity*: in
 * positive position to [QF.True] (keep the rows — over-fetch), under an odd
 * number of negations to [QF.False] (so the enclosing `not` keeps the rows).
 * The result is always implied by the authoritative predicate, never the
 * reverse — correctness stays in one evaluator (investigate_runtime.md §2).
 *
 * Time is data at compile time: `today`/`now` (and duration arithmetic on
 * them) fold to constants, so a filter is built per evaluation moment — per
 * envelope or per tick — and never cached across clock changes.
 */
class QueryCompiler(private val model: Model, private val today: LocalDate, private val now: Instant) {

    /** Pre-filter for candidates of [cond] over its base shape's table. */
    fun filterFor(cond: RefExpr): QF = simplify(compileRef(cond, positive = true))

    // ── refinement expressions ───────────────────────────────────────────────

    private fun compileRef(e: RefExpr, positive: Boolean): QF = when (e) {
        is RefName -> {
            val head = when {
                e.name in model.shapes -> QF.True // the table itself is the type filter
                e.name in model.refinements ->
                    compileRef(model.refinements.getValue(e.name).expr, positive)
                else -> fail(positive)
            }
            val where = e.where?.let { compileExpr(it, e.name, positive) } ?: QF.True
            QF.And(head, where)
        }
        is RefNot -> QF.Not(compileRef(e.inner, !positive))
        is RefAnd -> QF.And(compileRef(e.left, positive), compileRef(e.right, positive))
        is RefOr -> QF.Or(compileRef(e.left, positive), compileRef(e.right, positive))
    }

    // ── boolean-position expressions ─────────────────────────────────────────

    /**
     * [depth] 0 compiles against the subject row; >0 inside an element scope
     * (an exists/collection body). `this` always names the *outermost* subject
     * (README §10), and only a top-level exists can express that correlation —
     * at depth > 0 any `this`-touching conjunct degrades by polarity rather
     * than silently compiling against the wrong row.
     */
    private fun compileExpr(e: Expr, scope: String, positive: Boolean, depth: Int = 0): QF {
        if (depth > 0 && containsThis(e)) return fail(positive)
        return when (e) {
            is Binary -> when (e.op) {
                "and" -> QF.And(compileExpr(e.left, scope, positive, depth), compileExpr(e.right, scope, positive, depth))
                "or" -> QF.Or(compileExpr(e.left, scope, positive, depth), compileExpr(e.right, scope, positive, depth))
                "==", "!=", "<", "<=", ">", ">=" -> compileCmp(e, scope, positive)
                else -> fail(positive)
            }
            is NotExpr -> QF.Not(compileExpr(e.inner, scope, !positive, depth))
            is BoolLit -> if (e.value) QF.True else QF.False
            is PathExpr -> compileBoolAtom(e, scope, positive)
            is IsExpr -> compileIs(e, scope, positive)
            is ExistsExpr -> compileExists(e, scope, positive, depth)
            else -> fail(positive)
        }
    }

    private fun containsThis(e: Expr): Boolean = when (e) {
        is PathExpr -> e.root == "this"
        is UnaryMinus -> containsThis(e.inner)
        is Binary -> containsThis(e.left) || containsThis(e.right)
        is NotExpr -> containsThis(e.inner)
        is IfExpr -> containsThis(e.condition) || containsThis(e.thenExpr) || containsThis(e.elseExpr)
        is IsExpr -> containsThis(e.subject)
        is ExistsExpr -> (e.forExpr?.let { containsThis(it) } ?: false) ||
            (e.collection?.let { c -> c.bindings.any { containsThis(it.source) } || (c.where?.let { containsThis(it) } ?: false) } ?: false)
        is AggCall -> e.collection.bindings.any { containsThis(it.source) } ||
            (e.collection.where?.let { containsThis(it) } ?: false)
        is FunCall -> e.args.any { containsThis(it) }
        is SingularFor -> containsThis(e.forExpr)
        is Access -> containsThis(e.target)
        is ShapeForSource -> containsThis(e.forExpr)
        else -> false
    }

    /** Bare boolean atom: a boolean-typed path, or a refinement-membership name. */
    private fun compileBoolAtom(e: PathExpr, scope: String, positive: Boolean): QF {
        if (e.segs.isEmpty() && (e.root in model.refinements || e.root in model.shapes)) {
            // membership atom over the innermost element — same table only
            if (model.baseOf(e.root) != model.baseOf(scope)) return fail(positive)
            return compileRef(RefName(e.root), positive)
        }
        val hops = resolveHops(e, scope) ?: return fail(positive)
        val leaf = hops.last().second
        if (leaf.type.strip() != VType.Bool) return fail(positive)
        return wrapHops(hops.dropLast(1), QF.Cmp(hops.last().first, "==", QConst.QBool(true)))
    }

    private fun compileIs(e: IsExpr, scope: String, positive: Boolean): QF {
        when (e.kind) {
            "none", "some" -> {
                val hops = resolveHops(e.subject as? PathExpr ?: return fail(positive), scope)
                    ?: return fail(positive)
                return wrapHops(hops.dropLast(1), QF.NullCheck(hops.last().first, e.kind == "none"))
            }
            "empty", "notEmpty" -> {
                val p = e.subject as? PathExpr ?: return fail(positive)
                if (p.segs.isNotEmpty()) return fail(positive)
                val m = model.membersOf(scope)[p.root] ?: return fail(positive)
                val coll = m.type as? VType.Coll ?: return fail(positive)
                val back = backFieldOf(coll.shape, model.baseOf(scope) ?: return fail(positive))
                    ?: return fail(positive)
                val exists = QF.Exists(coll.shape, back, QF.True)
                return if (e.kind == "empty") QF.Not(exists) else exists
            }
            "refinement" -> {
                val refName = e.refinement ?: return fail(positive)
                val subject = e.subject as? PathExpr ?: return fail(positive)
                if (subject.root == "this" && subject.segs.isEmpty()) {
                    if (model.baseOf(refName) != model.baseOf(scope)) return fail(positive)
                    return compileRef(RefName(refName), positive)
                }
                // membership of a to-one related instance: forward join
                val hops = resolveHops(subject, scope, wantInstance = true) ?: return fail(positive)
                val leafShape = hops.last().second.type.instanceShape() ?: return fail(positive)
                if (model.baseOf(refName) != leafShape) return fail(positive)
                val inner = compileRef(RefName(refName), positive)
                return wrapHops(hops.dropLast(1), QF.RelPred(hops.last().first, leafShape, inner))
            }
            else -> return fail(positive)
        }
    }

    private fun compileExists(e: ExistsExpr, scope: String, positive: Boolean, depth: Int): QF {
        val subjectBase = model.baseOf(scope) ?: return fail(positive)
        // sugar form: `exists Shape for this` — top level only ('this' correlation)
        e.shape?.let { shapeName ->
            val forE = e.forExpr as? PathExpr ?: return fail(positive)
            if (depth > 0 || forE.root != "this" || forE.segs.isNotEmpty()) return fail(positive)
            val base = model.baseOf(shapeName) ?: return fail(positive)
            val back = backFieldOf(base, subjectBase) ?: return fail(positive)
            val inner = if (shapeName in model.refinements) compileRef(RefName(shapeName), positive) else QF.True
            return QF.Exists(base, back, inner)
        }
        // general form: single bare-shape binding with a where
        val c = e.collection ?: return fail(positive)
        val b = c.bindings.singleOrNull() ?: return fail(positive)
        val src = b.source as? PathExpr ?: return fail(positive)
        if (src.segs.isNotEmpty() || (src.root !in model.shapes && src.root !in model.refinements))
            return fail(positive)
        val elemScope = src.root
        val elemBase = model.baseOf(elemScope) ?: return fail(positive)
        val head = if (elemScope in model.refinements) compileRef(RefName(elemScope), positive) else QF.True

        var refField: String? = null
        var inner: QF = head
        for (conjunct in conjunctsOf(c.where)) {
            val corr = if (depth == 0) asCorrelation(conjunct, elemScope, subjectBase) else null
            if (corr != null && refField == null) { refField = corr; continue }
            inner = QF.And(inner, compileExpr(conjunct, elemScope, positive, depth + 1))
        }
        return QF.Exists(elemBase, refField, inner)
    }

    /** `field == this` (either order) on a to-one field typed to the subject. */
    private fun asCorrelation(e: Expr, elemScope: String, subjectBase: String): String? {
        val b = e as? Binary ?: return null
        if (b.op != "==") return null
        val (pathSide, thisSide) = when {
            (b.right as? PathExpr)?.let { it.root == "this" && it.segs.isEmpty() } == true -> b.left to b.right
            (b.left as? PathExpr)?.let { it.root == "this" && it.segs.isEmpty() } == true -> b.right to b.left
            else -> return null
        }
        check(thisSide is PathExpr)
        val p = pathSide as? PathExpr ?: return null
        if (p.segs.isNotEmpty()) return null
        val m = model.membersOf(elemScope)[p.root] ?: return null
        if (!m.stored || m.type.instanceShape() != subjectBase) return null
        return p.root
    }

    private fun conjunctsOf(e: Expr?): List<Expr> = when (e) {
        null -> emptyList()
        is Binary -> if (e.op == "and") conjunctsOf(e.left) + conjunctsOf(e.right) else listOf(e)
        else -> listOf(e)
    }

    // ── comparisons ──────────────────────────────────────────────────────────

    private fun compileCmp(e: Binary, scope: String, positive: Boolean): QF {
        tryCmp(e.left, e.op, e.right, scope)?.let { return it }
        tryCmp(e.right, flip(e.op), e.left, scope)?.let { return it }
        return fail(positive)
    }

    private fun tryCmp(pathSide: Expr, op: String, constSide: Expr, scope: String): QF? {
        val p = pathSide as? PathExpr ?: return null
        val hops = resolveHops(p, scope) ?: return null
        val const = constFold(constSide) ?: return null
        if (!typesMatch(hops.last().second.type.strip(), const)) return null
        return wrapHops(hops.dropLast(1), QF.Cmp(hops.last().first, op, const))
    }

    private fun flip(op: String): String = when (op) {
        "<" -> ">"; "<=" -> ">="; ">" -> "<"; ">=" -> "<="; else -> op
    }

    private fun typesMatch(t: VType, c: QConst): Boolean = when (c) {
        is QConst.QNum -> t is VType.Num
        is QConst.QText -> t == VType.Text
        is QConst.QBool -> t == VType.Bool
        is QConst.QDate -> t == VType.DateT
        is QConst.QDateTime -> t == VType.DateTimeT
    }

    // ── paths ────────────────────────────────────────────────────────────────

    /**
     * Resolves a `this`-or-bare-rooted path into stored-column hops: every hop
     * but the last a to-one reference, the last a stored/timestamp column (or,
     * with [wantInstance], a to-one reference). Null when any hop is derived,
     * captured, a collection, or otherwise not a physical column.
     */
    private fun resolveHops(
        p: PathExpr,
        scope: String,
        wantInstance: Boolean = false,
    ): List<Pair<String, MemberInfo>>? {
        val names = if (p.root == "this") p.segs.map { it.name }
        else listOf(p.root) + p.segs.map { it.name }
        if (names.isEmpty()) return null
        var sc: String? = scope
        val hops = mutableListOf<Pair<String, MemberInfo>>()
        for ((i, name) in names.withIndex()) {
            val m = model.membersOf(sc ?: return null)[name] ?: return null
            val last = i == names.lastIndex
            val column = m.stored || m.timestamp
            if (!column) return null
            if (!last && m.type.instanceShape() == null) return null
            if (last && !wantInstance && m.type.strip() is VType.Inst) return null
            if (last && wantInstance && m.type.instanceShape() == null) return null
            hops.add(name to m)
            sc = m.type.instanceShape()
        }
        return hops
    }

    /** Wraps a column condition in forward joins for each leading reference hop. */
    private fun wrapHops(refs: List<Pair<String, MemberInfo>>, leaf: QF): QF =
        refs.foldRight(leaf) { (field, m), inner ->
            QF.RelPred(field, m.type.instanceShape()!!, inner)
        }

    private fun backFieldOf(shape: String, targetShape: String): String? =
        model.shapes[shape]?.members?.filterIsInstance<StoredProp>()
            ?.singleOrNull { (it.type as? RelType)?.let { t -> !t.many && t.shape == targetShape } == true }
            ?.name

    // ── constants ────────────────────────────────────────────────────────────

    private fun constFold(e: Expr): QConst? = when (e) {
        is IntLit -> QConst.QNum(BigDecimal(e.value))
        is DecLit -> QConst.QNum(BigDecimal(e.text))
        is TextLit -> QConst.QText(e.value)
        is BoolLit -> QConst.QBool(e.value)
        TodayLit -> QConst.QDate(today)
        NowLit -> QConst.QDateTime(now)
        is UnaryMinus -> (constFold(e.inner) as? QConst.QNum)?.let { QConst.QNum(it.v.negate()) }
        is Binary -> foldBinary(e)
        else -> null
    }

    private fun foldBinary(e: Binary): QConst? {
        val r = e.right
        if (r is DurationLit && (e.op == "+" || e.op == "-")) {
            val sign = if (e.op == "+") 1L else -1L
            return when (val l = constFold(e.left)) {
                is QConst.QDate -> when (r.unit) {
                    "days" -> QConst.QDate(l.v.plusDays(sign * r.amount))
                    "weeks" -> QConst.QDate(l.v.plusWeeks(sign * r.amount))
                    else -> null
                }
                is QConst.QDateTime ->
                    unitSeconds(r.unit)?.let { QConst.QDateTime(l.v.plusSeconds(sign * r.amount * it)) }
                else -> null
            }
        }
        val l = constFold(e.left) as? QConst.QNum ?: return null
        val rv = constFold(e.right) as? QConst.QNum ?: return null
        return when (e.op) {
            "+" -> QConst.QNum(l.v.add(rv.v))
            "-" -> QConst.QNum(l.v.subtract(rv.v))
            "*" -> QConst.QNum(l.v.multiply(rv.v))
            "/" -> QConst.QNum(l.v.divide(rv.v, java.math.MathContext.DECIMAL64))
            else -> null
        }
    }

    private fun unitSeconds(unit: String): Long? = when (unit) {
        "seconds" -> 1L; "minutes" -> 60L; "hours" -> 3_600L; "days" -> 86_400L; "weeks" -> 604_800L
        else -> null
    }

    private fun fail(positive: Boolean): QF = if (positive) QF.True else QF.False

    // ── simplification ───────────────────────────────────────────────────────

    private fun simplify(f: QF): QF = when (f) {
        is QF.And -> {
            val l = simplify(f.l); val r = simplify(f.r)
            when {
                l == QF.False || r == QF.False -> QF.False
                l == QF.True -> r
                r == QF.True -> l
                else -> QF.And(l, r)
            }
        }
        is QF.Or -> {
            val l = simplify(f.l); val r = simplify(f.r)
            when {
                l == QF.True || r == QF.True -> QF.True
                l == QF.False -> r
                r == QF.False -> l
                else -> QF.Or(l, r)
            }
        }
        is QF.Not -> when (val inner = simplify(f.inner)) {
            QF.True -> QF.False
            QF.False -> QF.True
            is QF.Not -> inner.inner
            else -> QF.Not(inner)
        }
        is QF.Exists -> when (val inner = simplify(f.inner)) {
            QF.False -> QF.False
            else -> QF.Exists(f.shape, f.refField, inner)
        }
        is QF.RelPred -> when (val inner = simplify(f.inner)) {
            QF.False -> QF.False
            else -> QF.RelPred(f.field, f.shape, inner)
        }
        else -> f
    }
}

private fun VType.strip(): VType = if (this is VType.Optional) inner else this
