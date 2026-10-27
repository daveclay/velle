package velle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `for each <name> in <source>, ... { statements }` (Reference §14; OQ64): a
 * block in a rule body that creates one record per element of each source,
 * nested for a product, with a range `from 1 to n` as the one place a number becomes
 * a set — parsing and printing, V34's refusals, and the runtime laying out
 * the whole product in one firing.
 */
class ForEachTest {

    private val PLATES = """
        act ContainerType {
            name: text
            wellsPerContainer: integer
        }

        act ExperimentDesign {
            containerType: one ContainerType
            containerCount: integer
            replicates: integer
            wellsPerCondition: integer
        } with conditions

        shape Condition {
            design: one ExperimentDesign
            block: integer
            value: decimal
        }

        shape Container {
            design: one ExperimentDesign
            replicate: integer
            position: integer
        }

        shape Well {
            container: one Container
            position: integer
            condition: one Condition
        }

        rule LayOut when ExperimentDesign {
            for each replicate from 1 to replicates, position from 1 to containerCount {
                Container from { design: this, replicate: replicate, position: position } as container
                for each condition in conditions, slot from 1 to wellsPerCondition {
                    Well from {
                        container: container
                        condition: condition
                        position: (condition.block - 1) * wellsPerCondition + slot
                    }
                }
            }
        }
    """.trimIndent()

    private fun diags(spec: String) = Validator.validate(spec)
    private fun codes(spec: String) = diags(spec).map { it.code }

    private fun system(spec: String): VelleSystem {
        val d = diags(spec)
        check(d.isEmpty()) { d.toString() }
        return VelleSystem(Model(Parser.parse(spec)))
    }

    private fun VelleSystem.accept(shape: String, vararg fields: Pair<String, Any?>, parts: Map<String, List<Map<String, Any?>>> = emptyMap()): Long =
        commit(shape, fields.toMap(), parts = parts).let { assertIs<CommitResult.Accepted>(it, "refused: $it").id }

    private fun num(n: Int) = java.math.BigDecimal(n)

    // ── parsing and printing ─────────────────────────────────────────────────

    @Test
    fun `a block parses with a product of sources and prints back`() {
        val decls = Parser.parse(PLATES)
        val rule = decls.filterIsInstance<RuleDecl>().single()
        val outer = assertIs<ForEach>(rule.body.single())
        assertEquals(listOf("replicate", "position"), outer.bindings.map { it.name })
        assertIs<LoopSource.Range>(outer.bindings[0].source)
        val inner = assertIs<ForEach>(outer.body[1])
        assertIs<LoopSource.Over>(inner.bindings[0].source)
        assertEquals(listOf("container", "condition", "slot"), rule.loopBindings().map { it.first.name }.let { listOf("container") + it.drop(2) })
        val printed = Printer.print(rule)
        assertTrue("for each replicate from 1 to replicates, position from 1 to containerCount {" in printed, printed)
        assertTrue("        for each condition in conditions, slot from 1 to wellsPerCondition {" in printed, printed)
        // the printed form parses to the same rule
        assertEquals(rule, Parser.parse(decls.joinToString("\n") { Printer.print(it) }).filterIsInstance<RuleDecl>().single())
    }

    @Test
    fun `each and in stay ordinary identifiers elsewhere`() {
        val spec = """
            act A { each: integer, in: text }
            shape B { a: one A, n: integer }
            rule R when A { B from { a: this, n: each } }
        """.trimIndent()
        assertEquals(emptyList(), codes(spec))
    }

    // ── the runtime ──────────────────────────────────────────────────────────

    private fun design(sys: VelleSystem, containers: Int, replicates: Int, conditions: Int, wellsPerCondition: Int = 24): Long {
        val type = sys.accept("ContainerType", "name" to "96-well", "wellsPerContainer" to num(96))
        return sys.accept("ExperimentDesign",
            "containerType" to type, "containerCount" to num(containers), "replicates" to num(replicates), "wellsPerCondition" to num(wellsPerCondition),
            parts = mapOf("conditions" to (1..conditions).map { mapOf("block" to num(it), "value" to num(it * 10)) }))
    }

    @Test
    fun `one firing lays out containers x replicates containers and wells under each`() {
        val sys = system(PLATES)
        sys.scope {
            val d = design(sys, containers = 3, replicates = 2, conditions = 4)
            val containers = sys.instancesOf("Container")
            assertEquals(6, containers.size)
            assertEquals(setOf(1L to 1L, 1L to 2L, 1L to 3L, 2L to 1L, 2L to 2L, 2L to 3L),
                containers.map { (sys.get(it, "replicate") as Number).toLong() to (sys.get(it, "position") as Number).toLong() }.toSet())
            containers.forEach { assertEquals(d, sys.get(it, "design")) }
            val wells = sys.instancesOf("Well")
            assertEquals(576, wells.size)
            // every container holds 96 wells at positions 1 to 96, 24 per condition
            for (c in containers) {
                val own = wells.filter { sys.get(it, "container") == c }
                assertEquals((1..96).map { it.toLong() }.toSet(), own.map { (sys.get(it, "position") as Number).toLong() }.toSet())
                assertEquals(4, own.groupBy { sys.get(it, "condition") }.size)
                own.groupBy { sys.get(it, "condition") }.values.forEach { assertEquals(24, it.size) }
            }
            // the well's condition is the design's own part
            val conditions = sys.instancesOf("Condition").toSet()
            assertTrue(wells.all { sys.get(it, "condition") in conditions })
        }
    }

