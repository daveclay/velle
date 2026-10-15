package velle.generated.vendors

import java.math.BigDecimal
import velle.Commit
import velle.generated.*

/** The givens the generated specs demand, in the catalogue's own words (Reference §22, "The actor"). */
class Givens(private val sys: VendorsSystem) : RequiredGivens {

    private fun accept(r: Commit<*>) { check(r is Commit.Accepted<*>) { "refused: $r" } }

    /** Fixtures: seeded outside the trust boundary, before any case counts records. */
    private val ada: Staff = run { sys.system.seed("Staff", mapOf("name" to "Ada", "role" to "admin")); sys.staffs().last() }
    private val sam: Staff = run { sys.system.seed("Staff", mapOf("name" to "Sam", "role" to "staff")); sys.staffs().last() }
    private val acme: Vendor = run { sys.system.seed("Vendor", mapOf("name" to "Acme")); sys.vendors().last() }

    private fun product(name: String, price: String, cost: String): Product {
        accept(sys.commit(NewListProduct(acme, name, BigDecimal(price), BigDecimal(cost)), ada))
        return sys.products().last()
    }

    override fun assignVendor() { accept(sys.commit(NewAssignVendor(sam, acme), ada)) }

    override fun listProduct() { product("widget", "10.00", "4.00") }

    /** Listed with less than a dollar between price and cost. */
    override fun productForFlagThinMargin(): Product = product("nut", "2.00", "1.50")

    override fun changePrice() {
        val p = product("bolt", "5.00", "1.00")
        accept(sys.commit(NewAssignVendor(sam, acme), ada))
        accept(sys.commit(NewChangePrice(p, BigDecimal("6.00")), sam))
    }
}
