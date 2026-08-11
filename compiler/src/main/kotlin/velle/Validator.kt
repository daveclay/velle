package velle

/**
 * The v0 validator: the coarse, fail-closed slices of checks.md.
 *
 * Where checks.md calls for a prover (predicate disjointness, never-induction,
 * confluence), v0 proves the syntactic cases and fails closed on the rest —
 * calibration against realistic specs is OQ14–16's deferred work. Not yet
 * implemented (tracked in checks.md): V11 branch-sensitive narrowing, V12
 * at-most-one proofs beyond the refinement slice (base-shape to-one-inverse
 * proofs stay runtime-enforced), V14 descent certificates, and the A-series
 * beyond A4 (drift-exposed partitions — advisory, via Validator.advisories).
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
        checkRefinementDeclarations()
        model.rules.values.forEach { checkRuleNamesAndBody(it) }
        checkOneWriter()          // V1 (+ same-commit read-write, V15 coarse)
        model.rules.values.forEach { checkBoundaryAndDisarm(it) }  // V2, V4, V7
        model.rules.values.forEach { checkReachability(it) }       // V3
        checkFreezes()            // V5
        checkCaptures()           // V6
        checkFolds()              // V8
        checkNevers()             // V10
        checkDerivedCycles()      // V14 (stratification; certificates TODO)
        checkQuiescence()         // V16
        checkSingularProofs()     // V12 (refinement slice)
        checkTransients()         // V17 isolation, V18 totality
        checkDriftExposedPartitions() // A4 (advisory)
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
                    if (m.tolerates != null && m.tolerates !in setOf("duplication", "reordering"))
                        diags.add(Diagnostic("F3", "'$name.${m.name}' — fields tolerate 'duplication' or 'reordering', not '${m.tolerates}'"))
                }
                is DerivedProp -> checkExpr(m.expr, name)
                else -> {}
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
            ThenMarker -> {}
        }
    }

    private fun checkAssignment(rule: RuleDecl, scope: String, a: Assignment) {
        val resolved = resolveWrite(rule, scope, a) ?: return
        if (!resolved.member.stored) {
            val kind = when {
                resolved.member.timestamp -> "a timestamp field (commit metadata)"
                resolved.member.name == "id" -> "'id'"
                resolved.member.captured -> "a captured property"
                else -> "a derived property"
            }
            diags.add(Diagnostic("F3", "rule '${rule.name}' assigns ${resolved.owner}.${resolved.member.name} — $kind is never assignable"))
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
            is AggCall -> checkCollection(e.collection, scope, sumField = e.field)
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
            else -> {}
        }
    }

    private fun checkCollection(c: CollectionExpr, scope: String, sumField: String? = null) {
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
                             val value: Expr)

    private fun resolveWrite(rule: RuleDecl, scope: String, a: Assignment): Write? {
        var current: String? = if (a.target.root == "this") scope else {
            val m = model.membersOf(scope)[a.target.root]
            if (m == null) {
                diags.add(Diagnostic("F1", "rule '${rule.name}' assigns through unknown member '${a.target.root}'"))
                return null
            }
            if (a.target.segs.isEmpty())
                return Write(rule, model.baseOf(scope) ?: scope, m, a.target, a.value)
            m.type.instanceShape()
        }
        for ((i, seg) in a.target.segs.withIndex()) {
            val sc = current ?: return null
            val m = model.membersOf(sc)[seg.name]
            if (m == null) {
                diags.add(Diagnostic("F1", "rule '${rule.name}' assigns through unknown member '${seg.name}' of '$sc'"))
                return null
            }
            if (i == a.target.segs.lastIndex) return Write(rule, model.baseOf(sc) ?: sc, m, a.target, a.value)
            current = m.type.instanceShape()
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
                if (!provablyDisjoint(a.rule, b.rule))
                    diags.add(Diagnostic("V1", "rules '${a.rule.name}' and '${b.rule.name}' both assign " +
                        "${field.first}.${field.second} and their triggers are not provably disjoint"))
            }
        }
        // coarse V15: same-commit siblings must not read what a sibling writes
        val commitRules = model.rules.values.filter { it.preposition != "after" && firesOnCommit(it) }
        for (a in commitRules) for (b in commitRules) {
            if (a === b || provablyDisjoint(a, b) || !canShareCommit(a, b)) continue
            val aWrites = writes.filter { it.rule === a }.map { it.owner to it.member.name }.toSet()
            val bReads = ruleReads(b).fields
            (aWrites intersect bReads).forEach { (s, f) ->
                diags.add(Diagnostic("V15", "'${a.rule()}' writes $s.$f, which sibling '${b.rule()}' reads — " +
                    "outcome depends on unstated order; state the intent (OQ16)"))
            }
        }
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
        if (atoms.isNotEmpty() && (rule.preposition == "after" || schedules.isNotEmpty())) {
            if (atoms.none { disarmed(rule, it) }) {
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
        for (rule in model.rules.values) {
            rule.body.filterIsInstance<Creation>().forEach {
                kinds.add(CommitKind.Creates(it.shape, source = "rule ${rule.name}", byRule = rule))
            }
            writes.filter { it.rule === rule }.forEach {
                kinds.add(CommitKind.Assigns(it.owner, it.member.name, byRule = rule))
            }
        }
        kinds
    }

    private sealed interface CommitKind {
        val byRule: RuleDecl?
        data class Creates(val shape: String, val source: String, override val byRule: RuleDecl? = null) : CommitKind
        data class Assigns(val shape: String, val field: String, override val byRule: RuleDecl? = null) : CommitKind
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

    /** Does this commit kind possibly affect the rule's condition? */
    private fun affects(k: CommitKind, rule: RuleDecl): Boolean {
        val base = model.baseOfExpr(rule.condition) ?: return false
        // a transient act's refinements are evaluated exactly once, at its
        // creation commit — no other commit can ever fire the rule (README §4)
        if (base in model.transients)
            return k is CommitKind.Creates && k.shape == base
        val cond = conditionSummary(rule)
        return when (k) {
            is CommitKind.Creates ->
                k.shape == base || k.shape in cond.existsShapes
            is CommitKind.Assigns ->
                (k.shape to k.field) in cond.fields
        }
    }

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
                    "(rule '${w.rule.name}') — nothing orders one tick's firings (README §19, OQ15)"))
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

    // ── V14: definition-graph stratification (certificates TODO) ─────────────

    private fun checkDerivedCycles() {
        val edges = mutableMapOf<Pair<String, String>, MutableSet<Pair<String, String>>>()
        for ((shapeName, shape) in model.shapes) {
            for (m in shape.members.filterIsInstance<DerivedProp>()) {
                val s = ReadSummary()
                model.collectExpr(m.expr, shapeName, s)
                val deps = s.derivedSeen.filterNot { it == shapeName to m.name }
                edges[shapeName to m.name] = deps.toMutableSet()
            }
        }
        val visiting = mutableSetOf<Pair<String, String>>()
        val done = mutableSetOf<Pair<String, String>>()
        fun dfs(node: Pair<String, String>, path: List<Pair<String, String>>) {
            if (node in done) return
            if (node in visiting) {
                diags.add(Diagnostic("V14", "derived-property cycle: " +
                    (path.dropWhile { it != node } + node).joinToString(" -> ") { "${it.first}.${it.second}" } +
                    " — v0 has no descent certificates yet; restructure or wait for OQ15's whitelist"))
                return
            }
            visiting.add(node)
            edges[node].orEmpty().forEach { dfs(it, path + node) }
            visiting.remove(node)
            done.add(node)
        }
        edges.keys.forEach { dfs(it, emptyList()) }
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
                val broken = cycle.any { r -> guardAtoms(r).any { disarmed(r, it) } }
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
     * exists ...` (facts are monotone — no delete primitive), and `not <flag>`
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
                inner is ExistsExpr -> {}
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
