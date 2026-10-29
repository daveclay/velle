package velle.pharmacy

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.stereotype.Component
import velle.Commit
import velle.Ref
import velle.TickRejected
import velle.api.VelleApi
import velle.generated.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.random.Random

/**
 * What the pharmacy application configures under `pharmacy.seed`:
 *
 * ```yaml
 * pharmacy:
 *   seed:
 *     enabled: true   # load the test data when the books are empty
 *     days: 90        # how many days of business the data covers, ending today
 * ```
 */
@ConfigurationProperties("pharmacy.seed")
class SeedProperties {
    var enabled: Boolean = true
    var days: Int = 90
}

/**
 * Realistic test data for the pharmacy, loaded once, when the application starts
 * against an empty database. Everything but the first administrator arrives
 * through the spec's own acts, committed by the staff member who would commit
 * them, with the system's clock walked day by day from [SeedProperties.days]
 * days ago to today, and every schedule ticked on its day. So the fills were
 * filled on the days they say, the claims went stale and were followed up, the
 * expired lot was quarantined overnight, and the derivations read what the acts
 * left: nothing is written straight into the tables.
 *
 * A second start against the same database finds the staff on record and loads
 * nothing. `pharmacy.seed.enabled: false` turns it off.
 */
@Component
@EnableConfigurationProperties(SeedProperties::class)
@ConditionalOnProperty(prefix = "pharmacy.seed", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class PharmacySeed(
    private val pharmacy: PharmacySystem,
    private val props: SeedProperties,
    // the API bean is what connects the system to the store; depending on it puts the seed after that
    @Suppress("unused") api: VelleApi,
) : SmartInitializingSingleton {

    private val log = LoggerFactory.getLogger(PharmacySeed::class.java)

    override fun afterSingletonsInstantiated() {
        if (pharmacy.staffs().isNotEmpty()) {
            log.info("Velle pharmacy: the books already hold {} staff; nothing seeded", pharmacy.staffs().size)
            return
        }
        val started = System.nanoTime()
        val walk = SeedWalk(pharmacy, props.days)
        walk.run()
        pharmacy.setTime(Instant.now())
        log.info(
            "Velle pharmacy: seeded {} days of business in {} s ({}): {} patients, {} prescriptions, {} fills, {} claims, {} notices",
            props.days, (System.nanoTime() - started) / 1_000_000_000, walk.timing(),
            pharmacy.patients().size, pharmacy.prescriptions().size, pharmacy.fills().size, pharmacy.claims().size, pharmacy.notices().size,
        )
    }
}

/**
 * The days of business, one at a time. The roster ([Roster]) is loaded on the
 * first day; each patient's regimen is ordered over the first weeks and then
 * refilled on its cadence; a scripted event or two lands on particular days so
 * every state the spec names has a record behind it. The clock is set before each
 * step, so `today` and `now` in the spec are the day and hour being replayed.
 */
internal class SeedWalk(private val sys: PharmacySystem, private val days: Int) {

    private val log = LoggerFactory.getLogger(SeedWalk::class.java)
    private val rnd = Random(20260101)

    private val end: LocalDate = LocalDate.now(ZoneOffset.UTC)
    private val start: LocalDate = end.minusDays(days.toLong())
    private var clock: Instant = start.atTime(8, 0).toInstant(ZoneOffset.UTC)
    private val today: LocalDate get() = LocalDate.ofInstant(clock, ZoneOffset.UTC)

    /** The clock never runs ahead of the wall clock: the last day's afternoon is cut to now when now is the morning. */
    private fun at(date: LocalDate, hour: Int, minute: Int = 0) {
        clock = minOf(date.atTime(hour, minute).toInstant(ZoneOffset.UTC), Instant.now())
        sys.setTime(clock)
    }

    /** A few minutes pass between one step of a flow and the next, so timestamps order as the day did. */
    private fun later(minutes: Long = 5) {
        clock = minOf(clock.plusSeconds(minutes * 60), Instant.now())
        sys.setTime(clock)
    }

    // ── committing ───────────────────────────────────────────────────────────

    private fun <I, R> ok(r: Commit<I, R>): Commit.Accepted<I, R> = when (r) {
        is Commit.Accepted -> r
        is Commit.Refused -> throw IllegalStateException("seed: ${r.input} was refused on ${today}: ${r.reason}")
    }

    private fun <I, T> make(r: Commit<I, Ref<T>>): Ref<T> = ok(r).ref

    private var keys = 0
    private fun key(prefix: String) = "$prefix-${today.year}-${(++keys).toString().padStart(5, '0')}"

    private fun dec(s: String) = BigDecimal(s)
    private fun <T> List<T>.pick(): T = this[rnd.nextInt(size)]

    // ── the world ────────────────────────────────────────────────────────────

    private lateinit var founder: Ref<Staff>
    private val admins = mutableListOf<Ref<Staff>>()
    private val technicians = mutableListOf<Ref<Staff>>()
    /** each pharmacist with the day their licence runs out */
    private val pharmacists = mutableMapOf<Ref<Staff>, LocalDate>()
    private val staffNames = mutableMapOf<Ref<Staff>, String>()

    private val ingredients = mutableMapOf<String, Ref<Ingredient>>()
    private val drugs = mutableMapOf<String, Ref<Drug>>()
    private val specs = Roster.drugs.associateBy { it.name }
    private val plans = mutableListOf<Ref<InsurancePlan>>()
    private val prescribers = mutableListOf<Ref<Prescriber>>()

    private inner class PatientState(val spec: Roster.PatientSpec, val ref: Ref<Patient>) {
        /** the day the patient's regimen is first ordered, and whether it has been */
        var rollout: LocalDate = start
        var ordered = false
        val prescriber: Ref<Prescriber> get() = prescribers[spec.prescriber]
        val plan: Roster.Plan? get() = spec.plan?.let { Roster.plans[it] }
    }

    private val patients = mutableListOf<PatientState>()
    private fun patient(name: String) = patients.first { it.spec.name == name }

    /** A prescription the walk keeps filling: the patient, the drug, and when the next fill is due. */
    private inner class Rx(val ref: Ref<Prescription>, val patient: PatientState, val regimen: Roster.Regimen, val prescriber: Ref<Prescriber>) {
        var nextFill: LocalDate? = null
        var autoRefill = false
        /** a refill asked for and queued, to be filled on this day */
        var taskDue: LocalDate? = null
    }

    private val regimens = mutableListOf<Rx>()

    /** A verified fill waiting at the counter and the day the patient comes for it; null means they never do. */
    private class Waiting(val fill: Ref<Fill>, val patient: String, val collectOn: LocalDate?)

    private val counter = mutableListOf<Waiting>()

    /** A claim the insurer has not answered yet and the day the answer arrives. */
    private class Pending(val claim: Ref<Claim>, val plan: Roster.Plan, val controlled: Boolean, val answerOn: LocalDate)

    private val pendingClaims = mutableListOf<Pending>()

    // ── the walk ─────────────────────────────────────────────────────────────

    fun run() {
        sys.system.scope {
            at(start, 8, 0)
            loadRoster()
            priorHistory()
        }
        // each day runs in one scope of the runtime, as a request does: its reads share one working set and one
        // unit of work on the store between the commits, instead of a unit per read
        for (day in 0..days) sys.system.scope {
            val date = start.plusDays(day.toLong())
            if (day > 0 && day % 10 == 0) log.info("seed: {} days replayed, {} to go", day, days - day)
            if (day > 0) {
                at(date, 6, 0)
                tick("Daily") { sys.tickDaily() }
                if (date.dayOfMonth == 1) tick("Monthly") { sys.tickMonthly() }
            }
            at(date, 9, 0)
            scripted(day)
            rolloutsDue(date)
            refillsDue(date)
            tasksDue(date)
            purchaseOrdersDue(date)
            at(date, 13, 0)
            claimsDue(date)
            tick("Hourly") { sys.tickHourly() }
            at(date, 16, 0)
            pickupsDue(date)
            if (day < days) {
                at(date, 23, 30)
                tick("Nightly") { sys.tickNightly() }
            }
        }
    }

    private var tickNanos = 0L
    private var ticks = 0

    private fun tick(schedule: String, run: () -> Any?) {
        val t = System.nanoTime()
        try {
            run()
        } catch (e: TickRejected) {
            log.warn("seed: the {} tick on {} had firings refused: {}", schedule, today, e.message)
        }
        tickNanos += System.nanoTime() - t
        ticks++
    }

    /** How the time went: the ticks against everything else. */
    fun timing(): String = "$ticks ticks took ${tickNanos / 1_000_000_000} s"


    // ── day one: the roster ──────────────────────────────────────────────────

    private fun loadRoster() {
        // the first administrator is a fixture row: hiring is gated on an administrator (Reference §22, "The actor")
        sys.system.seed("Staff", mapOf("name" to Roster.FOUNDER, "role" to "admin"))
        founder = sys.staffs().single()
        staffNames[founder] = Roster.FOUNDER
        admins += founder

        for (h in Roster.hires) {
            ok(sys.commit(NewHireStaff(h.name, h.role), founder))
            val s = sys.staffs().last()
            staffNames[s] = h.name
            when (h.role) {
                "admin" -> admins += s
                "technician" -> technicians += s
                "pharmacist" -> {
                    val until = start.plusDays(h.licenceDays!!.toLong())
                    ok(sys.commit(NewLicenceRenewal(s, "RPH-${18000 + s.key.value.toString().toInt() * 37}", until), founder))
                    pharmacists[s] = until
                }
            }
            later(2)
        }

        for (name in Roster.ingredients) ingredients[name] = make(sys.commit(NewIngredient(name)))

        for (d in Roster.drugs) {
            val drug = make(sys.commit(NewDrug(d.name, d.strength, d.form, d.schedule, d.counselling, d.ingredients.map { ingredients.getValue(it) }, d.reorderPoint)))
            drugs[d.name] = drug
            // the price in force since well before the first day, and an older one it replaced
            ok(sys.commit(NewPriceChange(drug, start.minusDays(400), dec(d.price).multiply(dec("0.92")).setScale(2, RoundingMode.HALF_UP))))
            ok(sys.commit(NewPriceChange(drug, start.minusDays(35), dec(d.price))))
            d.lots.forEachIndexed { i, size ->
                val expires = when {
                    // one lot of prednisone runs out of date during the walk, with stock left: the nightly sweep quarantines it
                    d.name == "Prednisone" && i == 0 -> start.plusDays(45)
                    else -> start.plusDays(300L + rnd.nextInt(400))
                }
                ok(sys.commit(NewStockLot(drug, lotNumber(d, i), expires, size)))
            }
        }
        for ((a, b, severity) in Roster.interactions) ok(sys.commit(NewInteraction(drugs.getValue(a), drugs.getValue(b), severity)))

        for (p in Roster.plans) plans += make(sys.commit(NewInsurancePlan(p.name, p.coversControlled)))
        for (p in Roster.prescribers) prescribers += make(sys.commit(NewPrescriber(p.name, p.dea, p.fax)))

        Roster.patients.forEachIndexed { i, p ->
            val ref = make(sys.commit(NewPatient(p.name, p.birthDate, p.phone, p.allergies.map { ingredients.getValue(it) }, p.plan?.let { plans[it] })))
            patients += PatientState(p, ref).also { it.rollout = start.plusDays((i * 4L / 3) % 40) }
        }
        // these two have their regimen ordered after their old prescription has expired (see priorHistory)
        patient("Beatrice Holm").rollout = start.plusDays(21)
        patient("Jonas Weber").rollout = start.plusDays(16)
        // and this one is on the books from the second day, for the scripted refusal on the tenth
        patient("Harold Finch").rollout = start.plusDays(2)
        patient("Victor Nakamura").rollout = start.plusDays(4)
        log.info("seed: roster loaded on {}: {} staff, {} drugs, {} patients", today, sys.staffs().size, drugs.size, patients.size)
    }

    private fun lotNumber(d: Roster.DrugSpec, i: Int): String {
        val code = d.name.take(3).uppercase()
        return "$code-${(start.year - 1) % 100}${(1000 + rnd.nextInt(9000))}-${i + 1}"
    }

    /** Prescriptions written long before the first day, still on the books: they age out during the walk. */
    private fun priorHistory() {
        val tech = technicians.pick()
        // written eleven and a half months ago: expires on the fifteenth day of the walk, a notice goes to the patient
        val beatrice = patient("Beatrice Holm")
        val levo = order(beatrice, beatrice.prescriber, tech, start.minusDays(350), listOf(line(Roster.Regimen("Levothyroxine", 30, 30, 11, "take one tablet by mouth every morning on an empty stomach"))))
        // a controlled prescription written just under six months ago: expires on the tenth day
        val jonas = patient("Jonas Weber")
        order(jonas, jonas.prescriber, tech, start.minusDays(170), listOf(line(Roster.Regimen("Alprazolam", 60, 30, 5, "take one tablet by mouth twice daily as needed for anxiety"))))
        // a fill against the old levothyroxine order, collected, so the expiry notice is for a prescription in use
        val rx = sys.read(sys.read(levo).prescriptions.single())
        val fill = fillVerifyAndClaim(rx, tech) ?: return
        later(30)
        collect(fill, beatrice.spec.name)
    }

    // ── orders ───────────────────────────────────────────────────────────────

    private fun line(r: Roster.Regimen) = NewPrescription(drugs.getValue(r.drug), r.quantity, r.daysSupply, r.refills, r.directions)

    private fun order(p: PatientState, prescriber: Ref<Prescriber>, enteredBy: Ref<Staff>, writtenOn: LocalDate, lines: List<NewPrescription>): Ref<PrescriberOrder> {
        val ref = make(sys.commit(NewPrescriberOrder(prescriber, p.ref, writtenOn, lines), enteredBy))
        later(3)
        return ref
    }

    /** The regimen's order lands, and each line becomes a prescription the walk keeps filling. */
    private fun orderRegimen(p: PatientState, lines: List<Roster.Regimen>, writtenOn: LocalDate = today.minusDays(rnd.nextInt(3).toLong()), fillToday: Boolean = true, prescriber: Ref<Prescriber> = p.prescriber): List<Rx> {
        val tech = technicians.pick()
        val ref = order(p, prescriber, tech, writtenOn, lines.map(::line))
        val made = sys.read(ref).prescriptions.map { sys.read(it) }
        val rxs = lines.map { r -> Rx(made.first { it.drug.key == drugs.getValue(r.drug).key }, p, r, prescriber) }
        regimens += rxs
        if (fillToday) for (rx in rxs) firstFill(rx, tech)
        return rxs
    }

    private fun rolloutsDue(date: LocalDate) {
        for (p in patients.filter { !it.ordered && it.rollout <= date }) {
            p.ordered = true
            orderRegimen(p, p.spec.regimen)
        }
    }

    // ── filling, verifying, claiming, counselling, collecting ────────────────

    /** The lot the technician pulls: usable, enough on it, the soonest to expire first. */
    private fun lotFor(drug: Ref<Drug>, quantity: Int): Ref<StockLot>? =
        sys.read(drug).stockLots.map { sys.read(it) }.filter { it.isUsableLot && it.onHand >= quantity }.minByOrNull { it.expiresOn }

    private fun licensedPharmacist(notThis: Ref<Staff>? = null): Ref<Staff> =
        pharmacists.filter { (s, until) -> until >= today && s != notThis }.keys.toList().pick()

    /**
     * The technician's fill request, the pharmacist's check, and the insurer's claim.
     * Returns the fill, or null when the request was answered on record with a
     * refusal (the caller reads the reason off the books) or no lot could serve it.
     */
    private fun fillVerifyAndClaim(rx: Prescription, tech: Ref<Staff>, quantity: Int = rx.quantity, resolveConflicts: Boolean = true): Ref<Fill>? {
        val drug = sys.read(rx.drug)
        val lot = lotFor(rx.drug, quantity) ?: run { log.info("seed: no usable lot of {} for {} on {}", drug.name, rx.key, today); return null }
        if (drug.isControlled) {
            ok(sys.commit(NewMonitoringCheck(rx, clock, true), licensedPharmacist()))
            later(10)
        }
        val before = rx.fillsRecorded
        ok(sys.commit(NewFillPrescription(key("FILL"), rx, lot, quantity, before + 1), tech))
        later(10)
        var after = sys.read(rx)
        if (after.fillsRecorded == before && resolveConflicts && after.isActivePrescription && !after.isCleared) {
            // turned away for a conflict the pharmacist has not looked at: they look, and put the override on record
            val why = if (after.isAllergyConflict) "allergy reviewed with the patient: tolerated this medicine before, prescriber aware"
                      else "interaction reviewed: moderate, dose within limits, patient counselled on what to watch for"
            ok(sys.commit(NewConflictOverride(rx, why), licensedPharmacist()))
            later(15)
            ok(sys.commit(NewFillPrescription(key("FILL"), rx, lot, quantity, before + 1), tech))
            later(10)
            after = sys.read(rx)
        }
        if (after.fillsRecorded == before) return null
        val fill = after.fills.map { sys.read(it) }.maxBy { it.fillNumber }
        val rph = licensedPharmacist(notThis = tech)
        ok(sys.commit(NewVerifyFill(fill, "approved", "checked against the order"), rph))
        later(15)
        val verified = sys.read(fill)
        for (claim in verified.claims) {
            val plan = patients.first { it.ref == sys.read(rx.order).patient }.plan ?: continue
            // most answers come back within the hour; some take days, and go stale
            val answerOn = if (rnd.nextInt(10) == 0) today.plusDays(2L + rnd.nextInt(3)) else today
            pendingClaims += Pending(claim, plan, drug.isControlled, answerOn)
        }
        if (verified.isCounsellingDue) {
            ok(sys.commit(NewCounselling(fill, counsellingNote(drug)), rph))
            later(5)
        }
        return fill
    }

    private fun counsellingNote(drug: Drug) = when {
        drug.isControlled -> "reviewed dosing, drowsiness, and not driving; patient to keep out of reach of others"
        drug.name.startsWith("Warfarin") -> "reviewed bleeding signs and the INR schedule; avoid aspirin and ibuprofen"
        drug.name.startsWith("Levothyroxine") -> "take on an empty stomach, thirty minutes before breakfast"
        drug.name.startsWith("Albuterol") -> "demonstrated inhaler technique; rinse mouth after use"
        else -> "reviewed directions and common side effects; patient had no questions"
    }

    /** Insurer verdicts due today. */
    private fun claimsDue(date: LocalDate) {
        val due = pendingClaims.filter { it.answerOn <= date }
        pendingClaims -= due
        for (p in due) {
            val claim = sys.read(p.claim)
            when {
                p.controlled && !p.plan.coversControlled ->
                    ok(sys.commit(NewAdjudication(claim, "rejected", dec("0"), claim.amount)))
                rnd.nextInt(12) == 0 ->
                    ok(sys.commit(NewAdjudication(claim, "rejected", dec("0"), claim.amount)))
                else -> {
                    val share = claim.amount.multiply(dec(p.plan.copayShare)).setScale(2, RoundingMode.HALF_UP)
                    val copay = share.max(dec(p.plan.copayFloor)).min(claim.amount)
                    ok(sys.commit(NewAdjudication(claim, "paid", claim.amount.subtract(copay), copay)))
                }
            }
            later(1)
        }
    }

    /** The patient collects and pays what is due. */
    private fun collect(fill: Ref<Fill>, patient: String) {
        val f = sys.read(fill)
        if (!f.isReadyForPickup) return
        val collectedBy = if (rnd.nextInt(6) == 0) "$patient (spouse)" else patient
        ok(sys.commit(NewPickUp(fill, collectedBy, f.amountDue)))
        later(2)
    }

    /** A fill that lands waits at the counter until the patient comes, today or in the next days; one in twenty never does. */
    private fun toCounter(fill: Ref<Fill>, p: PatientState) {
        val collectOn = when (rnd.nextInt(20)) {
            0 -> null
            in 1..11 -> today
            else -> today.plusDays(1L + rnd.nextInt(3))
        }
        counter += Waiting(fill, p.spec.name, collectOn)
    }

    private fun pickupsDue(date: LocalDate) {
        val due = counter.filter { it.collectOn != null && it.collectOn <= date }
        counter -= due
        for (w in due) collect(w.fill, w.patient)
    }

    // ── the regimen's cadence ────────────────────────────────────────────────

    private fun firstFill(rx: Rx, tech: Ref<Staff>) {
        val fill = fillVerifyAndClaim(sys.read(rx.ref), tech)
        if (fill != null) toCounter(fill, rx.patient)
        rx.nextFill = today.plusDays(rx.regimen.daysSupply.toLong() - 2 + rnd.nextInt(5))
    }

    private fun refillsDue(date: LocalDate) {
        for (rx in regimens.filter { it.nextFill != null && it.nextFill!! <= date && it.taskDue == null }) {
            val p = sys.read(rx.ref)
            if (!p.isActivePrescription) {
                // refills used up, or the prescription has aged out: the patient asks anyway and is told why
                ok(sys.commit(NewRequestRefill(key("RFL"), rx.ref)))
                later(2)
                rx.nextFill = null
                // a regimen the patient stays on is re-authorised by the prescriber; a short course just ends
                if (p.isExhausted && !p.isExpired && (rx.regimen.refills > 0 || rx.regimen.daysSupply >= 30)) reauthorise(rx)
                else regimens -= rx
                continue
            }
            if (rx.autoRefill) {
                // the daily sweep queued the work; the technician fills it
                fillRefill(rx)
                continue
            }
            // the patient asks; most are filled the same day, some wait a few days and are nagged about
            ok(sys.commit(NewRequestRefill(key("RFL"), rx.ref)))
            later(2)
            if (rnd.nextInt(10) < 7) fillRefill(rx) else rx.taskDue = today.plusDays(3L + rnd.nextInt(2))
        }
    }

    private fun tasksDue(date: LocalDate) {
        for (rx in regimens.filter { it.taskDue != null && it.taskDue!! <= date }) {
            rx.taskDue = null
            fillRefill(rx)
        }
    }

    private fun fillRefill(rx: Rx) {
        val fill = fillVerifyAndClaim(sys.read(rx.ref), technicians.pick())
        if (fill != null) toCounter(fill, rx.patient)
        rx.nextFill = today.plusDays(rx.regimen.daysSupply.toLong() - 2 + rnd.nextInt(5))
    }

    /** The prescriber answers the authorisation request with a fresh order a few days on, and the regimen continues on it. */
    private fun reauthorise(rx: Rx) {
        regimens -= rx
        reauthorisations += Pair(today.plusDays(2L + rnd.nextInt(4)), rx)
    }

    private val reauthorisations = mutableListOf<Pair<LocalDate, Rx>>()

    // ── stock ────────────────────────────────────────────────────────────────

    /** An order the nightly sweep placed five or more days ago arrives: the lot that closes it. */
    private fun purchaseOrdersDue(date: LocalDate) {
        for (po in sys.purchaseOrders().filter { it.isOpenPurchaseOrder && it.orderedOn <= date.minusDays(5) }) {
            val drug = sys.read(po.drug)
            val spec = specs.getValue(drug.name)
            ok(sys.commit(NewStockLot(po.drug, lotNumber(spec, drug.stockLots.size), today.plusDays(500L + rnd.nextInt(300)), po.quantity, po)))
            later(1)
        }
    }

    private fun countLot(lot: StockLot, counted: Int) {
        ok(sys.commit(NewCountLot(lot, counted), technicians.pick()))
        later(3)
    }

    // ── the scripted days ────────────────────────────────────────────────────

    /** Events that make sure each state the spec names has a record behind it, on particular days of the walk. */
    private fun scripted(day: Int) {
        // the prescriber's fresh orders after a refill was refused for want of refills
        for ((on, rx) in reauthorisations.filter { it.first <= today }) {
            reauthorisations -= Pair(on, rx)
            orderRegimen(rx.patient, listOf(rx.regimen), prescriber = rx.prescriber)
        }
        when (day) {
            3 -> {
                // the technicians count three lots: two match, one is three short
                val lots = sys.stockLots()
                countLot(lots[2], lots[2].onHand)
                countLot(lots[7], lots[7].onHand)
                countLot(lots[11], lots[11].onHand - 3)
            }
            5 -> {
                // a sinus infection: amoxicillin-clavulanate for a patient allergic to penicillin. The
                // pharmacist is alerted, calls the prescriber, who discontinues it and sends azithromycin
                val sofia = patient("Sofia Marchetti")
                val tech = technicians.pick()
                val bad = order(sofia, sofia.prescriber, tech, today, listOf(line(Roster.Regimen("Amoxicillin-Clavulanate", 20, 10, 0, "take one tablet by mouth twice daily for ten days"))))
                val rx = sys.read(bad).prescriptions.single()
                later(20)
                fillVerifyAndClaim(sys.read(rx), tech, resolveConflicts = false) // refused on record: unresolved allergy conflict
                later(40)
                ok(sys.commit(NewDiscontinuation(rx, sofia.prescriber, "penicillin allergy; changed to azithromycin")))
                later(30)
                val zpak = orderRegimen(sofia, listOf(Roster.Regimen("Azithromycin", 6, 5, 0, "take two tablets by mouth on day one, then one daily for four days")), fillToday = false)
                firstFill(zpak.single(), tech)
                regimens -= zpak.single()
            }
            8 -> {
                // ibuprofen for a patient on warfarin: the interaction alert, and the pharmacist's override after a call
                val walter = patient("Walter Gibbs")
                val tech = technicians.pick()
                val o = order(walter, walter.prescriber, tech, today, listOf(line(Roster.Regimen("Ibuprofen", 15, 5, 0, "take one tablet by mouth every eight hours with food for five days"))))
                val rx = sys.read(o).prescriptions.single()
                later(25)
                ok(sys.commit(NewConflictOverride(rx, "spoke with Dr Okafor: five-day course, INR check booked for next week"), licensedPharmacist()))
                later(10)
                fillVerifyAndClaim(sys.read(rx), tech)?.let { toCounter(it, walter) }
            }
            10 -> {
                // a second oxycodone fill on a prescription that allows none: answered on record, and the prescriber asked
                val harold = patient("Harold Finch")
                regimens.first { it.patient == harold && it.regimen.drug == "Oxycodone" }.let { rx ->
                    fillVerifyAndClaim(sys.read(rx.ref), technicians.pick(), 30)
                    later(5)
                    ok(sys.commit(NewRequestRefill(key("RFL"), rx.ref)))
                    rx.nextFill = null
                    reauthorise(rx)
                }
            }
            12 -> {
                // a direction typed wrong on a new order, corrected before the fill; and a correction refused on a filled one
                val kwame = patient("Kwame Asante")
                val tech = technicians.pick()
                val o = order(kwame, kwame.prescriber, tech, today, listOf(line(Roster.Regimen("Prednisone", 21, 7, 0, "take 3 tablets daily"))))
                val rx = sys.read(o).prescriptions.single()
                later(8)
                ok(sys.commit(NewCorrectEntry(rx, "take three tablets by mouth daily for three days, then two for two days, then one for two days"), tech))
                later(5)
                fillVerifyAndClaim(sys.read(rx), tech)?.let { toCounter(it, kwame) }
                val filled = regimens.first { sys.read(it.ref).isFilledPrescription }
                ok(sys.commit(NewCorrectEntry(filled.ref, "take one tablet by mouth once daily with food"), tech))
            }
            15 -> {
                // an order keyed against the wrong patient, never filled: voided by a pharmacist; a void refused on a filled one
                val claire = patient("Claire Dubois")
                val tech = technicians.pick()
                val o = order(claire, prescribers[1], tech, today, listOf(line(Roster.Regimen("Metformin", 60, 30, 5, "take one tablet by mouth twice daily with meals"))))
                later(45)
                ok(sys.commit(NewVoidEntry(sys.read(o).prescriptions.single(), "entered against the wrong patient; re-entered for Kwame Asante"), licensedPharmacist()))
                val filled = regimens.first { sys.read(it.ref).isFilledPrescription }
                ok(sys.commit(NewVoidEntry(filled.ref, "duplicate"), licensedPharmacist()))
            }
            18 -> {
                // future-dated price changes: one stands, one is withdrawn next day; a withdrawal of a price in force is refused
                ok(sys.commit(NewPriceChange(drugs.getValue("Metformin"), today.plusDays(40), dec("0.10"))))
                ok(sys.commit(NewPriceChange(drugs.getValue("Lisinopril"), today.plusDays(30), dec("0.15"))))
                for ((name, price) in Roster.priceRises) ok(sys.commit(NewPriceChange(drugs.getValue(name), start.plusDays(40), dec(price))))
            }
            19 -> {
                val admin = admins.last()
                val future = sys.priceChanges().first { it.drug.key == drugs.getValue("Lisinopril").key && it.effectiveOn > today }
                ok(sys.commit(NewWithdrawPriceChange(future, "entered in error: the supplier's quote was for the 20 mg"), admin))
                later(4)
                val inForce = sys.priceChanges().first { it.drug.key == drugs.getValue("Amoxicillin").key && it.effectiveOn <= today }
                ok(sys.commit(NewWithdrawPriceChange(inForce, "trying to roll back last month's rise"), admin))
            }
            22 -> {
                ok(sys.commit(NewSetReorderPoint(drugs.getValue("Albuterol HFA"), 45), licensedPharmacist()))
                ok(sys.commit(NewSetReorderPoint(drugs.getValue("Oxycodone"), 120), admins.last()))
            }
            20 -> {
                // a patient on codeine from his own doctor gets tramadol from a pain clinic, both with refills left for
                // months: flagged for the pharmacist for as long as both stand, and refilled on its cadence like the rest
                val victor = patient("Victor Nakamura")
                orderRegimen(victor, listOf(Roster.Regimen("Tramadol", 60, 30, 5, "take one tablet by mouth every twelve hours as needed for pain")), prescriber = prescribers[4])
            }
            24 -> {
                // a refill that has aged out: a fill requested against the expired levothyroxine order is answered on record
                val old = sys.prescriptions().first { it.isExpired && sys.read(it.drug).name == "Levothyroxine" }
                fillVerifyAndClaim(old, technicians.pick())
            }
            28 -> {
                // the pharmacist rejects a fill: the wrong strength was pulled. The technician fills it again
                val rx = regimens.filter { it.regimen.drug == "Metformin" }.first().also { it.nextFill = today.plusDays(28) }
                val p = sys.read(rx.ref)
                val lot = lotFor(p.drug, p.quantity) ?: return
                val tech = technicians.pick()
                ok(sys.commit(NewFillPrescription(key("FILL"), rx.ref, lot, p.quantity, p.fillsRecorded + 1), tech))
                later(10)
                val fill = sys.read(rx.ref).fills.map { sys.read(it) }.maxBy { it.fillNumber }
                ok(sys.commit(NewVerifyFill(fill, "rejected", "500 mg ER pulled for a 500 mg IR order; returned to shelf"), licensedPharmacist(notThis = tech)))
                later(20)
                fillVerifyAndClaim(sys.read(rx.ref), tech)?.let { toCounter(it, rx.patient) }
            }
            30 -> {
                // a recall on a lisinopril lot that has been dispensed from: quarantined at once, every patient told
                val lot = sys.read(drugs.getValue("Lisinopril")).stockLots.map { sys.read(it) }.first { it.fills.isNotEmpty() }
                ok(sys.commit(NewRecall(lot, "FDA-${today.year}-0142", today.minusDays(1))))
            }
            33 -> {
                // two patients enrol in automatic refills
                for ((name, drug) in listOf("Eleanor Vance" to "Lisinopril", "Peter Lindgren" to "Atorvastatin")) {
                    regimens.firstOrNull { it.patient.spec.name == name && it.regimen.drug == drug }?.let { rx ->
                        ok(sys.commit(NewAutoRefillEnrolment(rx.ref)))
                        rx.autoRefill = true
                    }
                }
            }
            35 -> {
                // an antibiotic filled and waiting at the counter when the patient reports a new allergy to it
                val isabel = patient("Isabel Fonseca")
                val tech = technicians.pick()
                val o = order(isabel, isabel.prescriber, tech, today, listOf(line(Roster.Regimen("Sulfamethoxazole-Trimethoprim", 14, 7, 0, "take one tablet by mouth twice daily for seven days"))))
                val fill = fillVerifyAndClaim(sys.read(sys.read(o).prescriptions.single()), tech)
                later(90)
                ok(sys.commit(NewAddAllergy(isabel.ref, ingredients.getValue("sulfamethoxazole"))))
                if (fill != null) counter += Waiting(fill, isabel.spec.name, null) // never collected: returned to stock after ten days
            }
            60 -> {
                // an administrator records a pharmacist's licence renewal before it lapses
                val priya = pharmacists.entries.first { staffNames[it.key] == "Priya Raman" }.key
                ok(sys.commit(NewLicenceRenewal(priya, "RPH-${18000 + priya.key.value.toString().toInt() * 37}", today.plusDays(365)), admins.last()))
                pharmacists[priya] = today.plusDays(365)
            }
            66 -> {
                val yuki = patient("Yuki Sato")
                ok(sys.commit(NewSetAllergies(yuki.ref, listOf(ingredients.getValue("latex"), ingredients.getValue("shellfish")))))
            }
            70 -> {
                regimens.firstOrNull { it.patient.spec.name == "Peter Lindgren" && it.autoRefill }?.let { rx ->
                    ok(sys.commit(NewAutoRefillCancellation(rx.ref)))
                    rx.autoRefill = false
                }
            }
            75 -> {
                val lots = sys.stockLots().filter { it.isUsableLot }
                countLot(lots[4], lots[4].onHand)
                countLot(lots[9], lots[9].onHand - 2)
                countLot(lots[13], lots[13].onHand + 1)
            }
        }
    }
}
