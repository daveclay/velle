package velle.validation

import velle.*

// ── V37: transient fields of a kept act (Reference §4, "Transient fields of a
// kept act") ────────────────────────────────────────────────────────────────
//
// `transient name: type` on an act: the field is in the act's input and not
// in its record. It is read by what runs at the act's commit, a `never` over
// the act and a rule whose subject is the bare act, and by nothing else: a
// refinement of a kept act persists and is re-evaluated as its data drifts,
// a derived property is read later, and any other reader reads a record
// that has no such field. Nothing assigns it.

internal fun Validator.checkTransientFields() {
    if (transientFieldsByShape.isEmpty()) return
    checkTransientFieldsAreOnActs()
    for ((name, shape) in model.shapes) shape.members.forEach { member -> checkTransientReadsInShapeMember(name, member) }
    for ((name, refinement) in model.refinements) checkTransientReadsInRefinement(name, refinement)
    for (never in model.nevers) {
        val subject = model.baseOfExpr(never.target)
        transientFieldReadsInRef(never.target, subject, "${never.keyword} '${Printer.refExpr(never.target)}'")
    }
    for (rule in model.rules.values) checkTransientFieldsInRule(rule)
}

/** V37: only an act has input, so only an act declares a transient field. */
private fun Validator.checkTransientFieldsAreOnActs() {
    for ((shape, fields) in transientFieldsByShape) {
        if (shape in model.exposed) continue
        for (field in fields)
            diagnostics.add(Diagnostic("V37", "'$shape.$field' is transient, but '$shape' is a shape — a transient field is part " +
                "of an act's input, and a shape is created by rules, never committed; drop `transient`, or declare '$shape' an act"))
    }
}

private fun Validator.checkTransientReadsInShapeMember(name: String, member: Member) {
    when (member) {
        is StoredProp -> transientFieldReads(member.initially, name, name, null, "'$name.${member.name}'")
        is DerivedProp -> transientFieldReads(member.expr, name, name, null, "'$name.${member.name}'")
        else -> {}
    }
}

private fun Validator.checkTransientReadsInRefinement(name: String, refinement: RefinementDecl) {
    transientFieldReadsInRef(refinement.expr, null, "shape '$name'")
    refinement.members.filterIsInstance<DerivedProp>().forEach { property ->
        transientFieldReads(property.expr, name, name, null, "'$name.${property.name}'")
    }
}

private fun Validator.checkTransientFieldsInRule(rule: RuleDecl) {
    val scope = subjectScope(rule) ?: return
    val site = "rule '${rule.name}'"
    // the subject is the bare act, so the body runs once, at the act's commit
    val bareAct = (rule.condition as? RefName)?.takeIf { condition -> condition.where == null && condition.name in model.shapes }?.name
    transientFieldReadsInRef(rule.condition, null, site)
    val aliases = rule.by?.let { actor -> mapOf(actor.name to actor.shape) }.orEmpty()
    checkTransientFieldsInBody(rule.body, TransientReadScope(scope, scope, bareAct, site, aliases))
}

private fun Validator.checkTransientFieldsInBody(items: List<BodyItem>, readScope: TransientReadScope) {
    for (item in items) when (item) {
        is Assignment -> checkTransientFieldsInAssignment(item, readScope)
        is Creation -> {
            reportTransientReads(item.forExpr, readScope)
            item.fields.forEach { field -> reportTransientReads(field.value, readScope) }
        }
        is ForEach -> checkTransientFieldsInBlock(item, readScope)
        is DeleteStmt, ThenMarker -> {}
    }
}

private fun Validator.checkTransientFieldsInAssignment(assignment: Assignment, readScope: TransientReadScope) {
    reportTransientReads(assignment.value, readScope)
    val assigned = transientFieldOnPath(assignment.target, readScope.thisScope, readScope.thisScope, readScope.aliases) ?: return
    diagnostics.add(Diagnostic("V37", "${readScope.site} assigns '${assigned.owner}.${assigned.field}', which is transient — " +
        "it is in the act's input and not in its record, so there is nothing to assign"))
}

/** A block's sources are read in the enclosing scope; its body reads each `for each` name as its element's shape. */
private fun Validator.checkTransientFieldsInBlock(block: ForEach, readScope: TransientReadScope) {
    block.sourceExprs().forEach { source -> reportTransientReads(source, readScope) }
    val shapeByLoopName = block.bindings.associate { binding ->
        val element = (binding.source as? LoopSource.Over)?.let { over ->
            elementScopeOf(over.expr, readScope.thisScope, readScope.thisScope, readScope.aliases)
        }
        binding.name to (element ?: VALUE_ALIAS)
    }
    checkTransientFieldsInBody(block.body, readScope.copy(aliases = readScope.aliases + shapeByLoopName))
}

