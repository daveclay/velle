package velle.validation

import velle.*
import velle.spec.PathStart
import velle.spec.Scope

// ── signed consults: which way can a creation move this condition? ──────
//
// +1: creating an instance of the shape can only move the predicate
// toward true; -1: only toward false; 0: both directions (or unknown).
// Signs come from `exists` polarity under negation, refinement absorption,
// and count comparisons; everything else (sums, selectors, branches)
// stays 0 — coarse, and only ever coarser than the truth.
//
// Every walk below carries the [PolarityAccumulator] it signs into and
// `visiting`, the refinements and derived properties already walked, so a
// cycle through them ends.

private val comparisonOperators = setOf("<", "<=", ">", ">=", "==", "!=")

internal fun Validator.conditionPolarities(rule: RuleDecl): Polarities =
    polaritiesByConditionKey.getOrPut("rule:${rule.name}") {
        val base = model.baseOfExpr(rule.condition)
        val accumulator = PolarityAccumulator()
        if (base != null) signedRefExpr(rule.condition, base, +1, accumulator, mutableSetOf())
        accumulator.coverUnattributed(conditionSummary(rule))
        accumulator.toPolarities()
    }

internal fun Validator.refinementPolarities(name: String): Polarities =
    polaritiesByConditionKey.getOrPut("ref:$name") {
        val accumulator = PolarityAccumulator()
        val refinement = model.refinements[name]
        val base = model.baseOf(name)
        if (refinement != null && base != null) {
            signedRefExpr(refinement.expr, base, +1, accumulator, mutableSetOf(name))
            accumulator.coverUnattributed(model.predicateSummary(name))
        }
        accumulator.toPolarities()
    }

/** Signs a consult of [name] at [sign] (the name itself only where [mergeName]),
 *  and, the first time a refinement is reached, its own consults with their
 *  signs multiplied by [sign]. */
private fun Validator.absorbConsult(accumulator: PolarityAccumulator, name: String, sign: Int, visiting: MutableSet<String>, mergeName: Boolean) {
    if (mergeName) accumulator.merge(name, sign)
    if (name !in model.refinements || !visiting.add(name)) return
    val refinementPolarities = refinementPolarities(name)
    if (!refinementPolarities.complete) accumulator.incomplete = true
    refinementPolarities.signByConsult.forEach { (consult, consultSign) ->
        accumulator.merge(consult, if (consultSign == 0) 0 else consultSign * sign)
    }
}

private fun Validator.signedRefExpr(refExpr: RefExpr, scope: String, sign: Int, accumulator: PolarityAccumulator, visiting: MutableSet<String>) {
    when (refExpr) {
        is RefName -> signedRefName(refExpr, scope, sign, accumulator, visiting)
        is RefNot -> signedRefExpr(refExpr.inner, scope, -sign, accumulator, visiting)
        is RefAnd -> {
            signedRefExpr(refExpr.left, scope, sign, accumulator, visiting)
            signedRefExpr(refExpr.right, scope, sign, accumulator, visiting)
        }
        is RefOr -> {
            signedRefExpr(refExpr.left, scope, sign, accumulator, visiting)
            signedRefExpr(refExpr.right, scope, sign, accumulator, visiting)
        }
    }
}

private fun Validator.signedRefName(refName: RefName, scope: String, sign: Int, accumulator: PolarityAccumulator, visiting: MutableSet<String>) {
    // composition: the operand's internals, not its name — an
    // instance's membership here never flips by base creation alone
    if (refName.name in model.refinements) absorbConsult(accumulator, refName.name, sign, visiting, mergeName = false)
    val where = refName.where ?: return
    val whereScope = if (refName.name in model.shapes || refName.name in model.refinements) refName.name else scope
    signedExpr(where, Scope(whereScope), sign, accumulator, visiting)
}

/** A flat table: each kind of expression either signs its consults itself or
 *  hands its operands on at sign 0, where they move the predicate both ways. */
