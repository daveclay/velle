package velle.validation

import velle.*

// ── F2: the subject of `is held` (Reference §10) ─────────────────────────
//
// `x is held` asks whether a required reference points at `x` (V24), so
// `x` must be one record of a kept shape: a transient act is never held.

/** `x is held` asks whether a required reference points at `x`, so `x` is one record of a kept shape (Reference §10). */
internal fun Validator.checkHeldSubject(test: IsExpr, scope: String) {
    val subjectShape = (test.subject as? PathExpr)?.let { path -> pathTypeName(path, scope) }
    val bases = subjectShape?.let { shape -> model.basesOf(shape) }.orEmpty()
    if (bases.isNotEmpty() && bases.none { base -> base in model.transients }) return
    diagnostics.add(Diagnostic("F2", "'${Printer.expr(test)}' — 'is held' tests one record of a kept shape, " +
        "and '${Printer.expr(test.subject)}' is not one"))
}
