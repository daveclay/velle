package velle.api.scenario

import tools.jackson.core.JacksonException
import tools.jackson.core.JsonParser
import tools.jackson.core.JsonToken
import tools.jackson.core.ObjectReadContext
import tools.jackson.core.json.JsonFactory

/**
 * JSON text read into plain values, the forms the API layer's wire takes ([velle.api.Wire]): an
 * object is a `Map` in the order written, an array a `List`, a whole number a `Long`, any other
 * number a `BigDecimal`, and text, `true`, `false`, and `null` as themselves.
 */
object JsonText {

    private val factory = JsonFactory()

    /** The value [text] holds; [JsonTextProblem] when it is not JSON, with the line it stops at. */
    fun parse(text: String): Any? = try {
        factory.createParser(ObjectReadContext.empty(), text).use { parser ->
            val firstToken = parser.nextToken() ?: throw JsonTextProblem("it is empty")
            val value = valueAt(parser, firstToken)
            if (parser.nextToken() != null) throw JsonTextProblem("something follows the end of its value, on line ${parser.currentLocation().lineNr}")
            value
        }
    } catch (unreadable: JacksonException) {
        val where = unreadable.location?.let { location -> "line ${location.lineNr}, column ${location.columnNr}" } ?: "line 1"
        val what = unreadable.originalMessage.substringBefore(" (start marker at")
        throw JsonTextProblem("it is not JSON: $where, $what")
    }

    private fun valueAt(parser: JsonParser, token: JsonToken): Any? = when (token) {
        JsonToken.START_OBJECT -> objectAt(parser)
        JsonToken.START_ARRAY -> arrayAt(parser)
        JsonToken.VALUE_STRING -> parser.string
        JsonToken.VALUE_NUMBER_INT -> parser.longValue
        JsonToken.VALUE_NUMBER_FLOAT -> parser.decimalValue
        JsonToken.VALUE_TRUE -> true
        JsonToken.VALUE_FALSE -> false
        JsonToken.VALUE_NULL -> null
        else -> throw JsonTextProblem("it is not JSON: line ${parser.currentLocation().lineNr}, unexpected $token")
    }

    private fun objectAt(parser: JsonParser): Map<String, Any?> {
        val members = linkedMapOf<String, Any?>()
        while (true) {
            val token = parser.nextToken()
            if (token == JsonToken.END_OBJECT) return members
            val memberName = parser.currentName()
            members[memberName] = valueAt(parser, parser.nextToken())
        }
    }

    private fun arrayAt(parser: JsonParser): List<Any?> {
        val items = mutableListOf<Any?>()
        while (true) {
            val token = parser.nextToken()
            if (token == JsonToken.END_ARRAY) return items
            items.add(valueAt(parser, token))
        }
    }
}

/** JSON text that cannot be read, with what is wrong with it. */
class JsonTextProblem(message: String) : IllegalArgumentException(message)
