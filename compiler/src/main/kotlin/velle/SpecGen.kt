package velle

import java.math.BigDecimal

/**
 * Generates the executable specs (testgen.md): one file per story root, cases
 * derived from the case catalog, givens demanded through a generated
 * RequiredGivens interface, and a SPEC_INDEX.md for findability.
 */
object SpecGen {

    data class Output(
        /** file name (under specs/) → content */
        val specFiles: Map<String, String>,
        val requiredGivens: String,
        val support: String,
        val index: String,
        /** file name (under features/) → Gherkin rendering — documentation, not a runner */
        val featureFiles: Map<String, String>,
    )

    fun generate(specSource: String, systemName: String): Output =
        Generator(Model(Parser.parse(specSource)), systemName).generate()

    // ─────────────────────────────────────────────────────────────────────────

    private class Generator(val model: Model, val systemName: String) {

        /** Per-system package so several generated systems coexist in one module. */
        val sysPkg = "velle.generated." + systemName.lowercase()

        /**
         * A demanded given: its kdoc and the typed view it returns — or, for an
         * exit action ([takesSubject]), the view it receives: the test performs
         * the "when" visibly by handing the state-given's subject to the action.
         */
        data class Given(val doc: String, val viewType: String, val takesSubject: Boolean = false)

        val givens = LinkedHashMap<String, Given>() // method name → signature
        val indexEntries = LinkedHashMap<String, MutableList<String>>() // file → sentences
        val featureEntries = LinkedHashMap<String, MutableList<String>>() // feature file → scenario blocks
        val skipped = mutableListOf<String>()

        fun generate(): Output {
            val clusters = LinkedHashMap<String, MutableList<ClusterItem>>()
            for (rule in model.rules.values) {
                clusters.getOrPut(storyRootOf(rule)) { mutableListOf() }.add(ClusterItem.Rule(rule))
            }
            for ((i, never) in model.nevers.withIndex()) {
                val base = model.baseOfExpr(never.target) ?: continue
                clusters.getOrPut(storyRootOfName(base)) { mutableListOf() }.add(ClusterItem.Never(never, i))
            }

            val files = LinkedHashMap<String, String>()
            for ((root, items) in clusters) {
                val name = "${root}Spec"
                files["$name.kt"] = clusterFile(name, root, items)
            }
            val features = featureEntries.filterValues { it.isNotEmpty() }
                .mapValues { (name, scenarios) -> featureFile(name.removeSuffix(".feature"), scenarios) }
            return Output(files, requiredGivensFile(), supportFile(), indexFile(), features)
        }

        sealed interface ClusterItem {
            data class Rule(val rule: RuleDecl) : ClusterItem
            data class Never(val never: NeverDecl, val ordinal: Int) : ClusterItem
        }

        // ── story roots (testgen.md, "Organization") ─────────────────────────

        fun storyRootOf(rule: RuleDecl): String {
            val name = (rule.condition as? RefName)?.name
                ?: return model.baseOfExpr(rule.condition) ?: "Misc"
            return storyRootOfName(name)
        }

        fun storyRootOfName(name: String): String {
            if (name in model.shapes) return name
            val r = model.refinements[name] ?: return name
            val base = model.baseOf(name)
            // partition heuristic: an act-shape refinement testing membership elsewhere
            val tested = membershipTests(r.expr)
            if (tested.size == 1 && base != null && base in model.exposed)
                return storyRootOfName(tested.single())
            // chain heuristic: a state over a produced (unexposed) shape roots with its producer
            if (base != null && base !in model.exposed) {
                val producer = model.rules.values.firstOrNull { rule ->
                    rule.body.filterIsInstance<Creation>().any { it.shape == base }
                }
                if (producer != null) return storyRootOf(producer)
            }
            // composition: follow the leftmost refinement operand
            val left = leftmostName(r.expr)
            if (left != null && left in model.refinements && left != name) return storyRootOfName(left)
            return name
        }

        fun membershipTests(e: RefExpr): Set<String> {
            val out = mutableSetOf<String>()
            fun walkExpr(x: Expr) {
                when (x) {
                    is IsExpr -> {
                        x.refinement?.takeIf { it in model.refinements }?.let { out.add(it) }
                        walkExpr(x.subject)
                    }
                    is NotExpr -> walkExpr(x.inner)
                    is Binary -> { walkExpr(x.left); walkExpr(x.right) }
                    else -> {}
                }
            }
            fun walk(re: RefExpr) {
                when (re) {
                    is RefName -> re.where?.let { walkExpr(it) }
                    is RefNot -> walk(re.inner)
                    is RefAnd -> { walk(re.left); walk(re.right) }
                    is RefOr -> { walk(re.left); walk(re.right) }
                }
            }
            walk(e)
            return out
        }

