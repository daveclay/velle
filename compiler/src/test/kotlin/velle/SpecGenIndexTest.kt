package velle

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every prohibition appears in the spec index (testgen.md, "Organization"): as a generated case,
 * or under "Not yet generated" with the reason. A `never` over a shape that two parts take with
 * `is`, over a static union, or over a composite with no common base has no single base shape;
 * it is listed with a reason rather than dropped.
 */
class SpecGenIndexTest {

    private val takenShapeSpec = """
        transient act Upload { note: text } with rows
        shape Row { upload: one Upload, ident: text }
        shape CreateRow is Row { newKey: text }
        shape UpdateRow is Row { key: text }
        never (Row where ident == "")
        never (CreateRow where newKey == "")
        never (UpdateRow where key == "")
        shape Made { key: text }
        rule HandleUpload when Upload { for each r in rows { Made from { key: r.ident } } }
    """.trimIndent()

    @Test
    fun `a never over a taken shape is listed with the kinds it is over`() {
        val index = SpecGen.generate(takenShapeSpec, "Ex").index
        assertTrue("- never #1: shape is a union over CreateRow or UpdateRow" in index, index)
        assertTrue("- never #2: shape is not an act" in index, index)
        assertTrue("- never #3: shape is not an act" in index, index)
    }

    @Test
    fun `a never over a union and over a composite with no common base are listed`() {
        val spec = """
            act Post { body: text, score: integer }
            act Photo { caption: text, score: integer }
            shape Content = Post or Photo
            never (Content where score < 0)
            never (Post and Photo)
        """.trimIndent()
        val index = SpecGen.generate(spec, "Ex").index
        assertTrue("- never #1: shape is a union over Post or Photo" in index, index)
        assertTrue("- never #2: composite never" in index, index)
    }

    @Test
    fun `every example lists each of its prohibitions, generated or not`() {
        for (spec in File("../examples").listFiles()!!.flatMap { dir -> dir.listFiles { file -> file.extension == "velle" }?.toList() ?: emptyList() }) {
            val source = spec.readText()
            val declared = Model(Parser.parse(source)).nevers.size
            val index = SpecGen.generate(source, spec.nameWithoutExtension.replaceFirstChar { it.uppercase() }).index
            val notGenerated = index.substringAfter("## Not yet generated", "").lines().count { line -> listOf("- never #", "- requires #", "- unique #").any { line.startsWith(it) } }
            val generated = index.lines().count { it.startsWith("- never - ") || it.startsWith("- requires - ") } / 2
            assertEquals(declared, notGenerated + generated, "${spec.path}: declared $declared, listed ${notGenerated + generated}\n$index")
        }
    }
}
