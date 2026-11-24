package velle

/** A rule as a sentence: when it runs, for which record, and what it does. */
object RuleSentences {

    fun described(rule: RuleDecl, version: SpecVersion): String? {
        if (rule.by != null) return null
        val timing = timing(rule, version) ?: return null
        val effects = rule.body.map { item -> effect(item) ?: return null }
        if (effects.isEmpty()) return null
        return "$timing, it " + effects.joinToString(", then ")
    }

    private fun timing(rule: RuleDecl, version: SpecVersion): String? {
        val base = version.baseOf(Sentences.leftmostName(rule.condition))
        val record = Sentences.withArticle(base)
        val schedules = rule.triggers.filter { trigger -> trigger != "commit" }
        val atCommit = rule.preposition == null || "commit" in rule.triggers
        if (!atCommit) return sweep(rule, version, record, schedules)
        val event = event(rule, version, base, record) ?: return null
        val healed = if (schedules.isEmpty()) "" else ", and at each ${schedules.joinToString(" and each ")} tick for any it missed"
        val after = if (rule.preposition == "after") " just after the commit" else ""
        return "$event$after$healed"
    }

    /** A rule that runs only at a schedule's tick, over every record its condition holds for. */
    private fun sweep(rule: RuleDecl, version: SpecVersion, record: String, schedules: List<String>): String? {
        if (rule.trigger != Trigger.ENTRY) return null
        val ticks = "at each " + schedules.joinToString(" and each ") + " tick"
        val condition = conditionClause(rule, version) ?: return "$ticks, for every ${record.substringAfter(' ')}"
        return "$ticks, for every ${record.substringAfter(' ')} of which it is true that $condition"
    }

    private fun event(rule: RuleDecl, version: SpecVersion, base: String, record: String): String? {
        val condition = conditionClause(rule, version)
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

    /** The rule's condition as a clause, or null when it is the base shape alone and says nothing more. */
    private fun conditionClause(rule: RuleDecl, version: SpecVersion): String? {
        val condition = rule.condition
        if (condition is RefName && condition.where == null && condition.name in version.baseShapes) return null
        return Sentences.clause(condition, version.baseShapes)
    }

    private fun effect(item: BodyItem): String? = when (item) {
        is Creation -> creation(item)
        is Assignment -> {
            val target = Sentences.value(item.target, Sentences.Subject.record)
            val value = Sentences.value(item.value, Sentences.Subject.record)
            if (target == null || value == null) null else "sets $target to $value"
        }
        is DeleteStmt -> Sentences.value(item.target, Sentences.Subject.record)?.let { target -> "deletes $target" }
        else -> null
    }

    private fun creation(item: Creation): String? {
        if (item.forExpr != null || item.alias != null) return null
        val fields = item.fields.map { field ->
            val value = Sentences.value(field.value, Sentences.Subject.record) ?: return null
            "${field.name} set to $value"
        }
        val withFields = if (fields.isEmpty()) "" else " with " + fields.joinToString(", ")
        return "creates ${Sentences.withArticle(item.shape)}$withFields"
    }
}
