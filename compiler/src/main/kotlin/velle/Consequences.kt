package velle

/**
 * One consequence of an edit for declarations nobody edited: a sentence about [subject], the
 * shape or act it is about, and the [names] it mentions, by which the report finds the edit
 * that causes it. Every consequence is a "can" or a "can no longer": the compiler draws a
 * possibility whenever it cannot prove it impossible, so none of them says "will".
 */
data class Consequence(val subject: String, val sentence: String, val names: Set<String>)

/** What the compiler derives from one spec, the facts its diagrams and generated cases are drawn from. */
internal class SpecFacts(val version: SpecVersion) {
    private val model = version.model
    private val catalog = KindCatalog(model)

    val moves: Set<MembershipMove> = StateFlowFacts.moves(model, catalog).toSet()

    /**
     * (source, rule, runs outside the source's envelope): the rule graph's edges without their labels.
     * The graph draws an act's arrival as `commit<Act>`, or as `env<Act>` once it has passed the
     * act's refusals; both are the act arriving, so both are read as `commit<Act>` here, and a
     * refusal added or removed at the door does not read as every rule the act causes changing.
     */
    val mayFire: Set<Triple<String, String, Boolean>> = RuleGraphGen.mayFire(model, catalog)
        .map { edge -> Triple(arrival(edge.source), edge.rule, edge.crossesBoundary) }.toSet()

    private fun arrival(source: String): String {
        val act = source.removePrefix("env")
        return if (source.startsWith("env") && act in model.exposed) "commit$act" else source
    }

    /** The prohibitions checked once, when the act arrives, before any rule runs. */
    val checkedAtTheDoor: Set<NeverDecl> = Validator(model).doorNevers.map { index -> model.nevers[index] }.toSet()

    val prohibitions: Set<String> = model.nevers.map { prohibition -> Printer.prohibition(prohibition) }.toSet()

    /** What each act's work waits in line behind: its queue keys, and the shapes it queues on system-wide. */
    val queues: Map<String, String> = DomainAnalysis(model).actDomains.mapValues { (act, domain) -> queuePhrase(act, domain) }

    val advisories: Set<String> = Validator.advisories(version.source).map { diagnostic -> diagnostic.message }.toSet()

    /** The cases the generator writes, from its index; the cases it lists as not yet generated are left out. */
    val generatedCases: Set<String> = SpecGen.generate(version.source, "System").index
        .substringBefore("## Not yet generated").lines()
        .filter { line -> line.startsWith("- ") }.map { line -> line.removePrefix("- ") }.toSet()

    val baseOf: (String) -> String = { name -> model.baseOf(name) ?: name }

    private fun queuePhrase(act: String, domain: SerializationDomain): String {
        val keys = domain.renderKeys(act)
        val wideShapes = domain.widenings.map { widening -> widening.shape }.distinct()
        return when {
            wideShapes.isNotEmpty() -> "every other piece of work on any ${wideShapes.joinToString(" or any ")}, system-wide"
            keys.isEmpty() -> "nothing"
            else -> "other work on the same " + keys.joinToString(" or the same ")
        }
    }
}

/** The consequences two specs' facts differ by. */
internal object Consequences {

    fun between(before: SpecFacts, after: SpecFacts): List<Consequence> =
        membership(before, after) + mayFire(before, after) + door(before, after) + queues(before, after) +
            advisories(before, after) + generatedCases(before, after)

    // ── moving a record into or out of a refinement ─────────────────────────────

    private fun membership(before: SpecFacts, after: SpecFacts): List<Consequence> {
        val keys = (before.moves + after.moves).map { move -> Triple(move.base, move.refinement, move.cause.label) }.distinct()
        return keys.flatMap { (base, refinement, label) ->
            val beforeMove = before.moves.firstOrNull { move -> move.refinement == refinement && move.cause.label == label && move.base == base }
            val afterMove = after.moves.firstOrNull { move -> move.refinement == refinement && move.cause.label == label && move.base == base }
            val cause = (afterMove ?: beforeMove)!!.cause
            membershipChange(base, refinement, cause, beforeMove?.direction, afterMove?.direction)
        }
    }

    private fun movesIn(direction: Dir?) = direction == Dir.TOWARD_TRUE || direction == Dir.UNKNOWN
    private fun movesOut(direction: Dir?) = direction == Dir.TOWARD_FALSE || direction == Dir.UNKNOWN

    private fun membershipChange(base: String, refinement: String, cause: Cause, before: Dir?, after: Dir?): List<Consequence> {
        val causeWords = causeWords(cause)
        val record = Sentences.withArticle(base)
        val names = setOf(base, refinement) + causeNames(cause)
        val into = "make $record become ${Sentences.withArticle(refinement)}"
        val outOf = "make $record stop being ${Sentences.withArticle(refinement)}"
        return listOfNotNull(
            gainedOrLost(movesIn(before), movesIn(after), causeWords, into, base, names),
            gainedOrLost(movesOut(before), movesOut(after), causeWords, outOf, base, names),
        )
    }

    private fun gainedOrLost(before: Boolean, after: Boolean, cause: String, effect: String, subject: String, names: Set<String>): Consequence? = when {
        after && !before -> Consequence(subject, "${Sentences.capitalized(cause)} can now $effect.", names)
        before && !after -> Consequence(subject, "${Sentences.capitalized(cause)} can no longer $effect.", names)
        else -> null
    }

    private fun causeWords(cause: Cause): String = when (val kind = cause.kind) {
        is CommitKind.Create -> if (cause.label == "commit${kind.shape}") "a new ${kind.shape}" else "${ruleOf(cause)} creating ${Sentences.withArticle(kind.shape)}"
        is CommitKind.Assign -> "${ruleOf(cause)} setting ${kind.shape}'s ${kind.field}"
        is CommitKind.Delete -> "${ruleOf(cause)} deleting ${Sentences.withArticle(kind.shape)}"
        CommitKind.TimePasses -> "time passing"
    }

