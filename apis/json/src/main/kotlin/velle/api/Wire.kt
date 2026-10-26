package velle.api

import velle.ClosureEdge
import velle.Keyed
import velle.Model
import velle.NewRef
import velle.RecordRef
import velle.RecordSnapshot
import velle.Ref
import velle.Rejection
import velle.RelType
import velle.ScalarType
import velle.ShapeDecl
import velle.StoreKey
import velle.StoredProp
import velle.TypeRegistry
import velle.VType
import velle.VelleSystem
import velle.canonicalAny
import velle.fromCanonical
import velle.readLiteral
import velle.LiteralRead
import velle.EvalStep
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.Base64
import java.util.UUID

/**
 * The wire vocabulary: how a value crosses between a running system and a
 * caller who speaks JSON. Everything here is a plain Kotlin value a JSON
 * library writes as is — `Map`, `List`, `String`, `Boolean`, a number — and
 * nothing else.
 *
 * Out: a record is a map of its members, with `shape` and `key` first; a
 * reference is `{"shape": ..., "key": ...}`; a `Date`, `DateTime`, `time`,
 * `uuid` is its ISO text; a `duration` its ISO-8601 text (`PT24H`); `bytes`
 * base64; a value of an extension type its own canonical text; an `integer`
 * a whole number, every other number a decimal.
 *
 * In: an act's input is a map of the fields the committer supplies ([Binding
 * API], "The input class"). A `one` field takes a key, a `{"shape", "key"}`
 * pair, `{"new": n}` naming a sibling value tagged `"@new": n`, or, when the
 * field is named in the act's `with`, a nested object for a record created
 * here. A part collection is a list of nested objects. A scalar is read back
 * from the form it was written in.
 */
class Wire(private val system: VelleSystem) {

    private val model: Model get() = system.model

    // ── out ─────────────────────────────────────────────────────────────────────

    /** A reference as the wire carries it: the record's shape and its store key. */
    fun ref(ref: Keyed): Map<String, Any?> = linkedMapOf("shape" to ref.shape, "key" to ref.key.value)

    fun ref(ref: RecordRef): Map<String, Any?> = linkedMapOf("shape" to ref.shape, "key" to ref.key.value)

    private fun refOf(id: Long): Map<String, Any?> = ref(system.refFor(id))

    /** The refinements a record of [shape] may be a member of, named as the generated model object's flags are. */
    fun flagsOf(shape: String): List<String> = flagCache.getOrPut(shape) {
        model.refinements.keys.filter { r -> model.baseOf(r) == shape && model.basesOf(r).none { it in model.transients } }
    }
    private val flagCache = mutableMapOf<String, List<String>>()

    /**
     * The record [id] read as [scope] (its shape, or a refinement it is a member of)
     * for [viewer]: every member of the scope, a field the viewer may not see as null,
     * and on a shape one `is<Refinement>` flag per refinement of it.
     */
    fun record(id: Long, scope: String, viewer: Long?): Map<String, Any?> {
        val shape = system.shapeOf(id)
        val hidden = system.hiddenFields(viewer, id, scope)
        val out = linkedMapOf<String, Any?>()
        out["shape"] = shape
        out["key"] = system.refFor(id).key.value
        for (m in model.membersOf(scope).values) {
            if (m.name == "id") continue
            out[m.name] = if (m.name in hidden) null else out(memberValue(id, scope, m.name, m.owner), m.type)
        }
        if (scope in model.shapes) for (r in flagsOf(shape)) out["is$r"] = system.isMember(id, r)
        return out
    }

    private fun memberValue(id: Long, scope: String, name: String, owner: String): Any? =
        if (owner in model.shapes && scope in model.shapes) system.get(id, name) else system.getAs(id, scope, name)

