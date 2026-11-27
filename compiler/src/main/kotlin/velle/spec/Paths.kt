package velle.spec

import velle.MemberInfo
import velle.Model
import velle.PathExpr
import velle.VALUE_ALIAS
import velle.VType

// ── Paths, resolved (Reference §9, §10) ───────────────────────────────────────
//
// A path is a chain of names in a condition or a rule body, such as `member.name` in
// `shape LoanOfLateMember = Loan where member.name == "late"`. Resolving it finds what
// each name refers to: the record the path starts from, then the field each later name
// is a member of. Every part of the compiler that needs to know what a path means asks
// [PathResolver] and reads the [ResolvedPath] it answers with, so that two parts cannot
// disagree about it.

/**
 * The names a path can start from at one point of a spec, and what each names.
 *
 * At the top of a condition the [element] and the [subject] are the same shape: in
 * `Loan where member.name == "late"` both are `Loan`. Inside a filter they part: in
 * `Member where exists (Loan where branch == this.branch)`, a bare `branch` is a member of
 * the element, `Loan`, while `this` keeps naming the subject, `Member` (Reference §10).
 */
data class Scope(
    /** The shape whose members a bare name names: the innermost record being read. */
    val element: String,
    /** The shape `this` names: the outermost subject. */
    val subject: String = element,
    /** Names bound to a record, an `as` name, a `for each` name over records, a rule's actor, each to its shape. */
    val recordNames: Map<String, String> = emptyMap(),
    /** Names bound to a value, a `for each` name over numbers or scalar values, which no member resolves on. */
    val valueNames: Set<String> = emptySet(),
    /** A path, as its names after any leading `this`, to the shape a governing `is` test narrowed it to. */
    val narrowed: Map<List<String>, String> = emptyMap(),
) {
    /** The same point of the spec with [name] bound to a record of [shape]. */
    fun withRecordName(name: String, shape: String): Scope = copy(recordNames = recordNames + (name to shape), valueNames = valueNames - name)

    /** The same point of the spec with [name] bound to a value. */
    fun withValueName(name: String): Scope = copy(recordNames = recordNames - name, valueNames = valueNames + name)

    /** The scope of a filter's `where` over records of [element]: bare names move to it, `this` stays. */
    fun inside(element: String): Scope = copy(element = element)

    /** The same point of the spec with the narrowings in [narrowings] in force as well. */
    fun narrowedBy(narrowings: Map<List<String>, String>): Scope =
        if (narrowings.isEmpty()) this else copy(narrowed = narrowed + narrowings)

    companion object {
        /**
         * A scope given the way the read analysis has always been called: the element's shape, a map of
         * names to shapes in which [VALUE_ALIAS] marks a name bound to a value, and the subject's shape.
         */
        fun of(element: String, aliases: Map<String, String>, subject: String): Scope = Scope(
            element = element,
            subject = subject,
            recordNames = aliases.filterValues { shape -> shape != VALUE_ALIAS },
            valueNames = aliases.filterValues { shape -> shape == VALUE_ALIAS }.keys,
        )
    }
}

/** What a path's first name names. */
sealed interface PathStart {
    /** `this`: the subject. */
    data class Subject(val shape: String) : PathStart

    /** A bare name that is a member of the element: the path's first step is that member. */
    data class Element(val shape: String) : PathStart

    /** A name bound to a record, an `as` name, a `for each` name, a rule's actor. */
    data class RecordName(val name: String, val shape: String) : PathStart

    /** A name bound to a value. A step after it does not resolve. */
    data class ValueName(val name: String) : PathStart

    /** A shape or refinement named on its own, as a membership test or as the records a collection reads. */
    data class ShapeName(val name: String) : PathStart

    /** The record an expression finds, `(Fine for this)` or `latest(payments)`, with the names read after it; null when it finds no record. */
    data class FoundRecord(val shape: String?) : PathStart
}

/** How a step's record is reached from the step before it. */
enum class Reach {
    /** The step is a member of the record the path starts from. */
    START,

    /** The step before it is a reference to one record, `one Member` or `Member?`. */
    ONE_REFERENCE,

    /** The step before it is a collection of records, a `many` field or an inferred inverse. */
    COLLECTION,
}

/**
 * One name of a path after its start, resolved: the [member] it names, declared on or taken into
 * [readOn], the shape the step is looked up on, which is the shape a governing `is` test narrowed
 * the record to when one did.
 */
data class Step(val member: MemberInfo, val readOn: String, val reach: Reach) {
    val name: String get() = member.name
}

/** A name that names no member of [readOn]: the path stops resolving there. */
data class UnresolvedStep(val name: String, val readOn: String?)

/**
 * A path with each name resolved. [steps] holds every name that resolved, in order; when a name does
 * not, [unresolved] says which and the steps after it are absent. [endType] is the declared type at
 * the end, null when the path does not resolve to the end or ends on a value name. [path] is null for
 * names read after an expression, [PathStart.FoundRecord].
 */
