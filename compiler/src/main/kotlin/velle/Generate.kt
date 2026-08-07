package velle

import java.io.File

/** Regenerates the MockHarness surface and the executable specs: `gradle generate`. */
fun main(args: Array<String>) {
    val specPath = args.getOrElse(0) { "billing.velle" }
    val systemName = args.getOrElse(1) { "Billing" }
    val spec = File(specPath).readText()

    val diagnostics = Validator.validate(spec)
    check(diagnostics.isEmpty()) { "spec does not validate:\n" + diagnostics.joinToString("\n") }

    val surface = File("src/main/kotlin/velle/generated/$systemName.kt")
    surface.parentFile.mkdirs()
    surface.writeText(Codegen.generate(spec, systemName))
    println("wrote ${surface.path}")

    val specs = SpecGen.generate(spec, systemName)
    val specsDir = File("src/test/kotlin/velle/generated/specs")
    specsDir.deleteRecursively()
    specsDir.mkdirs()
    for ((name, content) in specs.specFiles) {
        File(specsDir, name).writeText(content)
        println("wrote ${File(specsDir, name).path}")
    }
    File(specsDir, "SpecSupport.kt").writeText(specs.support)
    val required = File("src/test/kotlin/velle/generated/RequiredGivens.kt")
    required.parentFile.mkdirs()
    required.writeText(specs.requiredGivens)
    println("wrote ${required.path}")
    File("SPEC_INDEX.md").writeText(specs.index)
    println("wrote SPEC_INDEX.md")
}