        fun leftmostName(e: RefExpr): String? = when (e) {
            is RefName -> e.name
            is RefNot -> leftmostName(e.inner)
            is RefAnd -> leftmostName(e.left)
            is RefOr -> leftmostName(e.left)
        }

        // ── condition analysis ───────────────────────────────────────────────

        fun conditionConjuncts(e: RefExpr): List<Expr> = when (e) {
            is RefName -> {
                val inherited = model.refinements[e.name]?.let { conditionConjuncts(it.expr) } ?: emptyList()
                inherited + (e.where?.let { predicateConjuncts(it) } ?: emptyList())
            }
            is RefAnd -> conditionConjuncts(e.left) + conditionConjuncts(e.right)
            else -> emptyList()
        }

        fun predicateConjuncts(p: Expr): List<Expr> =
            if (p is Binary && p.op == "and") predicateConjuncts(p.left) + predicateConjuncts(p.right)
            else listOf(p)

        fun guardConjuncts(rule: RuleDecl): List<Expr> =
            conditionConjuncts(rule.condition).filter { c ->
                val inner = (c as? NotExpr)?.inner
                inner is ExistsExpr || (inner is PathExpr && inner.segs.isEmpty())
            }

        /**
         * The act whose commit provably ends membership in a leaving rule's
         * condition: a `not exists X for this` atom over an exposed shape means
         * the exit "when" is *derivable* — committing an X — so the test performs
         * the real act instead of demanding an exit given. Drift exits (a value
         * predicate like `balance < 0`) return null: which commit recovers the
         * value is business judgment, owed by the human.
         */
        fun exitAct(rule: RuleDecl): ShapeDecl? =
            conditionConjuncts(rule.condition).firstNotNullOfOrNull { c ->
                val inner = (c as? NotExpr)?.inner as? ExistsExpr ?: return@firstNotNullOfOrNull null
                val shape = inner.shape ?: return@firstNotNullOfOrNull null
                if (inner.forExpr != PathExpr("this")) return@firstNotNullOfOrNull null
                model.shapes[shape]?.takeIf { shape in model.exposed }
            }

        class ExitCommit(val setup: List<String>, val call: String)

        /**
         * Build the typed-surface call committing [act] against the subject —
         * positional required args in declaration order, matching the generated
         * commit function. Null (→ fall back to an exit given) if the act doesn't
         * reference the subject's shape exactly once or a field is unconstructible.
         */
        fun exitCommit(act: ShapeDecl, subjectVar: String, subjectBase: String?): ExitCommit? {
            val setup = mutableListOf<String>()
            val args = mutableListOf<String>()
            var subjectRefs = 0
            for (p in act.members.filterIsInstance<StoredProp>()) {
                if (p.initially != null) continue
                when (val t = p.type) {
                    is RelType -> {
                        if (t.optional) continue
                        if (t.shape == subjectBase) {
                            subjectRefs++
                            args.add(subjectVar)
                        } else {
                            val given = "some${t.shape}"
                            demandGiven(given, "Provide any committed '${t.shape}'; return it.", "${t.shape}View")
                            setup.add("val ${p.name} = givens.$given()")
                            args.add(p.name)
                        }
                    }
                    is ScalarType -> {
                        if (t.optional) continue
                        args.add(when (t.name) {
                            "text" -> "\"sample\""
                            "boolean" -> "false"
                            "Date" -> "java.time.LocalDate.of(2026, 1, 15)"
                            "DateTime" -> "java.time.Instant.parse(\"2026-01-15T00:00:00Z\")"
                            else -> {
                                val v = pickValue(act.name, p.name, null, java.math.BigDecimal.ZERO) ?: return null
                                val plain = v.stripTrailingZeros().toPlainString()
                                when (t.name) {
                                    "integer" -> plain
                                    "long" -> "${plain}L"
                                    "double" -> "${v.toDouble()}"
                                    else -> "java.math.BigDecimal(\"$plain\")"
                                }
                            }
                        })
                    }
                }
            }
            if (subjectRefs != 1) return null
            return ExitCommit(setup, "sys.commit${act.name}(${args.joinToString(", ")})")
        }

        fun guardWindowDays(rule: RuleDecl): Long? {
            var days: Long? = null
            fun walk(x: Expr) {
                when (x) {
                    is DurationLit -> if (x.unit == "days") days = x.amount
                    is Binary -> { walk(x.left); walk(x.right) }
                    is NotExpr -> walk(x.inner)
                    is ExistsExpr -> x.collection?.where?.let { walk(it) }
                    else -> {}
                }
            }
            guardConjuncts(rule).forEach { walk(it) }
            return days
        }

