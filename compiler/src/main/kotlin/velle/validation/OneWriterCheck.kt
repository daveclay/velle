package velle.validation

import velle.*

// ── V1 (+ coarse V15): one writer per field per commit ───────────────────

internal fun Validator.checkOneWriter() {
    val writesByField = writes.filter { it.member.stored }.groupBy { it.owner to it.member.name }
    for ((field, fieldWrites) in writesByField) checkWritersOfField(field, fieldWrites)
    checkSiblingReadWrite()
}

/** Checks every pair of [fieldWrites], each once, in the order they were written. */
private fun Validator.checkWritersOfField(field: Pair<String, String>, fieldWrites: List<Write>) {
    for (firstIndex in fieldWrites.indices) for (secondIndex in firstIndex + 1 until fieldWrites.size)
        checkWriterPair(field, fieldWrites[firstIndex], fieldWrites[secondIndex])
}

private fun Validator.checkWriterPair(field: Pair<String, String>, first: Write, second: Write) {
    val (owner, fieldName) = field
    if (first.rule === second.rule) {
        diagnostics.add(Diagnostic("V1", "rule '${first.rule.name}' assigns $owner.$fieldName twice in one body"))
        return
    }
    if (!provablyDisjoint(first.rule, second.rule) && !aliasDischarged(first, second))
        diagnostics.add(Diagnostic("V1", "rules '${first.rule.name}' and '${second.rule.name}' both assign " +
            "$owner.$fieldName and their triggers are not provably disjoint"))
}

/**
 * The instance-aliasing discharge (OQ16's PromoteBuyer/PromoteReferrer
 * case): two writers of one field whose target routes differ by exactly
 * one to-one self-hop write provably different instances when a spendable
 * `never` forbids the hop pointing back at its own instance — the routes
 * share a prefix, so the two targets coincide exactly when `x.hop == x`,
 * the forbidden configuration.
 */
private fun Validator.aliasDischarged(first: Write, second: Write): Boolean {
    if (first.fanOut || second.fanOut) return false
    if (model.baseOfExpr(first.rule.condition) != model.baseOfExpr(second.rule.condition)) return false
    val longerRoute = longerRouteByOneHop(targetRoute(first), targetRoute(second)) ?: return false
    val hop = longerRoute.last()
    val hopMember = model.membersOf(first.owner)[hop] ?: return false
    if (!hopMember.stored || hopMember.type.instanceShape() != first.owner) return false
    return neverForbidsSelfReference(first.owner, hop)
}

/** The members a write's target passes through to reach the instance it writes. */
private fun targetRoute(write: Write): List<String> =
    listOf(write.target.root) + write.target.segs.dropLast(1).map { it.name }

/** The longer of two routes where it extends the shorter by exactly one hop; else null. */
private fun longerRouteByOneHop(firstRoute: List<String>, secondRoute: List<String>): List<String>? = when {
    firstRoute.size + 1 == secondRoute.size && secondRoute.subList(0, firstRoute.size) == firstRoute -> secondRoute
    secondRoute.size + 1 == firstRoute.size && firstRoute.subList(0, secondRoute.size) == secondRoute -> firstRoute
    else -> null
}

// ── coarse V15, read-write legs (sibling-confluence audit, channels A–D, F) ─
//
// On-commit siblings: subjects are pinned from one pre/post diff before
// any sibling runs (evaluation.md step 5), so a condition-only read of a
// sibling's write is safe — the flip it causes fires rules at its own
// commit, which is causality, not a race (probe H). What diverges is the
// BODY: bodies read the evolving state, so a body read of anything a
// sibling writes — a field, a shape's instance set (existence, aggregate),
// a timestamp any write advances — depends on the unstated order.
//
// After-commit followers of one commit are independent transactions in
// unspecified relative order (the queue's append order was itself a
// step-6 choice), and their conditions RE-CHECK at drain — so for
// after/after pairs, condition reads matter too (full summary).

private fun Validator.checkSiblingReadWrite() {
    checkOnCommitSiblings()
    checkAfterCommitFollowers()
}

private fun Validator.checkOnCommitSiblings() {
    val commitRules = onCommitRules()
    for (writer in commitRules) for (reader in commitRules) {
        if (writer === reader || !canInterfere(writer, reader)) continue
        for (what in readConflicts(writer, bodyReads(reader)))
            diagnostics.add(Diagnostic("V15", "'${writer.name}' writes $what, which sibling '${reader.name}' reads in its body — " +
                "outcome depends on unstated order; state the intent (OQ16)"))
    }
}

private fun Validator.checkAfterCommitFollowers() {
    val afterRules = model.rules.values.filter { it.preposition == "after" }
    for (writer in afterRules) for (reader in afterRules) {
        if (writer === reader || !canInterfere(writer, reader)) continue
        for (what in readConflicts(writer, ruleReads(reader)))
            diagnostics.add(Diagnostic("V15", "'${writer.name}' and '${reader.name}' follow the same commit as independent " +
                "after-commit transactions in unspecified relative order, and '${reader.name}' reads $what, " +
                "which '${writer.name}' writes — outcome depends on that order; state the intent (OQ16)"))
    }
}

/** Reads of a rule's body only — assignment values and their target
 *  routes, creation field values and `for` expressions. Condition reads
 *  are deliberately absent: subjects are pinned per commit (the section
 *  comment above). */
private fun Validator.bodyReads(rule: RuleDecl): ReadSummary = bodyReadsByRuleName.getOrPut(rule.name) {
    val reads = ReadSummary()
    val scope = subjectScope(rule)
    if (scope != null) {
        val aliases = ruleAliases(rule)
        rule.body.statements().forEach { statement -> collectStatementBodyReads(statement, scope, reads, aliases) }
    }
    reads
}

private fun Validator.collectStatementBodyReads(statement: BodyItem, scope: String, reads: ReadSummary, aliases: Map<String, String>) {
    when (statement) {
        is Assignment -> {
            statement.reads.forEach { read -> model.collectExpr(read, scope, reads, aliases) }
            if (statement.target.segs.isNotEmpty())
                model.collectExpr(PathExpr(statement.target.root, statement.target.segs.dropLast(1)), scope, reads, aliases)
        }
        is Creation -> {
            statement.forExpr?.let { model.collectExpr(it, scope, reads, aliases) }
            statement.fields.forEach { model.collectExpr(it.value, scope, reads, aliases) }
        }
        is ForEach -> statement.sourceExprs().forEach { model.collectExpr(it, scope, reads, aliases) }
        is DeleteStmt -> model.collectExpr(statement.target, scope, reads, aliases) // the route to the target
        ThenMarker -> {}
    }
}
