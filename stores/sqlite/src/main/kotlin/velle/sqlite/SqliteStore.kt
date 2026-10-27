package velle.sqlite

import velle.Model
import velle.VelleSystem
import velle.sql.OneConnection
import velle.sql.SqlStore
import velle.sql.SqlTypeBinding
import java.sql.Connection
import javax.sql.DataSource

/**
 * The generic SQL store ([SqlStore]) over SQLite: the layer's mapping and
 * protocol with [SqliteDialect]'s columns and transactions. Every example's
 * output module runs its scenarios over this one class, and it is the existence
 * proof that the generic protocol is enough for a store to implement once for
 * all specs.
 *
 * Over a `DataSource`, each unit of work takes a connection and returns it; a
 * file database in WAL mode then serves readers a snapshot while a writer
 * commits. Over one connection ([OneConnection]), every unit runs on it in turn:
 * the form for an in-memory database, which lives in its connection, and for a
 * test or a script that reads the tables itself beside the store.
 */
class SqliteStore(
    model: Model,
    dataSource: DataSource,
    bindings: List<SqlTypeBinding<*>> = SqlTypeBinding.load(),
) : SqlStore(model, dataSource, SqliteDialect, bindings) {

    /** The store over one connection the caller holds: an in-memory database's natural unit. */
    constructor(model: Model, conn: Connection, bindings: List<SqlTypeBinding<*>> = SqlTypeBinding.load()) :
        this(model, OneConnection(conn), bindings)

    companion object {
        /**
         * The one-line setup: create the schema for [system]'s model in the
         * database behind [dataSource] and connect the system through this store,
         * both halves of the protocol.
         */
        fun attach(system: VelleSystem, dataSource: DataSource, bindings: List<SqlTypeBinding<*>> = SqlTypeBinding.load()): SqliteStore =
            SqliteStore(system.model, dataSource, bindings).also {
                it.createSchema()
                system.connect(it, it)
            }

        /** The one-line setup an example's tests use, over one connection the test holds. */
        fun attach(system: VelleSystem, conn: Connection, bindings: List<SqlTypeBinding<*>> = SqlTypeBinding.load()): SqliteStore =
            attach(system, OneConnection(conn), bindings)
    }
}
