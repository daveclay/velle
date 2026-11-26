package velle

/**
 * Velle written as sentences a product owner reads: the names the spec gives things, spelled
 * as the spec spells them, and everyday words. The description of a spec ([SpecDescription])
 * and the comparison of two specs ([SpecComparison]) read their words from here, so a
 * condition is said the same way in both.
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
     * "whose sentOn", and `this` still means the outer record, "it". [aliases] maps a name bound
     * outside the record to the words it is said as: a rule's actor, `actor` to "actor", or the
     * viewer of a `visible to` clause, `actor` to "that Staff".
     */
    class Subject(val possessive: String, val aliases: Map<String, String> = emptyMap()) {
        /** Who the fields are inside `exists (Reminder where ...)`: "whose", with the same names bound. */
        fun inside(): Subject = Subject("whose", aliases)

        /** The same subject with [name] bound, said as [words]. */
        fun with(name: String, words: String): Subject = Subject(possessive, aliases + (name to words))

        companion object {
            val record = Subject("its")
        }
    }

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
    fun predicate(expression: Expr, subject: Subject): String? = when (expression) {
        is Binary -> binaryPredicate(expression, subject)
        is NotExpr -> negatedPredicate(expression.inner, subject)
        is IsExpr -> isPredicate(expression, subject)
        is ExistsExpr -> existsPredicate(expression, subject)
        is BoolLit -> if (expression.value) "always" else "never"
        is PathExpr -> nameCondition(expression, subject, holds = true)
        else -> null
    }

    /** A bare name used as a condition: a true-or-false field, "its locked is true", or a refinement, "it is a HitExperiment". */
    private fun nameCondition(name: PathExpr, subject: Subject, holds: Boolean): String? {
        if (name.root in subject.aliases) return null
        val isRefinement = name.root.first().isUpperCase() && name.segs.isEmpty()
        if (isRefinement) {
            val who = if (subject.possessive == "whose") "that" else "it"
            return "$who ${if (holds) "is" else "is not"} ${withArticle(name.root)}"
        }
        return "${path(name, subject)} is ${if (holds) "true" else "false"}"
    }

    private fun binaryPredicate(expression: Binary, subject: Subject): String? = when (expression.op) {
        "and" -> joined(predicate(expression.left, subject), "and", predicate(expression.right, subject))
        "or" -> joined(predicate(expression.left, subject), "or", predicate(expression.right, subject))
        in comparisonOperators -> comparison(expression, subject)
        "contains", "in" -> membership(expression, subject)
        else -> null
    }

    /** `tags contains "honors"`, `status in ["a", "b"]`, `this in lineage`. */
    private fun membership(expression: Binary, subject: Subject): String? {
        val left = value(expression.left, subject) ?: return null
        val right = expression.right
        if (expression.op == "contains") return value(right, subject)?.let { item -> "$left includes $item" }
        if (right is ListLit) {
            val items = right.items.map { item -> value(item, subject) ?: return null }
            return "$left is one of ${items.joinToString(", ")}"
        }
        return value(right, subject)?.let { collection -> "$left is among $collection" }
    }

    private val comparisonOperators = setOf("<", "<=", ">", ">=", "==", "!=")

    private fun comparison(expression: Binary, subject: Subject): String? {
        val leftFirst = isConstant(expression.left) && !isConstant(expression.right) ||
            isClockOffset(expression.left) && !isClockOffset(expression.right)
        if (leftFirst) return comparison(mirrored(expression), subject)
        val left = value(expression.left, subject) ?: return null
        val right = value(expression.right, subject) ?: return null
        if (expression.op == "==") return "$left is $right"
        if (expression.op == "!=") return "$left is not $right"
        if (isClockOffset(expression.right)) return relativeMoment(left, expression.op, right)
        val words = if (readsTheClock(expression.left) || readsTheClock(expression.right)) timeComparisonWords(expression.op) else comparisonWords(expression.op)
        return "$left is $words $right"
    }

    /** A moment against one some time from now: "its sentOn is later than 7 days ago", "its due is 3 days from today or earlier". */
    private fun relativeMoment(left: String, operator: String, moment: String): String = when (operator) {
        ">" -> "$left is later than $moment"
        "<" -> "$left is earlier than $moment"
        ">=" -> "$left is $moment or later"
        else -> "$left is $moment or earlier"
    }

    /** `today - 7 days`, `now + 1 hours`: a moment some time from the clock. */
    private fun isClockOffset(expression: Expr): Boolean =
        expression is Binary && (expression.left == TodayLit || expression.left == NowLit) && expression.right is DurationLit &&
            (expression.op == "-" || expression.op == "+")

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
        if (inner is PathExpr) return nameCondition(inner, subject, holds = false)
        if (inner is ExistsExpr && inner.collection != null && isRelationship(inner.collection)) {
            return records(inner.collection, subject)?.absence()
        }
        if (inner is IsExpr && inner.kind == "held") {
            val described = value(inner.subject, subject) ?: return null
            return "no record points at $described through a link that cannot be left empty"
        }
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
            "held" -> "a record points at $described through a link that cannot be left empty"
            else -> null
        }
    }

    private fun existsPredicate(expression: ExistsExpr, subject: Subject): String? {
        if (expression.shape != null) {
            val owner = value(expression.forExpr ?: return null, subject) ?: return null
            return "there is ${withArticle(expression.shape)} for $owner"
        }
        val collection = expression.collection ?: return null
        val records = records(collection, subject) ?: return null
        return if (isRelationship(collection)) records.existence() else records.existenceOfAShape()
    }

    /** `exists (fills where ...)` is over the record's own relationship; `exists (Reminder where ...)` is over a shape. */
    private fun isRelationship(collection: CollectionExpr): Boolean {
        val source = collection.bindings.singleOrNull()?.source as? PathExpr ?: return false
        return !source.root.first().isUpperCase() || source.segs.isNotEmpty()
    }

    /** A value: a field of the record, a literal, today, or arithmetic over them. */
    fun value(expression: Expr, subject: Subject): String? = when (expression) {
        is PathExpr -> if (expression == PathExpr("randomUUID")) "a new random identifier" else path(expression, subject)
        is IntLit -> expression.value.toString()
        is DecLit -> expression.text
        is TextLit -> "\"${expression.value}\""
        is BoolLit -> expression.value.toString()
        is DurationLit -> "${expression.amount} ${expression.unit}"
        NoneLit -> "nothing"
        TodayLit -> "today"
        NowLit -> "now"
        is UnaryMinus -> value(expression.inner, subject)?.let { inner -> "minus $inner" }
        is Binary -> arithmetic(expression, subject) ?: whether(expression, subject)
        is NotExpr, is IsExpr, is ExistsExpr -> whether(expression, subject)
        is AggCall -> aggregate(expression, subject)
        is FunCall -> functionCall(expression, subject)
        is SetExpr -> records(expression.collection, subject)?.every()
        is Access -> value(expression.target, subject)?.let { target -> "the ${expression.segs.joinToString("'s ") { segment -> segment.name }} of $target" }
        is SingularFor -> value(expression.forExpr, subject)?.let { owner -> "the ${expression.shape} for $owner" }
        is Interpolation -> text(expression, subject)
        is WalkExpr -> walk(expression)
        else -> null
    }

    /** A condition used as a value, as a field set to whether something holds: "whether its paidOn is at most its dueBy". */
    private fun whether(expression: Expr, subject: Subject): String? = predicate(expression, subject)?.let { condition -> "whether $condition" }

    /** `"refill for ${drug.name}"` is the text "refill for [its drug's name]". */
    private fun text(expression: Interpolation, subject: Subject): String? {
        val parts = expression.parts.map { part ->
            when (part) {
                is TextRun -> part.text
                is TextPiece -> "[" + (value(part.expr, subject) ?: return null) + "]"
            }
        }
        return "the text \"${parts.joinToString("")}\""
    }

    /** `walk referrer` is every record reached by following referrer step by step; `walk ... until` has no words yet. */
    private fun walk(expression: WalkExpr): String? {
        if (expression.until != null) return null
        val limit = expression.steps?.let { steps -> ", at most $steps steps away" } ?: ""
        return "every record reached from it by following ${expression.rel} step by step$limit"
    }

    private fun path(expression: PathExpr, subject: Subject): String {
        if (expression.root == FILTERED_RECORD) return "that"
        val root = when {
            expression.root == "this" -> "it"
            expression.root in subject.aliases -> subject.aliases.getValue(expression.root)
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
        if (isClockOffset(expression)) return if (expression.op == "-") "$right ago" else "$right from $left"
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
        if (expression.keyLookup) return null
        val records = records(expression.collection, subject) ?: return null
        val order = expression.orderBy.joinToString(", then ")
        return when (expression.name) {
            "count" -> "the number of ${records.all()}"
            "sum", "max", "min" -> expression.field?.let { field -> "the ${expression.name} of $field over ${records.all()}" }
            "latest", "first" -> if (order.isEmpty()) null else "the ${expression.name} of ${records.all()} by $order"
            else -> null
        }
    }

    // ── Collections ──────────────────────────────────────────────────────────────

    /**
     * The records of a collection with one source and no `as`, in words: [singular] and [plural] name
     * them, "DelinquencyFlag" and "DelinquencyFlag records", or "its fills" for both; [filter] narrows
     * them, "whose member is it" or "that is a CompletedAssay", or is empty. [ofTheRecord] is true
     * when they are reached from the record, as its fills are.
     */
    private class Records(val singular: String, val plural: String, val filter: String, val ofTheRecord: Boolean) {
        private val pluralFilter = filter.replace("that is a", "that are a").replace("that is not", "that are not")

        private fun narrowed(noun: String, narrowing: String) = if (narrowing.isEmpty()) noun else "$noun $narrowing"

        fun all(): String = narrowed(plural, pluralFilter)

        fun every(): String = if (ofTheRecord) narrowed("those of $plural", pluralFilter) else narrowed("every $singular", filter)

        fun existence(): String = if (filter.isEmpty()) "$plural has at least one" else "at least one of $plural is one $filter"

        fun absence(): String = if (filter.isEmpty()) "$plural has none" else "none of $plural is one $filter"

        /** `exists (Reminder where ...)`: "there is a Reminder whose ...". Its negation is "there is no Reminder whose ...". */
        fun existenceOfAShape(): String = narrowed("there is ${withArticle(singular)}", filter)
    }

    private fun records(collection: CollectionExpr, subject: Subject): Records? {
        val binding = collection.bindings.singleOrNull() ?: return null
        val where = binding.alias?.let { alias -> collection.where?.let { filter -> withoutAlias(filter, alias) ?: return null } } ?: collection.where
        val filter = where?.let { condition -> predicate(condition, subject.inside()) ?: return null } ?: ""
        return when (val source = binding.source) {
            is ShapeForSource -> {
                val owner = value(source.forExpr, subject) ?: return null
                Records("${source.shape} for $owner", "${source.shape} records for $owner", filter, ofTheRecord = false)
            }
            is PathExpr -> pathRecords(source, filter, subject)
            else -> null
        }
    }

    /**
     * The name a single collection binds with `as`, `other` in `exists (StandingBooking as other where
     * other.slot == this.slot)`, names the record the filter is about, so `other.slot` is its `slot`,
     * said "whose slot", and `other` alone is "that". Null when the name is read inside a value that
     * has no rewriting here, such as a nested collection, where it would name a record further out.
     */
    private fun withoutAlias(expression: Expr, alias: String): Expr? = when (expression) {
        is PathExpr -> when {
            expression.root != alias -> expression
            expression.segs.isEmpty() -> PathExpr(FILTERED_RECORD)
            else -> PathExpr(expression.segs.first().name, expression.segs.drop(1))
        }
        is Binary -> withoutAlias(expression.left, alias)?.let { left -> withoutAlias(expression.right, alias)?.let { right -> Binary(expression.op, left, right) } }
        is NotExpr -> withoutAlias(expression.inner, alias)?.let { inner -> NotExpr(inner) }
        is IsExpr -> withoutAlias(expression.subject, alias)?.let { inner -> expression.copy(subject = inner) }
        is IntLit, is DecLit, is TextLit, is BoolLit, is DurationLit, NoneLit, TodayLit, NowLit -> expression
        else -> null
    }

    /** The record a collection's filter is about, written by [withoutAlias] where the spec wrote its `as` name alone. */
    private const val FILTERED_RECORD = "\u0000the filtered record"

    private fun pathRecords(source: PathExpr, filter: String, subject: Subject): Records? {
        val isShape = source.root.first().isUpperCase() && source.segs.isEmpty() && source.root !in subject.aliases
        if (isShape) return Records(source.root, "${source.root} records", filter, ofTheRecord = false)
        val described = path(source, subject)
        return Records(described, described, filter, ofTheRecord = true)
    }

    private fun functionCall(expression: FunCall, subject: Subject): String? {
        val arguments = expression.args.map { argument -> value(argument, subject) ?: return null }
        return "${expression.name} of ${arguments.joinToString(" and ")}"
    }

    fun capitalized(sentence: String): String = sentence.replaceFirstChar { first -> first.uppercaseChar() }
}
