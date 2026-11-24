package velle

/**
 * One version of a spec, read for comparison: its declarations with the line each starts on.
 * [writtenSource] is the spec as its author wrote it, for pointing at a line; it differs from
 * [source] only when the comparison has carried renames over into an older version.
 */
class SpecVersion(val fileName: String, val source: String, val writtenSource: String = source) {
    val declarations: List<LocatedDecl> = Parser.parseLocated(source)

    val model: Model by lazy { Model(declarations.map { located -> located.decl }) }

    /** This version with [renames] applied to every name it spells, at the same lines. */
    fun renamed(renames: Map<String, String>): SpecVersion =
        if (renames.isEmpty()) this else SpecVersion(fileName, Renames.applied(source, renames), writtenSource)

    private val shapes: List<ShapeDecl> = declarations.map { located -> located.decl }.filterIsInstance<ShapeDecl>()
    private val refinements: Map<String, RefinementDecl> = declarations.map { located -> located.decl }
        .filterIsInstance<RefinementDecl>().associateBy { refinement -> refinement.name }

    /** The names of the spec's shapes and acts: what a refinement or a rule narrows in the end. */
    val baseShapes: Set<String> = shapes.map { shape -> shape.name }.toSet()

    val acts: Set<String> = shapes.filter { shape -> shape.exposed }.map { shape -> shape.name }.toSet()

    /** The refinements [name] is made from, at any depth: `ActionableOverdue = OverdueInvoice and not
     *  ArchivedInvoice` reads OverdueInvoice and ArchivedInvoice and whatever those are made from. */
    fun refinementsReadBy(name: String, seen: Set<String> = emptySet()): Set<String> {
        val refinement = refinements[name] ?: return emptySet()
        if (name in seen) return emptySet()
        val direct = refinementNames(refinement.expr).filter { read -> read in refinements }.toSet()
        return direct + direct.flatMap { read -> refinementsReadBy(read, seen + name) }
    }

    private fun refinementNames(expression: RefExpr): Set<String> = when (expression) {
        is RefName -> setOf(expression.name)
        is RefNot -> refinementNames(expression.inner)
        is RefAnd -> refinementNames(expression.left) + refinementNames(expression.right)
        is RefOr -> refinementNames(expression.left) + refinementNames(expression.right)
    }

    /** The shape or act a name narrows: itself for a shape, the base of its leftmost name for a refinement. */
    fun baseOf(name: String): String {
        val refinement = refinements[name] ?: return name
        return baseOf(Sentences.leftmostName(refinement.expr))
    }
}

/** Where in a spec a sentence comes from: the file, the line, and the declaration as written. */
data class Pointer(val fileName: String, val line: Int, val declaration: String) {
    fun rendered(): String = "In the spec: $fileName, line $line, `$declaration`"
}

/**
 * One edit between two specs. [sentence] says it in the business's words. When the report has
 * no words for it, [sentence] is null and [velleBefore] and [velleAfter] hold the declaration
 * as written, so the edit is never dropped.
 */
data class Edit(
    val sentence: String?,
    val pointer: Pointer,
    val velleBefore: String? = null,
    val velleAfter: String? = null,
    val subject: String = "",
)

/**
 * Two versions of a spec matched declaration by declaration: renames found and carried into the
 * older version, then each declaration paired with its counterpart by kind and name.
 */
class Comparison(val before: SpecVersion, val after: SpecVersion) {
    private val afterDeclarations = after.declarations.map { located -> Compared(located, after) }
    val renames: Renames = Renames.found(before.declarations.map { located -> Compared(located, before) }, afterDeclarations)

    /** The older version with the renames applied, so its names are the newer version's. */
    val beforeRenamed: SpecVersion = before.renamed(renames.map)

    val beforeByKey: Map<String, Compared> =
        beforeRenamed.declarations.map { located -> Compared(located, beforeRenamed) }.associateBy { compared -> compared.key }
    val afterByKey: Map<String, Compared> = afterDeclarations.associateBy { compared -> compared.key }

    val inBoth: List<Pair<Compared, Compared>> =
        afterByKey.keys.intersect(beforeByKey.keys).map { key -> beforeByKey.getValue(key) to afterByKey.getValue(key) }

    /** Declarations in both whose agreed spellings differ: a change of meaning. */
    val changed: List<Pair<Compared, Compared>> = inBoth.filter { (old, new) -> old.agreed != new.agreed }

    val removed: List<Compared> = (beforeByKey.keys - afterByKey.keys).map { key -> beforeByKey.getValue(key) }
    val added: List<Compared> = (afterByKey.keys - beforeByKey.keys).map { key -> afterByKey.getValue(key) }
}

/**
 * Reads two versions of a spec and says what was edited, in the names the spec gives things and
 * everyday words, each sentence pointing at the place in the spec that says it; then what the
 * edits change for declarations nobody edited; then what they do to a record like the ones the
 * system keeps.
 */
object SpecComparison {

    fun report(before: SpecVersion, after: SpecVersion): String {
        val comparison = Comparison(before, after)
        val edits = edits(comparison)
        if (edits.isEmpty()) return ComparisonReport.rendered(before, after, edits)
        val consequences = Consequences.between(SpecFacts(comparison.beforeRenamed), SpecFacts(after))
        val records = HypotheticalRecords.of(comparison)
        return ComparisonReport.rendered(before, after, edits, consequences, records)
    }

    fun edits(before: SpecVersion, after: SpecVersion): List<Edit> = edits(Comparison(before, after))

