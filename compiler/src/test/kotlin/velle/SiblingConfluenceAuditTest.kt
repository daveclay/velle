package velle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotEquals

/**
 * The sibling-confluence audit's probe suite (OQ16; the audit doc is
 * `working-docs/audit-sibling-confluence.md`). Each probe pins two facts about
 * one divergence channel:
 *
 *  1. whether the channel is REAL — run the same scenario under two step-6
 *     firing orders (identity and reversed, via [VelleSystem.firingOrder])
 *     and diff the outcome fingerprints; and
 *  2. the validator's verdict — a real channel must be rejected (the spec is
 *     order-dependent, evaluation.md's "never observable in a valid spec"
 *     claim demands the validator refuse it), and a channel the semantics
 *     make safe must be accepted.
 *
 * The runtime deliberately executes invalid specs here: the divergence half
 * of each probe is evidence about the semantics, not about the validator.
 */
class SiblingConfluenceAuditTest {

    // ── harness ──────────────────────────────────────────────────────────────

    private fun validate(src: String): List<String> = Validator.validate(src).map { it.code }

    /** Run [scenario] under a firing-order strategy; fingerprint the outcome. */
    private fun outcome(
        src: String,
        strategy: ((List<Pair<RuleDecl, Long>>) -> List<Pair<RuleDecl, Long>>)?,
        scenario: (VelleSystem) -> Unit,
    ): Map<String, List<String>> {
        val model = Model(Parser.parse(src))
        check(model.diagnostics.isEmpty()) { "probe spec broken: ${model.diagnostics}" }
        val sys = VelleSystem(model)
        sys.firingOrder = strategy
        scenario(sys)
        return fingerprint(sys, model)
    }

    private fun bothOrders(src: String, scenario: (VelleSystem) -> Unit) =
        outcome(src, null, scenario) to outcome(src, { it.reversed() }, scenario)

    /** Id-insensitive structural fingerprint, captures and failures included. */
    private fun fingerprint(sys: VelleSystem, model: Model): Map<String, List<String>> {
        val shapes = model.shapes.keys.filterNot { it in model.transients }.associateWith { shape ->
            sys.instancesOf(shape).map { canonical(sys, it, depth = 4) }.sorted()
        }
        val captures = sys.captures.entries.map { (key, values) ->
            val (id, refinement) = key
            "$refinement@${canonical(sys, id, depth = 2)}: " +
                values.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${render(sys, it.value)}" }
        }.sorted()
        return shapes + mapOf("«captures»" to captures, "«failures»" to sys.failures.sorted())
    }

    private fun canonical(sys: VelleSystem, id: Long, depth: Int): String {
        val inst = sys.instances[id] ?: return "missing"
        if (depth == 0) return inst.shape
        val fields = inst.fields.entries.sortedBy { it.key }.joinToString(",") { (k, v) ->
            "$k=" + when (v) {
                is Value.VRef -> canonical(sys, v.id, depth - 1)
                is Value.VColl -> v.ids.map { canonical(sys, it, depth - 1) }.sorted().toString()
                else -> render(sys, v)
            }
        }
        return "${inst.shape}{$fields}"
    }

    private fun render(sys: VelleSystem, v: Value): String = when (v) {
        is Value.VNum -> v.v.stripTrailingZeros().toPlainString()
        is Value.VRef -> canonical(sys, v.id, depth = 1)
        else -> v.toString()
    }

    private fun accepted(r: CommitResult): Long =
        (r as? CommitResult.Accepted)?.id ?: error("refused: $r")

    // ── A. body reads a sibling-assigned field — real, covered ──────────────

    private val bodyFieldRead = """
        expose shape Account {
            f: decimal initially 0
        }

        expose shape Poke {
            account: one Account
            v: decimal
        }

        shape Snapshot {
            account: one Account
            seen: decimal
        }

        rule WriteF when Poke {
            account.f = v
        }

        rule ReadF when Poke {
            Snapshot from { account: account, seen: account.f }
        }
    """.trimIndent()

    @Test
    fun `A - a body field read of a sibling write diverges, and V15 rejects it`() {
        val (ab, ba) = bothOrders(bodyFieldRead) { sys ->
            val acct = accepted(sys.commit("Account", mapOf()))
            accepted(sys.commit("Poke", mapOf("account" to acct, "v" to 7)))
        }
        assertNotEquals(ab, ba, "channel A must be a real divergence")
        assertTrue(validate(bodyFieldRead).contains("V15"), "got: ${Validator.validate(bodyFieldRead)}")
    }

