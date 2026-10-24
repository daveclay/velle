package velle.finance

import velle.TypeRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MoneyTypeTest {
    @Test
    fun `the type passes the registry's checks on its own samples and is found on the classpath`() {
        assertEquals(emptyList(), TypeRegistry(listOf(MoneyType())).diagnostics)
        assertEquals(listOf("velle.finance.Money"), TypeRegistry.default.byQualifiedName.keys.toList())
    }

    @Test
    fun `amounts are exact cents`() {
        assertEquals(Money.of("13.00"), Money.of("12.50") + Money.of("0.50"))
        assertEquals(Money.of("25.00"), Money.of("12.50") * java.math.BigDecimal("2"))
        assertEquals("12.50", Money.of("12.5").toString())
        assertFailsWith<IllegalArgumentException> { Money.of("0.125") }
    }
}
