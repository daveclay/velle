package velle

/**
 * The v0 validator: the coarse, fail-closed slices of checks.md.
 *
 * Where checks.md calls for a prover (predicate disjointness, never-induction,
 * confluence), v0 proves the syntactic cases and fails closed on the rest —
 * calibration against realistic specs is OQ16's deferred work. Not yet
 * implemented (tracked in checks.md): V11 branch-sensitive narrowing, V12
 * at-most-one proofs beyond the refinement slice (base-shape to-one-inverse
 * proofs stay runtime-enforced), and the A-series beyond A4/A5
 * (drift-exposed partitions and contention — advisory, via Validator.advisories).
 */
class Validator(private val model: Model) {

    private val diags = model.diagnostics

    companion object {
        private fun all(decls: List<Decl>): List<Diagnostic> {
            val model = Model(decls)
            Validator(model).run()
            return model.diagnostics.distinct()
        }

        /** The required, fail-closed diagnostics (checks.md F/V series). */
        fun validate(decls: List<Decl>): List<Diagnostic> = all(decls).filterNot { it.advisory }

        fun validate(source: String): List<Diagnostic> = validate(Parser.parse(source))

        /** The advisory findings (checks.md A-series) — guidance, never blocking. */
        fun advisories(source: String): List<Diagnostic> = all(Parser.parse(source)).filter { it.advisory }
    }

    fun run() {
        checkShapeDeclarations()
        checkRelationshipCoherence() // V19
        checkRefinementDeclarations()
        model.rules.values.forEach { checkRuleNamesAndBody(it) }
        checkOneWriter()          // V1 (+ same-commit read-write, V15 coarse)
        model.rules.values.forEach { checkBoundaryAndDisarm(it) }  // V2, V4, V7
        model.rules.values.forEach { checkReachability(it) }       // V3
        checkFreezes()            // V5
        checkCaptures()           // V6
        checkFolds()              // V8
        checkNevers()             // V10
        checkWellFoundedness()    // V14 (stratify, then certify)
        checkTransitionInterference() // V15 (third leg)
        checkQuiescence()         // V16
        checkSingularProofs()     // V12 (refinement slice)
        checkTransients()         // V17 isolation, V18 totality
        checkClosures()           // V22 legality + V1's closure self-pair extension
        checkDeleteStatements()   // V23 (OQ37: statement legality, one deleter, write-and-delete)
        checkReferentialCompleteness() // V24 (OQ37: cascade / absorb / copy)
        checkExistenceDependency()     // V25 (OQ37: guard witnesses, singularity proofs)
        checkNeverDeleters()           // V26 (OQ37: deleters join `never` induction)
        checkUndeletable()             // V27 (OQ37: the state-scoped deletion gate)
        checkDeleteStranding()         // V28 (OQ37: the V17 mirror at deletion)
        checkDriftExposedPartitions() // A4 (advisory)
        checkContention()             // A5 (advisory, OQ40)
        checkDeadOptionality()        // A2 (advisory: `? initially required` nothing can absent)
    }

    // ── V22: closure declaration legality + V1 closure extension (README §6,
    // "Inline part creation") ─────────────────────────────────────────────────
    //
    // Edge resolution (and its V22 diagnostics) lives in Model.closures — forced
    // here. What this check adds is V1's closure self-pair: a closure commit
    // makes N entrants of a part shape, so a rule triggered by it coincides with
    // itself across sibling firings, and a body assigning through the language-
    // populated back-reference provably converges on one container instance —
    // a write-write conflict, refused totally (value-equal RHS included).

    private fun checkClosures() {
        val parts = mutableListOf<ClosureEdge>()
        fun collect(e: ClosureEdge) { parts.add(e); e.children.forEach { collect(it) } }
        model.closures.values.flatten().forEach { collect(it) }
        if (parts.isEmpty()) return

        for (rule in model.rules.values) {
            val base = model.baseOfExpr(rule.condition) ?: continue
            for (edge in parts.filter { it.partShape == base }) {
                for (item in rule.body.filterIsInstance<Assignment>()) {
                    val p = item.target
                    val throughBackRef =
                        (p.root == edge.backRef && p.segs.isNotEmpty()) ||
                            (p.root == "this" && p.segs.size >= 2 && p.segs[0].name == edge.backRef)
                    if (throughBackRef)
                        diags.add(Diagnostic("V1", "'${rule.name}' when ${base} assigns through " +
                            "'${edge.backRef}' — at a closure commit this rule fires once per inline " +
                            "'${base}', every firing writing the same field of the same container: a " +
                            "proven write-write conflict. Derive the aggregate on the container, or " +
                            "move the condition to the container for once-per-act granularity (README §6)"))
                }
            }
        }
    }

    // ── F1/F2/F3: declarations ───────────────────────────────────────────────

    private fun checkShapeDeclarations() {
        for ((name, shape) in model.shapes) {
            for (m in shape.members) when (m) {
                is StoredProp -> {
                    if (m.name == "id") diags.add(Diagnostic("F3", "'$name.id' — 'id' is reserved (README §5)"))
                    val t = m.type
                    if (t is RelType && t.shape !in model.shapes)
                        diags.add(Diagnostic("F1", "'$name.${m.name}' relates to unknown shape '${t.shape}'"))
                    if (t is RelType && t.shape in model.refinements)
                        diags.add(Diagnostic("F2", "'$name.${m.name}' — relationships target base shapes, not refinements"))
                    m.initially?.let { checkExpr(it, name, allowGenerator = true) }
                    // `initially required` demands an optional type: the whole point of
                    // the decomposition is read-side optionality with creation-side
                    // requiredness (OQ37-R10); a plain field is already both
                    if (m.initiallyRequired &&
                        (t as? RelType)?.optional != true && (t as? ScalarType)?.optional != true)
                        diags.add(Diagnostic("F3", "'$name.${m.name}' — 'initially required' needs an " +
                            "optional type ('?'): a plain field is already required at creation and can " +
                            "never be absent (OQ37)"))
                    if (m.tolerates != null && m.tolerates !in setOf("duplication", "reordering"))
                        diags.add(Diagnostic("F3", "'$name.${m.name}' — fields tolerate 'duplication' or 'reordering', not '${m.tolerates}'"))
                }
                is DerivedProp -> checkExpr(m.expr, name)
                else -> {}
            }
        }
    }

    // ── V19: relationship declaration coherence (README §6) ──────────────────
    //
    // The declared side owns a relationship; the inverse is inferred. A bare
    // declared `many` *means* an owned m2m edge set, so declaring both sides of
    // one relationship — a `many B` on A while B declares any side back at A —
    // is either two distinct relationships or two owners of one edge set, and
    // the compiler refuses to guess. (Ambiguous inference — two same-target
    // declared sides on one shape — is handled by not inferring the inverse;
    // use sites then demand declared derived views, README §6's Transfer case.)

    private fun checkRelationshipCoherence() {
        for ((aName, a) in model.shapes) {
            for (f in a.members.filterIsInstance<StoredProp>()) {
                val t = f.type as? RelType ?: continue
                if (!t.many) continue
                val b = model.shapes[t.shape] ?: continue
                for (g in b.members.filterIsInstance<StoredProp>()) {
                    val bt = g.type as? RelType ?: continue
                    if (bt.shape != aName) continue
                    if (aName == t.shape && f === g) continue // a self-referential edge set is one side
                    if (bt.many && aName > t.shape) continue  // symmetric case: report once
                    diags.add(Diagnostic("V19", "'$aName.${f.name}: many ${t.shape}' and " +
                        "'${t.shape}.${g.name}: ${if (bt.many) "many" else "one"} $aName' declare both sides — " +
                        "two owners of one edge set, or two distinct relationships; drop one side " +
                        "(the inverse is inferred), or make the collection a derived view " +
                        "(`${f.name}: many ${t.shape} = (...)`), README §6"))
                }
            }
        }
    }

    private fun checkRefinementDeclarations() {
        for ((name, r) in model.refinements) {
            model.baseOfExpr(r.expr) ?: continue
            walkRefExpr(r.expr)
            for (m in r.members) when (m) {
                is DerivedProp -> checkExpr(m.expr, name)
                is FrozenClause -> {
                    val base = model.baseOf(name) ?: continue
                    val stored = model.shapes[base]?.members?.filterIsInstance<StoredProp>()?.map { it.name } ?: emptyList()
                    m.fields.filter { it !in stored }.forEach {
                        diags.add(Diagnostic("F3", "'$name' freezes '$it', which is not a stored field of '$base'"))
                    }
                }
                else -> {}
            }
        }
        model.nevers.forEach { walkRefExpr(it.target) }
    }

    private fun walkRefExpr(e: RefExpr) {
        when (e) {
            is RefName -> {
                val base = model.baseOf(e.name)
                e.where?.let { if (base != null) checkExpr(it, e.name) }
            }
            is RefNot -> walkRefExpr(e.inner)
            is RefAnd -> { walkRefExpr(e.left); walkRefExpr(e.right) }
            is RefOr -> { walkRefExpr(e.left); walkRefExpr(e.right) }
        }
    }

    // ── F1/F2/F4/V13: expression and body validation ─────────────────────────

    private fun subjectScope(rule: RuleDecl): String? = when (val c = rule.condition) {
        is RefName ->
            if (c.name in model.shapes || c.name in model.refinements) c.name
            else { diags.add(Diagnostic("F1", "rule '${rule.name}' — unknown condition '${c.name}'")); null }
        else -> model.baseOfExpr(rule.condition)
    }

    private fun checkRuleNamesAndBody(rule: RuleDecl) {
        val scope = subjectScope(rule) ?: return
        (rule.condition as? RefName)?.where?.let { checkExpr(it, scope) }
        for (item in rule.body) when (item) {
            is Assignment -> checkAssignment(rule, scope, item)
            is Creation -> checkCreation(rule, scope, item)
            is DeleteStmt -> {} // legality is V23's (checkDeleteStatements)
            ThenMarker -> {}
        }
    }

    private fun checkAssignment(rule: RuleDecl, scope: String, a: Assignment) {
        val resolved = resolveWrite(rule, scope, a) ?: return
        if (!resolved.member.stored) {
            // V20: an inferred inverse or derived collection is a view, never a
            // target — you may only assign what is stored (README §6)
            if (resolved.member.inverse != null || resolved.member.type is VType.Coll) {
                diags.add(Diagnostic("V20", "rule '${rule.name}' assigns ${resolved.owner}.${resolved.member.name} — " +
                    "a collection view is never an assignment target: assign the declared side " +
                    "(whole-set replacement) or write through it (fan-out), README §6"))
            } else {
                val kind = when {
                    resolved.member.timestamp -> "a timestamp field (commit metadata)"
                    resolved.member.name == "id" -> "'id'"
                    resolved.member.captured -> "a captured property"
                    else -> "a derived property"
                }
                diags.add(Diagnostic("F3", "rule '${rule.name}' assigns ${resolved.owner}.${resolved.member.name} — $kind is never assignable"))
            }
        }
        checkExpr(a.value, scope)
    }

    private fun checkCreation(rule: RuleDecl, scope: String, c: Creation) {
        val shape = model.shapes[c.shape]
        if (shape == null) {
            diags.add(Diagnostic("F1", "rule '${rule.name}' creates unknown shape '${c.shape}'"))
            return
        }
        c.forExpr?.let { forE ->
            checkExpr(forE, scope)
            checkForTypeMatch(c.shape, forE, scope, "rule '${rule.name}'")
        }
        val members = model.membersOf(c.shape)
        val provided = c.fields.map { it.name }.toSet()
        for (f in c.fields) {
            val m = members[f.name]
            when {
                m == null -> diags.add(Diagnostic("F1", "rule '${rule.name}' — '${c.shape}' has no field '${f.name}'"))
                m.timestamp -> diags.add(Diagnostic("F3", "rule '${rule.name}' supplies '${c.shape}.${f.name}' — timestamp fields are commit metadata"))
                !m.stored -> diags.add(Diagnostic("F3", "rule '${rule.name}' supplies derived '${c.shape}.${f.name}'"))
            }
            checkExpr(f.value, scope)
        }
        // F4 totality: every stored field without an initializer must be supplied
        // (the `for` form's type-matched field counts as supplied)
        val required = shape.members.filterIsInstance<StoredProp>()
            .filter { it.initially == null }.map { it.name }
        val forCovered = if (c.forExpr != null) typeMatchedFields(c.shape, c.forExpr, scope) else emptyList()
        (required - provided - forCovered.toSet()).forEach {
            diags.add(Diagnostic("F4", "rule '${rule.name}' — '${c.shape}.$it' has no value (from-mapping is totality-checked)"))
        }
    }

    /** V13: `for <expr>` sugar needs exactly one type-matched field. */
    private fun checkForTypeMatch(shape: String, forExpr: Expr, scope: String, where: String) {
        val matches = typeMatchedFields(shape, forExpr, scope)
        if (matches.size != 1)
            diags.add(Diagnostic("V13", "$where — 'for' matches ${matches.size} fields of '$shape' " +
                "(needs exactly one; name the field or use the where-filtered form)"))
    }

    private fun typeMatchedFields(shape: String, expr: Expr, scope: String): List<String> {
        val exprShape = exprInstanceShape(expr, scope) ?: return emptyList()
        // a refinement's matchable to-one fields are its base's stored fields
        return model.shapes[model.baseOf(shape) ?: shape]?.members?.filterIsInstance<StoredProp>()
            ?.filter { (it.type as? RelType)?.let { t -> !t.many && t.shape == exprShape } == true }
            ?.map { it.name } ?: emptyList()
    }

    private fun exprInstanceShape(e: Expr, scope: String): String? = when (e) {
        is PathExpr -> {
            if (e.root == "this" && e.segs.isEmpty()) model.baseOf(scope)
            else model.pathElementShape(e, scope)
        }
        else -> null
    }

    /** Name-resolution walk shared by predicates and values (F1, F2 closed list). */
    private fun checkExpr(e: Expr, scope: String, allowGenerator: Boolean = false) {
        when (e) {
            is PathExpr -> {
                if (allowGenerator && e.root == "randomUUID" && e.segs.isEmpty()) return
                resolvePath(e, scope)
            }
            is UnaryMinus -> checkExpr(e.inner, scope)
            is Binary -> { checkExpr(e.left, scope); checkExpr(e.right, scope) }
            is NotExpr -> checkExpr(e.inner, scope)
            is IfExpr -> { checkExpr(e.condition, scope); checkExpr(e.thenExpr, scope); checkExpr(e.elseExpr, scope) }
            is IsExpr -> {
                checkExpr(e.subject, scope)
                e.refinement?.let { model.baseOf(it) }
            }
            is ExistsExpr -> {
                e.forExpr?.let { checkExpr(it, scope) }
                e.shape?.let { s ->
                    if (s !in model.shapes && s !in model.refinements)
                        diags.add(Diagnostic("F1", "exists names unknown shape '$s'"))
                    else e.forExpr?.let { checkForTypeMatch(s, it, scope, "exists $s for ...") }
                }
                e.collection?.let { checkCollection(it, scope) }
            }
            is AggCall -> checkCollection(
                e.collection, scope, sumField = e.field,
                orderBy = e.orderBy.takeIf { e.name == "latest" || e.name == "first" }
            )
            is FunCall -> {
                if (e.name !in setOf("lowercase", "max", "min"))
                    diags.add(Diagnostic("F2", "unknown function '${e.name}' — the builtin list is closed (README §5)"))
                e.args.forEach { checkExpr(it, scope) }
            }
            is SingularFor -> {
                checkExpr(e.forExpr, scope)
                if (e.shape !in model.shapes && e.shape !in model.refinements)
                    diags.add(Diagnostic("F1", "unknown shape '${e.shape}'"))
            }
            is Access -> checkExpr(e.target, scope)
            is ShapeForSource -> checkExpr(e.forExpr, scope)
            is SetExpr -> checkCollection(e.collection, scope)
            else -> {}
        }
    }

