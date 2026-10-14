package velle.generated.peptides

import java.math.BigDecimal
import velle.generated.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import velle.Commit
import velle.generated.PeptidesSystem

/**
 * The business flows of the peptides pipeline, spelled once and shared by the
 * generated specs' givens and the hand-written pipeline test: reference data,
 * a lab experiment run to a hit, a greenhouse trial laid out, treated, scored
 * to its final day, and a field trial requested, permitted, planted, treated,
 * scouted, and harvested.
 *
 * Numbers used throughout, so a reader can check the derivations by hand:
 *   · lab hit — two replicates peaking at 70 and 75 percent inhibition against
 *     a 60 percent threshold
 *   · greenhouse curves over days 0, 7, 14 — control 10/40/80 (area 595),
 *     peptide 5/10/20 (area 157.5): efficacy (595 - 157.5) / 595 = 73.5 %
 *   · field yields — peptide 5000, control 4000 kg/ha: a 25 % gain against a
 *     10 % threshold
 *
 * The system clock starts 2026-01-01T09:00Z; `today` is that instant's UTC date.
 */
class Scenario(val sys: PeptidesSystem) {

    fun accept(r: Commit<*>) { check(r is Commit.Accepted<*>) { "refused: $r" } }

    val now: Instant get() = sys.system.now
    val today: LocalDate get() = LocalDate.ofInstant(sys.system.now, ZoneOffset.UTC)

    fun dec(s: String): BigDecimal = BigDecimal(s)

    // ── reference data ───────────────────────────────────────────────────────

    fun crop(name: String = "wheat"): Crop {
        accept(sys.commit(NewCrop(name))); return sys.crops().last()
    }

    fun pathogen(crop: Crop, name: String = "Fusarium graminearum"): Pathogen {
        accept(sys.commit(NewPathogen(name, crop))); return sys.pathogens().last()
    }

    fun peptide(code: String = "PEP-001"): Peptide {
        accept(sys.commit(NewPeptide(code, "GIGKFLHSAKKFGKAFVGEIMNS"))); return sys.peptides().last()
    }

    // ── lab ──────────────────────────────────────────────────────────────────

    fun assayProtocol(durationHours: Int = 48, replicates: Int = 2, hitThreshold: String = "60"): AssayProtocol {
        accept(sys.commit(NewAssayProtocol("broth microdilution", durationHours, replicates, dec(hitThreshold))))
        return sys.assayProtocols().last()
    }

    fun experiment(
        peptide: Peptide,
        pathogen: Pathogen,
        protocol: AssayProtocol = assayProtocol(),
    ): LabExperiment {
        accept(sys.commit(NewLabExperiment(peptide, pathogen, protocol))); return sys.labExperiments().last()
    }

    /** One replicate plate read at the given hour marks: (hour, percent inhibition). */
    fun assay(experiment: LabExperiment, replicate: Int, readings: List<Pair<Int, String>>): Assay {
        accept(sys.commit(NewAssay(experiment, replicate)))
        val plate = sys.assays().last()
        for ((hour, pct) in readings) accept(sys.commit(NewAssayReading(plate, hour, dec(pct))))
        return plate
    }

    class Lab(val crop: Crop, val pathogen: Pathogen, val peptide: Peptide, val experiment: LabExperiment)

    /** A peptide with a standing lab hit: two complete replicates peaking at 70 and 75. */
    fun labHit(peptideCode: String = "PEP-001"): Lab {
        val crop = crop()
        val pathogen = pathogen(crop)
        val peptide = peptide(peptideCode)
        val experiment = experiment(peptide, pathogen)
        assay(experiment, 1, listOf(0 to "0", 24 to "70", 48 to "65"))
        assay(experiment, 2, listOf(0 to "0", 24 to "60", 48 to "75"))
        return Lab(crop, pathogen, peptide, experiment)
    }

    // ── greenhouse ───────────────────────────────────────────────────────────

    fun greenhouse(name: String = "GH-1", min: String = "18", max: String = "28"): Greenhouse {
        accept(sys.commit(NewGreenhouse(name, dec(min), dec(max)))); return sys.greenhouses().last()
    }

    fun greenhouseProtocol(plotsPerArm: Int = 1, finalDay: Int = 14, efficacyThreshold: String = "50"): GreenhouseProtocol {
        accept(sys.commit(NewGreenhouseProtocol("detached-leaf challenge", plotsPerArm, finalDay, dec(efficacyThreshold))))
        return sys.greenhouseProtocols().last()
    }

    fun reading(greenhouse: Greenhouse, temperature: String, at: Instant = now) {
        accept(sys.commit(NewClimateReading(greenhouse, at, dec(temperature), dec("65"))))
    }

    class Trial(val lab: Lab, val greenhouse: Greenhouse, val trial: GreenhouseTrial) {
        lateinit var treated: GreenhousePlot
        lateinit var control: GreenhousePlot
    }

    /** A greenhouse trial approved against the lab hit, not yet laid out. */
    fun approvedTrial(lab: Lab = labHit(), greenhouse: Greenhouse = greenhouse(), key: String = "GH-REQ-1"): Trial {
        accept(sys.commit(NewRequestGreenhouseTrial(key, lab.peptide, lab.pathogen, greenhouse, greenhouseProtocol())))
        return Trial(lab, greenhouse, sys.greenhouseTrials().last())
    }

