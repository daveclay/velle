package velle.generated.pharmacy

import java.math.BigDecimal
import velle.Keyed
import velle.generated.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import velle.Commit
import velle.generated.PharmacySystem

/**
 * The pharmacy driven end-to-end through the generated typed surface — the
 * business-flow companion to the generated per-rule specs, and the place the
 * domain's own numbers are checked: a prescriber's order landing whole or not
 * at all, the price ledger read through today, stock on hand as a derivation
 * over receipts, fills, returns, and counts, the clinical checks (allergy by
 * ingredient, interaction in either direction, the override), the two kinds
 * of answer a fill request gets, the pharmacist's verification and its
 * refusals, the counter (notice, counselling, payment, the ten-day return to
 * stock read off a capture), refills by request and by enrolment, expiry by
 * age, insurance claims, recalls, reorders, and the administrator's sweeps.
 */
open class PharmacyFlowTest {

    /** The system under test; the storage-backed variants override this. */
    protected open fun newSystem(): PharmacySystem = PharmacySystem()
    protected val sys: PharmacySystem by lazy { newSystem() }
    private val s: Scenario by lazy { Scenario(sys) }

    private fun accept(r: Commit<*>) = assertIs<Commit.Accepted<*>>(r, "refused: $r")
    private fun refuse(r: Commit<*>) = assertTrue(r !is Commit.Accepted<*>, "expected a refusal, got: $r")
    private fun Keyed.isA(refinement: String) = sys.system.isMember(sys.system.idOf(this), refinement)
    private fun dec(s: String) = BigDecimal(s)
    private fun BigDecimal.plain() = stripTrailingZeros().toPlainString()

    // ── the prescriber's order ───────────────────────────────────────────────

    @Test
    fun `order - an order lands with all its prescriptions or not at all, and each prescription knows its own expiry`() {
        val tech = s.technician()
        val dr = s.prescriber()
        val patient = s.patient()
        val amoxicillin = s.drug("amoxicillin")
        val oxycodone = s.controlledDrug("oxycodone")

        // a schedule II line with refills refuses the whole order: nothing lands, not the clean line either
        refuse(sys.commit(NewPrescriberOrder(dr, patient, s.today, tech, listOf(s.line(amoxicillin), s.line(oxycodone, refills = 1)))))
        assertEquals(0, sys.prescriberOrders().size)
        assertEquals(0, sys.prescriptions().size)

        // a post-dated order is refused too
        refuse(sys.commit(NewPrescriberOrder(dr, patient, s.today.plusDays(1), tech, listOf(s.line(amoxicillin)))))

        val order = s.order(patient, dr, tech, listOf(s.line(amoxicillin), s.line(oxycodone, refills = 0)), writtenOn = s.today.minusDays(10))
        assertEquals(2, sys.read(order).prescriptions.size)
        val plain = sys.read(order).prescriptions.first { sys.read(it).drug == amoxicillin }
        val controlled = sys.read(order).prescriptions.first { sys.read(it).drug == oxycodone }
        assertEquals(s.today.minusDays(10).plusDays(365), sys.read(plain).expiresOn, "a year for an ordinary prescription")
        assertEquals(s.today.minusDays(10).plusDays(180), sys.read(controlled).expiresOn, "six months for a controlled one")
        assertEquals(3, sys.read(plain).refillsRemaining, "the first fill plus two refills")
        assertEquals(listOf(plain, controlled).map { it.key }.toSet(), sys.read(patient).prescriptions.map { it.key }.toSet(), "the two-hop view on the patient")
        assertTrue(plain.isA("ActivePrescription"))
        assertEquals(order, sys.read(plain).order, "the back-reference the closure populated")
    }

