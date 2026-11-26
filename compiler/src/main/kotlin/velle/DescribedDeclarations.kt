package velle

import velle.Sentences.Subject

/** One part of a declaration as `velle describe` gives it: a sentence, or the Velle of a part with no words. */
sealed interface Phrase {

    data class Said(val sentence: String) : Phrase

    /**
     * A part with no words: its [velle] as the compiler prints it, the [line] it is on, the line
     * [writtenAs] the author wrote it, and the [form] that keeps it from having words.
     */
    data class Unsaid(val velle: String, val line: Int, val writtenAs: String, val form: String) : Phrase
}

/**
 * One declaration as `velle describe` gives it: a [summary], and under it a [detail] per part, so
 * that a part with no words is shown as Velle on its own and the rest is still said. [label] names
 * the declaration when its summary has no words: "the rule RemindOverdue".
 */
data class DescribedDeclaration(val pointer: Pointer, val label: String, val summary: Phrase, val details: List<Phrase> = emptyList()) {
    val unsaid: List<Phrase.Unsaid> get() = (listOf(summary) + details).filterIsInstance<Phrase.Unsaid>()
}

/** The lines of a spec one declaration spans, for finding the line a part of it is written on. */
class DeclarationLines(private val writtenLines: List<String>, private val firstLine: Int, private val lastLine: Int) {

    /** The first line of the declaration whose text, trimmed, starts with one of [starts]; the declaration's first line when none does. */
    fun lineStarting(vararg starts: String): Int =
        (firstLine..lastLine).firstOrNull { line -> starts.any { start -> written(line).startsWith(start) } } ?: firstLine

    /** The first line of the declaration that holds [text]; the declaration's first line when none does. */
    fun lineHolding(text: String): Int = (firstLine..lastLine).firstOrNull { line -> text in written(line) } ?: firstLine

    fun written(line: Int): String = writtenLines.getOrElse(line - 1) { "" }.trim()
}

/** Each kind of declaration said part by part, in the words of a description ([Subject.described]). */
class DescribedDeclarations(private val compared: Compared, private val lines: DeclarationLines) {

    private val version = compared.version
    private val subject = Subject.described

    fun described(): DescribedDeclaration = when (val declaration = compared.declaration) {
        is ShapeDecl -> shape(declaration)
        is RefinementDecl -> refinement(declaration)
        is RuleDecl -> rule(declaration)
        is NeverDecl -> prohibition(declaration)
        is EnumDecl, is UsesDecl -> whole(DeclarationSentences.described(compared))
        is VisibilityDefault -> whole(null, "a `visible to` line for the whole spec")
    }

    private fun whole(description: String?, form: String = "`${compared.pointer.declaration}`"): DescribedDeclaration {
        val summary = description?.let { words -> Phrase.Said(DeclarationSentences.presentSentence(compared, words)) }
            ?: unsaid(compared.printed, compared.located.line, form)
        return DescribedDeclaration(compared.pointer, label(), summary)
    }

    private fun unsaid(velle: String, line: Int, form: String) = Phrase.Unsaid(velle, line, lines.written(line), form)

    // ── Shapes and acts ──────────────────────────────────────────────────────

    private fun shape(declaration: ShapeDecl): DescribedDeclaration {
        val kind = when {
            declaration.transient -> "a request someone makes, not kept"
            declaration.exposed -> "a request someone makes, kept as a record"
            else -> "a record the system keeps"
        }
        val takes = declaration.takes.map { taken -> Phrase.Said("It has every field $taken declares.") }
        val with = if (declaration.with.isEmpty()) emptyList() else listOf(unsaid(Printer.print(declaration).substringAfterLast("} "), compared.located.line, "an act's `with` list"))
        val owner = MemberOwner(declaration.name, declaration.name, refinement = false)
        val ownMembers = declaration.members.filter { member -> member.declaredOn() == null }
        val members = ownMembers.map { member -> memberPhrase(member, owner) }
        return DescribedDeclaration(compared.pointer, label(), Phrase.Said("${declaration.name} is $kind."), takes + with + members)
    }

    private fun Member.declaredOn(): String? = when (this) {
        is StoredProp -> declaredOn
        is DerivedProp -> declaredOn
        is TimestampProp -> declaredOn
        else -> null
    }

    private fun memberPhrase(member: Member, owner: MemberOwner): Phrase {
        val sentence = MemberSentences.said(member, owner)
        if (sentence != null) return Phrase.Said(sentence)
        return unsaid(Printer.member(member), lines.lineStarting(*memberStarts(member)), MemberSentences.formOf(member))
    }

