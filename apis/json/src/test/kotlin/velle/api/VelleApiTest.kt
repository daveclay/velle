package velle.api

import velle.Model
import velle.Parser
import velle.Ref
import velle.Validator
import velle.VelleSystem
import velle.sqlite.SqliteStore
import java.io.File
import java.math.BigDecimal
import java.sql.DriverManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The API layer over the example specs, in memory and over SQLite: the model
 * described, acts committed from wire maps (keys, references, nested parts, a
 * value supplied by value, a sibling named by number), records and queries read
 * back in wire form, refusals as data, the actor on commits and the viewer on reads.
 */
class VelleApiTest {

    private val day = Instant.parse("2026-03-10T12:00:00Z")
    private val clock = Clock.fixed(day, ZoneOffset.UTC)

    private fun system(spec: String): VelleSystem {
        val diags = Validator.validate(spec)
        check(diags.none { !it.advisory }) { diags.toString() }
        return VelleSystem(Model(Parser.parse(spec)), startTime = day)
    }

    private fun example(name: String) = File("../../examples/$name/$name.velle").readText()

    private fun billing(sqlite: Boolean = false): VelleApi {
        val sys = system(example("billing"))
        if (sqlite) SqliteStore.attach(sys, DriverManager.getConnection("jdbc:sqlite::memory:"))
        return VelleApi(sys, clock)
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.ref(): Map<String, Any?> = this["ref"] as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.items(): List<Map<String, Any?>> = this["items"] as List<Map<String, Any?>>

    private fun accepted(r: Map<String, Any?>): Map<String, Any?> {
        assertEquals(true, r["accepted"], r.toString())
        return r
    }

    // ── describe ────────────────────────────────────────────────────────────────

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `the description names the acts, the readable shapes, the schedules`() {
        val d = billing().description
        assertEquals(false, d["authorization"])
        assertEquals(listOf("Hourly", "Weekly"), d["schedules"])
        val acts = (d["acts"] as List<Map<String, Any?>>).associateBy { it["name"] }
        assertEquals(true, acts.getValue("SignUp")["transient"])
        val lineItem = acts.getValue("LineItem")
        assertEquals(false, lineItem["transient"])
        val fields = (lineItem["fields"] as List<Map<String, Any?>>).associateBy { it["name"] }
        assertEquals(mapOf("name" to "invoice", "type" to "Invoice", "kind" to "reference", "optional" to false), fields["invoice"])
        assertEquals("Money", fields.getValue("price")["type"])
        assertEquals(setOf("invoice", "description", "price", "quantity"), fields.keys) // `amount` is derived: never supplied
        val shapes = (d["shapes"] as List<Map<String, Any?>>).associateBy { it["name"] }
        assertTrue("SignUp" !in shapes, "a transient act is not readable")
        assertTrue("LineItem" in shapes, "a durable act is a readable shape")
        assertEquals(true, shapes.getValue("Tick")["velle"], "Velle's record of a tick is readable and marked as Velle's")
        assertTrue("Tick" !in acts, "and nothing commits it")
        val invoice = shapes.getValue("Invoice")
        assertEquals(listOf("isPaidInvoice", "isOverdueInvoice", "isActionableOverdue", "isIssuedInvoice", "isArchivedInvoice"), invoice["flags"])
        val members = (invoice["members"] as List<Map<String, Any?>>).associateBy { it["name"] }
        assertEquals("derived", members.getValue("total")["kind"])
        assertEquals("timestamp", members.getValue("issuedOn")["kind"])
        assertEquals("inverse", members.getValue("lineItems")["kind"])
        assertEquals("LineItem", members.getValue("lineItems")["type"])
        assertEquals("refinement", shapes.getValue("PaidInvoice")["kind"])
        assertEquals(listOf("Invoice"), shapes.getValue("PaidInvoice")["bases"])
    }

    // ── billing: commit, read, query ────────────────────────────────────────────

    private fun billingFlow(api: VelleApi) {
        // a transient act leaves no record: `ref` is null, and the record its rule made is found by a query
        val signUp = accepted(api.commit("SignUp", mapOf("name" to "Ada", "email" to "ada@example.com"), actor = null))
        assertNull(signUp["ref"])
        val customers = api.query("Customer", null, listOf("name"), null, null, null).items()
        assertEquals(1, customers.size)
        val ada = customers.single()
        assertEquals("Ada", ada["name"])
        assertEquals("0.00", ada["largestPayment"])
        assertEquals("Customer", ada["shape"])

        // a reference supplied as a bare key, a date as text, Money as text and as a number
        accepted(api.commit("BillCustomer", mapOf("customer" to ada["key"], "due" to "2026-04-01"), null))
        val invoice = api.query("Invoice", null, listOf("due"), null, null, null).items().single()
        val invKey = invoice["key"]!!
        val li = accepted(api.commit("LineItem", mapOf("invoice" to mapOf("shape" to "Invoice", "key" to invKey), "description" to "Design", "price" to "1200.00", "quantity" to 1), null))
        assertEquals("LineItem", li.ref()["shape"])
        accepted(api.commit("LineItem", mapOf("invoice" to invKey, "description" to "Hosting", "price" to 50, "quantity" to 2), null))

        val read = api.read("Invoice", invKey, null)
        assertEquals("1300.00", read["total"])
        assertEquals("1300.00", read["balance"])
        assertEquals("open", read["status"])
        assertEquals(false, read["isPaidInvoice"])
        assertEquals("2026-04-01", read["due"])
        assertEquals(2, (read["lineItems"] as List<*>).size)
        assertEquals(mapOf("shape" to "Customer", "key" to ada["key"]), read["customer"])
        assertEquals(day.toString(), read["issuedOn"])
        assertEquals(1L, api.read("LineItem", li.ref()["key"]!!, null)["quantity"]) // an integer comes back whole
        assertEquals("1200.00", api.read("LineItem", li.ref()["key"]!!, null)["amount"])

        // a refusal at the door: data, not an exception
        val zero = api.commit("SubmitPayment", mapOf("invoice" to invKey, "amount" to 0), null)
        assertEquals(false, zero["accepted"])
        @Suppress("UNCHECKED_CAST")
        val rejection = zero["rejection"] as Map<String, Any?>
        assertEquals("SubmitPayment", rejection["targetShape"])
        assertTrue((rejection["message"] as String).contains("amount"), rejection["message"] as String)

        // a malformed input is refused before the runtime, naming the field
        val bad = assertFailsWith<ApiException> { api.commit("LineItem", mapOf("invoice" to invKey, "description" to "x", "price" to "ten", "quantity" to 1), null) }
        assertEquals(ApiException.Kind.BAD_REQUEST, bad.kind)
        assertTrue("price" in bad.message!!, bad.message)
        val unknown = assertFailsWith<ApiException> { api.commit("LineItem", mapOf("invoice" to invKey, "colour" to "red"), null) }
        assertTrue("colour" in unknown.message!!, unknown.message)

        // paid in full: the derived status flips, the refinement flag with it
        accepted(api.commit("SubmitPayment", mapOf("invoice" to invKey, "amount" to "1300.00"), null))
        val paid = api.read("Invoice", invKey, null)
        assertEquals("paid", paid["status"])
        assertEquals(true, paid["isPaidInvoice"])
        assertEquals(1, api.count("PaidInvoice", null, null))
        assertEquals(1, api.query("Receipt", null, listOf("sentOn"), null, null, null).items().size)
        assertEquals("1300.00", api.read("Customer", ada["key"]!!, null)["largestPayment"])

        // a query in the spec's own predicate language, ordered and paged
        accepted(api.commit("BillCustomer", mapOf("customer" to ada["key"], "due" to "2026-01-01"), null))
        accepted(api.commit("BillCustomer", mapOf("customer" to ada["key"], "due" to "2026-02-01"), null))
        val overdueKeys = api.query("Invoice", "due < today and balance <= 0", listOf("due"), null, null, null).items().map { it["due"] }
        assertEquals(listOf("2026-01-01", "2026-02-01"), overdueKeys) // overdue by date, though empty invoices owe nothing
        val page1 = api.query("Invoice", null, listOf("-due"), 2, null, null)
        assertEquals(listOf("2026-04-01", "2026-02-01"), page1.items().map { it["due"] })
        val cursor = assertNotNull(page1["next"] as String?)
        val page2 = api.query("Invoice", null, listOf("-due"), 2, cursor, null)
        assertEquals(listOf("2026-01-01"), page2.items().map { it["due"] })
        assertNull(page2["next"])
        assertEquals(0, api.query("OverdueInvoice", null, listOf("due"), null, null, null).items().size) // nothing owed, nothing overdue

        // what the query checks refuse comes back as a bad request with the diagnostics
        val noOrder = assertFailsWith<ApiException> { api.query("Invoice", null, emptyList(), null, null, null) }
        assertTrue(noOrder.details.any { "order" in it }, noOrder.details.toString())
        val syntax = assertFailsWith<ApiException> { api.query("Invoice", "due <", listOf("due"), null, null, null) }
        assertTrue(syntax.message!!.startsWith("where:"), syntax.message)
        val unknownMember = assertFailsWith<ApiException> { api.query("Invoice", "colour == 1", listOf("due"), null, null, null) }
        assertEquals(ApiException.Kind.BAD_REQUEST, unknownMember.kind)
        assertEquals(ApiException.Kind.NOT_FOUND, assertFailsWith<ApiException> { api.read("Invoice", 999, null) }.kind)
        assertEquals(ApiException.Kind.NOT_FOUND, assertFailsWith<ApiException> { api.read("Nothing", 1, null) }.kind)
        assertEquals(ApiException.Kind.BAD_REQUEST, assertFailsWith<ApiException> { api.query("SignUp", null, listOf("name"), null, null, null) }.kind)

        // a tick: the weekly sweep reminds the overdue invoices that owe something — none here
        val tick = api.tick("Weekly")
        assertEquals(emptyList<Any>(), tick["rejections"])
        assertEquals(ApiException.Kind.NOT_FOUND, assertFailsWith<ApiException> { api.tick("Daily") }.kind)
    }

    @Test
    fun `billing in memory`() = billingFlow(billing())

    @Test
    fun `billing over SQLite, with the store's keys on the wire`() {
        val api = billing(sqlite = true)
        billingFlow(api)
        val customer = api.query("Customer", null, listOf("name"), null, null, null).items().single()
        assertEquals(1L, customer["key"]) // SQLite's first row id
    }

    // ── authorization: the actor on a commit, the viewer on a read ──────────────

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `an act declared by takes the actor, and a read is projected for the viewer`() {
        val sys = system(example("vendors"))
        val api = VelleApi(sys, clock)
        val admin = sys.seed("Staff", mapOf("name" to "Grace", "role" to "admin"))
        val staff = sys.seed("Staff", mapOf("name" to "Linus", "role" to "staff"))
        val vendor = sys.seed("Vendor", mapOf("name" to "Acme"))
        assertEquals(true, api.description["authorization"])

        // no actor: refused before the runtime
        assertEquals(ApiException.Kind.UNAUTHORIZED, assertFailsWith<ApiException> { api.commit("ListProduct", mapOf("vendor" to vendor.key.value, "name" to "Widget", "price" to 10, "cost" to 4), null) }.kind)
        assertEquals(ApiException.Kind.UNAUTHORIZED, assertFailsWith<ApiException> { api.read("Vendor", vendor.key.value, null) }.kind)

        // the role gate: a staff member may not list a product
        val gated = api.commit("ListProduct", mapOf("vendor" to vendor.key.value, "name" to "Widget", "price" to 10, "cost" to 4), staff)
        assertEquals(false, gated["accepted"])
        accepted(api.commit("ListProduct", mapOf("vendor" to vendor.key.value, "name" to "Widget", "price" to 10, "cost" to 4), admin))
        accepted(api.commit("AssignVendor", mapOf("staff" to staff.key.value, "vendor" to vendor.key.value), admin))

        // the administrator sees the cost; the assigned staff member sees the product without it
        val asAdmin = api.query("Product", null, listOf("name"), null, null, admin).items().single()
        assertEquals(BigDecimal(4), asAdmin["cost"])
        assertEquals(BigDecimal(6), asAdmin["margin"])
        val asStaff = api.query("Product", null, listOf("name"), null, null, staff).items().single()
        assertNull(asStaff["cost"])
        assertNull(asStaff["margin"])
        assertEquals(BigDecimal(10), asStaff["price"])
        assertEquals(asStaff["key"], asAdmin["key"])
        val productKey = asAdmin["key"]!!

        // an unassigned staff member is shown nothing: the record resolves absent
        val other = sys.seed("Staff", mapOf("name" to "Ken", "role" to "staff"))
        assertEquals(ApiException.Kind.NOT_FOUND, assertFailsWith<ApiException> { api.read("Product", productKey, other) }.kind)
        assertEquals(0, api.count("Product", null, other))

        // a refusal is projected for the actor: the staff member's rejection carries the product without its cost
        val below = api.commit("ChangePrice", mapOf("product" to productKey, "price" to 3), staff)
        assertEquals(false, below["accepted"])
        val rejection = below["rejection"] as Map<String, Any?>
        val pivots = rejection["pivots"] as List<Map<String, Any?>>
        val product = pivots.single { it["shape"] == "Product" }
        assertTrue("cost" !in (product["fields"] as Map<String, Any?>), product.toString())

        // the actor of a durable act is stored and read back as a reference; the viewer-less read is refused
        assertEquals(ApiException.Kind.UNAUTHORIZED, assertFailsWith<ApiException> { api.query("Product", null, listOf("name"), null, null, null) }.kind)
    }

    // ── closures: parts, a value supplied by value, a sibling named by number ───

    @Test
    fun `an act arrives with its parts, and a part with parts of its own`() {
        val api = VelleApi(system(example("orders")), clock)
        val customer = accepted(api.commit("Customer", mapOf("name" to "Ada"), null)).ref()["key"]!!
        val product = accepted(api.commit("Product", mapOf("sku" to "A-1"), null)).ref()["key"]!!
        val order = accepted(api.commit("Order", mapOf(
            "customer" to customer, "placedOn" to "2026-03-01",
            "orderLines" to listOf(
                mapOf("product" to product, "quantity" to 2, "customizations" to listOf(mapOf("note" to "gift wrap"))),
                mapOf("product" to product, "quantity" to 3),
            ),
        ), null))
        val read = api.read("Order", order.ref()["key"]!!, null)
        assertEquals(5L, read["total"])
        assertEquals(2, (read["orderLines"] as List<*>).size)
        assertEquals(1, api.count("Customization", null, null))
        assertEquals(2, api.count("Reservation", null, null)) // one firing per inline part

        // the whole closure is refused at the door when one part breaks a constraint
        val refused = api.commit("Order", mapOf("customer" to customer, "placedOn" to "2026-03-01",
            "orderLines" to listOf(mapOf("product" to product, "quantity" to 0))), null)
        assertEquals(false, refused["accepted"])
        assertEquals(1, api.count("Order", null, null))

        // a back-reference is Velle's to fill
        val claimed = assertFailsWith<ApiException> { api.commit("Order", mapOf("customer" to customer, "placedOn" to "2026-03-01",
            "orderLines" to listOf(mapOf("order" to 1, "product" to product, "quantity" to 1))), null) }
        assertTrue("container" in claimed.message!!, claimed.message)
    }

    @Test
    fun `a one field named in with takes a nested new value, naming its shape when the target is a union`() {
        val api = VelleApi(system(example("batches")), clock)
        val peptide = accepted(api.commit("Peptide", mapOf("code" to "P1"), null)).ref()["key"]!!
        val strain = accepted(api.commit("BacteriaStrain", mapOf("name" to "E. coli"), null)).ref()["key"]!!
        val run = accepted(api.commit("FermenterRun", mapOf("vessel" to "F1", "startedOn" to "2026-03-01"), null)).ref()["key"]!!
        val batch = accepted(api.commit("PeptideBatch", mapOf(
            "peptide" to peptide, "quantityMg" to 100,
            "origin" to mapOf("@shape" to "Fermentation", "strain" to strain, "run" to run),
        ), null))
        val read = api.read("PeptideBatch", batch.ref()["key"]!!, null)
        assertEquals("fermented", read["kind"])
        assertEquals("Fermentation", (read["origin"] as Map<*, *>)["shape"])
        assertEquals(true, read["isFermented"])
        assertEquals(1, api.count("Fermentation", null, null))

        val unnamed = assertFailsWith<ApiException> { api.commit("PeptideBatch", mapOf("peptide" to peptide, "quantityMg" to 1, "origin" to mapOf("strain" to strain, "run" to run)), null) }
        assertTrue("@shape" in unnamed.message!!, unnamed.message)
        // an existing origin by reference names its shape, since the field is union-typed; a bare key cannot
        val fermentation = (read["origin"] as Map<*, *>)["key"]!!
        val bare = assertFailsWith<ApiException> { api.commit("PeptideBatch", mapOf("peptide" to peptide, "quantityMg" to 1, "origin" to fermentation), null) }
        assertTrue("Composition" in bare.message!! && "Fermentation" in bare.message!!, bare.message)
        val claimed = api.commit("PeptideBatch", mapOf("peptide" to peptide, "quantityMg" to 1, "origin" to mapOf("shape" to "Fermentation", "key" to fermentation)), null)
        assertEquals(false, claimed["accepted"]) // a batch never claims another's origin: refused at the door, as data
    }

    private val PLATE = """
        act ContainerType { name: text, wellsPerContainer: integer }

        act ExperimentDesign {
            containerType: one ContainerType
            containerCount: integer
            replicates: integer
        } with { containers with { wells }, conditions }

        shape Condition { design: one ExperimentDesign, block: integer, value: decimal }
        shape Container { design: one ExperimentDesign, replicate: integer, position: integer }
        shape Well { container: one Container, position: integer, condition: one Condition }
    """.trimIndent()

    @Test
    fun `a sibling value is tagged with a number and pointed at by it`() {
        val api = VelleApi(system(PLATE), clock)
        val type = accepted(api.commit("ContainerType", mapOf("name" to "96-well", "wellsPerContainer" to 96), null)).ref()["key"]!!
        accepted(api.commit("ExperimentDesign", mapOf(
            "containerType" to type, "containerCount" to 1, "replicates" to 1,
            "containers" to listOf(mapOf("replicate" to 1, "position" to 1, "wells" to listOf(
                mapOf("position" to 1, "condition" to mapOf("new" to 1)),
                mapOf("position" to 2, "condition" to mapOf("new" to 2)),
            ))),
            "conditions" to listOf(
                mapOf("@new" to 1, "block" to 1, "value" to "0.1"),
                mapOf("@new" to 2, "block" to 2, "value" to 1.0),
            ),
        ), null))
        val wells = api.query("Well", null, listOf("position"), null, null, null).items()
        val conditions = api.query("Condition", null, listOf("block"), null, null, null).items()
        assertEquals(conditions.map { it["key"] }, wells.map { (it["condition"] as Map<*, *>)["key"] })
        assertEquals(BigDecimal("0.1"), conditions[0]["value"])
    }
}
