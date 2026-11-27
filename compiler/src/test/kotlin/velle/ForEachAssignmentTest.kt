package velle

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * An assignment inside a `for each` block (Reference §14, "Writing a field of each element"):
 * `name.field = value` through the name of the block it stands directly in, accepted where
 * the declarations show no record's field is written twice in one commit; what the runtime
 * writes; how the one-writer check, the comparison, and a prohibition see the write; and
 * each refusal's message.
 */
class ForEachAssignmentTest {

    private val LIBRARY = """
        act Member { name: text }

        act Loan {
            member: one Member
            dueOn: Date
            renewals: integer initially 0
            flagged: boolean initially false
        }

        transient act Renew { member: one Member }
    """.trimIndent()

    private val RENEW_LATE_LOANS = LIBRARY + """

        rule Extend when Renew {
            for each loan in (member.loans where dueOn < today) {
                loan.renewals = loan.renewals + 1
                loan.flagged = true
            }
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

    private fun VelleSystem.numberOf(record: Long, field: String): Long = (get(record, field) as Number).toLong()

    /** The system's clock starts at 2026-01-01, so a loan due in December 2025 is late. */
    private val lateDate = LocalDate.of(2025, 12, 1)
    private val notYetDue = LocalDate.of(2026, 2, 1)

    // ── what runs ────────────────────────────────────────────────────────────

    @Test
    fun `a block over a filter writes each chosen record with its own value`() {
        val sys = system(RENEW_LATE_LOANS)
        sys.scope {
            val member = sys.accept("Member", "name" to "Ada")
            val late = sys.accept("Loan", "member" to member, "dueOn" to lateDate)
            val notDue = sys.accept("Loan", "member" to member, "dueOn" to notYetDue)
            sys.accept("Renew", "member" to member)
            sys.accept("Renew", "member" to member)
            assertEquals(listOf(2L, 0L), listOf(late, notDue).map { loan -> sys.numberOf(loan, "renewals") })
            assertEquals(listOf(true, false), listOf(late, notDue).map { loan -> sys.get(loan, "flagged") })
        }
    }

    @Test
    fun `every value and filter in the block is read from the state before the rule's writes`() {
        val spec = LIBRARY + """

            rule Extend when Renew {
                for each loan in (member.loans where not flagged) {
                    loan.flagged = true
                    loan.renewals = count(member.loans where flagged)
                }
            }
        """.trimIndent()
        val sys = system(spec)
        sys.scope {
            val member = sys.accept("Member", "name" to "Ada")
            val loans = (1..3).map { sys.accept("Loan", "member" to member, "dueOn" to lateDate) }
            sys.accept("Renew", "member" to member)
            assertEquals(listOf(true, true, true), loans.map { loan -> sys.get(loan, "flagged") })
            // each loan's count was read before any loan was flagged
            assertEquals(listOf(0L, 0L, 0L), loans.map { loan -> sys.numberOf(loan, "renewals") })
            sys.accept("Renew", "member" to member)
            // the filter reads the field the block writes: every loan is flagged now, so none is written again
            assertEquals(listOf(0L, 0L, 0L), loans.map { loan -> sys.numberOf(loan, "renewals") })
        }
    }

    private val SHELVES = """
        act Library { name: text }
        act Shelf { library: one Library, name: text }
        act Book { shelf: one Shelf, label: text initially "" }
        transient act Relabel { library: one Library }
    """.trimIndent()

    @Test
    fun `a nested block through the other side of a one reference writes each record once`() {
        val spec = SHELVES + """

            rule LabelBooks when Relabel {
                for each shelf in library.shelfs {
                    for each book in shelf.books {
                        book.label = shelf.name
                    }
                }
            }
        """.trimIndent()
        val sys = system(spec)
        sys.scope {
            val library = sys.accept("Library", "name" to "Central")
            val fiction = sys.accept("Shelf", "library" to library, "name" to "fiction")
            val poetry = sys.accept("Shelf", "library" to library, "name" to "poetry")
            val novel = sys.accept("Book", "shelf" to fiction)
            val sonnets = sys.accept("Book", "shelf" to poetry)
            sys.accept("Relabel", "library" to library)
            assertEquals(listOf("fiction", "poetry"), listOf(novel, sonnets).map { book -> sys.get(book, "label") })
        }
    }

    @Test
    fun `a block that only creates is unchanged`() {
        val spec = LIBRARY + """

