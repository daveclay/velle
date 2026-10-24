package velle

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Visibility (Reference §22, "The actor"; OQ33 §5.7–§5.10): `visible to` clauses on
 * shapes and fields, `visible to anyone`, the spec-wide default, the requirement that
 * every readable shape declares once a spec has authorization, the read surface
 * applying the clauses, evaluation ignoring them, and a refusal projected for the actor.
 */
class VisibilityTest {

    private val spec = """
        visible to Staff

        shape Vendor { name: text, visible to anyone }
        shape Staff { name: text  role: text }
        shape Administrator = Staff where role == "admin"
        shape VendorAssignment { staff: one Staff  vendor: one Vendor }

        transient act AssignVendor by Administrator { staff: one Staff  vendor: one Vendor }
        rule RecordAssignment when AssignVendor { VendorAssignment from { staff: staff, vendor: vendor } }

        shape Product {
            vendor: one Vendor
            name: text
            price: decimal
            cost: decimal visible to Administrator
            margin: decimal = price - cost
            visible to Staff where exists (VendorAssignment where staff == actor and vendor == this.vendor)
        }
        shape ThinMargin = Product where margin < 1.00

        transient act ChangePrice by Staff { product: one Product  price: decimal }
        ChangePrice requires exists (VendorAssignment where staff == this.actor and vendor == this.product.vendor)
        never (ChangePrice where price < product.cost)
        rule ApplyPrice when ChangePrice { product.price = price }

        shape MarginAlert { product: one Product }
        rule FlagThinMargin when (ThinMargin where not exists MarginAlert for this) { MarginAlert from { product: this } }
    """.trimIndent()

    private fun diags(src: String) = Validator.validate(src)

    private fun newSystem(): VelleSystem {
        val d = diags(spec)
        check(d.isEmpty()) { d.toString() }
        return VelleSystem(Model(Parser.parse(spec)))
    }

    private class World(val sys: VelleSystem) {
        val admin = sys.idOf(sys.seed("Staff", mapOf("name" to "Ada", "role" to "admin")))
        val assigned = sys.idOf(sys.seed("Staff", mapOf("name" to "Sam", "role" to "staff")))
        val other = sys.idOf(sys.seed("Staff", mapOf("name" to "Olu", "role" to "staff")))
        val acme = sys.idOf(sys.seed("Vendor", mapOf("name" to "Acme")))
        val widget = sys.idOf(sys.seed("Product", mapOf("vendor" to acme, "name" to "widget", "price" to BigDecimal("10"), "cost" to BigDecimal("4"))))
        init { assertIs<CommitResult.Accepted>(sys.commit("AssignVendor", mapOf("staff" to assigned, "vendor" to acme), actor = admin)) }
    }

    @Test
    fun `clauses parse on shapes, fields, and the top level, and print back`() {
        val decls = Parser.parse(spec)
        val default = decls.filterIsInstance<VisibilityDefault>().single()
        assertEquals(VisibleClause("Staff"), default.clause)
        val product = decls.filterIsInstance<ShapeDecl>().single { it.name == "Product" }
        val record = product.members.filterIsInstance<VisibleClause>().single()
        assertEquals("Staff", record.shape); assertTrue(record.where != null)
        val cost = product.members.filterIsInstance<StoredProp>().single { it.name == "cost" }
        assertEquals(listOf(VisibleClause("Administrator")), cost.visible)
        val vendor = decls.filterIsInstance<ShapeDecl>().single { it.name == "Vendor" }
        assertTrue(vendor.members.filterIsInstance<VisibleClause>().single().anyone)
        assertEquals(Parser.parse(Printer.print(product)).single(), product)
        assertEquals(default, Parser.parse(Printer.print(default)).single())
    }

    @Test
    fun `the spec validates, and the viewer's predicate is checked over the shape`() {
        assertEquals(emptyList(), diags(spec))
        val bad = spec.replace("staff == actor and vendor == this.vendor", "staff == actor and vendor == this.vendr")
        assertTrue(diags(bad).any { it.code == "F1" && "vendr" in it.message }, diags(bad).toString())
    }

    @Test
    fun `V32 - once a spec has authorization every readable shape declares its visibility`() {
        val src = spec.replace("visible to Staff\n\n", "")
        val d = diags(src).filter { it.code == "V32" }
        assertEquals(setOf("Staff", "VendorAssignment", "MarginAlert"), d.map { it.message.substringAfter("'").substringBefore("'") }.toSet(), diags(src).toString())
        assertTrue("visible to anyone" in d.first().message)
        // a spec with no `by` and no clause is untouched
        assertTrue(diags("shape Thing { name: text }\nact MakeThing { name: text }").none { it.code == "V32" })
    }

