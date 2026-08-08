package velle

import java.math.BigDecimal

/**
 * Expression and membership evaluation against the runtime state.
 *
 * Scoping follows README §10: `this` is the outermost subject, a bare name means
 * the innermost collection-filter's element (or the subject where no filter is
 * open), and `as` aliases keep middle scopes reachable.
 */
class Evaluator(private val system: VelleSystem) {

    private val model get() = system.model

    /** Evaluation context: the subject plus any open collection-element scopes. */
    data class Ctx(
        val subjectScope: String,
        val subjectId: Long,
        val elements: List<Pair<String, Long>> = emptyList(), // (scope name, instance)
        val aliases: Map<String, Pair<String, Long>> = emptyMap(),
    ) {
        fun innermost(): Pair<String, Long> = elements.lastOrNull() ?: (subjectScope to subjectId)
        fun push(scope: String, id: Long, alias: String?): Ctx = copy(
            elements = elements + (scope to id),
            aliases = if (alias != null) aliases + (alias to (scope to id)) else aliases,
        )
    }

    // ── membership ───────────────────────────────────────────────────────────

    fun memberOfRefExpr(id: Long, e: RefExpr): Boolean = when (e) {
        is RefName -> memberOfName(id, e)
        is RefNot -> !memberOfRefExpr(id, e.inner)
        is RefAnd -> memberOfRefExpr(id, e.left) && memberOfRefExpr(id, e.right)
        is RefOr -> memberOfRefExpr(id, e.left) || memberOfRefExpr(id, e.right)
    }

    private fun memberOfName(id: Long, e: RefName): Boolean {
        val inst = system.instances[id] ?: return false
        val ok = when {
            e.name in model.shapes -> inst.shape == e.name
            e.name in model.refinements -> {
                val r = model.refinements.getValue(e.name)
                model.baseOf(e.name) == inst.shape && memberOfRefExpr(id, r.expr)
            }
            else -> throw VelleRuntimeError("unknown refinement '${e.name}'")
        }
        if (!ok) return false
        return e.where?.let { truthy(eval(it, Ctx(e.name, id))) } ?: true
    }

    // ── member reads ─────────────────────────────────────────────────────────

    /** Read a member of [id] in scope [scope] (a shape or refinement name). */
    fun readMember(id: Long, scope: String, name: String): Value {
        val m = model.membersOf(scope)[name]
            ?: throw VelleRuntimeError("'$name' is not a member of '$scope'")
        return readResolved(id, m)
    }

    private fun readResolved(id: Long, m: MemberInfo): Value {
        val inst = system.instances[id] ?: throw VelleRuntimeError("missing instance $id")
        if (m.name == "id") return Value.VRef(id)
        if (m.captured) {
            val store = system.captures[id to m.owner]
                ?: throw VelleRuntimeError("capture '${m.owner}.${m.name}' read outside membership")
            return store.getValue(m.name)
        }
        m.derived?.let { return eval(it.expr, Ctx(m.owner, id)) }
        if (m.type is VType.Coll) {
            // inferred inverse collection: instances of the related shape whose
            // unique to-one field references this instance
            val relShape = (m.type as VType.Coll).shape
            val backField = model.shapes.getValue(relShape).members
                .filterIsInstance<StoredProp>()
                .first { (it.type as? RelType)?.let { t -> !t.many && t.shape == inst.shape } == true }
            val ids = system.byShape[relShape].orEmpty()
                .filter { (system.instances.getValue(it).fields[backField.name] as? Value.VRef)?.id == id }
            return Value.VColl(ids, relShape)
        }
        return inst.fields[m.name] ?: Value.VNone
    }

    fun evalMember(id: Long, scope: String, prop: DerivedProp): Value =
        eval(prop.expr, Ctx(scope, id))

    // ── evaluation ───────────────────────────────────────────────────────────

    fun eval(e: Expr, ctx: Ctx): Value = when (e) {
        is IntLit -> Value.VNum(BigDecimal(e.value))
        is DecLit -> Value.VNum(BigDecimal(e.text))
        is TextLit -> Value.VText(e.value)
        is BoolLit -> Value.VBool(e.value)
        NoneLit -> Value.VNone
        NowLit -> Value.VDateTime(system.effectiveNow)
        TodayLit -> Value.VDate(system.effectiveToday)
        is DurationLit -> Value.VDuration(e.amount, e.unit)
        is PathExpr -> evalPath(e, ctx)
        is UnaryMinus -> {
            val v = eval(e.inner, ctx) as? Value.VNum ?: throw VelleRuntimeError("unary minus on non-number")
            Value.VNum(v.v.negate())
        }
        is Binary -> evalBinary(e, ctx)
        is NotExpr -> Value.VBool(!truthy(eval(e.inner, ctx)))
        is IfExpr -> if (truthy(eval(e.condition, ctx))) eval(e.thenExpr, ctx) else eval(e.elseExpr, ctx)
        is IsExpr -> evalIs(e, ctx)
        is ExistsExpr -> Value.VBool(evalExists(e, ctx))
        is AggCall -> evalAgg(e, ctx)
        is FunCall -> evalFun(e, ctx)
        is SingularFor -> evalSingularFor(e, ctx)
        is Access -> {
            var v = eval(e.target, ctx)
            for (seg in e.segs) v = access(v, seg)
            v
        }
        is ShapeForSource -> throw VelleRuntimeError("shape-for is a collection source, not a value")
    }

