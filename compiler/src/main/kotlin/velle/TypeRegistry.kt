package velle

import velle.ext.LiteralRejected
import velle.ext.Operator
import velle.ext.VelleType
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.ServiceLoader

/**
 * The extension types a process knows (OQ65): every `velle.ext.VelleType` named
 * in `META-INF/services/velle.ext.VelleType` on the classpath, loaded once and
 * handed to every [Model]. A registry with no extensions is the builtin
 * behaviour exactly.
 *
 * The registry is checked before any spec is (checks.md, E-series):
 * - E1: each type's name is a capitalized identifier and not a builtin, its
 *   namespace is a dotted lowercase path, and `namespace.name` is unique; no
 *   two types declare the same operator line.
 * - E2: each type's claims hold on its own samples.
 *
 * The builtin scalars are rows of the same tables where the compiler consults
 * them generically: [foldFacts] answers V8 for `decimal + ...` and `Money + ...`
 * alike; [isBuiltin] and [BUILTIN_NAMES] are the closed list Reference §5 gives.
 */
class TypeRegistry(types: List<VelleType<*>>) {

    val diagnostics: List<Diagnostic>

    /** Every registered type, by `namespace.name`. */
    val byQualifiedName: Map<String, VelleType<*>>

    /** The operator table across every registered type, keyed (left, op, right). */
    private val operators: Map<Triple<String, String, String>, Operator<*, *, *>>

    init {
        val diags = mutableListOf<Diagnostic>()
        val byQ = linkedMapOf<String, VelleType<*>>()
        for (t in types) {
            if (!NAME.matches(t.name))
                diags.add(Diagnostic("E1", "registered type '${t.qualifiedName}' (${t::class.qualifiedName}): " +
                    "its name '${t.name}' is not a capitalized identifier"))
            else if (t.name in BUILTIN_NAMES)
                diags.add(Diagnostic("E1", "registered type '${t.qualifiedName}' (${t::class.qualifiedName}): " +
                    "'${t.name}' is a builtin type name (Reference §5)"))
            if (!NAMESPACE.matches(t.namespace))
                diags.add(Diagnostic("E1", "registered type '${t.qualifiedName}' (${t::class.qualifiedName}): " +
                    "its namespace '${t.namespace}' is not a dotted lowercase path such as 'velle.finance'"))
            val prior = byQ.put(t.qualifiedName, t)
            if (prior != null)
                diags.add(Diagnostic("E1", "two registered types claim '${t.qualifiedName}': " +
                    "${prior::class.qualifiedName} and ${t::class.qualifiedName}"))
        }
        byQualifiedName = byQ
        val ops = linkedMapOf<Triple<String, String, String>, Operator<*, *, *>>()
        for (t in byQ.values) for (o in t.operators) {
            val key = Triple(o.left, o.op, o.right)
            if (ops.put(key, o) != null)
                diags.add(Diagnostic("E1", "two registered types declare the operator '${o.left} ${o.op} ${o.right}' " +
                    "(the second is '${t.qualifiedName}', ${t::class.qualifiedName})"))
            for (operand in listOf(o.left, o.right, o.result))
                if (operand !in BUILTIN_NAMES && operand !in byQ)
                    diags.add(Diagnostic("E1", "registered type '${t.qualifiedName}' declares the operator " +
                        "'${o.left} ${o.op} ${o.right} -> ${o.result}' over '$operand', which is neither a builtin nor a registered type"))
        }
        operators = ops
        if (diags.isEmpty()) for (t in byQ.values) diags.addAll(ClaimsCheck(t, this).run())
        diagnostics = diags
    }

    operator fun get(qualifiedName: String): VelleType<*>? = byQualifiedName[qualifiedName]

    /** The registered type whose class [value] is an instance of, or null (for rendering a raw value). */
    fun typeOfValue(value: Any): VelleType<*>? = byQualifiedName.values.firstOrNull { it.kotlinClass.isInstance(value) }

