package velle.validation

import velle.*

// ── signed consults: which way can a creation move this condition? ──────
//
// +1: creating an instance of the shape can only move the predicate
// toward true; -1: only toward false; 0: both directions (or unknown).
// Signs come from `exists` polarity under negation, refinement absorption,
// and count comparisons; everything else (sums, selectors, branches)
// stays 0 — coarse, and only ever coarser than the truth.

/** Creation-sensitive consults of a predicate, RAW-named and signed. A
 *  name appears only where creating an instance of its base can move the
 *  predicate: quantified consults (`exists`, collection sources and their
 *  refinement-atom filters, selectors). Membership tests on a *reference*
 *  (`order is ReadyToShip`, refinement composition) contribute their
 *  internals but not their own name — a fresh instance is unreferenced,
 *  so creating one never flips a reference-scoped membership. [complete]
 *  false means the walk could not attribute every consult; consumers must
 *  then treat the summary's unattributed entries as moving both ways. */
internal class Polarities(val signByConsult: Map<String, Int>, val complete: Boolean)

/** The signs a walk over one predicate has found so far, by consulted name. */
internal class PolarityAccumulator {
    val signByConsult = mutableMapOf<String, Int>()
    var incomplete = false

    /** Records [sign] for [consult]; a consult already seen at a different sign moves both ways (0). */
    fun merge(consult: String?, sign: Int) {
        val name = consult ?: return
        signByConsult[name] = if (signByConsult.containsKey(name) && signByConsult.getValue(name) != sign) 0 else sign
    }

    /** Where the walk could not attribute every consult, each shape the [summary]
     *  consults and the walk did not sign moves both ways (0). */
    fun coverUnattributed(summary: ReadSummary) {
        if (summary.opaque) incomplete = true
        if (incomplete) (summary.existsShapes + summary.collShapes).forEach { signByConsult.putIfAbsent(it, 0) }
    }

    fun toPolarities() = Polarities(signByConsult, !incomplete)
}
