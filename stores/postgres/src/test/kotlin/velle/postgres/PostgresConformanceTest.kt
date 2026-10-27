package velle.postgres

import velle.Model
import velle.sql.SqlStore
import velle.sql.SqlStoreConformance
import velle.sql.SqlTypeBinding
import java.time.Duration
import javax.sql.DataSource
import kotlin.test.BeforeTest

/** The SQL layer's scenarios over a PostgreSQL in a container, each test in a schema of its own. */
class PostgresConformanceTest : SqlStoreConformance() {
    @BeforeTest
    fun docker() = PostgresTesting.assumeAvailable()

    override fun dataSource(): DataSource = PostgresTesting.dataSource()
    override fun store(model: Model, dataSource: DataSource, bindings: List<SqlTypeBinding<*>>, lockTimeout: Duration): SqlStore =
        PostgresStore(model, dataSource, bindings, lockTimeout)
}
