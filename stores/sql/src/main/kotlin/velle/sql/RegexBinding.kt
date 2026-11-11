package velle.sql

import velle.ext.builtin.RegexValue

/** A `Regex` (Reference §5) held as its text: equality is faithful, and a pattern has no order. */
class RegexBinding : SqlTypeBinding<RegexValue> {
    override val type = "velle.Regex"
    override val storage = StorageClass.TEXT
    override fun encode(value: RegexValue): Any = value.source
    override fun decode(stored: Any): RegexValue = RegexValue(stored as String)
    override val answers: Set<String> = setOf("==", "!=")
}
