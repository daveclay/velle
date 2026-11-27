package velle

import java.math.BigDecimal

/**
 * Firing a rule: its body run for one subject (Reference §14), the mutations it makes
 * gathered and applied as one commit, which may fire further rules in turn.
 */
internal class RuleFiring(private val system: VelleSystem) {
    private val model get() = system.model
    private val evaluator get() = system.evaluator
    private val workingSet get() = system.workingSet

    fun fire(rule: RuleDecl, subject: Long) {
        // a rule declared `by` reads the transaction's root actor (Reference §22):
        // present when the root act carried one, absent on the paths V31 made
        // the clause say `?` for
        val actor = rule.by?.let { by -> by.name to system.session.transaction?.actor }
        val context = Evaluator.Ctx(conditionScopeName(rule), subject, actor = actor)
        val mutations = mutableListOf<Mutation>()
        // a failure with no rule named is this firing's own, from its body or from a record it
        // creates; one raised by a rule its commit fired arrives already named
        try {
            execute(rule.body, context, subject, mutations)
            if (mutations.isNotEmpty()) system.commitProcessor.apply(mutations)
        } catch (failure: FiringFailed) {
            if (failure.rule != null) throw failure
            throw FiringFailed(failure.message!!, rule.name)
        }
    }

    /** A firing of [rule] for [subject] failed with [cause]: the record for the report. */
    fun failureOf(rule: RuleDecl, subject: Long, cause: Throwable): FiringFailure =
        FiringFailure(rule.name, workingSet.recordRefOf(subject), cause)

    /**
     * Test-only ordering strategy for the choices evaluation.md leaves open, applied
     * when [VelleSystem.firingOrder] is set; otherwise declaration order, one arbitrary
     * valid choice.
     */
    fun ordered(pending: List<Firing>): List<Firing> = system.firingOrder?.invoke(pending) ?: pending

    fun conditionScopeName(rule: RuleDecl): String {
        val condition = rule.condition
        return if (condition is RefName && (condition.name in model.shapes || condition.name in model.refinements)) condition.name
        else model.baseOfExpr(condition) ?: throw VelleRuntimeError("unresolvable condition for '${rule.name}'")
    }

    /**
     * Runs one level of a body: the rule's, or a `for each` block's once per
     * element (Reference §14). An `as` name bound here is visible to the rest
     * of this level and to the blocks inside it, never outside.
     */
    private fun execute(items: List<BodyItem>, start: Evaluator.Ctx, subject: Long, mutations: MutableList<Mutation>) {
        var context = start
        for (item in orderedItems(items)) when (item) {
            is ForEach -> iterate(item, 0, context, subject, mutations)
            is Assignment -> addAssignments(item, context, mutations)
            is Creation -> context = addCreation(item, context, mutations)
            is DeleteStmt -> mutations.add(Mutation.Delete(deleteTarget(item, context, subject)))
            ThenMarker -> {} // ordering within one commit is a compilation concern
        }
    }

    /** A `for each` block, nested for a product: a later source may read an earlier name. */
    private fun iterate(loop: ForEach, bindingIndex: Int, context: Evaluator.Ctx, subject: Long, mutations: MutableList<Mutation>) {
        if (bindingIndex == loop.bindings.size) {
            execute(loop.body, context, subject, mutations)
            return
        }
        val binding = loop.bindings[bindingIndex]
        for (element in loopValues(binding.source, context)) {
            iterate(loop, bindingIndex + 1, context.copy(locals = context.locals + (binding.name to element)), subject, mutations)
        }
    }

    /** A collection path fans out: one write per member, the same value
     *  (Reference §6, "fan-out assignment"); a filtered left side writes the members
     *  its filter holds of (Reference §12); a plain path is one target. */
    private fun addAssignments(assignment: Assignment, context: Evaluator.Ctx, mutations: MutableList<Mutation>) {
        val (targetIds, field) = filteredTargets(assignment, context) ?: evaluator.resolveTargets(assignment.target, context)
        val value = evaluator.eval(assignment.value, context)
        for (targetId in targetIds) {
            val shape = workingSet.instance(targetId)?.shape
            val declared = shape?.let { targetShape -> model.storedFieldTypeOf(targetShape, field) }
            val coerced = declared?.let { declaredType -> system.commitProcessor.coerceCollection(value, declaredType) } ?: value
            mutations.add(Mutation.Assign(targetId, field, coerced))
        }
    }

