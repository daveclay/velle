package velle

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * The act boundary: reads a caller's raw input (Kotlin values, handles, and nested
 * maps for an act's parts) as the creation list of one commit, or refuses it at the
 * type level before anything is written. The exposure closure (Reference §6,
 * "Inline part creation"): the act plus its declared inline parts land as ONE
 * commit — parts are values created here, back-references language-populated,
 * entrants container-and-parts. A `one` field named in `with` may arrive by value
 * instead of by handle (OQ63 §6.1): the nested record is created in the same
 * commit, first.
 */
internal class ActInput(private val system: VelleSystem) {
    private val model get() = system.model

    /** [suppliedFields] and [parts] for an act of [shape] committed by [actor], as one commit's creation list. Throws [InputRefusal]. */
    fun prepare(
        shape: String,
        suppliedFields: Map<String, Any?>,
        parts: Map<String, List<Map<String, Any?>>>,
        actor: Long?,
    ): PreparedAct {
        val decl = model.shapes[shape]
            ?: throw InputRefusal("type: unknown shape '$shape'")
        if (shape !in model.exposed)
            throw InputRefusal("type: shape '$shape' is not an act — it enters state only as a rule's effect")
        duplicateRefusal(decl, suppliedFields)?.let { reason -> throw InputRefusal(reason) }
        checkActor(shape, decl, suppliedFields, actor)

        val edges = model.closures[shape].orEmpty()
        parts.keys.find { partName -> edges.none { edge -> edge.prop == partName } }?.let { partName ->
            throw InputRefusal("type: '$shape' declares no closure edge '$partName'")
        }
        // a `one` field pointing at a new record elsewhere in this act (OQ63 §6.2.2)
        // is resolved after every value is appended, below
        val split = splitNewRefs(suppliedFields)
        val skipped = byValueFieldsGiven(edges, parts) + split.siblingFields
        val supplied = convertFields(decl, split.own, skipRequired = skipped)
            ?: throw InputRefusal(typeFailure(decl, split.own))
        val actorProp = decl.actor
        val converted = if (actorProp != null) supplied + (actorProp.name to Value.VRef(actor!!)) else supplied

        val build = ClosureBuild()
        val rootIndex = appendRecord(shape, converted, parts, edges, build)
        build.addSiblingReferences(rootIndex, split)
        val ordered = resolveSiblings(build)
        return PreparedAct(ordered.creations, ordered.newIndexOf[rootIndex])
    }

    /** [fields] for a record of [shape] seeded outside the trust boundary ([VelleSystem.seed]). Throws [VelleRuntimeError]. */
    fun prepareSeed(shape: String, fields: Map<String, Any?>): Map<String, Value> {
        val decl = model.shapes[shape] ?: throw VelleRuntimeError("seed: unknown shape '$shape'")
        if (shape in model.transients) throw VelleRuntimeError("seed: '$shape' is a transient act, not a record")
        duplicateRefusal(decl, fields)?.let { reason -> throw VelleRuntimeError("seed: $reason") }
        return convertFields(decl, fields) ?: throw VelleRuntimeError("seed: " + typeFailure(decl, fields))
    }

    /**
     * The actor (Reference §22, "The actor") is required exactly when the act declares
     * `by` and refused otherwise. It is never a supplied field — a fact about the
     * commit the committer may not claim (Reference §5). A record outside the base
     * shape is a type-level refusal here; one outside the refinement the clause names
     * is the desugared requirement's refusal (Model).
     */
    private fun checkActor(shape: String, decl: ShapeDecl, suppliedFields: Map<String, Any?>, actor: Long?) {
        val actorProp = decl.actor
        if (actorProp == null) {
            if (actor != null) throw InputRefusal("type: '$shape' declares no actor (no `by` clause), yet one was given")
            return
        }
        if (actorProp.name in suppliedFields)
            throw InputRefusal("type: '$shape.${actorProp.name}' is the actor — a fact of the commit the committer cannot claim (Reference §22)")
        if (actor == null)
            throw InputRefusal("type: '$shape' is committed by a ${actorProp.shape} (`by ${actorProp.shape}`), and none was given")
        val bases = model.basesOf(actorProp.shape)
        if (bases.none { base -> acceptedReference(actor, base) })
            throw InputRefusal("type: the actor of '$shape' is not a ${bases.joinToString(" or ")}")
    }

