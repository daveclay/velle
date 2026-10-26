package velle.sqlite

import velle.Model
import velle.sql.SqlStore
import velle.sql.SqlStoreConformance
import velle.sql.SqlTypeBinding
import java.sql.Connection
import java.sql.DriverManager

/** The SQL layer's scenarios over an in-memory SQLite database. */
class SqliteConformanceTest : SqlStoreConformance() {
    override fun connect(): Connection = DriverManager.getConnection("jdbc:sqlite::memory:")
    override fun store(model: Model, conn: Connection, bindings: List<SqlTypeBinding<*>>): SqlStore = SqliteStore(model, conn, bindings)
}