    /** A member's value, as `VelleSystem.get` returns it, in wire form. */
    fun out(v: Any?, t: VType): Any? {
        if (v == null) return null
        return when (t) {
            is VType.Optional -> out(v, t.inner)
            is VType.Num -> number(v as BigDecimal, t.name)
            VType.Text -> v as String
            VType.Bool -> v as Boolean
            VType.DateT, VType.DateTimeT, VType.TimeT, VType.UuidT -> v.toString()
            VType.DurationT -> (v as Duration).toString()
            VType.BytesT -> Base64.getEncoder().encodeToString(v as ByteArray)
            is VType.Ext -> model.registry[t.qualifiedName]?.canonicalAny(v) ?: v.toString()
            is VType.Inst -> refOf(v as Long)
            is VType.Coll -> (v as List<*>).map { refOf(it as Long) }
            is VType.CollS -> (v as List<*>).map { out(it, model.scalarOf(t.name)) }
            VType.Id -> v
            VType.Unknown -> v.toString()
        }
    }

    private fun number(v: BigDecimal, name: String): Any =
        if (name == "integer" || name == "long") runCatching { v.longValueExact() }.getOrDefault(v) else v

    /** A refused act's rejection as data ([Reference §21]), every record in it a snapshot. */
    fun rejection(r: Rejection): Map<String, Any?> = linkedMapOf(
        "never" to r.never,
        "prohibition" to r.prohibition,
        "declaration" to r.declaration,
        "requirement" to r.requirement,
        "targetShape" to r.targetShape,
        "act" to r.act?.let(::snapshot),
        "rule" to r.rule,
        "schedule" to r.schedule,
        "subject" to r.subject?.let(::snapshot),
        "produced" to r.produced.map(::snapshot),
        "violating" to r.violating.map(::snapshot),
        "pivots" to r.pivots.map(::snapshot),
        "witnesses" to r.witnesses.map(::snapshot),
        "evaluation" to r.evaluation.map(::step),
        "today" to r.today,
        "message" to r.message,
    )

    private fun snapshot(s: RecordSnapshot): Map<String, Any?> = linkedMapOf(
        "shape" to s.shape,
        "key" to s.key.value,
        "fields" to s.fields.mapValues { plain(it.value) },
    )

    private fun step(s: EvalStep): Map<String, Any?> = when (s) {
        is EvalStep.Read -> linkedMapOf("record" to ref(s.record), "member" to s.member, "value" to plain(s.value))
        is EvalStep.Membership -> linkedMapOf("record" to ref(s.record), "refinement" to s.refinement, "member" to s.member)
    }

    /** A snapshot's field value, which arrives as plain Kotlin, in wire form. */
    private fun plain(v: Any?): Any? = when (v) {
        null -> null
        is RecordRef -> ref(v)
        is Keyed -> ref(v)
        is List<*> -> v.map { plain(it) }
        is String, is Boolean, is BigDecimal, is Int, is Long -> v
        is ByteArray -> Base64.getEncoder().encodeToString(v)
        is LocalDate, is Instant, is LocalTime, is UUID, is Duration -> v.toString()
        else -> model.registry.typeOfValue(v)?.canonicalAny(v) ?: v.toString()
    }

    // ── in ──────────────────────────────────────────────────────────────────────

    /** An act's input, flattened to what the runtime's own commit takes. */
    class Input(val fields: Map<String, Any?>, val parts: Map<String, List<Map<String, Any?>>>)

    /** The store key a caller wrote: a whole number becomes the `Long` every store and the in-memory runtime key by. */
    fun key(json: Any?): Any = when (json) {
        is Long -> json
        is Int -> json.toLong()
        is Short -> json.toLong()
        is Byte -> json.toLong()
        is BigInteger -> json.toLong()
        is BigDecimal -> if (json.stripTrailingZeros().scale() <= 0) json.longValueExact() else throw bad("a key is a whole number or a text, not $json")
        is Double -> if (json == Math.floor(json)) json.toLong() else throw bad("a key is a whole number or a text, not $json")
        is Float -> json.toLong()
        is String -> json.toLongOrNull() ?: json
        null -> throw bad("a key is required")
        else -> throw bad("a key is a whole number or a text, not ${json::class.simpleName}")
    }

