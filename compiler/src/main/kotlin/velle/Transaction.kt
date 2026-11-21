package velle

/** One change a commit makes to the state (evaluation.md, "Processing one commit"). */
internal sealed interface Mutation {
    data class Create(val shape: String, val fields: Map<String, Value>) : Mutation
    data class Assign(val id: Long, val field: String, val value: Value) : Mutation
    /** OQ37: the instance becomes transient at this, its final commit — fully
     *  present within the deleting transaction (last readers), removed at close. */
    data class Delete(val id: Long) : Mutation
}

/** A rule and the subject it fires for. */
internal typealias Firing = Pair<RuleDecl, Long>

/**
 * What one envelope (evaluation.md, "The universal transaction") has done so far:
 * enough to undo it on rollback, to build the commit set at its close, and to
 * name what it was about in a rejection.
 */
internal class Transaction(
    /** The act's actor (Reference §22, "The actor"): the committer of the root act, read by
     *  a rule declared `by`; null for a tick, an after-commit firing, or an act without `by`. */
    val actor: Long? = null,
) {
    /** Handles of the records this transaction created, in creation order. */
    val created = mutableListOf<Long>()
    /** (handle, field, value before the write) for every field write, in write order. */
    val priorFieldValues = mutableListOf<Triple<Long, String, Value?>>()
    /** The `after commit` firings queued by this transaction's commits, drained after it closes. */
    val afterQueue = mutableListOf<Firing>()
    /** Instances this transaction deletes (OQ37), in deletion order —
     *  physically removed at close, unmarked on rollback. */
    val deleted = mutableListOf<Long>()
    /** How deep the commit cascade has gone: each commit a firing makes is one level. */
    var cascadeDepth = 0
    /** The act this transaction is for, once inserted; null for a tick or after-commit firing. */
    var act: Long? = null
    /** The rule firing this transaction is, and its subject; null for an act. */
    var firing: Firing? = null
    /** The schedule whose tick this transaction serves; null otherwise. */
    var schedule: String? = null
    /** What the after-commit firings this envelope queued came to: refused by a `never`, or failed. */
    val afterRejections = mutableListOf<Rejection>()
    val afterFailures = mutableListOf<FiringFailure>()

    /** Cumulative footprint of every commit in the envelope (never gating). */
    val footprint = Footprint()

    /** (id, refinement) → prior values, recorded before each capture write
     *  or retraction — rollback restores them, the commit set reads the
     *  touched keys' end state. */
    val priorCaptures = mutableListOf<Pair<Pair<Long, String>, Map<String, Value>?>>()
}
