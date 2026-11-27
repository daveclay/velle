package velle

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * What `velle compare` and the diagrams say of two examples whose condition reaches a new record
 * through a reference an existing record holds, but not in the form the compiler drops it for: a
 * read inside a filter over another shape, and a read through a reference typed with a union.
 * Each test holds today's answer (QUESTIONS.md, OQ226).
 */
class HeldReferenceResidueTest {

    private fun example(name: String) = File("../examples/$name/$name.velle").readText()

    @Test
    fun `peptides - a new greenhouse trial is still said to move a peptide through GreenhouseValidated`() {
        val report = SpecComparison.report(SpecVersion("empty.velle", ""), SpecVersion("peptides.velle", example("peptides")))
        val consequences = report.substringAfter("## What that changes")
        // an existing GreenhouseResult names its trial already, but the read is inside a filter over GreenhouseResult
        assertTrue(consequences.lines().any { line -> line.startsWith("- ApproveGreenhouseTrial creating a GreenhouseTrial") && "GreenhouseValidated" in line }, consequences)
    }

    @Test
    fun `gallery - commitPost is still said to check the prohibition on commenting on one's own post`() {
        val document = DiagramGen.generate(example("gallery"), "Gallery")
        val commitPost = document.substringAfter("### commitPost").substringBefore("### commit")
        // a new post is the target of no comment that exists, but the reference is typed with the union Content
        assertTrue("target.author == author" in commitPost, commitPost)
    }
}
