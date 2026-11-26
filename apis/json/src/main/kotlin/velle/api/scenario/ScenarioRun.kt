package velle.api.scenario

import velle.ClosureEdge
import velle.DescriptionReport
import velle.Model
import velle.Parser
import velle.PathExpr
import velle.Query
import velle.QueryRefused
import velle.Ref
import velle.RelType
import velle.ShapeDecl
import velle.SpecDescription
import velle.SpecVersion
import velle.StoreKey
import velle.StoredProp
import velle.VelleSyntaxError
import velle.VType
import velle.VelleSystem
import velle.api.ApiException
import velle.api.VelleApi
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * A scenario run on a spec: a fresh system over the in-memory store every system starts with, and a
 * clock only the scenario moves, from the moment a system starts at, so a scenario prints the same
 * report on every run. Every step goes through the API layer ([VelleApi]), as a caller's request would.
 * The report says, per step, what was asked, whether it was accepted, the compiler's sentence for the
 * prohibition that refused it, and the records it created or changed, as the store holds them.
 */
class ScenarioRun(private val version: SpecVersion, private val scenarioName: String) {

    private val system = VelleSystem(Model(Parser.parse(version.source)), source = version.source)
    private val clock = ScenarioClock(system.now)
    private val api = VelleApi(system, clock)
    private val model = system.model
    private val records = RecordsSeen(api)
    private val refusedSteps = mutableListOf<Int>()

    /** The report of every step, or of the steps before the one with a [ScenarioProblem], followed by it. */
    fun report(steps: List<ScenarioStep>): ScenarioReport {
        val report = StringBuilder()
        report.appendLine("# What `$scenarioName` does on `${version.fileName}`")
        report.appendLine()
        report.appendLine("The scenario runs on a system that starts empty at ${clock.instant()}, whose clock moves only when a step says time passes.")
        report.appendLine("It says what this spec does with these records, as the store holds them after each step.")
        for (step in steps) {
            try {
                report.append(stepReport(step))
            } catch (problem: ScenarioProblem) {
                report.appendLine()
                report.appendLine("## The scenario stops at step ${step.number}")
                report.appendLine()
                report.appendLine(problem.problem)
                return ScenarioReport(report.toString(), stopped = true)
            }
        }
        val laterSteps = refusedSteps.filter { refused -> refused < steps.size }
        if (laterSteps.isNotEmpty()) {
            report.appendLine()
            report.appendLine("${stepsNamed(laterSteps)} refused. The steps after ${if (laterSteps.size == 1) "it" else "each"} still ran, on the records as they stood.")
        }
        return ScenarioReport(report.toString(), stopped = false)
    }

    private fun stepsNamed(numbers: List<Int>): String =
        if (numbers.size == 1) "Step ${numbers.single()} was" else "Steps ${numbers.dropLast(1).joinToString(", ")} and ${numbers.last()} were"

    private fun stepReport(step: ScenarioStep): String = when (step) {
        is ScenarioStep.Commit -> commit(step)
        is ScenarioStep.Find -> find(step)
        is ScenarioStep.Pass -> pass(step)
        is ScenarioStep.Tick -> tick(step)
        is ScenarioStep.Show -> show(step)
    }

    // ── commit ───────────────────────────────────────────────────────────────

    private fun commit(step: ScenarioStep.Commit): String {
        val act = step.act
        if (act !in api.acts) throw ScenarioProblem(step.number, "`$act` is not an act of `${version.fileName}`. Its acts are ${namesListed(api.acts)}.")
        val declaration = model.shapes.getValue(act)
        if (step.name != null && act in model.transients) {
            throw ScenarioProblem(step.number, "`$act` is not kept, so there is no record to name `${step.name}`. A `find` step names a record its rules create.")
        }
        val actor = step.actor?.let { actorName -> actorOf(step, declaration, actorName) }
        if (step.actor == null && declaration.actor != null) {
            throw ScenarioProblem(step.number, "`$act` is made by ${article(declaration.actor!!.shape)} ${declaration.actor!!.shape}; the step needs `by` naming one.")
        }
        val body = withNames(step.number, declaration, step.fields, model.closures[act].orEmpty())
        val before = records.now()
        val answer = try {
            api.commit(act, body, actor)
        } catch (malformed: ApiException) {
            throw ScenarioProblem(step.number, malformed.message ?: "the commit was not accepted as written.")
        }
        val heading = "commit $act" + fieldsInWords(step.fields) + (step.name?.let { name -> ", named $name" } ?: "") + (step.actor?.let { actorName -> ", by $actorName" } ?: "")
        return buildString {
            appendStepHeading(step.number, heading)
            if (answer["accepted"] == true) {
                appendLine("Accepted.")
                step.name?.let { name -> records.name(name, answer["ref"] as Map<*, *>) }
            } else {
                refusedSteps.add(step.number)
                appendRefusal(answer)
            }
            appendChanges(before)
        }
    }

