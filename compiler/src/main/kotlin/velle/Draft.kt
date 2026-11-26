package velle

import java.io.File

/**
 * A spec read for the `velle` command, from a file or as text: the spec when it validates, or why
 * it does not, said as Markdown. The spec is checked here, where it enters; everything after trusts it.
 */
sealed interface Draft {

    class Valid(val version: SpecVersion) : Draft

    class Refused(val report: String) : Draft

    companion object {

        fun read(path: String): Draft {
            val file = File(path)
            if (!file.isFile) return Refused("There is no file at `$path`.\n")
            return of(path, file.readText())
        }

        /** A spec given as text, as the authoring MCP server receives it, under [name], the name the answer calls it by. */
        fun of(name: String, source: String): Draft {
            val diagnostics = try {
                Validator.validate(source)
            } catch (syntaxError: VelleSyntaxError) {
                return Refused(refusal(name, listOf("The compiler cannot read it: ${syntaxError.message}")))
            }
            if (diagnostics.isNotEmpty()) return Refused(refusal(name, diagnostics.map { diagnostic -> diagnostic.toString() }))
            return Valid(SpecVersion(name, source))
        }

        private fun refusal(path: String, reasons: List<String>): String = buildString {
            appendLine("`$path` does not validate. The compiler refuses it for these reasons:")
            appendLine()
            reasons.forEach { reason -> appendLine("- " + reason.lines().joinToString("\n  ")) }
        }
    }
}
