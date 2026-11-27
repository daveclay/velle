package velle

import java.time.Instant
import java.time.LocalDate
import velle.validation.conditionReadsRefTo
import velle.validation.readsReferenceTo
import velle.validation.refinementPolarities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Places where two parts of the compiler disagree about what one path in a spec means, pinned
 * before OQ225 resolves every path once into one model. Each test asserts what the compiler does
 * today and says in its comment which parts disagree and what the other part answers, so the
 * test is flipped when the parts are made to agree.
 */
class PathResolutionDisagreementTest {

    private fun accepted(result: CommitResult): Long {
        assertIs<CommitResult.Accepted>(result, "commit refused: $result")
        return result.id
    }

    // ── 1. narrowing kept under `this.target` by the validator, under `target` by the model ──

    private fun gallery(excerpt: String) = """
        act Post { body: text }
        act Photo { caption: text }
        shape Content = Post or Photo
        act Comment {
            target: one Content
            excerpt: text = $excerpt
        }
    """.trimIndent()

    private fun excerptTypeByTheModel(spec: String): VType? {
        val model = Model(Parser.parse(spec))
        val excerpt = model.membersOf("Comment").getValue("excerpt").derived!!.expr
        return model.types.of(excerpt, "Comment")
    }

    @Test
    fun `a test spelled with this narrows a read spelled without it, for the validator as for the model's typing`() {
        val sameSpelling = gallery("if this.target is Post then this.target.body else \"\"")
        val mixedSpelling = gallery("if this.target is Post then target.body else \"\"")

        // at the top of a condition `this.target` and `target` name one path, so the read of 'body' is on a Post
        assertEquals(emptyList(), Validator.validate(sameSpelling))
        assertEquals(emptyList(), Validator.validate(mixedSpelling))
        assertEquals(VType.Text, excerptTypeByTheModel(mixedSpelling))
    }

    @Test
    fun `a test spelled without this narrows a read spelled with it, for the validator as for the model's typing`() {
        val mixedSpelling = gallery("if target is Post then this.target.body else \"\"")

        assertEquals(emptyList(), Validator.validate(mixedSpelling))
        assertEquals(VType.Text, excerptTypeByTheModel(mixedSpelling))
    }

    @Test
    fun `the polarity walk reads this inside a filter as the subject, as the read summary does`() {
        val spec = """
            act Member {
                name: text
                flagged: boolean = count(warnings) > 0   -- Member's 'flagged' counts Warnings
            }
            act Warning { member: one Member }
            act Loan {
                member: one Member
                flagged: boolean = count(holds) > 0      -- Loan's 'flagged' counts Holds
            }
            act Hold { loan: one Loan }
            -- inside the filter over loans, 'this' is still the member (Reference §10)
            shape Watched = Member where exists (loans where this.flagged)
        """.trimIndent()
        assertEquals(emptyList(), Validator.validate(spec))
        val model = Model(Parser.parse(spec))

        // Model.collectExpr keeps 'this' as the subject: the refinement reads Member.flagged, so Warnings
        val summary = model.predicateSummary("Watched")
        assertTrue(("Warning" to "member") in summary.fields, "read summary: ${summary.fields}")
        assertFalse(summary.fields.any { (shape, _) -> shape == "Hold" }, "read summary: ${summary.fields}")

        // the polarity walk resolves 'this.flagged' on the subject too, so the signs name Warnings, not Holds
        val polarities = Validator(model).refinementPolarities("Watched")
        assertEquals(mapOf("Loan" to 1, "Warning" to 0), polarities.signByConsult)
        assertTrue(polarities.complete)
    }

    // ── 3. a bare name inside a nested filter: the subject for ExprTypes, the element elsewhere ──

