package velle

/**
 * One envelope (evaluation.md, "The universal transaction") and its close. Over a store
 * an envelope is one write unit of work ([StateResolver.begin]): any read unit open in
 * this scope is closed first, the write unit opens, the envelope's queue keys are valued
 * against pre-commit state and handed to the store ([UnitOfWork.serialize]), the working
 * set is dropped so everything the envelope evaluates is read after the locks were taken,
 * and the unit ends with the outcome once the commit callback has run, or on the way out
 * of a refusal or a failure.
 */
internal class Envelope(private val system: VelleSystem) {
    private val workingSet get() = system.workingSet
    private val store get() = system.store
    private val session get() = system.session

    /**
     * Run [body] as one envelope serialized on [keys]. [actor] is the root act's committer;
     * [onAfter] hears the transaction once it and its after-commit firings are over. A
     * failure, a `never`'s refusal included, rolls the whole envelope back and comes back
     * as the result's exception.
     */
    fun <T> run(
        actor: Long? = null,
        keys: () -> Set<AdmissionKey> = { emptySet() },
        onAfter: (Transaction) -> Unit = {},
        body: (Transaction) -> T,
    ): Result<T> {
        val (writeUnit, transaction) = open(actor)
        return try {
            writeUnit.serialize(keys())
            workingSet.beginSnapshot() // the keys were valued against pre-lock state; the envelope reads post-lock state
            val outcome = body(transaction)
            system.prohibitions.checkAtClose(transaction)
            // the commit callback runs inside the envelope: its writes join the
            // engineer's storage transaction, and a failure rolls the whole
            // commit back (evaluation.md U2, in-envelope failure).
            writeCommitSet(transaction)
            close(Outcome.Committed)
            // deletions land now: the store already saw them in the commit set;
            // after-commit firings are separate transactions and never see the
            // instance (OQ37 — the transaction close is the removal)
            finalizeDeletes(transaction)
            drainAfterQueue(transaction)
            onAfter(transaction)
            Result.success(outcome)
        } catch (failure: Exception) {
            abandon(transaction, if (failure is NeverViolation) Outcome.Refused else Outcome.Failed(failure))
            Result.failure(failure)
        }
    }

    /**
     * Write one record of [shape] outside the trust boundary ([VelleSystem.seed]): its own
     * envelope, in which no rule fires and no prohibition is checked. A seed bypasses the
     * rules, so no domain was derived for it: it takes the shape's width.
     */
    fun seed(shape: String, fields: Map<String, Value>): Long {
        val (writeUnit, transaction) = open(actor = null)
        try {
            writeUnit.serialize(setOf(AdmissionKey.Shape(shape)))
            workingSet.beginSnapshot()
            val id = system.commitProcessor.applyCreate(Mutation.Create(shape, fields), transaction)
            writeCommitSet(transaction)
            close(Outcome.Committed)
            workingSet.beginSnapshot() // the seeded row is the store's now; a read of it starts there
            return id
        } catch (failure: Exception) {
            abandon(transaction, Outcome.Failed(failure))
            throw failure
        }
    }

    private fun open(actor: Long?): Pair<UnitOfWork, Transaction> {
        check(session.transaction == null) { "nested harness transaction" }
        workingSet.beginSnapshot()
        val writeUnit = store.openWriteUnit()
        val transaction = Transaction(actor)
        session.transaction = transaction
        return writeUnit to transaction
    }

    private fun close(outcome: Outcome) {
        session.transaction = null
        store.endUnit(outcome)
    }

    private fun abandon(transaction: Transaction, outcome: Outcome) {
        rollback(transaction)
        close(outcome)
    }

    /** The store assigns row identity and reports it back: the returned keys bind this session's handles to storage. */
    private fun writeCommitSet(transaction: Transaction) {
        val pending = CommitSetBuilder(system, transaction).build()
        val commitSet = pending.commitSet
        if (commitSet.isEmpty()) return
        val keys = store.onCommit.onCommit(commitSet)
        if (keys.size != commitSet.created.size)
            throw VelleRuntimeError("commit callback returned ${keys.size} keys for ${commitSet.created.size} created rows")
        pending.createdHandles.forEachIndexed { index, handle -> workingSet.bindStoreKey(handle, keys[index]) }
    }

    private fun CommitSet.isEmpty(): Boolean =
        created.isEmpty() && assigned.isEmpty() && captured.isEmpty() && retracted.isEmpty() && deleted.isEmpty()

    /** Undo the transaction in the working set, newest change first: its deletion marks, its field writes, its captures, its creations. */
    private fun rollback(transaction: Transaction) {
        workingSet.pendingDeleted.removeAll(transaction.deleted.toSet()) // deletions were only marks until close
        restoreFields(transaction)
        restoreCaptures(transaction)
        removeCreated(transaction)
    }

    private fun restoreFields(transaction: Transaction) {
        for ((id, field, prior) in transaction.priorFieldValues.asReversed()) {
            val fields = workingSet.instances[id]?.fields ?: continue
            if (prior == null) fields.remove(field) else fields[field] = prior
        }
    }

    private fun restoreCaptures(transaction: Transaction) {
        val captures = workingSet.captures
        for ((membership, prior) in transaction.priorCaptures.asReversed()) {
            if (prior == null) captures.remove(membership) else captures[membership] = prior
        }
    }

    private fun removeCreated(transaction: Transaction) {
        for (id in transaction.created.asReversed()) {
            val instance = workingSet.instances.remove(id) ?: continue
            workingSet.byShape[instance.shape]?.remove(id)
            workingSet.captures.keys.removeIf { (capturedId, _) -> capturedId == id }
        }
    }

    /** Physical removal at the deleting transaction's close: after the commit
     *  callback (the store saw the deletions), before the after-queue drains
     *  (its firings are separate transactions and must not see the instance). */
    private fun finalizeDeletes(transaction: Transaction) {
        for (id in transaction.deleted) {
            val instance = workingSet.instances.remove(id) ?: continue
            workingSet.byShape[instance.shape]?.remove(id)
            workingSet.captures.keys.removeIf { (capturedId, _) -> capturedId == id }
            workingSet.keyOf.remove(id)?.let { key -> workingSet.handleOf.remove(Ref.Persisted(instance.shape, key)) }
            workingSet.idShape.remove(id)
            workingSet.pendingDeleted.remove(id)
        }
    }

    /** [S2] synchronous, FIFO, each entry its own transaction. */
    private fun drainAfterQueue(transaction: Transaction) {
        val evaluator = system.evaluator
        for ((rule, subject) in system.ruleFiring.ordered(transaction.afterQueue)) {
            if (workingSet.instance(subject) == null) continue
            if (!evaluator.memberOfRefExpr(subject, rule.condition)) continue
            // what this firing's own after-commit firings came to bubbles up to the root envelope
            val result = run(
                keys = { system.queueKeys.ofFiring(rule, subject) },
                onAfter = { inner ->
                    transaction.afterRejections += inner.afterRejections
                    transaction.afterFailures += inner.afterFailures
                },
            ) { firingTransaction ->
                firingTransaction.firing = rule to subject
                // under the locks, against post-lock state, as a tick's firing is (OQ71 D1)
                if (evaluator.memberOfRefExpr(subject, rule.condition)) system.ruleFiring.fire(rule, subject)
            }
            when (val failure = result.exceptionOrNull()) {
                null -> {}
                is NeverViolation -> transaction.afterRejections.add(failure.rejection)
                else -> transaction.afterFailures.add(system.ruleFiring.failureOf(rule, subject, failure))
            }
        }
    }
}
