package velle

/**
 * Velle written as sentences a product owner reads: the names the spec gives things, spelled
 * as the spec spells them, and everyday words. The generated cases ([SpecGen]) and the
 * comparison of two specs ([SpecComparison]) read their words from here, so a condition is
 * said the same way in both.
 *
 * Every function returns null for a form it has no words for. The caller then prints the
 * Velle and says so; it never drops the form.
 */
object Sentences {

    /** The words for a comparison operator, as the generated cases have always said them. */
    fun comparisonWords(operator: String): String = when (operator) {
        "<=" -> "at most"; "<" -> "below"; ">=" -> "at least"; ">" -> "above"
        "==" -> "equal to"; "!=" -> "other than"; else -> operator
    }

    /** The words for a comparison of moments: a date or time is before or after another. */
    private fun timeComparisonWords(operator: String): String = when (operator) {
        "<=" -> "on or before"; "<" -> "before"; ">=" -> "on or after"; ">" -> "after"
        else -> comparisonWords(operator)
    }

    fun article(name: String): String = if (name.first().lowercaseChar() in "aeiou") "an" else "a"

    fun withArticle(name: String): String = "${article(name)} $name"

    /**
     * Who a clause is about. At the top of a condition the record is "it", and its fields are
     * "its balance". Inside `exists (Reminder where ...)` the fields are the Reminder's, said
     * "whose sentOn", and `this` still means the outer record, "it".
     */
    class Subject(val possessive: String, val aliases: Set<String> = emptySet(), val vocabulary: Vocabulary = Vocabulary.SHARED) {
        /** Who the fields are inside `exists (Reminder where ...)`: "whose", in the same vocabulary. */
        fun inside(): Subject = Subject("whose", vocabulary = vocabulary)

        companion object {
            val record = Subject("its")

            /** The record a description of a spec ([SpecDescription]) is about, with the description's words. */
            val described = Subject("its", vocabulary = Vocabulary.DESCRIPTION)
        }
    }

    /**
     * Which words a sentence may use. [SHARED] are the words the generated cases and the comparison
     * of two specs use, and their output is pinned. [DESCRIPTION] adds words only `velle describe`
     * uses so far, so that adding one never changes a comparison report.
     */
    enum class Vocabulary { SHARED, DESCRIPTION }

    // ── Conditions over records ──────────────────────────────────────────────────

    /** A refinement expression as a clause about "it": `OverdueInvoice and not ArchivedInvoice`
     *  is "it is an OverdueInvoice and it is not an ArchivedInvoice". [baseShapes] are the
     *  names of shapes and acts, for which "it is an Invoice" says nothing and is left out. */
    fun clause(expression: RefExpr, baseShapes: Set<String>, subject: Subject = Subject.record): String? = when (expression) {
        is RefName -> nameClause(expression, baseShapes, subject)
        is RefNot -> negatedClause(expression.inner, baseShapes, subject)
        is RefAnd -> joined(clause(expression.left, baseShapes, subject), "and", clause(expression.right, baseShapes, subject))
        is RefOr -> joined(clause(expression.left, baseShapes, subject), "or", clause(expression.right, baseShapes, subject))
    }

    private fun nameClause(expression: RefName, baseShapes: Set<String>, subject: Subject): String? {
        val membership = if (expression.name in baseShapes) null else "it is ${withArticle(expression.name)}"
        val restriction = expression.where?.let { predicate -> predicate(predicate, subject) ?: return null }
        return listOfNotNull(membership, restriction).joinToString(" and ").ifEmpty { "it is ${withArticle(expression.name)}" }
    }

    private fun negatedClause(inner: RefExpr, baseShapes: Set<String>, subject: Subject): String? {
        if (inner is RefName && inner.where == null) return "it is not ${withArticle(inner.name)}"
        val innerClause = clause(inner, baseShapes, subject) ?: return null
        return "it is not the case that $innerClause"
    }

