package velle.validation

import velle.*

// ── F1/F2/F4/V13: expression and body validation ─────────────────────────

/** The shape a rule's condition names as its subject, or null with F1 or F2 when it names none. */
internal fun Validator.subjectScope(rule: RuleDecl): String? = when (val condition = rule.condition) {
    is RefName ->
        if (condition.name in model.shapes || condition.name in model.refinements) condition.name
        else { diagnostics.add(Diagnostic("F1", "rule '${rule.name}' — unknown condition '${condition.name}'")); null }
    else -> model.baseOfExpr(rule.condition) ?: run {
        if (model.basesOfExpr(rule.condition).size > 1)
            diagnostics.add(Diagnostic("F2", "rule '${rule.name}' — its condition composes unrelated shapes inline; " +
                "name the union (`shape X = A or B`) and react to the name (Reference §9)"))
        null
    }
}

internal fun Validator.checkRuleNamesAndBody(rule: RuleDecl) {
    val scope = subjectScope(rule) ?: return
    // the rule's actor (Reference §22): an alias typed by the clause's shape,
    // in scope for the condition's predicate and the body
    rule.by?.let { actor -> scopeByAlias[actor.name] = actor.shape; ruleActor = actor }
    // the body's `as` names (Reference §14; V33): each stands for the record its
    // statement creates, as a reference, for the other statements of its level
    val created = checkCreationAliases(rule, scope)
    ruleNarrowing = model.types.narrowingOf(rule.condition, scope)
    ruleBeingChecked = rule
    try {
        (rule.condition as? RefName)?.where?.let { predicate -> checkExpr(predicate, scope) }
        checkBody(rule, scope, rule.body, created)
    } finally {
        rule.by?.let { actor -> scopeByAlias.remove(actor.name); ruleActor = null }
        ruleNarrowing = Narrowing.NONE
        ruleBeingChecked = null
    }
}

/** One level of a body: the rule's, or a block's. The level's `as` names are
 *  in scope for every statement of the level and the blocks inside it. */
internal fun Validator.checkBody(rule: RuleDecl, scope: String, items: List<BodyItem>, created: Map<String, String>) {
    val shapeByAliasOfThisLevel = items.filterIsInstance<Creation>().mapNotNull { creation ->
        creation.alias?.let { alias -> created[alias]?.let { shape -> alias to shape } }
    }
    shapeByAliasOfThisLevel.forEach { (alias, shape) -> scopeByAlias[alias] = shape }
    try {
        for (item in items) when (item) {
            is Assignment -> checkAssignment(rule, scope, item)
            is Creation -> checkCreation(rule, scope, item)
            is DeleteStmt -> {} // legality is V23's (checkDeleteStatements)
            ThenMarker -> {}
            is ForEach -> checkForEach(rule, scope, item, created)
        }
    } finally {
        shapeByAliasOfThisLevel.forEach { (alias, _) -> scopeByAlias.remove(alias) }
    }
}
