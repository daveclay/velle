package velle.api.scenario

import velle.CommandOutcome
import velle.api.VelleCommand
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `velle try` over `examples/billing/changes/00-billing.velle`, whose amounts are `Money`: each kind of
 * step, each outcome of a commit, and each problem a scenario can have, with the exit code of each.
 */
class TryCommandTest {

    private val billingPath = "../../examples/billing/changes/00-billing.velle"
    private val billing = File(billingPath).readText()

    private fun tried(scenario: String, spec: String = billing): CommandOutcome = TryCommand.runText("billing.velle", spec, "scenario.json", scenario)

    /** Alice signs up, is billed, and is named, the opening every scenario below shares. */
    private val aliceBilled = """
        { "commit": "SignUp", "with": { "name": "Alice", "email": "alice@example.org" } },
        { "find": "Customer", "where": "name == \"Alice\"", "as": "alice" },
        { "commit": "BillCustomer", "with": { "customer": "alice", "due": "2026-01-15" } },
        { "find": "Invoice", "where": "customer.name == \"Alice\"", "as": "invoice" }
    """.trimIndent()

    private fun scenario(vararg steps: String) = "[\n" + (listOf(aliceBilled) + steps).joinToString(",\n") + "\n]"

    @Test
    fun `an accepted commit says so and lists the record its rule created`() {
        val outcome = tried(scenario())

        assertEquals(CommandOutcome.DONE, outcome.exitCode, outcome.text)
        assertTrue(
            "## Step 1: commit SignUp with name \"Alice\", email \"alice@example.org\"\n\n" +
                "Accepted.\n\n" +
                "Created:\n" +
                "- Customer 1: name \"Alice\", email \"alice@example.org\", signedUpOn 2026-01-01T09:00:00Z, largestPayment 0.00\n" in outcome.text,
            outcome.text,
        )
    }

    @Test
    fun `find names the one record a condition holds for, and a later step refers to it by that name`() {
        val outcome = tried(scenario())

        assertTrue("## Step 2: find the Customer where `name == \"Alice\"`, named alice\n\nFound Customer 1 (alice).\n" in outcome.text, outcome.text)
        assertTrue("## Step 3: commit BillCustomer with customer alice, due \"2026-01-15\"\n" in outcome.text, outcome.text)
        assertTrue("- Invoice 2: customer Customer 1 (alice), due 2026-01-15, reference a random identifier," in outcome.text, outcome.text)
    }

    @Test
    fun `a refused commit gives the compiler's sentence for its never, changes nothing, and the steps after it still run`() {
        val outcome = tried(
            scenario(
                """{ "commit": "LineItem", "with": { "invoice": "invoice", "description": "Bits", "price": "5.00", "quantity": 0 } }""",
                """{ "show": "invoice" }""",
            ),
        )

        assertEquals(CommandOutcome.DONE, outcome.exitCode, outcome.text)
        assertTrue(
            "Refused.\n\n" +
                "The prohibition that refused it:\n\n" +
                "- A LineItem is refused when its quantity is at most 0.\n" +
                "  In the spec: billing.velle, line 89, `never (LineItem where quantity <= 0)`\n\n" +
                "The reason the system gave:\n" in outcome.text,
            outcome.text,
        )
        assertTrue("```\n\nNo record was created, changed, or deleted.\n\n## Step 6: show invoice" in outcome.text, outcome.text)
        assertTrue(outcome.text.endsWith("Step 5 was refused. The steps after it still ran, on the records as they stood.\n"), outcome.text)
    }

    @Test
    fun `time passing moves an invoice into a refinement, and a tick runs the rule over it`() {
        val outcome = tried(
            scenario(
                """{ "commit": "LineItem", "with": { "invoice": "invoice", "description": "Drill", "price": "80.00", "quantity": 1 } }""",
                """{ "pass": "21 days" }""",
                """{ "tick": "Weekly" }""",
            ),
        )

        assertTrue(
            "## Step 6: 21 days pass\n\n" +
                "The clock now reads 2026-01-22T09:00:00Z. No rule runs until a commit or a tick.\n\n" +
                "Changed:\n" +
                "- Invoice 2 (invoice): status open to overdue, now an OverdueInvoice, now an ActionableOverdue\n" in outcome.text,
            outcome.text,
        )
        assertTrue(
            "## Step 7: the Weekly tick\n\n" +
                "It ran at 2026-01-22T09:00:00Z.\n\n" +
                "Created:\n" +
                "- Reminder 4: invoice Invoice 2 (invoice), sentOn 2026-01-22\n" in outcome.text,
            outcome.text,
        )
    }

