package velle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the comparison of two specs says a change can make happen, under "What that changes",
 * read where the direction proofs decide it: an `exists` over a filtered collection, a rule
 * creating a record with values it gives, and a rule whose own write can only disarm its
 * condition. Each spec is compared with an empty spec, so every possible move is new.
 */
class ComparisonDirectionTest {

    private fun consequencesOf(spec: String): String {
        assertEquals(emptyList(), Validator.validate(spec), "the spec validates")
        val report = SpecComparison.report(SpecVersion("empty.velle", ""), SpecVersion("library.velle", spec))
        return report.substringAfter("## What that changes")
    }

    private fun assertSays(line: String, consequences: String) =
        assertTrue("- $line" in consequences, "expected \"$line\" in:\n$consequences")

    private fun assertDoesNotSay(line: String, consequences: String) =
        assertTrue("- $line" !in consequences, "did not expect \"$line\" in:\n$consequences")

    // ── an exists over a filtered collection moves the way its filter moves ──

    private val openAndLateLoans = """
        act Member { name: text }

        act Loan {
            member: one Member
            dueOn: Date
        }

        act Return { loan: one Loan }

        shape Notice { member: one Member }

        shape OpenLoan = Loan where count(returns) < 1

        shape LateLoan = Loan where dueOn < today

        shape MemberWithLateLoan = Member where exists (loans where dueOn < today)

        shape MemberWithOpenLoan = Member where exists (loans where count(returns) < 1)

        rule NoticeMembers when (Member where exists (loans where count(returns) < 1) and not exists Notice for this) on Nightly {
            Notice from { member: this }
        }
    """.trimIndent()

    @Test
    fun `time passing can make a member's loan late and never not late`() {
        val consequences = consequencesOf(openAndLateLoans)

        assertSays("Time passing can now make a Loan become a LateLoan.", consequences)
        assertSays("Time passing can now make a Member become a MemberWithLateLoan.", consequences)
        assertDoesNotSay("Time passing can now make a Member stop being a MemberWithLateLoan.", consequences)
    }

    @Test
    fun `a new return can take a member's loan out of open and never put one in`() {
        val consequences = consequencesOf(openAndLateLoans)

        assertSays("A new Return can now make a Loan stop being an OpenLoan.", consequences)
        assertSays("A new Return can now make a Member stop being a MemberWithOpenLoan.", consequences)
        assertDoesNotSay("A new Return can now make a Member become a MemberWithOpenLoan.", consequences)
        assertDoesNotSay("A new Return can now cause NoticeMembers to run", consequences)
    }

    @Test
    fun `a new loan can still make a member one with an open loan and cause the notice`() {
        val consequences = consequencesOf(openAndLateLoans)

        assertSays("A new Loan can now make a Member become a MemberWithOpenLoan.", consequences)
        assertSays("A new Loan can now cause NoticeMembers to run, later rather than in the same commit.", consequences)
    }

    @Test
    fun `a rule writing the field the filter reads, with no proof of which way, moves the member both ways`() {
        val consequences = consequencesOf(
            """
            act Member { name: text }

            act Loan {
                member: one Member
                status: text
            }

            transient act MarkLost { loan: one Loan }

            rule RecordLoss when MarkLost {
                loan.status = "lost"
            }

            shape MemberWithLostLoan = Member where exists (loans where status == "lost")
            """.trimIndent()
        )

        assertSays("RecordLoss setting Loan's status can now make a Member become a MemberWithLostLoan.", consequences)
        assertSays("RecordLoss setting Loan's status can now make a Member stop being a MemberWithLostLoan.", consequences)
    }

    @Test
    fun `a rule putting a book on a member's list moves the member both ways, though time moves each book one way`() {
        val consequences = consequencesOf(
            """
            act Book {
                title: text
                dueOn: Date
            }

            act Member {
                name: text
                books: many Book
            }

            transient act Lend {
                member: one Member
                book: one Book
            }

            rule AddBook when Lend {
                member.books = member.books + book
            }

            shape MemberWithLateBook = Member where exists (books where dueOn < today)
            """.trimIndent()
        )

        assertSays("AddBook setting Member's books can now make a Member become a MemberWithLateBook.", consequences)
        assertSays("AddBook setting Member's books can now make a Member stop being a MemberWithLateBook.", consequences)
        assertSays("Time passing can now make a Member become a MemberWithLateBook.", consequences)
        assertDoesNotSay("Time passing can now make a Member stop being a MemberWithLateBook.", consequences)
    }

