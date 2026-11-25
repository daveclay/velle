package velle

/**
 * What an act does, in sentences written from the model: who commits it, what refuses it
 * when it arrives, which rules it starts, and what those rules create. It is derived from
 * the rules, so it cannot say something they do not; the spec's comments never reach it.
 * A caller that offers the act to someone who has not read the spec, such as a tool an AI
 * assistant calls, shows this text.
 */
class ActDescription(private val model: Model) {
    private val catalog = KindCatalog(model)
    private val mayFire by lazy { RuleGraphGen.mayFire(model, catalog) }
    private val doorProhibitions by lazy { Validator(model).doorNevers.map { index -> model.nevers[index] } }

    /** The description of the act named [act], one sentence per line. */
    fun of(act: String): String {
        val declaration = model.shapes[act] ?: throw IllegalArgumentException("no act '$act'")
        val record = Sentences.withArticle(act)
        return (listOf(opening(act, record, declaration)) + refusals(act, record) + rulesStarted(act) + closing())
            .joinToString("\n")
    }

    private fun opening(act: String, record: String, declaration: ShapeDecl): String {
        val kept = if (act in model.transients) "It leaves no record of its own." else "It is kept as a record."
        val actor = declaration.actor?.let { by -> " It is committed by ${Sentences.withArticle(by.shape)}: the caller must be one." }.orEmpty()
        return "Commits $record. $kept$actor"
    }

    private fun refusals(act: String, record: String): List<String> =
        doorProhibitions.filter { prohibition -> subjectOf(prohibition) == act }.map { prohibition ->
            val forbidden = DeclarationSentences.forbidden(prohibition, model.shapes.keys)
            if (forbidden != null) "${Sentences.capitalized(record)} is refused when $forbidden."
            else "${Sentences.capitalized(record)} is refused by `${Printer.prohibition(prohibition)}`."
        }

    private fun subjectOf(prohibition: NeverDecl): String {
        val subject = prohibition.requirement?.subject ?: Sentences.leftmostName(prohibition.target)
        return model.baseOf(subject) ?: subject
    }

    /** The rules the act's commit can start: those triggered on the act itself or one of its
     *  refinements, and those the rule graph draws from the act's commit, in the spec's order. */
    private fun rulesStarted(act: String): List<String> {
        val drawn = mayFire.filter { edge -> edge.source == "commit$act" || edge.source == "env$act" }.map { edge -> edge.rule }.toSet()
        return model.rules.values
            .filter { rule -> rule.name in drawn || model.baseOfExpr(rule.condition) == act }
            .map { rule -> ruleSentence(rule) }
    }

    private fun ruleSentence(rule: RuleDecl): String {
        val created = createdBy(rule)
        val creates = if (created.isEmpty()) "" else ", which creates " + created.joinToString(" and ") { shape -> Sentences.withArticle(shape) }
        val timing = if (rule.preposition == "after") " after the commit is over" else ""
        return "Committing it can start rule ${rule.name}$timing$creates."
    }

    private fun createdBy(rule: RuleDecl): List<String> = creationsIn(rule.body).distinct()

    private fun creationsIn(body: List<BodyItem>): List<String> = body.flatMap { item ->
        when (item) {
            is Creation -> listOf(item.shape)
            is ForEach -> creationsIn(item.body)
            else -> emptyList()
        }
    }

    private fun closing(): String =
        "Every prohibition in the spec is checked when its work is done; a refusal names the prohibition it broke."
}
