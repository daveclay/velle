package velle.validation

import velle.*

// ── V34: a `for each` block (Reference §14) ────────────────────────────────
//
// Each name runs over a collection the rule can reach, or over a range of
// numbers; the name is new in the rule; the block holds creations and
// blocks only; an `as` name bound inside a block is read inside it only.

/** What a `for each` name stands for in the block: one record of a shape, or one value of a type. */
private sealed interface LoopElement {
    data class Records(val shape: String) : LoopElement
    data class Values(val type: VType) : LoopElement
}

internal fun Validator.checkForEach(rule: RuleDecl, scope: String, block: ForEach, created: Map<String, String>) {
    val narrowingOutside = branchNarrowing
    val forEachNamesOutside = forEachNames
    forEachNames = forEachNamesOutside + block.bindings.map { binding -> binding.name }
    val boundNames = block.bindings.filter { binding -> bindLoopName(rule, scope, binding, created) }.map { binding -> binding.name }
    block.body.forEach { item -> checkBlockStatementKind(rule, block, forEachNamesOutside, item) }
    try {
        checkBody(rule, scope, block.body, created)
    } finally {
        boundNames.forEach { name -> scopeByAlias.remove(name); scalarTypeByAlias.remove(name) }
        branchNarrowing = narrowingOutside
        forEachNames = forEachNamesOutside
    }
}

/** Checks one `for each` name and, when it is new and its source is a collection, puts it in scope.
 *  Returns whether the name was put in scope. Each name is in scope for the sources after it. */
private fun Validator.bindLoopName(rule: RuleDecl, scope: String, binding: LoopBinding, created: Map<String, String>): Boolean {
    val taken = isLoopNameTaken(binding.name, scope, created)
    if (taken)
        diagnostics.add(Diagnostic("V34", "rule '${rule.name}' — `for each ${binding.name}`: '${binding.name}' is already a name in this rule " +
            "(a field of the subject, the actor, a shape, an `as` name, or another `for each` name); choose another"))
    val element = when (val source = binding.source) {
        is LoopSource.Range -> rangeElement(rule, scope, source)
        is LoopSource.Over -> elementOfLoopSource(rule, binding.name, source.expr, scope)
    }
    if (taken || element == null) return false
    when (element) {
        is LoopElement.Records -> {
            scopeByAlias[binding.name] = element.shape
            // the source shows its name's fields present in the body, as a refinement's `where` shows `this` (Reference §10, §14)
            branchNarrowing = branchNarrowing + loopNarrowing(binding.name, element.shape, (binding.source as? LoopSource.Over)?.expr)
        }
        is LoopElement.Values -> scalarTypeByAlias[binding.name] = element.type
    }
    return true
}

private fun Validator.isLoopNameTaken(name: String, scope: String, created: Map<String, String>): Boolean =
    name == "this" || name in scopeByAlias || name in scalarTypeByAlias || name in created ||
        model.membersOf(scope).containsKey(name) || name in model.shapes || name in model.refinements

/** A range `from a to b` runs over whole numbers; each bound is checked to be a number. */
private fun Validator.rangeElement(rule: RuleDecl, scope: String, range: LoopSource.Range): LoopElement {
    for (bound in listOf(range.from, range.to)) {
        checkExpr(bound, scope)
        val boundType = scalarTypeOf(bound, scope)
        if (boundType != null && boundType !is VType.Num)
            diagnostics.add(Diagnostic("V34", "rule '${rule.name}' — '${Printer.expr(bound)}' bounds a range but is not a number"))
    }
    return LoopElement.Values(VType.Num("integer"))
}

/** V34: a block creates records; it neither assigns nor deletes. */
private fun Validator.checkBlockStatementKind(rule: RuleDecl, block: ForEach, forEachNamesOutside: Set<String>, item: BodyItem) {
    when (item) {
        is Assignment -> diagnostics.add(Diagnostic("V34", "rule '${rule.name}' assigns '${Printer.expr(item.target)}' inside 'for each' — " +
            "a block creates records; " + (assignmentThroughCollection(block, forEachNamesOutside, item) ?: GENERAL_ASSIGNMENT_ADVICE)))
        is DeleteStmt -> diagnostics.add(Diagnostic("V34", "rule '${rule.name}' deletes inside `for each` — a block creates records; " +
            "to delete every member of a collection, write a rule on the member shape that deletes `this` (Reference §12)"))
        else -> {}
    }
}

private const val GENERAL_ASSIGNMENT_ADVICE =
    "to write one field of every member of a collection, write the assignment in the rule, outside the block, " +
        "through the path to the collection, as 'this.invoices.customer = this.customer' writes 'customer' on every invoice (Reference §6)"

