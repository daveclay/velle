package velle

/** What the declared type of a stored field says about holding no value. One decision per question, shared by every writer. */

internal val TypeRef.isMany: Boolean
    get() = when (this) {
        is RelType -> many
        is ScalarType -> many
    }

internal val TypeRef.isOptional: Boolean
    get() = when (this) {
        is RelType -> optional
        is ScalarType -> optional
    }

/** Whether [this] field may hold no value: declared with `?`, or a `many`, whose absence is the empty collection. */
internal fun StoredProp.takesAbsence(): Boolean = type.isOptional || type.isMany

/**
 * Whether a creation must supply [this] field: it has no `initially`, it is not a `many`
 * (an absent `many` is the empty collection), and it is either not optional or declared
 * `? initially required` (OQ37-R10), read-optional but supplied at creation.
 */
internal fun StoredProp.requiredAtCreation(): Boolean =
    initially == null && !type.isMany && (initiallyRequired || !type.isOptional)

/** The empty collection a `many` of [type] holds when nothing is in it, which is its absence (Reference §6); null for a type that is not a `many`. */
internal fun emptyCollectionOf(type: TypeRef): Value? = when {
    type is RelType && type.many -> Value.VColl(emptyList(), type.shape)
    type is ScalarType && type.many -> Value.VVals(emptyList(), ordered = type.ordered)
    else -> null
}

/** The declared type of [shape]'s stored field [field]; null when the shape stores no such field. */
internal fun Model.storedFieldTypeOf(shape: String, field: String): TypeRef? =
    shapes[shape]?.members?.filterIsInstance<StoredProp>()?.find { prop -> prop.name == field }?.type

/** [shape]'s `on update` timestamps: they advance at every commit that writes one of its stored fields. */
internal fun Model.updateTimestampsOf(shape: String): List<TimestampProp> =
    shapes[shape]?.members?.filterIsInstance<TimestampProp>()?.filter { timestamp -> timestamp.on == "update" }.orEmpty()
