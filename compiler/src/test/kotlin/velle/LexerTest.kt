package velle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LexerTest {

    private fun lex(s: String) = Lexer(s).lex()

    @Test
    fun `casing separates keywords, identifiers, and shape names`() {
        val toks = lex("shape Customer where balance")
        assertEquals(TokType.KW, toks[0].type)
        assertEquals(TokType.UIDENT, toks[1].type)
        assertEquals(TokType.KW, toks[2].type)
        assertEquals(TokType.LIDENT, toks[3].type)
    }

    @Test
    fun `qdot lexes as one token`() {
        val toks = lex("parent?.root")
        assertEquals(listOf(TokType.LIDENT, TokType.QDOT, TokType.LIDENT, TokType.EOF), toks.map { it.type })
    }

    @Test
    fun `optional marker stays its own token`() {
        val toks = lex("processedOn: Date?")
        assertEquals(TokType.QMARK, toks[3].type)
    }

    @Test
    fun `comments run to end of line`() {
        val toks = lex("a -- everything here vanishes ==\nb")
        assertEquals(listOf(TokType.LIDENT, TokType.NEWLINE, TokType.LIDENT, TokType.EOF), toks.map { it.type })
    }

    @Test
    fun `newlines are suppressed inside parentheses`() {
        val toks = lex("(a and\n b)")
        assertTrue(toks.none { it.type == TokType.NEWLINE })
    }

    @Test
    fun `text literals handle the escape set`() {
        val toks = lex(""""the \"final\" notice\n"""")
        assertEquals("the \"final\" notice\n", toks[0].text)
    }

    @Test
    fun `comparison operators lex distinctly from assignment`() {
        val toks = lex("a == b = c <= d")
        assertEquals(
            listOf(TokType.LIDENT, TokType.EQ, TokType.LIDENT, TokType.ASSIGN,
                   TokType.LIDENT, TokType.LE, TokType.LIDENT, TokType.EOF),
            toks.map { it.type })
    }
}
