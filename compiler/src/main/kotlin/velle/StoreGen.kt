package velle

/**
 * The typed store surface (investigate_runtime.md §10): per spec, a generated
 * interface stating — in the domain's own names and types — every question the
 * runtime can ask of storage. The generic StateResolver stays the wire
 * protocol; this layer is developer-owned output on top of it:
 *
 * - `<Name>Store` — the strict interface: implementing it is answering every
 *   question the spec can ask; a spec edit that adds a question lands as a new
 *   abstract method, a compile error naming what is now owed.
 * - `<Name>StoreOverGeneric` — every method defaulted onto any generic backend
 *   (a model-driven store, a future framework): override only the questions
 *   worth hand-tuning.
 * - `<Name>StoreResolver` — the bridge the runtime speaks through: recognizes
 *   each incoming candidate filter as its named question (QTemplate) and calls
 *   the typed method with the extracted parameters.
 *
 * Candidate methods are named for the condition they serve (refinement, rule,
 * or never), and their parameters are the filter's evaluation-moment constants
 * (`today`/`now` arithmetic, pre-folded). The superset contract is unchanged:
 * a candidates implementation may over-return freely; the runtime re-checks
 * the authoritative predicate in memory.
 */
object StoreGen {

    fun generate(specSource: String, systemName: String, packageName: String = "velle.generated"): String {
        val model = Model(Parser.parse(specSource))
        require(model.diagnostics.isEmpty()) { "spec does not resolve: ${model.diagnostics}" }
        return Emitter(model, systemName, packageName).emit()
    }

    private class Emitter(val model: Model, val name: String, val pkg: String) {
        val sb = StringBuilder()
        fun line(s: String = "") { sb.append(s).append('\n') }

        val shapes = model.shapes.keys.filter { it !in model.transients }

        class RowField(val name: String, val type: VType)

        fun rowFields(shape: String): List<RowField> =
            model.shapes.getValue(shape).members.mapNotNull { m ->
                when (m) {
                    is StoredProp -> model.typeOf(m.type)
                        .takeIf { it !is VType.Coll } // declared `many` has no commit story (TODO.md)
                        ?.let { RowField(m.name, it) }
                    is TimestampProp -> RowField(m.name, VType.DateTimeT)
                    else -> null
                }
            }

        class Join(val shape: String, val field: String, val target: String)

        val joins: List<Join> = shapes.flatMap { s ->
            model.shapes.getValue(s).members.filterIsInstance<StoredProp>()
                .mapNotNull { p -> (p.type as? RelType)?.takeIf { !it.many }?.let { Join(s, p.name, it.shape) } }
        }

        class Question(
            val method: String,
            val base: String,
            val condKotlin: String,
            val condText: String,
            val sources: MutableList<String>,
            val template: QTemplate,
        ) {
            val holes get() = template.holes
        }

        val questions: List<Question> = buildList {
            val byKey = LinkedHashMap<Triple<String, QF, QF>, Question>()
            fun add(method: String, base: String, condKotlin: String, condText: String, source: String, cond: RefExpr) {
                if (base in model.transients) return
                val t = QTemplate.of(model, base, cond)
                if (t.isTrivial) return
                val key = Triple(base, t.a, t.b)
                byKey[key]?.let { it.sources.add(source); return }
                byKey[key] = Question(method, base, condKotlin, condText, mutableListOf(source), t)
            }
            for ((refName, _) in model.refinements) {
                val base = model.baseOf(refName) ?: continue
                add(
                    "${decap(refName)}Candidates", base,
                    "RefName(\"$refName\")", refName,
                    "refinement $refName", RefName(refName)
                )
            }
            for (r in model.rules.values) {
                val c = r.condition
                if (c is RefName && c.where == null && (c.name in model.refinements || c.name in model.shapes)) continue
                val base = model.baseOfExpr(c) ?: continue
                add(
                    "${decap(r.name)}Candidates", base,
                    "model.rules.getValue(\"${r.name}\").condition", Printer.refExpr(c),
                    "rule ${r.name}", c
                )
            }
            model.nevers.forEachIndexed { i, n ->
                val base = model.baseOfExpr(n.target) ?: return@forEachIndexed
                add(
                    "never${i + 1}Candidates", base,
                    "model.nevers[$i].target", Printer.refExpr(n.target),
                    "never #${i + 1}", n.target
                )
            }
            addAll(byKey.values)
        }

