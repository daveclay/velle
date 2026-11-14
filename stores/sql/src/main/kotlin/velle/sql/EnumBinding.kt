package velle.sql

import velle.EnumType

/** A type a spec declares by its words (Reference §5), held as the word itself: equality is faithful, and the words have no order. */
class EnumBinding(declared: EnumType) : SqlTypeBinding<String> {
    override val type = declared.qualifiedName
    override val storage = StorageClass.TEXT
    override fun encode(value: String): Any = value
    override fun decode(stored: Any): String = stored as String
    override val answers: Set<String> = setOf("==", "!=")
}