    @Test
    fun `a bare collection inside a filter over another shape is typed on the element, as the model reads it, so the validator accepts the sum`() {
        val spec = """
            act Customer {
                name: text
                lines: many Remark                       -- the customer's own 'lines' hold Remarks
                bigOrders: integer = count(orders where sum(lines, amount) > 100)
            }
            act Remark { amount: text }
            act Order { customer: one Customer }         -- an order's inferred 'lines' hold Lines
            act Line { order: one Order, amount: decimal }
        """.trimIndent()

        // inside the filter over orders a bare 'lines' is the order's Lines, and 'amount' a decimal
        assertEquals(emptyList(), Validator.validate(spec))
        assertEquals(VType.Num("decimal"), Model(Parser.parse(spec)).types.of(Parser.parsePredicate("sum(lines, amount)"), "Order", TypeEnv(thisScope = "Customer")))

        // Model.collectionElement resolves the bare 'lines' on the innermost element, the Order
        val model = Model(Parser.parse(spec))
        assertEquals("Line", model.collectionElement(CollectionExpr(listOf(Binding(PathExpr("lines")))), "Order"))
    }

    @Test
    fun `a walk inside a filter over another shape is typed on the element by ExprTypes, as the model finds it`() {
        val spec = """
            act Customer { name: text }
            act Order { customer: one Customer, parent: one Order? }
            shape Deep = Customer where exists (orders where (walk parent until parent is none) is some)
        """.trimIndent()
        assertEquals(emptyList(), Validator.validate(spec))
        val model = Model(Parser.parse(spec))

        // both follow 'parent' on the element, the Order, not on the subject, the Customer
        assertEquals(VType.Coll("Order"), model.types.of(WalkExpr("parent"), "Order", TypeEnv(thisScope = "Customer")))
        assertEquals(VType.Coll("Order"), model.walkType(WalkExpr("parent"), "Order"))
    }

    // ── 4. a filter over two collections: the first for the transient check, the last elsewhere ──

    private fun accounts(bindings: String) = """
        act Account { name: text }
        act Payment { account: one Account, amount: decimal }
        act Charge { account: one Account, transient amount: decimal }   -- Charge's 'amount' is input only
        shape Matched = Account where exists ($bindings where amount > 5)
    """.trimIndent()

    @Test
    fun `the transient check reads a bare name in a filter over two collections on the last, so it accepts a read of its stored field`() {
        val spec = accounts("charges as charge, payments")

        // the bare 'amount' is the last binding's, Payment's, which is stored
        assertEquals(emptyList(), Validator.validate(spec))
        assertTrue(("Payment" to "amount") in Model(Parser.parse(spec)).predicateSummary("Matched").fields)
    }

    @Test
    fun `the transient check reads a bare name in a filter over two collections on the last, so it refuses a read of its transient field`() {
        val spec = accounts("payments as payment, charges")

        // the bare 'amount' is Charge's, which is in the act's input and not in its record
        assertEquals(listOf("V37"), Validator.validate(spec).map { diagnostic -> diagnostic.code })
        assertTrue(("Charge" to "amount") in Model(Parser.parse(spec)).predicateSummary("Matched").fields)
    }

    // ── 5. a write to a field taken with `is`: the taken shape for Domains, each taker for Model ──

    private fun reassignments(postDeclaration: String) = """
        act Member { name: text, credits: integer initially 0 }
        $postDeclaration
        transient act Reassign { post: one Post, receiver: one Member }
        rule Move when Reassign { post.author = receiver }
        -- wakes when a post's author changes, and writes the author's credits
        rule Credit when (Post where author.name == "Ada") on commit { author.credits = author.credits + 1 }
    """.trimIndent()

    private fun reassignKeys(spec: String): List<String> {
        assertEquals(emptyList(), Validator.validate(spec))
        return DomainAnalysis(Model(Parser.parse(spec))).actDomains.getValue("Reassign").renderKeys("reassign").sorted()
    }

