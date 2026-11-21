package velle

sealed interface CommitResult {
    /** [ref] names the record the act became, null for a transient act, which
     *  leaves none. [id] is the runtime's handle for it: in memory, with no
     *  store, that is the identity the raw surface works in; over a store it
     *  is dead once the commit returns (see [VelleSystem.scope]), so hold [ref]. */
    data class Accepted(
        val id: Long,
        val ref: Ref.Persisted?,
        /** Firings behind the act's `after commit` boundary that a `never` refused: carried as data, the act stands (Reference §21). */
        val afterCommitRejections: List<Rejection> = emptyList(),
        /** Firings behind the boundary that failed for any other reason; the act stands, the next commit that queues them retries ([S4]). */
        val afterCommitFailures: List<FiringFailure> = emptyList(),
    ) : CommitResult
    /** [reason] is the rendered message; [rejection] is the same as data when a
     *  `never` refused the act (Reference §21), null for a type-level refusal. */
    data class Refused(val reason: String, val rejection: Rejection? = null) : CommitResult
}

/**
 * A typed commit's outcome (Reference §22, "External input"): the generated
 * surface commits one typed input value per act and hands back the same
 * value with the verdict, so a refusal holds what was refused as the
 * engineer's own type — never a map — and an acceptance holds what was
 * accepted beside the reference to the record it made. [R] is that
 * reference's type: `Ref<Shape>` for a durable act, `Nothing?` for a
 * transient act (Reference §4), which leaves no record to refer to. The
 * runtime's session handle never appears here: the engineer holds a
 * reference (Hydration.kt, Ref), which is the store's identity, and reads
 * it back with `read(ref)`. The runtime stays generic in [I]: it never
 * depends on generated code.
 */
sealed interface Commit<out I, out R> {
    /** The input value the caller committed, exactly as it was handed in. */
    val input: I
    /** [ref] names the record the act became: a `Ref<Shape>` for a durable act, null for a transient one.
     *  [afterCommitRejections] and [afterCommitFailures] are what the act's `after commit` firings came to:
     *  refused by a `never`, or failed for another reason; the act stands either way (Reference §21; `evaluation.md`). */
    data class Accepted<out I, out R>(
        override val input: I,
        val ref: R,
        val afterCommitRejections: List<Rejection> = emptyList(),
        val afterCommitFailures: List<FiringFailure> = emptyList(),
    ) : Commit<I, R>
    /** [reason] is the rendered message; [rejection] is the same as data when a
     *  `never` refused the act (Reference §21), null for a type-level refusal. */
    data class Refused<out I, out R>(override val input: I, val reason: String, val rejection: Rejection? = null) : Commit<I, R>
}

/** Attach the typed input to the runtime's untyped verdict, with the reference to the durable record it made. */
fun <I, T> CommitResult.carrying(input: I): Commit<I, Ref<T>> = when (this) {
    is CommitResult.Accepted -> Commit.Accepted(input, ref ?: throw VelleRuntimeError("a durable act's acceptance carries no reference"), afterCommitRejections, afterCommitFailures)
    is CommitResult.Refused -> Commit.Refused(input, reason, rejection)
}

/** Attach the typed input to the runtime's untyped verdict for a transient act, which leaves no record to refer to. */
fun <I> CommitResult.carryingNoRecord(input: I): Commit<I, Nothing?> = when (this) {
    is CommitResult.Accepted -> Commit.Accepted(input, null, afterCommitRejections, afterCommitFailures)
    is CommitResult.Refused -> Commit.Refused(input, reason, rejection)
}

open class VelleRuntimeError(message: String) : Exception(message)

/**
 * A rule firing that fails on the books rather than the machinery (Reference §10, §14): a
 * `from` block that would store an absence in a required field, or a selection that does not
 * single out a winner. Inside an act's transaction the act comes back refused naming the
 * [rule], which [RuleFiring.fire] fills in on the way out; behind an `after commit` boundary
 * or in a tick it is a [FiringFailure] like any other.
 */
class FiringFailed(message: String, val rule: String? = null) : VelleRuntimeError(message)

/** A `never` refused the envelope's final state: carries the [rejection] out of the envelope to whoever reports it. */
internal class NeverViolation(val rejection: Rejection) : Exception(rejection.message)
