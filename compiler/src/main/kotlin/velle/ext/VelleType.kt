package velle.ext

import java.math.BigDecimal
import kotlin.reflect.KClass

/**
 * One scalar type a spec may name in type position, as it names `Date`.
 * Nothing here is about storage: how a store holds a value of this type is a
 * store binding (`velle.sql.SqlTypeBinding` for the SQL stores),
 * written by whoever knows the store.
 *
 * A type is one value and a mapping for it, never a business concept: business
 * concepts live in the spec as shapes and rules. A value with parts (an amount
 * and its currency) is a shape with two fields, not a type. Types exist so the
 * binding can carry the data types of diverse systems in and out of Velle
 * without the language carrying lower-level mappings itself.
 *
 * Each member is read by a named part of the compiler. The capabilities beyond
 * these members are interfaces the class implements beside this one
 * (`Capabilities.kt`): [Ordered] for `< <= > >=`, `max`/`min`, a selector's
 * `by` and a query order; [Summable] for `sum`; and the ones registered
 * functions ask for. A capability this type does not implement is one a spec
 * cannot use: the compiler refuses the spec and names this class and the
 * interface to add (checks.md, E3).
 *
 * Registration: name the implementing class in
 * `META-INF/services/velle.ext.VelleType` on the classpath (Java's ServiceLoader).
 * The class needs a public no-argument constructor, or is a Kotlin `object`.
 */
interface VelleType<T : Any> {

    /** The name the spec writes by default: a capitalized identifier, `Money`. Checked by E1. */
    val name: String

    /** The library the type belongs to, a dotted lowercase path such as `velle.finance`.
     *  `namespace.name` is the qualified name a `uses` line spells: how a spec picks
     *  this `Money` over another library's, or renames it. */
    val namespace: String

    /** `namespace.name`, the one name the registry and the operator table key on. */
    val qualifiedName: String get() = "$namespace.$name"

    /** The class values have on the generated surface (act input classes, model
     *  objects, the typed store's rows) and inside the runtime. A committed value
     *  of another class is a type-level refusal at the boundary. */
    val kotlinClass: KClass<T>

    /**
     * Well-formedness beyond the class, checked at the boundary on every committed
     * value: the reason the value is refused (`type: Money — ...`), or null. A
     * refusal here has no `never` behind it and no rejection data: it is the same
     * kind of refusal as a missing required field. Business rules are `never`s,
     * not this; put "a balance is never negative" in the spec, where the product
     * owner can read it.
     */
    fun validate(value: T): String? = null

    /** The literal forms the spec may write where a `T` is expected. */
    val literals: Literals<T>

    /** Equality. Default Kotlin `==`. Must agree with `canonical` and, when
     *  ordered, with `compare == 0` (E2). */
    fun equal(a: T, b: T): Boolean = a == b

    /** Canonical text: one string per distinct value. The serialization-domain
     *  key (evaluation.md, serialization domains) and E2's witness. */
    fun canonical(value: T): String

    /** How a value reads in a rejection message (Reference §21). */
    fun display(value: T): String = canonical(value)

    /** The arithmetic this type takes part in. Empty means none: `balance + amount`
     *  is refused with E3. */
    val operators: List<Operator<*, *, *>>

    /** What the test generator needs to write a case that commits a value. */
    val samples: Samples<T>
}

/** Thrown by a literal reader for a constant the type does not accept; the
 *  compiler reports [message] as F2 at the literal. */
class LiteralRejected(message: String) : RuntimeException(message)

/**
 * Which of the spec's literal forms this type reads: a number or a quoted text,
 * the two Velle has. A reader returns the value or throws [LiteralRejected];
 * the compiler reports the reason as F2 at the literal, since literals are
 * compile-time constants. Both null: a spec can compare two fields of this type
 * but never write a constant of it. A reader parses one value; it does not
 * split a text into parts, because a value with parts is a shape.
 */
class Literals<T : Any>(
    /** `0`, `12.50`, `-3` where a `T` is expected (`balance < 0`, `initially 0`). */
    val fromNumber: ((BigDecimal) -> T)? = null,
    /** `"USD"`, `"555-0100"` where a `T` is expected. */
    val fromText: ((String) -> T)? = null,
) {
    companion object { fun <T : Any> none() = Literals<T>() }
}

/**
 * One line of the operator table: `left op right` has type `result` and this
 * implementation. Operand and result types are named by qualified name: a
 * builtin by its keyword (`"decimal"`, `"integer"`, `"text"`) and a registered
 * type by `namespace.name` (`"velle.finance.Money"`), never by the name a spec
 * happens to give it, since a `uses` line can rename it. Both directions of a
 * mixed operation are separate lines (`Money * decimal`, `decimal * Money`).
 *
 * The two fold facts are what V8 reads when a rule writes `x = x op y` and `x`
 * has this type (Reference §19). They are claims about *this operator*, checked
 * on the type's samples (E2) and otherwise trusted, exactly as `tolerates` is:
 * - [foldStepsCommute]: the order the step is applied in does not matter:
 *   `(x op a) op b` equals `(x op b) op a` for all a, b. True of `+` and `-`
 *   over a group, of `max`/`min`, of set union. Lets V8 accept the fold on a
 *   tick cadence.
 * - [foldIdempotent]: applying the same step twice changes nothing:
 *   `(x op a) op a` equals `x op a`. True of `max`/`min`, union, boolean or;
 *   false of `+`. Lets V8 accept an unguarded `after commit` or scheduled fold.
 *
 * An operator is total over its declared operand types: it returns a value for
 * every pair. There is no failure channel on purpose: an exception from an
 * extension is a transaction error and a bug in the extension. An operation
 * that is only sometimes defined is not an operator; it is a function returning
 * an optional.
 */
class Operator<L : Any, R : Any, O : Any>(
    val op: String,
    val left: String,
    val right: String,
    val result: String,
    val apply: (L, R) -> O,
    val foldStepsCommute: Boolean,
    val foldIdempotent: Boolean,
) {
    @Suppress("UNCHECKED_CAST")
    internal fun applyAny(l: Any, r: Any): Any = apply(l as L, r as R)
}

/**
 * What the test generator needs (testgen.md):
 * - [sample]: an acceptable value, for an act field no `never` constrains.
 * - [below]/[above]: the nearest acceptable value strictly below/above `v`, or
 *   null at the type's edge. Only read when the type is ordered; for a `never`
 *   over `balance < 0` the generator commits `below(0)` expecting refusal and
 *   `0` expecting acceptance. For a discrete type this is one step (a cent);
 *   for a continuous one any strictly smaller/larger value.
 * - [kotlinSource]: a Kotlin expression constructing `v` in a generated test,
 *   with the imports it needs. `Money.of("12.50")` with `import velle.finance.Money`.
 */
class Samples<T : Any>(
    val sample: T,
    val below: ((T) -> T?)? = null,
    val above: ((T) -> T?)? = null,
    val kotlinSource: (T) -> String,
    val imports: List<String> = emptyList(),
)
