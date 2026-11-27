package velle.validation

import velle.*
import velle.ext.Summable
import velle.spec.BoundElement
import velle.spec.FilterScopes

// ── collections and selectors (Reference §5, §10; checks F1, F2, E3) ────────
//
// A collection's bindings name the records (or values) it runs over; its
// `where` is read on each of them, and a sum's field and a selector's `by`
// datums are members of them.

/** How a collection is read: filtered, summed by a field, or ordered by `by` datums. */
private data class CollectionRead(val collection: CollectionExpr, val sumField: String?, val orderBy: List<String>?) {
    /** `(options as option where ...)`: a single `many` of values filtered by naming each value. */
    fun namesEachValue(binding: Binding): Boolean =
        binding.alias != null && collection.where != null && sumField == null && orderBy == null && collection.bindings.size == 1

    /** Whether the read reaches into the members of each element: what a `many` of values has none of. */
    fun readsMembers(binding: Binding): Boolean =
        collection.where != null || sumField != null || orderBy != null || collection.bindings.size > 1 || binding.alias != null
}

/** What the check of one binding of a collection found: records, values named by an alias, or a refusal that ends the check. */
private sealed interface BindingReading {
    data object Records : BindingReading
    data class NamedValues(val alias: String, val valueType: VType) : BindingReading
    data object Refused : BindingReading
}

/** The aliases a collection's bindings bring into scope, taken out again when the collection is checked. */
private class IntroducedAliases {
    val recordAliases = mutableListOf<String>()
    val valueAliases = mutableListOf<String>()
}

internal fun Validator.checkCollection(
    collection: CollectionExpr,
    scope: String,
    sumField: String? = null,
    orderBy: List<String>? = null,
) {
    val introduced = IntroducedAliases()
    val read = CollectionRead(collection, sumField, orderBy)
    // which records each binding gives and what the `where` is read on is the model's answer (`velle.spec.ScopeRules`)
    val filter = model.scopes.ofFilter(collection, scopeAt(scope))
    if (bindingsAccepted(read, filter, scope, introduced)) checkFilterAndSelectors(read, filter.where.element)
    // the `as` names end with the collection, whether or not a binding was refused
    introduced.recordAliases.forEach { alias -> scopeByAlias.remove(alias) }
    introduced.valueAliases.forEach { alias -> scalarTypeByAlias.remove(alias) }
}

/** The collection's `where`, `sum` field, and `by` datums, read on the records its bindings give. */
private fun Validator.checkFilterAndSelectors(read: CollectionRead, elementShape: String) {
    read.collection.where?.let { where -> checkExpr(where, elementShape) }
    read.sumField?.let { field -> checkSumField(elementShape, field) }
    // selectors' `by` datums: each must be an orderable member of the
    // element — the author's ordering statement, typechecked (F2, Reference §10)
    read.orderBy?.forEach { datum -> checkOrderingDatum(elementShape, datum) }
}

/** Checks each binding in turn and brings its alias into scope; false when one was refused. */
private fun Validator.bindingsAccepted(read: CollectionRead, filter: FilterScopes, scope: String, introduced: IntroducedAliases): Boolean {
    read.collection.bindings.forEachIndexed { index, binding ->
        when (val reading = readBinding(binding, read, scope)) {
            BindingReading.Refused -> return false
            is BindingReading.NamedValues -> {
                scalarTypeByAlias[reading.alias] = reading.valueType
                introduced.valueAliases.add(reading.alias)
            }
            // an alias names this binding's element, visible to the sources after it, the shared
            // `where`, and anything nested inside it (Reference §10, `as`)
            BindingReading.Records -> bindRecordAlias(binding, filter.sources[index], introduced)
        }
    }
    return true
}

private fun Validator.bindRecordAlias(binding: Binding, sourceScope: velle.spec.Scope, introduced: IntroducedAliases) {
    val alias = binding.alias ?: return
    val element = model.scopes.elementOf(binding, sourceScope) as? BoundElement.Records ?: return
    scopeByAlias[alias] = element.shape
    introduced.recordAliases.add(alias)
}

private fun Validator.readBinding(binding: Binding, read: CollectionRead, scope: String): BindingReading =
    when (val source = binding.source) {
        is PathExpr ->
            if (isShapeOrRefinement(source.root)) BindingReading.Records
            else readPathBinding(source, binding, read, scope)
        is ShapeForSource -> readShapeForBinding(source, scope)
        else -> {
            checkExpr(source, scope)
            BindingReading.Records
        }
    }

