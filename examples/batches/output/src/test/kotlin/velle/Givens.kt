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

    private fun fermentation(): Fermentation {
        sys.commit(NewBacteriaStrain("B. subtilis 168"))
        sys.commit(NewFermenterRun("F-1", LocalDate.of(2026, 3, 2)))
        sys.commit(NewFermentation(sys.bacteriaStrains().last(), sys.fermenterRuns().last()))
        return sys.fermentations().last()
    }

    override fun somePeptide(): Peptide = peptide("PEP-7")

    override fun someOrigin(): Origin = fermentation()

    /** A fermented batch, then one mixture that draws all of it. */
    override fun depleted(): PeptideBatch {
        val pep = peptide("PEP-7")
        sys.commit(NewPeptideBatch(pep, BigDecimal("10"), fermentation().ref))
        val batch = sys.peptideBatchs().last()
        sys.commit(NewComposition(LocalDate.of(2026, 4, 1), listOf(NewComponent(batch, BigDecimal("10")))))
        return batch
    }
}
