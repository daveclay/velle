package velle

import java.io.File

/** The specs the output module is generated from. */
private val SYSTEMS = listOf(
    "../billing.velle" to "Billing",
    "../membership.velle" to "Membership",
)

/** Regenerates the MockHarness surfaces and the executable specs: `gradle generate`. */
fun main(args: Array<String>) {
    val systems = if (args.isEmpty()) SYSTEMS else listOf(args[0] to args[1])
    systems.forEach { (specPath, systemName) -> generateSystem(specPath, systemName) }
}

private fun generateSystem(specPath: String, systemName: String) {
    val spec = File(specPath).readText()

    val diagnostics = Validator.validate(spec)
    check(diagnostics.isEmpty()) { "$specPath does not validate:\n" + diagnostics.joinToString("\n") }

    val surface = File("src/main/kotlin/velle/generated/$systemName.kt")
    surface.parentFile.mkdirs()
    surface.writeText(Codegen.generate(spec, systemName))
    println("wrote ${surface.path}")

    val specs = SpecGen.generate(spec, systemName)
    val systemDir = File("src/test/kotlin/velle/generated/${systemName.lowercase()}")
    val specsDir = File(systemDir, "specs")
    specsDir.deleteRecursively()
    specsDir.mkdirs()
    for ((name, content) in specs.specFiles) {
        File(specsDir, name).writeText(content)
        println("wrote ${File(specsDir, name).path}")
    }
    File(specsDir, "SpecSupport.kt").writeText(specs.support)
    File(systemDir, "RequiredGivens.kt").writeText(specs.requiredGivens)
    println("wrote ${File(systemDir, "RequiredGivens.kt").path}")
    File("SPEC_INDEX-$systemName.md").writeText(specs.index)
    println("wrote SPEC_INDEX-$systemName.md")
}
