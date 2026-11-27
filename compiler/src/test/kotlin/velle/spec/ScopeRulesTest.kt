package velle.spec

import velle.Binary
import velle.ExistsExpr
import velle.Expr
import velle.ForEach
import velle.IfExpr
import velle.Model
import velle.Parser
import velle.WalkExpr
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScopeRulesTest {

    private val library = """
        act Member {
            name: text
            branch: one Branch
            watched: boolean = exists (loans where branch == this.branch)
            late: boolean = exists (loans where LateLoan and lateness > 0)
            captioned: boolean = exists (loans as loan, Photo where loan.title == caption)
            unreadable: boolean = exists (name where this.name == "x")
        }

        act Branch { name: text }

        act Post { body: text }

        act Photo { caption: text }

        shape Content = Post or Photo

        act Loan {
            member: one Member
            branch: one Branch
            about: one Content
            title: text
            options: many text
            renewedFrom: one Loan?
            aboutBody: text = if about is Post then about.body else ""
            postWithBody: boolean = about is Post and about.body == ""
            photoOrBody: boolean = about is Photo or about.body == ""
            picked: boolean = exists (options as option where option == title)
            first: one Loan? = walk renewedFrom until title == "first"
            firstOfPost: one Loan? = if about is Post then (walk renewedFrom until title == "first") else none
        }

        shape LateLoan = Loan where title == "late" {
            lateness: integer = 1
        }

        shape Reminder {
            loan: one Loan
            wording: text
            copy: integer
        }

        rule Remind when (Member where name == "forgetful") {
            for each loan in (loans where LateLoan), wording in loan.options, copy from 1 to 2 {
                Reminder from { loan: loan, wording: wording, copy: copy }
            }
        }
    """.trimIndent()

    private val model = Model(Parser.parse(library))
    private val rules = model.scopes

    private fun derived(shape: String, name: String): Expr = model.membersOf(shape).getValue(name).derived!!.expr

    private fun filterOf(shape: String, name: String): FilterScopes =
        rules.ofFilter((derived(shape, name) as ExistsExpr).collection!!, Scope(shape))

    @Test
    fun `a filter's where reads a bare name on the element while this stays the subject`() {
        val scopes = filterOf("Member", "watched")

        assertEquals("Loan", scopes.element)
        assertEquals("Loan", scopes.where.element)
        assertEquals("Member", scopes.where.subject)
        assertTrue(scopes.where.inFilter)
    }

    @Test
    fun `a filter that names a refinement reads its where on that refinement's members`() {
        val scopes = filterOf("Member", "late")

        assertEquals("LateLoan", scopes.element)
        assertEquals("LateLoan", scopes.where.element)
    }

    @Test
    fun `each binding's source reads the names of the bindings before it and the where reads them all on the last element`() {
        val scopes = filterOf("Member", "captioned")

        assertEquals(listOf(emptyMap(), mapOf("loan" to "Loan")), scopes.sources.map { source -> source.recordNames })
        assertEquals("Photo", scopes.where.element)
        assertEquals(mapOf("loan" to "Loan"), scopes.where.recordNames)
    }

    @Test
    fun `a many of values filtered by naming each value binds the name to a value and leaves the element`() {
        val scopes = filterOf("Loan", "picked")

        assertNull(scopes.element)
        assertEquals(setOf("option"), scopes.where.valueNames)
        assertEquals("Loan", scopes.where.element)
    }

    @Test
    fun `a source that holds no records leaves the element untold`() {
        val scopes = filterOf("Member", "unreadable")

        assertNull(scopes.element)
        assertEquals("Member", scopes.where.element)
    }

    @Test
    fun `the then branch reads what the test shows and the else branch what its failing shows`() {
        val conditional = derived("Loan", "aboutBody") as IfExpr
        val scope = Scope("Loan")

        assertEquals(mapOf(listOf("about") to "Post"), rules.ofThen(conditional, scope).narrowed)
        assertEquals(mapOf(listOf("about") to "Photo"), rules.ofElse(conditional, scope).narrowed)
    }

    @Test
    fun `the right side of an and reads what its left side shows`() {
        val conjunction = derived("Loan", "postWithBody") as Binary

        assertEquals(mapOf(listOf("about") to "Post"), rules.ofRightSide(conjunction, Scope("Loan")).narrowed)
    }

    @Test
    fun `the right side of an or reads what its left side failing shows`() {
        val disjunction = derived("Loan", "photoOrBody") as Binary

        assertEquals(mapOf(listOf("about") to "Post"), rules.ofRightSide(disjunction, Scope("Loan")).narrowed)
    }

    @Test
    fun `a walk's until reads this and a bare name on the record reached`() {
        val walk = derived("Loan", "first") as WalkExpr
        val insideFilterOverLoans = Scope("Member").withRecordName("holder", "Member").inside("Loan")

        val until = rules.ofUntil(walk, insideFilterOverLoans)

        assertEquals("Loan", until.element)
        assertEquals("Loan", until.subject)
        assertEquals(mapOf("holder" to "Member"), until.recordNames)
    }

    @Test
    fun `a walk's until drops what a test showed about the record the walk starts from`() {
        val walk = ((derived("Loan", "firstOfPost") as IfExpr).thenExpr) as WalkExpr
        val narrowedStart = Scope("Loan").withRecordName("holder", "Member")
            .narrowedBy(mapOf(listOf("about") to "Post", listOf("holder") to "Member"))

        assertEquals(mapOf(listOf("holder") to "Member"), rules.ofUntil(walk, narrowedStart).narrowed)
    }

    @Test
    fun `a for each name is bound to what it runs over and each source reads the names before it`() {
        val block = model.rules.getValue("Remind").body.single() as ForEach

        val body = rules.ofBlock(block, Scope("Member"))

        assertEquals(mapOf("loan" to "LateLoan"), body.recordNames)
        assertEquals(setOf("wording", "copy"), body.valueNames)
        assertEquals("Member", body.element)
    }

    @Test
    fun `a for each name over a filter runs over the records the filter holds, over a many of values over values, over a range over numbers`() {
        val bindings = (model.rules.getValue("Remind").body.single() as ForEach).bindings
        val withLoan = Scope("Member").withRecordName("loan", "LateLoan")

        assertEquals(BoundElement.Records("LateLoan"), rules.elementOfLoop(bindings[0].source, Scope("Member")))
        assertEquals(BoundElement.Values, rules.elementOfLoop(bindings[1].source, withLoan))
        assertEquals(BoundElement.Unknown, rules.elementOfLoop(bindings[1].source, Scope("Member")))
        assertEquals(BoundElement.Values, rules.elementOfLoop(bindings[2].source, Scope("Member")))
    }
}
