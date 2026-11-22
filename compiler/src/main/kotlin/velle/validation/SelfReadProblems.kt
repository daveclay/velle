package velle.validation

import velle.*

// ── V14: definition-graph well-foundedness (stratify, then prove each self-read ends) ──
//
// Evaluation unfolds definitions, and unfolding is the one thing that can
// fail to bottom out, so the definition graph (derived properties;
// refinements referencing refinements) must be well-founded. The acyclic
// part passes free. A cycle through two or more definitions is refused.
// A single derived property reading itself is accepted in exactly two
// forms, both one step through an optional to-one on its own shape:
//   (a) the step is `latest`/`first` over the same shape picking a record
//       strictly earlier by a field fixed at creation (`previous` and
//       `streakAfter`, Reference §19);
//   (b) the step is a stored field and a spendable
//       `never (S where step == this)` forbids a record being its own step
//       (`root` through `parent`, Reference §7, §21).
// Everything else is refused with a message naming the reason and the
// spelling that passes (checks.md V14); fixpoint semantics are never
// attempted.

/** Why a derived property that reads itself cannot be shown to end, with the names its message needs. */
internal sealed interface SelfReadProblem {
    fun message(shape: String, property: String): String

    fun own(shape: String, property: String) = "'$shape.$property'"

    fun walkFix(step: String) = ", or write the walk 'walk $step' (Reference §7)"

    fun rulesPhrase(writers: List<String>): String {
        val names = writers.joinToString(" and ") { writer -> "'$writer'" }
        return if (writers.size == 1) "rule $names assigns" else "rules $names assign"
    }

    /** A count of steps: a word from two to four, digits beyond. */
    fun stepCount(count: Int) = if (count in 2..4) numberWord(count) else count.toString()
}

internal object DirectRead : SelfReadProblem {
    override fun message(shape: String, property: String) =
        "${own(shape, property)} is computed from itself, and there is nothing to start from; " +
            "compute it from other fields, or read it on a related record one step away"
}

internal object ThroughSelection : SelfReadProblem {
    override fun message(shape: String, property: String) =
        "${own(shape, property)} reads itself through a selection; the compiler can see the chain ends " +
            "only when the property reads itself one step away, through a field of $shape holding one $shape"
}

/** [firstStepHoldsOwnShape]: the first of [stepsBefore] is a field holding one record of the
 *  property's own shape, so the message can offer a walk over it. */
internal class DeepPath(val stepsBefore: List<String>, val firstStepHoldsOwnShape: Boolean) : SelfReadProblem {
    override fun message(shape: String, property: String): String {
        val firstStep = stepsBefore.first()
        val walk = if (firstStepHoldsOwnShape) walkFix(firstStep) else ""
        return "${own(shape, property)} reads itself ${stepCount(stepsBefore.size)} steps away, " +
            "through '${stepsBefore.joinToString(".")}'; the compiler can see the chain ends only when " +
            "the property reads itself one step away. Read '$firstStep.$property'$walk"
    }
}

internal class StepNotToSameShape(val step: String) : SelfReadProblem {
    override fun message(shape: String, property: String) =
        "${own(shape, property)} reads itself through '$step', which is not a field of $shape holding one $shape; " +
            "the compiler can see the chain ends only when the property reads itself one step away through such a field"
}

internal class RequiredStep(val step: String) : SelfReadProblem {
    override fun message(shape: String, property: String) =
        "${own(shape, property)} reads itself on its '$step', and every record has one, " +
            "so the chain through '$step' never ends; " +
            "declare '$step: one $shape?' so the record at the top of the chain has none"
}

internal class NoProhibition(val step: String) : SelfReadProblem {
    override fun message(shape: String, property: String) =
        "${own(shape, property)} reads itself on its '$step', so the chain through '$step' must end, " +
            "and nothing says a record is never its own '$step'; " +
            "write 'never ($shape where $step == this)'${walkFix(step)}"
}

internal class UnreliableProhibition(val step: String, val writers: List<String>) : SelfReadProblem {
    override fun message(shape: String, property: String): String {
        val why = if (writers.isEmpty()) "a rule changes what it reads" else "${rulesPhrase(writers)} '$step'"
        return "${own(shape, property)} reads itself on its '$step', so the chain through '$step' must end. " +
            "'never ($shape where $step == this)' says so, but $why, so the compiler cannot rely on it; " +
            "leave '$step' as set at creation${walkFix(step)}"
    }
}

internal class ComputedStep(val step: String) : SelfReadProblem {
    override fun message(shape: String, property: String) =
        "${own(shape, property)} reads itself on its '$step', so the chain through '$step' must end, " +
            "and '$step' is computed, so the compiler cannot see that it does. " +
            "It can when '$step' is 'latest' or 'first' over $shape picking a record strictly earlier " +
            "by a field fixed at creation, as 'previous' does in Reference §19, " +
            "or when '$step' is a stored field and the spec says 'never ($shape where $step == this)'; " +
            "write one of those${walkFix(step)}"
}

