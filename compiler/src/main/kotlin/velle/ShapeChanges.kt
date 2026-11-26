package velle

/** The words for a shape or act, and for the fields added to it, removed from it, or changed on it. */
object ShapeChanges {

    fun described(shape: ShapeDecl): String? {
        val fields = shape.members.filterNot { member -> member is ActorProp }
            .map { member -> fieldPhrase(member) ?: return null }
        val actor = shape.actor?.let { actor -> ", made by ${Sentences.withArticle(actor.shape)}" } ?: ""
        if (shape.with.isNotEmpty()) return null
        val taken = shape.takes.map { taken -> "every field $taken declares" }
        val withFields = if (taken.isEmpty() && fields.isEmpty()) "" else ", with " + (taken + fields).joinToString(", ")
        return kindPhrase(shape) + actor + withFields
    }

    private fun kindPhrase(shape: ShapeDecl): String = when {
        shape.transient -> "a request someone makes, not kept"
        shape.exposed -> "a request someone makes, kept as a record"
        else -> "a record the system keeps"
    }

    /** One field: `total (worked out as the sum of amount over its lineItems)`, in the words of [MemberSentences]. */
    fun fieldPhrase(member: Member): String? = when (member) {
        is StoredProp -> storedPhrase(member)
        is DerivedProp -> if (member.visible.isNotEmpty()) null else MemberSentences.workedOut(member.expr)?.let { worked -> "${member.name} (worked out as $worked)" }
        is TimestampProp -> "${member.name} (the time it was ${if (member.on == "create") "created" else "last changed"})"
        else -> null
    }

    private fun storedPhrase(member: StoredProp): String? {
        if (member.declaredOn != null) return null
        val kind = MemberSentences.storedKind(member) ?: return null
        val unique = if (member.unique) ", no two the same" else ""
        val transient = if (member.transient) ", not kept" else ""
        return "${member.name} ($kind$unique$transient)"
    }

    /** A changed shape: one edit per field added, removed, or changed, or the whole shape as Velle
     *  when what changed is not a field. */
    fun changed(before: Compared, beforeShape: ShapeDecl, after: Compared, afterShape: ShapeDecl): List<Edit> {
        val headerChanged = beforeShape.exposed != afterShape.exposed || beforeShape.transient != afterShape.transient ||
            beforeShape.takes != afterShape.takes || beforeShape.with != afterShape.with ||
            beforeShape.actor != afterShape.actor
        if (headerChanged) return listOf(DeclarationChanges.withoutWords(before, after))
        val beforeFields = beforeShape.members.associateBy { member -> memberKey(member) }
        val afterFields = afterShape.members.associateBy { member -> memberKey(member) }
        val fieldChanges = (beforeFields.keys + afterFields.keys)
            .map { key -> fieldChange(afterShape.name, beforeFields[key], afterFields[key]) }
        if (fieldChanges.any { change -> change is FieldChange.WithoutWords }) {
            return listOf(DeclarationChanges.withoutWords(before, after))
        }
        return fieldChanges.filterIsInstance<FieldChange.Said>()
            .map { change -> Edit(change.sentence, after.pointer, subject = afterShape.name) }
    }

    /** What happened to one field between the two versions of a shape. */
    private sealed interface FieldChange {
        data object Unchanged : FieldChange
        data object WithoutWords : FieldChange
        data class Said(val sentence: String) : FieldChange
    }

    private fun memberKey(member: Member): String = when (member) {
        is StoredProp -> member.name
        is DerivedProp -> member.name
        is TimestampProp -> member.name
        else -> Printer.member(member)
    }

    private fun fieldChange(shapeName: String, before: Member?, after: Member?): FieldChange {
        if (before != null && after != null && Printer.member(before) == Printer.member(after)) return FieldChange.Unchanged
        val beforePhrase = before?.let { member -> fieldPhrase(member) ?: return FieldChange.WithoutWords }
        val afterPhrase = after?.let { member -> fieldPhrase(member) ?: return FieldChange.WithoutWords }
        return when {
            beforePhrase == null -> FieldChange.Said("$shapeName now has the field $afterPhrase.")
            afterPhrase == null -> FieldChange.Said("$shapeName no longer has the field $beforePhrase.")
            beforePhrase == afterPhrase -> FieldChange.WithoutWords
            else -> FieldChange.Said("$shapeName's field is now $afterPhrase. Before: $beforePhrase.")
        }
    }
}
