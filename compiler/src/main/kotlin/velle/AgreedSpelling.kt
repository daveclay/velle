package velle

import velle.ext.Operator

/**
 * A declaration rewritten in one agreed spelling, so that two conditions mean the same when
 * their agreed spellings are equal (the guide `Comparing-specs.md`). The rewriting reasons only
 * from facts: the language's own (the sides of `and` and `or` may be swapped, `not not P` is `P`,
 * `not (P and Q)` is `not P or not Q`, a comparison may be turned around, equality is one
 * function) and the facts an operator or a function states about itself. Nothing is concluded
 * from a value, and an ordered comparison is never traded for its negation, since an absent
 * value satisfies neither.
 *
 * [claimsReliedOn] collects each fact an extension stated that changed the spelling, so the
 * report can name whose claim a "means the same" rests on.
 */
class AgreedSpelling(private val version: SpecVersion) {
    private val model: Model = version.model
    private val types = ExprTypes(model)

    val claimsReliedOn: MutableSet<String> = linkedSetOf()

    fun of(declaration: Decl): Decl = when (declaration) {
        is RefinementDecl -> declaration.copy(expr = refinement(declaration.expr))
        is NeverDecl -> prohibition(declaration)
        is RuleDecl -> declaration.copy(condition = refinement(declaration.condition))
        is ShapeDecl -> declaration.copy(members = declaration.members.map { member -> member(member, declaration.name) })
        else -> declaration
    }

    private fun prohibition(declaration: NeverDecl): NeverDecl {
        val requirement = declaration.requirement ?: return declaration.copy(target = refinement(declaration.target))
        val scope = requirement.subject
        return declaration.copy(
            target = refinement(declaration.target),
            requirement = requirement.copy(predicate = predicate(requirement.predicate, scope, TypeEnv())),
        )
    }

    private fun member(member: Member, scope: String): Member =
        if (member is DerivedProp) member.copy(expr = value(member.expr, scope, TypeEnv())) else member

    // ── Refinement expressions ───────────────────────────────────────────────────

    private fun refinement(expression: RefExpr): RefExpr = when (expression) {
        is RefName -> expression.copy(where = expression.where?.let { predicate -> predicate(predicate, expression.name, TypeEnv()) })
        is RefNot -> negatedRefinement(expression.inner)
        is RefAnd -> joinedRefinements(operandsOf(expression).map { operand -> refinement(operand) }) { left, right -> RefAnd(left, right) }
        is RefOr -> joinedRefinements(operandsOf(expression).map { operand -> refinement(operand) }) { left, right -> RefOr(left, right) }
    }

    private fun negatedRefinement(inner: RefExpr): RefExpr = when (inner) {
        is RefNot -> refinement(inner.inner)
        is RefAnd -> refinement(RefOr(RefNot(inner.left), RefNot(inner.right)))
        is RefOr -> refinement(RefAnd(RefNot(inner.left), RefNot(inner.right)))
        is RefName -> RefNot(refinement(inner))
    }

    private fun operandsOf(expression: RefExpr): List<RefExpr> = when {
        expression is RefAnd -> refinementOperands(expression, RefAnd::class.java)
        expression is RefOr -> refinementOperands(expression, RefOr::class.java)
        else -> listOf(expression)
    }

    private fun refinementOperands(expression: RefExpr, connective: Class<out RefExpr>): List<RefExpr> {
        if (!connective.isInstance(expression)) return listOf(expression)
        val (left, right) = when (expression) {
            is RefAnd -> expression.left to expression.right
            is RefOr -> expression.left to expression.right
            else -> return listOf(expression)
        }
        return refinementOperands(left, connective) + refinementOperands(right, connective)
    }

    private fun joinedRefinements(operands: List<RefExpr>, join: (RefExpr, RefExpr) -> RefExpr): RefExpr =
        operands.distinctBy { operand -> Printer.refExpr(operand) }
            .sortedBy { operand -> Printer.refExpr(operand) }
            .reduce(join)

    // ── Predicates ───────────────────────────────────────────────────────────────

    private fun predicate(expression: Expr, scope: String, env: TypeEnv): Expr = when (expression) {
        is Binary -> binaryPredicate(expression, scope, env)
        is NotExpr -> negatedPredicate(expression.inner, scope, env)
        is ExistsExpr -> existence(expression, scope, env)
        else -> value(expression, scope, env)
    }

    private fun binaryPredicate(expression: Binary, scope: String, env: TypeEnv): Expr = when (expression.op) {
        "and", "or" -> joinedPredicates(
            sameOperatorOperands(expression, expression.op).map { operand -> predicate(operand, scope, env) },
            expression.op,
        )
        ">" -> Binary("<", value(expression.right, scope, env), value(expression.left, scope, env))
        ">=" -> Binary("<=", value(expression.right, scope, env), value(expression.left, scope, env))
        "==", "!=" -> sortedSides(expression.op, value(expression.left, scope, env), value(expression.right, scope, env))
        else -> value(expression, scope, env)
    }

    private fun negatedPredicate(inner: Expr, scope: String, env: TypeEnv): Expr {
        if (inner is NotExpr) return predicate(inner.inner, scope, env)
        if (inner is Binary && inner.op == "and") return predicate(Binary("or", NotExpr(inner.left), NotExpr(inner.right)), scope, env)
        if (inner is Binary && inner.op == "or") return predicate(Binary("and", NotExpr(inner.left), NotExpr(inner.right)), scope, env)
        return NotExpr(predicate(inner, scope, env))
    }

