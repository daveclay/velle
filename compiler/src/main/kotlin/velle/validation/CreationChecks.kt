package velle.validation

import velle.*

// ── F1/F3/F4/V13/V20: a rule's assignments and creations ─────────────────
//
// An assignment writes a stored field, and its value fits the field's type.
// A creation names a shape with records of its own, supplies stored fields
// only, gives every stored field without an initializer a value, and its
// `for` sugar matches exactly one field. The `as` names a creation binds are
// checked in CreationAliasCheck.kt.

internal fun Validator.checkAssignment(rule: RuleDecl, scope: String, assignment: Assignment) {
    val write = resolveWrite(rule, scope, assignment, acceptedBlockWrite(rule, scope, assignment)) ?: return
    if (!checkFilteredLeftSide(rule, scope, assignment, write)) return
    if (!write.member.stored) reportUnassignableMember(rule, write)
    val diagnosticsBefore = diagnostics.size
    checkExpr(assignment.value, scope)
    checkExtWrite(write.member.type, assignment.value, scope, "rule '${rule.name}' assigns ${write.owner}.${write.member.name}")
    if (write.member.stored && diagnostics.size == diagnosticsBefore)
        checkDeclaredType("rule '${rule.name}' assigns '${write.owner}.${write.member.name}'", write.member.name, write.member.type, assignment.value, scope, ASSIGNED)
}

/** V20: a filtered left side chooses members of a collection (Reference §12); its filter is checked as any filter is.
 *  Returns false when the left side is refused. */
private fun Validator.checkFilteredLeftSide(rule: RuleDecl, scope: String, assignment: Assignment, write: Write): Boolean {
    val collection = assignment.filteredCollection ?: return true
    if (!write.fanOut) {
        val path = Printer.expr(collection.collection.bindings.single().source)
        diagnostics.add(Diagnostic("V20", "rule '${rule.name}' assigns '${Printer.assignmentTarget(assignment)}', and '$path' is one " +
            "record, not a collection; a filter on the left chooses which members of a collection are written, so write " +
            "'$path.${write.member.name} = ...' and put the test in the rule's condition (Reference §12)"))
        return false
    }
    checkExpr(collection, scope)
    return true
}

private fun Validator.reportUnassignableMember(rule: RuleDecl, write: Write) {
    val member = write.member
    // V20: an inferred inverse or derived collection is a view, never a
    // target — you may only assign what is stored (Reference §6)
    if (member.inverse != null || member.type is VType.Coll) {
        diagnostics.add(Diagnostic("V20", "rule '${rule.name}' assigns ${write.owner}.${member.name} — " +
            "a collection view is never an assignment target: assign the declared side " +
            "(whole-set replacement) or write through it (fan-out), Reference §6"))
        return
    }
    diagnostics.add(Diagnostic("F3", "rule '${rule.name}' assigns ${write.owner}.${member.name} — ${unassignableKind(member)} is never assignable"))
}

/** What kind of member that is not stored [member] is, as F3's message names it. */
private fun unassignableKind(member: MemberInfo): String = when {
    member.timestamp -> "a timestamp field (commit metadata)"
    member.name == "id" -> "'id'"
    member.captured -> "a captured property"
    else -> "a derived property"
}

internal fun Validator.checkCreation(rule: RuleDecl, scope: String, creation: Creation) {
    val shape = createdShapeOrReport(rule, creation) ?: return
    creation.forExpr?.let { target ->
        checkExpr(target, scope)
        checkForTypeMatch(creation.shape, target, scope, "rule '${rule.name}'")
    }
    val members = model.membersOf(creation.shape)
    creation.fields.forEach { field -> checkSuppliedField(rule, scope, creation, field, members[field.name]) }
    checkEveryFieldHasValue(rule, scope, creation, shape)
}

