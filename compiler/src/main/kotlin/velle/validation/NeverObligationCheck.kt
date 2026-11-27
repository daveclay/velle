package velle.validation

import velle.*

// ── V10: never obligations (coarse classification) ───────────────────────

internal fun Validator.checkNevers() {
    // a write and a read each name the base shape whose records hold the field (Reference §9)
    val written = writes.map { write -> write.owner to write.member.name }.toSet()
    for (never in model.nevers) checkNeverReadsNoAssignedField(never, written)
}

/** [written]: every field some rule assigns, by the base shape written. */
private fun Validator.checkNeverReadsNoAssignedField(never: NeverDecl, written: Set<Pair<String, String>>) {
    // a refusal's `is held` is answered when the request's commit ends, with the references its
    // rules moved in place (evaluation.md, "The door's moment"), so those reads ask no proof of the rules
    val bases = model.basesOfExpr(never.target)
    val isRefusal = bases.all { base -> base in model.transients }
    val reads = ReadSummary(includesHeldReads = !isRefusal)
    collectComposite(never.target, reads)
    (reads.fields intersect written).forEach { (shape, field) ->
        val writer = writes.first { write -> (write.owner to write.member.name) == (shape to field) }.rule.name
        val message = if (isRefusal) refusalReadsAssignedField(bases, shape, field, writer)
            else guaranteeReadsAssignedField(shape, field, writer)
        diagnostics.add(Diagnostic("V10", message))
    }
}

/** A `never` over transient acts only is a refusal: it binds the caller, not the rules (Reference §21). */
private fun refusalReadsAssignedField(requests: Set<String>, shape: String, field: String, writer: String): String {
    val requestNames = requests.sorted().joinToString(" or ") { request -> "'$request'" }
    return "a 'never' over the request $requestNames reads '$shape.$field', which rule '$writer' assigns, and " +
        "the compiler cannot yet check a refusal that reads a field a rule assigns. Read a field no rule " +
        "assigns, or derive '$field' from the records the rule writes; a refusal that reads records the " +
        "request's own rules create is checked after those rules run, so it sees them (Reference §21)"
}

private fun guaranteeReadsAssignedField(shape: String, field: String, writer: String): String =
    "a 'never' reads '$shape.$field', which rule '$writer' assigns, so it is a guarantee that the rules keep " +
        "it true, and the compiler cannot yet prove one over every assignment; read a field no rule assigns, " +
        "or derive '$field' from the records the rule writes (Reference §21)"
