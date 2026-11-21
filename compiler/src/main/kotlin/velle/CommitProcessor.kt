package velle

import java.util.UUID

/**
 * One commit (evaluation.md, "Processing one commit"): its mutations applied to the
 * working set, the watched conditions' member sets compared before and after, the
 * captures of entrants recorded, and the rules the change fires pinned and run.
 */
internal class CommitProcessor(private val system: VelleSystem) {
    private val model get() = system.model
    private val workingSet get() = system.workingSet
    private val evaluator get() = system.evaluator
    private val watchers get() = system.watchers

    /** Apply [mutations] as one commit of the open transaction; returns the handles of the records it created, in order. */
    fun apply(mutations: List<Mutation>, actCommit: Boolean = false): List<Long> {
        val transaction = system.session.transaction ?: error("commit outside transaction")
        if (transaction.cascadeDepth++ > MAX_CASCADE_DEPTH)
            throw VelleRuntimeError("cascade depth exceeded $MAX_CASCADE_DEPTH — quiescence backstop [S3]")

        val footprint = watchers.footprintOf(mutations)
        transaction.footprint.addAll(footprint)
        // only watchers this commit's footprint can affect get their member
        // sets evaluated — for the rest, no membership can have flipped
        val active = watchers.commitWatchers.filter { watcher -> watcher.canChangeUnder(footprint) }
        val before = active.associateWith { watcher -> watchers.membersOf(watcher) }

        val createdIds = applyMutations(mutations, transaction, before)

        if (actCommit) {
            // the door (Reference §21; evaluation.md "An exposed call", step 1): the
            // act's closure is in, nothing is processed, and the prohibitions no
            // rule can move are evaluated here — over this commit's footprint
            // alone, with the act named — and not again at the end. A refusal
            // reaches the caller before any rule reads the new state.
            transaction.act = createdIds.first()
            system.prohibitions.checkAtDoor(transaction, footprint)
        }

        val change = MembershipChange(before, active.associateWith { watcher -> watchers.membersOf(watcher) })
        val retractions = captureEntrants(active, change, transaction)
        val deletedNow = mutations.filterIsInstance<Mutation.Delete>().map { deletion -> deletion.id }.toSet()
        val pending = pinFirings(active, change, deletedNow, createdIds, transaction)
        for ((rule, subject) in system.ruleFiring.ordered(pending)) system.ruleFiring.fire(rule, subject)

        // close of the commit: leaver captures retract (exit rules were their last readers)
        retractions.forEach { membership -> setCapture(membership, null, transaction) }
        return createdIds
    }

    private fun applyMutations(mutations: List<Mutation>, transaction: Transaction, before: Map<ConditionWatcher, Set<Long>>): List<Long> {
        val createdIds = mutableListOf<Long>()
        val wroteStored = mutableSetOf<Long>()
        for (mutation in mutations) when (mutation) {
            is Mutation.Create -> createdIds.add(applyCreate(landed(mutation, createdIds), transaction))
            is Mutation.Assign -> {
                applyAssign(mutation, transaction, before)
                wroteStored.add(mutation.id)
            }
            is Mutation.Delete -> applyDelete(mutation, transaction)
        }
        // `on update` timestamps advance at every commit writing a stored field
        for (id in wroteStored) {
            val instance = workingSet.instances[id] ?: continue
            model.updateTimestampsOf(instance.shape).forEach { timestamp -> setField(instance, timestamp.name, Value.VDateTime(system.now), transaction) }
        }
        return createdIds
    }

    /**
     * A closure part's back-reference lands here: pending index → the
     * already-created container's id (Reference §6, "Inline part creation" —
     * materialization order is topological, never observable).
     */
    private fun landed(creation: Mutation.Create, createdIds: List<Long>): Mutation.Create {
        if (creation.fields.values.none { value -> value is Value.VPendingRef || value is Value.VPendingColl }) return creation
        return creation.copy(fields = creation.fields.mapValues { (_, value) -> landedValue(value, createdIds) })
    }

