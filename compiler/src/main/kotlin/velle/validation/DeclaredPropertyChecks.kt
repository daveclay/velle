package velle.validation

import velle.*

// ── declared types against their values (Reference §5, §7, §10, §14; checks F2, A9, A10) ──
//
// A derived or captured property, an `initially`, a `from` block, and an
// assignment each declare a type; the expression written for it is of that
// type, and a required one takes no expression that may be absent.

/** A derived or captured property (Reference §7): its expression resolves, a walk and a text
 *  literal declare what they make, and the declared type is the expression's. A property that
 *  states no type takes its expression's, and is refused when the compiler reads none. */
internal fun Validator.checkDerivedProperty(owner: String, property: DerivedProp) {
    val before = diagnostics.size
    checkExpr(property.expr, owner)
    checkWalkDeclaration(owner, property)
    checkInterpolationDeclaration(owner, property)
    if (diagnostics.size != before) return
    val declared = property.type
    if (declared == null) {
        checkTypeReadOff(owner, property)
        return
    }
    checkDeclaredType("'$owner.${property.name}'", property.name, model.typeOf(declared), property.expr, owner, COMPUTED)
}

/** A property that states no type is refused when the compiler reads none off its expression. */
private fun Validator.checkTypeReadOff(owner: String, property: DerivedProp) {
    if ((owner to property.name) in model.untypedByCycle) return
    if (model.membersOf(owner).getValue(property.name).type == VType.Unknown)
        diagnostics.add(Diagnostic("F2", "'$owner.${property.name}' states no type, and the compiler reads none off '${Printer.expr(property.expr)}'; declare it"))
}

/** Where a type is declared, as [checkDeclaredType]'s messages name it. */
private data class Declaration(val site: String, val field: String, val declared: VType, val wording: Wording) {
    val declaredBare: VType get() = ExprTypes.bare(declared)

    /** The kind of a value given to an extension type is [checkExtWrite]'s; whether it may be absent is checked here. */
    val kindCheckedElsewhere: Boolean get() = declaredBare is VType.Ext
}

/**
 * The declared type of a property against the expression written for it (Reference §7, §12,
 * §14; checks.md F2): the expression's type is the declared one, the number types being
 * one kind, and a required declaration takes no expression that may be absent. A constant
 * the declared type reads, `"09:00"` for a `time`, is read by it. The kind of a value given to an
 * extension type is [checkExtWrite]'s. An expression the compiler reads no type off is refused.
 */
internal fun Validator.checkDeclaredType(site: String, field: String, declared: VType, value: Expr, scope: String, wording: Wording) {
    if (isRandomUuid(value) || alreadyDiagnosed(value)) return
    if (readConstantAsDeclared(declared, value)) return
    val declaration = Declaration(site, field, declared, wording)
    val actual = model.types.of(value, scope, typeEnv())
    when {
        actual == null -> refuseUntypedValue(declaration, value)
        actual == model.types.absent -> refuseNoneWhereRequired(declaration)
        else -> checkActualType(declaration, actual, value, scope)
    }
}

private fun isRandomUuid(value: Expr): Boolean = value is PathExpr && value.root == "randomUUID" && value.segs.isEmpty()

/** One message per reason: a diagnostic that already names the expression has said what is wrong. */
private fun Validator.alreadyDiagnosed(value: Expr): Boolean {
    val written = Printer.expr(value)
    return diagnostics.any { diagnostic -> !diagnostic.advisory && written in diagnostic.message }
}

/** A constant the declared type reads, `"09:00"` for a `time`, is read by it. Returns whether it was. */
private fun Validator.readConstantAsDeclared(declared: VType, value: Expr): Boolean {
    val constant = constantOf(value) ?: return false
    val reader = registryName(declared)?.let { name -> model.registry.builtins[name] ?: model.registry[name] } ?: return false
    if (!readsForm(reader, constant)) return false
    checkConstantAs(constant, reader, value)
    return true
}

private fun Validator.optionalSpelling(declaration: Declaration): String = model.spell(VType.Optional(declaration.declaredBare))

private fun Validator.refuseUntypedValue(declaration: Declaration, value: Expr) {
    if (declaration.kindCheckedElsewhere) return
    diagnostics.add(Diagnostic("F2", "${declaration.site} — the compiler reads no type off '${Printer.expr(value)}' to check against the declared ${model.spell(declaration.declared)}"))
}

