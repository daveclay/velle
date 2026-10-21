package velle.generated.batches

import java.math.BigDecimal
import java.time.LocalDate
import velle.generated.*

/**
 * The human-owned scenarios the generated batches specs demand (testgen.md).
 */
class Givens(private val sys: BatchesSystem) : RequiredGivens {

    private fun peptide(code: String): Peptide {
        sys.commit(NewPeptide(code))
        return sys.peptides().last()
    }

    /** A new fermentation, ready to arrive nested in a batch (Reference §6, "A reference supplied by value"). */
    private fun fermentation(): NewFermentation {
        sys.commit(NewBacteriaStrain("B. subtilis 168"))
        sys.commit(NewFermenterRun("F-1", LocalDate.of(2026, 3, 2)))
        return NewFermentation(sys.bacteriaStrains().last(), sys.fermenterRuns().last())
    }

    override fun somePeptide(): Peptide = peptide("PEP-7")

    override fun newOrigin(): NewOrigin = fermentation()

    /** A fermented batch, then a batch mixed from all of it. */
    override fun depleted(): PeptideBatch {
        val pep = peptide("PEP-7")
        sys.commit(NewPeptideBatch(pep, BigDecimal("10"), OriginReference.New(fermentation())))
        val batch = sys.peptideBatchs().last()
        sys.commit(NewPeptideBatch(pep, BigDecimal("10"),
            OriginReference.New(NewComposition(LocalDate.of(2026, 4, 1), listOf(NewComponent(batch, BigDecimal("10")))))))
        return batch
    }
}
