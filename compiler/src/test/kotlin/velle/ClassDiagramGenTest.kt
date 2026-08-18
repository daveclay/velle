package velle

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClassDiagramGenTest {

    private val doc = DiagramGen.generate(File("../examples/billing/billing.velle").readText(), "Billing")
    private val diagram = doc.substringAfter("## Class diagram").substringBefore("## State flow")

    @Test
    fun `shapes render as classes with expose annotations`() {
        assertTrue("class Invoice {" in diagram)
        assertTrue("<<expose>>" in diagram)
        assertTrue("class ChangeDueDate {\n        <<expose transient>>" in diagram)
        assertFalse("class Receipt {\n        <<expose>>" in diagram, "Receipt is not exposed")
    }

    @Test
    fun `derived properties carry the UML slash, not their expressions`() {
        assertTrue("/total: decimal" in diagram)
        assertTrue("/balance: decimal" in diagram)
        assertFalse("sum(" in diagram, "derivation expressions stay out of the class boxes")
    }

    @Test
    fun `relationships are edges labeled with field and inferred inverse`() {
        assertTrue("LineItem \"*\" --> \"1\" Invoice : invoice (inverse lineItems)" in diagram)
        // a transient act's relationship gets no inverse — the instances are not kept
        assertTrue("ChangeDueDate \"*\" --> \"1\" Invoice : invoice\n" in diagram)
        assertFalse("changeDueDates" in diagram)
    }

    @Test
    fun `refinements attach to their base with predicate notes and body members`() {
        assertTrue("Invoice <|-- PaidInvoice" in diagram)
        assertTrue("note for PaidInvoice \"= Invoice where (total > 0) and (balance <= 0)\"" in diagram)
        assertTrue("frozen due" in diagram)
        assertTrue("/archivedOn: Date captured at entry" in diagram)
    }

    @Test
    fun `composed refinements point at their operands with dashed edges`() {
        assertTrue("ActionableOverdue ..> OverdueInvoice" in diagram)
        assertTrue("ActionableOverdue ..> ArchivedInvoice" in diagram)
    }
}