    // ── the closure: the act's record, its by-value records, and its parts ───

    /**
     * Append one record and its closure to the commit's creation list, returning
     * the record's index. Order is dependency order, so every pending reference
     * points backward (Hydration.kt, [Ref.Pending]): a value nested at a `one`
     * field named in `with` is appended before the record that points at it, and
     * a part is appended after the record its back-reference points at.
     */
    private fun appendRecord(
        shape: String,
        converted: Map<String, Value>,
        supplied: Map<String, List<Map<String, Any?>>>,
        edges: List<ClosureEdge>,
        build: ClosureBuild,
    ): Int {
        val nested = mutableMapOf<String, Value>()
        for (edge in edges.filter { edge -> edge.byValue }) {
            appendByValue(shape, edge, supplied[edge.prop].orEmpty(), converted, build)?.let { pending -> nested[edge.prop] = pending }
        }
        val index = build.creations.size
        build.creations.add(Mutation.Create(shape, converted + nested))
        for (edge in edges.filter { edge -> !edge.byValue }) {
            appendParts(shape, edge, supplied[edge.prop].orEmpty(), index, build)
        }
        return index
    }

    /** The record that arrives by value at the `one` field [edge] of [shape], appended; null when none arrived. */
    private fun appendByValue(
        shape: String,
        edge: ClosureEdge,
        raws: List<Map<String, Any?>>,
        converted: Map<String, Value>,
        build: ClosureBuild,
    ): Value? {
        if (raws.size > 1)
            throw InputRefusal("type: '$shape.${edge.prop}' is a `one` field — one value may arrive there, ${raws.size} were given")
        val raw = raws.singleOrNull() ?: return null
        if (edge.prop in converted)
            throw InputRefusal("type: '$shape.${edge.prop}' was given both as a reference to an existing record and as a new value")
        val concrete = (raw[VelleSystem.SHAPE_KEY] as? String) ?: edge.partShape
        if (concrete !in model.shapes || !model.typeAccepts(edge.partShape, concrete))
            throw InputRefusal("type: a value of '$concrete' cannot arrive at '$shape.${edge.prop}', which takes a ${edge.partShape}")
        return Value.VPendingRef(appendValue(concrete, raw, edge.childrenFor(concrete), build, "new value for '$shape.${edge.prop}'"))
    }

    /** The parts arriving at [edge] of the record at [containerIndex], appended after it with their back-reference at it. */
    private fun appendParts(shape: String, edge: ClosureEdge, raws: List<Map<String, Any?>>, containerIndex: Int, build: ClosureBuild) {
        for (raw in raws) {
            if (edge.backRef in raw)
                throw InputRefusal("type: '${edge.partShape}.${edge.backRef}' is language-populated — " +
                    "a back-reference cannot be claimed by the committer (Reference §6)")
            val concrete = partShapeOf(shape, edge, raw)
            appendValue(concrete, raw, edge.childrenFor(concrete), build, "inline part",
                extra = mapOf(edge.backRef to Value.VPendingRef(containerIndex)), skipRequired = setOf(edge.backRef))
        }
    }

