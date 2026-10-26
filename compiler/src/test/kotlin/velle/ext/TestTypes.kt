package velle.ext

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Extension types the compiler's own tests register (`META-INF/services/velle.ext.VelleType`
 * under test resources), so `TypeRegistry.default` carries them in every test:
 * - [TestMoney] (`velle.test.Price`): ordered, summable, `Money + Money`, `Money - Money`,
 *   `Money * decimal`, reads a number constant. Cents, so `below`/`above` step by one cent.
 * - [OtherMoney] (`velle.testalt.Price`): a second type named `Price`, so a spec that wants
 *   either must write a `uses` line.
 * - [TestBadge] (`velle.test.Badge`): no ordering, no operators, no sum; reads a text constant.
 */
data class Cents(val cents: Long) {
    override fun toString(): String = BigDecimal(cents).movePointLeft(2).setScale(2).toPlainString()
    companion object {
        fun of(text: String): Cents = Cents(BigDecimal(text).setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact())
    }
}

class TestMoney : VelleType<Cents>, Ordered<Cents>, Summable<Cents> {
    override val name = "Price"
    override val namespace = "velle.test"
    override val kotlinClass = Cents::class
    override val literals = Literals<Cents>(fromNumber = { n ->
        if (n.scale() > 2) throw LiteralRejected("$n has more than two decimal places; Money is whole cents")
        Cents.of(n.toPlainString())
    })
    override fun canonical(value: Cents) = value.toString()
    override val ordering: Comparator<Cents> = compareBy { it.cents }
    override val operators: List<Operator<*, *, *>> = listOf(
        Operator<Cents, Cents, Cents>("+", qualifiedName, qualifiedName, qualifiedName, { a, b -> Cents(a.cents + b.cents) }, foldStepsCommute = true, foldIdempotent = false),
        Operator<Cents, Cents, Cents>("-", qualifiedName, qualifiedName, qualifiedName, { a, b -> Cents(a.cents - b.cents) }, foldStepsCommute = true, foldIdempotent = false),
        Operator<Cents, BigDecimal, Cents>("*", qualifiedName, "decimal", qualifiedName,
            { a, b -> Cents(BigDecimal(a.cents).multiply(b).setScale(0, RoundingMode.HALF_EVEN).longValueExact()) }, foldStepsCommute = false, foldIdempotent = false),
    )
    override val zero = Cents(0)
    override fun plus(a: Cents, b: Cents) = Cents(a.cents + b.cents)
    override val samples = Samples(
        sample = Cents(1250),
        below = { Cents(it.cents - 1) },
        above = { Cents(it.cents + 1) },
        kotlinSource = { "velle.ext.Cents.of(\"$it\")" },
    )
}

data class AltCents(val cents: Long)

class OtherMoney : VelleType<AltCents>, Ordered<AltCents> {
    override val name = "Price"
    override val namespace = "velle.testalt"
    override val kotlinClass = AltCents::class
    override val literals = Literals<AltCents>(fromNumber = { AltCents(it.movePointRight(2).longValueExact()) })
    override fun canonical(value: AltCents) = BigDecimal(value.cents).movePointLeft(2).toPlainString()
    override val ordering: Comparator<AltCents> = compareBy { it.cents }
    override val operators: List<Operator<*, *, *>> = emptyList()
    override val samples = Samples(sample = AltCents(100), kotlinSource = { "velle.ext.AltCents(${it.cents})" })
}

data class Badge(val text: String)

class TestBadge : VelleType<Badge> {
    override val name = "Badge"
    override val namespace = "velle.test"
    override val kotlinClass = Badge::class
    override val literals = Literals<Badge>(fromText = { t ->
        if (t.isBlank()) throw LiteralRejected("a badge is never blank")
        Badge(t.lowercase())
    })
    override fun validate(value: Badge): String? = if (value.text.isBlank()) "a badge is never blank" else null
    override fun canonical(value: Badge) = value.text
    override val operators: List<Operator<*, *, *>> = emptyList()
    override val samples = Samples(sample = Badge("urgent"), kotlinSource = { "velle.ext.Badge(\"${it.text}\")" })
}
