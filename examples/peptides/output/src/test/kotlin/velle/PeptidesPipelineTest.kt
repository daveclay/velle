package velle.generated.peptides

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import velle.CommitResult
import velle.VelleRuntimeError
import velle.View
import velle.generated.PeptidesSystem

/**
 * The pipeline driven end-to-end through the generated typed surface — the
 * business-flow companion to the generated per-rule specs, and the place the
 * time-series behaviour the spec leans on is checked with real numbers: a
 * late-entered reading slotting into its hour mark, the area under a disease
 * curve by the trapezoid rule, a correction ledger, a void re-deriving the
 * curve, evidence fixed at the moment of evaluation while the live number
 * moves on, sensor episodes, silence alerts, admin-scheduled collection tasks
 * over both trial stages, a wash-off read off two series, and the stage gates
 * between lab, greenhouse, and field.
 */
open class PeptidesPipelineTest {

    /** The system under test; the storage-backed variants override this. */
    protected open fun newSystem(): PeptidesSystem = PeptidesSystem()
    protected val sys: PeptidesSystem by lazy { newSystem() }
    private val s: Scenario by lazy { Scenario(sys) }

    private fun accept(r: CommitResult) = assertIs<CommitResult.Accepted>(r, "refused: $r")
    private fun refuse(r: CommitResult) = assertTrue(r !is CommitResult.Accepted, "expected a refusal, got: $r")
    private fun View.isA(refinement: String) = sys.system.isMember(id, refinement)
    private fun BigDecimal.at(scale: Int) = setScale(scale, RoundingMode.HALF_UP)
    private fun dec(s: String) = BigDecimal(s)

    // ── lab ──────────────────────────────────────────────────────────────────

    @Test
    fun `lab - a reading entered late slots into its hour mark and the running peak re-derives`() {
        val lab = s.labHit()
        val plate = s.assay(lab.experiment, 3, listOf(0 to "10", 48 to "30"))
        assertEquals(dec("30"), plate.peakInhibition, "peak over the two readings on file")

        // the 24-hour reading arrives after the 48-hour one; it is ordered by its hour, not its arrival
        accept(sys.commitAssayReading(plate, 24, dec("80")))
        val at48 = sys.assayReadings().first { it.assay == plate && it.hoursElapsed == 48 }
        assertEquals(24, at48.previous?.hoursElapsed, "the predecessor is the latest earlier hour mark")
        assertEquals(dec("80"), at48.runningPeak, "the successor's running peak re-derived")
        assertEquals(dec("80"), plate.peakInhibition)

        // a second reading at an hour already on file would leave the series unordered.
        // The uniqueness `never` is checked at transaction end, but the rules watching
        // the series evaluate `latest ... by hoursElapsed` first and fail loudly on the
        // tie (README §10) — so today the caller gets a runtime error rather than a
        // refusal; the transaction rolls back either way
        assertFailsWith<VelleRuntimeError> { sys.commitAssayReading(plate, 24, dec("81")) }
        assertEquals(3, plate.assayReadings.size, "nothing landed")
        assertEquals(dec("80"), plate.peakInhibition)
    }

    @Test
    fun `lab - a hit is recorded once, retracted when a late replicate drags the mean down, and not re-recorded on re-entry`() {
        val lab = s.labHit()
        val experiment = lab.experiment
        assertTrue(experiment.isA("HitExperiment"))
        assertEquals(dec("72.5"), experiment.meanPeakInhibition.at(1))
        assertEquals(1, sys.labHits().size)
        assertEquals("lab-validated", lab.peptide.stage)

        // a third plate that never inhibits: mean (70 + 75 + 0) / 3 < 60
        s.assay(experiment, 3, listOf(0 to "0", 48 to "0"))
        assertFalse(experiment.isA("HitExperiment"))
        assertEquals(1, sys.labHitRetractions().size, "`when leaving`: the retraction is on record")
        assertEquals("candidate", lab.peptide.stage)

        // a fourth plate at 100 restores the mean: (70 + 75 + 0 + 100) / 4 >= 60
        s.assay(experiment, 4, listOf(0 to "0", 48 to "100"))
        assertTrue(experiment.isA("HitExperiment"))
        assertEquals(1, sys.labHits().size, "once ever: the hit already on file is not recorded again")
        assertEquals(dec("72.5"), sys.labHits().single().meanPeakInhibition.at(1), "the mean as it stood when first recorded")
    }

