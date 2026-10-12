package velle

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Cross-base unions (README §9 extended across unrelated shapes; OQ29): the
 * validator's union rules, and the runtime driven end-to-end through
 * gallery.velle.
 */
class UnionTest {

    private fun codes(source: String): List<String> = Validator.validate(source).map { it.code }
    private fun diags(source: String): List<Diagnostic> = Validator.validate(source)

    private val base = """
        act Member { name: text }
        act Post { author: one Member, body: text }
        act Photo { author: one Member, caption: text }
    """.trimIndent()

    // ── declarations ─────────────────────────────────────────────────────────

    @Test
    fun `a static union is a legal field type, and infers its inverse onto every operand`() {
        val src = base + """
            shape Content = Post or Photo
            act Comment { target: one Content, body: text }
            shape Busy = Post where count(comments) > 1
        """.trimIndent()
        assertEquals(emptyList(), diags(src))
        val model = Model(Parser.parse(src))
        assertTrue(model.isStaticUnion("Content"))
        assertEquals(setOf("Post", "Photo"), model.basesOf("Content"))
        assertTrue("comments" in model.membersOf("Photo"), "inverse on each operand")
        assertTrue("comments" in model.membersOf("Content"), "the one shared member")
        assertTrue("author" !in model.membersOf("Content"), "two declarations that agree are not shared (README §9)")
    }

    @Test
    fun `a union carrying a predicate is not a field type`() {
        val src = base + """
            shape Unpublished = Photo where caption == ""
            shape Content = Post or Unpublished
            act Comment { target: one Content, body: text }
        """.trimIndent()
        assertTrue("F2" in codes(src), "got: ${diags(src)}")
    }

    @Test
    fun `a direct read of an unshared name on the union is refused, a narrowed read is licensed`() {
        val direct = base + """
            shape Content = Post or Photo
            act Comment { target: one Content, body: text }
            shape Odd = Comment where target.author is some
        """.trimIndent()
        assertTrue("F1" in codes(direct), "got: ${diags(direct)}")

        val narrowed = base + """
            shape Content = Post or Photo
            act Comment { target: one Content, body: text }
            shape OnOwnPost = Comment where target is Post and target.author is some
            shape Notice { excerpt: text }
            rule Note when Comment {
                Notice from { excerpt: if target is Post then target.body else target.caption }
            }
        """.trimIndent()
        assertEquals(emptyList(), diags(narrowed))
    }

    @Test
    fun `a union body admits derived properties only`() {
        val src = base + """
            shape Content = Post or Photo {
                frozen body
                captured seenOn: DateTime = now
                tally: integer = 1 + 1
            }
        """.trimIndent()
        val f3 = diags(src).filter { it.code == "F3" }
        assertEquals(2, f3.size, "got: ${diags(src)}")
    }

    @Test
    fun `a union with a transient operand is refused until OQ35`() {
        val src = base + """
            transient act ChangeName { member: one Member, newName: text }
            transient act ChangeEmail { member: one Member, newEmail: text }
            shape ProfileChange = ChangeName or ChangeEmail
            shape Touch { touchedOn: DateTime }
            rule TouchProfile when ProfileChange { Touch from { touchedOn: now } }
            rule Rename when ChangeName { Touch from { touchedOn: now } }
            rule Remail when ChangeEmail { Touch from { touchedOn: now } }
        """.trimIndent()
        assertTrue("V17" in codes(src), "got: ${diags(src)}")
    }

    @Test
    fun `an inline cross-base condition must be named`() {
        val src = base + """
            shape Touch { touchedOn: DateTime }
            rule Note when (Post or Photo) { Touch from { touchedOn: now } }
        """.trimIndent()
        assertTrue("F2" in codes(src), "got: ${diags(src)}")
    }

    @Test
    fun `a membership test with no base in common is refused, and a cross-base and is flagged empty`() {
        val src = base + """
            shape Odd = Post where author is Photo
        """.trimIndent()
        assertTrue("F2" in codes(src), "got: ${diags(src)}")
        val empty = base + "\nshape Nothing = Post and Photo\n"
        assertTrue(Validator.advisories(empty).any { it.code == "A2" })
    }

    @Test
    fun `a union rule and an operand rule writing one field are not disjoint`() {
        val src = base + """
            shape Content = Post or Photo
            act Touch { post: one Post }
            rule A when Touch { post.body = "a" }
            rule B when Touch { post.body = "b" }
        """.trimIndent()
        assertTrue("V1" in codes(src), "same trigger: ${diags(src)}")
    }

    // ── the runtime, through gallery.velle ───────────────────────────────────

    private fun newSystem(): VelleSystem {
        val source = File("../examples/gallery/gallery.velle").readText()
        val d = Validator.validate(source)
        check(d.isEmpty()) { d.toString() }
        return VelleSystem(Model(Parser.parse(source)))
    }

    private fun VelleSystem.mustCommit(shape: String, vararg fields: Pair<String, Any?>): Long {
        val r = commit(shape, fields.toMap())
        return assertIs<CommitResult.Accepted>(r, "commit of $shape refused: $r").id
    }

    @Test
    fun `gallery - union membership, the shared inverse, narrowing, and the boundary`() {
        val sys = newSystem()
        val ada = sys.mustCommit("Member", "name" to "Ada")
        val grace = sys.mustCommit("Member", "name" to "Grace")
        val post = sys.mustCommit("Post", "author" to ada, "body" to "hello")
        val photo = sys.mustCommit("Photo", "author" to ada, "caption" to "sunset")

        assertTrue(sys.isMember(post, "Content"))
        assertTrue(sys.isMember(photo, "Content"))
        assertEquals(setOf(post, photo), sys.instancesOf("Content").toSet())

        val c1 = sys.mustCommit("Comment", "target" to post, "author" to grace, "body" to "hi")
        val c2 = sys.mustCommit("Comment", "target" to photo, "author" to grace, "body" to "lovely")
        assertEquals(listOf(c1), sys.get(post, "comments"))
        assertEquals(listOf(c2), sys.get(photo, "comments"))
        assertEquals(java.math.BigDecimal(1), sys.getAs(photo, "Content", "commentCount"))
        assertTrue(sys.isMember(c1, "PostComment"))
        assertTrue(sys.isMember(c2, "PhotoComment"))

        // per-operand narrowing in the rule body
        val notices = sys.instancesOf("CommentNotice")
        assertEquals("hello", sys.get(notices[0], "excerpt"))
        assertEquals("sunset", sys.get(notices[1], "excerpt"))

        // the never's narrowed conjunction: only a post's own author is refused
        assertIs<CommitResult.Refused>(sys.commit("Comment", mapOf("target" to post, "author" to ada, "body" to "me")))
        sys.mustCommit("Comment", "target" to photo, "author" to ada, "body" to "mine")
        assertEquals(listOf(photo), sys.instancesOf("PopularityNotice").map { sys.get(it, "content") })

        // a handle outside the union cannot inhabit the field's type
        val r = sys.commit("Comment", mapOf("target" to ada, "author" to grace, "body" to "?"))
        assertTrue(assertIs<CommitResult.Refused>(r).reason.startsWith("type:"))
    }
}
