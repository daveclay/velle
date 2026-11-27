package velle.spec

import velle.Model
import velle.Parser
import velle.PathExpr
import velle.Seg
import velle.VType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PathResolverTest {

    private val library = """
        act Member {
            name: text
            branch: one Branch
        }

        act Branch { name: text }

        shape Borrowing {
            member: one Member
            dueOn: Date
        }

        act Loan is Borrowing { title: text }

        act Rental is Borrowing { fee: integer }

        shape LoanOfLateMember = Loan where member.name == "late"

        shape Lending = Loan or Rental

        act Complaint { about: one Lending }
    """.trimIndent()

    private val resolver = PathResolver(Model(Parser.parse(library)))

    private fun path(text: String): PathExpr {
        val names = text.split(".")
        return PathExpr(names.first(), names.drop(1).map { name -> Seg(name, viaQdot = false) })
    }

    @Test
    fun `a bare name is a member of the element and its next name is reached through a reference to one record`() {
        val resolved = resolver.resolve(path("member.name"), Scope("Loan"))

        assertEquals(PathStart.Element("Loan"), resolved.start)
        assertEquals(listOf("member" to Reach.START, "name" to Reach.ONE_REFERENCE), resolved.steps.map { step -> step.name to step.reach })
        assertEquals(listOf("Loan", "Member"), resolved.steps.map { step -> step.readOn })
        assertEquals(VType.Text, resolved.endType)
    }

    @Test
    fun `a field declared on a shape loan takes is found on loan and names the shape that declares it`() {
        val resolved = resolver.resolve(path("member"), Scope("Loan"))

        assertEquals("Loan", resolved.steps.single().readOn)
        assertEquals("Borrowing", resolved.steps.single().member.owner)
    }

    @Test
    fun `an inferred inverse is a collection and the name after it is reached through that collection`() {
        val resolved = resolver.resolve(path("borrowings.dueOn"), Scope("Member"))

        assertEquals(listOf(Reach.START, Reach.COLLECTION), resolved.steps.map { step -> step.reach })
        assertTrue(resolved.steps.first().member.inverse != null)
    }

    @Test
    fun `this names the subject while a bare name inside a filter names the element`() {
        val insideFilter = Scope(element = "Loan", subject = "Member")

        assertEquals(PathStart.Subject("Member"), resolver.resolve(path("this.branch"), insideFilter).start)
        assertEquals("Branch", resolver.resolve(path("this.branch"), insideFilter).endShape)
        assertEquals(PathStart.Element("Loan"), resolver.resolve(path("member"), insideFilter).start)
    }

    @Test
    fun `a name bound to a record starts the path at that record`() {
        val resolved = resolver.resolve(path("borrower.name"), Scope("Loan").withRecordName("borrower", "Member"))

        assertEquals(PathStart.RecordName("borrower", "Member"), resolved.start)
        assertEquals("Member", resolved.steps.single().readOn)
    }

    @Test
    fun `a name bound to a value has nothing after it that resolves`() {
        val resolved = resolver.resolve(path("amount.cents"), Scope("Loan").withValueName("amount"))

        assertEquals(PathStart.ValueName("amount"), resolved.start)
        assertEquals("cents", resolved.unresolved?.name)
        assertNull(resolved.endType)
    }

    @Test
    fun `a refinement named on its own is a shape name with no type of its own`() {
        val resolved = resolver.resolve(path("LoanOfLateMember"), Scope("Loan"))

        assertEquals(PathStart.ShapeName("LoanOfLateMember"), resolved.start)
        assertTrue(resolved.resolves)
        assertNull(resolved.endType)
    }

    @Test
    fun `a name that is no member stops the path and says which shape it was looked for on`() {
        val resolved = resolver.resolve(path("member.nickname"), Scope("Loan"))

        assertEquals(UnresolvedStep("nickname", "Member"), resolved.unresolved)
        assertEquals(listOf("member"), resolved.steps.map { step -> step.name })
        assertNull(resolved.endType)
    }

    @Test
    fun `a name after a value has no shape to be looked for on`() {
        val resolved = resolver.resolve(path("dueOn.year"), Scope("Loan"))

        assertEquals(UnresolvedStep("year", null), resolved.unresolved)
    }

    @Test
    fun `after about is loan a name only loan declares is looked up on loan`() {
        val notNarrowed = resolver.resolve(path("about.title"), Scope("Complaint"))
        val narrowed = resolver.resolve(path("about.title"), Scope("Complaint").narrowedBy(mapOf(listOf("about") to "Loan")))

        assertEquals(UnresolvedStep("title", "Lending"), notNarrowed.unresolved)
        assertEquals("Loan", narrowed.steps.last().readOn)
        assertEquals(VType.Text, narrowed.endType)
    }

    @Test
    fun `after this is loan a name only loan declares is looked up on loan`() {
        val resolved = resolver.resolve(path("this.title"), Scope("Borrowing").narrowedBy(mapOf(emptyList<String>() to "Loan")))

        assertEquals("Loan", resolved.steps.single().readOn)
        assertEquals(VType.Text, resolved.endType)
    }
}