    // ── greenhouse ───────────────────────────────────────────────────────────

    @Test
    fun `greenhouse - a request is refused without a lab hit and approved with one`() {
        val crop = s.crop()
        val pathogen = s.pathogen(crop)
        val untested = s.peptide("PEP-UNTESTED")
        val greenhouse = s.greenhouse()
        val protocol = s.greenhouseProtocol()
        accept(sys.commitRequestGreenhouseTrial("REQ-A", untested, pathogen, greenhouse, protocol))
        assertEquals("REQ-A", sys.greenhouseRefusals().single().requestKey, "the refusal carries the request key back")
        assertEquals(0, sys.greenhouseTrials().size)

        val lab = s.labHit("PEP-002")
        accept(sys.commitRequestGreenhouseTrial("REQ-B", lab.peptide, lab.pathogen, greenhouse, protocol))
        assertEquals("REQ-B", sys.greenhouseTrials().single().requestKey)
        assertEquals(1, sys.greenhouseRefusals().size)
    }

    @Test
    fun `greenhouse - the disease curve accumulates by the trapezoid rule and completion records the efficacy as it stood`() {
        val t = s.treat(s.laidOut())
        assertTrue(t.trial.isA("LaidOutTrial"))
        assertFalse(t.trial.isA("CompleteTrial"))

        // no score before treatment is representable: the boundary refuses it
        val untreated = s.laidOut(s.approvedTrial(t.lab, t.greenhouse, "GH-REQ-2"))
        refuse(sys.commitPlotObservation(untreated.control, 0, dec("5"), s.today))

        s.observedToEnd(t)
        val control = t.control
        assertEquals(dec("595.0"), control.audpc.at(1), "(10+40)/2*7 + (40+80)/2*7")
        assertEquals(dec("157.5"), t.treated.audpc.at(1), "(5+10)/2*7 + (10+20)/2*7")
        assertEquals(dec("73.53"), t.trial.efficacy.at(2))
        assertTrue(t.trial.isA("CompleteTrial"))
        assertTrue(t.trial.isA("EffectiveTrial"))
        assertEquals("greenhouse-validated", t.lab.peptide.stage)

        val result = sys.greenhouseResults().single()
        assertEquals(dec("73.53"), result.efficacy.at(2))
        assertTrue(result.effective)

        // the trial is evaluated: a correction on any of its scores is refused as data
        val day14 = sys.plotObservations().first { it.plot == control && it.dayAfterTreatment == 14 }
        accept(sys.commitCorrectObservation(day14, dec("60"), "second look"))
        assertEquals("the trial has been evaluated", sys.correctionRefusals().single().reason)
        assertEquals(dec("80"), day14.diseaseSeverity)

        // a second control plot scored flat moves the live efficacy; the recorded verdict stands
        val lateControl = s.plot(t, "B2", "control")
        for (day in listOf(0, 7, 14)) s.observe(lateControl, day, "0")
        assertEquals(dec("297.5"), t.trial.meanControlAudpc.at(1), "(595 + 0) / 2")
        assertEquals(dec("47.06"), t.trial.efficacy.at(2), "the live number moved")
        assertFalse(t.trial.isA("EffectiveTrial"), "and the live state with it")
        assertEquals(dec("73.53"), sys.greenhouseResults().single().efficacy.at(2), "the record did not")
        assertEquals("greenhouse-validated", t.lab.peptide.stage)
    }

