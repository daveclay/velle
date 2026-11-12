package velle.sql

import velle.VType
import velle.ext.Signature

/**
 * What a function binding renders a call to (Reference §5; the wiki guide
 * `Extension-functions`, "Holding a function in a store"): a small tree the SQL
 * layer alone writes out as SQL. A binding composes the operands the store hands
 * it and names what to do with them; the layer quotes the columns, writes `?`
 * for every constant and binds it in order, and asks the dialect for a cast. A
 * binding never writes SQL text itself except through [Raw], the escape for a
 * form the tree does not cover.
 */
sealed interface SqlExpr {

    /** A stored column of the subject row, as the store supplies it in [SqlOperands]. */
    class Column internal constructor(internal val reference: String, internal val type: VType) : SqlExpr

    /** A constant the query supplied, as the store supplies it in [SqlOperands]; bound as a parameter. */
    class Param internal constructor(internal val value: Any, internal val type: VType) : SqlExpr

    /** A constant the binding supplies, bound as a parameter: an `Int`, `Long`, `BigDecimal`, `Double`, `String`, or `Boolean`. */
    data class Literal(val value: Any) : SqlExpr

    /** `name(args, ...)`: a function the database has, `ROUND`, or one the binding's DDL created. */
    data class Call(val name: String, val args: List<SqlExpr>) : SqlExpr {
        constructor(name: String, vararg args: SqlExpr) : this(name, args.toList())
    }

    /** `(left op right)`. */
    data class Infix(val left: SqlExpr, val op: String, val right: SqlExpr) : SqlExpr

    /** `CAST(expr AS ...)`, with the column type the dialect holds [storage] in. */
    data class Cast(val expr: SqlExpr, val storage: StorageClass) : SqlExpr

    /** SQL written as given, with `{0}`, `{1}`, ... standing for the rendered [args]: `EXTRACT(DOW FROM {0})`. */
    data class Raw(val sql: String, val args: List<SqlExpr> = emptyList()) : SqlExpr {
        constructor(sql: String, vararg args: SqlExpr) : this(sql, args.toList())
    }
}

/**
 * The operands of one call, under the parameter names of the [signature] the
 * call matched: `operands["amount"]` for a parameter, [all] for a variadic
 * parameter's arguments. Asking for a name that is no parameter of the
 * signature throws, which the store turns into a refusal when it is built.
 */
class SqlOperands internal constructor(
    val signature: Signature,
    private val single: Map<String, SqlExpr>,
    private val many: Map<String, List<SqlExpr>>,
) {
    private val parameterNames: List<String> get() = signature.parameters.map { it.name }

    operator fun get(name: String): SqlExpr =
        single[name] ?: throw IllegalArgumentException(
            if (name in many) "'$name' is a variadic parameter; read it with all(\"$name\")"
            else "no parameter is named '$name'; the signature's parameters are ${parameterNames.joinToString { "'$it'" }}"
        )

    fun all(name: String): List<SqlExpr> =
        many[name] ?: throw IllegalArgumentException(
            if (name in single) "'$name' is not a variadic parameter; read it with operands[\"$name\"]"
            else "no parameter is named '$name'; the signature's parameters are ${parameterNames.joinToString { "'$it'" }}"
        )
}
