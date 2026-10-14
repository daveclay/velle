package velle.generated.peptides

import velle.generated.PeptidesSystem
import velle.generated.*

/**
 * The human-owned scenarios the generated peptides specs demand (testgen.md):
 * how to *reach* each interesting state is business judgment the spec doesn't
 * contain. Every flow is spelled once in [Scenario]; this class only says
 * which flow each generated case starts from.
 */
class Givens(private val sys: PeptidesSystem) : RequiredGivens {

    private val s = Scenario(sys)

    // ── lab ──────────────────────────────────────────────────────────────────

    override fun labExperimentForRecordLabHit(): LabExperiment = s.labHit().experiment

    override fun hitExperiment(): LabExperiment = s.labHit().experiment

    /** A third replicate that never inhibits drags the mean below the threshold. */
    override fun exitHitExperiment(labExperiment: LabExperiment) {
        s.assay(labExperiment, 3, listOf(0 to "0", 48 to "0"))
    }

    // ── greenhouse ───────────────────────────────────────────────────────────

    override fun approvedGreenhouseRequest() {
        s.approvedTrial()
    }

    override fun refusedGreenhouseRequest() {
        val crop = s.crop()
        val pathogen = s.pathogen(crop)
        val peptide = s.peptide("PEP-UNTESTED")
        s.accept(sys.commit(NewRequestGreenhouseTrial("GH-REQ-REFUSED", peptide, pathogen, s.greenhouse(), s.greenhouseProtocol())))
    }

    override fun greenhouseTrialForRecordGreenhouseResult(): GreenhouseTrial = s.observedToEnd().trial

    override fun applicableCorrection() {
        val t = s.treat(s.laidOut())
        val obs = s.observe(t.control, 0, "12")
        s.accept(sys.commit(NewCorrectObservation(obs, s.dec("15"), "misread scale")))
    }

    override fun refusedCorrection() {
        val t = s.observedToEnd()
        s.accept(sys.commit(NewCorrectObservation(sys.plotObservations().last(), s.dec("15"), "too late")))
    }

    override fun applicableVoid() {
        val t = s.treat(s.laidOut())
        val obs = s.observe(t.control, 0, "12")
        s.accept(sys.commit(NewVoidObservation(obs, "wrong plot")))
    }

    override fun refusedVoid() {
        s.observedToEnd()
        s.accept(sys.commit(NewVoidObservation(sys.plotObservations().last(), "too late")))
    }

    override fun applicableMove() {
        val t = s.laidOut()
        s.accept(sys.commit(NewMovePlot(t.control, "B2")))
    }

    override fun refusedMove() {
        val t = s.treat(s.laidOut())
        s.accept(sys.commit(NewMovePlot(t.control, "B2")))
    }

    override fun greenhouseForOpenClimateExcursion(): Greenhouse {
        val gh = s.greenhouse()
        s.reading(gh, "31")
        return gh
    }

    /** Out of band, then a reading back inside the band: the open excursion becomes closable. */
    override fun closableExcursion(): ClimateExcursion {
        val gh = s.greenhouse()
        s.reading(gh, "31")
        val excursion = sys.climateExcursions().last()
        sys.advanceSeconds(600)
        s.reading(gh, "24")
        return excursion
    }

    /** An active trial whose greenhouse has logged nothing at all. */
    override fun greenhouseForRaiseSensorAlert(): Greenhouse {
        val t = s.treat(s.laidOut())
        return t.greenhouse
    }

    // ── field ────────────────────────────────────────────────────────────────

    override fun approvedFieldRequest() {
        s.approvedFieldTrial()
    }

    override fun refusedFieldRequest() {
        val crop = s.crop()
        val pathogen = s.pathogen(crop)
        val peptide = s.peptide("PEP-UNTESTED")
        s.accept(sys.commit(NewRequestFieldTrial("FT-REQ-REFUSED", peptide, pathogen, crop, s.site(), s.fieldProtocol())))
    }

    override fun plantTrial() {
        s.plantedFieldTrial()
    }

    override fun applyTreatment() {
        val f = s.plantedFieldTrial()
        s.accept(sys.commit(NewApplyTreatment(f.trial, s.today, s.dec("50"))))
    }

    override fun fieldTrialForNoticeHalt(): FieldTrial {
        val f = s.fieldPlots(s.approvedFieldTrial())
        s.accept(sys.commit(NewPhytotoxicityReport(f.treated, s.today, 3)))
        return f.trial
    }

    /** Sprayed today; six millimetres of rain tomorrow washes it off. */
    override fun treatmentApplicationForNoticeWashOff(): TreatmentApplication {
        val f = s.plantedFieldTrial()
        s.accept(sys.commit(NewApplyTreatment(f.trial, s.today, s.dec("50"))))
        val application = sys.treatmentApplications().last()
        s.accept(sys.commit(NewWeatherObservation(f.site, s.today.plusDays(1), s.dec("6"), s.dec("24"))))
        return application
    }

    // ── collection tasks ─────────────────────────────────────────────────────

    override fun collectionTaskForRemindTask(): CollectionTask = s.overdueTask()

    override fun applicableReschedule() {
        s.accept(sys.commit(NewRescheduleTask(s.overdueTask(), s.today.plusDays(3))))
    }

    override fun refusedReschedule() {
        s.accept(sys.commit(NewRescheduleTask(s.doneTask(), s.today.plusDays(3))))
    }

    override fun applicableCancel() {
        s.accept(sys.commit(NewCancelTask(s.overdueTask(), "trial abandoned")))
    }

    override fun refusedCancel() {
        s.accept(sys.commit(NewCancelTask(s.doneTask(), "too late")))
    }

    // ── any instance ─────────────────────────────────────────────────────────

    /** A plate with no readings yet, so any hour mark is free. */
    override fun someAssay(): Assay = s.assay(s.labHit().experiment, 3, emptyList())

    /** A plot on a treated trial, so a score at day 0 is accepted. */
    override fun someGreenhousePlot(): GreenhousePlot = s.treat(s.laidOut()).control

    override fun someFieldPlot(): FieldPlot = s.fieldPlots(s.approvedFieldTrial()).control

    override fun someTrial(): Trial = s.trial(s.approvedTrial().trial)
}
