package velle.generated.gallery

import velle.generated.GallerySystem
import velle.generated.*
import velle.generated.Member

/**
 * The human-owned scenarios the generated gallery specs demand (testgen.md).
 */
class Givens(private val sys: GallerySystem) : RequiredGivens {

    private fun member(name: String): Member {
        sys.commitMember(name)
        return sys.members().last()
    }

    override fun popular(): Content {
        val ada = member("Ada")
        val grace = member("Grace")
        sys.commitPhoto(ada, "sunset")
        val photo = sys.photos().last()
        sys.commitComment(photo, grace, "lovely")
        sys.commitComment(photo, ada, "thanks") // only posts forbid the author's own comment
        return photo
    }

    override fun comment(): Comment {
        val ada = member("Ada")
        val grace = member("Grace")
        sys.commitPost(ada, "hello")
        sys.commitComment(sys.posts().last(), grace, "hi")
        return sys.comments().last()
    }
}
