package velle

/**
 * The store contract (evaluation.md "The universal transaction"): the
 * runtime is a per-commit decision kernel that demand-hydrates state through an
 * engineer-supplied resolver, evaluates in memory, and hands each transaction's
 * mutation set back through a commit callback.
 *
 * Identity is the store's. Velle never mints persisted ids: rows are keyed
 * by whatever the store keys them by, carried opaquely as [StoreKey]; the
 * runtime's own instance ids are session-local handles that never appear in
 * this contract. References cross the boundary as typed [Ref] objects — never
 * a bare value that may or may not be persisted.
 */

/**
 * The store's key for one row — assigned by the store, opaque to Velle, only
 * ever round-tripped. Equality is value equality on the wrapped key (the
 * runtime's key↔handle map depends on it), so a store must return the same
 * representation it accepts — a Long rowid, a UUID string, whatever it owns.
 */
data class StoreKey(val value: Any)

/**
 * A reference to a record: its concrete base shape and its store key. One type
 * for both sides of the store boundary (Reference §22): a store row carries it
 * for every to-one field, and an application engineer holds it, typed by the
 * model object it points at — `Ref<Customer>` — and resolves it with
 * `read(ref)`. A model object is itself a `Ref<T>` to its own record, so it
 * passes wherever a reference is taken. Equality is identity: same shape, same
 * key, whichever class carries them; whether two reads of one record differ is
 * a comparison of fields, which is the engineer's to make. In memory, with no store, the key is the
 * runtime's own identity; the runtime maps between the two in one place.
 *
 * [T] is a phantom: never read, only there to type the reference. [Persisted]
 * is the untyped carrier (`Ref<Nothing>` fits every `Ref<T>`).
 */
interface Ref<out T> : Keyed {

    /** A row storage already holds, as a store row or a model object's field carries it. */
    class Persisted(override val shape: String, override val key: StoreKey) : Ref<Nothing>, RowRef {
        override fun equals(other: Any?) = other is Keyed && other.shape == shape && other.key == key
        override fun hashCode() = 31 * shape.hashCode() + key.hashCode()
        override fun toString() = "$shape#${key.value}"
    }

    /**
     * A row created in this same [CommitSet] — [index] into [CommitSet.created].
     * Pending refs always point *backward* (creation order is dependency order:
     * a create can only reference instances that already existed when it was
     * evaluated), so a store inserting in list order has already assigned the
     * key a pending ref names. Never seen outside a commit set.
     */
    data class Pending(override val shape: String, val index: Int) : RowRef
}

/**
 * What identifies a record: its concrete base shape and its store key. Every
 * reference is one, and so is every model object — including one typed as a
 * static union's interface (Reference §9), which is a model object of one of the
 * union's operands and is why the identity sits here rather than on [Ref].
 */
interface Keyed {
    /** always the *concrete base shape* of the record — the store never has to guess a table */
    val shape: String
    val key: StoreKey
}

/** The two forms a reference takes inside a commit set: a row the store holds, or a row this commit creates. */
sealed interface RowRef {
    val shape: String
}

/**
 * One instance as the engineer's storage holds it: stored fields and timestamp
 * fields only (derived properties recompute; refinement membership is the
 * predicate; captures travel on their own channel). Scalar values are plain
 * Kotlin — String, Boolean, BigDecimal (all numerics), LocalDate, Instant,
 * UUID, java.time.Duration, LocalTime, ByteArray —
 * every to-one reference is a [Ref.Persisted] built by the store, and a
 * declared `many` is a List of those (or of scalars) in membership order. A
 * field of a registered extension type (Reference §5) holds the type's own
 * Kotlin object; how the store encodes it is the store's binding for that type.
 *
 * A field typed with a static union (Reference §9) still arrives as a
 * [Ref.Persisted] naming the concrete operand: keys are unique only per
 * table, so the store must have kept the shape beside the key — a bare key
 * cannot say whether `target` is Post#1 or Photo#1 (SqliteStore keeps a
 * discriminator column, and matches on both in the join read).
 */
data class Row(val shape: String, val key: StoreKey, val fields: Map<String, Any?>)

/**
 * The read half: the typed questions evaluation can ask of storage — by key,
 * by reference (the join read), by shape (the scan read, filtered or not), and
 * per-membership capture memory. Fetches are cached per envelope by the
 * runtime — one consistent snapshot per transaction, never re-issued (evaluation.md U1).
 */
interface StateResolver {
    /** Fetch one row by the store's own key — following a to-one reference. */
    fun fetchByKey(shape: String, key: StoreKey): Row?

    /**
     * The scan read: all rows of a shape (refinement candidates, tick sweeps,
     * `never` checks). The order rows come back in is the order the query
     * surface reports them (`VelleSystem.instancesOf`), ahead of anything the
     * current transaction created that storage does not hold yet — so a store
     * whose scan order is insertion order gives the application creation order.
     */
    fun fetchAll(shape: String): List<Row>

    /** The join read: rows of [shape] whose to-one [field] references [target]. */
    fun fetchReferencing(shape: String, field: String, target: Ref.Persisted): List<Row>