internal class NonStrictComparison(val step: String, val comparison: OrderingComparison) : SelfReadProblem {
    override fun message(shape: String, property: String) =
        "${own(shape, property)} reads itself on its '$step', so the chain through '$step' must end. " +
            "'$step' picks by '${comparison.spelled()}', which can pick a record with the same '${comparison.datum}', " +
            "so the chain can come back to this record; write '${comparison.strictened().spelled()}'"
}

internal class ChangingOrderField(val step: String, val comparison: OrderingComparison, val writers: List<String>) : SelfReadProblem {
    override fun message(shape: String, property: String): String {
        val why = if (writers.isEmpty()) "'${comparison.datum}' is not a field fixed at creation"
            else "${rulesPhrase(writers)} '${comparison.datum}'"
        return "${own(shape, property)} reads itself on its '$step', so the chain through '$step' must end. " +
            "'$step' picks by '${comparison.spelled()}', and $why, so the order can change after the fact; " +
            "pick by a field no rule assigns, or by a 'timestamp on create'"
    }
}

/**
 * Why one self-recursive derived property cannot be shown to end, or null
 * when it can: every occurrence of the property's own name in its formula
 * is exactly `<step>.<property>`, one step through an optional to-one on
 * the same shape (the optionality is the base case: the chain can end),
 * and every such step is a descent or carries a spendable prohibition.
 * The walk is syntactic; a name collision with a same-named field
 * elsewhere is refused as a deep path, coarse but sound.
 */
internal fun Validator.selfReadProblem(shape: String, property: DerivedProp): SelfReadProblem? {
    val selfReads = SelfReadsOfFormula(model, shape, property.name)
    selfReads.visit(property.expr)
    selfReads.problem?.let { problem -> return problem }
    if (selfReads.steps.isEmpty()) return StepNotToSameShape(property.name)
    return selfReads.steps.firstNotNullOfOrNull { step -> stepProblem(shape, step) }
}

/**
 * The reads of [property] in its own formula on [shape]: the first [problem]
 * met, at which the walk stops, else the [steps] the property reads itself
 * one step away through.
 */
private class SelfReadsOfFormula(private val model: Model, private val shape: String, private val property: String) {
    val steps = mutableSetOf<String>()
    var problem: SelfReadProblem? = null
        private set

    private fun found(found: SelfReadProblem) { if (problem == null) problem = found }

    fun visit(expr: Expr?) {
        if (expr == null || problem != null) return
        when (expr) {
            is PathExpr -> visitPath(expr)
            is Access -> {
                if (expr.segs.any { segment -> segment.name == property }) found(ThroughSelection) else visit(expr.target)
            }
            is UnaryMinus -> visit(expr.inner)
            is Binary -> { visit(expr.left); visit(expr.right) }
            is NotExpr -> visit(expr.inner)
            is IfExpr -> { visit(expr.condition); visit(expr.thenExpr); visit(expr.elseExpr) }
            is Interpolation -> expr.pieces.forEach { piece -> visit(piece) }
            is IsExpr -> visit(expr.subject)
            is ExistsExpr -> { visit(expr.forExpr); expr.collection?.let { collection -> visitCollection(collection) } }
            is AggCall -> visitCollection(expr.collection)
            is FunCall -> expr.args.forEach { argument -> visit(argument) }
            is SingularFor -> visit(expr.forExpr)
            is ShapeForSource -> visit(expr.forExpr)
            is SetExpr -> visitCollection(expr.collection)
            is WalkExpr -> { if (expr.rel == property) found(DirectRead) else visit(expr.until) }
            else -> {}
        }
    }

    private fun visitCollection(collection: CollectionExpr) {
        collection.bindings.forEach { binding -> visit(binding.source) }
        visit(collection.where)
    }

    private fun visitPath(path: PathExpr) {
        if (path.root == property) return found(DirectRead)
        if (path.segs.none { segment -> segment.name == property }) return
        val stepsBefore = listOf(path.root) + path.segs.takeWhile { segment -> segment.name != property }.map { segment -> segment.name }
        if (stepsBefore.size > 1) return found(DeepPath(stepsBefore, holdsOneOfOwnShape(stepsBefore.first())))
        visitOneStep(stepsBefore.single())
    }

    private fun visitOneStep(step: String) {
        if (step == "this") return found(DirectRead)
        val member = model.membersOf(shape)[step]
        when {
            member == null || member.type.instanceShape() != shape -> found(StepNotToSameShape(step))
            member.type !is VType.Optional -> found(RequiredStep(step))
            else -> steps.add(step)
        }
    }

    private fun holdsOneOfOwnShape(field: String) = model.membersOf(shape)[field]?.type?.instanceShape() == shape
}