        fun partitionPeer(conditionName: String): String? {
            val c = model.refinements[conditionName] ?: return null
            val cName = c.expr as? RefName ?: return null
            val cWhere = cName.where ?: return null
            for ((otherName, other) in model.refinements) {
                if (otherName == conditionName) continue
                val o = other.expr as? RefName ?: continue
                if (o.name != cName.name) continue
                val oWhere = o.where ?: continue
                if (cWhere == NotExpr(oWhere) || oWhere == NotExpr(cWhere)) return otherName
            }
            return null
        }

        // ── cluster file emission ────────────────────────────────────────────

        fun clusterFile(className: String, root: String, items: List<ClusterItem>): String = buildString {
            currentFileSentences = indexEntries.getOrPut("$className.kt") { mutableListOf() }
            currentFeatureScenarios = featureEntries.getOrPut("$root.feature") { mutableListOf() }
            appendLine("// GENERATED by Velle from the spec — do not edit. Regenerate with: gradle generate")
            appendLine("package $sysPkg.specs")
            appendLine()
            appendLine("import kotlin.test.*")
            appendLine("import velle.*")
            appendLine("import velle.generated.*")
            appendLine("import $sysPkg.*")
            appendLine()
            appendLine("/**")
            provenance(root, items).forEach { block ->
                block.lines().forEach { appendLine(" * $it") }
                appendLine(" *")
            }
            appendLine(" */")
            appendLine("class $className : SpecSupport() {")
            for (item in items) {
                when (item) {
                    is ClusterItem.Rule -> ruleTests(item.rule).forEach { appendLine(it) }
                    is ClusterItem.Never -> neverTests(item.never, item.ordinal, "$className.kt").forEach { appendLine(it) }
                }
            }
            appendLine("}")
        }

        fun provenance(root: String, items: List<ClusterItem>): List<String> {
            val blocks = LinkedHashMap<String, String>()
            fun addRefinement(name: String) {
                model.refinements[name]?.let { blocks.putIfAbsent(name, Printer.print(it)) }
            }
            addRefinement(root)
            for (item in items) when (item) {
                is ClusterItem.Rule -> {
                    (item.rule.condition as? RefName)?.let { c ->
                        addRefinement(c.name)
                        partitionPeer(c.name)?.let { addRefinement(it) }
                    }
                    blocks["rule:${item.rule.name}"] = Printer.print(item.rule)
                }
                is ClusterItem.Never -> blocks["never:${item.ordinal}"] = Printer.print(item.never)
            }
            return blocks.values.toList()
        }

        // ── rule cases ───────────────────────────────────────────────────────