    private fun landedValue(value: Value, createdIds: List<Long>): Value = when (value) {
        is Value.VPendingRef -> Value.VRef(createdIds[value.index])
        is Value.VPendingColl -> Value.VColl(value.items.map { item -> (landedValue(item, createdIds) as Value.VRef).id }, value.shape)
        else -> value
    }

    /** Captures: evaluated at entry; leavers are returned, to be retracted at the commit's close. */
    private fun captureEntrants(active: List<ConditionWatcher>, change: MembershipChange, transaction: Transaction): List<Pair<Long, String>> {
        val retractions = mutableListOf<Pair<Long, String>>()
        for (watcher in active) {
            val refinement = Watchers.capturedRefinementOf(watcher) ?: continue
            val capturedProps = model.refinements.getValue(refinement).members
                .filterIsInstance<DerivedProp>().filter { prop -> prop.captured }
            for (id in change.entered(watcher)) {
                val values = capturedProps.associate { prop -> prop.name to evaluator.evalMember(id, refinement, prop) }
                setCapture(id to refinement, values, transaction)
            }
            for (id in change.left(watcher)) retractions.add(id to refinement)
        }
        return retractions
    }

    /**
     * Firings: entrants for `when R`, leavers for the exit forms — the
     * whole firing set is pinned from this commit's pre/post diff before
     * any sibling runs (evaluation.md step 5), then executed in an order
     * the spec must not observe (step 6, [S3]). An `after commit` firing is
     * queued on the transaction instead, and the rest are returned.
     */
    private fun pinFirings(
        active: List<ConditionWatcher>,
        change: MembershipChange,
        deletedNow: Set<Long>,
        createdIds: List<Long>,
        transaction: Transaction,
    ): List<Firing> {
        val pending = mutableListOf<Firing>()
        for (rule in model.rules.values) {
            if (!rule.firesOnCommit()) continue
            val watcher = watchers.ofRule(rule) ?: continue
            if (watcher !in active) continue // this commit provably cannot flip its condition
            var subjects = subjectsOf(rule, change, watcher, deletedNow)
            // a transient act's partitions are decided exactly once, at its
            // creation commit — consequence commits within the transaction
            // never re-partition it (Reference §4, "Transient acts")
            if (watcher.bases.any { base -> base in model.transients }) subjects = subjects intersect createdIds.toSet()
            for (subject in subjects) {
                if (rule.preposition == "after") transaction.afterQueue.add(rule to subject)
                else pending.add(rule to subject)
            }
        }
        return pending
    }

    /**
     * The records [rule] fires for after this commit. A leaver left either
     * because its predicate flipped or because this commit deleted it —
     * different exit causes, and each rule says which it reacts to
     * (Reference §13, "Deletion as a second exit cause"): bare `when leaving`
     * never fires at deletion.
     */
    private fun subjectsOf(rule: RuleDecl, change: MembershipChange, watcher: ConditionWatcher, deletedNow: Set<Long>): Set<Long> {
        if (!rule.leaving) return change.entered(watcher)
        val gone = change.left(watcher)
        return buildSet {
            if (rule.trigger.onPredicateExit) addAll(gone - deletedNow)
            if (rule.trigger.onDeletion) addAll(gone intersect deletedNow)
        }
    }

    // ── creating a record ────────────────────────────────────────────────────

    fun applyCreate(creation: Mutation.Create, transaction: Transaction): Long {
        val decl = model.shapes[creation.shape] ?: throw VelleRuntimeError("unknown shape '${creation.shape}'")
        val id = workingSet.mintHandle()
        val instance = Instance(id, creation.shape, id, creation.fields.toMutableMap())
        workingSet.instances[id] = instance
        workingSet.idShape[id] = creation.shape
        transaction.created.add(id)
        val suppliedNames = instance.fields.keys.toSet()
        fitStoredFields(decl, instance, suppliedNames)
        computeStartingValues(instance, suppliedNames)
        // the record joins the records of its shape once its starting values are computed: an
        // `initially` reads the records that existed before this one (Reference §5)
        workingSet.byShape.getOrPut(creation.shape) { mutableListOf() }.add(id)
        for (timestamp in decl.members.filterIsInstance<TimestampProp>())
            instance.fields[timestamp.name] = Value.VDateTime(system.now) // create and update both start here
        return id
    }

