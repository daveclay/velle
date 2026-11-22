package velle.validation

import velle.*

// ── can two rules fire from one commit? ──────────────────────────────────
//
// Sharper than [canShareCommit] ("some commit kind affects both
// conditions"): a rule gains a subject at a commit only when the commit
// can flip its condition in the firing direction — TOWARD membership for
// an entry rule, AWAY for a leaving rule. A creation moves a predicate
// only in the polarity the predicate consults the shape at (creating a
// `StockReservation` can only satisfy `exists StockReservation for this`
// and only falsify `not exists StockReservation for this`), a field write
// is value-dependent (either direction), a freshly created base instance
// can never LEAVE anything (it was a member of nothing), and a fresh
// instance cannot satisfy a positive `exists ... for this` over a shape
// the same commit does not create (nothing can reference it yet).

internal fun Validator.canCoFire(first: RuleDecl, second: RuleDecl): Boolean =
    possibleCommits.any { commit -> canGainSubject(commit, first) && canGainSubject(commit, second) }

/** Can two sibling rules interfere: not provably disjoint, able to fire
 *  from one commit, and not an entrant and leaver that exclude each other? */
internal fun Validator.canInterfere(first: RuleDecl, second: RuleDecl): Boolean =
    !provablyDisjoint(first, second) && canCoFire(first, second) && !enterLeaveExclusive(first, second)

private fun Validator.canGainSubject(commit: PossibleCommit, rule: RuleDecl): Boolean {
    val bases = model.basesOfExpr(rule.condition)
    if (bases.isEmpty()) return false
    if (bases.any { it in model.transients })
        return commit is PossibleCommit.Creates && commit.shape in bases && !rule.leaving
    // the deletion-only trigger gains a subject at exactly one kind of commit
    reachDecidedByRuleAlone(commit, rule, bases)?.let { return it }
    return when (commit) {
        is PossibleCommit.Creates -> creationCanGainSubject(commit, rule, bases)
        is PossibleCommit.Assigns -> affects(commit, rule) // value-dependent: either direction
        is PossibleCommit.Deletes -> deletionCanGainSubject(commit, rule, bases)
    }
}

/** Does a consult moving at [sign] move the condition the way [leaving] fires: away for a leaving rule, toward otherwise? */
private fun movesTowardFiring(sign: Int, leaving: Boolean): Boolean =
    if (leaving) sign <= 0 else sign >= 0

private fun Validator.creationCanGainSubject(commit: PossibleCommit.Creates, rule: RuleDecl, bases: Set<String>): Boolean {
    val coCreated = shapesCreatedWith(commit)
    if (commit.shape in bases) {
        if (rule.leaving) return false // a fresh instance was never a member
        return positiveCorrelatedExistsTargets(conditionConjuncts(rule.condition))
            .none { it !in coCreated }
    }
    // a creation flips the condition only through a consult a
    // fresh instance can satisfy, and only in that consult's polarity
    val signByConsult = conditionPolarities(rule).signByConsult
    return signByConsult.keys.filter { model.baseOf(it) == commit.shape }
        .filter { it in model.shapes || !freshCannotEnter(it, coCreated) }
        .any { consult -> movesTowardFiring(signByConsult.getValue(consult), rule.leaving) }
}

/** The shapes created in the same commit as [commit]'s: everything its rule creates. */
private fun shapesCreatedWith(commit: PossibleCommit.Creates): Set<String> =
    commit.byRule?.body?.statements()?.filterIsInstance<Creation>()?.map { it.shape }?.toSet()
        ?: setOf(commit.shape) // an act's commit carries exactly the act

private fun Validator.deletionCanGainSubject(commit: PossibleCommit.Deletes, rule: RuleDecl, bases: Set<String>): Boolean {
    // creation's mirror, signs flipped: a deletion can only unsatisfy a
    // consult, so it moves a predicate at the consult's NEGATED polarity;
    // the deleted instance itself can only LEAVE; and a read through a
    // reference at the deleted shape flips value-dependently (either
    // direction — `is none` becomes true, `is some` false)
    // deleting the subject's own shape is the exit cause only the
    // deletion-side forms react to (Reference §13); a predicate-exit
    // rule sees it through consults alone
    if (commit.shape in bases && rule.trigger.onDeletion) return true
    val signByConsult = conditionPolarities(rule).signByConsult
    return signByConsult.keys.filter { model.baseOf(it) == commit.shape }
        .any { consult -> movesTowardFiring(-signByConsult.getValue(consult), rule.leaving) } ||
        conditionReadsRefTo(rule, commit.shape)
}

