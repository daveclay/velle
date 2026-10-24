package velle.finance.sqlite

import velle.finance.Money
import velle.sqlite.SqliteTypeBinding
import java.sql.ResultSet

/**
 * `Money` in a SQLite column: an INTEGER of cents. Integers order as amounts
 * order, so the column answers every comparison faithfully and the store pushes
 * `balance < 0` down as `balance < ?` with `0` bound.
 */
class SqliteMoneyBinding : SqliteTypeBinding<Money> {
    override val type = "velle.finance.Money"
    override val columnType = "INTEGER"
    override fun bind(value: Money): Any = value.cents
    override fun read(rs: ResultSet, column: String): Money = Money.ofCents(rs.getLong(column))
    override val answers = setOf("==", "!=", "<", "<=", ">", ">=")
}
