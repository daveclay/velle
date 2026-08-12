package velle

/**
 * Hydration spike (working-docs/investigate_runtime.md §2–3): the runtime as a
 * per-commit decision kernel that demand-hydrates state through engineer-supplied
 * resolvers, evaluates in memory, and hands the transaction's mutation set back
 * through a commit callback.
 *
 * A [Row] is one instance as the engineer's storage holds it: stored fields and
 * timestamp fields only (derived properties recompute; refinement membership is
 * the predicate; captures have no storage home yet — a known spike hole). Field
 * values are plain Kotlin: String, Boolean, BigDecimal (all numerics), LocalDate,
 * Instant, and Long for a to-one reference's id.
 */
data class Row(val shape: String, val id: Long, val fields: Map<String, Any?>)

/**
 * The read half: the few, typed questions evaluation can ask of storage —
 * by id, by reference (the join read), by shape (the scan read, filtered or
 * not), and per-membership capture memory. The doc's design generates a
 * per-act interface from the static read set; the spike collapses that to the
 * question *kinds* the read set is made of. Fetches are cached per envelope by
 * the runtime — one consistent snapshot per transaction, never re-issued
 * (investigate_runtime.md §2).
 */
interface StateResolver {
    /** Highest id in storage — the runtime mints ids above it. */
    fun maxId(): Long

    /** Fetch one instance by id — following a to-one reference. */
    fun fetchById(shape: String, id: Long): Row?

    /**
     * The capture read: the per-membership values for [refinement] on instance
     * [id] of base [shape], or null when storage holds no current membership.
     * Values use [Row.fields]' vocabulary, one entry per captured property.
     *
     * Deliberately not defaulted: a capture is stored state the spec's exit
     * rules depend on (the last-reader contract, README §13), so a store facing
     * a capture-carrying spec must decide how to persist it — a table per
     * refinement, nullable columns on the base table, a discriminator — the
     * same catalog as any subtype-state mapping. [Model.captureSchemas]
     * enumerates exactly what must survive; a store for a spec with no
     * captures discharges this with `= null`.
     */
    fun fetchCaptures(shape: String, id: Long, refinement: String): Map<String, Any?>?

    /** The scan read: all instances of a shape (refinement candidates, tick sweeps, `never` checks). */
    fun fetchAll(shape: String): List<Row>

    /** The join read: instances of [shape] whose to-one [field] references [targetId]. */
    fun fetchReferencing(shape: String, field: String, targetId: Long): List<Row>

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
}

/**
 * The write half: one transaction's whole mutation set — the act's create plus
 * every rule-fired create and assign in the envelope — delivered atomically,
 * once, at transaction close. The callback runs *inside* the envelope: a thrown
 * exception rolls the whole commit back and the caller gets the error
 * (investigate_runtime.md §3). Transient acts are excluded — only their
 * consequences persist.
 *
 * The capture channel ([captured]/[retracted]) is declarative end-state, one op
 * per membership the envelope touched: a [Capture] means "this membership now
 * holds these values" (insert or replace), a [Retraction] means "no current
 * membership" (delete; deleting nothing is fine). Values use [Row.fields]'
 * vocabulary. A retraction is the one deletion in the whole contract — legal
 * because a capture is per-membership *memory*, not a record of something that
 * happened in the world (README §8; history is occurrence facts, not captures).
 */
data class CommitSet(
    val created: List<Row>,
    val assigned: List<Assign>,
    val captured: List<Capture> = emptyList(),
    val retracted: List<Retraction> = emptyList(),
) {
    data class Assign(val shape: String, val id: Long, val field: String, val value: Any?)

    /** Per-membership memory for [refinement] on instance [id] of base [shape]. */
    data class Capture(val shape: String, val id: Long, val refinement: String, val values: Map<String, Any?>)
    data class Retraction(val shape: String, val id: Long, val refinement: String)
}

fun interface CommitCallback {
    fun onCommit(commit: CommitSet)
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