    private fun joined(left: String?, connective: String, right: String?): String? =
        if (left == null || right == null) null else "$left $connective $right"

    /** The leftmost name of a refinement expression: the shape or refinement it narrows. */
    fun leftmostName(expression: RefExpr): String = when (expression) {
        is RefName -> expression.name
        is RefNot -> leftmostName(expression.inner)
        is RefAnd -> leftmostName(expression.left)
        is RefOr -> leftmostName(expression.left)
    }

    // ── Predicates and values ────────────────────────────────────────────────────

    /** A predicate as a clause: `balance > 0 and due < today` is "its balance is above 0 and
     *  its due is before today". */
    fun predicate(expression: Expr, subject: Subject): String? {
        if (subject.vocabulary == Vocabulary.DESCRIPTION) DescriptionWords.predicate(expression, subject)?.let { words -> return words }
        return sharedPredicate(expression, subject)
    }

    private fun sharedPredicate(expression: Expr, subject: Subject): String? = when (expression) {
        is Binary -> binaryPredicate(expression, subject)
        is NotExpr -> negatedPredicate(expression.inner, subject)
        is IsExpr -> isPredicate(expression, subject)
        is ExistsExpr -> existsPredicate(expression, subject)
        is BoolLit -> if (expression.value) "always" else "never"
        else -> null
    }

    private fun binaryPredicate(expression: Binary, subject: Subject): String? = when (expression.op) {
        "and" -> joined(predicate(expression.left, subject), "and", predicate(expression.right, subject))
        "or" -> joined(predicate(expression.left, subject), "or", predicate(expression.right, subject))
        in comparisonOperators -> comparison(expression, subject)
        else -> null
    }

    private val comparisonOperators = setOf("<", "<=", ">", ">=", "==", "!=")

    private fun comparison(expression: Binary, subject: Subject): String? {
        if (isConstant(expression.left) && !isConstant(expression.right)) return comparison(mirrored(expression), subject)
        val left = value(expression.left, subject) ?: return null
        val right = value(expression.right, subject) ?: return null
        val words = when {
            expression.op == "==" -> return "$left is $right"
            expression.op == "!=" -> return "$left is not $right"
            readsTheClock(expression.left) || readsTheClock(expression.right) -> timeComparisonWords(expression.op)
            else -> comparisonWords(expression.op)
        }
        return "$left is $words $right"
    }

    /** `0 < total` read the way a person says it: `total > 0`. */
    private fun mirrored(expression: Binary): Binary {
        val operator = when (expression.op) { "<" -> ">"; "<=" -> ">="; ">" -> "<"; ">=" -> "<="; else -> expression.op }
        return Binary(operator, expression.right, expression.left)
    }

    private fun isConstant(expression: Expr): Boolean =
        expression is IntLit || expression is DecLit || expression is TextLit || expression is BoolLit || expression == NoneLit

    private fun readsTheClock(expression: Expr): Boolean = when (expression) {
        TodayLit, NowLit -> true
        is Binary -> readsTheClock(expression.left) || readsTheClock(expression.right)
        else -> false
    }

    private fun negatedPredicate(inner: Expr, subject: Subject): String? {
        if (inner is IsExpr && inner.kind == "refinement") {
            val described = value(inner.subject, subject) ?: return null
            return "$described is not ${withArticle(inner.refinement ?: return null)}"
        }
        if (inner is ExistsExpr) {
            val existence = existsPredicate(inner, subject) ?: return null
            return existence.replaceFirst(Regex("^there is an? "), "there is no ")
        }
        if (inner is Binary && inner.op in comparisonOperators) {
            val positive = comparison(inner, subject) ?: return null
            return positive.replaceFirst(" is ", " is not ")
        }
        val innerClause = predicate(inner, subject) ?: return null
        return "it is not the case that $innerClause"
    }

