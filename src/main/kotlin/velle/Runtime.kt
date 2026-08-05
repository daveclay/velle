package velle

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Runtime values. All numerics normalize to BigDecimal — a spike simplification. */
sealed interface Value {
    data class VNum(val v: BigDecimal) : Value
    data class VText(val v: String) : Value
    data class VBool(val v: Boolean) : Value
    data class VDate(val v: LocalDate) : Value
    data class VDateTime(val v: Instant) : Value
    data class VRef(val id: Long) : Value
    data class VColl(val ids: List<Long>, val shape: String) : Value
    data class VDuration(val amount: Long, val unit: String) : Value
    data object VNone : Value

    companion object {
        fun num(v: Any): Value = VNum(
            when (v) {
                is BigDecimal -> v
                is Int -> BigDecimal(v)
                is Long -> BigDecimal(v)
                is Double -> BigDecimal.valueOf(v)
                else -> throw IllegalArgumentException("not numeric: $v")
            }
        )
    }
}

class Instance(
    val id: Long,
    val shape: String,
    val seq: Long,
    val fields: MutableMap<String, Value>,
)

sealed interface CommitResult {
    data class Accepted(val id: Long) : CommitResult
    data class Refused(val reason: String) : CommitResult
}

class VelleRuntimeError(message: String) : Exception(message)
private class NeverViolation(val reason: String) : Exception(reason)

/**
 * The v0 runtime: evaluation.md executed over in-memory state [S1].
 * The harness surface: [commit], [tick], [advance]/[setTime], and the query
 * methods — the engine under the typed accessors codegen will wrap.
 */
