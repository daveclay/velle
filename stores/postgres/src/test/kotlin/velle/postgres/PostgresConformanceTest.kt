package velle.postgres

import velle.Model
import velle.sql.SqlStore
import velle.sql.SqlStoreConformance
import velle.sql.SqlTypeBinding
import java.sql.Connection
import kotlin.test.BeforeTest

/** The SQL layer's scenarios over a PostgreSQL in a container. */
class PostgresConformanceTest : SqlStoreConformance() {
    @BeforeTest
    fun docker() = PostgresTesting.assumeAvailable()

    override fun connect(): Connection = PostgresTesting.connection()
    override fun store(model: Model, conn: Connection, bindings: List<SqlTypeBinding<*>>): SqlStore = PostgresStore(model, conn, bindings)
}
