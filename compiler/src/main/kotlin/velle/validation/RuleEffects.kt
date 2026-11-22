package velle.validation

import velle.*

// ── rule effects: what a rule's body changes ─────────────────────────────

/** What one rule's body changes: the fields it assigns as (owner shape,
 *  field) pairs, `on update` timestamps those writes advance included; the
 *  shapes it creates; and the shapes it deletes (OQ37). */
internal data class RuleEffects(
    val assignedFields: Set<Pair<String, String>>,
    val createdShapes: Set<String>,
    val deletedShapes: Set<String>,
) {
    fun isEmpty() = listOf(assignedFields, createdShapes, deletedShapes).all { it.isEmpty() }
}

internal fun Validator.ruleEffects(rule: RuleDecl): RuleEffects =
    effectsByRuleName.getOrPut(rule.name) {
        val assignedFields = mutableSetOf<Pair<String, String>>()
        writes.filter { it.rule === rule }.forEach { write ->
            assignedFields.add(write.owner to write.member.name)
            updateTimestamps(write.owner).forEach { timestamp -> assignedFields.add(write.owner to timestamp) }
        }
        val createdShapes = rule.body.statements().filterIsInstance<Creation>().map { it.shape }.toSet()
        val deletedShapes = model.deleteSites.filter { it.rule === rule }.map { it.shape }.toSet()
        RuleEffects(assignedFields, createdShapes, deletedShapes)
    }

/** The `on update` timestamps of [shape]: every write to the shape advances them. */
internal fun Validator.updateTimestamps(shape: String): Set<String> = updateTimestampsByShape.getOrPut(shape) {
    model.shapes[shape]?.members?.filterIsInstance<TimestampProp>()
        ?.filter { it.on == "update" }?.map { it.name }?.toSet() ?: emptySet()
}
