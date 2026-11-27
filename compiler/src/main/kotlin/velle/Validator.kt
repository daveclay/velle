package velle

import velle.validation.*

/**
 * The v0 validator: the coarse, fail-closed slices of checks.md.
 *
 * Where checks.md calls for a prover (predicate disjointness, never-induction,
 * confluence), v0 proves the syntactic cases and fails closed on the rest —
 * calibration against realistic specs is OQ16's deferred work. Not yet
 * implemented (tracked in checks.md): V12 at-most-one proofs beyond the
 * refinement slice (base-shape to-one-inverse proofs stay runtime-enforced),
 * and of the A-series A1 and A3; A2 and A4 to A10 are advisory, via
 * Validator.advisories.
 *
 * This class holds what the checks share: the model, the diagnostics they add
 * to, the state of the expression being checked, and the analyses worked out
 * once and read by several checks. Each check lives in its own file under
 * `velle/validation/`, as extension functions on this class, and [run] calls
 * them in order.
 */
class Validator(internal val model: Model) {
    internal val diagnostics = model.diagnostics

    companion object {
        private fun allDiagnostics(declarations: List<Decl>): List<Diagnostic> {
            val model = Model(declarations)
            Validator(model).run()
            return model.diagnostics.distinct()
        }

        /** The required, fail-closed diagnostics (checks.md F/V series). */
        fun validate(declarations: List<Decl>): List<Diagnostic> =
            allDiagnostics(declarations).filterNot { diagnostic -> diagnostic.advisory }

        fun validate(source: String): List<Diagnostic> = validate(Parser.parse(source))

        /** The advisory findings (checks.md A-series): guidance, never blocking. */
        fun advisories(source: String): List<Diagnostic> =
            allDiagnostics(Parser.parse(source)).filter { diagnostic -> diagnostic.advisory }
    }

    fun run() {
        checkTypeNames()          // F1/E1: every name in type position is a builtin, a shape, or a registered type
        checkShapeDeclarations()
        checkRelationshipCoherence() // V19
        checkRefinementDeclarations()
        model.rules.values.forEach { rule -> checkRuleNamesAndBody(rule) }
        checkOneWriter()          // V1 (+ same-commit read-write, V15 coarse)
        model.rules.values.forEach { rule -> checkBoundaryAndDisarm(rule) }  // V2, V4, V7
        model.rules.values.forEach { rule -> checkReachability(rule) }       // V3
        checkRuleActors()         // V31 (Reference §22, "The actor": a rule's `by` typed by reachability)
        checkVisibility()         // V32 (Reference §22: every readable shape declares its visibility once the spec has authorization)
        checkFreezes()            // V5
        checkCaptures()           // V6
        checkFolds()              // V8
        checkNevers()             // V10
        checkTransientFields()    // V37 (Reference §4, "Transient fields of a kept act")
        checkWellFoundedness()    // V14 (stratify, then certify)
        checkStartingValueCycles() // V14 (two `initially` values that read each other)
        checkTransitionInterference() // V15 (third leg)
        checkQuiescence()         // V16
        checkSingularProofs()     // V12 (refinement slice)
        checkTransients()         // V17 isolation, V18 totality
        checkClosures()           // V22 legality + V1's closure self-pair extension
        checkDeleteStatements()   // V23 (OQ37: statement legality, one deleter, write-and-delete)
        checkReferentialCompleteness() // V24 (OQ37: cascade / absorb / copy)
        checkExistenceDependency()     // V25 (OQ37: guard witnesses, singularity proofs)
        checkNeverDeleters()           // V26 (OQ37: deleters join `never` induction)
        checkUncalledCreations()       // V29 (a firing with no caller joins `never` induction)
        checkClockReadingConstraints() // V30 (a constraint that reads the clock: the clock is a writer with no caller)
        checkUndeletable()             // V27 (OQ37: the state-scoped deletion gate)
        checkDeleteStranding()         // V28 (OQ37: the V17 mirror at deletion; deletion-side forms transaction-bound)
        checkDeletionSideReferences()  // V24 (OQ37-R14: a deletion-side body keeps no reference to its subject)
        checkDeleterReachesBareLeaving() // A6 (advisory, OQ37-R18: the omission hazard)
        checkNeverMakesRequired()        // A8 (advisory): a prohibition that is a required declaration said the long way round
        checkDeadTriggerHalves()       // A2 (advisory, OQ37-R17/R19: inert `not deleted` / inert `leaving`)
        checkDriftExposedPartitions() // A4 (advisory)
        checkContention()             // A5 (advisory, OQ40)
        checkDeadOptionality()        // A2 (advisory: `? initially required` nothing can absent)
        checkInitiallyReadsOwnCollection() // A2 (advisory: an `initially` over the records that point at the record being created)
    }

    /**
     * The query API's check (Reference §22, the composed query; OQ51): [expr] is a
     * predicate over the members of [scope], a shape or refinement, checked with
     * the rules a refinement's own predicate gets. Returns the diagnostics
     * without leaving them on the model.
     */
    fun checkQueryPredicate(expr: Expr, scope: String): List<Diagnostic> {
        val diagnosticCountBefore = diagnostics.size
        checkExpr(expr, scope)
        transientFieldReads(expr, scope, scope, allowedAct = null, site = "the query")
        val queryDiagnostics = diagnostics.subList(diagnosticCountBefore, diagnostics.size).toList()
        while (diagnostics.size > diagnosticCountBefore) diagnostics.removeAt(diagnostics.size - 1)
        return queryDiagnostics
    }

