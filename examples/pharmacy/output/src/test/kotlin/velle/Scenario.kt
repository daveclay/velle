package velle.generated.pharmacy

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import velle.CommitResult
import velle.generated.PharmacySystem

/**
 * The business flows of the pharmacy, spelled once and shared by the generated
 * specs' givens and the hand-written flow test: staff with a licence, drugs
 * with ingredients, a price in force, and stock on the shelf; patients and
 * prescribers; a prescriber's order landing with its prescriptions; a fill
 * by a technician, verified by a pharmacist, counselled, and collected at the
 * counter.
 *
 * Numbers used throughout, so a reader can check the derivations by hand:
 *   · a lot of 100 tablets; a prescription for 30, two refills (three fills)
 *   · a unit price of 1.50 in force, so a fill of 30 totals 45.00
 *   · a licence good for a year; a lot good for a year
 *
 * The system clock starts 2026-01-01T09:00Z; `today` is that instant's UTC date.
 */
class Scenario(val sys: PharmacySystem) {

    fun accept(r: CommitResult) { check(r is CommitResult.Accepted) { "refused: $r" } }

    val now: Instant get() = sys.system.now
    val today: LocalDate get() = LocalDate.ofInstant(sys.system.now, ZoneOffset.UTC)

    fun dec(s: String): BigDecimal = BigDecimal(s)

    private var keys = 0
    fun key(prefix: String = "REQ"): String = "$prefix-${++keys}"

    // ── staff ────────────────────────────────────────────────────────────────

    fun staff(name: String, role: String): PharmacySystem.StaffView {
        accept(sys.commitStaff(name, role)); return sys.staffs().last()
    }

    /** A pharmacist whose licence runs until [licensedUntil]. */
    fun pharmacist(name: String = "Priya", licensedUntil: LocalDate = today.plusDays(365)): PharmacySystem.StaffView {
        val p = staff(name, "pharmacist")
        accept(sys.commitLicenceRenewal(p, "RPH-${p.id}", licensedUntil))
        return p
    }

    fun technician(name: String = "Tom"): PharmacySystem.StaffView = staff(name, "technician")
    fun administrator(name: String = "Ada"): PharmacySystem.StaffView = staff(name, "admin")

    // ── drugs ────────────────────────────────────────────────────────────────

    fun ingredient(name: String): PharmacySystem.IngredientView {
        accept(sys.commitIngredient(name)); return sys.ingredients().last()
    }

    /** A drug with a price of [price] in force from today. */
    fun drug(
        name: String = "amoxicillin",
        schedule: Int = 0,
        requiresCounselling: Boolean = false,
        ingredients: List<PharmacySystem.IngredientView> = listOf(ingredient(name)),
        reorderPoint: Int = 20,
        price: String = "1.50",
    ): PharmacySystem.DrugView {
        accept(sys.commitDrug(name, "500 mg", "tablet", schedule, requiresCounselling, ingredients, reorderPoint))
        val d = sys.drugs().last()
        accept(sys.commitPriceChange(d, today, dec(price)))
        return d
    }

    fun controlledDrug(name: String = "oxycodone", schedule: Int = 2): PharmacySystem.DrugView =
        drug(name, schedule = schedule, requiresCounselling = true)

    fun interaction(a: PharmacySystem.DrugView, b: PharmacySystem.DrugView, severity: String = "major") {
        accept(sys.commitInteraction(a, b, severity))
    }

    fun lot(
        drug: PharmacySystem.DrugView,
        lotNumber: String = "L-${drug.id}",
        expiresOn: LocalDate = today.plusDays(365),
        received: Int = 100,
        purchaseOrder: PharmacySystem.PurchaseOrderView? = null,
    ): PharmacySystem.StockLotView {
        accept(sys.commitStockLot(drug, lotNumber, expiresOn, received, purchaseOrder)); return sys.stockLots().last()
    }

    // ── patients and prescribers ─────────────────────────────────────────────

    fun plan(name: String = "BlueCare", coversControlled: Boolean = true): PharmacySystem.InsurancePlanView {
        accept(sys.commitInsurancePlan(name, coversControlled)); return sys.insurancePlans().last()
    }

    fun patient(
        name: String = "Pat",
        allergies: List<PharmacySystem.IngredientView> = emptyList(),
        plan: PharmacySystem.InsurancePlanView? = null,
    ): PharmacySystem.PatientView {
        accept(sys.commitPatient(name, LocalDate.of(1980, 5, 17), "555-0100", allergies, plan)); return sys.patients().last()
    }

    fun prescriber(name: String = "Dr Okafor"): PharmacySystem.PrescriberView {
        accept(sys.commitPrescriber(name, "AO1234563", "555-0199")); return sys.prescribers().last()
    }

    // ── the prescriber's order ───────────────────────────────────────────────

    fun line(drug: PharmacySystem.DrugView, quantity: Int = 30, daysSupply: Int = 30, refills: Int = 2, directions: String = "one daily") =
        PharmacySystem.NewPrescription(drug, quantity, daysSupply, refills, directions)

