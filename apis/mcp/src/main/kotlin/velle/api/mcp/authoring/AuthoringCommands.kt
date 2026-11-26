package velle.api.mcp.authoring

import velle.Cli
import velle.CommandOutcome
import velle.api.scenario.TryCommand

/**
 * What each authoring tool calls, with the spec as text: the same functions the `velle` command calls
 * once it has read its files, so a tool answers exactly what the command prints. The tools' reading of
 * their arguments ([AuthoringTools]) stays apart from these calls.
 */
internal object AuthoringCommands {

    fun validate(fileName: String, spec: String): CommandOutcome = Cli.validateText(fileName, spec)

    fun compare(beforeName: String, before: String, afterName: String, after: String): CommandOutcome =
        Cli.compareText(beforeName, before, afterName, after)

    fun describe(fileName: String, spec: String, about: String?): CommandOutcome = Cli.describeText(fileName, spec, about)

    fun tryScenario(fileName: String, spec: String, scenarioName: String, scenario: String): CommandOutcome =
        TryCommand.runText(fileName, spec, scenarioName, scenario)
}
