package velle

import java.time.Instant

// ── What every generated system inherits (Act API) ──────────────────────
//
// The generator emits what is specific to a spec: the input classes, the model
// objects, the field descriptors, the materializers, and one typed line per
// act and per shape. Everything a generated system does the same way as every
// other lives here, so a generated file reads as its surface and nothing else.

/** A generated input value: an act's input class or a nested new record. The
 *  boundary walks it once before the runtime runs (Act API, "The commit
 *  function"): [register] puts its new values in the act's registry, [raw]
 *  flattens its fields to what the runtime takes, [parts] carries an act's
 *  inline parts beside its fields. */
interface NewValue {
    fun register(reg: NewRegistry)
    fun raw(system: VelleSystem, reg: NewRegistry): Map<String, Any?>
    fun parts(system: VelleSystem, reg: NewRegistry): Map<String, List<Map<String, Any?>>> = emptyMap()
}

/** The base of a generated `<Name>System`: the runtime it drives, the
 *  harness-controlled clock, the boundary every commit overload passes, and the
 *  degenerate query behind each `<shapes>()` accessor. */
abstract class GeneratedSystem(spec: String, startTime: Instant) {
    val system: VelleSystem = VelleSystem(Model(Parser.parse(spec)), startTime)

    // ── clock (harness-controlled; ticks are separate calls) ──
    val now: Instant get() = system.now
    fun advanceSeconds(seconds: Long) = system.advance(seconds)
    fun advanceDays(days: Long) = system.advanceDays(days)
    fun setTime(t: Instant) = system.setTime(t)

    /** The boundary (OQ63 §6.2.3.3): every new value the input carries gets a token by
     *  identity, and a field pointing at a sibling names it; a malformed input is refused
     *  before the runtime, as a `type:` reason with no `never` behind it. */
    protected fun boundary(shape: String, input: NewValue, actor: Ref<*>? = null): CommitResult {
        val reg = NewRegistry()
        input.register(reg)
        val raw = input.raw(system, reg)
        val parts = input.parts(system, reg)
        reg.problem()?.let { return CommitResult.Refused(it) }
        return system.commit(shape, raw, parts, actor?.let(system::idOf))
    }

    /** Every current instance of [shape], as model objects, read in one scope. */
    protected fun <T> all(shape: String, materialize: (Long) -> T): List<T> =
        system.scope { system.instancesOf(shape).map(materialize) }
}

/** The base of a generated `Read`: `read(ref)`, the query registry, and the
 *  helpers behind the one typed line the generator emits per shape. [Self] is the
 *  generated class, so a registered query is built against its typed roots;
 *  [modelOf] builds the model object of whatever shape an instance is (a
 *  union-typed reference resolves to its operand's). */
abstract class ReadSurface<Self : ReadSurface<Self>>(protected val system: VelleSystem, private val modelOf: (Long) -> Ref<*>) {
    /** Queries registered beside the client (OQ51 R9): checked at the build, named for the store. */
    val registry = QueryRegistry()
    @Suppress("UNCHECKED_CAST")
    fun <Q : TypedQuery<*, *>> register(name: String, build: Self.() -> Q): Q = registry.register(name, (this as Self).build())

    /** The model object behind a reference, read now; throws when the record no longer exists. */
    @Suppress("UNCHECKED_CAST")
    operator fun <T : Any> invoke(ref: Ref<T>): T = fetch(ref) as T

    /** A bare key is not a reference: a persisted reference built by hand is refused at compile time. */
    @Deprecated("read a record by its key with read.<shape>(key); read(ref) takes a reference the system handed you", level = DeprecationLevel.ERROR)
    operator fun invoke(ref: Ref<Nothing>): Nothing = throw UnsupportedOperationException()

    private fun fetch(ref: Ref<*>): Ref<*> = system.scope {
        val id = system.idOf(ref)
        system.currentShapeOf(id) ?: throw NoSuchElementException("no current record for ${system.refFor(id)}")
        modelOf(id)
    }

    /** Fetch by store key (a model object is not a key: resolve it with `read(it)`). */
    @Suppress("UNCHECKED_CAST")
    protected fun <T : Any> byKey(shape: String, key: StoreKey): T = fetch(Ref.Persisted(shape, key)) as T

    /** The record behind [ref] as a member of [shape], or null when it is not one. */
    protected fun <T> member(shape: String, ref: Ref<*>, materialize: (Long) -> T): T? =
        system.scope { system.idOf(ref).let { if (system.exists(shape, it)) materialize(it) else null } }

    protected fun <F : Fields, T> query(shape: String, fields: F, materialize: (Long) -> T): TypedQuery<F, T> =
        TypedQuery(system, Query(shape), fields, materialize)
}

/** The base of a generated `<Viewer>Read` (Reference §22, "The actor"): every query
 *  carries the viewer, so the engine admits only what the shape's clauses admit, and
 *  every fetch resolves absent for a record the viewer is not shown. The viewer is
 *  held as a reference: a handle would die with the scope of the first read. */
abstract class ViewerReadSurface(protected val system: VelleSystem, protected val viewer: Ref<*>) {
    /** The record behind [ref], or null when it is gone, not a [shape], or not shown to this viewer. */
    protected fun <T> shown(shape: String, ref: Ref<*>, materialize: (Long) -> T): T? = system.scope {
        val id = system.idOf(ref)
        if (system.visibleCurrent(system.idOf(viewer), id) && system.exists(shape, id)) materialize(id) else null
    }

    protected fun <F : Fields, T> query(shape: String, fields: F, materialize: (Long) -> T): TypedQuery<F, T> =
        TypedQuery(system, Query(shape, viewer = viewer), fields, materialize)
}