    @Test
    fun `order - corrections and voids are refused once a prescription has been filled`() {
        val c = s.counter()
        accept(sys.commit(NewCorrectEntry(c.prescription, c.technician, "two daily")))
        assertEquals("two daily", sys.read(c.prescription).directions)

        // only the prescriber who wrote it may discontinue it
        refuse(sys.commit(NewDiscontinuation(c.prescription, s.prescriber("Dr Other"), "not mine")))

        s.filled(c)
        assertTrue(c.prescription.isA("FilledPrescription"))
        accept(sys.commit(NewCorrectEntry(c.prescription, c.technician, "three daily")))
        assertEquals("two daily", sys.read(c.prescription).directions, "frozen by FilledPrescription")
        assertEquals("the prescription has been filled", sys.read(sys.correctionRefusals().single()).reason)

        accept(sys.commit(NewVoidEntry(c.prescription, c.pharmacist, "wrong patient")))
        assertEquals(1, sys.voidRefusals().size)
        assertFalse(c.prescription.isA("Voided"))

        // an unfilled one is voided, and leaves the patient's active list
        val other = s.prescription(c.patient, c.drug, c.prescriber, c.technician)
        refuse(sys.commit(NewVoidEntry(other, c.technician, "wrong patient")))
        accept(sys.commit(NewVoidEntry(other, c.pharmacist, "wrong patient")))
        assertTrue(other.isA("Voided"))
        assertFalse(other.isA("ActivePrescription"))
        assertEquals(1, sys.entryVoids().size)
    }

    // ── prices ───────────────────────────────────────────────────────────────

    @Test
    fun `prices - the price in force is the dated entry, a future change waits and can be withdrawn, one in force cannot`() {
        val d = s.drug(price = "1.50")
        val admin = s.administrator()
        assertEquals("1.5", sys.read(d).unitPrice.plain())

        accept(sys.commit(NewPriceChange(d, s.today.plusDays(10), dec("2.00"))))
        val future = sys.priceChanges().last()
        assertEquals("1.5", sys.read(d).unitPrice.plain(), "the future change waits its turn")
        assertFalse(future.isA("EffectivePrice"))

        // two changes on one date would leave the ledger unordered
        refuse(sys.commit(NewPriceChange(d, s.today.plusDays(10), dec("2.25"))))

        sys.advanceDays(10)
        assertEquals("2", sys.read(d).unitPrice.plain(), "its day came")
        assertTrue(future.isA("EffectivePrice"))

        // now it is in force: withdrawal is refused on record
        accept(sys.commit(NewWithdrawPriceChange(future, admin, "second thoughts")))
        assertEquals("the price is already in force", sys.read(sys.priceWithdrawalRefusals().single()).reason)
        assertEquals(2, sys.read(d).priceChanges.size)

        // a fresh future change can be withdrawn — by an administrator only
        accept(sys.commit(NewPriceChange(d, s.today.plusDays(5), dec("9.99"))))
        val mistaken = sys.priceChanges().last()
        refuse(sys.commit(NewWithdrawPriceChange(mistaken, s.technician(), "typo")))
        accept(sys.commit(NewWithdrawPriceChange(mistaken, admin, "typo")))
        assertEquals(2, sys.read(d).priceChanges.size, "removed")
        val withdrawal = sys.priceWithdrawals().single()
        assertEquals("9.99", sys.read(withdrawal).unitPrice.plain(), "what was removed, copied out")
        assertEquals(d, sys.read(withdrawal).drug)
        assertEquals("2", sys.read(d).unitPrice.plain())
    }

    // ── clinical checks ──────────────────────────────────────────────────────

    @Test
    fun `clinical - an allergy is matched through the drug's ingredients and refuses the fill on record until overridden`() {
        val penicillin = s.ingredient("penicillin")
        val patient = s.patient(allergies = listOf(penicillin))
        val amoxicillin = s.drug("amoxicillin", ingredients = listOf(penicillin, s.ingredient("filler")))
        val c = s.counter(patient = patient, drug = amoxicillin)
        assertTrue(c.prescription.isA("AllergyConflict"))
        assertFalse(c.prescription.isA("Cleared"))
        assertEquals("allergy", sys.read(sys.pharmacistAlerts().single()).kind, "the pharmacist is alerted once")

        val key = s.requestFill(c.prescription, c.lot, c.technician)
        assertEquals(0, sys.fills().size)
        val refusal = sys.fillRefusals().single()
        assertEquals(key, sys.read(refusal).requestKey, "the counter finds its answer by the key")
        assertEquals("unresolved allergy or interaction conflict", sys.read(refusal).reason)

        refuse(sys.commit(NewConflictOverride(c.prescription, c.technician, "patient tolerates it")))
        accept(sys.commit(NewConflictOverride(c.prescription, c.pharmacist, "patient tolerates it")))
        assertTrue(c.prescription.isA("Cleared"))
        s.fill(c.prescription, c.lot, c.technician)
        assertEquals(1, sys.fills().size)
        assertEquals(1, sys.pharmacistAlerts().size, "no second alert")
    }