    /**
     * The shape one part arriving at [edge] of [container] is. A part of an edge over a shape
     * several shapes take with `is` (Reference §9) says which with [VelleSystem.SHAPE_KEY], as a
     * value at a union-typed field does; an edge over one shape needs no tag, and a tag it
     * carries must be that shape.
     */
    private fun partShapeOf(container: String, edge: ClosureEdge, raw: Map<String, Any?>): String {
        val kinds = model.basesOf(edge.partShape)
        val named = raw[VelleSystem.SHAPE_KEY] as? String
            ?: kinds.singleOrNull()
            ?: throw InputRefusal("type: a part of '$container.${edge.prop}' names which of " +
                kinds.sorted().joinToString(" or ") + " it is with '${VelleSystem.SHAPE_KEY}'")
        if (named !in kinds)
            throw InputRefusal("type: a '$named' cannot arrive at '$container.${edge.prop}', which takes " +
                (if (kinds.size == 1) "a ${edge.partShape}" else "a ${edge.partShape}: " + kinds.sorted().joinToString(" or ")))
        return named
    }

    /** Convert one raw nested value (a part or a by-value record) and append it with its own closure. */
    @Suppress("UNCHECKED_CAST")
    private fun appendValue(
        shape: String,
        raw: Map<String, Any?>,
        children: List<ClosureEdge>,
        build: ClosureBuild,
        what: String,
        extra: Map<String, Value> = emptyMap(),
        skipRequired: Set<String> = emptySet(),
    ): Int {
        val decl = model.shapes[shape] ?: throw InputRefusal("type: unknown shape '$shape'")
        val childKeys = children.map { child -> child.prop }.toSet()
        val split = splitNewRefs(raw.filterKeys { key -> key !in childKeys && key != VelleSystem.SHAPE_KEY && key != VelleSystem.NEW_KEY })
        duplicateRefusal(decl, split.own)?.let { reason -> throw InputRefusal(reason) }
        val nestedParts = children.associate { child -> child.prop to ((raw[child.prop] as? List<Map<String, Any?>>).orEmpty()) }
        val skipped = skipRequired + byValueFieldsGiven(children, nestedParts) + split.siblingFields
        val converted = convertFields(decl, split.own, skipRequired = skipped)
            ?: throw InputRefusal(typeFailure(decl, split.own, skipRequired = skipped) + " ($what)")
        val index = appendRecord(shape, converted + extra, nestedParts, children, build)
        (raw[VelleSystem.NEW_KEY] as? Number)?.toInt()?.let { token ->
            if (build.indexOfToken.put(token, index) != null)
                throw InputRefusal("type: a new $shape appears in two creating positions of this act — one new value, one record")
        }
        build.addSiblingReferences(index, split)
        return index
    }

    // ── sibling references between the act's new records ─────────────────────

    /**
     * Resolve every sibling reference to a pending reference and order the
     * creation list so each pending reference points backward (Hydration.kt,
     * [Ref.Pending]). Returns the ordered list and the old-to-new index map.
     */
    private fun resolveSiblings(build: ClosureBuild): OrderedCreations {
        for (reference in build.siblingReferences) {
            build.creations[reference.index] = withSiblingReference(build, reference)
        }
        val order = dependencyOrder(build.creations)
        val newIndexOf = IntArray(order.size)
        order.forEachIndexed { newIndex, oldIndex -> newIndexOf[oldIndex] = newIndex }
        val sorted = order.map { oldIndex ->
            val creation = build.creations[oldIndex]
            creation.copy(fields = creation.fields.mapValues { (_, value) -> reindexed(value, newIndexOf) })
        }
        return OrderedCreations(sorted, newIndexOf)
    }

    private fun withSiblingReference(build: ClosureBuild, reference: SiblingReference): Mutation.Create {
        val creation = build.creations[reference.index]
        val field = reference.field
        val target = build.indexOfToken[reference.token]
            ?: throw InputRefusal("type: '${creation.shape}.$field' points at a new record that is not among this act's values")
        val fieldType = model.storedFieldTypeOf(creation.shape, field) as? RelType
        val targetShape = build.creations[target].shape
        if (fieldType == null || !model.typeAccepts(fieldType.shape, targetShape))
            throw InputRefusal("type: '${creation.shape}.$field' takes a ${fieldType?.shape ?: "scalar"}, not a new $targetShape")
        val value = if (fieldType.many) pendingCollectionWith(creation, field, target, fieldType) else Value.VPendingRef(target)
        return creation.copy(fields = creation.fields + (field to value))
    }

