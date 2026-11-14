package velle.api.graphql

import graphql.ExecutionInput
import graphql.GraphQL
import graphql.schema.idl.SchemaPrinter
import velle.Model
import velle.Parser
import velle.Ref
import velle.Validator
import velle.VelleSystem
import velle.api.VelleApi
import java.io.File
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The schema built from the example specs, exercised end to end through graphql-java in memory. */
class VelleGraphQLTest {

    private val day = Instant.parse("2026-03-10T12:00:00Z")

    private fun system(spec: String): VelleSystem {
        val diags = Validator.validate(spec)
        check(diags.none { !it.advisory }) { diags.toString() }
        return VelleSystem(Model(Parser.parse(spec)), startTime = day)
    }

    private fun example(name: String) = File("../../examples/$name/$name.velle").readText()

    private class Engine(val graphQL: GraphQL, val sys: VelleSystem) {
        @Suppress("UNCHECKED_CAST")
        fun run(query: String, variables: Map<String, Any?> = emptyMap(), actor: Ref<*>? = null): Map<String, Any?> {
            val input = ExecutionInput.newExecutionInput(query).variables(variables)
            if (actor != null) input.graphQLContext(mapOf(VelleGraphQL.ACTOR to actor))
            val r = graphQL.execute(input.build())
            check(r.errors.isEmpty()) { r.errors.toString() }
            return r.getData<Map<String, Any?>>()
        }
        fun errors(query: String, variables: Map<String, Any?> = emptyMap(), actor: Ref<*>? = null) =
            graphQL.execute(ExecutionInput.newExecutionInput(query).variables(variables).also { if (actor != null) it.graphQLContext(mapOf(VelleGraphQL.ACTOR to actor)) }.build()).errors
    }

    private fun engine(name: String, ticks: Boolean = false): Engine {
        val sys = system(example(name))
        return Engine(VelleGraphQL.graphQL(VelleApi(sys, Clock.fixed(day, ZoneOffset.UTC)), ticks), sys)
    }

    private fun sdl(name: String, ticks: Boolean = false) =
        SchemaPrinter().print(VelleGraphQL.schema(VelleApi(system(example(name)), null), ticks))

    /** The body of `input <name> { ... }` in [sdl], with each field on its own line. */
    private fun inputType(sdl: String, name: String) = block(sdl, "input $name")

    /** The body of `type <name> { ... }` in [sdl], with each field on its own line. */
    private fun objectType(sdl: String, name: String) = block(sdl, "type $name")