    /** An order landing with its prescriptions; returns the order. */
    fun order(
        patient: PharmacySystem.PatientView,
        prescriber: PharmacySystem.PrescriberView,
        enteredBy: PharmacySystem.StaffView,
        lines: List<PharmacySystem.NewPrescription>,
        writtenOn: LocalDate = today,
    ): PharmacySystem.PrescriberOrderView {
        accept(sys.commitPrescriberOrder(prescriber, patient, writtenOn, enteredBy, lines)); return sys.prescriberOrders().last()
    }

    /** A one-line order; returns the prescription. */
    fun prescription(
        patient: PharmacySystem.PatientView,
        drug: PharmacySystem.DrugView,
        prescriber: PharmacySystem.PrescriberView = prescriber(),
        enteredBy: PharmacySystem.StaffView = technician("Entry clerk"),
        quantity: Int = 30,
        refills: Int = 2,
        writtenOn: LocalDate = today,
    ): PharmacySystem.PrescriptionView =
        order(patient, prescriber, enteredBy, listOf(line(drug, quantity = quantity, refills = refills)), writtenOn).prescriptions.single()

    // ── the fill, the verification, the counter ──────────────────────────────

    /** A monitoring-programme check cleared now by [pharmacist]. */
    fun monitoringCheck(prescription: PharmacySystem.PrescriptionView, pharmacist: PharmacySystem.StaffView) {
        accept(sys.commitMonitoringCheck(prescription, pharmacist, now, true))
    }

    /** Submits a fill request at the next fill number; returns the request's key. */
    fun requestFill(
        prescription: PharmacySystem.PrescriptionView,
        lot: PharmacySystem.StockLotView,
        filledBy: PharmacySystem.StaffView,
        quantity: Int = prescription.quantity,
        fillNumber: Int = prescription.fillsRecorded + 1,
    ): String {
        val k = key("FILL")
        accept(sys.commitFillPrescription(k, prescription, lot, quantity, fillNumber, filledBy))
        return k
    }

    /** A fill that lands (the request answered with a record); returns the fill. */
    fun fill(
        prescription: PharmacySystem.PrescriptionView,
        lot: PharmacySystem.StockLotView,
        filledBy: PharmacySystem.StaffView,
        quantity: Int = prescription.quantity,
    ): PharmacySystem.FillView {
        val before = sys.fills().size
        requestFill(prescription, lot, filledBy, quantity)
        check(sys.fills().size == before + 1) { "the fill was refused on record: ${sys.fillRefusals().lastOrNull()?.reason}" }
        return sys.fills().last()
    }

    fun verify(fill: PharmacySystem.FillView, pharmacist: PharmacySystem.StaffView, outcome: String = "approved") {
        accept(sys.commitVerifyFill(fill, pharmacist, outcome, "checked"))
    }

    fun counsel(fill: PharmacySystem.FillView, pharmacist: PharmacySystem.StaffView) {
        accept(sys.commitCounselling(fill, pharmacist, "take with food"))
    }

    fun pickUp(fill: PharmacySystem.FillView, amount: BigDecimal = fill.amountDue) {
        accept(sys.commitPickUp(fill, "the patient", amount))
    }

    /** Everything one fill needs around it. */
    class Counter(
        val technician: PharmacySystem.StaffView,
        val pharmacist: PharmacySystem.StaffView,
        val prescriber: PharmacySystem.PrescriberView,
        val patient: PharmacySystem.PatientView,
        val drug: PharmacySystem.DrugView,
        val lot: PharmacySystem.StockLotView,
        val prescription: PharmacySystem.PrescriptionView,
    ) {
        lateinit var fill: PharmacySystem.FillView
    }

    /** A patient with a prescription for a drug in stock, nothing filled yet. */
    fun counter(
        patient: PharmacySystem.PatientView = patient(),
        drug: PharmacySystem.DrugView = drug(),
        refills: Int = 2,
        quantity: Int = 30,
    ): Counter {
        val tech = technician()
        val rph = pharmacist()
        val dr = prescriber()
        val lot = lot(drug)
        val rx = prescription(patient, drug, dr, tech, quantity = quantity, refills = refills)
        return Counter(tech, rph, dr, patient, drug, lot, rx)
    }

    /** The counter with its first fill recorded, not yet verified. */
    fun filled(c: Counter = counter()): Counter {
        if (c.drug.schedule >= 2) monitoringCheck(c.prescription, c.pharmacist)
        c.fill = fill(c.prescription, c.lot, c.technician)
        return c
    }

    /** The counter with its first fill verified: ready for pickup, counselling due. */
    fun ready(c: Counter = filled()): Counter {
        verify(c.fill, c.pharmacist)
        return c
    }

    /** The counter with its first fill collected. */
    fun collected(c: Counter = ready()): Counter {
        counsel(c.fill, c.pharmacist)
        pickUp(c.fill)
        return c
    }
}
