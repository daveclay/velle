package velle.api.mcp

import velle.Model
import velle.TypeRegistry

/**
 * The JSON Schema of an act's input, built from the act's description ([velle.api.Describe.act]):
 * one property per field the committer supplies, one array per part, each required unless the
 * spec declares it optional. It tells the assistant what to send; the API layer still reads and
 * refuses the input itself, so the schema names no rule the wire does not already hold, and it
 * leaves other properties open for the wire's own keys, `@shape` and `@new`.
 */
internal class InputSchema(private val model: Model) {

    /** The schema of the act described by [act], with [extra] properties beside its fields. */
    fun of(act: Map<String, Any?>, extra: Map<String, Map<String, Any?>> = emptyMap()): Map<String, Any?> {
        val record = record(fieldsOf(act), partsOf(act))
        @Suppress("UNCHECKED_CAST")
        val properties = record["properties"] as Map<String, Any?>
        return record + ("properties" to properties + extra)
    }

    private fun record(fields: List<Map<String, Any?>>, parts: List<Map<String, Any?>>): Map<String, Any?> {
        val properties = linkedMapOf<String, Any?>()
        fields.forEach { field -> properties[field["name"] as String] = fieldSchema(field) }
        parts.forEach { part -> properties[part["field"] as String] = partsSchema(part) }
        val required = fields.filter { field -> field["optional"] != true }.map { field -> field["name"] as String }
        return linkedMapOf("type" to "object", "properties" to properties, "required" to required)
    }

    private fun fieldSchema(field: Map<String, Any?>): Map<String, Any?> {
        val typeName = field["type"] as String
        val single = when (field["kind"]) {
            "reference", "references" -> referenceSchema(typeName, field["mayBeNew"] == true)
            else -> valueSchema(typeName)
        }
        val many = field["kind"] == "values" || field["kind"] == "references"
        return if (many) linkedMapOf("type" to "array", "items" to single) else single
    }

    private fun referenceSchema(shape: String, mayBeNew: Boolean): Map<String, Any?> {
        val sibling = if (mayBeNew) ", or {\"new\": n} for a new value in this input tagged \"@new\": n" else ""
        return linkedMapOf(
            "type" to listOf("integer", "string", "object"),
            "description" to "A $shape: its key, or {\"shape\": ..., \"key\": ...}$sibling.",
        )
    }

    private fun valueSchema(typeName: String): Map<String, Any?> {
        val enumeration = model.enums[typeName]
        return when {
            enumeration != null -> linkedMapOf("type" to "string", "enum" to enumeration.words)
            typeName == "text" -> linkedMapOf("type" to "string")
            typeName == "boolean" -> linkedMapOf("type" to "boolean")
            typeName == "integer" -> linkedMapOf("type" to "integer")
            typeName in TypeRegistry.NUMBER_NAMES -> linkedMapOf("type" to listOf("number", "string"), "description" to "A $typeName.")
            else -> textualSchema(typeName)
        }
    }

    private fun textualSchema(typeName: String): Map<String, Any?> = when (typeName) {
        "Date" -> linkedMapOf("type" to "string", "format" to "date")
        "DateTime" -> linkedMapOf("type" to "string", "format" to "date-time")
        "uuid" -> linkedMapOf("type" to "string", "format" to "uuid")
        "time" -> linkedMapOf("type" to "string", "description" to "A time of day, such as 09:30.")
        "duration" -> linkedMapOf("type" to "string", "description" to "A duration: ISO-8601 such as PT36H, or the spec's spelling, such as 3 days.")
        "bytes" -> linkedMapOf("type" to "string", "contentEncoding" to "base64")
        else -> linkedMapOf("type" to listOf("string", "number"), "description" to "A $typeName, written as its text.")
    }

    private fun partsSchema(part: Map<String, Any?>): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        val kinds = part["kinds"] as List<Map<String, Any?>>?
        val item = if (kinds == null) record(fieldsOf(part), partsOf(part)) else kindsSchema(kinds)
        return linkedMapOf("type" to "array", "description" to "New ${part["shape"]} values, created with the act.", "items" to item)
    }

    /** A part that may be of several shapes: each element names its shape under `@shape`. */
    private fun kindsSchema(kinds: List<Map<String, Any?>>): Map<String, Any?> =
        linkedMapOf("anyOf" to kinds.map { kind -> kindSchema(kind) })

    private fun kindSchema(kind: Map<String, Any?>): Map<String, Any?> {
        val record = record(fieldsOf(kind), partsOf(kind))
        @Suppress("UNCHECKED_CAST")
        val properties = record["properties"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val required = record["required"] as List<String>
        val shapeProperty = linkedMapOf<String, Any?>("const" to kind["shape"])
        return record + mapOf("properties" to linkedMapOf<String, Any?>("@shape" to shapeProperty) + properties, "required" to listOf("@shape") + required)
    }

    @Suppress("UNCHECKED_CAST")
    private fun fieldsOf(described: Map<String, Any?>): List<Map<String, Any?>> = described["fields"] as List<Map<String, Any?>>? ?: emptyList()

    @Suppress("UNCHECKED_CAST")
    private fun partsOf(described: Map<String, Any?>): List<Map<String, Any?>> = described["parts"] as List<Map<String, Any?>>? ?: emptyList()
}