private fun Validator.refuseNoneWhereRequired(declaration: Declaration) {
    if (declaration.declared is VType.Optional) return
    diagnostics.add(Diagnostic("F2", declaration.wording.none(declaration.site, declaration.field, model.words(declaration.declared), optionalSpelling(declaration))))
}

private fun Validator.checkActualType(declaration: Declaration, actual: VType, value: Expr, scope: String) {
    val actualBare = ExprTypes.bare(actual)
    if (!declaration.kindCheckedElsewhere && !declaredAccepts(declaration.declaredBare, actualBare)) {
        val refusal = declaration.wording.mismatch(declaration.site, declaration.field, model.words(declaration.declared),
            model.words(actualBare), model.spell(actualBare))
        diagnostics.add(Diagnostic("F2", refusal + readAsNumberHint(declaration.declaredBare, actualBare, value)))
        return
    }
    // a text literal with an optional piece has its own sentence (checkInterpolationDeclaration); in a
    // `from` block and an assignment a selection that matches nothing is the runtime's to refuse (Reference §14)
    if (actual !is VType.Optional || declaration.declared is VType.Optional || value is Interpolation) return
    checkRequiredMayBeAbsent(declaration, value, scope)
}

/** A number given a duration is told how to read the duration as a number (Reference §5). */
private fun readAsNumberHint(declared: VType, actual: VType, value: Expr): String =
    if (declared is VType.Num && actual == VType.DurationT) ", such as 'days(${Printer.expr(value)})' (Reference §5)" else ""

/** A required declaration given a value that may be absent: refused when the absence is declared, or
 *  when the site refuses a selection no test shows to match; otherwise perhaps advised (A9). */
private fun Validator.checkRequiredMayBeAbsent(declaration: Declaration, value: Expr, scope: String) {
    val wording = declaration.wording
    when {
        model.types.absenceDeclared(value, scope, typeEnv()) ->
            diagnostics.add(Diagnostic("F2", wording.absent(declaration.site, declaration.field, model.words(declaration.declared), optionalSpelling(declaration))))
        wording.unshownSelection != null && model.types.absenceDeclared(value, scope, typeEnv(), selectionsCount = true) ->
            diagnostics.add(Diagnostic("F2", unshownSelectionMessage(declaration, value, scope)))
        else -> adviseOnMissOutsideTransaction(declaration.site, value, scope)
    }
}

/** A required derived or captured property, or a required `initially`, computed through a
 *  selection no test shows to match (Reference §5, §7): the message names the selection and the
 *  test that shows it matches. */
private fun Validator.unshownSelectionMessage(declaration: Declaration, value: Expr, scope: String): String {
    val declaredWords = model.words(declaration.declared)
    val optionalSpelled = optionalSpelling(declaration)
    val selection = model.types.unshownSelection(value, scope, typeEnv())
    val test = selection?.let { model.types.existsTestFor(it) }
    val refusal = declaration.wording.unshownSelection
    if (selection == null || test == null || refusal == null)
        return declaration.wording.absent(declaration.site, declaration.field, declaredWords, optionalSpelled)
    return refusal(declaration.site, declaration.field, declaredWords, optionalSpelled, Printer.expr(selection), Printer.expr(test))
}

/**
 * A9 (Reference §14): a rule that fires after the commit or on a schedule fills a required field
 * with a selection nothing shows to match. Inside the act's transaction a miss refuses the act;
 * here the act has already been kept, so the miss is only a failed firing. The rule's own
 * condition showing the selection matches silences it.
 */
private fun Validator.adviseOnMissOutsideTransaction(site: String, value: Expr, scope: String) {
    val rule = ruleBeingChecked ?: return
    if (rule.preposition != "after" && rule.triggers.all { trigger -> trigger == "commit" }) return
    val selection = model.types.unshownSelection(value, scope, typeEnv()) ?: return
    val test = model.types.existsTestFor(selection)?.let { Printer.expr(it) } ?: return
    addAdvisoryOnce(Diagnostic("A9", "$site — the field is required and '${Printer.expr(selection)}' may match nothing; the rule fires " +
        "outside the transaction of the act that triggers it, so a miss refuses nothing: the record stays and the firing fails. " +
        "Fire the rule only when the selection matches, with '$test' in its condition (Reference §14)", advisory = true))
}

/** An expression is checked once per reading of it; an advisory about it is said once. */
private fun Validator.addAdvisoryOnce(advisory: Diagnostic) {
    if (diagnostics.none { said -> said.code == advisory.code && said.message == advisory.message }) diagnostics.add(advisory)
}