    /** Each supplied stored field fit to its declared type; an unsupplied `many` starts empty — the empty collection is the absence (Reference §6). */
    private fun fitStoredFields(decl: ShapeDecl, instance: Instance, suppliedNames: Set<String>) {
        for (prop in decl.members.filterIsInstance<StoredProp>()) {
            if (prop.name in suppliedNames) instance.fields[prop.name] = coerceCollection(instance.fields.getValue(prop.name), prop.type)
            else if (prop.initially == null) emptyCollectionOf(prop.type)?.let { empty -> instance.fields[prop.name] = empty }
        }
    }

    /** Each `initially` after the ones it reads, wherever they are declared (Reference §5). */
    private fun computeStartingValues(instance: Instance, suppliedNames: Set<String>) {
        for (prop in model.startingValues.orderOf(instance.shape)) {
            val initially = prop.initially ?: continue
            if (prop.name in suppliedNames) continue
            instance.fields[prop.name] =
                if (initially == PathExpr("randomUUID")) randomUuidFor(prop)
                else startingValue(prop, initially, instance.shape, instance.id)
        }
    }

    /** A `uuid` field gets the value; a `text` field its canonical spelling (Reference §5). */
    private fun randomUuidFor(prop: StoredProp): Value {
        val uuid = UUID.randomUUID()
        return if ((prop.type as? ScalarType)?.name == "uuid") Value.VUuid(uuid) else Value.VText(uuid.toString())
    }

    /** What a field's `initially` computes for a record being created. A required field cannot
     *  start absent (Reference §5, §14): the compiler refuses every spelling known to, and the
     *  firing fails here rather than keeping a record with a hole in it. */
    private fun startingValue(field: StoredProp, initially: Expr, shape: String, recordId: Long): Value {
        val computed = evaluator.eval(initially, Evaluator.Ctx(shape, recordId))
        if (computed == Value.VNone && !field.takesAbsence())
            throw FiringFailed("'$shape.${field.name}' is required and its 'initially', '${Printer.expr(initially)}', is absent (Reference §14)")
        return coerceCollection(computed, field.type)
    }

    // ── writing and deleting ─────────────────────────────────────────────────

    private fun applyAssign(assignment: Mutation.Assign, transaction: Transaction, before: Map<ConditionWatcher, Set<Long>>) {
        val instance = workingSet.instance(assignment.id) ?: throw VelleRuntimeError("assign to missing instance ${assignment.id}")
        checkNotFrozen(assignment, instance, before)
        setField(instance, assignment.field, assignment.value, transaction)
    }

    /** Frozen-field tripwire: the validator proved this impossible; assert anyway [S5 spirit]. */
    private fun checkNotFrozen(assignment: Mutation.Assign, instance: Instance, before: Map<ConditionWatcher, Set<Long>>) {
        for ((refinementName, refinement) in model.refinements) {
            if (model.baseOf(refinementName) != instance.shape) continue
            val frozenFields = frozenFieldsOf(refinement, instance.shape) ?: continue
            if (assignment.field !in frozenFields) continue
            if (wasMemberBefore(assignment.id, refinementName, before))
                throw VelleRuntimeError("write to ${instance.shape}.${assignment.field}, frozen by '$refinementName' — validator gap")
        }
    }

    /** The fields [refinement] freezes on a record of [shape]: those it names, or every stored field for a bare `frozen`; null when it freezes none. */
    private fun frozenFieldsOf(refinement: RefinementDecl, shape: String): List<String>? {
        val frozen = refinement.members.filterIsInstance<FrozenClause>().singleOrNull() ?: return null
        return frozen.fields.ifEmpty {
            model.shapes[shape]?.members?.filterIsInstance<StoredProp>()?.map { prop -> prop.name } ?: emptyList()
        }
    }

