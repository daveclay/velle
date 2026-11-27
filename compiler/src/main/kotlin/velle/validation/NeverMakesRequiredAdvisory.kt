package velle.validation

import velle.*
import velle.spec.partsJoinedByOr

// ── A8 (advisory): a prohibition that is a required declaration (Reference §21) ──
//
// A prohibition over a base shape that only forbids optional fields absent
// on every record admits nothing the required declaration does not; the
// author almost certainly meant the declaration.

internal fun Validator.checkNeverMakesRequired() {
    for (never in model.nevers) {
        val forbiddenRecords = never.target as? RefName ?: continue
        val shape = model.shapes[forbiddenRecords.name] ?: continue
        val forbiddenTests = partsJoinedByOr(forbiddenRecords.where)
        if (forbiddenTests.isEmpty()) continue
        val fields = forbiddenTests.map { disjunct -> optionalFieldForbiddenAbsent(disjunct, shape) ?: continue }
        val fieldNames = fields.joinToString(" and ") { field -> "'${field.name}'" }
        val requiredDeclarations = fields.joinToString(" and ") { field -> "'${field.name}: ${Printer.type(requiredType(field.type))}'" }
        val declarationKind = if (never.requirement != null) "requirement" else "prohibition"
        diagnostics.add(Diagnostic("A8", "${Printer.prohibition(never)} only makes $fieldNames required on every ${shape.name}; " +
            "declare $requiredDeclarations and drop the $declarationKind", advisory = true))
    }
}

/** `field is none` over an optional stored field of [shape]; null for any other test. */
private fun Validator.optionalFieldForbiddenAbsent(test: Expr, shape: ShapeDecl): StoredProp? {
    val absence = test as? IsExpr ?: return null
    if (absence.kind != "none") return null
    val pathNames = (absence.subject as? PathExpr)?.let { path -> PresenceFacts.namesOf(path) } ?: return null
    val fieldName = pathNames.singleOrNull() ?: return null
    val field = shape.members.filterIsInstance<StoredProp>().firstOrNull { member -> member.name == fieldName } ?: return null
    return field.takeIf { isOptionalType(field.type) }
}

/** Whether [type] is declared with `?`. */
private fun isOptionalType(type: TypeRef): Boolean = when (type) {
    is ScalarType -> type.optional
    is RelType -> type.optional
}

/** [type] with its `?` dropped. */
private fun requiredType(type: TypeRef): TypeRef = when (type) {
    is ScalarType -> type.copy(optional = false)
    is RelType -> type.copy(optional = false)
}
