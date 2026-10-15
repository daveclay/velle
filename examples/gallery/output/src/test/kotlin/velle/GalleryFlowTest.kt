package velle.generated.gallery

import kotlin.test.Test
import velle.Ref
import velle.generated.*
import velle.generated.Member
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import velle.Commit
import velle.CommitResult
import velle.generated.GallerySystem

/**
 * Cross-base unions (Reference §9 extended across unrelated shapes; OQ29) driven
 * through the generated typed surface — the business-flow companion to the
 * generated per-rule specs.
 */
open class GalleryFlowTest {

    /** The system under test; the storage-backed variants override this. */
    protected open fun newSystem(): GallerySystem = GallerySystem()
    protected val sys: GallerySystem by lazy { newSystem() }

    private fun member(name: String): Member {
        assertIs<Commit.Accepted<*>>(sys.commit(NewMember(name)))
        return sys.members().last()
    }

    private fun post(author: Member, body: String): Post {
        assertIs<Commit.Accepted<*>>(sys.commit(NewPost(author, body)))
        return sys.posts().last()
    }

    private fun photo(author: Member, caption: String): Photo {
        assertIs<Commit.Accepted<*>>(sys.commit(NewPhoto(author, caption)))
        return sys.photos().last()
    }

    private fun comment(target: Ref<Content>, author: Member, body: String): Comment {
        assertIs<Commit.Accepted<*>>(sys.commit(NewComment(target, author, body)))
        return sys.comments().last()
    }

    @Test
    fun `the polymorphic association - one field, either shape, narrowed at the surface`() {
        val ada = member("Ada")
        val grace = member("Grace")
        val p = post(ada, "hello")
        val ph = photo(ada, "sunset")

        val onPost = comment(p, grace, "hi")
        val onPhoto = comment(ph, grace, "lovely")

        assertEquals("Post", sys.read(onPost).target.shape)
        assertEquals(p, (sys.read(sys.read(onPost).target) as? Post))
        assertNull((sys.read(sys.read(onPost).target) as? Photo), "a Post is not a Photo")
        assertEquals(ph, (sys.read(sys.read(onPhoto).target) as? Photo))
        assertNull((sys.read(sys.read(onPhoto).target) as? Post))

        // membership through the union-typed field
        assertTrue(with(sys) { sys.read(onPost).isPostComment })
        assertTrue(with(sys) { sys.read(onPhoto).isPhotoComment })
        assertEquals(listOf(onPost), sys.postComments().map { sys.read(it) })
    }

    @Test
    fun `the inverse inferred through the union lands on every operand and reads through the union`() {
        val ada = member("Ada")
        val grace = member("Grace")
        val p = post(ada, "hello")
        val ph = photo(ada, "sunset")
        val c1 = comment(p, grace, "one")
        val c2 = comment(ph, grace, "two")
        val c3 = comment(ph, ada, "three")

        assertEquals(listOf(c1), sys.read(p).comments)
        assertEquals(listOf(c2, c3), sys.read(ph).comments)
        // the union's own view: every operand's instances, the shared inverse, the derived body
        assertEquals(setOf(p.key, ph.key), sys.contents().map { it.key }.toSet())
        assertEquals(listOf(c2, c3), sys.read(ph).comments)
        assertEquals(1, sys.read(p).commentCount)
        assertEquals(2, sys.read(ph).commentCount)
    }

    @Test
    fun `per-operand narrowing in a rule body reads each operand's own fields`() {
        val ada = member("Ada")
        val grace = member("Grace")
        val p = post(ada, "a post body")
        val ph = photo(grace, "a photo caption")
        val onPost = comment(p, grace, "hi")
        val onPhoto = comment(ph, ada, "lovely")

        val notices = sys.commentNotices().associateBy { sys.read(it).comment }
        assertEquals(ada, sys.read(notices.getValue(onPost)).recipient, "the post's author is notified")
        assertEquals("a post body", sys.read(notices.getValue(onPost)).excerpt)
        assertEquals(grace, sys.read(notices.getValue(onPhoto)).recipient, "the photo's author is notified")
        assertEquals("a photo caption", sys.read(notices.getValue(onPhoto)).excerpt)
    }

    @Test
    fun `a rule over a refinement of the union fires on entry, for either operand`() {
        val ada = member("Ada")
        val grace = member("Grace")
        val ph = photo(ada, "sunset")
        comment(ph, grace, "one")
        assertEquals(0, sys.popularityNotices().size, "one comment is not popular")
        assertTrue(with(sys) { !sys.read(ph).isPopular })

        comment(ph, ada, "two")
        assertTrue(with(sys) { sys.read(ph).isPopular })
        assertEquals(listOf(ph.key), sys.popularityNotices().map { sys.read(it).content.key }, "entry fired once")
        assertEquals(ph, (sys.read(sys.read(sys.popularityNotices().single()).content) as? Photo))

        comment(ph, grace, "three")
        assertEquals(1, sys.popularityNotices().size, "a third comment is not a new entry")

        val p = post(ada, "hello")
        comment(p, grace, "one")
        comment(p, grace, "two")
        assertEquals(setOf(ph.key, p.key), sys.popularityNotices().map { sys.read(it).content.key }.toSet())
    }

    @Test
    fun `narrowing in a never's conjunction - the author's own post is refused, their own photo is not`() {
        val ada = member("Ada")
        val p = post(ada, "hello")
        val ph = photo(ada, "sunset")

        val refused = sys.commit(NewComment(p, ada, "me again"))
        assertIs<Commit.Refused<*>>(refused)
        assertEquals(0, sys.read(p).comments.size, "a refused act commits nothing")

        assertIs<Commit.Accepted<*>>(sys.commit(NewComment(ph, ada, "my own photo")))
    }

    @Test
    fun `a reference outside the union cannot inhabit the field's type`() {
        val ada = member("Ada")
        val grace = member("Grace")
        val r = sys.system.commit("Comment", mapOf("target" to ada.key, "author" to grace.key, "body" to "?"))
        val refused = assertIs<CommitResult.Refused>(r)
        assertTrue(refused.reason.startsWith("type:"), refused.reason)
        assertEquals(0, sys.comments().size)
    }
}