        class Param(val name: String, val kind: QConst, val qconst: String, val kotlin: String)

        fun params(q: Question): List<Param> {
            val used = mutableMapOf<String, Int>()
            return q.holes.map { h ->
                val opWord = when (h.op) {
                    "<" -> "Before"; "<=" -> "AtMost"; ">" -> "After"; ">=" -> "AtLeast"
                    "==" -> "Is"; else -> "Not"
                }
                var base = h.field + opWord
                used[base] = (used[base] ?: 0) + 1
                if (used.getValue(base) > 1) base += used.getValue(base)
                when (h.sample) {
                    is QConst.QDateTime -> Param(base, h.sample, "QConst.QDateTime($base)", "Instant")
                    else -> Param(base, h.sample, "QConst.QDate($base)", "LocalDate")
                }
            }
        }

        fun emit(): String {
            header()
            shapes.forEach { rowType(it) }
            model.captureSchemas.forEach { captureType(it) }
            storeInterface()
            questionsClass()
            overGeneric()
            resolverAdapter()
            mappers()
            return sb.toString()
        }

        fun header() {
            line("// GENERATED by Velle from ${name.lowercase()}.velle — do not edit.")
            line("// Regenerate with: gradle generate")
            line("//")
            line("// The typed store surface (investigate_runtime.md §10): ${name}Store states, in")
            line("// this domain's names and types, every question the runtime can ask of storage.")
            line("// Implement it directly (${name}StoreOverGeneric defaults every method onto a")
            line("// generic backend for selective overriding), and connect it with:")
            line("//   system.connect(${name}StoreResolver(store, system.model), callback)")
            line("package $pkg")
            line()
            line("import velle.*")
            line("import java.math.BigDecimal")
            line("import java.time.Instant")
            line("import java.time.LocalDate")
            line()
        }

        fun rowType(shape: String) {
            line("/** One stored $shape row; references arrive as typed [Ref.Persisted]. */")
            line("data class ${shape}Row(")
            line("    val key: StoreKey,")
            rowFields(shape).forEach { line("    val ${it.name}: ${rowKotlin(it.type)},") }
            line(")")
            line()
        }

        fun captureType(cs: CaptureSchema) {
            line("/** Per-membership memory for ${cs.refinement} (README §8; investigate_runtime.md §7). */")
            line("data class ${cs.refinement}Capture(")
            cs.props.forEach { line("    val ${it.name}: ${rowKotlin(it.type)},") }
            line(")")
            line()
        }

        fun storeInterface() {
            line("/**")
            line(" * Every question the $name spec can ask of storage, typed. Keyed and join")
            line(" * reads must be exact; every `...Candidates` read is under the superset")
            line(" * contract — over-return freely, the runtime re-checks the authoritative")
            line(" * predicate in memory — so a correct implementation may even return all rows.")
            line(" */")
            line("interface ${name}Store {")
            line("    // ── keyed reads ──")
            shapes.forEach { line("    fun ${decap(it)}(key: StoreKey): ${it}Row?") }
            line()
            line("    // ── scan floor: all rows of a shape ──")
            shapes.forEach { line("    fun all${plural(it)}(): List<${it}Row>") }
            line()
            line("    // ── join reads: rows referencing a target through a to-one field ──")
            joins.forEach {
                line("    fun ${decap(it.shape)}sBy${cap(it.field)}(target: Ref.Persisted): List<${it.shape}Row>")
            }
            if (questions.isNotEmpty()) {
                line()
                line("    // ── candidate reads: one per condition with a testable part ──")
                for (q in questions) {
                    val ps = params(q)
                    line("    /** Serves ${q.sources.joinToString("; ")} — `${q.condText}`.")
                    line("     *  Superset contract: any superset of the matching rows is correct. */")
                    line("    fun ${q.method}(${ps.joinToString(", ") { "${it.name}: ${it.kotlin}" }}): List<${q.base}Row>")
                }
            }
            if (model.captureSchemas.isNotEmpty()) {
                line()
                line("    // ── capture memory: null when no current membership (README §13) ──")
                model.captureSchemas.forEach {
                    line("    fun ${decap(it.refinement)}Capture(instance: Ref.Persisted): ${it.refinement}Capture?")
                }
            }
            line("}")
            line()
        }

