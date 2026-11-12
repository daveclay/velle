package velle

/**
 * Key lookups (Reference §10, "Reading the one related record"; Reference §21,
 * "Keys"): where a spec expects one record and the author wrote a filter,
 * `(Entry where key == this.entryKey)`, the filter is one record or absent when
 * it names every field of a key of the shape with `==` and compares each with
 * a value that is present. The filter is then read as `first` over itself, with
 * no order to name, and printed as written ([AggCall.keyLookup]). A filter that
 * names part of a key, or compares a key field with a value that may be absent,
 * stays a set, and in a position that expects one record that is V36.
 *
 * The positions that expect one record: a derived property declared `one`, a
 * creation's `for` subject, a `from` field declared `one`, and an assignment to
 * a `one` field, each through the branches of an `if`.
 *
 * Presence is read from the declarations: a literal, a required field of the
 * subject reached through required to-one fields, or an optional field the
 * enclosing refinement or `if` narrows with `is some`. Everything else may be
 * absent, because two absent values compare equal (Reference §10) and an absent
 * key would match every record without one.
 */
class KeyLookups(decls: List<Decl>) {

    val diagnostics = mutableListOf<Diagnostic>()

    private val shapes = decls.filterIsInstance<ShapeDecl>().associateBy { it.name }
    private val refinements = decls.filterIsInstance<RefinementDecl>().associateBy { it.name }

    val rewritten: List<Decl> = decls.map { decl ->
        when (decl) {
            is ShapeDecl -> decl.copy(members = decl.members.map { rewriteMember(it, decl.name, emptySet()) })
            is RefinementDecl -> baseOf(decl.name)?.let { base ->
                decl.copy(members = decl.members.map { rewriteMember(it, base, presentIn(decl.expr)) })
            } ?: decl
            is RuleDecl -> baseOfExpr(decl.condition)?.let { base ->
                decl.copy(body = decl.body.map { rewriteItem(it, base, presentIn(decl.condition)) })
            } ?: decl
            else -> decl
        }
    }

    // ── the positions that expect one record ─────────────────────────────────

    private fun rewriteMember(member: Member, scope: String, present: Set<String>): Member =
        if (member is DerivedProp && member.type != null && !isMany(member.type)) member.copy(expr = rewriteElement(member.expr, scope, present))
        else member

    private fun rewriteItem(item: BodyItem, scope: String, present: Set<String>): BodyItem = when (item) {
        is Creation -> item.copy(
            forExpr = item.forExpr?.let { rewriteElement(it, scope, present) },
            fields = item.fields.map { init ->
                if (fieldType(item.shape, init.name)?.let(::isMany) == false) init.copy(value = rewriteElement(init.value, scope, present))
                else init
            },
        )
        is Assignment -> {
            val target = pathType(item.target, scope)
            if (target != null && !isMany(target)) item.copy(value = rewriteElement(item.value, scope, present)) else item
        }
        is ForEach -> item.copy(body = item.body.map { rewriteItem(it, scope, present) })
        else -> item
    }

    private fun rewriteElement(expr: Expr, scope: String, present: Set<String>): Expr = when (expr) {
        is SetExpr -> keyLookup(expr, scope, present) ?: expr
        is IfExpr -> expr.copy(
            thenExpr = rewriteElement(expr.thenExpr, scope, present + narrowings(expr.condition)),
            elseExpr = rewriteElement(expr.elseExpr, scope, present),
        )
        else -> expr
    }

    // ── one filter against the shape's keys ──────────────────────────────────

    private fun keyLookup(set: SetExpr, scope: String, present: Set<String>): Expr? {
        val written = Printer.expr(set)
        val binding = set.collection.bindings.singleOrNull() ?: return null
        val source = binding.source as? PathExpr ?: return null
        if (source.segs.isNotEmpty()) return null
        val shape = baseOf(source.root)?.let { shapes[it] } ?: return null
        val compared = comparedFields(set.collection.where, binding.alias)
        val keys = shape.keys()
        if (keys.isEmpty()) {
            refuse("$written is a set of ${shape.name} where one record is expected, and ${shape.name} declares no key. " +
                "Declare one, `unique (<field>)`, and name every field of it, or select a record with " +
                "first(${shape.name} where ... by <field>) (Reference §10)")
            return null
        }
        val key = keys.firstOrNull { compared.keys.containsAll(it.fields) }
        if (key == null) {
            val nearest = keys.maxBy { it.fields.count(compared::containsKey) }
            val missing = nearest.fields.filterNot(compared::containsKey)
            refuse("$written is a set of ${shape.name} where one record is expected: a filter is one record when it names " +
                "every field of a key with ==, and the key ${Printer.member(nearest)} is missing " +
                missing.joinToString(", ") { "'$it'" } + " (Reference §10)")
            return null
        }
        for (field in key.fields) {
            val value = compared.getValue(field)
            if (isPresent(value, scope, present)) continue
            val spelled = Printer.expr(value)
            val fix = (value as? PathExpr)?.let { path ->
                val bare = if (path.root == "this") path.segs.joinToString(".") { it.name } else spelled
                "test `$bare is some` first, or declare it required"
            } ?: "compare the key with a value that is present"
            refuse("$written compares '$field' with '$spelled', which may be absent, and an absent value matches every " +
                "${shape.name} without a value for '$field'; $fix (Reference §10)")
            return null
        }
        return AggCall("first", set.collection, keyLookup = true)
    }