class VelleSystem(
    val model: Model,
    startTime: Instant = Instant.parse("2026-01-01T09:00:00Z"),
    val zone: ZoneId = ZoneId.of("UTC"),
) {
    var now: Instant = startTime
        private set

    /** membershipAt() briefly evaluates against a past clock (tick-exit detection) */
    internal var evalTime: Instant? = null
    internal val effectiveNow: Instant get() = evalTime ?: now
    internal val effectiveToday: LocalDate get() = effectiveNow.atZone(zone).toLocalDate()

    internal val instances = LinkedHashMap<Long, Instance>()
    internal val byShape = HashMap<String, MutableList<Long>>()
    /** (instance id, refinement name) → captured values for the current membership */
    internal val captures = HashMap<Pair<Long, String>, Map<String, Value>>()

    private var nextId = 1L
    private val lastTick = HashMap<String, Instant>()
    private val startInstant = startTime
    val evaluator = Evaluator(this)
    /** failures of after-commit / tick firings — the act stands, the gap is recorded [S4] */
    val failures = mutableListOf<String>()

    private val maxDepth = 500 // [S3] backstop behind the static quiescence proof

    fun advance(seconds: Long) { now = now.plusSeconds(seconds) }
    fun advanceDays(days: Long) { now = now.plusSeconds(days * 86_400) }
    fun setTime(t: Instant) { now = t }

    // ── transactions ─────────────────────────────────────────────────────────

    private class Txn {
        val created = mutableListOf<Long>()
        val oldValues = mutableListOf<Triple<Long, String, Value?>>()
        val afterQueue = mutableListOf<Pair<RuleDecl, Long>>()
        var depth = 0
    }

    private var txn: Txn? = null

    private fun <T> inTransaction(body: () -> T): Result<T> {
        check(txn == null) { "nested harness transaction" }
        val t = Txn()
        txn = t
        return try {
            val out = body()
            checkNevers()
            txn = null
            drainAfterQueue(t)
            Result.success(out)
        } catch (e: Exception) {
            rollback(t)
            txn = null
            Result.failure(e)
        }
    }

    private fun rollback(t: Txn) {
        for ((id, field, old) in t.oldValues.asReversed()) {
            if (old == null) instances[id]?.fields?.remove(field)
            else instances[id]?.fields?.put(field, old)
        }
        for (id in t.created.asReversed()) {
            val inst = instances.remove(id) ?: continue
            byShape[inst.shape]?.remove(id)
            captures.keys.removeIf { it.first == id }
        }
    }

    private fun drainAfterQueue(t: Txn) {
        // [S2] synchronous, FIFO, each entry its own transaction
        for ((rule, subject) in t.afterQueue) {
            if (subject !in instances) continue
            if (!evaluator.memberOfRefExpr(subject, rule.condition)) continue
            val result = inTransaction { fire(rule, subject) }
            result.exceptionOrNull()?.let {
                failures.add("after-commit firing of '${rule.name}' failed: ${it.message}")
            }
        }
    }

    // ── the exposed commit surface ────────────────────────────────────────────

    fun commit(shape: String, suppliedFields: Map<String, Any?>): CommitResult {
        val decl = model.shapes[shape]
            ?: return CommitResult.Refused("type: unknown shape '$shape'")
        if (shape !in model.exposed)
            return CommitResult.Refused("type: shape '$shape' is not exposed — it enters state only as a rule's effect")

        val converted = convertFields(decl, suppliedFields)
            ?: return CommitResult.Refused(typeFailure(decl, suppliedFields))

        val result = inTransaction { applyCommit(listOf(Mutation.Create(shape, converted))).single() }
        return result.fold(
            onSuccess = { CommitResult.Accepted(it) },
            onFailure = { e ->
                if (e is NeverViolation) CommitResult.Refused(e.reason) else throw e
            }
        )
    }

    private fun convertFields(decl: ShapeDecl, supplied: Map<String, Any?>): Map<String, Value>? {
        val out = mutableMapOf<String, Value>()
        for (m in decl.members.filterIsInstance<StoredProp>()) {
            val raw = supplied[m.name]
            if (raw == null) {
                if (m.initially == null && (m.type as? ScalarType)?.optional != true &&
                    (m.type as? RelType)?.optional != true) return null
                continue
            }
            out[m.name] = convert(raw, m.type) ?: return null
        }
        val known = decl.members.filterIsInstance<StoredProp>().map { it.name }.toSet()
        if (supplied.keys.any { it !in known }) return null // timestamps/id/derived/unknown
        return out
    }

    private fun convert(raw: Any, type: TypeRef): Value? = when (type) {
        is RelType -> (raw as? Long)?.takeIf { it in instances }?.let { Value.VRef(it) }
        is ScalarType -> when (type.name) {
            "text" -> (raw as? String)?.let { Value.VText(it) }
            "boolean" -> (raw as? Boolean)?.let { Value.VBool(it) }
            "Date" -> (raw as? LocalDate)?.let { Value.VDate(it) }
            "DateTime" -> (raw as? Instant)?.let { Value.VDateTime(it) }
            else -> runCatching { Value.num(raw) }.getOrNull()
        }
    }

    private fun typeFailure(decl: ShapeDecl, supplied: Map<String, Any?>): String {
        val required = decl.members.filterIsInstance<StoredProp>()
            .filter { it.initially == null }.map { it.name }
        val missing = required - supplied.keys
        return if (missing.isNotEmpty()) "type: '${decl.name}' requires $missing"
        else "type: unacceptable fields for '${decl.name}'"
    }

    // ── one commit (evaluation.md, "Processing one commit") ──────────────────

    internal sealed interface Mutation {
        data class Create(val shape: String, val fields: Map<String, Value>) : Mutation
        data class Assign(val id: Long, val field: String, val value: Value) : Mutation
    }

    /** Watched conditions: every commit-triggered rule plus capture-carrying refinements. */
    private data class Watcher(val key: String, val condition: RefExpr, val base: String)

    private val watchers: List<Watcher> by lazy {
        val ws = mutableListOf<Watcher>()
        for (r in model.rules.values) {
            val base = model.baseOfExpr(r.condition) ?: continue
            ws.add(Watcher("rule:${r.name}", r.condition, base))
        }
        for ((name, r) in model.refinements) {
            if (r.members.any { it is DerivedProp && it.captured }) {
                val base = model.baseOf(name) ?: continue
                ws.add(Watcher("capture:$name", RefName(name), base))
            }
        }
        ws
    }

    private fun memberSet(w: Watcher): Set<Long> =
        byShape[w.base].orEmpty().filter { evaluator.memberOfRefExpr(it, w.condition) }.toSet()

    private fun applyCommit(mutations: List<Mutation>): List<Long> {
        val t = txn ?: error("commit outside transaction")
        if (t.depth++ > maxDepth)
            throw VelleRuntimeError("cascade depth exceeded $maxDepth — quiescence backstop [S3]")

        val pre = watchers.associateWith { memberSet(it) }

        val createdIds = mutableListOf<Long>()
        var wroteStored = mutableSetOf<Long>()
        for (m in mutations) when (m) {
            is Mutation.Create -> createdIds.add(applyCreate(m, t))
            is Mutation.Assign -> { applyAssign(m, t, pre); wroteStored.add(m.id) }
        }
        // `on update` timestamps advance at every commit writing a stored field
        for (id in wroteStored) {
            val inst = instances[id] ?: continue
            model.shapes[inst.shape]?.members?.filterIsInstance<TimestampProp>()
                ?.filter { it.on == "update" }
                ?.forEach { setField(inst, it.name, Value.VDateTime(now), t) }
        }

        val post = watchers.associateWith { memberSet(it) }

        // captures: evaluate at entry, mark leavers for retraction at close
        val retractions = mutableListOf<Pair<Long, String>>()
        for (w in watchers.filter { it.key.startsWith("capture:") }) {
            val refName = w.key.removePrefix("capture:")
            val props = model.refinements.getValue(refName).members
                .filterIsInstance<DerivedProp>().filter { it.captured }
            for (id in post.getValue(w) - pre.getValue(w))
                captures[id to refName] = props.associate { it.name to evaluator.evalMember(id, refName, it) }
            for (id in pre.getValue(w) - post.getValue(w))
                retractions.add(id to refName)
        }

        // firings: entrants for `when R`, leavers for `when leaving R`
        for (rule in model.rules.values) {
            if (rule.preposition != "after" && "commit" !in rule.triggers && rule.preposition != null) continue
            if (rule.preposition == null || "commit" in rule.triggers) {
                val w = watchers.first { it.key == "rule:${rule.name}" }
                val subjects =
                    if (rule.leaving) pre.getValue(w) - post.getValue(w)
                    else post.getValue(w) - pre.getValue(w)
                for (subject in subjects) {
                    if (rule.preposition == "after") t.afterQueue.add(rule to subject)
                    else fire(rule, subject)
                }
            }
        }

        // close of the commit: leaver captures retract (exit rules were their last readers)
        retractions.forEach { captures.remove(it) }
        return createdIds
    }

    private fun applyCreate(m: Mutation.Create, t: Txn): Long {
        val decl = model.shapes[m.shape] ?: throw VelleRuntimeError("unknown shape '${m.shape}'")
        val id = nextId++
        val inst = Instance(id, m.shape, id, m.fields.toMutableMap())
        instances[id] = inst
        byShape.getOrPut(m.shape) { mutableListOf() }.add(id)
        t.created.add(id)
        for (p in decl.members.filterIsInstance<StoredProp>()) {
            if (p.name in inst.fields || p.initially == null) continue
            inst.fields[p.name] =
                if (p.initially == PathExpr("randomUUID")) Value.VText(UUID.randomUUID().toString())
                else evaluator.eval(p.initially, Evaluator.Ctx(m.shape, id))
        }
        for (p in decl.members.filterIsInstance<TimestampProp>())
            inst.fields[p.name] = Value.VDateTime(now) // create and update both start here
        return id
    }

    private fun applyAssign(m: Mutation.Assign, t: Txn, pre: Map<*, Set<Long>>) {
        val inst = instances[m.id] ?: throw VelleRuntimeError("assign to missing instance ${m.id}")
        // frozen-field tripwire: the validator proved this impossible; assert anyway [S5 spirit]
        for ((refName, r) in model.refinements) {
            val frozen = r.members.filterIsInstance<FrozenClause>().singleOrNull() ?: continue
            if (model.baseOf(refName) != inst.shape) continue
            val fields = frozen.fields.ifEmpty {
                model.shapes[inst.shape]?.members?.filterIsInstance<StoredProp>()?.map { it.name } ?: emptyList()
            }
            if (m.field in fields) {
                val wasMember = watchers.find { it.key == "capture:$refName" }
                    ?.let { pre[it]?.contains(m.id) }
                    ?: evaluator.memberOfRefExpr(m.id, RefName(refName))
                if (wasMember)
                    throw VelleRuntimeError("write to ${inst.shape}.${m.field}, frozen by '$refName' — validator gap")
            }
        }
        setField(inst, m.field, m.value, t)
    }

    private fun setField(inst: Instance, field: String, value: Value, t: Txn) {
        t.oldValues.add(Triple(inst.id, field, inst.fields[field]))
        inst.fields[field] = value
    }

    // ── firing a rule ────────────────────────────────────────────────────────

    private fun fire(rule: RuleDecl, subject: Long) {
        val scope = conditionScopeName(rule)
        val ctx = Evaluator.Ctx(scope, subject)
        val mutations = mutableListOf<Mutation>()
        for (item in rule.body) when (item) {
            is Assignment -> {
                val (targetId, field) = evaluator.resolveTarget(item.target, ctx)
                mutations.add(Mutation.Assign(targetId, field, evaluator.eval(item.value, ctx)))
            }
            is Creation -> {
                val fields = mutableMapOf<String, Value>()
                item.forExpr?.let { forE ->
                    val ref = evaluator.eval(forE, ctx) as? Value.VRef
                        ?: throw VelleRuntimeError("'for' target is not an instance")
                    val target = instances.getValue(ref.id).shape
                    val matched = model.shapes.getValue(item.shape).members
                        .filterIsInstance<StoredProp>()
                        .single { (it.type as? RelType)?.let { t -> !t.many && t.shape == target } == true }
                    fields[matched.name] = ref
                }
                item.fields.forEach { f -> fields[f.name] = evaluator.eval(f.value, ctx) }
                mutations.add(Mutation.Create(item.shape, fields))
            }
            ThenMarker -> {} // ordering within one commit is a compilation concern
        }
        if (mutations.isNotEmpty()) applyCommit(mutations)
    }

    internal fun conditionScopeName(rule: RuleDecl): String {
        val c = rule.condition
        return if (c is RefName && (c.name in model.shapes || c.name in model.refinements)) c.name
        else model.baseOfExpr(c) ?: throw VelleRuntimeError("unresolvable condition for '${rule.name}'")
    }

    // ── ticks (evaluation.md, "Ticks") ───────────────────────────────────────

    fun tick(schedule: String) {
        val previous = lastTick[schedule] ?: startInstant
        for (rule in model.rules.values) {
            if (schedule !in rule.triggers) continue
            val subjects: List<Long> =
                if (rule.leaving) {
                    // leavers at the tick commit itself: member under the previous
                    // tick's clock, not a member now
                    val base = model.baseOfExpr(rule.condition) ?: continue
                    byShape[base].orEmpty().filter {
                        membershipAt(previous, it, rule.condition) &&
                            !evaluator.memberOfRefExpr(it, rule.condition)
                    }
                } else {
                    val base = model.baseOfExpr(rule.condition) ?: continue
                    byShape[base].orEmpty().filter { evaluator.memberOfRefExpr(it, rule.condition) }
                }
            for (subject in subjects) {
                // each firing is its own transaction; a straggler blocks only itself
                if (!rule.leaving && !evaluator.memberOfRefExpr(subject, rule.condition)) continue
                val result = inTransaction { fire(rule, subject) }
                result.exceptionOrNull()?.let {
                    failures.add("tick($schedule) firing of '${rule.name}' failed: ${it.message}")
                }
            }
        }
        lastTick[schedule] = now
    }

    private fun membershipAt(time: Instant, id: Long, condition: RefExpr): Boolean {
        evalTime = time
        try { return evaluator.memberOfRefExpr(id, condition) } finally { evalTime = null }
    }

    // ── never enforcement at transaction end [S5] ────────────────────────────

    private fun checkNevers() {
        for ((i, n) in model.nevers.withIndex()) {
            val base = model.baseOfExpr(n.target) ?: continue
            for (id in byShape[base].orEmpty()) {
                if (evaluator.memberOfRefExpr(id, n.target))
                    throw NeverViolation("never #${i + 1} over $base is violated")
            }
        }
    }

    // ── query surface (read-only; the typed-accessor codegen wraps this) ─────

    fun instancesOf(name: String): List<Long> = when {
        name in model.shapes -> byShape[name].orEmpty().toList()
        name in model.refinements -> {
            val base = model.baseOf(name) ?: return emptyList()
            byShape[base].orEmpty().filter { evaluator.memberOfRefExpr(it, RefName(name)) }
        }
        else -> throw IllegalArgumentException("unknown shape or refinement '$name'")
    }

    fun isMember(id: Long, refinement: String): Boolean =
        evaluator.memberOfRefExpr(id, RefName(refinement))

    /** Read a field — stored, derived, timestamp, capture, or inverse collection — unwrapped. */
    fun get(id: Long, field: String): Any? {
        val inst = instances[id] ?: throw IllegalArgumentException("no instance $id")
        return unwrap(evaluator.readMember(id, inst.shape, field))
    }

    /** Read a refinement-scoped member (a capture or refinement-derived property). */
    fun getAs(id: Long, refinement: String, field: String): Any? =
        unwrap(evaluator.readMember(id, refinement, field))

    private fun unwrap(v: Value): Any? = when (v) {
        is Value.VNum -> v.v
        is Value.VText -> v.v
        is Value.VBool -> v.v
        is Value.VDate -> v.v
        is Value.VDateTime -> v.v
        is Value.VRef -> v.id
        is Value.VColl -> v.ids
        is Value.VDuration -> v.amount to v.unit
        Value.VNone -> null
    }
}
