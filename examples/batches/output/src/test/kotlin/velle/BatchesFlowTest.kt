package velle.generated.batches

import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import velle.Commit
import velle.generated.*

/**
 * Polymorphism over a shared record (Reference §6, §9) driven through the
 * generated typed surface: a batch whose provenance is one of three kinds,
 * spelled as a static union on one field rather than as three subtypes. The
 * business-flow companion to the generated per-rule specs; the things the
 * spelling cannot say are in `BatchesProbesTest`.
 */
open class BatchesFlowTest {

    /** The system under test; the storage-backed variants override this. */
    protected open fun newSystem(): BatchesSystem = BatchesSystem()
    protected val sys: BatchesSystem by lazy { newSystem() }

    private fun accept(r: Commit<*>) = assertIs<Commit.Accepted<*>>(r, "refused: $r")

    private fun peptide(code: String): Peptide {
        accept(sys.commit(NewPeptide(code)))
        return sys.peptides().last()
    }

    private fun fermentation(strain: String = "B. subtilis 168", vessel: String = "F-1"): Fermentation {
        accept(sys.commit(NewBacteriaStrain(strain)))
        accept(sys.commit(NewFermenterRun(vessel, LocalDate.of(2026, 3, 2))))
        accept(sys.commit(NewFermentation(sys.bacteriaStrains().last(), sys.fermenterRuns().last())))
        return sys.fermentations().last()
    }

    private fun order(vendor: String, item: String): ExternalOrder {
        accept(sys.commit(NewVendor(vendor)))
        accept(sys.commit(NewExternalOrder(sys.vendors().last(), item)))
        return sys.externalOrders().last()
    }

    private fun composition(vararg draws: Pair<PeptideBatch, String>): Composition {
        accept(sys.commit(NewComposition(LocalDate.of(2026, 4, 1), draws.map { (b, mg) -> NewComponent(b, BigDecimal(mg)) })))
        return sys.compositions().last()
    }

    private fun batch(peptide: Peptide, mg: String, origin: Origin): PeptideBatch {
        accept(sys.commit(NewPeptideBatch(peptide, BigDecimal(mg), origin.ref)))
        return sys.peptideBatchs().last()
    }

    @Test
    fun `three kinds of provenance through one field, each narrowed to its own data at the surface`() {
        val pep = peptide("PEP-7")
        val fermented = batch(pep, "100", fermentation())
        val ordered = batch(pep, "50", order("Bachem", "PO-4471/3"))
        val mixed = batch(pep, "30", composition(fermented to "20", ordered to "10"))

        // the kind is read off the origin, never stored
        assertEquals(listOf("fermented", "ordered", "composite"), listOf(fermented, ordered, mixed).map { sys.read(it).kind })
        assertEquals("Fermentation", sys.read(fermented).origin.shape)
        assertTrue(sys.read(fermented).isFermented)
        assertTrue(sys.read(ordered).isOrdered)
        assertTrue(sys.read(mixed).isComposite)
        assertEquals(listOf(fermented), sys.fermenteds().map { sys.read(it) })
        assertEquals(listOf(ordered), sys.ordereds().map { sys.read(it) })
        assertEquals(listOf(mixed), sys.composites().map { sys.read(it) })

        // narrowing per operand: each kind's own fields are behind its own type
        val f = assertIs<Fermentation>(sys.read(sys.read(fermented).origin))
        assertEquals("B. subtilis 168", sys.read(f.strain).name)
        assertNull(sys.read(sys.read(fermented).origin) as? ExternalOrder, "a fermentation is not an order")
        val o = assertIs<ExternalOrder>(sys.read(sys.read(ordered).origin))
        assertEquals("PO-4471/3", o.orderItem)
        val c = assertIs<Composition>(sys.read(sys.read(mixed).origin))
        assertEquals(setOf(fermented, ordered), c.components.map { sys.read(it).source }.toSet())
        assertEquals(BigDecimal("30"), c.drawnMg)
    }

    @Test
    fun `one origin yields one batch - the second claim is refused at the door`() {
        val pep = peptide("PEP-7")
        val ferm = fermentation()

        // between the origin's commit and the batch's, the origin is on the
        // books unclaimed: visible as data, not forbiddable (OQ63)
        assertEquals(listOf(ferm.key), sys.unclaimedOrigins().map { it.key })
        assertTrue(sys.read(ferm).isUnclaimedOrigin)

        batch(pep, "100", ferm)
        assertEquals(emptyList(), sys.unclaimedOrigins())
        assertEquals(1, sys.read(ferm).peptideBatches.size)

        val refused = sys.commit(NewPeptideBatch(pep, BigDecimal("5"), ferm.ref))
        assertIs<Commit.Refused<*>>(refused, "a fermentation yields one container")
        assertEquals(1, sys.peptideBatchs().size)
    }

    @Test
    fun `a mixture is of one peptide`() {
        val pep7 = peptide("PEP-7")
        val pep9 = peptide("PEP-9")
        val a = batch(pep7, "100", fermentation())
        val b = batch(pep9, "100", fermentation(strain = "E. coli BL21", vessel = "F-2"))
        val mix = composition(a to "10", b to "10")

        assertIs<Commit.Refused<*>>(sys.commit(NewPeptideBatch(pep7, BigDecimal("20"), mix.ref)), "one component is PEP-9")
        assertIs<Commit.Refused<*>>(sys.commit(NewPeptideBatch(pep9, BigDecimal("20"), mix.ref)), "one component is PEP-7")
        assertEquals(2, sys.peptideBatchs().size)
        // the mixture stays on the books unclaimed — the two-commit arrival's residue
        assertEquals(listOf(mix.key), sys.unclaimedOrigins().map { it.key })
    }

    @Test
    fun `what is left is a derivation over the draws - depletion is noticed once, over-drawing is refused`() {
        val pep = peptide("PEP-7")
        val source = batch(pep, "10", fermentation())
        assertEquals(BigDecimal("10"), sys.read(source).remainingMg)

        batch(pep, "4", composition(source to "4"))
        assertEquals(BigDecimal("4"), sys.read(source).consumedMg)
        assertEquals(BigDecimal("6"), sys.read(source).remainingMg)
        assertEquals(emptyList(), sys.depletionNotices())

        batch(pep, "6", composition(source to "6"))
        assertEquals(BigDecimal("0"), sys.read(source).remainingMg)
        assertTrue(sys.read(source).isDepleted)
        val notice = sys.depletionNotices().map { sys.read(it) }.single()
        assertEquals(source, notice.batch)
        assertEquals("fermented", notice.kind)

        // nothing draws more than a batch holds: the whole closure is refused
        val over = sys.commit(NewComposition(LocalDate.of(2026, 4, 2), listOf(NewComponent(source, BigDecimal("1")))))
        assertIs<Commit.Refused<*>>(over)
        assertEquals(2, sys.compositions().size)
        assertEquals(BigDecimal("0"), sys.read(source).remainingMg)
        assertEquals(1, sys.depletionNotices().size, "depletion was noticed once")
    }
}
