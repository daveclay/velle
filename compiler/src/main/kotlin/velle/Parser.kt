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
            t.type == TokType.UIDENT && peekAhead(1).isKw("requires") -> parseRequires()
            t.isKw("act") || t.isKw("transient") -> parseAct()
            t.isKw("uses") -> parseUses()
            // the spec-wide visibility default (Reference §22): `visible to Staff` / `visible to anyone`
            atVisible() -> {
                val c = parseVisibleClause(allowWhere = false)
                VisibilityDefault(c)
            }
            else -> fail("expected a declaration (shape/act/rule/never/requires/visible/uses)", t)
        }
    }

    // ── uses ─────────────────────────────────────────────────────────────────

    /** `uses velle.finance.Money [as Cash]` or `uses velle.finance.roundToCents [as round]`: a
     *  dotted lowercase path ending in a type's capitalized name or a function's lowercase
     *  one (Reference §5, extension types and functions). The alias matches the case. */
    private fun parseUses(): UsesDecl {
        expectKw("uses")
        val parts = mutableListOf<String>()
        val first = peek()
        if (!at(TokType.LIDENT)) fail("'uses' takes a qualified type or function name such as velle.finance.Money", first)
        parts.add(next().text)
        while (consumeIf(TokType.DOT)) {
            val t = peek()
            if (!at(TokType.LIDENT) && !at(TokType.UIDENT)) fail("'uses' takes a qualified type or function name such as velle.finance.Money", t)
            parts.add(next().text)
        }
        if (parts.size < 2) fail("'uses' names a type or a function by its library: velle.finance.Money, velle.finance.roundToCents", first)
        val isType = parts.last().first().isUpperCase()
        val alias = if (peek().isKw("as")) {
            next()
            if (isType) expect(TokType.UIDENT).text
            else { val t = peek(); if (!at(TokType.LIDENT)) fail("a function's alias is a lowercase identifier", t); next().text }
        } else null
        return UsesDecl(parts.joinToString("."), alias)
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

    /** `visible` is contextual (grammar.md): the visibility clause's first word only
     *  at the start of a body member, after a field, or at the top level. */
    private fun atVisible() = at(TokType.LIDENT) && peek().text == "visible"

    /**
     * `visible to <Shape> [as name] [where predicate]` | `visible to anyone`
     * (Reference §22, "The actor"). [allowWhere] is false for the top-level
     * default, which names a shape and nothing else.
     */
    private fun parseVisibleClause(allowWhere: Boolean): VisibleClause {
        expect(TokType.LIDENT) // `visible`
        val to = expect(TokType.LIDENT)
        if (to.text != "to") fail("expected 'to' after 'visible' (visible to <Shape> | visible to anyone)", to)
        val shape: String? = when {
            at(TokType.UIDENT) -> next().text
            at(TokType.LIDENT) && peek().text == "anyone" -> { next(); null }
            else -> fail("expected a shape name or 'anyone' after 'visible to'", peek())
        }
        var name = "actor"
        var named = false
        if (peek().isKw("as")) {
            if (shape == null) fail("'visible to anyone' binds no viewer to name", peek())
            next(); name = expect(TokType.LIDENT).text; named = true
        }
        var where: Expr? = null
        if (peek().isKw("where")) {
            if (!allowWhere) fail("the spec-wide default names a shape and nothing else: a predicate belongs on the shape it reads", peek())
            if (shape == null) fail("'visible to anyone' takes no predicate — it admits every viewer", peek())
            next(); skipNewlines()
            where = parsePredicate()
        }
        return VisibleClause(shape, name, named, where)
    }

    /** The field-level clauses after a property's declaration, if any. */
    private fun parseFieldVisibility(): List<VisibleClause> {
        val out = mutableListOf<VisibleClause>()
        while (atVisible()) out.add(parseVisibleClause(allowWhere = true))
        return out
    }

    private fun parseMember(refinementBody: Boolean): Member {
        val t = peek()
        if (atVisible()) {
            // record-level: a body clause in the family of `frozen` and `undeletable`
            return parseVisibleClause(allowWhere = true)
        }
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
        if (t.isKw("undeletable")) {
            next()
            if (!refinementBody) fail("'undeletable' is only legal in a refinement body — the deletion gate is state-scoped (OQ37)", t)
            return UndeletableClause
        }
        if (t.isKw("captured")) {
            next()
            val name = expect(TokType.LIDENT).text
            expect(TokType.COLON)
            val type = parseTypeRef()
            expect(TokType.ASSIGN)
            skipNewlines()
            if (!refinementBody) fail("'captured' is only legal in a refinement body", t)
            val value = parseValue()
            return DerivedProp(name, type, value, captured = true, visible = parseFieldVisibility())
        }
        val name = expect(TokType.LIDENT).text
        expect(TokType.COLON)
        if (peek().isKw("timestamp")) {
            next(); expectKw("on")
            val on = peek()
            if (!(on.isKw("create") || on.isKw("update"))) fail("expected 'create' or 'update'", on)
            next()
            return TimestampProp(name, on.text, visible = parseFieldVisibility())
        }
        val type = parseTypeRef()
        if (at(TokType.ASSIGN)) {
            next(); skipNewlines()
            val value = parseValue()
            return DerivedProp(name, type, value, visible = parseFieldVisibility())
        }
        var initially: Expr? = null
        var initiallyRequired = false
        var tolerates: String? = null
        if (peek().isKw("initially")) {
            next(); skipNewlines()
            // `required` is contextual, not reserved (grammar.md): it reads as the
            // creation-side requiredness marker only here, after `initially` (OQ37-R10)
            if (at(TokType.LIDENT) && peek().text == "required") { next(); initiallyRequired = true }
            else initially = parseValue()
        }
        if (peek().isKw("tolerates")) { next(); tolerates = expect(TokType.LIDENT).text }
        val visible = parseFieldVisibility()
        if (refinementBody)
            fail("a refinement property is derived (= expr) or captured — stored properties belong to the base shape", peek())
        // `many text tolerates duplication`: the collection keeps duplicates, in the order
        // given (Reference §5, "Collections of values"); the clause is the type's flag here
        val typed = if (type is ScalarType && type.many && tolerates == "duplication") type.copy(ordered = true) else type
        return StoredProp(name, typed, initially, tolerates, initiallyRequired, visible)
    }

    private fun parseTypeRef(): TypeRef {
        val t = peek()
        return when {
            t.isKw("one") || t.isKw("many") -> {
                next()
                val many = t.isKw("many")
                val target = peek()
                // `many` also takes a scalar — an owned collection of values (Reference §6)
                // a capitalized name after `one`/`many` is a shape here; `many Money`
                // (an owned set of an extension type's values) is resolved by the model
                val scalar = when {
                    target.type == TokType.KW && target.text in SCALAR_KEYWORDS -> target.text
                    target.type == TokType.UIDENT && (target.text == "Date" || target.text == "DateTime") -> target.text
                    else -> null
                }
                if (scalar != null) {
                    if (!many) fail("'one' takes a shape — a scalar field needs no cardinality", target)
                    next()
                    if (consumeIf(TokType.QMARK)) fail("a 'many' takes no '?' — the empty collection is the absence", target)
                    return ScalarType(scalar, optional = false, many = true)
                }
                val shape = expect(TokType.UIDENT).text
                val optional = consumeIf(TokType.QMARK)
                if (many && optional) fail("a 'many' takes no '?' — the empty collection is the absence", t)
                RelType(many = many, shape = shape, optional = optional)
            }
            t.type == TokType.KW && t.text in SCALAR_KEYWORDS -> {
                next(); ScalarType(t.text, consumeIf(TokType.QMARK))
            }
            // `Date`, `DateTime`, or a registered extension type by the name the spec
            // gives it (Reference §5): the model resolves the name, F1 when nothing has it
            t.type == TokType.UIDENT -> {
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
        // the trigger family (Reference §13): `when X` / `when leaving X` /
        // `when deleted X` / `when leaving or deleted X` / `when leaving not deleted X`
        val trigger = when {
            peek().isKw("leaving") -> {
                next(); skipNewlines()
                when {
                    peek().isKw("or") -> { next(); skipNewlines(); expectKw("deleted"); Trigger.LEAVING_OR_DELETED }
                    peek().isKw("not") -> { next(); skipNewlines(); expectKw("deleted"); Trigger.LEAVING_NOT_DELETED }
                    else -> Trigger.LEAVING
                }
            }
            peek().isKw("deleted") -> { next(); Trigger.DELETED }
            else -> Trigger.ENTRY
        }
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
        // `by Shape[?] [as name]` (Reference §22, "The actor"): the rule reads the
        // actor of the transaction it fires in. `by` is contextual (grammar.md):
        // it reads as this clause only here, after the trigger clause
        var by: RuleActor? = null
        if (atBy()) {
            next()
            val shape = expect(TokType.UIDENT).text
            val optional = consumeIf(TokType.QMARK)
            val (name, named) = parseActorName()
            by = RuleActor(shape, optional, name, named)
            skipNewlines()
        }
        var toleratesLoss = false
        var toleratesContention = false
        if (peek().isKw("tolerates")) {
            next()
            do {
                val w = expect(TokType.LIDENT)
                when (w.text) {
                    "loss" -> toleratesLoss = true
                    "contention" -> toleratesContention = true
                    else -> fail("a rule signs 'tolerates loss' or 'tolerates contention'; field hazards live on the field", w)
                }
            } while (at(TokType.COMMA).also { if (it) next() })
            skipNewlines()
        }
        val body = parseRuleBody()
        return RuleDecl(name, trigger, condition, preposition, triggers, toleratesLoss, body, toleratesContention, by)
    }

    /** `by` is contextual, not reserved: the actor clause's keyword only in the
     *  two header positions the grammar gives it (an act's, a rule's), and the
     *  selector-ordering keyword after `latest`/`first`; an identifier elsewhere. */
    private fun atBy() = at(TokType.LIDENT) && peek().text == "by"

    /** The optional `as name` after an actor clause's shape: the name the actor
     *  is bound under, `actor` by default. Nothing is reserved — a scope that
     *  declares a field under the bound name is the validator's error (F3). */
    private fun parseActorName(): Pair<String, Boolean> {
        if (!peek().isKw("as")) return "actor" to false
        next()
        return expect(TokType.LIDENT).text to true
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
        if (peek().isKw("delete")) {
            // deletion: literal static path, like assignment (OQ37)
            next()
            val root = when {
                peek().isKw("this") -> { next(); "this" }
                at(TokType.LIDENT) -> next().text
                else -> fail("expected the delete target (a literal static path)", peek())
            }
            val segs = mutableListOf<Seg>()
            while (at(TokType.DOT)) { next(); segs.add(Seg(expect(TokType.LIDENT).text, viaQdot = false)) }
            return DeleteStmt(PathExpr(root, segs))
        }
        if (at(TokType.UIDENT)) return parseCreation()
        if (peek().isKw("for") && peekAhead(1).let { it.type == TokType.LIDENT && it.text == "each" }) return parseForEach()
        // assignment: literal static path (Reference §12) — plain '.' only
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

    /** `for each a in X, b from 1 to n { ... }` (Reference §14): `each`, `in`, and `to` are contextual words. */
    private fun parseForEach(): ForEach {
        expectKw("for")
        next() // each
        val bindings = mutableListOf<LoopBinding>()
        do {
            skipNewlines()
            val name = expect(TokType.LIDENT).text
            val word = peek()
            val source: LoopSource = when {
                word.isKw("from") -> {
                    next(); skipNewlines()
                    val from = parseValue()
                    val toWord = peek()
                    if (toWord.type != TokType.LIDENT || toWord.text != "to") fail("expected 'to' after the first bound of a `for each ... from`", toWord)
                    next(); skipNewlines()
                    LoopSource.Range(from, parseValue())
                }
                word.type == TokType.LIDENT && word.text == "in" -> { next(); skipNewlines(); LoopSource.Over(parseValue()) }
                else -> fail("expected 'in <collection>' or 'from <number> to <number>' after the name of a `for each`", word)
            }
            bindings.add(LoopBinding(name, source))
        } while (consumeIf(TokType.COMMA))
        skipNewlines()
        return ForEach(bindings, parseRuleBody())
    }

    private fun parseCreation(): Creation {
        val shape = expect(TokType.UIDENT).text
        val creation = parseCreationBody(shape)
        // `as <name>` names the created record for the rest of the body (Reference §14)
        if (peek().isKw("as")) { next(); return creation.copy(alias = expect(TokType.LIDENT).text) }
        return creation
    }

    private fun parseCreationBody(shape: String): Creation {
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

    // ── never / act ───────────────────────────────────────────────────────

    private fun parseNever(): NeverDecl {
        expectKw("never")
        val target = parseCondition()
        return NeverDecl(target, parseToleratesContention("never"))
    }

    /**
     * `Subject requires <predicate>` — the requirement spelling of a prohibition
     * (Reference §21): every Subject satisfies the predicate. Desugared here to
     * `never (Subject where <complement>)`, with the complement pushed through
     * the predicate so the rest of the compiler sees the same forbidden
     * configuration a hand-written `never` would state.
     */
    private fun parseRequires(): NeverDecl {
        val subject = expect(TokType.UIDENT).text
        expectKw("requires")
        skipNewlines()
        inRequirement = true
        val predicate = try { parsePredicate() } finally { inRequirement = false }
        val target = RefName(subject, complementOf(predicate))
        return NeverDecl(target, parseToleratesContention("requirement"), Requirement(subject, predicate))
    }

    private fun parseToleratesContention(what: String): Boolean {
        if (!peek().isKw("tolerates")) return false
        next()
        val w = expect(TokType.LIDENT)
        if (w.text != "contention") fail("a $what signs 'tolerates contention' only (OQ40)", w)
        return true
    }

    /** True while the predicate of a `requires` declaration is being read: the one
     *  position where `to be` spells membership (Reference §21). */
    private var inRequirement = false


    /** An act (Reference §22, "External input"): a base shape whose instances arrive
     *  from outside, declared in exactly one place — `[transient] act Name { ... } [with ...]`. */
    private fun parseAct(): Decl {
        val transient = if (peek().isKw("transient")) { next(); true } else false
        expectKw("act")
        val name = expect(TokType.UIDENT).text
        // `by Shape [as name]` (Reference §22, "The actor"): who commits this act,
        // bound in the act's scope and stored on a durable act. An act without
        // the clause has no actor — a processor verdict arrives from a machine
        var actor: ActorProp? = null
        if (atBy()) {
            val byTok = next()
            val shape = expect(TokType.UIDENT).text
            if (at(TokType.QMARK))
                fail("an act's actor is never absent: an act declared `by` takes one at every commit, and an act without `by` has none", byTok)
            val (actorName, named) = parseActorName()
            actor = ActorProp(actorName, shape, named)
        }
        if (!at(TokType.LBRACE))
            fail("expected '{' after the act's name — an act is a base shape declared with its fields, never a refinement", peek())
        val members = parseMemberBlock(refinementBody = false)
        return ShapeDecl(name, listOfNotNull(actor) + members, exposed = true, transient = transient, with = parseWithClause())
    }

    // ── the exposure closure (Reference §6, "Inline part creation") ─────────────
    // `with` is contextual, not reserved (grammar.md): it reads as the closure
    // keyword only here — after the act's body (or a closure entry) — and is
    // an ordinary identifier everywhere else.

    private fun atWith() = at(TokType.LIDENT) && peek().text == "with"

    private fun parseWithClause(): List<WithEntry> {
        if (!atWith()) return emptyList()
        next() // consume 'with'
        skipNewlines()
        if (at(TokType.LBRACE)) {
            next()
            val entries = mutableListOf<WithEntry>()
            skipSeparators()
            while (!at(TokType.RBRACE)) {
                entries.add(parseWithEntry())
                if (!at(TokType.RBRACE)) skipSeparators()
            }
            expect(TokType.RBRACE)
            return entries
        }
        val entries = mutableListOf(parseWithEntry())
        while (at(TokType.COMMA)) { next(); skipNewlines(); entries.add(parseWithEntry()) }
        return entries
    }

    private fun parseWithEntry(): WithEntry {
        val name = expect(TokType.LIDENT).text
        return WithEntry(name, parseWithClause())
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

    // ── predicates (Reference §10) ──────────────────────────────────────────────

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
            t.type == TokType.LIDENT && t.text == "to" && peekAhead(1).let { it.type == TokType.LIDENT && it.text == "be" } -> {
                if (!inRequirement) fail("'to be' spells membership only in the predicate of a 'requires' declaration; write 'is' here", t)
                next(); next()
                val r = peek()
                if (r.type != TokType.UIDENT) fail("expected RefinementName after 'to be'; optionality, emptiness, and non-membership are spelled 'is some', 'is not empty', 'is not'", r)
                next()
                IsExpr(left, "refinement", r.text, toBe = true)
            }
            t.type in COMPARISON_OPS -> {
                val op = next().text
                skipNewlines()
                Binary(op, left, parseValue())
            }
            // the text operators are contextual words (Tokens.kt, TEXT_OPERATORS):
            // `contains` / `like` / `matches`, and `starts with` / `ends with` as two words
            t.type == TokType.LIDENT && (t.text == "contains" || t.text == "like" || t.text == "matches") -> {
                val op = next().text
                skipNewlines()
                Binary(op, left, parseValue())
            }
            // `x in [a, b]` and `x not in [a, b]` (Reference §10): membership of a listed set of values
            t.type == TokType.LIDENT && t.text == "in" -> {
                next()
                skipNewlines()
                Binary("in", left, parseValue())
            }
            t.isKw("not") && peekAhead(1).let { it.type == TokType.LIDENT && it.text == "in" } -> {
                next(); next()
                skipNewlines()
                NotExpr(Binary("in", left, parseValue()))
            }
            // `x between a and b` (Reference §10) is `x >= a and x <= b`, both bounds included
            t.type == TokType.LIDENT && t.text == "between" -> {
                next()
                skipNewlines()
                val lo = parseValue()
                skipNewlines()
                expectKw("and")
                skipNewlines()
                val hi = parseValue()
                Binary("and", Binary(">=", left, lo), Binary("<=", left, hi))
            }
            t.type == TokType.LIDENT && (t.text == "starts" || t.text == "ends") &&
                peekAhead(1).let { it.type == TokType.LIDENT && it.text == "with" } -> {
                val op = next().text + " with"
                next()
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
            t.isKw("not") -> {
                next()
                val r = peek()
                when {
                    r.isKw("empty") -> { next(); IsExpr(subject, "notEmpty") }
                    // `x is not Shape` is the spelling of non-membership; it is the same
                    // predicate as the general negation `not (x is Shape)`, so it parses to the same tree.
                    r.type == TokType.UIDENT -> { next(); NotExpr(IsExpr(subject, "refinement", r.text)) }
                    else -> fail("expected 'empty' or RefinementName after 'is not'", r)
                }
            }
            t.type == TokType.UIDENT -> { next(); IsExpr(subject, "refinement", t.text) }
            else -> fail("expected none/some/empty/'not empty'/RefinementName/'not' RefinementName after 'is'", t)
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
        while (at(TokType.STAR) || at(TokType.SLASH) || at(TokType.PERCENT)) {
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
                // duration units are contextual: only a unit word directly after an
                // integer literal reads as one (Tokens.kt, DURATION_UNITS)
                val unit = peek()
                if (unit.type == TokType.LIDENT && unit.text in DURATION_UNITS) {
                    next(); DurationLit(t.text.toLong(), unit.text)
                } else IntLit(t.text.toLong())
            }
            t.type == TokType.DEC -> { next(); DecLit(t.text) }
            t.type == TokType.TEXT -> { next(); TextLit(t.text) }
            t.isKw("true") -> { next(); BoolLit(true) }
            t.isKw("false") -> { next(); BoolLit(false) }
            t.isKw("none") -> { next(); NoneLit }
            t.isKw("empty") -> { next(); EmptyLit }
            t.isKw("now") -> { next(); NowLit }
            t.isKw("today") -> { next(); TodayLit }
            t.isKw("this") -> { next(); PathExpr("this") }
            t.type == TokType.LIDENT -> parseNameOrCall()
            t.type == TokType.UIDENT -> { next(); PathExpr(t.text) }
            t.type == TokType.LPAREN -> parseParenPrimary()
            t.type == TokType.LBRACK -> parseListLit()
            else -> fail("expected an expression", t)
        }
    }

    /** `[a, b, c]` — the listed values on the right of `in` (Reference §10). */
    private fun parseListLit(): Expr {
        expect(TokType.LBRACK)
        skipNewlines()
        val items = mutableListOf<Expr>()
        if (!at(TokType.RBRACK)) {
            items.add(parseValue())
            skipNewlines()
            while (at(TokType.COMMA)) {
                next(); skipNewlines()
                items.add(parseValue())
                skipNewlines()
            }
        }
        expect(TokType.RBRACK)
        return ListLit(items)
    }

    private fun parseNameOrCall(): Expr {
        val name = expect(TokType.LIDENT).text
        if (!at(TokType.LPAREN)) return PathExpr(name)
        next() // consume '('
        val result = when (name) {
            "count" -> AggCall(name, parseCollectionExpr())
            "latest", "first" -> {
                // selectors carry their ordering as an explicit author statement —
                // `by` is contextual (an identifier elsewhere), required here
                val coll = parseCollectionExpr()
                if (!(at(TokType.LIDENT) && peek().text == "by"))
                    fail("$name(...) requires 'by <property>' — the ordering datum is the author's statement, never implicit (Reference §10)", peek())
                next() // consume 'by'
                val order = mutableListOf(expect(TokType.LIDENT).text)
                while (at(TokType.COMMA)) { next(); order.add(expect(TokType.LIDENT).text) }
                AggCall(name, coll, orderBy = order)
            }
            "sum" -> {
                // sum's collection is the single-binding form; its second argument
                // is the summed field (grammar.md, aggregateCall) — absent when the
                // collection is a `many` of values summed directly (Reference §5)
                val coll = parseCollectionExpr(singleBinding = true)
                if (at(TokType.COMMA)) { next(); AggCall(name, coll, expect(TokType.LIDENT).text) }
                else AggCall(name, coll)
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
        // set denotation: `(Shape where pred)` / `(this.invoices where Overdue)` (Reference §6)
        if (peek().isKw("where")) {
            val p = inner as? PathExpr
                ?: fail("a set denotation filters a shape name or relationship path", peek())
            next(); skipNewlines()
            val where = parsePredicate()
            expect(TokType.RPAREN)
            return SetExpr(CollectionExpr(listOf(Binding(p)), where))
        }
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

/** The negation of a two-valued predicate, pushed to the atoms: comparisons flip,
 *  `and`/`or` swap, membership and its negation exchange, anything else is wrapped.
 *  The forbidden configuration is spelled with `is`, whatever the requirement wrote.
 *  Shared by the parser (`requires`) and the model (the `by <Refinement>` gate an
 *  act's header desugars to, Reference §22). */
internal fun complementOf(e: Expr): Expr = when {
    e is NotExpr -> asIsExpr(e.inner)
    e is IsExpr && e.kind == "refinement" -> NotExpr(e.copy(toBe = false))
    e is Binary && e.op == "and" -> Binary("or", complementOf(e.left), complementOf(e.right))
    e is Binary && e.op == "or" -> Binary("and", complementOf(e.left), complementOf(e.right))
    e is Binary && e.op in INVERSE_OP -> Binary(INVERSE_OP.getValue(e.op), e.left, e.right)
    e is IsExpr && e.kind == "none" -> IsExpr(e.subject, "some")
    e is IsExpr && e.kind == "some" -> IsExpr(e.subject, "none")
    e is IsExpr && e.kind == "empty" -> IsExpr(e.subject, "notEmpty")
    e is IsExpr && e.kind == "notEmpty" -> IsExpr(e.subject, "empty")
    else -> NotExpr(e)
}

private fun asIsExpr(e: Expr): Expr = if (e is IsExpr) e.copy(toBe = false) else e

private val INVERSE_OP = mapOf("==" to "!=", "!=" to "==", "<" to ">=", ">=" to "<", ">" to "<=", "<=" to ">")