    @Test
    fun `clinical - an interaction is found from either side, against the patient's current prescriptions`() {
        val warfarin = s.drug("warfarin")
        val aspirin = s.drug("aspirin")
        s.interaction(aspirin, warfarin)
        assertEquals(1, sys.read(warfarin).interactions.size, "the view reads the pair from the other side")
        refuse(sys.commit(NewInteraction(aspirin, aspirin, "major")))

        val patient = s.patient()
        val dr = s.prescriber()
        val tech = s.technician()
        val onWarfarin = s.prescription(patient, warfarin, dr, tech)
        assertFalse(onWarfarin.isA("InteractionConflict"), "nothing to interact with yet")

        val onAspirin = s.prescription(patient, aspirin, dr, tech)
        assertTrue(onAspirin.isA("InteractionConflict"))
        assertTrue(onWarfarin.isA("InteractionConflict"), "and the other way round")
        assertEquals(2, sys.pharmacistAlerts().size)
        assertTrue(sys.pharmacistAlerts().all { sys.read(it).kind == "interaction" })

        // discontinuing the warfarin clears both
        accept(sys.commit(NewDiscontinuation(onWarfarin, dr, "stopped")))
        assertFalse(onAspirin.isA("InteractionConflict"))
        assertEquals(2, sys.pharmacistAlerts().size)

        // two controlled prescriptions from two prescribers flag the patient
        val oxy = s.controlledDrug()
        s.prescription(patient, oxy, dr, tech, refills = 0)
        assertFalse(patient.isA("MultiplePrescribers"))
        s.prescription(patient, oxy, s.prescriber("Dr Other"), tech, refills = 0)
        assertTrue(patient.isA("MultiplePrescribers"))
    }

    // ── filling ──────────────────────────────────────────────────────────────

    @Test
    fun `fill - data refusals at the boundary keep nothing, business refusals land on record with the reason`() {
        val c = s.counter()
        val otherDrug = s.drug("ibuprofen")
        val otherLot = s.lot(otherDrug)
        val expired = s.lot(c.drug, lotNumber = "OLD", expiresOn = s.today.minusDays(1))

        refuse(sys.commit(NewFillPrescription("K1", c.prescription, otherLot, 30, 1, c.technician)))   // the wrong drug's lot
        refuse(sys.commit(NewFillPrescription("K2", c.prescription, expired, 30, 1, c.technician)))    // an unusable lot
        refuse(sys.commit(NewFillPrescription("K3", c.prescription, c.lot, 101, 1, c.technician)))     // more than the lot holds
        refuse(sys.commit(NewFillPrescription("K4", c.prescription, c.lot, 31, 1, c.technician)))      // more than prescribed
        refuse(sys.commit(NewFillPrescription("K5", c.prescription, c.lot, 30, 2, c.technician)))      // out of sequence
        refuse(sys.commit(NewFillPrescription("K6", c.prescription, c.lot, 30, 1, s.administrator()))) // not a dispenser
        assertEquals(0, sys.fills().size)
        assertEquals(0, sys.fillRefusals().size, "a boundary refusal keeps nothing")

        // more than the lot holds is answered on record, over the shelf as it stands
        val small = s.lot(c.drug, lotNumber = "SMALL", received = 20)
        val k0 = s.requestFill(c.prescription, small, c.technician)
        assertEquals("the lot does not hold that much", sys.read(sys.fillRefusals().single { sys.read(it).requestKey == k0 }).reason)
        assertEquals(0, sys.fills().size)

        // a partial fill is fine; the second fill is the next number
        s.fill(c.prescription, c.lot, c.technician, quantity = 10)
        assertEquals(1, sys.read(c.prescription).fillsRecorded)
        assertEquals(90, sys.read(c.lot).onHand)
        assertEquals("15", sys.read(sys.fills().single()).total.plain(), "10 at 1.50")

        // use up the refills: three fills in all, then the fourth is answered on record
        s.fill(c.prescription, c.lot, c.technician)
        s.fill(c.prescription, c.lot, c.technician)
        assertTrue(c.prescription.isA("Exhausted"))
        val key = s.requestFill(c.prescription, c.lot, c.technician)
        assertEquals(3, sys.fills().size)
        assertEquals("no refills remaining", sys.read(sys.fillRefusals().single { sys.read(it).requestKey == key }).reason)
        assertEquals(30, sys.read(c.lot).onHand, "100 less 10, 30, and 30")

        // a discontinued one says so
        val d = s.prescription(c.patient, c.drug, c.prescriber, c.technician)
        accept(sys.commit(NewDiscontinuation(d, c.prescriber, "changed therapy")))
        val k2 = s.requestFill(d, c.lot, c.technician)
        assertEquals("prescription discontinued", sys.read(sys.fillRefusals().single { sys.read(it).requestKey == k2 }).reason)

        // an expired one says so
        val e = s.prescription(c.patient, c.drug, c.prescriber, c.technician, writtenOn = s.today.minusDays(366))
        assertTrue(e.isA("Expired"))
        val k3 = s.requestFill(e, c.lot, c.technician)
        assertEquals("prescription expired", sys.read(sys.fillRefusals().single { sys.read(it).requestKey == k3 }).reason)
    }

