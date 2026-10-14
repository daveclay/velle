package velle

import java.sql.Connection
import velle.generated.*
import java.sql.DriverManager
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import velle.generated.OrdersStoreOverGeneric
import velle.generated.OrdersStoreResolver
import velle.generated.OrdersSystem
import velle.sqlite.SqliteStore

/**
 * The orders flows over real storage — the exposure closure (README §6,
 * "Inline part creation") landing container and parts as one storage
 * transaction, pending references resolved as rows are minted. Once through
 * the generic protocol, once through the generated typed store surface.
 */
class OrdersFlowOverSqliteTest : OrdersFlowTest() {
    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    @AfterTest
    fun close() = conn.close()

    override fun newSystem(): OrdersSystem = OrdersSystem().also { SqliteStore.attach(it.system, conn) }
}

class OrdersFlowOverTypedStoreTest : OrdersFlowTest() {
    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    @AfterTest
    fun close() = conn.close()

    override fun newSystem(): OrdersSystem = OrdersSystem().also { sys ->
        val backend = SqliteStore(sys.system.model, conn).apply { createSchema() }
        val store = OrdersStoreOverGeneric(backend, sys.system.model)
        sys.system.connect(OrdersStoreResolver(store, sys.system.model), backend)
    }
}

/** The closure in storage: one transaction's rows, and what a fresh process reads back through the inverse. */
class OrdersPersistenceTest {
    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    @AfterTest
    fun close() = conn.close()

    private fun process(): OrdersSystem = OrdersSystem().also { SqliteStore.attach(it.system, conn) }

    private fun accept(r: Commit<*>) = assertIs<Commit.Accepted<*>>(r, "refused: $r")

    private fun rows(sql: String): List<List<Any?>> =
        conn.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                buildList {
                    while (rs.next()) add((1..rs.metaData.columnCount).map { i ->
                        rs.getObject(i).let { if (it is Number) it.toLong() else it }
                    })
                }
            }
        }

    private fun OrdersSystem.cardedProduct(): Pair<Customer, Product> {
        accept(commit(NewCustomer("Ada")))
        accept(commit(NewProduct("SKU-1")))
        return customers().last() to products().last()
    }

    @Test
    fun `a closure lands as one storage transaction and reads back through its inverse`() {
        val first = process()
        val (ada, prod) = first.cardedProduct()
        accept(first.commit(NewOrder(
            customer = ada,
            placedOn = LocalDate.parse("2026-08-21"),
            orderLines = listOf(
                NewOrderLine(
                    product = prod, quantity = 2,
                    customizations = listOf(NewCustomization(note = "gift wrap")),
                ),
                NewOrderLine(product = prod, quantity = 3),
            ),
        )))

        // back-references were pending refs at commit time, resolved to the minted keys
        assertEquals(listOf(listOf(1L, 1L), listOf(2L, 1L)), rows("""SELECT id, "order" FROM OrderLine ORDER BY id"""))
        assertEquals(listOf(listOf(1L)), rows("""SELECT line FROM Customization"""))
        assertEquals(listOf(listOf(1L, 2L), listOf(2L, 3L)), rows("""SELECT line, quantity FROM Reservation ORDER BY id"""))

        val second = process()
        val order = second.read.order(1L)
        assertEquals(2, second.read(order).orderLines.size, "the inverse is a join read over the lines' pointer column")
        assertEquals(5, second.read(order).total, "the aggregate derives from stored parts")
        val custz = second.customizations().single()
        assertEquals(2, second.read(second.read(custz).line).quantity)
        assertEquals(order, second.read(second.read(custz).line).order)
    }

    @Test
    fun `a refused closure leaves storage untouched`() {
        val first = process()
        val (ada, prod) = first.cardedProduct()
        val r = first.commit(NewOrder(
            customer = ada,
            placedOn = LocalDate.parse("2026-08-21"),
            orderLines = listOf(
                NewOrderLine(product = prod, quantity = 2),
                NewOrderLine(product = prod, quantity = 0),
            ),
        ))
        assertIs<Commit.Refused<*>>(r)
        for (table in listOf("Order", "OrderLine", "Customization", "Reservation"))
            assertEquals(listOf(listOf(0L)), rows("""SELECT COUNT(*) FROM "$table""""), "$table rows after a refused closure")
        assertNull(rows("""SELECT MAX(id) FROM "Order"""").single().single(), "no key was minted")
    }
}

