package velle.ext

import java.time.LocalDate
import java.time.LocalTime

/**
 * The capabilities a type declares by implementing an interface beside
 * [VelleType]. Each carries the one operation the compiler, the runtime, or a
 * registered function needs, so a function body is written once against the
 * interface and never against a concrete type: `max` is defined on anything
 * [Ordered], `year` on anything with a [HasCalendarDate], an engineer's
 * `BusinessDate` joins both by implementing them.
 *
 * A capability this type does not implement is one a spec cannot use on it:
 * the compiler refuses the spec and names the class and the interface to add
 * (checks.md, E3). Velle's own builtin types implement the same interfaces;
 * `velle.ext.builtin` is the reference implementation of each.
 */

/**
 * A total order over every acceptable value. Every consumer of ordering reads
 * this one member: `< <= > >=`, a selector's `by`, `latest`/`first`, a query
 * order, `max`/`min`, and the boundary cases the test generator derives. A type
 * whose values are only sometimes comparable (money in two currencies) is not
 * ordered. Must agree with [VelleType.equal]: `compare == 0` exactly when
 * `equal` (E2).
 */
interface Ordered<T : Any> {
    val ordering: Comparator<T>
}

/** `sum(collection, field)` over a field of this type: the empty collection sums to
 *  [zero]; [plus] must be associative and commutative (a collection has no order), checked on samples (E2). */
interface Summable<T : Any> {
    val zero: T
    fun plus(a: T, b: T): T
}

/** A number, in any representation: a function over numbers reads one through
 *  [toDecimal] and builds its result through [fromDecimal]. The builtin number
 *  types implement it over `BigDecimal` directly. */
interface Numeric<T : Any> {
    fun toDecimal(v: T): java.math.BigDecimal
    fun fromDecimal(d: java.math.BigDecimal): T
}

/** A value with a sign: `abs` gives it without one. */
interface Signed<T : Any> {
    fun abs(v: T): T
}

/** A value that names a calendar date: `year`, `month`, `day`, `dayOfWeek` read it.
 *  A moment reads its date in the system's zone, which [context] carries. */
interface HasCalendarDate<T : Any> {
    fun calendarDate(v: T, context: FunctionContext): LocalDate
}

/** A value that names a time of day: `hour`, `minute` read it.
 *  A moment reads its time in the system's zone, which [context] carries. */
interface HasTimeOfDay<T : Any> {
    fun timeOfDay(v: T, context: FunctionContext): LocalTime
}
