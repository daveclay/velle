package velle.validation

import velle.*

// ── F1/F2/F3: declarations ───────────────────────────────────────────────

/** A field or derived property whose declaration writes a type. */
private data class TypedMember(val owner: String, val name: String, val type: TypeRef)

/** Every capitalized name in type position resolves (Reference §5): `Date`, `DateTime`,
 *  or a registered extension type in scope. A shape's name after `one`/`many` is a
 *  relationship and checked in [checkShapeDeclarations]; a registered type there is F1. */
internal fun Validator.checkTypeNames() {
    val shapeMembers = model.shapes.values.flatMap { shape -> shape.members.mapNotNull { member -> typedMember(shape.name, member) } }
    val refinementMembers = model.refinements.values.flatMap { refinement -> refinement.members.mapNotNull { member -> typedMember(refinement.name, member) } }
    (shapeMembers + refinementMembers).forEach { member -> checkTypeName(member) }
}

private fun typedMember(owner: String, member: Member): TypedMember? = when (member) {
    is StoredProp -> TypedMember(owner, member.name, member.type)
    is DerivedProp -> member.type?.let { type -> TypedMember(owner, member.name, type) }
    else -> null
}

private fun Validator.checkTypeName(member: TypedMember) {
    val at = "'${member.owner}.${member.name}'"
    when (val type = member.type) {
        is ScalarType -> if (!TypeRegistry.isBuiltin(type.name)) model.resolveTypeName(type.name, at)
        is RelType -> if (type.shape !in model.shapes && type.shape !in model.refinements && type.shape in model.typeScope)
            diagnostics.add(Diagnostic("F1", "$at: '${type.shape}' is a registered type, not a shape; " +
                "a field of it is `${member.name}: ${type.shape}`" + (if (type.many) " or `many ${type.shape}`" else "")))
    }
}

internal fun Validator.checkShapeDeclarations() {
    for ((name, shape) in model.shapes) {
        for (member in shape.members) when (member) {
            is StoredProp -> checkStoredProperty(name, member)
            is DerivedProp -> checkDerivedProperty(name, member)
            is ActorProp -> checkActorClause(name, shape, member)
            else -> {}
        }
    }
}

private fun Validator.checkStoredProperty(shapeName: String, property: StoredProp) {
    if (property.name == "id") diagnostics.add(Diagnostic("F3", "'$shapeName.id' — 'id' is reserved (Reference §5)"))
    (property.type as? RelType)?.let { relationship -> checkRelationshipTarget(shapeName, property, relationship) }
    property.initially?.let { initialValue -> checkInitialValue(shapeName, property, initialValue) }
    checkInitiallyRequired(shapeName, property)
    checkTolerates(shapeName, property)
}

private fun Validator.checkRelationshipTarget(shapeName: String, property: StoredProp, relationship: RelType) {
    val target = relationship.shape
    if (target !in model.shapes && target !in model.refinements && target !in model.typeScope)
        diagnostics.add(Diagnostic("F1", "'$shapeName.${property.name}' relates to unknown shape '$target'"))
    // a static union — a pure `or` of base shapes — is a stable target
    // type (Reference §6); any refinement carrying a predicate is not
    if (target in model.refinements && !model.isStaticUnion(target))
        diagnostics.add(Diagnostic("F2", "'$shapeName.${property.name}' — relationships target base shapes, not refinements: " +
            "a field type is fixed at the write, a membership drifts after it (Reference §6). " +
            (if (model.isCrossBase(target))
                "'$target' is a union whose operands carry a predicate; a field's target union must be " +
                "a pure `or` of base shapes (Reference §9)"
            else "State the condition on the act that creates '$shapeName': '$shapeName requires ${property.name} to be $target', or a gate refinement")))
}