    @Test
    fun `show prints a record's fields and the refinements it is in`() {
        val outcome = tried(
            scenario(
                """{ "commit": "LineItem", "with": { "invoice": "invoice", "description": "Drill", "price": "80.00", "quantity": 1 }, "as": "drill" }""",
                """{ "commit": "SubmitPayment", "with": { "invoice": "invoice", "amount": "80.00" } }""",
                """{ "show": "invoice" }""",
                """{ "show": "Receipt", "where": "invoice.total > 0" }""",
            ),
        )

        assertTrue(
            "## Step 7: show invoice\n\n" +
                "Invoice 2 (invoice):\n\n" +
                "- customer: Customer 1 (alice)\n" +
                "- due: 2026-01-15\n" +
                "- reference: a random identifier\n" +
                "- issuedOn: 2026-01-01T09:00:00Z\n" +
                "- total: 80.00\n" +
                "- balance: 0.00\n" +
                "- status: paid\n" +
                "- lineItems: LineItem 3 (drill)\n" in outcome.text,
            outcome.text,
        )
        assertTrue("- issuances: none\n" in outcome.text, outcome.text)
        assertTrue("\nIt is a PaidInvoice.\n" in outcome.text, outcome.text)
        assertTrue("## Step 8: show each Receipt where `invoice.total > 0`\n\nReceipt 5:\n" in outcome.text, outcome.text)
        assertTrue("\nIt is in none of the refinements of Receipt.\n" in outcome.text, outcome.text)
    }

    @Test
    fun `the same scenario prints the same report on every run`() {
        val steps = scenario("""{ "pass": "3 days" }""", """{ "show": "invoice" }""")

        assertEquals(tried(steps), tried(steps))
    }

    @Test
    fun `by names the actor an earlier step named`() {
        val spec = listOf(
            "visible to Staff",
            "act Hire { name: text }",
            "shape Staff { name: text }",
            "rule AddStaff when Hire { Staff from { name: name } }",
            "act Recall by Staff { reference: text }",
        ).joinToString("\n")
        val outcome = tried(
            """[
                { "commit": "Hire", "with": { "name": "Bob" } },
                { "find": "Staff", "where": "name == \"Bob\"", "as": "bob" },
                { "commit": "Recall", "with": { "reference": "R-1" }, "by": "bob" },
                { "commit": "Recall", "with": { "reference": "R-2" } }
            ]""",
            spec,
        )

        assertTrue("## Step 3: commit Recall with reference \"R-1\", by bob\n\nAccepted.\n" in outcome.text, outcome.text)
        assertTrue(outcome.text.endsWith(stopped("Step 4: `Recall` is made by a Staff; the step needs `by` naming one.")), outcome.text)
    }

    // ── problems with the scenario ───────────────────────────────────────────

    /** The scenario stops, exit 1, with [sentence], "Step 5: ...", as the closing part of its report. */
    private fun assertStops(scenario: String, sentence: String) {
        val outcome = tried(scenario)
        assertEquals(CommandOutcome.REFUSED, outcome.exitCode, outcome.text)
        assertTrue(outcome.text.endsWith(stopped(sentence)), outcome.text)
    }

    private fun stopped(sentence: String): String {
        val stepNumber = sentence.removePrefix("Step ").substringBefore(":")
        return "## The scenario stops at step $stepNumber\n\n${sentence.substringAfter(": ")}\n"
    }

    @Test
    fun `a scenario that is not JSON, not a list, or a step of no kind is refused before anything runs`() {
        assertEquals(CommandOutcome.refused("`scenario.json` cannot be run. The scenario cannot be read: it is not JSON: line 1, column 23, Unexpected end-of-input: expected close marker for Array.\n"), tried("[ { \"tick\": \"Weekly\" }"))
        assertEquals(CommandOutcome.refused("`scenario.json` cannot be run. A scenario is a JSON list of steps, `[ {...}, {...} ]`.\n"), tried("{ \"tick\": \"Weekly\" }"))
        assertEquals(
            CommandOutcome.refused("`scenario.json` cannot be run. Step 2: a step has exactly one of `commit`, `find`, `pass`, `tick`, `show`; this one has none.\n"),
            tried("[ { \"tick\": \"Weekly\" }, { \"wait\": \"8 days\" } ]"),
        )
        assertEquals(
            CommandOutcome.refused("`scenario.json` cannot be run. Step 1: `eight days` is not a duration as a spec writes one, such as \"8 days\" or \"3 hours\".\n"),
            tried("[ { \"pass\": \"eight days\" } ]"),
        )
    }