    @Test
    fun `a read of a field declared on a taken shape widens over the taker read, not over every taker`() {
        val spec = """
            act Peptide { name: text, noted: boolean initially false }
            shape Trial { peptide: one Peptide }         -- GreenhouseTrial and FieldTrial take 'peptide'
            act GreenhouseTrial is Trial { bench: text }
            act FieldTrial is Trial { site: text }
            -- reads 'peptide' on GreenhouseTrials only, with no key back to the request
            rule Note when (Peptide where not noted and exists (GreenhouseTrial where peptide.name == "p1")) on commit { noted = true }
        """.trimIndent()
        assertEquals(emptyList(), Validator.validate(spec))

        // the read lands on GreenhouseTrial's records (Model.recordsHolding), as the read analysis records it
        val widened = DomainAnalysis(Model(Parser.parse(spec))).actDomains.values.flatMap { domain -> domain.widenings }.map { widening -> widening.shape }.toSet()
        assertTrue("FieldTrial" !in widened, widened.toString())
    }

    @Test
    fun `a write to a field declared on a taken shape wakes the rule it wakes when the field is declared on the act`() {
        val declaredOnPost = reassignments("act Post { title: text, author: one Member }")
        val takenFromContribution = reassignments("shape Contribution { author: one Member }\nact Post is Contribution { title: text }")

        // the write lands on Post's records either way (Model.recordsHolding), so Credit is woken
        // and the author it writes is keyed
        assertEquals(listOf("reassign.post", "reassign.post.author", "reassign.receiver"), reassignKeys(declaredOnPost))
        assertEquals(listOf("reassign.post", "reassign.post.author", "reassign.receiver"), reassignKeys(takenFromContribution))
    }

    // ── 6. a refinement named inside a watcher's `where`: not followed for correlations ──

    private fun finedNotices(condition: String) = """
        act Member { name: text }
        act Fine { member: one Member }
        shape Fined = Member where exists Fine for this
        shape FinedNotice { member: one Member }
        rule NoticeFined when $condition on commit { FinedNotice from { member: this } }
    """.trimIndent()

    private fun fineDomain(condition: String): SerializationDomain {
        val spec = finedNotices(condition)
        assertEquals(emptyList(), Validator.validate(spec))
        return DomainAnalysis(Model(Parser.parse(spec))).actDomains.getValue("Fine")
    }

    @Test
    fun `a refinement named inside a rule's where is followed for the subject's correlations, as the same refinement as the condition's shape is`() {
        val asTheShape = fineDomain("(Fined where name != \"\")")
        assertEquals(listOf("fine.member"), asTheShape.renderKeys("fine"))
        assertFalse(asTheShape.wide, "widenings: ${asTheShape.widenings}")

        // a bare 'Fined' and 'this is Fined' test the subject against the refinement, so the route
        // from a Fine back to its member is followed and the domain does not widen
        for (condition in listOf("(Member where Fined and name != \"\")", "(Member where this is Fined and name != \"\")")) {
            val insideTheWhere = fineDomain(condition)
            assertFalse(insideTheWhere.widenings.any { widening -> widening.shape == "Fine" }, "$condition widenings: ${insideTheWhere.widenings}")
        }
    }

    // ── 7. a `for each` name as a path root: an unknown step for Domains' body routes ──

    private fun annotations(scan: String) = """
        act Branch { name: text }
        act Account {
            branch: one Branch
            risky: boolean = exists (Transfer where source == this)   -- correlates Transfers on 'source'
        }
        -- two references to Account, so no inverse is inferred onto it
        act Transfer { source: one Account, target: one Account, note: text initially "" }
        shape Report { account: one Account, risky: boolean }
        transient act Annotate { transfer: one Transfer, note: text }
        rule Note when Annotate { transfer.note = note }
        $scan
    """.trimIndent()

    private fun annotateKeys(scan: String): List<String> {
        val spec = annotations(scan)
        assertEquals(emptyList(), Validator.validate(spec))
        return DomainAnalysis(Model(Parser.parse(spec))).actDomains.getValue("Annotate").renderKeys("annotate").sorted()
    }

