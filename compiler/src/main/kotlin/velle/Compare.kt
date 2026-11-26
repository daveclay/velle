package velle

/**
 * Compares two versions of a spec and prints, as Markdown, what changed in the business's
 * words: `velle.CompareKt <before.velle> <after.velle>`, the same as `velle compare` ([Cli]).
 * Both specs must validate.
 */
fun main(arguments: Array<String>) {
    exitWith(Cli.run(listOf("compare") + arguments))
}
