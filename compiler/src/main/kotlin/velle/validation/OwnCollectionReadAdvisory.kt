package velle.validation

import velle.*

// ── A2 (advisory): an `initially` that reads the records pointing at its own record ──
//
// An `initially` that reads the records pointing at the record being
// created (Reference §5). A record cannot be pointed at before it exists, so that collection
// is empty every time the starting value is computed, and what is read from it is a constant.

internal fun Validator.checkInitiallyReadsOwnCollection() {
    for ((shapeName, shape) in model.shapes) {
        for (field in shape.members.filterIsInstance<StoredProp>()) adviseOnOwnCollectionRead(shapeName, field)
    }
}

/** The A2 advisory for [field] of [shapeName] when its `initially` reads such a collection. */
private fun Validator.adviseOnOwnCollectionRead(shapeName: String, field: StoredProp) {
    val initially = field.initially ?: return
    val read = ownCollectionRead(initially, shapeName, subjectLevel = true) ?: return
    diagnostics.add(Diagnostic("A2", "'$shapeName.${field.name}' starts from '${read.spelled}', the ${read.elementShape} records that point at this $shapeName. " +
        "None can when the $shapeName is created, so '${read.spelled}' is empty every time; write the value '${field.name}' starts with, " +
        "or declare it with '=' to keep it current (Reference §5)", advisory = true))
}

/** A collection of the records that point at the record being created, as the `initially` spells it. */
internal class OwnCollectionRead(val spelled: String, val elementShape: String)

/** The first read in [expr] of a collection that is empty when a [shape] is created. A bare name
 *  is the record's own member only at [subjectLevel]; inside a filter it is the element's, and
 *  `this` stays the record. */
private fun Validator.ownCollectionRead(expr: Expr?, shape: String, subjectLevel: Boolean): OwnCollectionRead? = when (expr) {
    null -> null
    is PathExpr -> ownMemberOf(expr, subjectLevel)?.let { memberName -> memberStartingEmpty(memberName, shape, emptySet()) }
    is ExistsExpr -> ownCollectionRead(expr.forExpr, shape, subjectLevel) ?: expr.collection?.let { collection -> ownCollectionReadIn(collection, shape, subjectLevel) }
    is AggCall -> ownCollectionReadIn(expr.collection, shape, subjectLevel)
    is SetExpr -> ownCollectionReadIn(expr.collection, shape, subjectLevel)
    else -> partsOf(expr).firstNotNullOfOrNull { part -> ownCollectionRead(part, shape, subjectLevel) }
}

private fun Validator.ownCollectionReadIn(collection: CollectionExpr, shape: String, subjectLevel: Boolean): OwnCollectionRead? {
    // a member read whole, `count(notes)`, is named as the member; a filter is quoted as written
    val spelled = collection.bindings.singleOrNull()?.source?.takeIf { collection.where == null }?.let { source -> Printer.expr(source) }
        ?: Printer.expr(SetExpr(collection))
    collectionStartingEmpty(collection, shape, emptySet())?.let { elementShape -> return OwnCollectionRead(spelled, elementShape) }
    return collection.bindings.firstNotNullOfOrNull { binding -> ownCollectionRead(binding.source, shape, subjectLevel) }
        ?: ownCollectionRead(collection.where, shape, subjectLevel = false)
}

/** [memberName] of [shape] when it is a collection that is empty when a [shape] is created: the
 *  inverse the compiler infers, or one declared over such a collection. [visiting] ends a
 *  chain of declarations that read one another. */
private fun Validator.memberStartingEmpty(memberName: String, shape: String, visiting: Set<String>): OwnCollectionRead? {
    if (memberName in visiting) return null
    val member = model.membersOf(shape)[memberName] ?: return null
    member.inverse?.let { inverse -> return OwnCollectionRead(memberName, inverse.shape) }
    val declared = (member.derived?.expr as? SetExpr)?.collection ?: return null
    val elementShape = collectionStartingEmpty(declared, shape, visiting + memberName) ?: return null
    return OwnCollectionRead(memberName, elementShape)
}

/** The element shape of [collection] when it is empty at the creation of a [shape]: a filter
 *  whose every record points at the new record, `(Note where chart == this)`, or a filter
 *  over a member that starts empty, `(notes where body != "")`. */
private fun Validator.collectionStartingEmpty(collection: CollectionExpr, shape: String, visiting: Set<String>): String? {
    val source = collection.bindings.singleOrNull()?.source as? PathExpr ?: return null
    val sourceShape = source.root.takeIf { root -> source.segs.isEmpty() && (root in model.shapes || root in model.refinements) }
    if (sourceShape == null) {
        val memberName = ownMemberOf(source, subjectLevel = true) ?: return null
        return memberStartingEmpty(memberName, shape, visiting)?.elementShape
    }
    val filter = collection.where ?: return null
    return sourceShape.takeIf { conjunctsOf(filter).any { conjunct -> pointsAtThis(conjunct, sourceShape) } }
}

/** Whether [condition] is `field == this`, with `field` a stored reference of [elementShape] to one record. */
private fun Validator.pointsAtThis(condition: Expr, elementShape: String): Boolean {
    if (condition !is Binary || condition.op != "==") return false
    val sides = listOf(condition.left, condition.right).filterIsInstance<PathExpr>().filter { side -> side.segs.isEmpty() }
    if (sides.size != 2 || sides.none { side -> side.root == "this" }) return false
    val fieldName = sides.firstOrNull { side -> side.root != "this" }?.root ?: return false
    val field = model.membersOf(elementShape)[fieldName] ?: return false
    return field.stored && field.type.instanceShape() != null
}
