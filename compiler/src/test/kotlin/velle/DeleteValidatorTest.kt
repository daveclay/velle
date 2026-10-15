package velle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The delete checks (checks.md V23–V28; Reference §12 "Deletion"), one fixture
 * per case: each error case is a check's refusal, each clean case its
 * documented discharge.
 */
class DeleteValidatorTest {

    private fun codes(source: String): List<String> = Validator.validate(source).map { it.code }
    private fun advisories(source: String): List<Diagnostic> = Validator.advisories(source)

    private fun assertHas(code: String, source: String) {
        val diags = Validator.validate(source)
        assertTrue(diags.any { it.code == code }, "expected a $code, got: $diags")
    }

    private fun assertLacks(code: String, source: String) {
        val diags = Validator.validate(source)
        assertTrue(diags.none { it.code == code }, "expected no $code, got: $diags")
    }

    // ── the statement (V23) ──────────────────────────────────────────────────

    @Test
    fun `deleting a transient act is refused - there is nothing to delete`() {
        assertHas("V23", """
            transient act Ping { note: text }
            transient act Purge { ping: one Ping }
            rule ApplyPurge when Purge { delete ping }
        """.trimIndent())
    }

    @Test
    fun `a body that writes a field of the instance it deletes is refused`() {
        assertHas("V23", """
            act Task { name: text, done: boolean initially false }
            transient act Drop { task: one Task }
            rule ApplyDrop when Drop {
                task.done = true
                delete task
            }
        """.trimIndent())
    }

    @Test
    fun `a fan-out delete through a collection is refused`() {
        assertHas("V23", """
            act Order { code: text }
            shape LineItem { order: one Order? initially required, sku: text }
            transient act Wipe { order: one Order }
            rule ApplyWipe when Wipe { delete order.lineItems }
        """.trimIndent())
    }

    @Test
    fun `deleting the same target twice in one body is refused`() {
        assertHas("V23", """
            act Task { name: text }
            transient act Drop { task: one Task }
            rule ApplyDrop when Drop {
                delete task
                delete task
            }
        """.trimIndent())
    }

    // ── referential completeness (V24) ────────────────────────

    @Test
    fun `C5 - a plain required reference strands and errors`() {
        assertHas("V24", """
            act Invoice { code: text }
            shape LineItem { invoice: one Invoice, sku: text }
            transient act Void { invoice: one Invoice }
            rule ApplyVoid when Void { delete invoice }
        """.trimIndent())
    }

    @Test
    fun `C3 - an absorbing reference discharges completeness`() {
        assertLacks("V24", """
            act Invoice { code: text }
            shape LineItem { invoice: one Invoice? initially required, sku: text }
            transient act Void { invoice: one Invoice }
            rule ApplyVoid when Void { delete invoice }
        """.trimIndent())
    }

    @Test
    fun `C4 - a same-commit cascade discharges completeness`() {
        assertLacks("V24", """
            act Invoice { code: text }
            shape Memo { invoice: one Invoice, note: text }
            transient act Void { invoice: one Invoice, memo: one Memo }
            rule ApplyVoid when Void {
                delete memo
                delete invoice
            }
        """.trimIndent())
    }

    // ── existence-dependency (V25) ────────────────────────────

    /** The canonical fixture: deleting the witness re-arms the guard and the
     *  backstop double-applies. */
    @Test
    fun `C6 - deleting a guard witness errors`() {
        assertHas("V25", """
            act Account { owner: text, balance: decimal initially 0 }
            act Deposit { account: one Account, amount: decimal }
            shape DepositApplication { deposit: one Deposit? initially required, note: text }
            shape UnappliedDeposit = Deposit where not exists DepositApplication for this
            rule ApplyDeposit when UnappliedDeposit after commit, Hourly {
                account.balance = account.balance + amount
                DepositApplication from { deposit: this, note: "applied" }
            }
            transient act PurgeApplication { application: one DepositApplication }
            rule ApplyPurge when PurgeApplication { delete application }
        """.trimIndent())
    }

    /** C7's shape: the prune is legal once the guard's memory moves off the
     *  evidence onto a field witness — no guard reads Reminder's existence. */
    @Test
    fun `C7 restructured - the field-witness guard frees the evidence to prune`() {
        assertLacks("V25", """
            act Invoice { code: text, lastRemindedOn: Date? }
            shape Reminder { invoice: one Invoice? initially required, sentOn: Date }
            rule RemindOverdue
                when (Invoice where lastRemindedOn is none or lastRemindedOn <= today - 3 days)
                on Daily {
                Reminder from { invoice: this, sentOn: today }
                this.lastRemindedOn = today
            }
            rule PruneOldReminders when (Reminder where sentOn < today - 90 days) on Monthly {
                delete this
            }
        """.trimIndent())
    }

    /** C8: a deleter of the singular reference's shape errors bare, and clears
     *  when conditioned on the complement — the shared prover's power. */
    @Test
    fun `C8 - stranding a singular reference errors, the complement clears it`() {
        val base = """
            act Account { owner: text }
            act DelinquencyFlag { account: one Account, note: text }
            shape Resolution { flag: one DelinquencyFlag? initially required, note: text }
            shape OpenFlag = DelinquencyFlag where not exists Resolution for this
            shape ClosedFlag = DelinquencyFlag where exists Resolution for this
            shape Cleared = Account where exists DelinquencyFlag for this
            rule Escalate when Cleared {
                Note from { body: (OpenFlag for this).note }
            }
            shape Note { body: text }
        """.trimIndent()
        assertHas("V25", base + """

            transient act Purge { flag: one DelinquencyFlag }
            rule ApplyPurge when Purge { delete flag }
        """.trimIndent())
        assertLacks("V25", base + """

            transient act Purge { flag: one DelinquencyFlag }
            rule ApplyPurge when (Purge where flag is ClosedFlag) { delete flag }
        """.trimIndent())
    }