    @Test
    fun `fill - a controlled fill needs a clear monitoring check within a day and goes in the log with the balance after`() {
        val c = s.counter(drug = s.controlledDrug(), refills = 0)
        refuse(sys.commit(NewFillPrescription("K1", c.prescription, c.lot, 30, 1, c.technician)))
        refuse(sys.commit(NewMonitoringCheck(c.prescription, c.technician, s.now, true)))
        accept(sys.commit(NewMonitoringCheck(c.prescription, c.pharmacist, s.now, false)))
        refuse(sys.commit(NewFillPrescription("K2", c.prescription, c.lot, 30, 1, c.technician)), ) // not cleared

        s.monitoringCheck(c.prescription, c.pharmacist)
        sys.advanceSeconds(25 * 3600)
        refuse(sys.commit(NewFillPrescription("K3", c.prescription, c.lot, 30, 1, c.technician)))   // a day old
        s.monitoringCheck(c.prescription, c.pharmacist)
        val fill = s.fill(c.prescription, c.lot, c.technician)

        val entry = sys.controlledLogEntrys().single()
        assertEquals(fill, sys.read(entry).fill)
        assertEquals(70, sys.read(entry).balanceAfter, "100 received less 30 dispensed, as it stood at the fill")
        assertEquals(70, sys.read(c.drug).onHand)
    }

    // ── verification ─────────────────────────────────────────────────────────

    @Test
    fun `verify - a licensed pharmacist verifies once, never their own controlled fill, and a rejection returns the stock`() {
        val c = s.filled(s.counter(drug = s.controlledDrug(), refills = 0))
        val lapsed = s.pharmacist("Lapsed", licensedUntil = s.today.minusDays(1))
        refuse(sys.commit(NewVerifyFill(c.fill, lapsed, "approved", "")))
        refuse(sys.commit(NewVerifyFill(c.fill, c.technician, "approved", "")))
        refuse(sys.commit(NewVerifyFill(c.fill, c.pharmacist, "maybe", "")))

        // the pharmacist fills the second one themself: another pharmacist must verify it
        val other = s.counter(patient = c.patient, drug = c.drug, refills = 0)
        s.monitoringCheck(other.prescription, c.pharmacist)
        val ownFill = s.fill(other.prescription, other.lot, c.pharmacist)
        refuse(sys.commit(NewVerifyFill(ownFill, c.pharmacist, "approved", "")))
        s.verify(ownFill, s.pharmacist("Colleague"))

        s.verify(c.fill, c.pharmacist, "rejected")
        refuse(sys.commit(NewVerifyFill(c.fill, c.pharmacist, "approved", "")), ) // verified once
        assertTrue(c.fill.isA("RejectedFill"))
        assertFalse(c.fill.isA("ConsumingFill"))
        assertEquals(100, sys.read(c.lot).onHand, "the rejected fill went back on the shelf")
        assertEquals(1, sys.read(c.prescription).refillsRemaining, "and did not use the one fill allowed")
        assertFalse(c.fill.isA("ReadyForPickup"))
    }

    // ── the counter ──────────────────────────────────────────────────────────

