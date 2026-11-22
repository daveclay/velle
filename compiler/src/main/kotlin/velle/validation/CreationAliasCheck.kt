package velle.validation

import velle.*

// ── V33: a creation's `as` name (Reference §14; OQ63 §6.2.1) ─────────────
//
// The name is a reference to a record that has no id until the commit
// lands. It may be read where a reference is taken — a creation's field, a
// `for` target — and nowhere else: not through (`review.member` would read a
// record that does not exist yet), not in an assignment or a delete, not
// from a name already in scope, and never in a cycle between two creations,
// since no record could then be created first.

/** Checks the body's `as` names and returns each accepted name with the shape its creation makes. */
internal fun Validator.checkCreationAliases(rule: RuleDecl, scope: String): Map<String, String> {
    val shapeByAlias = acceptedCreationAliases(rule, scope)
    if (shapeByAlias.isEmpty()) return shapeByAlias
    val createdNames = shapeByAlias.keys
    checkAliasesReadInsideTheirBlock(rule, rule.body, enclosingAliases = emptySet(), createdNames)
    rule.body.statements().forEach { statement -> checkAliasUseIn(statement, rule, createdNames) }
    checkCreationsPointOneWay(rule)
    return shapeByAlias
}

/** The `as` names that are new in the rule, each to the shape its creation makes; a name already taken is reported. */
private fun Validator.acceptedCreationAliases(rule: RuleDecl, scope: String): Map<String, String> {
    val loopNames = rule.loopBindings().map { (binding, _) -> binding.name }.toSet()
    val shapeByAlias = linkedMapOf<String, String>()
    for (creation in rule.creations()) {
        val alias = creation.alias ?: continue
        if (isAliasTaken(alias, scope, shapeByAlias.keys, loopNames)) {
            diagnostics.add(Diagnostic("V33", "rule '${rule.name}' — '${creation.shape} ... as $alias': '$alias' is already a name in this rule " +
                "(a field of the subject, the actor, a shape, a `for each` name, or another `as`); choose another"))
            continue
        }
        shapeByAlias[alias] = creation.shape
    }
    return shapeByAlias
}

private fun Validator.isAliasTaken(alias: String, scope: String, earlierAliases: Set<String>, loopNames: Set<String>): Boolean =
    alias == "this" || alias in scopeByAlias || alias in earlierAliases || alias in loopNames ||
        model.membersOf(scope).containsKey(alias) || alias in model.shapes || alias in model.refinements

// ── V34: a name bound inside a block is read inside that block only ───────

/** Checks one level of a body: [enclosingAliases] are the `as` names of the levels around it. */
private fun Validator.checkAliasesReadInsideTheirBlock(
    rule: RuleDecl, items: List<BodyItem>, enclosingAliases: Set<String>, createdNames: Set<String>,
) {
    val visibleAliases = enclosingAliases + items.filterIsInstance<Creation>().mapNotNull { creation -> creation.alias }
    for (item in items) when (item) {
        is Creation -> checkCreationReadsOnlyVisibleAliases(rule, item, visibleAliases, createdNames)
        is ForEach -> {
            item.bindings.forEach { binding -> checkLoopSourceReadsNoCreatedRecord(rule, binding, createdNames) }
            checkAliasesReadInsideTheirBlock(rule, item.body, visibleAliases, createdNames)
        }
        else -> {}
    }
}

private fun Validator.checkCreationReadsOnlyVisibleAliases(
    rule: RuleDecl, creation: Creation, visibleAliases: Set<String>, createdNames: Set<String>,
) {
    val values = creation.fields.map { field -> field.value } + listOfNotNull(creation.forExpr)
    for (value in values) {
        val namesFromInnerBlocks = createdNames.filter { name -> name !in visibleAliases && mentions(value, setOf(name)) }
        namesFromInnerBlocks.forEach { name ->
            diagnostics.add(Diagnostic("V34", "rule '${rule.name}' — '${creation.shape}' reads '$name', a name bound by `as` inside a `for each` block, " +
                "outside that block; the name stands for one record per element and means nothing outside"))
        }
    }
}

private fun Validator.checkLoopSourceReadsNoCreatedRecord(rule: RuleDecl, binding: LoopBinding, createdNames: Set<String>) {
    for (source in loopSourceExpressions(binding.source)) {
        if (mentions(source, createdNames)) diagnostics.add(Diagnostic("V33", "rule '${rule.name}' — `for each ${binding.name}` runs over " +
            "'${Printer.expr(source)}', which reads a record this rule creates; it has no fields until the commit lands"))
    }
}

private fun loopSourceExpressions(source: LoopSource): List<Expr> = when (source) {
    is LoopSource.Over -> listOf(source.expr)
    is LoopSource.Range -> listOf(source.from, source.to)
}

// ── V33: where a created record's name may be read ────────────────────────

private fun Validator.checkAliasUseIn(statement: BodyItem, rule: RuleDecl, createdNames: Set<String>) {
    when (statement) {
        is Creation -> {
            statement.forExpr?.let { target -> checkAliasReads(target, standsWhole = true, AliasReadSite(rule, createdNames, "a `for` target")) }
            statement.fields.forEach { field ->
                checkAliasReads(field.value, standsWhole = true, AliasReadSite(rule, createdNames, "a field of '${statement.shape}'"))
            }
        }
        is Assignment -> checkAssignmentAvoidsCreatedRecords(rule, statement, createdNames)
        is DeleteStmt -> if (statement.target.root in createdNames)
            diagnostics.add(Diagnostic("V33", "rule '${rule.name}' deletes '${statement.target.root}', a record this rule creates in the same body"))
        is ForEach, ThenMarker -> {}
    }
}