    private fun actorOf(step: ScenarioStep.Commit, declaration: ShapeDecl, actorName: String): Ref<*> {
        val actorShape = declaration.actor?.shape
            ?: throw ScenarioProblem(step.number, "`${declaration.name}` is not made by anyone, so `by` has nothing to name.")
        val named = records.named(actorName) ?: throw unknownName(step.number, actorName)
        if (named.shape !in model.basesOf(actorShape)) throw ScenarioProblem(step.number, "`$actorName` is a ${named.shape}, and `${declaration.name}` is made by ${article(actorShape)} $actorShape.")
        return Ref.Persisted(named.shape, StoreKey(named.key))
    }

    /**
     * [fields] with each reference written as a name replaced by the reference the API layer takes,
     * `{"shape", "key"}`: in the act's own fields, and in the parts and new records it arrives with.
     */
    private fun withNames(number: Int, declaration: ShapeDecl, fields: Map<String, Any?>, edges: List<ClosureEdge>): Map<String, Any?> =
        fields.mapValues { (fieldName, value) ->
            val edge = edges.find { candidate -> candidate.prop == fieldName }
            val field = declaration.members.filterIsInstance<StoredProp>().find { member -> member.name == fieldName }
            when {
                edge != null && value is List<*> -> value.map { part -> partWithNames(number, edge, part) }
                edge != null && value is Map<*, *> -> partWithNames(number, edge, value)
                field != null && field.type is RelType -> referenceWithNames(number, value)
                else -> value
            }
        }

    @Suppress("UNCHECKED_CAST")
    private fun partWithNames(number: Int, edge: ClosureEdge, part: Any?): Any? {
        val partFields = part as? Map<String, Any?> ?: return part
        val shape = (partFields["@shape"] as? String) ?: model.basesOf(edge.partShape).singleOrNull() ?: return part
        val partDeclaration = model.shapes[shape] ?: return part
        return withNames(number, partDeclaration, partFields, edge.childrenFor(shape))
    }

    private fun referenceWithNames(number: Int, value: Any?): Any? = when (value) {
        is String -> records.named(value)?.wire() ?: throw unknownName(number, value)
        is List<*> -> value.map { item -> referenceWithNames(number, item) }
        else -> value
    }

    private fun unknownName(number: Int, name: String): ScenarioProblem {
        val given = records.names()
        val known = if (given.isEmpty()) "No step before it names a record." else "The names given so far are ${namesListed(given)}."
        return ScenarioProblem(number, "`$name` is not a name an earlier step gave with `as`. $known")
    }

    private fun StringBuilder.appendRefusal(answer: Map<String, Any?>) {
        appendLine("Refused.")
        (answer["rejection"] as? Map<*, *>)?.let { rejection ->
            appendLine()
            appendLine("The prohibition that refused it:")
            appendLine()
            append(prohibitionWords(rejection))
        }
        appendLine()
        appendLine("The reason the system gave:")
        appendLine()
        appendLine("```")
        appendLine((answer["reason"] as String).trimEnd())
        appendLine("```")
    }

    /** What `velle describe` says of the `never` a rejection names, or its Velle as written when describe has no entry for it. */
    private fun prohibitionWords(rejection: Map<*, *>): String {
        val ordinal = rejection["never"] as Int
        val never = model.nevers.getOrNull(ordinal - 1)
        val described = never?.let { declaration -> SpecDescription.ofDeclaration(version, declaration) }
        if (described != null) return DescriptionReport.renderedDeclaration(described)
        return "- `${rejection["declaration"]}`\n"
    }

    // ── find and show ────────────────────────────────────────────────────────

