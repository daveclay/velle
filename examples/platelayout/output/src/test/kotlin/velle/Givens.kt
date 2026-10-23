package velle.generated.platelayout

import java.math.BigDecimal
import velle.generated.*

/**
 * The human-owned scenarios the generated plate-layout specs demand (testgen.md).
 */
class Givens(private val sys: PlateLayoutSystem) : RequiredGivens {

    /** A ninety-six-well plate type, three containers in two replicates, four conditions of twenty-four wells. */
    override fun experimentDesign(): ExperimentDesign {
        sys.commit(NewContainerType("96-well", 96))
        val type = sys.containerTypes().last()
        sys.commit(NewExperimentDesign(
            containerType = type, containerCount = 3, replicates = 2, wellsPerCondition = 24,
            conditions = (1..4).map { NewCondition(block = it, value = BigDecimal(it * 10)) },
        ))
        return sys.experimentDesigns().last()
    }
}
