package velle.validation

import velle.*

// ── guards: atoms and disarm (V2), boundary/apparatus (V4), captures (V7) ─

internal sealed interface GuardAtom {
    data class Witness(val shape: String) : GuardAtom
    data class Flag(val name: String) : GuardAtom
}

/** The guards in a rule's condition: each negated conjunct its body can make true. */
internal fun Validator.guardAtoms(rule: RuleDecl): List<GuardAtom> =
    conditionConjuncts(rule.condition).mapNotNull { conjunct ->
        val negated = (conjunct as? NotExpr)?.inner ?: return@mapNotNull null
        guardAtomOf(negated)
    }

/** The guard a negated conjunct states: `not exists` a witness, or `not` a flag. */
private fun Validator.guardAtomOf(negated: Expr): GuardAtom? = when {
    negated is ExistsExpr && negated.shape != null ->
        GuardAtom.Witness(model.baseOf(negated.shape) ?: negated.shape)
    negated is ExistsExpr && negated.collection != null -> collectionWitness(negated.collection)
    negated is PathExpr && negated.segs.isEmpty() -> GuardAtom.Flag(negated.root)
    else -> null
}

private fun Validator.collectionWitness(collection: CollectionExpr): GuardAtom.Witness? {
    val source = collection.bindings.firstOrNull()?.source as? PathExpr ?: return null
    if (source.root !in model.shapes && source.root !in model.refinements) return null
    return GuardAtom.Witness(model.baseOf(source.root) ?: source.root)
}

internal fun Validator.disarmed(rule: RuleDecl, atom: GuardAtom): Boolean = when (atom) {
    // a witness over a shape several shapes take with `is`, or a static union, is produced
    // by a record of any of them (Reference §9)
    is GuardAtom.Witness -> rule.body.statements().filterIsInstance<Creation>().any { model.typeAccepts(atom.shape, it.shape) }
    is GuardAtom.Flag -> rule.body.filterIsInstance<Assignment>()
        .any { it.target.segs.lastOrNull()?.name == atom.name || (it.target.segs.isEmpty() && it.target.root == atom.name) }
}

/** The shapes a body can create to produce the witness: a shape with records of its own, or
 *  every shape that takes a taken shape, or every operand of a static union (Reference §9). */
private fun Validator.witnessShapes(shape: String): List<String> =
    if (shape in model.shapes) listOf(shape) else model.basesOf(shape).toList()

/** V4: a rule behind the `after commit` boundary needs a guard, so a lost firing is found, and a backup schedule, so it is retried. */
private fun Validator.afterCommitProblem(rule: RuleDecl, noGuard: Boolean, noSchedule: Boolean): String? {
    val guardFix = "trigger the rule on a guard, a condition its body makes false by creating a record or setting a field"
    return when {
        noGuard && noSchedule -> "rule '${rule.name}' fires 'after commit', in a transaction of its own, so a firing " +
            "can be lost between the commit and the rule, and nothing would find it; $guardFix, " +
            "and add a backup schedule beside 'after commit' (Reference §18)"
        noGuard -> "rule '${rule.name}' fires 'after commit' and on a schedule without a guard, so a lost firing " +
            "would never be found and the schedule would fire it again for every record; $guardFix (Reference §18)"
        noSchedule -> "rule '${rule.name}' fires 'after commit' with a guard but no backup schedule, so a lost " +
            "firing would never be retried; add a schedule beside it, 'after commit, Hourly' (Reference §18)"
        else -> null
    }
}

internal fun Validator.checkBoundaryAndDisarm(rule: RuleDecl) {
    val atoms = guardAtoms(rule)
    val schedules = rule.triggers.filter { it != "commit" }
    if (rule.preposition == "after") {
        afterCommitProblem(rule, atoms.isEmpty(), schedules.isEmpty())?.let { problem -> diagnostics.add(Diagnostic("V4", problem)) }
    }
    val reEvaluates = rule.preposition == "after" || schedules.isNotEmpty()
    if (reEvaluates) checkDisarm(rule, atoms)
    // V7: a capture-reading rule is transaction-bound
    if (rule.leaving) checkCaptureAcrossBoundary(rule, reEvaluates)
}

/** V2: a guarded rule that can re-evaluate (boundary or tick) must disarm its own
 *  trigger. Falsifying any one conjunct falsifies the conjunction, so one
 *  disarmed atom discharges the proof — the others are conditions, not guards.
 *  Deleting the subject is the structural disarm: the trigger state has
 *  no member left to re-fire on (OQ37). */
private fun Validator.checkDisarm(rule: RuleDecl, atoms: List<GuardAtom>) {
    if (atoms.isEmpty() || atoms.any { disarmed(rule, it) } || deletesSubject(rule)) return
    val what = atoms.joinToString(" or ") { atom -> disarmingAction(atom) }
    diagnostics.add(Diagnostic("V2", "rule '${rule.name}' never leaves its trigger state, so it would fire again " +
        "for the same record; its body must $what to make its own condition false (Reference §18)"))
}

private fun Validator.disarmingAction(atom: GuardAtom): String = when (atom) {
    is GuardAtom.Witness -> "produce a " + witnessShapes(atom.shape).joinToString(" or ") { "'$it'" }
    is GuardAtom.Flag -> "assign '${atom.name}'"
}

private fun Validator.checkCaptureAcrossBoundary(rule: RuleDecl, reEvaluates: Boolean) {
    val scope = subjectScope(rule) ?: return
    if (bodyReadsCapturedMember(rule, scope) && reEvaluates)
        diagnostics.add(Diagnostic("V7", "rule '${rule.name}' reads a captured property, which does not survive " +
            "the transaction boundary it declares (Reference §13)"))
}

private fun Validator.bodyReadsCapturedMember(rule: RuleDecl, scope: String): Boolean {
    val capturedNames = model.membersOf(scope).values.filter { it.captured }.map { it.name }.toSet()
    if (capturedNames.isEmpty()) return false
    // a delete's target route is not read for the captured value
    return rule.body.statements().filterNot { it is DeleteStmt }
        .flatMap { statementReadExpressions(it) }
        .any { readsAnyRoot(it, capturedNames) }
}

/** Does [expr] read a path rooted at one of [roots], directly or through its value operands? */
private fun readsAnyRoot(expr: Expr, roots: Set<String>): Boolean =
    if (expr is PathExpr) expr.root in roots
    else valueOperands(expr).any { readsAnyRoot(it, roots) }
