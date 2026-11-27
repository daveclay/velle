package velle.sql

/**
 * The name the SQL store gives a Velle name in the database: lower-case words joined
 * by `_`, the PostgreSQL convention. A capital starts a word, and a run of capitals is
 * one word, its last capital starting the next when a lower-case letter follows it:
 * `dueOn` is `due_on`, `HTTPServer` is `http_server`, `ArchivedInvoice` is
 * `archived_invoice`. Two Velle names can give one ([SqlStore] refuses to start then).
 */
fun snakeCase(name: String): String = buildString {
    name.forEachIndexed { index, letter ->
        if (index > 0 && letter.isUpperCase() && startsWord(name, index)) append('_')
        append(letter.lowercaseChar())
    }
}

/** Whether the capital at [index] starts a word: after a lower-case letter or a digit, or as the last capital of a run followed by a lower-case letter. */
private fun startsWord(name: String, index: Int): Boolean {
    val previous = name[index - 1]
    if (!previous.isUpperCase()) return true
    val next = name.getOrNull(index + 1) ?: return false
    return next.isLowerCase()
}