    // ── never maintenance (V26) ──────────────────────────────────

    @Test
    fun `C9 - a deleter the invariant consults errors, the base's own deleter does not`() {
        val base = """
            act Order { code: text }
            shape LineItem { order: one Order? initially required, sku: text }
            never (Order where count(lineItems) == 0)
        """.trimIndent()
        assertHas("V26", base + """

            transient act Remove { item: one LineItem }
            rule ApplyRemove when Remove { delete item }
        """.trimIndent())
        // deleting an Order only shrinks the forbidden set — but stranding its
        // items is V24's business, so the items cascade
        assertLacks("V26", """
            act Order { code: text }
            transient act Void { order: one Order }
            rule ApplyVoid when Void { delete order }
            never (Order where code == "")
        """.trimIndent())
    }

    // ── the deletion gate (V27) ─────────────────────────────────

    @Test
    fun `C10 - an unconditioned deleter trips the gate, the partition discharges it`() {
        val base = """
            act Listing { title: text, isDraft: boolean initially true }
            shape Draft = Listing where isDraft
            shape Published = Listing where not isDraft {
                undeletable
            }
            transient act Discard { listing: one Listing }
        """.trimIndent()
        assertHas("V27", base + """

            rule ApplyDiscard when Discard { delete listing }
        """.trimIndent())
        assertLacks("V27", base + """

            rule ApplyDiscard when (Discard where listing is Draft) { delete listing }
            rule RefuseDiscard when (Discard where listing is not Draft) {
                Refusal from { title: listing.title }
            }
            shape Refusal { title: text }
        """.trimIndent())
    }

    @Test
    fun `a gate no deleter could trip is dead machinery - advisory`() {
        val a = advisories("""
            act Listing { title: text, isDraft: boolean initially true }
            shape Published = Listing where not isDraft {
                undeletable
            }
            act Note { body: text }
            transient act Drop { note: one Note }
            rule ApplyDrop when Drop { delete note }
        """.trimIndent())
        assertTrue(a.any { it.code == "A2" && "undeletable" in it.message }, "expected the dead-gate A2, got: $a")
    }

    // ── stranding (V28) ─────────────────────────────────────────

    @Test
    fun `C11 - an after-commit exit rule cannot read a deletion's leaver`() {
        assertHas("V28", """
            act Task { name: text, done: boolean initially false }
            shape OpenTask = Task where not done
            transient act Drop { task: one Task }
            rule ApplyDrop when Drop { delete task }
            rule LogExit when leaving OpenTask after commit, Daily {
                ExitLog from { name: name }
            }
            shape ExitLog { name: text }
        """.trimIndent())
    }

    @Test
    fun `an on-commit exit rule is the leaver's last reader - no stranding`() {
        assertLacks("V28", """
            act Task { name: text, done: boolean initially false }
            shape OpenTask = Task where not done
            transient act Drop { task: one Task }
            rule ApplyDrop when Drop { delete task }
            rule LogExit when leaving OpenTask {
                ExitLog from { name: name }
            }
            shape ExitLog { name: text }
        """.trimIndent())
    }

    // ── ripples through the existing proofs ──────────────────────────────────

    @Test
    fun `V12 - a deleter of the anti-monotone witness breaks the at-most-one proof`() {
        assertHas("V12", """
            act Account { owner: text }
            shape Flag { account: one Account, note: text }
            shape OpenFlag = Flag where not exists Retirement for this
            shape Retirement { flag: one Flag? initially required, note: text }
            shape Flagged = Account where exists Flag for this
            rule Report when Flagged {
                Note from { body: (OpenFlag for this).note }
            }
            shape Note { body: text }
            transient act Undo { retirement: one Retirement }
            rule ApplyUndo when Undo { delete retirement }
        """.trimIndent())
    }

    @Test
    fun `V2 - deleting the subject is the structural disarm`() {
        assertLacks("V2", """
            act Draft { title: text }
            shape Keep { draft: one Draft? initially required }
            transient act MarkKeep { draft: one Draft }
            rule ApplyKeep when MarkKeep { Keep from { draft: draft } }
            rule Purge when (Draft where not exists Keep for this) on Weekly {
                delete this
            }
        """.trimIndent())
    }

    // ── `? initially required` (OQ37-R10) ────────────────────────────────────

    @Test
    fun `initially required demands an optional type`() {
        assertHas("F3", """
            act Listing { title: text initially required }
        """.trimIndent())
    }

    @Test
    fun `dead optionality is advisory - nothing can make the field absent`() {
        val a = advisories("""
            act Seller { name: text }
            act Listing { title: text, seller: one Seller? initially required }
        """.trimIndent())
        assertTrue(a.any { it.code == "A2" && "initially required" in it.message }, "expected the dead-optionality A2, got: $a")
    }

    @Test
    fun `a deleter of the target keeps the optionality alive`() {
        val a = advisories("""
            act Seller { name: text }
            act Listing { title: text, seller: one Seller? initially required }
            transient act Close { seller: one Seller }
            rule ApplyClose when Close { delete seller }
        """.trimIndent())
        assertTrue(a.none { it.code == "A2" && "initially required" in it.message }, "expected no dead-optionality A2, got: $a")
    }
}