    @Test
    fun `counter - the patient is told, counselling gates the first fill, payment must cover what is due, and the exit is recorded with the day it arrived`() {
        val c = s.ready()
        val notice = sys.notices().single()
        assertEquals("ready for pickup", sys.read(notice).subject)
        assertEquals(c.patient, (sys.read(sys.read(notice).recipient) as? Patient), "the union-typed recipient")
        assertEquals("555-0100", sys.read(notice).contact, "a patient is texted")
        assertTrue(c.fill.isA("ReadyForPickup"))
        assertTrue(c.fill.isA("CounsellingDue"), "a first fill is counselled")

        refuse(sys.commit(NewPickUp(c.fill, "the patient", sys.read(c.fill).amountDue)))
        refuse(sys.commit(NewCounselling(c.fill, c.technician, "...")))
        s.counsel(c.fill, c.pharmacist)
        assertFalse(c.fill.isA("CounsellingDue"))
        assertEquals("45", sys.read(c.fill).amountDue.plain(), "no insurer: the patient pays the lot")
        refuse(sys.commit(NewPickUp(c.fill, "the patient", dec("44.99"))))

        sys.advanceDays(3)
        s.pickUp(c.fill)
        assertFalse(c.fill.isA("ReadyForPickup"))
        val exit = sys.counterExits().single()
        assertEquals(s.today.minusDays(3), sys.read(exit).readyOn, "the capture, read as it left")
        assertEquals(s.today, sys.read(exit).leftOn)
        refuse(sys.commit(NewPickUp(c.fill, "the patient", sys.read(c.fill).amountDue)), ) // not at the counter any more

        // a second fill of the same ordinary drug needs no counselling
        val second = s.fill(c.prescription, c.lot, c.technician)
        s.verify(second, c.pharmacist)
        assertFalse(second.isA("CounsellingDue"))
        s.pickUp(second)
    }

    @Test
    fun `counter - a fill uncollected for ten days goes back to stock overnight, restoring the shelf and the refill`() {
        val c = s.ready()
        assertEquals(70, sys.read(c.lot).onHand)
        assertEquals(2, sys.read(c.prescription).refillsRemaining)

        sys.advanceDays(10)
        sys.tickNightly()
        assertEquals(0, sys.returnToStocks().size, "ten days is not yet past ten days")
        assertTrue(c.fill.isA("ReadyForPickup"))

        sys.advanceDays(1)
        assertTrue(c.fill.isA("Uncollected"))
        sys.tickNightly()
        val ret = sys.returnToStocks().single()
        assertEquals(c.fill, sys.read(ret).fill)
        assertFalse(c.fill.isA("ReadyForPickup"))
        assertFalse(c.fill.isA("ConsumingFill"))
        assertEquals(100, sys.read(c.lot).onHand, "back on the shelf")
        assertEquals(3, sys.read(c.prescription).refillsRemaining, "the refill is not used up")
        val exit = sys.counterExits().single()
        assertEquals(s.today.minusDays(11), sys.read(exit).readyOn, "the capture read at the tick's exit commit")
        sys.tickNightly()
        assertEquals(1, sys.returnToStocks().size, "the guard makes the next tick harmless")
        refuse(sys.commit(NewPickUp(c.fill, "the patient", sys.read(c.fill).amountDue)))
    }

    @Test
    fun `counter - an allergy reported while a fill waits raises one alert, whether the list is replaced or extended`() {
        val c = s.ready()
        assertEquals(0, sys.counterAlerts().size)
        val ingredient = sys.read(c.drug).ingredients.single()

        accept(sys.commit(NewSetAllergies(c.patient, listOf(s.ingredient("latex"), ingredient))))
        assertEquals(2, sys.read(c.patient).allergies.size)
        assertTrue(c.prescription.isA("AllergyConflict"))
        assertEquals(c.fill, sys.read(sys.counterAlerts().single()).fill)
        assertEquals(1, sys.pharmacistAlerts().size, "the prescription-level alert too")

        accept(sys.commit(NewSetAllergies(c.patient, emptyList())))
        assertFalse(c.prescription.isA("AllergyConflict"))
        accept(sys.commit(NewAddAllergy(c.patient, ingredient)))
        assertEquals(listOf(ingredient), sys.read(c.patient).allergies)
        assertEquals(1, sys.counterAlerts().size, "once per fill")
        accept(sys.commit(NewAddAllergy(c.patient, ingredient)))
        assertEquals(1, sys.read(c.patient).allergies.size, "a set: adding a present member is a no-op")
    }

    // ── stock ────────────────────────────────────────────────────────────────