    private fun checkCollection(
        c: CollectionExpr,
        scope: String,
        sumField: String? = null,
        orderBy: List<String>? = null,
    ) {
        var element = scope
        val introduced = mutableListOf<String>()
        for (b in c.bindings) {
            when (val src = b.source) {
                is PathExpr ->
                    if (src.root in model.shapes || src.root in model.refinements) element = src.root
                    else {
                        resolvePath(src, scope)
                        model.pathElementShape(src, scope)?.let { element = it }
                    }
                is ShapeForSource -> { checkExpr(src.forExpr, scope); element = src.shape }
                else -> checkExpr(src, scope)
            }
            // an alias names this binding's element, visible to the shared `where`
            // and anything nested inside it (README §10, `as`)
            b.alias?.let { aliasScopes[it] = element; introduced.add(it) }
        }
        c.where?.let { checkExpr(it, element) }
        sumField?.let {
            if (model.membersOf(element)[it] == null)
                diags.add(Diagnostic("F1", "sum(..., $it) — '$element' has no member '$it'"))
        }
        // selectors' `by` datums: each must be an orderable member of the
        // element — the author's ordering statement, typechecked (F2, README §10)
        orderBy?.forEach { d ->
            val m = model.membersOf(element)[d]
            if (m == null)
                diags.add(Diagnostic("F2", "selector 'by $d' — '$element' has no member '$d'"))
            else {
                val t = (m.type as? VType.Optional)?.inner ?: m.type
                val orderable = t is VType.Num || t == VType.Text || t == VType.DateT || t == VType.DateTimeT
                if (!orderable)
                    diags.add(Diagnostic("F2", "selector 'by $d' — $element.$d is not orderable (README §10)"))
            }
        }
        introduced.forEach { aliasScopes.remove(it) }
    }

    /** `as`-alias → element scope, live while the binding's `where` is checked. */
    private val aliasScopes = mutableMapOf<String, String>()

    private fun resolvePath(p: PathExpr, scope: String) {
        var current: String? = when {
            p.root == "this" -> scope
            p.root in aliasScopes -> aliasScopes[p.root]
            p.root in model.shapes || p.root in model.refinements -> return // membership atom / collection source
            else -> {
                val m = model.membersOf(scope)[p.root]
                if (m == null) {
                    diags.add(Diagnostic("F1", "'${p.root}' is not a member of '$scope' " +
                        "(bare names resolve in the innermost scope only — use this.<field> for the subject)"))
                    return
                }
                m.type.instanceShape() ?: (m.type as? VType.Coll)?.shape
            }
        }
        for (seg in p.segs) {
            val sc = current ?: return
            val m = model.membersOf(sc)[seg.name]
            if (m == null) {
                diags.add(Diagnostic("F1", "'${seg.name}' is not a member of '$sc'"))
                return
            }
            current = m.type.instanceShape() ?: (m.type as? VType.Coll)?.shape
        }
    }

    // ── writes ───────────────────────────────────────────────────────────────

    private data class Write(val rule: RuleDecl, val owner: String, val member: MemberInfo, val target: PathExpr,
                             val value: Expr,
                             /** the target traverses a `many` hop: one write per member (README §6);
                              *  one-writer treats it as a write to the field on every instance (V1, coarse) */
                             val fanOut: Boolean = false)

    private fun resolveWrite(rule: RuleDecl, scope: String, a: Assignment): Write? {
        var fanOut = false
        fun stepThrough(m: MemberInfo, sc: String, atPenultimate: Boolean): String? {
            val coll = m.type as? VType.Coll
            if (coll != null) {
                // V20: a collection path fans out exactly at the penultimate hop —
                // it writes a field *of* the members, never through them (README §6)
                if (!atPenultimate) {
                    diags.add(Diagnostic("V20", "rule '${rule.name}' assigns through '$sc.${m.name}' and onward — " +
                        "a collection path writes a field of the collection's members, never through them; " +
                        "the deeper write is its own rule on the shape that owns the field (README §6)"))
                    return null
                }
                if (fanOut) {
                    diags.add(Diagnostic("V20", "rule '${rule.name}' assigns through two `many` hops — " +
                        "a fan-out traverses exactly one (README §6)"))
                    return null
                }
                fanOut = true
                return coll.shape
            }
            if (m.type is VType.CollS) {
                diags.add(Diagnostic("V20", "rule '${rule.name}' assigns through scalar collection '$sc.${m.name}'"))
                return null
            }
            return m.type.instanceShape()
        }
        var current: String? = if (a.target.root == "this") scope else {
            val m = model.membersOf(scope)[a.target.root]
            if (m == null) {
                diags.add(Diagnostic("F1", "rule '${rule.name}' assigns through unknown member '${a.target.root}'"))
                return null
            }
            if (a.target.segs.isEmpty())
                return Write(rule, model.baseOf(scope) ?: scope, m, a.target, a.value)
            stepThrough(m, model.baseOf(scope) ?: scope, atPenultimate = a.target.segs.size == 1)
        }
        for ((i, seg) in a.target.segs.withIndex()) {
            val sc = current ?: return null
            val m = model.membersOf(sc)[seg.name]
            if (m == null) {
                diags.add(Diagnostic("F1", "rule '${rule.name}' assigns through unknown member '${seg.name}' of '$sc'"))
                return null
            }
            if (i == a.target.segs.lastIndex) return Write(rule, model.baseOf(sc) ?: sc, m, a.target, a.value, fanOut)
            current = stepThrough(m, sc, atPenultimate = i == a.target.segs.lastIndex - 1)
        }
        return null
    }

    private val writes: List<Write> by lazy {
        model.rules.values.flatMap { rule ->
            val scope = subjectScope(rule) ?: return@flatMap emptyList()
            rule.body.filterIsInstance<Assignment>().mapNotNull { resolveWrite(rule, scope, it) }
        }
    }

    // ── V1 (+ coarse V15): one writer per field per commit ───────────────────

    private fun checkOneWriter() {
        val byField = writes.filter { it.member.stored }.groupBy { it.owner to it.member.name }
        for ((field, ws) in byField) {
            for (i in ws.indices) for (j in i + 1 until ws.size) {
                val (a, b) = ws[i] to ws[j]
                if (a.rule === b.rule) {
                    diags.add(Diagnostic("V1", "rule '${a.rule.name}' assigns ${field.first}.${field.second} twice in one body"))
                    continue
                }
                if (!provablyDisjoint(a.rule, b.rule) && !aliasDischarged(a, b))
                    diags.add(Diagnostic("V1", "rules '${a.rule.name}' and '${b.rule.name}' both assign " +
                        "${field.first}.${field.second} and their triggers are not provably disjoint"))
            }
        }
        checkSiblingReadWrite()
    }

    // ── coarse V15, read-write legs (sibling-confluence audit, channels A–D, F) ─
    //
    // On-commit siblings: subjects are pinned from one pre/post diff before
    // any sibling runs (evaluation.md step 5), so a condition-only read of a
    // sibling's write is safe — the flip it causes fires rules at its own
    // commit, which is causality, not a race (probe H). What diverges is the
    // BODY: bodies read the evolving state, so a body read of anything a
    // sibling writes — a field, a shape's instance set (existence, aggregate),
    // a timestamp any write advances — depends on the unstated order.
    //
    // After-commit followers of one commit are independent transactions in
    // unspecified relative order (the queue's append order was itself a
    // step-6 choice), and their conditions RE-CHECK at drain — so for
    // after/after pairs, condition reads matter too (full summary).

    private fun checkSiblingReadWrite() {
        val commitRules = model.rules.values.filter { it.preposition != "after" && firesOnCommit(it) }
        for (a in commitRules) for (b in commitRules) {
            if (a === b || provablyDisjoint(a, b) || !canCoFire(a, b) || enterLeaveExclusive(a, b)) continue
            for (what in readConflicts(a, bodyReads(b)))
                diags.add(Diagnostic("V15", "'${a.name}' writes $what, which sibling '${b.name}' reads in its body — " +
                    "outcome depends on unstated order; state the intent (OQ16)"))
        }
        val afterRules = model.rules.values.filter { it.preposition == "after" }
        for (a in afterRules) for (b in afterRules) {
            if (a === b || provablyDisjoint(a, b) || !canCoFire(a, b) || enterLeaveExclusive(a, b)) continue
            for (what in readConflicts(a, ruleReads(b).reads))
                diags.add(Diagnostic("V15", "'${a.name}' and '${b.name}' follow the same commit as independent " +
                    "after-commit transactions in unspecified relative order, and '${b.name}' reads $what, " +
                    "which '${a.name}' writes — outcome depends on that order; state the intent (OQ16)"))
        }
    }

    // ── can two rules fire from one commit? ──────────────────────────────────
    //
    // Sharper than [canShareCommit] ("some commit kind affects both
    // conditions"): a rule gains a subject at a commit only when the commit
    // can flip its condition in the firing direction — TOWARD membership for
    // an entry rule, AWAY for a leaving rule. A creation moves a predicate
    // only in the polarity the predicate consults the shape at (creating a
    // `StockReservation` can only satisfy `exists StockReservation for this`
    // and only falsify `not exists StockReservation for this`), a field write
    // is value-dependent (either direction), a freshly created base instance
    // can never LEAVE anything (it was a member of nothing), and a fresh
    // instance cannot satisfy a positive `exists ... for this` over a shape
    // the same commit does not create (nothing can reference it yet).

    private fun canCoFire(a: RuleDecl, b: RuleDecl): Boolean =
        commitKinds().any { k -> canGainSubject(k, a) && canGainSubject(k, b) }

    private fun canGainSubject(k: CommitKind, rule: RuleDecl): Boolean {
        val base = model.baseOfExpr(rule.condition) ?: return false
        if (base in model.transients)
            return k is CommitKind.Creates && k.shape == base && !rule.leaving
        val cond = conditionSummary(rule)
        if (cond.opaque) return true
        return when (k) {
            is CommitKind.Creates -> {
                val coCreated = k.byRule?.body?.filterIsInstance<Creation>()?.map { it.shape }?.toSet()
                    ?: setOf(k.shape) // an exposed commit carries exactly the act
                if (k.shape == base) {
                    if (rule.leaving) return false // a fresh instance was never a member
                    return positiveCorrelatedExistsTargets(conditionConjuncts(rule.condition))
                        .none { it !in coCreated }
                }
                // a creation flips the condition only through a consult a
                // fresh instance can satisfy, and only in that consult's polarity
                val pol = conditionPolarities(rule).map
                pol.keys.filter { model.baseOf(it) == k.shape }
                    .filter { it in model.shapes || !freshCannotEnter(it, coCreated) }
                    .any { n -> pol.getValue(n).let { p -> if (rule.leaving) p <= 0 else p >= 0 } }
            }
            is CommitKind.Assigns -> affects(k, rule) // value-dependent: either direction
            is CommitKind.Deletes -> {
                // creation's mirror, signs flipped: a deletion can only unsatisfy a
                // consult, so it moves a predicate at the consult's NEGATED polarity;
                // the deleted instance itself can only LEAVE; and a read through a
                // reference at the deleted shape flips value-dependently (either
                // direction — `is none` becomes true, `is some` false)
                if (k.shape == base && rule.leaving) true
                else {
                    val pol = conditionPolarities(rule).map
                    pol.keys.filter { model.baseOf(it) == k.shape }
                        .any { n -> pol.getValue(n).let { p -> if (rule.leaving) p >= 0 else p <= 0 } } ||
                        conditionReadsRefTo(rule, k.shape)
                }
            }
        }
    }

    // ── entrant/leaver exclusivity ───────────────────────────────────────────
    //
    // The episodes discharge: [a] fires on ENTRY into a condition whose
    // conjuncts include every conjunct of the condition [b] LEAVES. One
    // instance cannot do both at one commit — a's post-state makes the shared
    // conjuncts true, b's makes them false. Different instances at one commit
    // are ruled out when every commit kind that could fire both is a write to
    // the shared base's own columns, read only through the instance's own
    // fields (no exists or aggregate over the base, no relationship hop back
    // onto it) — then the only instance whose membership the write can flip
    // is the written one, on both sides.

    private fun enterLeaveExclusive(a: RuleDecl, b: RuleDecl): Boolean {
        val (enter, leave) = when {
            !a.leaving && b.leaving -> a to b
            a.leaving && !b.leaving -> b to a
            else -> return false
        }
        val base = model.baseOfExpr(enter.condition) ?: return false
        if (base != model.baseOfExpr(leave.condition)) return false
        val ce = conditionConjuncts(enter.condition)
        val cl = conditionConjuncts(leave.condition)
        if (cl.isEmpty() || !ce.containsAll(cl)) return false
        val shared = commitKinds().filter { canGainSubject(it, enter) && canGainSubject(it, leave) }
        return shared.all { k ->
            k is CommitKind.Assigns && k.shape == base &&
                ownColumnOnly(cl, base, k.field) &&
                (ce - cl.toSet()).none { conj -> conjunctReads(conj, base, k.field) }
        }
    }

    /** Every conjunct reading ([base], [field]) does so through the subject's
     *  own columns only: seg-free or `this`-rooted single-segment paths. */
    private fun ownColumnOnly(conjuncts: List<Expr>, base: String, field: String): Boolean =
        conjuncts.all { conj ->
            if (!conjunctReads(conj, base, field)) return@all true
            var own = true
            fun walk(e: Expr?) {
                when (e) {
                    null -> {}
                    is PathExpr -> if (!(e.segs.isEmpty() || (e.root == "this" && e.segs.size == 1))) own = false
                    is UnaryMinus -> walk(e.inner)
                    is Binary -> { walk(e.left); walk(e.right) }
                    is NotExpr -> walk(e.inner)
                    is IfExpr -> { walk(e.condition); walk(e.thenExpr); walk(e.elseExpr) }
                    is IsExpr -> walk(e.subject)
                    is FunCall -> e.args.forEach { walk(it) }
                    // exists/aggregates/selectors reach other instances
                    is ExistsExpr, is AggCall, is SingularFor, is ShapeForSource, is SetExpr, is Access -> own = false
                    else -> {} // literals
                }
            }
            walk(conj)
            own
        }

