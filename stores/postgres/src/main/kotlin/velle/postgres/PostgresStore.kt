package velle.postgres

import velle.Model
import velle.VelleSystem
import velle.sql.SqlStore
import velle.sql.SqlTypeBinding
import java.sql.Connection

/** The generic SQL store ([SqlStore]) over PostgreSQL: the layer's mapping and protocol with [PostgresDialect]'s columns. */
class PostgresStore(
    model: Model,
    conn: Connection,
    bindings: List<SqlTypeBinding<*>> = SqlTypeBinding.load(),
) : SqlStore(model, conn, PostgresDialect, bindings) {

    companion object {
        /** Create the schema for [system]'s model in [conn] and connect the system through this store, both halves of the protocol. */
        fun attach(system: VelleSystem, conn: Connection, bindings: List<SqlTypeBinding<*>> = SqlTypeBinding.load()): PostgresStore =
            PostgresStore(system.model, conn, bindings).also {
                it.createSchema()
                system.connect(it, it)
            }
    }
}
