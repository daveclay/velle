package velle.validation

import velle.*

// ── A5 (advisory): contention width (OQ40) ───────────────────────────────
//
// The serialization-domain derivation (Domains.kt) attributes every read
// and write of every envelope to a queue key; where a read has no path
// back to any key, the domain honestly widens to the whole shape — every
// commit touching that shape joins a single queue. "Unintentionally
// single-threaded" is as legitimate a failure as a wrong value, so the
// width demands a stated policy: correlate the read (a model fact — a
// relationship the model was missing), move the rule to a schedule (the
// read then runs once per tick instead of inside every commit), or
// declare `tolerates contention` on the declaration carrying the read.
// Severity ruling 2026-08-18: advisory, not a compile error — the failure
// a wide domain causes is throughput, not a wrong value. A tolerance
// covering no width is dead and flagged, like an impossible `reordering`.

internal fun Validator.checkContention() {
    val wideningsByDeclaration = DomainAnalysis(model).commitWidenings()
    for ((declaration, widenings) in wideningsByDeclaration) {
        for (widening in widenings.filterNot { widening -> widening.tolerated }.distinctBy { widening -> widening.shape }) {
            adviseOnWidening(declaration, widening)
        }
    }
    // dead tolerance: a declared `tolerates contention` covering no width
    for (declaration in tolerantDeclarations()) {
        if (wideningsByDeclaration[declaration].isNullOrEmpty())
            diagnostics.add(Diagnostic("A5", "$declaration declares `tolerates contention` but causes no " +
                "contention beyond its queue keys — remove the tolerance (OQ40)", advisory = true))
    }
}

/** The A5 advisory for one untolerated [widening] caused by [declaration], listing the policies to state. */
private fun Validator.adviseOnWidening(declaration: String, widening: Widening) {
    val scheduleOption = if (declaration.startsWith("rule "))
        "\n    - move the rule to a schedule (`on <Schedule>`) — the read then runs once per tick instead of inside every commit, or"
    else ""
    diagnostics.add(Diagnostic("A5", "every commit touching ${widening.shape} joins a single queue. " +
        "${widening.cause.replaceFirstChar { first -> first.uppercase() }} ($declaration), so any two such commits conflict. " +
        "State the policy:\n    - correlate the read (relate ${widening.shape} to the shape that partitions it), or" +
        scheduleOption +
        "\n    - declare `tolerates contention` on the $declaration (OQ40)",
        advisory = true))
}

/** Every rule and prohibition that declares `tolerates contention`, named as the widenings name them. */
private fun Validator.tolerantDeclarations(): List<String> = buildList {
    model.rules.values.filter { rule -> rule.toleratesContention }.forEach { rule -> add("rule ${rule.name}") }
    model.nevers.forEachIndexed { index, never -> if (never.toleratesContention) add("${never.keyword} #${index + 1}") }
}
