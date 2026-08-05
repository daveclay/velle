package velle

/**
 * Recursive-descent parser for Velle, written from grammar.md.
 *
 * Newline discipline: newlines separate declarations, shape/refinement members,
 * `from { }` field inits, and rule-body statements (comma is an equivalent
 * separator within braces). Inside parentheses the lexer already suppressed
 * newlines. Where the grammar *requires* a following operand (after an infix
 * operator, `where`, `if`, ...) a newline is a continuation and is skipped.
 */
class Parser(private val tokens: List<Token>) {

    private var pos = 0

    companion object {
        fun parse(source: String): List<Decl> = Parser(Lexer(source).lex()).parseSpec()
    }

    fun parseSpec(): List<Decl> {
        val decls = mutableListOf<Decl>()
        skipNewlines()
        while (!at(TokType.EOF)) {
            decls.add(parseDecl())
            skipNewlines()
        }
        return decls
    }

    private fun parseDecl(): Decl {
        val t = peek()
        return when {
            t.isKw("shape") -> parseShapeOrRefinement()
            t.isKw("rule") -> parseRule()
            t.isKw("never") -> parseNever()
            t.isKw("expose") -> parseExpose()
            else -> fail("expected a declaration (shape/rule/never/expose)", t)
        }
    }

    // ── shape / refinement ───────────────────────────────────────────────────

    private fun parseShapeOrRefinement(): Decl {
        expectKw("shape")
        val name = expect(TokType.UIDENT).text
        return when {
            at(TokType.LBRACE) -> ShapeDecl(name, parseMemberBlock(refinementBody = false))
            at(TokType.ASSIGN) -> {
                next()
                skipNewlines()
                val expr = parseRefExpr()
                val members =
                    if (at(TokType.LBRACE)) parseMemberBlock(refinementBody = true) else emptyList()
                RefinementDecl(name, expr, members)
            }
            else -> fail("expected '{' or '=' after shape name", peek())
        }
    }

    private fun parseMemberBlock(refinementBody: Boolean): List<Member> {
        expect(TokType.LBRACE)
        val members = mutableListOf<Member>()
        skipSeparators()
        while (!at(TokType.RBRACE)) {
            members.add(parseMember(refinementBody))
            if (!at(TokType.RBRACE)) skipSeparators()
        }
        expect(TokType.RBRACE)
        return members
    }

    private fun parseMember(refinementBody: Boolean): Member {
        val t = peek()
        if (t.isKw("frozen")) {
            next()
            val fields = mutableListOf<String>()
            if (at(TokType.LIDENT)) {
                fields.add(next().text)
                while (at(TokType.COMMA)) { next(); fields.add(expect(TokType.LIDENT).text) }
            }
            if (!refinementBody) fail("'frozen' is only legal in a refinement body", t)
            return FrozenClause(fields)
        }
        if (t.isKw("captured")) {
            next()
            val name = expect(TokType.LIDENT).text
            expect(TokType.COLON)
            val type = parseTypeRef()
            expect(TokType.ASSIGN)
            skipNewlines()
            if (!refinementBody) fail("'captured' is only legal in a refinement body", t)
            return DerivedProp(name, type, parseValue(), captured = true)
        }
        val name = expect(TokType.LIDENT).text
        expect(TokType.COLON)
        if (peek().isKw("timestamp")) {
            next(); expectKw("on")
            val on = peek()
            if (!(on.isKw("create") || on.isKw("update"))) fail("expected 'create' or 'update'", on)
            next()
            return TimestampProp(name, on.text)
        }
        val type = parseTypeRef()
        if (at(TokType.ASSIGN)) {
            next(); skipNewlines()
            return DerivedProp(name, type, parseValue())
        }
        var initially: Expr? = null
        var tolerates: String? = null
        if (peek().isKw("initially")) { next(); skipNewlines(); initially = parseValue() }
        if (peek().isKw("tolerates")) { next(); tolerates = expect(TokType.LIDENT).text }
        if (refinementBody)
            fail("a refinement property is derived (= expr) or captured — stored properties belong to the base shape", peek())
        return StoredProp(name, type, initially, tolerates)
    }

