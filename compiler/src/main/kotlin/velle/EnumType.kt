package velle

import velle.ext.Accepts
import velle.ext.LiteralRejected
import velle.ext.Literals
import velle.ext.Operator
import velle.ext.Parameter
import velle.ext.Returns
import velle.ext.Samples
import velle.ext.Signature
import velle.ext.VelleFunction
import velle.ext.VelleType
import velle.ext.builtin.TextType

/**
 * The type a spec declares with `Role is one of "pharmacist", "technician", "admin"`
 * (Reference §5, "A field that takes one of a few words"): one value per word, held as
 * the word itself, equal when the words are, with no order, no arithmetic, and no sum.
 * It is a registered type like any other, built by the model from the declaration and
 * put in the spec's own registry, so every consumer that dispatches on the registry
 * reads it there: the validator reads a quoted word through [literals] and refuses one
 * not on the list (F2), the boundary refuses a committed word through [validate] (V21),
 * and a store holds the word as text.
 */
class EnumType(override val name: String, val words: List<String>) : VelleType<String> {
    override val namespace = NAMESPACE
    override val kotlinClass = String::class
    override val literals = Literals<String>(fromText = { word -> if (word in words) word else throw LiteralRejected(notOneOf(word)) })
    override fun validate(value: String): String? = if (value in words) null else notOneOf(value)
    override fun canonical(value: String) = value
    override val operators: List<Operator<*, *, *>> get() = emptyList()
    override val samples = Samples(words.first(), kotlinSource = { word -> "velle.generated.$name.${entryName(word)}" })
    override fun toString() = name

    /** The reason a word is refused: `"boss" is not one of "pharmacist", "technician", "admin"`. */
    fun notOneOf(word: String) = "\"$word\" is not one of ${quotedWords()}"

    fun quotedWords() = words.joinToString(", ") { word -> "\"$word\"" }

    /**
     * The entry of the generated Kotlin `enum class` that carries [word] (Act API): the
     * word itself when it is a Kotlin identifier, otherwise the word with every character
     * that is not a letter or a digit replaced by an underscore, led by one when it starts
     * with a digit. Two words that collapse to one entry are refused at the declaration (F1).
     */
    fun entryName(word: String): String {
        val cleaned = word.map { character -> if (character.isLetterOrDigit()) character else '_' }.joinToString("")
        return if (cleaned.first().isDigit()) "_$cleaned" else cleaned
    }

    companion object {
        /** The namespace of every type a spec declares: `spec.Role` is its qualified name. */
        const val NAMESPACE = "spec"
    }
}

/**
 * The reading function of a type declared by its words (Reference §5): `role(value)` is the
 * word the text spells, absent when it spells none, as `number(value)` reads a number. Its
 * name is the type's with a lowercase first letter; a type whose reading function would
 * take a builtin function's name is refused (E1).
 */
class EnumReader(private val type: EnumType) : VelleFunction {
    override val name = readerName(type.name)
    override val namespace = EnumType.NAMESPACE
    override val signatures = listOf(Signature(
        parameters = listOf(Parameter("text", Accepts.Is(TextType::class), takes = "text")),
        result = Returns.Named(type.qualifiedName),
        optionalResult = true,
        apply = { _, args -> (args[0].value as String).takeIf { word -> word in type.words } },
    ))

    companion object {
        fun readerName(typeName: String) = typeName.replaceFirstChar { it.lowercase() }
    }
}
