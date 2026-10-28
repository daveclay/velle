package velle.api.spring

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import velle.FiringFailure
import velle.RecordRef
import velle.Rejection
import velle.TickListener
import velle.TickReport

/** A listener the starter registers of its own accord; the application's listeners are every other [TickListener] bean. */
interface StarterTickListener : TickListener

/**
 * The log, which hears every tick and every act whose after-commit firings were
 * refused or failed. Registered when the application declares no [TickListener]
 * bean of its own: a tick that ran is info, a refused or failed firing is warn
 * with the rendered message, so it reaches a human by default (Reference §21).
 */
class LoggingTickListener : StarterTickListener {
    private val log = LoggerFactory.getLogger(LoggingTickListener::class.java)

    override fun ticked(report: TickReport, rejections: List<Rejection>) {
        if (rejections.isEmpty() && report.failures.isEmpty()) log.info("Velle tick {} ran: {}", report.schedule, report.firings)
        else log.warn("Velle tick {} ran: {}; {} firing(s) rejected, {} failed:\n{}", report.schedule, report.firings, rejections.size, report.failures.size,
            (rejections.map { it.message } + report.failures.map { it.message }).joinToString("\n\n"))
    }

    override fun afterCommit(act: RecordRef?, rejections: List<Rejection>, failures: List<FiringFailure>) {
        log.warn("Velle act {} stands; {} after-commit firing(s) rejected, {} failed:\n{}", act ?: "(transient)", rejections.size, failures.size,
            (rejections.map { it.message } + failures.map { it.message }).joinToString("\n\n"))
    }
}

/**
 * Counts, when the application has a `MeterRegistry`: `velle.ticks` by `schedule` and
 * `outcome` (`clean`, `rejected`, `failed`), `velle.tick.firings`, `velle.tick.rejections`,
 * and `velle.tick.failures` by `schedule` and `rule`, and `velle.after_commit.rejections`
 * and `velle.after_commit.failures` by `rule`. Spring's own observation of the scheduled
 * task already times each run.
 */
class MetricsTickListener(private val registry: MeterRegistry) : StarterTickListener {

    override fun ticked(report: TickReport, rejections: List<Rejection>) {
        val outcome = when {
            rejections.isNotEmpty() -> "rejected"
            report.failures.isNotEmpty() -> "failed"
            else -> "clean"
        }
        registry.counter("velle.ticks", "schedule", report.schedule, "outcome", outcome).increment()
        for ((rule, n) in report.firings) registry.counter("velle.tick.firings", "schedule", report.schedule, "rule", rule).increment(n.toDouble())
        for (r in rejections) registry.counter("velle.tick.rejections", "schedule", report.schedule, "rule", r.rule ?: "").increment()
        for (f in report.failures) registry.counter("velle.tick.failures", "schedule", report.schedule, "rule", f.rule).increment()
    }

    override fun afterCommit(act: RecordRef?, rejections: List<Rejection>, failures: List<FiringFailure>) {
        for (r in rejections) registry.counter("velle.after_commit.rejections", "rule", r.rule ?: "").increment()
        for (f in failures) registry.counter("velle.after_commit.failures", "rule", f.rule).increment()
    }
}
