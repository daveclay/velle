package velle.finance.sql

import velle.finance.Money
import velle.sql.ALL_COMPARISONS
import velle.sql.SqlTypeBinding
import velle.sql.StorageClass

/**
 * `Money` in a SQL column: an integer of cents. Integers order as amounts
 * order, so the column answers every comparison faithfully and the store pushes
 * `balance < 0` down as `balance < ?` with `0` bound. The binding names no
 * database: SQLite holds the integer as INTEGER, PostgreSQL as BIGINT.
 */
class SqlMoneyBinding : SqlTypeBinding<Money> {
    override val type = "velle.finance.Money"
    override val storage = StorageClass.INTEGER
    override fun encode(value: Money): Any = value.cents
    override fun decode(stored: Any): Money = Money.ofCents(stored as Long)
    override val answers = ALL_COMPARISONS
}