    @Test
    fun `greenhouse - a correction is a ledger entry the curve reads, never an overwrite`() {
        val t = s.treat(s.laidOut())
        s.observe(t.control, 0, "10")
        val day7 = s.observe(t.control, 7, "40")
        s.observe(t.control, 14, "80")
        assertEquals(dec("595.0"), t.control.audpc.at(1))

        accept(sys.commitCorrectObservation(day7, dec("50"), "misread scale"))
        val correction = sys.observationCorrections().single()
        assertEquals(dec("40"), correction.previousSeverity, "the value it replaced, read before it landed")
        assertEquals(dec("40"), day7.recordedSeverity, "what was committed stays committed")
        assertEquals(dec("50"), day7.diseaseSeverity, "the score in force is the latest correction")
        assertEquals(dec("665.0"), t.control.audpc.at(1), "(10+50)/2*7 + (50+80)/2*7 — the curve re-derived")

        // the ledger orders by the correction's commit timestamp, so a second correction
        // needs a later clock instant — the harness clock only moves when advanced
        sys.advanceSeconds(60)
        accept(sys.commitCorrectObservation(day7, dec("45"), "third look"))
        assertEquals(dec("45"), day7.diseaseSeverity)
        assertEquals(2, day7.observationCorrections.size)
    }

    @Test
    fun `greenhouse - voiding a mistaken score removes it and the curve re-derives over the survivors`() {
        val t = s.treat(s.laidOut())
        s.observe(t.control, 0, "10")
        val wrongPlot = s.observe(t.control, 7, "95")
        s.observe(t.control, 14, "80")
        val day14 = sys.plotObservations().last()
        assertEquals(7, day14.previous?.dayAfterTreatment)

        accept(sys.commitVoidObservation(wrongPlot, "scored against the wrong plot"))
        assertEquals(2, t.control.plotObservations.size)
        assertEquals(0, day14.previous?.dayAfterTreatment, "the chain closed over the gap")
        assertEquals(dec("630.0"), t.control.audpc.at(1), "(10+80)/2*14")
        val void = sys.voidRecords().single()
        assertEquals("B1", void.bench)
        assertEquals(dec("95"), void.diseaseSeverity, "what was removed, copied out")

        // once evaluated, a void is refused as data and the row stays
        s.observe(t.treated, 0, "5"); s.observe(t.treated, 7, "10"); s.observe(t.treated, 14, "20")
        assertTrue(t.trial.isA("EvaluatedTrial"))
        accept(sys.commitVoidObservation(day14, "too late"))
        assertEquals("the trial has been evaluated", sys.voidRefusals().single().reason)
        assertEquals(2, t.control.plotObservations.size)
    }

    @Test
    fun `greenhouse - plots move between benches until treatment, then the bench is frozen`() {
        val t = s.laidOut()
        accept(sys.commitMovePlot(t.control, "B2"))
        assertEquals("B2", t.control.bench)

        s.treat(t)
        accept(sys.commitMovePlot(t.control, "B3"))
        assertEquals("B2", t.control.bench, "frozen by TreatedPlot")
        assertEquals("the trial has been treated", sys.moveRefusals().single().reason)
    }

    @Test
    fun `greenhouse - climate excursions are episodes, a treated trial that lived through one is compromised, and silent sensors raise one alert per two hours`() {
        val t = s.treat(s.laidOut())
        val gh = t.greenhouse
        s.reading(gh, "24")
        assertFalse(gh.isA("OutOfBand"))

        sys.advanceSeconds(3600)
        s.reading(gh, "31")
        assertTrue(gh.isA("OutOfBand"))
        val excursion = sys.climateExcursions().single()
        assertEquals(dec("31"), excursion.temperature)
        assertTrue(excursion.isA("OpenExcursion"))
        assertTrue(t.trial.isA("CompromisedTrial"))

        // still out of band: the same episode, not a second one
        sys.advanceSeconds(3600)
        s.reading(gh, "32")
        assertEquals(1, sys.climateExcursions().size)

        // back in band: the closer runs as its own transaction right after the reading
        sys.advanceSeconds(3600)
        s.reading(gh, "25")
        assertFalse(gh.isA("OutOfBand"))
        assertEquals(1, sys.excursionEnds().size)
        assertFalse(excursion.isA("OpenExcursion"))
        sys.tickHourly()
        assertEquals(1, sys.excursionEnds().size, "the backstop finds nothing left to close")

        // a second stretch out of band is a second episode
        sys.advanceSeconds(3600)
        s.reading(gh, "17")
        assertEquals(2, sys.climateExcursions().size)

        // the sensors go quiet while the trial is active
        sys.advanceSeconds(3 * 3600)
        assertTrue(gh.isA("SilentGreenhouse"))
        sys.tickHourly()
        assertEquals(1, sys.sensorAlerts().size)
        sys.tickHourly()
        assertEquals(1, sys.sensorAlerts().size, "at most one alert per two hours")
        sys.advanceSeconds(3 * 3600)
        sys.tickHourly()
        assertEquals(2, sys.sensorAlerts().size)

        // a reading resumes: no longer silent
        s.reading(gh, "24")
        assertFalse(gh.isA("SilentGreenhouse"))
    }