    // ── B. body existence read over a sibling-created shape ─────────────────
    //
    // `exists` is a predicate atom, not a value expression, so the direct
    // spelling can't appear in a `from` block — but the channel is reachable
    // through a derived-property hop, which is also the harder case for the
    // check: the existence read is one indirection away from the body text.

    private val existenceRead = """
        expose shape Account {
            note: text
            hasEvidence: boolean = if exists Evidence for this then true else false
        }

        expose shape Trigger {
            account: one Account
        }

        shape Evidence {
            account: one Account
        }

        shape Report {
            account: one Account
            saw: boolean
        }

        rule MakeEvidence when Trigger {
            Evidence from { account: account }
        }

        rule CheckEvidence when Trigger {
            Report from { account: account, saw: account.hasEvidence }
        }
    """.trimIndent()

    @Test
    fun `B - a body existence read of a sibling creation diverges, and V15 rejects it`() {
        val (ab, ba) = bothOrders(existenceRead) { sys ->
            val acct = accepted(sys.commit("Account", mapOf("note" to "n")))
            accepted(sys.commit("Trigger", mapOf("account" to acct)))
        }
        assertNotEquals(ab, ba, "channel B must be a real divergence")
        assertTrue(validate(existenceRead).contains("V15"), "got: ${Validator.validate(existenceRead)}")
    }

    // ── C. body aggregate read through an inferred inverse ──────────────────

    private val aggregateRead = """
        expose shape Account {
            note: text
        }

        expose shape Trigger {
            account: one Account
        }

        shape Payment {
            account: one Account
            amount: decimal
        }

        shape Tally {
            account: one Account
            n: integer
        }

        rule MakePayment when Trigger {
            Payment from { account: account, amount: 5 }
        }

        rule CountPayments when Trigger {
            Tally from { account: account, n: count(account.payments) }
        }
    """.trimIndent()

    @Test
    fun `C - a body aggregate over a sibling-created shape diverges, and V15 rejects it`() {
        val (ab, ba) = bothOrders(aggregateRead) { sys ->
            val acct = accepted(sys.commit("Account", mapOf("note" to "n")))
            accepted(sys.commit("Trigger", mapOf("account" to acct)))
        }
        assertNotEquals(ab, ba, "channel C must be a real divergence")
        assertTrue(validate(aggregateRead).contains("V15"), "got: ${Validator.validate(aggregateRead)}")
    }

    // ── D. body timestamp read advanced by a sibling's write ────────────────

    private val timestampRead = """
        expose shape Account {
            f: decimal initially 0
            touchedAt: timestamp on update
        }

        expose shape Poke {
            account: one Account
            v: decimal
        }

        shape Stamp {
            account: one Account
            seen: DateTime
        }

        rule WriteF when Poke {
            account.f = v
        }

        rule ReadStamp when Poke {
            Stamp from { account: account, seen: account.touchedAt }
        }
    """.trimIndent()

    @Test
    fun `D - a body timestamp read of a sibling-advanced stamp diverges, and V15 rejects it`() {
        val (ab, ba) = bothOrders(timestampRead) { sys ->
            val acct = accepted(sys.commit("Account", mapOf()))
            sys.advance(60) // the stamp's pre-write value must be distinguishable
            accepted(sys.commit("Poke", mapOf("account" to acct, "v" to 7)))
        }
        assertNotEquals(ab, ba, "channel D must be a real divergence")
        assertTrue(validate(timestampRead).contains("V15"), "got: ${Validator.validate(timestampRead)}")
    }

    // ── E. a captured value reads a sibling-assigned field ──────────────────

    private val captureValue = """
        expose shape Account {
            flagged: boolean initially false
            f: decimal initially 0
        }

        expose shape Review {
            account: one Account
            v: decimal
        }

        shape Flagged = Account where flagged {
            captured snap: decimal = f
        }

        rule Flag when Review {
            account.flagged = true
        }

        rule Bump when Review {
            account.f = v
        }
    """.trimIndent()