    private fun evalPath(p: PathExpr, ctx: Ctx): Value {
        var v: Value = when {
            p.root == "this" -> Value.VRef(ctx.subjectId)
            p.root in ctx.aliases -> Value.VRef(ctx.aliases.getValue(p.root).second)
            p.root in model.refinements || p.root in model.shapes -> {
                // bare shape/refinement name: membership test of the innermost element
                val (_, id) = ctx.innermost()
                return Value.VBool(memberOfRefExpr(id, RefName(p.root)))
            }
            else -> {
                val (scope, id) = ctx.innermost()
                readMember(id, scope, p.root)
            }
        }
        for (seg in p.segs) v = access(v, seg)
        return v
    }

    private fun access(v: Value, seg: Seg): Value = when (v) {
        is Value.VRef -> {
            val inst = system.instances.getValue(v.id)
            // scope through the instance's refinement memberships is not needed at
            // runtime: captures are read via their owner refinement in readMember
            readMemberAnyScope(v.id, inst.shape, seg.name)
        }
        Value.VNone ->
            if (seg.viaQdot) Value.VNone
            else throw VelleRuntimeError("'.' through an absent value (validator narrowing gap)")
        else -> throw VelleRuntimeError("cannot access '.${seg.name}' on $v")
    }

    /** Member lookup that also sees refinement-scoped members the instance currently holds. */
    private fun readMemberAnyScope(id: Long, shape: String, name: String): Value {
        model.membersOf(shape)[name]?.let { return readResolved(id, it) }
        for ((refName, r) in model.refinements) {
            if (model.baseOf(refName) != shape) continue
            val m = r.members.filterIsInstance<DerivedProp>().find { it.name == name } ?: continue
            if (m.captured) system.captures[id to refName]?.let { return it.getValue(name) }
            else if (memberOfRefExpr(id, RefName(refName))) return eval(m.expr, Ctx(refName, id))
        }
        throw VelleRuntimeError("'$name' is not readable on '$shape' instance $id")
    }

    private fun evalIs(e: IsExpr, ctx: Ctx): Value {
        val v = eval(e.subject, ctx)
        return Value.VBool(
            when (e.kind) {
                "none" -> v == Value.VNone
                "some" -> v != Value.VNone
                "empty" -> (v as? Value.VColl)?.ids?.isEmpty()
                    ?: throw VelleRuntimeError("'is empty' on non-collection")
                "notEmpty" -> (v as? Value.VColl)?.ids?.isNotEmpty()
                    ?: throw VelleRuntimeError("'is not empty' on non-collection")
                "refinement" -> {
                    val ref = v as? Value.VRef ?: return Value.VBool(false)
                    memberOfRefExpr(ref.id, RefName(e.refinement!!))
                }
                else -> throw VelleRuntimeError("unknown is-kind ${e.kind}")
            }
        )
    }

    // ── collections, exists, aggregates ──────────────────────────────────────

    private fun evalExists(e: ExistsExpr, ctx: Ctx): Boolean {
        e.shape?.let { shape ->
            val target = eval(e.forExpr!!, ctx) as? Value.VRef ?: return false
            return instancesReferencing(shape, target.id).isNotEmpty()
        }
        return anyCombination(e.collection!!, ctx)
    }

    /**
     * Instances of [shape] whose unique type-matched to-one field references [id].
     * A refinement name resolves to its base for the field lookup and instance
     * scan, then filters by membership — the same resolution bindingCandidates
     * applies to refinement roots in collection position.
     */
    private fun instancesReferencing(shape: String, id: Long): List<Long> {
        val base = if (shape in model.refinements) model.baseOf(shape)!! else shape
        val targetShape = system.instances.getValue(id).shape
        val field = model.shapes.getValue(base).members.filterIsInstance<StoredProp>()
            .single { (it.type as? RelType)?.let { t -> !t.many && t.shape == targetShape } == true }
        val referencing = system.byShape[base].orEmpty().filter {
            (system.instances.getValue(it).fields[field.name] as? Value.VRef)?.id == id
        }
        return if (shape in model.refinements) referencing.filter { memberOfRefExpr(it, RefName(shape)) }
        else referencing
    }

