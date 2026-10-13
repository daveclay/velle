package velle.generated.pharmacy

import velle.generated.PharmacySystem
import velle.generated.*

/**
 * The human-owned scenarios the generated pharmacy specs demand (testgen.md):
 * how to *reach* each interesting state is business judgment the spec doesn't
 * contain. Every flow is spelled once in [Scenario]; this class only says
 * which flow each generated case starts from.
 */
class Givens(private val sys: PharmacySystem) : RequiredGivens {

    private val s = Scenario(sys)

    // ── staff ────────────────────────────────────────────────────────────────

    /** Licensed for twenty more days: within the thirty-day reminder window. */
    override fun staffForRemindLicence(): Staff = s.pharmacist(licensedUntil = s.today.plusDays(20))

    /** A pharmacist: the generated cases cannot see the role refusals on the acts they synthesise
     *  (`CountLot`, `SetReorderPoint`), and a pharmacist satisfies both. */
    override fun someStaff(): Staff = s.pharmacist("Any staff")

    // ── drugs and prices ─────────────────────────────────────────────────────

    override fun someDrug(): Drug = s.drug()

    override fun controlled(): Drug = s.controlledDrug()

    /** A change dated next month is withdrawn before its day comes. */
    override fun applicableWithdrawal() {
        val d = s.drug()
        s.accept(sys.commitPriceChange(d, s.today.plusDays(30), s.dec("2.00")))
        s.accept(sys.commitWithdrawPriceChange(sys.priceChanges().last(), s.administrator(), "entered in error"))
    }

    /** The price in force since today cannot be withdrawn. */
    override fun refusedWithdrawal() {
        s.drug()
        s.accept(sys.commitWithdrawPriceChange(sys.priceChanges().last(), s.administrator(), "too late"))
    }

    override fun setReorderPoint() {
        s.accept(sys.commitSetReorderPoint(s.drug(), s.administrator(), 40))
    }

    // ── stock ────────────────────────────────────────────────────────────────

    override fun someStockLot(): StockLot = s.lot(s.drug())

    override fun matchingCount() {
        val lot = s.lot(s.drug(), received = 100)
        s.accept(sys.commitCountLot(lot, s.technician(), 100))
    }

    override fun discrepantCount() {
        val lot = s.lot(s.drug(), received = 100)
        s.accept(sys.commitCountLot(lot, s.technician(), 97))
    }

    /** A lot that expired yesterday with stock left, and no nightly tick yet. */
    override fun stockLotForQuarantineExpired(): StockLot {
        val lot = s.lot(s.drug(), expiresOn = s.today.plusDays(5))
        sys.advanceDays(6)
        return lot
    }

    override fun stockLotForQuarantineRecalled(): StockLot {
        val lot = s.lot(s.drug())
        s.accept(sys.commitRecall(lot, "FDA-2026-001", s.today))
        return lot
    }

    /** A fill from a lot that is recalled afterwards: the recall flips the fill into the rule's condition. */
    override fun fillForNoticeRecall(): Fill {
        val c = s.filled()
        s.accept(sys.commitRecall(c.lot, "FDA-2026-002", s.today))
        return c.fill
    }

    override fun fillForLogControlled(): Fill = s.filled(s.counter(drug = s.controlledDrug(), refills = 0)).fill

    /** Twenty-five on the shelf against a reorder point of forty, no order open. */
    override fun drugForReorder(): Drug {
        val d = s.drug(reorderPoint = 40)
        s.lot(d, received = 25)
        return d
    }

    // ── patients ─────────────────────────────────────────────────────────────

    override fun setAllergies() {
        val p = s.patient()
        s.accept(sys.commitSetAllergies(p, listOf(s.ingredient("penicillin"))))
    }

    override fun addAllergy() {
        val p = s.patient()
        s.accept(sys.commitAddAllergy(p, s.ingredient("sulfa")))
    }

    // ── prescriptions ────────────────────────────────────────────────────────

    override fun applicableCorrection() {
        val c = s.counter()
        s.accept(sys.commitCorrectEntry(c.prescription, c.technician, "two daily"))
    }

    override fun refusedCorrection() {
        val c = s.filled()
        s.accept(sys.commitCorrectEntry(c.prescription, c.technician, "two daily"))
    }

    override fun applicableVoid() {
        val c = s.counter()
        s.accept(sys.commitVoidEntry(c.prescription, c.pharmacist, "wrong patient"))
    }