    @Test
    fun `E - a captured value reading a sibling-assigned field diverges, and V15 rejects it`() {
        val (ab, ba) = bothOrders(captureValue) { sys ->
            val acct = accepted(sys.commit("Account", mapOf()))
            accepted(sys.commit("Review", mapOf("account" to acct, "v" to 9)))
        }
        assertNotEquals(ab, ba, "channel E must be a real divergence")
        assertTrue(validate(captureValue).contains("V15"), "got: ${Validator.validate(captureValue)}")
    }

    // ── F. two after-commit followers of one commit, in queue order ─────────

    private val afterPair = """
        expose shape Account {
            f: decimal initially 0
        }

        expose shape Poke {
            account: one Account
            v: decimal
        }

        shape WriteApp {
            poke: one Poke
        }

        shape CopyApp {
            poke: one Poke
            seen: decimal
        }

        shape UnwrittenPoke = Poke where not exists WriteApp for this
        shape UncopiedPoke = Poke where not exists CopyApp for this

        rule WriteLater when UnwrittenPoke after commit, Hourly {
            account.f = v
            WriteApp from { poke: this }
        }

        rule CopyLater when UncopiedPoke after commit, Hourly {
            CopyApp from { poke: this, seen: account.f }
        }
    """.trimIndent()

    @Test
    fun `F - two after-commit followers reading and writing one field diverge, and V15 rejects them`() {
        val (ab, ba) = bothOrders(afterPair) { sys ->
            val acct = accepted(sys.commit("Account", mapOf()))
            accepted(sys.commit("Poke", mapOf("account" to acct, "v" to 7)))
        }
        assertNotEquals(ab, ba, "channel F must be a real divergence")
        assertTrue(validate(afterPair).contains("V15"), "got: ${Validator.validate(afterPair)}")
    }

    // ── G. a sibling creation flips a watched refinement ─────────────────────

    private val creationFlip = """
        expose shape Account {
            f: decimal initially 0
        }

        expose shape Trigger {
            account: one Account
            v: decimal
        }

        shape Evidence {
            account: one Account
        }

        shape Hot = Account where f < 10 and exists Evidence for this

        shape Alarm {
            account: one Account
        }

        rule MakeEvidence when Trigger {
            Evidence from { account: account }
        }

        rule Bump when Trigger {
            account.f = v
        }

        rule Watch when Hot {
            Alarm from { account: this }
        }
    """.trimIndent()

    @Test
    fun `G - a creation-driven flip of a watched refinement diverges, and V15 rejects it`() {
        val (ab, ba) = bothOrders(creationFlip) { sys ->
            val acct = accepted(sys.commit("Account", mapOf()))
            accepted(sys.commit("Trigger", mapOf("account" to acct, "v" to 20)))
        }
        assertNotEquals(ab, ba, "channel G must be a real divergence")
        assertTrue(validate(creationFlip).contains("V15"), "got: ${Validator.validate(creationFlip)}")
    }

    // ── H. a condition-only read is pinned — confluent, and accepted ────────
    //
    // Subjects are pinned from one pre/post diff before any sibling runs
    // (evaluation.md step 5): a sibling's write cannot change who fires at
    // this commit, and the flip it does cause fires rules at its own commit —
    // causality, not a race. A condition-only read is therefore safe, and
    // rejecting it would be pure over-rejection.

    private val conditionOnlyRead = """
        expose shape Account {
            f: decimal initially 0
        }

        expose shape Poke {
            account: one Account
            v: decimal
        }

        shape RichPoke = Poke where account.f > 5

        shape Note {
            poke: one Poke
        }

        rule Bump when Poke {
            account.f = v
        }

        rule NoteRich when RichPoke {
            Note from { poke: this }
        }
    """.trimIndent()

    @Test
    fun `H - a condition-only read of a sibling write is confluent, and accepted`() {
        val (ab, ba) = bothOrders(conditionOnlyRead) { sys ->
            val acct = accepted(sys.commit("Account", mapOf()))
            accepted(sys.commit("Poke", mapOf("account" to acct, "v" to 20)))
        }
        assertEquals(ab, ba, "channel H must be confluent — subjects are pinned")
        assertEquals(emptyList(), Validator.validate(conditionOnlyRead),
            "a condition-only read must not be rejected")
    }

    // ── I. independent siblings — the confluent baseline ─────────────────────

    private val independent = """
        expose shape Account {
            f: decimal initially 0
            g: decimal initially 0
        }

        expose shape Poke {
            account: one Account
            v: decimal
        }

        rule WriteF when Poke {
            account.f = v
        }

        rule WriteG when Poke {
            account.g = v
        }
    """.trimIndent()