    private fun find(step: ScenarioStep.Find): String {
        val matching = matching(step.number, step.root, step.where)
        val found = when (matching.size) {
            1 -> matching.single()
            0 -> throw ScenarioProblem(step.number, "no ${step.root} is one where `${step.where}`, so there is nothing to name `${step.name}`.")
            else -> throw ScenarioProblem(step.number, "${matching.size} records of ${step.root} are ones where `${step.where}`: ${matching.joinToString(", ") { record -> records.called(record) }}. A `find` names exactly one; narrow the condition.")
        }
        records.name(step.name, found)
        return buildString {
            appendStepHeading(step.number, "find the ${step.root} where `${step.where}`, named ${step.name}")
            appendLine("Found ${records.called(found)}.")
        }
    }

    private fun show(step: ScenarioStep.Show): String {
        val shown = if (step.name != null) {
            listOf(records.named(step.name)?.wire() ?: throw unknownName(step.number, step.name))
        } else {
            matching(step.number, step.root!!, step.where!!)
        }
        val heading = if (step.name != null) "show ${step.name}" else "show each ${step.root} where `${step.where}`"
        return buildString {
            appendStepHeading(step.number, heading)
            if (shown.isEmpty()) appendLine("No ${step.root} is one where `${step.where}`.")
            shown.forEachIndexed { index, reference ->
                if (index > 0) appendLine()
                appendRecord(records.read(reference))
            }
        }
    }

    private fun StringBuilder.appendRecord(record: Map<String, Any?>) {
        appendLine("${records.called(record)}:")
        appendLine()
        for ((member, value) in RecordWords.fields(model, api, record)) appendLine("- $member: ${records.valueInWords(value, RecordWords.kindOf(model, record, member))}")
        val refinements = RecordWords.refinementsOf(api, record)
        appendLine()
        appendLine(if (refinements.isEmpty()) "It is in none of the refinements of ${record["shape"]}." else "It is ${refinements.joinToString(", ") { refinement -> "${article(refinement)} $refinement" }}.")
    }

    /** The references to the records of [root] that [where] holds for, in the order of their keys. */
    private fun matching(number: Int, root: String, where: String): List<Map<String, Any?>> {
        if (root !in model.shapes && root !in model.refinements) throw ScenarioProblem(number, "`$root` is not a shape or a refinement of `${version.fileName}`.")
        if (root in model.transients) throw ScenarioProblem(number, "`$root` is not kept, so no record of it can be found.")
        val condition = try {
            Parser.parsePredicate(where)
        } catch (unreadable: VelleSyntaxError) {
            throw ScenarioProblem(number, "`$where` cannot be read as a condition: ${unreadable.message}")
        }
        return api.scope {
            val identities = try {
                system.query(Query(root, condition)).ids
            } catch (refused: QueryRefused) {
                throw ScenarioProblem(number, "`$where` is not a condition over $root: ${refused.diagnostics.joinToString("; ") { diagnostic -> diagnostic.message }}")
            }
            identities.map { identity -> api.wire.ref(system.refFor(identity)) }
        }
    }

    // ── time ─────────────────────────────────────────────────────────────────

    private fun pass(step: ScenarioStep.Pass): String {
        val before = records.now()
        clock.instant = clock.instant().plus(step.duration)
        return buildString {
            appendStepHeading(step.number, "${step.written} pass")
            appendLine("The clock now reads ${clock.instant()}. No rule runs until a commit or a tick.")
            appendChanges(before)
        }
    }

    private fun tick(step: ScenarioStep.Tick): String {
        if (step.schedule !in api.schedules) {
            val schedules = if (api.schedules.isEmpty()) "It has no schedules." else "Its schedules are ${namesListed(api.schedules)}."
            throw ScenarioProblem(step.number, "`${step.schedule}` is not a schedule of `${version.fileName}`. $schedules")
        }
        val before = records.now()
        val answer = api.tick(step.schedule)
        return buildString {
            appendStepHeading(step.number, "the ${step.schedule} tick")
            appendLine("It ran at ${answer["at"]}.")
            val rejections = answer["rejections"] as List<*>
            rejections.forEach { rejection ->
                val refused = rejection as Map<*, *>
                appendLine()
                appendLine("A firing of ${refused["rule"]} was refused. The prohibition that refused it:")
                appendLine()
                append(prohibitionWords(refused))
                appendLine()
                appendLine("The reason the system gave:")
                appendLine()
                appendLine("```")
                appendLine(refused["message"].toString().trimEnd())
                appendLine("```")
            }
            appendChanges(before)
        }
    }

