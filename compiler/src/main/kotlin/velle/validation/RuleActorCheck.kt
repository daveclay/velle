package velle.validation

import velle.*

// ── V31: a rule's actor, typed by reachability (Reference §22, "The actor") ─
//
// `rule R when Product by Staff? [as name]`: the rule reads the committer of
// the root act of the transaction it fires in. Inside a transaction that is
// a fact of the commit, so the type is decided by which commits can fire
// the rule: every path starting at an act declared `by` a shape the clause
// accepts → the actor is present; some path starting at a tick, an
// `after commit` firing, or an act without `by` → the actor may be absent,
// and the clause must say `?`. Outside any act's transaction there is no
// actor to read: an `after commit` or scheduled rule reads the stored
// actor of a durable act through the data instead.

/** Where a path to a rule's trigger starts: an act, with or without an actor, or a firing outside any act's transaction. */
private sealed interface ActorRoot {
    data class ActWithActor(val name: String, val actor: ActorProp) : ActorRoot

    /** A start with no actor, described as V31's message names it. */
    sealed interface WithoutActor : ActorRoot { val description: String }

    data class ActWithoutActor(val name: String) : WithoutActor {
        override val description: String get() = "act '$name', which declares no actor"
    }

    data class Boundary(override val description: String) : WithoutActor
}

/** Whether [rule] fires outside any act's transaction: after commit, or on a schedule. */
private fun firesOutsideAnyAct(rule: RuleDecl): Boolean =
    rule.preposition == "after" || rule.triggers.any { trigger -> trigger != "commit" }

private fun Validator.actorRoots(rule: RuleDecl, visiting: Set<String> = emptySet()): Set<ActorRoot> {
    val roots = linkedSetOf<ActorRoot>()
    for (commit in possibleCommits) {
        if (affects(commit, rule)) roots.addAll(actorRootsOf(commit, visiting))
    }
    return roots
}

/** Where the paths through [commit] start: its act when an act commits it, or the roots of the rule that does. */
private fun Validator.actorRootsOf(commit: PossibleCommit, visiting: Set<String>): Set<ActorRoot> {
    val firingRule = commit.byRule
    if (firingRule == null) {
        val act = (commit as PossibleCommit.Creates).source.removePrefix("act ").substringBefore(" ")
        val actor = model.actorOf(act)
        return setOf(if (actor != null) ActorRoot.ActWithActor(act, actor) else ActorRoot.ActWithoutActor(act))
    }
    if (firesOutsideAnyAct(firingRule)) {
        val how = if (firingRule.preposition == "after") "after commit"
            else "on ${firingRule.triggers.filter { trigger -> trigger != "commit" }.joinToString(", ")}"
        return setOf(ActorRoot.Boundary("rule '${firingRule.name}', which fires $how"))
    }
    if (firingRule.name in visiting) return emptySet()
    return actorRoots(firingRule, visiting + firingRule.name)
}

internal fun Validator.checkRuleActors() {
    for (rule in model.rules.values) {
        val actor = rule.by ?: continue
        val scope = subjectScope(rule) ?: continue
        checkRuleActor(rule, actor, scope)
    }
}

private fun Validator.checkRuleActor(rule: RuleDecl, actor: RuleActor, scope: String) {
    if (actor.shape !in model.shapes && actor.shape !in model.refinements) {
        diagnostics.add(Diagnostic("F1", "rule '${rule.name}' — 'by ${actor.shape}' names unknown shape '${actor.shape}'"))
        return
    }
    val declaredBases = model.basesOf(actor.shape)
    declaredBases.filter { base -> base in model.exposed }.forEach { actBase ->
        diagnostics.add(Diagnostic("F2", "rule '${rule.name}' — 'by ${actor.shape}': the actor's shape is a `shape`, " +
            "never an act ('$actBase' is one) (Reference §22, \"The actor\")"))
    }
    val placementProblem = actorPlacementProblem(rule, actor, scope)
    if (placementProblem != null) {
        diagnostics.add(placementProblem)
        return
    }
    checkActorOnEveryPath(rule, actor, declaredBases)
}

/** V31 or F3 when the rule has no actor of its own to bind: it fires outside an act, its subject carries
 *  an actor already, or the actor's name is taken by a member of the subject. */
