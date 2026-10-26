# OQ66 — Extension functions and predicates: a function an engineer writes in Kotlin, registered beside the types, and Velle's own builtins expressed the same way

**Status:** opened 2026-10-24 at Dave's request while settling OQ65 D4; OQ65 built 2026-10-24, so this is unblocked. 2026-10-26: Dave's direction in §4 puts this question ahead of OQ68 and states the principle behind both; §5 is the inventory of what the one interface has to carry; §6 is the four decisions the inventory forces, each with a recommendation, for Dave.
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
- **The store side.** How a function says what a store may render for it, per store, is OQ69, not this file: this file stops at the validator and the evaluator.
- **Infix spelling.** Whether a two-argument predicate may be written infix (`a overlaps b`) the way the text operators are, and what that does to the lexer.

## 4. The direction (Dave, 2026-10-26)

Two rulings. First, custom functions outrank custom types. Having explored custom types and added more builtin types, Dave finds a function an engineer writes (`isBusinessDay(date)`, `roundToCents(amount)`) more useful than a type an engineer writes; when a compromise or a limitation has to land somewhere, it lands on the type side. This reverses OQ68 D4's order: this question goes first, and OQ68 follows over the finished function interface.

Second, the principle behind both questions, stated generally: the API Velle hands an engineer to extend it is the API Velle's own implementation codes to. Every builtin function is one more implementation of the function interface, registered like any other, and the hard-coded branches in §5 go. The reason is not only that dogfooding proves the interface carries every mechanism a builtin needs; it is that Velle's own implementation stays maintainable and extensible when there is one dispatch to read and one place to add to.

## 5. What the one interface has to carry: the builtin inventory

Each row is a fact the compiler knows about a builtin function today, where it is hard-coded, and the member of the interface that would carry it. A row with no member is a hole an engineer's function would also fall into, which is what §4 wants found. Counted 2026-10-26 by grepping the function names: about a dozen branches each in `Validator.kt` and `Evaluator.kt`, eight in `Read.kt`, three in `StateFlowGen.kt`, two in `SqliteStore.kt`, plus the two lists in `Tokens.kt`.

