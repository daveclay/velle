package velle.postgres

import velle.Model
import velle.VelleSystem
import velle.sql.SqlStore
import velle.sql.SqlTypeBinding
import java.time.Duration
import javax.sql.DataSource

/**
 * The generic SQL store ([SqlStore]) over PostgreSQL: the layer's mapping and
 * protocol with [PostgresDialect]'s columns, transactions, and advisory locks.
 * Each unit of work takes a connection from [dataSource], a pool in a
 * deployment, and returns it when the unit ends.
 */
class PostgresStore(
    model: Model,
    dataSource: DataSource,
    bindings: List<SqlTypeBinding<*>> = SqlTypeBinding.load(),
    lockTimeout: Duration = Duration.ofSeconds(10),
) : SqlStore(model, dataSource, PostgresDialect, bindings, lockTimeout) {

    companion object {
        /** Create the schema for [system]'s model in the database behind [dataSource] and connect the system through this store, both halves of the protocol. */
        fun attach(system: VelleSystem, dataSource: DataSource, bindings: List<SqlTypeBinding<*>> = SqlTypeBinding.load()): PostgresStore =
            PostgresStore(system.model, dataSource, bindings).also {
                it.createSchema()
                system.connect(it, it)
            }
    }
}
