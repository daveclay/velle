package velle

import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import kotlin.system.exitProcess

/**
 * The `velle` command: `validate`, `compare`, and `describe`, each printing Markdown for an
 * assistant to read and a product owner to be shown. It writes no file, and calls neither git
 * nor the network. `skills/velle-design/scripts/velle` runs it from the skill's jar.
 */
fun main(arguments: Array<String>) {
    exitWith(Cli.run(arguments.toList()))
}

/** Prints what a run of the command says, in UTF-8 whatever the machine's locale, and exits with its code. */
fun exitWith(outcome: CommandOutcome) {
    val standardOutput = PrintStream(FileOutputStream(FileDescriptor.out), true, Charsets.UTF_8)
    standardOutput.print(outcome.text)
    standardOutput.flush()
    exitProcess(outcome.exitCode)
}

/** What one run of the command prints, and the code it exits with. */
data class CommandOutcome(val text: String, val exitCode: Int) {
    companion object {
        /** The command did what was asked. */
        const val DONE = 0

        /** A draft does not validate, a file is missing, or a name is not in the spec. */
        const val REFUSED = 1

        /** The command was not given a subcommand it knows, or not the right number of files. */
        const val USAGE = 2

        fun done(text: String) = CommandOutcome(text, DONE)
        fun refused(text: String) = CommandOutcome(text, REFUSED)
    }
}

object Cli {

    val usage = listOf(
        "Usage:",
        "",
        "    velle validate <draft.velle>",
        "    velle compare <before.velle> <after.velle>",
        "    velle describe <draft.velle> [<Name>]",
        "",
        "`validate` says whether the draft validates, and if not, why.",
        "`compare` says what changed from one version of a spec to another. Both must validate.",
        "`describe` says what the draft says, or with a Name, what it says about that shape or act.",
        "",
        "Each prints Markdown and changes no file.",
        "",
    ).joinToString("\n")

    fun run(arguments: List<String>): CommandOutcome {
        val subcommand = arguments.firstOrNull() ?: return usageOutcome()
        val operands = arguments.drop(1)
        return when (subcommand) {
            "validate" -> if (operands.size == 1) validate(operands[0]) else usageOutcome()
            "compare" -> if (operands.size == 2) compare(operands[0], operands[1]) else usageOutcome()
            "describe" -> if (operands.size in 1..2) describe(operands[0], operands.getOrNull(1)) else usageOutcome()
            else -> usageOutcome()
        }
    }

    private fun usageOutcome() = CommandOutcome(usage, CommandOutcome.USAGE)

    fun validate(path: String): CommandOutcome = when (val draft = Draft.read(path)) {
        is Draft.Valid -> CommandOutcome.done("`$path` validates.\n" + advisories(draft.version))
        is Draft.Refused -> CommandOutcome.refused(draft.report)
    }

    /** What the compiler advises about a draft that validates: what the author may not have meant. */
    private fun advisories(version: SpecVersion): String {
        val advisories = Validator.advisories(version.source)
        if (advisories.isEmpty()) return ""
        return buildString {
            appendLine()
            appendLine("## What the compiler advises")
            appendLine()
            appendLine("Each is something the spec says that its author may not have meant. None stops it validating.")
            appendLine()
            advisories.forEach { advisory -> appendLine(listItem(advisory.toString())) }
        }
    }

    fun compare(beforePath: String, afterPath: String): CommandOutcome {
        val before = Draft.read(beforePath)
        val after = Draft.read(afterPath)
        if (before is Draft.Valid && after is Draft.Valid) {
            return CommandOutcome.done(SpecComparison.report(before.version, after.version))
        }
        val reports = listOf(before, after).filterIsInstance<Draft.Refused>().map { refused -> refused.report }
        return CommandOutcome.refused(reports.joinToString("\n"))
    }

    fun describe(path: String, name: String?): CommandOutcome {
        val version = when (val draft = Draft.read(path)) {
            is Draft.Valid -> draft.version
            is Draft.Refused -> return CommandOutcome.refused(draft.report)
        }
        if (name == null) return CommandOutcome.done(DescriptionReport.rendered(SpecDescription.of(version)))
        val focusNames = SpecDescription.focusNames(version)
        if (name in focusNames) return CommandOutcome.done(DescriptionReport.rendered(SpecDescription.of(version, name)))
        val base = focusNames.entries.firstOrNull { baseAndRefinements -> name in baseAndRefinements.value }?.key
            ?: return CommandOutcome.refused(unknownName(path, name, focusNames))
        val opening = "$name is a refinement of $base, so this describes $base and everything about it."
        return CommandOutcome.done(DescriptionReport.rendered(SpecDescription.of(version, base, opening)))
    }

    /** A message as one item of a Markdown list, its later lines kept inside the item. */
    private fun listItem(message: String): String {
        val messageLines = message.lines()
        return (listOf("- " + messageLines.first()) + messageLines.drop(1).map { line -> "  " + line.trim() }).joinToString("\n")
    }

    private fun unknownName(path: String, name: String, focusNames: Map<String, List<String>>): String = buildString {
        appendLine("`$name` is not the name of a shape, an act, or a refinement in `$path`. The names it could be,")
        appendLine("each shape or act with its refinements under it:")
        appendLine()
        focusNames.forEach { (base, refinements) ->
            appendLine("- $base")
            refinements.forEach { refinement -> appendLine("  - $refinement") }
        }
    }
}
