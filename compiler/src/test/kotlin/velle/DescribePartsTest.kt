package velle

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `velle describe` falls back to Velle at the smallest part it has no words for, and says the rest:
 * a field (`Product` in `examples/vendors/vendors.velle`), a line of a rule's body (`QuoteRequest`
 * in `examples/orders/orders.velle`), and a part of a condition (`CloseAccount`'s prohibition in
 * `examples/moderation/moderation.velle`). The words it adds for that are its own: the comparison of
 * two specs still shows a changed `if ... then ... else` field as Velle.
 */
class DescribePartsTest {

    private fun described(path: String, name: String): String = Cli.run(listOf("describe", path, name)).text

    @Test
    fun `a field with no words is shown as Velle on its own, with its line, and the other fields are said`() {
        val vendors = "../examples/vendors/vendors.velle"
        val product = described(vendors, "Product").substringAfter("- Product is a record the system keeps.\n")

        assertTrue(
            product.startsWith(
                "  In the spec: $vendors, line 81, `shape Product`\n" +
                    "  - Its vendor is one Vendor.\n" +
                    "  - Its name is a text.\n" +
                    "  - Its price is a decimal.\n" +
                    "  - There are no words for this part yet. The Velle, line 85:\n" +
                    "    ```velle\n" +
                    "    cost: decimal visible to Administrator\n" +
                    "    ```\n",
            ),
            product,
        )
    }

    @Test
    fun `a body line of a rule with no words is shown as Velle, and the rule's timing is still said`() {
        val orders = "../examples/orders/orders.velle"
        val text = described(orders, "PriceRequest")

        assertTrue(
            "- QuoteRequest: when a PriceRequest arrives, it does the following.\n" +
                "  In the spec: $orders, line 120, `rule QuoteRequest when PriceRequest`\n" +
                "  - There are no words for this part yet. The Velle, line 121:\n" +
                "    ```velle\n" +
                "    Quote from { customer: customer } as quote\n" +
                "    ```\n" +
                "  - There are no words for this part yet. The Velle, line 122:\n" +
                "    ```velle\n" +
                "    for each item in requestedItems {\n" in text,
            text,
        )
    }

    @Test
    fun `a part of a condition with no words is shown as Velle under the sentence that opens it`() {
        val moderation = "../examples/moderation/moderation.velle"
        val text = described(moderation, "CloseAccount")

        assertTrue(
            "- A CloseAccount is refused when this holds:\n" +
                "  In the spec: $moderation, line 221, `never (CloseAccount where seller is held)`\n" +
                "  - There are no words for this part yet. The Velle, line 221:\n" +
                "    ```velle\n" +
                "    seller is held\n" +
                "    ```\n" in text,
            text,
        )
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
    fun `the comparison of two specs keeps its own words, so a changed if-then-else field is still shown as Velle`() {
        val billing = File("../examples/billing/billing.velle").readText()
        val statusLine = "    status: Status = if balance <= 0 then \"paid\" else if due < today then \"overdue\" else \"open\""
        assertTrue(statusLine in billing, "billing.velle no longer holds: $statusLine")
        val edited = billing.replace(statusLine, "    status: Status = if balance <= 0 then \"paid\" else \"open\"")
        assertEquals(emptyList(), Validator.validate(edited), "the edited spec still validates")

        val report = SpecComparison.report(SpecVersion("before.velle", billing), SpecVersion("after.velle", edited))

        assertTrue("- Invoice changed, and this report has no words for it yet. The Velle:" in report, report)
    }
}