    private fun conjunctReads(conj: Expr, base: String, field: String): Boolean {
        val s = ReadSummary()
        model.collectExpr(conj, base, s)
        return s.opaque || (base to field) in s.fields || (base to field) in s.collFields
    }

    // ── signed consults: which way can a creation move this condition? ──────
    //
    // +1: creating an instance of the shape can only move the predicate
    // toward true; -1: only toward false; 0: both directions (or unknown).
    // Signs come from `exists` polarity under negation, refinement absorption,
    // and count comparisons; everything else (sums, selectors, branches)
    // stays 0 — coarse, and only ever coarser than the truth.

    /** Creation-sensitive consults of a predicate, RAW-named and signed. A
     *  name appears only where creating an instance of its base can move the
     *  predicate: quantified consults (`exists`, collection sources and their
     *  refinement-atom filters, selectors). Membership tests on a *reference*
     *  (`order is ReadyToShip`, refinement composition) contribute their
     *  internals but not their own name — a fresh instance is unreferenced,
     *  so creating one never flips a reference-scoped membership. [complete]
     *  false means the walk could not attribute every consult; consumers must
     *  then treat the summary's unattributed entries as moving both ways. */
    private class Polarities(val map: Map<String, Int>, val complete: Boolean)

    private class PolAcc {
        val out = mutableMapOf<String, Int>()
        var incomplete = false
    }

    private fun conditionPolarities(rule: RuleDecl): Polarities =
        polarityCache.getOrPut("rule:${rule.name}") {
            val base = model.baseOfExpr(rule.condition)
            val acc = PolAcc()
            if (base != null) signedRefExpr(rule.condition, base, +1, acc, mutableSetOf())
            val s = conditionSummary(rule)
            if (s.opaque) acc.incomplete = true
            if (acc.incomplete) (s.existsShapes + s.collShapes).forEach { acc.out.putIfAbsent(it, 0) }
            Polarities(acc.out, !acc.incomplete)
        }

    private fun refinementPolarities(name: String): Polarities =
        polarityCache.getOrPut("ref:$name") {
            val acc = PolAcc()
            val r = model.refinements[name]
            val base = model.baseOf(name)
            if (r != null && base != null) {
                signedRefExpr(r.expr, base, +1, acc, mutableSetOf(name))
                val s = model.predicateSummary(name)
                if (s.opaque) acc.incomplete = true
                if (acc.incomplete) (s.existsShapes + s.collShapes).forEach { acc.out.putIfAbsent(it, 0) }
            }
            Polarities(acc.out, !acc.incomplete)
        }

    private val polarityCache = mutableMapOf<String, Polarities>()

    private fun merge(acc: PolAcc, name: String?, sign: Int) {
        val s = name ?: return
        acc.out[s] = if (acc.out.containsKey(s) && acc.out.getValue(s) != sign) 0 else sign
    }

    private fun absorb(acc: PolAcc, name: String, sign: Int, visiting: MutableSet<String>, mergeName: Boolean) {
        if (mergeName) merge(acc, name, sign)
        if (name in model.refinements && visiting.add(name)) {
            val p = refinementPolarities(name)
            if (!p.complete) acc.incomplete = true
            p.map.forEach { (sh, pol) -> merge(acc, sh, if (pol == 0) 0 else pol * sign) }
        }
    }

    private fun signedRefExpr(e: RefExpr, scope: String, sign: Int, acc: PolAcc, visiting: MutableSet<String>) {
        when (e) {
            is RefName -> {
                // composition: the operand's internals, not its name — an
                // instance's membership here never flips by base creation alone
                if (e.name in model.refinements) absorb(acc, e.name, sign, visiting, mergeName = false)
                e.where?.let { signedExpr(it, e.name.takeIf { n -> n in model.shapes || n in model.refinements } ?: scope, sign, acc, visiting) }
            }
            is RefNot -> signedRefExpr(e.inner, scope, -sign, acc, visiting)
            is RefAnd -> { signedRefExpr(e.left, scope, sign, acc, visiting); signedRefExpr(e.right, scope, sign, acc, visiting) }
            is RefOr -> { signedRefExpr(e.left, scope, sign, acc, visiting); signedRefExpr(e.right, scope, sign, acc, visiting) }
        }
    }

    private fun signedExpr(e: Expr?, scope: String, sign: Int, acc: PolAcc, visiting: MutableSet<String>) {
        when (e) {
            null -> {}
            is NotExpr -> signedExpr(e.inner, scope, -sign, acc, visiting)
            is Binary -> when {
                e.op == "and" || e.op == "or" -> { signedExpr(e.left, scope, sign, acc, visiting); signedExpr(e.right, scope, sign, acc, visiting) }
                e.op in setOf("<", "<=", ">", ">=", "==", "!=") -> {
                    val (agg, dir) = when {
                        e.left is AggCall -> e.left as AggCall to (if (e.op == ">" || e.op == ">=") sign else if (e.op == "<" || e.op == "<=") -sign else 0)
                        e.right is AggCall -> e.right as AggCall to (if (e.op == "<" || e.op == "<=") sign else if (e.op == ">" || e.op == ">=") -sign else 0)
                        else -> null to 0
                    }
                    if (agg != null && agg.name == "count") signedCollection(agg.collection, scope, dir, acc, visiting)
                    else { signedExpr(e.left, scope, 0, acc, visiting); signedExpr(e.right, scope, 0, acc, visiting) }
                }
                else -> { signedExpr(e.left, scope, 0, acc, visiting); signedExpr(e.right, scope, 0, acc, visiting) }
            }
            is ExistsExpr -> {
                e.shape?.let { absorb(acc, it, sign, visiting, mergeName = true) } // quantifies
                e.collection?.let { signedCollection(it, scope, sign, acc, visiting) }
            }
            is IsExpr -> {
                // a membership test on a reference or the subject: internals
                // only — a fresh instance is unreferenced and not the subject
                e.refinement?.let { absorb(acc, it, sign, visiting, mergeName = false) }
                signedExpr(e.subject, scope, 0, acc, visiting)
            }
            is PathExpr -> {
                if (e.root in model.refinements && e.segs.isEmpty()) absorb(acc, e.root, sign, visiting, mergeName = false)
                else if (e.root in model.shapes && e.segs.isEmpty()) merge(acc, e.root, sign)
                else followPath(e, scope, acc, visiting)
            }
            is IfExpr -> { signedExpr(e.condition, scope, 0, acc, visiting); signedExpr(e.thenExpr, scope, 0, acc, visiting); signedExpr(e.elseExpr, scope, 0, acc, visiting) }
            is UnaryMinus -> signedExpr(e.inner, scope, 0, acc, visiting)
            is AggCall -> signedCollection(e.collection, scope, 0, acc, visiting)
            is FunCall -> e.args.forEach { signedExpr(it, scope, 0, acc, visiting) }
            is SingularFor -> { absorb(acc, e.shape, 0, visiting, mergeName = true); signedExpr(e.forExpr, scope, 0, acc, visiting) } // selects across instances
            is ShapeForSource -> { absorb(acc, e.shape, 0, visiting, mergeName = true); signedExpr(e.forExpr, scope, 0, acc, visiting) }
            is Access -> signedExpr(e.target, scope, 0, acc, visiting)
            is SetExpr -> signedCollection(e.collection, scope, 0, acc, visiting)
            else -> {}
        }
    }

    /** Member paths hide consults behind derived properties (`netPaid >=
     *  amount` consults `SuccessfulCharge` through `netPaid`'s sum): resolve
     *  the path and walk each derived member's formula, at sign 0 — a consult
     *  reached through a value read moves the predicate both ways. */
    private fun followPath(p: PathExpr, scope: String, acc: PolAcc, visiting: MutableSet<String>) {
        val steps: List<String> = if (p.root == "this") p.segs.map { it.name } else listOf(p.root) + p.segs.map { it.name }
        var sc: String? = scope
        for (name in steps) {
            val m = sc?.let { model.membersOf(it)[name] }
            if (m == null) { acc.incomplete = true; return } // alias or unresolvable: fills cover it
            m.derived?.let { d ->
                if (visiting.add("prop:${m.owner}.${m.name}"))
                    signedExpr(d.expr, m.owner, 0, acc, visiting)
            }
            (m.type as? VType.Coll)?.let { merge(acc, it.shape, 0) }
            sc = m.type.instanceShape() ?: (m.type as? VType.Coll)?.shape
        }
    }

    private fun signedCollection(c: CollectionExpr, scope: String, sign: Int, acc: PolAcc, visiting: MutableSet<String>) {
        var element = scope
        // a `where` that is exactly one refinement-membership atom gates fresh
        // rows behind that refinement: the consult is the refinement (name
        // merged — a fresh element enters through it or not at all), never
        // the collection's bare element shape
        val atomFilter = (c.where as? PathExpr)?.takeIf { it.segs.isEmpty() && it.root in model.refinements }
        for (b in c.bindings) {
            when (val src = b.source) {
                is PathExpr ->
                    if ((src.root in model.shapes || src.root in model.refinements) && src.segs.isEmpty()) {
                        absorb(acc, src.root, sign, visiting, mergeName = true)
                        element = src.root
                    } else {
                        val el = model.pathElementShape(src, scope)
                        if (el == null) acc.incomplete = true
                        else {
                            if (atomFilter == null) merge(acc, el, sign)
                            element = el
                        }
                    }
                is ShapeForSource -> {
                    absorb(acc, src.shape, sign, visiting, mergeName = true)
                    signedExpr(src.forExpr, scope, 0, acc, visiting)
                    element = src.shape
                }
                else -> { acc.incomplete = true; signedExpr(src, scope, 0, acc, visiting) }
            }
        }
        atomFilter?.let { absorb(acc, it.root, sign, visiting, mergeName = true) }
            ?: c.where?.let { signedExpr(it, element, sign, acc, visiting) }
    }

    /** What of [a]'s effects does a summary of [reads] consult? Human-readable
     *  conflict descriptions; empty means provably no read-write conflict. */
    private fun readConflicts(a: RuleDecl, reads: ReadSummary): List<String> {
        val (assigned, created, deleted) = ruleEffects(a)
        if (reads.opaque)
            return if (assigned.isEmpty() && created.isEmpty() && deleted.isEmpty()) emptyList()
            else listOf("state its summary cannot attribute (opaque — treated as reading anything)")
        val out = mutableListOf<String>()
        (assigned intersect reads.fields).forEach { out.add("${it.first}.${it.second}") }
        (assigned intersect reads.collFields).forEach { out.add("${it.first}.${it.second} (a timestamp its write advances)") }
        // a created instance changes an existence/aggregate read only where a
        // fresh instance can actually satisfy what the read consults
        val readNames = reads.existsShapes + reads.collShapes
        for (s in created) {
            val touched = readNames.filter { model.baseOf(it) == s }
                .any { it in model.shapes || !freshCannotEnter(it, created) }
            if (touched) out.add("instances of '$s' (existence or an aggregate)")
        }
        // a deletion changes any existence/aggregate read of the shape, and any
        // read through a reference at it (the absorbing `is none`, OQ37)
        for (s in deleted) {
            val touched = readNames.any { model.baseOf(it) == s } ||
                (reads.fields + reads.collFields).any { (o, f) ->
                    model.membersOf(o)[f]?.type?.instanceShape()?.let { model.baseOf(it) } == s
                }
            if (touched) out.add("instances of '$s' (a delete the read consults)")
        }
        return out
    }

    /** Can a freshly created base instance be a member of [refinement] at its
     *  own creation commit? Not if the predicate carries a positive `exists`
     *  conjunct correlated to the subject over a shape the same commit does
     *  not create — nothing can reference the fresh instance yet (the V12/V18
     *  family's fresh-instance argument, aimed at consult analysis). */
    private fun freshCannotEnter(refinement: String, coCreated: Set<String>): Boolean {
        if (refinement !in model.refinements) return false
        return positiveCorrelatedExistsTargets(conditionConjuncts(RefName(refinement)))
            .any { it !in coCreated }
    }

    /** Targets of positive `exists T for this` / `exists (T where f == this)`
     *  conjuncts — memberships a fresh, not-yet-referencable instance cannot
     *  have. */
    private fun positiveCorrelatedExistsTargets(conjuncts: List<Expr>): List<String> {
        fun comparesToThis(e: Expr): Boolean = when {
            e is Binary && e.op == "and" -> comparesToThis(e.left) || comparesToThis(e.right)
            e is Binary -> e.op == "==" && (e.left == PathExpr("this") || e.right == PathExpr("this"))
            else -> false
        }
        return conjuncts.mapNotNull { c ->
            val ex = c as? ExistsExpr ?: return@mapNotNull null // positive only: `not exists` is a NotExpr
            val correlated = ex.forExpr == PathExpr("this") ||
                ex.collection?.where?.let { comparesToThis(it) } == true
            if (!correlated) return@mapNotNull null
            ex.shape?.let { model.baseOf(it) }
                ?: (ex.collection?.bindings?.firstOrNull()?.source as? PathExpr)
                    ?.takeIf { it.segs.isEmpty() }?.root?.let { model.baseOf(it) }
        }
    }

    /** [a]'s effects: fields assigned — `on update` timestamps those writes
     *  advance included — shapes created, and shapes deleted (OQ37). */
    private fun ruleEffects(a: RuleDecl): Triple<Set<Pair<String, String>>, Set<String>, Set<String>> =
        effectsCache.getOrPut(a.name) {
            val assigned = mutableSetOf<Pair<String, String>>()
            writes.filter { it.rule === a }.forEach { w ->
                assigned.add(w.owner to w.member.name)
                updateTimestamps(w.owner).forEach { assigned.add(w.owner to it) }
            }
            val created = a.body.filterIsInstance<Creation>().map { it.shape }.toSet()
            val deleted = model.deleteSites.filter { it.rule === a }.map { it.shape }.toSet()
            Triple(assigned, created, deleted)
        }

    private val effectsCache = mutableMapOf<String, Triple<Set<Pair<String, String>>, Set<String>, Set<String>>>()

    private fun updateTimestamps(shape: String): Set<String> = updateTsCache.getOrPut(shape) {
        model.shapes[shape]?.members?.filterIsInstance<TimestampProp>()
            ?.filter { it.on == "update" }?.map { it.name }?.toSet() ?: emptySet()
    }

    private val updateTsCache = mutableMapOf<String, Set<String>>()

