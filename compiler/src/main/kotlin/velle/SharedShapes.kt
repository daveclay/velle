package velle

/**
 * The `is` clauses resolved (Reference §9, "Sharing a field across shapes").
 *
 * A shape named after `is` is a taken shape. Its declarations become one
 * declaration on every shape that takes it, each copy marked with the taken
 * shape's name, so that the union rules see one declaration and share it. The
 * taken shape itself becomes a refinement over its takers, a static union, so
 * every read over it is a read the union rules already answer. A taken shape
 * has no records of its own.
 */
class SharedShapes(declarations: List<Decl>) {
    val diagnostics = mutableListOf<Diagnostic>()

    /** The taken shapes, by name, as declared. */
    val taken: Map<String, ShapeDecl>

    /** The shapes that take each taken shape directly, in declaration order. */
    val takersOf: Map<String, List<String>>

    /** The declarations with every `is` clause resolved. */
    val rewritten: List<Decl>

    private val shapes: Map<String, ShapeDecl> = declarations.filterIsInstance<ShapeDecl>().associateBy { it.name }
    private val inCycle = mutableSetOf<String>()

    init {
        val takers = linkedMapOf<String, MutableList<String>>()
        for (shape in shapes.values) for (takenName in shape.takes) takers.getOrPut(takenName) { mutableListOf() }.add(shape.name)
        takersOf = takers
        checkTakenNames()
        findCycles()
        taken = takers.keys.filter { it in shapes && it !in inCycle }.associateWith { shapes.getValue(it) }
        rewritten = declarations.map(::rewrite)
    }

    private fun checkTakenNames() {
        for ((takenName, takerNames) in takersOf) {
            val decl = shapes[takenName]
            if (decl == null) {
                for (taker in takerNames) diagnostics.add(Diagnostic("F1", "'$taker is $takenName' names unknown shape '$takenName'"))
                continue
            }
            if (decl.exposed) diagnostics.add(Diagnostic("F3", "'$takenName' is declared an act, and '${takerNames.first()}' is $takenName. " +
                "A shape other shapes take has no records of its own, so it cannot be committed; declare it 'shape $takenName'"))
        }
    }

    private fun findCycles() {
        for (start in shapes.keys) {
            val path = pathBackTo(start, start, emptyList()) ?: continue
            if (path.first() != path.minOrNull()) continue // report each cycle once, from its first name
            inCycle.addAll(path)
            val lines = path.indices.joinToString(" and ") { i -> "'${path[i]}' is ${path[(i + 1) % path.size]}" }
            diagnostics.add(Diagnostic("F3", "$lines: a shape cannot take a shape that takes it; drop one of the two"))
        }
    }

    /** The cycle from [start] back to itself through `is`, or null when there is none. */
    private fun pathBackTo(start: String, at: String, path: List<String>): List<String>? {
        val decl = shapes[at] ?: return null
        for (next in decl.takes) {
            if (next == start) return path + at
            if (next in path) continue
            pathBackTo(start, next, path + at)?.let { return it }
        }
        return null
    }

    private fun rewrite(decl: Decl): Decl {
        if (decl !is ShapeDecl) return decl
        if (decl.name in taken) return unionOverTakers(decl.name)
        if (decl.takes.isEmpty() || decl.name in inCycle) return decl
        return decl.copy(members = takenMembers(decl) + decl.members)
    }

    /** A taken shape as a refinement over the shapes that take it, directly or through a chain. */
    private fun unionOverTakers(takenName: String): RefinementDecl {
        val bases = baseTakersOf(takenName).map { RefName(it) }
        return RefinementDecl(takenName, bases.reduce<RefExpr, RefExpr> { left, right -> RefOr(left, right) })
    }

    /** The shapes with records that take [takenName], directly or through a chain, in declaration order. */
    private fun baseTakersOf(takenName: String): List<String> =
        takersOf.getValue(takenName).flatMap { taker -> if (taker in taken) baseTakersOf(taker) else listOf(taker) }.distinct()

    /** Every member [taker] takes, each marked with the shape it was declared on, with collisions reported. */
    private fun takenMembers(taker: ShapeDecl): List<Member> {
        val collected = linkedMapOf<Pair<String, String>, Member>()
        collectTaken(taker, collected)
        val ownNames = taker.members.mapNotNull { it.memberName() }.toSet()
        val byName = collected.values.groupBy { it.memberName() }
        for ((name, members) in byName) {
            if (name == null) continue
            val sources = members.mapNotNull { it.declaredOn }.distinct()
            if (sources.size > 1) diagnostics.add(Diagnostic("F3", "'${taker.name}' takes '$name' from both " +
                "'${sources[0]}' and '${sources[1]}'; a shape has one declaration per name, so take one of them, or rename the field on one"))
            else if (name in ownNames) diagnostics.add(Diagnostic("F3", "'${taker.name}' declares '$name' and takes it from " +
                "'${sources.single()}'; keep one of the two"))
        }
        return collected.values.toList()
    }

    private fun collectTaken(shape: ShapeDecl, into: LinkedHashMap<Pair<String, String>, Member>) {
        for (takenName in shape.takes) {
            val source = taken[takenName] ?: continue
            for (member in source.members) {
                val copy = member.declaredOn(takenName)
                into.putIfAbsent(takenName to (copy.memberName() ?: copy.toString()), copy)
            }
            collectTaken(source, into)
        }
    }
}

/** The name a member declares, or null for a clause with none. */
fun Member.memberName(): String? = when (this) {
    is StoredProp -> name
    is DerivedProp -> name
    is TimestampProp -> name
    is ActorProp -> name
    else -> null
}

/** The taken shape a member was declared on, or null for a member of the shape's own (Reference §9). */
val Member.declaredOn: String? get() = when (this) {
    is StoredProp -> declaredOn
    is DerivedProp -> declaredOn
    is TimestampProp -> declaredOn
    else -> null
}

/** This member as taken from [takenName]. */
fun Member.declaredOn(takenName: String): Member = when (this) {
    is StoredProp -> copy(declaredOn = takenName)
    is DerivedProp -> copy(declaredOn = takenName)
    is TimestampProp -> copy(declaredOn = takenName)
    else -> this
}
