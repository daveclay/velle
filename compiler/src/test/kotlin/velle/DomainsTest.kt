package velle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Calibration of the serialization-domain derivation (OQ40) against the
 * question's worked examples: the derived domains must reproduce the answers
 * the document derives by hand — deposit → account, transfer → {source,
 * target}, uniqueness → the email value, the branch cap collapsing to the
 * branch, and the institution-wide cap honestly widening with the advisory
 * naming the read.
 */
class DomainsTest {

    private fun analysis(src: String): DomainAnalysis {
        val model = Model(Parser.parse(src))
        check(model.diagnostics.isEmpty()) { model.diagnostics.toString() }
        return DomainAnalysis(model)
    }

    private fun keys(d: SerializationDomain, noun: String) = d.renderKeys(noun).sorted()

    // ── the deposit world (OQ40, solution 1 exercised) ───────────────────────

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
    fun `deposit queues per account - the act row itself is conflict-free`() {
        val a = analysis(deposits)
        val d = a.actDomains.getValue("Deposit")
        assertEquals(listOf("deposit.account"), keys(d, "deposit"), "got: ${d.paths}")
        assertFalse(d.wide, "unexpected widenings: ${d.widenings}")
    }

    @Test
    fun `the tick firing keys the swept deposit itself plus its account`() {
        // the commit envelope and the Hourly sweep of the same deposit must
        // conflict — the double-application U3 names; the sweep's subject row
        // is persisted, so it joins the domain
        val a = analysis(deposits)
        val d = a.scheduledRuleDomains.getValue("ApplyDeposit")
        assertEquals(listOf("this", "this.account"), keys(d, "this"))
        assertFalse(d.wide)
    }

    @Test
    fun `an act touching nothing beyond itself contends with nothing`() {
        val a = analysis(deposits)
        val d = a.actDomains.getValue("Account")
        assertEquals(emptyList(), keys(d, "account"))
        assertFalse(d.wide)
    }

    // ── the transfer: a domain is a key set, not one key ─────────────────────

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
    fun `transfer queues per source and target`() {
        val a = analysis(transfers)
        val d = a.actDomains.getValue("Transfer")
        assertEquals(listOf("transfer.source", "transfer.target"), keys(d, "transfer"))
        assertFalse(d.wide, "unexpected widenings: ${d.widenings}")
    }

    // ── uniqueness: the domain keys on a committed value ─────────────────────

    private val uniqueness = """
        expose shape Customer {
            email: text
            name: text
        }

        never (Customer where exists (Customer as other where other.email == this.email and not (other == this)))
    """.trimIndent()

    @Test
    fun `uniqueness keys on the committed value - no row exists to lock`() {
        val a = analysis(uniqueness)
        val d = a.actDomains.getValue("Customer")
        assertEquals(listOf("email value"), keys(d, "customer"))
        assertFalse(d.wide, "the value correlation must rescue the scan: ${d.widenings}")
    }

    // ── the lending cap: correlated scan collapses to the branch ─────────────

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
    fun `the correlated cap collapses to the branch key - never the Loan table`() {
        val a = analysis(branchCap)
        val approve = a.actDomains.getValue("ApproveLoan")
        assertEquals(listOf("approveLoan.loan", "approveLoan.loan.branch"), keys(approve, "approveLoan"))
        assertFalse(approve.wide, "the reverse-path walk must key the invariant: ${approve.widenings}")

        val loan = a.actDomains.getValue("Loan")
        assertEquals(listOf("loan.branch"), keys(loan, "loan"))
        assertFalse(loan.wide)
    }

    // ── the institution-wide cap: honest widening, named read ────────────────

    private val institutionCap = """
        shape Institution {
            lendingCap: decimal
        }
        expose Institution

        expose shape Loan {
            amount: decimal
            approved: boolean initially false
        }

        shape ApprovedLoan = Loan where approved == true

        never (Institution where sum(ApprovedLoan, amount) > lendingCap)
    """.trimIndent()

    @Test
    fun `an uncorrelated read widens to the whole shape and names itself`() {
        val a = analysis(institutionCap)
        val d = a.actDomains.getValue("Loan")
        assertTrue(d.wide)
        val w = d.widenings.first { it.shape == "Loan" }
        assertEquals("never #1", w.declaration)
        assertTrue("reads every Loan" in w.cause, w.cause)
        assertFalse(w.tolerated)
    }

    @Test
    fun `A5 warns on the unexamined width and names the policy options`() {
        val advisories = Validator.advisories(institutionCap).filter { it.code == "A5" }
        assertTrue(advisories.isNotEmpty(), "expected an A5 advisory")
        val msg = advisories.first().message
        assertTrue("every commit touching Loan joins a single queue" in msg, msg)
        assertTrue("tolerates contention" in msg, msg)
        // it is an advisory, never a required diagnostic (severity ruling 2026-08-18)
        assertTrue(Validator.validate(institutionCap).none { it.code == "A5" })
    }

    @Test
    fun `tolerates contention on the never silences A5 and marks the width deliberate`() {
        val tolerated = institutionCap.replace(
            "never (Institution where sum(ApprovedLoan, amount) > lendingCap)",
            "never (Institution where sum(ApprovedLoan, amount) > lendingCap) tolerates contention",
        )
        assertTrue(Validator.advisories(tolerated).none { it.code == "A5" },
            "got: ${Validator.advisories(tolerated)}")
        val d = analysis(tolerated).actDomains.getValue("Loan")
        assertTrue(d.wide && d.exposed.isEmpty(), "the width stays visible but is tolerated")
    }

