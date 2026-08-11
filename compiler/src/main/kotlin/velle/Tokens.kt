package velle

enum class TokType {
    UIDENT, LIDENT, KW,
    INT, DEC, TEXT,
    NEWLINE, EOF,
    LBRACE, RBRACE, LPAREN, RPAREN, COLON, COMMA,
    ASSIGN, EQ, NEQ, LT, LE, GT, GE,
    PLUS, MINUS, STAR, SLASH,
    QMARK, DOT, QDOT,
}

data class Token(val type: TokType, val text: String, val line: Int, val col: Int) {
    fun isKw(word: String) = type == TokType.KW && text == word
    override fun toString() = "${type}(${text})@$line:$col"
}

// Reserved words (grammar.md, lexical layer). Only lowercase-initial words can be
// keywords; Date/DateTime are UIDENTs recognized in type position by the parser.
val KEYWORDS: Set<String> = setOf(
    "shape", "rule", "never", "expose", "transient",
    "when", "leaving", "on", "after", "commit",
    "where", "and", "or", "not", "is", "exists", "for", "from", "then",
    "if", "else", "as", "this",
    "none", "some", "empty",
    "one", "many",
    "initially", "captured", "frozen", "tolerates",
    "timestamp", "create", "update",
    "true", "false",
    "now", "today",
    "text", "integer", "long", "decimal", "double", "boolean",
)

/**
 * Contextual, not reserved: a unit word is recognized only immediately after an
 * integer literal (`3 days`), the one position the grammar gives it — everywhere
 * else `days`, `minutes`, ... are ordinary identifiers (`minutes: integer`).
 */
val DURATION_UNITS = setOf("seconds", "minutes", "hours", "days", "weeks")
val SCALAR_KEYWORDS = setOf("text", "integer", "long", "decimal", "double", "boolean")

class VelleSyntaxError(message: String, val line: Int, val col: Int) :
    Exception("$message (at $line:$col)")
