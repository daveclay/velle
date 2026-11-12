package velle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A condition is a value of type boolean (Reference §7, "A boolean property is its
 * condition"; `grammar.md`, "Value expressions"). A boolean derived property, a boolean
 * field in a `from` block, and a boolean assignment are written as the condition itself,
 * `isRoot: boolean = parentName is none`, never `if ... then true else false`.
 */
class BooleanValueTest {

    private fun value(source: String): Expr = Parser(Lexer(source).lex()).parseValue()

    private fun system(spec: String): VelleSystem {
        val decls = Parser.parse(spec)
        val diags = Validator.validate(decls)
        check(diags.isEmpty()) { diags.toString() }
        return VelleSystem(Model(decls))
    }

    @Test
    fun `a boolean derived property is written as its condition`() {
        val sys = system(
            """
            act Kind {
                name: text
                parentName: text?
                isRoot: boolean = parentName is none or parentName == "survey"
            }
            shape RootKind = Kind where isRoot
            """.trimIndent()
        )
        sys.scope {
            val survey = assertIs<CommitResult.Accepted>(sys.commit("Kind", mapOf("name" to "survey"))).id
            val page = assertIs<CommitResult.Accepted>(sys.commit("Kind", mapOf("name" to "page", "parentName" to "survey"))).id
            assertIs<CommitResult.Accepted>(sys.commit("Kind", mapOf("name" to "question", "parentName" to "page")))
            assertEquals(listOf(survey, page), sys.instancesOf("RootKind"))
        }
    }

    @Test
    fun `a boolean field in a from block and a boolean assignment are written as their condition`() {
        val sys = system(
            """
            act Reading {
                efficacy: decimal
                threshold: decimal
            }
            shape Verdict {
                reading: one Reading
                effective: boolean
            }
            rule RecordVerdict when (Reading where not exists Verdict for this) {
                Verdict from { reading: this, effective: efficacy >= threshold }
            }
            shape EffectiveVerdict = Verdict where effective

            act Note {
                locked: boolean initially false
            }
            act Lock {
                note: one Note
                force: boolean
                weight: integer
            }
            rule ApplyLock when Lock {
                note.locked = force or weight > 10
            }
            shape LockedNote = Note where locked
            """.trimIndent()
        )
        sys.scope {
            assertIs<CommitResult.Accepted>(sys.commit("Reading", mapOf("efficacy" to 60, "threshold" to 50)))
            assertIs<CommitResult.Accepted>(sys.commit("Reading", mapOf("efficacy" to 40, "threshold" to 50)))
            assertEquals(1, sys.instancesOf("EffectiveVerdict").size)

            val note = assertIs<CommitResult.Accepted>(sys.commit("Note", mapOf())).id
            assertIs<CommitResult.Accepted>(sys.commit("Lock", mapOf("note" to note, "force" to false, "weight" to 5)))
            assertEquals(emptyList(), sys.instancesOf("LockedNote"))
            assertIs<CommitResult.Accepted>(sys.commit("Lock", mapOf("note" to note, "force" to false, "weight" to 11)))
            assertEquals(listOf(note), sys.instancesOf("LockedNote"))
        }
    }

    @Test
    fun `a comparison binds tighter than and, and a conditional's branches reach to the end`() {
        val conjunction = assertIs<Binary>(value("a == b and c"))
        assertEquals("and", conjunction.op)
        assertEquals(Binary("==", PathExpr("a"), PathExpr("b")), conjunction.left)

        // `between a and b` keeps its own `and`; the conjunction continues after the upper bound
        val bounded = assertIs<Binary>(value("x between 1 and 3 and y"))
        assertEquals("and", bounded.op)
        assertEquals(PathExpr("y"), bounded.right)

        // `exists Shape for expr` takes a value, so the conjunction continues after it
        val existence = assertIs<Binary>(value("exists Verdict for this and y"))
        assertEquals("and", existence.op)
        assertIs<ExistsExpr>(existence.left)

        val conditional = assertIs<IfExpr>(value("if p then 1 else q == 2"))
        assertEquals(Binary("==", PathExpr("q"), IntLit(2)), conditional.elseExpr)

        assertEquals("if p then 1 else q == 2", Printer.expr(conditional))
        assertEquals("(a == b) and c", Printer.expr(conjunction))
    }
}