    @Test
    fun `a viewer's shape is never an act, and a field under the bound name demands as`() {
        val act = spec.replace("shape Staff { name: text  role: text }", "act Staff { name: text  role: text }")
        assertTrue(diags(act).any { it.code == "F2" && "never an act" in it.message }, diags(act).toString())
        val clash = spec.replace("shape Product {\n    vendor: one Vendor", "shape Product {\n    actor: text\n    vendor: one Vendor")
        assertTrue(diags(clash).any { it.code == "F3" && "name the viewer" in it.message }, diags(clash).toString())
        val transient = spec.replace("transient act ChangePrice by Staff { product: one Product  price: decimal }",
            "transient act ChangePrice by Staff { product: one Product  price: decimal  visible to Staff }")
        assertTrue(diags(transient).any { it.code == "F3" && "transient act" in it.message }, diags(transient).toString())
    }

    @Test
    fun `the read surface admits what the clauses admit, and the engineer's view sees everything`() {
        val sys = newSystem(); val w = World(sys)
        fun products(viewer: Long?) = sys.query(Query("Product", orderBy = listOf(OrderDatum("name")), viewer = viewer?.let { sys.refFor(it) })).ids
        assertEquals(listOf(w.widget), products(null))
        assertEquals(listOf(w.widget), products(w.assigned), "assigned to Acme: sees Acme's product")
        assertEquals(emptyList(), products(w.other), "not assigned: sees nothing")
        assertEquals(emptyList(), products(w.admin), "an administrator with no assignment sees no product either")
        assertTrue(sys.visibleTo(w.other, w.acme), "a vendor is visible to anyone")
        assertTrue(sys.visibleTo(w.other, w.other) && sys.visibleTo(w.other, w.admin), "staff records take the spec-wide default")
        assertFalse(sys.visibleCurrent(w.other, w.widget))
        assertTrue(sys.visibleCurrent(w.assigned, w.widget))
    }

    @Test
    fun `a gated field is hidden from a viewer the clause does not admit, and nothing changes what is calculated`() {
        val sys = newSystem(); val w = World(sys)
        assertEquals(setOf("cost"), sys.hiddenFields(w.assigned, w.widget))
        assertEquals(emptySet(), sys.hiddenFields(null, w.widget))
        assertIs<CommitResult.Accepted>(sys.commit("AssignVendor", mapOf("staff" to w.admin, "vendor" to w.acme), actor = w.admin))
        assertEquals(emptySet(), sys.hiddenFields(w.admin, w.widget), "an administrator assigned to the vendor sees the cost")
        // margin is price - cost for everyone: a read for the assigned staff member still gets 6
        assertEquals(0, (sys.get(w.widget, "margin") as BigDecimal).compareTo(BigDecimal("6")))
        assertIs<CommitResult.Accepted>(sys.commit("ChangePrice", mapOf("product" to w.widget, "price" to BigDecimal("4.5")), actor = w.assigned))
        assertEquals(1, sys.instancesOf("MarginAlert").size, "the rule read the cost through margin, whoever asked")
    }

    @Test
    fun `a refusal is projected for the actor like a read`() {
        val sys = newSystem(); val w = World(sys)
        val r = sys.commit("ChangePrice", mapOf("product" to w.widget, "price" to BigDecimal("3")), actor = w.assigned)
        assertIs<CommitResult.Refused>(r)
        val rej = r.rejection!!
        val product = (rej.pivots + rej.witnesses).single { it.shape == "Product" }
        assertFalse("cost" in product.fields, "the staff member learns the price was refused, not the cost: $rej")
        assertTrue(rej.evaluation.none { it is EvalStep.Read && it.member == "cost" }, rej.evaluation.toString())
        assertTrue("cost = " !in rej.message, rej.message) // the prohibition's text names the field; the data never shows its value
        // the same refusal for an administrator assigned to the vendor carries the cost
        assertIs<CommitResult.Accepted>(sys.commit("AssignVendor", mapOf("staff" to w.admin, "vendor" to w.acme), actor = w.admin))
        val r2 = sys.commit("ChangePrice", mapOf("product" to w.widget, "price" to BigDecimal("3")), actor = w.admin)
        val rej2 = assertIs<CommitResult.Refused>(r2).rejection!!
        assertTrue((rej2.pivots + rej2.witnesses).single { it.shape == "Product" }.fields.containsKey("cost"))
    }

    @Test
    fun `a generated surface types a viewer's read`() {
        val src = Codegen.generate(spec, "Vendors")
        assertTrue("interface ProductForStaff : Keyed" in src, src)
        assertTrue("val cost: BigDecimal?" in src)
        assertTrue("fun read(viewer: Ref<Staff>): StaffRead" in src)
        assertTrue("fun product(ref: Ref<Product>): ProductForStaff?" in src)
        assertTrue("val vendors: VendorQuery get() = query(\"Vendor\", VendorFields()) { materializeVendor(it) }" in src, src)
        assertNull(Regex("interface VendorFor").find(src), "no gated field on Vendor: the class is the view")
    }
}