/** The declaration of the shape [creation] makes, or null with F1 when that shape has no records of its own. */
private fun Validator.createdShapeOrReport(rule: RuleDecl, creation: Creation): ShapeDecl? {
    if (creation.shape in model.sharedShapes) {
        val takers = model.basesOf(creation.shape)
        diagnostics.add(Diagnostic("F1", "rule '${rule.name}' creates '${creation.shape}', which has no records of its own: " +
            "${takers.joinToString(" and ")} ${if (takers.size == 1) "is" else "are"} ${creation.shape}; create one of them"))
        return null
    }
    val shape = model.shapes[creation.shape]
    if (shape != null) return shape
    val operands = model.refinements[creation.shape]?.let { model.basesOf(creation.shape) }.orEmpty()
    diagnostics.add(if (operands.isEmpty()) Diagnostic("F1", "rule '${rule.name}' creates unknown shape '${creation.shape}'")
        else Diagnostic("F1", "rule '${rule.name}' creates '${creation.shape}', which has no records of its own: " +
            "a record of it is a ${operands.joinToString(" or a ")}; create one of them (Reference §9)"))
    return null
}

private fun Validator.checkSuppliedField(rule: RuleDecl, scope: String, creation: Creation, field: FieldInit, member: MemberInfo?) {
    unsuppliableFieldProblem(rule, creation, field, member)?.let { problem -> diagnostics.add(problem) }
    val diagnosticsBefore = diagnostics.size
    checkExpr(field.value, scope)
    if (member != null && member.stored && diagnostics.size == diagnosticsBefore)
        checkDeclaredType("rule '${rule.name}' supplies '${creation.shape}.${field.name}'", field.name, member.type, field.value, scope, SUPPLIED)
}

/** F1 or F3 when [field] names no field of the created shape, or one a creation never supplies. */
private fun unsuppliableFieldProblem(rule: RuleDecl, creation: Creation, field: FieldInit, member: MemberInfo?): Diagnostic? = when {
    member == null -> Diagnostic("F1", "rule '${rule.name}' — '${creation.shape}' has no field '${field.name}'")
    member.timestamp -> Diagnostic("F3", "rule '${rule.name}' supplies '${creation.shape}.${field.name}' — timestamp fields are commit metadata")
    member.actor -> Diagnostic("F3", "rule '${rule.name}' supplies '${creation.shape}.${field.name}' — the actor is a fact of the act's commit, never supplied (Reference §22)")
    !member.stored -> Diagnostic("F3", "rule '${rule.name}' supplies derived '${creation.shape}.${field.name}'")
    else -> null
}

/** F4 totality: every stored field without an initializer must be supplied
 *  (the `for` form's type-matched field counts as supplied). */
private fun Validator.checkEveryFieldHasValue(rule: RuleDecl, scope: String, creation: Creation, shape: ShapeDecl) {
    val required = shape.members.filterIsInstance<StoredProp>()
        .filter { property -> property.initially == null }.map { property -> property.name }
    val supplied = creation.fields.map { field -> field.name }.toSet()
    val coveredByFor = if (creation.forExpr != null) typeMatchedFields(creation.shape, creation.forExpr, scope) else emptyList()
    (required - supplied - coveredByFor.toSet()).forEach { fieldName ->
        diagnostics.add(Diagnostic("F4", "rule '${rule.name}' — '${creation.shape}.$fieldName' has no value (from-mapping is totality-checked)"))
    }
}

/** V13: `for <expr>` sugar needs exactly one type-matched field. */
internal fun Validator.checkForTypeMatch(shape: String, forExpr: Expr, scope: String, where: String) {
    val matches = typeMatchedFields(shape, forExpr, scope)
    if (matches.size != 1)
        diagnostics.add(Diagnostic("V13", "$where — 'for' matches ${matches.size} fields of '$shape' " +
            "(needs exactly one; name the field or use the where-filtered form)"))
}

internal fun Validator.typeMatchedFields(shape: String, expr: Expr, scope: String): List<String> {
    val exprShape = instanceShapeOf(expr, scope) ?: return emptyList()
    return model.typeMatchedFields(shape, exprShape)
}

/** The shape of the one record after `for`, placed as the condition's reads place it
 *  (`Model.forRecordShape`), and read as the shape a field could be typed with: a refinement
 *  as its base shape, or as the static union it is drawn from (Reference §9). Null when the
 *  record cannot be placed. */
private fun Validator.instanceShapeOf(expression: Expr, scope: String): String? {
    val thisShape = thisScope ?: scope
    if (expression == PathExpr("this")) return model.viewScopeOf(thisShape)
    val recordShape = model.forRecordShape(expression, scope, scopeByAlias, thisShape) ?: return null
    return model.viewScopeOf(recordShape) ?: recordShape
}
