package velle

import java.time.Instant

/**
 * A tick (evaluation.md, "Ticks"): every rule on a schedule run over its current
 * members, each firing its own transaction, and the moment the schedule ran handed to
 * the store.
 */
internal class TickRunner(private val system: VelleSystem) {
    private val model get() = system.model
    private val evaluator get() = system.evaluator
    private val workingSet get() = system.workingSet

    /**
     * A firing a `never` rejects has no caller to hand the refusal to, so when the sweep
     * is done the tick throws [TickRejected] carrying each firing's [Rejection]. The other
     * firings stand; the next tick retries the rejected ones.
     */
    fun tick(schedule: String): TickReport {
        // a tick is a new evaluation moment: select subjects against settled
        // storage, not the previous envelope's leftover working set
        workingSet.beginSnapshot()
        val previous = system.latestTick(schedule) ?: system.startTime
        val outcome = TickOutcome()
        for (rule in model.rules.values) {
            if (schedule !in rule.triggers) continue
            // a tick deletes nothing: the deletion-side forms are transaction-bound
            // and the validator refuses them a schedule (V28)
            if (rule.trigger == Trigger.DELETED) continue
            // the subjects come from every base shape the condition spans: one for an
            // ordinary refinement, each operand for a union or a taken shape (Reference §9)
            val bases = model.basesOfExpr(rule.condition)
            if (bases.isEmpty()) continue
            for (subject in subjectsOf(rule, bases, previous)) fireFor(rule, subject, schedule, previous, outcome)
        }
        // the moment goes to the store after the firings, so a sweep cut off leaves the previous
        // tick in place and the next tick covers the gap (Reference §17)
        persistTick(schedule)
        workingSet.beginSnapshot() // the firings are over: a read after the tick starts from the store
        val report = TickReport(schedule, system.now, outcome.firings, outcome.failures)
        announce(report, outcome.rejected)
        return report
    }

    /**
     * The subjects [rule] fires for at this tick. For a `leaving` rule, the leavers at the
     * tick commit itself: members under the previous tick's clock, not members now. For an
     * entering rule, subject selection runs against settled state (each firing is its own
     * later transaction), so the pre-filter is sound with no self-containment caveat: the
     * resolver's candidate superset is re-checked by the authoritative predicate right here.
     */
    private fun subjectsOf(rule: RuleDecl, bases: Set<String>, previous: Instant): List<Long> {
        val filter = if (rule.leaving) null else system.queryCompiler().filterFor(rule.condition)
        return bases.flatMap { base ->
            val candidates = if (filter == null) workingSet.idsOf(base) else workingSet.idsOfCandidates(base, filter)
            candidates.filter { id -> isSubject(rule, id, previous) }
        }
    }

    /** Each firing is its own transaction; a straggler blocks only itself. */
    private fun fireFor(rule: RuleDecl, subject: Long, schedule: String, previous: Instant, outcome: TickOutcome) {
        // an earlier firing of this tick may have moved the subject out of the
        // condition: skip it before taking a lock for nothing
        if (!isSubject(rule, subject, previous)) return
        // what the firing's own after-commit firings came to is the tick's to report
        val result = system.envelope.run(
            keys = { system.queueKeys.ofFiring(rule, subject) },
            onAfter = { transaction ->
                outcome.rejected += transaction.afterRejections
                outcome.failures += transaction.afterFailures
            },
        ) { transaction ->
            transaction.firing = rule to subject
            transaction.schedule = schedule
            // the authoritative check, under the firing's locks and against
            // post-lock state: another instance's tick, or a commit, may have
            // fired for this subject since it was selected, and a rule that
            // disarms itself then finds its condition false and does nothing
            // (OQ71 D1; Reference §16, §18)
            val fires = isSubject(rule, subject, previous)
            if (fires) system.ruleFiring.fire(rule, subject)
            fires
        }
        result.fold(
            onSuccess = { fired -> if (fired) outcome.firings[rule.name] = (outcome.firings[rule.name] ?: 0) + 1 },
            onFailure = { failure ->
                if (failure is NeverViolation) outcome.rejected.add(failure.rejection)
                else outcome.failures.add(system.ruleFiring.failureOf(rule, subject, failure))
            },
        )
    }

    /**
     * The tick is complete and on record: the listeners hear it, then the caller does.
     * A listener's exception rides as suppressed on a rejection, which is the louder news.
     */
    private fun announce(report: TickReport, rejected: List<Rejection>) {
        val rejection = if (rejected.isNotEmpty()) TickRejected(report, rejected) else null
        try {
            system.tell { listener -> listener.ticked(report, rejected) }
        } catch (failure: Throwable) {
            if (rejection == null) throw failure
            rejection.addSuppressed(failure)
        }
        if (rejection != null) throw rejection
    }

    /**
     * Hands the store the moment of [schedule]'s tick, in a write unit of its own serialized
     * on the schedule. The unit commits no record, so no rule fires and no `never` is checked.
     */
    private fun persistTick(schedule: String) {
        workingSet.beginSnapshot()
        val tickUnit = system.store.openWriteUnit()
        try {
            tickUnit.serialize(setOf(AdmissionKey.Schedule(schedule)))
            system.store.tickMemory.persistTick(schedule, system.now)
        } catch (failure: Exception) {
            system.store.endUnit(Outcome.Failed(failure))
            throw failure
        }
        system.store.endUnit(Outcome.Committed)
    }

    /** Is [subject] one the rule fires for now: a member of the condition, or for a `leaving` rule a member at [previous] and not now. */
    private fun isSubject(rule: RuleDecl, subject: Long, previous: Instant): Boolean =
        if (rule.leaving) membershipAt(previous, subject, rule.condition) && !evaluator.memberOfRefExpr(subject, rule.condition)
        else evaluator.memberOfRefExpr(subject, rule.condition)

    private fun membershipAt(time: Instant, id: Long, condition: RefExpr): Boolean {
        system.evalTime = time
        try {
            return evaluator.memberOfRefExpr(id, condition)
        } finally {
            system.evalTime = null
        }
    }

    /** What one tick's firings came to. */
    private class TickOutcome {
        val rejected = mutableListOf<Rejection>()
        val failures = mutableListOf<FiringFailure>()
        val firings = linkedMapOf<String, Int>()
    }
}
