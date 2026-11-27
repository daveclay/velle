package velle.validation

import velle.*
import velle.spec.partsJoinedByOr

// ── V24: referential completeness (OQ37, OQ162) ──────────────────────
//
// A required reference holds the record it points at (Reference §6, §12).
// A rule may delete a record of a shape some required `one` field can point at
// only where no such field points at it. The author says so with `x is held`:
// a `never` over the request that refuses a held record, or the deleting rule's
// own condition excluding one. The other way is to delete the pointing record
// in the same rule. A reference declared `? initially required`, an optional
// one, and a `many` field hold nothing and need nothing. The deletion-side
// half of V24 is in DeletionSideReferenceCheck.kt.

internal fun Validator.checkReferentialCompleteness() {
    for (site in model.deleteSites) {
        val holdingFields = unresolvedHoldingFields(site)
        if (holdingFields.isEmpty()) continue
        when {
            refusedWhileHeld(site) -> {}
            conditionedOnNotHeld(site) -> reportReferencesMadeBesideTheDelete(site, holdingFields)
            else -> holdingFields.forEach { holding -> reportHeldRecordDeleted(site, holding) }
        }
    }
}

/** The fields that hold a record [site] deletes, leaving out the shapes the same rule also deletes. */
private fun Validator.unresolvedHoldingFields(site: DeleteSite): List<HoldingField> {
    val shapesDeletedByTheRule = model.deleteSites
        .filter { other -> other.rule === site.rule }
        .map { other -> other.shape }
        .toSet()
    return model.holdingFields(site.shape).filter { holding -> holding.shape !in shapesDeletedByTheRule }
}

/** `<target> is held`, or `<target> is R` where R is exactly the held records of its base. */
private fun Validator.testsHeld(test: Expr, target: PathExpr): Boolean {
    val isTest = test as? IsExpr ?: return false
    if (isTest.subject != target) return false
    if (isTest.kind == "held") return true
    val refinement = isTest.refinement?.let { name -> model.refinements[name] } ?: return false
    val predicate = (refinement.expr as? RefName)?.where
    return predicate == IsExpr(PathExpr("this"), "held")
}

/** A `never` over the transient act that triggers the rule refuses every request whose target is held. */
private fun Validator.refusedWhileHeld(site: DeleteSite): Boolean {
    val act = triggeringRequest(site.rule) ?: return false
    return model.nevers.any { never ->
        val forbiddenRecords = never.target as? RefName ?: return@any false
        forbiddenRecords.name == act &&
            forbiddenRecords.where?.let { forbidden -> forbidsHeld(forbidden, site.stmt.target) } == true
    }
}

/** Whether [forbidden], or one of its `or` operands, tests that [target] is held. */
private fun Validator.forbidsHeld(forbidden: Expr, target: PathExpr): Boolean =
    partsJoinedByOr(forbidden).any { disjunct -> testsHeld(disjunct, target) }

/** The transient act a rule fires for, null for a rule over kept records or on a schedule. */
private fun Validator.triggeringRequest(rule: RuleDecl): String? =
    model.baseOfExpr(rule.condition)?.takeIf { base -> base in model.transients }

/** Whether the deleting rule's condition has the conjunct `<target> is not held`. */
private fun Validator.conditionedOnNotHeld(site: DeleteSite): Boolean =
    conditionConjuncts(site.rule.condition).any { conjunct -> conjunct is NotExpr && testsHeld(conjunct.inner, site.stmt.target) }

/** The V24 error naming [holding] and every spelling that would accept the delete. */
private fun Validator.reportHeldRecordDeleted(site: DeleteSite, holding: HoldingField) {
    val target = Printer.expr(site.stmt.target)
    val refusalSpelling = triggeringRequest(site.rule)
        ?.let { act -> "refuse the request with 'never ($act where $target is held)', " }
        .orEmpty()
    diagnostics.add(Diagnostic("V24", "${deleteSiteLabel(site)}, and '${holding.shape}.${holding.field}: one ${holding.declaredType}' " +
        "holds the record it points at, so a '${site.shape}' cannot be deleted while a '${holding.shape}' points at it: " +
        "${refusalSpelling}condition the rule on '$target is not held', delete the '${holding.shape}' in the same rule, " +
        "or declare the reference '${holding.field}: one ${holding.declaredType}? initially required' so it goes absent " +
        "when the '${site.shape}' is deleted (Reference §12)"))
}

/**
 * A condition is read when the rule fires, so `x is not held` cannot see a required reference
 * the same commit points at a record of the deleted shape. Only a `never` is read when the
 * commit ends (evaluation.md, "The door's moment").
 */
private fun Validator.reportReferencesMadeBesideTheDelete(site: DeleteSite, holdingFields: List<HoldingField>) {
    for (rule in model.rules.values) {
        if (rule !== site.rule && (provablyDisjoint(rule, site.rule) || !canCoFire(rule, site.rule))) continue
        val pointedField = holdingFields.firstOrNull { holding -> pointsRequiredReference(rule, holding) } ?: continue
        reportReferenceMadeBesideTheDelete(site, rule, pointedField)
    }
}

/** Whether [rule] creates a record of [holding]'s shape or assigns the holding field. */
private fun Validator.pointsRequiredReference(rule: RuleDecl, holding: HoldingField): Boolean =
    rule.creations().any { creation -> (model.baseOf(creation.shape) ?: creation.shape) == holding.shape } ||
        writes.any { write -> write.rule === rule && write.owner == holding.shape && write.member.name == holding.field }

/** The V24 error for [rule] pointing [pointedField] at a deleted record in the commit whose condition could not see it. */
private fun Validator.reportReferenceMadeBesideTheDelete(site: DeleteSite, rule: RuleDecl, pointedField: HoldingField) {
    val target = Printer.expr(site.stmt.target)
    val acceptedSpelling = triggeringRequest(site.rule)
        ?.let { act -> "refuse the request with 'never ($act where $target is held)', which is read when the commit ends, and drop the condition" }
        ?: "move one of the two to a commit of its own"
    diagnostics.add(Diagnostic("V24", "${deleteSiteLabel(site)} on the condition '$target is not held', and rule '${rule.name}' " +
        "can point '${pointedField.shape}.${pointedField.field}' at a '${site.shape}' in the same commit. The condition is read " +
        "before the rules run, so it cannot see that reference: $acceptedSpelling (Reference §12)"))
}
