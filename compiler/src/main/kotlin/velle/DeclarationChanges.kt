package velle

/**
 * The sentence for one declaration added, removed, or changed between two specs. Each
 * declaration kind has its own words; a declaration the words cannot cover is given as Velle.
 */
object DeclarationChanges {

    fun added(after: Compared): Edit {
        val description = DeclarationSentences.described(after) ?: return withoutWords(null, after)
        return Edit(DeclarationSentences.addedSentence(after, description), after.pointer, subject = after.subject)
    }

    fun removed(before: Compared): Edit {
        val description = DeclarationSentences.described(before) ?: return withoutWords(before, null)
        return Edit(DeclarationSentences.removedSentence(before, description), before.pointer, subject = before.subject)
    }

    fun changed(before: Compared, after: Compared): List<Edit> {
        val beforeDeclaration = before.declaration
        val afterDeclaration = after.declaration
        if (beforeDeclaration is ShapeDecl && afterDeclaration is ShapeDecl) {
            return ShapeChanges.changed(before, beforeDeclaration, after, afterDeclaration)
        }
        val described = DeclarationSentences.described(after)
        val describedBefore = DeclarationSentences.described(before)
        if (described == null || describedBefore == null || described == describedBefore) {
            return listOf(withoutWords(before, after))
        }
        return listOf(Edit(DeclarationSentences.changedSentence(after, described, describedBefore), after.pointer, subject = after.subject))
    }

    fun withoutWords(before: Compared?, after: Compared?): Edit {
        val pointer = (after ?: before)!!.pointer
        return Edit(null, pointer, velleBefore = before?.printed, velleAfter = after?.printed, subject = (after ?: before)!!.subject)
    }
}

/** What one declaration says, as a sentence, and the sentences for adding, removing, and changing it. */
object DeclarationSentences {

    /** The declaration described in the present tense, or null when there are no words for it. */
    fun described(compared: Compared): String? = when (val declaration = compared.declaration) {
        is RefinementDecl -> refinementCondition(declaration, compared.version)
        is NeverDecl -> prohibition(declaration, compared.version)
        is RuleDecl -> RuleSentences.described(declaration, compared.version)
        is ShapeDecl -> ShapeChanges.described(declaration)
        is EnumDecl -> "one of the words " + declaration.words.joinToString(", ") { word -> "\"$word\"" }
        is UsesDecl -> "${declaration.name} is taken from ${declaration.qualified.substringBeforeLast('.')}"
        is VisibilityDefault -> null
    }

    fun addedSentence(compared: Compared, description: String): String =
        presenceSentence(compared, description, Presence("New", "is", "now"))

    fun removedSentence(compared: Compared, description: String): String =
        presenceSentence(compared, description, Presence("Removed", "was", "no longer"))

    /** The words that differ between a declaration added and one removed. */
    private class Presence(val label: String, val verb: String, val timing: String)

    private fun presenceSentence(compared: Compared, description: String, presence: Presence): String =
        when (val declaration = compared.declaration) {
            is RefinementDecl ->
                "${presence.label}: ${baseArticle(declaration, compared.version)} ${presence.verb} ${Sentences.withArticle(declaration.name)} when $description."
            is NeverDecl -> Sentences.capitalized(prohibitionSentence(declaration, compared.version, presence.timing, description))
            else -> "${presence.label}: ${compared.name?.let { name -> "$name, " } ?: ""}$description."
        }

    fun changedSentence(after: Compared, description: String, descriptionBefore: String): String =
        when (val declaration = after.declaration) {
            is RefinementDecl ->
                "${Sentences.capitalized(baseArticle(declaration, after.version))} is now ${Sentences.withArticle(declaration.name)} " +
                    "when $description. Before: when $descriptionBefore."
            else -> "${after.name ?: "The spec"} changed. Now: $description. Before: $descriptionBefore."
        }

    private fun baseArticle(declaration: RefinementDecl, version: SpecVersion): String =
        Sentences.withArticle(version.baseOf(declaration.name))

    private fun refinementCondition(declaration: RefinementDecl, version: SpecVersion): String? {
        if (declaration.members.isNotEmpty()) return null
        return Sentences.clause(declaration.expr, version.baseShapes)
    }

    /** What the prohibition forbids, as a clause about the record it is over, for a `never`; what it
     *  demands, for a requirement. */
    fun forbidden(declaration: NeverDecl, version: SpecVersion): String? = prohibition(declaration, version)

    private fun prohibition(declaration: NeverDecl, version: SpecVersion): String? {
        if (declaration.keyDeclaration != null || declaration.fromActorClause) return null
        val requirement = declaration.requirement
        if (requirement != null) return Sentences.predicate(requirement.predicate, Sentences.Subject.record)
        return Sentences.clause(declaration.target, version.baseShapes)
    }

    /**
     * `never` over an act is a refusal: the act is refused. Over a kept shape it is a
     * constraint on the books: such a record is forbidden. A requirement says the opposite way
     * round, "unless".
     */
    private fun prohibitionSentence(declaration: NeverDecl, version: SpecVersion, timing: String, description: String): String {
        val subject = declaration.requirement?.subject ?: Sentences.leftmostName(declaration.target)
        val base = version.baseOf(subject)
        val verdict = if (base in version.acts) "refused" else "forbidden"
        val connective = if (declaration.requirement != null) "unless" else "when"
        return "${Sentences.withArticle(subject)} is $timing $verdict $connective $description."
    }
}