    private fun existence(expression: ExistsExpr, scope: String, env: TypeEnv): Expr {
        val collection = expression.collection ?: return expression.copy(forExpr = expression.forExpr?.let { owner -> value(owner, scope, env) })
        val binding = collection.bindings.singleOrNull()
        val element = (binding?.source as? PathExpr)?.takeIf { source -> source.segs.isEmpty() }?.root
        val where = collection.where
        if (element == null || where == null) return expression
        val aliases = binding.alias?.let { alias -> env.aliases + (alias to element) } ?: env.aliases
        val innerEnv = env.copy(thisScope = env.thisScope ?: scope, aliases = aliases)
        return expression.copy(collection = collection.copy(where = predicate(where, element, innerEnv)))
    }

    private fun joinedPredicates(operands: List<Expr>, connective: String): Expr =
        operands.distinctBy { operand -> Printer.expr(operand) }
            .sortedBy { operand -> Printer.expr(operand) }
            .reduce { left, right -> Binary(connective, left, right) }

    private fun sortedSides(operator: String, left: Expr, right: Expr): Expr {
        val (first, second) = listOf(left, right).sortedBy { side -> Printer.expr(side) }
        return Binary(operator, first, second)
    }

    private fun sameOperatorOperands(expression: Expr, operator: String): List<Expr> =
        if (expression is Binary && expression.op == operator) {
            sameOperatorOperands(expression.left, operator) + sameOperatorOperands(expression.right, operator)
        } else {
            listOf(expression)
        }

    // ── Values ───────────────────────────────────────────────────────────────────

    private fun value(expression: Expr, scope: String, env: TypeEnv): Expr = when (expression) {
        is Binary -> arithmetic(expression, scope, env)
        is FunCall -> call(expression, scope, env)
        is UnaryMinus -> UnaryMinus(value(expression.inner, scope, env))
        else -> expression
    }

    private fun arithmetic(expression: Binary, scope: String, env: TypeEnv): Expr {
        val line = lineFor(expression, scope, env)
            ?: return Binary(expression.op, value(expression.left, scope, env), value(expression.right, scope, env))
        val operands = if (line.groupingDoesNotMatter) {
            sameLineOperands(expression, line, scope, env)
        } else {
            listOf(expression.left, expression.right)
        }
        val spelled = operands.map { operand -> value(operand, scope, env) }
        if (operands.size > 2) noteClaim(line, "grouping does not matter for")
        val ordered = if (line.sidesMayBeSwapped) spelled.sortedBy { operand -> Printer.expr(operand) } else spelled
        if (ordered != spelled) noteClaim(line, "the sides may be swapped for")
        return ordered.reduce { left, right -> Binary(expression.op, left, right) }
    }

    /** The operands of a chain like `a + b + c` whose every step is the same operator line. */
    private fun sameLineOperands(expression: Expr, line: Operator<*, *, *>, scope: String, env: TypeEnv): List<Expr> {
        if (expression !is Binary || !sameLine(lineFor(expression, scope, env), line)) return listOf(expression)
        return sameLineOperands(expression.left, line, scope, env) + sameLineOperands(expression.right, line, scope, env)
    }

    private fun sameLine(candidate: Operator<*, *, *>?, line: Operator<*, *, *>): Boolean =
        candidate != null && candidate.op == line.op && candidate.left == line.left && candidate.right == line.right

    /** The operator line an arithmetic expression uses, by its operands' types, or null when none states facts. */
    private fun lineFor(expression: Binary, scope: String, env: TypeEnv): Operator<*, *, *>? {
        val leftName = registryName(typeOf(expression.left, scope, env)) ?: return null
        val rightName = registryName(typeOf(expression.right, scope, env)) ?: return null
        val registry = model.registry
        val owners = listOfNotNull(registry.builtins[leftName] ?: registry[leftName], registry.builtins[rightName] ?: registry[rightName])
        return registry.operator(leftName, expression.op, rightName)
            ?: owners.flatMap { owner -> owner.operators }
                .firstOrNull { candidate -> candidate.op == expression.op && candidate.left == leftName && candidate.right == rightName }
    }

    private fun typeOf(expression: Expr, scope: String, env: TypeEnv): VType? =
        runCatching { types.of(expression, scope, env) }.getOrNull()

    private fun call(expression: FunCall, scope: String, env: TypeEnv): Expr {
        val function = model.functionScope[expression.name]
        val arguments = expression.args.map { argument -> value(argument, scope, env) }
        if (function == null) return expression.copy(args = arguments)
        val flattened = if (function.groupingDoesNotMatter) {
            arguments.flatMap { argument -> if (argument is FunCall && argument.name == expression.name) argument.args else listOf(argument) }
        } else {
            arguments
        }
        val ordered = if (function.argumentsInAnyOrder) flattened.sortedBy { argument -> Printer.expr(argument) } else flattened
        if (function.namespace != "velle" && (ordered != arguments)) {
            claimsReliedOn.add("`${function.namespace}` is right that `${function.name}` takes its arguments in any order and in any grouping")
        }
        return expression.copy(args = ordered)
    }

    private fun noteClaim(line: Operator<*, *, *>, fact: String) {
        val owner = listOf(line.left, line.right).firstOrNull { name -> '.' in name } ?: return
        val library = owner.substringBeforeLast('.')
        val spelled = "${line.left.substringAfterLast('.')} ${line.op} ${line.right.substringAfterLast('.')}"
        claimsReliedOn.add("`$library` is right that $fact `$spelled`")
    }
}