    override fun refusedVoid() {
        val c = s.filled()
        s.accept(sys.commitVoidEntry(c.prescription, c.pharmacist, "wrong patient"))
    }

    /** A patient allergic to penicillin, prescribed a drug containing it. */
    override fun prescriptionForAlertAllergy(): Prescription {
        val penicillin = s.ingredient("penicillin")
        val patient = s.patient(allergies = listOf(penicillin))
        val drug = s.drug("amoxicillin", ingredients = listOf(penicillin))
        return s.prescription(patient, drug)
    }

    /** A patient on warfarin, now prescribed aspirin; the interaction is entered the other way round. */
    override fun prescriptionForAlertInteraction(): Prescription {
        val warfarin = s.drug("warfarin")
        val aspirin = s.drug("aspirin")
        s.interaction(warfarin, aspirin)
        val patient = s.patient()
        s.prescription(patient, warfarin)
        return s.prescription(patient, aspirin)
    }

    // ── filling ──────────────────────────────────────────────────────────────

    override fun applicableFill() {
        val c = s.counter()
        s.requestFill(c.prescription, c.lot, c.technician)
    }

    /** No refills authorised and the one fill already recorded: the request is answered on record. */
    override fun refusedFill() {
        val c = s.filled(s.counter(refills = 0))
        s.requestFill(c.prescription, c.lot, c.technician)
    }

    override fun verifyFill() {
        val c = s.filled()
        s.verify(c.fill, c.pharmacist)
    }

    // ── the counter ──────────────────────────────────────────────────────────

    override fun readyForPickup(): Fill = s.ready().fill

    /** Ready eleven days ago; no nightly tick yet. */
    override fun uncollected(): Fill {
        val c = s.ready()
        sys.advanceDays(11)
        return c.fill
    }

    /** Counselled and collected. */
    override fun exitReadyForPickup(fill: Fill) {
        val rph = s.pharmacist("Second pharmacist")
        s.counsel(fill, rph)
        s.pickUp(fill)
    }

    /** A fill at the counter, then the patient reports an allergy to its drug's ingredient. */
    override fun fillForAlertCounter(): Fill {
        val c = s.ready()
        s.accept(sys.commitAddAllergy(c.patient, sys.read(c.drug).ingredients.single()))
        return c.fill
    }

    override fun pickUp() {
        s.collected()
    }

    // ── refills ──────────────────────────────────────────────────────────────

    override fun applicableRefill() {
        val c = s.counter()
        s.accept(sys.commitRequestRefill(s.key(), c.prescription))
    }

    override fun refusedRefill() {
        val c = s.filled(s.counter(refills = 0))
        s.accept(sys.commitRequestRefill(s.key(), c.prescription))
    }

    override fun refillRefusalForAskPrescriber(): RefillRefusal {
        refusedRefill()
        return sys.refillRefusals().last()
    }

    /** Requested three days ago, never filled; no daily tick yet. */
    override fun refillTaskForNagRefillTask(): RefillTask {
        applicableRefill()
        sys.advanceDays(3)
        return sys.refillTasks().last()
    }

    /** Enrolled, last filled twenty-five days ago; no daily tick yet. */
    override fun prescriptionForQueueAutoRefill(): Prescription {
        val c = s.collected()
        s.accept(sys.commitAutoRefillEnrolment(c.prescription))
        sys.advanceDays(25)
        return c.prescription
    }

    override fun activePrescription(): Prescription = s.counter().prescription

    /** Discontinued by its prescriber. */
    override fun exitActivePrescription(prescription: Prescription) {
        s.accept(sys.commitDiscontinuation(prescription, sys.read(sys.read(prescription).order).prescriber, "changed therapy"))
    }

    // ── insurance ────────────────────────────────────────────────────────────

    override fun fillForSubmitClaim(): Fill = s.ready(s.filled(s.counter(patient = s.patient(plan = s.plan())))).fill

    /** Claimed twenty-five hours ago, unanswered; no hourly tick yet. */
    override fun claimForFollowUpClaim(): Claim {
        fillForSubmitClaim()
        sys.advanceSeconds(25 * 3600)
        return sys.claims().last()
    }

    override fun adjudication() {
        fillForSubmitClaim()
        s.accept(sys.commitAdjudication(sys.claims().last(), "paid", s.dec("40.00"), s.dec("5.00")))
    }
}

