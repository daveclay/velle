package velle

import velle.Sentences.Subject

/**
 * Words only a description of a spec (`velle describe`) uses so far, for forms the shared words
 * ([Sentences]) have none for, or say in a way that misreads a relationship as a shape. Each
 * function returns null for any form it does not cover, and [Sentences] then uses its shared words.
 * Kept apart so that adding a word here never changes a comparison report.
 */
object DescriptionWords {

    fun predicate(expression: Expr, subject: Subject): String? = when (expression) {
        is PathExpr -> condition(expression, subject, holds = true)
        is NotExpr -> negated(expression.inner, subject)
        is Binary -> membership(expression, subject)
        is ExistsExpr -> expression.collection?.let { collection -> existsOver(collection, subject, holds = true) }
        else -> null
    }

    private fun negated(inner: Expr, subject: Subject): String? = when (inner) {
        is PathExpr -> condition(inner, subject, holds = false)
        is ExistsExpr -> inner.collection?.let { collection -> existsOver(collection, subject, holds = false) }
        else -> null
    }

    /** A bare name used as a condition: a true-or-false field, "its locked is true", or a refinement, "it is a HitExperiment". */
    private fun condition(name: PathExpr, subject: Subject, holds: Boolean): String? {
        if (name.root in subject.aliases) return null
        val isRefinement = name.root.first().isUpperCase() && name.segs.isEmpty()
        if (!isRefinement) return trueOrFalseField(name, subject, if (holds) "true" else "false")
        val who = if (subject.possessive == "whose") "that" else "it"
        val verb = if (holds) "is" else "is not"
        return "$who $verb ${Sentences.withArticle(name.root)}"
    }

    fun value(expression: Expr, subject: Subject): String? = when (expression) {
        is AggCall -> aggregate(expression, subject)
        is SetExpr -> records(expression.collection, subject)?.let { records -> records.every() }
        is Binary, is NotExpr, is IsExpr, is ExistsExpr -> whether(expression, subject)
        is Access -> Sentences.value(expression.target, subject)?.let { target -> "the ${expression.segs.joinToString("'s ") { segment -> segment.name }} of $target" }
        is SingularFor -> Sentences.value(expression.forExpr, subject)?.let { owner -> "the ${expression.shape} for $owner" }
        is Interpolation -> text(expression, subject)
        is WalkExpr -> walk(expression)
        is PathExpr -> if (expression == PathExpr("randomUUID")) "a new random identifier" else null
        else -> null
    }

    /** `"refill for ${drug.name}"` is the text "refill for [its drug's name]". */
    private fun text(expression: Interpolation, subject: Subject): String? {
        val parts = expression.parts.map { part ->
            when (part) {
                is TextRun -> part.text
                is TextPiece -> "[" + (Sentences.value(part.expr, subject) ?: return null) + "]"
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

    /** A true-or-false field read as a condition: `where locked` is "its locked is true". */
    private fun trueOrFalseField(field: PathExpr, subject: Subject, truth: String): String? {
        if (field.root.first().isUpperCase() || field.root in subject.aliases) return null
        val described = Sentences.value(field, subject) ?: return null
        return "$described is $truth"
    }

    /** `tags contains "honors"`, `status in ["a", "b"]`, `this in lineage`. */
    private fun membership(expression: Binary, subject: Subject): String? {
        if (expression.op != "contains" && expression.op != "in") return null
        val left = Sentences.value(expression.left, subject) ?: return null
        if (expression.op == "contains") return Sentences.value(expression.right, subject)?.let { right -> "$left includes $right" }
        val right = expression.right
        if (right is ListLit) {
            val items = right.items.map { item -> Sentences.value(item, subject) ?: return null }
            return "$left is one of ${items.joinToString(", ")}"
        }
        return Sentences.value(right, subject)?.let { collection -> "$left is among $collection" }
    }

    /** A condition used as a value, as a field set to whether something holds: "whether its paidOn is on or before its dueBy". */
    private fun whether(expression: Expr, subject: Subject): String? {
        if (expression is Binary && expression.op !in setOf("and", "or", "<", "<=", ">", ">=", "==", "!=", "contains", "in")) return null
        return Sentences.predicate(expression, subject)?.let { condition -> "whether $condition" }
    }

    // ── Collections ──────────────────────────────────────────────────────────

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

        fun existence(): String = when {
            filter.isEmpty() -> "$plural has at least one"
            else -> "at least one of $plural is one $filter"
        }

        fun absence(): String = when {
            filter.isEmpty() -> "$plural has none"
            else -> "none of $plural is one $filter"
        }
    }

    private fun records(collection: CollectionExpr, subject: Subject): Records? {
        val binding = collection.bindings.singleOrNull() ?: return null
        if (binding.alias != null) return null
        val filter = collection.where?.let { where -> Sentences.predicate(where, subject.inside()) ?: return null } ?: ""
        return when (val source = binding.source) {
            is ShapeForSource -> {
                val owner = Sentences.value(source.forExpr, subject) ?: return null
                Records("${source.shape} for $owner", "${source.shape} records for $owner", filter, ofTheRecord = false)
            }
            is PathExpr -> pathRecords(source, filter, subject)
            else -> null
        }
    }

    private fun pathRecords(source: PathExpr, filter: String, subject: Subject): Records? {
        val isShape = source.root.first().isUpperCase() && source.segs.isEmpty() && source.root !in subject.aliases
        if (isShape) return Records(source.root, "${source.root} records", filter, ofTheRecord = false)
        val described = Sentences.value(source, subject) ?: return null
        return Records(described, described, filter, ofTheRecord = true)
    }

    /** `exists (fills where ...)` over the record's own relationship; an `exists` over a shape keeps the shared words. */
    private fun existsOver(collection: CollectionExpr, subject: Subject, holds: Boolean): String? {
        val source = collection.bindings.singleOrNull()?.source as? PathExpr ?: return null
        val isRelationship = !source.root.first().isUpperCase() || source.segs.isNotEmpty()
        if (!isRelationship) return null
        val records = records(collection, subject) ?: return null
        return if (holds) records.existence() else records.absence()
    }

    private fun aggregate(expression: AggCall, subject: Subject): String? {
        if (expression.keyLookup) return null
        val collection = expression.collection
        val source = collection.bindings.singleOrNull()?.source
        val sharedWordsFit = collection.where == null && expression.orderBy.isEmpty() &&
            source is PathExpr && !source.root.first().isUpperCase()
        if (sharedWordsFit) return null
        val records = records(collection, subject) ?: return null
        val order = expression.orderBy.joinToString(", then ")
        return when (expression.name) {
            "count" -> "the number of ${records.all()}"
            "sum", "max", "min" -> expression.field?.let { field -> "the ${expression.name} of $field over ${records.all()}" }
            "latest", "first" -> if (order.isEmpty()) null else "the ${expression.name} of ${records.all()} by $order"
            else -> null
        }
    }
}
