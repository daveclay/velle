package velle

/**
 * A generated case's name ([SpecGen]) said as what the case checks, for the comparison of two
 * specs: `never - a LineItem where price below 0.00 is refused` is "that a LineItem whose price
 * is below 0.00 is refused". A name of a form not covered here is quoted as written.
 */
object GeneratedCaseWords {

    private val refusedPast = Regex("""^(?:never|requires) - an? (\w+) where (\w+) (.+) is (refused|accepted)$""")
    private val triedWith = Regex("""^(?:never|requires) - an? (\w+) with (\w+) (.+) is (refused|accepted)$""")
    private val ruleCase = Regex("""^(\w+) - (.+)$""")

    fun checked(case: String): String {
        refusedPast.matchEntire(case)?.let { match ->
            val (shape, field, bound, outcome) = match.destructured
            return "that ${Sentences.withArticle(shape)} whose $field is $bound is $outcome"
        }
        triedWith.matchEntire(case)?.let { match ->
            val (shape, field, value, outcome) = match.destructured
            return "that ${Sentences.withArticle(shape)} whose $field is $value is $outcome"
        }
        ruleCase.matchEntire(case)?.let { match ->
            val (rule, happening) = match.destructured
            return "that $happening, by $rule"
        }
        return "`$case`"
    }
}
