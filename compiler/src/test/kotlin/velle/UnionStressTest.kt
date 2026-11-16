package velle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Adversarial probes of a static union (Reference §9), one per case of OQ44.
 * Each test records what the compiler and runtime do today; a test that pins
 * a gap says so in its name, and is rewritten when the gap closes.
 */
class UnionStressTest {

    private fun diags(source: String): List<Diagnostic> = Validator.validate(source)
    private fun codes(source: String): List<String> = diags(source).map { it.code }

    private fun system(source: String): VelleSystem {
        val d = diags(source)
        check(d.isEmpty()) { d.toString() }
        return VelleSystem(Model(Parser.parse(source)))
    }

    private fun VelleSystem.mustCommit(
        shape: String,
        fields: Map<String, Any?>,
        parts: Map<String, List<Map<String, Any?>>> = emptyMap(),
    ): Long {
        val r = commit(shape, fields, parts)
        return assertIs<CommitResult.Accepted>(r, "commit of $shape refused: $r").id
    }

    private val content = """
        act Member { name: text unique }
        act Post { author: one Member, body: text }
        act Photo { author: one Member, caption: text }
        shape Content = Post or Photo
    """.trimIndent()

    // ── runtime ──────────────────────────────────────────────────────────────

    @Test
    fun `a union-typed edge set holds records of each operand, and each operand gets the inverse`() {
        val sys = system(content + "\nact Collection { name: text, items: many Content }")
        sys.scope {
            val ada = sys.mustCommit("Member", mapOf("name" to "Ada"))
            val post = sys.mustCommit("Post", mapOf("author" to ada, "body" to "hello"))
            val photo = sys.mustCommit("Photo", mapOf("author" to ada, "caption" to "sunset"))
            val album = sys.mustCommit("Collection", mapOf("name" to "mine", "items" to listOf(post, photo)))
            assertEquals(setOf(post, photo), (sys.get(album, "items") as List<*>).toSet())
            assertEquals(listOf(album), sys.get(post, "collections"))
            assertEquals(listOf(album), sys.get(photo, "collections"))
            val outside = sys.commit("Collection", mapOf("name" to "no", "items" to listOf(ada)))
            assertTrue(assertIs<CommitResult.Refused>(outside).reason.startsWith("type:"), outside.toString())
        }
    }

    @Test
    fun `a closure over the inverse of a union-typed field fills the part's reference with the new operand`() {
        val sys = system("""
            act Member { name: text unique }
            act Post { author: one Member, body: text } with comments
            act Photo { author: one Member, caption: text }
            shape Content = Post or Photo
            act Comment { target: one Content, note: text }
        """.trimIndent())
        sys.scope {
            val ada = sys.mustCommit("Member", mapOf("name" to "Ada"))
            val post = sys.mustCommit("Post", mapOf("author" to ada, "body" to "hello"), mapOf("comments" to listOf(mapOf("note" to "first"))))
            val comment = sys.instancesOf("Comment").single()
            assertEquals(post, sys.get(comment, "target"))
            assertEquals(listOf(comment), sys.get(post, "comments"))
        }
    }

    @Test
    fun `deleting an operand record absorbs through a union-typed reference and fires when deleted over the union`() {
        val sys = system(content + """

            act Comment { target: one Content? initially required, note: text }
            act RemovePost { post: one Post? initially required }
            rule Remove when RemovePost { delete post }
            shape Tombstone { note: text }
            rule Mark when deleted Content { Tombstone from { note: "gone" } }
            shape Orphan = Comment where target is none
            rule Sweep when Orphan { delete this }
        """.trimIndent())
        sys.scope {
            val ada = sys.mustCommit("Member", mapOf("name" to "Ada"))
            val post = sys.mustCommit("Post", mapOf("author" to ada, "body" to "hello"))
            val photo = sys.mustCommit("Photo", mapOf("author" to ada, "caption" to "sunset"))
            sys.mustCommit("Comment", mapOf("target" to post, "note" to "on the post"))
            val kept = sys.mustCommit("Comment", mapOf("target" to photo, "note" to "on the photo"))
            sys.mustCommit("RemovePost", mapOf("post" to post))
            assertEquals(emptyList(), sys.instancesOf("Post"))
            assertEquals(1, sys.instancesOf("Tombstone").size, "when deleted Content fired once for the post")
            assertEquals(listOf(kept), sys.instancesOf("Comment"), "the comment whose target went absent was swept")
        }
    }

