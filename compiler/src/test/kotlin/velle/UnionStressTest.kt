package velle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Adversarial probes of a static union (Reference §9), one per case of the
 * union stress pass (OQ44): the checks that reason per operand, narrowing to
 * the rest of a union, a walk through a union-typed field, the diagrams.
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
    fun `a walk through a union-typed reference ends at a record without the relationship, and until reads the record reached`() {
        val sys = system("""
            act Alert { title: text }
            act Escalation { source: one Trail, reason: text }
            shape Trail = Alert or Escalation
            shape Chain = Escalation {
                above: many Trail = walk source
                root: one Trail? = walk source until this is Alert
            }
        """.trimIndent())
        sys.scope {
            val alert = sys.mustCommit("Alert", mapOf("title" to "smoke"))
            val first = sys.mustCommit("Escalation", mapOf("source" to alert, "reason" to "no answer"))
            val second = sys.mustCommit("Escalation", mapOf("source" to first, "reason" to "still none"))
            assertEquals(setOf(first, alert), (sys.getAs(second, "Chain", "above") as List<*>).toSet())
            assertEquals(alert, sys.getAs(second, "Chain", "root"))
        }
        val readsAnOperandsField = """
            act Alert { title: text }
            act Escalation { source: one Trail, reason: text }
            shape Trail = Alert or Escalation
            shape Chain = Escalation { root: one Trail? = walk source until source is Alert }
        """.trimIndent()
        assertTrue(diags(readsAnOperandsField).any { it.code == "F1" && "'source' is declared on 'Escalation' separately" in it.message },
            "the condition is asked of an alert too: ${diags(readsAnOperandsField)}")
        run {
        }
    }

    @Test
    fun `a singular reference beside a union-typed and an operand-typed field is refused as ambiguous`() {
        val src = content + """

            act Comment { target: one Content, post: one Post, note: text }
            shape Summary = Post { latest: one Comment? = (Comment for this) }
        """.trimIndent()
        val v13 = diags(src).filter { it.code == "V13" }
        assertEquals(1, v13.size, diags(src).toString())
        assertTrue("'Comment.target' and 'Comment.post'" in v13.single().message, v13.single().message)
    }

    // ── the checks that skip a cross-base refinement (gaps) ──────────────────

    @Test
    fun `V12 proves at-most-one for each operand of a refinement over a union`() {
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
        val v12 = diags(overUnion).filter { it.code == "V12" }
        assertTrue(v12.any { "'Post' is an act" in it.message } && v12.any { "'Photo' is an act" in it.message }, diags(overUnion).toString())
    }

    @Test
    fun `V24 sees a required union-typed referrer of a deleted operand`() {
        val deletion = """

            act RemovePost { post: one Post? initially required }
            rule Remove when RemovePost { delete post }
        """.trimIndent()
        val operandTyped = content + "\nact Comment { target: one Post, note: text }\n" + deletion
        assertTrue("V24" in codes(operandTyped), diags(operandTyped).toString())
        val unionTyped = content + "\nact Comment { target: one Content, note: text }\n" + deletion
        assertTrue(diags(unionTyped).any { it.code == "V24" && "'Comment.target: one Content'" in it.message }, diags(unionTyped).toString())
    }

    @Test
    fun `V10 sees a rule writing on an operand what a never over a union reads`() {
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
        assertTrue(diags(overUnion).any { it.code == "V10" && "rule 'Drop' assigns" in it.message }, diags(overUnion).toString())
    }

    @Test
    fun `the predicate of a refinement or never over a union is name-checked, naming separate declarations`() {
        assertTrue(diags(content + "\nshape Odd = Content where bogus is empty").any { it.code == "F1" && "'bogus'" in it.message })
        val perOperand = """
            act Member { name: text unique }
            act Post { author: one Member, body: text, views: integer }
            act Photo { author: one Member, caption: text, views: integer }
            shape Content = Post or Photo
            never (Content where views < 0)
        """.trimIndent()
        val f1 = diags(perOperand).filter { it.code == "F1" }
        assertEquals(1, f1.size, diags(perOperand).toString())
        assertTrue("'views' is declared on 'Post' and 'Photo' separately, so it is not a member of 'Content'" in f1.single().message, f1.single().message)
        assertTrue("if this is Post then this.views else" in f1.single().message && "take with 'is'" in f1.single().message, f1.single().message)
    }

    @Test
    fun `narrowing by is not, and the residual else of a three-operand or nested union, read the rest of the union`() {
        val isNot = content + "\nact Comment { target: one Content, note: text }\n" +
            "shape Excerpt = Comment { label: text = if target is not Post then target.caption else target.body }"
        assertEquals(emptyList(), diags(isNot))
        val residual = """
            act Member { name: text unique }
            act Post { author: one Member, body: text }
            act Photo { author: one Member, caption: text }
            act Clip { author: one Member, title: text }
            shape Content = Post or Photo or Clip
            act Comment { target: one Content, note: text }
            shape Excerpt = Comment { label: text = if target is Post then target.body else if target is Photo then target.caption else target.title }
        """.trimIndent()
        assertEquals(emptyList(), diags(residual))
        val nested = content + """

            act Video { author: one Member, title: text }
            shape Media = Content or Video
            act Reaction { about: one Media, kind: text }
            shape Labelled = Reaction { label: text = if about is Content then "c" else about.title }
        """.trimIndent()
        assertEquals(emptyList(), diags(nested))
        val stillRefused = content + "\nact Comment { target: one Content, note: text }\n" +
            "shape Excerpt = Comment { label: text = if target is not Post then target.body else target.caption }"
        assertTrue(diags(stillRefused).any { it.code == "F1" && "'body' is not a member of 'Photo'" in it.message }, diags(stillRefused).toString())
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
            shape Tagged = Comment { tagCount: integer = if target is Post then length(target.body) else 0 }
        """.trimIndent()
        assertEquals(emptyList(), diags(sourcePath))
        val model = Model(Parser.parse(sourcePath))
        val tagCount = model.refinements.getValue("Tagged").members.filterIsInstance<DerivedProp>().single()
        val reads = ReadSummary().also { model.collectExpr(tagCount.expr, "Tagged", it) }
        assertTrue(!reads.opaque && ("Post" to "body") in reads.fields, "the narrowed read is carried into the summary: opaque=${reads.opaque} ${reads.fields}")
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
    fun `the element of a refinement-filtered collection is the refinement, so its body properties can be summed`() {
        val sys = system("""
            act Member { name: text unique }
            act Claim { member: one Member, amount: integer, paid: boolean }
            shape PaidClaim = Claim where paid { patientPays: integer = amount / 2 }
            shape Account = Member { owed: integer = sum(claims where PaidClaim, patientPays) }
        """.trimIndent())
        sys.scope {
            val ada = sys.mustCommit("Member", mapOf("name" to "Ada"))
            sys.mustCommit("Claim", mapOf("member" to ada, "amount" to 10, "paid" to true))
            sys.mustCommit("Claim", mapOf("member" to ada, "amount" to 30, "paid" to true))
            sys.mustCommit("Claim", mapOf("member" to ada, "amount" to 100, "paid" to false))
            assertEquals(java.math.BigDecimal(20), sys.getAs(ada, "Account", "owed"))
        }
    }

    @Test
    fun `the diagrams draw a static union and a refinement over it`() {
        val gallery = java.io.File("../examples/gallery/gallery.velle").readText()
        val doc = DiagramGen.generate(gallery, "Gallery")
        assertTrue("class Contribution {" in doc, "a shape other shapes take has a box")
        val classDiagram = doc.substringBefore("## State flow")
        assertTrue("class Content {" in classDiagram && "<<union>>" in classDiagram, classDiagram)
        assertTrue("Content <|-- Post : or" in classDiagram && "Content <|-- Photo : or" in classDiagram, classDiagram)
        assertTrue("class Popular {" in classDiagram && "Content <|-- Popular" in classDiagram, classDiagram)
        val stateFlow = doc.substringAfter("## State flow")
        assertTrue("### Post" in stateFlow && "### Photo" in stateFlow && "**Popular**" in stateFlow, stateFlow)
    }

    @Test
    fun `creating a union is refused naming its operands`() {
        val src = content + "\nact Flag { about: one Content }\nrule Make when Flag { Content from { author: about.author } }"
        assertTrue(diags(src).any { "creates 'Content', which has no records of its own: a record of it is a Post or a Photo; create one of them" in it.message }, diags(src).toString())
    }
}
