package velle.api.scenario

import java.time.Duration

/**
 * A scenario for `velle try`: the steps of an example a person gives, run in order on a system that
 * starts empty. Each step carries its [ScenarioStep.number], counting from 1, for the report and for
 * a problem's message. A reference to a record is the name an earlier step gave it with `as`.
 */
sealed interface ScenarioStep {
    val number: Int

    /** Commit [act] with [fields], the body the API layer's commit takes, a reference written as a name; named [name]; by the record named [actor]. */
    data class Commit(override val number: Int, val act: String, val fields: Map<String, Any?>, val name: String?, val actor: String?) : ScenarioStep

    /** Name [name] the one record of [root] (a shape or a refinement) that [where], a Velle condition, holds for. */
    data class Find(override val number: Int, val root: String, val where: String, val name: String) : ScenarioStep

    /** Move the clock forward by [duration], written [written] in the scenario. */
    data class Pass(override val number: Int, val written: String, val duration: Duration) : ScenarioStep

    /** Run every rule on [schedule]. */
    data class Tick(override val number: Int, val schedule: String) : ScenarioStep

    /** Show the record named [name], or every record of [root] that [where] holds for. */
    data class Show(override val number: Int, val name: String?, val root: String?, val where: String?) : ScenarioStep
}

/** What is wrong with a scenario, at the step numbered [step], or with the whole scenario when null. */
class ScenarioProblem(val step: Int?, val problem: String) : IllegalArgumentException(problem) {
    /** "Step 3: ..." or the problem alone. */
    val sentence: String get() = if (step == null) problem else "Step $step: $problem"
}
