package velle.generated.platelayout

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import velle.Commit
import velle.generated.*

/**
 * Many records from one request (Reference §14, `for each`) driven through the
 * generated typed surface: one design submission lays out containers ×
 * replicates containers and the wells under each, the counts are read off the
 * layout, and a design the books rules refuse leaves nothing behind.
 */
open class PlateLayoutFlowTest {

    /** The system under test; the storage-backed variants override this. */
    protected open fun newSystem(): PlateLayoutSystem = PlateLayoutSystem()
    protected val sys: PlateLayoutSystem by lazy { newSystem() }

    private fun accept(r: Commit<*, *>) = assertIs<Commit.Accepted<*, *>>(r, "refused: $r")

    private fun plate(wells: Int = 96): ContainerType {
        accept(sys.commit(NewContainerType("$wells-well", wells)))
        return sys.containerTypes().last()
    }

    private fun conditions(n: Int) = (1..n).map { NewCondition(block = it, value = BigDecimal(it * 10)) }

    private fun design(type: ContainerType, containers: Int, replicates: Int, conditions: List<NewCondition>, wellsPerCondition: Int): ExperimentDesign {
        accept(sys.commit(NewExperimentDesign(type, containers, replicates, wellsPerCondition, conditions)))
        return sys.experimentDesigns().last()
    }

    @Test
    fun `one submission lays out every container and every well`() {
        val d = design(plate(96), containers = 3, replicates = 2, conditions = conditions(4), wellsPerCondition = 24)

        // containers × replicates containers, one per (replicate, position)
        val containers = sys.containers()
        assertEquals(6, containers.size)
        assertEquals((1..2).flatMap { r -> (1..3).map { r to it } }.toSet(), containers.map { it.replicate to it.position }.toSet())
        assertTrue(containers.all { sys.read(it.design) == d })

        // under each, the container type's wells, one per position, a block per condition
        val byBlock = sys.conditions().associateBy { it.block }
        for (c in containers) {
            val wells = c.wells.map { sys.read(it) }
            assertEquals(96, wells.size)
            assertEquals((1..96).toSet(), wells.map { it.position }.toSet())
            for (b in 1..4) {
                val own = wells.filter { sys.read(it.condition) == byBlock.getValue(b) }
                assertEquals(((b - 1) * 24 + 1..b * 24).toSet(), own.map { it.position }.toSet(), "block $b holds its condition")
            }
        }

        // the counts are derivations over the layout, never stored
        val read = sys.read(d)
        assertEquals(6, read.laidOutContainers)
        assertEquals(576, read.laidOutWells)
        assertEquals(576, sys.wells().size)
    }

    @Test
    fun `a layout whose blocks do not fill the plate is refused whole`() {
        val type = plate(96)
        // five blocks of twenty-four is one hundred and twenty wells on a ninety-six-well plate
        assertIs<Commit.Refused<*, *>>(sys.commit(NewExperimentDesign(type, 1, 1, 24, conditions(5))))
        assertEquals(emptyList(), sys.experimentDesigns())
        assertEquals(emptyList(), sys.conditions(), "the conditions arrived with the design and left with it")
        assertEquals(emptyList(), sys.containers())
        assertEquals(emptyList(), sys.wells())
    }

    @Test
    fun `a design with nothing to test, nothing to lay out, or a block the plate has not, is refused`() {
        val type = plate(96)
        assertIs<Commit.Refused<*, *>>(sys.commit(NewExperimentDesign(type, 1, 1, 96, emptyList())), "no conditions")
        assertIs<Commit.Refused<*, *>>(sys.commit(NewExperimentDesign(type, 1, 0, 96, conditions(1))), "no replicates")
        assertIs<Commit.Refused<*, *>>(sys.commit(NewExperimentDesign(type, 0, 1, 96, conditions(1))), "no containers")
        assertIs<Commit.Refused<*, *>>(sys.commit(NewExperimentDesign(type, 1, 1, 48,
            listOf(NewCondition(block = 1, value = BigDecimal.ONE), NewCondition(block = 5, value = BigDecimal.TEN)))), "block 5 of 2")
        assertEquals(emptyList(), sys.containers())
        assertEquals(emptyList(), sys.wells())
    }

    @Test
    fun `each design is laid out on its own`() {
        val big = plate(96)
        val small = plate(24)
        val first = design(big, containers = 3, replicates = 2, conditions = conditions(4), wellsPerCondition = 24)
        val second = design(small, containers = 1, replicates = 1, conditions = conditions(2), wellsPerCondition = 12)
        assertEquals(6, sys.read(first).laidOutContainers)
        assertEquals(576, sys.read(first).laidOutWells)
        assertEquals(1, sys.read(second).laidOutContainers)
        assertEquals(24, sys.read(second).laidOutWells)
        assertEquals(600, sys.wells().size)
    }
}
