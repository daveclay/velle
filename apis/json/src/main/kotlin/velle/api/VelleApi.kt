package velle.api

import velle.CommitResult
import velle.Cursor
import velle.Diagnostic
import velle.OrderDatum
import velle.Page
import velle.Parser
import velle.Query
import velle.QueryRefused
import velle.Ref
import velle.StoreKey
import velle.TickRejected
import velle.VelleSyntaxError
import velle.VelleSystem
import java.time.Clock

/**
 * A running system's operations for a caller who speaks JSON: commit an act,
 * read one record, run a query, count, fire a tick, and describe the model.
 * Driven by the compiled model, so one class serves every spec; it names no
 * transport, and a transport (REST, GraphQL, a message queue) maps its requests
 * onto these calls and the answers back.
 *
 * Calls run side by side: each one is a scope of its own on the runtime, with a
 * working set of records no other call shares, and the store underneath takes a
 * unit of work per read and per commit ([Store API]). Two commits that revolve
 * around one record take turns on the store's queue keys; everything else runs
 * together.
 *
 * The clock: before each call the system's clock is set from [clock], so `today`
 * and `now` in the spec are the wall clock of the machine the system runs on.
 * Pass null to leave the clock to the caller, as a test does.
 *
 * Authorization ([Authorization]): an act declared `by` is committed by the
 * [actor] the caller resolved, and refused without one. Once a spec declares
 * who may see its records, every read carries the viewer and is refused without
 * one; a spec with no authorization serves the engineer's own view, which sees
 * everything.
 */
class VelleApi(val system: VelleSystem, private val clock: Clock? = Clock.systemUTC()) {

    val model get() = system.model
    /** The value codec this layer speaks: a transport that has to read or write a value itself uses the same one. */
    val wire = Wire(system)
    private val describe = Describe(model)

    /** The model as a caller sees it, computed once. */
    val description: Map<String, Any?> by lazy { describe.describe() }

    /** The schedules the spec's rules run on. */
    val schedules: List<String> get() = describe.schedules

    /** The shapes and refinements a caller may read. */
    val readable: List<String> get() = describe.readable()

    /** The acts a caller may commit. */
    val acts: List<String> get() = model.exposed.toList()

    /** Does the act named [act] declare an actor (`by`)? Unknown acts answer false. */
    fun actorShapeOf(act: String): String? = model.shapes[act]?.actor?.shape

    /** A reference to a record from its shape and key, as a resolver builds the actor from an authenticated identity. */
    fun refTo(shape: String, key: Any): Ref.Persisted {
        if (shape !in model.shapes) throw ApiException.badRequest("no shape '$shape'")
        return Ref.Persisted(shape, StoreKey(wire.key(key)))
    }

    /** One operation on the current clock, in one scope of the runtime: what every call on this class, and the application's own code, runs inside. */
    fun <T> scope(body: () -> T): T {
        clock?.let { system.setTime(it.instant()) }
        return system.scope(body)
    }

    // ── commit ──────────────────────────────────────────────────────────────────

    /**
     * Commit [act] with the fields in [body] for [actor]. The answer is
     * `{"accepted": true, "ref": {...}}` (`ref` null for a transient act) or
     * `{"accepted": false, "reason": ..., "rejection": {...}}`, the rejection as
     * data when a `never` refused it ([Reference §21]) and null for a malformed input.
     */
    fun commit(act: String, body: Map<String, Any?>, actor: Ref<*>?): Map<String, Any?> {
        if (act !in model.exposed) throw ApiException.notFound("no act '$act'")
        val actorProp = model.shapes.getValue(act).actor
        if (actorProp != null && actor == null)
            throw ApiException.unauthorized("'$act' is committed by a ${actorProp.shape}; the request names no actor")
        return scope {
            val input = wire.input(act, body)
            val actorId = if (actorProp != null) system.idOf(actor!!) else null
            when (val r = system.commit(act, input.fields, input.parts, actorId)) {
                is CommitResult.Accepted -> linkedMapOf("accepted" to true, "ref" to r.ref?.let(wire::ref))
                is CommitResult.Refused -> linkedMapOf("accepted" to false, "reason" to r.reason, "rejection" to r.rejection?.let(wire::rejection))
            }
        }
    }

    // ── read ────────────────────────────────────────────────────────────────────

    /** The record of [root] (a shape or refinement) with store key [key], as [viewer] sees it; not found when gone, not a member, or not shown. */
    fun read(root: String, key: Any, viewer: Ref<*>?): Map<String, Any?> {
        checkRoot(root)
        val base = if (root in model.shapes) root else model.baseOf(root)
            ?: throw ApiException.badRequest("'$root' is a union of several shapes; read a record by its own shape")
        val k = wire.key(key)
        return scope {
            val viewerId = viewerId(viewer)
            val id = try {
                system.idOf(Ref.Persisted(base, StoreKey(k)))
            } catch (e: IllegalArgumentException) {
                throw ApiException.notFound("no $root with key $k")
            }
            if (system.currentShapeOf(id) == null || !system.exists(root, id) || !system.visibleCurrent(viewerId, id))
                throw ApiException.notFound("no $root with key $k")
            wire.record(id, root, viewerId)
        }
    }

    /** The record behind a reference the caller holds, as [viewer] sees it, read as its own shape. */
    fun read(ref: Ref<*>, viewer: Ref<*>?): Map<String, Any?> = read(ref.shape, ref.key.value, viewer)

