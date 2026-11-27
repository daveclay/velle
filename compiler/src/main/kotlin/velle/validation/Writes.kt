package velle.validation

import velle.*

// ── writes ───────────────────────────────────────────────────────────────

internal data class Write(val rule: RuleDecl, val owner: String, val member: MemberInfo, val target: PathExpr,
                         val value: Expr,
                         /** the target traverses a `many` hop: one write per member (Reference §6);
                          *  one-writer treats it as a write to the field on every instance (V1, coarse) */
                         val fanOut: Boolean = false)

/** One step of an assignment's target path: the shape it reaches, and whether
 *  a `many` hop on the way already fanned the write out. */
private class TargetStep(val shape: String, val fanOut: Boolean)

/** Resolves [assignment]'s target to the field it writes, or reports why it
 *  cannot be written and returns null. */
internal fun Validator.resolveWrite(rule: RuleDecl, scope: String, assignment: Assignment): Write? {
    val target = assignment.target
    if (target.root == "this") return resolveTargetSegments(rule, assignment, TargetStep(scope, fanOut = false))
    val rootMember = model.membersOf(scope)[target.root]
    if (rootMember == null) {
        // an assignment through a `for each` name has its one message, V34's
        if (target.root !in forEachNames)
            diagnostics.add(Diagnostic("F1", "rule '${rule.name}' assigns through unknown member '${target.root}'"))
        return null
    }
    val scopeBase = model.baseOf(scope) ?: scope
    if (target.segs.isEmpty())
        return writeOfMember(rule, scopeBase, rootMember, assignment, fanOut = false, targetText = target.root)
    val firstStep = stepThrough(rule, rootMember, scopeBase, atPenultimate = target.segs.size == 1, fannedOut = false)
        ?: return null
    return resolveTargetSegments(rule, assignment, firstStep)
}

/** Walks the target's segments from [firstStep] to the member the last one names. */
private fun Validator.resolveTargetSegments(rule: RuleDecl, assignment: Assignment, firstStep: TargetStep): Write? {
    val segments = assignment.target.segs
    var step = firstStep
    for ((index, segment) in segments.withIndex()) {
        val member = model.membersOf(step.shape)[segment.name]
        if (member == null) {
            diagnostics.add(Diagnostic("F1", "rule '${rule.name}' assigns through unknown member '${segment.name}' of '${step.shape}'"))
            return null
        }
        if (index == segments.lastIndex) {
            val owner = model.baseOf(step.shape) ?: step.shape
            return writeOfMember(rule, owner, member, assignment, step.fanOut, targetText = Printer.expr(assignment.target))
        }
        step = stepThrough(rule, member, step.shape, atPenultimate = index == segments.lastIndex - 1, step.fanOut) ?: return null
    }
    return null
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

/** Steps through [member] of [ownerShape] toward the written field; null where
 *  the path cannot go on (reported, unless the member reaches no shape). */
private fun Validator.stepThrough(rule: RuleDecl, member: MemberInfo, ownerShape: String, atPenultimate: Boolean, fannedOut: Boolean): TargetStep? {
    val collectionType = member.type as? VType.Coll
    if (collectionType != null) return fanOutStep(rule, member, ownerShape, collectionType, atPenultimate, fannedOut)
    if (member.type is VType.CollS) {
        diagnostics.add(Diagnostic("V20", "rule '${rule.name}' assigns through scalar collection '$ownerShape.${member.name}'"))
        return null
    }
    val instanceShape = member.type.instanceShape() ?: return null
    return TargetStep(instanceShape, fannedOut)
}

/** V20: a collection path fans out exactly at the penultimate hop —
 *  it writes a field *of* the members, never through them (Reference §6). */
private fun Validator.fanOutStep(
    rule: RuleDecl, member: MemberInfo, ownerShape: String, collectionType: VType.Coll, atPenultimate: Boolean, fannedOut: Boolean,
): TargetStep? {
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
    return TargetStep(collectionType.shape, fanOut = true)
}

internal fun Validator.findWrites(): List<Write> {
    return model.rules.values.flatMap { rule ->
        val scope = subjectScope(rule) ?: return@flatMap emptyList()
        rule.body.filterIsInstance<Assignment>().mapNotNull { resolveWrite(rule, scope, it) }
    }
}