    @Test
    fun `a dead tolerance is flagged - no width to tolerate`() {
        val dead = branchCap.replace(
            "never (Branch where sum(loans where ApprovedLoan, amount) > lendingCap)",
            "never (Branch where sum(loans where ApprovedLoan, amount) > lendingCap) tolerates contention",
        )
        val advisories = Validator.advisories(dead).filter { it.code == "A5" }
        assertTrue(advisories.any { "remove the tolerance" in it.message }, "got: $advisories")
    }

    // ── the cadence discharge: on a schedule, the width leaves the commits ───

    private fun complianceSpec(header: String) = """
        shape Institution {
            alertThreshold: decimal
        }
        expose Institution

        expose shape Loan {
            amount: decimal
            approved: boolean initially false
        }

        shape ApprovedLoan = Loan where approved == true

        shape ComplianceAlert {
            institution: one Institution
            raisedOn: DateTime
        }

        shape BreachedInstitution = Institution where sum(ApprovedLoan, amount) > alertThreshold

        rule NotifyCompliance when BreachedInstitution $header {
            ComplianceAlert from { institution: this, raisedOn: now }
        }
    """.trimIndent()

    @Test
    fun `a commit-triggered global condition widens every loan commit`() {
        val a = analysis(complianceSpec(""))
        assertTrue(a.actDomains.getValue("Loan").wide)
        val advisories = Validator.advisories(complianceSpec("")).filter { it.code == "A5" }
        assertTrue(advisories.any { "rule NotifyCompliance" in it.message && "schedule" in it.message },
            "a rule's diagnostic lists the cadence discharge: $advisories")
    }

    @Test
    fun `on a schedule the read leaves every commit's footprint`() {
        val a = analysis(complianceSpec("on Nightly"))
        assertFalse(a.actDomains.getValue("Loan").wide,
            "the cadence discharge: ${a.actDomains.getValue("Loan").widenings}")
        // the firing's own width is priced at the cadence, not warned
        assertTrue(a.scheduledRuleDomains.getValue("NotifyCompliance").wide)
        assertTrue(Validator.advisories(complianceSpec("on Nightly")).none { it.code == "A5" })
    }

    @Test
    fun `tolerates contention on the commit-triggered rule accepts the width`() {
        val src = complianceSpec("tolerates contention")
        assertTrue(Validator.advisories(src).none { it.code == "A5" },
            "got: ${Validator.advisories(src)}")
        val d = analysis(src).actDomains.getValue("Loan")
        assertTrue(d.wide && d.exposed.isEmpty())
    }

    // ── the phantom writer with no envelope work of its own ──────────────────

    @Test
    fun `an act that only flips a membership still keys the row readers correlate on`() {
        // creating an Issuance is the writer's half of `exists Issuance for
        // this` — its commit must share the invoice key with every envelope
        // whose guard reads that membership, even though no rule fires inside
        // the issuance envelope at all
        val src = """
            shape Invoice {
                due: Date
            }
            expose Invoice

            expose shape Issuance {
                invoice: one Invoice
            }

            shape IssuedInvoice = Invoice where exists Issuance for this

            expose transient shape ChangeDueDate {
                invoice: one Invoice
                newDue: Date
            }

            shape ApplicableDueChange = ChangeDueDate where not invoice is IssuedInvoice

            rule ApplyDueChange when ApplicableDueChange {
                invoice.due = newDue
            }
        """.trimIndent()
        val a = analysis(src)
        assertEquals(listOf("issuance.invoice"), keys(a.actDomains.getValue("Issuance"), "issuance"))
        val change = a.actDomains.getValue("ChangeDueDate")
        assertTrue(QueueKey.Path(listOf("invoice")) in change.paths, "got: ${change.paths}")
        assertFalse(change.wide, "got: ${change.widenings}")
    }

    // ── body-side correlation: a reader in a rule body still keys the writer ─

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
    fun `a correlated read in a rule body keys the writer - the transfer keys its source`() {
        // Account infers no `transfers` collection (two Transfer fields target
        // it), and no watcher *condition* consults Transfer — the only
        // correlated reader is ReportAudit's body. The transfer commit must
        // still key the source row, or the audit's sum and a concurrent
        // transfer to the same account race (OQ42 item 2).
        val a = analysis(audits)
        val d = a.actDomains.getValue("Transfer")
        assertEquals(listOf("transfer.source"), keys(d, "transfer"), "got: ${d.paths}")
        assertFalse(d.wide, "unexpected widenings: ${d.widenings}")
    }

    @Test
    fun `the audit keys the account it snapshots`() {
        val a = analysis(audits)
        val d = a.actDomains.getValue("AuditRequest")
        assertEquals(listOf("auditRequest.account"), keys(d, "auditRequest"))
        assertFalse(d.wide)
    }

    // ── the contention map renders both audiences' view ──────────────────────

    @Test
    fun `the contention map states keys in business words and marks width`() {
        val narrow = Model(Parser.parse(deposits))
        val map = ContentionMapGen.render(narrow)
        assertTrue("commitDeposit" in map && "`deposit.account`" in map, map)
        assertTrue("ApplyDeposit (each Hourly firing)" in map && "`this.account`" in map, map)

        val wideMap = ContentionMapGen.render(Model(Parser.parse(institutionCap)))
        assertTrue("⚠ commitLoan" in wideMap, wideMap)
        assertTrue("one queue over `Loan`" in wideMap || "one queue over `Institution`, `Loan`" in wideMap
            || "system-wide" in wideMap, wideMap)
    }

    @Test
    fun `the generated commit function carries the queue-key contract`() {
        val code = Codegen.generate(deposits, "Bank")
        assertTrue("Queue key: [deposit.account]." in code, code.lines().filter { "Queue" in it }.toString())
        assertTrue(code.indexOf("Queue key: [deposit.account].") < code.indexOf("fun commitDeposit("))
    }
}
