package velle.api.scenario

import velle.CommandOutcome
import velle.Draft
import java.io.File

/**
 * `velle try <draft.velle> <scenario.json>`: the scenario's steps run on the draft, reported as
 * Markdown. It reads the two files, then works on their text, which the authoring MCP server hands
 * it directly ([runText]). A draft that does not validate is refused as `velle validate` refuses it;
 * a scenario with a problem stops at that step, with the report of the steps before it.
 */
object TryCommand {

    fun run(specPath: String, scenarioPath: String): CommandOutcome {
        val specFile = File(specPath)
        if (!specFile.isFile) return CommandOutcome.refused("There is no file at `$specPath`.\n")
        val scenarioFile = File(scenarioPath)
        if (!scenarioFile.isFile) return CommandOutcome.refused("There is no file at `$scenarioPath`.\n")
        return runText(specPath, specFile.readText(), scenarioPath, scenarioFile.readText())
    }

    /** [run] for a spec and a scenario given as text, called [specName] and [scenarioName] in the report. */
    fun runText(specName: String, specSource: String, scenarioName: String, scenarioText: String): CommandOutcome {
        val version = when (val draft = Draft.of(specName, specSource)) {
            is Draft.Valid -> draft.version
            is Draft.Refused -> return CommandOutcome.refused(draft.report)
        }
        val steps = try {
            ScenarioReading.steps(scenarioText)
        } catch (problem: ScenarioProblem) {
            return CommandOutcome.refused("`$scenarioName` cannot be run. ${problem.sentence}\n")
        }
        val report = ScenarioRun(version, scenarioName).report(steps)
        return if (report.stopped) CommandOutcome.refused(report.text) else CommandOutcome.done(report.text)
    }
}
