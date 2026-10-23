package velle.generated.vendors

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import velle.Commit
import velle.generated.*

/**
 * The read side of authorization, end to end (Reference §22, "The actor"): the
 * same catalogue read through an assigned staff member, an administrator, a
 * stranger, and the engineer's own unfiltered view; a refusal handed back to a
 * staff member without the cost they may not see; and the margin calculated
 * from the cost for everyone, whoever asks.
 */
open class VendorsFlowTest {

    protected open fun newSystem(): VendorsSystem = VendorsSystem()
    protected val sys: VendorsSystem by lazy { newSystem() }

    private fun accept(r: Commit<*, *>) = assertIs<Commit.Accepted<*, *>>(r, "refused: $r")
    private fun refuse(r: Commit<*, *>) = assertTrue(r !is Commit.Accepted<*, *>, "expected a refusal, got: $r")
    private fun dec(s: String) = BigDecimal(s)

    /** Staff and vendors are fixtures, seeded outside the trust boundary; products and assignments arrive through acts. */
    private class World(val sys: VendorsSystem) {
        fun staff(name: String, role: String): Staff { sys.system.seed("Staff", mapOf("name" to name, "role" to role)); return sys.staffs().last() }
        fun vendor(name: String): Vendor { sys.system.seed("Vendor", mapOf("name" to name)); return sys.vendors().last() }
        val ada = staff("Ada", "admin")
        val sam = staff("Sam", "staff")
        val olu = staff("Olu", "staff")
        val acme = vendor("Acme")
        val bolt = vendor("Bolt")
    }

    private fun product(w: World, vendor: Vendor, name: String, price: String, cost: String): Product {
        accept(sys.commit(NewListProduct(vendor, name, dec(price), dec(cost)), w.ada))
        return sys.products().last()
    }

    @Test
    fun `reads - an assigned staff member sees their vendor's products without the cost, an administrator sees everything, a stranger nothing`() {
        val w = World(sys)
        val widget = product(w, w.acme, "widget", "10.00", "4.00")
        product(w, w.bolt, "nut", "2.00", "1.50")
        accept(sys.commit(NewAssignVendor(w.sam, w.acme), w.ada))

        val sam = sys.read(w.sam)
        assertEquals(listOf("widget"), sam.products.orderBy { name.ascending }.list().map { it.name })
        val seen: ProductForStaff = assertNotNull(sam.product(widget))
        assertEquals(dec("10.00"), seen.price)
        assertNull(seen.cost, "the cost is shown to administrators only")
        assertNull(seen.margin, "and so is the margin, by its own clause")
        assertEquals(listOf("Acme", "Bolt"), sam.vendors.orderBy { name.ascending }.list().map { it.name }, "vendors are visible to anyone")
        assertEquals(3, sam.staffs.orderBy { name.ascending }.list().size, "staff records take the spec-wide default: visible to staff")

        val olu = sys.read(w.olu)
        assertEquals(emptyList(), olu.products.orderBy { name.ascending }.list(), "assigned to nobody")
        assertNull(olu.product(widget), "an invisible record resolves absent")

        val ada = sys.read(w.ada)
        assertEquals(listOf("nut", "widget"), ada.products.orderBy { name.ascending }.list().map { it.name })
        assertEquals(dec("4.00"), assertNotNull(ada.product(widget)).cost)
        assertEquals(dec("6.00"), assertNotNull(ada.product(widget)).margin)

        // the engineer's own view: the class, every field, no viewer
        assertEquals(dec("4.00"), sys.read(widget).cost)
        assertEquals(2, sys.read.products.orderBy { name.ascending }.list().size)
    }

    @Test
    fun `a refusal handed back to a staff member names the product without its cost`() {
        val w = World(sys)
        val widget = product(w, w.acme, "widget", "10.00", "4.00")
        accept(sys.commit(NewAssignVendor(w.sam, w.acme), w.ada))
        refuse(sys.commit(NewChangePrice(widget, dec("5.00")), w.olu)) // not assigned

        val r = sys.commit(NewChangePrice(widget, dec("3.00")), w.sam)
        val rej = assertIs<Commit.Refused<*, *>>(r).rejection!!
        val seenProduct = (rej.pivots + rej.witnesses).single { it.shape == "Product" }
        assertTrue("cost" !in seenProduct.fields, "projected for the actor: $rej")
        assertTrue("price" in seenProduct.fields)
        assertTrue(rej.evaluation.none { it is velle.EvalStep.Read && it.member == "cost" })

        accept(sys.commit(NewAssignVendor(w.ada, w.acme), w.ada))
        val r2 = sys.commit(NewChangePrice(widget, dec("3.00")), w.ada)
        val rej2 = assertIs<Commit.Refused<*, *>>(r2).rejection!!
        assertTrue("cost" in (rej2.pivots + rej2.witnesses).single { it.shape == "Product" }.fields, "an administrator sees the cost in the same refusal")
    }

    @Test
    fun `the margin is calculated from the cost whoever asks, and the alert fires`() {
        val w = World(sys)
        val widget = product(w, w.acme, "widget", "10.00", "9.50")
        accept(sys.commit(NewAssignVendor(w.sam, w.acme), w.ada))
        assertEquals(1, sys.marginAlerts().size, "listed under a dollar: flagged at once")
        val nut = product(w, w.acme, "nut", "3.00", "1.00")
        assertEquals(1, sys.marginAlerts().size)
        accept(sys.commit(NewChangePrice(nut, dec("1.50")), w.sam))
        assertEquals(2, sys.marginAlerts().size, "the staff member who cannot see the cost moved the margin under a dollar all the same")
        assertEquals(dec("0.50"), sys.read(nut).margin)
        assertNull(assertNotNull(sys.read(w.sam).product(widget)).margin)
    }
}
