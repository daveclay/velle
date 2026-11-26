package velle.api.scenario

import velle.DurationLit
import velle.Parser
import velle.Value
import velle.VelleSyntaxError

/**
 * A scenario read from its JSON text: a list of steps, each an object whose first member names its
 * kind, `commit`, `find`, `pass`, `tick`, or `show`. Reading checks only the scenario's own form; the
 * names it uses are checked against the spec when it runs ([ScenarioRun]).
 */
object ScenarioReading {

    private val membersOfEachKind = mapOf(
        "commit" to setOf("commit", "with", "as", "by"),
        "find" to setOf("find", "where", "as"),
        "pass" to setOf("pass"),
        "tick" to setOf("tick"),
        "show" to setOf("show", "where"),
    )

    fun steps(text: String): List<ScenarioStep> {
        val value = try {
            JsonText.parse(text)
        } catch (unreadable: JsonTextProblem) {
            throw ScenarioProblem(null, "The scenario cannot be read: ${unreadable.message}.")
        }
        val items = value as? List<*> ?: throw ScenarioProblem(null, "A scenario is a JSON list of steps, `[ {...}, {...} ]`.")
        if (items.isEmpty()) throw ScenarioProblem(null, "The scenario has no steps.")
        return items.mapIndexed { index, item -> step(index + 1, item) }
    }

    private fun step(number: Int, item: Any?): ScenarioStep {
        @Suppress("UNCHECKED_CAST")
        val members = item as? Map<String, Any?> ?: throw ScenarioProblem(number, "a step is a JSON object, such as `{ \"tick\": \"Weekly\" }`.")
        val kinds = members.keys.filter { member -> member in membersOfEachKind }
        val kind = kinds.singleOrNull()
            ?: throw ScenarioProblem(number, "a step has exactly one of `commit`, `find`, `pass`, `tick`, `show`; this one has ${if (kinds.isEmpty()) "none" else kinds.joinToString(" and ") { name -> "`$name`" }}.")
        val unknown = members.keys - membersOfEachKind.getValue(kind)
        if (unknown.isNotEmpty()) {
            val allowed = membersOfEachKind.getValue(kind).joinToString(", ") { name -> "`$name`" }
            throw ScenarioProblem(number, "a `$kind` step has only $allowed; ${unknown.joinToString(" and ") { name -> "`$name`" }} is not one of them.")
        }
        val reader = StepMembers(number, members)
        return when (kind) {
            "commit" -> ScenarioStep.Commit(number, reader.text("commit"), reader.fields("with"), reader.optionalText("as"), reader.optionalText("by"))
            "find" -> ScenarioStep.Find(number, reader.text("find"), reader.text("where"), reader.text("as"))
            "pass" -> pass(number, reader.text("pass"))
            "tick" -> ScenarioStep.Tick(number, reader.text("tick"))
            else -> ScenarioStep.Show(number, reader.text("show").takeIf { members["where"] == null }, reader.text("show").takeIf { members["where"] != null }, reader.optionalText("where"))
        }
    }

    /** `"8 days"`, read as the spec writes a duration. */
    private fun pass(number: Int, written: String): ScenarioStep.Pass {
        val notADuration = ScenarioProblem(number, "`$written` is not a duration as a spec writes one, such as \"8 days\" or \"3 hours\".")
        val duration = try {
            Parser.parsePredicate(written) as? DurationLit ?: throw notADuration
        } catch (unreadable: VelleSyntaxError) {
            throw notADuration
        }
        if (duration.amount <= 0) throw ScenarioProblem(number, "time only passes forward; `$written` is not more than nothing.")
        return ScenarioStep.Pass(number, written, Value.VDuration(duration.amount, duration.unit).toJava())
    }

    /** The members of one step, read with the step's number in every problem. */
    private class StepMembers(private val number: Int, private val members: Map<String, Any?>) {

        fun text(member: String): String {
            val value = members[member] ?: throw ScenarioProblem(number, "it needs `$member`.")
            return value as? String ?: throw ScenarioProblem(number, "`$member` is a text in quotes.")
        }

        fun optionalText(member: String): String? = if (members[member] == null) null else text(member)

        @Suppress("UNCHECKED_CAST")
        fun fields(member: String): Map<String, Any?> {
            val value = members[member] ?: return emptyMap()
            return value as? Map<String, Any?> ?: throw ScenarioProblem(number, "`$member` is a JSON object of the act's fields.")
        }
    }
}
