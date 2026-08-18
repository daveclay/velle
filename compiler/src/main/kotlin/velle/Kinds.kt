package velle

/**
 * The commit-kind vocabulary shared by the diagram generators (working-docs/
 * questions/OQ41): every observable change in a running Velle system is a
 * creation, a rule's field write, or the passage of time (`today`/`now`
 * advancing, schedule ticks). Each generator asks the same two questions —
 * which kinds can this spec produce, and can a given kind change what a given
 * predicate reads — so the answers live here. Both are deliberate
 * over-approximations, matching the runtime's relevance gating: a diagram that
 * omits a possible cause lies, one that shows an unreachable one merely hedges.
 */
internal sealed interface CommitKind {
    data class Create(val shape: String) : CommitKind
    data class Assign(val shape: String, val field: String) : CommitKind
    data object TimePasses : CommitKind
}

/** A commit kind paired with a human-readable origin that can produce it. */
internal data class Cause(val kind: CommitKind, val label: String)

internal class KindCatalog(private val model: Model) {

    /** Every kind a running system can produce, each with its origin: the
     *  exposed acts, the rule bodies, and time itself. */
    val causes: List<Cause> by lazy {
        buildList {
            for (act in model.exposed) add(Cause(CommitKind.Create(act), "commit$act"))
            for (rule in model.rules.values) for (k in producedKinds(rule)) when (k) {
                is CommitKind.Create -> add(Cause(k, "${rule.name} inserts ${k.shape}"))
                is CommitKind.Assign -> add(Cause(k, "${rule.name} writes ${k.shape}.${k.field}"))
                CommitKind.TimePasses -> {}
            }
            add(Cause(CommitKind.TimePasses, "time passes"))
        }.distinct()
    }

    /** The commit kinds a rule's body performs, at owner-shape granularity. */
    fun producedKinds(rule: RuleDecl): List<CommitKind> {
        val subject = (rule.condition as? RefName)?.name ?: model.baseOfExpr(rule.condition)
        val out = mutableListOf<CommitKind>()
        for (item in rule.body) when (item) {
            is Creation -> out.add(CommitKind.Create(item.shape))
            is Assignment -> {
                val t = item.target
                val ownerScope =
                    if (t.segs.isEmpty()) subject
                    else model.pathElementShape(PathExpr(t.root, t.segs.dropLast(1)), subject ?: continue)
                val owner = ownerScope?.let { model.baseOf(it) } ?: continue
                val field = if (t.segs.isEmpty()) t.root else t.segs.last().name
                out.add(CommitKind.Assign(owner, field))
            }
            ThenMarker -> {}
        }
        return out
    }

    /** Can this kind change the value of a predicate with this read summary? */
    fun touches(k: CommitKind, s: ReadSummary): Boolean = s.opaque || when (k) {
        is CommitKind.Create ->
            k.shape in s.existsShapes || k.shape in s.collShapes ||
                s.fields.any { it.first == k.shape } || s.collFields.any { it.first == k.shape }
        is CommitKind.Assign ->
            (k.shape to k.field) in s.fields || (k.shape to k.field) in s.collFields
        CommitKind.TimePasses -> s.readsTime
    }
}

/** Mermaid line-content hygiene: '#' opens an entity code, ';' ends a statement. */
internal fun mermaidEsc(s: String) = s.replace("#", "no.").replace(";", ",")
