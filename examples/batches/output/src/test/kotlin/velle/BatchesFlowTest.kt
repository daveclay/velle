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
 * spelled as a static union on one field rather than as three subtypes, and
 * arriving with the batch as one commit: `origin` is named in the batch's
 * `with`, so the reference is supplied by value (Reference §6). The
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

    // Each kind of origin is a new value the batch arrives with (`OriginReference.New`);
    // the reference data it points at is committed first, by handle.
    private fun fermentation(strain: String = "B. subtilis 168", vessel: String = "F-1"): OriginReference {
        accept(sys.commit(NewBacteriaStrain(strain)))
        accept(sys.commit(NewFermenterRun(vessel, LocalDate.of(2026, 3, 2))))
        return OriginReference.New(NewFermentation(sys.bacteriaStrains().last(), sys.fermenterRuns().last()))
    }

    private fun order(vendor: String, item: String): OriginReference {
        accept(sys.commit(NewVendor(vendor)))
        return OriginReference.New(NewExternalOrder(sys.vendors().last(), item))
    }

    private fun composition(vararg draws: Pair<PeptideBatch, String>): OriginReference =
        OriginReference.New(NewComposition(LocalDate.of(2026, 4, 1), draws.map { (b, mg) -> NewComponent(b, BigDecimal(mg)) }))

    private fun batch(peptide: Peptide, mg: String, origin: OriginReference): PeptideBatch {
        accept(sys.commit(NewPeptideBatch(peptide, BigDecimal(mg), origin)))
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
    fun `a batch arrives with its origin as one commit, and one origin yields one batch`() {
        val pep = peptide("PEP-7")
        assertEquals(emptyList(), sys.fermentations())

        // the fermentation is created in the batch's own commit: there is no
        // moment when it is on the books without its batch
        val fermented = batch(pep, "100", fermentation())
        val ferm = sys.fermentations().single()
        assertEquals(ferm, sys.read(sys.read(fermented).origin))
        assertEquals(listOf(fermented), sys.read(ferm).peptideBatches.map { sys.read(it) })

        // a second batch naming that fermentation by handle is refused at the door
        val refused = sys.commit(NewPeptideBatch(pep, BigDecimal("5"), OriginReference.Existing(ferm.ref)))
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

        assertIs<Commit.Refused<*>>(sys.commit(NewPeptideBatch(pep7, BigDecimal("20"), mix)), "one component is PEP-9")
        assertIs<Commit.Refused<*>>(sys.commit(NewPeptideBatch(pep9, BigDecimal("20"), mix)), "one component is PEP-7")
        assertEquals(2, sys.peptideBatchs().size)
        // the mixture arrived with the batch, so the refusal leaves nothing behind
        assertEquals(emptyList(), sys.compositions())
        assertEquals(emptyList(), sys.components())
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

        // nothing draws more than a batch holds: the batch, its mixture, and the
        // mixture's components are one closure, refused whole
        val over = sys.commit(NewPeptideBatch(pep, BigDecimal("1"),
            OriginReference.New(NewComposition(LocalDate.of(2026, 4, 2), listOf(NewComponent(source, BigDecimal("1")))))))
        assertIs<Commit.Refused<*>>(over)
        assertEquals(2, sys.compositions().size)
        assertEquals(BigDecimal("0"), sys.read(source).remainingMg)
        assertEquals(1, sys.depletionNotices().size, "depletion was noticed once")
    }
}
