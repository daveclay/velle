package velle.sql

import java.io.PrintWriter
import java.lang.reflect.Proxy
import java.sql.Connection
import java.util.logging.Logger
import javax.sql.DataSource

/**
 * A `DataSource` over one connection the caller owns: every unit of work the
 * store opens runs on it, and closing a connection handed out here returns it
 * rather than closing it. For a database that lives and dies with its
 * connection, SQLite's in-memory database, this is the honest unit; for a file
 * or a server database a pooled `DataSource` is, and this class is for a test
 * or a script that holds one connection and reads the tables itself beside the
 * store. Units of work run one at a time, as the runtime issues them.
 */
class OneConnection(private val conn: Connection) : DataSource {

    /** The connection, with `close` a no-op; everything else is the connection's own. */
    override fun getConnection(): Connection = Proxy.newProxyInstance(
        Connection::class.java.classLoader, arrayOf(Connection::class.java),
    ) { _, method, args ->
        when (method.name) {
            "close" -> null
            "isClosed" -> conn.isClosed
            else -> try {
                if (args == null) method.invoke(conn) else method.invoke(conn, *args)
            } catch (e: java.lang.reflect.InvocationTargetException) {
                throw e.targetException
            }
        }
    } as Connection

    override fun getConnection(username: String?, password: String?): Connection = connection

    override fun getLogWriter(): PrintWriter? = null
    override fun setLogWriter(out: PrintWriter?) {}
    override fun setLoginTimeout(seconds: Int) {}
    override fun getLoginTimeout(): Int = 0
    override fun getParentLogger(): Logger = Logger.getLogger("velle.sql")
    override fun <T> unwrap(iface: Class<T>): T =
        if (iface.isInstance(this)) iface.cast(this) else throw java.sql.SQLException("not a ${iface.name}")
    override fun isWrapperFor(iface: Class<*>): Boolean = iface.isInstance(this)
}
