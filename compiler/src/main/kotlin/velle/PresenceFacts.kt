package velle

/**
 * What the prohibitions the rules cannot move say about presence (Reference §21; checks.md
 * V10, V11): `never (Submission where parentLabel is some and parent is none)` says `parent` is
 * present on every Submission where `parentLabel is some` holds, since the conjunction never
 * holds. Read from the declarations, before the model, so the key filter ([KeyLookups]) and the
 * type reading ([ExprTypes]) read the same facts.
 *
 * A prohibition is spent only when no rule can move it: no rule assigns a field its predicate
 * reads, by name anywhere in the spec, and no shape it reads through beyond its own is deleted
 * anywhere, since a deleted record reads absent. A predicate with a part this class does not
 * read, an `exists` or a selection, is not spent. Fail closed is the rule for every spend.
 */
class PresenceFacts(decls: List<Decl>) {

    /** One prohibition's consequence: [path] is present on every record of [subject] where every test in [conditions] holds. */
    data class Fact(val subject: String, val conditions: Set<String>, val path: List<String>)

    private val shapes = decls.filterIsInstance<ShapeDecl>().associateBy { it.name }
    private val refinements = decls.filterIsInstance<RefinementDecl>().associateBy { it.name }
    private val rules = decls.filterIsInstance<RuleDecl>()

    /** The fields any rule assigns, by name. */
    private val assignedFieldNames: Set<String> = rules.flatMap { rule -> assignedNames(rule.body) }.toSet()

    /** The shapes any rule deletes. */
    private val deletedShapes: Set<String> = rules.flatMap { rule -> deletedShapes(rule) }.toSet()

    val facts: List<Fact> = decls.filterIsInstance<NeverDecl>().flatMap { never -> factsOf(never) }

    /** The paths present in [scope] given the tests [established] there, each with every step on the way to it. */
    fun presentIn(scope: String, established: Set<String>): Set<List<String>> {
        val within = withinOf(scope)
        return facts.filter { fact -> fact.subject in within && established.containsAll(fact.conditions) }
            .flatMapTo(linkedSetOf()) { fact -> Narrowing.stepsOf(fact.path) }
    }

    /** The names a record in [scope] is a member of: the scope, the refinements it names, and its base shape. */
    fun withinOf(scope: String): Set<String> = within(scope, mutableSetOf())

    private fun within(name: String, seen: MutableSet<String>): Set<String> {
        if (!seen.add(name)) return emptySet()
        val refinement = refinements[name] ?: return setOf(name)
        return setOf(name) + withinExpr(refinement.expr, seen)
    }

    private fun withinExpr(expr: RefExpr, seen: MutableSet<String>): Set<String> = when (expr) {
        is RefName -> within(expr.name, seen)
        is RefAnd -> withinExpr(expr.left, seen) + withinExpr(expr.right, seen)
        is RefOr -> withinExpr(expr.left, seen.toMutableSet()) intersect withinExpr(expr.right, seen.toMutableSet())
        is RefNot -> emptySet()
    }

    // ── reading one prohibition ──────────────────────────────────────────────

    private fun factsOf(never: NeverDecl): List<Fact> {
        val target = never.target as? RefName ?: return emptyList()
        val base = baseOf(target.name) ?: return emptyList()
        return disjuncts(target.where).flatMap { disjunct -> factsOf(target.name, base, conjuncts(disjunct)) }
    }

    /** `C and q is none` never holding: `q` is present wherever `C` holds, for each such `q`. */
    private fun factsOf(subject: String, base: String, conjuncts: List<Expr>): List<Fact> {
        if (conjuncts.any { conjunct -> !spendable(conjunct, base) }) return emptyList()
        return conjuncts.mapNotNull { conjunct ->
            val absence = conjunct as? IsExpr ?: return@mapNotNull null
            if (absence.kind != "none") return@mapNotNull null
            val path = absence.subject as? PathExpr ?: return@mapNotNull null
            Fact(subject, (conjuncts - conjunct).map(::testKey).toSet(), namesOf(path))
        }
    }

