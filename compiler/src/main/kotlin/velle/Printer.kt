package velle

/** Pretty-prints AST back to Velle source — provenance headers for generated specs. */
object Printer {

    /** The act's closure as written: `a, b` flat, braces where an entry carries its own closure. */
    private fun withClause(entries: List<WithEntry>): String = entries.joinToString(", ") {
        if (it.children.isEmpty()) it.name else "${it.name} with { ${withClause(it.children)} }"
    }

    fun print(d: Decl): String = when (d) {
        is ShapeDecl -> buildString {
            append(if (d.exposed) (if (d.transient) "transient act " else "act ") else "shape ")
            append("${d.name} {\n")
            d.members.forEach { append("    ${member(it)}\n") }
            append("}")
            if (d.with.isNotEmpty()) append(" with " + withClause(d.with))
        }
        is RefinementDecl -> buildString {
            append("shape ${d.name} = ${refExpr(d.expr)}")
            if (d.members.isNotEmpty()) {
                append(" {\n")
                d.members.forEach { append("    ${member(it)}\n") }
                append("}")
            }
        }
        is RuleDecl -> buildString {
            append("rule ${d.name} when ")
            append(d.trigger.keyword)
            append(condition(d.condition))
            if (d.preposition != null) append(" ${d.preposition} ${d.triggers.joinToString(", ")}")
            if (d.toleratesLoss || d.toleratesContention) append(
                " tolerates " + listOfNotNull(
                    "loss".takeIf { d.toleratesLoss }, "contention".takeIf { d.toleratesContention }
                ).joinToString(", ")
            )
            append(" {\n")
            d.body.forEach {
                when (it) {
                    ThenMarker -> append("    then\n")
                    is Assignment -> append("    ${expr(it.target)} = ${expr(it.value)}\n")
                    is Creation -> append("    ${creation(it)}\n")
                    is DeleteStmt -> append("    delete ${expr(it.target)}\n")
                }
            }
            append("}")
        }
        is NeverDecl -> prohibition(d) +
            if (d.toleratesContention) " tolerates contention" else ""
    }

    /** The prohibition as the author wrote it, without its tolerance clause:
     *  `never (X where P)` or `X requires P`. */
    fun prohibition(d: NeverDecl): String =
        d.requirement?.let { "${it.subject} requires ${expr(it.predicate)}" } ?: "never ${condition(d.target)}"

    /** The prohibition's body for a label: the forbidden configuration of a `never`
     *  (`X where P`, as diagnostics have always quoted it), or a requirement as written. */
    fun prohibitionBody(d: NeverDecl): String =
        d.requirement?.let { "${it.subject} requires ${expr(it.predicate)}" } ?: refExpr(d.target)

    private fun condition(e: RefExpr): String =
        if (e is RefName && e.where == null) e.name else "(${refExpr(e)})"

    private fun creation(c: Creation): String = buildString {
        append(c.shape)
        if (c.forExpr != null) {
            append(" for ${expr(c.forExpr)}")
            c.fields.forEach { append(" ${it.name}: ${expr(it.value)}") }
        } else {
            append(" from { ")
            append(c.fields.joinToString(", ") { "${it.name}: ${expr(it.value)}" })
            append(" }")
        }
    }

    fun member(m: Member): String = when (m) {
        is StoredProp -> buildString {
            append("${m.name}: ${type(m.type)}")
            m.initially?.let { append(" initially ${expr(it)}") }
            if (m.initiallyRequired) append(" initially required")
            m.tolerates?.let { append(" tolerates $it") }
        }
        is DerivedProp -> (if (m.captured) "captured " else "") + "${m.name}: ${type(m.type)} = ${expr(m.expr)}"
        is TimestampProp -> "${m.name}: timestamp on ${m.on}"
        is FrozenClause -> "frozen" + if (m.fields.isEmpty()) "" else " ${m.fields.joinToString(", ")}"
        UndeletableClause -> "undeletable"
    }

    fun type(t: TypeRef): String = when (t) {
        is ScalarType -> (if (t.many) "many " else "") + t.name + if (t.optional) "?" else ""
        is RelType -> (if (t.many) "many " else "one ") + t.shape + if (t.optional) "?" else ""
    }

    fun refExpr(e: RefExpr): String = when (e) {
        is RefName -> e.name + (e.where?.let { " where ${expr(it)}" } ?: "")
        is RefNot -> "not ${refOperand(e.inner)}"
        is RefAnd -> "${refOperand(e.left)} and ${refOperand(e.right)}"
        is RefOr -> "${refOperand(e.left)} or ${refOperand(e.right)}"
    }

    private fun refOperand(e: RefExpr): String =
        if (e is RefName && e.where != null) "(${refExpr(e)})"
        else if (e is RefOr || e is RefAnd) "(${refExpr(e)})"
        else refExpr(e)

    fun expr(e: Expr): String = when (e) {
        is IntLit -> e.value.toString()
        is DecLit -> e.text
        is TextLit -> "\"${e.value}\""
        is BoolLit -> e.value.toString()
        NoneLit -> "none"
        EmptyLit -> "empty"
        NowLit -> "now"
        TodayLit -> "today"
        is DurationLit -> "${e.amount} ${e.unit}"
        is PathExpr -> e.root + e.segs.joinToString("") { (if (it.viaQdot) "?." else ".") + it.name }
        is UnaryMinus -> "-${operand(e.inner)}"
        is Binary -> "${operand(e.left)} ${e.op} ${operand(e.right)}"
        is NotExpr ->
            if (e.inner is IsExpr && e.inner.kind == "refinement") "${operand(e.inner.subject)} is not ${e.inner.refinement}"
            else "not ${operand(e.inner)}"
        is IfExpr -> "if ${expr(e.condition)} then ${expr(e.thenExpr)} else ${expr(e.elseExpr)}"
        is IsExpr -> "${operand(e.subject)} ${if (e.toBe) "to be" else "is"} ${
            when (e.kind) {
                "none" -> "none"; "some" -> "some"; "empty" -> "empty"; "notEmpty" -> "not empty"
                else -> e.refinement ?: "?"
            }
        }"
        is ExistsExpr ->
            if (e.shape != null) "exists ${e.shape} for ${expr(e.forExpr!!)}"
            else "exists (${collection(e.collection!!)})"
        is AggCall -> "${e.name}(${collection(e.collection)}${e.field?.let { ", $it" } ?: ""}" +
            (if (e.orderBy.isEmpty()) "" else " by ${e.orderBy.joinToString(", ")}") + ")"
        is FunCall -> "${e.name}(${e.args.joinToString(", ") { expr(it) }})"
        is SingularFor -> "(${e.shape} for ${expr(e.forExpr)})"
        is SetExpr -> "(${collection(e.collection)})"
        is Access -> operand(e.target) + e.segs.joinToString("") { (if (it.viaQdot) "?." else ".") + it.name }
        is ShapeForSource -> "${e.shape} for ${expr(e.forExpr)}"
    }

    private fun operand(e: Expr): String = when (e) {
        is Binary, is IfExpr, is IsExpr, is NotExpr, is ExistsExpr -> "(${expr(e)})"
        else -> expr(e)
    }

    private fun collection(c: CollectionExpr): String = buildString {
        append(c.bindings.joinToString(", ") { b ->
            (if (b.source is ShapeForSource) expr(b.source) else expr(b.source)) +
                (b.alias?.let { " as $it" } ?: "")
        })
        c.where?.let { append(" where ${expr(it)}") }
    }
}
