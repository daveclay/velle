package velle

/** Pretty-prints AST back to Velle source — provenance headers for generated specs. */
object Printer {

    fun print(d: Decl): String = when (d) {
        is ShapeDecl -> buildString {
            if (d.exposed) append(if (d.transient) "expose transient " else "expose ")
            append("shape ${d.name} {\n")
            d.members.forEach { append("    ${member(it)}\n") }
            append("}")
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
            if (d.leaving) append("leaving ")
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
                }
            }
            append("}")
        }
        is NeverDecl -> "never ${condition(d.target)}" +
            if (d.toleratesContention) " tolerates contention" else ""
        is ExposeDecl -> "expose ${if (d.transient) "transient " else ""}${d.shape}"
    }

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
            m.tolerates?.let { append(" tolerates $it") }
        }
        is DerivedProp -> (if (m.captured) "captured " else "") + "${m.name}: ${type(m.type)} = ${expr(m.expr)}"
        is TimestampProp -> "${m.name}: timestamp on ${m.on}"
        is FrozenClause -> "frozen" + if (m.fields.isEmpty()) "" else " ${m.fields.joinToString(", ")}"
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
        is NotExpr -> "not ${operand(e.inner)}"
        is IfExpr -> "if ${expr(e.condition)} then ${expr(e.thenExpr)} else ${expr(e.elseExpr)}"
        is IsExpr -> "${operand(e.subject)} is ${
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