    private fun spendable(conjunct: Expr, base: String): Boolean {
        val paths = pathsRead(conjunct) ?: return false
        return paths.all { path -> spendablePath(namesOf(path), base) }
    }

    /** No step of the path is a field a rule assigns, and no record it reads through is one a rule deletes. */
    private fun spendablePath(names: List<String>, base: String): Boolean {
        var shape = base
        for ((index, name) in names.withIndex()) {
            if (name in assignedFieldNames) return false
            val type = fieldType(shape, name) ?: return false
            val target = (type as? RelType)?.shape
            if (index < names.lastIndex) {
                if (target == null || target in deletedShapes) return false
                shape = target
            }
        }
        return true
    }

    /** The paths an expression reads, or null for an expression with a part this class does not read. */
    private fun pathsRead(expr: Expr): List<PathExpr>? = when (expr) {
        is PathExpr -> listOf(expr)
        is IsExpr -> pathsRead(expr.subject)
        is NotExpr -> pathsRead(expr.inner)
        is UnaryMinus -> pathsRead(expr.inner)
        is Binary -> pathsRead(expr.left)?.let { left -> pathsRead(expr.right)?.let { right -> left + right } }
        is FunCall -> expr.args.map { arg -> pathsRead(arg) ?: return null }.flatten()
        is IntLit, is DecLit, is TextLit, is BoolLit, is DurationLit, NoneLit, NowLit, TodayLit -> emptyList()
        else -> null
    }

    // ── the declarations, read syntactically ─────────────────────────────────

    private fun assignedNames(items: List<BodyItem>): List<String> = items.flatMap { item ->
        when (item) {
            is Assignment -> listOf(item.target.segs.lastOrNull()?.name ?: item.target.root)
            is ForEach -> assignedNames(item.body)
            else -> emptyList()
        }
    }

    private fun deletedShapes(rule: RuleDecl): List<String> {
        val base = baseOfExpr(rule.condition) ?: return emptyList()
        return rule.body.filterIsInstance<DeleteStmt>().mapNotNull { stmt ->
            if (stmt.target.root == "this" && stmt.target.segs.isEmpty()) base
            else (pathType(stmt.target, base) as? RelType)?.shape
        }
    }

    private fun baseOf(name: String, seen: MutableSet<String> = mutableSetOf()): String? = when {
        name in shapes -> name
        name in refinements && seen.add(name) -> baseOfExpr(refinements.getValue(name).expr, seen)
        else -> null
    }

    private fun baseOfExpr(expr: RefExpr, seen: MutableSet<String> = mutableSetOf()): String? = when (expr) {
        is RefName -> baseOf(expr.name, seen)
        is RefAnd -> baseOfExpr(expr.left, seen)
        is RefOr -> baseOfExpr(expr.left, seen)?.takeIf { left -> left == baseOfExpr(expr.right, seen) }
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
        var type: TypeRef? = null
        for (name in namesOf(path)) {
            type = fieldType(shape, name) ?: return null
            shape = (type as? RelType)?.shape ?: shape
        }
        return type
    }

    companion object {
        /** The key a test is established under: an `is` test by its path and kind, anything else as printed. */
        fun testKey(test: Expr): String {
            if (test is IsExpr && test.subject is PathExpr)
                return "is " + namesOf(test.subject).joinToString(".") + " " + test.kind + (test.refinement?.let { name -> " $name" } ?: "")
            return Printer.expr(test)
        }

        fun namesOf(path: PathExpr): List<String> =
            if (path.root == "this") path.segs.map { seg -> seg.name } else listOf(path.root) + path.segs.map { seg -> seg.name }

        fun conjuncts(expr: Expr?): List<Expr> = when {
            expr == null -> emptyList()
            expr is Binary && expr.op == "and" -> conjuncts(expr.left) + conjuncts(expr.right)
            else -> listOf(expr)
        }

        fun disjuncts(expr: Expr?): List<Expr> = when {
            expr == null -> emptyList()
            expr is Binary && expr.op == "or" -> disjuncts(expr.left) + disjuncts(expr.right)
            else -> listOf(expr)
        }
    }
}
