package velle.validation

// ── counts spelled as words in diagnostics ───────────────────────────────

private val numberWords = listOf("one", "two", "three", "four", "five", "six", "seven", "eight", "nine")

/** [count] spelled as a word from one to nine, and in digits otherwise. */
internal fun numberWord(count: Int): String = numberWords.getOrNull(count - 1) ?: count.toString()
