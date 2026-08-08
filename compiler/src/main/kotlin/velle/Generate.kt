package velle

import java.io.File

/**
 * Regenerates one system's MockHarness surface and executable specs, writing
 * relative to the working directory — each example's output module registers a
 * `generate` task passing its own spec; the root `generate` task runs them all.
 */
fun main(args: Array<String>) {
    require(args.size == 2) { "usage: velle.GenerateKt <spec.velle> <SystemName> (run from an output module dir)" }
    generateSystem(args[0], args[1])
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

    val featuresDir = File("features/${systemName.lowercase()}")
    featuresDir.deleteRecursively()
    featuresDir.mkdirs()
    for ((name, content) in specs.featureFiles) {
        File(featuresDir, name).writeText(content)
        println("wrote ${File(featuresDir, name).path}")
    }
}