    /** Reads of a rule's body only — assignment values and their target
     *  routes, creation field values and `for` expressions. Condition reads
     *  are deliberately absent: subjects are pinned per commit (above). */
    private fun bodyReads(rule: RuleDecl): ReadSummary = bodySummaries.getOrPut(rule.name) {
        val s = ReadSummary()
        val scope = subjectScope(rule)
        if (scope != null) {
            rule.body.forEach { item ->
                when (item) {
                    is Assignment -> {
                        model.collectExpr(item.value, scope, s)
                        if (item.target.segs.isNotEmpty())
                            model.collectExpr(PathExpr(item.target.root, item.target.segs.dropLast(1)), scope, s)
                    }
                    is Creation -> {
                        item.forExpr?.let { model.collectExpr(it, scope, s) }
                        item.fields.forEach { model.collectExpr(it.value, scope, s) }
                    }
                    is DeleteStmt -> model.collectExpr(item.target, scope, s) // the route to the target
                    ThenMarker -> {}
                }
            }
        }
        s
    }

    private val bodySummaries = mutableMapOf<String, ReadSummary>()

    // ── spent invariants: `never`s as proof inputs (README §21, checks.md V10) ─
    //
    // Only a fully-discharged invariant is spendable. In v0 that is exactly the
    // input-constrained kind: no rule writes any field its predicate reads, so
    // the invariant is enforced entirely at the boundary (compiled guardrails
    // at every expose site) and holds in every settled state. A rule-maintained
    // `never` would need the inductive proof v0 doesn't attempt (V10 fails
    // closed on it), so it is never spent.

    private val spendableNevers: List<NeverDecl> by lazy {
        val written = writes.map { it.owner to it.member.name }.toSet()
        model.nevers.filter { n ->
            val s = model.summaryOfRefExpr(n.target)
            // deleters join the proof's state changes (OQ37): deleting the never's
            // own base only shrinks the forbidden set, but a deleter of any other
            // consulted shape can flip membership either way — not spendable
            val nBase = model.baseOfExpr(n.target)
            val foreignConsults = s.touchedShapes().mapNotNull { model.baseOf(it) }.toSet() - setOfNotNull(nBase)
            !s.opaque && s.fields.none { it in written } &&
                foreignConsults.none { it in model.deletedShapes }
        }
    }

    /**
     * Does a spendable `never (S where hop == this)` hold — no instance of
     * [shape]'s [hop] relationship ever points back at its own instance? The
     * direct-case acyclicity certificate (checks.md V14) and the aliasing
     * discharge (checks.md V1/V15). The predicate must be exactly the one
     * equality over the whole base shape: a refinement scope or an extra
     * conjunct narrows what the invariant forbids, which weakens it below
     * what the proof needs — fail closed on those.
     */
    private fun neverForbidsSelfReference(shape: String, hop: String): Boolean =
        spendableNevers.any { n ->
            val t = n.target as? RefName ?: return@any false
            if (t.name != shape) return@any false
            val w = t.where as? Binary ?: return@any false
            if (w.op != "==") return@any false
            val self = PathExpr("this")
            val h = PathExpr(hop)
            (w.left == h && w.right == self) || (w.left == self && w.right == h)
        }

    /**
     * The instance-aliasing discharge (OQ16's PromoteBuyer/PromoteReferrer
     * case): two writers of one field whose target routes differ by exactly
     * one to-one self-hop write provably different instances when a spendable
     * `never` forbids the hop pointing back at its own instance — the routes
     * share a prefix, so the two targets coincide exactly when `x.hop == x`,
     * the forbidden configuration.
     */
    private fun aliasDischarged(a: Write, b: Write): Boolean {
        if (a.fanOut || b.fanOut) return false
        if (model.baseOfExpr(a.rule.condition) != model.baseOfExpr(b.rule.condition)) return false
        fun route(w: Write): List<String> = listOf(w.target.root) + w.target.segs.dropLast(1).map { it.name }
        val ra = route(a)
        val rb = route(b)
        val long = when {
            ra.size + 1 == rb.size && rb.subList(0, ra.size) == ra -> rb
            rb.size + 1 == ra.size && ra.subList(0, rb.size) == rb -> ra
            else -> return false
        }
        val hop = long.last()
        val m = model.membersOf(a.owner)[hop] ?: return false
        if (!m.stored || m.type.instanceShape() != a.owner) return false
        return neverForbidsSelfReference(a.owner, hop)
    }

    private fun RuleDecl.rule() = name
    private fun firesOnCommit(r: RuleDecl) =
        r.preposition == null || "commit" in r.triggers

    /** Can one commit fire both rules? Coarse: any commit kind affecting both. */
    private fun canShareCommit(a: RuleDecl, b: RuleDecl): Boolean =
        commitKinds().any { k -> affects(k, a) && affects(k, b) }

    private fun provablyDisjoint(a: RuleDecl, b: RuleDecl): Boolean {
        val baseA = model.baseOfExpr(a.condition)
        val baseB = model.baseOfExpr(b.condition)
        if (baseA == null || baseB == null) return false
        if (baseA != baseB) return !(canShareCommit(a, b))
        val ca = conditionConjuncts(a.condition)
        val cb = conditionConjuncts(b.condition)
        // syntactic complement: one side asserts P, the other `not P`
        return ca.any { p -> cb.any { q -> q == NotExpr(p) || p == NotExpr(q) } }
    }

    private fun conditionConjuncts(e: RefExpr): List<Expr> = when (e) {
        is RefName -> {
            val inherited = model.refinements[e.name]?.let { conditionConjuncts(it.expr) } ?: emptyList()
            inherited + (e.where?.let { predicateConjuncts(it) } ?: emptyList())
        }
        is RefAnd -> conditionConjuncts(e.left) + conditionConjuncts(e.right)
        else -> emptyList()
    }

    private fun predicateConjuncts(p: Expr): List<Expr> =
        if (p is Binary && p.op == "and") predicateConjuncts(p.left) + predicateConjuncts(p.right)
        else listOf(p)

    // ── guards: atoms and disarm (V2), boundary/apparatus (V4), captures (V7) ─

    private sealed interface GuardAtom {
        data class Witness(val shape: String) : GuardAtom
        data class Flag(val name: String) : GuardAtom
    }

    private fun guardAtoms(rule: RuleDecl): List<GuardAtom> =
        conditionConjuncts(rule.condition).mapNotNull { conjunct ->
            val inner = (conjunct as? NotExpr)?.inner ?: return@mapNotNull null
            when {
                inner is ExistsExpr && inner.shape != null ->
                    GuardAtom.Witness(model.baseOf(inner.shape) ?: inner.shape)
                inner is ExistsExpr && inner.collection != null ->
                    (inner.collection.bindings.firstOrNull()?.source as? PathExpr)
                        ?.takeIf { it.root in model.shapes || it.root in model.refinements }
                        ?.let { GuardAtom.Witness(model.baseOf(it.root) ?: it.root) }
                inner is PathExpr && inner.segs.isEmpty() -> GuardAtom.Flag(inner.root)
                else -> null
            }
        }

    private fun disarmed(rule: RuleDecl, atom: GuardAtom): Boolean = when (atom) {
        is GuardAtom.Witness -> rule.body.filterIsInstance<Creation>().any { it.shape == atom.shape }
        is GuardAtom.Flag -> rule.body.filterIsInstance<Assignment>()
            .any { it.target.segs.lastOrNull()?.name == atom.name || (it.target.segs.isEmpty() && it.target.root == atom.name) }
    }

    private fun checkBoundaryAndDisarm(rule: RuleDecl) {
        val atoms = guardAtoms(rule)
        val schedules = rule.triggers.filter { it != "commit" }
        if (rule.preposition == "after") {
            if (atoms.isEmpty() || schedules.isEmpty())
                diags.add(Diagnostic("V4", "rule '${rule.name}' declares 'after commit' without " +
                    (if (atoms.isEmpty()) "a dischargeable guard" else "a backstop schedule") +
                    " — this firing can be lost at the declared boundary (README §11, §18)"))
        }
        // V2: a guarded rule that can re-evaluate (boundary or tick) must disarm its own
        // trigger. Falsifying any one conjunct falsifies the conjunction, so one
        // disarmed atom discharges the proof — the others are conditions, not guards.
        // deleting the subject is the structural disarm: the trigger state has
        // no member left to re-fire on (OQ37)
        if (atoms.isNotEmpty() && (rule.preposition == "after" || schedules.isNotEmpty())) {
            if (atoms.none { disarmed(rule, it) } && !deletesSubject(rule)) {
                val what = atoms.joinToString(" or ") { atom ->
                    when (atom) {
                        is GuardAtom.Witness -> "produce a '${atom.shape}'"
                        is GuardAtom.Flag -> "assign '${atom.name}'"
                    }
                }
                diags.add(Diagnostic("V2", "rule '${rule.name}' never leaves its trigger state — its body must $what (the disarm law, README §18)"))
            }
        }
        // V7: a capture-reading rule is transaction-bound
        if (rule.leaving) {
            val scope = subjectScope(rule) ?: return
            val readsCapture = ruleReads(rule).capturedReads.isNotEmpty() ||
                bodyReadsCapturedMember(rule, scope)
            if (readsCapture && (rule.preposition == "after" || schedules.isNotEmpty()))
                diags.add(Diagnostic("V7", "rule '${rule.name}' reads a captured property, which does not survive " +
                    "the transaction boundary it declares (README §13)"))
        }
    }

    private fun bodyReadsCapturedMember(rule: RuleDecl, scope: String): Boolean {
        val captured = model.membersOf(scope).values.filter { it.captured }.map { it.name }.toSet()
        if (captured.isEmpty()) return false
        var found = false
        fun walk(e: Expr) {
            when (e) {
                is PathExpr -> if (e.root in captured) found = true
                is Binary -> { walk(e.left); walk(e.right) }
                is UnaryMinus -> walk(e.inner)
                is NotExpr -> walk(e.inner)
                is IfExpr -> { walk(e.condition); walk(e.thenExpr); walk(e.elseExpr) }
                is FunCall -> e.args.forEach(::walk)
                else -> {}
            }
        }
        rule.body.forEach { item ->
            when (item) {
                is Assignment -> walk(item.value)
                is Creation -> item.fields.forEach { walk(it.value) }
                is DeleteStmt -> {}
                ThenMarker -> {}
            }
        }
        return found
    }

    // ── V3: reachability ─────────────────────────────────────────────────────

    private fun commitKinds(): List<CommitKind> = commitKindsCache

    private val commitKindsCache: List<CommitKind> by lazy {
        val kinds = mutableListOf<CommitKind>()
        model.exposed.forEach { kinds.add(CommitKind.Creates(it, source = "expose $it")) }
        // closure parts are committable-via-closure (README §6, "Inline part
        // creation"): the closure commit creates them, so they join the trigger,
        // reachability, and co-firability analyses as ordinary creations
        fun addClosure(container: String, e: ClosureEdge) {
            kinds.add(CommitKind.Creates(e.partShape, source = "expose $container with ${e.prop}"))
            e.children.forEach { addClosure(container, it) }
        }
        model.closures.forEach { (container, edges) -> edges.forEach { addClosure(container, it) } }
        for (rule in model.rules.values) {
            rule.body.filterIsInstance<Creation>().forEach {
                kinds.add(CommitKind.Creates(it.shape, source = "rule ${rule.name}", byRule = rule))
            }
            writes.filter { it.rule === rule }.forEach {
                kinds.add(CommitKind.Assigns(it.owner, it.member.name, byRule = rule))
            }
            model.deleteSites.filter { it.rule === rule }.forEach {
                kinds.add(CommitKind.Deletes(it.shape, byRule = rule))
            }
        }
        kinds
    }

    private sealed interface CommitKind {
        val byRule: RuleDecl?
        data class Creates(val shape: String, val source: String, override val byRule: RuleDecl? = null) : CommitKind
        data class Assigns(val shape: String, val field: String, override val byRule: RuleDecl? = null) : CommitKind
        /** A rule's delete of an instance of [shape] (OQ37) — the third state change. */
        data class Deletes(val shape: String, override val byRule: RuleDecl? = null) : CommitKind
    }

    private fun ruleReads(rule: RuleDecl): RuleSummary = ruleSummaries.getOrPut(rule.name) {
        val s = ReadSummary()
        val scope = subjectScope(rule)
        val summary = RuleSummary(s)
        if (scope != null) {
            (rule.condition as? RefName)?.let { c ->
                if (c.name in model.refinements) s.absorb(model.predicateSummary(c.name))
                c.where?.let { model.collectExpr(it, scope, s) }
            }
            if (rule.condition !is RefName) collectComposite(rule.condition, s)
            val base = model.baseOfExpr(rule.condition)
            base?.let { summary.conditionBase = it }
            rule.body.forEach { item ->
                when (item) {
                    is Assignment -> model.collectExpr(item.value, scope, s)
                    is Creation -> item.fields.forEach { model.collectExpr(it.value, scope, s) }
                    is DeleteStmt -> model.collectExpr(item.target, scope, s)
                    ThenMarker -> {}
                }
            }
        }
        summary
    }

    private fun collectComposite(e: RefExpr, s: ReadSummary) {
        when (e) {
            is RefName -> {
                if (e.name in model.refinements) s.absorb(model.predicateSummary(e.name))
                e.where?.let { model.collectExpr(it, model.baseOfExpr(e) ?: return, s) }
            }
            is RefNot -> collectComposite(e.inner, s)
            is RefAnd -> { collectComposite(e.left, s); collectComposite(e.right, s) }
            is RefOr -> { collectComposite(e.left, s); collectComposite(e.right, s) }
        }
    }

    private class RuleSummary(val reads: ReadSummary) {
        var conditionBase: String? = null
        val fields get() = reads.fields
        val capturedReads = mutableSetOf<String>()
    }

    private fun conditionSummary(rule: RuleDecl): ReadSummary = ruleConditionSummaries.getOrPut(rule.name) {
        val s = ReadSummary()
        collectComposite(rule.condition, s)
        s
    }

    private val ruleSummaries = mutableMapOf<String, RuleSummary>()
    private val ruleConditionSummaries = mutableMapOf<String, ReadSummary>()

    /** Does this commit kind possibly affect the rule's condition? Widened by
     *  the sibling-confluence audit to the summary's full read vocabulary:
     *  consulted shapes base-normalized (a refinement name in `existsShapes`
     *  never matched a created base shape), collection consults, timestamp
     *  reads (any write to the shape advances its `on update` stamps), and
     *  `opaque` as affects-everything — the summary's own soundness contract. */
    private fun affects(k: CommitKind, rule: RuleDecl): Boolean {
        val base = model.baseOfExpr(rule.condition) ?: return false
        // a transient act's refinements are evaluated exactly once, at its
        // creation commit — no other commit can ever fire the rule (README §4)
        if (base in model.transients)
            return k is CommitKind.Creates && k.shape == base
        val cond = conditionSummary(rule)
        if (cond.opaque) return true
        return when (k) {
            is CommitKind.Creates ->
                k.shape == base || k.shape in consultedShapes(rule)
            is CommitKind.Assigns ->
                (k.shape to k.field) in cond.fields ||
                    (k.shape to k.field) in cond.collFields ||
                    cond.collFields.any { it.first == k.shape && it.second in updateTimestamps(k.shape) }
            // a deletion flips existence/aggregate consults of the shape, removes the
            // deleted instance from every refinement of its base, and turns reads
            // through references at it absent (`is none` — the absorbing reference)
            is CommitKind.Deletes ->
                k.shape == base || k.shape in consultedShapes(rule) || conditionReadsRefTo(rule, k.shape)
        }
    }

