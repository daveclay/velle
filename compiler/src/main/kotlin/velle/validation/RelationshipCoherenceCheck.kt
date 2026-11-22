package velle.validation

import velle.*

// ── V19: relationship declaration coherence (Reference §6) ──────────────────
//
// The declared side owns a relationship; the inverse is inferred. A bare
// declared `many` *means* an owned m2m edge set, so declaring both sides of
// one relationship — a `many B` on A while B declares any side back at A —
// is either two distinct relationships or two owners of one edge set, and
// the compiler refuses to guess. (Ambiguous inference — two same-target
// declared sides on one shape — is handled by not inferring the inverse;
// use sites then demand declared derived views, Reference §6's Transfer case.)

internal fun Validator.checkRelationshipCoherence() {
    for ((ownerName, owner) in model.shapes) {
        for (field in owner.members.filterIsInstance<StoredProp>()) {
            val relationship = field.type as? RelType ?: continue
            if (!relationship.many) continue
            val target = model.shapes[relationship.shape] ?: continue
            checkTargetDeclaresNoSideBack(ownerName, field, relationship.shape, target)
        }
    }
}

/** V19 for each field of [target] that points back at [ownerName] while [field] is a `many` of [target]. */
private fun Validator.checkTargetDeclaresNoSideBack(ownerName: String, field: StoredProp, targetName: String, target: ShapeDecl) {
    for (backField in target.members.filterIsInstance<StoredProp>()) {
        val backRelationship = backField.type as? RelType ?: continue
        if (backRelationship.shape != ownerName) continue
        if (ownerName == targetName && field === backField) continue // a self-referential edge set is one side
        if (backRelationship.many && ownerName > targetName) continue  // symmetric case: report once
        diagnostics.add(Diagnostic("V19", "'$ownerName.${field.name}: many $targetName' and " +
            "'$targetName.${backField.name}: ${if (backRelationship.many) "many" else "one"} $ownerName' declare both sides — " +
            "two owners of one edge set, or two distinct relationships; drop one side " +
            "(the inverse is inferred), or make the collection a derived view " +
            "(`${field.name}: many $targetName = (...)`), Reference §6"))
    }
}

internal fun Validator.checkRefinementDeclarations() {
    for ((name, refinement) in model.refinements) {
        val bases = model.basesOfExpr(refinement.expr)
        if (bases.isEmpty()) continue
        checkWherePredicates(refinement.expr)
        if (bases.size > 1) checkCrossBaseUnion(name, refinement, bases)
        refinement.members.forEach { member -> checkRefinementMember(name, member) }
    }
    model.nevers.forEach { never -> checkWherePredicates(never.target) }
}

private fun Validator.checkRefinementMember(name: String, member: Member) {
    when (member) {
        is DerivedProp -> checkDerivedProperty(name, member)
        is FrozenClause -> checkFrozenFieldsAreStored(name, member)
        else -> {}
    }
}

/** F3: a refinement freezes only stored fields of its base shape. */
private fun Validator.checkFrozenFieldsAreStored(name: String, frozen: FrozenClause) {
    val base = model.baseOf(name) ?: return
    val storedFields = model.shapes[base]?.members?.filterIsInstance<StoredProp>()?.map { property -> property.name } ?: emptyList()
    frozen.fields.filter { field -> field !in storedFields }.forEach { field ->
        diagnostics.add(Diagnostic("F3", "'$name' freezes '$field', which is not a stored field of '$base'"))
    }
}

/**
 * A cross-base union (Reference §9 extended across unrelated bases): the
 * property rule is unchanged, so its body may carry derived properties only
 * (Reference §9 — `frozen` has no base to name, a creation-entered capture is
 * `initially` on each operand), and a transient operand is refused: a shape
 * the acts take with `is` is the spelling that answers several requests with
 * one rule (Reference §9, "Sharing a field across shapes").
 */
private fun Validator.checkCrossBaseUnion(name: String, refinement: RefinementDecl, bases: Set<String>) {
    // a taken shape is one declaration its takers inherit, so a transient taker is what
    // the refusal below waits for (Reference §9, "Sharing a field across shapes")
    if (name in model.sharedShapes) return
    bases.filter { base -> base in model.transients }.forEach { transientAct ->
        diagnostics.add(Diagnostic("V17", "'$name' composes transient act '$transientAct' with unrelated shapes — " +
            "a union with a transient operand is refused, since every read over it would be a branch per act; " +
            "declare the field the acts share on a shape they take with 'is' and write the rule over that shape " +
            "(Reference §9), or write one rule per act"))
    }
    refinement.members.forEach { member -> checkCrossBaseUnionMember(name, member, bases) }
}

private fun Validator.checkCrossBaseUnionMember(name: String, member: Member, bases: Set<String>) {
    when (member) {
        is DerivedProp -> if (member.captured)
            diagnostics.add(Diagnostic("F3", "'$name.${member.name}' — a cross-base union is entered only by creation, " +
                "so a capture there is `initially` on each operand; declare it on ${bases.joinToString(" and ")} (Reference §9)"))
        is FrozenClause ->
            diagnostics.add(Diagnostic("F3", "'$name' declares `frozen` — a cross-base union has no base shape whose " +
                "stored fields it could name; freeze on a refinement of each operand (Reference §9)"))
        UndeletableClause ->
            diagnostics.add(Diagnostic("F3", "'$name' declares `undeletable` on a cross-base union — " +
                "gate deletion on a refinement of each operand (Reference §9)"))
        else -> {}
    }
}

/** Checks each `where` in a refinement expression against the shape it names. */
private fun Validator.checkWherePredicates(expression: RefExpr) {
    when (expression) {
        // a predicate over a union reads the union's own members (Reference §9): what every
        // operand takes from one declaration, the shared inverses, and anything narrowed
        is RefName -> expression.where?.let { predicate -> if (model.basesOf(expression.name).isNotEmpty()) checkExpr(predicate, expression.name) }
        is RefNot -> checkWherePredicates(expression.inner)
        is RefAnd -> { checkWherePredicates(expression.left); checkWherePredicates(expression.right) }
        is RefOr -> { checkWherePredicates(expression.left); checkWherePredicates(expression.right) }
    }
}
