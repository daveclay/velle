package velle.validation

import velle.*

// ── read-write conflicts: which of a rule's effects does a read consult? ─

/** What of [rule]'s effects does a summary of [reads] consult? Human-readable
 *  conflict descriptions; empty means provably no read-write conflict. */
internal fun Validator.readConflicts(rule: RuleDecl, reads: ReadSummary): List<String> {
    val effects = ruleEffects(rule)
    if (reads.opaque)
        return if (effects.isEmpty()) emptyList()
        else listOf("state its summary cannot attribute (opaque — treated as reading anything)")
    return assignedFieldConflicts(effects, reads) +
        createdShapeConflicts(effects, reads) +
        deletedShapeConflicts(effects, reads)
}

private fun assignedFieldConflicts(effects: RuleEffects, reads: ReadSummary): List<String> =
    (effects.assignedFields intersect reads.fields).map { (owner, field) -> "$owner.$field" } +
        (effects.assignedFields intersect reads.collFields).map { (owner, field) -> "$owner.$field (a timestamp its write advances)" }

/** A created instance changes an existence or aggregate read only where a
 *  fresh instance can actually satisfy what the read consults. */
private fun Validator.createdShapeConflicts(effects: RuleEffects, reads: ReadSummary): List<String> {
    val consultedNames = reads.existsShapes + reads.collShapes
    return effects.createdShapes.filter { createdShape ->
        consultedNames.filter { model.baseOf(it) == createdShape }
            .any { it in model.shapes || !freshCannotEnter(it, effects.createdShapes) }
    }.map { createdShape -> "instances of '$createdShape' (existence or an aggregate)" }
}

/** A deletion changes any existence or aggregate read of the shape, and any
 *  read through a reference at it (the absorbing `is none`, OQ37). */
private fun Validator.deletedShapeConflicts(effects: RuleEffects, reads: ReadSummary): List<String> {
    val consultedNames = reads.existsShapes + reads.collShapes
    return effects.deletedShapes.filter { deletedShape ->
        consultedNames.any { model.baseOf(it) == deletedShape } || readsReferenceTo(reads, deletedShape)
    }.map { deletedShape -> "instances of '$deletedShape' (a delete the read consults)" }
}

/** Does [reads] read a reference-typed field whose target's base is [shape]? */
internal fun Validator.readsReferenceTo(reads: ReadSummary, shape: String): Boolean =
    (reads.fields + reads.collFields).any { (owner, field) ->
        model.membersOf(owner)[field]?.type?.let { type -> model.referencesRecordsOf(type, shape) } == true
    }
