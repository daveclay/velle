package velle.finance

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * An amount of money in one currency, exact to the cent. The value a spec's
 * `Money` field holds, on the generated surface and in a store's row.
 *
 * One currency on purpose: a `Money` that carried its currency would be a value
 * with two parts, and in Velle a value with parts is a shape with two fields
 * (`amount: Money`, `currency: text`), where the product owner can read the
 * rules about it. This class is the amount alone.
 */
class Money private constructor(val cents: Long) : Comparable<Money> {

    operator fun plus(other: Money) = Money(Math.addExact(cents, other.cents))
    operator fun minus(other: Money) = Money(Math.subtractExact(cents, other.cents))

    /** Scales by [factor], rounding half to even to the cent. */
    operator fun times(factor: BigDecimal): Money =
        Money(BigDecimal(cents).multiply(factor).setScale(0, RoundingMode.HALF_EVEN).longValueExact())

    fun toBigDecimal(): BigDecimal = BigDecimal(cents).movePointLeft(2).setScale(2)

    override fun compareTo(other: Money): Int = cents.compareTo(other.cents)
    override fun equals(other: Any?): Boolean = other is Money && other.cents == cents
    override fun hashCode(): Int = cents.hashCode()

    /** The amount as a plain decimal with two places: `12.50`, `-3.00`. */
    override fun toString(): String = toBigDecimal().toPlainString()

    companion object {
        val ZERO = Money(0)

        /** `Money.of("12.50")`: a decimal with at most two places. */
        fun of(text: String): Money = of(BigDecimal(text))

        /** A decimal with at most two places; more is refused, since a cent is the unit. */
        fun of(amount: BigDecimal): Money {
            require(amount.stripTrailingZeros().scale() <= 2) { "$amount has more than two decimal places; Money is whole cents" }
            return Money(amount.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact())
        }

        fun ofCents(cents: Long) = Money(cents)
    }
}
