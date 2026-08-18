package velle

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The falsification harness for the serialization-domain derivation (OQ40):
 * the derivation's one-sentence meaning is *two envelopes conflict iff their
 * domains intersect*, so for every pair the derivation calls **disjoint**,
 * running the two envelopes in either order against the reference evaluator
 * must produce the same final state — commutation. Any counterexample is a
 * soundness bug caught mechanically. (Conflicting pairs carry no obligation —
 * many happen to commute — but a conflicting pair that visibly does *not*
 * commute doubles as the negative control showing the fingerprint can tell
 * states apart.)
 *
 * Final states are compared by an id-insensitive fingerprint: per shape, the
 * multiset of instances with references expanded structurally — the two
 * orders assign different internal ids, and identity is the store's, not the
 * runtime's (README §5), so only structure may be compared.
 */
class CommutationTest {

    // ── the harness ──────────────────────────────────────────────────────────

    private fun system(spec: String): VelleSystem {
        val model = Model(Parser.parse(spec))
        check(model.diagnostics.isEmpty()) { model.diagnostics.toString() }
        return VelleSystem(model)
    }

    private fun VelleSystem.mustCommit(shape: String, vararg fields: Pair<String, Any?>): Long {
        val r = commit(shape, fields.toMap())
        return assertIs<CommitResult.Accepted>(r, "commit of $shape refused: $r").id
    }

    /** Id-insensitive state fingerprint: shape → sorted structural instance forms. */
    private fun fingerprint(sys: VelleSystem): Map<String, List<String>> =
        sys.model.shapes.keys.filterNot { it in sys.model.transients }.associateWith { shape ->
            sys.instancesOf(shape).map { canonical(sys, it, depth = 4) }.sorted()
        }

    private fun canonical(sys: VelleSystem, id: Long, depth: Int): String {
        val inst = sys.instances[id] ?: return "missing"
        if (depth == 0) return inst.shape
        val fields = inst.fields.entries.sortedBy { it.key }.joinToString(",") { (k, v) ->
            "$k=" + when (v) {
                is Value.VRef -> canonical(sys, v.id, depth - 1)
                is Value.VColl -> v.ids.map { canonical(sys, it, depth - 1) }.sorted().toString()
                is Value.VNum -> v.v.stripTrailingZeros().toPlainString()
                else -> v.toString()
            }
        }
        return "${inst.shape}{$fields}"
    }

    /** Run [a] then [b] on one fresh system, [b] then [a] on another (same
     *  [setup] both times), and return the two final fingerprints. */
    private fun bothOrders(
        spec: String,
        setup: (VelleSystem) -> Unit,
        a: (VelleSystem) -> Unit,
        b: (VelleSystem) -> Unit,
    ): Pair<Map<String, List<String>>, Map<String, List<String>>> {
        val ab = system(spec).also { setup(it); a(it); b(it) }
        val ba = system(spec).also { setup(it); b(it); a(it) }
        assertTrue(ab.failures.isEmpty() && ba.failures.isEmpty(),
            "firings failed: ${ab.failures + ba.failures}")
        return fingerprint(ab) to fingerprint(ba)
    }

    // ── deposits: disjoint account keys commute ──────────────────────────────

    private val deposits = """
        shape Account {
            balance: decimal initially 0
        }
        expose Account

        expose shape Deposit {
            account: one Account
            amount: decimal
        }

        shape DepositApplication {
            deposit: one Deposit
            appliedOn: DateTime
        }

        shape UnappliedDeposit = Deposit where not exists DepositApplication for this

        rule ApplyDeposit when UnappliedDeposit after commit, Hourly {
            account.balance = account.balance + amount
            DepositApplication from { deposit: this, appliedOn: now }
        }
    """.trimIndent()

