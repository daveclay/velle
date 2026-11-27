package velle.validation

import velle.*
import velle.spec.PathStart
import velle.spec.Scope
import velle.spec.subExpressionsOf

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
        is StoredProp -> transientFieldReads(member.initially, name, allowedAct = null, site = "'$name.${member.name}'")
        is DerivedProp -> transientFieldReads(member.expr, name, allowedAct = null, site = "'$name.${member.name}'")
        else -> {}
    }
}

private fun Validator.checkTransientReadsInRefinement(name: String, refinement: RefinementDecl) {
    transientFieldReadsInRef(refinement.expr, null, "shape '$name'")
    refinement.members.filterIsInstance<DerivedProp>().forEach { property ->
        transientFieldReads(property.expr, name, allowedAct = null, site = "'$name.${property.name}'")
    }
}

private fun Validator.checkTransientFieldsInRule(rule: RuleDecl) {
    val subject = subjectScope(rule) ?: return
    val site = "rule '${rule.name}'"
    // the subject is the bare act, so the body runs once, at the act's commit
    val bareAct = (rule.condition as? RefName)?.takeIf { condition -> condition.where == null && condition.name in model.shapes }?.name
    transientFieldReadsInRef(rule.condition, null, site)
    val bodyScope = rule.by?.let { actor -> Scope(subject).withRecordName(actor.name, actor.shape) } ?: Scope(subject)
    checkTransientFieldsInBody(rule.body, TransientRead(bodyScope, bareAct, site))
}

private fun Validator.checkTransientFieldsInBody(items: List<BodyItem>, read: TransientRead) {
    for (item in items) when (item) {
        is Assignment -> checkTransientFieldsInAssignment(item, read)
        is Creation -> {
            reportTransientReads(item.forExpr, read)
            item.fields.forEach { field -> reportTransientReads(field.value, read) }
        }
        is ForEach -> checkTransientFieldsInBlock(item, read)
        is DeleteStmt, ThenMarker -> {}
    }
}

private fun Validator.checkTransientFieldsInAssignment(assignment: Assignment, read: TransientRead) {
    assignment.reads.forEach { expression -> reportTransientReads(expression, read) }
    val assigned = transientFieldOnPath(assignment.target, read.scope) ?: return
    diagnostics.add(Diagnostic("V37", "${read.site} assigns '${assigned.owner}.${assigned.field}', which is transient — " +
        "it is in the act's input and not in its record, so there is nothing to assign"))
}

/** Each source of a block reads the names before it; the body reads every name of the block (`velle.spec.ScopeRules`). */
private fun Validator.checkTransientFieldsInBlock(block: ForEach, read: TransientRead) {
    val scopes = model.scopes.ofBlock(block, read.scope)
    block.bindings.forEachIndexed { index, binding ->
        expressionsOf(binding.source).forEach { source -> reportTransientReads(source, read.copy(scope = scopes.sources[index])) }
    }
    checkTransientFieldsInBody(block.body, read.copy(scope = scopes.body))
}

/** The expressions a `for each` source reads: its collection, or the two bounds of its range. */
private fun expressionsOf(source: LoopSource): List<Expr> = when (source) {
    is LoopSource.Over -> listOf(source.expr)
    is LoopSource.Range -> listOf(source.from, source.to)
}

/** The reads in a refinement expression: each `where` runs with its name's records as `this`. */
private fun Validator.transientFieldReadsInRef(expr: RefExpr, allowedAct: String?, site: String) {
    when (expr) {
        is RefName -> expr.where?.let { where ->
            transientFieldReads(where, model.baseOf(expr.name) ?: expr.name, allowedAct, site)
        }
        is RefAnd -> { transientFieldReadsInRef(expr.left, allowedAct, site); transientFieldReadsInRef(expr.right, allowedAct, site) }
        is RefOr -> { transientFieldReadsInRef(expr.left, allowedAct, site); transientFieldReadsInRef(expr.right, allowedAct, site) }
        is RefNot -> transientFieldReadsInRef(expr.inner, allowedAct, site)
    }
}

/**
 * Where a read is checked: the [scope] its names are read at, [allowedAct], the act whose own
 * transient fields may be read on the subject itself, and [site], which names the reader in the message.
 */
private data class TransientRead(val scope: Scope, val allowedAct: String?, val site: String)

/**
 * Reports every read of a transient field in [expr], read with a record of [subject] as `this`,
 * that is not a read of [allowedAct]'s own field on the subject itself.
 */
internal fun Validator.transientFieldReads(expr: Expr?, subject: String, allowedAct: String?, site: String) =
    reportTransientReads(expr, TransientRead(Scope(subject), allowedAct, site))

/** Reads each path where it meets one, and each part of any other expression at the scope `ScopeRules` gives it. */
private fun Validator.reportTransientReads(expression: Expr?, read: TransientRead) {
    expression ?: return
    if (expression is PathExpr) {
        reportTransientPathRead(expression, read)
        return
    }
    // a walk's `until` is asked of each record the walk reaches, none of them the act being committed
    val allowedInParts = read.allowedAct.takeUnless { expression is WalkExpr }
    model.scopes.scopedPartsOf(expression, read.scope).forEach { part ->
        reportTransientReads(part.expression, read.copy(scope = part.scope, allowedAct = allowedInParts))
    }
}

private fun Validator.reportTransientPathRead(path: PathExpr, read: TransientRead) {
    val found = transientFieldOnPath(path, read.scope) ?: return
    if (read.allowedAct == found.owner && found.onSubjectItself) return
    diagnostics.add(Diagnostic("V37", "${read.site} reads '${found.owner}.${found.field}', which is transient — " +
        "it is in the act's input and not in its record, so only a `never` over '${found.owner}' " +
        "or a rule on the bare act may read it, at the commit (Reference §4)"))
}

/** A transient field a path reads: the act that owns it, the field, and whether it is the subject's own field, read on the subject. */
private data class TransientFieldRead(val owner: String, val field: String, val onSubjectItself: Boolean)

/** The first transient field a path reads at [scope], or null when the path reads none. */
private fun Validator.transientFieldOnPath(path: PathExpr, scope: Scope): TransientFieldRead? {
    val resolved = model.paths.resolve(path, scope)
    val index = resolved.steps.indexOfFirst { step -> step.member.transient }
    if (index < 0) return null
    val member = resolved.steps[index].member
    // a bare name is the subject's inside no filter; inside one it is the element's
    val startsAtSubject = resolved.start is PathStart.Subject || resolved.start is PathStart.Element && !scope.inFilter
    return TransientFieldRead(member.owner, member.name, onSubjectItself = startsAtSubject && index == 0)
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
            is Assignment -> item.reads.forEach { read -> visitTree(site, read, visit) }
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