    @Test
    fun `gap - a walk through a union-typed reference fails at runtime on an operand that lacks the relationship`() {
        val sys = system("""
            act Alert { title: text }
            act Escalation { source: one Trail, reason: text }
            shape Trail = Alert or Escalation
            shape Chain = Escalation {
                above: many Trail = walk source
                nearestAlert: one Trail? = walk source until source is Alert
            }
        """.trimIndent())
        sys.scope {
            val alert = sys.mustCommit("Alert", mapOf("title" to "smoke"))
            val first = sys.mustCommit("Escalation", mapOf("source" to alert, "reason" to "no answer"))
            val second = sys.mustCommit("Escalation", mapOf("source" to first, "reason" to "still none"))
            val failure = runCatching { sys.getAs(second, "Chain", "above") }.exceptionOrNull()
            assertIs<VelleRuntimeError>(failure, "the walk reads 'source' on the alert it reached")
            assertTrue("'source' is not readable on 'Alert'" in failure.message!!, failure.message!!)
        }
    }

    @Test
    fun `gap - a singular reference beside a union-typed and an operand-typed field has no diagnostic and fails on the first read`() {
        val sys = system(content + """

            act Comment { target: one Content, post: one Post, note: text }
            shape Summary = Post { latest: one Comment? = (Comment for this) }
        """.trimIndent())
        sys.scope {
            val ada = sys.mustCommit("Member", mapOf("name" to "Ada"))
            val targeted = sys.mustCommit("Post", mapOf("author" to ada, "body" to "a"))
            val posted = sys.mustCommit("Post", mapOf("author" to ada, "body" to "b"))
            sys.mustCommit("Comment", mapOf("target" to targeted, "post" to posted, "note" to "hi"))
            // no diagnostic chose a field: the evaluator asks for the single field of Comment that
            // accepts a Post, finds 'target' and 'post', and the first read throws
            val failure = runCatching { sys.getAs(posted, "Summary", "latest") }.exceptionOrNull()
            assertIs<IllegalArgumentException>(failure, "the read should have failed on two candidate fields")
        }
    }

    // ── the checks that skip a cross-base refinement (gaps) ──────────────────

    @Test
    fun `gap - V12 proves at-most-one over a base and skips the same refinement over a union`() {
        val overBase = """
            act Member { name: text unique }
            act Post { author: one Member, body: text }
            act Comment { target: one Post, note: text }
            shape Popular = Post where count(comments) > 3
            shape Spotlight = Member { featured: one Post? = (Popular for this) }
        """.trimIndent()
        assertTrue("V12" in codes(overBase), diags(overBase).toString())
        val overUnion = """
            act Member { name: text unique }
            shape Contribution { author: one Member }
            act Post is Contribution { body: text }
            act Photo is Contribution { caption: text }
            shape Content = Post or Photo
            act Comment { target: one Content, note: text }
            shape Popular = Content where count(comments) > 3
            shape Spotlight = Member { featured: one Content? = (Popular for this) }
        """.trimIndent()
        assertEquals(emptyList(), diags(overUnion), "the same proof is skipped over the union")
    }

    @Test
    fun `gap - V24 sees a required operand-typed referrer and not a required union-typed one`() {
        val deletion = """

            act RemovePost { post: one Post? initially required }
            rule Remove when RemovePost { delete post }
        """.trimIndent()
        val operandTyped = content + "\nact Comment { target: one Post, note: text }\n" + deletion
        assertTrue("V24" in codes(operandTyped), diags(operandTyped).toString())
        val unionTyped = content + "\nact Comment { target: one Content, note: text }\n" + deletion
        assertEquals(emptyList(), diags(unionTyped), "the stranded union-typed referrer is not seen")
    }

    @Test
    fun `gap - V10 sees a rule writing what a never over a base reads, and not one over a union`() {
        val overBase = """
            act Member { name: text unique }
            act Post { author: one Member, body: text, views: integer initially 0 }
            never (Post where views < 0)
            act Unview { post: one Post }
            rule Drop when Unview { post.views = post.views - 1 }
        """.trimIndent()
        assertTrue("V10" in codes(overBase), diags(overBase).toString())
        val overUnion = """
            act Member { name: text unique }
            shape Viewed { views: integer initially 0 }
            act Post is Viewed { author: one Member, body: text }
            act Photo is Viewed { author: one Member, caption: text }
            shape Content = Post or Photo
            never (Content where views < 0)
            act Unview { post: one Post }
            rule Drop when Unview { post.views = post.views - 1 }
        """.trimIndent()
        assertEquals(emptyList(), diags(overUnion), "the never's read of the shared field is not matched to the rule's write of Post.views")
    }