private fun Validator.checkInitialValue(shapeName: String, property: StoredProp, initialValue: Expr) {
    val fieldType = model.typeOf(property.type)
    val diagnosticsBefore = diagnostics.size
    checkExpr(initialValue, shapeName, allowGenerator = true)
    checkExtWrite(fieldType, initialValue, shapeName, "'$shapeName.${property.name} initially'")
    if (diagnostics.size == diagnosticsBefore) checkDeclaredType("'$shapeName.${property.name}'", property.name, fieldType, initialValue, shapeName, INITIALLY)
}

/** `initially required` demands an optional type: the whole point of
 *  the decomposition is read-side optionality with creation-side
 *  requiredness (OQ37-R10); a plain field is already both. */
private fun Validator.checkInitiallyRequired(shapeName: String, property: StoredProp) {
    val declaredType = property.type
    if (!property.initiallyRequired) return
    if ((declaredType as? RelType)?.optional == true || (declaredType as? ScalarType)?.optional == true) return
    diagnostics.add(Diagnostic("F3", "'$shapeName.${property.name}' — 'initially required' needs an " +
        "optional type ('?'): a plain field is already required at creation and can " +
        "never be absent (OQ37)"))
}

private fun Validator.checkTolerates(shapeName: String, property: StoredProp) {
    val tolerated = property.tolerates ?: return
    if (tolerated !in setOf("duplication", "reordering"))
        diagnostics.add(Diagnostic("F3", "'$shapeName.${property.name}' — fields tolerate 'duplication' or 'reordering', not '$tolerated'"))
    // a `many` of records is a set of links: a link cannot arrive twice, so
    // there is no duplication to tolerate (Reference §5, "Collections of values")
    val relationship = property.type as? RelType
    if (tolerated == "duplication" && relationship != null && relationship.many)
        diagnostics.add(Diagnostic("F3", "'$shapeName.${property.name}' — a `many ${relationship.shape}` holds each record once, so it cannot tolerate duplication; " +
            "if how many times a link occurs matters, that count is data on a link shape (Reference §6)"))
}

// ── the actor clause (Reference §22, "The actor") ────────────────────────
//
// `act X by Shape [as name]`: the shape is a `shape`, a static union of
// shapes, or a refinement of one — never an act, since an act is an input
// and the person who commits one is a record. The bound name is `actor`
// unless `as` chose another, and nothing is reserved: a scope that declares
// a field under the bound name is told to name the actor, not to rename
// the field.

private fun Validator.checkActorClause(act: String, shape: ShapeDecl, actor: ActorProp) {
    if (actor.shape !in model.shapes && actor.shape !in model.refinements) {
        diagnostics.add(Diagnostic("F1", "'$act by ${actor.shape}' names unknown shape '${actor.shape}'"))
        return
    }
    model.basesOf(actor.shape).filter { base -> base in model.exposed }.forEach { actBase ->
        diagnostics.add(Diagnostic("F2", "'$act by ${actor.shape}' — the actor's shape is a `shape`, never an act: " +
            "an act is an input, and the person who commits one is a record; declare '$actBase' as a shape " +
            "and create its records with a rule (Reference §22, \"The actor\")"))
    }
    if (actor.name == "id")
        diagnostics.add(Diagnostic("F3", "'$act by ${actor.shape} as id' — 'id' is reserved (Reference §5)"))
    val clash = shape.members.any { member -> member !is ActorProp && memberName(member) == actor.name }
    if (clash) diagnostics.add(Diagnostic("F3",
        if (actor.named) "'$act' declares a field '${actor.name}' and names its actor '${actor.name}' too — one name, two things"
        else "'$act' declares a field 'actor' and binds its actor under that name by default — " +
            "name the actor: `act $act by ${actor.shape} as <name>` (Reference §22, \"The actor\")"))
}

/** The name [member] declares, or null for a member that declares none. */
internal fun Validator.memberName(member: Member): String? = when (member) {
    is StoredProp -> member.name
    is DerivedProp -> member.name
    is TimestampProp -> member.name
    is ActorProp -> member.name
    else -> null
}