/** A10 (Reference §10): `?.` directly after a selection says what the plain dot says there, the
 *  rest of the path being absent when the selection matched nothing. */
internal fun Validator.adviseOnQuestionMarkAfterSelection(access: Access) {
    if (model.types.collectionSelectedFrom(access.target) == null || !access.segs.first().viaQdot) return
    val plain = access.copy(segs = listOf(access.segs.first().copy(viaQdot = false)) + access.segs.drop(1))
    addAdvisoryOnce(Diagnostic("A10", "'${Printer.expr(access)}' — the '?' after '${Printer.expr(access.target)}' adds nothing: a read through a selection " +
        "is absent when the selection matched nothing; write '${Printer.expr(plain)}' (Reference §10)", advisory = true))
}

/** Whether a declaration of [declared] holds a value of [actual]: the same type, any number
 *  for a number (Reference §5), a record or collection of a shape the declaration admits. */
private fun Validator.declaredAccepts(declared: VType, actual: VType): Boolean = when {
    actual == VType.Unknown -> true
    declared is VType.Num && actual is VType.Num -> true
    actual == model.types.emptyCollection -> declared is VType.Coll || declared is VType.CollS
    declared is VType.Inst && actual is VType.Inst -> model.types.admits(declared.shape, actual.shape)
    declared is VType.Coll && actual is VType.Coll -> model.types.admits(declared.shape, actual.shape)
    declared is VType.CollS && actual is VType.CollS -> valueCollectionsAgree(declared, actual)
    else -> declared == actual
}

/** Two `many`s of values agree when their values are of one type, the number types being one kind. */
private fun valueCollectionsAgree(declared: VType.CollS, actual: VType.CollS): Boolean =
    declared.name == actual.name || (declared.name in TypeRegistry.NUMBER_NAMES && actual.name in TypeRegistry.NUMBER_NAMES)

/** A text literal with pieces is absent when a piece is (Reference §5): a property holding one
 *  with an optional piece is declared `text?`. A refinement body may narrow the piece, so it is left alone. */
private fun Validator.checkInterpolationDeclaration(owner: String, property: DerivedProp) {
    val literal = property.expr as? Interpolation ?: return
    val declared = property.type as? ScalarType ?: return
    if (declared.optional || owner in model.refinements) return
    val absentPiece = literal.pieces.filterIsInstance<PathExpr>().firstOrNull { piece -> pathMayBeAbsent(piece, owner) } ?: return
    diagnostics.add(Diagnostic("F2", "'$owner.${property.name}' — the text is absent when '${Printer.expr(absentPiece)}' is; declare it `text?`"))
}

private fun Validator.pathMayBeAbsent(path: PathExpr, scope: String): Boolean =
    membersAlong(path, scope)?.any { member -> member.type is VType.Optional } ?: false

/** A derived property spelled as a walk declares the type the walk has: a
 *  `many` of the reached shape, or, with `until`, a `one ...?` of it. */
private fun Validator.checkWalkDeclaration(owner: String, property: DerivedProp) {
    val walk = property.expr as? WalkExpr ?: return
    // a walk property that states no type takes the walk's (Reference §7)
    val declared = property.type ?: return
    val target = model.walkTarget(walk, owner) ?: return
    val problem = walkDeclarationProblem(walk, declared as? RelType, target) ?: return
    diagnostics.add(Diagnostic("F2", "'$owner.${property.name}' — $problem"))
}

/** What is wrong with declaring [walk] as [declared], a relationship or (null) a value; null when nothing is. */
private fun Validator.walkDeclarationProblem(walk: WalkExpr, declared: RelType?, target: String): String? {
    if (declared == null)
        return "a walk gives records of '$target', not a value; declare it `${if (walk.until != null) "one $target?" else "many $target"}`"
    walkCardinalityProblem(walk, declared, target)?.let { problem -> return problem }
    if (!model.typeAccepts(declared.shape, target))
        return "the walk reaches records of '$target', but the property is declared over '${declared.shape}'"
    return null
}

/** A walk with `until` is declared `one ...?`; one without it is declared `many`. */
private fun walkCardinalityProblem(walk: WalkExpr, declared: RelType, target: String): String? = when {
    walk.until != null && (declared.many || !declared.optional) -> "a walk with 'until' is the one record found or none; declare it `one $target?`"
    walk.until == null && !declared.many -> "a walk gives every record reached; declare it `many $target`"
    else -> null
}
