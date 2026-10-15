package velle

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    // ── V29: a firing with no caller joins never induction ───────────────────

    private val shippingWithNightlyCancel = """
        act Order { reference: text  placedOn: Date }
        act Payment { order: one Order  paidOn: Date }
        shape UnpaidOrder = Order where not exists (payments)
        act Cancellation { order: one Order  requestedOn: Date }
        shape CancelledOrder = Order where exists (cancellations)
        act Shipment { order: one Order  shippedOn: Date }
        never (Shipment where order is CancelledOrder)
    """.trimIndent()

    @Test
    fun `V29 - a scheduled rule creating a record a constraint consults is refused`() {
        val src = shippingWithNightlyCancel + """

            rule CancelUnpaid
                when (UnpaidOrder where placedOn < today - 7 days and not exists (cancellations))
                on Nightly {
                Cancellation from { order: this, requestedOn: today }
            }
        """.trimIndent()
        val d = diags(src).filter { it.code == "V29" }
        assertEquals(1, d.size, "got: ${diags(src)}")
        assertTrue("rule 'CancelUnpaid' on Nightly creates a Cancellation, which never #1 (Shipment where order is CancelledOrder) consults" in d.single().message, d.single().message)
    }

    @Test
    fun `V29 - a guard the prover cannot see through does not discharge it`() {
        val src = shippingWithNightlyCancel + """

            rule CancelUnpaid
                when (UnpaidOrder where placedOn < today - 7 days and not exists (cancellations) and not exists (shipments))
                on Nightly {
                Cancellation from { order: this, requestedOn: today }
            }
        """.trimIndent()
        assertTrue(codes(src).contains("V29"), "fail closed: got ${diags(src)}")
    }

    // ── V30: a constraint that reads the clock ───────────────────────────────

    private val permits = """
        act Site { name: text }
        act Permit { site: one Site  expiresOn: Date  issuedOn: Date }
        shape PermittedSite = Site where exists (Permit where site == this and expiresOn >= today)
        act Planting { site: one Site  plantedOn: Date }
    """.trimIndent()

    @Test
    fun `V30 - a constraint reading the clock through a consulted refinement is refused`() {
        val src = permits + "\nnever (Planting where site is not PermittedSite)"
        val d = diags(src).filter { it.code == "V30" }
        assertEquals(1, d.size, "got: ${diags(src)}")
        assertTrue("never #1 (Planting where site is not PermittedSite) over Planting reads the clock" in d.single().message, d.single().message)
        assertTrue("state it on a transient request" in d.single().message)
    }

    @Test
    fun `V30 - a constraint reading the clock directly is refused, as a requirement too`() {
        assertTrue(codes(permits + "\nnever (Planting where plantedOn > today)").contains("V30"))
        assertTrue(codes(permits + "\nPlanting requires plantedOn <= today").contains("V30"))
    }

    @Test
    fun `V30 - a refusal reads the clock once, at the request`() {
        val src = permits + """

            transient act PlantRequest { site: one Site  plantedOn: Date }
            never (PlantRequest where site is not PermittedSite)
            rule Record when PlantRequest { Planting from { site: site, plantedOn: plantedOn } }
        """.trimIndent()
        assertFalse(codes(src).contains("V30"), "got: ${diags(src)}")
    }

    // Reference §21, "Where the prohibition is written decides which it is": one
    // business sentence, two placements. On the transient request it is a
    // refusal and a past-due invoice stays an ordinary state; on the record it
    // is a constraint the books would age into, and V30 refuses it.
    private val invoiceSubmission = """
        shape Customer { name: text }
        transient act SubmitInvoice { customer: one Customer  dueOn: Date }
        shape Invoice { customer: one Customer  dueOn: Date }
        rule RecordInvoice when SubmitInvoice { Invoice from { customer: customer, dueOn: dueOn } }
    """.trimIndent()

    @Test
    fun `V30 - a due date in the past is refused on the transient request, and the past-due record is allowed`() {
        val src = invoiceSubmission + "\nnever (SubmitInvoice where dueOn < today)"
        assertEquals(emptyList(), diags(src), "got: ${diags(src)}")
        assertEquals(setOf(0), Validator(Model(Parser.parse(src))).doorNevers, "the refusal is door-checked")
    }

    @Test
    fun `V30 - the same sentence on the invoice record is a constraint the books would age into`() {
        val src = invoiceSubmission + "\nnever (Invoice where dueOn < today)"
        assertTrue(codes(src).contains("V30"), "got: ${diags(src)}")
    }

    @Test
    fun `V30 - a constraint comparing dates to each other does not read the clock`() {
        assertFalse(codes(permits + "\nnever (Permit where expiresOn < issuedOn)").contains("V30"))
    }

    @Test
    fun `V29 - an after-commit firing has no caller either`() {
        val src = shippingWithNightlyCancel + """

            transient act Review { order: one Order }
            rule CancelOnReview when (Review where order is UnpaidOrder) after commit {
                Cancellation from { order: order, requestedOn: today }
            }
        """.trimIndent()
        assertTrue(codes(src).contains("V29"), "got: ${diags(src)}")
    }

    @Test
    fun `V29 - a commit-triggered rule answers its act and is not refused here`() {
        val src = shippingWithNightlyCancel + """

            transient act Review { order: one Order }
            rule CancelOnReview when (Review where order is UnpaidOrder) {
                Cancellation from { order: order, requestedOn: today }
            }
        """.trimIndent()
        assertTrue(!codes(src).contains("V29"), "got: ${diags(src)}")
    }

    @Test
    fun `V29 - a refusal over a transient act binds no tick - a scheduled rule creating a record it consults is allowed`() {
        val src = shippingWithNightlyCancel + """

            shape UsableOrder = Order where not exists (cancellations)
            transient act Ship { order: one Order }
            never (Ship where order is not UsableOrder)
            rule RecordShipment when Ship {
                Shipment from { order: order, shippedOn: today }
            }
            rule CancelUnpaid
                when (UnpaidOrder where placedOn < today - 7 days and not exists (cancellations) and not exists (shipments))
                on Nightly {
                Cancellation from { order: this, requestedOn: today }
            }
        """.trimIndent()
        val v29 = diags(src).filter { it.code == "V29" }
        // the constraint over Shipment still refuses the nightly cancellation; the refusal over Ship does not join
        assertEquals(1, v29.size, "got: ${diags(src)}")
        assertTrue("never #1" in v29.single().message, v29.single().message)
    }

    @Test
    fun `V29 - a scheduled rule creating a record no constraint consults is allowed`() {
        val src = shippingWithNightlyCancel + """

            shape Reminder { order: one Order  sentOn: Date }
            rule RemindUnpaid when (UnpaidOrder where not exists (reminders)) on Nightly {
                Reminder from { order: this, sentOn: today }
            }
        """.trimIndent()
        assertTrue(!codes(src).contains("V29"), "got: ${diags(src)}")
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
            act Customer {
                name: text
                vip: boolean initially false
            }

            shape Odd = Customer where nonexistent == "x"
        """.trimIndent()
        assertTrue(codes(src).contains("F1"), "got: ${diags(src)}")
    }

    @Test
    fun `F1 - this inside a collection filter names the outermost subject, not the element`() {
        // Reference §10: `this` is the outermost subject; a bare name is the innermost
        // element. The runtime always read it that way; the validator once resolved
        // `this` against the filter's element too (found by examples/peptides).
        val src = """
            act Protocol {
                durationHours: integer
            }

            act Assay {
                protocol: one Protocol
            }

            act Reading {
                assay: one Assay
                hoursElapsed: integer
            }

            shape CompletedAssay = Assay where exists (readings where hoursElapsed >= this.protocol.durationHours)
        """.trimIndent()
        assertEquals(emptyList(), diags(src).filter { it.code == "F1" })
    }

    @Test
    fun `F1 - an as-alias used as a collection source resolves to its element`() {
        val src = """
            act Trial {
                name: text
            }

            act Plot {
                trial: one Trial
            }

            act Scouting {
                plot: one Plot
                scoutedOn: Date
            }

            shape BehindOnScouting = Trial where
                exists (plots as plot where not exists (plot.scoutings where scoutedOn > today - 7 days))
        """.trimIndent()
        assertEquals(emptyList(), diags(src).filter { it.code == "F1" })
    }

    @Test
    fun `F3 - assigning a derived property fails`() {
        val src = """
            act Account {
                balance: decimal = 0 + 0
            }

            act Poke {
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
            act Order {
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
            act Customer {
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
            act Customer {
                email: text
            }

            act CorrectEmail {
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
            act Customer {
                email: text
                locked: boolean initially false
            }

            act CorrectEmail {
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
            act Deposit {
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
            act Order {
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
            act Invoice {
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
    fun `V3 - a transient act partitioned on a time-dependent state is read at the request, not by aging`() {
        val src = """
            act Invoice {
                due: Date
            }

            shape Overdue = Invoice where due < today

            transient act PayInvoice {
                invoice: one Invoice
                amount: decimal
            }

            shape Payment {
                invoice: one Invoice
                amount: decimal
            }

            shape LatePayment {
                invoice: one Invoice
                amount: decimal
            }

            shape OnTimePayment = PayInvoice where invoice is not Overdue
            shape OverduePayment = PayInvoice where invoice is Overdue

            rule RecordPayment when OnTimePayment {
                Payment from { invoice: invoice, amount: amount }
            }

            rule RecordLatePayment when OverduePayment {
                LatePayment from { invoice: invoice, amount: amount }
            }
        """.trimIndent()
        assertTrue(!codes(src).contains("V3"), "got: ${diags(src)}")
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
            act Invoice {
                due: Date
            }

            act Issuance {
                invoice: one Invoice
            }

            shape IssuedInvoice = Invoice where exists Issuance for this {
                frozen due
            }

            act ChangeDue {
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
            act Account {
                balance: decimal initially 0
            }

            act Deposit {
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
    fun `V10 - a guarantee fails closed in v0`() {
        val src = """
            act Account {
                balance: decimal initially 0
                suspended: boolean initially false
            }

            act Suspend {
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
            act Ping {
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
            act Receipt {
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
            act Customer {
                name: text
            }

            act Referral {
                referrer: one Customer
                referee: one Customer
            }

            shape Referrer = Customer where exists Referral for this
        """.trimIndent()
        assertTrue(codes(src).contains("V13"), "got: ${diags(src)}")
    }

    // ── V12 (refinement slice): (Refinement for expr) singularity proofs ─────

    /** Reference §20's episodes pattern — the flagship whole-spec singularity proof. */
    private val episodes = """
        act Account {
            balance: decimal
        }

        act BalanceReport {
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
    fun `V12 - declaring the base an act defeats the proof`() {
        val src = episodes.replace("shape DelinquencyFlag {", "act DelinquencyFlag {")
        assertTrue(codes(src).contains("V12"), "got: ${diags(src)}")
    }

    @Test
    fun `V12 - an unguarded producer defeats the proof`() {
        val src = episodes + """

            act ManualFlag {
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
            act Account {
                balance: decimal
            }

            act BalanceReport {
                account: one Account
                reported: decimal
            }

            shape DelinquencyFlag {
                account: one Account
                resolved: boolean initially false
            }

            act FlagDispute {
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

            rule CloseFlag when (OpenDelinquencyFlag where account is not Delinquent) {
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
        act Invoice {
            due: Date
        }

        act Issuance {
            invoice: one Invoice
        }

        shape IssuedInvoice = Invoice where exists Issuance for this

        act ChangeDueDate {
            invoice: one Invoice
            newDue: Date
        }

        shape ApplicableDueChange = ChangeDueDate where invoice is not IssuedInvoice
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
                "shape ApplicableDueChange = ChangeDueDate where invoice is not IssuedInvoice",
                """
                shape DueChangeApplication {
                    change: one ChangeDueDate
                }

                shape UnhandledDueChange = ChangeDueDate where
                    not exists DueChangeApplication for this and not exists DueChangeRefusal for this

                shape ApplicableDueChange = UnhandledDueChange where invoice is not IssuedInvoice
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
            act Account {
                balance: decimal
            }

            act BalanceReport {
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

            rule CloseFlag when (OpenDelinquencyFlag where account is not Delinquent) {
                this.resolved = true
            }

            shape Reportable = Account where (OpenDelinquencyFlag for this).resolved == false
        """.trimIndent()
        val v12s = diags(src).filter { it.code == "V12" }
        assertEquals(emptyList(), v12s, "got: ${diags(src)}")
    }

    // ── V14: well-foundedness (stratify, then certify) ───────────────────────

    /** Reference §19's predecessor recurrence — the streak as derived history. */
    private val recurrence = """
        shape Account {
            note: text
        }

        act Payment {
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

            act Payment {
                account: one Account
                onTime: boolean
                position: integer
                previous: one Payment? = latest(Payment where account == this.account and position < this.position by position)
                streakAfter: integer =
                    if not onTime then 0
                    else if previous is some then previous.streakAfter + 1
                    else 1
            }

            act Reorder {
                payment: one Payment
                newPosition: integer
            }

            rule Apply when Reorder {
                payment.position = newPosition
            }
        """.trimIndent()
        assertTrue(codes(src).contains("V14"), "got: ${diags(src)}")
    }

    /** Reference §7's root/parent example — acyclicity supplied by a spent `never`. */
    private val rootParent = """
        act Node {
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
    fun `V14 - a guarantee cannot be spent`() {
        val src = rootParent + """

            act Reparent {
                node: one Node
                newParent: one Node?
            }

            rule Apply when Reparent {
                node.parent = newParent
            }

            never (Node where parent == this)
        """.trimIndent()
        // the never reads what 'Apply' writes: a guarantee, so V10 fails
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
            act Item {
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
        act Account {
            balance: decimal
            creditLimit: decimal
        }

        act AccountReview {
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
        act Customer {
            tier: text
            referrer: one Customer?
        }

        act QualifiedPurchase {
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