/** `Shape for x` as a source, `count(Loan for this)`: the shape is known (Reference §10). */
private fun Validator.readShapeForBinding(source: ShapeForSource, scope: String): BindingReading {
    checkExpr(source, scope)
    if (isShapeOrRefinement(source.shape)) return BindingReading.Records
    diagnostics.add(Diagnostic("F1", "unknown shape '${source.shape}'"))
    return BindingReading.Refused
}

/** A binding over a path: a `many` of values has no members to filter, sum, or order by (Reference §5):
 *  it is counted bare, or filtered by naming each value, `(options as o where ...)`. */
private fun Validator.readPathBinding(source: PathExpr, binding: Binding, read: CollectionRead, scope: String): BindingReading {
    if (namesUndeclaredShape(source, scope)) {
        diagnostics.add(Diagnostic("F1", "unknown shape '${source.root}'"))
        return BindingReading.Refused
    }
    val diagnosticCountBeforePath = diagnostics.size
    resolvePath(source, scope)
    val valueCollection = scalarTypeOf(source, scope) as? VType.CollS
    if (valueCollection != null && binding.alias != null && read.namesEachValue(binding))
        return BindingReading.NamedValues(binding.alias, model.scalarOf(valueCollection.name))
    if (valueCollection != null && read.readsMembers(binding)) {
        diagnostics.add(Diagnostic("F2", "'${Printer.expr(source)}' is a collection of values, not of records — " +
            "it can be counted, run over with `for each`, tested with `is empty`, or filtered by naming each value, " +
            "`(${Printer.expr(source)} as value where ...)`, but not filtered by a member, summed, or ordered (Reference §5)"))
        return BindingReading.Refused
    }
    // a path already refused reaches no records, so its filter and selectors are not read on the subject
    if (diagnostics.size > diagnosticCountBeforePath && model.scopes.elementOf(binding, scopeAt(scope)) !is BoundElement.Records) return BindingReading.Refused
    return BindingReading.Records
}

/** A capitalised source that is no shape, alias, or member in scope, `(Counselling where fill == this)`,
 *  is a shape the spec does not declare (Reference §10). */
private fun Validator.namesUndeclaredShape(source: PathExpr, scope: String): Boolean {
    val root = source.root
    if (source.segs.isNotEmpty() || !root.first().isUpperCase()) return false
    if (root in scopeByAlias || root in scalarTypeByAlias || root == ruleActor?.name || narrowedPrefix(source) != null) return false
    return root !in model.membersOf(scope)
}

private fun Validator.checkSumField(elementShape: String, field: String) {
    val member = model.membersOf(elementShape)[field]
    if (member == null) {
        diagnostics.add(Diagnostic("F1", "sum(..., $field) — '$elementShape' has no member '$field'"))
        return
    }
    val extension = extOf(member.type) ?: return
    if (extension !is Summable<*>)
        diagnostics.add(Diagnostic("E3", "sum(..., $field) — ${describe(extension)} declares no sum, so '$elementShape.$field' cannot be summed; implement `Summable` on the type"))
}

private fun Validator.checkOrderingDatum(elementShape: String, datum: String) {
    val member = model.membersOf(elementShape)[datum]
    if (member == null) {
        diagnostics.add(Diagnostic("F2", "selector 'by $datum' — '$elementShape' has no member '$datum'"))
        return
    }
    if (!isOrderable(member.type)) refuseUnorderedDatum(elementShape, datum, member.type)
}

private fun Validator.refuseUnorderedDatum(elementShape: String, datum: String, datumType: VType) {
    val extension = extOf(datumType)
    when {
        extension == null ->
            diagnostics.add(Diagnostic("F2", "selector 'by $datum' — $elementShape.$datum is not orderable (Reference §10)"))
        extension is EnumType ->
            diagnostics.add(Diagnostic("F2", "selector 'by $datum' — ${noOrder(extension, "'$elementShape.$datum'")}; order by another field (Reference §5)"))
        else ->
            diagnostics.add(Diagnostic("E3", "selector 'by $datum' — ${describe(extension)} declares no ordering, so '$elementShape.$datum' cannot order a selector; declare `ordering` on the type"))
    }
}