    @Test
    fun `an unknown act, schedule, shape, or name stops the scenario at its step, after the steps before it`() {
        assertStops(scenario("""{ "commit": "Refund", "with": {} }"""), "Step 5: `Refund` is not an act of `billing.velle`. Its acts are `SignUp`, `CorrectEmail`, `BillCustomer`, `LineItem`, `SubmitPayment`, `Issuance`, `ChangeDueDate`, `ArchiveRequest`, `UnarchiveRequest`.")
        assertStops(scenario("""{ "tick": "Nightly" }"""), "Step 5: `Nightly` is not a schedule of `billing.velle`. Its schedules are `Hourly`, `Weekly`.")
        assertStops(scenario("""{ "show": "Refund", "where": "amount > 0" }"""), "Step 5: `Refund` is not a shape or a refinement of `billing.velle`.")
        assertStops(scenario("""{ "show": "bob" }"""), "Step 5: `bob` is not a name an earlier step gave with `as`. The names given so far are `alice`, `invoice`.")
        assertStops(scenario("""{ "commit": "SubmitPayment", "with": { "invoice": "bills", "amount": "1.00" } }"""), "Step 5: `bills` is not a name an earlier step gave with `as`. The names given so far are `alice`, `invoice`.")
        val outcome = tried(scenario("""{ "tick": "Nightly" }"""))
        assertTrue("## Step 4: find the Invoice" in outcome.text && "## The scenario stops at step 5\n" in outcome.text, outcome.text)
    }

    @Test
    fun `a find that holds for no record or for several stops the scenario`() {
        assertStops(scenario("""{ "find": "Payment", "where": "amount > 0", "as": "payment" }"""), "Step 5: no Payment is one where `amount > 0`, so there is nothing to name `payment`.")
        assertStops(
            scenario(
                """{ "commit": "SignUp", "with": { "name": "Alice", "email": "other@example.org" } }""",
                """{ "find": "Customer", "where": "name == \"Alice\"", "as": "twin" }""",
            ),
            "Step 6: 2 records of Customer are ones where `name == \"Alice\"`: Customer 1 (alice), Customer 3. A `find` names exactly one; narrow the condition.",
        )
    }

    @Test
    fun `a field the act does not have, or a name for a request that is not kept, stops the scenario`() {
        assertStops(scenario("""{ "commit": "SignUp", "with": { "nam": "Bob" } }"""), "Step 5: SignUp.nam: 'SignUp' has no field 'nam' the committer supplies")
        assertStops(scenario("""{ "commit": "SignUp", "with": { "name": "Bob", "email": "b@example.org" }, "as": "bob" }"""), "Step 5: `SignUp` is not kept, so there is no record to name `bob`. A `find` step names a record its rules create.")
    }

    @Test
    fun `the command takes two files and exits 2 otherwise, and a missing file is said`() {
        assertEquals(CommandOutcome.USAGE, VelleCommand.run(listOf("try", billingPath)).exitCode)
        assertEquals(CommandOutcome.refused("There is no file at `nowhere.json`.\n"), VelleCommand.run(listOf("try", billingPath, "nowhere.json")))
        assertEquals(CommandOutcome.DONE, VelleCommand.run(listOf("validate", billingPath)).exitCode)
    }

    @Test
    fun `a draft that does not validate is refused as validate refuses it`() {
        val refused = "../../compiler/src/test/resources/diagnostics/V2-guard-never-made-false.velle"
        val scenario = kotlin.io.path.createTempFile(suffix = ".json").toFile().apply { writeText("[ { \"tick\": \"Weekly\" } ]") }

        assertEquals(velle.Cli.run(listOf("validate", refused)), VelleCommand.run(listOf("try", refused, scenario.path)))
    }
}