    @Test
    fun `tasks - an admin schedules when scores are collected - a task is done when every plot has data at its mark and nagged daily while overdue`() {
        val t = s.treat(s.laidOut())
        val day0 = s.task(s.trial(t.trial), 0, s.today)
        val day7 = s.task(s.trial(t.trial), 7, s.today.plusDays(7))
        assertEquals(2, day0.plotsPending)
        assertTrue(day0.isA("OpenTask"))
        assertFalse(day0.isA("OverdueTask"), "due today is not overdue")

        s.observe(t.control, 0, "10")
        assertEquals(1, day0.plotsPending, "one plot still without a day-0 score")
        s.observe(t.treated, 0, "5")
        assertTrue(day0.isA("DoneTask"))
        assertEquals(2, day7.plotsPending, "a day-0 score is no day-7 score")

        // the day-7 task falls due and nobody scores: one reminder per day, not per tick
        sys.advanceDays(8)
        assertTrue(day7.isA("OverdueTask"))
        sys.tickDaily()
        sys.tickDaily()
        assertEquals(1, sys.taskReminders().size)
        assertEquals(day7, sys.taskReminders().single().task)
        sys.advanceDays(1)
        sys.tickDaily()
        assertEquals(2, sys.taskReminders().size)

        // a score past the mark counts for it: day 9 satisfies the day-7 task
        s.observe(t.control, 9, "35"); s.observe(t.treated, 9, "8")
        assertTrue(day7.isA("DoneTask"))
        sys.advanceDays(1)
        sys.tickDaily()
        assertEquals(2, sys.taskReminders().size, "done tasks are not nagged")
    }

    @Test
    fun `tasks - the admin can reschedule or cancel a task until it is done`() {
        val t = s.treat(s.laidOut())
        val open = s.task(s.trial(t.trial), 7, s.today.plusDays(7))
        val done = s.task(s.trial(t.trial), 0, s.today)
        s.observe(t.control, 0, "10"); s.observe(t.treated, 0, "5")
        assertTrue(done.isA("DoneTask"))

        accept(sys.commitRescheduleTask(open, s.today.plusDays(10)))
        assertEquals(s.today.plusDays(10), open.dueOn)
        accept(sys.commitRescheduleTask(done, s.today.plusDays(10)))
        assertEquals(s.today, done.dueOn, "frozen by DoneTask")
        assertEquals("the task is done", sys.rescheduleRefusals().single().reason)

        accept(sys.commitCancelTask(done, "too late"))
        assertEquals(2, sys.collectionTasks().size, "undeletable while done")
        assertEquals(done, sys.cancelRefusals().single().task)

        sys.advanceDays(11)
        sys.tickDaily()
        assertEquals(1, sys.taskReminders().size)
        accept(sys.commitCancelTask(open, "trial abandoned"))
        assertEquals(listOf(done), sys.collectionTasks(), "the open task is gone")
        val cancellation = sys.taskCancellations().single()
        assertEquals(7, cancellation.dayMark)
        assertEquals(s.today.minusDays(1), cancellation.dueOn, "what was cancelled, copied out")
        assertNull(sys.taskReminders().single().task, "the reminder's reference absorbed the deletion")
        sys.tickDaily()
        assertEquals(1, sys.taskReminders().size, "nothing left to nag")
    }