    /** Does the rule's condition read a reference-typed field whose target is
     *  [shape]? Deleting the target flips such reads to absent (OQ37-R10). */
    private fun conditionReadsRefTo(rule: RuleDecl, shape: String): Boolean {
        val cond = conditionSummary(rule)
        if (cond.opaque) return true
        return (cond.fields + cond.collFields).any { (o, f) ->
            model.membersOf(o)[f]?.type?.instanceShape()?.let { model.baseOf(it) } == shape
        }
    }

    /** Base shapes whose instance sets the rule's condition consults. */
    private fun consultedShapes(rule: RuleDecl): Set<String> = consultedCache.getOrPut(rule.name) {
        val cond = conditionSummary(rule)
        (cond.existsShapes + cond.collShapes).mapNotNull { model.baseOf(it) }.toSet()
    }

    private val consultedCache = mutableMapOf<String, Set<String>>()

    private fun checkReachability(rule: RuleDecl) {
        val schedules = rule.triggers.filter { it != "commit" }
        val cond = conditionSummary(rule)
        if (cond.readsTime && schedules.isEmpty()) {
            diags.add(Diagnostic("V3", "rule '${rule.name}' — its condition depends on the passage of time, " +
                "which no act commit changes: entry by aging is unobserved. Add a schedule to 'on', or this rule under-fires (README §11)"))
            return
        }
        if (schedules.isNotEmpty()) return // tick-served
        if (commitKinds().none { affects(it, rule) })
            diags.add(Diagnostic("V3", "rule '${rule.name}' can never fire — no expose, rule effect, or tick " +
                "in the spec can affect its condition (README §11, §22)"))
    }

    // ── V5: freeze disjointness ──────────────────────────────────────────────

    private fun checkFreezes() {
        for ((refName, r) in model.refinements) {
            val frozen = r.members.filterIsInstance<FrozenClause>().singleOrNull() ?: continue
            val base = model.baseOf(refName) ?: continue
            val fields = frozen.fields.ifEmpty {
                model.shapes[base]?.members?.filterIsInstance<StoredProp>()?.map { it.name } ?: emptyList()
            }
            for (w in writes) {
                if (w.owner != base || w.member.name !in fields) continue
                val route = PathExpr(w.target.root, w.target.segs.dropLast(1))
                val excluded = conditionConjuncts(w.rule.condition).any { conjunct ->
                    conjunct == NotExpr(IsExpr(route, "refinement", refName))
                }
                if (!excluded)
                    diags.add(Diagnostic("V5", "rule '${w.rule.name}' writes $base.${w.member.name}, frozen by '$refName' — " +
                        "the trigger does not provably exclude membership; partition the act (README §8, \"Frozen fields\")"))
            }
        }
    }

    // ── V6: capture entry-evaluability (coarse) ──────────────────────────────

    private fun checkCaptures() {
        for ((refName, r) in model.refinements) {
            val base = model.baseOf(refName) ?: continue
            for (m in r.members.filterIsInstance<DerivedProp>().filter { it.captured }) {
                val s = ReadSummary()
                model.collectExpr(m.expr, refName, s)
                // coarse: base-shape stored fields, literals, and time are entry-evaluable;
                // anything reaching another shape must be guaranteed by the predicate — v0
                // only accepts what the exists-atoms of the predicate mention
                val guaranteed = model.predicateSummary(refName).existsShapes
                s.existsShapes.filterNot { it in guaranteed }.forEach {
                    diags.add(Diagnostic("V6", "capture '$refName.${m.name}' reads '$it', which its predicate does not guarantee at entry (README §8)"))
                }
                s.fields.filterNot { (owner, _) -> owner == base }.forEach { (owner, f) ->
                    if (owner !in guaranteed)
                        diags.add(Diagnostic("V6", "capture '$refName.${m.name}' reads $owner.$f beyond the base shape without a predicate guarantee"))
                }
            }
        }
    }

    // ── V8: folds ────────────────────────────────────────────────────────────

    private fun checkFolds() {
        for (w in writes) {
            val rhsReads = ReadSummary().also {
                val scope = subjectScope(w.rule) ?: return@also
                (w.rule.body.filterIsInstance<Assignment>().find { a -> a.target == w.target })
                    ?.let { a -> model.collectExpr(a.value, scope, it) }
            }
            if ((w.owner to w.member.name) !in rhsReads.fields) continue // not self-referential
            val assignment = w.rule.body.filterIsInstance<Assignment>().first { it.target == w.target }
            val whitelisted = (assignment.value as? FunCall)?.name in setOf("max", "min")
            if (whitelisted) continue
            val schedules = w.rule.triggers.filter { it != "commit" }
            val tolerates = (model.shapes[w.owner]?.members
                ?.filterIsInstance<StoredProp>()?.find { it.name == w.member.name })?.tolerates
            val guarded = guardAtoms(w.rule).any { disarmed(w.rule, it) }
            val duplicationExposed = (w.rule.preposition == "after" || schedules.isNotEmpty()) && !guarded
            if (duplicationExposed && tolerates != "duplication")
                diags.add(Diagnostic("V8", "'${w.owner}.${w.member.name}' folds its own value and can be applied twice " +
                    "(rule '${w.rule.name}') — gate it on a dischargeable state, derive it, add a reconciliation sweep, " +
                    "or declare `tolerates duplication` (README §19)"))
            // +/- folds commute with each other ((x-a)-b == (x-b)-a); only genuinely
            // order-dependent forms (streaks, resets) fail this
            val orderSafe = assignment.value.let { it is Binary && it.op in setOf("+", "-") }
            if (schedules.isNotEmpty() && !orderSafe && tolerates != "reordering")
                diags.add(Diagnostic("V8", "'${w.owner}.${w.member.name}' is an order-dependent fold on a tick cadence " +
                    "(rule '${w.rule.name}') — nothing orders one tick's firings; " +
                    "spell the value as a predecessor recurrence over the ordering datum (README §19)"))
        }
    }

    // ── V10: never obligations (coarse classification) ───────────────────────

    private fun checkNevers() {
        for (n in model.nevers) {
            val s = ReadSummary()
            collectComposite(n.target, s)
            val written = writes.map { it.owner to it.member.name }.toSet()
            (s.fields intersect written).forEach { (shape, field) ->
                val writer = writes.first { it.owner == shape && it.member.name == field }.rule.name
                diags.add(Diagnostic("V10", "never over $shape.$field is rule-maintained ('$writer' writes it) — " +
                    "v0 cannot yet prove the invariant inductively; fail closed (README §21, OQ16)"))
            }
        }
    }

    // ── V14: definition-graph well-foundedness (stratify, then certify) ──────
    //
    // Evaluation is definition-unfolding, and unfolding is the one thing that
    // can fail to bottom out — so the definition graph (derived properties;
    // refinements referencing refinements) must be well-founded. The acyclic
    // part passes free (stratification). Each static cycle owes a certificate
    // from the decidable whitelist (checks.md V14), and v0 certifies exactly
    // one cycle form — a single derived property reading itself one hop
    // through an optional to-one on its own shape — by either:
    //   (a) strict descent: the hop is a derived selector over the same shape
    //       whose predicate strictly compares a creation-fixed datum against
    //       the subject's (`previous` = latest(... receivedOn < this.receivedOn
    //       ...) — the streakAfter recurrence, README §19);
    //   (b) spent acyclicity: the hop is a stored to-one and a spendable
    //       `never (S where hop == this)` forbids it pointing back at its own
    //       instance (`root` through `parent`, README §7, §21 — the direct case).
    // Everything else — direct self-reads, deeper paths, multi-node cycles,
    // refinement cycles — fails closed; fixpoint semantics are never attempted.

    private sealed interface DefNode {
        data class Prop(val shape: String, val prop: String) : DefNode
        data class Ref(val name: String) : DefNode
    }

    private fun label(n: DefNode) = when (n) {
        is DefNode.Prop -> "${n.shape}.${n.prop}"
        is DefNode.Ref -> "shape '${n.name}'"
    }

    private fun checkWellFoundedness() {
        val edges = mutableMapOf<DefNode, Set<DefNode>>()
        val exprs = mutableMapOf<DefNode, Pair<String, DerivedProp>>()
        for ((shapeName, shape) in model.shapes) {
            for (m in shape.members.filterIsInstance<DerivedProp>()) {
                val s = ReadSummary()
                model.collectExpr(m.expr, shapeName, s)
                val refs = mutableSetOf<String>()
                exprMentions(m.expr, refs)
                val node = DefNode.Prop(shapeName, m.name)
                edges[node] = s.derivedSeen.map { DefNode.Prop(it.first, it.second) }.toSet() +
                    refs.map { DefNode.Ref(it) }
                exprs[node] = shapeName to m
            }
        }
        for ((name, r) in model.refinements) {
            val s = model.summaryOfRefExpr(r.expr)
            val refs = mutableSetOf<String>()
            refExprMentions(r.expr, refs)
            edges[DefNode.Ref(name)] = s.derivedSeen.map { DefNode.Prop(it.first, it.second) }.toSet() +
                refs.map { DefNode.Ref(it) }
        }
        val visiting = mutableSetOf<DefNode>()
        val done = mutableSetOf<DefNode>()
        fun dfs(node: DefNode, path: List<DefNode>) {
            if (node in done) return
            if (node in visiting) {
                val cycle = path.dropWhile { it != node } + node
                val selfLoop = (cycle.toSet().singleOrNull() as? DefNode.Prop)
                    ?.let { exprs[it] }
                if (selfLoop != null && certifiedSelfRecursion(selfLoop.first, selfLoop.second)) return
                diags.add(Diagnostic("V14", "definition cycle: " +
                    cycle.joinToString(" -> ") { label(it) } +
                    " — no certificate from the whitelist applies: v0 certifies a derived property " +
                    "that reads itself one hop through an optional to-one on its own shape, where the hop " +
                    "either strictly descends a creation-fixed datum (a derived `latest ... by` predecessor) " +
                    "or is proven acyclic by a spendable `never (Shape where hop == this)`; " +
                    "restructure, or supply a certificate (checks.md V14)"))
                return
            }
            visiting.add(node)
            edges[node].orEmpty().forEach { dfs(it, path + node) }
            visiting.remove(node)
            done.add(node)
        }
        edges.keys.forEach { dfs(it, emptyList()) }
    }

    /**
     * Certify one self-recursive derived property: every occurrence of the
     * property's own name in its formula must be exactly `<hop>.<prop>`, one
     * hop through an optional to-one on the same shape (the optionality is
     * the structural base case — the chain can end), and every such hop must
     * carry a descent or acyclicity certificate. Any other occurrence — a
     * direct self-read, a deeper path, a read through a selector — fails
     * closed. Name collisions with same-named fields elsewhere fail closed
     * too; the walk is syntactic, and coarse-but-sound is the v0 contract.
     */
    private fun certifiedSelfRecursion(shape: String, prop: DerivedProp): Boolean {
        val name = prop.name
        val hops = mutableSetOf<String>()
        var ok = true
        fun walk(e: Expr?) {
            if (e == null || !ok) return
            when (e) {
                is PathExpr -> {
                    if (e.root == name || e.segs.any { it.name == name }) {
                        val hop = e.root.takeIf { e.root != name && e.segs.size == 1 && e.segs[0].name == name }
                        val m = hop?.let { model.membersOf(shape)[it] }
                        if (m == null || m.type !is VType.Optional || m.type.instanceShape() != shape) {
                            ok = false
                            return
                        }
                        hops.add(hop)
                    }
                }
                is Access -> {
                    if (e.segs.any { it.name == name }) { ok = false; return }
                    walk(e.target)
                }
                is UnaryMinus -> walk(e.inner)
                is Binary -> { walk(e.left); walk(e.right) }
                is NotExpr -> walk(e.inner)
                is IfExpr -> { walk(e.condition); walk(e.thenExpr); walk(e.elseExpr) }
                is IsExpr -> walk(e.subject)
                is ExistsExpr -> {
                    walk(e.forExpr)
                    e.collection?.let { c -> c.bindings.forEach { walk(it.source) }; walk(c.where) }
                }
                is AggCall -> { e.collection.bindings.forEach { walk(it.source) }; walk(e.collection.where) }
                is FunCall -> e.args.forEach { walk(it) }
                is SingularFor -> walk(e.forExpr)
                is ShapeForSource -> walk(e.forExpr)
                is SetExpr -> { e.collection.bindings.forEach { walk(it.source) }; walk(e.collection.where) }
                else -> {}
            }
        }
        walk(prop.expr)
        return ok && hops.isNotEmpty() && hops.all { hopCertified(shape, it) }
    }

    private fun hopCertified(shape: String, hop: String): Boolean {
        val m = model.membersOf(shape)[hop] ?: return false
        m.derived?.let { return strictDescentSelector(shape, it.expr) }
        return m.stored && neverForbidsSelfReference(shape, hop)
    }

    /** Is [e] a `latest`/`first` over [shape]'s own instances whose predicate
     *  carries a strict comparison of a creation-fixed datum against the
     *  subject's — so every hop lands on a strictly smaller (or larger) value
     *  of a datum that never changes, and the chain must end in finite data? */
    private fun strictDescentSelector(shape: String, e: Expr): Boolean {
        val agg = e as? AggCall ?: return false
        if (agg.name != "latest" && agg.name != "first") return false
        val src = agg.collection.bindings.singleOrNull()?.source ?: return false
        val overOwnShape = when {
            src is PathExpr && src.segs.isEmpty() -> model.baseOf(src.root) == shape
            src is ShapeForSource -> model.baseOf(src.shape) == shape
            else -> false
        }
        if (!overOwnShape) return false
        val conjuncts = agg.collection.where?.let(::conjunctsOf) ?: return false
        return conjuncts.any { strictDescentConjunct(shape, it) }
    }

    private fun strictDescentConjunct(shape: String, c: Expr): Boolean {
        val bin = c as? Binary ?: return false
        if (bin.op != "<" && bin.op != ">") return false
        fun bare(e: Expr): String? =
            (e as? PathExpr)?.takeIf { it.segs.isEmpty() && it.root != "this" }?.root
        fun viaThis(e: Expr): String? =
            (e as? PathExpr)?.takeIf { it.root == "this" && it.segs.size == 1 }?.segs?.single()?.name
        val datum = bare(bin.left)?.takeIf { it == viaThis(bin.right) }
            ?: bare(bin.right)?.takeIf { it == viaThis(bin.left) }
            ?: return false
        return creationFixed(shape, datum)
    }