    private fun bindingCandidates(b: Binding, ctx: Ctx): Pair<String, List<Long>> = when (val src = b.source) {
        is PathExpr ->
            if (src.root in model.shapes && src.segs.isEmpty())
                src.root to system.byShape[src.root].orEmpty().toList()
            else if (src.root in model.refinements && src.segs.isEmpty()) {
                val base = model.baseOf(src.root)!!
                base to system.byShape[base].orEmpty().filter { memberOfRefExpr(it, RefName(src.root)) }
            } else {
                val v = eval(src, ctx)
                val coll = v as? Value.VColl ?: throw VelleRuntimeError("binding is not a collection: $src")
                coll.shape to coll.ids
            }
        is ShapeForSource -> {
            val target = eval(src.forExpr, ctx) as? Value.VRef
                ?: throw VelleRuntimeError("'for' target is not an instance")
            src.shape to instancesReferencing(src.shape, target.id)
        }
        else -> throw VelleRuntimeError("unsupported binding source")
    }

    private fun combinations(c: CollectionExpr, ctx: Ctx): Sequence<Ctx> {
        var ctxs = sequenceOf(ctx)
        for (b in c.bindings) {
            // sibling bindings all resolve against the same enclosing subject
            // (README §10) — only the shared `where` sees the pushed frames
            val (scope, ids) = bindingCandidates(b, ctx)
            ctxs = ctxs.flatMap { outer -> ids.asSequence().map { outer.push(scope, it, b.alias) } }
        }
        return if (c.where == null) ctxs else ctxs.filter { truthy(eval(c.where, it)) }
    }

    private fun anyCombination(c: CollectionExpr, ctx: Ctx): Boolean = combinations(c, ctx).any()

    private fun evalAgg(e: AggCall, ctx: Ctx): Value = when (e.name) {
        "count" -> Value.VNum(BigDecimal(combinations(e.collection, ctx).count()))
        "sum" -> {
            var acc = BigDecimal.ZERO
            for (c in combinations(e.collection, ctx)) {
                val (scope, id) = c.innermost()
                val v = readMember(id, scope, e.field!!) as? Value.VNum
                    ?: throw VelleRuntimeError("sum over non-numeric '${e.field}'")
                acc = acc.add(v.v)
            }
            Value.VNum(acc)
        }
        "latest", "first" -> {
            val all = combinations(e.collection, ctx).map { it.innermost() }.toList()
            if (all.isEmpty()) Value.VNone
            else {
                val ordered = all.sortedWith(
                    compareBy({ orderingKey(it.first, it.second).first }, { orderingKey(it.first, it.second).second })
                )
                val chosen = if (e.name == "latest") ordered.last() else ordered.first()
                Value.VRef(chosen.second)
            }
        }
        else -> throw VelleRuntimeError("unknown aggregate '${e.name}'")
    }

    /**
     * Selector ordering: the element shape's sole `timestamp on create`, else creation
     * order; creation sequence breaks timestamp ties (one shape orders consistently
     * by one mode, so the two scales never mix within a selector).
     */
    private fun orderingKey(scope: String, id: Long): Pair<Long, Long> {
        val inst = system.instances.getValue(id)
        val tsProp = model.shapes[inst.shape]?.members?.filterIsInstance<TimestampProp>()
            ?.singleOrNull { it.on == "create" }
        val ts = tsProp?.let { (inst.fields[it.name] as? Value.VDateTime)?.v }
        return (ts?.toEpochMilli() ?: 0L) to inst.seq
    }

    private fun evalFun(e: FunCall, ctx: Ctx): Value = when (e.name) {
        "lowercase" -> {
            val v = eval(e.args.single(), ctx) as? Value.VText
                ?: throw VelleRuntimeError("lowercase on non-text")
            Value.VText(v.v.lowercase())
        }
        "max", "min" -> {
            val nums = e.args.map {
                (eval(it, ctx) as? Value.VNum)?.v ?: throw VelleRuntimeError("${e.name} on non-number")
            }
            Value.VNum(if (e.name == "max") nums.max() else nums.min())
        }
        else -> throw VelleRuntimeError("unknown function '${e.name}' (the builtin list is closed)")
    }

    private fun evalSingularFor(e: SingularFor, ctx: Ctx): Value {
        val target = eval(e.forExpr, ctx) as? Value.VRef
            ?: throw VelleRuntimeError("'for' target is not an instance")
        val matches = instancesReferencing(e.shape, target.id)
        return when (matches.size) {
            0 -> throw VelleRuntimeError("(${e.shape} for ...) found no instance — the licensing predicate should have prevented this")
            1 -> Value.VRef(matches.single())
            else -> throw VelleRuntimeError("(${e.shape} for ...) is not singular (${matches.size} matches) — at-most-one proof gap")
        }
    }

