package velle.validation

import velle.*

// ── possible commits: the state changes the spec can make ────────────────
//
// Every creation, assignment, and deletion an act, a closure, or a rule can
// commit, and which rule conditions each can affect. Reachability (V3),
// co-firing, and disjointness all read this list.

internal sealed interface PossibleCommit {
    val byRule: RuleDecl?
    data class Creates(val shape: String, val source: String, override val byRule: RuleDecl? = null) : PossibleCommit
    data class Assigns(val shape: String, val field: String, override val byRule: RuleDecl? = null) : PossibleCommit
    /** A rule's delete of an instance of [shape] (OQ37) — the third state change. */
    data class Deletes(val shape: String, override val byRule: RuleDecl? = null) : PossibleCommit
}

internal fun Validator.listPossibleCommits(): List<PossibleCommit> =
    model.exposed.map { act -> PossibleCommit.Creates(act, source = "act $act") } +
        model.closures.flatMap { (container, edges) -> edges.flatMap { edge -> closureCreations(container, edge) } } +
        model.rules.values.flatMap { rule -> commitsOfRule(rule) }

/** Closure parts are committable-via-closure (Reference §6, "Inline part
 *  creation"): the closure commit creates them, so they join the trigger,
 *  reachability, and co-firability analyses as ordinary creations. */
private fun Validator.closureCreations(container: String, edge: ClosureEdge): List<PossibleCommit> =
    // a by-value edge creates one of its target's operands (OQ63 §6.1), and a part edge
    // over a shape several shapes take creates one of its takers (Reference §9)
    model.basesOf(edge.partShape).map { PossibleCommit.Creates(it, source = "act $container with ${edge.prop}") } +
        edge.children.flatMap { child -> closureCreations(container, child) }

private fun Validator.commitsOfRule(rule: RuleDecl): List<PossibleCommit> {
    val creations = rule.body.statements().filterIsInstance<Creation>().map { creation ->
        PossibleCommit.Creates(creation.shape, source = "rule ${rule.name}", byRule = rule)
    }
    val assignments = writes.filter { write -> write.rule === rule }.map { write ->
        PossibleCommit.Assigns(write.owner, write.member.name, byRule = rule)
    }
    val deletions = model.deleteSites.filter { site -> site.rule === rule }.map { site ->
        PossibleCommit.Deletes(site.shape, byRule = rule)
    }
    return creations + assignments + deletions
}

/** Does this commit kind possibly affect the rule's condition? Widened by
 *  the sibling-confluence audit to the summary's full read vocabulary:
 *  consulted shapes base-normalized (a refinement name in `existsShapes`
 *  never matched a created base shape), collection consults, timestamp
 *  reads (any write to the shape advances its `on update` stamps), and
 *  `opaque` as affects-everything — the summary's own soundness contract. */
internal fun Validator.affects(commit: PossibleCommit, rule: RuleDecl): Boolean {
    val bases = model.basesOfExpr(rule.condition)
    if (bases.isEmpty()) return false
    // a transient act's refinements are evaluated exactly once, at its
    // creation commit — no other commit can ever fire the rule (Reference §4)
    if (bases.any { it in model.transients })
        return commit is PossibleCommit.Creates && commit.shape in bases
    reachDecidedByRuleAlone(commit, rule, bases)?.let { return it }
    return when (commit) {
        is PossibleCommit.Creates -> creationAffects(commit, rule, bases)
        is PossibleCommit.Assigns -> assignmentAffects(commit, conditionSummary(rule))
        is PossibleCommit.Deletes -> deletionAffects(commit, rule, bases)
    }
}

/** Where the rule alone decides whether [commit] reaches its condition: a
 *  deletion-only trigger is reached only by deleting its subject's base, and
 *  an opaque condition by every commit. Null where the commit's kind decides. */
internal fun Validator.reachDecidedByRuleAlone(commit: PossibleCommit, rule: RuleDecl, bases: Set<String>): Boolean? = when {
    rule.trigger == Trigger.DELETED -> commit is PossibleCommit.Deletes && commit.shape in bases
    conditionSummary(rule).opaque -> true
    else -> null
}

/** A freshly created base instance is born a member (entry) but was a
 *  member of nothing, so it can never leave. */
private fun Validator.creationAffects(commit: PossibleCommit.Creates, rule: RuleDecl, bases: Set<String>): Boolean =
    (commit.shape in bases && !rule.leaving) || commit.shape in consultedShapes(rule)

/** A deletion flips existence/aggregate consults of the shape, removes the
 *  deleted instance from every refinement of its base, and turns reads
 *  through references at it absent (`is none` — the absorbing reference). */
private fun Validator.deletionAffects(commit: PossibleCommit.Deletes, rule: RuleDecl, bases: Set<String>): Boolean =
    (commit.shape in bases && (!rule.leaving || rule.trigger.onDeletion)) ||
        commit.shape in consultedShapes(rule) || conditionReadsRefTo(rule, commit.shape)

private fun Validator.assignmentAffects(commit: PossibleCommit.Assigns, condition: ReadSummary): Boolean {
    val assignedField = commit.shape to commit.field
    return assignedField in condition.fields ||
        assignedField in condition.collFields ||
        condition.collFields.any { (owner, field) -> owner == commit.shape && field in updateTimestamps(commit.shape) }
}

/** Does the rule's condition read a reference-typed field whose target is
 *  [shape]? Deleting the target flips such reads to absent (OQ37-R10). */
internal fun Validator.conditionReadsRefTo(rule: RuleDecl, shape: String): Boolean {
    val condition = conditionSummary(rule)
    if (condition.opaque) return true
    return (condition.fields + condition.collFields).any { (owner, field) ->
        model.membersOf(owner)[field]?.type?.let { type -> model.referencesRecordsOf(type, shape) } == true
    }
}

/** Base shapes whose instance sets the rule's condition consults. */
private fun Validator.consultedShapes(rule: RuleDecl): Set<String> = consultedShapesByRuleName.getOrPut(rule.name) {
    val condition = conditionSummary(rule)
    (condition.existsShapes + condition.collShapes).flatMap { model.basesOf(it) }.toSet()
}