data class ResolvedPath(
    val path: PathExpr?,
    val start: PathStart,
    val steps: List<Step>,
    val unresolved: UnresolvedStep?,
    val endType: VType?,
) {
    /** Whether every name resolved. */
    val resolves: Boolean get() = unresolved == null

    /** The shape of the records the path's end reaches, its record's or its collection's; null for a value. */
    val endShape: String? get() = endType?.let { type -> type.instanceShape() ?: (type as? VType.Coll)?.shape }
}

/** Resolves paths against the declarations of one spec. */
class PathResolver(private val model: Model) {

    fun resolve(path: PathExpr, scope: Scope): ResolvedPath {
        val start = startOf(path, scope)
        return when (start) {
            is PathStart.ValueName -> ResolvedPath(path, start, emptyList(), path.segs.firstOrNull()?.let { seg -> UnresolvedStep(seg.name, null) }, null)
            is PathStart.ShapeName -> resolveShapeName(path, start)
            is PathStart.Subject -> walk(path, start, VType.Inst(start.shape), emptyList(), path.segs.map { seg -> seg.name }, emptyList(), scope)
            is PathStart.RecordName -> walk(path, start, startTypeOfRecordName(start, scope), emptyList(), path.segs.map { seg -> seg.name }, listOf(start.name), scope)
            is PathStart.Element -> resolveFromElement(path, start, scope)
            is PathStart.FoundRecord -> resolveAfter(start.shape, path.segs.map { seg -> seg.name })
        }
    }

    /**
     * The [names] read after an expression that finds a record of [recordShape], `(Fine for this).loan`.
     * No `is` test narrows such a record, so no narrowing applies.
     */
    fun resolveAfter(recordShape: String?, names: List<String>): ResolvedPath {
        val start = PathStart.FoundRecord(recordShape)
        if (recordShape == null) return ResolvedPath(null, start, emptyList(), names.firstOrNull()?.let { name -> UnresolvedStep(name, null) }, null)
        return walk(null, start, VType.Inst(recordShape), emptyList(), names, emptyList(), Scope(recordShape))
    }

    private fun startOf(path: PathExpr, scope: Scope): PathStart = when {
        path.root == "this" -> PathStart.Subject(scope.subject)
        path.root in scope.valueNames -> PathStart.ValueName(path.root)
        path.root in scope.recordNames -> PathStart.RecordName(path.root, scope.recordNames.getValue(path.root))
        path.root in model.shapes || path.root in model.refinements -> PathStart.ShapeName(path.root)
        else -> PathStart.Element(scope.element)
    }

    /** A record name's own narrowing, `actor is Member` read as `actor.name`, applies to its first step. */
    private fun startTypeOfRecordName(start: PathStart.RecordName, scope: Scope): VType =
        VType.Inst(scope.narrowed[listOf(start.name)] ?: start.shape)

    /** A shape or refinement named on its own has no type of its own: its records are those of [PathStart.ShapeName.name]. */
    private fun resolveShapeName(path: PathExpr, start: PathStart.ShapeName): ResolvedPath {
        val unresolved = path.segs.firstOrNull()?.let { seg -> UnresolvedStep(seg.name, start.name) }
        return ResolvedPath(path, start, emptyList(), unresolved, null)
    }

    private fun resolveFromElement(path: PathExpr, start: PathStart.Element, scope: Scope): ResolvedPath {
        val firstMember = model.membersOf(start.shape)[path.root]
            ?: return ResolvedPath(path, start, emptyList(), UnresolvedStep(path.root, start.shape), null)
        val firstStep = Step(firstMember, start.shape, Reach.START)
        val namesSoFar = listOf(path.root)
        val firstType = scope.narrowed[namesSoFar]?.let { shape -> VType.Inst(shape) } ?: firstMember.type
        return walk(path, start, firstType, listOf(firstStep), path.segs.map { seg -> seg.name }, namesSoFar, scope)
    }

    /** Resolves [remainingNames] one at a time from a record of [startType], narrowing each by the scope. */
    private fun walk(
        path: PathExpr?,
        start: PathStart,
        startType: VType,
        stepsSoFar: List<Step>,
        remainingNames: List<String>,
        namesSoFar: List<String>,
        scope: Scope,
    ): ResolvedPath {
        val steps = stepsSoFar.toMutableList()
        var currentType: VType = startType
        var names = namesSoFar
        for (name in remainingNames) {
            val readOn = recordShapeOf(currentType)
                ?: return ResolvedPath(path, start, steps, UnresolvedStep(name, null), null)
            val member = model.membersOf(readOn)[name]
                ?: return ResolvedPath(path, start, steps, UnresolvedStep(name, readOn), null)
            steps.add(Step(member, readOn, reachThrough(currentType, steps.isEmpty())))
            names = names + name
            currentType = scope.narrowed[names]?.let { shape -> VType.Inst(shape) } ?: member.type
        }
        return ResolvedPath(path, start, steps, null, currentType)
    }

    private fun reachThrough(typeBefore: VType, firstStep: Boolean): Reach = when {
        firstStep -> Reach.START
        typeBefore is VType.Coll -> Reach.COLLECTION
        else -> Reach.ONE_REFERENCE
    }

    private fun recordShapeOf(type: VType): String? = type.instanceShape() ?: (type as? VType.Coll)?.shape
}