    private fun edits(comparison: Comparison): List<Edit> {
        val renamed = comparison.renames.pairs.map { pair -> renameEdit(pair.first, pair.second) }
        val changed = comparison.changed.flatMap { (old, new) -> DeclarationChanges.changed(old, new) }
        val reworded = comparison.inBoth.filter { (old, new) -> old.agreed == new.agreed && old.printed != new.printed }
            .mapNotNull { (old, new) -> rewordEdit(old, new) }
        val removed = comparison.removed.map { compared -> DeclarationChanges.removed(compared) }
        val added = comparison.added.map { compared -> DeclarationChanges.added(compared) }
        return (renamed + changed + reworded + removed + added).sortedBy { edit -> edit.pointer.line }
    }

    /** A declaration written differently that means the same. Said only when that rests on a claim an
     *  extension made, naming the claim; a rewording the language's own facts settle is no change. */
    private fun rewordEdit(before: Compared, after: Compared): Edit? {
        val claims = before.claimsReliedOn + after.claimsReliedOn
        if (claims.isEmpty()) return null
        val sentence = "${after.name ?: "A declaration"} is written differently and means the same, if ${claims.joinToString(" and ")}."
        return Edit(sentence, after.pointer, subject = after.name ?: "")
    }

    private fun renameEdit(before: Compared, after: Compared): Edit {
        val oldName = before.name ?: ""
        val newName = after.name ?: ""
        val warning = if (after.declaration is ShapeDecl) {
            " Records already stored as $oldName are not carried over by this change."
        } else {
            ""
        }
        return Edit("$oldName is now called $newName. Nothing else about it changed.$warning", after.pointer, subject = newName)
    }
}

/** A declaration of one spec version, with the key it is matched by across versions. */
class Compared(val located: LocatedDecl, val version: SpecVersion) {
    val declaration: Decl get() = located.decl
    val printed: String = Printer.print(located.decl)

    val name: String? = when (val declaration = located.decl) {
        is ShapeDecl -> declaration.name
        is RefinementDecl -> declaration.name
        is RuleDecl -> declaration.name
        is EnumDecl -> declaration.name
        else -> null
    }

    private val kind: String = when (located.decl) {
        is ShapeDecl -> "shape"
        is RefinementDecl -> "refinement"
        is RuleDecl -> "rule"
        is EnumDecl -> "words"
        is UsesDecl -> "uses"
        is VisibilityDefault -> "visible"
        is NeverDecl -> "never"
    }

    /** The name the declaration is about: its own, or for a prohibition the shape or act it is over. */
    val subject: String = name ?: when (val declaration = located.decl) {
        is NeverDecl -> declaration.requirement?.subject ?: Sentences.leftmostName(declaration.target)
        else -> ""
    }

    val renamable: Boolean = located.decl is ShapeDecl || located.decl is RefinementDecl || located.decl is RuleDecl

    /** The declaration's first line as the author wrote it. */
    val pointer: Pointer = Pointer(
        version.fileName,
        located.line,
        version.writtenSource.lines()[located.line - 1].trim().removeSuffix("{").trim(),
    )

    fun printedUnder(renames: Map<String, String>): String = Renames.applied(printed, renames)

    private val spelling: AgreedSpelling by lazy { AgreedSpelling(version) }

    /** The declaration in its agreed spelling: equal for two declarations that mean the same. */
    val agreed: String by lazy { Printer.print(spelling.of(located.decl)) }

    /** The extension claims the agreed spelling rests on. */
    val claimsReliedOn: Set<String> get() = agreed.let { spelling.claimsReliedOn }

    /** What this declaration is matched by: its kind and name, or for a declaration with no name, what it says. */
    val key: String by lazy {
        val identity = when (val declaration = located.decl) {
            is UsesDecl -> declaration.qualified
            is NeverDecl -> agreed
            is VisibilityDefault -> ""
            else -> name
        }
        "$kind:$identity"
    }
}

/** Declarations that disappeared under one name and appeared under another with nothing else different. */
class Renames(val pairs: List<Pair<Compared, Compared>>) {
    val map: Map<String, String> = pairs.associate { pair -> (pair.first.name ?: "") to (pair.second.name ?: "") }

    companion object {
        fun applied(text: String, renames: Map<String, String>): String {
            if (renames.isEmpty()) return text
            val names = Regex("\\b(" + renames.keys.joinToString("|") { name -> Regex.escape(name) } + ")\\b")
            return names.replace(text) { match -> renames.getValue(match.value) }
        }

        /** Pairs found one at a time, each under the renames found before it, until no more are found. */
        fun found(before: List<Compared>, after: List<Compared>): Renames {
            val beforeNames = before.mapNotNull { compared -> compared.name }.toSet()
            val afterNames = after.mapNotNull { compared -> compared.name }.toSet()
            val removed = before.filter { compared -> compared.renamable && compared.name !in afterNames }
            val added = after.filter { compared -> compared.renamable && compared.name !in beforeNames }
            val pairs = mutableListOf<Pair<Compared, Compared>>()
            while (true) {
                val next = nextPair(removed, added, pairs) ?: return Renames(pairs)
                pairs.add(next)
            }
        }

        private fun nextPair(
            removed: List<Compared>,
            added: List<Compared>,
            pairsSoFar: List<Pair<Compared, Compared>>,
        ): Pair<Compared, Compared>? {
            val renamesSoFar = Renames(pairsSoFar).map
            val unpairedRemoved = removed.filter { compared -> pairsSoFar.none { pair -> pair.first === compared } }
            val unpairedAdded = added.filter { compared -> pairsSoFar.none { pair -> pair.second === compared } }
            for (oldDeclaration in unpairedRemoved) {
                val match = unpairedAdded.firstOrNull { newDeclaration ->
                    val candidate = renamesSoFar + ((oldDeclaration.name ?: "") to (newDeclaration.name ?: ""))
                    oldDeclaration.printedUnder(candidate) == newDeclaration.printed
                }
                if (match != null) return oldDeclaration to match
            }
            return null
        }
    }
}
