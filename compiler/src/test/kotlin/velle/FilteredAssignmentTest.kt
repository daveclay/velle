package velle

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * An assignment whose left side is a filtered collection, `(member.loans where dueOn < this.before).flagged = true`
 * (Reference §12, "The left side"; Reference §6, "Write one field on every member"): how it parses and prints,
 * what the runtime writes, how the one-writer check, the comparison, the diagrams, the serialization domain,
 * and the recheck of conditions see the write, and what the compiler refuses.
 */
class FilteredAssignmentTest {

    private val LIBRARY = """
        act Member { name: text }

        act Loan {
            member: one Member
            dueOn: Date
            renewals: integer initially 0
            flagged: boolean initially false
        }

        transient act FlagLate {
            member: one Member
            before: Date
        }
    """.trimIndent()

    private val FLAG_BEFORE = LIBRARY + """

        rule Flag when FlagLate {
            (member.loans where dueOn < this.before).flagged = true
        }
    """.trimIndent()

    private fun diagnosticsOf(spec: String) = Validator.validate(spec)
    private fun messagesOf(spec: String) = diagnosticsOf(spec).map { diagnostic -> "[${diagnostic.code}] ${diagnostic.message}" }

    private fun system(spec: String): VelleSystem {
        val diagnostics = diagnosticsOf(spec)
        check(diagnostics.isEmpty()) { diagnostics.toString() }
        return VelleSystem(Model(Parser.parse(spec)))
    }

    private fun VelleSystem.accept(shape: String, vararg fields: Pair<String, Any?>): Long =
        commit(shape, fields.toMap()).let { result -> assertIs<CommitResult.Accepted>(result, "refused: $result").id }

    /** The system's clock starts at 2026-01-01. */
    private val december = LocalDate.of(2025, 12, 1)
    private val february = LocalDate.of(2026, 2, 1)
    private val april = LocalDate.of(2026, 4, 1)

    // ── parsing ──────────────────────────────────────────────────────────────

    @Test
    fun `a statement opening with a parenthesis is a filtered assignment, and prints back`() {
        val rule = Parser.parse(FLAG_BEFORE).filterIsInstance<RuleDecl>().single()
        val assignment = assertIs<Assignment>(rule.body.single())
        assertEquals(PathExpr("member", listOf(Seg("loans", false), Seg("flagged", false))), assignment.target)
        assertEquals(Binary("<", PathExpr("dueOn"), PathExpr("this", listOf(Seg("before", false)))), assignment.filter)
        assertEquals(BoolLit(true), assignment.value)
        val printed = Printer.print(rule)
        assertTrue("(member.loans where dueOn < this.before).flagged = true" in printed, printed)
        assertEquals(rule, Parser.parse(printed).filterIsInstance<RuleDecl>().single())
    }

    @Test
    fun `a parenthesis on the right of an assignment is still a value`() {
        val spec = LIBRARY + "\n\nact Tally { member: one Member, late: integer initially 0 }\n\n" +
            "rule Count when Tally {\n    late = count(member.loans where dueOn < today)\n    late = (late + 1)\n}"
        val assignments = Parser.parse(spec).filterIsInstance<RuleDecl>().single().body.map { item -> assertIs<Assignment>(item) }
        assertEquals(listOf(null, null), assignments.map { assignment -> assignment.filter })
    }

    @Test
    fun `a parenthesis that is not a filtered path is refused by the parser`() {
        for ((statement, expected) in listOf(
            "(member.loans).flagged = true" to "expected 'where'",
            "(Loan where flagged).flagged = true" to "a filtered left side starts from a field of the rule's subject",
            "(member.loans where flagged) = true" to "expected DOT",
        )) {
            val failure = assertFailsWith<VelleSyntaxError>(statement) {
                Parser.parse(LIBRARY + "\n\nrule Flag when FlagLate {\n    $statement\n}")
            }
            assertTrue(expected in failure.message!!, "$statement: ${failure.message}")
        }
    }

    // ── what runs ────────────────────────────────────────────────────────────

    @Test
    fun `the filter reads the request and chooses which members are written`() {
        val sys = system(FLAG_BEFORE)
        sys.scope {
            val member = sys.accept("Member", "name" to "Ada")
            val loans = listOf(december, february, april).map { due -> sys.accept("Loan", "member" to member, "dueOn" to due) }
            sys.accept("FlagLate", "member" to member, "before" to LocalDate.of(2026, 3, 1))
            assertEquals(listOf(true, true, false), loans.map { loan -> sys.get(loan, "flagged") })
        }
    }