    @Test
    fun `I - independent sibling writes are confluent, and accepted`() {
        val (ab, ba) = bothOrders(independent) { sys ->
            val acct = accepted(sys.commit("Account", mapOf()))
            accepted(sys.commit("Poke", mapOf("account" to acct, "v" to 7)))
        }
        assertEquals(ab, ba, "independent siblings must commute")
        assertEquals(emptyList(), Validator.validate(independent))
    }

    // ── J. finding C1 — a compensating rule racing a pinned refusal ──────────
    //
    // The audit's one genuine order-dependence, found in
    // `examples/payments/payments.velle` (rule ReleaseStockOnExhaustion vs
    // rule RecordAddressRefusal) and fixed there by moving the release to
    // `after commit, Nightly`. This probe distills that spec's mechanism into
    // a standalone spec: a reservation arriving for an already-given-up order
    // pins two rules at one commit — the release (the reservation should not
    // stand) and the refusal (the order just became ready, so the pending
    // address change is refused). Release-first, the order leaves ReadyOrder
    // mid-transaction, the still-unhandled change enters ApplicableChange at
    // the release's own commit, and ApplyChange fires — while the pinned
    // refusal still lands: the change is applied AND refused. Refusal-first
    // refuses only. The ratified fix runs the release as its own transaction
    // after the commit; by then the refusal is on record, so the change can
    // never re-enter ApplicableChange.

    private fun releaseSpec(cadence: String) = """
        expose shape Order {
            address: text
            givenUp: boolean = if exists GiveUp for this then true else false
            reserved: boolean = if exists Reservation for this then true else false
        }

        expose shape GiveUp {
            order: one Order
        }

        expose shape Reservation {
            order: one Order
        }

        shape ReservationRelease {
            reservation: one Reservation
        }

        shape ActiveReservation = Reservation where not exists ReservationRelease for this

        shape ReadyOrder = Order where exists (ActiveReservation where order == this)

        expose shape ChangeAddress {
            order: one Order
            newAddress: text
        }

        shape ChangeApplication {
            change: one ChangeAddress
        }

        shape ChangeRefusal {
            change: one ChangeAddress
        }

        shape UnhandledChange = ChangeAddress where
            not exists ChangeApplication for this and not exists ChangeRefusal for this

        shape TriagedChange = UnhandledChange where order.reserved

        shape ApplicableChange = TriagedChange where not order is ReadyOrder
        shape RefusedChange    = TriagedChange where order is ReadyOrder

        rule ApplyChange when ApplicableChange {
            order.address = newAddress
            ChangeApplication from { change: this }
        }

        rule RecordRefusal when RefusedChange {
            ChangeRefusal from { change: this }
        }

        rule ReleaseOnGiveUp when (ActiveReservation where order.givenUp)$cadence {
            ReservationRelease from { reservation: this }
        }
    """.trimIndent()

    /** The pre-fix spelling: the release fires inside the same transaction. */
    private val releaseRace = releaseSpec("")

    /** The ratified fix: the release follows the commit as its own transaction. */
    private val releaseResolved = releaseSpec(" after commit, Nightly")

    private val releaseScenario: (VelleSystem) -> Unit = { sys ->
        val order = accepted(sys.commit("Order", mapOf("address" to "old")))
        accepted(sys.commit("GiveUp", mapOf("order" to order)))
        accepted(sys.commit("ChangeAddress", mapOf("order" to order, "newAddress" to "new")))
        accepted(sys.commit("Reservation", mapOf("order" to order)))
    }

    @Test
    fun `J - C1, an in-transaction release racing a pinned refusal diverges, and V15 rejects it`() {
        val (ab, ba) = bothOrders(releaseRace, releaseScenario)
        assertNotEquals(ab, ba, "channel J (finding C1) must be a real divergence")
        assertTrue(validate(releaseRace).contains("V15"), "got: ${Validator.validate(releaseRace)}")
    }

    @Test
    fun `J - C1 resolved, the release as its own after-commit transaction is confluent, and accepted`() {
        val (ab, ba) = bothOrders(releaseResolved, releaseScenario)
        assertEquals(ab, ba, "the ratified spelling must be firing-order independent")
        assertEquals(emptyList(), Validator.validate(releaseResolved),
            "the ratified spelling must validate clean")
    }
}