    /** Fixed at the creation commit, never changed after: a `timestamp on
     *  create` field, or a stored field no rule ever assigns. */
    private fun creationFixed(shape: String, field: String): Boolean {
        val members = model.shapes[shape]?.members ?: return false
        members.filterIsInstance<TimestampProp>().find { it.name == field }
            ?.let { return it.on == "create" }
        if (members.filterIsInstance<StoredProp>().none { it.name == field }) return false
        return writes.none { it.owner == shape && it.member.name == field }
    }

    // ── refinement mentions (the definition graph's refinement edges, and
    //    "which refinements does this rule watch" for V15) ────────────────────

    private fun refExprMentions(e: RefExpr, out: MutableSet<String>) {
        when (e) {
            is RefName -> {
                if (e.name in model.refinements) out.add(e.name)
                e.where?.let { exprMentions(it, out) }
            }
            is RefNot -> refExprMentions(e.inner, out)
            is RefAnd -> { refExprMentions(e.left, out); refExprMentions(e.right, out) }
            is RefOr -> { refExprMentions(e.left, out); refExprMentions(e.right, out) }
        }
    }

    private fun exprMentions(e: Expr?, out: MutableSet<String>) {
        e ?: return
        fun add(name: String?) { if (name != null && name in model.refinements) out.add(name) }
        when (e) {
            is PathExpr -> add(e.root)
            is UnaryMinus -> exprMentions(e.inner, out)
            is Binary -> { exprMentions(e.left, out); exprMentions(e.right, out) }
            is NotExpr -> exprMentions(e.inner, out)
            is IfExpr -> { exprMentions(e.condition, out); exprMentions(e.thenExpr, out); exprMentions(e.elseExpr, out) }
            is IsExpr -> { add(e.refinement); exprMentions(e.subject, out) }
            is ExistsExpr -> {
                add(e.shape)
                exprMentions(e.forExpr, out)
                e.collection?.let { c -> c.bindings.forEach { exprMentions(it.source, out) }; exprMentions(c.where, out) }
            }
            is AggCall -> { e.collection.bindings.forEach { exprMentions(it.source, out) }; exprMentions(e.collection.where, out) }
            is FunCall -> e.args.forEach { exprMentions(it, out) }
            is SingularFor -> { add(e.shape); exprMentions(e.forExpr, out) }
            is ShapeForSource -> { add(e.shape); exprMentions(e.forExpr, out) }
            is SetExpr -> { e.collection.bindings.forEach { exprMentions(it.source, out) }; exprMentions(e.collection.where, out) }
            else -> {}
        }
    }

    private val refinementMentionGraph: Map<String, Set<String>> by lazy {
        model.refinements.mapValues { (_, r) ->
            mutableSetOf<String>().also { refExprMentions(r.expr, it) }
        }
    }

    /** Refinements whose membership this rule's condition depends on, closed
     *  transitively over refinement definitions: if the condition mentions T
     *  and T's definition mentions R, a flip of R flips T. */
    private fun watchedRefinements(rule: RuleDecl): Set<String> = watchedCache.getOrPut(rule.name) {
        val out = mutableSetOf<String>()
        val work = ArrayDeque<String>()
        mutableSetOf<String>().also { refExprMentions(rule.condition, it) }.forEach { work.add(it) }
        while (work.isNotEmpty()) {
            val r = work.removeFirst()
            if (out.add(r)) refinementMentionGraph[r].orEmpty().forEach { work.add(it) }
        }
        out
    }

    private val watchedCache = mutableMapOf<String, Set<String>>()

    // ── V15 (third leg): transition interference ─────────────────────────────
    //
    // Two unordered siblings can each flip one refinement's membership — by
    // assigning a field its predicate reads, or by creating an instance of a
    // shape it consults. Whether the mid-transaction membership history has a
    // transition at all — does the instance pass through the refinement
    // between the two effects? — then depends on which sibling fired first
    // (checks.md V15; OQ16's value-dependent interference case, fail-closed:
    // statically there is only "both inputs affected by unordered siblings").
    // An unobserved refinement is skipped — with no observer, the
    // mid-transaction history is not part of the outcome. Observers are rules
    // whose conditions depend on the refinement (transitively), and the
    // refinement's own captures: a capture evaluates at the entry commit and
    // persists, so it observes the history with no rule involved. Captures
    // add a channel of their own (probe E): the captured VALUE reads fields —
    // a sibling assigning one while another causes the entry makes the
    // captured value depend on their order.

    private fun checkTransitionInterference() {
        val commitRules = model.rules.values.filter { it.preposition != "after" && firesOnCommit(it) }
        if (commitRules.size < 2) return
        val watchers = mutableMapOf<String, RuleDecl>()
        for (rule in model.rules.values) watchedRefinements(rule).forEach { watchers.putIfAbsent(it, rule) }
        fun fmt(tokens: Set<String>) = tokens.joinToString(", ")
        for ((refName, r) in model.refinements) {
            val captured = r.members.filterIsInstance<DerivedProp>().filter { it.captured }
            val watcher = watchers[refName]
            if (watcher == null && captured.isEmpty()) continue
            val observer = watcher?.let { "rule '${it.name}' watches it" }
                ?: "its captures record the entry"

            val pr = model.predicateSummary(refName)
            val base = model.baseOf(refName)
            val polarities = refinementPolarities(refName).map

            /** How this rule can flip membership in [refName]: token → sign
             *  (+1 toward membership, -1 away, 0 both ways). Field writes are
             *  value-dependent (0). A creation flips only through a consult a
             *  fresh instance can satisfy, at that consult's polarity — and
             *  creating the base shape itself is entry (+) unless the
             *  predicate provably excludes fresh instances. */
            fun flips(rule: RuleDecl): Map<String, Int> {
                val (assigned, created, deleted) = ruleEffects(rule)
                if (pr.opaque)
                    return (assigned.map { "${it.first}.${it.second}" } +
                        created.map { "creates '$it'" } +
                        deleted.map { "deletes '$it'" }).associateWith { 0 }
                val fieldTokens = assigned.intersect(pr.fields + pr.collFields)
                    .associate { "${it.first}.${it.second}" to 0 }
                val createTokens = mutableMapOf<String, Int>()
                for (s in created) {
                    if (s == base && !freshCannotEnter(refName, created)) {
                        createTokens["creates '$s'"] = +1
                        continue
                    }
                    val signs = polarities.keys.filter { model.baseOf(it) == s }
                        .filter { it in model.shapes || !freshCannotEnter(it, created) }
                        .map { polarities.getValue(it) }
                    if (signs.isEmpty()) continue
                    createTokens["creates '$s'"] = signs.toSet().singleOrNull()?.takeIf { it != 0 } ?: 0
                }
                // deletion mirrors creation with the sign negated: it can only
                // unsatisfy a consult; the deleted base instance can only leave;
                // reads through references at the shape flip value-dependently
                for (s in deleted) {
                    if (s == base) { createTokens["deletes '$s'"] = -1; continue }
                    val signs = polarities.keys.filter { model.baseOf(it) == s }
                        .map { -polarities.getValue(it) }
                    val viaRef = (pr.fields + pr.collFields).any { (o, f) ->
                        model.membersOf(o)[f]?.type?.instanceShape()?.let { model.baseOf(it) } == s
                    }
                    if (signs.isEmpty() && !viaRef) continue
                    createTokens["deletes '$s'"] =
                        if (viaRef) 0 else signs.toSet().singleOrNull()?.takeIf { it != 0 } ?: 0
                }
                return fieldTokens + createTokens
            }

            val capturedReads = ReadSummary().also { s ->
                captured.forEach { model.collectExpr(it.expr, refName, s) }
            }

            for (i in commitRules.indices) for (j in commitRules.indices) {
                if (i == j) continue
                val a = commitRules[i]
                val b = commitRules[j]
                if (provablyDisjoint(a, b) || !canCoFire(a, b) || enterLeaveExclusive(a, b)) continue
                val fa = flips(a)
                val fb = flips(b)
                // Both siblings can flip membership, and not provably in the
                // same direction: whether the transaction passes through the
                // refinement depends on their order. Same-direction effects
                // are safe — the flip lands at whichever effect completes the
                // predicate, in both orders (two prerequisites of one
                // conjunction never race each other).
                val signs = (fa.values + fb.values).toSet()
                val sameDirection = signs.size == 1 && signs.single() != 0
                if (i < j && fa.isNotEmpty() && fb.isNotEmpty() && (fa.keys + fb.keys).size >= 2 && !sameDirection) {
                    diags.add(Diagnostic("V15", "'${a.name}' (${fmt(fa.keys)}) and sibling '${b.name}' (${fmt(fb.keys)}) " +
                        "each affect membership in '$refName', and $observer: whether the transaction passes " +
                        "through '$refName' between the two effects depends on their unstated order; " +
                        "state the intent — condition one rule on the other's outcome, move both effects " +
                        "into one rule, or move one rule to `after commit` so it runs as its own " +
                        "transaction and sees the other's outcome already settled (OQ16)"))
                }
                // one sibling causes the entry, the other writes what the
                // captured expressions read: the captured value depends on order
                if (captured.isNotEmpty() && fa.isNotEmpty()) {
                    for (what in readConflicts(b, capturedReads))
                        diags.add(Diagnostic("V15", "'${a.name}' can cause entry into '$refName', whose captures " +
                            "read $what, which sibling '${b.name}' writes — the captured value depends on " +
                            "their unstated order; state the intent (OQ16)"))
                }
            }
        }
    }

    // ── V16: quiescence (condition-graph cycles broken by disarms) ───────────

    private fun checkQuiescence() {
        val commitRules = model.rules.values.filter { firesOnCommit(it) || it.preposition == "after" }
        val edges = commitRules.associateWith { a ->
            val produced = commitKinds().filter { it.byRule === a }
            commitRules.filter { b -> produced.any { affects(it, b) } }
        }
        val visiting = mutableSetOf<RuleDecl>()
        val done = mutableSetOf<RuleDecl>()
        fun dfs(node: RuleDecl, path: List<RuleDecl>) {
            if (node in done) return
            if (node in visiting) {
                val cycle = path.dropWhile { it != node } + node
                val broken = cycle.any { r -> guardAtoms(r).any { disarmed(r, it) } || deletesSubject(r) }
                if (!broken)
                    diags.add(Diagnostic("V16", "rule cascade may not quiesce: " +
                        cycle.joinToString(" -> ") { it.name } +
                        " — no disarming guard breaks the cycle (README §11, OQ16)"))
                return
            }
            visiting.add(node)
            edges[node].orEmpty().forEach { dfs(it, path + node) }
            visiting.remove(node)
            done.add(node)
        }
        commitRules.forEach { dfs(it, emptyList()) }
    }

    // ── V12 (refinement slice): at-most-one proofs for (Refinement for expr) ─
    //
    // A singular reference to a refinement R is licensed by the whole-spec
    // singularity proof (README §10; §20 "Episodes as data"): at most one
    // member of R can reference a given subject. The proof is inductive over
    // commits, with two legs — every producer of R's base carries the entry
    // guard, and R's predicate is anti-monotone (membership, once lost, can't
    // return, so members only ever appear at guarded creation). v0 proves the
    // coarse fail-closed slice of each leg; base-shape singular references
    // stay runtime-enforced (V12 beyond this slice is TODO, checks.md).

    private fun checkSingularProofs() {
        forEachSpecExpr { site, e ->
            if (e !is SingularFor || e.shape !in model.refinements) return@forEachSpecExpr
            val r = e.shape
            val base = model.baseOf(r) ?: return@forEachSpecExpr
            val demand = "$site — '($r for ...)' needs at-most-one"
            if (base in model.exposed)
                diags.add(Diagnostic("V12", "$demand, but '$base' is exposed — an external committer " +
                    "can create a second member while one exists; use latest/first instead"))
            antiMonotoneFailure(r, base)?.let { why ->
                diags.add(Diagnostic("V12", "$demand, but membership in '$r' can recur — $why; " +
                    "use latest/first instead"))
            }
            for (rule in model.rules.values) {
                for (c in rule.body.filterIsInstance<Creation>()) {
                    if (c.shape != base) continue
                    if (!creationGuarded(rule, c, r, base))
                        diags.add(Diagnostic("V12", "$demand, but rule '${rule.name}' creates '$base' " +
                            "without the entry guard 'not exists $r for ...' — guard it, or use latest/first"))
                }
            }
        }
    }

    /**
     * Anti-monotone: once an instance leaves R it can never re-enter, so guarded
     * creation bounds membership forever. v0's provable conjunct forms: `not
     * exists ...` (monotone while no rule deletes the witness — OQ37), and `not <flag>`
     * where every writer assigns literal true (a one-way latch, checked against
     * the same write set V1 walks). Returns null when proven, else the reason.
     */
    private fun antiMonotoneFailure(r: String, base: String): String? {
        val conjuncts = refPredicateConjuncts(RefName(r))
            ?: return "'$r' composes with or/not — v0 proves plain conjunctions only"
        for (c in conjuncts) {
            val inner = (c as? NotExpr)?.inner
                ?: return "a conjunct is not of the form 'not ...' — not provably anti-monotone in v0"
            when {
                inner is ExistsExpr -> {
                    // 'not exists W' was anti-monotone because facts persisted; a
                    // deleter of W makes re-entry by deletion possible (OQ37)
                    val w = inner.shape
                        ?: (inner.collection?.bindings?.firstOrNull()?.source as? PathExpr)
                            ?.takeIf { it.segs.isEmpty() }?.root
                    val wb = w?.let { model.baseOf(it) }
                    if (wb != null && wb in model.deletedShapes)
                        return "a rule deletes '$wb' (OQ37), so the 'not exists' conjunct can " +
                            "re-become true — re-entry by deletion is possible"
                }
                inner is PathExpr && inner.segs.isEmpty() -> {
                    val flag = inner.root
                    val falseWriter = writes.firstOrNull {
                        it.owner == base && it.member.name == flag && it.value != BoolLit(true)
                    }
                    if (falseWriter != null)
                        return "rule '${falseWriter.rule.name}' writes '$flag' non-true, " +
                            "re-entry by drift is possible"
                }
                else -> return "a conjunct is not provably anti-monotone in v0 " +
                    "('not exists ...' and one-way latch flags are the provable forms)"
            }
        }
        return null
    }

    /** Flattened conjuncts of a refinement expression's predicates; null on or/not composition (fail closed). */
    private fun refPredicateConjuncts(e: RefExpr): List<Expr>? = when (e) {
        is RefName -> {
            val nested = when {
                e.name in model.shapes -> emptyList()
                e.name in model.refinements -> refPredicateConjuncts(model.refinements.getValue(e.name).expr)
                else -> null
            } ?: return null
            nested + (e.where?.let(::conjunctsOf).orEmpty())
        }
        is RefAnd -> {
            val l = refPredicateConjuncts(e.left) ?: return null
            val r = refPredicateConjuncts(e.right) ?: return null
            l + r
        }
        else -> null
    }