    private fun isPredicate(expression: IsExpr, subject: Subject): String? {
        val described = value(expression.subject, subject) ?: return null
        return when (expression.kind) {
            "none" -> "$described is absent"
            "some" -> "$described is present"
            "empty" -> "$described has none"
            "notEmpty" -> "$described has at least one"
            "refinement" -> "$described is ${withArticle(expression.refinement ?: return null)}"
            else -> null
        }
    }

    private fun existsPredicate(expression: ExistsExpr, subject: Subject): String? {
        if (expression.shape != null) {
            val owner = value(expression.forExpr ?: return null, subject) ?: return null
            return "there is ${withArticle(expression.shape)} for $owner"
        }
        val collection = expression.collection ?: return null
        val binding = collection.bindings.singleOrNull() ?: return null
        val source = binding.source as? PathExpr ?: return null
        if (source.segs.isNotEmpty() || binding.alias != null) return null
        val restriction = collection.where?.let { inner -> predicate(inner, subject.inside()) ?: return null }
        return listOfNotNull("there is ${withArticle(source.root)}", restriction).joinToString(" ")
    }

    /** A value: a field of the record, a literal, today, or arithmetic over them. */
    fun value(expression: Expr, subject: Subject): String? {
        if (subject.vocabulary == Vocabulary.DESCRIPTION) DescriptionWords.value(expression, subject)?.let { words -> return words }
        return sharedValue(expression, subject)
    }

    private fun sharedValue(expression: Expr, subject: Subject): String? = when (expression) {
        is PathExpr -> path(expression, subject)
        is IntLit -> expression.value.toString()
        is DecLit -> expression.text
        is TextLit -> "\"${expression.value}\""
        is BoolLit -> expression.value.toString()
        is DurationLit -> "${expression.amount} ${expression.unit}"
        NoneLit -> "nothing"
        TodayLit -> "today"
        NowLit -> "now"
        is UnaryMinus -> value(expression.inner, subject)?.let { inner -> "minus $inner" }
        is Binary -> arithmetic(expression, subject)
        is AggCall -> aggregate(expression, subject)
        is FunCall -> functionCall(expression, subject)
        else -> null
    }

    private fun path(expression: PathExpr, subject: Subject): String {
        val root = when {
            expression.root == "this" -> "it"
            expression.root in subject.aliases -> expression.root
            else -> "${subject.possessive} ${expression.root}"
        }
        if (expression.segs.isEmpty()) return root
        val owner = if (root == "it") "its" else "$root's"
        val steps = expression.segs.map { segment -> segment.name }
        return owner + " " + steps.joinToString("'s ")
    }

    private fun arithmetic(expression: Binary, subject: Subject): String? {
        val left = value(expression.left, subject) ?: return null
        val right = value(expression.right, subject) ?: return null
        if (readsTheClock(expression.left) && expression.right is DurationLit) {
            return when (expression.op) {
                "-" -> "$right before $left"
                "+" -> "$right after $left"
                else -> null
            }
        }
        val words = when (expression.op) {
            "+" -> "plus"; "-" -> "minus"; "*" -> "times"; "/" -> "divided by"
            else -> return null
        }
        return "$left $words $right"
    }

    private fun aggregate(expression: AggCall, subject: Subject): String? {
        if (expression.keyLookup || expression.orderBy.isNotEmpty()) return null
        val collection = expression.collection
        val binding = collection.bindings.singleOrNull() ?: return null
        if (collection.where != null || binding.alias != null) return null
        val over = value(binding.source, subject) ?: return null
        return when (expression.name) {
            "count" -> "the number of $over"
            "sum", "max", "min" -> expression.field?.let { field -> "the ${expression.name} of $field over $over" }
            else -> null
        }
    }

    private fun functionCall(expression: FunCall, subject: Subject): String? {
        val arguments = expression.args.map { argument -> value(argument, subject) ?: return null }
        return "${expression.name} of ${arguments.joinToString(" and ")}"
    }

    fun capitalized(sentence: String): String = sentence.replaceFirstChar { first -> first.uppercaseChar() }
}
