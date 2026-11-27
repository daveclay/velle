package velle.validation

import velle.*
import velle.spec.BoundElement

// ── V34: a `for each` block (Reference §14) ────────────────────────────────
//
// Each name runs over a collection the rule can reach, or over a range of
// numbers; the name is new in the rule; the block holds creations, blocks,
// and assignments through its own name where no record's field can be
// written twice in one commit; an `as` name bound inside a block is read
// inside it only.

/** What a `for each` name stands for in the block: one record of a shape, or one value of a type. */
private sealed interface LoopElement {
    data class Records(val shape: String) : LoopElement
    data class Values(val type: VType) : LoopElement
}

internal fun Validator.checkForEach(rule: RuleDecl, scope: String, block: ForEach, created: Map<String, String>) {
    val narrowingOutside = branchNarrowing
    val forEachNamesOutside = forEachNames
    val blocksOutside = enclosingBlocks
    forEachNames = forEachNamesOutside + block.bindings.map { binding -> binding.name }
    enclosingBlocks = blocksOutside + block
    val boundNames = block.bindings.filter { binding -> bindLoopName(rule, scope, binding, created) }.map { binding -> binding.name }
    block.body.forEach { item -> checkBlockStatementKind(rule, scope, item) }
    try {
        checkBody(rule, scope, block.body, created)
    } finally {
        boundNames.forEach { name -> scopeByAlias.remove(name); scalarTypeByAlias.remove(name) }
        branchNarrowing = narrowingOutside
        forEachNames = forEachNamesOutside
        enclosingBlocks = blocksOutside
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

/** V34: a block assigns only where no record's field can be written twice; it never deletes. */
private fun Validator.checkBlockStatementKind(rule: RuleDecl, scope: String, item: BodyItem) {
    when (item) {
        is Assignment -> blockAssignmentRefusal(rule, scope, BlockAssignment(item, enclosingBlocks))
            ?.let { message -> diagnostics.add(Diagnostic("V34", message)) }
        is DeleteStmt -> diagnostics.add(Diagnostic("V34", "rule '${rule.name}' deletes inside `for each` — a block creates records; " +
            "to delete every member of a collection, write a rule on the member shape that deletes `this` (Reference §12)"))
        else -> {}
    }
}

/** The record an assignment in the block being checked writes through its name, as its name to its shape,
 *  when V34 accepts the assignment; empty otherwise, and outside any block. */
internal fun Validator.acceptedBlockWrite(rule: RuleDecl, scope: String, assignment: Assignment): Map<String, String> =
    if (enclosingBlocks.isEmpty()) emptyMap() else blockWriteShape(rule, scope, BlockAssignment(assignment, enclosingBlocks))

/** The record [blockAssignment] writes through its block's name, as that name to its shape, when V34 accepts it; empty otherwise. */
internal fun Validator.blockWriteShape(rule: RuleDecl, scope: String, blockAssignment: BlockAssignment): Map<String, String> {
    if (blockAssignmentRefusal(rule, scope, blockAssignment) != null) return emptyMap()
    val root = blockAssignment.assignment.target.root
    val shape = loopShapesAround(rule, scope, blockAssignment)[root]?.takeIf { shape -> shape != VALUE_ALIAS } ?: return emptyMap()
    return mapOf(root to shape)
}

private fun Validator.loopShapesAround(rule: RuleDecl, scope: String, blockAssignment: BlockAssignment): Map<String, String> =
    model.loopElementShapesAlong(blockAssignment.blocks, scope, ruleAliases(rule))

/**
 * Why V34 refuses an assignment written in a block, or null when it is accepted: the left side is
 * `name.field` through the name of the block it stands directly in; that block and every block around
 * it has one name and runs over records; and each inner block runs over the records that point at the
 * enclosing block's name through a `one` field. Then each record is met once, and the assignment
 * writes no field of it twice (Reference §14).
 */
internal fun Validator.blockAssignmentRefusal(rule: RuleDecl, scope: String, blockAssignment: BlockAssignment): String? {
    val written = "rule '${rule.name}' assigns '${Printer.expr(blockAssignment.assignment.target)}'"
    val shapeByLoopName = loopShapesAround(rule, scope, blockAssignment)
    return leftSideRefusal(written, blockAssignment)
        ?: blockOfOneNameRefusal(written, blockAssignment, shapeByLoopName)
        ?: innerSourceRefusal(written, blockAssignment, shapeByLoopName)
}

private fun blockText(block: ForEach): String = "for each " + block.bindings.joinToString(", ") { binding -> Printer.loopBinding(binding) }

/** The left side is one field of the name of the block the assignment stands directly in. */
private fun leftSideRefusal(written: String, blockAssignment: BlockAssignment): String? {
    val target = blockAssignment.assignment.target
    val block = blockAssignment.innermost
    val within = "$written within '${blockText(block)}'"
    val ownNames = block.bindings.map { binding -> binding.name }
    val ownName = ownNames.first()
    val outerNames = blockAssignment.blocks.dropLast(1).flatMap { outer -> outer.bindings.map { binding -> binding.name } }
    return when {
        target.root in outerNames -> "$within, so one ${target.root} would be written once for each $ownName; " +
            "an assignment in a block writes a field of that block's own name, here '$ownName' (Reference §14)"
        target.root !in ownNames -> "$within, which would write it once for each $ownName; an assignment in a block writes " +
            "a field of the block's own name, here '$ownName', and any other is written in the rule, outside the block (Reference §14)"
        target.segs.isEmpty() -> "$within, which names the record itself; the left side is one stored field of '${target.root}' (Reference §14)"
        target.segs.size > 1 -> "$within, and one record reached through '${target.root}' can be reached from more than one " +
            "${target.root} and written once for each; the left side is one stored field of '${target.root}' (Reference §14)"
        else -> null
    }
}

/** The block and every block around it has one name, over a collection of records. */
private fun Validator.blockOfOneNameRefusal(written: String, blockAssignment: BlockAssignment, shapeByLoopName: Map<String, String>): String? {
    val target = Printer.expr(blockAssignment.assignment.target)
    for (block in blockAssignment.blocks) {
        val reason = severalNamesReason(block, blockAssignment, target) ?: notRecordsReason(block, shapeByLoopName, target) ?: continue
        val advice = assignmentThroughCollection(blockAssignment)?.let { spelling -> "; $spelling" } ?: " (Reference §14)"
        return "$written within '${blockText(block)}', $reason$advice"
    }
    return null
}

private fun severalNamesReason(block: ForEach, blockAssignment: BlockAssignment, target: String): String? {
    if (block.bindings.size == 1) return null
    val root = blockAssignment.assignment.target.root
    val metName = if (block.bindings.any { binding -> binding.name == root }) root else "record"
    return "which has more than one name, so one $metName is met more than once and '$target' could be written twice " +
        "in one commit; an assignment stands in blocks of one name"
}

private fun notRecordsReason(block: ForEach, shapeByLoopName: Map<String, String>, target: String): String? {
    val binding = block.bindings.single()
    val kind = when {
        binding.source is LoopSource.Range -> "number"
        shapeByLoopName[binding.name] == VALUE_ALIAS -> "value"
        else -> return null
    }
    return "which runs over ${kind}s, not records, so '$target' could be written once for each $kind; " +
        "an assignment stands in blocks over collections of records"
}

/** Each inner block runs over the records that point at the enclosing block's name through a `one` field, filtered or not. */
private fun Validator.innerSourceRefusal(written: String, blockAssignment: BlockAssignment, shapeByLoopName: Map<String, String>): String? {
    for ((outer, inner) in blockAssignment.blocks.zipWithNext()) {
        val outerName = outer.bindings.single().name
        val innerBinding = inner.bindings.single()
        val reason = innerSourceReason(innerBinding, outerName, shapeByLoopName) ?: continue
        return "$written within '${blockText(inner)}', $reason, so one ${innerBinding.name} can be met under more than one " +
            "$outerName and written once for each; a block inside another assigns only when it runs over the records that " +
            "point at '$outerName' through a 'one' field (Reference §14)"
    }
    return null
}

/** Why [innerBinding]'s collection can hold one record under two records of [outerName], or null when it cannot. */
private fun Validator.innerSourceReason(innerBinding: LoopBinding, outerName: String, shapeByLoopName: Map<String, String>): String? {
    val notFromOuter = "whose collection does not start from '$outerName'"
    val path = pathUnderFilter((innerBinding.source as LoopSource.Over).expr) ?: return notFromOuter
    if (path.root != outerName || path.segs.size != 1) return notFromOuter
    val outerShape = shapeByLoopName[outerName] ?: return notFromOuter
    val member = model.membersOf(outerShape)[path.segs.single().name] ?: return notFromOuter
    val collection = Printer.expr(path)
    val inverse = member.inverse
    return when {
        inverse != null && !inverse.many -> null
        member.derived != null -> "and '$collection' is derived"
        inverse != null -> "and '$collection' is the other side of a 'many'"
        member.type is VType.Coll -> "and '$collection' is a declared 'many'"
        else -> notFromOuter
    }
}

/** The path a block's collection names, `shelf.books`, bare or under a filter `(shelf.books where ...)`; null for any other collection. */
private fun pathUnderFilter(collection: Expr): PathExpr? = when (collection) {
    is PathExpr -> collection
    is SetExpr -> collection.collection.bindings.singleOrNull()?.source as? PathExpr
    else -> null
}

/** The advice naming the author's own collection, `items.n = 1` for `item.n = 1` inside `for each item in items`,
 *  or null when that spelling would not stand outside the block: the target is not `name.field` through the
 *  innermost block's name, the source is not a path to a collection the rule reaches without a block, or the
 *  value reads a name a block binds. */
private fun Validator.assignmentThroughCollection(blockAssignment: BlockAssignment): String? {
    val assignment = blockAssignment.assignment
    val block = blockAssignment.innermost
    val forEachNamesOutside = blockAssignment.blocks.dropLast(1).flatMap { outer -> outer.bindings.map { binding -> binding.name } }.toSet()
    val target = assignment.target
    if (target.segs.size != 1) return null
    val collectionPath = collectionPathOf(block, target.root, forEachNamesOutside) ?: return null
    val blockNames = block.bindings.map { binding -> binding.name } + block.body.mapNotNull { statement -> (statement as? Creation)?.alias }
    if (rootsOf(assignment.value).any { root -> root in blockNames || root in forEachNamesOutside }) return null
    val field = target.segs.single().name
    val collection = Printer.expr(collectionPath)
    val value = Printer.expr(assignment.value)
    if (collectionPath is SetExpr)
        return "to write '$field' on the members of '$collection', write '$collection.$field = $value' in the rule, outside the block (Reference §12)"
    return "to write '$field' on every member of '$collection', write '$collection.$field = $value' in the rule, outside the block (Reference §6)"
}

/** The source of the block's name [name] when the same collection can stand on the left of an assignment outside
 *  the block: a path the rule reaches, `items`, or that path under a filter, `(items where n > 0)` (Reference §12);
 *  never a bare shape name, a filter naming its element with `as`, or a collection that reads an outer block's name. */
private fun Validator.collectionPathOf(block: ForEach, name: String, forEachNamesOutside: Set<String>): Expr? {
    val source = block.bindings.find { binding -> binding.name == name }?.source as? LoopSource.Over ?: return null
    val path = when (val collection = source.expr) {
        is PathExpr -> collection
        is SetExpr -> filteredPathOf(collection, forEachNamesOutside) ?: return null
        else -> return null
    }
    val isShapeName = path.segs.isEmpty() && (path.root in model.shapes || path.root in model.refinements)
    if (isShapeName || path.root in forEachNamesOutside) return null
    return source.expr
}

/** The path a filter `(items where n > 0)` runs over, when the filter could stand on the left of an assignment. */
private fun filteredPathOf(filter: SetExpr, forEachNamesOutside: Set<String>): PathExpr? {
    val binding = filter.collection.bindings.singleOrNull() ?: return null
    if (binding.alias != null) return null
    val condition = filter.collection.where ?: return null
    if (rootsOf(condition).any { root -> root in forEachNamesOutside }) return null
    return binding.source as? PathExpr
}

/** What a `for each` name's source shows about each element, kept under the name (Reference §14):
 *  the element scope's own `where` when the source is a refinement, and the filter's `where`,
 *  `(rows where newKey is some)`, whose tests on the element make `name.newKey` present in the body.
 *  A test the filter writes on the outer subject, `this.note is some`, or on an outer `as` name,
 *  says nothing about the element and is left out. */
private fun Validator.loopNarrowing(name: String, element: String, source: Expr?): Narrowing =
    model.types.elementNarrowing((source as? SetExpr)?.collection, element, name, scopeByAlias.keys)

/** What a `for each` name runs over, or null with a diagnostic when the source is not a collection. */
private fun Validator.elementOfLoopSource(rule: RuleDecl, name: String, source: Expr, scope: String): LoopElement? {
    if (!loopSourceResolves(source, scope)) return null
    if (!isCollection(source, scope)) return reportNotACollection(rule, name, source)
    // which records the name runs over is the model's answer (`velle.spec.ScopeRules`)
    return when (val element = model.scopes.elementOfLoop(LoopSource.Over(source), scopeAt(scope))) {
        is BoundElement.Records -> LoopElement.Records(element.shape)
        BoundElement.Values -> (scalarTypeOf(source, scope) as? VType.CollS)?.let { values -> LoopElement.Values(model.scalarOf(values.name)) }
        BoundElement.Unknown -> null
    }
}

/** Checks the names [source] reads; false when a path that is the whole source was refused. */
private fun Validator.loopSourceResolves(source: Expr, scope: String): Boolean {
    if (source !is PathExpr) {
        checkExpr(source, scope)
        return true
    }
    if (source.segs.isEmpty() && isShapeOrRefinement(source.root)) return true
    val diagnosticsBefore = diagnostics.size
    resolvePath(source, scope)
    return diagnostics.size == diagnosticsBefore
}

/** A path to a `many` or a shape named on its own, a filter, and a walk without `until`, which names one record. */
private fun Validator.isCollection(source: Expr, scope: String): Boolean = when (source) {
    is PathExpr -> source.segs.isEmpty() && isShapeOrRefinement(source.root) ||
        scalarTypeOf(source, scope).let { type -> type == null || type is VType.Coll || type is VType.CollS }
    is SetExpr -> true
    is WalkExpr -> source.until == null
    else -> false
}

/** Reports V34 for a source that is not a collection, and returns null: such a source has no element. */
private fun Validator.reportNotACollection(rule: RuleDecl, name: String, source: Expr): LoopElement? {
    diagnostics.add(Diagnostic("V34", "rule '${rule.name}' — `for each $name in ${Printer.expr(source)}`: '${Printer.expr(source)}' is not a collection; " +
        "name a `many` field, an inferred inverse, a filter `(Shape where ...)`, a shape, or a range `from 1 to n` (Reference §14)"))
    return null
}
