package velle

import velle.Sentences.Subject

/**
 * The declaration a body line belongs to: a shape or act, or a refinement of [base]. A body line of
 * a refinement holds only while the record is one, "While it is an IssuedInvoice, its due cannot change."
 */
data class MemberOwner(val name: String, val base: String, val refinement: Boolean)

/**
 * One line of a shape, act, or refinement body as a sentence about "it", for `velle describe`:
 * "Its customer is one Customer." Null when there are no words for the line, and [formOf] then
 * names what keeps it from having them.
 */
object MemberSentences {

    private val subject = Subject.record

    fun said(member: Member, owner: MemberOwner): String? = when (member) {
        is StoredProp -> stored(member, owner)
        is DerivedProp -> derived(member, owner)
        is TimestampProp -> if (member.visible.isNotEmpty()) null else "Its ${member.name} is the time it was ${if (member.on == "create") "created" else "last changed"}."
        is ActorProp -> actor(member)
        is FrozenClause -> frozen(member, owner)
        UndeletableClause -> if (owner.refinement) "While it is ${Sentences.withArticle(owner.name)}, it cannot be deleted." else "It cannot be deleted."
        is UniqueKey -> "No two ${owner.base} records have the same ${member.fields.joinToString(" and ")}."
        is VisibleClause -> null
    }

    /** What keeps a body line from having words, for the work list of forms. */
    fun formOf(member: Member): String = when (member) {
        is StoredProp -> storedForm(member)
        is DerivedProp -> derivedForm(member)
        is TimestampProp -> "a field with `visible to`"
        is VisibleClause -> "`visible to` on a shape"
        else -> "`${Printer.member(member)}`"
    }

    private fun storedForm(member: StoredProp): String = when {
        member.visible.isNotEmpty() -> "a field with `visible to`"
        member.tolerates != null -> "a field that `tolerates ${member.tolerates}`"
        else -> member.initially?.let { initial -> FormsWithoutWords.of(initial, subject) } ?: "`${Printer.member(member)}`"
    }

    private fun derivedForm(member: DerivedProp): String = when {
        member.visible.isNotEmpty() -> "a field with `visible to`"
        else -> FormsWithoutWords.of(member.expr, subject) ?: "`${Printer.member(member)}`"
    }

    private fun stored(member: StoredProp, owner: MemberOwner): String? {
        val kind = storedKind(member) ?: return null
        val unique = if (member.unique) ", and no two ${owner.base} records have the same one" else ""
        val transient = if (member.transient) ", and is not kept" else ""
        val verb = if (member.type.many()) "are" else "is"
        return "Its ${member.name} $verb $kind$unique$transient."
    }

    /**
     * What a stored field holds and how it starts: "one Seller, which must be given when it is
     * created and may be absent later", "a Money, starting as 0". The comparison of two specs says
     * a field in the same words. Null when there are no words for the field.
     */
    fun storedKind(member: StoredProp): String? {
        if (member.visible.isNotEmpty()) return null
        val keepsRepeats = member.tolerates == "duplication" && (member.type as? ScalarType)?.many == true
        if (member.tolerates != null && !keepsRepeats) return null
        val starting = member.initially?.let { initial ->
            val initialWords = Sentences.value(initial, subject) ?: return null
            ", starting as $initialWords"
        } ?: ""
        val type = if (member.initiallyRequired) requiredAtCreation(member.type) else typeWords(member.type)
        val repeats = if (keepsRepeats) ", kept in the order given, repeats included" else ""
        return "$type$repeats$starting"
    }

    private fun TypeRef.many(): Boolean = when (this) {
        is ScalarType -> many
        is RelType -> many
    }

    /** "one Customer", "a Date, and may be absent", "any number of text values". */
    fun typeWords(type: TypeRef): String = when (type) {
        is ScalarType -> when {
            type.many -> "any number of ${type.name} values"
            else -> Sentences.withArticle(type.name) + absent(type.optional)
        }
        is RelType -> (if (type.many) "any number of ${type.shape} records" else "one ${type.shape}") + absent(type.optional)
    }

    /** `seller: one Seller? initially required`: given when the record is created, and absent only later. */
    private fun requiredAtCreation(type: TypeRef): String {
        val present = when (type) {
            is ScalarType -> typeWords(type.copy(optional = false))
            is RelType -> typeWords(type.copy(optional = false))
        }
        return "$present, which must be given when it is created and may be absent later"
    }

    private fun absent(optional: Boolean): String = if (optional) ", and may be absent" else ""

    private fun derived(member: DerivedProp, owner: MemberOwner): String? {
        if (member.visible.isNotEmpty()) return null
        val worked = workedOut(member.expr) ?: return null
        val verb = if (member.type?.many() == true || member.expr is SetExpr || member.expr is WalkExpr) "are" else "is"
        if (!member.captured) return "Its ${member.name} $verb worked out as $worked."
        return "When it becomes ${Sentences.withArticle(owner.name)}, it records its ${member.name} as $worked at that moment."
    }

    /**
     * A derived field's value. An `if ... then ... else` is said only here, at the top of the field,
     * where "X when C, otherwise Y" cannot be read as part of a larger value.
     */
    fun workedOut(expression: Expr): String? {
        if (expression !is IfExpr) return Sentences.value(expression, subject)
        val condition = Sentences.predicate(expression.condition, subject) ?: return null
        val chosen = Sentences.value(expression.thenExpr, subject) ?: return null
        val otherwise = workedOut(expression.elseExpr) ?: return null
        return "$chosen when $condition, otherwise $otherwise"
    }

    private fun actor(member: ActorProp): String {
        val made = "It is made by ${Sentences.withArticle(member.shape)}"
        return if (member.named) "$made, who is its ${member.name}." else "$made."
    }

    private fun frozen(member: FrozenClause, owner: MemberOwner): String {
        val change = if (member.fields.isEmpty()) "none of its fields can change" else "its ${member.fields.joinToString(" and ")} cannot change"
        return if (owner.refinement) "While it is ${Sentences.withArticle(owner.name)}, $change." else "${Sentences.capitalized(change)}."
    }
}