    @Test
    fun `gap - the predicate of a refinement or never over a union is not name-checked`() {
        assertEquals(emptyList(), diags(content + "\nshape Odd = Content where bogus is empty"))
        val perOperand = """
            act Member { name: text unique }
            act Post { author: one Member, body: text, views: integer }
            act Photo { author: one Member, caption: text, views: integer }
            shape Content = Post or Photo
            never (Content where views < 0)
        """.trimIndent()
        assertEquals(emptyList(), diags(perOperand), "a field the operands declare separately is read by name at runtime")
    }

    @Test
    fun `gap - narrowing by is not, and the residual else of a three-operand union, are refused`() {
        val isNot = content + "\nact Comment { target: one Content, note: text }\n" +
            "shape Excerpt = Comment { label: text = if target is not Post then target.caption else target.body }"
        assertTrue(diags(isNot).any { it.code == "F1" && "'caption'" in it.message }, diags(isNot).toString())
        val residual = """
            act Member { name: text unique }
            act Post { author: one Member, body: text }
            act Photo { author: one Member, caption: text }
            act Clip { author: one Member, title: text }
            shape Content = Post or Photo or Clip
            act Comment { target: one Content, note: text }
            shape Excerpt = Comment { label: text = if target is Post then target.body else if target is Photo then target.caption else target.title }
        """.trimIndent()
        assertEquals(listOf("F1"), codes(residual), diags(residual).toString())
        assertTrue(diags(residual).single().message.contains("'title'"))
    }

    @Test
    fun `narrowing inside a collection filter and on a filter's source path is licensed`() {
        val inFilter = content + "\nact Comment { target: one Content, note: text }\n" +
            "shape Busy = Member { longComments: integer = count(Comment where target is Post and target.body != \"\") }"
        assertEquals(emptyList(), diags(inFilter))
        val sourcePath = """
            act Member { name: text unique }
            act Post { author: one Member, body: text, tags: many text }
            act Photo { author: one Member, caption: text }
            shape Content = Post or Photo
            act Comment { target: one Content, note: text }
            shape Tagged = Comment { tagCount: integer = if target is Post then count(target.tags) else 0 }
        """.trimIndent()
        assertEquals(emptyList(), diags(sourcePath))
    }

    @Test
    fun `a union of refinements generates no view of its own`() {
        val composite = content + """

            shape LongPost = Post where length(body) > 10
            shape Captioned = Photo where caption != ""
            shape Notable = LongPost or Captioned
            shape Twice = Post or Post
            shape Mixed = Content or Post
            shape Notice { about: one Content }
            rule Note when Notable { Notice from { about: this } }
        """.trimIndent()
        assertEquals(emptyList(), diags(composite))
        val kotlin = Codegen.generate(composite, "Ex")
        assertTrue("fun twices()" in kotlin && "fun mixeds()" in kotlin, "a degenerate union still gets a view")
        assertTrue("fun notables()" !in kotlin, "a union of refinements has no base and no static union to name a view after")
    }

    @Test
    fun `gap - the element of a refinement-filtered collection is its base, not the refinement`() {
        val src = """
            act Member { name: text unique }
            act Claim { member: one Member, amount: integer, paid: boolean }
            shape PaidClaim = Claim where paid { patientPays: integer = amount / 2 }
            shape Account = Member { owed: integer = sum(claims where PaidClaim, patientPays) }
        """.trimIndent()
        assertTrue(diags(src).any { "'Claim' has no member 'patientPays'" in it.message }, diags(src).toString())
    }

    @Test
    fun `gap - the diagrams omit a static union and a refinement over it`() {
        val gallery = java.io.File("../examples/gallery/gallery.velle").readText()
        val doc = DiagramGen.generate(gallery, "Gallery")
        assertTrue("class Contribution {" in doc, "a shape other shapes take has a box")
        assertTrue("class Content {" !in doc, "the union has no box of its own")
        assertTrue("class Popular {" !in doc, "the refinement over the union has no box")
        assertTrue("**Popular**" !in doc, "the refinement over the union has no state-flow axis")
    }

    @Test
    fun `gap - creating a union names it an unknown shape`() {
        val src = content + "\nact Flag { about: one Content }\nrule Make when Flag { Content from { author: about.author } }"
        assertTrue(diags(src).any { "creates unknown shape 'Content'" in it.message }, diags(src).toString())
    }
}
