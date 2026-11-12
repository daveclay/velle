package velle.sql

import velle.ext.Accepts
import velle.ext.Numeric
import velle.ext.Parameter
import velle.ext.Returns
import velle.ext.Signature
import velle.ext.VelleFunction
import velle.ext.builtin.BooleanType
import java.math.BigDecimal

/**
 * Two functions the SQL layer's conformance registers
 * (`META-INF/services/velle.ext.VelleFunction` under the fixtures' resources),
 * and the bindings its scenarios hand the store by explicit list:
 * - [Doubled] (`velle.sqltest.doubled`): a number doubled, in its own type.
 * - [IsEven] (`velle.sqltest.isEven`): a predicate over a number.
 * The bindings are deliberately plain so every SQL database agrees with the body.
 */
class Doubled : VelleFunction {
    override val name = "doubled"
    override val namespace = "velle.sqltest"
    override val signatures = listOf(Signature(
        parameters = listOf(Parameter("value", Accepts.Is(Numeric::class))),
        result = Returns.SameAs(0),
        apply = { _, args ->
            val numeric = args[0].capability<Numeric<Any>>()
            numeric.fromDecimal(numeric.toDecimal(args[0].value).multiply(BigDecimal(2)))
        },
    ))
}

class IsEven : VelleFunction {
    override val name = "isEven"
    override val namespace = "velle.sqltest"
    override val signatures = listOf(Signature(
        parameters = listOf(Parameter("value", Accepts.Is(Numeric::class))),
        result = Returns.Is(BooleanType::class),
        apply = { _, args ->
            args[0].capability<Numeric<Any>>().toDecimal(args[0].value).remainder(BigDecimal(2)).signum() == 0
        },
    ))
}

/** The SQL-wide rendering of `doubled`; [renders] counts the calls rendered through it. */
object DoubledSql : SqlFunctionBinding {
    override val function = "velle.sqltest.doubled"
    var renders = 0
    override fun render(operands: SqlOperands): SqlExpr {
        renders++
        return SqlExpr.Infix(operands["value"], "*", SqlExpr.Literal(2))
    }
}

/** A rendering of `doubled` claiming one dialect, picked over [DoubledSql] on that dialect. */
class DoubledOn(dialect: String) : SqlFunctionBinding {
    override val function = "velle.sqltest.doubled"
    override val dialects = setOf(dialect)
    var renders = 0
    override fun render(operands: SqlOperands): SqlExpr {
        renders++
        return SqlExpr.Infix(SqlExpr.Literal(2), "*", operands["value"])
    }
}

/** A binding that reads an operand by a name that is no parameter of `doubled`. */
object DoubledWrongName : SqlFunctionBinding {
    override val function = "velle.sqltest.doubled"
    override fun render(operands: SqlOperands): SqlExpr = SqlExpr.Infix(operands["amount"], "*", SqlExpr.Literal(2))
}

/** A binding naming a function nothing registers. */
object NothingSql : SqlFunctionBinding {
    override val function = "velle.sqltest.nothing"
    override fun render(operands: SqlOperands): SqlExpr = operands["value"]
}

/** The rendering of `doubled` with DDL the store runs with the schema. */
class DoubledWithDdl(override val ddl: List<String>) : SqlFunctionBinding {
    override val function = "velle.sqltest.doubled"
    override fun render(operands: SqlOperands): SqlExpr = SqlExpr.Infix(operands["value"], "*", SqlExpr.Literal(2))
}

/** The SQL-wide rendering of `isEven`. */
object IsEvenSql : SqlFunctionBinding {
    override val function = "velle.sqltest.isEven"
    override fun render(operands: SqlOperands): SqlExpr =
        SqlExpr.Infix(SqlExpr.Infix(operands["value"], "%", SqlExpr.Literal(2)), "=", SqlExpr.Literal(0))
}
