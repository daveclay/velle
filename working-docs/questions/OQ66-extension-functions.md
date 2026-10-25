# OQ66 — Extension functions and predicates: a function an engineer writes in Kotlin, registered beside the types, and Velle's own builtins expressed the same way

**Status:** opened 2026-10-24 at Dave's request while settling OQ65 D4; OQ65 built 2026-10-24, so this is unblocked. Nothing designed past the contract OQ65 fixes; the sketch below is what that contract implies.
**In plain terms:** OQ65 lets an engineer add a data type. The same engineer will want `roundToCents(amount)`, `isBusinessDay(date)`, `distanceBetween(a, b)`: a value computed from values, or a yes/no asked of them, written once in Kotlin and called from a spec. Velle already has a closed list of such functions (`count`, `sum`, `latest`, `first`, `max`, `min`, `lowercase`, and the text operators), each hard-coded in the validator and the evaluator. Dave's direction: custom functions and predicates arrive the same way custom types do, and the builtins should be implemented through the same API, so there is one way a function tells the compiler what it accepts and what the proofs may assume of it.
**See:** the type side of the contract as built: `compiler/src/main/kotlin/velle/ext/VelleType.kt` (the KDoc states what each capability is and that an operator has no failure channel), `TypeRegistry.kt` (`foldFacts`, the operator table), `Validator.checkFunctionCall` and `Evaluator.evalFun` (`max`/`min` keyed on the *ordered* capability, the first rows of the function table), the wiki guide `Extension-types` · OQ59 ("Custom right-hand-side functions carry metadata") · Reference §5 (the closed builtin list), §10 (the functions in use), §19 (the fold whitelist), §22 "The extension framework" · `checks.md` F2, V8 · `Evaluator.evalFun`, `evalAgg`, `Validator.checkExpr` (`is FunCall`).

---

## 1. The contract OQ65 fixes

Dave, 2026-10-24: "there's an API contract between functions/predicates and data types, and that API should inform the compiler of what's valid." A type declares capabilities (ordered, summable, these operator lines, these literal forms). A function declares, per argument, the capability or the concrete type it needs, and what it returns. The compiler accepts a call when every argument's type declares what the function needs. Nothing else decides validity.

## 2. The sketch

```kotlin
package velle.ext

/** One function or predicate a spec may call. A predicate is a function whose result is `boolean`. */
interface VelleFunction {
    val name: String                       // as the spec calls it: `roundToCents`
    val namespace: String                  // `velle.finance`; `uses` picks or renames as for a type
    val parameters: List<Parameter>        // each: a concrete type name, or a capability (Ordered, Summable), or "same as parameter n"
    val result: ResultType                 // a concrete type name, or "the type of parameter n"
    val apply: (List<Any>) -> Any          // pure, total, deterministic (evaluation.md: evaluated as often as the runtime likes)
    val foldStepsCommute: Boolean          // V8, when a rule writes x = f(x, y): order does not matter
    val foldIdempotent: Boolean            // V8: applying it twice changes nothing (max/min are the builtin examples)
    val readsClock: Boolean                // a function over `today`/`now` is a clock read for V30 and the tick law
}
```

## 3. What has to be settled

- **Which builtins are functions.** `max`, `min`, `lowercase` are plain functions over capabilities (OQ65 D4 already routes `max`/`min` through the table; Dave's ruling: "allow a type to partake in max/min if it's valid; if it's not valid, then the data type API needs to provide a way to indicate that to the velle compiler"). `count`, `sum`, `latest ... by`, `first ... by` are aggregates with their own syntax (a collection, a field, a `by` list); they consume capabilities but may stay language constructs. The text operators are infix predicates over `text`. Deciding which of these move is the first item.
- **Purity.** The runtime evaluates a predicate at every commit it may be relevant to and re-evaluates on retry (`evaluation.md` S4); a function must be a pure function of its arguments. Whether the API can enforce more than a documented contract and E2-style sample checks.
- **The builtin arithmetic as registered rows** moved to OQ68 (the builtin *types* through `VelleType`, Dave's dogfooding direction, 2026-10-24); what stays here is the text operators as registered predicates over the builtin `text` type, and `lowercase` as a registered function.
- **Partial functions.** OQ65 gives an operator no failure channel on purpose (an exception from an operator is a transaction error and a bug in the type); a function that is only sometimes defined returns an optional the spec narrows (Reference §10). Whether `?` on a result type is enough.
- **Accessors on a type.** OQ65 D3 ruled a type opaque to the spec: no path reaches into it and no spec-visible member exists on it, so `price.currency` is two fields of a shape. If an accessor ever exists it is a function with a receiver (`phone.areaCode`), designed here, or not at all.
- **Predicates in refinement position.** `where isBusinessDay(due)` is an atom (`grammar.md`, `booleanAtom`); the validator's read summaries and polarity analysis need to see through a call to what it reads.
- **Generators.** `initially nextInvoiceNumber`: a function with no arguments and a non-determinism flag, re-run on retry as `randomUUID` is (Reference §5). `initially randomUUID` has no extension-type analog today; a type cannot say how a fresh value is generated.
- **Durations on extension types.** `deadline + 3 days` where `deadline` is an extension type is refused today. If OQ68 makes `duration` a registered type, `Deadline + duration → Deadline` is an ordinary operator line an author declares, and this item closes there.
- **Infix spelling.** Whether a two-argument predicate may be written infix (`a overlaps b`) the way the text operators are, and what that does to the lexer.