    @Test
    fun `deposits to different accounts commute - the derivation calls them disjoint`() {
        // derived domain: {deposit.account} — two calls with different accounts
        // have disjoint key sets, so U3 licenses running them in parallel;
        // the harness checks the license: either order, identical final state
        var acct1 = 0L; var acct2 = 0L
        val (ab, ba) = bothOrders(
            deposits,
            setup = { acct1 = it.mustCommit("Account"); acct2 = it.mustCommit("Account") },
            a = { it.mustCommit("Deposit", "account" to acct1, "amount" to BigDecimal("100")) },
            b = { it.mustCommit("Deposit", "account" to acct2, "amount" to BigDecimal("250")) },
        )
        assertEquals(ab, ba)
    }

    // ── transfers: disjoint {source,target} sets commute ─────────────────────

    private val transfers = """
        shape Account {
            balance: decimal initially 0
        }
        expose Account

        shape LedgerEntry {
            account: one Account
            amount: decimal
        }

        expose shape Transfer {
            source: one Account
            target: one Account
            amount: decimal
        }

        rule PostTransfer when Transfer {
            LedgerEntry from { account: source, amount: -amount }
            LedgerEntry from { account: target, amount: amount }
        }
    """.trimIndent()

    @Test
    fun `transfers over disjoint account pairs commute`() {
        // A→B and C→D: derived domains {source,target} evaluate to disjoint
        // sets, so the pair must commute
        val accts = LongArray(4)
        val (ab, ba) = bothOrders(
            transfers,
            setup = { sys -> repeat(4) { i -> accts[i] = sys.mustCommit("Account") } },
            a = { it.mustCommit("Transfer", "source" to accts[0], "target" to accts[1], "amount" to BigDecimal("10")) },
            b = { it.mustCommit("Transfer", "source" to accts[2], "target" to accts[3], "amount" to BigDecimal("20")) },
        )
        assertEquals(ab, ba)
    }

    // ── branch caps: approvals at different branches commute ─────────────────

    private val branchCap = """
        shape Branch {
            lendingCap: decimal
        }
        expose Branch

        expose shape Loan {
            branch: one Branch
            amount: decimal
            approved: boolean initially false
        }

        shape ApprovedLoan = Loan where approved == true

        expose shape ApproveLoan {
            loan: one Loan
        }

        rule Approve when ApproveLoan {
            loan.approved = true
        }

        never (Branch where sum(loans where ApprovedLoan, amount) > lendingCap)
    """.trimIndent()

    @Test
    fun `approvals at different branches commute - the cap collapsed to the branch key`() {
        var loan1 = 0L; var loan2 = 0L
        val (ab, ba) = bothOrders(
            branchCap,
            setup = { sys ->
                val b1 = sys.mustCommit("Branch", "lendingCap" to BigDecimal("1000"))
                val b2 = sys.mustCommit("Branch", "lendingCap" to BigDecimal("1000"))
                loan1 = sys.mustCommit("Loan", "branch" to b1, "amount" to BigDecimal("600"))
                loan2 = sys.mustCommit("Loan", "branch" to b2, "amount" to BigDecimal("700"))
            },
            a = { it.mustCommit("ApproveLoan", "loan" to loan1) },
            b = { it.mustCommit("ApproveLoan", "loan" to loan2) },
        )
        assertEquals(ab, ba)
    }

    // ── the negative control: a conflicting pair that visibly cannot commute ─

    private val uniqueness = """
        expose shape Customer {
            email: text
            name: text
        }

        never (Customer where exists (Customer as other where other.email == this.email and not (other == this)))
    """.trimIndent()

