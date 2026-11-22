package velle.validation

import velle.*

// ── V10: never obligations (coarse classification) ───────────────────────

internal fun Validator.checkNevers() {
    // a write names the shape written; a read names the declaration, which for a field
    // taken with `is` is the taken shape's (Reference §9), so match through the declaration
    val written = writes.map { write -> declarationOf(write.owner, write.member.name) }.toSet()
    for (never in model.nevers) checkNeverReadsNoAssignedField(never, written)
}

/** [written]: every field some rule assigns, as the declaration a read of it names. */
private fun Validator.checkNeverReadsNoAssignedField(never: NeverDecl, written: Set<Pair<String, String>>) {
    // a refusal's `is held` is answered when the request's commit ends, with the references its
    // rules moved in place (evaluation.md, "The door's moment"), so those reads ask no proof of the rules
    val isRefusal = model.basesOfExpr(never.target).all { base -> base in model.transients }
    val reads = ReadSummary(includesHeldReads = !isRefusal)
    collectComposite(never.target, reads)
    (reads.fields intersect written).forEach { (shape, field) ->
        val writer = writes.first { write -> declarationOf(write.owner, write.member.name) == (shape to field) }.rule.name
        diagnostics.add(Diagnostic("V10", "a 'never' reads '$shape.$field', which rule '$writer' assigns, so it is a " +
            "guarantee that the rules keep it true, and the compiler cannot yet prove one over every " +
            "assignment; read a field no rule assigns, or derive '$field' from the records the rule " +
            "writes (Reference §21)"))
    }
}

/** The declaration a field of [shape] is read as: the taken shape's when it came by `is`. */
private fun Validator.declarationOf(shape: String, field: String): Pair<String, String> =
    (model.membersOf(shape)[field]?.owner ?: shape) to field