    // ── a rule re-pointing a reference moves the record on the other side ────

    private fun movingLoans(lateLoanTest: String) = """
        act Member { name: text }

        act Loan {
            member: one Member
            dueOn: Date
        }

        transient act Transfer {
            loan: one Loan
            receiver: one Member
        }

        rule MoveLoan when Transfer {
            loan.member = receiver
        }

        shape MemberWithLateLoan = Member where $lateLoanTest
    """.trimIndent()

    @Test
    fun `a rule moving a loan to another member, read through the member's loans, moves members both ways`() {
        val consequences = consequencesOf(movingLoans("exists (loans where dueOn < today)"))

        assertSays("MoveLoan setting Loan's member can now make a Member become a MemberWithLateLoan.", consequences)
        assertSays("MoveLoan setting Loan's member can now make a Member stop being a MemberWithLateLoan.", consequences)
    }

    @Test
    fun `a rule moving a loan to another member, read as the loans naming the member, moves members both ways`() {
        val consequences = consequencesOf(movingLoans("exists (Loan where member == this and dueOn < today)"))

        assertSays("MoveLoan setting Loan's member can now make a Member become a MemberWithLateLoan.", consequences)
        assertSays("MoveLoan setting Loan's member can now make a Member stop being a MemberWithLateLoan.", consequences)
    }

    @Test
    fun `a rule moving a loan to another member, read as the loans for the member, moves members both ways`() {
        val consequences = consequencesOf(movingLoans("exists Loan for this"))

        assertSays("MoveLoan setting Loan's member can now make a Member become a MemberWithLateLoan.", consequences)
        assertSays("MoveLoan setting Loan's member can now make a Member stop being a MemberWithLateLoan.", consequences)
    }

    @Test
    fun `a rule moving a loan to another member, counted as the loans for the member, moves members both ways`() {
        val consequences = consequencesOf(movingLoans("count(Loan for this) > 0"))

        assertSays("MoveLoan setting Loan's member can now make a Member become a MemberWithLateLoan.", consequences)
        assertSays("MoveLoan setting Loan's member can now make a Member stop being a MemberWithLateLoan.", consequences)
    }

    @Test
    fun `a rule moving a loan to another member, read as the late loans for the member, moves members both ways`() {
        val consequences = consequencesOf(movingLoans("exists (Loan for this where dueOn < today)"))

        assertSays("MoveLoan setting Loan's member can now make a Member become a MemberWithLateLoan.", consequences)
        assertSays("MoveLoan setting Loan's member can now make a Member stop being a MemberWithLateLoan.", consequences)
    }

    // ── a rule's creation is read for the values it gives the record ─────────

    private fun lending(dueOn: String) = """
        act Member { name: text }

        transient act Borrow {
            member: one Member
            until: Date
        }

        shape Loan {
            member: one Member
            dueOn: Date
        }

        rule LendTool when Borrow {
            Loan from { member: member, dueOn: $dueOn }
        }

        shape MemberWithLateLoan = Member where exists (loans where dueOn < today)

        rule ChaseLateLoans when (Loan where dueOn < today) on Nightly {
            member.name = "late"
        }
    """.trimIndent()

    @Test
    fun `a loan created due seven days from today cannot make its member one with a late loan`() {
        val consequences = consequencesOf(lending("today + 7 days"))

        assertDoesNotSay("LendTool creating a Loan can now make a Member become a MemberWithLateLoan.", consequences)
        assertSays("Time passing can now make a Member become a MemberWithLateLoan.", consequences)
        assertDoesNotSay("Time passing can now make a Member stop being a MemberWithLateLoan.", consequences)
    }

    @Test
    fun `a loan created due seven days from today cannot cause the rule over late loans`() {
        val consequences = consequencesOf(lending("today + 7 days"))

        assertDoesNotSay("LendTool can now cause ChaseLateLoans to run", consequences)
    }