    /** Whether [id] was a member of [refinement] before this commit: from the member set taken then when one was, otherwise evaluated now. */
    private fun wasMemberBefore(id: Long, refinement: String, before: Map<ConditionWatcher, Set<Long>>): Boolean =
        watchers.ofCapture(refinement)?.let { watcher -> before[watcher]?.contains(id) }
            ?: evaluator.memberOfRefExpr(id, RefName(refinement))

    private fun setField(instance: Instance, field: String, value: Value, transaction: Transaction) {
        transaction.priorFieldValues.add(Triple(instance.id, field, instance.fields[field]))
        instance.fields[field] = value
    }

    /** The one writer of the capture map inside a transaction. */
    private fun setCapture(membership: Pair<Long, String>, values: Map<String, Value>?, transaction: Transaction) {
        val captures = workingSet.captures
        transaction.priorCaptures.add(membership to captures[membership])
        if (values == null) captures.remove(membership) else captures[membership] = values
        workingSet.captureMisses.remove(membership)
    }

    /** The deleting commit (OQ37): the instance leaves the state's member sets
     *  now — post-state membership excludes it, exit rules fire as its last
     *  readers — and is physically removed at transaction close. */
    private fun applyDelete(deletion: Mutation.Delete, transaction: Transaction) {
        val instance = workingSet.instance(deletion.id) ?: throw VelleRuntimeError("delete of missing instance ${deletion.id}")
        if (instance.shape in model.transients)
            throw VelleRuntimeError("delete of transient act '${instance.shape}' — validator gap (V23)")
        checkDeletable(deletion, instance)
        if (workingSet.pendingDeleted.add(deletion.id)) transaction.deleted.add(deletion.id)
    }

    /** Undeletable tripwire: the validator proved this impossible; assert anyway [S5 spirit]. */
    private fun checkDeletable(deletion: Mutation.Delete, instance: Instance) {
        for ((refinementName, refinement) in model.refinements) {
            if (refinement.members.none { member -> member is UndeletableClause }) continue
            if (model.baseOf(refinementName) != instance.shape) continue
            if (deletion.id !in workingSet.pendingDeleted && evaluator.memberOfRefExpr(deletion.id, RefName(refinementName)))
                throw VelleRuntimeError("delete of ${instance.shape} ${deletion.id}, undeletable in '$refinementName' — validator gap")
        }
    }

    /** Fit a value to its declared field type: type the bare `empty` literal, dedupe a
     *  `many` (a set; one that `tolerates duplication` keeps its duplicates and its
     *  order, Reference §5), and read a number or text constant as the field's
     *  extension type (Reference §5; OQ65 D2). Everything else passes through. */
    fun coerceCollection(value: Value, type: TypeRef): Value {
        if (value == Value.VEmpty) return emptyCollectionOf(type) ?: value
        return when {
            value is Value.VColl -> Value.VColl(value.ids.distinct(), value.shape)
            value is Value.VVals -> coerceValues(value, type)
            type is ScalarType && !type.many -> evaluator.coerceLiteral(value, model.typeScope[type.name])
            else -> value
        }
    }

    private fun coerceValues(collection: Value.VVals, type: TypeRef): Value {
        val scalarType = type as? ScalarType
        val ordered = scalarType?.ordered == true
        val values = if (ordered) collection.values else collection.values.distinct()
        val coerced = values.map { element -> scalarType?.let { declared -> evaluator.coerceLiteral(element, model.typeScope[declared.name]) } ?: element }
        return Value.VVals(coerced, ordered)
    }

    /** One commit's member sets of the watched conditions, before its mutations and after them. */
    private class MembershipChange(
        private val before: Map<ConditionWatcher, Set<Long>>,
        private val after: Map<ConditionWatcher, Set<Long>>,
    ) {
        fun entered(watcher: ConditionWatcher): Set<Long> = after.getValue(watcher) - before.getValue(watcher)
        fun left(watcher: ConditionWatcher): Set<Long> = before.getValue(watcher) - after.getValue(watcher)
    }

    private companion object {
        /** [S3] backstop behind the static quiescence proof. */
        const val MAX_CASCADE_DEPTH = 500
    }
}
