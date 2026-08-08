package velle

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class CodegenGoldenTest {

    private val systems = listOf("../membership.velle" to "Membership")

    @Test
    fun `the checked-in generated surfaces match the generator`() {
        for ((specPath, systemName) in systems) {
            val expected = Codegen.generate(File(specPath).readText(), systemName)
            val actual = File("src/main/kotlin/velle/generated/$systemName.kt").readText()
            assertEquals(expected, actual, "$systemName surface drifted — run: gradle generate")
        }
    }

    @Test
    fun `the checked-in generated specs match the generator`() {
        for ((specPath, systemName) in systems) {
            val specs = SpecGen.generate(File(specPath).readText(), systemName)
            val systemDir = File("src/test/kotlin/velle/generated/${systemName.lowercase()}")
            val specsDir = File(systemDir, "specs")
            for ((name, content) in specs.specFiles) {
                assertEquals(content, File(specsDir, name).readText(), "$name drifted — run: gradle generate")
            }
            assertEquals(specs.support, File(specsDir, "SpecSupport.kt").readText())
            assertEquals(specs.requiredGivens, File(systemDir, "RequiredGivens.kt").readText())
            assertEquals(specs.index, File("SPEC_INDEX-$systemName.md").readText())
            assertEquals(specs.specFiles.keys + "SpecSupport.kt",
                specsDir.listFiles()!!.map { it.name }.toSet(), "stale files in $systemName specs/")
            val featuresDir = File("features/${systemName.lowercase()}")
            for ((name, content) in specs.featureFiles) {
                assertEquals(content, File(featuresDir, name).readText(), "$name drifted — run: gradle generate")
            }
            assertEquals(specs.featureFiles.keys,
                featuresDir.listFiles()!!.map { it.name }.toSet(), "stale files in $systemName features/")
        }
    }
}