    /** The element's fields compared with `==` in the filter's conjuncts, each with the value it is compared to. */
    private fun comparedFields(where: Expr?, alias: String?): Map<String, Expr> {
        val out = linkedMapOf<String, Expr>()
        for (conjunct in conjuncts(where)) {
            val cmp = conjunct as? Binary ?: continue
            if (cmp.op != "==") continue
            for ((side, other) in listOf(cmp.left to cmp.right, cmp.right to cmp.left)) {
                val field = elementField(side, alias) ?: continue
                out.putIfAbsent(field, other)
                break
            }
        }
        return out
    }

    /** `field`, or `alias.field` when the binding is named: the element's own field. */
    private fun elementField(expr: Expr, alias: String?): String? {
        val path = expr as? PathExpr ?: return null
        return when {
            alias != null && path.root == alias && path.segs.size == 1 -> path.segs.single().name
            alias == null && path.root != "this" && path.segs.isEmpty() && path.root.first().isLowerCase() -> path.root
            else -> null
        }
    }

    private fun refuse(message: String) { diagnostics.add(Diagnostic("V36", message)) }

    // ── presence ─────────────────────────────────────────────────────────────

    /** A value the filter compares a key field with is present when it is a literal, or a path
     *  from the subject through required fields, or a path the scope narrows with `is some`. */
    private fun isPresent(value: Expr, scope: String, present: Set<String>): Boolean = when (value) {
        is IntLit, is DecLit, is TextLit, is BoolLit, is DurationLit, NowLit, TodayLit -> true
        is PathExpr -> pathIsPresent(value, scope, present)
        is Interpolation -> value.pieces.all { piece -> isPresent(piece, scope, present) }
        else -> false
    }

    private fun pathIsPresent(path: PathExpr, scope: String, present: Set<String>): Boolean {
        val segments = if (path.root == "this") path.segs.map { it.name } else listOf(path.root) + path.segs.map { it.name }
        if (path.root != "this" && shapes[scope]?.let { fieldType(it.name, path.root) } == null) return false
        var shape: String = scope
        for ((index, name) in segments.withIndex()) {
            val type = fieldType(shape, name) ?: return false
            val prefix = segments.take(index + 1).joinToString(".")
            if (isOptional(type) && prefix !in present) return false
            shape = (type as? RelType)?.shape ?: if (index == segments.lastIndex) return true else return false
        }
        return true
    }

    /** The paths a condition's conjuncts narrow with `is some`, spelled without `this.`. */
    private fun narrowings(condition: Expr): Set<String> = conjuncts(condition).mapNotNullTo(linkedSetOf()) { conjunct ->
        val test = conjunct as? IsExpr ?: return@mapNotNullTo null
        if (test.kind != "some") return@mapNotNullTo null
        val path = test.subject as? PathExpr ?: return@mapNotNullTo null
        if (path.root == "this") path.segs.joinToString(".") { it.name } else (listOf(path.root) + path.segs.map { it.name }).joinToString(".")
    }

    /** What a refinement expression narrows with `is some`: its own `where` and, through a name, the refinement it names. */
    private fun presentIn(expr: RefExpr, seen: MutableSet<String> = mutableSetOf()): Set<String> = when (expr) {
        is RefName -> {
            val own = expr.where?.let(::narrowings).orEmpty()
            val named = refinements[expr.name]?.takeIf { seen.add(expr.name) }?.let { presentIn(it.expr, seen) }.orEmpty()
            own + named
        }
        is RefAnd -> presentIn(expr.left, seen) + presentIn(expr.right, seen)
        is RefOr, is RefNot -> emptySet()
    }

    // ── the declarations, read syntactically ─────────────────────────────────

    private fun baseOf(name: String, seen: MutableSet<String> = mutableSetOf()): String? = when {
        name in shapes -> name
        name in refinements && seen.add(name) -> baseOfExpr(refinements.getValue(name).expr, seen)
        else -> null
    }

    private fun baseOfExpr(expr: RefExpr, seen: MutableSet<String> = mutableSetOf()): String? = when (expr) {
        is RefName -> baseOf(expr.name, seen)
        is RefAnd -> baseOfExpr(expr.left, seen)
        is RefOr -> baseOfExpr(expr.left, seen)?.takeIf { it == baseOfExpr(expr.right, seen) }
        is RefNot -> baseOfExpr(expr.inner, seen)
    }

    private fun fieldType(shape: String, field: String): TypeRef? {
        val decl = shapes[shape] ?: return null
        for (member in decl.members) when (member) {
            is StoredProp -> if (member.name == field) return member.type
            is DerivedProp -> if (member.name == field) return member.type
            is ActorProp -> if (member.name == field) return RelType(many = false, shape = member.shape, optional = false)
            else -> {}
        }
        return null
    }

    private fun pathType(path: PathExpr, scope: String): TypeRef? {
        var shape = scope
        val segments = if (path.root == "this") path.segs.map { it.name } else listOf(path.root) + path.segs.map { it.name }
        var type: TypeRef? = null
        for (name in segments) {
            type = fieldType(shape, name) ?: return null
            shape = (type as? RelType)?.shape ?: shape
        }
        return type
    }

    private fun isMany(type: TypeRef): Boolean = when (type) {
        is ScalarType -> type.many
        is RelType -> type.many
    }

    private fun isOptional(type: TypeRef): Boolean = when (type) {
        is ScalarType -> type.optional
        is RelType -> type.optional
    }

    private fun conjuncts(expr: Expr?): List<Expr> = when {
        expr == null -> emptyList()
        expr is Binary && expr.op == "and" -> conjuncts(expr.left) + conjuncts(expr.right)
        else -> listOf(expr)
    }
}
