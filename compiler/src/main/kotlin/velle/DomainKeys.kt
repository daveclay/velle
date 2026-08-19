package velle

import java.math.BigDecimal

/**
 * Evaluates a derived serialization domain against one concrete commit — the
 * reference implementation of the queue-key contract the generated commit
 * functions carry ("Queue key: [deposit.account]"). An engineer's queue does
 * exactly this at enqueue time: resolve each path key to the row it names
 * through the commit's supplied references, and each value key to the
 * committed value; two envelopes conflict when any resolved token is shared.
 *
 * Returns null when the domain cannot be resolved to concrete tokens — a
 * widening, a path hop that does not land on a row, a value key whose value
 * the commit does not carry. The caller must then treat the envelope as
 * conflicting with everything: wide, never wrong.
 */
object DomainKeys {

    /** The concrete tokens one envelope queues on: row ids for path keys,
     *  (shape.field, committed value) pairs for value keys. */
    data class Tokens(val rows: Set<Long>, val values: Set<Pair<String, String>>) {
        infix fun intersects(other: Tokens): Boolean =
            rows.any { it in other.rows } || values.any { it in other.values }
    }

    /**
     * Resolve [domain] for a commit of [act] with [fields], reading hops
     * beyond the first through [sys]'s settled state. [fields] is the
     * commit-surface map (references as row ids).
     */
    fun evaluate(
        sys: VelleSystem,
        act: String,
        domain: SerializationDomain,
        fields: Map<String, Any?>,
    ): Tokens? {
        if (domain.wide) return null
        val rows = mutableSetOf<Long>()
        for (p in domain.paths) {
            // an act's own row is fresh and never a key; a domain is keyed
            // through the act's references. An absent optional reference — or
            // a `none` met mid-path — is not a failure: the key names no row,
            // so it queues on nothing.
            var id: Long? = when (val raw = fields[p.segs.firstOrNull()]) {
                null -> null
                is Long -> raw
                else -> return null
            }
            for (seg in p.segs.drop(1)) {
                val at = id ?: break
                // a hop can also name a refinement-scoped member — a capture
                // (`ticket.closedBy`, where `closedBy` lives on ClosedTicket).
                // Captures are committed state recorded at membership entry
                // and readable at admission (ruled 2026-08-19; OQ42 audit R1):
                // resolve through the declaring refinement while the row is a
                // member; a non-member's key names no row
                val hop = runCatching { sys.get(at, seg) }.recoverCatching { e ->
                    val base = sys.instances[at]?.shape?.let { sys.model.baseOf(it) ?: it } ?: throw e
                    val owner = sys.model.refinements.keys.singleOrNull { r ->
                        sys.model.baseOf(r) == base && sys.model.membersOf(r)[seg]?.owner == r
                    } ?: throw e
                    if (sys.isMember(at, owner)) sys.getAs(at, owner, seg) else null
                }.getOrElse { return null }
                id = when (hop) {
                    null -> null
                    is Long -> hop
                    else -> return null
                }
            }
            id?.let { rows.add(it) }
        }
        val values = mutableSetOf<Pair<String, String>>()
        for (v in domain.valueKeys) {
            // the key names the scanned shape's field; the committed value is
            // only certainly known when the act *is* that shape
            if (v.shape != act) return null
            val raw = fields[v.field] ?: return null
            values.add("${v.shape}.${v.field}" to normalize(raw))
        }
        return Tokens(rows, values)
    }

    private fun normalize(raw: Any): String =
        if (raw is BigDecimal) raw.stripTrailingZeros().toPlainString() else raw.toString()
}