private fun Validator.signedExpr(expr: Expr?, scope: Scope, sign: Int, accumulator: PolarityAccumulator, visiting: MutableSet<String>) {
    when (expr) {
        null -> {}
        is NotExpr -> signedExpr(expr.inner, scope, -sign, accumulator, visiting)
        is Binary -> signedBinary(expr, scope, sign, accumulator, visiting)
        is ExistsExpr -> signedExists(expr, scope, sign, accumulator, visiting)
        is IsExpr -> signedMembershipTest(expr, scope, sign, accumulator, visiting)
        is PathExpr -> signedPath(expr, scope, sign, accumulator, visiting)
        // a selector selects across instances
        is SingularFor -> signedSelector(expr.shape, expr.forExpr, scope, accumulator, visiting)
        is ShapeForSource -> signedSelector(expr.shape, expr.forExpr, scope, accumulator, visiting)
        is AggCall -> signedCollection(expr.collection, scope, 0, accumulator, visiting)
        is SetExpr -> signedCollection(expr.collection, scope, 0, accumulator, visiting)
        // a walk reads its relationship on every record reached: the relationship's
        // own consults, at sign 0, plus the condition's
        is WalkExpr -> {
            followPath(PathExpr(expr.rel), scope, accumulator, visiting)
            signedExpr(expr.until, model.scopes.ofUntil(expr, scope), 0, accumulator, visiting)
        }
        // a branch moves the predicate both ways, and reads what its test shows (Reference §10)
        is IfExpr -> {
            signedExpr(expr.condition, scope, 0, accumulator, visiting)
            signedExpr(expr.thenExpr, model.scopes.ofThen(expr, scope), 0, accumulator, visiting)
            signedExpr(expr.elseExpr, model.scopes.ofElse(expr, scope), 0, accumulator, visiting)
        }
        is Access -> signedExpr(expr.target, scope, 0, accumulator, visiting)
        else -> valueOperands(expr).forEach { operand -> signedExpr(operand, scope, 0, accumulator, visiting) }
    }
}

private fun Validator.signedBinary(binary: Binary, scope: Scope, sign: Int, accumulator: PolarityAccumulator, visiting: MutableSet<String>) {
    when (binary.op) {
        "and", "or" -> {
            signedExpr(binary.left, scope, sign, accumulator, visiting)
            signedExpr(binary.right, model.scopes.ofRightSide(binary, scope), sign, accumulator, visiting)
        }
        in comparisonOperators -> signedComparison(binary, scope, sign, accumulator, visiting)
        else -> signedOperandsAtZero(binary, scope, accumulator, visiting)
    }
}

/** A comparison with a `count` on one side moves with the counted collection;
 *  any other comparison moves both ways. */
private fun Validator.signedComparison(comparison: Binary, scope: Scope, sign: Int, accumulator: PolarityAccumulator, visiting: MutableSet<String>) {
    val aggregate = comparison.left as? AggCall ?: comparison.right as? AggCall
    if (aggregate == null || aggregate.name != "count") {
        signedOperandsAtZero(comparison, scope, accumulator, visiting)
        return
    }
    val direction = growthDirection(comparison.op, aggregateOnLeft = comparison.left is AggCall, sign)
    signedCollection(aggregate.collection, scope, direction, accumulator, visiting)
}

/** The sign at which growing the aggregate moves the comparison: [sign] where
 *  the aggregate is on the greater side, its negation where it is on the lesser
 *  side, and 0 for an equality, which growth can move either way. */
private fun growthDirection(operator: String, aggregateOnLeft: Boolean, sign: Int): Int {
    val greaterThan = operator == ">" || operator == ">="
    val lessThan = operator == "<" || operator == "<="
    return when {
        greaterThan -> if (aggregateOnLeft) sign else -sign
        lessThan -> if (aggregateOnLeft) -sign else sign
        else -> 0
    }
}

private fun Validator.signedOperandsAtZero(binary: Binary, scope: Scope, accumulator: PolarityAccumulator, visiting: MutableSet<String>) {
    signedExpr(binary.left, scope, 0, accumulator, visiting)
    signedExpr(binary.right, scope, 0, accumulator, visiting)
}

private fun Validator.signedExists(exists: ExistsExpr, scope: Scope, sign: Int, accumulator: PolarityAccumulator, visiting: MutableSet<String>) {
    exists.shape?.let { shape -> absorbConsult(accumulator, shape, sign, visiting, mergeName = true) } // quantifies
    exists.collection?.let { collection -> signedCollection(collection, scope, sign, accumulator, visiting) }
}

private fun Validator.signedMembershipTest(membershipTest: IsExpr, scope: Scope, sign: Int, accumulator: PolarityAccumulator, visiting: MutableSet<String>) {
    // a membership test on a reference or the subject: internals
    // only — a fresh instance is unreferenced and not the subject
    membershipTest.refinement?.let { refinement -> absorbConsult(accumulator, refinement, sign, visiting, mergeName = false) }
    signedExpr(membershipTest.subject, scope, 0, accumulator, visiting)
}