private fun Validator.actorPlacementProblem(rule: RuleDecl, actor: RuleActor, scope: String): Diagnostic? {
    if (firesOutsideAnyAct(rule))
        return Diagnostic("V31", "rule '${rule.name}' declares 'by ${actor.shape}', but it fires outside any " +
            "act's transaction (${if (rule.preposition == "after") "after commit" else "on a schedule"}), " +
            "where there is nobody asking — the actor is a fact of the act: read it off a durable act's " +
            "record through the data, or copy it when materializing (Reference §22, \"The actor\")")
    val subjectActor = model.baseOf(scope)?.let { base -> model.actorOf(base) }
    if (subjectActor != null)
        return Diagnostic("V31", "rule '${rule.name}' declares 'by ${actor.shape}', but its subject '$scope' " +
            "is an act that carries its own actor — '${subjectActor.name}' is already in scope")
    if (model.membersOf(scope).containsKey(actor.name))
        return Diagnostic("F3", "rule '${rule.name}' binds its actor as '${actor.name}', which is already a " +
            "member of '$scope' — name the actor: `by ${actor.shape}${if (actor.optional) "?" else ""} as <name>`")
    return null
}

/** V31: some path to the trigger starts at an act with an actor, every such actor is one the clause
 *  accepts, and a path without an actor makes the clause optional. */
private fun Validator.checkActorOnEveryPath(rule: RuleDecl, actor: RuleActor, declaredBases: Set<String>) {
    val roots = actorRoots(rule)
    val actsWithActor = roots.filterIsInstance<ActorRoot.ActWithActor>()
    val rootsWithoutActor = roots.filterIsInstance<ActorRoot.WithoutActor>()
    if (actsWithActor.isEmpty()) {
        diagnostics.add(Diagnostic("V31", "rule '${rule.name}' declares 'by ${actor.shape}', but no path to its trigger " +
            "starts at an act declared `by` — nothing it can fire in carries an actor"))
        return
    }
    actsWithActor.forEach { act -> checkActorAccepted(rule, actor, declaredBases, act) }
    if (rootsWithoutActor.isEmpty() || actor.optional) return
    val paths = rootsWithoutActor.joinToString("; ") { root -> root.description }
    diagnostics.add(Diagnostic("V31", "rule '${rule.name}' declares 'by ${actor.shape}', but its trigger is also " +
        "reached from $paths, where there is no actor — declare `by ${actor.shape}?` and narrow with " +
        "'${actor.name} is some' (Reference §22, \"The actor\")"))
}

private fun Validator.checkActorAccepted(rule: RuleDecl, actor: RuleActor, declaredBases: Set<String>, act: ActorRoot.ActWithActor) {
    if (declaredBases.containsAll(model.basesOf(act.actor.shape))) return
    diagnostics.add(Diagnostic("V31", "rule '${rule.name}' declares 'by ${actor.shape}', but it can fire in the " +
        "transaction of act '${act.name}', which is committed by a '${act.actor.shape}' — " +
        "declare the union, or a shape every path's actor belongs to"))
}

/** The alias a rule's `by` clause binds, for the read summaries: the actor's
 *  reads are reads on its shape, not an unresolvable root. */
internal fun Validator.ruleAliases(rule: RuleDecl): Map<String, String> =
    actorAlias(rule) + creationAliases(rule) + loopAliases(rule)

private fun actorAlias(rule: RuleDecl): Map<String, String> =
    rule.by?.let { actor -> mapOf(actor.name to actor.shape) }.orEmpty()

/** The names the body's `as` clauses bind, each to the shape it creates (Reference §14). */
private fun creationAliases(rule: RuleDecl): Map<String, String> =
    rule.creations().mapNotNull { creation -> creation.alias?.let { alias -> alias to creation.shape } }.toMap()

/** The names the body's `for each` blocks bind (Reference §14): a record name
 *  to its element shape, a number or scalar name to [VALUE_ALIAS]. */
internal fun Validator.loopAliases(rule: RuleDecl): Map<String, String> = loopAliasesByRuleName.getOrPut(rule.name) {
    val scope = (rule.condition as? RefName)?.name?.takeIf { name -> name in model.shapes || name in model.refinements }
        ?: model.baseOfExpr(rule.condition) ?: return@getOrPut emptyMap()
    model.loopElementShapes(rule, scope, actorAlias(rule) + creationAliases(rule))
}
