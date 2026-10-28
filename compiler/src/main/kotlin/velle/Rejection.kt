package velle

import java.math.BigDecimal
import java.time.Instant

/**
 * What a rejection carries (`evaluation.md`, "Errors, refusals, and retry"). A `never` refused a
 * transaction; this is everything the runtime had in hand when it did, as
 * data for the engineer's binding code to build a specific response from,
 * plus a rendered [message] so that no binding code is required to get a
 * readable one.
 *
 * The parts: the [act] that was refused, or for a rule firing the [rule],
 * its [subject], and the [schedule] when a tick ran it; the records the
 * transaction [produced] beyond the act; the records that would have been in
 * the forbidden state, [violating]; the records the act (or the firing's
 * subject and products) references that the prohibition's evaluation read
 * through, [pivots] — for "cancel this order" that is the order, the thing
 * the person was acting on; any other records the evaluation read,
 * [witnesses]; and the [evaluation] itself, every member read and refinement
 * test the predicate made over the violating records, in order.
 *
 * An act's rejection comes back in [CommitResult.Refused]; a tick's is
 * thrown as [TickRejected], since a tick has no caller to return it to.
 */
data class Rejection(
    /** The prohibition's 1-based ordinal in the spec. */
    val never: Int,
    /** The forbidden configuration, e.g. `Shipment where order is CancelledOrder` — for a
     *  requirement, the complement the compiler derived. */
    val prohibition: String,
    /** The declaration as the author wrote it: `never (Shipment where ...)` or `VerifyFill requires ...`. */
    val declaration: String,
    /** The requirement's predicate as written, when the prohibition was spelled `requires`; else null. */
    val requirement: String?,
    /** The shape the prohibition is over. */
    val targetShape: String,
    /** The act refused, with its fields; null when a rule firing's transaction was rejected. */
    val act: RecordSnapshot?,
    /** The rule whose firing was rejected; null for an act. */
    val rule: String?,
    /** The schedule whose tick ran the rule; null for an act or an after-commit firing. */
    val schedule: String?,
    /** The firing's subject, the record the rule ran for; null for an act. */
    val subject: RecordSnapshot?,
    /** Records the transaction created beyond the act: the firing's products, or what the act's rules produced. */
    val produced: List<RecordSnapshot>,
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

/**
 * A firing that failed for a reason other than a `never`: the store threw, a lock
 * timed out, the runtime hit an error. Not the spec's meaning but the machinery's,
 * so it is not a [Rejection], which is what a `never` says about the books. The
 * firing's envelope rolled back; the next tick, or for an after-commit firing the
 * next commit that queues it, retries it ([S4]).
 */
data class FiringFailure(val rule: String, val subject: RecordRef?, val cause: Throwable) {
    val message: String get() = "firing of '$rule'" + (subject?.let { " for $it" } ?: "") + " failed: ${cause.message}"
}

/**
 * What one tick did (Reference §17): the schedule, the moment, how many firings
 * of each rule landed, and the firings that failed. A firing a `never` refused is
 * not here but on the [TickRejected] that carries this report. The runtime keeps
 * no report once it has returned it.
 */
data class TickReport(val schedule: String, val at: Instant, val firings: Map<String, Int>, val failures: List<FiringFailure>)

/**
 * A tick's rejection. A tick has no caller, so a firing that a `never`
 * rejects is thrown once the sweep is done, carrying each rejected firing's
 * [Rejection] — the same object an act's refusal returns — and the tick's
 * [report] of everything else it did. The other firings stood; the next tick
 * retries the rejected ones.
 */
class TickRejected(val report: TickReport, val rejections: List<Rejection>) :
    Exception(rejections.joinToString("\n\n") { it.message }) {
    val schedule: String get() = report.schedule
    val at: Instant get() = report.at
}

/**
 * Who hears what no caller is told (OQ71 D5). Registered on a [VelleSystem] with
 * `listen`, told on the thread that did the work, after the work is complete and
 * durable: a listener that throws undoes nothing, and its exception reaches the
 * caller after every listener has had its turn. A tick's outcome is [ticked]; an
 * act's `after commit` firings that a `never` refused or that failed are
 * [afterCommit], told only when there is something to tell. The committing code
 * has the same lists on its result; the listener is for the people nobody's code
 * tells: a log, an alert, a metric.
 */
interface TickListener {
    /** Every tick, once its own `Tick` row has landed: the report, and the firings a `never` refused. */
    fun ticked(report: TickReport, rejections: List<Rejection>)

    /** An accepted act whose after-commit firings came to something: the act's record (null for a transient act), the refused, the failed. */
    fun afterCommit(act: RecordRef?, rejections: List<Rejection>, failures: List<FiringFailure>) {}
}

/** A reference to a record: its shape and store key — the same pair a [Ref] carries. A record
 *  the refused transaction created has no key yet and is named by the runtime's handle instead. */
data class RecordRef(val shape: String, val key: StoreKey) {
    override fun toString() = "$shape#${key.value}"
}

/** A record as the rejection saw it: its reference and stored fields, references as [RecordRef]. */
data class RecordSnapshot(val ref: RecordRef, val fields: Map<String, Any?>) {
    val shape get() = ref.shape
    val key get() = ref.key
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
            // a value of a registered extension type reads as the type displays it (Reference §5; OQ65)
            else -> TypeRegistry.default.typeOfValue(v)?.displayAny(v) ?: v.toString()
        }
        fun record(s: RecordSnapshot) =
            "${label(s.ref)}: " + s.fields.entries.joinToString(", ") { (k, v) -> "$k = ${show(v)}" }

        val subject = r.violating.joinToString(", ") { label(it.ref) }
        val article = if (r.prohibition.first().lowercaseChar() in "aeiou") "an" else "a"
        when {
            r.act != null -> append("${r.act.shape} refused: ")
            r.schedule != null -> append("${r.schedule} tick rejected: ")
            r.rule != null -> append("rule ${r.rule} rejected: ")
            else -> append("Transaction rejected: ")
        }
        if (r.requirement != null) {
            val verb = if (r.violating.size == 1) "does" else "do"
            appendLine("${r.declaration}, and $subject $verb not.")
            appendLine("  requires #${r.never}: ${r.declaration}")
        } else {
            appendLine("$subject would be $article ${r.prohibition}.")
            appendLine("  never #${r.never}: ${r.prohibition}")
        }
        r.act?.let { appendLine("  ${record(it)}") }
        r.rule?.let { rule -> appendLine("  rule $rule" + (r.subject?.let { " for ${record(it)}" } ?: "")) }
        r.produced.forEach { appendLine("  produced ${record(it)}") }
        r.violating.filter { it.ref != actRef && it.ref != r.subject?.ref }.forEach { appendLine("  ${record(it)}") }
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