    @Test
    fun `a loan created already overdue can make its member one with a late loan and cause the rule over late loans`() {
        val consequences = consequencesOf(lending("today - 1 days"))

        assertSays("LendTool creating a Loan can now make a Member become a MemberWithLateLoan.", consequences)
        assertSays("LendTool can now cause ChaseLateLoans to run, later rather than in the same commit.", consequences)
    }

    @Test
    fun `a loan created with the due date the borrower asked for can make its member one with a late loan`() {
        val consequences = consequencesOf(lending("until"))

        assertSays("LendTool creating a Loan can now make a Member become a MemberWithLateLoan.", consequences)
        assertSays("LendTool can now cause ChaseLateLoans to run, later rather than in the same commit.", consequences)
    }

    // ── a rule whose own write disarms its condition does not cause itself ───

    @Test
    fun `a rule whose own creation can only take a member out of its condition does not cause itself to run`() {
        val consequences = consequencesOf(
            """
            act Member { name: text }

            shape Suspension { member: one Member }

            rule SuspendMembers when (Member where not exists Suspension for this) on Nightly {
                Suspension from { member: this }
            }
            """.trimIndent()
        )

        assertSays("A new Member can now cause SuspendMembers to run, later rather than in the same commit.", consequences)
        assertDoesNotSay("SuspendMembers can now cause SuspendMembers to run", consequences)
    }

    @Test
    fun `the rule graph still draws the rule that disarms its own guard`() {
        val spec = """
            act Member { name: text }

            shape Suspension { member: one Member }

            rule SuspendMembers when (Member where not exists Suspension for this) on Nightly {
                Suspension from { member: this }
            }
        """.trimIndent()
        val graph = RuleGraphGen.render(Model(Parser.parse(spec)), KindCatalog(Model(Parser.parse(spec))))

        assertTrue("SuspendMembers -.->|\"inserts Suspension — disarms its own guard" in graph, graph)
    }

    // ── the value a created record's field has at the moment it is created ──

    private val loanSpec = """
        act Member { name: text }

        transient act Borrow {
            member: one Member
            until: Date
        }

        shape Loan {
            member: one Member
            dueOn: Date
        }
    """.trimIndent()

    private val analysis = Model(Parser.parse(loanSpec)).let { model -> FlowAnalysis(model, KindCatalog(model)) }

    private val dueBeforeToday = Binary("<", PathExpr("dueOn"), TodayLit)

    private fun dueBeforeTodayWhenCreatedWith(dueOn: Expr): Boolean? =
        analysis.initTruth(dueBeforeToday, "Loan", mapOf("dueOn" to dueOn))

    @Test
    fun `a loan due seven days after today is not due before today when it is created`() {
        assertEquals(false, dueBeforeTodayWhenCreatedWith(Binary("+", TodayLit, DurationLit(7, "days"))))
    }

    @Test
    fun `a loan due one day before today is due before today when it is created`() {
        assertEquals(true, dueBeforeTodayWhenCreatedWith(Binary("-", TodayLit, DurationLit(1, "days"))))
    }

    @Test
    fun `a loan due a week after today is not due before today plus six days`() {
        val dueBeforeSixDaysOn = Binary("<", PathExpr("dueOn"), Binary("+", TodayLit, DurationLit(6, "days")))

        assertEquals(false, analysis.initTruth(dueBeforeSixDaysOn, "Loan", mapOf("dueOn" to Binary("+", TodayLit, DurationLit(1, "weeks")))))
    }

    @Test
    fun `a loan due on the date the borrower gave is unknown when it is created`() {
        assertNull(dueBeforeTodayWhenCreatedWith(PathExpr("until")))
    }

    @Test
    fun `a loan due a month after today is unknown against a date in days`() {
        assertNull(dueBeforeTodayWhenCreatedWith(Binary("+", TodayLit, DurationLit(1, "months"))))
    }

    @Test
    fun `a value on the clock of now is unknown against today`() {
        assertNull(dueBeforeTodayWhenCreatedWith(Binary("+", NowLit, DurationLit(1, "days"))))
    }

    @Test
    fun `a loan whose due date the creation does not give is unknown when it is created`() {
        assertNull(analysis.initTruth(dueBeforeToday, "Loan", emptyMap()))
    }
}
