package velle

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `velle describe` falls back to Velle at the smallest part it has no words for, and says the rest:
 * a field (`Member` in `examples/membership/membership.velle`), a line of a rule's body
 * (`RecordCatalogue` in `examples/orders/orders.velle`), and a part of a condition (`ProductInFamily`
 * in `examples/orders/orders.velle`). The comparison of two specs says a field in the same words.
 */
class DescribePartsTest {

    private fun described(path: String, name: String): String = Cli.run(listOf("describe", path, name)).text

    @Test
    fun `a field with no words is shown as Velle on its own, with its line, and the other fields are said`() {
        val membership = "../examples/membership/membership.velle"
        val member = described(membership, "Member").substringAfter("- Member is a record the system keeps.\n")

        assertTrue(
            member.startsWith(
                "  In the spec: $membership, line 38, `shape Member`\n" +
                    "  - Its name is a text.\n" +
                    "  - Its signupEmail is a text.\n" +
                    "  - Its plan is one Plan.\n" +
                    "  - Its referredBy is one Member, and may be absent.\n" +
                    "  - Its joinedOn is the time it was created.\n" +
                    "  - Its lastChangedOn is the time it was last changed.\n" +
                    "  - Its balance is a decimal, starting as 0.\n" +
                    "  - Its lowestBalance is a decimal, starting as 0.\n" +
                    "  - There are no words for this part yet. The Velle, line 47:\n" +
                    "    ```velle\n" +
                    "    visitCount: long initially 0 tolerates duplication\n" +
                    "    ```\n",
            ),
            member,
        )
    }

    @Test
    fun `a body line of a rule with no words is shown as Velle, and the rule's timing is still said`() {
        val orders = "../examples/orders/orders.velle"
        val text = described(orders, "CatalogueUpdate")

        assertTrue(
            "- RecordCatalogue: when a CatalogueUpdate arrives, it does the following.\n" +
                "  In the spec: $orders, line 174, `rule RecordCatalogue when CatalogueUpdate`\n" +
                "  - There are no words for this part yet. The Velle, line 175:\n" +
                "    ```velle\n" +
                "    for each change in changes {\n" in text,
            text,
        )
    }

    @Test
    fun `a part of a condition with no words is shown as Velle under the sentence that opens it`() {
        val orders = "../examples/orders/orders.velle"
        val text = described(orders, "Product")

        assertTrue(
            "- A Product is a ProductInFamily when this holds:\n" +
                "  In the spec: $orders, line 40, `shape ProductInFamily = Product where exists (SkuFamily where this.sku matches skuPattern)`\n" +
                "  - There are no words for this part yet. The Velle, line 40:\n" +
                "    ```velle\n" +
                "    exists (SkuFamily where this.sku matches skuPattern)\n" +
                "    ```\n" in text,
            text,
        )
    }

    @Test
    fun `who a record and its fields are shown to is said`() {
        val vendors = "../examples/vendors/vendors.velle"
        val text = described(vendors, "Product")

        assertTrue("- A record is shown to every Staff, unless its shape says who it is shown to.\n" in Cli.run(listOf("describe", vendors)).text)
        assertTrue(
            "  - Its cost is a decimal. Of those it is shown to, only an Administrator is shown its cost.\n" +
                "  - Its margin is worked out as its price minus its cost. Of those it is shown to, only an Administrator is shown its margin.\n" +
                "  - It is shown to a Staff when there is a VendorAssignment whose staff is that Staff and whose vendor is its vendor.\n" +
                "  - It is shown to every Administrator.\n" in text,
            text,
        )
    }

    @Test
    fun `the parts an act arrives with are said, for a collection and for a one field`() {
        val orders = described("../examples/orders/orders.velle", "Order")
        val batches = described("../examples/batches/batches.velle", "PeptideBatch")

        assertTrue("  - Its orderLines can arrive with it as new records in the same request, each able to bring its customizations the same way.\n" in orders, orders)
        assertTrue(
            "  - Its origin can arrive with it as a new record in the same request, in place of naming one already kept, " +
                "and an origin arriving that way can bring its components the same way.\n" in batches,
            batches,
        )
    }