    /**
     * The filtered scan read: rows of [shape] that may satisfy [filter] — the
     * compiled pre-filter for a refinement condition (Query.kt). The contract is
     * superset-only: return at least every row matching the filter; returning
     * more is always legal (this default returns everything), because the
     * runtime re-checks the authoritative predicate in memory on what comes
     * back. A store may translate the filter to its query language wholesale,
     * partially (weakening the parts its encoding can't compare — see
     * SqliteStore's renderer for the polarity rule that keeps that sound), or
     * not at all — a performance choice, never a correctness one.
     */
    fun fetchCandidates(shape: String, filter: QF): List<Row> = fetchAll(shape)

    /**
     * The capture read: the per-membership values for [refinement] on the
     * [instance] row, or null when storage holds no current membership. Values
     * use [Row.fields]' vocabulary, one entry per captured property (reference
     * values as [Ref.Persisted]).
     *
     * Deliberately not defaulted: a capture is stored state the spec's exit
     * rules depend on (the last-reader contract, Reference §13), so a store facing
     * a capture-carrying spec must decide how to persist it — a table per
     * refinement, nullable columns on the base table, a discriminator — the
     * same catalog as any subtype-state mapping. [Model.captureSchemas]
     * enumerates exactly what must survive; a store for a spec with no
     * captures discharges this with `= null`.
     */
    fun fetchCaptures(instance: Ref.Persisted, refinement: String): Map<String, Any?>?
}

/**
 * The write half: one transaction's whole mutation set — the act's create plus
 * every rule-fired create and assign in the envelope — delivered atomically,
 * once, at transaction close. The callback runs *inside* the envelope: a thrown
 * exception rolls the whole commit back and the caller gets the error (evaluation.md U2).
 * Transient acts are excluded — only their consequences persist.
 *
 * [created] is in creation order; reference-typed values in a creation's
 * fields are [Ref]s, and any [Ref.Pending] among them points at an *earlier*
 * entry, so inserting in order always has the referenced key in hand. Assigns
 * target persisted rows by construction (writes to same-set creates are folded
 * into the creation's fields), and the types say so.
 *
 * The capture channel ([captured]/[retracted]) is declarative end-state, one op
 * per membership the envelope touched: a [Capture] means "this membership now
 * holds these values" (insert or replace), a [Retraction] means "no current
 * membership" (delete; deleting nothing is fine). A capture entered by the
 * same set's create carries a [Ref.Pending] instance; a retraction is always
 * persisted, because create-then-exit inside one envelope nets to nothing. A
 * retraction and a [Deletion] are the contract's only removals — the first
 * legal because a capture is per-membership *memory*, not a record of
 * something that happened in the world (Reference §8; history is occurrence
 * facts, not captures), the second because the spec said `delete` (Reference §12).
 */
data class CommitSet(
    val created: List<Creation>,
    val assigned: List<Assign>,
    val captured: List<Capture> = emptyList(),
    val retracted: List<Retraction> = emptyList(),
    val deleted: List<Deletion> = emptyList(),
) {
    /** One new row; the store assigns its key and reports it back. */
    data class Creation(val shape: String, val fields: Map<String, Any?>)

    data class Assign(val target: Ref.Persisted, val field: String, val value: Any?)

    /** Per-membership memory for [refinement] on [instance] (Reference §8). */
    data class Capture(val instance: RowRef, val refinement: String, val values: Map<String, Any?>)
    data class Retraction(val instance: Ref.Persisted, val refinement: String)

    /** A `delete` statement's row removal (OQ37): the instance ceased to be part
     *  of the state at this transaction. The store removes the row and any
     *  capture memory keyed on it; how (hard delete, tombstone, crypto-shred)
     *  is the store's realization choice — the statement is declarative.
     *  Apply deletions after [created]/[assigned]: a same-set write never
     *  targets a deleted row, but a surviving row may hold a reference at a
     *  deleted one — such a reference reads as absent thereafter (the
     *  absorbing reference, `? initially required`). That reading is only
     *  sound while the dead key stays dead: a store must never reissue a
     *  deleted row's key to a later row (SQLite: `AUTOINCREMENT`, not the
     *  reusing default), or the dangling reference silently points at a
     *  stranger. Capture memory keyed on the row goes with it. */
    data class Deletion(val target: Ref.Persisted)
}

fun interface CommitCallback {
    /**
     * Lands [commit] in storage and returns the store-assigned key for each
     * entry of [CommitSet.created], in the same order — identity is the
     * store's, and this is where it says so. Runs inside the envelope: throw,
     * and the whole commit rolls back.
     */
    fun onCommit(commit: CommitSet): List<StoreKey>
}

/**
 * What a store must be able to persist for one capture-carrying refinement:
 * per-membership memory keyed by (instance of [base], [refinement]), one typed
 * value per property in [props] — written at entry commits, deleted at exit
 * commits, absent while there is no membership. How that maps onto storage is
 * the store's decision; this is the complete statement of the problem.
 */
data class CaptureSchema(val refinement: String, val base: String, val props: List<Prop>) {
    data class Prop(val name: String, val type: VType)
}