    /** The spec-facing name of a builtin or registered type in an E3 sentence. */
    fun describe(qualifiedName: String): String = byQualifiedName[qualifiedName]?.let { "'${it.name}' (${it::class.qualifiedName})" } ?: "'$qualifiedName'"

    /** The registered types that share the unqualified [name]. */
    fun named(name: String): List<VelleType<*>> = byQualifiedName.values.filter { it.name == name }

    /** The one operator line `left op right`, or null when no type declares it. */
    fun operator(left: String, op: String, right: String): Operator<*, *, *>? = operators[Triple(left, op, right)]

    /** Every operator line with [left] on the left and [op] as its operator (for typing an untyped right operand). */
    fun operatorsFrom(left: String, op: String): List<Operator<*, *, *>> =
        operators.values.filter { it.left == left && it.op == op }

    /** Every operator line with [right] on the right and [op] as its operator (for typing an untyped left operand). */
    fun operatorsTo(right: String, op: String): List<Operator<*, *, *>> =
        operators.values.filter { it.right == right && it.op == op }

    /**
     * V8's two facts for `x = x op y` where `x` has type [left] (Reference §19):
     * whether the step commutes with other steps, and whether it is idempotent;
     * null when no such operator exists. The builtin numbers' `+` and `-`
     * commute and are not idempotent; `*` and `/` are not claimed.
     */
    fun foldFacts(left: String, op: String): FoldFacts? {
        if (left in NUMBER_NAMES) return if (op == "+" || op == "-") FoldFacts(commutes = true, idempotent = false) else null
        val lines = operatorsFrom(left, op)
        if (lines.isEmpty()) return null
        return FoldFacts(commutes = lines.all { it.foldStepsCommute }, idempotent = lines.all { it.foldIdempotent })
    }

    data class FoldFacts(val commutes: Boolean, val idempotent: Boolean)

    companion object {
        /** The builtin scalars (Reference §5), by the name a spec writes. */
        val BUILTIN_NAMES: Set<String> = setOf("text", "integer", "long", "decimal", "double", "boolean", "Date", "DateTime", "uuid", "duration", "time", "bytes")
        val NUMBER_NAMES: Set<String> = setOf("integer", "long", "decimal", "double")

        private val NAME = Regex("[A-Z][A-Za-z0-9]*")
        private val NAMESPACE = Regex("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*")

        fun isBuiltin(name: String) = name in BUILTIN_NAMES

        /** A registry with no extensions: the builtin behaviour. */
        val EMPTY = TypeRegistry(emptyList())

        /** Every type on the classpath, loaded once per process. */
        val default: TypeRegistry by lazy { load() }

        fun load(loader: ClassLoader = TypeRegistry::class.java.classLoader): TypeRegistry =
            TypeRegistry(ServiceLoader.load(VelleType::class.java, loader).toList())

        /** Sample values of a builtin or registered type, for E2's checks over mixed operators. */
        internal fun samplesOf(name: String, registry: TypeRegistry): List<Any> = when (name) {
            "text" -> listOf("a", "b")
            "boolean" -> listOf(true, false)
            "Date" -> listOf(LocalDate.of(2026, 1, 15), LocalDate.of(2026, 3, 1))
            "DateTime" -> listOf(Instant.parse("2026-01-15T00:00:00Z"), Instant.parse("2026-03-01T00:00:00Z"))
            "uuid" -> listOf(java.util.UUID.fromString("00000000-0000-4000-8000-000000000001"), java.util.UUID.fromString("00000000-0000-4000-8000-000000000002"))
            "duration" -> listOf(java.time.Duration.ofDays(1), java.time.Duration.ofDays(2))
            "time" -> listOf(java.time.LocalTime.of(9, 30), java.time.LocalTime.of(17, 0))
            "bytes" -> listOf(byteArrayOf(1, 2), byteArrayOf(3))
            in NUMBER_NAMES -> listOf(BigDecimal("2"), BigDecimal("3"))
            else -> registry[name]?.let { ClaimsCheck.sampleSet(it) } ?: emptyList()
        }
    }
}

