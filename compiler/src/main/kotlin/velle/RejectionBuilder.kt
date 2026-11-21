package velle

/**
 * Everything in hand at a refusal, as data (`evaluation.md`, "Errors, refusals, and
 * retry"): the prohibition is re-evaluated over the [violating] records with the
 * evaluator's trace on, and the records the trace touched are snapshotted. The act's
 * referenced records that the trace read through are the pivots — what the person was
 * acting on.
 *
 * A refusal is handed back through the same surface a read is (Reference §22, "The
 * actor"; OQ33 §5.10): the records it carries are projected for the actor as a read
 * would project them — invisible records dropped, hidden fields absent — while the act
 * itself, which the actor committed, stays whole.
 */
internal class RejectionBuilder(
    private val system: VelleSystem,
    private val transaction: Transaction,
    /** The prohibition's position among the spec's `never`s, counting from 1. */
    private val ordinal: Int,
    private val never: NeverDecl,
    private val watcher: ConditionWatcher,
    private val violating: List<Long>,
) {
    private val workingSet = system.workingSet

    /** The rejection names records by key; the visibility filter needs the handle back. */
    private val handleOfRecord = HashMap<RecordRef, Long>()

    // read after the trace, as the records stand once the prohibition is re-evaluated
    private val actId by lazy { transaction.act?.takeIf(::present) }
    private val subjectId by lazy { transaction.firing?.second?.takeIf(::present) }
    private val produced by lazy { transaction.created.filter { id -> id != actId && present(id) } }
    private val actor = transaction.actor?.takeIf { system.model.hasAuthorization }

    fun build(): Rejection {
        val steps = traceOfViolations()
        val touched = recordsTouched(steps)
        val originRefs = originReferences()
        val pivots = touched.filter { id -> id in originRefs && id !in violating && id != subjectId && id !in produced }
        val witnesses = touched.filter { id ->
            id != actId && id != subjectId && id !in produced && id !in violating && id !in pivots
        }
        val evaluation = steps.distinct().mapNotNull(::evalStepOf)
        return Rejection(
            never = ordinal,
            prohibition = Printer.refExpr(never.target),
            declaration = Printer.prohibition(never),
            requirement = never.requirement?.let { requirement -> Printer.expr(requirement.predicate) },
            key = never.keyDeclaration,
            targetShape = watcher.bases.joinToString(" or "),
            act = actId?.let(::snapshot),
            rule = transaction.firing?.first?.name,
            schedule = transaction.schedule,
            subject = subjectId?.takeIf(::visible)?.let(::project),
            produced = produced.filter(::visible).map(::project),
            violating = violating.filter { id -> id == actId || visible(id) }.map(::project),
            pivots = pivots.filter(::visible).map(::project),
            witnesses = witnesses.filter(::visible).map(::project),
            evaluation = shownToActor(evaluation),
            today = if (watcher.summary.readsTime) system.effectiveToday.toString() else null,
        )
    }

    private fun present(id: Long): Boolean = workingSet.instance(id) != null

    private fun traceOfViolations(): List<Evaluator.Trace> {
        val steps = mutableListOf<Evaluator.Trace>()
        val evaluator = system.evaluator
        evaluator.trace = steps
        try {
            for (id in violating) evaluator.memberOfRefExpr(id, never.target)
        } finally {
            evaluator.trace = null
        }
        return steps
    }

    /** What the transaction was about: the act's references, or the firing's subject, its references, and the references of what it produced. */
    private fun originReferences(): Set<Long> =
        (listOfNotNull(actId, subjectId) + produced)
            .flatMap { id -> workingSet.instance(id)!!.fields.values.flatMap(::referencedHandles) }
            .toSet() + listOfNotNull(subjectId)

    private fun recordsTouched(steps: List<Evaluator.Trace>): List<Long> = steps.flatMap { step ->
        when (step) {
            is Evaluator.Trace.Read -> listOf(step.id) + referencedHandles(step.value)
            is Evaluator.Trace.Membership -> listOf(step.id)
        }
    }.distinct().filter(::present)

    private fun evalStepOf(step: Evaluator.Trace): EvalStep? = when (step) {
        is Evaluator.Trace.Read -> if (present(step.id)) EvalStep.Read(recordOf(step.id), step.member, plain(step.value)) else null
        is Evaluator.Trace.Membership -> if (present(step.id)) EvalStep.Membership(recordOf(step.id), step.refinement, step.member) else null
    }

    private fun recordOf(id: Long): RecordRef {
        val ref = workingSet.refFor(id)
        return RecordRef(ref.shape, ref.key).also { record -> handleOfRecord[record] = id }
    }

    private fun plain(value: Value): Any? = when (value) {
        is Value.VNum -> value.v
        is Value.VText -> value.v
        is Value.VBool -> value.v
        is Value.VDate -> value.v
        is Value.VDateTime -> value.v
        is Value.VUuid -> value.v
        is Value.VTime -> value.v
        is Value.VBytes -> value.v
        is Value.VRef -> if (present(value.id)) recordOf(value.id) else null
        is Value.VColl -> value.ids.filter(::present).map(::recordOf)
        is Value.VVals -> value.values.map(::plain)
        is Value.VDuration -> "${value.amount} ${value.unit}"
        is Value.VExt -> value.v // rendered through the type's `display` by Rejection.show
        Value.VEmpty -> emptyList<Any?>()
        Value.VNone, is Value.VPendingRef, is Value.VPendingColl -> null
    }

    private fun snapshot(id: Long): RecordSnapshot {
        val fields = workingSet.instance(id)!!.fields
            .filterValues { value -> value !is Value.VPendingRef && value !is Value.VPendingColl }
            .mapValues { (_, value) -> plain(value) }
        return RecordSnapshot(recordOf(id), fields)
    }

    private fun visible(id: Long): Boolean = actor == null || system.visibleTo(actor, id)

    private fun project(id: Long): RecordSnapshot {
        val whole = snapshot(id)
        if (actor == null || id == actId) return whole
        val hidden = system.hiddenFields(actor, id)
        return if (hidden.isEmpty()) whole else RecordSnapshot(whole.ref, whole.fields.filterKeys { field -> field !in hidden })
    }

    private fun shownToActor(evaluation: List<EvalStep>): List<EvalStep> {
        if (actor == null) return evaluation
        return evaluation.filter { step ->
            when (step) {
                is EvalStep.Read -> handleOfRecord.getValue(step.record).let { id -> id == actId || (visible(id) && step.member !in system.hiddenFields(actor, id)) }
                is EvalStep.Membership -> handleOfRecord.getValue(step.record).let { id -> id == actId || visible(id) }
            }
        }
    }
}