/** Why one step of a self-read is no proof the chain ends, or null when it is. */
private fun Validator.stepProblem(shape: String, step: String): SelfReadProblem? {
    val member = model.membersOf(shape).getValue(step)
    member.derived?.let { derived -> return descentProblem(shape, step, derived.expr) }
    if (!member.stored) return ComputedStep(step)
    if (neverForbidsSelfReference(shape, step)) return null
    val writers = rulesAssigning(shape, step)
    return if (neverForbidsSelfReference(shape, step, model.nevers)) UnreliableProhibition(step, writers) else NoProhibition(step)
}

/** The names of the rules that assign [field] of [shape], each once. */
private fun Validator.rulesAssigning(shape: String, field: String): List<String> =
    writes.filter { write -> write.owner == shape && write.member.name == field }.map { write -> write.rule.name }.distinct()

/**
 * Why a computed step is no strict descent, or null when it is one: a
 * `latest`/`first` over the shape's own instances whose predicate carries
 * a strict comparison of a creation-fixed datum against the subject's, so
 * every step lands on a strictly smaller (or larger) value of a datum that
 * never changes, and the chain must end in finite data.
 */
private fun Validator.descentProblem(shape: String, step: String, formula: Expr): SelfReadProblem? {
    val selection = (formula as? AggCall)?.takeIf { aggregate -> selectsFromOwnShape(aggregate, shape) }
        ?: return ComputedStep(step)
    val conjuncts = selection.collection.where?.let(::conjunctsOf) ?: return ComputedStep(step)
    return orderingProblem(shape, step, conjuncts.mapNotNull(::orderingComparison))
}

/** [selection] is a `latest` or `first` over the records of [shape] itself. */
private fun Validator.selectsFromOwnShape(selection: AggCall, shape: String): Boolean {
    if (selection.name !in setOf("latest", "first")) return false
    val sourceShape = selection.collection.bindings.singleOrNull()?.source?.let(::namedSourceShape) ?: return false
    return model.baseOf(sourceShape) == shape
}

/** The shape a collection source names: `Shape` bare, or `Shape for ...`; null for any other source. */
private fun namedSourceShape(source: Expr): String? = when {
    source is PathExpr && source.segs.isEmpty() -> source.root
    source is ShapeForSource -> source.shape
    else -> null
}

/** Why the [comparisons] of a selection's predicate are no strict descent, or null when one is. */
private fun Validator.orderingProblem(shape: String, step: String, comparisons: List<OrderingComparison>): SelfReadProblem? {
    if (comparisons.any { comparison -> comparison.strict && creationFixed(shape, comparison.datum) }) return null
    val nearest = comparisons.firstOrNull() ?: return ComputedStep(step)
    if (!nearest.strict) return NonStrictComparison(step, nearest)
    return ChangingOrderField(step, nearest, rulesAssigning(shape, nearest.datum))
}

/** `datum < this.datum`: a conjunct ordering the selected record against the subject by one field. */
internal data class OrderingComparison(val datum: String, val op: String) {
    val strict get() = op == "<" || op == ">"
    fun spelled() = "$datum $op this.$datum"
    fun strictened() = copy(op = op.trimEnd('='))
}

/** Each ordering operator and the one that says the same with its sides swapped. */
private val swappedOrdering = mapOf("<" to ">", ">" to "<", "<=" to ">=", ">=" to "<=")

private fun orderingComparison(conjunct: Expr): OrderingComparison? {
    val comparison = conjunct as? Binary ?: return null
    val swappedOp = swappedOrdering[comparison.op] ?: return null
    return comparisonOfField(comparison.left, comparison.right, comparison.op)
        ?: comparisonOfField(comparison.right, comparison.left, swappedOp)
}

/** `datum op this.datum`, read with [fieldSide] as the selected record's `datum` and [subjectSide] as the subject's. */
private fun comparisonOfField(fieldSide: Expr, subjectSide: Expr, op: String): OrderingComparison? {
    val datum = bareFieldName(fieldSide) ?: return null
    return OrderingComparison(datum, op).takeIf { datum == fieldNameOfThis(subjectSide) }
}

/** `datum`: a bare name other than `this`. */
private fun bareFieldName(expr: Expr): String? =
    (expr as? PathExpr)?.takeIf { path -> path.segs.isEmpty() && path.root != "this" }?.root

/** `this.datum`: one field of the subject. */
internal fun fieldNameOfThis(expr: Expr): String? =
    (expr as? PathExpr)?.takeIf { path -> path.root == "this" }?.segs?.singleOrNull()?.name

/** Fixed at the creation commit, never changed after: a `timestamp on
 *  create` field, or a stored field no rule ever assigns. */
private fun Validator.creationFixed(shape: String, field: String): Boolean {
    val members = model.shapes[shape]?.members ?: return false
    members.filterIsInstance<TimestampProp>().find { member -> member.name == field }
        ?.let { timestamp -> return timestamp.on == "create" }
    if (members.filterIsInstance<StoredProp>().none { member -> member.name == field }) return false
    return rulesAssigning(shape, field).isEmpty()
}