    private fun ruleOf(cause: Cause): String = cause.label.substringBefore(' ')

    private fun causeNames(cause: Cause): Set<String> = when (val kind = cause.kind) {
        is CommitKind.Create -> setOf(kind.shape, ruleOf(cause))
        is CommitKind.Assign -> setOf(kind.shape, kind.field, ruleOf(cause))
        is CommitKind.Delete -> setOf(kind.shape, ruleOf(cause))
        CommitKind.TimePasses -> emptySet()
    }

    // ── causing a rule to run ────────────────────────────────────────────────────

    private fun mayFire(before: SpecFacts, after: SpecFacts): List<Consequence> {
        val gained = (after.mayFire - before.mayFire).map { edge -> mayFireConsequence(edge, "can now", after) }
        val lost = (before.mayFire - after.mayFire).map { edge -> mayFireConsequence(edge, "can no longer", before) }
        return gained + lost
    }

    private fun mayFireConsequence(edge: Triple<String, String, Boolean>, timing: String, facts: SpecFacts): Consequence {
        val (source, rule, crossesBoundary) = edge
        val act = source.removePrefix("commit").takeIf { source.startsWith("commit") }
        val sourceWords = if (act != null) "A new $act" else source
        val laterNote = if (crossesBoundary) ", later rather than in the same commit" else ""
        val subject = act?.let(facts.baseOf) ?: source
        return Consequence(subject, "$sourceWords $timing cause $rule to run$laterNote.", setOfNotNull(act, source, rule))
    }

    // ── the door, the queues, the advisories, the generated cases ──────────────

    /** A prohibition both specs keep, moved into or out of the door set by a change elsewhere. A
     *  prohibition added or removed is an edit, and said there. */
    private fun door(before: SpecFacts, after: SpecFacts): List<Consequence> {
        val kept = before.prohibitions.intersect(after.prohibitions)
        val nowAtTheDoor = (after.checkedAtTheDoor - before.checkedAtTheDoor).filter { prohibition -> Printer.prohibition(prohibition) in kept }.map { prohibition ->
            doorConsequence(prohibition, after, "is now checked when it arrives, before any rule runs")
        }
        val noLonger = (before.checkedAtTheDoor - after.checkedAtTheDoor).filter { prohibition -> Printer.prohibition(prohibition) in kept }.map { prohibition ->
            doorConsequence(prohibition, before, "is no longer checked when it arrives; it is checked when its work is done")
        }
        return nowAtTheDoor + noLonger
    }

    private fun doorConsequence(prohibition: NeverDecl, facts: SpecFacts, timing: String): Consequence {
        val written = Printer.prohibition(prohibition)
        val subject = prohibition.requirement?.subject ?: Sentences.leftmostName(prohibition.target)
        val forbidden = DeclarationSentences.forbidden(prohibition, facts.version)
        val sentence = if (forbidden != null) {
            "Whether ${Sentences.withArticle(subject)} is refused when $forbidden $timing."
        } else {
            "The prohibition `$written` $timing. This report has no words for the prohibition yet."
        }
        return Consequence(facts.baseOf(subject), sentence, namesIn(written))
    }

    private fun queues(before: SpecFacts, after: SpecFacts): List<Consequence> =
        after.queues.filter { (act, queue) -> act in before.queues && before.queues[act] != queue }
            .map { (act, queue) ->
                Consequence(act, "A new $act now waits its turn behind $queue. Before: behind ${before.queues[act]}.", setOf(act))
            }

    private fun advisories(before: SpecFacts, after: SpecFacts): List<Consequence> =
        (after.advisories - before.advisories).map { advisory -> Consequence("", "The compiler now advises: $advisory", namesIn(advisory)) } +
            (before.advisories - after.advisories).map { advisory -> Consequence("", "The compiler no longer advises: $advisory", namesIn(advisory)) }

    /**
     * The example tests the compiler writes from the spec ([SpecGen]), said as what each one checks:
     * "that a LineItem whose price is below 0.00 is refused". A test that is no longer written is not
     * a rule that stops holding; the sentence says it is about the tests.
     */
    private fun generatedCases(before: SpecFacts, after: SpecFacts): List<Consequence> {
        val gone = (before.generatedCases - after.generatedCases).sorted()
        val new = (after.generatedCases - before.generatedCases).sorted()
        return listOfNotNull(
            gone.takeIf { cases -> cases.isNotEmpty() }?.let { cases ->
                val opening = if (cases.size == 1) "The compiler no longer writes 1 of the example tests it writes from the spec. It checked"
                else "The compiler no longer writes ${cases.size} of the example tests it writes from the spec. They checked"
                Consequence("", "$opening ${checks(cases)}.", cases.flatMap(::namesIn).toSet())
            },
            new.takeIf { cases -> cases.isNotEmpty() }?.let { cases ->
                val opening = if (cases.size == 1) "The compiler now writes 1 more example test from the spec. It checks"
                else "The compiler now writes ${cases.size} more example tests from the spec. They check"
                Consequence("", "$opening ${checks(cases)}.", cases.flatMap(::namesIn).toSet())
            },
        )
    }

    private fun checks(cases: List<String>): String {
        val checked = cases.map { case -> GeneratedCaseWords.checked(case) }
        return if (checked.size == 1) checked.single() else checked.dropLast(1).joinToString(", ") + ", and " + checked.last()
    }

    private fun namesIn(text: String): Set<String> = Regex("\\b[A-Z][A-Za-z0-9]*\\b").findAll(text).map { match -> match.value }.toSet()
}