    // ── what a step changed ──────────────────────────────────────────────────

    private fun StringBuilder.appendChanges(before: Map<RecordName, Map<String, Any?>>) {
        val after = records.now()
        val created = after.keys - before.keys
        val changed = after.keys.intersect(before.keys).filter { name -> after[name] != before[name] }
        val deleted = before.keys - after.keys
        appendLine()
        if (created.isEmpty() && changed.isEmpty() && deleted.isEmpty()) {
            appendLine("No record was created, changed, or deleted.")
            return
        }
        if (created.isNotEmpty()) {
            appendLine("Created:")
            created.forEach { name -> appendLine("- ${createdInWords(after.getValue(name))}") }
        }
        if (changed.isNotEmpty()) {
            if (created.isNotEmpty()) appendLine()
            appendLine("Changed:")
            changed.forEach { name -> appendLine("- ${changedInWords(before.getValue(name), after.getValue(name))}") }
        }
        if (deleted.isNotEmpty()) {
            if (created.isNotEmpty() || changed.isNotEmpty()) appendLine()
            appendLine("Deleted:")
            deleted.forEach { name -> appendLine("- ${records.called(before.getValue(name))}") }
        }
    }

    private fun createdInWords(record: Map<String, Any?>): String {
        val fields = RecordWords.fields(model, api, record).filterValues { value -> value != emptyList<Any?>() }.entries.joinToString(", ") { (member, value) ->
            "$member ${records.valueInWords(value, RecordWords.kindOf(model, record, member))}"
        }
        val refinements = RecordWords.refinementsOf(api, record)
        val membership = if (refinements.isEmpty()) "" else "; it is " + refinements.joinToString(", ") { refinement -> "${article(refinement)} $refinement" }
        return "${records.called(record)}: $fields$membership"
    }

    private fun changedInWords(before: Map<String, Any?>, after: Map<String, Any?>): String {
        val beforeFields = RecordWords.fields(model, api, before)
        val fieldChanges = RecordWords.fields(model, api, after).filter { (member, value) -> beforeFields[member] != value }
            .map { (member, value) -> "$member ${records.valueInWords(beforeFields[member], RecordWords.kindOf(model, after, member))} to ${records.valueInWords(value, RecordWords.kindOf(model, after, member))}" }
        val beforeRefinements = RecordWords.refinementsOf(api, before).toSet()
        val afterRefinements = RecordWords.refinementsOf(api, after).toSet()
        val entered = (afterRefinements - beforeRefinements).map { refinement -> "now ${article(refinement)} $refinement" }
        val left = (beforeRefinements - afterRefinements).map { refinement -> "no longer ${article(refinement)} $refinement" }
        return "${records.called(after)}: " + (fieldChanges + entered + left).joinToString(", ")
    }

    // ── words ────────────────────────────────────────────────────────────────

    private fun StringBuilder.appendStepHeading(number: Int, heading: String) {
        appendLine()
        appendLine("## Step $number: $heading")
        appendLine()
    }

    private fun fieldsInWords(fields: Map<String, Any?>): String {
        if (fields.isEmpty()) return ""
        return " with " + fields.entries.joinToString(", ") { (fieldName, value) -> "$fieldName ${givenInWords(value)}" }
    }

    /** A value as the scenario gave it: a name as the name, text in quotes. */
    private fun givenInWords(value: Any?): String = when (value) {
        null -> "absent"
        is String -> if (records.named(value) != null) value else "\"$value\""
        is List<*> -> "[" + value.joinToString(", ") { item -> givenInWords(item) } + "]"
        is Map<*, *> -> "{" + value.entries.joinToString(", ") { (member, item) -> "$member ${givenInWords(item)}" } + "}"
        else -> value.toString()
    }

    private fun namesListed(names: Collection<String>): String = names.joinToString(", ") { name -> "`$name`" }

    private fun article(name: String): String = if (name.first().lowercaseChar() in "aeiou") "an" else "a"
}

/** A report of a scenario, and whether it [stopped] at a problem before its end. */
class ScenarioReport(val text: String, val stopped: Boolean)

/** A clock that reads [instant] until the scenario moves it. */
internal class ScenarioClock(var instant: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = instant
}

