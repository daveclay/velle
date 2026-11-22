package velle.validation

import velle.*
import velle.ext.builtin.RegexType

// ── text patterns and text literals with pieces (Reference §5, §10; checks F2, A7) ──
//
// The right of `matches` is a pattern that compiles; a text built from pieces
// is read as a pattern only where its pieces were meant as pattern; and each
// piece of a text literal is a value.

private fun Validator.isRegexType(type: VType?): Boolean = ((type as? VType.Optional)?.inner ?: type) == VType.Ext(RegexType.QUALIFIED_NAME)

/** The right of `matches` (Reference §10): a quoted regular expression, checked here so a malformed one
 *  never reaches a rule; a literal with pieces, whose fixed runs are checked around a placeholder for
 *  each piece; or a `Regex`, which is never malformed. */
internal fun Validator.checkMatchesPattern(matching: Binary, scope: String) {
    val spelled = "'${Printer.expr(matching)}'"
    when (val right = matching.right) {
        is TextLit -> compileOrRefuse(right.value) { reason -> "$spelled — the pattern is not a regular expression: $reason" }
        is Interpolation -> {
            val fixedRuns = right.parts.joinToString("") { part -> if (part is TextRun) part.text else "(?:x)" }
            compileOrRefuse(fixedRuns) { reason -> "$spelled — the pattern is not a regular expression around its pieces: $reason" }
        }
        // a path whose type the validator cannot read off the model (a bare name inside an `exists` body) is left alone, as every type check leaves it
        else -> scalarTypeOf(right, scope)?.let { type ->
            if (!isRegexType(type))
                diagnostics.add(Diagnostic("F2", "$spelled — 'matches' takes a quoted regular expression, a text literal with pieces, or a Regex on the right"))
        }
    }
}

private fun Validator.compileOrRefuse(pattern: String, message: (String) -> String) {
    try { Regex(pattern) } catch (error: java.util.regex.PatternSyntaxException) {
        diagnostics.add(Diagnostic("F2", message(error.description)))
    }
}

/** `regex(expr)` over a derived text built from pieces (Reference §10): the text's pieces were characters
 *  when it was built and are pattern when it is read; declaring the property `Regex` keeps them. */
internal fun Validator.checkPatternRead(call: FunCall, scope: String) {
    val path = call.args.singleOrNull() as? PathExpr ?: return
    val member = membersAlong(path, scope)?.lastOrNull() ?: return
    if (member.derived?.expr !is Interpolation) return
    diagnostics.add(Diagnostic("A7", "'${Printer.expr(call)}' — '${member.name}' is a text built from pieces, and reading it as a pattern reads its pieces " +
        "as pattern too; declare '${member.name}' as `Regex` to keep a text piece as characters", advisory = true))
}

/** The members a path reads, hop by hop, from [scope]; null when a hop is not a member. */
internal fun Validator.membersAlong(path: PathExpr, scope: String): List<MemberInfo>? {
    val names = if (path.root == "this") path.segs.map { segment -> segment.name } else listOf(path.root) + path.segs.map { segment -> segment.name }
    val members = mutableListOf<MemberInfo>()
    var current: String? = scope
    for (name in names) {
        val member = current?.let { shape -> model.membersOf(shape)[name] } ?: return null
        members.add(member)
        current = member.type.instanceShape()
    }
    return members
}

/** The spelling of `a + b` over texts as a text literal with pieces (Reference §5). */
internal fun Validator.interpolationFor(sum: Binary): String = "\"" + pieceSpelling(sum.left) + pieceSpelling(sum.right) + "\""

private fun pieceSpelling(side: Expr): String = if (side is TextLit) side.value else "\${" + Printer.expr(side) + "}"

/** The pieces of a text literal (Reference §5): each is checked as any expression, and is a value. */
internal fun Validator.checkPieces(literal: Interpolation, scope: String) {
    for (piece in literal.pieces) {
        checkExpr(piece, scope)
        val pieceType = scalarTypeOf(piece, scope)
        when {
            piece is SetExpr || pieceType is VType.Coll || pieceType is VType.CollS ->
                diagnostics.add(Diagnostic("F2", "'${Printer.expr(literal)}' — a piece of a text is a value, but '${Printer.expr(piece)}' is a collection"))
            pieceType is VType.Inst ->
                diagnostics.add(Diagnostic("F2", "'${Printer.expr(literal)}' — a piece of a text is a value, but '${Printer.expr(piece)}' is a record; " +
                    "write the field you mean, such as '${Printer.expr(piece)}.${firstTextFieldOf(pieceType.shape)}'"))
        }
    }
}

private fun Validator.firstTextFieldOf(shape: String): String =
    model.membersOf(shape).values.firstOrNull { member -> member.stored && member.type == VType.Text }?.name ?: "<field>"
