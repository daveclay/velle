package velle

import java.util.Collections

/** A test's ear: every firing failure a system reports, from its ticks and its acts' after-commit firings. */
class FailureCollector : TickListener {
    val failures: MutableList<FiringFailure> = Collections.synchronizedList(mutableListOf())
    override fun ticked(report: TickReport, rejections: List<Rejection>) { failures += report.failures }
    override fun afterCommit(act: RecordRef?, rejections: List<Rejection>, failures: List<FiringFailure>) { this.failures += failures }
}