    /** A `many` field: the handles already converted, plus the new element at [target]. */
    private fun pendingCollectionWith(creation: Mutation.Create, field: String, target: Int, fieldType: RelType): Value {
        val items = when (val current = creation.fields[field]) {
            is Value.VColl -> current.ids.map { handle -> Value.VRef(handle) }
            is Value.VPendingColl -> current.items
            else -> emptyList()
        }
        if (items.any { item -> item is Value.VPendingRef && item.index == target })
            throw InputRefusal("type: duplicate in '${creation.shape}.$field' — a `many` is a set (Reference §6)")
        return Value.VPendingColl(items + Value.VPendingRef(target), fieldType.shape)
    }

    // ── field conversion ─────────────────────────────────────────────────────

    /** [supplied] as [decl]'s stored field values; null when a required field is missing, a field is undeclared, or a value has the wrong type. */
    private fun convertFields(decl: ShapeDecl, supplied: Map<String, Any?>, skipRequired: Set<String> = emptySet()): Map<String, Value>? {
        val converted = mutableMapOf<String, Value>()
        val storedProps = decl.members.filterIsInstance<StoredProp>()
        for (prop in storedProps) {
            val raw = supplied[prop.name]
            if (raw == null) {
                // an absent `many` is the empty collection — the absence (Reference §6)
                emptyCollectionOf(prop.type)?.let { empty -> converted[prop.name] = empty }
                if (prop.name !in skipRequired && prop.requiredAtCreation()) return null
                continue
            }
            converted[prop.name] = convert(raw, prop.type) ?: return null
        }
        val known = storedProps.map { prop -> prop.name }.toSet()
        if (supplied.keys.any { name -> name !in known }) return null // timestamps/id/derived/unknown
        return converted
    }

    private fun convert(raw: Any, type: TypeRef): Value? = when (type) {
        is RelType -> if (type.many) convertReferenceList(raw, type) else convertReference(raw, type)
        is ScalarType -> if (type.many) convertScalarList(raw, type) else convertScalar(raw, type.name)
    }

    private fun convertReference(raw: Any, type: RelType): Value? =
        (raw as? Long)?.takeIf { handle -> acceptedReference(handle, type.shape) }?.let { handle -> Value.VRef(handle) }

    private fun convertReferenceList(raw: Any, type: RelType): Value? {
        val elements = raw as? List<*> ?: return null
        val handles = elements.map { element -> (element as? Long)?.takeIf { handle -> acceptedReference(handle, type.shape) } ?: return null }
        return Value.VColl(handles, type.shape)
    }

    private fun convertScalarList(raw: Any, type: ScalarType): Value? {
        val elements = raw as? List<*> ?: return null
        val values = elements.map { element -> element?.let { present -> convertScalar(present, type.name) } ?: return null }
        return Value.VVals(values, ordered = type.ordered)
    }

    /** A committed reference names an existing instance the field's declared
     *  type accepts — the shape itself, or an operand of a static union (Reference §9):
     *  a handle of the wrong shape can't inhabit the type, a boundary refusal. */
    private fun acceptedReference(handle: Long, declared: String): Boolean {
        if (declared in model.shapes) system.workingSet.idShape.putIfAbsent(handle, declared)
        val instance = system.workingSet.instance(handle) ?: return false
        return model.typeAccepts(declared, instance.shape)
    }