    @Test
    fun `stock - on hand is a derivation, counts confirm or adjust it, expired lots are quarantined overnight`() {
        val c = s.filled()
        assertEquals(70, sys.read(c.lot).onHand)
        accept(sys.commit(NewCountLot(c.lot, c.technician, 70)))
        assertEquals(1, sys.countConfirmations().size)
        assertEquals(0, sys.stockAdjustments().size)

        accept(sys.commit(NewCountLot(c.lot, c.technician, 68)))
        val adj = sys.stockAdjustments().single()
        assertEquals(-2, sys.read(adj).delta, "counted less the balance it replaced")
        assertEquals(68, sys.read(c.lot).onHand)
        assertEquals(68, sys.read(c.drug).onHand)
        refuse(sys.commit(NewCountLot(c.lot, s.administrator(), 68)))

        val shortDated = s.lot(c.drug, lotNumber = "SHORT", expiresOn = s.today.plusDays(2), received = 10)
        assertEquals(78, sys.read(c.drug).onHand)
        sys.advanceDays(3)
        assertTrue(shortDated.isA("ExpiredLot"))
        assertFalse(shortDated.isA("UsableLot"))
        sys.tickNightly()
        assertEquals("expired", sys.read(sys.quarantines().single()).reason)
        assertTrue(shortDated.isA("QuarantinedLot"))
        sys.tickNightly()
        assertEquals(1, sys.quarantines().size)
        refuse(sys.commit(NewFillPrescription("K", c.prescription, shortDated, 5, 2, c.technician)))
    }

    @Test
    fun `stock - a recall quarantines the lot and tells every patient who had medicine from it, once`() {
        val c1 = s.collected()
        val c2 = s.collected(s.ready(s.filled(s.counter(patient = s.patient("Second"), drug = c1.drug))))
        // both patients were served from their own lots; serve the second patient's refill from the first lot too
        val refill = s.fill(c2.prescription, c1.lot, c1.technician)
        s.verify(refill, c1.pharmacist)
        assertEquals(2, sys.read(c1.lot).fills.size)

        accept(sys.commit(NewRecall(c1.lot, "FDA-2026-007", s.today)))
        assertTrue(c1.lot.isA("RecalledLot"))
        assertEquals("recalled", sys.read(sys.quarantines().single()).reason)
        assertEquals(2, sys.recallNotices().size, "one per fill from the lot, in the recall's transaction")
        assertEquals(setOf(c1.patient.key, c2.patient.key), sys.recallNotices().map { sys.read(it).patient.key }.toSet())
        assertTrue(sys.recallNotices().all { sys.read(it).reference == "FDA-2026-007" })
        assertEquals(0, sys.read(c2.lot).recalls.size, "the other lot is untouched")

        // nothing more can be filled from it
        refuse(sys.commit(NewFillPrescription("K", c1.prescription, c1.lot, 30, 2, c1.technician)))
        s.fill(c1.prescription, c2.lot, c1.technician)
        assertEquals(2, sys.recallNotices().size)
    }

    @Test
    fun `stock - a drug below its reorder point is reordered overnight, once, until the order is received`() {
        val d = s.drug(reorderPoint = 40)
        val lot = s.lot(d, received = 25)
        assertTrue(d.isA("LowStock"))
        sys.tickNightly()
        val po = sys.purchaseOrders().single()
        assertEquals(80, sys.read(po).quantity, "twice the reorder point")
        assertEquals(s.today, sys.read(po).orderedOn)
        assertTrue(po.isA("OpenPurchaseOrder"))
        sys.tickNightly()
        assertEquals(1, sys.purchaseOrders().size, "an open order stops a second")

        // the administrator lowers the point: no longer low, but the order stays open
        accept(sys.commit(NewSetReorderPoint(d, s.administrator(), 20)))
        refuse(sys.commit(NewSetReorderPoint(d, s.technician(), 10)))
        refuse(sys.commit(NewSetReorderPoint(d, s.administrator(), -1)))
        assertFalse(d.isA("LowStock"))

        val delivered = s.lot(d, lotNumber = "L-PO", received = 80, purchaseOrder = po)
        assertTrue(po.isA("ReceivedPurchaseOrder"))
        assertEquals(po, sys.read(delivered).purchaseOrder)
        assertEquals(105, sys.read(d).onHand)
        assertEquals(2, sys.read(sys.read(lot).drug).stockLots.size)
    }

    // ── refills ──────────────────────────────────────────────────────────────

