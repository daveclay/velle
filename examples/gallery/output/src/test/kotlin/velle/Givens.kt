package velle.generated.gallery

import velle.generated.GallerySystem

/**
 * The human-owned scenarios the generated gallery specs demand (testgen.md).
 */
class Givens(private val sys: GallerySystem) : RequiredGivens {

    private fun member(name: String): GallerySystem.MemberView {
        sys.commitMember(name)
        return sys.members().last()
    }

    override fun popular(): GallerySystem.ContentView {
        val ada = member("Ada")
        val grace = member("Grace")
        sys.commitPhoto(ada, "sunset")
        val photo = sys.photos().last().asContent()
        sys.commitComment(photo, grace, "lovely")
        sys.commitComment(photo, ada, "thanks") // only posts forbid the author's own comment
        return photo
    }

    override fun comment(): GallerySystem.CommentView {
        val ada = member("Ada")
        val grace = member("Grace")
        sys.commitPost(ada, "hello")
        sys.commitComment(sys.posts().last().asContent(), grace, "hi")
        return sys.comments().last()
    }
}
