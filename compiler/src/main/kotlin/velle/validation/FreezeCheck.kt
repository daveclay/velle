package velle.validation

import velle.*

// ── V5: freeze disjointness ──────────────────────────────────────────────

internal fun Validator.checkFreezes() {
    for ((refinementName, refinement) in model.refinements) {
        val frozen = refinement.members.filterIsInstance<FrozenClause>().singleOrNull() ?: continue
        val base = model.baseOf(refinementName) ?: continue
        val frozenFields = frozen.fields.ifEmpty {
            model.shapes[base]?.members?.filterIsInstance<StoredProp>()?.map { field -> field.name } ?: emptyList()
        }
        writes.filter { write -> write.owner == base && write.member.name in frozenFields }
            .forEach { write -> checkFrozenWrite(write, refinementName, base) }
    }
}

/** [write] to a field frozen by [refinementName] needs a condition excluding that refinement's members. */
private fun Validator.checkFrozenWrite(write: Write, refinementName: String, base: String) {
    val route = PathExpr(write.target.root, write.target.segs.dropLast(1))
    val excluded = conditionConjuncts(write.rule.condition).any { conjunct ->
        conjunct == NotExpr(IsExpr(route, "refinement", refinementName))
    }
    if (!excluded)
        diagnostics.add(Diagnostic("V5", "rule '${write.rule.name}' writes $base.${write.member.name}, frozen by '$refinementName' — " +
            "the trigger does not provably exclude membership; partition the act (Reference §8, \"Frozen fields\")"))
}
