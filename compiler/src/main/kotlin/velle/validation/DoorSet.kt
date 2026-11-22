package velle.validation

import velle.*

// ── the door set: prohibitions checked at the act's own commit (Reference §21,
// ruled 2026-10-14) ───────────────────────────────────────────────────────
//
// A prohibition constrains the settled transaction, and its rejection names
// the act in flight. One whose predicate reads nothing any rule writes,
// creates, or deletes cannot change its verdict by the rules running, so the
// runtime evaluates it once, at the act's commit before any rule, and skips
// it at the transaction end: the same verdict, no second evaluation, and no
// rule ever runs on a state the prohibition forbids. Every other prohibition
// is evaluated at the end only. The set is by ordinal into `model.nevers`.

/** Everything the spec's rules can change: the fields they assign, the
 *  shapes owning those fields, and the base shapes they create. */
private class RuleChanges(
    val writtenFields: Set<Pair<String, String>>,
    val writtenShapes: Set<String>,
    val createdBases: Set<String>,
)

internal fun Validator.findDoorNevers(): Set<Int> {
    val writtenFields = writes.map { it.owner to it.member.name }.toSet()
    val createdBases = model.rules.values.flatMap { rule ->
        rule.body.statements().filterIsInstance<Creation>().map { model.baseOf(it.shape) ?: it.shape }
    }.toSet()
    val ruleChanges = RuleChanges(writtenFields, writtenFields.map { it.first }.toSet(), createdBases)
    return model.nevers.indices.filter { index -> readsNothingRulesChange(model.nevers[index], ruleChanges) }.toSet()
}

private fun Validator.readsNothingRulesChange(never: NeverDecl, ruleChanges: RuleChanges): Boolean {
    val reads = model.summaryOfRefExpr(never.target)
    if (reads.opaque) return false
    // a never over a union or a taken shape (Reference §9) consults each operand or taker,
    // so a rule creating a record of one keeps the never at the transaction end
    val consultedBases = (reads.touchedShapes() + model.basesOfExpr(never.target))
        .flatMap { model.basesOf(it) }.toSet()
    // an `on update` timestamp advances at every write to its shape
    val updateStampReads = reads.collFields.filter { (shape, field) -> field in updateTimestamps(model.baseOf(shape) ?: shape) }
    return (reads.fields + reads.collFields).none { it in ruleChanges.writtenFields } &&
        updateStampReads.none { it.first in ruleChanges.writtenShapes } &&
        consultedBases.none { it in ruleChanges.createdBases || it in model.deletedShapes }
}
