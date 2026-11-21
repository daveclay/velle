package velle

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Reads a raw value a store returned (the [Row] contract) as a runtime [Value] of the
 * field's declared type. A reference arrives as a [Ref.Persisted] and is read as this
 * session's handle for it, minted by [handleFor]. A value of the wrong Kotlin class is the
 * store's bug, and fails naming the field it was read for.
 */
internal class StoredValueReader(
    private val model: Model,
    private val handleFor: (Ref.Persisted) -> Long,
) {
    /** [raw] read as [type]; [fieldName] names the field in a failure, as `Shape.field`. */
    fun read(raw: Any, type: VType, fieldName: String): Value = when (type) {
        is VType.Optional -> read(raw, type.inner, fieldName)
        is VType.Inst -> Value.VRef(handleFor(expect<Ref.Persisted>(raw, fieldName, "reference is not a Ref.Persisted")))
        is VType.Num -> Value.num(raw)
        VType.Text -> Value.VText(expect<String>(raw, fieldName, "not text"))
        VType.Bool -> Value.VBool(readBoolean(raw, fieldName))
        VType.DateT -> Value.VDate(expect<LocalDate>(raw, fieldName, "not a Date"))
        VType.DateTimeT -> Value.VDateTime(expect<Instant>(raw, fieldName, "not a DateTime"))
        VType.UuidT -> Value.VUuid(expect<UUID>(raw, fieldName, "not a uuid"))
        VType.DurationT -> Value.VDuration.ofJava(expect<java.time.Duration>(raw, fieldName, "not a duration"))
        VType.TimeT -> Value.VTime(expect<java.time.LocalTime>(raw, fieldName, "not a time"))
        VType.BytesT -> Value.VBytes(expect<ByteArray>(raw, fieldName, "not bytes"))
        is VType.Ext -> readExtension(raw, type, fieldName)
        is VType.Coll -> Value.VColl(readReferenceList(raw, fieldName), type.shape)
        is VType.CollS -> Value.VVals(readScalarList(raw, type, fieldName), ordered = type.ordered)
        else -> throw VelleRuntimeError("hydrating $fieldName: unsupported type for $raw")
    }

    private inline fun <reified T> expect(raw: Any?, fieldName: String, problem: String): T =
        raw as? T ?: throw VelleRuntimeError("hydrating $fieldName: $problem ($raw)")

    private fun readBoolean(raw: Any, fieldName: String): Boolean = when (raw) {
        is Boolean -> raw
        is Number -> raw.toInt() != 0
        else -> throw VelleRuntimeError("hydrating $fieldName: not a boolean ($raw)")
    }

    /** The Row contract: a field of an extension type holds the type's Kotlin object. */
    private fun readExtension(raw: Any, type: VType.Ext, fieldName: String): Value {
        val extensionType = model.registry[type.qualifiedName]
            ?: throw VelleRuntimeError("hydrating $fieldName: no registered type '${type.qualifiedName}'")
        if (!extensionType.kotlinClass.isInstance(raw))
            throw VelleRuntimeError("hydrating $fieldName: not a ${extensionType.kotlinClass.qualifiedName} ($raw)")
        return Value.VExt(extensionType, raw)
    }

    private fun readReferenceList(raw: Any, fieldName: String): List<Long> =
        expect<List<*>>(raw, fieldName, "not a collection").map { element ->
            handleFor(expect<Ref.Persisted>(element, fieldName, "collection element is not a Ref.Persisted"))
        }

    private fun readScalarList(raw: Any, type: VType.CollS, fieldName: String): List<Value> {
        val elementType = model.scalarOf(type.name)
        return expect<List<*>>(raw, fieldName, "not a collection").map { element ->
            read(element ?: throw VelleRuntimeError("hydrating $fieldName: null element"), elementType, fieldName)
        }
    }
}