    private fun parseTypeRef(): TypeRef {
        val t = peek()
        return when {
            t.isKw("one") || t.isKw("many") -> {
                next()
                val shape = expect(TokType.UIDENT).text
                val optional = consumeIf(TokType.QMARK)
                if (t.isKw("many") && optional) fail("'many' relationships take no '?'", t)
                RelType(many = t.isKw("many"), shape = shape, optional = optional)
            }
            t.type == TokType.KW && t.text in SCALAR_KEYWORDS -> {
                next(); ScalarType(t.text, consumeIf(TokType.QMARK))
            }
            t.type == TokType.UIDENT && (t.text == "Date" || t.text == "DateTime") -> {
                next(); ScalarType(t.text, consumeIf(TokType.QMARK))
            }
            else -> fail("expected a type", t)
        }
    }

    // ── rule ─────────────────────────────────────────────────────────────────

    private fun parseRule(): RuleDecl {
        expectKw("rule")
        val name = expect(TokType.UIDENT).text
        skipNewlines()
        expectKw("when")
        skipNewlines()
        val leaving = peek().isKw("leaving").also { if (it) next() }
        skipNewlines()
        val condition = parseCondition()
        skipNewlines()
        var preposition: String? = null
        val triggers = mutableListOf<String>()
        if (peek().isKw("on") || peek().isKw("after")) {
            preposition = next().text
            skipNewlines()
            triggers.add(parseTrigger())
            while (at(TokType.COMMA)) { next(); skipNewlines(); triggers.add(parseTrigger()) }
            skipNewlines()
        }
        var toleratesLoss = false
        if (peek().isKw("tolerates")) {
            next()
            val w = expect(TokType.LIDENT)
            if (w.text != "loss") fail("a rule signs 'tolerates loss'; field hazards live on the field", w)
            toleratesLoss = true
            skipNewlines()
        }
        val body = parseRuleBody()
        return RuleDecl(name, leaving, condition, preposition, triggers, toleratesLoss, body)
    }

    private fun parseCondition(): RefExpr = when {
        at(TokType.UIDENT) -> RefName(next().text)
        at(TokType.LPAREN) -> { next(); val e = parseRefExpr(); expect(TokType.RPAREN); e }
        else -> fail("expected a condition (refinement name or parenthesized refinement)", peek())
    }

    private fun parseTrigger(): String {
        val t = peek()
        return when {
            t.isKw("commit") -> { next(); "commit" }
            t.type == TokType.UIDENT -> next().text
            else -> fail("expected 'commit' or a schedule name", t)
        }
    }

    private fun parseRuleBody(): List<BodyItem> {
        expect(TokType.LBRACE)
        val items = mutableListOf<BodyItem>()
        skipSeparators()
        while (!at(TokType.RBRACE)) {
            if (peek().isKw("then")) {
                next()
                items.add(ThenMarker)
            } else {
                items.add(parseStatement())
            }
            if (!at(TokType.RBRACE)) skipSeparators()
        }
        expect(TokType.RBRACE)
        return items
    }

    private fun parseStatement(): BodyItem {
        if (at(TokType.UIDENT)) return parseCreation()
        // assignment: literal static path (README §12) — plain '.' only
        val root = when {
            peek().isKw("this") -> { next(); "this" }
            at(TokType.LIDENT) -> next().text
            else -> fail("expected a statement (creation or assignment)", peek())
        }
        val segs = mutableListOf<Seg>()
        while (at(TokType.DOT)) { next(); segs.add(Seg(expect(TokType.LIDENT).text, viaQdot = false)) }
        expect(TokType.ASSIGN)
        skipNewlines()
        return Assignment(PathExpr(root, segs), parseValue())
    }