    @Test
    fun `refills - a request queues work that is nagged daily after two days and closes when the fill lands`() {
        val c = s.collected()
        accept(sys.commit(NewRequestRefill("R-1", c.prescription)))
        val task = sys.refillTasks().single()
        assertEquals("R-1", sys.read(task).requestKey)
        assertEquals(1, sys.read(task).fillsAtRequest)
        assertTrue(task.isA("OpenRefillTask"))
        assertFalse(task.isA("OverdueRefillTask"))

        sys.advanceDays(2)
        sys.tickDaily()
        assertEquals(0, sys.taskNags().size, "two days old is not yet overdue")
        sys.advanceDays(1)
        sys.tickDaily()
        sys.tickDaily()
        assertEquals(1, sys.taskNags().size, "one nag a day")
        sys.advanceDays(1)
        sys.tickDaily()
        assertEquals(2, sys.taskNags().size)

        s.fill(c.prescription, c.lot, c.technician)
        assertFalse(task.isA("OpenRefillTask"))
        sys.advanceDays(1)
        sys.tickDaily()
        assertEquals(2, sys.taskNags().size, "a closed task is not nagged")
    }

    @Test
    fun `refills - a refused request carries its reason, and want of refills asks the prescriber once`() {
        val c = s.collected(s.ready(s.filled(s.counter(refills = 0))))
        accept(sys.commit(NewRequestRefill("R-1", c.prescription)))
        accept(sys.commit(NewRequestRefill("R-2", c.prescription)))
        assertEquals(0, sys.refillTasks().size)
        assertEquals(listOf("no refills remaining", "no refills remaining"), sys.refillRefusals().map { sys.read(it).reason })
        assertEquals(1, sys.authorisationRequests().size, "asked once however many times the patient asks")
        val fax = sys.notices().single { sys.read(it).subject == "refill authorisation requested" }
        assertEquals(c.prescriber, (sys.read(sys.read(fax).recipient) as? Prescriber))
        assertEquals("555-0199", sys.read(fax).contact, "a prescriber is faxed")

        // a discontinued prescription is refused for its own reason, and the prescriber is not asked
        val d = s.prescription(c.patient, c.drug, c.prescriber, c.technician)
        accept(sys.commit(NewDiscontinuation(d, c.prescriber, "stopped")))
        accept(sys.commit(NewRequestRefill("R-3", d)))
        assertEquals("prescription discontinued", sys.read(sys.refillRefusals().last()).reason)
        assertEquals(1, sys.authorisationRequests().size)
    }

    @Test
    fun `refills - an enrolled prescription is queued twenty-five days after its last fill, once, until the patient leaves`() {
        val c = s.collected()
        accept(sys.commit(NewAutoRefillEnrolment(c.prescription)))
        assertTrue(c.prescription.isA("AutoRefill"))
        sys.advanceDays(24)
        sys.tickDaily()
        assertEquals(0, sys.refillTasks().size)
        sys.advanceDays(1)
        sys.tickDaily()
        val task = sys.refillTasks().single()
        assertNull(sys.read(task).requestKey, "nobody asked")
        sys.tickDaily()
        assertEquals(1, sys.refillTasks().size, "open work stops a second")

        s.fill(c.prescription, c.lot, c.technician)
        assertFalse(task.isA("OpenRefillTask"))
        accept(sys.commit(NewAutoRefillCancellation(c.prescription)))
        assertFalse(c.prescription.isA("AutoRefill"))
        sys.advanceDays(30)
        sys.tickDaily()
        assertEquals(1, sys.refillTasks().size, "left the programme")
    }

    @Test
    fun `expiry - a prescription that expires by age gets a notice at the daily tick, one used up or discontinued does not`() {
        val c = s.counter()
        val aging = s.prescription(c.patient, c.drug, c.prescriber, c.technician, writtenOn = s.today.minusDays(360))
        val stopped = s.prescription(c.patient, c.drug, c.prescriber, c.technician)
        assertTrue(aging.isA("ActivePrescription"))

        accept(sys.commit(NewDiscontinuation(stopped, c.prescriber, "stopped")))
        assertFalse(stopped.isA("ActivePrescription"))
        sys.tickDaily()
        assertEquals(0, sys.notices().size, "a commit-caused exit is not the tick's business")

        sys.advanceDays(6)
        assertFalse(aging.isA("ActivePrescription"), "expired by age")
        sys.tickDaily()
        val notice = sys.notices().single()
        assertEquals("prescription expired", sys.read(notice).subject)
        assertEquals(c.patient, (sys.read(sys.read(notice).recipient) as? Patient))
        sys.tickDaily()
        assertEquals(1, sys.notices().size, "a leaver leaves once")
    }

