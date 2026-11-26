package velle

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The language reference the velle-design skill ships to assistants that have no wiki,
 * `skills/velle-design/references/`, checked against the compiler so it cannot drift from it.
 *
 * Every fenced block is marked with how the text presents it:
 * - ```` ```velle ```` is an example the compiler accepts. Advisories are allowed.
 * - ```` ```velle advisory ```` is accepted, and the `>` lines right after the block quote advisories it draws.
 * - ```` ```velle refused ```` is refused, and the `>` lines right after the block quote every message it draws.
 *
 * A quoted message is printed as the compiler prints it, without its pointers into the full
 * Reference ("(Reference §N)"), and, for a spelling that does not parse, without the
 * "(at line:column)" position.
 * A quote ending in "..." stands for every message that begins with the text before it.
 *
 * `Best-practices.md` is a copy of a wiki page, checked by `SkillJarTest`, and is not read here.
 */
class SkillReferenceTest {

    private val referenceFolder = File("../skills/velle-design/references")
    private val referenceFileNames = listOf("language.md", "expressions.md", "relationships.md", "prohibitions.md", "time-and-repetition.md")

    /**
     * Reserved words the reference leaves out on purpose, each with the reason. A keyword added to
     * the lexer fails the test `every reserved word is shown in code in the reference` until it is written
     * into the reference or listed here.
     */
    private val keywordsLeftOut: Map<String, String> = emptyMap()

    /** How the reference presents a fenced block, by the info string after its opening fence. */
    private enum class Presentation(val infoString: String) {
        ACCEPTED("velle"),
        ADVISED("velle advisory"),
        REFUSED("velle refused"),
    }

    /** One fenced block: where it opens, how the text presents it, its Velle, and the messages quoted after it. */
    private data class Example(
        val fileName: String,
        val openingLine: Int,
        val presentation: Presentation?,
        val infoString: String,
        val source: String,
        val quotedMessages: List<String>,
        val quoteLineIndexes: List<Int>,
    )

    /** What the compiler prints for one block: its errors, or why it cannot read it, and its advisories. */
    private data class Verdict(val errors: List<String>, val advisories: List<String>)

    private fun lines(fileName: String): List<String> = File(referenceFolder, fileName).readLines()

    private fun fenceIndexes(lines: List<String>): List<Int> = lines.indices.filter { index -> lines[index].startsWith("```") }

    private fun examplesIn(fileName: String): List<Example> {
        val lines = lines(fileName)
        val fenceIndexes = fenceIndexes(lines)
        assertEquals(0, fenceIndexes.size % 2, "$fileName has a fence that is never closed")
        return fenceIndexes.chunked(2).map { openingAndClosing -> example(fileName, lines, openingAndClosing[0], openingAndClosing[1]) }
    }

    private fun example(fileName: String, lines: List<String>, openingIndex: Int, closingIndex: Int): Example {
        val infoString = lines[openingIndex].removePrefix("```").trim()
        val quoteLineIndexes = quoteLineIndexesAfter(lines, closingIndex)
        return Example(
            fileName = fileName,
            openingLine = openingIndex + 1,
            presentation = Presentation.entries.firstOrNull { presentation -> presentation.infoString == infoString },
            infoString = infoString,
            source = lines.subList(openingIndex + 1, closingIndex).joinToString("\n"),
            quotedMessages = quoteLineIndexes.map { index -> lines[index].removePrefix("> ") },
            quoteLineIndexes = quoteLineIndexes,
        )
    }

    /** The `>` lines after a closing fence, which may stand apart by blank lines, up to the first line of other prose. */
    private fun quoteLineIndexesAfter(lines: List<String>, closingIndex: Int): List<Int> {
        val following = (closingIndex + 1 until lines.size).takeWhile { index -> lines[index].isBlank() || lines[index].startsWith("> ") }
        return following.filter { index -> lines[index].startsWith("> ") }
    }

    private fun verdict(source: String): Verdict = try {
        val errors = Validator.validate(source).map { diagnostic -> withoutPointer(diagnostic.toString()) }
        val advisories = Validator.advisories(source).map { diagnostic -> withoutPointer(diagnostic.toString()) }
        Verdict(errors, advisories)
    } catch (syntaxError: VelleSyntaxError) {
        val withoutPosition = syntaxError.message!!.replace(Regex("""\s*\(at \d+:\d+\)$"""), "")
        Verdict(listOf(withoutPointer(withoutPosition)), emptyList())
    }

    /** The message without its pointers into the full Reference, " (Reference §N)" or ", Reference §N", wherever they stand. */
    private fun withoutPointer(message: String): String =
        message.replace(Regex(""" \(Reference §[^)]*\)"""), "").replace(Regex(""", Reference §[^)]*"""), "")

    private fun quoteMatches(quotedMessage: String, printedMessage: String): Boolean =
        if (quotedMessage.endsWith("...")) printedMessage.startsWith(quotedMessage.removeSuffix("...").trimEnd())
        else printedMessage == quotedMessage

    /** Why one block disagrees with the compiler, or null when it agrees. */
    private fun disagreement(example: Example): String? {
        val presentation = example.presentation
            ?: return "the block is marked `${example.infoString}`; mark it velle, velle advisory, or velle refused"
        val verdict = verdict(example.source)
        return when (presentation) {
            Presentation.ACCEPTED -> acceptedDisagreement(example, verdict)
            Presentation.ADVISED -> advisedDisagreement(example, verdict)
            Presentation.REFUSED -> refusedDisagreement(example, verdict)
        }
    }

    private fun acceptedDisagreement(example: Example, verdict: Verdict): String? {
        if (verdict.errors.isNotEmpty()) return "the text presents it as compiling, and the compiler prints:\n" + listed(verdict.errors)
        if (example.quotedMessages.isNotEmpty()) return "the text quotes messages after a block it presents as compiling; mark the block velle refused or velle advisory"
        return null
    }

    private fun advisedDisagreement(example: Example, verdict: Verdict): String? {
        if (verdict.errors.isNotEmpty()) return "the text presents it as compiling with an advisory, and the compiler refuses it:\n" + listed(verdict.errors)
        if (example.quotedMessages.isEmpty()) return "the text quotes no advisory after the block"
        val unprinted = example.quotedMessages.filter { quoted -> verdict.advisories.none { printed -> quoteMatches(quoted, printed) } }
        if (unprinted.isEmpty()) return null
        return "the text quotes advisories the compiler does not print:\n" + listed(unprinted) + "\nthe compiler's advisories:\n" + listed(verdict.advisories)
    }

    private fun refusedDisagreement(example: Example, verdict: Verdict): String? {
        if (verdict.errors.isEmpty()) return "the text presents it as refused, and the compiler accepts it"
        if (example.quotedMessages.isEmpty()) return "the text quotes no message after the block; the compiler prints:\n" + listed(verdict.errors)
        val unprinted = example.quotedMessages.filter { quoted -> verdict.errors.none { printed -> quoteMatches(quoted, printed) } }
        val unquoted = verdict.errors.filter { printed -> example.quotedMessages.none { quoted -> quoteMatches(quoted, printed) } }
        if (unprinted.isEmpty() && unquoted.isEmpty()) return null
        return "the quoted messages are not what the compiler prints.\nquoted and not printed:\n" + listed(unprinted) +
            "\nprinted and not quoted:\n" + listed(unquoted)
    }

    private fun listed(messages: List<String>): String =
        if (messages.isEmpty()) "  (none)" else messages.joinToString("\n") { message -> "  $message" }

    @Test
    fun `every fenced block in the reference is what the text says the compiler makes of it`() {
        val disagreements = referenceFileNames.flatMap { fileName -> examplesIn(fileName) }
            .mapNotNull { example -> disagreement(example)?.let { reason -> "${example.fileName}, line ${example.openingLine}: $reason" } }

        assertTrue(disagreements.isEmpty(), disagreements.joinToString("\n\n"))
    }

    /** The lines of one file outside any fence and outside the quotes after a block. */
    private fun proseLineIndexes(fileName: String): List<Int> {
        val lines = lines(fileName)
        val examples = examplesIn(fileName)
        val fenced = fenceIndexes(lines).chunked(2).flatMap { openingAndClosing -> openingAndClosing[0]..openingAndClosing[1] }.toSet()
        val quoted = examples.flatMap { example -> example.quoteLineIndexes }.toSet()
        return lines.indices.filter { index -> index !in fenced && index !in quoted }
    }

    @Test
    fun `compiler messages are quoted only after the block that draws them`() {
        val compilerMessage = Regex("""\[[VFEA]\d+] \S""")
        val inProse = referenceFileNames.flatMap { fileName ->
            val lines = lines(fileName)
            proseLineIndexes(fileName).filter { index -> compilerMessage.containsMatchIn(lines[index]) }
                .map { index -> "$fileName, line ${index + 1}: ${lines[index].take(100)}" }
        }

        assertTrue(
            inProse.isEmpty(),
            "these lines quote a compiler message in prose, where no test reads it; write the spelling as a " +
                "```velle refused block and quote the message on a > line after it:\n" + inProse.joinToString("\n"),
        )
    }

    /** Every word written in code in the reference: fenced blocks and inline spans, outside the list of reserved words. */
    private fun wordsInCode(): Set<String> {
        val inlineCode = Regex("(`+)(.+?)\\1")
        val word = Regex("[a-z][A-Za-z]*")
        val fencedSources = referenceFileNames.flatMap { fileName -> examplesIn(fileName) }.map { example -> example.source }
        val inlineSpans = referenceFileNames.flatMap { fileName ->
            val lines = lines(fileName)
            proseLineIndexes(fileName).map { index -> lines[index] }
                .filterNot { line -> line.startsWith(RESERVED_WORDS_LINE) }
                .flatMap { line -> inlineCode.findAll(line).map { span -> span.groupValues[2] }.toList() }
        }
        return (fencedSources + inlineSpans).flatMap { code -> word.findAll(code).map { match -> match.value }.toList() }.toSet()
    }

    @Test
    fun `every reserved word is shown in code in the reference`() {
        val shown = wordsInCode()
        val missing = KEYWORDS.filter { keyword -> keyword !in shown && keyword !in keywordsLeftOut }
        val listedButShown = keywordsLeftOut.keys.filter { keyword -> keyword in shown || keyword !in KEYWORDS }

        assertEquals(emptyList(), missing, "reserved words the reference never shows in code; show each, or list it in keywordsLeftOut with the reason")
        assertEquals(emptyList(), listedButShown, "keywordsLeftOut lists words the reference shows, or that are not reserved; drop them from the list")
    }

    @Test
    fun `the reserved words language md lists are the lexer's`() {
        val reservedWordsLine = lines("language.md").single { line -> line.startsWith(RESERVED_WORDS_LINE) }
        val listed = Regex("`([^`]+)`").find(reservedWordsLine)!!.groupValues[1].split(" ").toSet()

        assertEquals(KEYWORDS - SCALAR_KEYWORDS, listed, "language.md §2 lists the reserved words other than the type names")
    }

    private companion object {
        const val RESERVED_WORDS_LINE = "**Reserved words** include"
    }
}
