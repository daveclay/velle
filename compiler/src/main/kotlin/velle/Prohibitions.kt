package velle

/**
 * `never` enforcement [S5]. A `never` constrains every transaction's *final* state
 * (Reference §21), and state changes only through commits — so a prohibition reading
 * nothing an envelope wrote (and not reading the clock, which moves between envelopes)
 * held at the last transaction end and still holds. The relevance test is
 * [ConditionWatcher.canChangeUnder] over the footprint, plus time reads.
 */
internal class Prohibitions(private val system: VelleSystem) {
    private val model get() = system.model

    /** The door set (Reference §21, ruled 2026-10-14): the prohibitions whose verdict
     *  no rule can change, evaluated once at the act's own commit and skipped at
     *  the transaction end. Computed by the validator from the rules' writes,
     *  creations, and deletions against each predicate's reads. */
    private val doorNevers: Set<Int> by lazy { Validator(model).doorNevers }

    /**
     * The act's door: only the door set, only over the act's own commit ([footprint]),
     * no time bypass (time never makes a commit relevant).
     */
    fun checkAtDoor(transaction: Transaction, footprint: Footprint) {
        for ((index, never) in model.nevers.withIndex()) {
            val watcher = system.watchers.neverWatchers[index] ?: continue
            if (index !in doorNevers || !watcher.canChangeUnder(footprint)) continue
            refuseIfViolated(index, never, watcher, transaction)
        }
    }

    /**
     * The transaction's end, over the envelope's cumulative footprint. At the end of an
     * act's transaction the door set is skipped — it was evaluated at the door and no rule
     * can have moved it — except a clock-reading constraint, which V30 refuses and the
     * backstop still catches here when an unvalidated spec runs.
     */
    fun checkAtClose(transaction: Transaction) {
        for ((index, never) in model.nevers.withIndex()) {
            val watcher = system.watchers.neverWatchers[index] ?: continue
            val readsTime = watcher.summary.readsTime
            val isRefusal = watcher.bases.all { base -> base in model.transients }
            if (transaction.act != null && index in doorNevers && (isRefusal || !readsTime)) continue
            if (!readsTime && !watcher.canChangeUnder(transaction.footprint)) continue
            refuseIfViolated(index, never, watcher, transaction)
        }
    }

    private fun refuseIfViolated(index: Int, never: NeverDecl, watcher: ConditionWatcher, transaction: Transaction) {
        val violating = system.watchers.membersOf(watcher).toList()
        if (violating.isEmpty()) return
        throw NeverViolation(RejectionBuilder(system, transaction, index + 1, never, watcher, violating).build())
    }
}
