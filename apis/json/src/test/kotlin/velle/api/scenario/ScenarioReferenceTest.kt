package velle.api.scenario

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The skill's page on scenarios, `skills/velle-design/references/scenarios.md`, checked by running it:
 * every ```` ```json ```` block is a scenario run on the page's ```` ```velle ```` spec, and the
 * ````` ````text ````` block right after it is exactly what `velle try` prints for it.
 *
 * Run with `VELLE_UPDATE_SCENARIOS=true` to write what `velle try` prints into those blocks; the diff is the review.
 */
class ScenarioReferenceTest {

    private val page = File("../../skills/velle-design/references/scenarios.md")

    /** A fenced block: the line indexes of its opening and closing fences, and its info string. */
    private data class Fenced(val opening: Int, val closing: Int, val infoString: String)

    private fun fencedBlocks(lines: List<String>): List<Fenced> {
        val blocks = mutableListOf<Fenced>()
        var index = 0
        while (index < lines.size) {
            val fence = Regex("^(`{3,})(.*)$").matchEntire(lines[index])
            if (fence == null) {
                index++
                continue
            }
            val marker = fence.groupValues[1]
            val closing = (index + 1 until lines.size).first { candidate -> lines[candidate] == marker }
            blocks.add(Fenced(index, closing, fence.groupValues[2].trim()))
            index = closing + 1
        }
        return blocks
    }

    private fun content(lines: List<String>, block: Fenced): String = lines.subList(block.opening + 1, block.closing).joinToString("\n")

    @Test
    fun `every scenario on the page prints what the page shows`() {
        val lines = page.readLines()
        val blocks = fencedBlocks(lines)
        val spec = content(lines, blocks.first { block -> block.infoString == "velle" })
        val pairs = blocks.zipWithNext().filter { (scenario, output) -> scenario.infoString == "json" && output.infoString == "text" }
        assertEquals(blocks.count { block -> block.infoString == "json" }, pairs.size, "every json block on the page is followed by its output in a ````text block")

        val printed = pairs.map { (scenario, _) -> TryCommand.runText("lending.velle", spec, "scenario.json", content(lines, scenario)).text.trimEnd() }
        if (System.getenv("VELLE_UPDATE_SCENARIOS") == "true") {
            page.writeText(rewritten(lines, pairs.map { pair -> pair.second }, printed))
            return
        }
        pairs.zip(printed).forEach { (pair, report) ->
            assertEquals(content(lines, pair.second), report, "scenarios.md, the scenario at line ${pair.first.opening + 1}")
        }
    }

    private fun rewritten(lines: List<String>, outputs: List<Fenced>, printed: List<String>): String {
        val replaced = mutableListOf<String>()
        var index = 0
        outputs.zip(printed).forEach { (output, report) ->
            replaced.addAll(lines.subList(index, output.opening + 1))
            replaced.addAll(report.lines())
            index = output.closing
        }
        replaced.addAll(lines.subList(index, lines.size))
        return replaced.joinToString("\n", postfix = "\n")
    }
}