    // ── insurance ────────────────────────────────────────────────────────────

    @Test
    fun `insurance - a verified fill for an insured patient is claimed once, the verdict sets what the patient pays, and silence is followed up`() {
        val uninsured = s.ready()
        assertEquals(0, sys.claims().size, "no plan, no claim")

        val c = s.ready(s.filled(s.counter(patient = s.patient("Insured", plan = s.plan()))))
        assertTrue(c.fill.isA("InsuredFill"))
        val claim = sys.claims().single()
        assertEquals(c.fill, sys.read(claim).fill)
        assertEquals(sys.read(c.patient).plan, sys.read(claim).plan)
        assertEquals("45", sys.read(claim).amount.plain())
        assertTrue(claim.isA("PendingClaim"))
        assertEquals("45", sys.read(c.fill).amountDue.plain(), "until the insurer answers, the patient would pay the lot")

        // silence for a day: followed up hourly, at most once a day
        sys.advanceSeconds(23 * 3600)
        sys.tickHourly()
        assertEquals(0, sys.claimFollowUps().size)
        sys.advanceSeconds(2 * 3600)
        sys.tickHourly()
        sys.tickHourly()
        assertEquals(1, sys.claimFollowUps().size)

        refuse(sys.commit(NewAdjudication(claim, "maybe", dec("40"), dec("5"))))
        accept(sys.commit(NewAdjudication(claim, "paid", dec("40.00"), dec("5.00"))))
        assertTrue(claim.isA("PaidClaim"))
        assertFalse(claim.isA("StaleClaim"))
        assertEquals("5", sys.read(c.fill).amountDue.plain())
        sys.advanceSeconds(24 * 3600)
        sys.tickHourly()
        assertEquals(1, sys.claimFollowUps().size, "answered claims are left alone")

        refuse(sys.commit(NewPickUp(c.fill, "the patient", dec("4.99"))))
        s.counsel(c.fill, c.pharmacist)
        s.pickUp(c.fill)
        assertEquals("5", sys.read(sys.pickups().single()).amountPaid.plain())
        assertEquals(1, sys.claims().size, "claimed once")
    }

    // ── administration ───────────────────────────────────────────────────────

    @Test
    fun `admin - staff roles are a closed list, licences near lapsing are reminded weekly, and the monthly snapshot counts every controlled drug`() {
        refuse(sys.commit(NewStaff("Jo", "janitor")))
        val soon = s.pharmacist("Soon", licensedUntil = s.today.plusDays(20))
        val fine = s.pharmacist("Fine", licensedUntil = s.today.plusDays(200))
        refuse(sys.commit(NewLicenceRenewal(s.technician(), "RPH-X", s.today.plusDays(365))))
        assertTrue(soon.isA("LicenceExpiringSoon"))
        assertFalse(fine.isA("LicenceExpiringSoon"))

        sys.tickDaily()
        sys.tickDaily()
        assertEquals(listOf(soon), sys.licenceReminders().map { sys.read(it).pharmacist })
        sys.advanceDays(6)
        sys.tickDaily()
        assertEquals(1, sys.licenceReminders().size, "weekly")
        sys.advanceDays(1)
        sys.tickDaily()
        assertEquals(2, sys.licenceReminders().size)

        // a renewal ends the reminders; the lapse ends the right to verify
        accept(sys.commit(NewLicenceRenewal(soon, "RPH-NEW", s.today.plusDays(400))))
        assertFalse(soon.isA("LicenceExpiringSoon"))
        sys.advanceDays(7)
        sys.tickDaily()
        assertEquals(2, sys.licenceReminders().size)

        val oxy = s.controlledDrug("oxycodone")
        val codeine = s.controlledDrug("codeine", schedule = 3)
        s.drug("plain")
        s.lot(oxy, received = 50)
        sys.tickMonthly()
        val snapshots = sys.controlledInventorySnapshots()
        assertEquals(setOf(oxy.key, codeine.key), snapshots.map { sys.read(it).drug.key }.toSet(), "controlled drugs only")
        assertEquals(50, sys.read(snapshots.single { sys.read(it).drug == oxy }).onHand)
        sys.tickMonthly()
        assertEquals(4, sys.controlledInventorySnapshots().size, "one per drug per tick is the intent")
    }
}