    @Test
    fun `a filter on the left reads the time as any filter does`() {
        val spec = LIBRARY + """

            rule Flag when FlagLate {
                (member.loans where dueOn < today).flagged = true
            }
        """.trimIndent()
        val sys = system(spec)
        sys.scope {
            val member = sys.accept("Member", "name" to "Ada")
            val late = sys.accept("Loan", "member" to member, "dueOn" to december)
            val notDue = sys.accept("Loan", "member" to member, "dueOn" to february)
            sys.accept("FlagLate", "member" to member, "before" to april)
            assertEquals(listOf(true, false), listOf(late, notDue).map { loan -> sys.get(loan, "flagged") })
        }
    }

    @Test
    fun `the filter and the value are read from the state before the rule's writes`() {
        val spec = LIBRARY + """

            rule Flag when FlagLate {
                (member.loans where not flagged).flagged = true
                member.loans.renewals = count(member.loans where flagged)
            }
        """.trimIndent()
        val sys = system(spec)
        sys.scope {
            val member = sys.accept("Member", "name" to "Ada")
            val loans = (1..3).map { sys.accept("Loan", "member" to member, "dueOn" to december) }
            sys.seed("Loan", mapOf("member" to member, "dueOn" to december, "flagged" to true))
            sys.accept("FlagLate", "member" to member, "before" to april)
            val allLoans = sys.instancesOf("Loan")
            assertEquals(4, allLoans.size)
            assertTrue(allLoans.all { loan -> sys.get(loan, "flagged") == true })
            // the count was read before any loan was flagged: one loan was flagged then
            assertEquals(listOf(1L, 1L, 1L), loans.map { loan -> (sys.get(loan, "renewals") as Number).toLong() })
        }
    }

    @Test
    fun `a filtered write through a derived collection writes only what both choose`() {
        val spec = LIBRARY.replace("act Member { name: text }",
            "act Member {\n    name: text\n    overdue: many Loan = (loans where dueOn < today)\n}") + """

            rule Flag when FlagLate {
                (member.overdue where dueOn < this.before).flagged = true
            }
        """.trimIndent()
        val sys = system(spec)
        sys.scope {
            val member = sys.accept("Member", "name" to "Ada")
            val early = sys.accept("Loan", "member" to member, "dueOn" to LocalDate.of(2025, 11, 1))
            val late = sys.accept("Loan", "member" to member, "dueOn" to december)
            val notDue = sys.accept("Loan", "member" to member, "dueOn" to february)
            sys.accept("FlagLate", "member" to member, "before" to LocalDate.of(2025, 11, 15))
            assertEquals(listOf(true, false, false), listOf(early, late, notDue).map { loan -> sys.get(loan, "flagged") })
        }
    }

    // ── how the checks and the runtime see the write ─────────────────────────

    @Test
    fun `the one-writer check counts a filtered write as a write to the field on every member`() {
        val spec = FLAG_BEFORE + """

            rule Reset when FlagLate {
                member.loans.flagged = false
            }
        """.trimIndent()
        assertEquals(listOf("[V1] rules 'Flag' and 'Reset' both assign Loan.flagged and their triggers are not provably disjoint"),
            messagesOf(spec))
    }

    @Test
    fun `the comparison names the rule as a cause of what its filtered write sets`() {
        val spec = FLAG_BEFORE + "\n\nshape FlaggedLoan = Loan where flagged"
        assertEquals(emptyList(), diagnosticsOf(spec))
        val report = SpecComparison.report(SpecVersion("empty.velle", ""), SpecVersion("library.velle", spec))
        val consequences = report.substringAfter("## What that changes")
        assertTrue("- Flag setting Loan's flagged can now make a Loan become a FlaggedLoan." in consequences, consequences)
    }

    @Test
    fun `the state-flow diagram shows the filtered write`() {
        val diagram = DiagramGen.generate(FLAG_BEFORE + "\n\nshape FlaggedLoan = Loan where flagged", "Library")
        assertTrue("S->>S: (member.loans where dueOn < this.before).flagged = true" in diagram, diagram)
        assertTrue("out --> FlaggedLoan : Flag writes Loan.flagged" in diagram, diagram)
    }

