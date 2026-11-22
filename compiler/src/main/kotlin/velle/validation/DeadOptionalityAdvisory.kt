package velle.validation

import velle.*

// ── A2 (advisory): dead optionality (OQ37) ───────────────────────────────
//
// OQ37-R10's converse diagnostic: an `? initially required` field nothing
// can ever make absent — no deleter of its target, no writer of the
// field — is `one X` wearing a costume.

internal fun Validator.checkDeadOptionality() {
    for ((shapeName, shape) in model.shapes) {
        for (field in shape.members.filterIsInstance<StoredProp>()) {
            if (field.initiallyRequired) adviseOnDeadOptionality(shapeName, field)
        }
    }
}

/** The A2 advisory for [field] of [shapeName] when no rule deletes its target and no rule assigns it. */
private fun Validator.adviseOnDeadOptionality(shapeName: String, field: StoredProp) {
    val targetShape = (field.type as? RelType)?.shape
    val targetDeletable = targetShape?.let { shape -> shape in model.deletedShapes } == true
    val written = writes.any { write -> write.owner == shapeName && write.member.name == field.name }
    if (targetDeletable || written) return
    diagnostics.add(Diagnostic("A2", "'$shapeName.${field.name}' is '? initially required' but " +
        "nothing can make it absent — no rule deletes " +
        (targetShape?.let { shape -> "'$shape'" } ?: "its target") +
        " and no rule assigns the field; did you mean the plain required form? (OQ37)",
        advisory = true))
}