        fun questionsClass() {
            if (questions.isEmpty()) return
            line("/** The candidate questions as recognizable templates (QTemplate). */")
            line("class ${name}StoreQuestions(model: Model) {")
            questions.forEach {
                line("    val ${it.method} = QTemplate.of(model, \"${it.base}\", ${it.condKotlin})")
            }
            line("}")
            line()
        }

        fun overGeneric() {
            line("/**")
            line(" * ${name}Store over any generic backend — a model-driven store or a framework.")
            line(" * Every method is implemented; override only the questions worth hand-tuning.")
            line(" */")
            line("open class ${name}StoreOverGeneric(")
            line("    private val backend: StateResolver,")
            if (questions.isNotEmpty()) line("    model: Model,") else line("    @Suppress(\"UNUSED_PARAMETER\") model: Model,")
            line(") : ${name}Store {")
            if (questions.isNotEmpty()) line("    private val questions = ${name}StoreQuestions(model)")
            line()
            shapes.forEach {
                line("    override fun ${decap(it)}(key: StoreKey): ${it}Row? = backend.fetchByKey(\"$it\", key)?.to${it}Row()")
            }
            line()
            shapes.forEach {
                line("    override fun all${plural(it)}(): List<${it}Row> = backend.fetchAll(\"$it\").map { it.to${it}Row() }")
            }
            line()
            joins.forEach {
                line("    override fun ${decap(it.shape)}sBy${cap(it.field)}(target: Ref.Persisted): List<${it.shape}Row> =")
                line("        backend.fetchReferencing(\"${it.shape}\", \"${it.field}\", target).map { it.to${it.shape}Row() }")
            }
            for (q in questions) {
                val ps = params(q)
                line()
                line("    override fun ${q.method}(${ps.joinToString(", ") { "${it.name}: ${it.kotlin}" }}): List<${q.base}Row> =")
                line("        backend.fetchCandidates(\"${q.base}\", questions.${q.method}.instantiate(listOf(${ps.joinToString(", ") { it.qconst }})))")
                line("            .map { it.to${q.base}Row() }")
            }
            model.captureSchemas.forEach {
                line()
                line("    override fun ${decap(it.refinement)}Capture(instance: Ref.Persisted): ${it.refinement}Capture? =")
                line("        backend.fetchCaptures(instance, \"${it.refinement}\")?.to${it.refinement}Capture()")
            }
            line("}")
            line()
        }

        fun resolverAdapter() {
            line("/** The bridge the runtime speaks through: generic protocol in, typed store out. */")
            line("class ${name}StoreResolver(")
            line("    private val store: ${name}Store,")
            if (questions.isNotEmpty()) line("    model: Model,") else line("    @Suppress(\"UNUSED_PARAMETER\") model: Model,")
            line(") : StateResolver {")
            if (questions.isNotEmpty()) line("    private val questions = ${name}StoreQuestions(model)")
            line()
            line("    override fun fetchByKey(shape: String, key: StoreKey): Row? = when (shape) {")
            shapes.forEach { line("        \"$it\" -> store.${decap(it)}(key)?.toRow()") }
            line("        else -> null")
            line("    }")
            line()
            line("    override fun fetchAll(shape: String): List<Row> = when (shape) {")
            shapes.forEach { line("        \"$it\" -> store.all${plural(it)}().map { it.toRow() }") }
            line("        else -> emptyList()")
            line("    }")
            line()
            line("    override fun fetchReferencing(shape: String, field: String, target: Ref.Persisted): List<Row> = when {")
            joins.forEach {
                line("        shape == \"${it.shape}\" && field == \"${it.field}\" -> store.${decap(it.shape)}sBy${cap(it.field)}(target).map { it.toRow() }")
            }
            line("        else -> emptyList()")
            line("    }")
            line()
            line("    override fun fetchCandidates(shape: String, filter: QF): List<Row> {")
            if (questions.isNotEmpty()) {
                for (q in questions) {
                    val ps = params(q)
                    val args = ps.mapIndexed { i, p ->
                        if (p.kind is QConst.QDateTime) "h.instant($i)" else "h.date($i)"
                    }
                    line("        if (shape == \"${q.base}\")")
                    line("            questions.${q.method}.match(filter)?.let { h -> return store.${q.method}(${args.joinToString(", ")}).map { it.toRow() } }")
                }
            }
            line("        return fetchAll(shape) // unrecognized filters fall back to the scan floor")
            line("    }")
            line()
            line("    override fun fetchCaptures(instance: Ref.Persisted, refinement: String): Map<String, Any?>? = when (refinement) {")
            model.captureSchemas.forEach {
                line("        \"${it.refinement}\" -> store.${decap(it.refinement)}Capture(instance)?.toValues()")
            }
            line("        else -> null")
            line("    }")
            line("}")
            line()
            if (questions.any { it.holes.isNotEmpty() }) {
                line("private fun List<QConst>.date(i: Int): LocalDate = (this[i] as QConst.QDate).v")
                line("private fun List<QConst>.instant(i: Int): Instant = (this[i] as QConst.QDateTime).v")
                line()
            }
        }