/** A record by its shape and store key, as the store names it. */
internal data class RecordName(val shape: String, val key: Any)

/** The fields and refinements of a record the API layer read, for the report. */
internal object RecordWords {

    /** The record's members, without its shape, key, and refinement flags. */
    fun fields(model: Model, api: VelleApi, record: Map<String, Any?>): Map<String, Any?> {
        val flags = api.wire.flagsOf(record["shape"] as String).map { refinement -> "is$refinement" }.toSet()
        return record.filterKeys { member -> member != "shape" && member != "key" && member !in flags }
    }

    /** The refinements the record is a member of, in the order the spec declares them. */
    fun refinementsOf(api: VelleApi, record: Map<String, Any?>): List<String> =
        api.wire.flagsOf(record["shape"] as String).filter { refinement -> record["is$refinement"] == true }

    /**
     * How the report writes [member]'s value: a text in quotes; a field the spec fills with `initially
     * randomUUID`, whose value differs on every run, by what it is and not its value; any other value
     * as the store gives it.
     */
    fun kindOf(model: Model, record: Map<String, Any?>, member: String): ValueKind {
        val shape = record["shape"] as String
        val declaration = model.shapes[shape]
        val random = declaration?.members?.filterIsInstance<StoredProp>()?.any { field -> field.name == member && field.initially == PathExpr("randomUUID") } == true
        if (random) return ValueKind.RANDOM
        val type = model.membersOf(shape)[member]?.type
        val text = type == VType.Text || (type is VType.Optional && type.inner == VType.Text)
        return if (text) ValueKind.TEXT else ValueKind.OTHER
    }
}

/** How a value is written in the report ([RecordWords.kindOf]). */
internal enum class ValueKind { TEXT, RANDOM, OTHER }

/**
 * The records the store holds, read through the API layer in the engineer's view, which sees every
 * record whoever may see it, and the names the scenario gave some of them.
 */
internal class RecordsSeen(private val api: VelleApi) {

    private val model = api.model
    private val namedRecords = linkedMapOf<String, RecordName>()
    private val keptShapes = model.shapes.keys.filter { shape -> shape !in model.transients }

    fun name(name: String, reference: Map<*, *>) {
        namedRecords[name] = RecordName(reference["shape"] as String, reference["key"]!!)
    }

    fun named(name: String): RecordName? = namedRecords[name]

    fun names(): List<String> = namedRecords.keys.toList()

    /** Every record the store holds now, each read as its own shape. */
    fun now(): Map<RecordName, Map<String, Any?>> = api.scope {
        keptShapes.flatMap { shape ->
            api.system.query(Query(shape)).ids.map { identity -> api.wire.record(identity, shape, null) }
        }.sortedBy { record -> record["key"].toString().padStart(20, '0') }
            .associateBy { record -> RecordName(record["shape"] as String, record["key"]!!) }
    }

    /** The record a reference names, read now. */
    fun read(reference: Map<String, Any?>): Map<String, Any?> = api.scope {
        val identity = api.system.idOf(Ref.Persisted(reference["shape"] as String, StoreKey(reference["key"]!!)))
        api.wire.record(identity, reference["shape"] as String, null)
    }

    /** "Customer 2" or, named by the scenario, "Customer 2 (alice)". */
    fun called(record: Map<*, *>): String {
        val recordName = RecordName(record["shape"] as String, record["key"]!!)
        val given = namedRecords.entries.firstOrNull { entry -> entry.value == recordName }?.key
        return "${recordName.shape} ${recordName.key}" + (given?.let { name -> " ($name)" } ?: "")
    }

    /** A value as the store holds it, in wire form: text in quotes, a reference as the record it names, nothing as "absent". */
    fun valueInWords(value: Any?, kind: ValueKind): String = when {
        kind == ValueKind.RANDOM -> "a random identifier"
        value == null -> "absent"
        value is String && kind == ValueKind.TEXT -> "\"$value\""
        value is Map<*, *> && value.containsKey("shape") && value.containsKey("key") -> called(value)
        value is List<*> -> if (value.isEmpty()) "none" else value.joinToString(", ") { item -> valueInWords(item, kind) }
        else -> value.toString()
    }
}

private fun RecordName.wire(): Map<String, Any?> = linkedMapOf("shape" to shape, "key" to key)
