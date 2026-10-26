# OQ68 — The builtin scalar types implemented through `velle.ext.VelleType`, the API the public uses

**Status:** opened 2026-10-24 at Dave's direction, after OQ65 built. Nothing designed past the inventory and the decisions below.
**In plain terms:** Velle has eight builtin scalar types (`text`, `integer`, `long`, `decimal`, `double`, `boolean`, `Date`, `DateTime`). Every fact the compiler knows about them is hard-coded: a `when` branch in the validator, the evaluator, the code generator, the test generator, and the SQLite store. OQ65 gave the public an API, `velle.ext.VelleType<T>`, that states those same facts for a type an engineer adds. Dave's direction: the builtins should be implementations of that API, registered like any other type, so that Velle eats its own dogfood. Anything the API cannot say about `decimal` or `Date` is a hole an engineer's type would also fall into, found by Velle first.
**See:** `compiler/src/main/kotlin/velle/ext/VelleType.kt` (the API) · `TypeRegistry.kt` (`BUILTIN_NAMES`, `NUMBER_NAMES`, `foldFacts`, `samplesOf`: the builtins already half-expressed in the registry's vocabulary) · `Model.kt` (`VType.Num/Text/Bool/DateT/DateTimeT` beside `VType.Ext`) · `Runtime.kt` (`Value.VNum/VText/VBool/VDate/VDateTime/VDuration` beside `Value.VExt`) · `Codegen.scalarKotlin`, `Read.kt` (`TextField`, `NumField`, `BoolField`, `DateField`, `DateTimeField` beside `ExtField`/`OrderedExtField`), `SpecGen` (the literal samples `"sample"`, `false`, `2026-01-15`), `SqliteStore.sqlType`, `DomainKeys.normalize` · Reference §5, §10, §19; `checks.md` F2, V8, E1–E4 · OQ66 (functions and predicates over the same contract) · OQ67 (the store layers the builtin bindings would land in).

---

## 1. What the stance buys, and what it costs

**Buys.** One dispatch instead of two. Today every consumer of a type fact has a builtin branch and an extension branch: `Validator.scalarTypeOf` and `isOrderable`, `Evaluator.evalBinary`/`compareValues`/`valueEquals`/`evalAgg`/`evalFun`, `Codegen.kotlinType`/`readExpr`/`fieldHandle`, `StoreGen.rowKotlin`/`fromRaw`, `SpecGen`'s samples, `SqliteStore.sqlType`/`readColumn`/`writeColumn`/`renderCmp`, `DomainKeys.normalize`, `Rejection.show`. Ninety-odd `VType.*` branches across the compiler and the store (counted 2026-10-24). With the builtins in the registry, each becomes one lookup, and E2 checks Velle's own claims about `decimal` the way it checks Alice's about `Money`. The API cannot drift from what the compiler needs, because the compiler needs nothing it does not get through the API.

**Costs.** The API must grow to say everything the builtins do, and some of it the API cannot say today (§3). The runtime's builtin values are typed Kotlin (`BigDecimal`, `String`, `LocalDate`) behind `Value.VNum` and friends; through the API they would be `Any` behind `Value.VExt`, with casts and boxed comparisons on every evaluation. Measure before deciding whether the builtins keep a fast path under the same table.

**What does not change.** The spec. The eight names stay keywords or capitalized identifiers exactly as Reference §5 lists them; `uses` never names a builtin; no author sees the registry. This is a compiler-internal refactor with a public consequence, a more complete `VelleType`.

## 2. The inventory: every builtin fact, and the API member that would carry it

| Fact | Hard-coded today | Through the API |
|---|---|---|
| The name in type position | `SCALAR_KEYWORDS` (`Tokens.kt`), `Date`/`DateTime` as capitalized identifiers in `Parser.parseTypeRef`, `TypeRegistry.BUILTIN_NAMES` | `name`, `namespace = "velle"`; E1 relaxed for the builtin namespace's lowercase names (§3.1) |
| The Kotlin class on the generated surface | `Codegen.scalarKotlin` (`String`, `Int`, `Long`, `BigDecimal`, `Double`, `Boolean`, `LocalDate`, `Instant`); `StoreGen.rowKotlin` | `kotlinClass` |
| Literal forms | `IntLit`/`DecLit` typed `decimal` (`Validator.scalarTypeOf`), `TextLit` typed `text`, `BoolLit`, `DurationLit`, and the clock reads `NowLit`/`TodayLit` (`Ast.kt`); no date literal | `literals.fromNumber`/`fromText`; booleans and durations have no form, and `today`/`now` stay clock reads (§3.2) |
| Equality, canonical text | `Evaluator.valueEquals` (`BigDecimal.compareTo == 0`), `DomainKeys.normalize` (`stripTrailingZeros().toPlainString()`) | `equal`, `canonical` |
| Ordering | `Validator.isOrderable` (numbers, text, dates), `Evaluator.compareValues`/`orderCompare` | `ordering` |
| Arithmetic | `Evaluator.evalBinary`: number `+ - * /` over `BigDecimal`, `Date`/`DateTime` ± duration, set `+`/`-` on a `many`; `TypeRegistry.foldFacts` answers V8 for numbers' `+`/`-` only | `operators`, one line per (left, op, right); durations and the numeric tower are §3.3 and §3.4 |
| `sum` | `Evaluator.evalAgg` folds `BigDecimal`; `Read.sum` returns `BigDecimal` | `sum` |
| `max`/`min` | `Evaluator.evalFun`, `Validator.checkFunctionCall`: numbers, or the *ordered* capability for an extension | follows from `ordering` (already so for extensions) |
| Test samples and boundaries | `SpecGen.pickValue` (literal ± 1, 1, 0), act-field samples `"sample"`, `false`, `2026-01-15`, `Instant.parse(...)` | `samples` (`sample`, `below`, `above`, `kotlinSource`) |
| Storage | `SqliteStore.sqlType` (`INTEGER` for `integer`/`long`/`boolean`, `TEXT` otherwise), `readColumn`/`writeColumn`, `encode` (degrading what TEXT cannot order) | a `SqliteTypeBinding` per builtin, shipped by `stores/sqlite` (§4 D3) |
| Query field handles | `TextField` (with `contains`/`startsWith`/`endsWith`/`like`), `NumField`, `BoolField`, `DateField`, `DateTimeField` | `ExtField`/`OrderedExtField`; the text predicates are OQ66's (§3.5) |
| Rejection text | `Rejection.show`, `plain()` | `display` |

## 3. What the API cannot say today — the holes dogfooding finds

Each is a fact about a builtin that `VelleType` has no member for. Each is also a fact a public type might one day need, which is the point.

### 3.1 Names
Builtin names are lowercase keywords; E1 requires a capitalized identifier. `Date`/`DateTime` already follow the public rule. Either the `velle` namespace is exempt (the keywords stay keywords, and the registry rows carry the keyword as `name`), or the lexer's `SCALAR_KEYWORDS` is derived from the registry's builtin rows at startup. The first is smaller; the second removes one more table.

### 3.2 Literal forms
The API reads a number or a text. `true`/`false` is a third form the `boolean` type needs; `3 days` is a fourth that a duration needs (§3.3); a date has none today (dates arrive by commit or by `today`), and `today`/`now` are clock reads with V30 consequences, never literals, so they stay language. Options: `Literals` grows `fromBoolean` and `fromDuration`, or booleans and durations stay language and never go through the API. A public type with a boolean reading (`Flag`) argues for the first.

### 3.3 Durations
`Date + 3 days` is an operator line whose right operand is not a type: `VDuration(amount, unit)` is a value with no `VType`. For the builtins to go through the operator table, `duration` becomes a registered type in the `velle` namespace, with its own literal form (§3.2), ordering, `+`/`-` on itself, and `* integer`. Then `Date + duration → Date`, `DateTime + duration → DateTime`, and `DateTime - DateTime → duration` are ordinary lines, and OQ66's "durations on extension types" item (`Deadline + duration`) becomes a line an author declares. Whether `duration` is then a type a spec may write in a field position (`sla: duration`) is a Reference §5 decision.

### 3.4 The numeric tower
Four number types share one `Value.VNum(BigDecimal)` and one operand name, `"decimal"`, in the operator table. `integer + decimal` is legal today by promotion. Through the API, either each pair is a line (sixteen lines per operator, four types, generated), or `Operator` gains a notion of "any number" that promotes. The second is a capability class (*numeric*) like *ordered*; D2 below. `count` returns `integer`; `sum` over `integer` returns `integer`; `/` over integers returns what the Reference says it does, which the line must state.

### 3.5 Text predicates
`contains`, `starts with`, `ends with`, `like`, and `lowercase` are not `+ - * /`. They are predicates and a function over `text`, which is OQ66's registry. `text` as a registered type declares *ordered*, *equal*, the two literal forms, and nothing else; the predicates register against it in OQ66. Until then, `TextField` keeps its four methods beside the table.

### 3.6 `boolean` in `where`
`and`/`or`/`not` and the truth of a `where` are language, not operators on a type. `boolean` through the API declares `equal`, `canonical`, the literal form of §3.2, and no ordering, operators, or sum. The language keeps the connectives.

### 3.7 What stays outside the registry
`id` (never writable in type position, `==` only), `optional` and `many` (constructors over a type, not types), references and collections. These are not scalars and have no `VelleType`.

### 3.8 Performance
`Value.VNum` carries a `BigDecimal` the evaluator adds directly. `Value.VExt(type, Any)` adds through `Operator.applyAny` with two casts. The arithmetic in a tick over a large collection runs that path per element. Options: accept it and measure; or keep the typed `Value` classes as the representation while every *decision* (is it ordered, what does `+` produce, what column holds it) comes from the table. The second keeps the dogfood where it matters, the facts, and leaves the hot path alone.

## 4. Decisions for Dave

Each leads with the one-sentence decision I recommend, then what it costs and the alternative.

### D1. Do it, as a compiler-internal refactor with no spec-visible change: the eight builtins become `VelleType` rows in a `velle` namespace, loaded first, and every builtin branch in the compiler and the store becomes a registry lookup.
Cost: §3's API growth, and the `Value` representation question (§3.8). Alternative: leave the builtins hard-coded and let the two dispatches coexist, which is where OQ65 left it and what Dave rejected.

### D2. The API grows by exactly what the builtins need and nothing speculative: `Literals.fromBoolean`, a registered `duration` type with a `fromDuration` form, and a *numeric* capability for promotion among the four number types.
Cost: three additions to a public API a week old. Alternative: per-pair operator lines generated for the numeric tower (no new capability, sixteen lines per operator) and booleans/durations kept in the language outside the API, which leaves two builtins undogfooded.

### D3. The SQLite store ships one `SqliteTypeBinding` per builtin and `sqlType`/`readColumn`/`writeColumn` lose their builtin branches; E4 runs on Velle's own bindings at every store construction.
Cost: eight small classes in `stores/sqlite`, and E4's round-trip on eight more types at startup (a temp table, milliseconds). Alternative: keep `sqlType` for builtins and bindings for extensions, which is the asymmetry the stance removes. This also decides how OQ67's SQL layer ships its builtin bindings: once, dialect-independent, with dialect overrides.

### D4. Order of work: this question first, then OQ66's functions and predicates over the finished table, then OQ67's layers over the finished bindings.
Cost: OQ66 waits again. Alternative: OQ66 first, with the builtin functions registering against hard-coded builtin types, which would have to be redone here.

**Reversed by Dave, 2026-10-26:** custom functions outrank custom types, and a compromise or a limitation lands on the type side, so OQ66 goes first and this question follows over the finished function interface. The principle both serve is stated in OQ66 §4: Velle's own implementation codes to the interface it hands the engineer.

### D5. The typed `Value` classes stay as the runtime's representation; every fact about a value comes from the table.
Cost: `Value.VNum` and `Value.VExt` both exist, so the runtime keeps a small conversion between "the table's `Any`" and "the typed value". Alternative: one `VExt` for everything, measured first; if the measurement is fine, D5 is moot and the simpler representation wins.

## 5. Build sketch, once decided

1. `velle.ext` grows per D2; `TypeRegistry` loads the `velle` namespace rows before the classpath and exposes one `typeOf(VType)` for every scalar.
2. Validator: `scalarTypeOf`, `isOrderable`, `operatorLine`, `checkFunctionCall`, V8 read the table for builtins too; the builtin-only branches go.
3. Evaluator: `evalBinary`, `compareValues`, `valueEquals`, `evalAgg`, `evalFun` read the table; durations become a type (§3.3).
4. Codegen, StoreGen, SpecGen: `kotlinClass`, `samples`, `kotlinSource` replace the `when (name)` tables; `Read.kt`'s builtin field handles become `ExtField`/`OrderedExtField` specialisations, `TextField` keeping its predicates until OQ66.
5. `stores/sqlite`: eight bindings in `META-INF/services`; `sqlType` deleted.
6. Tests: E2 over the eight builtins is the first new test; every existing example is the regression suite, since nothing in a spec changes.
7. Docs: Reference §5 says the builtins are registered types Velle ships; the Extension types guide names them as the reference implementations to read; `checks.md` E1 states the `velle` namespace rule; `evaluation.md` the `duration` type if it becomes one.
