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
        val result = Validator.validate(File("../billing.velle").readText())
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
            } using MockHarness

            shape Odd = Customer where nonexistent == "x"
        """.trimIndent()
        assertTrue(codes(src).contains("F1"), "got: ${diags(src)}")
    }

    @Test
    fun `F3 - assigning a derived property fails`() {
        val src = """
            expose shape Account {
                balance: decimal = 0 + 0
            } using MockHarness

            expose shape Poke {
                account: one Account
            } using MockHarness

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
            } using MockHarness

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
            } using MockHarness
        """.trimIndent()
        assertTrue(codes(src).contains("F2"), "got: ${diags(src)}")
    }

    // ── V-checks ─────────────────────────────────────────────────────────────

    @Test
    fun `V1 - two writers with coinciding triggers`() {
        val src = """
            expose shape Customer {
                email: text
            } using MockHarness

            expose shape CorrectEmail {
                customer: one Customer
                corrected: text
            } using MockHarness

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
            } using MockHarness

            expose shape CorrectEmail {
                customer: one Customer
                corrected: text
            } using MockHarness

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
            } using MockHarness

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
            } using MockHarness

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
            } using MockHarness

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
            } using MockHarness

            expose shape Issuance {
                invoice: one Invoice
            } using MockHarness

            shape IssuedInvoice = Invoice where exists Issuance for this {
                frozen due
            }

            expose shape ChangeDue {
                invoice: one Invoice
                newDue: Date
            } using MockHarness

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
            } using MockHarness

            expose shape Deposit {
                account: one Account
                amount: decimal
            } using MockHarness

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
            } using MockHarness

            expose shape Suspend {
                account: one Account
            } using MockHarness

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
            } using MockHarness

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
            } using MockHarness

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
            } using MockHarness

            expose shape Referral {
                referrer: one Customer
                referee: one Customer
            } using MockHarness

            shape Referrer = Customer where exists Referral for this
        """.trimIndent()
        assertTrue(codes(src).contains("V13"), "got: ${diags(src)}")
    }

    // ── V12 (refinement slice): (Refinement for expr) singularity proofs ─────

    /** README §20's episodes pattern — the flagship whole-spec singularity proof. */
    private val episodes = """
        expose shape Account {
            balance: decimal
        } using MockHarness

        expose shape BalanceReport {
            account: one Account
            reported: decimal
        } using MockHarness

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
        val src = episodes + "\n\nexpose DelinquencyFlag using MockHarness"
        assertTrue(codes(src).contains("V12"), "got: ${diags(src)}")
    }

    @Test
    fun `V12 - an unguarded producer defeats the proof`() {
        val src = episodes + """


            expose shape ManualFlag {
                account: one Account
            } using MockHarness

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
            } using MockHarness

            expose shape BalanceReport {
                account: one Account
                reported: decimal
            } using MockHarness

            shape DelinquencyFlag {
                account: one Account
                resolved: boolean initially false
            }

            expose shape FlagDispute {
                flag: one DelinquencyFlag
            } using MockHarness

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

    @Test
    fun `V12 - a one-way latch flag is as provable as the evidence pair`() {
        val src = """
            expose shape Account {
                balance: decimal
            } using MockHarness

            expose shape BalanceReport {
                account: one Account
                reported: decimal
            } using MockHarness

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
}