    private fun conjunctsOf(e: Expr): List<Expr> =
        if (e is Binary && e.op == "and") conjunctsOf(e.left) + conjunctsOf(e.right) else listOf(e)

    /**
     * Does [rule]'s condition carry the entry guard for [r], correlated on a
     * field this [c]reation populates with `this`? Accepted guard spellings:
     * `not exists R for this` (correlating field resolved by type match) and
     * `not exists (R where f == this)`.
     */
    private fun creationGuarded(rule: RuleDecl, c: Creation, r: String, base: String): Boolean {
        val scope = subjectScope(rule) ?: return false
        val thisExpr = PathExpr("this")
        val correlated = buildSet {
            c.fields.forEach { fi -> if (fi.value == thisExpr) add(fi.name) }
            if (c.forExpr == thisExpr) typeMatchedFields(base, thisExpr, scope).singleOrNull()?.let { add(it) }
        }
        val conjuncts = refPredicateConjuncts(rule.condition) ?: return false
        return conjuncts.any { conj ->
            val ex = ((conj as? NotExpr)?.inner as? ExistsExpr) ?: return@any false
            when {
                ex.shape == r && ex.forExpr == thisExpr ->
                    typeMatchedFields(base, thisExpr, scope).singleOrNull() in correlated
                ex.collection != null -> {
                    val b = ex.collection.bindings.singleOrNull()?.source as? PathExpr
                    val w = ex.collection.where as? Binary
                    b?.root == r && b.segs.isEmpty() && w?.op == "==" &&
                        ((w.left as? PathExpr)?.segs?.isEmpty() == true &&
                            w.right == thisExpr && (w.left as PathExpr).root in correlated ||
                         (w.right as? PathExpr)?.segs?.isEmpty() == true &&
                            w.left == thisExpr && (w.right as PathExpr).root in correlated)
                }
                else -> false
            }
        }
    }

    // ── V17/V18: transient acts (README §4, "Transient acts") ────────────────
    //
    // A transient act exists only within its own commit's transaction — an
    // input to the state, not a member of it. V17 (isolation): nothing durable
    // or later may depend on it. V18 (totality): every request gets a response
    // — an act no rule answers would be ignored with no record it ever
    // arrived; v0 proves the coarse slice (a bare-shape rule, or a syntactic
    // complement pair) and fails closed otherwise.

    private fun checkTransients() {
        if (model.transients.isEmpty()) return

        // V17: no property anywhere may be typed to a transient shape
        fun checkMembers(owner: String, members: List<Member>) {
            for (m in members) {
                val (mName, type) = when (m) {
                    is StoredProp -> m.name to m.type
                    is DerivedProp -> m.name to m.type
                    else -> continue
                }
                val rel = type as? RelType ?: continue
                if (rel.shape in model.transients)
                    diags.add(Diagnostic("V17", "'$owner.$mName' references transient act '${rel.shape}' — " +
                        "a transient act exists only within its own commit's transaction, so nothing durable " +
                        "may point at it; copy the fields the outcome needs instead"))
            }
        }
        model.shapes.forEach { (name, s) -> checkMembers(name, s.members) }
        model.refinements.forEach { (name, r) -> checkMembers(name, r.members) }

        // V17: the transient shape's name may appear in no expression — even
        // from its own refinement family, that is a read across acts
        forEachSpecExpr { site, e ->
            val mentioned = when (e) {
                is PathExpr -> e.root.takeIf { it in model.transients }
                is ExistsExpr -> e.shape?.takeIf { it in model.transients }
                    ?: (e.collection?.bindings?.firstOrNull()?.source as? PathExpr)?.root?.takeIf { it in model.transients }
                is SingularFor -> e.shape.takeIf { it in model.transients }
                is ShapeForSource -> e.shape.takeIf { it in model.transients }
                is IsExpr -> e.refinement?.takeIf { (model.baseOf(it) ?: it) in model.transients }
                else -> null
            } ?: return@forEachSpecExpr
            diags.add(Diagnostic("V17", "$site reads transient act '$mentioned' — " +
                "the act is not kept after its commit, so no expression may query it; " +
                "read the durable outcomes its rules produced instead"))
        }

        // V17: only the boundary commits a transient act — a rule-created
        // instance would persist, contradicting the marker
        for (rule in model.rules.values) {
            rule.body.filterIsInstance<Creation>().filter { it.shape in model.transients }.forEach {
                diags.add(Diagnostic("V17", "rule '${rule.name}' creates transient act '${it.shape}' — " +
                    "a transient act is an input at the boundary, never a rule's effect"))
            }
        }

        // V17: rules over a transient act fire only at its one commit
        for (rule in model.rules.values) {
            val base = subjectScope(rule)?.let { model.baseOf(it) } ?: continue
            if (base !in model.transients) continue
            if (rule.leaving)
                diags.add(Diagnostic("V17", "rule '${rule.name}' — 'when leaving' over transient act '$base' " +
                    "is meaningless: the act's refinements are evaluated exactly once, at its commit; there are no exits"))
            if (rule.preposition == "after" || rule.triggers.any { it != "commit" })
                diags.add(Diagnostic("V17", "rule '${rule.name}' — transient act '$base' is gone before any " +
                    "'after commit' firing or tick runs; handle it at its commit, and hang asynchronous work " +
                    "off a durable intent the handling rule creates"))
        }

        // V18: every request gets a response — either a rule fires, or the
        // boundary refuses. A `never` over the act is an answer too: the
        // refused configuration gets its response as the commit's refusal,
        // with nothing kept (README §4; §21).
        for (t in model.transients) {
            val handlers = model.rules.values.filter {
                !it.leaving && subjectScope(it)?.let { s -> model.baseOf(s) } == t
            }
            val refusals = model.nevers.filter { model.baseOfExpr(it.target) == t }
            val answers: List<List<Expr>?> =
                handlers.map { refPredicateConjuncts(it.condition) } +
                    refusals.map { refPredicateConjuncts(it.target) }
            val bare =
                handlers.any { (it.condition as? RefName)?.let { c -> c.name == t && c.where == null } == true } ||
                    refusals.any { (it.target as? RefName)?.let { c -> c.name == t && c.where == null } == true }
            val complementPair = answers.any { a ->
                answers.any { b ->
                    a !== b && a?.singleOrNull()?.let { p ->
                        b?.singleOrNull()?.let { q -> p == NotExpr(q) || q == NotExpr(p) }
                    } == true
                }
            }
            if (!bare && !complementPair)
                diags.add(Diagnostic("V18", "a '$t' can arrive that nothing provably answers. '$t' is transient — " +
                    "it is not kept after its commit — so an unanswered '$t' would be ignored, and no record " +
                    "that it arrived would exist anywhere. v0 proves coverage for an answer on the bare shape " +
                    "or a complementary pair (P / not P), where an answer is a rule's condition or a `never` " +
                    "over the act (the boundary refusal is the response); add a catch-all rule, refuse the " +
                    "remainder with a `never`, or restructure the partitions as complements (per-reason " +
                    "refusals go in one complement rule with a conditional reason value)"))
        }
    }

    // ── V23–V28: delete (OQ37) ───────────────────────────────────────────────
    //
    // Delete is instance-granularity mutation whose legality is derived from
    // what the spec proves *using* the instance's existence — never banned by
    // category. The boundary principle (the C1–C11 catalog,
    // working-docs/investigate-delete.md): a delete errors when it can break
    // something the spec proves or declares — never merely because state
    // changes; recalculation is drift, the design working. The disjointness
    // prover is deliberately the shared refinement-overlap one — syntactic
    // conjunct complements — and nothing finer (ruled conservative,
    // 2026-08-14): no window arithmetic, no lifetime analysis. The accepted
    // false positive's discharge is restructure, never a signature (guard
    // re-arming is not `tolerates`-signable — ruled, 2026-08-14).

    /** The conjuncts provably constraining which instances a delete site can
     *  target: for `delete this`, the deleting rule's own condition; for a
     *  path target, the predicates of every refinement the condition asserts
     *  of that path (`<path> is R` conjuncts). Empty means "any instance". */
    private fun deleteScopeConjuncts(site: DeleteSite): List<Expr> {
        val t = site.stmt.target
        if (t.root == "this" && t.segs.isEmpty()) return conditionConjuncts(site.rule.condition)
        return conditionConjuncts(site.rule.condition).flatMap { conj ->
            val isE = conj as? IsExpr ?: return@flatMap emptyList<Expr>()
            if (isE.kind != "refinement" || isE.subject != t) return@flatMap emptyList<Expr>()
            isE.refinement?.let { refPredicateConjuncts(RefName(it)) } ?: emptyList()
        }
    }

    /** The shared prover, aimed at deletion: the delete scope provably excludes
     *  membership in [name] when a scope conjunct is the syntactic complement
     *  of one of [name]'s predicate conjuncts. A base shape has no conjuncts,
     *  so nothing excludes it — fail closed. */
    private fun deleteExcludes(site: DeleteSite, name: String): Boolean {
        val target = refPredicateConjuncts(RefName(name)) ?: return false
        if (target.isEmpty()) return false
        val scope = deleteScopeConjuncts(site)
        // the explicit spelling: a `not (<target path> is Name)` conjunct on the condition
        val notIs = conditionConjuncts(site.rule.condition)
            .any { it == NotExpr(IsExpr(site.stmt.target, "refinement", name)) }
        return notIs || scope.any { p -> target.any { q -> p == NotExpr(q) || q == NotExpr(p) } }
    }

    /** Does this rule's body delete its own subject? Deleting `this` leaves
     *  every trigger state — the structural disarm (V2/V16). */
    private fun deletesSubject(rule: RuleDecl): Boolean =
        rule.body.any { it is DeleteStmt && it.target == PathExpr("this") }

    private fun siteLabel(s: DeleteSite) =
        "rule '${s.rule.name}' deletes ${s.shape} ('delete ${Printer.expr(s.stmt.target)}')"

    // V23 — the statement: literal static to-one path; a shape known to the
    // spec, never a transient act; one deleter per instance per commit (two
    // deleters of one shape at one commit is the coincidence error); and a
    // commit never both writes a field of an instance and deletes it — there
    // is no business sentence "change it and also remove it, at once" (fail
    // closed, pending a use case).
    private fun checkDeleteStatements() {
        for (rule in model.rules.values) {
            val scope = subjectScope(rule) ?: continue
            for (stmt in rule.body.filterIsInstance<DeleteStmt>()) {
                val t = stmt.target
                val shape: String? =
                    if (t.root == "this" && t.segs.isEmpty()) model.baseOf(scope)
                    else {
                        resolvePath(t, scope) // F1 on unknown members
                        model.pathElementShape(t, scope)?.let { model.baseOf(it) }
                    }
                if (shape == null) {
                    if (!(t.root == "this" && t.segs.isEmpty()))
                        diags.add(Diagnostic("V23", "rule '${rule.name}' — 'delete ${Printer.expr(t)}' " +
                            "does not resolve to a single instance: the target is a literal static " +
                            "to-one path (OQ37; no fan-out deletes in v0 — a per-member delete is its " +
                            "own rule on the shape that owns the instances)"))
                    continue
                }
                if (shape in model.transients)
                    diags.add(Diagnostic("V23", "rule '${rule.name}' deletes transient act '$shape' — " +
                        "a transient act is an input to the state, not a member of it: there is " +
                        "nothing to delete (README §4)"))
                // a collection anywhere in the target is a fan-out — refused
                if (pathHasCollectionHop(t, scope))
                    diags.add(Diagnostic("V23", "rule '${rule.name}' — 'delete ${Printer.expr(t)}' " +
                        "traverses a collection: no fan-out deletes in v0 — a per-member delete is " +
                        "its own rule on the shape that owns the instances (OQ37)"))
                // same body: the same instance deleted twice, or written and deleted
                val dupes = rule.body.filterIsInstance<DeleteStmt>().count { it.target == t }
                if (dupes > 1)
                    diags.add(Diagnostic("V23", "rule '${rule.name}' deletes ${Printer.expr(t)} twice in one body"))
                for (a in rule.body.filterIsInstance<Assignment>()) {
                    val writesDeleted =
                        if (t.root == "this" && t.segs.isEmpty())
                            (a.target.root == "this" && a.target.segs.size == 1) ||
                                (a.target.root != "this" && a.target.segs.isEmpty())
                        else a.target.root == t.root &&
                            a.target.segs.dropLast(1).map { it.name } == t.segs.map { it.name } &&
                            a.target.segs.size == t.segs.size + 1
                    if (writesDeleted)
                        diags.add(Diagnostic("V23", "rule '${rule.name}' both writes " +
                            "${Printer.expr(a.target)} and deletes ${Printer.expr(t)} in one commit — " +
                            "there is no business sentence \"change it and also remove it, at once\" (OQ37)"))
                }
            }
        }
        // cross-rule: one deleter per instance per commit, and no co-firing
        // writer of a shape a sibling deletes — both fail closed at shape
        // granularity (the coarse slice of the instance-level rulings)
        val sites = model.deleteSites
        for (i in sites.indices) for (j in i + 1 until sites.size) {
            val (a, b) = sites[i] to sites[j]
            if (a.rule === b.rule || a.shape != b.shape) continue
            if (!provablyDisjoint(a.rule, b.rule) && canCoFire(a.rule, b.rule))
                diags.add(Diagnostic("V23", "rules '${a.rule.name}' and '${b.rule.name}' can both delete " +
                    "a ${a.shape} at one commit and their triggers are not provably disjoint — " +
                    "one deleter per instance per commit (OQ37)"))
        }
        for (site in sites) {
            for (w in writes) {
                if (w.owner != site.shape || w.rule === site.rule) continue
                if (!provablyDisjoint(w.rule, site.rule) && canCoFire(w.rule, site.rule))
                    diags.add(Diagnostic("V23", "'${w.rule.name}' writes ${w.owner}.${w.member.name} and " +
                        "sibling '${site.rule.name}' deletes ${site.shape} — a commit that both writes a " +
                        "field of an instance and deletes it is refused, fail closed (OQ37); " +
                        "condition one rule away from the other"))
            }
        }
    }

    private fun pathHasCollectionHop(t: PathExpr, scope: String): Boolean {
        val steps = if (t.root == "this") t.segs.map { it.name } else listOf(t.root) + t.segs.map { it.name }
        var current: String? = scope
        for (name in steps) {
            val m = current?.let { model.membersOf(it)[name] } ?: return false
            if (m.type is VType.Coll || m.type is VType.CollS) return true
            current = m.type.instanceShape()
        }
        return false
    }

