package velle

/**
 * The v0 validator: the coarse, fail-closed slices of checks.md.
 *
 * Where checks.md calls for a prover (predicate disjointness, never-induction,
 * confluence), v0 proves the syntactic cases and fails closed on the rest —
 * calibration against realistic specs is OQ14–16's deferred work. Not yet
 * implemented (tracked in checks.md): V11 branch-sensitive narrowing, V12
 * at-most-one proofs beyond rejection, V14 descent certificates, and the
 * advisory A-series.
 */
class Validator(private val model: Model) {

    private val diags = model.diagnostics

    companion object {
        fun validate(decls: List<Decl>): List<Diagnostic> {
            val model = Model(decls)
            Validator(model).run()
            return model.diagnostics.distinct()
        }

        fun validate(source: String): List<Diagnostic> = validate(Parser.parse(source))
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
        return model.shapes[shape]?.members?.filterIsInstance<StoredProp>()
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
                    if (s !in model.shapes) diags.add(Diagnostic("F1", "exists names unknown shape '$s'"))
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
                if (e.shape !in model.shapes) diags.add(Diagnostic("F1", "unknown shape '${e.shape}'"))
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

    private data class Write(val rule: RuleDecl, val owner: String, val member: MemberInfo, val target: PathExpr)

    private fun resolveWrite(rule: RuleDecl, scope: String, a: Assignment): Write? {
        var current: String? = if (a.target.root == "this") scope else {
            val m = model.membersOf(scope)[a.target.root]
            if (m == null) {
                diags.add(Diagnostic("F1", "rule '${rule.name}' assigns through unknown member '${a.target.root}'"))
                return null
            }
            if (a.target.segs.isEmpty())
                return Write(rule, model.baseOf(scope) ?: scope, m, a.target)
            m.type.instanceShape()
        }
        for ((i, seg) in a.target.segs.withIndex()) {
            val sc = current ?: return null
            val m = model.membersOf(sc)[seg.name]
            if (m == null) {
                diags.add(Diagnostic("F1", "rule '${rule.name}' assigns through unknown member '${seg.name}' of '$sc'"))
                return null
            }
            if (i == a.target.segs.lastIndex) return Write(rule, model.baseOf(sc) ?: sc, m, a.target)
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
        model.exposed.keys.forEach { kinds.add(CommitKind.Creates(it, source = "expose $it")) }
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
}