    private fun convertScalar(raw: Any, name: String): Value? = when (name) {
        "text" -> (raw as? String)?.let { text -> Value.VText(text) }
        "boolean" -> (raw as? Boolean)?.let { truth -> Value.VBool(truth) }
        "Date" -> (raw as? LocalDate)?.let { date -> Value.VDate(date) }
        "DateTime" -> (raw as? Instant)?.let { instant -> Value.VDateTime(instant) }
        "uuid" -> (raw as? UUID)?.let { uuid -> Value.VUuid(uuid) }
        "duration" -> (raw as? java.time.Duration)?.let { duration -> Value.VDuration.ofJava(duration) }
        "time" -> (raw as? java.time.LocalTime)?.let { timeOfDay -> Value.VTime(timeOfDay) }
        "bytes" -> (raw as? ByteArray)?.let { bytes -> Value.VBytes(bytes) }
        in TypeRegistry.NUMBER_NAMES -> runCatching { Value.num(raw) }.getOrNull()
        else -> convertExtension(raw, name)
    }

    /** An extension type (Reference §5; OQ65): the value is the type's own class and
     *  passes its `validate`; anything else is a type-level refusal at the boundary. */
    private fun convertExtension(raw: Any, name: String): Value? {
        val type = model.typeScope[name] ?: return null
        if (!type.kotlinClass.isInstance(raw)) return null
        type.validateAny(raw)?.let { reason -> throw InputRefusal("type: ${model.specNameOf(type)} — $reason") }
        return Value.VExt(type, raw)
    }
}

/** A type-level refusal of a committed input: the act is refused with [reason] before anything is written. */
internal class InputRefusal(val reason: String) : Exception(reason)

/** An act's input as the creation list of one commit, in dependency order, and the index of the act's own record in it. */
internal class PreparedAct(val mutations: List<Mutation>, val rootIndex: Int)

/** The creation list after [ActInput] ordered it, and where each record's index moved to. */
private class OrderedCreations(val creations: List<Mutation.Create>, val newIndexOf: IntArray)

/** A `one` field of the record at [index] pointing at the sibling new record whose registry token is [token]. */
private data class SiblingReference(val index: Int, val field: String, val token: Int)

/** The creation list of one act under construction, with the sibling references still to resolve. */
private class ClosureBuild {
    val creations = mutableListOf<Mutation.Create>()
    /** registry token → index of the record it names */
    val indexOfToken = mutableMapOf<Int, Int>()
    val siblingReferences = mutableListOf<SiblingReference>()

    fun addSiblingReferences(index: Int, split: SplitFields) {
        for ((field, token) in split.siblingRefs) siblingReferences.add(SiblingReference(index, field, token))
    }
}

/** A raw value's fields with its [NewRef]s taken out ([own]), and the (field, token) of each one taken out. */
private class SplitFields(val own: Map<String, Any?>, val siblingRefs: List<Pair<String, Int>>) {
    val siblingFields: Set<String> get() = siblingRefs.map { (field, _) -> field }.toSet()
}

/**
 * Separate a raw value's [NewRef]s from the rest: a `one` field holding one,
 * and the [NewRef] elements of a `many` field (the handles stay in the raw
 * list).
 */
private fun splitNewRefs(raw: Map<String, Any?>): SplitFields {
    val siblingRefs = mutableListOf<Pair<String, Int>>()
    val own = mutableMapOf<String, Any?>()
    for ((field, value) in raw) when {
        value is NewRef -> siblingRefs.add(field to value.token)
        value is List<*> && value.any { element -> element is NewRef } -> {
            value.filterIsInstance<NewRef>().forEach { newRef -> siblingRefs.add(field to newRef.token) }
            own[field] = value.filter { element -> element !is NewRef }
        }
        else -> own[field] = value
    }
    return SplitFields(own, siblingRefs)
}

/** The `one` fields among [edges] at which a record arrives by value in [parts]: the commit fills them, so they are never missing. */
private fun byValueFieldsGiven(edges: List<ClosureEdge>, parts: Map<String, List<Map<String, Any?>>>): Set<String> =
    edges.filter { edge -> edge.byValue && parts[edge.prop]?.isNotEmpty() == true }.map { edge -> edge.prop }.toSet()

