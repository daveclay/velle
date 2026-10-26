package velle

import velle.ext.Accepts
import velle.ext.FunctionContext
import velle.ext.Ordered
import velle.ext.Returns
import velle.ext.Signature
import velle.ext.Typed
import velle.ext.VelleFunction
import velle.ext.VelleType
import velle.ext.builtin.DecimalType
import java.math.BigDecimal

/**
 * Matching a call against a registered function's signatures (Reference §5;
 * `velle.ext.VelleFunction`), in one place for the validator, the evaluator,
 * and the compile-time folds: the same inputs give the same signature.
 *
 * An argument is its scalar [VType] (null when the validator could not type
 * it, which another diagnostic already reports) and whether it is a bare
 * constant, since a constant takes the type of the argument it is paired with
 * through [Accepts.SameAs] (`max(0, balance)` with `balance: Money` reads `0`
 * as a `Money`).
 */
class FunctionResolver(private val registry: TypeRegistry, private val elementTypeOf: (VType.CollS) -> VType?) {

    class Arg(val type: VType?, val constant: Boolean)

    sealed interface Outcome {
        /** The matched signature, the result type (optional when an argument is or the
         *  signature says so), and the resolved type of each argument (a constant's is the
         *  type it is read as; a `many`'s is the collection). */
        class Ok(val signature: Signature, val result: VType, val argTypes: List<VType?>) : Outcome
        /** No signature fits: one reason per signature, the likeliest first. */
        class NoMatch(val reasons: List<Reason>) : Outcome
    }

    /** Why one signature refused the call. [code] is E3 when the argument is an extension
     *  type missing a capability (the fix is in its class), F2 otherwise. [arity] marks a
     *  refusal on the number of arguments alone, reported only when no signature took that many. */
    class Reason(val code: String, val message: String, val arity: Boolean = false)

    fun resolve(fn: VelleFunction, call: String, args: List<Arg>, argText: (Int) -> String): Outcome {
        val reasons = mutableListOf<Reason>()
        for (sig in fn.signatures) {
            when (val r = tryMatch(fn, sig, call, args, argText)) {
                is Outcome.Ok -> return r
                is Reason -> reasons.add(r)
            }
        }
        // a signature that took the arguments' number explains better than one that did not
        return Outcome.NoMatch(reasons.sortedBy { it.arity })
    }

    private fun bare(t: VType?): VType? = (t as? VType.Optional)?.inner ?: t

    private fun tryMatch(fn: VelleFunction, sig: Signature, call: String, args: List<Arg>, argText: (Int) -> String): Any {
        val params = sig.parameters
        val variadic = params.lastOrNull()?.variadic == true
        val fixed = if (variadic) params.size - 1 else params.size
        if ((!variadic && args.size != params.size) || (variadic && args.size <= fixed))
            return Reason("F2", "'$call' — '${fn.name}' takes ${arity(sig)}", arity = true)
        fun paramFor(i: Int) = if (i < params.size) params[i] else params.last()

        // the type each `same as n` group resolves to: the first non-constant argument in the
        // group, else the first typed one (a group of constants keeps the constant's own type)
        val anchors = HashMap<Int, VType?>()
        fun anchor(idx: Int): VType? = anchors.getOrPut(idx) {
            val members = args.indices.filter { i -> i == idx || (paramFor(i).accepts as? Accepts.SameAs)?.index == idx }
            members.firstOrNull { !args[it].constant && args[it].type != null }?.let { bare(args[it].type) }
                ?: members.firstOrNull { args[it].type != null }?.let { bare(args[it].type) }
        }

        val resolved = ArrayList<VType?>(args.size)
        for (i in args.indices) {
            val p = paramFor(i)
            val t = bare(args[i].type)
            when (val a = p.accepts) {
                is Accepts.Is -> {
                    if (t == null) { resolved.add(null); continue }
                    val obj = registry.typeFor(t)
                    if (t is VType.CollS || obj == null)
                        return Reason("F2", "'$call' — '${fn.name}' takes ${p.takes ?: describe(a.type)}, but '${argText(i)}' is ${describeArg(t)}")
                    if (!a.type.isInstance(obj)) return missing(fn, call, obj, a.type, p, argText(i))
                    resolved.add(t)
                }
                is Accepts.ManyOf -> {
                    if (t == null) { resolved.add(null); continue }
                    val elem = (t as? VType.CollS)?.let { elementTypeOf(it) }
                    val obj = elem?.let { registry.typeFor(it) }
                    if (obj == null)
                        return Reason("F2", "'$call' — '${fn.name}' takes ${p.takes ?: "a many of " + describe(a.type)}, but '${argText(i)}' is ${describeArg(t)}")
                    if (!a.type.isInstance(obj)) return missing(fn, call, obj, a.type, p, argText(i), plural = true)
                    resolved.add(t)
                }
                is Accepts.SameAs -> {
                    val g = anchor(a.index)
                    if (g == null || t == null) { resolved.add(g); continue }
                    if (args[i].constant) {
                        val obj = registry.typeFor(g)
                        if (obj != null && !readsConstant(obj, t))
                            return Reason("F2", "'$call' — '${fn.name}' takes one type; '${argText(i)}' is not a ${describeType(obj)}")
                        resolved.add(g); continue
                    }
                    if (t != g && !(t is VType.Num && g is VType.Num))
                        return Reason("F2", "'$call' — '${fn.name}' takes one type; '${argText(i)}' is not ${describeArg(g)}")
                    resolved.add(t)
                }
            }
        }
        val result: VType = when (val r = sig.result) {
            is Returns.Is -> registry.typeOfClass(r.type)?.let { registry.vtypeOf(it) }
                ?: return Reason("F2", "'$call' — '${fn.qualifiedName}' returns ${r.type.qualifiedName}, which is not a registered type")
            is Returns.SameAs -> anchor(r.index) ?: return Outcome.Ok(sig, VType.Unknown, resolved)
            is Returns.ElementOf -> (bare(args.getOrNull(r.index)?.type) as? VType.CollS)?.let { elementTypeOf(it) }
                ?: return Outcome.Ok(sig, VType.Unknown, resolved)
        }
        val optional = sig.optionalResult || args.any { it.type is VType.Optional }
        return Outcome.Ok(sig, if (optional && result !is VType.Optional && result != VType.Unknown) VType.Optional(result) else result, resolved)
    }

