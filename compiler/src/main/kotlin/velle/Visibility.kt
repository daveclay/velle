package velle

/**
 * Visibility (Reference §22, "The actor"; OQ33 §5.7–§5.10). Applied by the read surface
 * only: a query with a viewer admits the records the shape's clauses admit, a fetch of an
 * invisible record resolves absent, and a model object is projected with its hidden
 * fields absent. Evaluation never consults any of this — a derived property is
 * calculated over every input whether or not the viewer may see it.
 */
internal class Visibility(private val system: VelleSystem) {
    private val model get() = system.model
    private val evaluator get() = system.evaluator

    /** Is the record [id] returned to [viewer]? The engineer's own view (null) sees everything. */
    fun visibleTo(viewer: Long?, id: Long): Boolean {
        if (viewer == null) return true
        val instance = system.workingSet.instance(id) ?: return false
        val clauses = model.recordVisibility(instance.shape)
        if (clauses.isEmpty()) return true
        return clauses.any { clause -> admits(clause, viewer, instance.shape, id) }
    }

    /** The members of [id], read as [scope], that [viewer] may not see: a field with
     *  clauses none of which admit the viewer. Empty for the engineer's own view. */
    fun hiddenFields(viewer: Long?, id: Long, scope: String?): Set<String> {
        if (viewer == null) return emptySet()
        val instance = system.workingSet.instance(id) ?: return emptySet()
        val readAs = scope ?: instance.shape
        return model.membersOf(readAs).values
            .filter { member -> member.visible.isNotEmpty() && member.visible.none { clause -> admits(clause, viewer, readAs, id) } }
            .map { member -> member.name }.toSet()
    }

    /** Does [clause] admit [viewer] to the record [id] of [scope]? */
    private fun admits(clause: VisibleClause, viewer: Long, scope: String, id: Long): Boolean {
        val shape = clause.shape ?: return true
        val viewerInstance = system.workingSet.instance(viewer) ?: return false
        if (viewerInstance.shape !in model.basesOf(shape)) return false
        if (shape in model.refinements && !evaluator.memberOfRefExpr(viewer, RefName(shape))) return false
        val predicate = clause.where ?: return true
        return evaluator.truthy(evaluator.eval(predicate, Evaluator.Ctx(scope, id, actor = clause.name to viewer)))
    }
}