        fun mappers() {
            line("// ── row mappers: the generic wire format <-> the typed rows ──")
            for (s in shapes) {
                val fs = rowFields(s)
                line()
                line("fun Row.to${s}Row(): ${s}Row = ${s}Row(")
                line("    key = key,")
                fs.forEach { line("    ${it.name} = ${fromRaw("fields[\"${it.name}\"]", it.type)},") }
                line(")")
                line()
                line("fun ${s}Row.toRow(): Row = Row(\"$s\", key, buildMap {")
                fs.forEach {
                    if (it.type is VType.Optional) line("    ${it.name}?.let { put(\"${it.name}\", it) }")
                    else line("    put(\"${it.name}\", ${it.name})")
                }
                line("})")
            }
            for (cs in model.captureSchemas) {
                line()
                line("fun Map<String, Any?>.to${cs.refinement}Capture(): ${cs.refinement}Capture = ${cs.refinement}Capture(")
                cs.props.forEach { line("    ${it.name} = ${fromRaw("this[\"${it.name}\"]", it.type)},") }
                line(")")
                line()
                line("fun ${cs.refinement}Capture.toValues(): Map<String, Any?> = buildMap {")
                cs.props.forEach {
                    if (it.type is VType.Optional) line("    ${it.name}?.let { put(\"${it.name}\", it) }")
                    else line("    put(\"${it.name}\", ${it.name})")
                }
                line("}")
            }
        }

        // ── type mapping ─────────────────────────────────────────────────────

        fun rowKotlin(t: VType): String = when (t) {
            is VType.Num -> when (t.name) {
                "integer" -> "Int"; "long" -> "Long"; "double" -> "Double"; else -> "BigDecimal"
            }
            VType.Text -> "String"
            VType.Bool -> "Boolean"
            VType.DateT -> "LocalDate"
            VType.DateTimeT -> "Instant"
            is VType.Inst -> "Ref.Persisted"
            is VType.Optional -> rowKotlin(t.inner) + "?"
            else -> "Any"
        }

        fun fromRaw(expr: String, t: VType): String = when (t) {
            is VType.Num -> when (t.name) {
                "integer" -> "($expr as Number).toInt()"
                "long" -> "($expr as Number).toLong()"
                "double" -> "($expr as Number).toDouble()"
                else -> "$expr as BigDecimal"
            }
            VType.DateT -> "$expr as LocalDate"
            VType.DateTimeT -> "$expr as Instant"
            VType.Text -> "$expr as String"
            VType.Bool -> "$expr as Boolean"
            is VType.Inst -> "$expr as Ref.Persisted"
            is VType.Optional -> when (val inner = t.inner) {
                is VType.Num -> when (inner.name) {
                    "integer" -> "($expr as Number?)?.toInt()"
                    "long" -> "($expr as Number?)?.toLong()"
                    "double" -> "($expr as Number?)?.toDouble()"
                    else -> "$expr as BigDecimal?"
                }
                else -> "$expr as ${rowKotlin(inner)}?"
            }
            else -> expr
        }

        fun decap(s: String) = s.replaceFirstChar { it.lowercase() }
        fun cap(s: String) = s.replaceFirstChar { it.uppercase() }
        fun plural(s: String) = s + "s"
    }
}
