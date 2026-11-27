package velle.validation

import velle.*
import velle.spec.PathStart
import velle.spec.Scope

// ── V23: delete statement legality (OQ37) ────────────────────────────────
//
// The statement: literal static to-one path; a shape known to the
// spec, never a transient act; one deleter per instance per commit (two
// deleters of one shape at one commit is the coincidence error); and a
// commit never both writes a field of an instance and deletes it — there
// is no business sentence "change it and also remove it, at once" (fail
// closed, pending a use case). The shared delete scope and the principle
// behind every delete check are in DeleteScope.kt (checks.md V23).

internal fun Validator.checkDeleteStatements() {
    for (rule in model.rules.values) {
        val scope = subjectScope(rule) ?: continue
        for (deleteStmt in rule.body.filterIsInstance<DeleteStmt>()) checkDeleteStatement(rule, deleteStmt, scope)
    }
    // cross-rule: one deleter per instance per commit, and no co-firing
    // writer of a shape a sibling deletes — both fail closed at shape
    // granularity (the coarse slice of the instance-level rulings)
    checkCoincidentDeleters()
    checkWritersBesideDeleters()
}

/** One `delete` in [rule]'s body, whose subject is a [scope]: its target, and what the same body does to it. */
private fun Validator.checkDeleteStatement(rule: RuleDecl, deleteStmt: DeleteStmt, scope: String) {
    val target = deleteStmt.target
    val deletedShape = deletedShape(target, scope)
    if (deletedShape == null) {
        if (!isTheSubjectItself(target))
            diagnostics.add(Diagnostic("V23", "rule '${rule.name}' deletes '${Printer.expr(target)}', which does " +
                "not name one record through 'one' fields; a delete removes one record, named by a " +
                "fixed path such as 'delete this' or 'delete listing' (Reference §12)"))
        return
    }
    if (deletedShape in model.transients)
        diagnostics.add(Diagnostic("V23", "rule '${rule.name}' deletes the transient act '$deletedShape', which is " +
            "not kept after its commit, so there is nothing to delete (Reference §4)"))
    // a collection anywhere in the target is a fan-out — refused
    if (pathHasCollectionHop(target, scope))
        diagnostics.add(Diagnostic("V23", "rule '${rule.name}' deletes '${Printer.expr(target)}', which goes " +
            "through a collection; a delete removes one record, so write a rule whose subject is " +
            "each record to delete, and it fires once per record (Reference §12)"))
    checkSameBodyUses(rule, target)
}

/** The base shape [target] deletes from a [scope] subject, null when the path names no one record. */
private fun Validator.deletedShape(target: PathExpr, scope: String): String? {
    if (isTheSubjectItself(target)) return model.baseOf(scope)
    resolvePath(target, scope) // F1 on unknown members
    return model.pathElementShape(target, scope)?.let { elementShape -> model.baseOf(elementShape) }
}

/** Same body: the same instance deleted twice, or written and deleted. */
private fun Validator.checkSameBodyUses(rule: RuleDecl, target: PathExpr) {
    val deletesOfTarget = rule.body.filterIsInstance<DeleteStmt>().count { deleteStmt -> deleteStmt.target == target }
    if (deletesOfTarget > 1)
        diagnostics.add(Diagnostic("V23", "rule '${rule.name}' deletes '${Printer.expr(target)}' twice in one body; delete it once"))
    for (assignment in rule.body.filterIsInstance<Assignment>()) {
        if (!assignsFieldOf(assignment, target)) continue
        diagnostics.add(Diagnostic("V23", "rule '${rule.name}' both assigns '${Printer.expr(assignment.target)}' " +
            "and deletes '${Printer.expr(target)}' in one commit, and a record cannot be changed and " +
            "removed at once; drop the assignment, or keep the record (Reference §12)"))
    }
}

/** Whether [assignment] writes a field directly on the record [deleted] names. */
private fun assignsFieldOf(assignment: Assignment, deleted: PathExpr): Boolean {
    val written = assignment.target
    if (isTheSubjectItself(deleted))
        return (written.root == "this" && written.segs.size == 1) || (written.root != "this" && written.segs.isEmpty())
    return written.root == deleted.root &&
        written.segs.dropLast(1).map { segment -> segment.name } == deleted.segs.map { segment -> segment.name } &&
        written.segs.size == deleted.segs.size + 1
}

/** Two rules deleting one shape that can fire from one commit, with no proof they never delete the same record. */
private fun Validator.checkCoincidentDeleters() {
    val sites = model.deleteSites
    for ((index, first) in sites.withIndex()) {
        for (second in sites.drop(index + 1)) {
            if (first.rule === second.rule || first.shape != second.shape) continue
            if (provablyDisjoint(first.rule, second.rule) || !canCoFire(first.rule, second.rule)) continue
            diagnostics.add(Diagnostic("V23", "rules '${first.rule.name}' and '${second.rule.name}' can both fire from one " +
                "commit and each deletes a '${first.shape}', and the compiler cannot see that they never delete " +
                "the same record; condition one so the two cannot fire together (Reference §12)"))
        }
    }
}

/** A rule assigning a field of a shape another rule deletes, where both can fire from one commit. */
private fun Validator.checkWritersBesideDeleters() {
    for (site in model.deleteSites) {
        for (write in writes) {
            if (write.owner != site.shape || write.rule === site.rule) continue
            if (provablyDisjoint(write.rule, site.rule) || !canCoFire(write.rule, site.rule)) continue
            diagnostics.add(Diagnostic("V23", "rule '${write.rule.name}' assigns '${write.owner}.${write.member.name}' and " +
                "rule '${site.rule.name}' deletes a '${site.shape}', and both can fire from one commit, " +
                "so one record could be changed and removed at once; condition one so the two cannot " +
                "fire together (Reference §12)"))
        }
    }
}

/** Whether any step of [target], read from a [scope] subject, goes through a collection. */
private fun Validator.pathHasCollectionHop(target: PathExpr, scope: String): Boolean {
    val resolved = model.paths.resolve(target, Scope(scope))
    if (resolved.start !is PathStart.Subject && resolved.start !is PathStart.Element) return false
    return resolved.steps.any { step -> step.member.type is VType.Coll || step.member.type is VType.CollS }
}