/** The set refusal: a duplicate in a committed `many` is a caller bug or a
 *  multiplicity claim a set cannot express (Reference §6; §5, "Collections of
 *  values"). A `many` of values that `tolerates duplication` keeps them. Inline
 *  part *values* are a bag — two identical values mint two distinct instances —
 *  so this applies to reference collections only. */
private fun duplicateRefusal(decl: ShapeDecl, supplied: Map<String, Any?>): String? {
    for (prop in decl.members.filterIsInstance<StoredProp>()) {
        val type = prop.type
        val raw = supplied[prop.name] as? List<*> ?: continue
        if (raw.size == raw.distinct().size) continue
        if (type is RelType && type.many)
            return "type: duplicate in '${decl.name}.${prop.name}' — a `many` is a set; " +
                "if multiplicity is meaningful, it is data on an edge shape (Reference §6)"
        if (type is ScalarType && type.many && !type.ordered)
            return "type: duplicate in '${decl.name}.${prop.name}' — a `many` holds each value once; " +
                "`tolerates duplication` on the property keeps duplicates (Reference §5)"
    }
    return null
}

/** Why field conversion refused [supplied] for [decl]: a required field missing, a field the
 *  shape does not declare, or a value of the wrong type. [skipRequired] names the fields the
 *  commit fills in itself, a part's back-reference or a sibling reference, which are never
 *  the committer's to supply and so are never reported missing. */
private fun typeFailure(decl: ShapeDecl, supplied: Map<String, Any?>, skipRequired: Set<String> = emptySet()): String {
    val stored = decl.members.filterIsInstance<StoredProp>()
    val required = stored.filter { prop -> prop.requiredAtCreation() }.map { prop -> prop.name }
    val missing = required - supplied.keys - skipRequired
    val undeclared = supplied.keys - stored.map { prop -> prop.name }.toSet()
    val timestampSupplied = decl.members.filterIsInstance<TimestampProp>().firstOrNull { timestamp -> timestamp.name in supplied.keys }
    return when {
        missing.isNotEmpty() -> "type: '${decl.name}' requires $missing"
        // a timestamp is the commit's own fact, never the committer's (Reference §5, "Timestamps")
        timestampSupplied != null -> "type: '${decl.name}.${timestampSupplied.name}' is a timestamp the commit fills; a committer cannot supply it"
        undeclared.isNotEmpty() -> "type: '${decl.name}' does not declare $undeclared"
        else -> "type: unacceptable fields for '${decl.name}'"
    }
}

/** Every pending index [creation] refers to: the records of this act it must be created after. */
private fun pendingIndexesIn(creation: Mutation.Create): List<Int> = creation.fields.values.flatMap { value ->
    when (value) {
        is Value.VPendingRef -> listOf(value.index)
        is Value.VPendingColl -> value.items.filterIsInstance<Value.VPendingRef>().map { pending -> pending.index }
        else -> emptyList()
    }
}

/** Stable topological order: the earliest record whose references are all placed. */
private fun dependencyOrder(creations: List<Mutation.Create>): List<Int> {
    val order = mutableListOf<Int>()
    val placed = BooleanArray(creations.size)
    while (order.size < creations.size) {
        val next = creations.indices.firstOrNull { index ->
            !placed[index] && pendingIndexesIn(creations[index]).all { dependency -> placed[dependency] }
        } ?: throw InputRefusal("type: the new records of this act point at each other in a cycle; no record can be created first")
        placed[next] = true
        order.add(next)
    }
    return order
}

private fun reindexed(value: Value, newIndexOf: IntArray): Value = when (value) {
    is Value.VPendingRef -> Value.VPendingRef(newIndexOf[value.index])
    is Value.VPendingColl -> Value.VPendingColl(value.items.map { item -> reindexed(item, newIndexOf) }, value.shape)
    else -> value
}
