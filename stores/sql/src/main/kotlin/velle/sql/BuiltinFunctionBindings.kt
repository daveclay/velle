package velle.sql

/**
 * The renderings Velle ships for its own builtin functions (Reference §5),
 * registered through `META-INF/services/velle.sql.SqlFunctionBinding` like any
 * other binding. Only a rendering that agrees with the function's body on every
 * SQL database is shipped SQL-wide: `abs` is; `lowercase` is not, since a
 * database's `LOWER` may fold ASCII only where the body folds Unicode.
 */
class AbsBinding : SqlFunctionBinding {
    override val function: String = "velle.abs"
    override fun render(operands: SqlOperands): SqlExpr = SqlExpr.Call("ABS", operands["value"])
}