    @Test
    fun `equal email values conflict and do not commute - the fingerprint can tell`() {
        // derived domain: the email *value* — two signups with the same address
        // contend, and whichever runs first wins; the orders end in different
        // states, which is exactly why U3 makes conflicting envelopes take
        // turns rather than run in parallel. This also proves the harness can
        // distinguish final states at all.
        fun signup(name: String) = { sys: VelleSystem ->
            sys.commit("Customer", mapOf("email" to "a@x.com", "name" to name)); Unit
        }
        val ab = system(uniqueness).also { signup("Ada")(it); signup("Grace")(it) }
        val ba = system(uniqueness).also { signup("Grace")(it); signup("Ada")(it) }
        assertEquals(1, ab.instancesOf("Customer").size, "the never must refuse the second signup")
        assertNotEquals(fingerprint(ab), fingerprint(ba))

        // and with different addresses the derivation calls them disjoint — they commute
        val ab2 = system(uniqueness).also {
            it.mustCommit("Customer", "email" to "a@x.com", "name" to "Ada")
            it.mustCommit("Customer", "email" to "b@x.com", "name" to "Grace")
        }
        val ba2 = system(uniqueness).also {
            it.mustCommit("Customer", "email" to "b@x.com", "name" to "Grace")
            it.mustCommit("Customer", "email" to "a@x.com", "name" to "Ada")
        }
        assertEquals(fingerprint(ab2), fingerprint(ba2))
    }

    // ── body-side correlation: the audit and the transfer meet on the account ─

    private val audits = """
        shape Account {
            balance: decimal initially 0
        }
        expose Account

        expose shape Transfer {
            source: one Account
            target: one Account
            amount: decimal
        }

        expose shape AuditRequest {
            account: one Account
        }

        shape AuditReport {
            request: one AuditRequest
            outbound: decimal
        }

        rule ReportAudit when AuditRequest {
            AuditReport from { request: this, outbound: sum(Transfer where source == this.account, amount) }
        }
    """.trimIndent()

    @Test
    fun `a body-only correlated read conflicts with the writer - and disjoint accounts commute`() {
        // the only reader correlating Transfer to an account lives in
        // ReportAudit's *body* (no `transfers` inverse exists — two Transfer
        // fields target Account). Same account: the orders visibly differ
        // (the report snapshots 0 or 10), and the derivation must call the
        // pair conflicting — before body-side collection (OQ42 item 2) it
        // called them disjoint, a soundness bug this pair would have caught.
        val accts = mutableListOf<Long>()
        fun setup(sys: VelleSystem) {
            accts.clear()
            repeat(2) { accts.add(sys.mustCommit("Account")) }
        }
        val (ab, ba) = bothOrders(
            audits,
            setup = ::setup,
            a = { it.mustCommit("Transfer", "source" to accts[0], "target" to accts[1], "amount" to BigDecimal("10")) },
            b = { it.mustCommit("AuditRequest", "account" to accts[0]) },
        )
        assertNotEquals(ab, ba, "the same-account pair must be distinguishable — it conflicts")

        // audit a different account: the derivation calls the pair disjoint,
        // so the orders must agree
        val (ab2, ba2) = bothOrders(
            audits,
            setup = ::setup,
            a = { it.mustCommit("Transfer", "source" to accts[0], "target" to accts[1], "amount" to BigDecimal("10")) },
            b = { it.mustCommit("AuditRequest", "account" to accts[1]) },
        )
        assertEquals(ab2, ba2)
    }

    // ── act-vs-tick: the sweep and the commit share the account key ──────────

    @Test
    fun `the sweep heals exactly once - guard and witness meet on the derived keys`() {
        // the deposit envelope applies in its own cascade; the Hourly sweep must
        // find nothing left to do — the guard read and the witness creation
        // contend on the same derived keys (the deposit row and its account),
        // which is what forces them to take turns instead of double-applying
        val sys = system(deposits)
        val acct = sys.mustCommit("Account")
        sys.mustCommit("Deposit", "account" to acct, "amount" to BigDecimal("100"))
        assertEquals(0, BigDecimal("100").compareTo(sys.get(acct, "balance") as BigDecimal))
        sys.advance(3600)
        sys.tick("Hourly")
        assertEquals(0, BigDecimal("100").compareTo(sys.get(acct, "balance") as BigDecimal),
            "the sweep must not re-apply an applied deposit")
        assertEquals(1, sys.instancesOf("DepositApplication").size)
    }
}