    @Test
    fun `a condition over the written field is read again after the filtered write`() {
        val spec = FLAG_BEFORE + """

            act Notice { loan: one Loan }

            rule NoticeFlagged when (Loan where flagged) {
                Notice from { loan: this }
            }
        """.trimIndent()
        val sys = system(spec)
        sys.scope {
            val member = sys.accept("Member", "name" to "Ada")
            val late = sys.accept("Loan", "member" to member, "dueOn" to december)
            sys.accept("Loan", "member" to member, "dueOn" to april)
            sys.accept("FlagLate", "member" to member, "before" to february)
            assertEquals(listOf<Any?>(late), sys.instancesOf("Notice").map { notice -> sys.get(notice, "loan") })
        }
    }

    @Test
    fun `a filtered write queues the request where the unfiltered write does`() {
        val unfiltered = FLAG_BEFORE.replace("(member.loans where dueOn < this.before).flagged = true", "member.loans.flagged = true")
        fun queueKeys(spec: String): List<String> {
            assertEquals(emptyList(), diagnosticsOf(spec))
            return DomainAnalysis(Model(Parser.parse(spec))).actDomains.getValue("FlagLate").renderKeys("flagLate").sorted()
        }
        assertEquals(queueKeys(unfiltered), queueKeys(FLAG_BEFORE))
    }

    @Test
    fun `a sibling that writes what the filter reads is read in the body`() {
        val spec = FLAG_BEFORE + """

            rule Postpone when FlagLate {
                member.loans.dueOn = this.before
            }
        """.trimIndent()
        assertTrue(messagesOf(spec).any { message -> message.startsWith("[V15] 'Postpone' writes Loan.dueOn, which sibling 'Flag' reads in its body") },
            messagesOf(spec).toString())
    }

    // ── refusals ─────────────────────────────────────────────────────────────

    @Test
    fun `the filter is checked as any filter is`() {
        assertEquals(listOf("[F1] 'before' is not a member of 'Loan' (bare names resolve in the innermost scope only — use this.<field> for the subject)"),
            messagesOf(FLAG_BEFORE.replace("this.before", "before")))
    }

    @Test
    fun `a filter on the left chooses members of a collection, not one record`() {
        val spec = LIBRARY + """

            rule Rename when FlagLate {
                (member where name == "").name = "unnamed"
            }
        """.trimIndent()
        assertEquals(listOf("[V20] rule 'Rename' assigns '(member where name == \"\").name', and 'member' is one record, not a " +
            "collection; a filter on the left chooses which members of a collection are written, so write 'member.name = ...' " +
            "and put the test in the rule's condition (Reference §12)"),
            messagesOf(spec))
    }

    @Test
    fun `a filtered path through a collection and onward is refused as any path is`() {
        val spec = LIBRARY.replace("act Member { name: text }", "act Member { name: text, tier: text }") + """

            rule Promote when FlagLate {
                (member.loans.member where dueOn < this.before).tier = "gold"
            }
        """.trimIndent()
        assertTrue(messagesOf(spec).any { message -> message.startsWith("[V20] rule 'Promote' assigns through 'Member.loans' and onward") },
            messagesOf(spec).toString())
    }

    @Test
    fun `an assignment a block cannot hold is advised the filtered form`() {
        val spec = LIBRARY.replace("transient act FlagLate {\n    member: one Member\n    before: Date\n}",
            "transient act FlagLate {\n    member: one Member\n    before: Date\n    times: integer\n}") + """

            rule Flag when FlagLate {
                for each loan in (member.loans where dueOn < this.before), step from 1 to times {
                    loan.flagged = true
                }
            }
        """.trimIndent()
        assertEquals(listOf("[V34] rule 'Flag' assigns 'loan.flagged' within 'for each loan in (member.loans where dueOn < this.before), " +
            "step from 1 to times', which has more than one name, so one loan is met more than once and 'loan.flagged' could be " +
            "written twice in one commit; an assignment stands in blocks of one name; to write 'flagged' on the members of " +
            "'(member.loans where dueOn < this.before)', write '(member.loans where dueOn < this.before).flagged = true' in the " +
            "rule, outside the block (Reference §12)"),
            messagesOf(spec))
    }
}