/** What reading a spec constant as an extension type produced (Reference §5; OQ65 D2). */
sealed interface LiteralRead {
    data class Ok(val value: Any) : LiteralRead
    /** The type declares no reader for this literal form (`number` or `text`). */
    data class NoReader(val form: String) : LiteralRead
    /** The type's reader refused the constant, with its reason. */
    data class Rejected(val reason: String) : LiteralRead
}

/** Read a number or a text constant as a value of this type, through the reader it declares. */
fun VelleType<*>.readLiteral(number: BigDecimal?, text: String?): LiteralRead {
    return try {
        when {
            number != null -> literals.fromNumber?.let { LiteralRead.Ok(it(number)) } ?: LiteralRead.NoReader("number")
            text != null -> literals.fromText?.let { LiteralRead.Ok(it(text)) } ?: LiteralRead.NoReader("text")
            else -> LiteralRead.NoReader("literal")
        }
    } catch (e: LiteralRejected) {
        LiteralRead.Rejected(e.message ?: "rejected")
    }
}

/** Equality under the type's own `equal`, for two values already known to be of this type. */
@Suppress("UNCHECKED_CAST")
fun <T : Any> VelleType<T>.equalAny(a: Any, b: Any): Boolean = equal(a as T, b as T)

@Suppress("UNCHECKED_CAST")
fun <T : Any> VelleType<T>.compareAny(a: Any, b: Any): Int =
    (ordering ?: throw VelleRuntimeError("'$name' declares no ordering")).compare(a as T, b as T)

@Suppress("UNCHECKED_CAST")
fun <T : Any> VelleType<T>.canonicalAny(v: Any): String = canonical(v as T)

@Suppress("UNCHECKED_CAST")
fun <T : Any> VelleType<T>.displayAny(v: Any): String = display(v as T)

@Suppress("UNCHECKED_CAST")
fun <T : Any> VelleType<T>.validateAny(v: Any): String? = validate(v as T)

@Suppress("UNCHECKED_CAST")
fun <T : Any> VelleType<T>.kotlinSourceAny(v: Any): String = samples.kotlinSource(v as T)

@Suppress("UNCHECKED_CAST")
fun <T : Any> VelleType<T>.belowAny(v: Any): Any? = samples.below?.invoke(v as T)

@Suppress("UNCHECKED_CAST")
fun <T : Any> VelleType<T>.aboveAny(v: Any): Any? = samples.above?.invoke(v as T)

@Suppress("UNCHECKED_CAST")
fun <T : Any> VelleType<T>.sumAny(a: Any, b: Any): Any =
    (sum ?: throw VelleRuntimeError("'$name' declares no sum")).plus(a as T, b as T)

/**
 * E2: a type's claims checked on the values the type itself hands over. The
 * compiler cannot prove an engineer's Kotlin, but it can refuse a type whose
 * claims fail on its own samples. Each clause names the Kotlin class, because
 * the fix is there, not in any spec.
 */
private class ClaimsCheck<T : Any>(private val type: VelleType<T>, private val registry: TypeRegistry) {
    private val out = mutableListOf<Diagnostic>()
    private val where = "'${type.qualifiedName}' (${type::class.qualifiedName})"

    private fun fail(claim: String) = out.add(Diagnostic("E2", "registered type $where: $claim"))