    private fun parseCreation(): Creation {
        val shape = expect(TokType.UIDENT).text
        return when {
            peek().isKw("from") -> {
                next()
                expect(TokType.LBRACE)
                val fields = mutableListOf<FieldInit>()
                skipSeparators()
                while (!at(TokType.RBRACE)) {
                    val fname = expect(TokType.LIDENT).text
                    expect(TokType.COLON)
                    skipNewlines()
                    fields.add(FieldInit(fname, parseValue()))
                    if (!at(TokType.RBRACE)) skipSeparators()
                }
                expect(TokType.RBRACE)
                Creation(shape, fields = fields)
            }
            peek().isKw("for") -> {
                next()
                val forExpr = parseValue()
                val fields = mutableListOf<FieldInit>()
                while (at(TokType.LIDENT)) {
                    val fname = next().text
                    expect(TokType.COLON)
                    fields.add(FieldInit(fname, parseValue()))
                }
                Creation(shape, forExpr = forExpr, fields = fields)
            }
            else -> fail("expected 'from' or 'for' after created shape name", peek())
        }
    }

    // ── never / expose ───────────────────────────────────────────────────────

    private fun parseNever(): NeverDecl {
        expectKw("never")
        return NeverDecl(parseCondition())
    }

    private fun parseExpose(): Decl {
        expectKw("expose")
        if (peek().isKw("shape")) {
            val decl = parseShapeOrRefinement()
            if (decl !is ShapeDecl) fail("only base shapes can be exposed inline", peek())
            expectKw("using")
            val mechanism = expect(TokType.UIDENT).text
            return (decl as ShapeDecl).copy(exposedVia = mechanism)
        }
        val shape = expect(TokType.UIDENT).text
        expectKw("using")
        return ExposeDecl(shape, expect(TokType.UIDENT).text)
    }

    // ── refinement expressions ───────────────────────────────────────────────

    private fun parseRefExpr(): RefExpr {
        var left = parseRefTerm()
        while (peek().isKw("or")) { next(); skipNewlines(); left = RefOr(left, parseRefTerm()) }
        return left
    }

    private fun parseRefTerm(): RefExpr {
        var left = parseRefFactor()
        while (peek().isKw("and")) { next(); skipNewlines(); left = RefAnd(left, parseRefFactor()) }
        return left
    }

    private fun parseRefFactor(): RefExpr {
        if (peek().isKw("not")) { next(); skipNewlines(); return RefNot(parseRefFactor()) }
        return when {
            at(TokType.UIDENT) -> {
                val name = next().text
                if (peek().isKw("where")) {
                    next(); skipNewlines()
                    RefName(name, parsePredicate())
                } else RefName(name)
            }
            at(TokType.LPAREN) -> { next(); val e = parseRefExpr(); expect(TokType.RPAREN); e }
            else -> fail("expected a shape or refinement name", peek())
        }
    }

    // ── predicates (README §10) ──────────────────────────────────────────────

    fun parsePredicate(): Expr {
        var left = parseConjunction()
        while (peek().isKw("or")) { next(); skipNewlines(); left = Binary("or", left, parseConjunction()) }
        return left
    }

    private fun parseConjunction(): Expr {
        var left = parseNegation()
        while (peek().isKw("and")) { next(); skipNewlines(); left = Binary("and", left, parseNegation()) }
        return left
    }

    private fun parseNegation(): Expr {
        if (peek().isKw("not")) { next(); skipNewlines(); return NotExpr(parseNegation()) }
        return parseAtom()
    }

    private fun parseAtom(): Expr {
        if (peek().isKw("exists")) return parseExists()
        val left = parseValue()
        val t = peek()
        return when {
            t.isKw("is") -> { next(); parseIsRhs(left) }
            t.type in COMPARISON_OPS -> {
                val op = next().text
                skipNewlines()
                Binary(op, left, parseValue())
            }
            else -> left // boolean atom, or a plain value in value position
        }
    }