    @Test
    fun `an empty range creates nothing, and a later source may read an earlier name`() {
        val sys = system(PLATES)
        sys.scope {
            design(sys, containers = 3, replicates = 0, conditions = 2)
            assertEquals(0, sys.instancesOf("Container").size)
            assertEquals(0, sys.instancesOf("Well").size)

            val chained = system("""
                act Order { lines: integer }
                shape Line { order: one Order, n: integer, copy: integer }
                rule R when Order {
                    for each n from 1 to lines, copy from 1 to n {
                        Line from { order: this, n: n, copy: copy }
                    }
                }
            """.trimIndent())
            chained.accept("Order", "lines" to num(3))
            assertEquals(6, chained.instancesOf("Line").size) // 1 + 2 + 3
        }
    }

    @Test
    fun `the whole layout lands or is refused as one`() {
        val sys = system(PLATES + "\n\nnever (Well where position > container.design.containerType.wellsPerContainer)")
        sys.scope {
            // 5 conditions × 24 wells = 120 positions on a 96-well plate: one well is refused, so the design is
            val type = sys.accept("ContainerType", "name" to "96-well", "wellsPerContainer" to num(96))
            val r = sys.commit("ExperimentDesign",
                mapOf("containerType" to type, "containerCount" to num(1), "replicates" to num(1), "wellsPerCondition" to num(24)),
                parts = mapOf("conditions" to (1..5).map { mapOf("block" to num(it), "value" to num(it)) }))
            assertIs<CommitResult.Refused>(r)
            assertEquals(0, sys.instancesOf("ExperimentDesign").size)
            assertEquals(0, sys.instancesOf("Container").size)
            assertEquals(0, sys.instancesOf("Well").size)
        }
    }

    @Test
    fun `a block runs over a part collection and over a bare shape`() {
        val sys = system("""
            act Design { name: text } with items
            shape Item { design: one Design, position: integer }
            act Plate { design: one Design }
            shape Well { plate: one Plate, position: integer }
            rule Fill when Plate {
                for each item in design.items {
                    Well from { plate: this, position: item.position }
                }
            }
            shape Tally { plate: one Plate, design: one Design }
            rule Count when Plate {
                for each d in Design {
                    Tally from { plate: this, design: d }
                }
            }
        """.trimIndent())
        sys.scope {
            val d = sys.accept("Design", "name" to "A", parts = mapOf("items" to listOf(mapOf("position" to num(3)), mapOf("position" to num(7)))))
            sys.accept("Design", "name" to "B")
            sys.accept("Plate", "design" to d)
            assertEquals(setOf(3L, 7L), sys.instancesOf("Well").map { (sys.get(it, "position") as Number).toLong() }.toSet())
            assertEquals(2, sys.instancesOf("Tally").size)
        }
    }

    // ── V34 ──────────────────────────────────────────────────────────────────

    private val BASE = """
        act Design { n: integer, label: text } with items
        shape Item { design: one Design, k: integer }
        shape Slot { design: one Design, i: integer }
    """.trimIndent()

    @Test
    fun `V34 - a source that is not a collection`() {
        val d = diags(BASE + "\nrule R when Design { for each x in label { Slot from { design: this, i: 1 } } }")
        assertTrue(d.any { it.code == "V34" && "is not a collection" in it.message }, d.toString())
    }

    @Test
    fun `V34 - a range bound that is not a number`() {
        val d = diags(BASE + "\nrule R when Design { for each x from 1 to label { Slot from { design: this, i: x } } }")
        assertTrue(d.any { it.code == "V34" && "bounds a range but is not a number" in it.message }, d.toString())
    }

    @Test
    fun `V34 - a name already in the rule`() {
        val taken = diags(BASE + "\nrule R when Design { for each n from 1 to n { Slot from { design: this, i: n } } }")
        assertTrue(taken.any { it.code == "V34" && "already a name" in it.message }, taken.toString())
        val twice = diags(BASE + "\nrule R when Design { for each i from 1 to n, i in items { Slot from { design: this, i: i } } }")
        assertTrue(twice.any { it.code == "V34" && "already a name" in it.message }, twice.toString())
        val asName = diags(BASE + "\nrule R when Design { for each s from 1 to n { Slot from { design: this, i: s } as s } }")
        assertTrue(asName.any { it.code == "V33" && "already a name" in it.message }, asName.toString())
    }

    @Test
    fun `V34 - a block holds creations and blocks only`() {
        val assign = diags(BASE + "\nrule R when Design { for each item in items { item.k = 1 } }")
        assertTrue(assign.any { it.code == "V34" && "assigns inside" in it.message }, assign.toString())
        val del = diags(BASE + "\nrule R when Design { for each item in items { delete item } }")
        assertTrue(del.any { it.code == "V34" && "deletes inside" in it.message }, del.toString())
    }

    @Test
    fun `V34 - an as name bound in a block is read inside it only`() {
        val spec = BASE + """

            shape Pair { design: one Design, slot: one Slot }
            rule R when Design {
                for each i from 1 to n { Slot from { design: this, i: i } as slot }
                Pair from { design: this, slot: slot }
            }
        """.trimIndent()
        val d = diags(spec)
        assertTrue(d.any { it.code == "V34" && "outside that block" in it.message }, d.toString())
    }

    @Test
    fun `a number name has no members`() {
        val d = diags(BASE + "\nrule R when Design { for each i from 1 to n { Slot from { design: this, i: i.k } } }")
        assertTrue(d.any { it.code == "F1" && "is a value, not a record" in it.message }, d.toString())
    }

    @Test
    fun `the clean spec validates`() {
        assertEquals(emptyList(), codes(PLATES))
    }
}