    private fun memberStarts(member: Member): Array<String> = when (member) {
        is StoredProp -> arrayOf("${member.name}:", "transient ${member.name}:")
        is DerivedProp -> arrayOf("${member.name}:", "${member.name} =", "captured ${member.name}")
        is TimestampProp -> arrayOf("${member.name}:")
        is VisibleClause -> arrayOf("visible")
        is UniqueKey -> arrayOf("unique")
        is FrozenClause -> arrayOf("frozen")
        UndeletableClause -> arrayOf("undeletable")
        is ActorProp -> arrayOf("by")
    }

    // ── Refinements ──────────────────────────────────────────────────────────

    private fun refinement(declaration: RefinementDecl): DescribedDeclaration {
        val base = version.baseOf(declaration.name)
        val opening = "${Sentences.capitalized(Sentences.withArticle(base))} is ${Sentences.withArticle(declaration.name)} when"
        val condition = conditionPhrases(opening, declaration.expr)
        val owner = MemberOwner(declaration.name, base, refinement = true)
        val members = declaration.members.map { member -> memberPhrase(member, owner) }
        return DescribedDeclaration(compared.pointer, label(), condition.first, condition.second + members)
    }

    /**
     * A condition after [opening]: one sentence when it has words, or else "when all of these hold:"
     * with a detail per part joined by `and`, each said or shown as Velle.
     */
    private fun conditionPhrases(opening: String, expression: RefExpr, sayNothing: Set<String> = version.baseShapes): Pair<Phrase, List<Phrase>> {
        val clause = Sentences.clause(expression, sayNothing, subject)
        if (clause != null) return Phrase.Said("$opening $clause.") to emptyList()
        val parts = conjuncts(expression, sayNothing)
        val heading = if (parts.size == 1) "$opening this holds:" else "$opening all of these hold:"
        return Phrase.Said(heading) to parts.map { part -> partPhrase(part) }
    }

    /** A part of a condition joined to the others by `and`: a refinement expression or a predicate. */
    private sealed interface ConditionPart {
        data class OfRefinement(val expression: RefExpr) : ConditionPart
        data class OfPredicate(val expression: Expr) : ConditionPart
    }

    /** The parts of [expression] joined by `and`. A name in [sayNothing] is a shape, or the record the sentence is about, and is left out. */
    private fun conjuncts(expression: RefExpr, sayNothing: Set<String>): List<ConditionPart> = when (expression) {
        is RefAnd -> conjuncts(expression.left, sayNothing) + conjuncts(expression.right, sayNothing)
        is RefName -> nameConjuncts(expression, sayNothing)
        else -> listOf(ConditionPart.OfRefinement(expression))
    }

    private fun nameConjuncts(expression: RefName, sayNothing: Set<String>): List<ConditionPart> {
        val membership = if (expression.name in sayNothing) emptyList() else listOf(ConditionPart.OfRefinement(RefName(expression.name)))
        val restriction = expression.where?.let { predicate -> predicateConjuncts(predicate) }.orEmpty()
        return membership + restriction
    }

    private fun predicateConjuncts(expression: Expr): List<ConditionPart> =
        if (expression is Binary && expression.op == "and") predicateConjuncts(expression.left) + predicateConjuncts(expression.right)
        else listOf(ConditionPart.OfPredicate(expression))

    private fun partPhrase(part: ConditionPart): Phrase = when (part) {
        is ConditionPart.OfRefinement -> {
            val clause = Sentences.clause(part.expression, version.baseShapes, subject)
            val velle = Printer.refExpr(part.expression)
            clause?.let { words -> Phrase.Said(words) }
                ?: unsaid(velle, lines.lineHolding(velle), FormsWithoutWords.of(part.expression, version.baseShapes, subject) ?: velle)
        }
        is ConditionPart.OfPredicate -> {
            val clause = Sentences.predicate(part.expression, subject)
            val velle = Printer.expr(part.expression)
            clause?.let { words -> Phrase.Said(words) }
                ?: unsaid(velle, lines.lineHolding(velle.removePrefix("(").removeSuffix(")")), FormsWithoutWords.ofCondition(part.expression, subject) ?: velle)
        }
    }

    // ── Rules ────────────────────────────────────────────────────────────────

