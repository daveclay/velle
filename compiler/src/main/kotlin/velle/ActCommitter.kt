package velle

/**
 * The two ways a record enters the state from outside: an act committed through its
 * door, and a record seeded outside the trust boundary.
 */
internal class ActCommitter(private val system: VelleSystem) {

    /** An act of [shape] committed by [actor]: its input read at the boundary, then one envelope. */
    fun commit(
        shape: String,
        suppliedFields: Map<String, Any?>,
        parts: Map<String, List<Map<String, Any?>>>,
        actor: Long?,
    ): CommitResult {
        val prepared = try {
            system.actInput.prepare(shape, suppliedFields, parts, actor)
        } catch (refusal: InputRefusal) {
            return CommitResult.Refused(refusal.reason)
        }
        var afterRejections: List<Rejection> = emptyList()
        var afterFailures: List<FiringFailure> = emptyList()
        val result = system.envelope.run(
            actor,
            keys = { system.queueKeys.ofAct(shape, prepared.mutations, prepared.rootIndex) },
            onAfter = { transaction ->
                afterRejections = transaction.afterRejections.toList()
                afterFailures = transaction.afterFailures.toList()
            },
        ) {
            system.commitProcessor.apply(prepared.mutations, actCommit = true)[prepared.rootIndex]
        }
        // the envelope and its after-commit firings are over: what they built in
        // memory goes, so any read from here on starts from the store. A transient
        // act goes with it, which is its removal (Reference §4 "Transient acts";
        // evaluation.md): V17 bans every read of the act from anything that runs
        // later, so removing it here is observably the same as at its close.
        system.workingSet.beginSnapshot()
        return result.fold(
            onSuccess = { id -> accepted(shape, id, afterRejections, afterFailures) },
            onFailure = { failure -> refused(failure) },
        )
    }

    /**
     * The act stands and is durable; what its after-commit firings came to is told
     * to the listeners now, since the caller's code may read it and nobody else will.
     */
    private fun accepted(shape: String, id: Long, afterRejections: List<Rejection>, afterFailures: List<FiringFailure>): CommitResult {
        val ref = if (shape in system.model.transients) null else system.refFor(id)
        if (afterRejections.isNotEmpty() || afterFailures.isNotEmpty()) {
            val record = ref?.let { persisted -> RecordRef(persisted.shape, persisted.key) }
            system.tell { listener -> listener.afterCommit(record, afterRejections, afterFailures) }
        }
        return CommitResult.Accepted(id, ref, afterRejections, afterFailures)
    }

    private fun refused(failure: Throwable): CommitResult = when (failure) {
        is NeverViolation -> CommitResult.Refused(failure.rejection.message, failure.rejection)
        // a firing that failed on the books: the committer is told by the refusal (Reference §14)
        is FiringFailed -> CommitResult.Refused("rule '${failure.rule}' did not fire: ${failure.message}")
        else -> throw failure
    }

    /** A record of [shape] seeded outside the trust boundary ([VelleSystem.seed]); returns its handle. */
    fun seed(shape: String, fields: Map<String, Any?>): Long {
        val converted = system.actInput.prepareSeed(shape, fields)
        return system.envelope.seed(shape, converted)
    }
}
