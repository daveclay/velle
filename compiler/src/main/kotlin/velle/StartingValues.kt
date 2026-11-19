package velle

/**
 * The order in which a new record's `initially` values are computed (Reference §5): each after
 * every other `initially` field of the same record that it reads, directly or through a derived
 * property, wherever the two are declared. Two that read each other have no such order; the
 * validator refuses them (checks.md V14), and [orderOf] then keeps them as declared.
 */
class StartingValues(private val model: Model) {

    /** For each field of [shape] with an `initially`, the `initially` fields of the same record it reads. */
    fun readsOf(shape: String): Map<String, Set<String>> = readsByShape.getOrPut(shape) {
        startingFieldsOf(shape).associate { field -> field.name to startingFieldsRead(field.initially, shape, emptySet()) }
    }

    /** The fields of [shape] with an `initially`, each after the ones it reads. */
    fun orderOf(shape: String): List<StoredProp> = orderByShape.getOrPut(shape) {
        val fieldsByName = startingFieldsOf(shape).associateBy { field -> field.name }
        val reads = readsOf(shape)
        val ordered = LinkedHashSet<String>()
        fun place(fieldName: String, placing: Set<String>) {
            if (fieldName in ordered || fieldName in placing) return
            reads[fieldName].orEmpty().forEach { readName -> place(readName, placing + fieldName) }
            ordered.add(fieldName)
        }
        fieldsByName.keys.forEach { fieldName -> place(fieldName, emptySet()) }
        ordered.map { fieldName -> fieldsByName.getValue(fieldName) }
    }

    private val readsByShape = HashMap<String, Map<String, Set<String>>>()
    private val orderByShape = HashMap<String, List<StoredProp>>()

    private fun startingFieldsOf(shape: String): List<StoredProp> =
        model.shapes[shape]?.members.orEmpty().filterIsInstance<StoredProp>().filter { field -> field.initially != null }

    /** The `initially` fields of the record itself that [expr] reads, unfolding the derived
     *  properties it reads on the way. [unfolding] ends a derived property that reads itself. */
    private fun startingFieldsRead(expr: Expr?, shape: String, unfolding: Set<String>): Set<String> =
        ownMembersRead(expr, subjectLevel = true).flatMap { memberName -> startingFieldsBehind(memberName, shape, unfolding) }.toSet()

    private fun startingFieldsBehind(memberName: String, shape: String, unfolding: Set<String>): Set<String> {
        val declared = model.shapes[shape]?.members.orEmpty()
        val stored = declared.filterIsInstance<StoredProp>().firstOrNull { field -> field.name == memberName }
        if (stored != null) return if (stored.initially != null) setOf(memberName) else emptySet()
        val derived = declared.filterIsInstance<DerivedProp>().firstOrNull { property -> property.name == memberName }
        if (derived == null || memberName in unfolding) return emptySet()
        return startingFieldsRead(derived.expr, shape, unfolding + memberName)
    }

    /** The names [expr] reads on the record itself. A bare name is the record's own only at
     *  [subjectLevel]; inside a filter it is the element's, and `this` stays the record. */
    private fun ownMembersRead(expr: Expr?, subjectLevel: Boolean): Set<String> = when (expr) {
        null -> emptySet()
        is PathExpr -> setOfNotNull(ownMemberOf(expr, subjectLevel))
        is ExistsExpr -> ownMembersRead(expr.forExpr, subjectLevel) + expr.collection?.let { collection -> ownMembersReadIn(collection, subjectLevel) }.orEmpty()
        is AggCall -> ownMembersReadIn(expr.collection, subjectLevel)
        is SetExpr -> ownMembersReadIn(expr.collection, subjectLevel)
        // a walk follows a relationship of the record and tests the record itself first
        is WalkExpr -> setOf(expr.rel) + ownMembersRead(expr.until, subjectLevel = true)
        else -> partsOf(expr).flatMap { part -> ownMembersRead(part, subjectLevel) }.toSet()
    }

    private fun ownMembersReadIn(collection: CollectionExpr, subjectLevel: Boolean): Set<String> =
        collection.bindings.flatMap { binding -> ownMembersRead(binding.source, subjectLevel) }.toSet() +
            ownMembersRead(collection.where, subjectLevel = false)
}

/** The record's own member a path starts at, `notes`, `this.notes`, `notes.size`. A bare name is
 *  the record's own only at [subjectLevel]; inside a filter it is the element's. */
internal fun ownMemberOf(path: PathExpr, subjectLevel: Boolean): String? = when {
    path.root == "this" -> path.segs.firstOrNull()?.name
    subjectLevel -> path.root
    else -> null
}

/** The expressions directly inside [expr], for every form that holds no collection of its own. */
internal fun partsOf(expr: Expr): List<Expr> = when (expr) {
    is UnaryMinus -> listOf(expr.inner)
    is Binary -> listOf(expr.left, expr.right)
    is NotExpr -> listOf(expr.inner)
    is IfExpr -> listOf(expr.condition, expr.thenExpr, expr.elseExpr)
    is Interpolation -> expr.pieces
    is IsExpr -> listOf(expr.subject)
    is FunCall -> expr.args
    is SingularFor -> listOf(expr.forExpr)
    is ShapeForSource -> listOf(expr.forExpr)
    is Access -> listOf(expr.target)
    else -> emptyList()
}