    /**
     * The records behind several references, each read as its own shape, in one
     * scope: a record that is gone or not shown to [viewer] is null in its place.
     * What a transport resolving a collection field calls, instead of one read per element.
     */
    fun readEach(refs: List<Ref<*>>, viewer: Ref<*>?): List<Map<String, Any?>?> {
        if (refs.isEmpty()) return emptyList()
        return scope {
            val viewerId = viewerId(viewer)
            refs.map { ref ->
                if (ref.shape !in model.shapes) null
                else {
                    val id = try { system.idOf(ref) } catch (e: IllegalArgumentException) { null }
                    if (id == null || system.currentShapeOf(id) == null || !system.visibleCurrent(viewerId, id)) null
                    else wire.record(id, ref.shape, viewerId)
                }
            }
        }
    }

    /**
     * The records of [root] that satisfy [where], a predicate in the spec's own
     * language over the root's members ([Reference §10]), in the order [orderBy]
     * names (`"due"`, `"-total"` for descending), [size] at a time from the
     * cursor [after]. The answer is `{"items": [...], "next": cursor-or-null}`.
     */
    fun query(root: String, where: String?, orderBy: List<String>, size: Int?, after: String?, viewer: Ref<*>?): Map<String, Any?> {
        checkRoot(root)
        val q = build(root, where, orderBy, size, after, viewer)
        return scope {
            val viewerId = viewerId(viewer)
            val result = try { system.query(q) } catch (e: QueryRefused) { throw refused(e) }
            linkedMapOf(
                "items" to result.ids.map { wire.record(it, root, viewerId) },
                "next" to result.next?.encode(),
            )
        }
    }

    /** How many records of [root] satisfy [where], as [viewer] sees them. */
    fun count(root: String, where: String?, viewer: Ref<*>?): Int {
        checkRoot(root)
        val q = build(root, where, emptyList(), null, null, viewer)
        return scope {
            viewerId(viewer)
            try { system.count(q) } catch (e: QueryRefused) { throw refused(e) }
        }
    }

    private fun build(root: String, where: String?, orderBy: List<String>, size: Int?, after: String?, viewer: Ref<*>?): Query {
        val predicate = where?.takeIf { it.isNotBlank() }?.let {
            try { Parser.parsePredicate(it) } catch (e: VelleSyntaxError) { throw ApiException.badRequest("where: ${e.message}") }
        }
        val order = orderBy.filter { it.isNotBlank() }.map { d ->
            val t = d.trim()
            when {
                t.startsWith("-") -> OrderDatum(t.drop(1).trim(), descending = true)
                t.endsWith(" desc", ignoreCase = true) -> OrderDatum(t.dropLast(5).trim(), descending = true)
                t.endsWith(" asc", ignoreCase = true) -> OrderDatum(t.dropLast(4).trim())
                else -> OrderDatum(t)
            }
        }
        val page = size?.let { n ->
            if (n <= 0) throw ApiException.badRequest("size: a page holds at least one record")
            val cursor = after?.takeIf { it.isNotBlank() }?.let {
                try { Cursor.decode(it, model) } catch (e: IllegalArgumentException) { throw ApiException.badRequest("after: not a cursor this query handed out") }
            }
            Page(n, cursor)
        }
        if (after != null && size == null) throw ApiException.badRequest("after: a cursor continues a paged query; give a size")
        return Query(root, predicate, order, page, viewer)
    }

    private fun refused(e: QueryRefused) = ApiException.badRequest("the query was refused", e.diagnostics.map(Diagnostic::message))

    private fun checkRoot(root: String) {
        if (root !in model.shapes && root !in model.refinements) throw ApiException.notFound("no shape or refinement '$root'")
        if (root in model.transients || model.basesOf(root).any { it in model.transients })
            throw ApiException.badRequest("'$root' is a transient act: an input to the state, never readable (Reference §4)")
    }

    /** The viewer's handle for this scope; refused when the spec has authorization and no viewer was resolved. */
    private fun viewerId(viewer: Ref<*>?): Long? {
        if (viewer == null) {
            if (model.hasAuthorization) throw ApiException.unauthorized("this system declares who may see its records; the request names no actor")
            return null
        }
        if (viewer.shape !in model.shapes) throw ApiException.unauthorized("the actor's shape '${viewer.shape}' is not in this system")
        return system.idOf(viewer)
    }

    // ── tick ────────────────────────────────────────────────────────────────────

    /** Run every rule on [schedule] now. The answer names the schedule, the moment, and each firing a `never` rejected. */
    fun tick(schedule: String): Map<String, Any?> {
        if (schedule !in schedules) throw ApiException.notFound("no schedule '$schedule'; this system has ${schedules}")
        return scope {
            val rejections = try {
                system.tick(schedule); emptyList()
            } catch (e: TickRejected) {
                e.rejections.map(wire::rejection)
            }
            linkedMapOf("schedule" to schedule, "at" to system.now.toString(), "rejections" to rejections)
        }
    }
}

/** A request this layer refuses before the runtime sees it, with the kind a transport maps to its own status. */
class ApiException(val kind: Kind, message: String, val details: List<String> = emptyList()) : IllegalArgumentException(message) {
    enum class Kind { BAD_REQUEST, NOT_FOUND, UNAUTHORIZED }

    companion object {
        fun badRequest(message: String, details: List<String> = emptyList()) = ApiException(Kind.BAD_REQUEST, message, details)
        fun notFound(message: String) = ApiException(Kind.NOT_FOUND, message)
        fun unauthorized(message: String) = ApiException(Kind.UNAUTHORIZED, message)
    }
}