// ── entrant/leaver exclusivity ───────────────────────────────────────────
//
// The episodes discharge: [entering] fires on ENTRY into a condition whose
// conjuncts include every conjunct of the condition [leaving] LEAVES. One
// instance cannot do both at one commit — the entrant's post-state makes the
// shared conjuncts true, the leaver's makes them false. Different instances
// at one commit are ruled out when every commit kind that could fire both is
// a write to the shared base's own columns, read only through the instance's
// own fields (no exists or aggregate over the base, no relationship hop back
// onto it) — then the only instance whose membership the write can flip
// is the written one, on both sides.

/** Of two rules, the one that fires on entry and the one that fires on leaving. */
private class EntrantAndLeaver(val entering: RuleDecl, val leaving: RuleDecl)

private fun entrantAndLeaver(first: RuleDecl, second: RuleDecl): EntrantAndLeaver? = when {
    !first.leaving && second.leaving -> EntrantAndLeaver(first, second)
    first.leaving && !second.leaving -> EntrantAndLeaver(second, first)
    else -> null
}

internal fun Validator.enterLeaveExclusive(first: RuleDecl, second: RuleDecl): Boolean {
    val rules = entrantAndLeaver(first, second) ?: return false
    val base = model.baseOfExpr(rules.entering.condition) ?: return false
    if (base != model.baseOfExpr(rules.leaving.condition)) return false
    val enteredConjuncts = conditionConjuncts(rules.entering.condition)
    val leftConjuncts = conditionConjuncts(rules.leaving.condition)
    if (leftConjuncts.isEmpty() || !enteredConjuncts.containsAll(leftConjuncts)) return false
    val enteredOnlyConjuncts = enteredConjuncts - leftConjuncts.toSet()
    val sharedCommits = possibleCommits.filter { canGainSubject(it, rules.entering) && canGainSubject(it, rules.leaving) }
    return sharedCommits.all { commit -> flipsOnlyTheWrittenInstance(commit, base, leftConjuncts, enteredOnlyConjuncts) }
}

private fun Validator.flipsOnlyTheWrittenInstance(
    commit: PossibleCommit, base: String, leftConjuncts: List<Expr>, enteredOnlyConjuncts: List<Expr>,
): Boolean =
    commit is PossibleCommit.Assigns && commit.shape == base &&
        ownColumnOnly(leftConjuncts, base, commit.field) &&
        enteredOnlyConjuncts.none { conjunct -> conjunctReads(conjunct, base, commit.field) }

/** Every conjunct reading ([base], [field]) does so through the subject's
 *  own columns only: seg-free or `this`-rooted single-segment paths. */
private fun Validator.ownColumnOnly(conjuncts: List<Expr>, base: String, field: String): Boolean =
    conjuncts.all { conjunct -> !conjunctReads(conjunct, base, field) || readsOnlyOwnColumns(conjunct) }

/** Does [expr] read nothing but its subject's own columns? A literal reads nothing. */
private fun readsOnlyOwnColumns(expr: Expr): Boolean = when {
    expr is PathExpr -> isOwnColumn(expr)
    expr is IsExpr -> readsOnlyOwnColumns(expr.subject)
    expr::class in otherInstanceReaders -> false
    else -> valueOperands(expr).all { readsOnlyOwnColumns(it) }
}

/** A seg-free path, or a `this`-rooted single-segment one. */
private fun isOwnColumn(path: PathExpr): Boolean =
    path.segs.isEmpty() || (path.root == "this" && path.segs.size == 1)

/** Exists, aggregates, and selectors reach other instances. */
private val otherInstanceReaders =
    setOf(ExistsExpr::class, AggCall::class, SingularFor::class, ShapeForSource::class, SetExpr::class, Access::class, WalkExpr::class)

private fun Validator.conjunctReads(conjunct: Expr, base: String, field: String): Boolean {
    val reads = ReadSummary()
    model.collectExpr(conjunct, base, reads)
    return reads.opaque || (base to field) in reads.fields || (base to field) in reads.collFields
}
