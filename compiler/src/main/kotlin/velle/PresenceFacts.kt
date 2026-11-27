package velle

import velle.spec.partsJoinedByAnd
import velle.spec.partsJoinedByOr

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
 *
 * A prohibition over a static union, or over a shape several shapes take with `is` (Reference §9),
 * is enforced on every operand or taker, so its fact holds in every scope over one of them: a
 * record of `Post` is within `Post`, the refinements over it, and every union `Post` is an operand
 * of, a taken shape arriving here already rewritten as the union of its takers ([SharedShapes]).
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

    /** The refinements that are a pure `or` of names: static unions, and taken shapes as rewritten. */
    private val unions: Map<String, RefinementDecl> = refinements.filterValues { refinement -> pureOr(refinement.expr) }

    /**
     * The names a record in [scope] is a member of: the scope, the refinements it names, its base
     * shape, and every union one of those is an operand of, unions of unions included.
     */
    fun withinOf(scope: String): Set<String> {
        val names = within(scope, mutableSetOf()).toMutableSet()
        var grew = true
        while (grew) {
            val reached = unions.filter { (name, union) -> name !in names && leavesOf(union.expr).any { leaf -> leaf in names } }
            names.addAll(reached.keys)
            grew = reached.isNotEmpty()
        }
        return names
    }

    private fun pureOr(expr: RefExpr): Boolean = when (expr) {
        is RefName -> expr.where == null
        is RefOr -> pureOr(expr.left) && pureOr(expr.right)
        else -> false
    }

    private fun leavesOf(expr: RefExpr): List<String> = when (expr) {
        is RefName -> listOf(expr.name)
        is RefOr -> leavesOf(expr.left) + leavesOf(expr.right)
        else -> emptyList()
    }

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
        val bases = basesOf(target.name)
        if (bases.isEmpty()) return emptyList()
        return partsJoinedByOr(target.where).flatMap { disjunct -> factsOf(target.name, bases, partsJoinedByAnd(disjunct)) }
    }

    /** `C and q is none` never holding: `q` is present wherever `C` holds, for each such `q`, on every base. */
    private fun factsOf(subject: String, bases: Set<String>, conjuncts: List<Expr>): List<Fact> {
        if (conjuncts.any { conjunct -> bases.any { base -> !spendable(conjunct, base) } }) return emptyList()
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

    /** The shapes a rule deletes; a rule over a union deleting its subject deletes a record of any operand. */
    private fun deletedShapes(rule: RuleDecl): List<String> {
        val bases = basesOfExpr(rule.condition)
        return rule.body.filterIsInstance<DeleteStmt>().flatMap { stmt ->
            if (stmt.target.root == "this" && stmt.target.segs.isEmpty()) bases.toList()
            else bases.mapNotNull { base -> (pathType(stmt.target, base) as? RelType)?.shape }
        }
    }

    /** The base shapes a name's records are drawn from: one, or every operand of a union. */
    private fun basesOf(name: String, seen: MutableSet<String> = mutableSetOf()): Set<String> = when {
        name in shapes -> setOf(name)
        name in refinements && seen.add(name) -> basesOfExpr(refinements.getValue(name).expr, seen)
        else -> emptySet()
    }

    private fun basesOfExpr(expr: RefExpr, seen: MutableSet<String> = mutableSetOf()): Set<String> = when (expr) {
        is RefName -> basesOf(expr.name, seen)
        is RefAnd -> basesOfExpr(expr.left, seen) intersect basesOfExpr(expr.right, seen)
        is RefOr -> basesOfExpr(expr.left, seen) + basesOfExpr(expr.right, seen)
        is RefNot -> basesOfExpr(expr.inner, seen)
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
    }
}
