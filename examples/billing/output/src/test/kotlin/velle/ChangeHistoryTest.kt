package velle

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The billing spec's history of changes, `examples/billing/changes/`: every version validates,
 * and each checked-in report is what `velle.CompareKt` prints today for its two versions.
 * A stale report is regenerated with `./gradlew :examples:billing:output:compareChanges`.
 */
class ChangeHistoryTest {

    private val changesDirectory = File("../changes")

    private val versions: List<File> =
        changesDirectory.listFiles { file -> file.name.endsWith(".velle") }!!.sortedBy { file -> file.name }

    private fun versionOf(file: File) = SpecVersion(file.name, file.readText())

    private fun assertReport(reportName: String, before: File, after: File) {
        val report = File(changesDirectory, reportName)
        assertTrue(report.exists(), "$reportName is missing; run :examples:billing:output:compareChanges")
        assertEquals(
            report.readText(),
            SpecComparison.report(versionOf(before), versionOf(after)),
            "$reportName is stale; run :examples:billing:output:compareChanges",
        )
    }

    @Test
    fun `every version of the billing spec validates`() {
        assertTrue(versions.size >= 2, "the history holds at least two versions")
        for (version in versions) {
            assertEquals(emptyList(), Validator.validate(version.readText()), version.name)
        }
    }

    @Test
    fun `each report is what the comparison prints for a version and the one before it`() {
        for ((before, after) in versions.zipWithNext()) {
            assertReport(after.nameWithoutExtension + ".md", before, after)
        }
    }

    @Test
    fun `the report over the whole history is what the comparison prints for the first and last versions`() {
        val first = versions.first()
        val last = versions.last()
        assertReport("${first.name.substringBefore("-")}-to-${last.name.substringBefore("-")}.md", first, last)
    }
}
