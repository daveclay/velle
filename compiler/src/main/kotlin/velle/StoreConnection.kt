package velle

/**
 * The three parts of one store (Hydration.kt) the runtime runs over, and the
 * unit of work open against them on the current thread. A system is connected
 * to a [MemoryStore] from birth until [connect] says otherwise.
 */
internal class StoreConnection(model: Model, private val currentSession: () -> Session) {
    private val memory = MemoryStore(model)

    var resolver: StateResolver = memory
        private set
    var onCommit: CommitCallback = memory
        private set
    var tickMemory: TickMemory = memory
        private set

    fun connect(resolver: StateResolver, onCommit: CommitCallback, tickMemory: TickMemory) {
        this.resolver = resolver
        this.onCommit = onCommit
        this.tickMemory = tickMemory
    }

    /** The resolver, with a read unit open when no unit is: every fetch goes through here. */
    fun reader(): StateResolver {
        val session = currentSession()
        if (session.unitOfWork == null) session.unitOfWork = resolver.begin(WorkKind.READ)
        return resolver
    }

    /** Close whatever unit is open, read or write, any read unit first: then open a write unit. */
    fun openWriteUnit(): UnitOfWork {
        endUnit(Outcome.Committed)
        val writeUnit = resolver.begin(WorkKind.WRITE)
        currentSession().unitOfWork = writeUnit
        return writeUnit
    }

    fun endUnit(outcome: Outcome) {
        val session = currentSession()
        val openUnit = session.unitOfWork ?: return
        session.unitOfWork = null
        openUnit.end(outcome)
    }
}
