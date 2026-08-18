package velle

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiagramGenTest {

    private val doc = DiagramGen.generate(File("../examples/billing/billing.velle").readText(), "Billing")

    @Test
    fun `one mermaid diagram per exposed act`() {
        val model = Model(Parser.parse(File("../examples/billing/billing.velle").readText()))
        for (act in model.exposed) assertTrue("## commit$act" in doc, "missing section for $act")
        assertEquals(model.exposed.size, Regex("```mermaid").findAll(doc).count())
        assertEquals(model.exposed.size, Regex("sequenceDiagram").findAll(doc).count())
    }

    @Test
    fun `payment cascade shows in-envelope firings, the boundary, and the backstop`() {
        val payment = doc.substringAfter("## commitPayment").substringBefore("\n## ")
        // in-envelope: the fold and the receipt share the payment's transaction
        assertTrue("TrackLargestPayment" in payment)
        assertTrue("SendReceipt" in payment)
        assertTrue("insert Receipt" in payment)
        // the declared boundary: EmailReceipt is an async arrow into its own frame
        assertTrue("after commit — EmailReceipt begins its own transaction" in payment)
        assertTrue("insert ReceiptEmail" in payment)
        assertTrue("backstop — every Hourly tick" in payment)
        // the boundary never on payments guards this envelope
        assertTrue("never Payment where amount <= 0" in payment)
    }

    @Test
    fun `transient act notes removal and shows both partitions`() {
        val change = doc.substringAfter("## commitChangeDueDate").substringBefore("\n## ")
        assertTrue("transient act" in change)
        assertTrue("ApplyDueChange" in change)
        assertTrue("RecordDueChangeRefusal" in change)
    }

    @Test
    fun `tick-only rules the commit affects appear as tick notes`() {
        val lineItem = doc.substringAfter("## commitLineItem").substringBefore("\n## ")
        assertTrue("RemindOverdue" in lineItem, "a LineItem commit affects OverdueInvoice's balance read")
        assertTrue("Weekly tick" in lineItem)
    }

    @Test
    fun `mermaid blocks are balanced`() {
        for (block in doc.split("```mermaid").drop(1).map { it.substringBefore("```") }) {
            val opens = block.lines().count { it.trim().startsWith("critical ") || it.trim().startsWith("loop ") }
            val ends = block.lines().count { it.trim() == "end" }
            assertEquals(opens, ends, "unbalanced frames in:\n$block")
        }
    }
}