            act RenewalOfLoan { loan: one Loan }

            rule Spread when Renew {
                for each loan in (member.loans where dueOn < today) {
                    RenewalOfLoan from { loan: loan }
                }
            }

            rule Count when RenewalOfLoan {
                loan.renewals = loan.renewals + 1
            }
        """.trimIndent()
        val sys = system(spec)
        sys.scope {
            val member = sys.accept("Member", "name" to "Ada")
            val late = sys.accept("Loan", "member" to member, "dueOn" to lateDate)
            val notDue = sys.accept("Loan", "member" to member, "dueOn" to notYetDue)
            sys.accept("Renew", "member" to member)
            sys.accept("Renew", "member" to member)
            assertEquals(listOf(2L, 0L), listOf(late, notDue).map { loan -> sys.numberOf(loan, "renewals") })
            assertEquals(2, sys.instancesOf("RenewalOfLoan").size)
        }
    }

    @Test
    fun `a write through a derived collection writes only the records it holds`() {
        val spec = """
            act Member {
                name: text
                overdue: many Loan = (loans where dueOn < today)
            }

            act Loan {
                member: one Member
                dueOn: Date
                flagged: boolean initially false
            }

            transient act FlagLate { member: one Member }

            rule Flag when FlagLate {
                member.overdue.flagged = true
            }
        """.trimIndent()
        val sys = system(spec)
        sys.scope {
            val member = sys.accept("Member", "name" to "Ada")
            val late = sys.accept("Loan", "member" to member, "dueOn" to lateDate)
            val notDue = sys.accept("Loan", "member" to member, "dueOn" to notYetDue)
            sys.accept("FlagLate", "member" to member)
            assertEquals(listOf(true, false), listOf(late, notDue).map { loan -> sys.get(loan, "flagged") })
        }
    }

    // ── how the checks and the runtime see the write ─────────────────────────

    @Test
    fun `the one-writer check counts a block's write as a write to the field on every member`() {
        val spec = RENEW_LATE_LOANS + """

            rule Reset when Renew {
                member.loans.flagged = false
            }
        """.trimIndent()
        assertEquals(listOf("[V1] rules 'Extend' and 'Reset' both assign Loan.flagged and their triggers are not provably disjoint"),
            messagesOf(spec))
    }

    @Test
    fun `a field written in a block and elsewhere in the same rule is written twice in one body`() {
        val spec = LIBRARY + """

            rule Extend when Renew {
                for each loan in (member.loans where dueOn < today) {
                    loan.flagged = true
                }
                member.loans.flagged = false
            }
        """.trimIndent()
        assertEquals(listOf("[V1] rule 'Extend' assigns Loan.flagged twice in one body"), messagesOf(spec))
    }

    @Test
    fun `the comparison names the rule as a cause of what its block writes`() {
        val spec = RENEW_LATE_LOANS + "\n\nshape FlaggedLoan = Loan where flagged"
        assertEquals(emptyList(), diagnosticsOf(spec))
        val report = SpecComparison.report(SpecVersion("empty.velle", ""), SpecVersion("library.velle", spec))
        val consequences = report.substringAfter("## What that changes")
        assertTrue("- Extend setting Loan's flagged can now make a Loan become a FlaggedLoan." in consequences, consequences)
    }

    @Test
    fun `a prohibition over a field a block writes is a guarantee, as over any assigned field`() {
        assertEquals(listOf("[V10] a 'never' reads 'Loan.renewals', which rule 'Extend' assigns, so it is a guarantee that the " +
            "rules keep it true, and the compiler cannot yet prove one over every assignment; read a field no rule assigns, " +
            "or derive 'renewals' from the records the rule writes (Reference §21)"),
            messagesOf(RENEW_LATE_LOANS + "\n\nnever (Loan where renewals > 1)"))
    }

    @Test
    fun `a condition over the written field is read again after the block writes it`() {
        val spec = RENEW_LATE_LOANS + """

            act Notice { loan: one Loan }

