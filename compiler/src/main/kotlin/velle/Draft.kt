package velle

import java.io.File

/**
 * A spec file read for the `velle` command: the spec when it validates, or why it does not, said
 * as Markdown. The file is checked here, where it enters; everything after trusts it.
 */
sealed interface Draft {

    class Valid(val version: SpecVersion) : Draft

    class Refused(val report: String) : Draft

    companion object {

        fun read(path: String): Draft {
            val file = File(path)
            if (!file.isFile) return Refused("There is no file at `$path`.\n")
            val source = file.readText()
            val diagnostics = try {
                Validator.validate(source)
            } catch (syntaxError: VelleSyntaxError) {
                return Refused(refusal(path, listOf("The compiler cannot read it: ${syntaxError.message}")))
            }
            if (diagnostics.isNotEmpty()) return Refused(refusal(path, diagnostics.map { diagnostic -> diagnostic.toString() }))
            return Valid(SpecVersion(path, source))
        }

        private fun refusal(path: String, reasons: List<String>): String = buildString {
            appendLine("`$path` does not validate. The compiler refuses it for these reasons:")
            appendLine()
            reasons.forEach { reason -> appendLine("- " + reason.lines().joinToString("\n  ")) }
        }
    }
}
