package velle

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ValidatorTest {

    private fun codes(source: String): List<String> = Validator.validate(source).map { it.code }
    private fun diags(source: String): List<Diagnostic> = Validator.validate(source)

    // ── the fixture is clean ─────────────────────────────────────────────────

    @Test
    fun `billing fixture validates with no diagnostics`() {
        val result = Validator.validate(File("../examples/billing/billing.velle").readText())
        assertEquals(emptyList(), result, "expected a clean fixture, got: $result")
    }

    // ── foundations ──────────────────────────────────────────────────────────

    @Test
    fun `F1 - unknown relationship target`() {
        val src = """
            shape A {
                other: one Missing
            }
        """.trimIndent()
        assertTrue(codes(src).contains("F1"), "got: ${diags(src)}")
    }

    @Test
    fun `F1 - bare name resolves in the innermost scope only`() {
        val src = """
            expose shape Customer {
                name: text
                vip: boolean initially false
            }

            shape Odd = Customer where nonexistent == "x"
        """.trimIndent()
        assertTrue(codes(src).contains("F1"), "got: ${diags(src)}")
    }

    @Test
    fun `F3 - assigning a derived property fails`() {
        val src = """
            expose shape Account {
                balance: decimal = 0 + 0
            }

            expose shape Poke {
                account: one Account
            }

            rule Break when Poke {
                account.balance = 0
            }
        """.trimIndent()
        assertTrue(codes(src).contains("F3"), "got: ${diags(src)}")
    }

    @Test
    fun `F4 - from-mapping totality`() {
        val src = """
            expose shape Order {
                total: decimal
            }

            shape Receipt {
                order: one Order
                amount: decimal
            }

            rule SendReceipt when Order {
                Receipt from { order: this }
            }
        """.trimIndent()
        assertTrue(codes(src).contains("F4"), "got: ${diags(src)}")
    }

    @Test
    fun `F2 - the function list is closed`() {
        val src = """
            expose shape Customer {
                name: text
                shout: text = uppercase(name)
            }
        """.trimIndent()
        assertTrue(codes(src).contains("F2"), "got: ${diags(src)}")
    }

    // ── V-checks ─────────────────────────────────────────────────────────────

    @Test
    fun `V1 - two writers with coinciding triggers`() {
        val src = """
            expose shape Customer {
                email: text
            }

            expose shape CorrectEmail {
                customer: one Customer
                corrected: text
            }

            rule Apply when CorrectEmail {
                customer.email = corrected
            }

            rule Normalize when CorrectEmail {
                customer.email = lowercase(corrected)
            }
        """.trimIndent()
        assertTrue(codes(src).contains("V1"), "got: ${diags(src)}")
    }

    @Test
    fun `V1 - partitioned triggers are provably disjoint`() {
        val src = """
            expose shape Customer {
                email: text
                locked: boolean initially false
            }

            expose shape CorrectEmail {
                customer: one Customer
                corrected: text
            }

            shape ApplicableCorrection = CorrectEmail where not customer.locked
            shape RefusedCorrection = CorrectEmail where customer.locked

            rule Apply when ApplicableCorrection {
                customer.email = corrected
            }

            shape CorrectionRefusal {
                correction: one CorrectEmail
            }

            rule Refuse when RefusedCorrection {
                CorrectionRefusal from { correction: this }
            }
        """.trimIndent()
        assertEquals(emptyList(), diags(src))
    }

    @Test
    fun `V2 - forgetting the disarm is caught`() {
        val src = """
            expose shape Deposit {
                amount: decimal
                applied: boolean initially false
            }

            shape UnappliedDeposit = Deposit where not applied

            shape Application {
                deposit: one Deposit
            }

            rule Apply when UnappliedDeposit after commit, Hourly {
                Application from { deposit: this }
            }
        """.trimIndent()
        // guard atom is the flag `applied`; the body produces evidence but never assigns it
        assertTrue(codes(src).contains("V2"), "got: ${diags(src)}")
    }

    @Test
    fun `V4 - after commit without apparatus is the stranding error`() {
        val src = """
            expose shape Order {
                total: decimal
            }

            shape Confirmation {
                order: one Order
            }

            rule Confirm when Order after commit {
                Confirmation from { order: this }
            }
        """.trimIndent()
        assertTrue(codes(src).contains("V4"), "got: ${diags(src)}")
    }

    @Test
    fun `V3 - time-dependent condition with no schedule under-fires`() {
        val src = """
            expose shape Invoice {
                due: Date
            }

            shape Overdue = Invoice where due < today

            shape Alert {
                invoice: one Invoice
            }

            rule Nag when Overdue {
                Alert from { invoice: this }
            }
        """.trimIndent()
        assertTrue(codes(src).contains("V3"), "got: ${diags(src)}")
    }

    @Test
    fun `V3 - a rule on an uncommittable act can never fire`() {
        val src = """
            shape Ghost {
                note: text
            }

            shape Echo {
                ghost: one Ghost
            }

            rule Haunt when Ghost {
                Echo from { ghost: this }
            }
        """.trimIndent()
        assertTrue(codes(src).contains("V3"), "got: ${diags(src)}")
    }

    @Test
    fun `V5 - writing a frozen field without a partition`() {
        val src = """
            expose shape Invoice {
                due: Date
            }

            expose shape Issuance {
                invoice: one Invoice
            }

            shape IssuedInvoice = Invoice where exists Issuance for this {
                frozen due
            }

            expose shape ChangeDue {
                invoice: one Invoice
                newDue: Date
            }

            rule Apply when ChangeDue {
                invoice.due = newDue
            }
        """.trimIndent()
        assertTrue(codes(src).contains("V5"), "got: ${diags(src)}")
    }

    @Test
    fun `V8 - the unguarded fold double-counts by construction`() {
        val src = """
            expose shape Account {
                balance: decimal initially 0
            }

            expose shape Deposit {
                account: one Account
                amount: decimal
            }

            rule ApplyDeposit when Deposit on commit, Hourly {
                account.balance = account.balance + amount
            }
        """.trimIndent()
        assertTrue(codes(src).contains("V8"), "got: ${diags(src)}")
    }

    @Test
    fun `V10 - a rule-maintained never fails closed in v0`() {
        val src = """
            expose shape Account {
                balance: decimal initially 0
                suspended: boolean initially false
            }

            expose shape Suspend {
                account: one Account
            }

            rule ApplySuspend when Suspend {
                account.suspended = true
            }

            never (Account where suspended)
        """.trimIndent()
        assertTrue(codes(src).contains("V10"), "got: ${diags(src)}")
    }

    @Test
    fun `V16 - an unguarded creation cycle may never quiesce`() {
        val src = """
            expose shape Ping {
                note: text
            }

            shape Pong {
                ping: one Ping
            }

            rule Reply when Ping {
                Pong from { ping: this }
            }

            rule ReReply when Pong {
                Ping from { note: "again" }
            }
        """.trimIndent()
        assertTrue(codes(src).contains("V16"), "got: ${diags(src)}")
    }

    @Test
    fun `V16 - a guarded cycle quiesces`() {
        val src = """
            expose shape Receipt {
                total: decimal
            }

            shape ReceiptEmail {
                receipt: one Receipt
            }

            shape Unemailed = Receipt where not exists ReceiptEmail for this

            rule Email when Unemailed after commit, Hourly {
                ReceiptEmail from { receipt: this }
            }
        """.trimIndent()
        assertEquals(emptyList(), diags(src))
    }

    @Test
    fun `V13 - for-sugar with two matching fields is ambiguous`() {
        val src = """
            expose shape Customer {
                name: text
            }

            expose shape Referral {
                referrer: one Customer
                referee: one Customer
            }

            shape Referrer = Customer where exists Referral for this
        """.trimIndent()
        assertTrue(codes(src).contains("V13"), "got: ${diags(src)}")
    }

    // ── V12 (refinement slice): (Refinement for expr) singularity proofs ─────

    /** README §20's episodes pattern — the flagship whole-spec singularity proof. */
    private val episodes = """
        expose shape Account {
            balance: decimal
        }

        expose shape BalanceReport {
            account: one Account
            reported: decimal
        }

        shape DelinquencyFlag {
            account: one Account
            flaggedOn: Date initially today
        }

        shape DelinquencyResolution {
            flag: one DelinquencyFlag
            resolvedOn: Date initially today
        }

        shape Delinquent = Account where balance < 0
        shape OpenDelinquencyFlag = DelinquencyFlag where not exists DelinquencyResolution for this

        rule RecordBalance when BalanceReport {
            account.balance = reported
        }

        rule OpenDelinquencyEpisode
            when (Delinquent where not exists OpenDelinquencyFlag for this) {
            DelinquencyFlag from { account: this }
        }

        rule CloseDelinquencyEpisode when leaving Delinquent {
            DelinquencyResolution from { flag: (OpenDelinquencyFlag for this) }
        }
    """.trimIndent()

    @Test
    fun `V12 - guarded evidence-pair episodes spec is licensed`() {
        assertEquals(emptyList(), diags(episodes))
    }

    @Test
    fun `V12 - exposing the base defeats the proof`() {
        val src = episodes + "\n\nexpose DelinquencyFlag"
        assertTrue(codes(src).contains("V12"), "got: ${diags(src)}")
    }

    @Test
    fun `V12 - an unguarded producer defeats the proof`() {
        val src = episodes + """


            expose shape ManualFlag {
                account: one Account
            }

            rule FlagManually when ManualFlag {
                DelinquencyFlag from { account: account }
            }
        """.trimIndent()
        assertTrue(codes(src).contains("V12"), "got: ${diags(src)}")
    }

    @Test
    fun `V12 - a false-writer of the flag defeats anti-monotonicity`() {
        val src = """
            expose shape Account {
                balance: decimal
            }

            expose shape BalanceReport {
                account: one Account
                reported: decimal
            }

            shape DelinquencyFlag {
                account: one Account
                resolved: boolean initially false
            }

            expose shape FlagDispute {
                flag: one DelinquencyFlag
            }

            shape Delinquent = Account where balance < 0
            shape OpenDelinquencyFlag = DelinquencyFlag where not resolved

            rule RecordBalance when BalanceReport {
                account.balance = reported
            }

            rule OpenDelinquencyEpisode
                when (Delinquent where not exists OpenDelinquencyFlag for this) {
                DelinquencyFlag from { account: this }
            }

            rule CloseFlag when (OpenDelinquencyFlag where not account is Delinquent) {
                this.resolved = true
            }

            rule ReopenFlag when FlagDispute {
                flag.resolved = false
            }

            shape Reportable = Account where (OpenDelinquencyFlag for this).resolved == false
        """.trimIndent()
        assertTrue(codes(src).contains("V12"), "got: ${diags(src)}")
    }

    // ── A4 (advisory): drift-exposed act partitions ──────────────────────────

    /** The bare rejection-as-data partition: an is-refinement atom, no anchor. */
    private val barePartition = """
        expose shape Invoice {
            due: Date
        }

        expose shape Issuance {
            invoice: one Invoice
        }

        shape IssuedInvoice = Invoice where exists Issuance for this

        expose shape ChangeDueDate {
            invoice: one Invoice
            newDue: Date
        }

        shape ApplicableDueChange = ChangeDueDate where not invoice is IssuedInvoice
        shape RefusedDueChange    = ChangeDueDate where invoice is IssuedInvoice

        rule ApplyDueChange when ApplicableDueChange {
            invoice.due = newDue
        }

        shape DueChangeRefusal {
            change: one ChangeDueDate
            refusedOn: DateTime
        }

        rule RecordRefusal when RefusedDueChange {
            DueChangeRefusal from { change: this, refusedOn: now }
        }
    """.trimIndent()

    @Test
    fun `A4 - a bare act partition on mutable state is drift-exposed`() {
        val advisories = Validator.advisories(barePartition)
        assertEquals(2, advisories.count { it.code == "A4" }, "got: $advisories")
        // advisory, never a required diagnostic
        assertEquals(emptyList(), diags(barePartition))
    }

    @Test
    fun `A4 - the handled-once anchor silences the advisory`() {
        val src = barePartition
            .replace(
                "shape ApplicableDueChange = ChangeDueDate where not invoice is IssuedInvoice",
                """
                shape DueChangeApplication {
                    change: one ChangeDueDate
                }

                shape UnhandledDueChange = ChangeDueDate where
                    not exists DueChangeApplication for this and not exists DueChangeRefusal for this

                shape ApplicableDueChange = UnhandledDueChange where not invoice is IssuedInvoice
                """.trimIndent(),
            )
            .replace(
                "shape RefusedDueChange    = ChangeDueDate where invoice is IssuedInvoice",
                "shape RefusedDueChange = UnhandledDueChange where invoice is IssuedInvoice",
            )
            .replace(
                "invoice.due = newDue",
                "invoice.due = newDue\n    DueChangeApplication from { change: this }",
            )
        assertEquals(emptyList(), Validator.advisories(src).filter { it.code == "A4" })
        assertEquals(emptyList(), diags(src))
    }

    @Test
    fun `V12 - a one-way latch flag is as provable as the evidence pair`() {
        val src = """
            expose shape Account {
                balance: decimal
            }

            expose shape BalanceReport {
                account: one Account
                reported: decimal
            }

            shape DelinquencyFlag {
                account: one Account
                resolved: boolean initially false
            }

            shape Delinquent = Account where balance < 0
            shape OpenDelinquencyFlag = DelinquencyFlag where not resolved

            rule RecordBalance when BalanceReport {
                account.balance = reported
            }

            rule OpenDelinquencyEpisode
                when (Delinquent where not exists OpenDelinquencyFlag for this) {
                DelinquencyFlag from { account: this }
            }

            rule CloseFlag when (OpenDelinquencyFlag where not account is Delinquent) {
                this.resolved = true
            }

            shape Reportable = Account where (OpenDelinquencyFlag for this).resolved == false
        """.trimIndent()
        val v12s = diags(src).filter { it.code == "V12" }
        assertEquals(emptyList(), v12s, "got: ${diags(src)}")
    }

    // ── V14: well-foundedness (stratify, then certify) ───────────────────────

    /** README §19's predecessor recurrence — the streak as derived history. */
    private val recurrence = """
        shape Account {
            note: text
        }

        expose shape Payment {
            account: one Account
            onTime: boolean
            receivedOn: timestamp on create
            previous: one Payment? = latest(Payment where account == this.account and receivedOn < this.receivedOn by receivedOn)
            streakAfter: integer =
                if not onTime then 0
                else if previous is some then previous.streakAfter + 1
                else 1
        }
    """.trimIndent()

    @Test
    fun `V14 - the streak recurrence is certified by strict descent`() {
        assertEquals(emptyList(), diags(recurrence))
    }

    @Test
    fun `V14 - a non-strict comparison is no descent certificate`() {
        val src = recurrence.replace("receivedOn < this.receivedOn", "receivedOn <= this.receivedOn")
        assertTrue(codes(src).contains("V14"), "got: ${diags(src)}")
    }

    @Test
    fun `V14 - descent needs a creation-fixed datum`() {
        // the ordering datum is a stored field a rule reassigns — descent can be undone
        val src = """
            shape Account {
                note: text
            }

            expose shape Payment {
                account: one Account
                onTime: boolean
                position: integer
                previous: one Payment? = latest(Payment where account == this.account and position < this.position by position)
                streakAfter: integer =
                    if not onTime then 0
                    else if previous is some then previous.streakAfter + 1
                    else 1
            }

            expose shape Reorder {
                payment: one Payment
                newPosition: integer
            }

            rule Apply when Reorder {
                payment.position = newPosition
            }
        """.trimIndent()
        assertTrue(codes(src).contains("V14"), "got: ${diags(src)}")
    }

    /** README §7's root/parent example — acyclicity supplied by a spent `never`. */
    private val rootParent = """
        expose shape Node {
            parent: one Node?
            root: one Node? =
                if parent is none then none
                else if parent.root is none then parent
                else parent.root
        }
    """.trimIndent()

    @Test
    fun `V14 - root through parent is certified by a spendable never`() {
        val src = rootParent + "\n\nnever (Node where parent == this)"
        assertEquals(emptyList(), diags(src))
    }

    @Test
    fun `V14 - root through parent without the never fails closed`() {
        assertTrue(codes(rootParent).contains("V14"), "got: ${diags(rootParent)}")
    }

    @Test
    fun `V14 - a rule-maintained never cannot be spent`() {
        val src = rootParent + """


            expose shape Reparent {
                node: one Node
                newParent: one Node?
            }

            rule Apply when Reparent {
                node.parent = newParent
            }

            never (Node where parent == this)
        """.trimIndent()
        // the never reads what 'Apply' writes: rule-maintained, so V10 fails
        // closed on the invariant and V14 refuses to spend it
        assertTrue(codes(src).contains("V10"), "got: ${diags(src)}")
        assertTrue(codes(src).contains("V14"), "got: ${diags(src)}")
    }

    @Test
    fun `V14 - a direct self-read has no well-founded reading`() {
        val src = """
            shape Counter {
                base: integer
                next: integer = next + 1
            }
        """.trimIndent()
        assertTrue(codes(src).contains("V14"), "got: ${diags(src)}")
    }

    @Test
    fun `V14 - a refinement cycle has no well-founded reading`() {
        val src = """
            expose shape Item {
                flag: boolean
            }

            shape Odd = Item where this is Even
            shape Even = Item where this is Odd
        """.trimIndent()
        assertTrue(codes(src).contains("V14"), "got: ${diags(src)}")
    }

    // ── V15 (third leg): transition interference ─────────────────────────────

    /** OQ16's value-dependent interference case: two siblings write the two
     *  inputs of one watched predicate. */
    private val interference = """
        expose shape Account {
            balance: decimal
            creditLimit: decimal
        }

        expose shape AccountReview {
            account: one Account
            newBalance: decimal
            newLimit: decimal
        }

        shape Overextended = Account where balance > creditLimit

        rule ApplyBalance when AccountReview {
            account.balance = newBalance
        }

        rule ApplyLimit when AccountReview {
            account.creditLimit = newLimit
        }

        shape Alarm {
            account: one Account
        }

        rule Watch when Overextended {
            Alarm from { account: this }
        }
    """.trimIndent()

    @Test
    fun `V15 - sibling writes to both inputs of a watched refinement interfere`() {
        assertTrue(codes(interference).contains("V15"), "got: ${diags(interference)}")
    }

    @Test
    fun `V15 - with no watcher the mid-transaction history is not observed`() {
        val src = interference
            .substringBefore("shape Alarm")
            .replace("shape Overextended = Account where balance > creditLimit\n\n", "")
        assertEquals(emptyList(), diags(src), "got: ${diags(src)}")
    }

    @Test
    fun `V15 - disjoint siblings cannot interfere`() {
        val src = interference
            .replace("rule ApplyBalance when AccountReview {",
                "shape FullReview = AccountReview where newLimit > 0\n" +
                "shape PartialReview = AccountReview where not newLimit > 0\n\n" +
                "rule ApplyBalance when PartialReview {")
            .replace("rule ApplyLimit when AccountReview {", "rule ApplyLimit when FullReview {")
        assertEquals(emptyList(), diags(src).filter { it.code == "V15" }, "got: ${diags(src)}")
    }

    // ── V1: the instance-aliasing discharge (a spent never) ──────────────────

    /** OQ16's PromoteBuyer/PromoteReferrer aliasing case. */
    private val promotion = """
        expose shape Customer {
            tier: text
            referrer: one Customer?
        }

        expose shape QualifiedPurchase {
            customer: one Customer
        }

        rule PromoteBuyer when QualifiedPurchase {
            customer.tier = "gold"
        }

        rule PromoteReferrer when (QualifiedPurchase where customer.referrer is some) {
            customer.referrer.tier = "advocate"
        }
    """.trimIndent()

    @Test
    fun `V1 - routes differing by a self-hop collide without the invariant`() {
        assertTrue(codes(promotion).contains("V1"), "got: ${diags(promotion)}")
    }

    @Test
    fun `V1 - a spendable never discharges the aliasing`() {
        val src = promotion + "\n\nnever (Customer where referrer == this)"
        assertEquals(emptyList(), diags(src).filter { it.code == "V1" }, "got: ${diags(src)}")
    }
}