            rule NoticeFlagged when (Loan where flagged) {
                Notice from { loan: this }
            }
        """.trimIndent()
        val sys = system(spec)
        sys.scope {
            val member = sys.accept("Member", "name" to "Ada")
            val late = sys.accept("Loan", "member" to member, "dueOn" to lateDate)
            sys.accept("Loan", "member" to member, "dueOn" to notYetDue)
            sys.accept("Renew", "member" to member)
            assertEquals(listOf<Any?>(late), sys.instancesOf("Notice").map { notice -> sys.get(notice, "loan") })
        }
    }

    @Test
    fun `a block's write queues the request where the same write through the collection does`() {
        val throughCollection = LIBRARY + """

            rule Flag when Renew {
                member.loans.flagged = true
            }
        """.trimIndent()
        val inBlock = LIBRARY + """

            rule Flag when Renew {
                for each loan in member.loans {
                    loan.flagged = true
                }
            }
        """.trimIndent()
        fun queueKeys(spec: String): List<String> {
            assertEquals(emptyList(), diagnosticsOf(spec))
            return DomainAnalysis(Model(Parser.parse(spec))).actDomains.getValue("Renew").renderKeys("renew").sorted()
        }
        assertEquals(queueKeys(throughCollection), queueKeys(inBlock))
        assertTrue(queueKeys(inBlock).isNotEmpty())
    }

    @Test
    fun `a block's running total draws the same check as a plain one`() {
        val plain = """
            act Loan { renewals: integer initially 0 }
            rule Extend when Loan on commit, Nightly {
                renewals = renewals + 1
            }
        """.trimIndent()
        val inBlock = """
            act Member { name: text }
            act Loan { member: one Member, renewals: integer initially 0 }
            rule Extend when Member on commit, Nightly {
                for each loan in loans {
                    loan.renewals = loan.renewals + 1
                }
            }
        """.trimIndent()
        assertEquals(listOf("V8"), diagnosticsOf(plain).map { diagnostic -> diagnostic.code })
        assertEquals(diagnosticsOf(plain).map { diagnostic -> diagnostic.message },
            diagnosticsOf(inBlock).map { diagnostic -> diagnostic.message })
        // on a commit alone, neither draws it
        assertEquals(emptyList(), diagnosticsOf(RENEW_LATE_LOANS))
    }

    // ── refusals ─────────────────────────────────────────────────────────────

    @Test
    fun `an assignment through an outer block's name is refused`() {
        val spec = SHELVES.replace("act Shelf { library: one Library, name: text }", "act Shelf { library: one Library, name: text, label: text initially \"\" }") + """

            rule LabelShelves when Relabel {
                for each shelf in library.shelfs {
                    for each book in shelf.books {
                        shelf.label = book.label
                    }
                }
            }
        """.trimIndent()
        assertEquals(listOf("[V34] rule 'LabelShelves' assigns 'shelf.label' within 'for each book in shelf.books', so one shelf " +
            "would be written once for each book; an assignment in a block writes a field of that block's own name, here 'book' (Reference §14)"),
            messagesOf(spec))
    }

    @Test
    fun `an assignment through a name that is not the block's is refused`() {
        val spec = LIBRARY.replace("act Member { name: text }", "act Member { name: text, lastRenewed: Date? }") + """

            rule Extend when Renew {
                for each loan in member.loans {
                    member.lastRenewed = today
                }
            }
        """.trimIndent()
        assertEquals(listOf("[V34] rule 'Extend' assigns 'member.lastRenewed' within 'for each loan in member.loans', which would write it once " +
            "for each loan; an assignment in a block writes a field of the block's own name, here 'loan', and any other is written " +
            "in the rule, outside the block (Reference §14)"),
            messagesOf(spec))
    }

    @Test
    fun `an assignment through a record the block's name reaches is refused`() {
        val spec = LIBRARY.replace("act Member { name: text }", "act Member { name: text, lastRenewed: Date? }") + """

            rule Extend when Renew {
                for each loan in member.loans {
                    loan.member.lastRenewed = today
                }
            }
        """.trimIndent()
        assertEquals(listOf("[V34] rule 'Extend' assigns 'loan.member.lastRenewed' within 'for each loan in member.loans', and " +
            "one record reached through 'loan' can be reached from more than one loan and written once for each; the left side is one " +
            "stored field of 'loan' (Reference §14)"),
            messagesOf(spec).filter { message -> message.startsWith("[V34]") })
    }

    @Test
    fun `an assignment in a block with two names is refused, with the spelling through the collection`() {
        val spec = LIBRARY.replace("transient act Renew { member: one Member }", "transient act Renew { member: one Member, times: integer }") + """

