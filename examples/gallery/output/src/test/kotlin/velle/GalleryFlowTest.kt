package velle.generated.gallery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import velle.CommitResult
import velle.generated.GallerySystem

/**
 * Cross-base unions (README §9 extended across unrelated shapes; OQ29) driven
 * through the generated typed surface — the business-flow companion to the
 * generated per-rule specs.
 */
class GalleryFlowTest {

    private val sys = GallerySystem()

    private fun member(name: String): GallerySystem.MemberView {
        assertIs<CommitResult.Accepted>(sys.commitMember(name))
        return sys.members().last()
    }

    private fun post(author: GallerySystem.MemberView, body: String): GallerySystem.PostView {
        assertIs<CommitResult.Accepted>(sys.commitPost(author, body))
        return sys.posts().last()
    }

    private fun photo(author: GallerySystem.MemberView, caption: String): GallerySystem.PhotoView {
        assertIs<CommitResult.Accepted>(sys.commitPhoto(author, caption))
        return sys.photos().last()
    }

    private fun comment(target: GallerySystem.ContentView, author: GallerySystem.MemberView, body: String): GallerySystem.CommentView {
        assertIs<CommitResult.Accepted>(sys.commitComment(target, author, body))
        return sys.comments().last()
    }

    @Test
    fun `the polymorphic association - one field, either shape, narrowed at the surface`() {
        val ada = member("Ada")
        val grace = member("Grace")
        val p = post(ada, "hello")
        val ph = photo(ada, "sunset")

        val onPost = comment(p.asContent(), grace, "hi")
        val onPhoto = comment(ph.asContent(), grace, "lovely")

        assertEquals("Post", onPost.target.shape)
        assertEquals(p, onPost.target.asPost())
        assertNull(onPost.target.asPhoto(), "a Post is not a Photo")
        assertEquals(ph, onPhoto.target.asPhoto())
        assertNull(onPhoto.target.asPost())

        // membership through the union-typed field
        assertTrue(with(sys) { onPost.isPostComment() })
        assertTrue(with(sys) { onPhoto.isPhotoComment() })
        assertEquals(listOf(onPost), sys.postComments().map { sys.comment(it.id) })
    }

    @Test
    fun `the inverse inferred through the union lands on every operand and reads through the union`() {
        val ada = member("Ada")
        val grace = member("Grace")
        val p = post(ada, "hello")
        val ph = photo(ada, "sunset")
        val c1 = comment(p.asContent(), grace, "one")
        val c2 = comment(ph.asContent(), grace, "two")
        val c3 = comment(ph.asContent(), ada, "three")

        assertEquals(listOf(c1), p.comments)
        assertEquals(listOf(c2, c3), ph.comments)
        // the union's own view: every operand's instances, the shared inverse, the derived body
        assertEquals(setOf(p.id, ph.id), sys.contents().map { it.id }.toSet())
        assertEquals(listOf(c2, c3), ph.asContent().comments)
        assertEquals(1, p.asContent().commentCount)
        assertEquals(2, ph.asContent().commentCount)
    }

    @Test
    fun `per-operand narrowing in a rule body reads each operand's own fields`() {
        val ada = member("Ada")
        val grace = member("Grace")
        val p = post(ada, "a post body")
        val ph = photo(grace, "a photo caption")
        val onPost = comment(p.asContent(), grace, "hi")
        val onPhoto = comment(ph.asContent(), ada, "lovely")

        val notices = sys.commentNotices().associateBy { it.comment }
        assertEquals(ada, notices.getValue(onPost).recipient, "the post's author is notified")
        assertEquals("a post body", notices.getValue(onPost).excerpt)
        assertEquals(grace, notices.getValue(onPhoto).recipient, "the photo's author is notified")
        assertEquals("a photo caption", notices.getValue(onPhoto).excerpt)
    }

    @Test
    fun `a rule over a refinement of the union fires on entry, for either operand`() {
        val ada = member("Ada")
        val grace = member("Grace")
        val ph = photo(ada, "sunset")
        comment(ph.asContent(), grace, "one")
        assertEquals(0, sys.popularityNotices().size, "one comment is not popular")
        assertTrue(with(sys) { !ph.asContent().isPopular() })

        comment(ph.asContent(), ada, "two")
        assertTrue(with(sys) { ph.asContent().isPopular() })
        assertEquals(listOf(ph.id), sys.popularityNotices().map { it.content.id }, "entry fired once")
        assertEquals(ph, sys.popularityNotices().single().content.asPhoto())

        comment(ph.asContent(), grace, "three")
        assertEquals(1, sys.popularityNotices().size, "a third comment is not a new entry")

        val p = post(ada, "hello")
        comment(p.asContent(), grace, "one")
        comment(p.asContent(), grace, "two")
        assertEquals(setOf(ph.id, p.id), sys.popularityNotices().map { it.content.id }.toSet())
    }

    @Test
    fun `narrowing in a never's conjunction - the author's own post is refused, their own photo is not`() {
        val ada = member("Ada")
        val p = post(ada, "hello")
        val ph = photo(ada, "sunset")

        val refused = sys.commitComment(p.asContent(), ada, "me again")
        assertIs<CommitResult.Refused>(refused)
        assertEquals(0, p.comments.size, "a refused act commits nothing")

        assertIs<CommitResult.Accepted>(sys.commitComment(ph.asContent(), ada, "my own photo"))
    }

    @Test
    fun `a reference outside the union cannot inhabit the field's type`() {
        val ada = member("Ada")
        val grace = member("Grace")
        val r = sys.system.commit("Comment", mapOf("target" to ada.id, "author" to grace.id, "body" to "?"))
        val refused = assertIs<CommitResult.Refused>(r)
        assertTrue(refused.reason.startsWith("type:"), refused.reason)
        assertEquals(0, sys.comments().size)
    }
}