    // V24 — referential completeness (cascade, OQ37): deleting an instance
    // that required `one` references point at is illegal unless every referrer
    // is resolved — deleted in the same commit (the cascade, spelled), the
    // reference declared `? initially required` (absorbing — it goes absent),
    // or restructured to copies. Never transitive magic: each hop is its own
    // visible decision (README §8's freeze-depth precedent).
    private fun checkReferentialCompleteness() {
        for (site in model.deleteSites) {
            val cascaded = site.rule.body.filterIsInstance<DeleteStmt>()
                .mapNotNull { s -> model.deleteSites.find { it.rule === site.rule && it.stmt === s }?.shape }
                .toSet()
            for ((refName, referrer) in model.shapes) {
                if (refName in model.transients) continue // an act reads it within the commit — the last reader
                for (f in referrer.members.filterIsInstance<StoredProp>()) {
                    val t = f.type as? RelType ?: continue
                    if (t.shape != site.shape || t.many || t.optional) continue
                    if (refName in cascaded) continue // resolved at the same commit (coarse C4)
                    diags.add(Diagnostic("V24", "${siteLabel(site)}, which strands " +
                        "'$refName.${f.name}: one ${site.shape}' — deleting an instance required " +
                        "references point at needs every referrer resolved: delete the referrer in the " +
                        "same commit, declare the reference '${f.name}: one ${site.shape}? initially " +
                        "required' (it then absorbs the deletion by going absent), or copy the fields " +
                        "that must survive (OQ37)"))
                }
            }
        }
    }

    // V25 — existence is spent in proofs (OQ37's genuinely new check). Two
    // v0 slices: (a) guard witnesses — a `not exists W` conjunct on any rule's
    // condition makes W's records load-bearing: deleting one re-arms the guard
    // and the rule re-fires (the double-apply hazard); (b) singularity proofs —
    // a `(R for ...)` reference relies on the member the guard maintains;
    // deleting it mid-episode strands the reference. Discharge is the shared
    // disjointness prover only; not `tolerates`-signable (ruled): intentional
    // re-triggering is reversal-as-data, cleanup is provable disjointness or
    // OQ27 retention.
    private fun checkExistenceDependency() {
        if (model.deleteSites.isEmpty()) return
        for (rule in model.rules.values) {
            for (conj in conditionConjuncts(rule.condition)) {
                val inner = (conj as? NotExpr)?.inner as? ExistsExpr ?: continue
                val witness = inner.shape
                    ?: (inner.collection?.bindings?.firstOrNull()?.source as? PathExpr)
                        ?.takeIf { it.segs.isEmpty() && (it.root in model.shapes || it.root in model.refinements) }?.root
                    ?: continue
                val base = model.baseOf(witness) ?: continue
                for (site in model.deleteSites) {
                    if (site.shape != base) continue
                    if (deleteExcludes(site, witness)) continue
                    diags.add(Diagnostic("V25", "${siteLabel(site)}, whose existence '${rule.name}'s " +
                        "guard reads ('not exists $witness ...') — the delete re-arms the guard and the " +
                        "rule applies again. Restructure the guard onto a field witness, write the " +
                        "disjointness into the predicates (the shared refinement-overlap prover, nothing " +
                        "finer — OQ37, ruled conservative), or — for intentional re-triggering — model " +
                        "the reversal as data (README §22); this hazard is not `tolerates`-signable"))
                }
            }
        }
        forEachSpecExpr { spot, e ->
            if (e !is SingularFor) return@forEachSpecExpr
            val base = model.baseOf(e.shape) ?: return@forEachSpecExpr
            for (site in model.deleteSites) {
                if (site.shape != base) continue
                if (e.shape != base && deleteExcludes(site, e.shape)) continue
                diags.add(Diagnostic("V25", "${siteLabel(site)}, and $spot relies on " +
                    "'(${e.shape} for ...)' being present — the delete can strand the singular " +
                    "reference mid-episode. Condition the deleter on the complement of " +
                    "'${e.shape}' (complementary predicates, which the shared prover clears), " +
                    "or restructure (OQ37)"))
            }
        }
    }

    // V26 — deleters join every invariant's inductive proof (OQ37): deleting
    // an instance of the `never`'s own base only shrinks the forbidden set —
    // safe; deleting an instance of any *other* shape the predicate consults
    // can flip membership either way, and v0 cannot prove the invariant holds
    // across it — fail closed.
    private fun checkNeverDeleters() {
        if (model.deleteSites.isEmpty()) return
        for ((i, n) in model.nevers.withIndex()) {
            val nBase = model.baseOfExpr(n.target)
            val s = model.summaryOfRefExpr(n.target)
            val consulted = s.touchedShapes().mapNotNull { model.baseOf(it) }.toSet() - setOfNotNull(nBase)
            for (site in model.deleteSites) {
                if (site.shape !in consulted && !s.opaque) continue
                diags.add(Diagnostic("V26", "${siteLabel(site)}, which never #${i + 1} " +
                    "(over $nBase) consults — the delete can end a transaction violating the " +
                    "invariant, and v0 cannot prove otherwise: condition the deleting act, or " +
                    "resolve the invariant's other side in the same commit (OQ37)"))
            }
        }
    }

    // V27 — the deletion gate (OQ37-R1/R9): `undeletable` scopes deletion
    // permission to a state exactly as `frozen` scopes write permission — the
    // same fail-closed disjointness proof, the same fix-it idiom (partition
    // the deleter onto the deletable subset; refusal lands as data).
    private fun checkUndeletable() {
        for ((refName, r) in model.refinements) {
            if (r.members.none { it is UndeletableClause }) continue
            val base = model.baseOf(refName) ?: continue
            val guarded = model.deleteSites.filter { it.shape == base }
            for (site in guarded) {
                if (deleteExcludes(site, refName)) continue
                diags.add(Diagnostic("V27", "${siteLabel(site)}, undeletable while a member of " +
                    "'$refName' — the trigger does not provably exclude membership; partition the " +
                    "deleter onto the deletable subset so refusal lands as data (OQ37; the " +
                    "README §8 \"Frozen fields\" idiom, aimed at existence)"))
            }
            if (guarded.isEmpty() && model.deleteSites.isNotEmpty())
                diags.add(Diagnostic("A2", "'$refName' declares `undeletable` but no rule deletes " +
                    "'$base' — the gate refuses nothing (dead machinery)", advisory = true))
        }
    }

    // V28 — transaction stranding, the V17 mirror (OQ37): nothing after the
    // deleting transaction's close may read the deleted instance. A `when
    // leaving` rule with an `after commit` boundary or a tick backstop can be
    // handed a deletion's leaver — a subject that does not survive to its
    // firing. Durable reactions hang off the outcome record the deleting
    // commit produced.
    private fun checkDeleteStranding() {
        if (model.deleteSites.isEmpty()) return
        for (rule in model.rules.values) {
            if (!rule.leaving) continue
            if (!(rule.preposition == "after" || rule.triggers.any { it != "commit" })) continue
            val base = model.baseOfExpr(rule.condition) ?: continue
            for (site in model.deleteSites) {
                if (site.shape != base) continue
                val left = (rule.condition as? RefName)?.name
                if (left != null && left != base && deleteExcludes(site, left)) continue
                diags.add(Diagnostic("V28", "${siteLabel(site)}, and '${rule.name}' reads the " +
                    "leaver ${if (rule.preposition == "after") "after commit" else "at a tick"} — a " +
                    "deleted instance does not survive the transaction boundary the rule declares " +
                    "(OQ37; the V17 mirror). React on-commit as a last reader, or hang the durable " +
                    "reaction off the outcome record the deleting commit produces"))
            }
        }
    }

    // A2 (advisory) — dead optionality (OQ37-R10's converse diagnostic): an
    // `? initially required` field nothing can ever make absent — no deleter
    // of its target, no writer of the field — is `one X` wearing a costume.
    private fun checkDeadOptionality() {
        for ((shapeName, shape) in model.shapes) {
            for (m in shape.members.filterIsInstance<StoredProp>()) {
                if (!m.initiallyRequired) continue
                val rel = m.type as? RelType
                val targetDeletable = rel?.shape?.let { it in model.deletedShapes } == true
                val written = writes.any { it.owner == shapeName && it.member.name == m.name }
                if (!targetDeletable && !written)
                    diags.add(Diagnostic("A2", "'$shapeName.${m.name}' is '? initially required' but " +
                        "nothing can make it absent — no rule deletes " +
                        (rel?.shape?.let { "'$it'" } ?: "its target") +
                        " and no rule assigns the field; did you mean the plain required form? (OQ37)",
                        advisory = true))
            }
        }
    }

    // ── A4 (advisory): drift-exposed act partitions ──────────────────────────
    //
    // An act is data — it persists — so a refinement of an act shape over
    // mutable state is re-evaluated at every later change to that state: an
    // act applied long ago drifts into the refused side when the state flips
    // (a spurious firing per flip) and drifts back when it flips again (a
    // stale re-fire). The signature: a rule triggered by a partition of an
    // exposed act on an `is <Refinement>` atom, whose body does not disarm
    // its own trigger. Legitimate drift-reactive rules (the compensation
    // pattern, windowed sweeps) pass, because they disarm — the disarm proof
    // doubles as the anchor. Worked exhibit: examples/partition-drift/.

    private fun checkDriftExposedPartitions() {
        for (rule in model.rules.values) {
            if (rule.leaving) continue
            val scope = subjectScope(rule) ?: continue
            val base = model.baseOf(scope) ?: continue
            if (base !in model.exposed) continue
            if (base in model.transients) continue // evaluated once, at the act's commit: drift cannot exist
            val conjuncts = refPredicateConjuncts(rule.condition) ?: continue
            val stateAtom = conjuncts.firstOrNull { c ->
                val inner = (c as? NotExpr)?.inner ?: c
                inner is IsExpr && inner.kind == "refinement"
            } ?: continue
            if (guardAtoms(rule).any { disarmed(rule, it) }) continue
            val atom = Printer.expr(stateAtom)
            diags.add(Diagnostic("A4", "rule '${rule.name}' partitions act '$base' on mutable state " +
                "($atom) with no handled-anchor — acts persist, so every later flip of that state " +
                "re-partitions every '$base' ever committed: spurious firings on entry, stale re-fires " +
                "on return. Mark the act `expose transient` (the partition then evaluates once, at its " +
                "commit), anchor the partition with the outcome evidence the rule produces (scope it to " +
                "unhandled acts), or accept per-flip re-firing deliberately (examples/partition-drift/)",
                advisory = true))
        }
    }

    // ── A5 (advisory): contention width (OQ40) ───────────────────────────────
    //
    // The serialization-domain derivation (Domains.kt) attributes every read
    // and write of every envelope to a queue key; where a read has no path
    // back to any key, the domain honestly widens to the whole shape — every
    // commit touching that shape joins a single queue. "Unintentionally
    // single-threaded" is as legitimate a failure as a wrong value, so the
    // width demands a stated policy: correlate the read (a model fact — a
    // relationship the model was missing), move the rule to a schedule (the
    // read then runs once per tick instead of inside every commit), or
    // declare `tolerates contention` on the declaration carrying the read.
    // Severity ruling 2026-08-18: advisory, not a compile error — the failure
    // a wide domain causes is throughput, not a wrong value. A tolerance
    // covering no width is dead and flagged, like an impossible `reordering`.

    private fun checkContention() {
        val byDecl = DomainAnalysis(model).commitWidenings()
        for ((decl, widenings) in byDecl) {
            for (w in widenings.filterNot { it.tolerated }.distinctBy { it.shape }) {
                val cadence = if (decl.startsWith("rule "))
                    "\n    - move the rule to a schedule (`on <Schedule>`) — the read then runs once per tick instead of inside every commit, or"
                else ""
                diags.add(Diagnostic("A5", "every commit touching ${w.shape} joins a single queue. " +
                    "${w.cause.replaceFirstChar { it.uppercase() }} ($decl), so any two such commits conflict. " +
                    "State the policy:\n    - correlate the read (relate ${w.shape} to the shape that partitions it), or" +
                    cadence +
                    "\n    - declare `tolerates contention` on the $decl (OQ40)",
                    advisory = true))
            }
        }
        // dead tolerance: a declared `tolerates contention` covering no width
        val tolerantDecls = buildList {
            model.rules.values.filter { it.toleratesContention }.forEach { add("rule ${it.name}") }
            model.nevers.forEachIndexed { i, n -> if (n.toleratesContention) add("never #${i + 1}") }
        }
        for (decl in tolerantDecls) {
            if (byDecl[decl].isNullOrEmpty())
                diags.add(Diagnostic("A5", "$decl declares `tolerates contention` but causes no " +
                    "contention beyond its queue keys — remove the tolerance (OQ40)", advisory = true))
        }
    }

    /** Walks every expression in the spec with a human-readable site label. */
    private fun forEachSpecExpr(visit: (site: String, e: Expr) -> Unit) {
        fun walk(site: String, e: Expr?) {
            e ?: return
            visit(site, e)
            when (e) {
                is UnaryMinus -> walk(site, e.inner)
                is Binary -> { walk(site, e.left); walk(site, e.right) }
                is NotExpr -> walk(site, e.inner)
                is IfExpr -> { walk(site, e.condition); walk(site, e.thenExpr); walk(site, e.elseExpr) }
                is IsExpr -> walk(site, e.subject)
                is ExistsExpr -> {
                    walk(site, e.forExpr)
                    e.collection?.let { coll ->
                        coll.bindings.forEach { walk(site, it.source) }
                        walk(site, coll.where)
                    }
                }
                is AggCall -> {
                    e.collection.bindings.forEach { walk(site, it.source) }
                    walk(site, e.collection.where)
                }
                is FunCall -> e.args.forEach { walk(site, it) }
                is SingularFor -> walk(site, e.forExpr)
                is ShapeForSource -> walk(site, e.forExpr)
                is Access -> walk(site, e.target)
                is SetExpr -> {
                    e.collection.bindings.forEach { walk(site, it.source) }
                    walk(site, e.collection.where)
                }
                else -> {}
            }
        }
        fun walkRef(site: String, e: RefExpr) {
            when (e) {
                is RefName -> walk(site, e.where)
                is RefAnd -> { walkRef(site, e.left); walkRef(site, e.right) }
                is RefOr -> { walkRef(site, e.left); walkRef(site, e.right) }
                is RefNot -> walkRef(site, e.inner)
            }
        }
        for ((name, shape) in model.shapes) shape.members.forEach { m ->
            when (m) {
                is StoredProp -> walk("shape '$name'", m.initially)
                is DerivedProp -> walk("shape '$name'", m.expr)
                else -> {}
            }
        }
        for ((name, ref) in model.refinements) {
            walkRef("shape '$name'", ref.expr)
            ref.members.forEach { m -> if (m is DerivedProp) walk("shape '$name'", m.expr) }
        }
        for (rule in model.rules.values) {
            walkRef("rule '${rule.name}'", rule.condition)
            rule.body.forEach { item ->
                when (item) {
                    is Assignment -> walk("rule '${rule.name}'", item.value)
                    is Creation -> {
                        walk("rule '${rule.name}'", item.forExpr)
                        item.fields.forEach { walk("rule '${rule.name}'", it.value) }
                    }
                    else -> {}
                }
            }
        }
        model.nevers.forEach { walkRef("never declaration", it.target) }
    }
}
