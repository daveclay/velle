package velle.sql

import java.util.ServiceLoader

/**
 * How a SQL store renders one registered function (Reference §5; the wiki guide
 * `Extension-functions`, "Holding a function in a store") so a read that filters
 * on the function narrows its rows in the database instead of handing every row
 * to the evaluator. The function's body still runs on every row the store hands
 * back and has the last word; a rendering only narrows.
 *
 * A binding is a library apart from the function, in the function's own library
 * or any other, and the owner of a system chooses which bindings it runs with by
 * what they put on the classpath. One binding renders for every SQL database
 * ([dialects] null) or for the databases it names; the store picks the binding
 * claiming its dialect over the one claiming any, and refuses two bindings that
 * claim one function on one dialect. A function with no binding runs in memory.
 *
 * Registration: name the implementing class in
 * `META-INF/services/velle.sql.SqlFunctionBinding` on the classpath, or hand the
 * store an explicit list.
 */
interface SqlFunctionBinding {

    /** The function's qualified name, `namespace.name`: `velle.finance.roundToCents`. */
    val function: String

    /** The dialects this rendering is for, by [SqlDialect.name]: `SQLite`, `PostgreSQL`; null for any SQL database. */
    val dialects: Set<String>? get() = null

    /**
     * The call as SQL, from its operands under the matched signature's parameter
     * names. Every name read must be a parameter of that signature; the store
     * checks this for every signature when it is built.
     */
    fun render(operands: SqlOperands): SqlExpr

    /**
     * Statements the store runs once, with the schema, before any call is
     * rendered: a `CREATE OR REPLACE FUNCTION` the rendering calls. Each must be
     * safe to run again. When the database refuses one, the store records the
     * refusal, leaves the function to the evaluator, and reports it in
     * [SqlStore.renderingsNotInstalled]; a system that insists on the rendering
     * names the function in the store's `insistRendered` and the refusal stops
     * the store from starting.
     */
    val ddl: List<String> get() = emptyList()

    companion object {
        /** Every binding on the classpath. */
        fun load(loader: ClassLoader = SqlFunctionBinding::class.java.classLoader): List<SqlFunctionBinding> =
            ServiceLoader.load(SqlFunctionBinding::class.java, loader).toList()
    }
}