    // ── operators ────────────────────────────────────────────────────────────

    private fun evalBinary(e: Binary, ctx: Ctx): Value {
        when (e.op) {
            "and" -> return Value.VBool(truthy(eval(e.left, ctx)) && truthy(eval(e.right, ctx)))
            "or" -> return Value.VBool(truthy(eval(e.left, ctx)) || truthy(eval(e.right, ctx)))
        }
        val l = eval(e.left, ctx)
        val r = eval(e.right, ctx)
        return when (e.op) {
            "+", "-" -> arith(e.op, l, r)
            "*", "/" -> {
                val a = (l as? Value.VNum)?.v ?: throw VelleRuntimeError("'${e.op}' on non-number")
                val b = (r as? Value.VNum)?.v ?: throw VelleRuntimeError("'${e.op}' on non-number")
                Value.VNum(if (e.op == "*") a.multiply(b) else a.divide(b, java.math.MathContext.DECIMAL64))
            }
            "==", "!=" -> {
                val eq = valueEquals(l, r)
                Value.VBool(if (e.op == "==") eq else !eq)
            }
            "<", "<=", ">", ">=" -> {
                val c = compareValues(l, r)
                Value.VBool(
                    when (e.op) {
                        "<" -> c < 0; "<=" -> c <= 0; ">" -> c > 0; else -> c >= 0
                    }
                )
            }
            else -> throw VelleRuntimeError("unknown operator '${e.op}'")
        }
    }

    private fun arith(op: String, l: Value, r: Value): Value {
        if (l is Value.VNum && r is Value.VNum)
            return Value.VNum(if (op == "+") l.v.add(r.v) else l.v.subtract(r.v))
        if (r is Value.VDuration) {
            val sign = if (op == "+") 1L else -1L
            return when (l) {
                is Value.VDate -> Value.VDate(
                    when (r.unit) {
                        "days" -> l.v.plusDays(sign * r.amount)
                        "weeks" -> l.v.plusWeeks(sign * r.amount)
                        else -> throw VelleRuntimeError("'${r.unit}' is clock time — not applicable to a Date (README §10)")
                    }
                )
                is Value.VDateTime -> Value.VDateTime(l.v.plusSeconds(sign * r.amount * unitSeconds(r.unit)))
                else -> throw VelleRuntimeError("duration arithmetic on $l")
            }
        }
        throw VelleRuntimeError("'$op' on $l and $r")
    }

    private fun unitSeconds(unit: String): Long = when (unit) {
        "seconds" -> 1
        "minutes" -> 60
        "hours" -> 3_600
        "days" -> 86_400
        "weeks" -> 604_800
        else -> throw VelleRuntimeError("unknown duration unit '$unit'")
    }

    private fun valueEquals(l: Value, r: Value): Boolean = when {
        l is Value.VNum && r is Value.VNum -> l.v.compareTo(r.v) == 0
        l is Value.VRef && r is Value.VRef -> l.id == r.id // identity comparison (README §5)
        else -> l == r
    }

    private fun compareValues(l: Value, r: Value): Int = when {
        l is Value.VNum && r is Value.VNum -> l.v.compareTo(r.v)
        l is Value.VDate && r is Value.VDate -> l.v.compareTo(r.v)
        l is Value.VDateTime && r is Value.VDateTime -> l.v.compareTo(r.v)
        l is Value.VText && r is Value.VText -> l.v.compareTo(r.v)
        else -> throw VelleRuntimeError("cannot order $l against $r")
    }

    internal fun truthy(v: Value): Boolean =
        (v as? Value.VBool)?.v ?: throw VelleRuntimeError("non-boolean in predicate position: $v")

    // ── assignment targets ───────────────────────────────────────────────────

    /** Resolve `a.b.c` to (instance holding c, "c"). */
    fun resolveTarget(target: PathExpr, ctx: Ctx): Pair<Long, String> {
        if (target.segs.isEmpty()) {
            require(target.root != "this") { "assignment needs a field" }
            return ctx.subjectId to target.root
        }
        var current: Long = if (target.root == "this") ctx.subjectId else {
            val v = readMember(ctx.subjectId, ctx.subjectScope, target.root)
            (v as? Value.VRef)?.id ?: throw VelleRuntimeError("assignment route '${target.root}' is not an instance")
        }
        for ((i, seg) in target.segs.withIndex()) {
            if (i == target.segs.lastIndex) return current to seg.name
            val inst = system.instances.getValue(current)
            val v = readMemberAnyScope(current, inst.shape, seg.name)
            current = (v as? Value.VRef)?.id
                ?: throw VelleRuntimeError("assignment route '.${seg.name}' is not an instance")
        }
        error("unreachable")
    }
}