    fun plot(t: Trial, bench: String, treatment: String): GreenhousePlot {
        accept(sys.commit(NewGreenhousePlot(t.trial, bench, treatment))); return sys.greenhousePlots().last()
    }

    /** One plot per arm. */
    fun laidOut(t: Trial = approvedTrial()): Trial {
        t.treated = plot(t, "A1", "peptide")
        t.control = plot(t, "B1", "control")
        return t
    }

    fun treat(t: Trial, at: Instant = now): Trial {
        accept(sys.commit(NewTreatment(t.trial, at))); return t
    }

    fun observe(plot: GreenhousePlot, day: Int, severity: String, on: LocalDate = today): PlotObservation {
        accept(sys.commit(NewPlotObservation(plot, day, dec(severity), on))); return sys.plotObservations().last()
    }

    /** Laid out, treated, and scored on days 0, 7, and 14 — the curves in the class comment. */
    fun observedToEnd(t: Trial = treat(laidOut()), controlCurve: List<String> = listOf("10", "40", "80"), peptideCurve: List<String> = listOf("5", "10", "20")): Trial {
        val days = listOf(0, 7, 14)
        for (i in days.indices) {
            observe(t.control, days[i], controlCurve[i], today.plusDays(days[i].toLong()))
            observe(t.treated, days[i], peptideCurve[i], today.plusDays(days[i].toLong()))
        }
        return t
    }

    /** A complete trial whose result is on record. Effective by default; a flat control curve makes it fail. */
    fun evaluatedTrial(effective: Boolean = true, lab: Lab = labHit()): Trial =
        if (effective) observedToEnd(treat(laidOut(approvedTrial(lab))))
        else observedToEnd(treat(laidOut(approvedTrial(lab))), controlCurve = listOf("10", "40", "80"), peptideCurve = listOf("10", "40", "80"))

    // ── field ────────────────────────────────────────────────────────────────

    fun site(name: String = "Dodge County north"): FieldSite {
        accept(sys.commit(NewFieldSite(name, "NE"))); return sys.fieldSites().last()
    }

    fun permit(site: FieldSite, crop: Crop, from: LocalDate = today, until: LocalDate = today.plusDays(365)) {
        accept(sys.commit(NewPermit(site, crop, from, until)))
    }

    fun fieldProtocol(): FieldProtocol {
        accept(sys.commit(NewFieldProtocol("small-plot efficacy", dec("10")))); return sys.fieldProtocols().last()
    }

    class Field(val gh: Trial, val site: FieldSite, val trial: FieldTrial) {
        lateinit var treated: FieldPlot
        lateinit var control: FieldPlot
    }

    /** A field trial approved against an effective greenhouse result, with a site but no permit yet. */
    fun approvedFieldTrial(gh: Trial = evaluatedTrial(), key: String = "FT-REQ-1"): Field {
        val site = site()
        accept(sys.commit(NewRequestFieldTrial(key, gh.lab.peptide, gh.lab.pathogen, gh.lab.crop, site, fieldProtocol())))
        return Field(gh, site, sys.fieldTrials().last())
    }

    fun fieldPlots(f: Field): Field {
        accept(sys.commit(NewFieldPlot(f.trial, 1, "peptide"))); f.treated = sys.fieldPlots().last()
        accept(sys.commit(NewFieldPlot(f.trial, 1, "control"))); f.control = sys.fieldPlots().last()
        return f
    }

    /** Permitted, planted today, and laid out in one block. */
    fun plantedFieldTrial(f: Field = approvedFieldTrial()): Field {
        permit(f.site, f.gh.lab.crop)
        accept(sys.commit(NewPlantTrial(f.trial, today, "LOT-7")))
        return fieldPlots(f)
    }

    // ── collection tasks ─────────────────────────────────────────────────────

    fun trial(t: GreenhouseTrial): velle.generated.Trial = t
    fun trial(t: FieldTrial): velle.generated.Trial = t

    /** An admin schedules collection at a day mark, due on a date. */
    fun task(trial: velle.generated.Trial, dayMark: Int, dueOn: LocalDate): CollectionTask {
        accept(sys.commit(NewCollectionTask(trial.ref, dayMark, dueOn))); return sys.collectionTasks().last()
    }

    /** A treated greenhouse trial with a day-7 task that fell due yesterday and has no scores. */
    fun overdueTask(): CollectionTask {
        val t = treat(laidOut())
        return task(trial(t.trial), 7, today.minusDays(1))
    }

    /** A greenhouse trial scored to its end, with a day-14 task that is therefore done. */
    fun doneTask(): CollectionTask {
        val t = observedToEnd()
        return task(trial(t.trial), 14, today.plusDays(14))
    }

    fun scout(plot: FieldPlot, day: Int, severity: String, on: LocalDate = today) {
        accept(sys.commit(NewScouting(plot, day, on, dec("30"), dec(severity))))
    }

    fun harvest(plot: FieldPlot, yieldKgPerHa: String, on: LocalDate = today) {
        accept(sys.commit(NewHarvest(plot, on, dec(yieldKgPerHa))))
    }
}