            rule Extend when Renew {
                for each loan in member.loans, step from 1 to times {
                    loan.flagged = true
                }
            }
        """.trimIndent()
        assertEquals(listOf("[V34] rule 'Extend' assigns 'loan.flagged' within 'for each loan in member.loans, step from 1 to times', " +
            "which has more than one name, so one loan is met more than once and 'loan.flagged' could be written twice in one commit; " +
            "an assignment stands in blocks of one name; to write 'flagged' on every member of 'member.loans', write " +
            "'member.loans.flagged = true' in the rule, outside the block (Reference §6)"),
            messagesOf(spec))
    }

    @Test
    fun `an assignment within a block over numbers is refused`() {
        val spec = LIBRARY.replace("transient act Renew { member: one Member }", "transient act Renew { member: one Member, times: integer }") + """

            rule Extend when Renew {
                for each step from 1 to times {
                    for each loan in member.loans {
                        loan.renewals = step
                    }
                }
            }
        """.trimIndent()
        assertEquals(listOf("[V34] rule 'Extend' assigns 'loan.renewals' within 'for each step from 1 to times', which runs over " +
            "numbers, not records, so 'loan.renewals' could be written once for each number; an assignment stands in blocks over " +
            "collections of records (Reference §14)"),
            messagesOf(spec))
    }

    private val TAGGED_SHELVES = """
        act Library { name: text }
        act Shelf { library: one Library, name: text, books: many Book }
        act Book { label: text initially "" }
        transient act Relabel { library: one Library }
    """.trimIndent()

    @Test
    fun `a nested block over a declared many is refused`() {
        val spec = TAGGED_SHELVES + """

            rule LabelBooks when Relabel {
                for each shelf in library.shelfs {
                    for each book in shelf.books {
                        book.label = shelf.name
                    }
                }
            }
        """.trimIndent()
        assertEquals(listOf("[V34] rule 'LabelBooks' assigns 'book.label' within 'for each book in shelf.books', and 'shelf.books' " +
            "is a declared 'many', so one book can be met under more than one shelf and written once for each; a block inside " +
            "another assigns only when it runs over the records that point at 'shelf' through a 'one' field (Reference §14)"),
            messagesOf(spec))
    }

    @Test
    fun `a nested block over a derived collection is refused`() {
        val spec = SHELVES.replace("act Shelf { library: one Library, name: text }",
            "act Shelf { library: one Library, name: text, unlabelled: many Book = (books where label == \"\") }") + """

            rule LabelBooks when Relabel {
                for each shelf in library.shelfs {
                    for each book in shelf.unlabelled {
                        book.label = shelf.name
                    }
                }
            }
        """.trimIndent()
        assertEquals(listOf("[V34] rule 'LabelBooks' assigns 'book.label' within 'for each book in shelf.unlabelled', and " +
            "'shelf.unlabelled' is derived, so one book can be met under more than one shelf and written once for each; a block " +
            "inside another assigns only when it runs over the records that point at 'shelf' through a 'one' field " +
            "(Reference §14)"),
            messagesOf(spec))
    }

    @Test
    fun `a nested block whose collection does not start from the outer name is refused`() {
        val spec = SHELVES + """

            rule LabelBooks when Relabel {
                for each shelf in library.shelfs {
                    for each book in Book {
                        book.label = shelf.name
                    }
                }
            }
        """.trimIndent()
        assertEquals(listOf("[V34] rule 'LabelBooks' assigns 'book.label' within 'for each book in Book', whose collection does " +
            "not start from 'shelf', so one book can be met under more than one shelf and written once for each; a block inside another assigns only when it runs " +
            "over the records that point at 'shelf' through a 'one' field (Reference §14)"),
            messagesOf(spec))
    }

    @Test
    fun `a nested block over a filter through the other side of a one reference is accepted`() {
        val spec = SHELVES + """

            rule LabelBooks when Relabel {
                for each shelf in library.shelfs {
                    for each book in (shelf.books where label == "") {
                        book.label = shelf.name
                    }
                }
            }
        """.trimIndent()
        assertEquals(emptyList(), diagnosticsOf(spec))
    }
}