    private fun rule(declaration: RuleDecl): DescribedDeclaration {
        val timing = RuleSentences.timing(declaration, version, subject)
        val bodySubject = declaration.by?.let { actor -> Subject("its", setOf(actor.name), Sentences.Vocabulary.DESCRIPTION) } ?: subject
        val effects = declaration.body.map { item -> item to RuleSentences.effect(item, bodySubject) }
        val saidEffects = effects.mapNotNull { itemAndEffect -> itemAndEffect.second }
        val allSaid = declaration.by == null && effects.isNotEmpty() && saidEffects.size == effects.size
        if (timing != null && allSaid) {
            return DescribedDeclaration(compared.pointer, label(), Phrase.Said("${declaration.name}: $timing, it ${saidEffects.joinToString(", then ")}."))
        }
        val summary = if (timing != null) Phrase.Said("${declaration.name}: $timing, it does the following.") else Phrase.Said("${declaration.name}:")
        val header = if (timing != null) emptyList() else listOf(ruleHeader(declaration))
        val actor = declaration.by?.let { actor -> listOf(ruleActor(actor)) }.orEmpty()
        val body = effects.map { itemAndEffect -> effectPhrase(itemAndEffect.first, itemAndEffect.second, bodySubject) }
        return DescribedDeclaration(compared.pointer, label(), summary, header + actor + body)
    }

    private fun ruleHeader(declaration: RuleDecl): Phrase {
        val velle = Printer.print(declaration.copy(body = emptyList(), by = null)).removeSuffix(" {\n}")
        val form = FormsWithoutWords.of(declaration.condition, version.baseShapes, subject) ?: ruleTimingForm(declaration)
        return unsaid(velle, compared.located.line, form)
    }

    private fun ruleTimingForm(declaration: RuleDecl): String = when {
        declaration.preposition != null && "commit" !in declaration.triggers && declaration.trigger != Trigger.ENTRY ->
            "a rule `when leaving` run only at a tick"
        else -> "when the rule runs"
    }

    private fun ruleActor(actor: RuleActor): Phrase {
        val velle = "by ${actor.shape}${if (actor.optional) "?" else ""}" + if (actor.named) " as ${actor.name}" else ""
        return unsaid(velle, lines.lineHolding(velle), "a rule that reads who made the request, `by`")
    }

    private fun effectPhrase(item: BodyItem, effect: String?, bodySubject: Subject): Phrase {
        if (effect != null) return Phrase.Said(Sentences.capitalized(effect) + ".")
        if (item is ThenMarker) return Phrase.Said("Then, once the lines above are done:")
        val velle = Printer.bodyItem(item)
        return unsaid(velle, lines.lineHolding(velle.lines().first().trim()), effectForm(item, bodySubject))
    }

    private fun effectForm(item: BodyItem, bodySubject: Subject): String = when (item) {
        is ForEach -> "a `for each` block"
        is Creation -> creationForm(item, bodySubject)
        is Assignment -> FormsWithoutWords.of(item.value, bodySubject) ?: FormsWithoutWords.of(item.target, bodySubject) ?: "an assignment"
        is DeleteStmt -> "a `delete`"
        ThenMarker -> "`then`"
    }

    private fun creationForm(item: Creation, bodySubject: Subject): String = when {
        item.alias != null -> "a creation named with `as`"
        item.forExpr != null -> "a creation `for` a record"
        else -> item.fields.firstNotNullOfOrNull { field -> FormsWithoutWords.of(field.value, bodySubject) } ?: "a creation"
    }

    // ── Prohibitions ─────────────────────────────────────────────────────────

    private fun prohibition(declaration: NeverDecl): DescribedDeclaration {
        if (declaration.keyDeclaration != null || declaration.fromActorClause) return whole(null)
        val opening = Sentences.capitalized(DeclarationSentences.prohibitionOpening(declaration, version))
        val requirement = declaration.requirement
        if (requirement == null) {
            val condition = conditionPhrases(opening, declaration.target, version.baseShapes + compared.subject)
            return DescribedDeclaration(compared.pointer, label(), condition.first, condition.second)
        }
        val clause = Sentences.predicate(requirement.predicate, subject)
        if (clause != null) return DescribedDeclaration(compared.pointer, label(), Phrase.Said("$opening $clause."))
        val parts = predicateConjuncts(requirement.predicate)
        val heading = if (parts.size == 1) "$opening this holds:" else "$opening all of these hold:"
        return DescribedDeclaration(compared.pointer, label(), Phrase.Said(heading), parts.map { part -> partPhrase(part) })
    }

    private fun label(): String = when (val declaration = compared.declaration) {
        is ShapeDecl -> if (declaration.exposed) "the act ${declaration.name}" else "the shape ${declaration.name}"
        is RefinementDecl -> "the refinement ${declaration.name}"
        is RuleDecl -> "the rule ${declaration.name}"
        is NeverDecl -> "this prohibition over ${compared.subject}"
        is EnumDecl -> "the list of words ${declaration.name}"
        is UsesDecl -> "this `uses` line"
        is VisibilityDefault -> "this `visible` line"
    }
}
