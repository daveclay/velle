package velle.validation

import velle.*
import velle.spec.PathStart
import velle.spec.ResolvedPath
import velle.spec.Scope

// ── writes ───────────────────────────────────────────────────────────────

internal data class Write(val rule: RuleDecl, val owner: String, val member: MemberInfo, val target: PathExpr,
                         val value: Expr,
                         /** the target traverses a `many` hop: one write per member (Reference §6);
                          *  one-writer treats it as a write to the field on every instance (V1, coarse) */
                         val fanOut: Boolean = false)

/** Resolves [assignment]'s target to the field it writes, or reports why it
 *  cannot be written and returns null. [blockRecordShape] holds the `for each`
 *  name the target goes through and the shape of its records, when V34 accepts
 *  the assignment in its block: one write per record, as through a collection. */
internal fun Validator.resolveWrite(
    rule: RuleDecl, scope: String, assignment: Assignment, blockRecordShape: Map<String, String> = emptyMap(),
): Write? {
    val target = assignment.target
    val writeScope = blockRecordShape.entries.fold(Scope(scope)) { soFar, (name, shape) -> soFar.withRecordName(name, shape) }
    val resolved = model.paths.resolve(target, writeScope)
    if (resolved.start is PathStart.Element && resolved.steps.isEmpty() || resolved.start is PathStart.ShapeName) {
        // an assignment through a `for each` name that V34 refuses has its one message, V34's
        if (target.root !in forEachNames)
            diagnostics.add(Diagnostic("F1", "rule '${rule.name}' assigns through unknown member '${target.root}'"))
        return null
    }
    var fannedOut = resolved.start is PathStart.RecordName
    val lastIndex = namesWrittenThrough(resolved) - 1
    for ((index, step) in resolved.steps.withIndex()) {
        if (index == lastIndex) {
            val owner = model.baseOf(step.readOn) ?: step.readOn
            val targetText = if (target.segs.isEmpty()) target.root else Printer.expr(target)
            return writeOfMember(rule, owner, step.member, assignment, fannedOut, targetText)
        }
        fannedOut = stepThrough(rule, step.member, step.readOn, atPenultimate = index == lastIndex - 1, fannedOut) ?: return null
    }
    resolved.unresolved?.readOn?.let { readOn ->
        diagnostics.add(Diagnostic("F1", "rule '${rule.name}' assigns through unknown member '${resolved.unresolved.name}' of '$readOn'"))
    }
    return null
}

/** How many names of the target are members a write goes through or lands on: the root too, when it is a member itself. */
private fun namesWrittenThrough(resolved: ResolvedPath): Int {
    val segments = resolved.path?.segs?.size ?: 0
    return if (resolved.start is PathStart.Element) segments + 1 else segments
}

private fun Validator.writeOfMember(
    rule: RuleDecl, owner: String, member: MemberInfo, assignment: Assignment, fanOut: Boolean, targetText: String,
): Write? {
    if (member.actor) {
        diagnostics.add(Diagnostic("F3", "rule '${rule.name}' assigns '$targetText' — the actor is a fact of the act's commit, never assigned (Reference §22)"))
        return null
    }
    return Write(rule, owner, member, assignment.target, assignment.value, fanOut)
}

/** Steps through [member] of [ownerShape] toward the written field: whether
 *  the write has fanned out after it, or null where the path cannot go on
 *  (reported, unless the member reaches no shape). */
private fun Validator.stepThrough(rule: RuleDecl, member: MemberInfo, ownerShape: String, atPenultimate: Boolean, fannedOut: Boolean): Boolean? {
    if (member.type is VType.Coll) return fanOutStep(rule, member, ownerShape, atPenultimate, fannedOut)
    if (member.type is VType.CollS) {
        diagnostics.add(Diagnostic("V20", "rule '${rule.name}' assigns through scalar collection '$ownerShape.${member.name}'"))
        return null
    }
    member.type.instanceShape() ?: return null
    return fannedOut
}

/** V20: a collection path fans out exactly at the penultimate hop —
 *  it writes a field *of* the members, never through them (Reference §6). */
private fun Validator.fanOutStep(rule: RuleDecl, member: MemberInfo, ownerShape: String, atPenultimate: Boolean, fannedOut: Boolean): Boolean? {
    if (!atPenultimate) {
        diagnostics.add(Diagnostic("V20", "rule '${rule.name}' assigns through '$ownerShape.${member.name}' and onward — " +
            "a collection path writes a field of the collection's members, never through them; " +
            "the deeper write is its own rule on the shape that owns the field (Reference §6)"))
        return null
    }
    if (fannedOut) {
        diagnostics.add(Diagnostic("V20", "rule '${rule.name}' assigns through two `many` hops — " +
            "a fan-out traverses exactly one (Reference §6)"))
        return null
    }
    return true
}

internal fun Validator.findWrites(): List<Write> {
    return model.rules.values.flatMap { rule ->
        val scope = subjectScope(rule) ?: return@flatMap emptyList()
        rule.body.filterIsInstance<Assignment>().mapNotNull { resolveWrite(rule, scope, it) } + blockWrites(rule, scope)
    }
}

/** The writes of the assignments V34 accepts inside the rule's `for each` blocks (Reference §14). */
private fun Validator.blockWrites(rule: RuleDecl, scope: String): List<Write> =
    rule.blockAssignments().mapNotNull { blockAssignment ->
        val recordShape = blockWriteShape(rule, scope, blockAssignment)
        if (recordShape.isEmpty()) null else resolveWrite(rule, scope, blockAssignment.assignment, recordShape)
    }
