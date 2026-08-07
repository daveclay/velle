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
    )

    fun generate(specSource: String, systemName: String): Output =
        Generator(Model(Parser.parse(specSource)), systemName).generate()

    // ─────────────────────────────────────────────────────────────────────────

    private class Generator(val model: Model, val systemName: String) {

        val givens = LinkedHashMap<String, String>() // method name → kdoc
        val indexEntries = LinkedHashMap<String, MutableList<String>>() // file → sentences
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
            return Output(files, requiredGivensFile(), supportFile(), indexFile())
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
            appendLine("// GENERATED by Velle from the spec — do not edit. Regenerate with: gradle generate")
            appendLine("package velle.generated.specs")
            appendLine()
            appendLine("import kotlin.test.*")
            appendLine("import velle.*")
            appendLine("import velle.generated.*")
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
            val kindWord: String
            val givenName: String
            when {
                rule.leaving -> { kindWord = "leaving"; givenName = "exit${rule.name}" }
                tickOnly -> { kindWord = "tick"; givenName = "populate${rule.name}" }
                else -> { kindWord = "entry"; givenName = "enter${rule.name}" }
            }
            demandGiven(givenName, givenDoc(rule, condName, tickOnly, schedules))

            val body = StringBuilder()
            val counts = rule.body.filterIsInstance<Creation>().map { it.shape }.distinct()
            counts.forEach { body.line(2, "val before${it} = count(\"$it\")") }
            body.line(2, "val subject = givens.$givenName()")

            when {
                rule.leaving -> condName?.let {
                    body.line(2, "assertFalse(member(subject, \"$it\"), \"the given must cause an exit from '$it'\")")
                }
                tickOnly -> {
                    condName?.let {
                        body.line(2, "assertTrue(member(subject, \"$it\"), \"the given must deliver a member of '$it'\")")
                    }
                    body.line(2, "sys.system.tick(\"${schedules.first()}\")")
                }
                else -> if (rule.preposition != "after") condName?.let {
                    // for `after commit` rules the firing has already disarmed the trigger
                    // state by the time the given returns — the disarm assert covers it
                    body.line(2, "assertTrue(member(subject, \"$it\"), \"the given must deliver a member of '$it'\")")
                }
            }

            for (creation in rule.body.filterIsInstance<Creation>()) {
                val v = "produced${creation.shape}"
                body.line(2, "assertEquals(before${creation.shape} + 1, count(\"${creation.shape}\"), \"rule ${rule.name}: one '${creation.shape}' per firing\")")
                val thisFields = creation.fields.filter { it.value == PathExpr("this") }.map { it.name }
                if (thisFields.isNotEmpty()) {
                    body.line(2, "val $v = last(\"${creation.shape}\")")
                    thisFields.forEach {
                        body.line(2, "assertEquals(subject, field($v, \"$it\"), \"${creation.shape}.$it: this\")")
                    }
                }
            }

            for (a in rule.body.filterIsInstance<Assignment>()) {
                val rhs = a.value as? PathExpr ?: continue
                if (rhs.segs.isNotEmpty() || rhs.root == "this") continue
                if (a.target.segs.isEmpty()) continue
                var read = "subject"
                for (seg in listOf(a.target.root) + a.target.segs.dropLast(1).map { it.name }) {
                    if (seg == "this") continue
                    read = "ref($read, \"$seg\")"
                }
                val lastField = a.target.segs.last().name
                body.line(2, "assertEquals(field(subject, \"${rhs.root}\"), field($read, \"$lastField\"), \"${Printer.expr(a.target)} = ${rhs.root}\")")
            }

            condName?.let { cn ->
                partitionPeer(cn)?.let { peer ->
                    body.line(2, "assertTrue(member(subject, \"$cn\") != member(subject, \"$peer\"), \"'$cn' and '$peer' partition the act\")")
                }
            }

            val guarded = guardConjuncts(rule).isNotEmpty()
            if (rule.preposition == "after" && guarded && condName != null && model.refinements[condName]?.expr is RefName &&
                (model.refinements.getValue(condName).expr as RefName).where != null
            ) {
                body.line(2, "assertFalse(member(subject, \"$condName\"), \"the disarm law: the firing left its trigger state\")")
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

            val sentence = when {
                rule.leaving -> "${rule.name} - leaving ${condName ?: "its condition"} fires the exit reaction"
                tickOnly -> "${rule.name} - the ${schedules.first()} sweep serves ${condName ?: "its condition"}"
                rule.preposition == "after" -> "${rule.name} - entering ${condName ?: "its condition"} fires after the transaction"
                else -> "${rule.name} - entering ${condName ?: "its condition"} fires its effects"
            }
            recordCase(sentence)
            return listOf(testFn(sentence, body.toString()))
        }

        fun givenDoc(rule: RuleDecl, condName: String?, tickOnly: Boolean, schedules: List<String>): String {
            val cond = condName ?: "the rule's condition"
            return when {
                rule.leaving ->
                    "Create a member of '$cond', then perform the commit that makes it leave (fires rule ${rule.name}); return the subject's id."
                tickOnly ->
                    "Bring ONE subject into '$cond' without ticking ${schedules.joinToString("/") { "'$it'" }}; return the subject's id."
                rule.preposition == "after" ->
                    "Perform the commit(s) that make ONE new subject enter '$cond' (rule ${rule.name} then fires after the transaction); return the trigger subject's id."
                else ->
                    "Perform the commit(s) that make ONE new subject enter '$cond' (fires rule ${rule.name}); return the subject's id."
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
                tests.add(testFn(sentence, body.toString()))
            }
            run {
                val sentence = "never - a ${shape.name} with ${fieldPath.root} ${plain(legal)} is accepted"
                val body = StringBuilder()
                fields.setup.forEach { body.line(2, it) }
                body.line(2, "val result = sys.system.commit(\"${shape.name}\", mapOf(${fields.entries(fieldPath.root, legal)}))")
                body.line(2, "assertIs<CommitResult.Accepted>(result)")
                recordCase(sentence)
                tests.add(testFn(sentence, body.toString()))
            }
            return tests
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
                        demandGiven(given, "Provide any committed '${t.shape}'; return its id.")
                        val varName = p.name
                        setup.add("val $varName = givens.$given()")
                        pairs.add(p.name to varName)
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

        fun recordCase(sentence: String) {
            currentFileSentences?.add(sentence)
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

        fun demandGiven(name: String, doc: String) {
            givens.putIfAbsent(name, doc)
        }

        // ── companion outputs ────────────────────────────────────────────────

        fun requiredGivensFile(): String = buildString {
            appendLine("// GENERATED by Velle from the spec — do not edit. Regenerate with: gradle generate")
            appendLine("package velle.generated")
            appendLine()
            appendLine("/**")
            appendLine(" * The scenarios the generated specs require — the human-owned half of the")
            appendLine(" * given/derived split (testgen.md). Implement as:")
            appendLine(" *")
            appendLine(" *     package velle.generated")
            appendLine(" *     class Givens(private val sys: ${systemName}System) : RequiredGivens { ... }")
            appendLine(" *")
            appendLine(" * A missing given is a compile error naming exactly what the spec's tests are owed.")
            appendLine(" */")
            appendLine("interface RequiredGivens {")
            for ((name, doc) in givens) {
                appendLine()
                appendLine("    /** $doc */")
                appendLine("    fun $name(): Long")
            }
            appendLine("}")
        }

        fun supportFile(): String = buildString {
            appendLine("// GENERATED by Velle from the spec — do not edit. Regenerate with: gradle generate")
            appendLine("package velle.generated.specs")
            appendLine()
            appendLine("import velle.generated.*")
            appendLine()
            appendLine("abstract class SpecSupport {")
            appendLine("    val sys = ${systemName}System()")
            appendLine("    val givens: RequiredGivens = Givens(sys)")
            appendLine("    fun count(shape: String) = sys.system.instancesOf(shape).size")
            appendLine("    fun last(shape: String) = sys.system.instancesOf(shape).last()")
            appendLine("    fun member(id: Long, refinement: String) = sys.system.isMember(id, refinement)")
            appendLine("    fun field(id: Long, name: String) = sys.system.get(id, name)")
            appendLine("    fun ref(id: Long, name: String) = sys.system.get(id, name) as Long")
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
