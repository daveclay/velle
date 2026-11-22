package velle.validation

import velle.*

// ── V24 (deletion side, OQ37-R14): a deleted subject is not kept ─────────
//
// A deletion-side body (`when deleted X`, `when leaving or deleted X`) may
// not keep a reference to its subject: the product would outlive the
// deleted instance. It copies the fields it needs; a predicate-exit body
// may keep the reference (Reference §13, "One reading rule").

internal fun Validator.checkDeletionSideReferences() {
    for (rule in model.rules.values) {
        if (!rule.trigger.onDeletion) continue
        if (!keepsReferenceToSubject(rule)) continue
        diagnostics.add(Diagnostic("V24", "rule '${rule.name}' — a 'when ${rule.trigger.keyword.trim()}' body " +
            "may not keep a reference to its subject ('this'): the record will not exist after the " +
            "deleting commit. Copy the fields that must survive instead (Reference §13)"))
    }
}

/** Whether a value [rule]'s body creates or assigns is `this` itself. */
private fun keepsReferenceToSubject(rule: RuleDecl): Boolean =
    rule.body.statements().any { statement ->
        when (statement) {
            is Creation -> statement.forExpr?.let(::readsSubjectWhole) == true ||
                statement.fields.any { field -> readsSubjectWhole(field.value) }
            is Assignment -> readsSubjectWhole(statement.value)
            else -> false
        }
    }

/** Whether [expr] is, or holds as an operand, the bare `this`. A flat table of the expression forms that pass a value through. */
private fun readsSubjectWhole(expr: Expr): Boolean = when (expr) {
    is PathExpr -> expr.root == "this" && expr.segs.isEmpty()
    is Binary -> readsSubjectWhole(expr.left) || readsSubjectWhole(expr.right)
    is UnaryMinus -> readsSubjectWhole(expr.inner)
    is NotExpr -> readsSubjectWhole(expr.inner)
    is IfExpr -> readsSubjectWhole(expr.condition) || readsSubjectWhole(expr.thenExpr) || readsSubjectWhole(expr.elseExpr)
    is Interpolation -> expr.pieces.any { piece -> readsSubjectWhole(piece) }
    is FunCall -> expr.args.any { argument -> readsSubjectWhole(argument) }
    else -> false
}