    private fun parseIsRhs(subject: Expr): Expr {
        val t = peek()
        return when {
            t.isKw("none") -> { next(); IsExpr(subject, "none") }
            t.isKw("some") -> { next(); IsExpr(subject, "some") }
            t.isKw("empty") -> { next(); IsExpr(subject, "empty") }
            t.isKw("not") -> { next(); expectKw("empty"); IsExpr(subject, "notEmpty") }
            t.type == TokType.UIDENT -> { next(); IsExpr(subject, "refinement", t.text) }
            else -> fail("expected none/some/empty/'not empty'/RefinementName after 'is'", t)
        }
    }

    private fun parseExists(): Expr {
        expectKw("exists")
        skipNewlines()
        if (at(TokType.LPAREN)) {
            next()
            val coll = parseCollectionExpr()
            expect(TokType.RPAREN)
            return ExistsExpr(collection = coll)
        }
        val shape = expect(TokType.UIDENT).text
        expectKw("for")
        skipNewlines()
        return ExistsExpr(shape = shape, forExpr = parseValue())
    }

    // ── values ───────────────────────────────────────────────────────────────

    fun parseValue(): Expr {
        if (peek().isKw("if")) return parseIf()
        return parseAdditive()
    }

    private fun parseIf(): Expr {
        expectKw("if")
        skipNewlines()
        val cond = parsePredicate()
        expectKw("then")
        skipNewlines()
        val thenE = parseValue()
        skipNewlines()
        expectKw("else")
        skipNewlines()
        val elseE = if (peek().isKw("if")) parseIf() else parseValue()
        return IfExpr(cond, thenE, elseE)
    }

    private fun parseAdditive(): Expr {
        var left = parseMultiplicative()
        while (at(TokType.PLUS) || at(TokType.MINUS)) {
            val op = next().text
            skipNewlines()
            left = Binary(op, left, parseMultiplicative())
        }
        return left
    }

    private fun parseMultiplicative(): Expr {
        var left = parseUnary()
        while (at(TokType.STAR) || at(TokType.SLASH)) {
            val op = next().text
            skipNewlines()
            left = Binary(op, left, parseUnary())
        }
        return left
    }

    private fun parseUnary(): Expr {
        if (at(TokType.MINUS)) { next(); return UnaryMinus(parseUnary()) }
        return parsePostfix()
    }

    private fun parsePostfix(): Expr {
        var e = parsePrimary()
        while (at(TokType.DOT) || at(TokType.QDOT)) {
            val viaQdot = next().type == TokType.QDOT
            val seg = Seg(expect(TokType.LIDENT).text, viaQdot)
            e = when (e) {
                is PathExpr -> e.copy(segs = e.segs + seg)
                is Access -> e.copy(segs = e.segs + seg)
                else -> Access(e, listOf(seg))
            }
        }
        return e
    }

    private fun parsePrimary(): Expr {
        val t = peek()
        return when {
            t.type == TokType.INT -> {
                next()
                val unit = peek()
                if (unit.type == TokType.KW && unit.text in DURATION_UNITS) {
                    next(); DurationLit(t.text.toLong(), unit.text)
                } else IntLit(t.text.toLong())
            }
            t.type == TokType.DEC -> { next(); DecLit(t.text) }
            t.type == TokType.TEXT -> { next(); TextLit(t.text) }
            t.isKw("true") -> { next(); BoolLit(true) }
            t.isKw("false") -> { next(); BoolLit(false) }
            t.isKw("none") -> { next(); NoneLit }
            t.isKw("now") -> { next(); NowLit }
            t.isKw("today") -> { next(); TodayLit }
            t.isKw("this") -> { next(); PathExpr("this") }
            t.type == TokType.LIDENT -> parseNameOrCall()
            t.type == TokType.UIDENT -> { next(); PathExpr(t.text) }
            t.type == TokType.LPAREN -> parseParenPrimary()
            else -> fail("expected an expression", t)
        }
    }

