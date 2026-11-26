package velle.api

import velle.Cli
import velle.CommandOutcome
import velle.api.scenario.TryCommand
import velle.exitWith

/**
 * The whole `velle` command, as the skill's jar runs it: `try` here, where the API layer that runs
 * a system is on the classpath, and `validate`, `compare`, and `describe` by [Cli], unchanged.
 */
fun main(arguments: Array<String>) {
    exitWith(VelleCommand.run(arguments.toList()))
}

object VelleCommand {

    fun run(arguments: List<String>): CommandOutcome {
        if (arguments.firstOrNull() != "try") return Cli.run(arguments)
        val operands = arguments.drop(1)
        if (operands.size != 2) return CommandOutcome(Cli.usage, CommandOutcome.USAGE)
        return TryCommand.run(operands[0], operands[1])
    }
}
