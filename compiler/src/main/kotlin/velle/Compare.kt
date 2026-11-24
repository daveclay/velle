package velle

import java.io.File

/**
 * Compares two versions of a spec and prints, as Markdown, what changed in the business's
 * words: `velle.CompareKt <before.velle> <after.velle>`. Both specs must validate.
 */
fun main(args: Array<String>) {
    require(args.size == 2) { "usage: velle.CompareKt <before.velle> <after.velle>" }
    val before = readValidated(args[0])
    val after = readValidated(args[1])
    print(SpecComparison.report(before, after))
}

private fun readValidated(path: String): SpecVersion {
    val source = File(path).readText()
    val diagnostics = Validator.validate(source)
    check(diagnostics.isEmpty()) { "$path does not validate:\n" + diagnostics.joinToString("\n") }
    return SpecVersion(path, source)
}
