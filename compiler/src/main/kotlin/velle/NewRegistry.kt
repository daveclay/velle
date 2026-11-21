package velle

/**
 * In a raw commit, a `one` field's reference to a new record arriving
 * elsewhere in the same act (Reference §6; OQ63 §6.2.2): [token] is the
 * number that record's raw value carries under [VelleSystem.NEW_KEY]. The
 * boundary resolves it to the record's id before anything is written.
 */
data class NewRef(val token: Int)

/**
 * The boundary's registry of the new values one act carries (OQ63 §6.2.3.3):
 * every value in a creating position (a part, or a `one` field's `New`) gets
 * a token by object identity, and a field pointing at a sibling names that
 * token. A value in two creating positions, or a reference to a value in
 * none, is a type-level refusal of the whole act ([problem]).
 */
class NewRegistry {
    private val tokens = java.util.IdentityHashMap<Any, Int>()
    private val duplicates = mutableListOf<Any>()
    private val unknown = mutableListOf<Any>()

    /** Register a value in a creating position; returns its token. */
    fun add(value: Any): Int {
        tokens[value]?.let { existingToken -> duplicates.add(value); return existingToken }
        val newToken = tokens.size + 1
        tokens[value] = newToken
        return newToken
    }

    /** The token of a value some field points at; records it as unknown when it is in no creating position. */
    fun tokenOf(value: Any): Int = tokens[value] ?: run { unknown.add(value); -1 }

    fun tokenOrNull(value: Any): Int? = tokens[value]

    /** Null when every reference resolves and no value is created twice. */
    fun problem(): String? = when {
        duplicates.isNotEmpty() -> "type: ${duplicates.first().javaClass.simpleName} appears in two creating positions of this act — one new value, one record"
        unknown.isNotEmpty() -> "type: a field points at a ${unknown.first().javaClass.simpleName} that is not among this act's new values — " +
            "put it in a part list or at a `one` field named in `with`, or pass a handle to an existing record"
        else -> null
    }
}