    @Test
    fun `tasks - one task shape serves both stages - a field task counts scoutings at its day mark`() {
        val f = s.plantedFieldTrial()
        val gh = f.gh
        val fieldTask = s.task(s.trial(f.trial), 10, s.today.plusDays(10))
        val greenhouseTask = s.task(s.trial(gh.trial), 14, s.today)
        assertEquals("FieldTrial", fieldTask.trial.shape)
        assertEquals(f.trial, fieldTask.trial.asFieldTrial())
        assertEquals(2, fieldTask.plotsPending, "the field trial's two plots, no greenhouse plot")
        assertEquals(0, greenhouseTask.plotsPending, "the greenhouse trial was scored to day 14 before the field stage")
        assertEquals(listOf(fieldTask), f.trial.let { sys.TrialView(it.id) }.collectionTasks, "the inverse inferred onto the operand")

        assertTrue(f.trial.isA("NeverScouted"))
        s.scout(f.treated, 10, "5")
        assertEquals(1, fieldTask.plotsPending)
        s.scout(f.control, 10, "30")
        assertTrue(fieldTask.isA("DoneTask"))
        assertFalse(f.trial.isA("NeverScouted"))
    }

    @Test
    fun `greenhouse - two complete ineffective trials deprioritize a peptide`() {
        val lab = s.labHit()
        s.evaluatedTrial(effective = false, lab = lab)
        assertFalse(lab.peptide.isA("Deprioritized"))
        s.evaluatedTrial(effective = false, lab = lab)
        assertTrue(lab.peptide.isA("Deprioritized"))
        assertEquals("lab-validated", lab.peptide.stage)
    }

    // ── field ────────────────────────────────────────────────────────────────

    @Test
    fun `field - the request needs an effective greenhouse result on record, and planting needs a permit in force`() {
        val gh = s.evaluatedTrial(effective = false)
        val site = s.site()
        val protocol = s.fieldProtocol()
        accept(sys.commitRequestFieldTrial("FT-A", gh.lab.peptide, gh.lab.pathogen, gh.lab.crop, site, protocol))
        assertEquals("FT-A", sys.fieldRefusals().single().requestKey)

        val effective = s.evaluatedTrial(effective = true, lab = gh.lab)
        assertTrue(effective.trial.isA("EffectiveTrial"))
        accept(sys.commitRequestFieldTrial("FT-B", gh.lab.peptide, gh.lab.pathogen, gh.lab.crop, site, protocol))
        val trial = sys.fieldTrials().single()
        assertFalse(trial.isA("PermittedTrial"))

        // no permit: planting must not land at all
        refuse(sys.commitPlantTrial(trial, s.today, "LOT-7"))
        assertEquals(0, sys.plantings().size)

        // an expired permit is no permit
        s.permit(site, gh.lab.crop, from = s.today.minusDays(400), until = s.today.minusDays(1))
        refuse(sys.commitPlantTrial(trial, s.today, "LOT-7"))

        s.permit(site, gh.lab.crop, until = s.today.plusDays(30))
        assertTrue(trial.isA("PermittedTrial"))
        accept(sys.commitPlantTrial(trial, s.today, "LOT-7"))
        assertTrue(trial.isA("PlantedTrial"))
        assertEquals(s.today, sys.plantings().single().plantedOn)

        // the permit lapses: the planting on record stands, and the system keeps
        // accepting unrelated work — the check was about the request's moment
        sys.advanceDays(31)
        assertFalse(trial.isA("PermittedTrial"))
        assertTrue(trial.isA("PlantedTrial"))
        accept(sys.commitFieldPlot(trial, 1, "control"))
        refuse(sys.commitPlantTrial(trial, s.today, "LOT-8"))
    }

    @Test
    fun `field - rain the day after spraying washes the peptide off, read off the site's weather series`() {
        val f = s.plantedFieldTrial()
        accept(sys.commitApplyTreatment(f.trial, s.today, dec("50")))
        val application = sys.treatmentApplications().last()
        assertFalse(application.isA("WashedOffApplication"))

        // rain on the day itself does not count; rain the next day does, if heavy enough
        accept(sys.commitWeatherObservation(f.site, s.today, dec("12"), dec("22")))
        accept(sys.commitWeatherObservation(f.site, s.today.plusDays(1), dec("2"), dec("24")))
        assertFalse(application.isA("WashedOffApplication"))
        assertEquals(0, sys.reapplicationNotices().size)

        // the next day's row is corrected upward: the same row cannot be entered twice
        refuse(sys.commitWeatherObservation(f.site, s.today.plusDays(1), dec("6"), dec("24")))

        accept(sys.commitApplyTreatment(f.trial, s.today.plusDays(3), dec("50")))
        val second = sys.treatmentApplications().last()
        accept(sys.commitWeatherObservation(f.site, s.today.plusDays(4), dec("6"), dec("24")))
        assertTrue(second.isA("WashedOffApplication"))
        assertEquals(second, sys.reapplicationNotices().single().application)
        accept(sys.commitWeatherObservation(f.site, s.today.plusDays(5), dec("9"), dec("24")))
        assertEquals(1, sys.reapplicationNotices().size, "once per washed-off application")
    }