/** The reads in a refinement expression: each `where` runs with its name's base as `this`. */
private fun Validator.transientFieldReadsInRef(expr: RefExpr, allowedAct: String?, site: String) {
    when (expr) {
        is RefName -> expr.where?.let { where ->
            val scope = model.baseOf(expr.name) ?: expr.name
            transientFieldReads(where, scope, scope, allowedAct, site)
        }
        is RefAnd -> { transientFieldReadsInRef(expr.left, allowedAct, site); transientFieldReadsInRef(expr.right, allowedAct, site) }
        is RefOr -> { transientFieldReadsInRef(expr.left, allowedAct, site); transientFieldReadsInRef(expr.right, allowedAct, site) }
        is RefNot -> transientFieldReadsInRef(expr.inner, allowedAct, site)
    }
}

/**
 * Where a read is checked. [thisScope] is what `this` names; [elementScope] is
 * what a bare name resolves on, the element of the innermost collection
 * `where`; [allowedAct] is the act whose own transient fields may be read at
 * the subject level; [site] names the reader in the message; [aliases] maps
 * each name in scope to its shape, or to [VALUE_ALIAS].
 */
private data class TransientReadScope(
    val thisScope: String, val elementScope: String, val allowedAct: String?, val site: String,
    val aliases: Map<String, String>,
)

/**
 * Reports every read of a transient field in [expr] that is not a read of
 * [allowedAct]'s own field at the subject level. [thisScope] is what `this`
 * names; [elementScope] is what a bare name resolves on, the element of the
 * innermost collection `where`.
 */
internal fun Validator.transientFieldReads(
    expr: Expr?, thisScope: String, elementScope: String, allowedAct: String?, site: String,
    aliases: Map<String, String> = emptyMap(),
) = reportTransientReads(expr, TransientReadScope(thisScope, elementScope, allowedAct, site, aliases))

private fun Validator.reportTransientReads(expression: Expr?, readScope: TransientReadScope) {
    expression ?: return
    if (expression is PathExpr) {
        reportTransientPathRead(expression, readScope)
        return
    }
    operandsOf(expression).forEach { operand -> reportTransientReads(operand, readScope) }
    collectionOf(expression)?.let { collection -> reportTransientReadsInCollection(collection, readScope) }
}

private fun Validator.reportTransientPathRead(path: PathExpr, readScope: TransientReadScope) {
    val read = transientFieldOnPath(path, readScope.thisScope, readScope.elementScope, readScope.aliases) ?: return
    val ownRead = path.root == "this" || (path.root !in readScope.aliases && readScope.elementScope == readScope.thisScope)
    if (readScope.allowedAct == read.owner && ownRead && read.stepIndex == 0) return
    diagnostics.add(Diagnostic("V37", "${readScope.site} reads '${read.owner}.${read.field}', which is transient — " +
        "it is in the act's input and not in its record, so only a `never` over '${read.owner}' " +
        "or a rule on the bare act may read it, at the commit (Reference §4)"))
}

/** A collection's `where` runs with its first binding's element as the bare-name scope and `this` unchanged. */
private fun Validator.reportTransientReadsInCollection(collection: CollectionExpr, readScope: TransientReadScope) {
    collection.bindings.forEach { binding -> reportTransientReads(binding.source, readScope) }
    val where = collection.where ?: return
    val bindingAliases = collection.bindings.mapNotNull { binding ->
        binding.alias?.let { alias -> elementScopeOf(binding.source, readScope)?.let { shape -> alias to shape } }
    }
    val element = collection.bindings.firstOrNull()?.let { binding -> elementScopeOf(binding.source, readScope) } ?: readScope.elementScope
    reportTransientReads(where, readScope.copy(elementScope = element, aliases = readScope.aliases + bindingAliases))
}

private fun Validator.elementScopeOf(source: Expr, readScope: TransientReadScope): String? =
    elementScopeOf(source, readScope.thisScope, readScope.elementScope, readScope.aliases)

/** The shape of a collection source's element: a bare shape name, or the type the model gives the expression. */
private fun Validator.elementScopeOf(source: Expr, thisScope: String, elementScope: String, aliases: Map<String, String>): String? {
    if (source is PathExpr && source.segs.isEmpty() && (source.root in model.shapes || source.root in model.refinements)) return source.root
    val env = TypeEnv(thisScope = thisScope, aliases = aliases)
    return when (val type = model.types.of(source, elementScope, env)) {
        is VType.Coll -> type.shape
        else -> type?.instanceShape()
    }
}