    private fun parseNameOrCall(): Expr {
        val name = expect(TokType.LIDENT).text
        if (!at(TokType.LPAREN)) return PathExpr(name)
        next() // consume '('
        val result = when (name) {
            "count", "latest", "first" -> AggCall(name, parseCollectionExpr())
            "sum" -> {
                // sum's collection is the single-binding form; its second argument
                // is the summed field (grammar.md, aggregateCall)
                val coll = parseCollectionExpr(singleBinding = true)
                expect(TokType.COMMA)
                AggCall(name, coll, expect(TokType.LIDENT).text)
            }
            else -> {
                val args = mutableListOf<Expr>()
                if (!at(TokType.RPAREN)) {
                    args.add(parseValue())
                    while (at(TokType.COMMA)) { next(); args.add(parseValue()) }
                }
                FunCall(name, args)
            }
        }
        expect(TokType.RPAREN)
        return result
    }

    private fun parseParenPrimary(): Expr {
        expect(TokType.LPAREN)
        // `(Shape for expr)` — the singular query form
        if (at(TokType.UIDENT) && peekAhead(1).isKw("for")) {
            val shape = next().text
            next() // for
            val e = parseValue()
            expect(TokType.RPAREN)
            return SingularFor(shape, e)
        }
        val inner = parsePredicate()
        expect(TokType.RPAREN)
        return inner
    }

    private fun parseCollectionExpr(singleBinding: Boolean = false): CollectionExpr {
        val bindings = mutableListOf(parseBinding())
        while (!singleBinding && at(TokType.COMMA)) { next(); bindings.add(parseBinding()) }
        var where: Expr? = null
        if (peek().isKw("where")) { next(); skipNewlines(); where = parsePredicate() }
        return CollectionExpr(bindings, where)
    }

    private fun parseBinding(): Binding {
        val source: Expr = when {
            at(TokType.UIDENT) -> {
                val shape = next().text
                if (peek().isKw("for")) { next(); ShapeForSource(shape, parseValue()) }
                else parseBindingPathFrom(shape)
            }
            at(TokType.LIDENT) -> parseBindingPathFrom(next().text)
            peek().isKw("this") -> { next(); parseBindingPathFrom("this") }
            else -> fail("expected a collection (relationship path or shape name)", peek())
        }
        var alias: String? = null
        if (peek().isKw("as")) { next(); alias = expect(TokType.LIDENT).text }
        return Binding(source, alias)
    }

    private fun parseBindingPathFrom(root: String): PathExpr {
        val segs = mutableListOf<Seg>()
        while (at(TokType.DOT)) { next(); segs.add(Seg(expect(TokType.LIDENT).text, viaQdot = false)) }
        return PathExpr(root, segs)
    }

    // ── plumbing ─────────────────────────────────────────────────────────────

    private val COMPARISON_OPS = setOf(
        TokType.EQ, TokType.NEQ, TokType.LT, TokType.LE, TokType.GT, TokType.GE
    )

    private fun peek(): Token = tokens[pos]
    private fun peekAhead(n: Int): Token = tokens.getOrElse(pos + n) { tokens.last() }
    private fun next(): Token = tokens[pos++]
    private fun at(type: TokType) = peek().type == type

    private fun consumeIf(type: TokType): Boolean {
        if (at(type)) { next(); return true }
        return false
    }

    private fun expect(type: TokType): Token {
        val t = peek()
        if (t.type != type) fail("expected $type", t)
        return next()
    }

    private fun expectKw(word: String): Token {
        val t = peek()
        if (!t.isKw(word)) fail("expected '$word'", t)
        return next()
    }

    private fun skipNewlines() { while (at(TokType.NEWLINE)) next() }
    private fun skipSeparators() { while (at(TokType.NEWLINE) || at(TokType.COMMA)) next() }

    private fun fail(message: String, t: Token): Nothing =
        throw VelleSyntaxError("$message, found ${t.type}('${t.text}')", t.line, t.col)
}