        fun ruleTests(rule: RuleDecl): List<String> {
            val condName = (rule.condition as? RefName)?.name
            val schedules = rule.triggers.filter { it != "commit" }
            val tickOnly = rule.preposition != "after" && schedules.isNotEmpty() && "commit" !in rule.triggers
            // the subject reads as what it is: the condition's base-shape instance
            val base = model.baseOfExpr(rule.condition)
            val subject = base?.replaceFirstChar { it.lowercase() } ?: "subject"
            // scenario-language given names, derived from the condition: a bare
            // condition names the state itself (`unappliedDeposit()`, and rules
            // sharing one condition share one given); an inline `where` (or a
            // composite) can't be named, so the rule disambiguates
            // (`memberForRestoreService()`)
            val bare = (rule.condition as? RefName)?.takeIf { it.where == null }
            val viewType = "${base ?: "Unknown"}View"

            val body = StringBuilder()
            val counts = rule.body.filterIsInstance<Creation>().map { it.shape }.distinct()
            counts.forEach { body.line(2, "val before${it} = count(\"$it\")") }
            var exitActName: String? = null

            if (rule.leaving) {
                // exit tests perform the "when" visibly and assert both sides of
                // the transition. An act-paired exit derives the when from the
                // predicate — the test commits the real act; only a drift exit
                // demands a human-owned exit action beside the state-given.
                val cond = condName ?: "the rule's condition"
                val stateGiven =
                    if (bare != null) bare.name.replaceFirstChar { it.lowercase() }
                    else "${subject}For${rule.name}"
                demandGiven(stateGiven, "Bring ONE subject into '$cond'; return it.", viewType)
                body.line(2, "// given: a member of '$cond'")
                body.line(2, "val $subject = givens.$stateGiven()")
                condName?.let {
                    body.line(2, "$subject.assertIsA(\"$it\", \"the given must deliver a member of '$it'\")")
                }
                val derived = exitAct(rule)?.let { act -> exitCommit(act, subject, base)?.let { act to it } }
                if (derived != null) {
                    val (act, commit) = derived
                    exitActName = act.name
                    body.line(2, "// when: a ${act.name} for it is committed")
                    commit.setup.forEach { body.line(2, it) }
                    body.line(2, commit.call)
                } else {
                    val exitGiven = if (bare != null) "exit${bare.name}" else "exitFor${rule.name}"
                    demandGiven(exitGiven, "Perform the commit that takes [$subject] out of '$cond'.", viewType, takesSubject = true)
                    body.line(2, "// when: the commit that takes it out of '$cond'")
                    body.line(2, "givens.$exitGiven($subject)")
                }
                condName?.let {
                    body.line(2, "$subject.assertIsNotA(\"$it\", \"the exit commit must end the membership\")")
                }
            } else {
                val givenName =
                    if (bare != null) bare.name.replaceFirstChar { it.lowercase() }
                    else "${subject}For${rule.name}"
                demandGiven(givenName, givenDoc(rule, condName, tickOnly, schedules), viewType)
                body.line(2, "// given: ${givenComment(rule, condName, tickOnly, schedules)}")
                body.line(2, "val $subject = givens.$givenName()")
                when {
                    tickOnly -> {
                        condName?.let {
                            body.line(2, "$subject.assertIsA(\"$it\", \"the given must deliver a member of '$it'\")")
                        }
                        body.line(2, "sys.system.tick(\"${schedules.first()}\")")
                    }
                    else -> if (rule.preposition != "after") condName?.let {
                        // for `after commit` rules the firing has already disarmed the trigger
                        // state by the time the given returns — the disarm assert covers it
                        body.line(2, "$subject.assertIsA(\"$it\", \"the given must deliver a member of '$it'\")")
                    }
                }
            }

            for (creation in rule.body.filterIsInstance<Creation>()) {
                val v = "produced${creation.shape}"
                body.line(2, "assertEquals(before${creation.shape} + 1, count(\"${creation.shape}\"), \"rule ${rule.name}: one '${creation.shape}' per firing\")")
                val thisFields = creation.fields.filter { it.value == PathExpr("this") }.map { it.name }
                if (thisFields.isNotEmpty()) {
                    body.line(2, "val $v = last(\"${creation.shape}\")")
                    thisFields.forEach {
                        body.line(2, "assertEquals($subject.id, field($v, \"$it\"), \"${creation.shape}.$it: this\")")
                    }
                }
            }

            for (a in rule.body.filterIsInstance<Assignment>()) {
                val rhs = a.value as? PathExpr ?: continue
                if (rhs.segs.isNotEmpty() || rhs.root == "this") continue
                if (a.target.segs.isEmpty()) continue
                var read = subject
                for (seg in listOf(a.target.root) + a.target.segs.dropLast(1).map { it.name }) {
                    if (seg == "this") continue
                    read = "ref($read, \"$seg\")"
                }
                val lastField = a.target.segs.last().name
                body.line(2, "assertEquals(field($subject, \"${rhs.root}\"), field($read, \"$lastField\"), \"${Printer.expr(a.target)} = ${rhs.root}\")")
            }

            condName?.let { cn ->
                partitionPeer(cn)?.let { peer ->
                    body.line(2, "assertTrue(member($subject, \"$cn\") != member($subject, \"$peer\"), \"'$cn' and '$peer' partition the act\")")
                }
            }

            val guarded = guardConjuncts(rule).isNotEmpty()
            if (rule.preposition == "after" && guarded && condName != null && model.refinements[condName]?.expr is RefName &&
                (model.refinements.getValue(condName).expr as RefName).where != null
            ) {
                body.line(2, "$subject.assertIsNotA(\"$condName\", \"the disarm law: the firing left its trigger state\")")
            }
            if (guarded && (rule.preposition == "after" || schedules.isNotEmpty())) {
                for (s in schedules) {
                    body.line(2, "sys.system.tick(\"$s\")")
                }
                counts.forEach {
                    body.line(2, "assertEquals(before${it} + 1, count(\"$it\"), \"the guard makes re-evaluation harmless\")")
                }
            }
            guardWindowDays(rule)?.let { days ->
                if (tickOnly) {
                    body.line(2, "sys.advanceDays(${days + 1})")
                    body.line(2, "sys.system.tick(\"${schedules.first()}\")")
                    counts.forEach {
                        body.line(2, "assertEquals(before${it} + 2, count(\"$it\"), \"the $days-day window reopened\")")
                    }
                }
            }

            // case sentences read in the domain's vocabulary — subject, act, and
            // consequence as the spec's own nouns — not Velle metalanguage
            val cond = condName ?: base ?: "its condition"
            val effects = effectsPhrase(rule)
            val sentence = "${rule.name} - " + when {
                rule.leaving && exitActName != null ->
                    "${article(exitActName!!)} $exitActName for ${article(cond)} $cond $effects"
                rule.leaving -> "${article(subject)} $subject that leaves $cond $effects"
                tickOnly -> "at the ${schedules.first()} tick, ${article(cond)} $cond $effects"
                rule.preposition == "after" -> "a new $cond $effects after the commit"
                else -> "a new $cond $effects"
            }
            recordCase(sentence)
            recordScenario(rule, sentence, cond, subject, tickOnly, schedules, exitActName, guarded, counts)
            return listOf(testFn(sentence, body.toString()))
        }