    @Suppress("UNCHECKED_CAST")
    fun run(): List<Diagnostic> {
        val s = type.samples
        val sample = s.sample
        if (!type.kotlinClass.isInstance(sample)) fail("its sample is not a ${type.kotlinClass.qualifiedName}")
        type.validate(sample)?.let { fail("its sample fails its own validate: $it") }
        if (!type.equal(sample, sample)) fail("equal(sample, sample) is false")
        if (type.canonical(sample).isEmpty()) fail("canonical(sample) is empty")

        val values = sampleSet(type) as List<T>
        val ordering = type.ordering
        if (ordering != null) {
            if (ordering.compare(sample, sample) != 0) fail("ordering.compare(sample, sample) is not 0")
            s.below?.invoke(sample)?.let { b ->
                if (ordering.compare(b, sample) >= 0) fail("below(sample) does not order strictly below the sample")
                if (ordering.compare(sample, b) <= 0) fail("ordering is not antisymmetric on (sample, below(sample))")
                if (type.equal(b, sample)) fail("below(sample) is equal to the sample")
            }
            s.above?.invoke(sample)?.let { a ->
                if (ordering.compare(a, sample) <= 0) fail("above(sample) does not order strictly above the sample")
                if (ordering.compare(sample, a) >= 0) fail("ordering is not antisymmetric on (sample, above(sample))")
            }
            for (a in values) for (b in values)
                if ((ordering.compare(a, b) == 0) != type.equal(a, b))
                    fail("ordering.compare == 0 disagrees with equal on (${type.canonical(a)}, ${type.canonical(b)})")
        } else if (s.below != null || s.above != null) {
            fail("declares below/above samples but no ordering; the boundary values are never read")
        }
        for (a in values) for (b in values)
            if (type.equal(a, b) != (type.canonical(a) == type.canonical(b)))
                fail("equal disagrees with canonical on (${type.canonical(a)}, ${type.canonical(b)})")

        type.literals.fromText?.let { read ->
            val c = type.canonical(sample)
            try {
                if (!type.equal(read(c), sample)) fail("fromText(canonical(sample)) is not equal to the sample")
            } catch (e: LiteralRejected) {
                fail("fromText rejects the sample's own canonical text '$c': ${e.message}")
            }
        }
        type.literals.fromNumber?.let { read ->
            val c = type.canonical(sample)
            val n = c.toBigDecimalOrNull()
            if (n != null) try {
                if (!type.equal(read(n), sample)) fail("fromNumber(canonical(sample)) is not equal to the sample")
            } catch (e: LiteralRejected) {
                fail("fromNumber rejects the sample's own canonical number $c: ${e.message}")
            }
        }

        type.sum?.let { sum ->
            if (!type.equal(sum.plus(sum.zero, sample), sample)) fail("sum: zero + sample is not the sample")
            for (a in values) for (b in values)
                if (!type.equal(sum.plus(a, b), sum.plus(b, a)))
                    fail("sum.plus is not commutative on (${type.canonical(a)}, ${type.canonical(b)})")
            if (values.size >= 3) {
                val (a, b, c) = values
                if (!type.equal(sum.plus(sum.plus(a, b), c), sum.plus(a, sum.plus(b, c))))
                    fail("sum.plus is not associative on its samples")
            }
        }

        for (o in type.operators) {
            if (o.left != type.qualifiedName || o.result != type.qualifiedName) continue // only `x = x op y` folds are claims about this type
            val steps = TypeRegistry.samplesOf(o.right, registry)
            if (steps.isEmpty()) continue
            val equalResult = { a: Any, b: Any -> type.equal(a as T, b as T) }
            if (o.foldStepsCommute) for (x in values) for (a in steps) for (b in steps)
                if (!equalResult(o.applyAny(o.applyAny(x, a), b), o.applyAny(o.applyAny(x, b), a)))
                    fail("operator '${o.op}' claims foldStepsCommute but (x ${o.op} a) ${o.op} b differs from (x ${o.op} b) ${o.op} a on its samples")
            if (o.foldIdempotent) for (x in values) for (a in steps)
                if (!equalResult(o.applyAny(o.applyAny(x, a), a), o.applyAny(x, a)))
                    fail("operator '${o.op}' claims foldIdempotent but (x ${o.op} a) ${o.op} a differs from x ${o.op} a on its samples")
        }
        return out
    }

    companion object {
        /** The sample, its neighbours when the type gives them, and the sum's zero: the values E2 checks on. */
        fun <T : Any> sampleSet(type: VelleType<T>): List<T> {
            val s = type.samples
            return listOfNotNull(s.sample, s.below?.invoke(s.sample), s.above?.invoke(s.sample), type.sum?.zero)
                .distinctBy { type.canonical(it) }
        }
    }
}