| Fact | Hard-coded today | Through the interface |
|---|---|---|
| The name a spec calls | `BUILTIN_FUNCTIONS` (`Tokens.kt`); `Parser.parseNameOrCall` already accepts any lowercase identifier before `(`; `Validator` (`is FunCall`, line ~964) refuses a name outside the list | `name`, `namespace`; `uses` picks or renames as for a type; the lexer's list goes, the validator asks the registry |
| Arity and argument types | `checkFunctionCall`: one text (`lowercase`, `uppercase`, `trim`, `length`); one number-or-duration (`abs`); one Date-or-DateTime (`year`, `month`, `day`, `dayOfWeek`); one time-or-DateTime (`hour`, `minute`); `max`/`min`: n arguments of one ordered type, or one `many` of ordered values | `parameters`: each a concrete type, a capability (*ordered*), a union of concrete types (`Date | DateTime`), or "same as parameter n"; `max`/`min`'s two shapes are two overloads or one variadic parameter |
| The result type | `scalarTypeOf`: fixed (`text`, `integer`), "the type of argument 1" (`abs`, `max`/`min`), or the element type of the one collection argument | `result`: a concrete type, "the type of parameter n", or "the element type of parameter n" |
| Absent arguments and results | `evalFun`: `abs` and the parts return absent on absent; `lowercase` throws on non-text; `max(values)` over an empty `many` is absent | one rule for every function: absent in, absent out, which the optional narrowing of Reference §10 already assumes; `result` may be optional for a function absent on a present input (the empty collection) |
| The body | `evalFun` | `apply` |
| Fold facts for V8 | `Validator` line ~2515: `max`/`min` commute and repeat harmlessly; every other call is null | `foldStepsCommute`, `foldIdempotent` |
| Environment | the parts of a `DateTime` are read in `system.zone`, the zone `today` uses | **hole**: `apply` over bare arguments cannot see the zone. Either the interface passes a context (the system's zone, and whether the call counts as a clock read for V30 and the tick law) or the parts of a `DateTime` stay language. A context argument also answers the generator item in §3 (`initially nextInvoiceNumber`) |
| The read API handle | `Read.kt`: `TextFunction` for `lowercase`/`uppercase`/`trim`, `PartOf` for `length` and the six parts, written by hand on each field class | a generated handle per registered function on every field class whose type satisfies its first parameter (the codegen item) |
| Starting values | `StateFlowGen` lines ~545, ~643, ~735: `max`/`min` over `initially` constants folded at compile time | falls out of `apply`, with one requirement it adds: `apply` must be callable by the compiler on constant arguments, with no running system behind it, so the context of the environment row must have a compile-time form |
| Printing | `Printer.expr`: `name(args)` | falls out of `name` |
| Reads, polarity, aliases | `Model.collectExpr`, `Validator.aliasReads`/`signedExpr`/the walkers: a call reads what its arguments read | no member: a function is pure over its arguments, so the walkers stay as they are |
| The store side | `SqliteStore.render`/`renderCmp`: the text operators and `is empty` as SQL, `matches` as installed `REGEXP`, nothing else pushed down | OQ69 |
| The text operators | `TEXT_OPERATORS` (`Tokens.kt`); infix at `Parser` line ~613; typed at `Validator` line ~873; evaluated at `Evaluator` line ~646 (`contains` also over a `many`); compiled at `Query` lines ~133, ~282, ~303; rendered at `SqliteStore` line ~333 | predicates over `text`, functions returning `boolean`; the infix spelling is the §3 item; `contains` over a `many` is a collection operation and stays language |
| The aggregates | `AggCall` (`count`, `sum`, `latest`, `first`): their own parse shape, a collection, a field, a `by` list | stay language constructs; they consume a type's `sum` and `ordering` capabilities and take no part in this interface (§3, first item, now decided this way as the working assumption) |

What the inventory says about the sketch in §2: `parameters` needs capabilities, concrete types, unions of concrete types, and "same as n"; `result` needs "element type of n"; `apply` needs a context, not a bare list; the optional rule needs stating once. `readsClock` is subsumed by the context. Everything else in §2 stands.

## 6. Decisions for Dave (2026-10-26)

Each leads with the one-sentence decision I recommend, then what it costs and the alternative. Taking all four leaves nothing to decide before building. The infix spelling of a two-argument predicate (§3) and the store side (OQ69) stay open and do not block the build.

### D1. `apply` takes a context argument: a small object carrying the system's zone, with a compile-time form that carries no zone.
Cost: every function body takes a parameter, including an engineer's that never reads the zone. Alternative: the parts of a `DateTime` stay hard-coded in the evaluator, so six builtins are never written through the interface.

### D2. A parameter is one of: a concrete type, a capability (*ordered*), a union of concrete types (`Date | DateTime`), or "same as parameter n".
Cost: the union form is new vocabulary in the interface, and the validator's refusal text has to name it. Alternative: no union, and each builtin splits into one function per type, so `year` on a `Date` and `year` on a `DateTime` are two registrations of one name, which puts overloading into the interface anyway.

### D3. `max` and `min` register as one variadic parameter over an ordered type, with the collection form `max(values)` as a second overload.
Cost: overloading enters the interface for two builtins. Alternative: the collection form is language, beside the aggregates, so `max(values)` is not dogfooded.

### D4. One absent rule for every function: an absent argument gives an absent result, and a function may declare an optional result for the case where present arguments still give nothing, such as an empty collection.
Cost: `lowercase` throws on absent today and would give absent instead, which the narrowing of Reference §10 already assumes. Alternative: each function declares its own absent handling, one more member and one more thing an engineer can get wrong.