/** A transient field a path reads: the act that owns it, the field, and the step of the path that reads it. */
private data class TransientFieldRead(val owner: String, val field: String, val stepIndex: Int)

/** Where a path starts: the shape its first step resolves on, null when that is a value, and the steps. */
private data class PathStart(val shape: String?, val steps: List<String>)

/**
 * The first transient field a path reads, or null when the path reads none.
 * A step after the first resolves on the shape the previous step's reference
 * points at.
 */
private fun Validator.transientFieldOnPath(path: PathExpr, thisScope: String, elementScope: String, aliases: Map<String, String>): TransientFieldRead? {
    val start = pathStart(path, thisScope, elementScope, aliases)
    var shape = start.shape
    for ((index, step) in start.steps.withIndex()) {
        val owner = shape ?: return null
        val member = model.membersOf(owner)[step] ?: return null
        if (member.transient) return TransientFieldRead(member.owner, step, index)
        shape = member.type.instanceShape() ?: (member.type as? VType.Coll)?.shape
    }
    return null
}

private fun Validator.pathStart(path: PathExpr, thisScope: String, elementScope: String, aliases: Map<String, String>): PathStart {
    val segmentNames = path.segs.map { segment -> segment.name }
    return when {
        path.root == "this" -> PathStart(thisScope, segmentNames)
        path.root in aliases -> PathStart(aliases.getValue(path.root).takeIf { shape -> shape != VALUE_ALIAS }, segmentNames)
        path.root in model.shapes || path.root in model.refinements -> PathStart(path.root, segmentNames)
        else -> PathStart(elementScope, listOf(path.root) + segmentNames)
    }
}

/** Walks every expression in the spec with a human-readable site label. */
internal fun Validator.forEachSpecExpr(visit: (site: String, expression: Expr) -> Unit) {
    for ((name, shape) in model.shapes) shape.members.forEach { member -> visitShapeMember("shape '$name'", member, visit) }
    for ((name, refinement) in model.refinements) {
        visitCondition("shape '$name'", refinement.expr, visit)
        refinement.members.filterIsInstance<DerivedProp>().forEach { property -> visitTree("shape '$name'", property.expr, visit) }
    }
    for (rule in model.rules.values) visitRule(rule, visit)
    model.nevers.forEach { never -> visitCondition(neverSite(never), never.target, visit) }
}

private fun visitShapeMember(site: String, member: Member, visit: (String, Expr) -> Unit) {
    when (member) {
        is StoredProp -> visitTree(site, member.initially, visit)
        is DerivedProp -> visitTree(site, member.expr, visit)
        else -> {}
    }
}

private fun visitRule(rule: RuleDecl, visit: (String, Expr) -> Unit) {
    val site = "rule '${rule.name}'"
    visitCondition(site, rule.condition, visit)
    rule.body.statements().forEach { item ->
        when (item) {
            is Assignment -> visitTree(site, item.value, visit)
            is Creation -> {
                visitTree(site, item.forExpr, visit)
                item.fields.forEach { field -> visitTree(site, field.value, visit) }
            }
            is ForEach -> item.sourceExprs().forEach { source -> visitTree(site, source, visit) }
            else -> {}
        }
    }
}

private fun visitCondition(site: String, condition: RefExpr, visit: (String, Expr) -> Unit) {
    when (condition) {
        is RefName -> visitTree(site, condition.where, visit)
        is RefAnd -> { visitCondition(site, condition.left, visit); visitCondition(site, condition.right, visit) }
        is RefOr -> { visitCondition(site, condition.left, visit); visitCondition(site, condition.right, visit) }
        is RefNot -> visitCondition(site, condition.inner, visit)
    }
}

/** Visits [expression] and then every expression inside it, outermost first. */
private fun visitTree(site: String, expression: Expr?, visit: (String, Expr) -> Unit) {
    expression ?: return
    visit(site, expression)
    subExpressionsOf(expression).forEach { part -> visitTree(site, part, visit) }
}

/** The site name a `never` is walked under: its subject, so a message names what the author wrote. */
internal fun Validator.neverSite(never: NeverDecl): String =
    "'never' over '${(never.target as? RefName)?.name ?: Printer.refExpr(never.target)}'"

internal fun Validator.findTransientFieldsByShape(): Map<String, Set<String>> {
    return model.shapes.mapValues { (_, shape) ->
        shape.members.filterIsInstance<StoredProp>().filter { property -> property.transient }.map { property -> property.name }.toSet()
    }.filterValues { fields -> fields.isNotEmpty() }
}
