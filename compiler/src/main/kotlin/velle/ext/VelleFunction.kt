package velle.ext

import java.time.ZoneId
import kotlin.reflect.KClass

/**
 * One function a spec may call, as it calls `abs(amount)` or `year(due)`. A
 * predicate is a function whose result is `boolean`. The compiler reads the
 * [signatures] to accept or refuse a call and to type its result; the runtime
 * calls the matched signature's body. Velle's own builtins (`velle.ext.builtin`)
 * are implementations of this interface, registered like any other.
 *
 * A function is a pure function of its arguments: the runtime evaluates it as
 * often as it likes, at every commit it may be relevant to and again on retry,
 * and the compiler may call it on constants with no running system behind it.
 * A body that is only sometimes defined returns null, which the spec sees as
 * an absent value and narrows (Reference §10); it never throws.
 *
 * Registration: name the implementing class in
 * `META-INF/services/velle.ext.VelleFunction` on the classpath. The class
 * needs a public no-argument constructor, or is a Kotlin `object`.
 */
interface VelleFunction {

    /** The name the spec calls: a lowercase identifier, `roundToCents`. Checked by E1. */
    val name: String

    /** The library the function belongs to, a dotted lowercase path such as `velle.finance`.
     *  `namespace.name` is what a `uses` line spells to pick this function over another
     *  library's of the same name, or to rename it. */
    val namespace: String

    val qualifiedName: String get() = "$namespace.$name"

    /** The shapes a call may take. The compiler picks the first whose parameters the
     *  arguments satisfy, and refuses the call naming every signature when none fit. */
    val signatures: List<Signature>

    /**
     * V8's two facts when a rule writes `x = f(x, y)` (Reference §19), claims about
     * the function trusted as an operator's are:
     * - [foldStepsCommute]: `f(f(x, a), b)` equals `f(f(x, b), a)`. Lets V8 accept the
     *   fold on a tick cadence.
     * - [foldIdempotent]: `f(f(x, a), a)` equals `f(x, a)`. Lets V8 accept an unguarded
     *   `after commit` or scheduled fold. True of `max`/`min`; false of most functions.
     */
    val foldStepsCommute: Boolean get() = false
    val foldIdempotent: Boolean get() = false
}

/**
 * One shape of a call: its parameters, what it returns, and the body for it.
 * The body receives one [Typed] per argument, in order, with a variadic
 * parameter's arguments as separate entries; it returns the result as the
 * result type's Kotlin value, or null for absent.
 *
 * [optionalResult]: the result may be absent even when every argument is
 * present (`max` over an empty collection). Without it the result is absent
 * only when an argument is: the runtime never calls the body with an absent
 * argument, so a body never sees null.
 */
class Signature(
    val parameters: List<Parameter>,
    val result: Returns,
    val optionalResult: Boolean = false,
    val apply: (FunctionContext, List<Typed>) -> Any?,
)

/**
 * One parameter. [variadic] is allowed on the last parameter only: it takes one
 * or more arguments, each checked against [accepts]. [takes] is how a refusal
 * names what the parameter wants ("a Date or a DateTime"); without it the
 * compiler lists the types that satisfy [accepts].
 */
class Parameter(
    val name: String,
    val accepts: Accepts,
    val variadic: Boolean = false,
    val takes: String? = null,
)

/** What one parameter accepts. The compiler accepts a call when every argument's
 *  type satisfies its parameter; nothing else decides validity. */
sealed interface Accepts {
    /** The argument's type is an instance of [type]: the type's own class (`DateType::class`,
     *  `MoneyType::class`), or a capability interface any type may implement (`Ordered::class`). */
    data class Is(val type: KClass<*>) : Accepts

    /** A `many` of values whose element type is an instance of [type]. The body receives
     *  the elements as a `List` in the [Typed] value, with the element type. */
    data class ManyOf(val type: KClass<*>) : Accepts

    /** The type the argument in position [index] turned out to have. A constant in that
     *  position takes the type of the first argument that is not a constant. */
    data class SameAs(val index: Int) : Accepts
}

/** What a signature returns. */
sealed interface Returns {
    /** A value of the registered type whose class is [type]. */
    data class Is(val type: KClass<*>) : Returns
    /** The type the argument in position [index] has. */
    data class SameAs(val index: Int) : Returns
    /** The element type of the `many` in position [index]. */
    data class ElementOf(val index: Int) : Returns
}

/** One argument as a body receives it: the registered type and the value in that
 *  type's Kotlin class. For an [Accepts.ManyOf] parameter, [value] is a `List` of
 *  element values and [type] is the element type. */
class Typed(val type: VelleType<*>, val value: Any) {
    /** The type cast to the capability the parameter declared: `args[0].capability<Ordered<Any>>()`. */
    @Suppress("UNCHECKED_CAST")
    fun <C> capability(): C = type as C
    @Suppress("UNCHECKED_CAST")
    val elements: List<Any> get() = value as List<Any>
}

/**
 * What a body may ask of the system it runs in. At compile time, when the
 * compiler folds a call over constants, there is no system: [zone] throws, so
 * a body that needs it is one the compiler cannot fold.
 */
interface FunctionContext {
    /** The zone the system runs in, the one `today` reads its date in. */
    val zone: ZoneId

    companion object {
        /** The compile-time context: no system, no zone. */
        val COMPILE_TIME: FunctionContext = object : FunctionContext {
            override val zone: ZoneId get() = throw IllegalStateException("no system zone at compile time")
        }
        fun of(zone: ZoneId): FunctionContext = object : FunctionContext { override val zone = zone }
    }
}