    private fun block(sdl: String, header: String): String {
        assertTrue("\n$header {" in sdl, "no $header in\n$sdl")
        return sdl.substringAfter("\n$header {").substringBefore("\n}") + "\n"
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.at(vararg path: String): Any? = path.fold(this as Any?) { acc, k -> (acc as Map<String, Any?>)[k] }

    @Test
    fun `the schema has a type per shape, a page per root, a mutation per act, and the scalars the spec needs`() {
        val sdl = SchemaPrinter().print(VelleGraphQL.schema(VelleApi(system(example("billing")), null), ticks = true))
        assertTrue("type Invoice {" in sdl, sdl)
        assertTrue("total: Money!" in sdl, sdl)
        assertTrue("due: Date!" in sdl, sdl)
        assertTrue("lineItems: [LineItem!]!" in sdl, sdl)
        assertTrue("isPaidInvoice: Boolean!" in sdl, sdl)
        assertTrue("type PaidInvoice {" in sdl, sdl)
        assertTrue("invoices(after: String, first: Int, orderBy: [String!]!, where: String): InvoicePage!" in sdl, sdl)
        assertTrue("signUp(input: SignUpInput!): SignUpOutcome!" in sdl, sdl)
        assertTrue("input LineItemInput {" in sdl && "invoice: ID!" in sdl && "price: Money!" in sdl, sdl)
        assertTrue("tick(schedule: String!): TickOutcome!" in sdl, sdl)
        assertTrue("scalar Money" in sdl, sdl)
        assertTrue("type SignUp {" !in sdl && "type SignUpPage" !in sdl, "a transient act is not readable")
        assertTrue("record: LineItem" in sdl, sdl)
    }

    @Test
    fun `billing through the schema`() {
        val e = engine("billing", ticks = true)
        val signUp = e.run("""mutation { signUp(input: {name: "Ada", email: "ada@example.com"}) { accepted ref { shape key } } }""")
        assertEquals(true, signUp.at("signUp", "accepted"))
        assertNull(signUp.at("signUp", "ref"))

        val customers = e.run("""{ customers(orderBy: ["name"]) { items { key name largestPayment } next } }""")
        @Suppress("UNCHECKED_CAST")
        val ada = (customers.at("customers", "items") as List<Map<String, Any?>>).single()
        assertEquals("Ada", ada["name"])
        assertEquals("0.00", ada["largestPayment"])

        e.run("""mutation(${'$'}c: ID!) { billCustomer(input: {customer: ${'$'}c, due: "2026-04-01"}) { accepted } }""", mapOf("c" to ada["key"]))
        @Suppress("UNCHECKED_CAST")
        val invoice = (e.run("""{ invoices(orderBy: ["due"]) { items { key due status customer { name } } } }""").at("invoices", "items") as List<Map<String, Any?>>).single()
        assertEquals("paid", invoice["status"]) // nothing billed yet, nothing owed
        assertEquals("Ada", invoice.at("customer", "name"))

        // a Money as a number literal and as a string variable; the record read back in the outcome
        val li = e.run("""mutation(${'$'}i: ID!, ${'$'}p: Money!) {
            a: lineItem(input: {invoice: ${'$'}i, description: "Design", price: ${'$'}p, quantity: 1}) { accepted record { amount quantity } }
            b: lineItem(input: {invoice: ${'$'}i, description: "Hosting", price: 50, quantity: 2}) { accepted ref { shape } }
        }""", mapOf("i" to invoice["key"], "p" to "1200.00"))
        assertEquals("1200.00", li.at("a", "record", "amount"))
        assertEquals(1, li.at("a", "record", "quantity"))
        assertEquals("LineItem", li.at("b", "ref", "shape"))

        val read = e.run("""query(${'$'}k: ID!) { invoice(key: ${'$'}k) { total balance status isPaidInvoice lineItems { description amount } } }""", mapOf("k" to invoice["key"]))
        assertEquals("1300.00", read.at("invoice", "total"))
        assertEquals("open", read.at("invoice", "status"))
        assertEquals(2, (read.at("invoice", "lineItems") as List<*>).size)
        assertEquals(false, read.at("invoice", "isPaidInvoice"))

        // a refusal is data in the outcome, not an error
        val zero = e.run("""mutation(${'$'}i: ID!) { submitPayment(input: {invoice: ${'$'}i, amount: 0}) { accepted reason rejection { targetShape message violating { shape fields } } } }""", mapOf("i" to invoice["key"]))
        assertEquals(false, zero.at("submitPayment", "accepted"))
        assertEquals("SubmitPayment", zero.at("submitPayment", "rejection", "targetShape"))

        // a malformed value is a coercion error before anything runs
        val bad = e.errors("""mutation(${'$'}i: ID!) { submitPayment(input: {invoice: ${'$'}i, amount: "ten"}) { accepted } }""", mapOf("i" to invoice["key"]))
        assertTrue(bad.isNotEmpty() && bad.first().message.contains("ten"), bad.toString())

        e.run("""mutation(${'$'}i: ID!) { submitPayment(input: {invoice: ${'$'}i, amount: "1300.00"}) { accepted } }""", mapOf("i" to invoice["key"]))
        val paid = e.run("""query(${'$'}k: ID!) { invoice(key: ${'$'}k) { status isPaidInvoice } paidInvoiceCount receipts(orderBy: ["sentOn"]) { items { invoice { status } } } }""", mapOf("k" to invoice["key"]))
        assertEquals("paid", paid.at("invoice", "status"))
        assertEquals(1, paid["paidInvoiceCount"])
        assertEquals("paid", (paid.at("receipts", "items") as List<*>).single().let { (it as Map<*, *>)["invoice"] as Map<*, *> }["status"])

        // the predicate language in `where`, and a cursor across pages
        e.run("""mutation(${'$'}c: ID!) { a: billCustomer(input: {customer: ${'$'}c, due: "2026-01-01"}) { accepted } b: billCustomer(input: {customer: ${'$'}c, due: "2026-02-01"}) { accepted } }""", mapOf("c" to ada["key"]))
        val p1 = e.run("""{ invoices(where: "due < today", orderBy: ["-due"], first: 1) { items { due } next } }""")
        assertEquals(listOf("2026-02-01"), (p1.at("invoices", "items") as List<*>).map { (it as Map<*, *>)["due"] })
        val cursor = assertNotNull(p1.at("invoices", "next") as String?)
        val p2 = e.run("""query(${'$'}a: String) { invoices(where: "due < today", orderBy: ["-due"], first: 1, after: ${'$'}a) { items { due } next } }""", mapOf("a" to cursor))
        assertEquals(listOf("2026-01-01"), (p2.at("invoices", "items") as List<*>).map { (it as Map<*, *>)["due"] })
        assertNull(p2.at("invoices", "next"))

        // a refused query is a GraphQL error carrying its kind
        val refused = e.errors("""{ invoices(where: "colour == 1", orderBy: ["due"]) { items { due } } }""")
        assertEquals("BAD_REQUEST", refused.single().extensions["kind"])
        assertNull(e.run("""{ invoice(key: "999") { due } }""")["invoice"])

        val tick = e.run("""mutation { tick(schedule: "Weekly") { schedule rejections { message } } }""")
        assertEquals("Weekly", tick.at("tick", "schedule"))
    }

    @Test
    fun `the actor in the context commits and views`() {
        val e = engine("vendors")
        val admin = e.sys.seed("Staff", mapOf("name" to "Grace", "role" to "admin"))
        val staff = e.sys.seed("Staff", mapOf("name" to "Linus", "role" to "staff"))
        val vendor = e.sys.seed("Vendor", mapOf("name" to "Acme"))

        val anonymous = e.errors("""{ vendors(orderBy: ["name"]) { items { name } } }""")
        assertEquals("UNAUTHORIZED", anonymous.single().extensions["kind"])

        e.run("""mutation(${'$'}v: ID!, ${'$'}s: ID!) {
            listProduct(input: {vendor: ${'$'}v, name: "Widget", price: 10, cost: 4}) { accepted }
            assignVendor(input: {vendor: ${'$'}v, staff: ${'$'}s}) { accepted }
        }""", mapOf("v" to vendor.key.value, "s" to staff.key.value), actor = admin)

        val q = """{ products(orderBy: ["name"]) { items { key name price cost margin vendor { name } } } }"""
        @Suppress("UNCHECKED_CAST")
        val asAdmin = (e.run(q, actor = admin).at("products", "items") as List<Map<String, Any?>>).single()
        assertEquals(BigDecimal(4), asAdmin["cost"])
        @Suppress("UNCHECKED_CAST")
        val asStaff = (e.run(q, actor = staff).at("products", "items") as List<Map<String, Any?>>).single()
        assertNull(asStaff["cost"])
        assertNull(asStaff["margin"])
        assertEquals("Acme", asStaff.at("vendor", "name"))

        val gated = e.run("""mutation(${'$'}v: ID!) { listProduct(input: {vendor: ${'$'}v, name: "Gadget", price: 1, cost: 1}) { accepted reason } }""", mapOf("v" to vendor.key.value), actor = staff)
        assertEquals(false, gated.at("listProduct", "accepted"))
    }

    @Test
    fun `parts and a value supplied by value, through input types`() {
        val o = engine("orders")
        val c = o.run("""mutation { customer(input: {name: "Ada"}) { ref { key } } product(input: {sku: "A-1", unitPrice: 10}) { ref { key } } }""")
        val order = o.run("""mutation(${'$'}c: ID!, ${'$'}p: ID!) { order(input: {customer: ${'$'}c, placedOn: "2026-03-01", cardNumber: "4111111111111111", cardholder: "Ada Lovelace", orderLines: [
            {product: ${'$'}p, quantity: 2, customizations: [{note: "gift wrap"}]}, {product: ${'$'}p, quantity: 3}
        ]}) { accepted record { total orderLines { quantity customizations { note } } } } }""", mapOf("c" to c.at("customer", "ref", "key"), "p" to c.at("product", "ref", "key")))
        assertEquals(5, order.at("order", "record", "total"))
        assertEquals(2, (order.at("order", "record", "orderLines") as List<*>).size)

        val b = engine("batches")
        val refs = b.run("""mutation { peptide(input: {code: "P1"}) { ref { key } } bacteriaStrain(input: {name: "E. coli"}) { ref { key } } fermenterRun(input: {vessel: "F1", startedOn: "2026-03-01"}) { ref { key } } }""")
        val batch = b.run("""mutation(${'$'}p: ID!, ${'$'}s: ID!, ${'$'}r: ID!) { peptideBatch(input: {peptide: ${'$'}p, quantityMg: 100, origin: {newFermentation: {strain: ${'$'}s, run: ${'$'}r}}}) {
            accepted record { kind origin { __typename ... on Fermentation { strain { name } } } }
        } }""", mapOf("p" to refs.at("peptide", "ref", "key"), "s" to refs.at("bacteriaStrain", "ref", "key"), "r" to refs.at("fermenterRun", "ref", "key")))
        assertEquals("fermented", batch.at("peptideBatch", "record", "kind"))
        assertEquals("Fermentation", batch.at("peptideBatch", "record", "origin", "__typename"))
        assertEquals("E. coli", batch.at("peptideBatch", "record", "origin", "strain", "name"))
        val both = b.errors("""mutation(${'$'}p: ID!) { peptideBatch(input: {peptide: ${'$'}p, quantityMg: 1, origin: {existing: {shape: "Fermentation", key: "1"}, newExternalOrder: {vendor: "1", orderItem: "x"}}}) { accepted } }""", mapOf("p" to refs.at("peptide", "ref", "key")))
        assertEquals("BAD_REQUEST", both.single().extensions["kind"])
    }

    @Test
    fun `an input field is nullable when its type is optional or many, or it is a part or a value created with the act`() {
        val student = inputType(sdl("enrollment"), "StudentInput")
        assertTrue("\n  name: String!\n" in student, student)
        assertTrue("\n  advisor: ID\n" in student, "an optional one is nullable: $student")
        assertTrue("\n  courses: [ID!]\n" in student, "a many reference is nullable: $student")
        assertTrue("\n  tags: [String!]\n" in student, "a many value is nullable: $student")
        val course = inputType(sdl("enrollment"), "CourseInput")
        assertTrue("\n  startsAt: Time\n" in course, "an optional value is nullable: $course")
        assertTrue("\n  term: Duration!\n" in course, course)

        val order = inputType(sdl("orders"), "OrderInput")
        assertTrue("\n  orderLines: [OrderOrderLineInput!]\n" in order, "parts are nullable: $order")
        val batch = inputType(sdl("batches"), "PeptideBatchInput")
        assertTrue("\n  origin: PeptideBatchOriginInput\n" in batch, "a value created with the act is nullable: $batch")
    }

    @Test
    fun `an optional field that is initially required is non-null in the input, and an initially-valued field is left out`() {
        val listing = inputType(sdl("moderation"), "ListingInput")
        assertTrue("\n  seller: ID!\n" in listing, listing)
        assertTrue("isDraft" !in listing, listing)
    }

    @Test
    fun `a part's input leaves out the reference back to its container`() {
        val line = inputType(sdl("orders"), "OrderOrderLineInput")
        assertTrue("order:" !in line, line)
        assertTrue("\n  product: ID!\n" in line, line)
        val customization = inputType(sdl("orders"), "OrderCustomizationInput")
        assertTrue("line:" !in customization, customization)
    }

    @Test
    fun `a reference to a union of shapes is a RefInput, a reference to one shape is an ID`() {
        val comment = inputType(sdl("gallery"), "CommentInput")
        assertTrue("\n  target: RefInput!\n" in comment, "Content is Post or Photo: $comment")
        assertTrue("\n  author: ID!\n" in comment, comment)
        val task = inputType(sdl("peptides"), "CollectionTaskInput")
        assertTrue("\n  trial: RefInput!\n" in task, "the trial may be a record created with the task: $task")
    }

    @Test
    fun `only a nested input has asNew`() {
        assertTrue("asNew" !in inputType(sdl("billing"), "SignUpInput"))
        assertTrue("\n  asNew: Int\n" in inputType(sdl("orders"), "OrderOrderLineInput"))
    }

    @Test
    fun `a record type has no id and no transient field`() {
        val order = objectType(sdl("orders"), "Order")
        assertTrue("\n  id:" !in order, order)
        assertTrue("cardNumber" !in order && "cardholder" !in order, order)
        assertTrue("\n  placedOn: Date!\n" in order, order)
    }

    @Test
    fun `a member named key or shape is served as key_ or shape_`() {
        val sys = system("""
            act Entry {
                key: text
                label: text
            }
        """)
        val graphQL = VelleGraphQL.graphQL(VelleApi(sys, Clock.fixed(day, ZoneOffset.UTC)))
        assertTrue("\n  key_: String!\n" in objectType(SchemaPrinter().print(graphQL.graphQLSchema), "Entry"))
        val e = Engine(graphQL, sys)
        val entry = e.run("""mutation { entry(input: {key: "k-1", label: "first"}) { record { key key_ label } } }""")
        assertEquals("k-1", entry.at("entry", "record", "key_"))
        assertNotNull(entry.at("entry", "record", "key"))
    }

    @Test
    fun `a reference to a record the actor may not see reads as absent, not as a refusal`() {
        val e = engine("vendors")
        val admin = e.sys.seed("Staff", mapOf("name" to "Grace", "role" to "admin"))
        val staff = e.sys.seed("Staff", mapOf("name" to "Linus", "role" to "staff"))
        val globex = e.sys.seed("Vendor", mapOf("name" to "Globex"))
        e.run("""mutation(${'$'}v: ID!) { listProduct(input: {vendor: ${'$'}v, name: "Thin", price: 1, cost: 1}) { accepted } }""", mapOf("v" to globex.key.value), actor = admin)
        val query = """{ marginAlerts(orderBy: ["raisedOn"]) { items { product { name } } } }"""
        assertEquals("Thin", (e.run(query, actor = admin).at("marginAlerts", "items") as List<*>).single().let { (it as Map<*, *>)["product"] as Map<*, *> }["name"])
        // the product is absent for the staff member; the field is non-null, so GraphQL reports that and nulls the item
        val asStaff = e.errors(query, actor = staff).single()
        assertNull(asStaff.extensions?.get("kind"), asStaff.toString())
        assertTrue("non null" in asStaff.message, asStaff.toString())
    }

    @Test
    fun `without ticks there is no tick mutation`() {
        val api = VelleApi(system(example("billing")), null)
        assertNull(VelleGraphQL.schema(api).mutationType.getFieldDefinition("tick"))
        assertNull(VelleGraphQL.graphQL(api).graphQLSchema.mutationType.getFieldDefinition("tick"))
        assertNotNull(VelleGraphQL.graphQL(api, ticks = true).graphQLSchema.mutationType.getFieldDefinition("tick"))
    }
}
