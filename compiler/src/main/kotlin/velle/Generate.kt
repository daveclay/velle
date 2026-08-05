package velle

import java.io.File

/** Regenerates the MockHarness surface: `gradle generate`. */
fun main(args: Array<String>) {
    val specPath = args.getOrElse(0) { "billing.velle" }
    val systemName = args.getOrElse(1) { "Billing" }
    val spec = File(specPath).readText()

    val diagnostics = Validator.validate(spec)
    check(diagnostics.isEmpty()) { "spec does not validate:\n" + diagnostics.joinToString("\n") }

    val out = File("src/main/kotlin/velle/generated/$systemName.kt")
    out.parentFile.mkdirs()
    out.writeText(Codegen.generate(spec, systemName))
    println("wrote ${out.path}")
}
