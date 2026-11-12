package velle

import kotlin.test.Test
import kotlin.test.assertTrue

/** The generated system embeds its spec text in a Kotlin raw string; a `$` in the spec, a text
 *  literal with pieces (Reference §5), is escaped so Kotlin does not read it as interpolation. */
class SpecConstantTest {

    @Test
    fun `a text literal with pieces survives the embedded spec text`() {
        val src = Codegen.generate("""
            act SkuFamily {
                prefix: text
                skuPattern: Regex
            }
            shape Product {
                sku: text
            }
            shape ProductOfFamily = Product where exists (SkuFamily where this.sku matches "^${'$'}{prefix}-${'$'}{skuPattern}${'$'}")
        """.trimIndent(), "Orders")
        val specText = src.substringAfter("private val SPEC = \"\"\"").substringBefore("\"\"\"")
        assertTrue("\${'$'}{prefix}" in specText, specText)
        assertTrue("\${'$'}\"" in specText, specText)
    }
}
