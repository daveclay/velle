package velle

/**
 * A watched condition: a commit-triggered rule's condition, a capture-carrying
 * refinement, or a `never`'s target, with its static read summary. [sensShapes]
 * is the shape-granular sensitivity set (existence/collection consults,
 * base-normalized); [selfContained] means the predicate reads nothing
 * beyond the base shape's own columns — the criterion under which a
 * storage-side pre-filter is sound mid-envelope (any instance whose
 * membership the envelope changed was itself written, so it is already in
 * the working set; every untouched instance's row still matches its
 * in-memory state).
 */
internal class ConditionWatcher(
    val key: String,
    val condition: RefExpr,
    /** the base shapes the condition's subjects are drawn from — several for a cross-base union (Reference §9) */
    val bases: Set<String>,
    val summary: ReadSummary,
    val sensShapes: Set<String>,
    val selfContained: Boolean,
    /** Shapes targeted by reference-typed fields the predicate reads — a
     *  deletion of one flips those reads to absent (OQ37-R10). */
    val refTargets: Set<String>,
) {
    /**
     * Can a commit with [footprint] change this watcher's member set? The runtime sibling of
     * the compiler's derived trigger set (Reference §11, "Rules ground in
     * commits"): membership flips only when data the predicate reads changes,
     * and the footprint says exactly what changed. `today`/`now` are constant
     * within an envelope, so time reads never make a commit relevant here —
     * entry by aging belongs to ticks.
     */
    fun canChangeUnder(footprint: Footprint): Boolean {
        if (summary.opaque) return true
        if (footprint.created.any { shape -> shape in bases || shape in sensShapes }) return true
        // a deletion flips existence/aggregate consults, removes the deleted
        // instance from every refinement of its base, and turns reference reads
        // at it absent (OQ37)
        if (footprint.deleted.any { shape -> shape in bases || shape in sensShapes || shape in refTargets }) return true
        return footprint.assigned.any { shapeAndField ->
            shapeAndField.first in sensShapes || shapeAndField in summary.fields || shapeAndField in summary.collFields
        }
    }

    companion object {
        fun of(model: Model, key: String, condition: RefExpr, bases: Set<String>): ConditionWatcher {
            val summary = model.summaryOfRefExpr(condition)
            val sensShapes = (summary.existsShapes + summary.collShapes).flatMap { shape -> model.basesOf(shape) }.toSet()
            val readFields = summary.fields + summary.collFields
            val selfContained = !summary.opaque && sensShapes.isEmpty() && bases.size == 1 &&
                readFields.all { (owner, _) -> owner in bases }
            val refTargets = readFields.flatMap { (owner, field) ->
                model.membersOf(owner)[field]?.type?.instanceShape()?.let { shape -> model.basesOf(shape) } ?: emptySet()
            }.toSet()
            return ConditionWatcher(key, condition, bases, summary, sensShapes, selfContained, refTargets)
        }
    }
}

/** The mutation footprint of one commit, or of a whole envelope: what it creates, writes, and deletes. */
internal class Footprint {
    val created = mutableSetOf<String>()
    val assigned = mutableSetOf<Pair<String, String>>()
    val deleted = mutableSetOf<String>()

    fun addAll(other: Footprint) {
        created.addAll(other.created)
        assigned.addAll(other.assigned)
        deleted.addAll(other.deleted)
    }
}

/**
 * Every condition the runtime watches across a commit, and the two questions asked of
 * them: what a commit's footprint is, and which records a watched condition holds now.
 */
internal class Watchers(private val system: VelleSystem) {
    private val model get() = system.model

    /** Watched conditions: every commit-triggered rule plus capture-carrying
     *  refinements. Schedule-only rules are deliberately absent — their subjects
     *  come from the tick's own member scan, never from a commit diff (§16/§17). */
    val commitWatchers: List<ConditionWatcher> by lazy { ruleWatchers() + captureWatchers() }

    val byKey: Map<String, ConditionWatcher> by lazy { commitWatchers.associateBy { watcher -> watcher.key } }

    /** Never targets with their read summaries, index-aligned with model.nevers. */
    val neverWatchers: List<ConditionWatcher?> by lazy {
        model.nevers.map { never ->
            model.basesOfExpr(never.target).takeIf { bases -> bases.isNotEmpty() }
                ?.let { bases -> ConditionWatcher.of(model, "never", never.target, bases) }
        }
    }

    private fun ruleWatchers(): List<ConditionWatcher> = model.rules.values
        .filter { rule -> rule.firesOnCommit() }
        .mapNotNull { rule ->
            val bases = model.basesOfExpr(rule.condition)
            if (bases.isEmpty()) null else ConditionWatcher.of(model, ruleKey(rule), rule.condition, bases)
        }

    private fun captureWatchers(): List<ConditionWatcher> = model.refinements
        .filter { (_, refinement) -> refinement.members.any { member -> member is DerivedProp && member.captured } }
        .mapNotNull { (name, _) ->
            model.baseOf(name)?.let { base -> ConditionWatcher.of(model, captureKey(name), RefName(name), setOf(base)) }
        }

    /** The watcher of a commit-triggered [rule]'s condition; null when its condition spans no base shape. */
    fun ofRule(rule: RuleDecl): ConditionWatcher? = byKey[ruleKey(rule)]

    /** The watcher of a capture-carrying [refinement]; null when it carries no capture. */
    fun ofCapture(refinement: String): ConditionWatcher? = byKey[captureKey(refinement)]

    /** The records [watcher]'s condition holds now, in the order the working set reads them. */
    fun membersOf(watcher: ConditionWatcher): Set<Long> {
        val workingSet = system.workingSet
        val candidates =
            if (watcher.selfContained) workingSet.idsOfCandidates(watcher.bases.single(), system.queryCompiler().filterFor(watcher.condition))
            else watcher.bases.flatMap { base -> workingSet.idsOf(base) }
        return candidates.filter { id -> system.evaluator.memberOfRefExpr(id, watcher.condition) }.toSet()
    }

    fun footprintOf(mutations: List<Mutation>): Footprint {
        val footprint = Footprint()
        for (mutation in mutations) when (mutation) {
            is Mutation.Create -> footprint.created.add(mutation.shape)
            is Mutation.Assign -> {
                val shape = system.workingSet.instance(mutation.id)?.shape ?: continue
                footprint.assigned.add(shape to mutation.field)
                // any stored write also advances the shape's `on update` timestamps
                model.updateTimestampsOf(shape).forEach { timestamp -> footprint.assigned.add(shape to timestamp.name) }
            }
            is Mutation.Delete -> system.workingSet.instance(mutation.id)?.shape?.let { shape -> footprint.deleted.add(shape) }
        }
        return footprint
    }

    companion object {
        private const val CAPTURE_PREFIX = "capture:"
        private fun ruleKey(rule: RuleDecl) = "rule:${rule.name}"
        private fun captureKey(refinement: String) = "$CAPTURE_PREFIX$refinement"

        /** The refinement whose captures [watcher] watches; null for a watcher of a rule's condition. */
        fun capturedRefinementOf(watcher: ConditionWatcher): String? =
            if (watcher.key.startsWith(CAPTURE_PREFIX)) watcher.key.removePrefix(CAPTURE_PREFIX) else null
    }
}

/** Whether [this] rule reacts to commits: no preposition (fires inside the commit), or a `commit` trigger. */
internal fun RuleDecl.firesOnCommit(): Boolean = preposition == null || "commit" in triggers