        /** Interface kdoc: what the implementer owes. Rule-agnostic, since rules sharing a bare condition share the given. (Exit rules demand their pair inline — a state-given and an exit action.) */
        fun article(name: String) = if (name.first().lowercaseChar() in "aeiou") "an" else "a"

        /** The same case, rendered as a Gherkin scenario for the .feature documentation. */
        fun recordScenario(
            rule: RuleDecl,
            sentence: String,
            cond: String,
            subject: String,
            tickOnly: Boolean,
            schedules: List<String>,
            exitActName: String?,
            guarded: Boolean,
            counts: List<String>,
        ) {
            val g = StringBuilder()
            g.appendLine()
            g.appendLine("  # rule ${rule.name}")
            g.appendLine("  Scenario: ${sentence.removePrefix("${rule.name} - ").replaceFirstChar { it.uppercase() }}")
            val thens = mutableListOf<String>()
            when {
                rule.leaving -> {
                    g.appendLine("    Given a member of \"$cond\"")
                    if (exitActName != null) g.appendLine("    When a \"$exitActName\" for it is committed")
                    else g.appendLine("    When the commit that takes it out of \"$cond\" lands")
                    thens.add("it is no longer a \"$cond\"")
                }
                tickOnly -> {
                    g.appendLine("    Given one $subject in \"$cond\", no \"${schedules.first()}\" tick yet")
                    g.appendLine("    When \"${schedules.first()}\" ticks")
                }
                else -> {
                    g.appendLine("    When a commit brings one new $subject into \"$cond\"")
                    if (rule.preposition == "after") thens.add("the rule has fired after that transaction")
                }
            }
            for (creation in rule.body.filterIsInstance<Creation>()) {
                thens.add("one more \"${creation.shape}\" exists")
                creation.fields.filter { it.value == PathExpr("this") }.forEach {
                    thens.add("that ${creation.shape}'s \"${it.name}\" is the $subject")
                }
            }
            for (a in rule.body.filterIsInstance<Assignment>()) {
                val rhs = a.value as? PathExpr ?: continue
                if (rhs.segs.isNotEmpty() || rhs.root == "this") continue
                if (a.target.segs.isEmpty()) continue
                thens.add("the ${Printer.expr(a.target).removePrefix("this.")} now equals the act's \"${rhs.root}\"")
            }
            (rule.condition as? RefName)?.name?.let { cn ->
                partitionPeer(cn)?.let { thens.add("the act is in exactly one of \"$cn\" / \"$it\"") }
            }
            if (rule.preposition == "after" && guarded) thens.add("the $subject has left \"$cond\"")
            if (guarded && schedules.isNotEmpty() && counts.isNotEmpty())
                thens.add("another \"${schedules.first()}\" tick produces nothing more")
            guardWindowDays(rule)?.let { days ->
                if (tickOnly && counts.isNotEmpty())
                    thens.add("after ${days + 1} days pass, \"${schedules.first()}\" produces another \"${counts.first()}\"")
            }
            if (thens.isEmpty())
                thens.add(if (tickOnly) "the sweep has run for every current member" else "that $subject is ${article(cond)} \"$cond\"")
            thens.forEachIndexed { i, t -> g.appendLine("    ${if (i == 0) "Then" else "And"} $t") }
            currentFeatureScenarios?.add(g.toString())
        }

        /** The rule's consequences as a domain phrase: "produces a Receipt and sets customer.email". */
        fun effectsPhrase(rule: RuleDecl): String {
            val created = rule.body.filterIsInstance<Creation>().map { it.shape }.distinct()
            val set = rule.body.filterIsInstance<Assignment>()
                .map { Printer.expr(it.target).removePrefix("this.") }.distinct()
            val parts = mutableListOf<String>()
            if (created.isNotEmpty())
                parts.add("produces " + created.joinToString(" and ") { "${article(it)} $it" })
            if (set.isNotEmpty()) parts.add("sets " + set.joinToString(" and "))
            return parts.joinToString(" and ")
        }

