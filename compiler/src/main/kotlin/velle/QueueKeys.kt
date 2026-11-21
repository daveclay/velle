package velle

/**
 * The queue keys of an envelope, valued at admission (evaluation.md, "serialization
 * domain"): the compiler's derivation of each act's and each firing's serialization
 * domain (OQ40), valued here against the envelope's inputs and pre-commit state, inside
 * the write unit, so the reads it needs happen on the unit's connection.
 */
internal class QueueKeys(private val system: VelleSystem) {
    private val model get() = system.model
    private val workingSet get() = system.workingSet

    /** The serialization domains (OQ40, `evaluation.md`): the compiler's derivation, valued here at admission. */
    private val domains: DomainAnalysis by lazy { DomainAnalysis(model) }

    /**
     * The queue keys of an act's envelope, valued against the act's own inputs
     * and pre-commit state: a path key is the row the act's stored to-one path
     * reaches, hopping through stored and captured members; a value key is the
     * value the envelope commits, read off the act's own fields or a hop from
     * one of its references; a widening is the shape. A path that reaches
     * no row contributes no key.
     */
    fun ofAct(act: String, mutations: List<Mutation>, rootIndex: Int): Set<AdmissionKey> {
        val domain = domains.actDomains[act] ?: return emptySet()
        val rootFields = (mutations.getOrNull(rootIndex) as? Mutation.Create)?.fields
        val keys = linkedSetOf<AdmissionKey>()
        for (path in domain.paths) {
            // the act's own row is fresh and conflict-free
            val firstSegment = path.segs.firstOrNull() ?: continue
            val start = referenceIn(rootFields, firstSegment) ?: continue
            hopAlong(start, path.segs.drop(1))?.let { reached -> rowKey(reached) }?.let(keys::add)
        }
        for (valueKey in domain.valueKeys) {
            // the value is read where the compiler found the subject side gets it:
            // the act's own field, or a hop from one of its references; a key with
            // no such place takes the shape's width
            val value = (valueKey.source as? ValueSource.Reach)?.let { reach ->
                if (reach.segs.isEmpty()) rootFields?.get(reach.field)
                else reachFrom(referenceIn(rootFields, reach.segs.first()), reach.segs.drop(1), reach.field)
            }
            keys.add(valueKeyOrWidth(valueKey, value))
        }
        for (widening in domain.widenings) keys.add(AdmissionKey.Shape(widening.shape))
        return keys
    }

    /**
     * The queue keys of one rule firing that is its own envelope (a tick's or the
     * after-commit queue's), valued from the swept subject: an empty path is the
     * subject's own row, a longer one hops from it; a value key reads the
     * subject's stored field its correlation compares against, and takes the
     * shape's width when the compiler found no such field.
     */
    fun ofFiring(rule: RuleDecl, subject: Long): Set<AdmissionKey> {
        val domain = domains.firingDomains[rule.name] ?: return emptySet()
        val keys = linkedSetOf<AdmissionKey>()
        for (path in domain.paths) rowKey(lastRowReached(subject, path.segs))?.let(keys::add)
        for (valueKey in domain.valueKeys) {
            val value = (valueKey.source as? ValueSource.Reach)?.let { reach -> reachFrom(subject, reach.segs, reach.field) }
            keys.add(valueKeyOrWidth(valueKey, value))
        }
        for (widening in domain.widenings) keys.add(AdmissionKey.Shape(widening.shape))
        return keys
    }

    /** The key on [value] when it has a spelling a store can queue on; otherwise the shape's width. */
    private fun valueKeyOrWidth(valueKey: QueueKey.ValueOf, value: Value?): AdmissionKey {
        val canonical = value?.let(::canonical)
        return if (canonical != null) AdmissionKey.Value(valueKey.shape, valueKey.field, canonical) else AdmissionKey.Shape(valueKey.shape)
    }

    private fun referenceIn(fields: Map<String, Value>?, field: String): Long? = (fields?.get(field) as? Value.VRef)?.id

    /** The row [segments] hop to from [start]; null when a hop reaches no row. */
    private fun hopAlong(start: Long, segments: List<String>): Long? {
        var at = start
        for (segment in segments) at = hop(at, segment) ?: return null
        return at
    }

    /** The row [segments] hop to from [start], or the last row reached before a hop reached none. Unlike [hopAlong]; whether they should agree is OQ163. */
    private fun lastRowReached(start: Long, segments: List<String>): Long {
        var at = start
        for (segment in segments) at = hop(at, segment) ?: break
        return at
    }

    /** The stored value [field] of the row [segments] hops from [start]; null when a hop reaches no row or the field is unset. */
    private fun reachFrom(start: Long?, segments: List<String>, field: String): Value? {
        val reached = hopAlong(start ?: return null, segments) ?: return null
        return workingSet.instance(reached)?.fields?.get(field)
    }

    /** One hop of a key path: the row [id]'s stored to-one member [segment], or the captured member of that name (Reference §13). */
    private fun hop(id: Long, segment: String): Long? {
        val instance = workingSet.instance(id) ?: return null
        (instance.fields[segment] as? Value.VRef)?.let { reference -> return reference.id }
        return capturedHop(instance, segment)
    }

    /** The record the captured member [segment] of [instance] refers to, in the first capture of its shape that holds one. */
    private fun capturedHop(instance: Instance, segment: String): Long? {
        val schemas = model.captureSchemas.filter { schema -> schema.base == instance.shape && schema.props.any { prop -> prop.name == segment } }
        for (schema in schemas) {
            (workingSet.captureValues(instance.id, schema.refinement)?.get(segment) as? Value.VRef)?.let { reference -> return reference.id }
        }
        return null
    }

    /** The row key for a persisted handle; null for a row this envelope is creating, which nothing else can hold yet. */
    private fun rowKey(id: Long): AdmissionKey? {
        val key = workingSet.keyOf[id] ?: return null
        val shape = workingSet.idShape[id] ?: return null
        return AdmissionKey.Row(shape, key)
    }

    /** The one spelling of a committed value a store can queue on; null for a value no envelope correlates on. */
    private fun canonical(value: Value): String? = when (value) {
        is Value.VNum -> value.v.stripTrailingZeros().toPlainString()
        is Value.VText -> value.v
        is Value.VBool -> value.v.toString()
        is Value.VDate -> value.v.toString()
        is Value.VDateTime -> value.v.toString()
        is Value.VTime -> value.v.toString()
        is Value.VUuid -> value.v.toString()
        is Value.VDuration -> value.seconds().toString()
        is Value.VExt -> value.type.canonicalAny(value.v)
        is Value.VRef -> rowKey(value.id)?.text
        else -> null
    }
}