    @Test
    fun `a rule's for each block and a record it names with as are said`() {
        val text = described("../examples/orders/orders.velle", "PriceRequest")

        assertTrue(
            "- QuoteRequest: when a PriceRequest arrives, it creates a Quote with customer set to its customer, " +
                "then creates a QuoteLine with quote set to the new Quote, product set to the item's product, quantity set to the item's quantity, " +
                "unitPrice set to the item's product's unitPrice, for each item of its requestedItems.\n" in text,
            text,
        )
    }

    @Test
    fun `is held and a collection named with as are said`() {
        val closeAccount = described("../examples/moderation/moderation.velle", "CloseAccount")
        val booking = described("../examples/bookings/bookings.velle", "StandingBooking")

        assertTrue("- A CloseAccount is refused when a record points at its seller through a link that cannot be left empty.\n" in closeAccount, closeAccount)
        assertTrue("- A StandingBooking is forbidden when there is a StandingBooking whose slot is its slot and that is not it.\n" in booking, booking)
    }

    @Test
    fun `a rule that reads who made the request shows that clause as Velle and says the rest`() {
        val spec = kotlin.io.path.createTempFile(suffix = ".velle").toFile()
        spec.writeText(
            listOf(
                "visible to Staff",
                "shape Staff { name: text }",
                "act Recall by Staff { reference: text }",
                "shape Quarantine { recall: one Recall }",
                "rule QuarantineRecalled when Recall { Quarantine from { recall: this } }",
                "shape QuarantineLog { quarantine: one Quarantine  loggedBy: one Staff? }",
                "rule LogQuarantine when Quarantine by Staff? {",
                "    QuarantineLog from { quarantine: this, loggedBy: actor }",
                "}",
            ).joinToString("\n"),
        )
        assertEquals(CommandOutcome.DONE, Cli.run(listOf("validate", spec.path)).exitCode, Cli.run(listOf("validate", spec.path)).text)

        val text = described(spec.path, "Quarantine")

        assertTrue(
            "- LogQuarantine: when a Quarantine is created, it does the following.\n" +
                "  In the spec: ${spec.path}, line 7, `rule LogQuarantine when Quarantine by Staff?`\n" +
                "  - There are no words for this part yet. The Velle, line 7:\n" +
                "    ```velle\n" +
                "    by Staff?\n" +
                "    ```\n" +
                "  - Creates a QuarantineLog with quarantine set to it, loggedBy set to actor.\n" in text,
            text,
        )
        assertTrue(
            "## What rules over other records do to Quarantine\n\n### Recall\n\n- QuarantineRecalled: when a Recall arrives, it creates a Quarantine with recall set to it." in text,
            text,
        )
    }

    @Test
    fun `the record a prohibition is over is not said again in its condition`() {
        val gallery = "../examples/gallery/gallery.velle"
        val text = Cli.run(listOf("describe", gallery)).text

        assertTrue("- A Content is refused when its creditLine is present and its source is absent.\n" in text, text)
    }

    @Test
    fun `the comparison of two specs says a changed if-then-else field in the words describe uses`() {
        val billing = File("../examples/billing/billing.velle").readText()
        val statusLine = "    status: Status = if balance <= 0 then \"paid\" else if due < today then \"overdue\" else \"open\""
        assertTrue(statusLine in billing, "billing.velle no longer holds: $statusLine")
        val edited = billing.replace(statusLine, "    status: Status = if balance <= 0 then \"paid\" else \"open\"")
        assertEquals(emptyList(), Validator.validate(edited), "the edited spec still validates")

        val report = SpecComparison.report(SpecVersion("before.velle", billing), SpecVersion("after.velle", edited))

        assertTrue(
            "- Invoice's field is now status (worked out as \"paid\" when its balance is at most 0, otherwise \"open\"). " +
                "Before: status (worked out as \"paid\" when its balance is at most 0, otherwise \"overdue\" when its due is before today, otherwise \"open\")." in report,
            report,
        )
    }
}