        fun givenDoc(rule: RuleDecl, condName: String?, tickOnly: Boolean, schedules: List<String>): String {
            val cond = condName ?: "the rule's condition"
            return when {
                tickOnly ->
                    "Bring ONE subject into '$cond' without ticking ${schedules.joinToString("/") { "'$it'" }}; return the subject."
                rule.preposition == "after" ->
                    "Perform the commit(s) that make ONE new subject enter '$cond' (the rule under test fires after that transaction); return the trigger subject."
                else ->
                    "Perform the commit(s) that make ONE new subject enter '$cond'; return the subject."
            }
        }

        /** In-test comment: the state the given has delivered at this point. */
        fun givenComment(rule: RuleDecl, condName: String?, tickOnly: Boolean, schedules: List<String>): String {
            val cond = condName ?: "the rule's condition"
            return when {
                tickOnly -> "one subject in '$cond'; no ${schedules.joinToString("/") { "'$it'" }} tick yet"
                rule.preposition == "after" -> "one new subject entered '$cond', and rule ${rule.name} has fired after that transaction"
                else -> "one new subject entered '$cond'"
            }
        }

        // ── never cases ──────────────────────────────────────────────────────

        fun neverTests(never: NeverDecl, ordinal: Int, file: String): List<String> {
            val target = never.target as? RefName ?: return skip(ordinal, "composite never")
            val shape = model.shapes[target.name] ?: return skip(ordinal, "never over a refinement")
            if (target.name !in model.exposed) return skip(ordinal, "shape not exposed")
            val conjunct = target.where?.let { predicateConjuncts(it) } ?: return skip(ordinal, "no predicate")
            if (conjunct.size != 1) return skip(ordinal, "multi-conjunct predicate")
            val cmp = conjunct.single() as? Binary ?: return skip(ordinal, "non-comparison predicate")
            val fieldPath = cmp.left as? PathExpr ?: return skip(ordinal, "complex comparison subject")
            if (fieldPath.segs.isNotEmpty()) return skip(ordinal, "multi-hop comparison subject")
            val literal = literalOf(cmp.right) ?: return skip(ordinal, "non-literal bound")

            val violating = pickValue(shape.name, fieldPath.root, mustSatisfy = cmp, literal = literal)
                ?: return skip(ordinal, "no violating value found")
            val legal = pickValue(shape.name, fieldPath.root, mustSatisfy = null, literal = literal)
                ?: return skip(ordinal, "no legal value found")

            val fields = commitFields(shape, fieldPath.root) ?: return skip(ordinal, "unconstructible act")
            val opWords = opWords(cmp.op)
            val relGivens = shape.members.filterIsInstance<StoredProp>()
                .filter { it.initially == null }
                .mapNotNull { ((it.type as? RelType)?.takeIf { t -> !t.optional })?.shape }

            val tests = mutableListOf<String>()
            run {
                val sentence = "never - a ${shape.name} where ${fieldPath.root} $opWords ${plain(literal)} is refused"
                val body = StringBuilder()
                fields.setup.forEach { body.line(2, it) }
                body.line(2, "val before = count(\"${shape.name}\")")
                body.line(2, "val result = sys.system.commit(\"${shape.name}\", mapOf(${fields.entries(fieldPath.root, violating)}))")
                body.line(2, "assertIs<CommitResult.Refused>(result)")
                body.line(2, "assertEquals(before, count(\"${shape.name}\"), \"a refused act commits nothing\")")
                recordCase(sentence)
                recordNeverScenario(never, sentence, shape.name, fieldPath.root, plain(violating), relGivens,
                    "Then it is refused", "And nothing has committed")
                tests.add(testFn(sentence, body.toString()))
            }
            run {
                val sentence = "never - a ${shape.name} with ${fieldPath.root} ${plain(legal)} is accepted"
                val body = StringBuilder()
                fields.setup.forEach { body.line(2, it) }
                body.line(2, "val result = sys.system.commit(\"${shape.name}\", mapOf(${fields.entries(fieldPath.root, legal)}))")
                body.line(2, "assertIs<CommitResult.Accepted>(result)")
                recordCase(sentence)
                recordNeverScenario(never, sentence, shape.name, fieldPath.root, plain(legal), relGivens,
                    "Then it is accepted")
                tests.add(testFn(sentence, body.toString()))
            }
            return tests
        }

        fun recordNeverScenario(
            never: NeverDecl,
            sentence: String,
            shape: String,
            field: String,
            value: String,
            relGivens: List<String>,
            vararg outcome: String,
        ) {
            val g = StringBuilder()
            g.appendLine()
            g.appendLine("  # ${Printer.print(never).lines().first()}")
            g.appendLine("  Scenario: ${sentence.removePrefix("never - ").replaceFirstChar { it.uppercase() }}")
            relGivens.forEach { g.appendLine("    Given any committed \"$it\"") }
            g.appendLine("    When a \"$shape\" with $field $value is committed")
            outcome.forEach { g.appendLine("    $it") }
            currentFeatureScenarios?.add(g.toString())
        }