    /** The query API's order datums: each an orderable member of [scope], never
     *  `id` (Reference §5, §10; OQ51 R2, R10). */
    fun checkQueryOrder(datums: List<String>, scope: String): List<Diagnostic> = buildList {
        for (datum in datums) {
            val member = model.membersOf(scope)[datum]
            when {
                member == null -> add(Diagnostic("F2", "order by '$datum' — '$scope' has no member '$datum'"))
                member.transient -> add(Diagnostic("V37", "order by '$datum' — '$scope.$datum' is transient: in the act's input, never in its record (Reference §4)"))
                datum == "id" -> add(Diagnostic("F2", "order by 'id' — identity orders nothing (Reference §5); name a datum the data carries"))
                !isOrderable(member.type) -> add(Diagnostic("F2", "order by '$datum' — $scope.$datum is not orderable (Reference §10)"))
            }
        }
    }

    // ── The rule and expression being checked ───────────────────────────────
    //
    // Set while one rule or one expression is checked, and put back after.

    /** The rule whose body is being checked; null outside one. */
    internal var ruleBeingChecked: RuleDecl? = null

    /** The actor clause of the rule being checked, for the optional-read check in [resolvePath]. */
    internal var ruleActor: RuleActor? = null

    /** What the rule being checked shows for its body through its condition's inline `where`. */
    internal var ruleNarrowing: Narrowing = Narrowing.NONE

    /** The outermost subject of the expression being checked: what `this` names
     *  (Reference §10). Set by the outermost [checkExpr] call and left alone by the
     *  nested ones, so a collection filter's element scope never captures it. */
    internal var thisScope: String? = null

    /** Each `as` name to the shape of the records it names, while the binding's `where` is checked. */
    internal val scopeByAlias = mutableMapOf<String, String>()

    /** Each `for each` name bound to numbers or scalar values (Reference §14) to the type of its values: a value with no members. */
    internal val scalarTypeByAlias = mutableMapOf<String, VType>()

    /** The names of every `for each` block around the statement being checked (Reference §14). An assignment
     *  through one of these names that V34 refuses has been answered. */
    internal var forEachNames: Set<String> = emptySet()

    /** The `for each` blocks around the statement being checked, outermost first (Reference §14). */
    internal var enclosingBlocks: List<ForEach> = emptyList()

    /** Each narrowed path, by its names after any leading `this` (`velle.spec.ShapeNarrowing.namesOf`), to the
     *  shape it is narrowed to, while a licensed branch is checked. */
    internal val scopeByNarrowedPath = mutableMapOf<List<String>, String>()

    /** What the governing tests of the branch being checked show: paths present and tests established (Reference §10). */
    internal var branchNarrowing: Narrowing = Narrowing.NONE

    // ── Analyses of the whole spec, worked out once ─────────────────────────

    /** Each assignment written directly in a rule body, resolved to the field it writes. */
    internal val writes: List<Write> by lazy { findWrites() }

    /** Every commit the spec can make: each act, each closure part, and each rule's creations, writes, and deletes. */
    internal val possibleCommits: List<PossibleCommit> by lazy { listPossibleCommits() }

    /** The prohibitions a proof may take as given: no rule writes a field they read or deletes a record of another shape they consult. */
    internal val spendableNevers: List<NeverDecl> by lazy { findSpendableNevers() }

    /** The ordinals into `model.nevers` of the prohibitions checked once, at the act's own commit, before any rule runs. */
    val doorNevers: Set<Int> by lazy { findDoorNevers() }

    /** Each refinement's name to the names of the refinements its predicate mentions. */
    internal val refinementMentionGraph: Map<String, Set<String>> by lazy { buildRefinementMentionGraph() }

    /** Each shape's name to the names of its transient fields, for the shapes that have any. */
    internal val transientFieldsByShape: Map<String, Set<String>> by lazy { findTransientFieldsByShape() }

    // ── Analyses of one rule or one shape, worked out on first use ──────────

    internal val loopAliasesByRuleName = mutableMapOf<String, Map<String, String>>()
    internal val ruleReadsByRuleName = mutableMapOf<String, ReadSummary>()
    internal val conditionSummaryByRuleName = mutableMapOf<String, ReadSummary>()
    internal val consultedShapesByRuleName = mutableMapOf<String, Set<String>>()
    internal val watchedRefinementsByRuleName = mutableMapOf<String, Set<String>>()
    internal val bodyReadsByRuleName = mutableMapOf<String, ReadSummary>()
    internal val effectsByRuleName = mutableMapOf<String, RuleEffects>()
    internal val updateTimestampsByShape = mutableMapOf<String, Set<String>>()

    /** Keyed `rule:<rule name>` for a rule's condition, `ref:<refinement name>` for a refinement's predicate. */
    internal val polaritiesByConditionKey = mutableMapOf<String, Polarities>()
}