/** The advice naming the author's own collection, `items.n = 1` for `item.n = 1` inside `for each item in items`,
 *  or null when that spelling would not stand outside the block: the target is not `name.field` through this
 *  block's name, the source is not a path to a collection the rule reaches without a block, or the value reads
 *  a name a block binds. */
private fun Validator.assignmentThroughCollection(block: ForEach, forEachNamesOutside: Set<String>, assignment: Assignment): String? {
    val target = assignment.target
    if (target.segs.size != 1) return null
    val collectionPath = collectionPathOf(block, target.root, forEachNamesOutside) ?: return null
    val blockNames = block.bindings.map { binding -> binding.name } + block.body.mapNotNull { statement -> (statement as? Creation)?.alias }
    if (rootsOf(assignment.value).any { root -> root in blockNames || root in forEachNamesOutside }) return null
    val field = target.segs.single().name
    val collection = Printer.expr(collectionPath)
    return "to write '$field' on every member of '$collection', write '$collection.$field = ${Printer.expr(assignment.value)}' " +
        "in the rule, outside the block (Reference §6)"
}

/** The source of the block's name [name] when it is a path the rule reaches outside the block:
 *  neither a bare shape name nor a path through an outer block's name. */
private fun Validator.collectionPathOf(block: ForEach, name: String, forEachNamesOutside: Set<String>): PathExpr? {
    val source = block.bindings.find { binding -> binding.name == name }?.source as? LoopSource.Over ?: return null
    val path = source.expr as? PathExpr ?: return null
    val isShapeName = path.segs.isEmpty() && (path.root in model.shapes || path.root in model.refinements)
    if (isShapeName || path.root in forEachNamesOutside) return null
    return path
}

/** What a `for each` name's source shows about each element, kept under the name (Reference §14):
 *  the element scope's own `where` when the source is a refinement, and the filter's `where`,
 *  `(rows where newKey is some)`, whose tests on the element make `name.newKey` present in the body.
 *  A test the filter writes on the outer subject, `this.note is some`, or on an outer `as` name,
 *  says nothing about the element and is left out. */
private fun Validator.loopNarrowing(name: String, element: String, source: Expr?): Narrowing =
    model.types.elementNarrowing((source as? SetExpr)?.collection, element, name, scopeByAlias.keys)

/** What a `for each` name runs over, or null with a diagnostic when the source is not a collection. */
private fun Validator.elementOfLoopSource(rule: RuleDecl, name: String, source: Expr, scope: String): LoopElement? = when (source) {
    is PathExpr -> elementOfPath(rule, name, source, scope)
    is SetExpr -> elementOfFilter(source, scope)
    is WalkExpr -> elementOfWalk(rule, name, source, scope)
    else -> {
        checkExpr(source, scope)
        reportNotACollection(rule, name, source)
    }
}

private fun Validator.elementOfFilter(filter: SetExpr, scope: String): LoopElement? {
    checkExpr(filter, scope)
    return model.collectionElement(filter.collection, scope, scopeByAlias)?.let { shape -> LoopElement.Records(shape) }
}

/** A walk runs over the records it reaches; a walk `until` names one record and is no collection. */
private fun Validator.elementOfWalk(rule: RuleDecl, name: String, walk: WalkExpr, scope: String): LoopElement? {
    checkExpr(walk, scope)
    if (walk.until != null) return reportNotACollection(rule, name, walk)
    return model.walkTarget(walk, scope)?.let { shape -> LoopElement.Records(shape) }
}

/** A bare shape name runs over its records; any other path runs over the collection it resolves to. */
private fun Validator.elementOfPath(rule: RuleDecl, name: String, path: PathExpr, scope: String): LoopElement? {
    if (path.segs.isEmpty() && (path.root in model.shapes || path.root in model.refinements)) return LoopElement.Records(path.root)
    val diagnosticsBefore = diagnostics.size
    resolvePath(path, scope)
    if (diagnostics.size > diagnosticsBefore) return null
    return when (val pathType = scalarTypeOf(path, scope)) {
        is VType.Coll -> LoopElement.Records(pathType.shape)
        is VType.CollS -> LoopElement.Values(model.scalarOf(pathType.name))
        null -> null
        else -> reportNotACollection(rule, name, path)
    }
}

/** Reports V34 for a source that is not a collection, and returns null: such a source has no element. */
private fun Validator.reportNotACollection(rule: RuleDecl, name: String, source: Expr): LoopElement? {
    diagnostics.add(Diagnostic("V34", "rule '${rule.name}' — `for each $name in ${Printer.expr(source)}`: '${Printer.expr(source)}' is not a collection; " +
        "name a `many` field, an inferred inverse, a filter `(Shape where ...)`, a shape, or a range `from 1 to n` (Reference §14)"))
    return null
}