        fun skip(ordinal: Int, reason: String): List<String> {
            skipped.add("never #${ordinal + 1}: $reason")
            return emptyList()
        }

        fun literalOf(e: Expr): BigDecimal? = when (e) {
            is IntLit -> BigDecimal(e.value)
            is DecLit -> BigDecimal(e.text)
            is UnaryMinus -> literalOf(e.inner)?.negate()
            else -> null
        }

        /**
         * Pick a numeric value for [field] of [shape]: if [mustSatisfy] is given,
         * the value satisfies that comparison; either way it falsifies every
         * *other* single-conjunct never comparison on the same field.
         */
        fun pickValue(shape: String, field: String, mustSatisfy: Binary?, literal: BigDecimal): BigDecimal? {
            val candidates = listOf(
                literal.subtract(BigDecimal.ONE), literal, literal.add(BigDecimal.ONE),
                BigDecimal.ONE, BigDecimal.ZERO,
            )
            val constraints = neverComparisonsOn(shape, field)
            return candidates.firstOrNull { v ->
                (mustSatisfy == null || satisfies(v, mustSatisfy)) &&
                    constraints.none { it != mustSatisfy && satisfies(v, it) }
            }
        }

        fun neverComparisonsOn(shape: String, field: String): List<Binary> =
            model.nevers.mapNotNull { n ->
                val t = n.target as? RefName ?: return@mapNotNull null
                if (t.name != shape) return@mapNotNull null
                val c = t.where?.let { predicateConjuncts(it) }?.singleOrNull() as? Binary ?: return@mapNotNull null
                if ((c.left as? PathExpr)?.root == field && (c.left as? PathExpr)?.segs?.isEmpty() == true) c else null
            }

        fun satisfies(v: BigDecimal, cmp: Binary): Boolean {
            val bound = literalOf(cmp.right) ?: return false
            val c = v.compareTo(bound)
            return when (cmp.op) {
                "<" -> c < 0; "<=" -> c <= 0; ">" -> c > 0; ">=" -> c >= 0
                "==" -> c == 0; "!=" -> c != 0
                else -> false
            }
        }

        class ActFields(val setup: List<String>, private val pairs: List<Pair<String, String>>) {
            fun entries(overrideField: String, value: BigDecimal): String =
                pairs.joinToString(", ") { (name, expr) ->
                    if (name == overrideField) "\"$name\" to java.math.BigDecimal(\"${value.toPlainString()}\")"
                    else "\"$name\" to $expr"
                }
        }

        /** Build commit entries for every required field; null if some field is unconstructible. */
        fun commitFields(shape: ShapeDecl, constrainedField: String): ActFields? {
            val setup = mutableListOf<String>()
            val pairs = mutableListOf<Pair<String, String>>()
            for (p in shape.members.filterIsInstance<StoredProp>()) {
                if (p.initially != null) continue
                when (val t = p.type) {
                    is RelType -> {
                        if (t.optional) continue
                        val given = "some${t.shape}"
                        demandGiven(given, "Provide any committed '${t.shape}'; return it.", "${t.shape}View")
                        val varName = p.name
                        setup.add("// given: any committed '${t.shape}'")
                        setup.add("val $varName = givens.$given()")
                        pairs.add(p.name to "$varName.id")
                    }
                    is ScalarType -> {
                        if (t.optional) continue
                        val expr = when (t.name) {
                            "text" -> "\"sample\""
                            "boolean" -> "false"
                            "Date" -> "java.time.LocalDate.of(2026, 1, 15)"
                            "DateTime" -> "java.time.Instant.parse(\"2026-01-15T00:00:00Z\")"
                            else -> {
                                if (p.name == constrainedField) "OVERRIDDEN"
                                else {
                                    val v = pickValue(shape.name, p.name, null, BigDecimal.ZERO) ?: return null
                                    "java.math.BigDecimal(\"${v.toPlainString()}\")"
                                }
                            }
                        }
                        pairs.add(p.name to expr)
                    }
                }
            }
            return ActFields(setup, pairs)
        }

        fun opWords(op: String): String = when (op) {
            "<=" -> "at most"; "<" -> "below"; ">=" -> "at least"; ">" -> "above"
            "==" -> "equal to"; "!=" -> "other than"; else -> op
        }

        fun plain(v: BigDecimal): String = v.stripTrailingZeros().toPlainString()

        // ── shared emission ──────────────────────────────────────────────────

        var currentFileSentences: MutableList<String>? = null
        var currentFeatureScenarios: MutableList<String>? = null

