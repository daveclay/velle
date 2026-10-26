package velle.finance

import velle.ext.LiteralRejected
import velle.ext.Literals
import velle.ext.Operator
import velle.ext.Ordered
import velle.ext.Samples
import velle.ext.Summable
import velle.ext.VelleType
import java.math.BigDecimal

/**
 * `Money` as a Velle type (Reference §5, "Extension types"): what a spec may do
 * with a field of [Money], declared capability by capability. Registered in
 * `META-INF/services/velle.ext.VelleType`; a spec on a classpath holding this
 * library writes `balance: Money` with nothing else declared.
 *
 * - **Constants.** A number constant where a `Money` is expected (`balance < 0`,
 *   `initially 0`, `amount <= 0`) reads as an amount; more than two decimal places
 *   is refused at compile time.
 * - **Ordered** (`velle.ext.Ordered`), so `< <= > >=`, `max`/`min`, a selector's `by`,
 *   and a query order all apply.
 * - **Arithmetic**: `Money + Money`, `Money - Money`, `Money * decimal`, and
 *   `decimal * Money`. The two additive steps commute, so a rule may fold a balance
 *   on a tick cadence; neither is idempotent, so an unguarded `after commit` fold
 *   is still refused (Reference §19).
 * - **Summable** (`velle.ext.Summable`): `sum(lineItems, amount)` is a `Money`.
 */
class MoneyType : VelleType<Money>, Ordered<Money>, Summable<Money> {
    override val name = "Money"
    override val namespace = "velle.finance"
    override val kotlinClass = Money::class

    override val literals = Literals<Money>(fromNumber = { n ->
        if (n.stripTrailingZeros().scale() > 2) throw LiteralRejected("$n has more than two decimal places; Money is whole cents")
        Money.of(n)
    })

    override fun canonical(value: Money): String = value.toString()

    override val ordering: Comparator<Money> = Comparator { a, b -> a.compareTo(b) }

    override val operators: List<Operator<*, *, *>> = listOf(
        Operator<Money, Money, Money>("+", qualifiedName, qualifiedName, qualifiedName, { a, b -> a + b }, foldStepsCommute = true, foldIdempotent = false),
        Operator<Money, Money, Money>("-", qualifiedName, qualifiedName, qualifiedName, { a, b -> a - b }, foldStepsCommute = true, foldIdempotent = false),
        Operator<Money, BigDecimal, Money>("*", qualifiedName, "decimal", qualifiedName, { a, b -> a * b }, foldStepsCommute = false, foldIdempotent = false),
        Operator<BigDecimal, Money, Money>("*", "decimal", qualifiedName, qualifiedName, { a, b -> b * a }, foldStepsCommute = false, foldIdempotent = false),
    )

    override val zero: Money = Money.ZERO
    override fun plus(a: Money, b: Money): Money = a + b

    override val samples = Samples(
        sample = Money.of("12.50"),
        below = { Money.ofCents(it.cents - 1) },
        above = { Money.ofCents(it.cents + 1) },
        kotlinSource = { "velle.finance.Money.of(\"$it\")" },
    )
}
