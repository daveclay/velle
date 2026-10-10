package velle

import java.math.BigDecimal

/**
 * What a rejection carries (OQ47, "Rejections"). A `never` refused a
 * transaction; this is everything the runtime had in hand when it did, as
 * data for the engineer's binding code to build a specific response from,
 * plus a rendered [message] so that no binding code is required to get a
 * readable one.
 *
 * The parts: the [act] that was refused (absent for a tick); the records
 * that would have been in the forbidden state, [violating]; the records the
 * act references that the prohibition's evaluation read through, [pivots]
 * — for "cancel this order" that is the order, the thing the person was
 * acting on; any other records the evaluation read, [witnesses]; and the
 * [evaluation] itself, every member read and refinement test the predicate
 * made over the violating records, in order.
 */
data class Rejection(
    /** The prohibition's 1-based ordinal in the spec. */
    val never: Int,
    /** The prohibition's predicate as written, e.g. `Shipment where order is CancelledOrder`. */
    val prohibition: String,
    /** The shape the prohibition is over. */
    val targetShape: String,
    /** The act refused, with its fields; null when a tick's transaction was rejected. */
    val act: RecordSnapshot?,
    /** The records that would have been in the forbidden state. May include the act. */
    val violating: List<RecordSnapshot>,
    /** Records the act references that the evaluation read through. */
    val pivots: List<RecordSnapshot>,
    /** Other records the evaluation read, beyond the act, the violating records, and the pivots. */
    val witnesses: List<RecordSnapshot>,
    /** Every member read and refinement test made evaluating the violating records, deduplicated in order. */
    val evaluation: List<EvalStep>,
    /** The clock as the evaluation saw it, when the prohibition reads time. */
    val today: String?,
) {
    val message: String = RejectionRenderer.render(this)
}

/** A reference to a record: its shape and session-local id. */
data class RecordRef(val shape: String, val id: Long) {
    override fun toString() = "$shape#$id"
}

/** A record as the rejection saw it: its reference and stored fields, references as [RecordRef]. */
data class RecordSnapshot(val ref: RecordRef, val fields: Map<String, Any?>) {
    val shape get() = ref.shape
    val id get() = ref.id
}

/** One step of the prohibition's evaluation. */
sealed interface EvalStep {
    /** A member read on a record: `Order#1.cancellations = [Cancellation#2]`. */
    data class Read(val record: RecordRef, val member: String, val value: Any?) : EvalStep
    /** A refinement test on a record: `Order#1 is CancelledOrder`. */
    data class Membership(val record: RecordRef, val refinement: String, val member: Boolean) : EvalStep
}

/** The default rendering: readable without binding code, structured enough to scan. */
object RejectionRenderer {
    fun render(r: Rejection): String = buildString {
        val actRef = r.act?.ref
        fun label(ref: RecordRef) = if (ref == actRef) "this ${ref.shape}" else ref.toString()
        fun show(v: Any?): String = when (v) {
            null -> "none"
            is String -> "\"$v\""
            is RecordRef -> label(v)
            is BigDecimal -> v.stripTrailingZeros().toPlainString()
            is List<*> -> v.joinToString(", ", "[", "]") { show(it) }
            else -> v.toString()
        }
        fun record(s: RecordSnapshot) =
            "${label(s.ref)}: " + s.fields.entries.joinToString(", ") { (k, v) -> "$k = ${show(v)}" }

        val subject = r.violating.joinToString(", ") { label(it.ref) }
        val article = if (r.prohibition.first().lowercaseChar() in "aeiou") "an" else "a"
        if (r.act != null) append("${r.act.shape} refused: ") else append("Transaction rejected: ")
        appendLine("$subject would be $article ${r.prohibition}.")
        appendLine("  never #${r.never}: ${r.prohibition}")
        r.act?.let { appendLine("  ${record(it)}") }
        r.violating.filter { it.ref != actRef }.forEach { appendLine("  ${record(it)}") }
        r.pivots.forEach { appendLine("  through ${record(it)}") }
        r.witnesses.forEach { appendLine("  also ${record(it)}") }
        if (r.evaluation.isNotEmpty()) {
            appendLine("  evaluation:")
            for (step in r.evaluation) when (step) {
                is EvalStep.Read -> appendLine("    ${label(step.record)}.${step.member} = ${show(step.value)}")
                is EvalStep.Membership ->
                    appendLine("    ${label(step.record)} is ${if (step.member) "" else "not "}${step.refinement}")
            }
        }
        r.today?.let { appendLine("  today: $it") }
    }.trimEnd()
}
