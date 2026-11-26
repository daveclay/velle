package velle

/** What a [BirthValue]'s amount counts: a plain number, a span of time, or a moment counted from `today` or from `now`. */
internal enum class BirthValueKind { NUMBER, DURATION, FROM_TODAY, FROM_NOW }

/**
 * A value known at the moment a record is created, read by the state-flow analyses
 * ([FlowAnalysis.initTruth]). A number is its own amount. A span of time and a moment are
 * counted in seconds: `today + 7 days` is seven days of seconds from today. Two values are
 * compared only when they are of one kind, so a moment from `today` never meets one from
 * `now`, and a number never meets a date.
 */
internal data class BirthValue(val kind: BirthValueKind, val amount: Double) {
    fun asNumber(): Double? = amount.takeIf { kind == BirthValueKind.NUMBER }

    companion object {
        fun number(amount: Double) = BirthValue(BirthValueKind.NUMBER, amount)
    }
}

/** The arithmetic and the comparisons over [BirthValue]s: null wherever the result is not known. */
internal object BirthValues {

    private const val SECONDS_PER_DAY = 86_400.0

    /** A duration literal in seconds; null for a unit that does not convert exactly to seconds. */
    fun duration(literal: DurationLit): BirthValue? {
        if (literal.unit !in DURATION_UNITS) return null
        val seconds = literal.amount * Value.VDuration.unitSeconds(literal.unit)
        return BirthValue(BirthValueKind.DURATION, seconds.toDouble())
    }

    fun negated(value: BirthValue): BirthValue? = when (value.kind) {
        BirthValueKind.NUMBER, BirthValueKind.DURATION -> value.copy(amount = -value.amount)
        else -> null
    }

    fun arithmetic(operator: String, left: BirthValue?, right: BirthValue?): BirthValue? {
        if (left == null || right == null) return null
        return when (operator) {
            "+" -> sum(left, right)
            "-" -> negated(right)?.let { negatedRight -> sum(left, negatedRight) }
            "*" -> numbers(left, right)?.let { (leftNumber, rightNumber) -> BirthValue.number(leftNumber * rightNumber) }
            "/" -> numbers(left, right)?.takeIf { (_, divisor) -> divisor != 0.0 }
                ?.let { (leftNumber, divisor) -> BirthValue.number(leftNumber / divisor) }
            else -> null
        }
    }

    private fun numbers(left: BirthValue, right: BirthValue): Pair<Double, Double>? {
        val leftNumber = left.asNumber() ?: return null
        val rightNumber = right.asNumber() ?: return null
        return leftNumber to rightNumber
    }

    /** Two numbers, two spans, or a moment moved by a span, in either order. */
    private fun sum(left: BirthValue, right: BirthValue): BirthValue? = when {
        left.kind == right.kind && left.kind.isQuantity() -> left.copy(amount = left.amount + right.amount)
        left.kind.isMoment() && right.kind == BirthValueKind.DURATION -> moved(left, right)
        right.kind.isMoment() && left.kind == BirthValueKind.DURATION -> moved(right, left)
        else -> null
    }

    /** A moment moved by a span. A date moves by whole days only; any other span is not a date. */
    private fun moved(moment: BirthValue, span: BirthValue): BirthValue? {
        val wholeDays = span.amount % SECONDS_PER_DAY == 0.0
        if (moment.kind == BirthValueKind.FROM_TODAY && !wholeDays) return null
        return moment.copy(amount = moment.amount + span.amount)
    }

    private fun BirthValueKind.isQuantity() = this == BirthValueKind.NUMBER || this == BirthValueKind.DURATION

    private fun BirthValueKind.isMoment() = this == BirthValueKind.FROM_TODAY || this == BirthValueKind.FROM_NOW

    /** Whether `left <operator> right` holds; null when either is unknown or they are of different kinds. */
    fun compared(operator: String, left: BirthValue?, right: BirthValue?): Boolean? {
        if (left == null || right == null || left.kind != right.kind) return null
        return when (operator) {
            "<" -> left.amount < right.amount
            "<=" -> left.amount <= right.amount
            ">" -> left.amount > right.amount
            ">=" -> left.amount >= right.amount
            "==" -> left.amount == right.amount
            "!=" -> left.amount != right.amount
            else -> null
        }
    }
}
