package velle

import velle.Sentences.Subject

/** A rule as a sentence: when it runs, for which record, and what it does. */
object RuleSentences {

    fun described(rule: RuleDecl, version: SpecVersion): String? {
        if (rule.by != null) return null
        val timing = timing(rule, version) ?: return null
        val effects = rule.body.map { item -> effect(item) ?: return null }
        if (effects.isEmpty()) return null
        return "$timing, it " + effects.joinToString(", then ")
    }

    /** When the rule runs and for which record: "when a SubmitPayment arrives". Null when there are no words for it. */
    fun timing(rule: RuleDecl, version: SpecVersion, subject: Subject = Subject.record): String? {
        val base = version.baseOf(Sentences.leftmostName(rule.condition))
        val record = Sentences.withArticle(base)
        val schedules = rule.triggers.filter { trigger -> trigger != "commit" }
        val atCommit = rule.preposition == null || "commit" in rule.triggers
        if (!atCommit) return sweep(rule, version, record, schedules, subject)
        val event = event(rule, version, base, record, subject) ?: return null
        val healed = if (schedules.isEmpty()) "" else ", and at each ${schedules.joinToString(" and each ")} tick for any it missed"
        val after = if (rule.preposition == "after") " just after the commit" else ""
        return "$event$after$healed"
    }

    /** A rule that runs only at a schedule's tick, over every record its condition holds for. */
    private fun sweep(rule: RuleDecl, version: SpecVersion, record: String, schedules: List<String>, subject: Subject): String? {
        if (rule.trigger != Trigger.ENTRY) return null
        val ticks = "at each " + schedules.joinToString(" and each ") + " tick"
        val every = "$ticks, for every ${record.substringAfter(' ')}"
        return when (val condition = condition(rule, version, subject)) {
            Condition.BaseAlone -> every
            Condition.Unsaid -> null
            is Condition.Said -> "$every of which it is true that ${condition.clause}"
        }
    }

    private fun event(rule: RuleDecl, version: SpecVersion, base: String, record: String, subject: Subject): String? {
        val condition = when (val said = condition(rule, version, subject)) {
            Condition.BaseAlone -> null
            Condition.Unsaid -> return null
            is Condition.Said -> said.clause
        }
        if (rule.trigger == Trigger.ENTRY) return entryEvent(condition, base in version.acts, record)
        if (rule.trigger == Trigger.DELETED) return "when $record is deleted" + (condition?.let { clause -> " while $clause" } ?: "")
        val stops = "when it stops being true of $record that ${condition ?: return null}"
        return when (rule.trigger) {
            Trigger.LEAVING_OR_DELETED -> "$stops, or the $base is deleted while it is"
            Trigger.LEAVING_NOT_DELETED -> "$stops, unless because the $base is deleted"
            else -> stops
        }
    }

    private fun entryEvent(condition: String?, isAct: Boolean, record: String): String = when {
        condition == null && isAct -> "when $record arrives"
        condition == null -> "when $record is created"
        else -> "when it becomes true of $record that $condition"
    }

    /** What a rule's condition says beyond which records it is over. */
    private sealed interface Condition {
        /** The condition is the base shape alone, and says nothing more than which records. */
        data object BaseAlone : Condition

        /** There are no words for the condition. */
        data object Unsaid : Condition

        data class Said(val clause: String) : Condition
    }

    /**
     * The rule's condition in words. The shared words treat a condition with no words as the base
     * shape alone, and so leave it out of the sentence; the comparison of two specs has always said
     * a rule that way, and its reports are pinned. A description says no sentence instead.
     */
    private fun condition(rule: RuleDecl, version: SpecVersion, subject: Subject): Condition {
        val condition = rule.condition
        if (condition is RefName && condition.where == null && condition.name in version.baseShapes) return Condition.BaseAlone
        val clause = Sentences.clause(condition, version.baseShapes, subject)
        return when {
            clause != null -> Condition.Said(clause)
            subject.vocabulary == Sentences.Vocabulary.SHARED -> Condition.BaseAlone
            else -> Condition.Unsaid
        }
    }

    /** One line of a rule's body as what the rule does: "creates a Receipt with ...". Null when there are no words for it. */
    fun effect(item: BodyItem, subject: Subject = Subject.record): String? = when (item) {
        is Creation -> creation(item, subject)
        is Assignment -> {
            val target = Sentences.value(item.target, subject)
            val value = Sentences.value(item.value, subject)
            if (target == null || value == null) null else "sets $target to $value"
        }
        is DeleteStmt -> Sentences.value(item.target, subject)?.let { target -> "deletes $target" }
        else -> null
    }

    private fun creation(item: Creation, subject: Subject): String? {
        if (item.alias != null) return null
        val owner = item.forExpr?.let { owner ->
            if (subject.vocabulary == Sentences.Vocabulary.SHARED) return null
            " for " + (Sentences.value(owner, subject) ?: return null)
        } ?: ""
        val fields = item.fields.map { field ->
            val value = Sentences.value(field.value, subject) ?: return null
            "${field.name} set to $value"
        }
        val withFields = if (fields.isEmpty()) "" else " with " + fields.joinToString(", ")
        return "creates ${Sentences.withArticle(item.shape)}$owner$withFields"
    }
}