private fun Validator.signedPath(path: PathExpr, scope: Scope, sign: Int, accumulator: PolarityAccumulator, visiting: MutableSet<String>) {
    when {
        path.segs.isEmpty() && path.root in model.refinements -> absorbConsult(accumulator, path.root, sign, visiting, mergeName = false)
        path.segs.isEmpty() && path.root in model.shapes -> accumulator.merge(path.root, sign)
        else -> followPath(path, scope, accumulator, visiting)
    }
}

private fun Validator.signedSelector(shape: String, forExpr: Expr, scope: Scope, accumulator: PolarityAccumulator, visiting: MutableSet<String>) {
    absorbConsult(accumulator, shape, 0, visiting, mergeName = true)
    signedExpr(forExpr, scope, 0, accumulator, visiting)
}

/** Member paths hide consults behind derived properties (`netPaid >=
 *  amount` consults `SuccessfulCharge` through `netPaid`'s sum): resolve
 *  the path and walk each derived member's formula, at sign 0 — a consult
 *  reached through a value read moves the predicate both ways. */
private fun Validator.followPath(path: PathExpr, scope: Scope, accumulator: PolarityAccumulator, visiting: MutableSet<String>) {
    val resolved = model.paths.resolve(path, scope)
    resolved.steps.forEach { step -> signedMemberRead(step.member, accumulator, visiting) }
    // an alias, or a name that does not resolve: fills cover it
    if (!resolved.resolves || resolved.start is PathStart.RecordName || resolved.start is PathStart.ValueName) accumulator.incomplete = true
}

/** Reading [member] consults its formula, if it is derived, and its element shape, if it is a collection. */
private fun Validator.signedMemberRead(member: MemberInfo, accumulator: PolarityAccumulator, visiting: MutableSet<String>) {
    val derived = member.derived
    if (derived != null && visiting.add("prop:${member.owner}.${member.name}"))
        signedExpr(derived.expr, Scope(member.owner), 0, accumulator, visiting)
    (member.type as? VType.Coll)?.let { collectionType -> accumulator.merge(collectionType.shape, 0) }
}

private fun Validator.signedCollection(collection: CollectionExpr, scope: Scope, sign: Int, accumulator: PolarityAccumulator, visiting: MutableSet<String>) {
    // a `where` that is exactly one refinement-membership atom gates fresh
    // rows behind that refinement: the consult is the refinement (name
    // merged — a fresh element enters through it or not at all), never
    // the collection's bare element shape
    val refinementFilter = (collection.where as? PathExpr)?.takeIf { it.segs.isEmpty() && it.root in model.refinements }
    // each source reads the names before it, and the `where` reads bare names on the element while
    // `this` stays the subject (`velle.spec.ScopeRules`)
    val filter = model.scopes.ofFilter(collection, scope)
    collection.bindings.forEachIndexed { index, binding ->
        signedBindingSource(binding.source, filter.sources[index], sign, filteredByRefinement = refinementFilter != null, accumulator, visiting)
    }
    if (refinementFilter != null) absorbConsult(accumulator, refinementFilter.root, sign, visiting, mergeName = true)
    else collection.where?.let { where -> signedExpr(where, filter.where, sign, accumulator, visiting) }
}

/** Signs one collection binding's source. */
private fun Validator.signedBindingSource(
    source: Expr, scope: Scope, sign: Int, filteredByRefinement: Boolean,
    accumulator: PolarityAccumulator, visiting: MutableSet<String>,
) {
    when (source) {
        is PathExpr -> signedPathSource(source, scope, sign, filteredByRefinement, accumulator, visiting)
        is ShapeForSource -> {
            absorbConsult(accumulator, source.shape, sign, visiting, mergeName = true)
            signedExpr(source.forExpr, scope, 0, accumulator, visiting)
        }
        else -> {
            accumulator.incomplete = true
            signedExpr(source, scope, 0, accumulator, visiting)
        }
    }
}

private fun Validator.signedPathSource(
    source: PathExpr, scope: Scope, sign: Int, filteredByRefinement: Boolean,
    accumulator: PolarityAccumulator, visiting: MutableSet<String>,
) {
    if (source.segs.isEmpty() && (source.root in model.shapes || source.root in model.refinements)) {
        absorbConsult(accumulator, source.root, sign, visiting, mergeName = true)
        return
    }
    val elementShape = model.paths.resolve(source, scope).endShape
    if (elementShape == null) accumulator.incomplete = true
    else if (!filteredByRefinement) accumulator.merge(elementShape, sign)
}
