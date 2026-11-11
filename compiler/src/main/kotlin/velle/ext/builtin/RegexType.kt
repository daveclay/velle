package velle.ext.builtin

import velle.ext.LiteralRejected
import velle.ext.Literals
import velle.ext.Samples
import velle.ext.VelleType
import java.util.regex.PatternSyntaxException

/**
 * A regular expression (Reference §5): the text of a pattern, in the Java
 * dialect, that compiles. One that does not cannot be constructed, so a `Regex`
 * value is never malformed. Equal by its text.
 */
class RegexValue(val source: String) {
    /** The compiled pattern; the constructor compiles it once so a bad pattern fails here. */
    val pattern: Regex = try { Regex(source) } catch (error: PatternSyntaxException) {
        throw LiteralRejected("'$source' is not a regular expression: ${error.description}")
    }

    override fun equals(other: Any?): Boolean = other is RegexValue && other.source == source
    override fun hashCode(): Int = source.hashCode()
    override fun toString(): String = source
}

/**
 * The `Regex` type, shipped with Velle and registered as any extension type is
 * (`META-INF/services/velle.ext.VelleType`), so a spec names it with no `uses`
 * line. `regex(text)` reads a text as one, absent when the text does not
 * compile; a quoted text beside a `Regex` reads through [literals]. The text of
 * a literal with pieces in a `Regex` position is a pattern piece by piece: a
 * text piece is characters, a `Regex` piece is pattern (Reference §5, §10).
 */
class RegexType : VelleType<RegexValue> {
    override val name = "Regex"
    override val namespace = "velle"

    companion object {
        /** The qualified name a `Regex` value's type carries, `velle.Regex`. */
        const val QUALIFIED_NAME = "velle.Regex"

        /** Whether [type] is the `Regex` type. */
        fun isRegex(type: VelleType<*>): Boolean = type.qualifiedName == QUALIFIED_NAME
    }
    override val kotlinClass = RegexValue::class
    override val literals = Literals<RegexValue>(fromText = { text -> RegexValue(text) })
    override fun canonical(value: RegexValue): String = value.source
    override val operators = emptyList<velle.ext.Operator<*, *, *>>()
    override val samples = Samples(
        sample = RegexValue("[a-z]+"),
        kotlinSource = { value -> "velle.ext.builtin.RegexValue(\"${kotlinEscaped(value.source)}\")" },
    )

    private fun kotlinEscaped(source: String): String =
        source.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")

    override fun toString(): String = name
}