private fun Validator.checkAssignmentAvoidsCreatedRecords(rule: RuleDecl, assignment: Assignment, createdNames: Set<String>) {
    if (assignment.target.root in createdNames) {
        diagnostics.add(Diagnostic("V33", "rule '${rule.name}' assigns through '${assignment.target.root}', a record this rule creates — " +
            "give the value in the creation instead"))
        return
    }
    if (!mentions(assignment.value, createdNames)) return
    val firstNameRead = createdNames.first { name -> mentions(assignment.value, setOf(name)) }
    diagnostics.add(Diagnostic("V33", "rule '${rule.name}' assigns '${Printer.expr(assignment.target)}' from '$firstNameRead', " +
        "a record this rule creates — a name bound by `as` is read only where a creation takes a reference"))
}

/** The rule, the names its creations bind, and the place in the body a read is checked at, as the message names it. */
private data class AliasReadSite(val rule: RuleDecl, val createdNames: Set<String>, val where: String)

/** V33's read walk: a name bound by `as` may stand only as a whole reference. [standsWhole] is true
 *  for the value a creation's field or `for` target takes, and for either branch of an `if` that is one. */
private fun Validator.checkAliasReads(expression: Expr, standsWhole: Boolean, site: AliasReadSite) {
    when (expression) {
        is PathExpr -> checkAliasPath(expression, standsWhole, site)
        is IfExpr -> {
            checkAliasReads(expression.condition, standsWhole = false, site)
            checkAliasReads(expression.thenExpr, standsWhole, site)
            checkAliasReads(expression.elseExpr, standsWhole, site)
        }
        else -> {
            operandsOf(expression).forEach { operand -> checkAliasReads(operand, standsWhole = false, site) }
            collectionOf(expression)?.let { collection -> checkAliasReadsInCollection(collection, site) }
        }
    }
}

private fun Validator.checkAliasPath(path: PathExpr, standsWhole: Boolean, site: AliasReadSite) {
    if (path.root !in site.createdNames) return
    if (path.segs.isNotEmpty())
        diagnostics.add(Diagnostic("V33", "rule '${site.rule.name}' — '${Printer.expr(path)}' reads through '${path.root}', " +
            "a record this rule creates; it has no fields until the commit lands. Read the value you built it from instead"))
    else if (!standsWhole)
        diagnostics.add(Diagnostic("V33", "rule '${site.rule.name}' — '${path.root}' names a record this rule creates; " +
            "in ${site.where} it may only stand as a whole reference"))
}

private fun Validator.checkAliasReadsInCollection(collection: CollectionExpr, site: AliasReadSite) {
    collection.bindings.mapNotNull { binding -> binding.source as? PathExpr }
        .filter { source -> source.root in site.createdNames }
        .forEach { source -> reportReadThroughCreatedRecord(source, site) }
    collection.where?.let { filter -> checkAliasReads(filter, standsWhole = false, site) }
}

private fun Validator.reportReadThroughCreatedRecord(source: PathExpr, site: AliasReadSite) {
    diagnostics.add(Diagnostic("V33",
        "rule '${site.rule.name}' — '${Printer.expr(source)}' reads through '${source.root}', a record this rule creates; it has no fields until the commit lands"))
}

// ── V33: no cycle between two creations ───────────────────────────────────

/** A creation naming a record whose creation names it back: no record can be created first. */
private fun Validator.checkCreationsPointOneWay(rule: RuleDecl) {
    val namedCreations = rule.creations().filter { creation -> creation.alias != null }
    val aliases = namedCreations.map { creation -> creation.alias!! }
    val aliasesNamedByAlias = namedCreations.associate { creation ->
        creation.alias!! to aliases.filter { other -> other != creation.alias && creationMentions(creation, other) }.toSet()
    }
    if (!hasCycle(aliasesNamedByAlias)) return
    diagnostics.add(Diagnostic("V33", "rule '${rule.name}' — the records created as ${aliasesNamedByAlias.keys.joinToString(", ") { "'$it'" }} point at each other; " +
        "no record can be created first. Point one way, and let the other side be the inferred inverse (Reference §6)"))
}

private fun creationMentions(creation: Creation, name: String): Boolean =
    creation.fields.any { field -> mentions(field.value, setOf(name)) } ||
        creation.forExpr?.let { target -> mentions(target, setOf(name)) } == true

/** Whether following [targetsByNode] from some node comes back to a node already on the way. */
private fun hasCycle(targetsByNode: Map<String, Set<String>>): Boolean {
    val onTheWay = mutableSetOf<String>()
    val finished = mutableSetOf<String>()
    fun leadsIntoCycle(node: String): Boolean {
        if (node in finished) return false
        if (!onTheWay.add(node)) return true
        val cycleFound = targetsByNode[node].orEmpty().any { target -> leadsIntoCycle(target) }
        onTheWay.remove(node)
        finished.add(node)
        return cycleFound
    }
    return targetsByNode.keys.any { node -> leadsIntoCycle(node) }
}

/** Does [expression] mention any of [names] as a path root? */
private fun mentions(expression: Expr, names: Set<String>): Boolean = when (expression) {
    is PathExpr -> expression.root in names
    else -> subExpressionsOf(expression).any { part -> mentions(part, names) }
}