    @Test
    fun `field - scouting keeps a running curve, a never-scouted trial is reported, and harvest yield gain validates the peptide`() {
        val f = s.plantedFieldTrial()
        assertTrue(f.trial.isA("NeverScouted"), "planted, never scouted")

        s.scout(f.treated, 0, "0")
        assertTrue(f.trial.isA("NeverScouted"), "one plot of two")
        s.scout(f.control, 0, "0")
        assertFalse(f.trial.isA("NeverScouted"))
        sys.advanceDays(10)
        s.scout(f.treated, 10, "5"); s.scout(f.control, 10, "30")
        assertEquals(dec("150.0"), f.control.scoutings.maxBy { it.daysAfterPlanting }.audpcSoFar.at(1), "(0+30)/2*10")
        refuse(sys.commitScouting(f.control, 10, s.today, dec("30"), dec("31")))

        assertFalse(f.trial.isA("HarvestedTrial"))
        s.harvest(f.treated, "5000")
        assertFalse(f.trial.isA("HarvestedTrial"), "every plot must be harvested")
        s.harvest(f.control, "4000")
        assertTrue(f.trial.isA("HarvestedTrial"))
        assertEquals(dec("25.00"), f.trial.yieldGain.at(2))
        assertTrue(f.trial.isA("SuccessfulFieldTrial"))
        assertTrue(f.gh.lab.peptide.isA("FieldValidated"))
        assertEquals("field-validated", f.gh.lab.peptide.stage)
    }

    @Test
    fun `field - a severe phytotoxicity report halts the trial and later applications are refused at the boundary`() {
        val f = s.plantedFieldTrial()
        accept(sys.commitPhytotoxicityReport(f.treated, s.today, 2))
        assertFalse(f.trial.isA("HaltedTrial"), "mild injury is noted, not a halt")
        accept(sys.commitApplyTreatment(f.trial, s.today, dec("50")))

        // the halting report lands although an application is on record: the
        // `never` was about the request's moment, not about history
        accept(sys.commitPhytotoxicityReport(f.treated, s.today.plusDays(2), 3))
        assertTrue(f.trial.isA("HaltedTrial"))
        assertEquals(1, sys.haltNotices().size)
        refuse(sys.commitApplyTreatment(f.trial, s.today.plusDays(3), dec("50")))
        assertEquals(1, sys.treatmentApplications().size)

        accept(sys.commitPhytotoxicityReport(f.control, s.today.plusDays(3), 4))
        assertEquals(1, sys.haltNotices().size, "one notice per halt, however many reports")
        refuse(sys.commitPhytotoxicityReport(f.control, s.today, 6))
    }

    @Test
    fun `pipeline - the peptide's stage is read off the evidence at every step`() {
        val crop = s.crop()
        val pathogen = s.pathogen(crop)
        val peptide = s.peptide()
        assertEquals("candidate", peptide.stage)
        val experiment = s.experiment(peptide, pathogen)
        s.assay(experiment, 1, listOf(0 to "0", 48 to "70"))
        assertEquals("candidate", peptide.stage, "one replicate of two")
        s.assay(experiment, 2, listOf(0 to "0", 48 to "75"))
        assertEquals("lab-validated", peptide.stage)

        val lab = Scenario.Lab(crop, pathogen, peptide, experiment)
        val gh = s.observedToEnd(s.treat(s.laidOut(s.approvedTrial(lab))))
        assertEquals("greenhouse-validated", peptide.stage)

        val f = s.plantedFieldTrial(s.approvedFieldTrial(gh))
        s.harvest(f.treated, "5000"); s.harvest(f.control, "4000")
        assertEquals("field-validated", peptide.stage)
        assertNull(sys.fieldRefusals().firstOrNull())
    }
}
