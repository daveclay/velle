package velle.validation

import velle.*

// ── V6: capture entry-evaluability (coarse) ──────────────────────────────

internal fun Validator.checkCaptures() {
    for ((refinementName, refinement) in model.refinements) {
        val base = model.baseOf(refinementName) ?: continue
        refinement.members.filterIsInstance<DerivedProp>().filter { property -> property.captured }
            .forEach { capture -> checkCapture(refinementName, base, capture) }
    }
}

private fun Validator.checkCapture(refinementName: String, base: String, capture: DerivedProp) {
    val reads = ReadSummary()
    model.collectExpr(capture.expr, refinementName, reads)
    // coarse: base-shape stored fields, literals, and time are entry-evaluable;
    // anything reaching another shape must be guaranteed by the predicate — v0
    // only accepts what the exists-atoms of the predicate mention
    val guaranteed = model.predicateSummary(refinementName).existsShapes
    reads.existsShapes.filterNot { shape -> shape in guaranteed }.forEach { shape ->
        diagnostics.add(Diagnostic("V6", "capture '$refinementName.${capture.name}' reads '$shape', which its predicate does not guarantee at entry (Reference §8)"))
    }
    reads.fields.filterNot { (owner, _) -> owner == base }.forEach { (owner, field) ->
        if (owner !in guaranteed)
            diagnostics.add(Diagnostic("V6", "capture '$refinementName.${capture.name}' reads $owner.$field beyond the base shape without a predicate guarantee"))
    }
}
