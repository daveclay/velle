package velle.generated.gallery

import velle.generated.GallerySystem
import velle.generated.*
import velle.generated.Member

/**
 * The human-owned scenarios the generated gallery specs demand (testgen.md).
 */
class Givens(private val sys: GallerySystem) : RequiredGivens {

    private fun member(name: String): Member {
        sys.commit(NewMember(name))
        return sys.members().last()
    }

    override fun popular(): Content {
        val ada = member("Ada")
        val grace = member("Grace")
        sys.commit(NewPhoto(ada, "sunset"))
        val photo = sys.photos().last()
        sys.commit(NewComment(photo, grace, "lovely"))
        sys.commit(NewComment(photo, ada, "thanks")) // only posts forbid the author's own comment
        return photo
    }

    override fun comment(): Comment {
        val ada = member("Ada")
        val grace = member("Grace")
        sys.commit(NewPost(ada, "hello"))
        sys.commit(NewComment(sys.posts().last(), grace, "hi"))
        return sys.comments().last()
    }
}
