package velle.validation

import velle.*

// ── V30: a constraint that reads the clock (ruled 2026-10-14; Reference §21) ──
//
// Time advancing is a commit — the tick — so `today` and `now` are data
// with a writer, and that writer fires with nobody asking: the V29 case
// with the clock as the writer. A constraint on a persistent shape whose
// predicate reads the clock, directly or through a refinement it consults,
// can be violated by aging alone, with no act to refuse; and since the end
// check collects every violating record, two aged records make the books
// unrepairable from inside the system. v0 proves nothing about which
// direction time moves a predicate — fail closed. A refusal is exempt: a
// transient act is checked once, at the request, and the clock is read at
// that moment (V3's stance for a request's time-dependent partition).

internal fun Validator.checkClockReadingConstraints() {
    for ((index, never) in model.nevers.withIndex()) {
        if (isRefusal(never)) continue
        if (!model.summaryOfRefExpr(never.target).readsTime) continue
        val forbiddenBase = model.baseOfExpr(never.target)
        diagnostics.add(Diagnostic("V30", "${never.keyword} #${index + 1} (${Printer.prohibitionBody(never)}) over " +
            "${forbiddenBase ?: Printer.refExpr(never.target)} reads the clock — the books can age into the forbidden " +
            "state with no act to refuse, and the clock has no caller: state it on a transient request, " +
            "where the clock is read once at the moment of the act; record the decision as a field the " +
            "prohibition reads instead of the clock; or retract it (Reference §21)"))
    }
}
