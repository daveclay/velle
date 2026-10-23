package velle

/**
 * Lexer for Velle (grammar.md, "Lexical layer").
 *
 * Line-orientation: a newline is a token (statement / member separator), except
 * inside parentheses, where newlines are insignificant and suppressed here.
 * Comments run from `--` to end of line. Text literals are single-line with
 * Kotlin-style escapes minus `\$`.
 */
class Lexer(private val src: String) {

    private var pos = 0
    private var line = 1
    private var col = 1
    private var parenDepth = 0
    private val tokens = mutableListOf<Token>()

    fun lex(): List<Token> {
        while (pos < src.length) {
            val c = src[pos]
            when {
                c == '\n' -> {
                    if (parenDepth == 0) emit(TokType.NEWLINE, "\n")
                    advance()
                    line++; col = 1
                }
                c == ' ' || c == '\t' || c == '\r' -> advance()
                c == '-' && peek(1) == '-' -> skipComment()
                c.isDigit() -> number()
                c == '"' -> textLiteral()
                c.isLetter() -> identifier()
                else -> symbol()
            }
        }
        emit(TokType.EOF, "")
        return tokens
    }

    private fun skipComment() {
        while (pos < src.length && src[pos] != '\n') advance()
    }

    private fun number() {
        val startCol = col
        val sb = StringBuilder()
        while (pos < src.length && src[pos].isDigit()) { sb.append(src[pos]); advance() }
        if (pos < src.length && src[pos] == '.' && peek(1)?.isDigit() == true) {
            sb.append('.'); advance()
            while (pos < src.length && src[pos].isDigit()) { sb.append(src[pos]); advance() }
            tokens.add(Token(TokType.DEC, sb.toString(), line, startCol))
        } else {
            tokens.add(Token(TokType.INT, sb.toString(), line, startCol))
        }
    }

    private fun textLiteral() {
        val startLine = line
        val startCol = col
        advance() // opening quote
        val sb = StringBuilder()
        while (true) {
            if (pos >= src.length || src[pos] == '\n')
                throw VelleSyntaxError("unterminated text literal", startLine, startCol)
            val c = src[pos]
            if (c == '"') { advance(); break }
            if (c == '\\') {
                advance()
                if (pos >= src.length) throw VelleSyntaxError("unterminated escape", line, col)
                when (val e = src[pos]) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    'u' -> {
                        val hex = src.substring(pos + 1, minOf(pos + 5, src.length))
                        if (hex.length < 4) throw VelleSyntaxError("bad \\u escape", line, col)
                        sb.append(hex.toInt(16).toChar())
                        repeat(4) { advance() }
                    }
                    else -> throw VelleSyntaxError("unknown escape \\$e", line, col)
                }
                advance()
            } else {
                sb.append(c); advance()
            }
        }
        tokens.add(Token(TokType.TEXT, sb.toString(), startLine, startCol))
    }

    private fun identifier() {
        val startCol = col
        val sb = StringBuilder()
        while (pos < src.length && (src[pos].isLetterOrDigit())) { sb.append(src[pos]); advance() }
        val word = sb.toString()
        val type = when {
            word in KEYWORDS -> TokType.KW
            word[0].isUpperCase() -> TokType.UIDENT
            else -> TokType.LIDENT
        }
        tokens.add(Token(type, word, line, startCol))
    }

    private fun symbol() {
        val c = src[pos]
        val n = peek(1)
        fun two(t: TokType, text: String) { emit(t, text); advance(); advance() }
        fun one(t: TokType) { emit(t, c.toString()); advance() }
        when {
            c == '=' && n == '=' -> two(TokType.EQ, "==")
            c == '=' -> one(TokType.ASSIGN)
            c == '!' && n == '=' -> two(TokType.NEQ, "!=")
            c == '<' && n == '=' -> two(TokType.LE, "<=")
            c == '<' -> one(TokType.LT)
            c == '>' && n == '=' -> two(TokType.GE, ">=")
            c == '>' -> one(TokType.GT)
            c == '?' && n == '.' -> two(TokType.QDOT, "?.")
            c == '?' -> one(TokType.QMARK)
            c == '.' -> one(TokType.DOT)
            c == '+' -> one(TokType.PLUS)
            c == '-' -> one(TokType.MINUS)
            c == '*' -> one(TokType.STAR)
            c == '/' -> one(TokType.SLASH)
            c == ':' -> one(TokType.COLON)
            c == ',' -> one(TokType.COMMA)
            c == '{' -> one(TokType.LBRACE)
            c == '}' -> one(TokType.RBRACE)
            c == '(' -> { parenDepth++; one(TokType.LPAREN) }
            c == ')' -> { parenDepth--; one(TokType.RPAREN) }
            else -> throw VelleSyntaxError("unexpected character '$c'", line, col)
        }
    }

    private fun emit(type: TokType, text: String) = tokens.add(Token(type, text, line, col))
    private fun advance() { pos++; col++ }
    private fun peek(ahead: Int): Char? = src.getOrNull(pos + ahead)
}
