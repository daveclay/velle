package velle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * OQ163: what the runtime hands the store at admission when a queue key's path stops
 * partway, because an optional reference along it is absent. An act's path that stops
 * contributes no key (`QueueKeys.ofAct`); a firing's path that stops keys the last record
 * it reached (`QueueKeys.ofFiring`, `lastRowReached`). In this spec both rules also key the
 * account through the shorter path `[account]`, so the two behaviours hand the store the
 * same keys. These tests pin today's behaviour until OQ163 decides it.
 */
class PartialKeyPathProbeTest {

    /** The in-memory store with every write unit's admission keys recorded. */
    private class Recording(private val inner: MemoryStore) : StateResolver by inner, CommitCallback by inner, TickMemory by inner {
        val serialized = mutableListOf<List<String>>()
        override fun begin(kind: WorkKind): UnitOfWork {
            val unit = inner.begin(kind)
            if (kind != WorkKind.WRITE) return unit
            return object : UnitOfWork {
                override fun serialize(keys: Set<AdmissionKey>) {
                    serialized.add(keys.map { key -> key.text }.sorted())
                    unit.serialize(keys)
                }
                override fun end(outcome: Outcome) = unit.end(outcome)
            }
        }
    }

    private val spec = """
        shape Region { name: text  flagged: boolean }
        shape Account { region: one Region? }
        act Charge { account: one Account }
        shape Invoice { account: one Account  overdue: boolean }
        rule FlagOnCharge when (Charge where account.region is some) { this.account.region.flagged = true }
        rule FlagOverdue when (Invoice where overdue) on Nightly { this.account.region.flagged = true }
    """.trimIndent()

    private fun system(): Pair<VelleSystem, Recording> {
        val model = Model(Parser.parse(spec))
        check(model.diagnostics.isEmpty()) { model.diagnostics.toString() }
        val system = VelleSystem(model)
        val store = Recording(MemoryStore(model))
        system.connect(store, store, store)
        return system to store
    }

    @Test
    fun `both domains key the account and the region it refers to`() {
        val domains = DomainAnalysis(Model(Parser.parse(spec)))
        val chargePaths = domains.actDomains.getValue("Charge").paths.map { path -> path.segs }.toSet()
        val overduePaths = domains.firingDomains.getValue("FlagOverdue").paths.map { path -> path.segs }.toSet()
        assertEquals(setOf(listOf("account"), listOf("account", "region")), chargePaths)
        assertEquals(setOf(emptyList(), listOf("account"), listOf("account", "region")), overduePaths)
    }

    @Test
    fun `an act whose key path meets an absent region keys only the account`() {
        val (system, store) = system()
        val account = system.seed("Account", emptyMap())
        store.serialized.clear()
        val result = system.scope { system.commit("Charge", mapOf("account" to system.idOf(account))) }
        assertIs<CommitResult.Accepted>(result, "refused: $result")
        // `[account]` keys the account; `[account, region]` stops at the absent region and contributes nothing
        assertEquals(listOf(listOf("Account#${account.key.value}")), store.serialized)
    }

    @Test
    fun `a firing whose key path meets an absent region keys the account it reached`() {
        val (system, store) = system()
        val account = system.seed("Account", emptyMap())
        val invoice = system.seed("Invoice", mapOf("account" to system.idOf(account), "overdue" to true))
        store.serialized.clear()
        // the firing itself then fails on the absent region; its keys were handed over first
        system.tick("Nightly")
        // `[]` keys the invoice, `[account]` the account, and `[account, region]` stops at the account and keys it again
        assertEquals(
            listOf(listOf("Account#${account.key.value}", "Invoice#${invoice.key.value}"), listOf("schedule:Nightly")),
            store.serialized,
        )
    }
}
