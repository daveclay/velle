package velle

/** The commit set one envelope hands the store, and the handles of the records it creates, in the same order as the set's creations. */
internal class PendingCommit(val commitSet: CommitSet, val createdHandles: List<Long>)

/**
 * The transaction's whole mutation set — creates with their final fields
 * (initially/generator/timestamp values included) in creation order, so
 * pending refs point backward; assigns collapsed to the final value per
 * (instance, field). References cross as typed [Ref]s: persisted rows by
 * their store key, same-set creates as [Ref.Pending] indices. Transient
 * acts are excluded: only their consequences persist (Reference §4).
 */
internal class CommitSetBuilder(system: VelleSystem, private val transaction: Transaction) {
    private val model = system.model
    private val workingSet = system.workingSet
    private val pendingDeleted = workingSet.pendingDeleted

    // created-then-deleted inside one envelope nets to nothing (OQ37)
    private val createdHandles = transaction.created.filter { handle ->
        handle !in pendingDeleted && workingSet.instances.getValue(handle).shape !in model.transients
    }
    private val creationIndexOf = createdHandles.withIndex().associate { (index, handle) -> handle to index }

    fun build(): PendingCommit {
        val creations = creations()
        val assignments = assignments()
        val (captured, retracted) = captureChannel()
        return PendingCommit(CommitSet(creations, assignments, captured, retracted, deletions()), createdHandles)
    }

    private fun creations(): List<CommitSet.Creation> = createdHandles.map { handle ->
        val instance = workingSet.instances.getValue(handle)
        val decl = model.shapes.getValue(instance.shape)
        val fields = buildMap {
            for (member in decl.members) {
                val name = storedMemberName(member) ?: continue
                put(name, storeValue(instance.fields[name] ?: Value.VNone))
            }
        }
        CommitSet.Creation(instance.shape, fields)
    }

    /** The name a record's row stores [member] under; null for a member the row does not hold. */
    private fun storedMemberName(member: Member): String? = when (member) {
        // a transient field is in the act's input and not in its record (Reference §4)
        is StoredProp -> if (member.transient) null else member.name
        is TimestampProp -> member.name
        is ActorProp -> member.name
        else -> null
    }

    private fun assignments(): List<CommitSet.Assign> {
        val createdSet = transaction.created.toSet()
        return transaction.priorFieldValues.asSequence()
            .map { (id, field, _) -> id to field }
            .distinct()
            .filter { (id, _) -> id !in createdSet && id !in pendingDeleted }
            .map { (id, field) ->
                val instance = workingSet.instances.getValue(id)
                val target = refOf(id) as? Ref.Persisted
                    ?: throw VelleRuntimeError("assign targets unpersisted instance $id")
                CommitSet.Assign(target, field, storeValue(instance.fields[field] ?: Value.VNone))
            }
            .toList()
    }

    /**
     * The capture channel: net end-state per touched membership — present at
     * envelope close is an upsert, absent a retraction (delete); a
     * create-then-exit inside one envelope persisted nothing and nets out.
     */
    private fun captureChannel(): Pair<List<CommitSet.Capture>, List<CommitSet.Retraction>> {
        val captured = mutableListOf<CommitSet.Capture>()
        val retracted = mutableListOf<CommitSet.Retraction>()
        for ((id, refinement) in transaction.priorCaptures.map { (membership, _) -> membership }.distinct()) {
            val base = model.baseOf(refinement) ?: continue
            if (base in model.transients) continue
            // a deleted instance's touched memberships net to retraction; its
            // untouched capture rows go with the row itself (the Deletion op)
            val values = if (id in pendingDeleted) null else workingSet.captures[id to refinement]
            if (values != null) {
                captured.add(CommitSet.Capture(refOf(id), refinement, values.mapValues { (_, value) -> storeValue(value) }))
                continue
            }
            val key = workingSet.keyOf[id] ?: continue
            retracted.add(CommitSet.Retraction(Ref.Persisted(workingSet.idShape.getValue(id), key), refinement))
        }
        return captured to retracted
    }

    /** The deletions (OQ37): persisted rows this transaction removed; a created-then-deleted instance has no key and appears nowhere. */
    private fun deletions(): List<CommitSet.Deletion> = transaction.deleted.distinct().mapNotNull { id ->
        workingSet.keyOf[id]?.let { key -> CommitSet.Deletion(Ref.Persisted(workingSet.idShape.getValue(id), key)) }
    }

    private fun refOf(handle: Long): RowRef {
        workingSet.keyOf[handle]?.let { key -> return Ref.Persisted(workingSet.idShape.getValue(handle), key) }
        creationIndexOf[handle]?.let { index -> return Ref.Pending(workingSet.idShape.getValue(handle), index) }
        throw VelleRuntimeError("reference to unpersisted instance $handle escapes the commit set")
    }

    private fun storeValue(value: Value): Any? = when (value) {
        is Value.VRef -> refOf(value.id)
        is Value.VColl -> value.ids.map { handle -> refOf(handle) }
        is Value.VVals -> value.values.map { element -> unwrapped(element) }
        else -> unwrapped(value)
    }
}
