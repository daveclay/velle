package velle.generated.pharmacy

import java.math.BigDecimal
import velle.generated.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import velle.Commit
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

    fun accept(r: Commit<*>) { check(r is Commit.Accepted<*>) { "refused: $r" } }

    val now: Instant get() = sys.system.now
    val today: LocalDate get() = LocalDate.ofInstant(sys.system.now, ZoneOffset.UTC)

    fun dec(s: String): BigDecimal = BigDecimal(s)

    private var keys = 0
    fun key(prefix: String = "REQ"): String = "$prefix-${++keys}"

    // ── staff ────────────────────────────────────────────────────────────────

    /** The first administrator: hiring is gated on an administrator (`HireStaff by
     *  Administrator`), so the first one is seeded by the engineer outside the trust
     *  boundary — a fixture row, no act, no door, no rule (Reference §22, "The actor"). */
    val founder: Staff by lazy {
        sys.system.seed("Staff", mapOf("name" to "Founder", "role" to "admin"))
        sys.staffs().last()
    }

    /** Everyone else is hired by the founder through the gated act. */
    fun staff(name: String, role: String): Staff {
        accept(sys.commit(NewHireStaff(name, role), founder)); return sys.staffs().last()
    }

    /** A pharmacist whose licence runs until [licensedUntil], recorded by the founder. */
    fun pharmacist(name: String = "Priya", licensedUntil: LocalDate = today.plusDays(365)): Staff {
        val p = staff(name, "pharmacist")
        accept(sys.commit(NewLicenceRenewal(p, "RPH-${p.key}", licensedUntil), founder))
        return p
    }

    fun technician(name: String = "Tom"): Staff = staff(name, "technician")
    fun administrator(name: String = "Ada"): Staff = staff(name, "admin")

    // ── drugs ────────────────────────────────────────────────────────────────

    fun ingredient(name: String): Ingredient {
        accept(sys.commit(NewIngredient(name))); return sys.ingredients().last()
    }

    /** A drug with a price of [price] in force from today. */
    fun drug(
        name: String = "amoxicillin",
        schedule: Int = 0,
        requiresCounselling: Boolean = false,
        ingredients: List<Ingredient> = listOf(ingredient(name)),
        reorderPoint: Int = 20,
        price: String = "1.50",
    ): Drug {
        accept(sys.commit(NewDrug(name, "500 mg", "tablet", schedule, requiresCounselling, ingredients, reorderPoint)))
        val d = sys.drugs().last()
        accept(sys.commit(NewPriceChange(d, today, dec(price))))
        return d
    }

    fun controlledDrug(name: String = "oxycodone", schedule: Int = 2): Drug =
        drug(name, schedule = schedule, requiresCounselling = true)

    fun interaction(a: Drug, b: Drug, severity: String = "major") {
        accept(sys.commit(NewInteraction(a, b, severity)))
    }

    fun lot(
        drug: Drug,
        lotNumber: String = "L-${drug.key}",
        expiresOn: LocalDate = today.plusDays(365),
        received: Int = 100,
        purchaseOrder: PurchaseOrder? = null,
    ): StockLot {
        accept(sys.commit(NewStockLot(drug, lotNumber, expiresOn, received, purchaseOrder))); return sys.stockLots().last()
    }

    // ── patients and prescribers ─────────────────────────────────────────────

    fun plan(name: String = "BlueCare", coversControlled: Boolean = true): InsurancePlan {
        accept(sys.commit(NewInsurancePlan(name, coversControlled))); return sys.insurancePlans().last()
    }

    fun patient(
        name: String = "Pat",
        allergies: List<Ingredient> = emptyList(),
        plan: InsurancePlan? = null,
    ): Patient {
        accept(sys.commit(NewPatient(name, LocalDate.of(1980, 5, 17), "555-0100", allergies, plan))); return sys.patients().last()
    }

    fun prescriber(name: String = "Dr Okafor"): Prescriber {
        accept(sys.commit(NewPrescriber(name, "AO1234563", "555-0199"))); return sys.prescribers().last()
    }

    // ── the prescriber's order ───────────────────────────────────────────────

    fun line(drug: Drug, quantity: Int = 30, daysSupply: Int = 30, refills: Int = 2, directions: String = "one daily") =
        NewPrescription(drug, quantity, daysSupply, refills, directions)

    /** An order landing with its prescriptions; returns the order. */
    fun order(
        patient: Patient,
        prescriber: Prescriber,
        enteredBy: Staff,
        lines: List<NewPrescription>,
        writtenOn: LocalDate = today,
    ): PrescriberOrder {
        accept(sys.commit(NewPrescriberOrder(prescriber, patient, writtenOn, lines), enteredBy)); return sys.prescriberOrders().last()
    }

    /** A one-line order; returns the prescription. */
    fun prescription(
        patient: Patient,
        drug: Drug,
        prescriber: Prescriber = prescriber(),
        enteredBy: Staff = technician("Entry clerk"),
        quantity: Int = 30,
        refills: Int = 2,
        writtenOn: LocalDate = today,
    ): Prescription =
        sys.read(sys.read(order(patient, prescriber, enteredBy, listOf(line(drug, quantity = quantity, refills = refills)), writtenOn)).prescriptions.single())

    // ── the fill, the verification, the counter ──────────────────────────────

    /** A monitoring-programme check cleared now by [pharmacist]. */
    fun monitoringCheck(prescription: Prescription, pharmacist: Staff) {
        accept(sys.commit(NewMonitoringCheck(prescription, now, true), pharmacist))
    }

    /** Submits a fill request at the next fill number; returns the request's key. */
    fun requestFill(
        prescription: Prescription,
        lot: StockLot,
        filledBy: Staff,
        quantity: Int = sys.read(prescription).quantity,
        fillNumber: Int = sys.read(prescription).fillsRecorded + 1,
    ): String {
        val k = key("FILL")
        accept(sys.commit(NewFillPrescription(k, prescription, lot, quantity, fillNumber), filledBy))
        return k
    }

    /** A fill that lands (the request answered with a record); returns the fill. */
    fun fill(
        prescription: Prescription,
        lot: StockLot,
        filledBy: Staff,
        quantity: Int = sys.read(prescription).quantity,
    ): Fill {
        val before = sys.fills().size
        requestFill(prescription, lot, filledBy, quantity)
        check(sys.fills().size == before + 1) { "the fill was refused on record: ${sys.fillRefusals().lastOrNull()?.let { sys.read(it) }?.reason}" }
        return sys.fills().last()
    }

    fun verify(fill: Fill, pharmacist: Staff, outcome: String = "approved") {
        accept(sys.commit(NewVerifyFill(fill, outcome, "checked"), pharmacist))
    }

    fun counsel(fill: Fill, pharmacist: Staff) {
        accept(sys.commit(NewCounselling(fill, "take with food"), pharmacist))
    }

    fun pickUp(fill: Fill, amount: BigDecimal = sys.read(fill).amountDue) {
        accept(sys.commit(NewPickUp(fill, "the patient", amount)))
    }

    /** Everything one fill needs around it. */
    class Counter(
        val technician: Staff,
        val pharmacist: Staff,
        val prescriber: Prescriber,
        val patient: Patient,
        val drug: Drug,
        val lot: StockLot,
        val prescription: Prescription,
    ) {
        lateinit var fill: Fill
    }

    /** A patient with a prescription for a drug in stock, nothing filled yet. */
    fun counter(
        patient: Patient = patient(),
        drug: Drug = drug(),
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
        if (sys.read(c.drug).schedule >= 2) monitoringCheck(c.prescription, c.pharmacist)
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