    /** The members a filtered left side chooses, read before the rule writes anything, and the field it writes; null for a plain path. */
    private fun filteredTargets(assignment: Assignment, context: Evaluator.Ctx): Pair<List<Long>, String>? {
        val collection = assignment.filteredCollection ?: return null
        val memberIds = evaluator.collectionValues(collection, context).map { member ->
            (member as? Value.VRef)?.id ?: throw VelleRuntimeError("'${Printer.assignmentTarget(assignment)}' chooses a value, not a record")
        }
        return memberIds to assignment.target.segs.last().name
    }

    /** Adds a creation's record to [mutations]; returns the context the rest of the level reads, with its `as` name bound. */
    private fun addCreation(creation: Creation, context: Evaluator.Ctx, mutations: MutableList<Mutation>): Evaluator.Ctx {
        val fields = mutableMapOf<String, Value>()
        creation.forExpr?.let { forExpr -> forField(creation, forExpr, context, mutations).let { (name, reference) -> fields[name] = reference } }
        for (field in creation.fields) {
            fields[field.name] = evaluator.evalFor(field.value, model.storedFieldTypeOf(creation.shape, field.name), context)
        }
        checkRequiredPresent(creation, fields)
        // `as name` (Reference §14): later statements reference this record by its
        // index in the creation list; the commit resolves the pending reference
        val aliased = creation.alias?.let { alias -> context.copy(pending = context.pending + (alias to mutations.size)) } ?: context
        mutations.add(Mutation.Create(creation.shape, fields))
        return aliased
    }

    /** The field a creation's `for` fills, and the reference it holds: the one `one` field whose type takes the target's shape. */
    private fun forField(creation: Creation, forExpr: Expr, context: Evaluator.Ctx, mutations: List<Mutation>): Pair<String, Value> {
        val reference = evaluator.eval(forExpr, context)
        // the target's shape: read off the instance, or, for a record this
        // firing creates (`as name`), off the creation that named it
        val target = when (reference) {
            is Value.VRef -> (workingSet.instance(reference.id) ?: throw VelleRuntimeError("missing instance ${reference.id}")).shape
            is Value.VPendingRef -> (mutations[reference.index] as Mutation.Create).shape
            else -> throw VelleRuntimeError("'for' target is not an instance")
        }
        val matched = model.shapes.getValue(creation.shape).members
            .filterIsInstance<StoredProp>()
            .single { prop -> (prop.type as? RelType)?.let { type -> !type.many && model.typeAccepts(type.shape, target) } == true }
        return matched.name to reference
    }

    /** A required field given an absent value: the selection that fed it matched
     *  nothing (Reference §10, §14). The firing fails rather than storing a record
     *  with a hole in it, as a selection that does not single out a winner does. */
    private fun checkRequiredPresent(creation: Creation, fields: Map<String, Value>) {
        for (prop in model.shapes.getValue(creation.shape).members.filterIsInstance<StoredProp>()) {
            if (fields[prop.name] != Value.VNone || prop.takesAbsence()) continue
            val given = creation.fields.firstOrNull { field -> field.name == prop.name }?.value?.let { value -> Printer.expr(value) } ?: "the value given"
            throw FiringFailed(
                "'${creation.shape}.${prop.name}' is required and '$given' is absent: the selection matched nothing (Reference §14)")
        }
    }

    /** The target is a literal static path (OQ37): the subject itself, or a to-one route from it. */
    private fun deleteTarget(deletion: DeleteStmt, context: Evaluator.Ctx, subject: Long): Long {
        if (deletion.target.root == "this" && deletion.target.segs.isEmpty()) return subject
        return (evaluator.eval(deletion.target, context) as? Value.VRef)?.id
            ?: throw VelleRuntimeError("delete target '${Printer.expr(deletion.target)}' is not a present instance")
    }

    /** The elements one `for each` name runs over, in the current iteration's context. */
    private fun loopValues(source: LoopSource, context: Evaluator.Ctx): List<Value> = when (source) {
        is LoopSource.Over -> evaluator.collectionValues(source.expr, context)
        is LoopSource.Range -> {
            val from = rangeBound(source.from, context)
            val to = rangeBound(source.to, context)
            if (to < from) emptyList() else (from..to).map { step -> Value.VNum(BigDecimal(step)) }
        }
    }

    private fun rangeBound(bound: Expr, context: Evaluator.Ctx): Long {
        val number = (evaluator.eval(bound, context) as? Value.VNum)?.v
            ?: throw VelleRuntimeError("range bound '${Printer.expr(bound)}' is not a number")
        if (number.stripTrailingZeros().scale() > 0)
            throw VelleRuntimeError("range bound '${Printer.expr(bound)}' is $number, not a whole number")
        return number.toLong()
    }
}
