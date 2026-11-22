package velle.validation

import velle.*
import velle.ext.builtin.ReadRegex

// ── function calls (Reference §5; OQ65 D4; checks F1, F2, E3) ───────────────
//
// The builtin functions, each keyed by the capability its arguments need (OQ65 D4):
// `lowercase` needs builtin text; `max`/`min` need one ordered type across every
// argument, with constants read as that type. The dispatch Custom functions (OQ66)
// will register into.

/** The signature a call matches, or why none does; null when the name resolves to no function
 *  (reported once, by [checkFunctionCall]). */
private fun Validator.resolveCall(call: FunCall, scope: String): FunctionResolver.Outcome? = model.types.resolveCall(call, scope, typeEnv())

/**
 * A call of a builtin or registered function (Reference §5; `velle.ext.VelleFunction`):
 * the name resolves, one signature accepts the arguments' types, and a constant beside
 * an extension argument reads as that type. E3 when an extension type lacks the
 * capability a parameter asks for; F2 otherwise.
 */
internal fun Validator.checkFunctionCall(call: FunCall, scope: String) {
    val function = model.resolveFunctionName(call.name, "'${Printer.expr(call)}'")
    if (function == null) {
        diagnostics.addAll(model.diagnostics.filter { diagnostic -> diagnostic !in diagnostics })
        return
    }
    if (function.qualifiedName == ReadRegex.qualifiedName) checkPatternRead(call, scope)
    when (val outcome = resolveCall(call, scope)) {
        is FunctionResolver.Outcome.NoMatch -> outcome.reasons.firstOrNull()?.let { reason -> diagnostics.add(Diagnostic(reason.code, reason.message)) }
        is FunctionResolver.Outcome.Ok -> checkConstantArguments(call, outcome.argTypes)
        null -> {}
    }
}

/** Each constant argument where the signature takes an extension type reads as that type. */
private fun Validator.checkConstantArguments(call: FunCall, argumentTypes: List<VType?>) {
    for ((argument, argumentType) in call.args.zip(argumentTypes)) {
        val constant = constantOf(argument) ?: continue
        val extension = extOf(argumentType ?: continue) ?: continue
        checkConstantAs(constant, extension, argument)
    }
}