    @Test
    fun `a read through a for each name gives the serialization domain the correlation route the same read through the subject does`() {
        val throughTheSubject = "rule Scan when Account on commit { Report from { account: this, risky: risky } }"
        val throughALoopName = "rule Scan when Branch on commit { for each account in accounts { Report from { account: account, risky: account.risky } } }"

        // a body reading 'risky' correlates Transfers on 'source', so a write to a Transfer keys its source
        assertEquals(listOf("annotate.transfer", "annotate.transfer.source"), annotateKeys(throughTheSubject))
        // the loop name 'account' starts the path at its element, an Account (Model.namesBoundIn)
        assertEquals(listOf("annotate.transfer", "annotate.transfer.source"), annotateKeys(throughALoopName))
    }

    // ── 8. a union-typed reference: no store pre-filter for Query, a match for the Evaluator ──

    private val comments = """
        act Post { body: text }
        act Photo { caption: text }
        shape Content = Post or Photo
        act Comment { target: one Content }
        shape Discussed = Post where exists Comment for this
        shape DiscussedByFilter = Post where exists (Comment where target == this)
    """.trimIndent()

    private fun preFilter(spec: String, refinement: String): QF =
        QueryCompiler(Model(Parser.parse(spec)), LocalDate.of(2026, 1, 1), Instant.parse("2026-01-01T00:00:00Z")).filterFor(RefName(refinement))

    @Test
    fun `exists for this over a union-typed reference compiles to the store pre-filter a plain reference does, as the Evaluator matches it`() {
        assertEquals(emptyList(), Validator.validate(comments))
        val plainReference = comments.replace("target: one Content", "target: one Post")
        assertEquals(QF.Exists("Comment", "target", QF.True), preFilter(plainReference, "Discussed"))
        assertEquals(QF.Exists("Comment", "target", QF.True), preFilter(plainReference, "DiscussedByFilter"))

        // a reference typed Content holds Posts (Model.referencesRecordsOf), so both forms correlate on 'target'
        assertEquals(QF.Exists("Comment", "target", QF.True), preFilter(comments, "Discussed"))
        assertEquals(QF.Exists("Comment", "target", QF.True), preFilter(comments, "DiscussedByFilter"))

        // the Evaluator matches the reference by the bases of its declared type
        val system = VelleSystem(Model(Parser.parse(comments)))
        system.scope {
            val post = accepted(system.commit("Post", mapOf("body" to "hello")))
            accepted(system.commit("Comment", mapOf("target" to post)))
            assertTrue(system.isMember(post, "Discussed"))
            assertTrue(system.isMember(post, "DiscussedByFilter"))
        }
    }

    // ── 9. a union-typed reference: no read through it for ReadConflicts, a read for PossibleCommits ──

    @Test
    fun `a read of a union-typed reference is a read of a reference to its operand, for the read conflicts as for the possible commits`() {
        val spec = """
            act Post { body: text }
            act Photo { caption: text }
            shape Content = Post or Photo
            act Comment { target: one Content, body: text }
            shape Note { comment: one Comment }
            rule Noted when (Comment where target.id != none) { Note from { comment: this } }
        """.trimIndent()
        assertEquals(emptyList(), Validator.validate(spec))
        val model = Model(Parser.parse(spec))
        val validator = Validator(model)
        val rule = model.rules.getValue("Noted")
        val conditionReads = model.summaryOfRefExpr(rule.condition)
        assertEquals(setOf("Comment" to "target"), conditionReads.fields.toSet())

        // both ask whether a field typed Content holds Posts (Model.referencesRecordsOf)
        assertTrue(validator.readsReferenceTo(conditionReads, "Post"))
        assertTrue(validator.conditionReadsRefTo(rule, "Post"))
    }
}