        fun recordCase(sentence: String) {
            currentFileSentences?.add(sentence)
        }

        fun featureFile(root: String, scenarios: List<String>): String = buildString {
            appendLine("# GENERATED by Velle from the spec — documentation, not a runner; the")
            appendLine("# executable form is specs/${root}Spec.kt. Regenerate with: gradle generate")
            appendLine("Feature: $root")
            scenarios.forEach { append(it) }
        }

        fun testFn(sentence: String, body: String): String = buildString {
            appendLine()
            appendLine("    @Test")
            appendLine("    fun `${sanitize(sentence)}`() {")
            append(body)
            append("    }")
        }

        fun sanitize(s: String): String = s.map { c ->
            if (c.isLetterOrDigit() || c == ' ' || c == '-' || c == '\'') c else ' '
        }.joinToString("").replace(Regex("  +"), " ").trim()

        fun StringBuilder.line(indentLevel: Int, s: String) {
            repeat(indentLevel) { append("    ") }
            appendLine(s)
        }

        fun demandGiven(name: String, doc: String, viewType: String, takesSubject: Boolean = false) {
            givens.putIfAbsent(name, Given(doc, viewType, takesSubject))
        }

        // ── companion outputs ────────────────────────────────────────────────

        fun requiredGivensFile(): String = buildString {
            appendLine("// GENERATED by Velle from the spec — do not edit. Regenerate with: gradle generate")
            appendLine("package $sysPkg")
            appendLine()
            appendLine("import velle.generated.${systemName}System")
            appendLine()
            appendLine("/**")
            appendLine(" * The scenarios the generated specs require — the human-owned half of the")
            appendLine(" * given/derived split (testgen.md). Implement as:")
            appendLine(" *")
            appendLine(" *     package $sysPkg")
            appendLine(" *     class Givens(private val sys: ${systemName}System) : RequiredGivens { ... }")
            appendLine(" *")
            appendLine(" * A missing given is a compile error naming exactly what the spec's tests are owed.")
            appendLine(" */")
            appendLine("interface RequiredGivens {")
            for ((name, given) in givens) {
                val type = "${systemName}System.${given.viewType}"
                appendLine()
                appendLine("    /** ${given.doc} */")
                if (given.takesSubject) {
                    val param = given.viewType.removeSuffix("View").replaceFirstChar { it.lowercase() }
                    appendLine("    fun $name($param: $type)")
                } else {
                    appendLine("    fun $name(): $type")
                }
            }
            appendLine("}")
        }

        fun supportFile(): String = buildString {
            appendLine("// GENERATED by Velle from the spec — do not edit. Regenerate with: gradle generate")
            appendLine("package $sysPkg.specs")
            appendLine()
            appendLine("import kotlin.test.assertTrue")
            appendLine("import velle.View")
            appendLine("import velle.generated.*")
            appendLine("import $sysPkg.*")
            appendLine()
            appendLine("abstract class SpecSupport {")
            appendLine("    val sys = ${systemName}System()")
            appendLine("    val givens: RequiredGivens = Givens(sys)")
            appendLine("    private class Raw(override val id: Long) : View")
            appendLine("    fun count(shape: String) = sys.system.instancesOf(shape).size")
            appendLine("    fun last(shape: String): View = Raw(sys.system.instancesOf(shape).last())")
            appendLine("    fun member(v: View, refinement: String) = sys.system.isMember(v.id, refinement)")
            appendLine("    fun field(v: View, name: String) = sys.system.get(v.id, name)")
            appendLine("    fun ref(v: View, name: String): View = Raw(sys.system.get(v.id, name) as Long)")
            appendLine()
            appendLine("    /** Asserts this instance is currently a member of [refinement] — Velle's `is`. */")
            appendLine("    fun View.assertIsA(refinement: String, message: String) =")
            appendLine("        assertTrue(member(this, refinement), message)")
            appendLine()
            appendLine("    /** Asserts this instance is currently NOT a member of [refinement]. */")
            appendLine("    fun View.assertIsNotA(refinement: String, message: String) =")
            appendLine("        assertTrue(!member(this, refinement), message)")
            appendLine("}")
        }

        fun indexFile(): String = buildString {
            appendLine("# Spec Index — $systemName")
            appendLine()
            appendLine("Generated from the Velle spec (testgen.md). One file per business state;")
            appendLine("each case below is an executable test.")
            for ((file, sentences) in indexEntries) {
                appendLine()
                appendLine("## $file")
                appendLine()
                sentences.forEach { appendLine("- $it") }
            }
            if (skipped.isNotEmpty()) {
                appendLine()
                appendLine("## Not yet generated")
                appendLine()
                skipped.forEach { appendLine("- $it") }
            }
        }
    }
}
