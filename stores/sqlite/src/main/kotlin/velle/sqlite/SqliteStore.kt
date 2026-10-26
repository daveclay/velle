package velle.sqlite

import velle.Model
import velle.VelleSystem
import velle.sql.SqlStore
import velle.sql.SqlTypeBinding
import java.sql.Connection

/**
 * The generic SQL store ([SqlStore]) over SQLite: the layer's mapping and
 * protocol with [SqliteDialect]'s columns. Every example's output module runs
 * its scenarios over this one class, and it is the existence proof that the
 * generic protocol is enough for a store to implement once for all specs.
 */
class SqliteStore(
    model: Model,
    conn: Connection,
    bindings: List<SqlTypeBinding<*>> = SqlTypeBinding.load(),
) : SqlStore(model, conn, SqliteDialect(conn), bindings) {

    companion object {
        /**
         * The one-line setup an example's tests use: create the schema for
         * [system]'s model in [conn] and connect the system through this store,
         * both halves of the protocol.
         */
        fun attach(system: VelleSystem, conn: Connection, bindings: List<SqlTypeBinding<*>> = SqlTypeBinding.load()): SqliteStore =
            SqliteStore(system.model, conn, bindings).also {
                it.createSchema()
                system.connect(it, it)
            }
    }
}
