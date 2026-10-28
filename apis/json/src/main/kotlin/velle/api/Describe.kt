package velle.api

import velle.ClosureEdge
import velle.Model
import velle.RelType
import velle.ScalarType
import velle.StoredProp
import velle.TypeRef
import velle.VType
import velle.VisibleClause

/**
 * The model as a caller sees it: which acts may be committed and what each
 * takes, which shapes and refinements may be read and what each carries, which
 * schedules may be ticked, and whether the system has authorization. A client,
 * a form, or a schema generator reads this once and needs nothing else to use
 * the system.
 */
class Describe(private val model: Model) {

    /** The schedules the spec's rules run on, in the order the rules name them. */
    val schedules: List<String> = model.rules.values.flatMap { it.triggers }.filter { it != "commit" }.distinct()

    /** Every act, every readable shape and refinement, the schedules, and the authorization flag. */
    fun describe(): Map<String, Any?> = linkedMapOf(
        "authorization" to model.hasAuthorization,
        "schedules" to schedules,
        "acts" to model.exposed.map { act(it) },
        "shapes" to readable().map { shape(it) },
    )

    /** The shapes and refinements a caller may read: everything but a transient act and its refinements. */
    fun readable(): List<String> =
        model.shapes.keys.filter { it !in model.transients } +
            model.refinements.keys.filter { r -> model.basesOf(r).none { it in model.transients } }

    fun act(name: String): Map<String, Any?> {
        val decl = model.shapes.getValue(name)
        val edges = model.closures[name].orEmpty()
        return linkedMapOf(
            "name" to name,
            "transient" to (name in model.transients),
            "actor" to decl.actor?.let { linkedMapOf("field" to it.name, "shape" to it.shape) },
            "fields" to fields(name, edges, backRef = null),
            "parts" to edges.filter { !it.byValue }.map { part(name, it) },
        )
    }

    /** The fields a committer supplies for [shape] at this level of an act's closure. */
    private fun fields(shape: String, edges: List<ClosureEdge>, backRef: String?): List<Map<String, Any?>> {
        val decl = model.shapes.getValue(shape)
        val siblings = model.siblingFields(actOf(edges, shape), shape, edges, backRef).map { it.name }.toSet()
        return decl.members.filterIsInstance<StoredProp>()
            .filter { it.name != backRef && it.initially == null }
            .map { p ->
                val m = linkedMapOf<String, Any?>()
                m["name"] = p.name
                m.putAll(type(p.type))
                if (p.name in siblings) m["mayBeNew"] = true
                edges.find { it.byValue && it.prop == p.name }?.let { e ->
                    m["newValue"] = model.basesOf(e.partShape).sorted().map { operand ->
                        linkedMapOf(
                            "shape" to operand,
                            "fields" to fields(operand, e.childrenFor(operand), backRef = null),
                            "parts" to e.childrenFor(operand).filter { !it.byValue }.map { part(operand, it) },
                        )
                    }
                }
                m
            }
    }

    private fun actOf(edges: List<ClosureEdge>, fallback: String): String = edges.firstOrNull()?.owner ?: fallback

    private fun part(owner: String, e: ClosureEdge): Map<String, Any?> = linkedMapOf(
        "field" to e.prop,
        "shape" to e.partShape,
        "fields" to fields(e.partShape, e.childrenFor(e.partShape), e.backRef),
        "parts" to e.childrenFor(e.partShape).filter { !it.byValue }.map { part(e.partShape, it) },
    )

    fun shape(name: String): Map<String, Any?> {
        val refinement = name in model.refinements
        val out = linkedMapOf<String, Any?>(
            "name" to name,
            "kind" to if (refinement) "refinement" else "shape",
        )
        if (refinement) out["bases"] = model.basesOf(name).sorted()
        if (name in model.exposed) out["act"] = true
        if (name in model.builtin) out["velle"] = true // Velle's own shape, which no act creates (Reference §17)
        out["members"] = model.membersOf(name).values.filter { it.name != "id" }.map { m ->
            val d = linkedMapOf<String, Any?>("name" to m.name)
            d.putAll(type(m.type))
            d["kind"] = when {
                m.actor -> "actor"
                m.timestamp -> "timestamp"
                m.inverse != null -> "inverse"
                m.captured -> "captured"
                m.derived != null -> "derived"
                else -> "stored"
            }
            if (m.visible.isNotEmpty()) d["visibleTo"] = m.visible.map(::clause)
            d
        }
        if (!refinement) out["flags"] = model.refinements.keys
            .filter { r -> model.baseOf(r) == name && model.basesOf(r).none { it in model.transients } }
            .map { "is$it" }
        if (model.hasAuthorization) out["visibleTo"] = model.recordVisibility(name).map(::clause)
        return out
    }

    private fun clause(c: VisibleClause): Map<String, Any?> =
        linkedMapOf("shape" to c.shape, "where" to (c.where != null))

    /** A type as the wire names it: the spec's own word for a scalar, the target shape for a reference. */
    private fun type(t: TypeRef): Map<String, Any?> = when (t) {
        is ScalarType -> linkedMapOf("type" to t.name, "kind" to if (t.many) "values" else "value", "optional" to t.optional)
        is RelType -> linkedMapOf("type" to t.shape, "kind" to if (t.many) "references" else "reference", "optional" to t.optional)
    }

    private fun type(t: VType): Map<String, Any?> {
        val bare = (t as? VType.Optional)?.inner ?: t
        val optional = t is VType.Optional
        return when (bare) {
            is VType.Inst -> linkedMapOf("type" to bare.shape, "kind" to "reference", "optional" to optional)
            is VType.Coll -> linkedMapOf("type" to bare.shape, "kind" to "references", "optional" to false)
            is VType.CollS -> linkedMapOf("type" to bare.name, "kind" to "values", "optional" to false)
            else -> linkedMapOf("type" to scalarName(bare), "kind" to "value", "optional" to optional)
        }
    }

    /** The spec's word for a scalar type: `text`, `integer`, `Date`, or an extension type's name in this spec. */
    fun scalarName(t: VType): String = when (t) {
        is VType.Optional -> scalarName(t.inner)
        is VType.Num -> t.name
        VType.Text -> "text"
        VType.Bool -> "boolean"
        VType.DateT -> "Date"
        VType.DateTimeT -> "DateTime"
        VType.UuidT -> "uuid"
        VType.DurationT -> "duration"
        VType.TimeT -> "time"
        VType.BytesT -> "bytes"
        is VType.Ext -> model.registry[t.qualifiedName]?.let { model.specNameOf(it) } ?: t.qualifiedName
        VType.Id -> "id"
        is VType.Inst -> t.shape
        is VType.Coll -> t.shape
        is VType.CollS -> t.name
        VType.Unknown -> "unknown"
    }
}