    private fun missing(fn: VelleFunction, call: String, obj: VelleType<*>, k: kotlin.reflect.KClass<*>, p: velle.ext.Parameter, arg: String, plural: Boolean = false): Reason {
        val isExt = registry.vtypeOf(obj) is VType.Ext
        val what = if (plural) "them" else "it"
        return if (isExt) {
            val cap = if (k == Ordered::class) "declares no ordering" else "does not implement `${k.simpleName}`"
            Reason("E3", "'$call' — ${describeType(obj)} $cap, so '${fn.name}' is not defined on $what; implement `${k.simpleName}` on the type")
        } else if (k == Ordered::class) {
            Reason("F2", "'$call' — '${obj.name}' values have no order, so '${fn.name}' is not defined on $what (Reference §5)")
        } else {
            Reason("F2", "'$call' — '${fn.name}' takes ${p.takes ?: describe(k)}, but '$arg' is ${if (plural) "a many of ${obj.name}" else describeType(obj)}")
        }
    }

    private fun readsConstant(obj: VelleType<*>, constantType: VType): Boolean = when (constantType) {
        is VType.Num -> obj.literals.fromNumber != null
        VType.Text -> obj.literals.fromText != null
        else -> registry.vtypeOf(obj) == constantType
    }

    /** "a Date or a DateTime": the types that satisfy [k], the builtins first. */
    fun describe(k: kotlin.reflect.KClass<*>): String {
        val names = registry.typesSatisfying(k).map { describeType(it) }
        return when (names.size) {
            0 -> "a value implementing ${k.simpleName}"
            1 -> names.single()
            else -> names.dropLast(1).joinToString(", ") + " or " + names.last()
        }
    }

    private fun describeType(t: VelleType<*>): String =
        if (registry.vtypeOf(t) is VType.Ext) "'${t.name}' (${t::class.qualifiedName})" else article(t.name)

    private fun describeArg(t: VType?): String = when (t) {
        null -> "untyped"
        is VType.CollS -> "a many of ${t.name}"
        is VType.Inst, is VType.Coll -> "a record"
        else -> registry.typeFor(t)?.let { describeType(it) } ?: "a value of another type"
    }

    private fun article(name: String) = if (name[0].lowercaseChar() in "aeiou") "an $name" else "a $name"

    private fun arity(sig: Signature): String {
        val n = sig.parameters.size
        val last = sig.parameters.lastOrNull()
        return when {
            last?.variadic == true && n == 2 -> "two or more arguments, ${sig.parameters[0].takes ?: last.takes ?: describe((sig.parameters[0].accepts as? Accepts.Is)?.type ?: Any::class)}"
            last?.variadic == true -> "$n or more arguments"
            n == 1 -> "one argument, ${last?.takes ?: "a value"}"
            else -> "$n arguments"
        }
    }

    companion object {
        /** A bare constant in a call: a number, a text, or a negated number. */
        fun isConstant(e: Expr): Boolean = when (e) {
            is IntLit, is DecLit, is TextLit -> true
            is UnaryMinus -> isConstant(e.inner)
            else -> false
        }

        /** The compile-time fold of [fn] over number constants: the matched signature's body
         *  with no system behind it, or null when the call does not fold to a number. */
        fun foldNumbers(registry: TypeRegistry, fn: VelleFunction, values: List<BigDecimal>): BigDecimal? {
            val resolver = FunctionResolver(registry) { null }
            val args = values.map { Arg(VType.Num("decimal"), constant = true) }
            val ok = resolver.resolve(fn, fn.name, args) { "" } as? FunctionResolver.Outcome.Ok ?: return null
            return runCatching { ok.signature.apply(FunctionContext.COMPILE_TIME, values.map { Typed(DecimalType, it) }) }.getOrNull() as? BigDecimal
        }
    }
}