    /** The input of [act] from the map a caller sent. Unknown fields and malformed values are refused here with their field named. */
    fun input(act: String, body: Map<String, Any?>): Input {
        val decl = model.shapes[act] ?: throw ApiException.notFound("no act '$act'")
        val m = nested(decl, body, model.closures[act].orEmpty(), backRef = null, path = act)
        // a closure edge's entry is a list of nested values; a `one` field named in `with`
        // that arrived as a reference instead is an ordinary field
        val edges = model.closures[act].orEmpty().map { it.prop }.toSet()
        @Suppress("UNCHECKED_CAST")
        return Input(
            fields = m.filterKeys { (it !in edges || m[it] !is List<*>) && it != "@shape" && it != "@new" },
            parts = m.filterKeys { it in edges && m[it] is List<*> }.mapValues { it.value as List<Map<String, Any?>> },
        )
    }

    /**
     * One record's worth of input: its supplied fields converted, its parts and
     * nested new values converted under their own field names, `@shape` and
     * `@new` carried through as the runtime reads them.
     */
    private fun nested(decl: ShapeDecl, body: Map<String, Any?>, edges: List<ClosureEdge>, backRef: String?, path: String): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>()
        val stored = decl.members.filterIsInstance<StoredProp>().associateBy { it.name }
        val actor = decl.actor?.name
        for ((k, v) in body) {
            val here = "$path.$k"
            when {
                k == "@shape" -> out[k] = v as? String ?: throw bad("$here: '@shape' names a shape")
                k == "@new" -> out[k] = (v as? Number)?.toInt() ?: throw bad("$here: '@new' is a whole number tagging this value for a sibling's `{\"new\": n}`")
                k == actor -> throw bad("$here: the actor is a fact of the commit, not a field the committer supplies")
                k == backRef -> throw bad("$here: a part's reference to its container is filled in by Velle")
                else -> {
                    val edge = edges.find { it.prop == k }
                    val prop = stored[k]
                    when {
                        edge != null && !edge.byValue -> out[k] = parts(edge, v, here)
                        edge != null && v is Map<*, *> && !isReference(v) -> out[k] = listOf(newValue(edge, v, here))
                        prop != null -> if (v != null) out[k] = field(prop, v, here)
                        else -> throw bad("$here: '${decl.name}' has no field '$k' the committer supplies")
                    }
                }
            }
        }
        return out
    }

    private fun isReference(m: Map<*, *>) = m.containsKey("key") || m.containsKey("new")

    @Suppress("UNCHECKED_CAST")
    private fun parts(edge: ClosureEdge, v: Any?, here: String): List<Map<String, Any?>> {
        val list = v as? List<*> ?: throw bad("$here: a list of new ${edge.partShape} values")
        val decl = model.shapes.getValue(edge.partShape)
        return list.mapIndexed { i, item ->
            val m = item as? Map<String, Any?> ?: throw bad("$here[$i]: a new ${edge.partShape} value")
            nested(decl, m, edge.childrenFor(edge.partShape), edge.backRef, "$here[$i]") + ("@shape" to edge.partShape)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun newValue(edge: ClosureEdge, v: Map<*, *>, here: String): Map<String, Any?> {
        val operands = model.basesOf(edge.partShape)
        val concrete = (v["@shape"] as? String) ?: operands.singleOrNull()
            ?: throw bad("$here: a new value for a ${edge.partShape} names which of ${operands.sorted()} it is with '@shape'")
        if (concrete !in operands) throw bad("$here: a '$concrete' cannot arrive at a field typed ${edge.partShape}")
        val decl = model.shapes.getValue(concrete)
        return nested(decl, v as Map<String, Any?>, edge.childrenFor(concrete), backRef = null, path = here) + ("@shape" to concrete)
    }

    private fun field(prop: StoredProp, v: Any, here: String): Any = when (val t = prop.type) {
        is RelType -> if (t.many) (v as? List<*> ?: throw bad("$here: a list of references")).map { reference(it, t.shape, here) }
            else reference(v, t.shape, here)
        is ScalarType -> if (t.many) (v as? List<*> ?: throw bad("$here: a list of ${t.name} values")).map { scalar(it, t.name, here) }
            else scalar(v, t.name, here)
    }

    /** A reference a committer wrote, as the runtime's handle, or a `NewRef` to a sibling value. */
    private fun reference(v: Any?, declared: String, here: String): Any {
        val operands = model.basesOf(declared)
        fun handle(shape: String, key: Any?): Long {
            if (shape !in operands) throw bad("$here: a '$shape' cannot be referenced from a field typed $declared")
            return system.idOf(Ref.Persisted(shape, StoreKey(key(key))))
        }
        return when (v) {
            is Map<*, *> -> when {
                v.containsKey("new") -> NewRef((v["new"] as? Number)?.toInt() ?: throw bad("$here: `new` names a sibling's '@new' number"))
                v.containsKey("key") -> handle((v["shape"] as? String) ?: operands.singleOrNull()
                    ?: throw bad("$here: a reference to a $declared names its shape, one of ${operands.sorted()}"), v["key"])
                else -> throw bad("$here: a reference is a key, {\"shape\", \"key\"}, or {\"new\": n}")
            }
            null -> throw bad("$here: a reference is required")
            else -> handle(operands.singleOrNull()
                ?: throw bad("$here: a reference to a $declared names its shape, one of ${operands.sorted()}"), v)
        }
    }

    /** A scalar the caller wrote, as the Kotlin value the runtime's boundary accepts for the type named [name]. */
    fun scalar(v: Any?, name: String, here: String): Any {
        if (v == null) throw bad("$here: a value is required")
        fun text(): String = v as? String ?: throw bad("$here: a $name is written as text, not ${v::class.simpleName}")
        return try {
            when (name) {
                "text" -> text()
                "boolean" -> v as? Boolean ?: text().toBooleanStrictOrNull() ?: throw bad("$here: true or false")
                "Date" -> LocalDate.parse(text())
                "DateTime" -> Instant.parse(text())
                "uuid" -> UUID.fromString(text())
                "time" -> LocalTime.parse(text())
                "bytes" -> Base64.getDecoder().decode(text())
                "duration" -> duration(text(), here)
                in TypeRegistry.NUMBER_NAMES -> when (v) {
                    is Number -> BigDecimal(v.toString())
                    is String -> v.toBigDecimalOrNull() ?: throw bad("$here: a number")
                    else -> throw bad("$here: a number")
                }
                else -> {
                    val type = model.typeScope[name] ?: throw bad("$here: unknown type '$name'")
                    when (v) {
                        is Number -> when (val r = type.readLiteral(BigDecimal(v.toString()), null)) {
                            is LiteralRead.Ok -> r.value
                            is LiteralRead.Rejected -> throw bad("$here: ${r.reason}")
                            is LiteralRead.NoReader -> throw bad("$here: a $name is written as text")
                        }
                        is String -> try { type.fromCanonical(v) } catch (e: IllegalArgumentException) { throw bad("$here: ${e.message}") }
                        else -> throw bad("$here: a $name")
                    }
                }
            }
        } catch (e: java.time.format.DateTimeParseException) {
            throw bad("$here: '${e.parsedString}' is not a $name (${e.message})")
        } catch (e: IllegalArgumentException) {
            if (e is ApiException) throw e
            throw bad("$here: ${e.message}")
        }
    }

    /** `PT36H` as ISO-8601, or `3 days` as the spec writes it (`weeks`, `days`, `hours`, `minutes`, `seconds`). */
    private fun duration(s: String, here: String): Duration {
        val t = s.trim()
        if (t.startsWith("P") || t.startsWith("-P")) return Duration.parse(t)
        val m = Regex("""^(-?\d+)\s*(weeks?|days?|hours?|minutes?|seconds?)$""").matchEntire(t)
            ?: throw bad("$here: a duration is ISO-8601 (PT36H) or an amount and a unit (3 days)")
        val n = m.groupValues[1].toLong()
        return when (m.groupValues[2].removeSuffix("s")) {
            "week" -> Duration.ofDays(7 * n)
            "day" -> Duration.ofDays(n)
            "hour" -> Duration.ofHours(n)
            "minute" -> Duration.ofMinutes(n)
            else -> Duration.ofSeconds(n)
        }
    }

    private fun bad(message: String) = ApiException.badRequest(message)
}
