# OQ65 — Extension types: a scalar type an engineer writes in Kotlin and a spec names like `Date`

**Status:** opened 2026-10-23; D1–D6 settled with Dave 2026-10-24; **built 2026-10-24** (§4, every step). What was built is the code: `compiler/src/main/kotlin/velle/ext/VelleType.kt` (the API), `TypeRegistry.kt` (registry, E1, E2, literal reading), the `uses` line in `Ast.kt`/`Parser.kt`/`Printer.kt`, `Model.typeScope`/`VType.Ext`, the validator's extension checks (E3, literal typing, the function table for `max`/`min`, V8 through operator facts), `Value.VExt` and the evaluator's dispatch, `stores/sqlite/.../SqliteTypeBinding.kt` with E4 in `SqliteStore`, the generators, `extensions/finance/` (Alice) and `extensions/finance-sqlite/` (Bob), billing's amounts as `Money`, and the docs (Reference §5/§10/§19/§22, `grammar.md`, `checks.md` E-series, `evaluation.md`, `testgen.md`, `GLOSSARY.md`, the wiki guide `Extension-types.md`). §6 lists where the build departed from the proposal below; the proposal is kept as the record of the reasoning.
**In plain terms:** Velle has eight scalar types, all Kotlin-grounded. `Money` appears throughout the Reference and is not one of them. An engineer should be able to write `Money` (or `Percentage`, `Sku`, `PhoneNumber`) once in Kotlin, put it on the classpath, and have every spec in the workspace name it in type position. The compiler proves things about specs using facts about types: which operators apply, whether values are ordered, whether a fold step can be applied twice or out of order, what value sits just past a boundary. Today those facts are hard-coded per builtin. The API an extension type implements must make the engineer state every fact the compiler reads, so that a capability the type does not declare is one the spec cannot use, with a diagnostic naming the type, never a guess.
**See:** Reference §5 (the builtin list, "The Reference also uses `Money`"), §10 (comparisons, orderable datums, `max`/`min`), §19 (the fold whitelist), §22 "Extensible data types" and "The extension framework" (the three commitments this design honours: validator-facing metadata, distributable, not output) · OQ59 (custom functions' metadata: the same registry, the other hat) · `checks.md` F2, V8 · `evaluation.md` (the Row contract, type-level refusal) · `testgen.md` (boundary cases).

---

## 1. What the compiler reads off a type today — the inventory

Every row is a place a builtin's fact is hard-coded. The right column is what an extension type must therefore supply. Nothing on this list is optional: a type that leaves one out leaves a hole in a proof or a crash in a phase.

| The compiler needs | Where the builtin fact is hard-coded today | The extension supplies |
|---|---|---|
| A name in type position | `Parser.parseTypeRef` (`SCALAR_KEYWORDS`, and `Date`/`DateTime` as capitalized identifiers); `Model.typeOf` → `VType` | `name`: a capitalized identifier, unique against builtins, shapes, and other types |
| A Kotlin type on the generated surface | `Codegen.scalarKotlin`/`kotlinType`/`readExpr`; `StoreGen.rowKotlin`/`fromRaw`; `SpecGen` value rendering | `kotlinClass`, and the Kotlin source that constructs a value (for generated tests) |
| Boundary acceptance of a committed value | `VelleSystem.convertScalar`: wrong class → type-level refusal (`type: unacceptable fields`) | the class check comes free from `kotlinClass`; `validate(value)` adds a well-formedness refusal reason beyond the class |
| Literals in predicates and `initially` | `Validator.scalarTypeOf` types `IntLit`/`DecLit` as `decimal` and `TextLit` as `text`; `QueryCompiler.constFold`; `Evaluator.eval` | `literals`: which builtin literal forms (number, text) the type reads, and how |
| Arithmetic `+ - * /` | `Evaluator.arith`/`evalBinary`: numbers only, `Date`/`DateTime` plus a duration, set union on a `many`; the validator checks operand types only for the text operators (F2's "operator/operand compatibility" is otherwise a runtime throw) | `operators`: every (left, op, right) → result the type takes part in, with its implementation |
| Equality `==`/`!=` | `Evaluator.valueEquals`: `BigDecimal.compareTo == 0`, reference identity, else Kotlin `==` | `equal(a, b)`, consistent with `canonical` |
| Ordering `< <= > >=`, `by` datums, query order, `latest`/`first` | `Validator.isOrderable` (numbers, text, dates); `Evaluator.compareValues`/`orderCompare` | `ordering`: a comparator, or none |
| `sum` | `Evaluator.evalAgg` folds `BigDecimal`; `Read.sum` returns `BigDecimal` and F2-rejects a non-number | `sum`: a zero and an addition, or none |
| `max`/`min` | `Evaluator.evalFun`: numbers only | follows from `ordering` (decision D4) |
| The fold hazards (V8, Reference §19) | duplication-safe whitelist = `max`/`min`; order-safe = the assignment's right side is a `Binary` with `+` or `-` | per operator: `foldStepsCommute` (order does not matter) and `foldIdempotent` (applying it twice changes nothing) |
| Boundary test cases (`testgen.md`) | `SpecGen.pickValue`: literal−1, literal, literal+1, 1, 0; `satisfies` on `BigDecimal`; act-field samples `"sample"`, `false`, `2026-01-15` | `samples`: one acceptable value; `below(v)`/`above(v)` for an ordered type; the Kotlin source for a value |
| Storage | the Row contract (`Hydration.kt`): scalar values are plain Kotlin builtins; `SqliteStore.sqlType`/`encode`/`readColumn`/`writeColumn`; the typed store's row classes | nothing on the type (D5): a row holds the type's Kotlin object, and a *store binding* for that type and that store (§2.5) says how the store holds it |
| Query push-down soundness | `QueryCompiler.typesMatch`; `SqliteStore.encode` degrades a comparison its encoding cannot order (the superset contract keeps that sound) | the store binding (§2.5) says which comparisons its column can answer; the rest degrade |
| Serialization-domain keys | `DomainKeys.normalize`: `BigDecimal` to a canonical plain string | `canonical(value)` |
| Rejection messages | `Rejection.show` | `display(value)`, defaulting to `canonical` |
| Diagrams, the printer, the editor grammar | type name only | nothing |

**What does not depend on a scalar's type, and so does not change.** Closures (Reference §4, §6) are trees of records; a scalar is a value in a field and is never a part. Relationships, inverses, one-writer (V1), the disarm proof (V2), confluence (V15), quiescence (V16), serialization domains (beyond key text), visibility, deletion, and the trigger family are all type-agnostic. An extension type is a scalar in the exact sense of Reference §5: it cannot be the target of `one`/`many` as a shape can, and a path cannot reach into it (`price.currency` is not a Velle expression; decision D3).

## 2. The design

### 2.0 What a type is for, and what it is not

Dave's statement, 2026-10-24, which the engineer-facing wiki page and the KDoc of `VelleType` open with:

> Custom types are not intended to be a mechanism for custom *shapes*. Custom types are used to help Velle read closer to a business domain, and business concepts live in Velle as shapes and rules. Types provide a way for the binding API to handle the vast landscape of data types present in diverse systems without the Velle language itself having to provide lower-level mappings.

What follows from it for the design:

- **A type is one value.** An amount of money is a type. An amount *with its currency* is two values, and two values are a shape: `shape Price { amount: Money, currency: Currency }`. The spec then reads `price.currency` as a field, the compiler proves things about it as a field, and nothing is hidden inside a scalar.
- **Nothing in a type is business logic.** A `never` says what a balance may be; a type says what a well-formed amount is. If an engineer finds themself encoding a business rule in `validate`, it belongs in the spec where the product owner can read it.
- **A type's job is the mapping.** The Kotlin class the binding hands in and out, how the value persists, how it compares and adds, what a constant of it looks like. That is the "lower-level mapping" the language itself does not carry.

Dave's second statement, the same day, on storage: "When it comes to custom data types, the engineer is explicitly saying they have a custom data layer that they need to fit velle onto. That means dealing with the store." And on stores generally: ideally there is one store implementation, because object-relational mapping is a solved problem; Velle *provides* that store and does not *force* it on a system.

D2, D3, D5, and D6 all follow from this: no composite constants (D2), no paths into a type (D3), a store binding per type and store, written by whoever knows the store (D5), a single-currency `Money` (D6).

### 2.1 Where a type lives and how Velle finds it

An extension type is a Kotlin class implementing `velle.ext.VelleType<T>`, in any jar on the classpath, named in `META-INF/services/velle.ext.VelleType` (Java's `ServiceLoader`). This is "registered with Velle" with nothing else to configure: the engineer's output module adds the extension as a Gradle dependency and both uses below see it.

Velle loads the registry in three places, because the spec is read in three places:

- `velle.GenerateKt`, at generate time: the validator typechecks against the registry, the generators render its Kotlin classes.
- `GeneratedSystem`, at runtime: it re-parses the spec (`Model(Parser.parse(spec))`), so the evaluator needs the same registry to run operators and compare values.
- A store, at construction: it already takes the model; it now also takes its store bindings (§2.5), one per extension type the spec stores, and refuses to start without one for each.

The registry is one object, `TypeRegistry`, built once per process from `ServiceLoader` and handed to `Model`. A `Model` with no extensions behaves exactly as today.

**The registry is itself validated, before the spec** (a new E-series in `checks.md`, extension checks):

- **E1 — a type's name is usable.** `name` is a capitalized identifier and not a builtin; `namespace.name` is unique in the registry (two classes claiming `velle.finance.Money` is an error naming both). A spec's unqualified use of a name is an error when two registered types share it and no `uses` line picks one, or when a shape or refinement in the spec has the same name; the diagnostic prescribes the `uses` line (D1). A name a `uses` line introduces is checked the same way.
- **E2 — a type's claims hold on its own samples.** The compiler cannot prove an engineer's Kotlin, but it can refuse a type whose claims fail on the values the type itself hands over: `canonical` agrees with `equal`; the comparator is antisymmetric and agrees with `equal` at zero; each operator marked `foldStepsCommute` commutes on sample pairs, each marked `foldIdempotent` is idempotent on them; `below(v) < v < above(v)` where defined; each literal form the type reads round-trips its own `canonical`. This is the same posture as `tolerates`: the engineer states a fact, the compiler spends it, and it checks what it can.
- **E4 — a store holds every type the spec stores.** Checked when a store is constructed with a model: for each extension type on a stored field, the store has a binding (§2.5), and the binding round-trips the type's samples through the store (`read(write(v))` equals `v` under the type's `equal`). A missing binding is refused at startup, naming the type and the store: "SqliteStore has no binding for 'Money'; add a `SqliteTypeBinding<Money>`".
- **E3 — the spec asks for a capability the type does not declare.** This is F2 with a better sentence: `balance < 0` on a type with no ordering is "'Money' declares no ordering, so '<' is not defined on it; extend `velle.finance.Money` or compare a field that is ordered". The diagnostic names the Kotlin class, because the fix is there, not in the spec.

### 2.2 The API

**Capabilities, and the contract with functions (D4, settled 2026-10-24).** A type does not say which functions may be called on it. It declares *capabilities*: it is ordered (`ordering`), it adds up (`sum`), it takes part in these arithmetic lines (`operators`), it reads these constants (`literals`). A function or operator, builtin or custom, declares what capability it needs of each argument: `max` and `min` need an ordered argument, `sum` a summable field, `<` an ordered pair, `lowercase` the builtin `text`. The compiler accepts a call when the argument's type declares what the function needs, and refuses it with E3 naming the type and the missing capability otherwise. So "`max` works on `Money`" is not a fact anyone writes down; it follows from `Money` declaring an ordering. Dave's ruling: "allow a type to partake in max/min if it's valid; if it's not valid, then the data type API needs to provide a way to indicate that to the velle compiler ... there's an API contract between functions/predicates and data types, and that API should inform the compiler of what's valid." Custom functions and predicates, and the builtins re-expressed as registered functions over the same capabilities, are OQ66; this question builds the type side of the contract and the two builtins (`max`, `min`) that read it first.

The principle the interface enforces: **every member the compiler reads is abstract; every capability is a value the engineer writes, with `null`/empty meaning "the spec cannot do this".** Nothing defaults to "allowed". The few defaults (`validate`, `equal`, `display`) default to the strictest reading, not the most permissive.

```kotlin
package velle.ext

import java.math.BigDecimal
import kotlin.reflect.KClass

/**
 * One scalar type a spec may name in type position, as it names `Date`.
 * Nothing here is about storage: how a store holds a value of this type is a
 * store binding (OQ65 §2.5), written by whoever knows the store.
 *
 * A type is one value and a mapping for it, never a business concept: business
 * concepts live in the spec as shapes and rules. A value with parts (an amount
 * and its currency) is a shape with two fields, not a type. Types exist so the
 * binding can carry the data types of diverse systems in and out of Velle
 * without the language carrying lower-level mappings itself.
 *
 * Each member is read by a named part of the compiler (OQ65 §1). A capability
 * this type does not declare is one a spec cannot use: the compiler refuses the
 * spec and names this class as where to add it.
 */
interface VelleType<T : Any> {

    /** The name the spec writes by default: a capitalized identifier, `Money`. Checked by E1. */
    val name: String

    /** The library the type belongs to, a dotted lowercase path such as `velle.finance`.
     *  `namespace.name` is the qualified name a `uses` line spells (D1): how a spec picks
     *  this `Money` over another library's, or renames it. */
    val namespace: String

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
     *  key (evaluation.md U3) and E2's witness. */
    fun canonical(value: T): String

    /** How a value reads in a rejection message (Reference §21). */
    fun display(value: T): String = canonical(value)

    /**
     * The *ordered* capability, or null. Every consumer of ordering reads this
     * one member: `< <= > >=`, a selector's `by`, `latest`/`first`, a query
     * order, `max`/`min` (D4), and the boundary cases testgen derives. Without
     * it each of those is refused for this type, with E3 naming the type. Must be a total order over every
     * acceptable value: a type whose values are only sometimes comparable
     * (money in two currencies) is not ordered; see OQ65 §3 D6.
     */
    val ordering: Comparator<T>?

    /** The arithmetic this type takes part in. Empty means none: `balance + amount`
     *  is refused with E3. */
    val operators: List<Operator<*, *, *>>

    /** `sum(collection, field)` over a field of this type, or null (refused). */
    val sum: Sum<T>?

    /** What the test generator needs to write a case that commits a value. */
    val samples: Samples<T>
}

/**
 * Which of the spec's literal forms this type reads: a number or a quoted text,
 * the two Velle has (D2). A reader returns the value or throws
 * `LiteralRejected(reason)`; the compiler reports the reason as F2 at the
 * literal, since literals are compile-time constants. Both null: a spec can
 * compare two fields of this type but never write a constant of it. A reader
 * parses one value; it does not split a text into parts, because a value with
 * parts is a shape (§2.0).
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
 * implementation. Operand and result types are named as the spec names them —
 * a builtin (`"decimal"`) or a registered type (`"Money"`). Both directions of
 * a mixed operation are separate lines (`Money * decimal`, `decimal * Money`).
 *
 * The two fold facts are what V8 reads when a rule writes `x = x op y` and `x`
 * has this type (Reference §19). They are claims about *this operator*, checked
 * on the type's samples (E2) and otherwise trusted, exactly as `tolerates` is:
 * - [foldStepsCommute]: the order the step is applied in does not matter —
 *   `(x op a) op b` equals `(x op b) op a` for all a, b. True of `+` and `-`
 *   over a group, of `max`/`min`, of set union. Lets V8 accept the fold on a
 *   tick cadence.
 * - [foldIdempotent]: applying the same step twice changes nothing —
 *   `(x op a) op a` equals `x op a`. True of `max`/`min`, union, boolean or;
 *   false of `+`. Lets V8 accept an unguarded `after commit` or scheduled fold.
 *
 * An operator is total over its declared operand types: it returns a value for
 * every pair. There is no failure channel on purpose — an exception from an
 * extension is a transaction error (evaluation.md S4) and a bug in the
 * extension. An operation that is only sometimes defined is not an operator;
 * it is a function returning an optional, which is OQ59's half of the framework.
 */
class Operator<L : Any, R : Any, O : Any>(
    val op: String,            // "+", "-", "*", "/"
    val left: String,
    val right: String,
    val result: String,
    val apply: (L, R) -> O,
    val foldStepsCommute: Boolean,
    val foldIdempotent: Boolean,
)

/** `sum` over a field of `T`: the empty collection sums to [zero]; [plus] must be
 *  associative and commutative (a collection has no order), checked on samples (E2). */
class Sum<T : Any>(val zero: T, val plus: (T, T) -> T)

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
```

What the interface does not let the engineer do, on purpose:

- **Default a capability to "allowed".** `ordering`, `operators`, `sum`, `literals`, and `samples` are abstract. A type with no ordering must say `override val ordering = null` and read the KDoc that says what that refuses.
- **Reach into a value from the spec.** There is no member list; a type is opaque to paths (D3).
- **Fail partway.** No `Result`, no exception type, no optional result on an operator. If the engineer needs one, the design pushes it to a function (OQ59) where the spec sees the optional and narrows it (Reference §10).
- **Claim an algebraic fact without a sample it is checked on.** `samples.sample` is required, so E2 always has at least one value, and `below`/`above` give it pairs.

### 2.3 How the compiler uses it, phase by phase

**Parse.** `parseTypeRef` keeps accepting builtins by keyword. Any other capitalized identifier in type position parses as `NamedType(name, optional, many)`, resolved by `Model` against the spec's shapes, its `uses` lines, and the registry: a shape → the relationship type (with `one`/`many` as today), a registered type → `VType.Ext(qualifiedName)`, neither → F1 "no shape or registered type named 'X'". `one X` where X is a registered type is the existing "'one' takes a shape" error; `many X` is an owned set of values, as `many text` is. A name that is both a shape and a registered type is E1 unless a `uses` line renames the type.

**`uses` (D1, settled 2026-10-24).** By default every registered type is in scope under its own `name`, so a spec that wants `Money` writes `Money` and declares nothing. A top-level line narrows or renames:

```
uses velle.finance.Money            -- this library's Money, when two libraries register one
uses velle.finance.Money as Cash    -- the same type under the name the spec prefers
```

The qualified name is the type's `namespace.name`. A `uses` line is for the two cases the default cannot serve: two registered types with one name (the line picks), and a spec whose own shape, or whose business vocabulary, wants the name (the line renames). With `as`, the original name is no longer in scope for that type in this spec, so `Money` may be a shape. Other registered types stay in scope by their names; a `uses` line never turns the default off. A `uses` line naming a qualified name the registry does not hold is F1, naming the registry. Diagnostics and generated test prose use the spec's name for the type; the generated Kotlin uses `kotlinClass` either way.

**Type the expression.** `scalarTypeOf` returns `VType.Ext(name)` for a path to such a field. An untyped literal takes its type from the other operand: `balance < 0` with `balance: Money` reads `0` through `Money.literals.fromNumber`; if the type has no such reader, F2 says so. For arithmetic, the operator table decides: `balance * 2` matches `(Money, *, decimal)` so `2` is a `decimal`; `balance + 10` matches `(Money, +, Money)` so `10` is read as `Money`. If more than one line could type the literal, F2 asks for a field or a typed value instead of a constant. A comparison between two different types is F2 as the Reference already says.

**The checks.** F2 gains the E3 sentence for every refused capability. The builtin functions are checked through one table keyed by the capability each argument needs (`max`/`min`: ordered; `sum`: summable; `lowercase`: `text`), not by a list of type names: today's `Evaluator.evalFun` and `Validator` branches on `"max", "min"` become the first rows of that table, so OQ66 can register custom functions into it without touching the dispatch. V8 replaces its two hard-coded tests with lookups: the duplication whitelist is `foldIdempotent` on the operator the assignment's right side applies (with `max`/`min` idempotent by definition for any ordered type), and `orderSafe` is `foldStepsCommute`. Builtins get the same table internally, so the builtin behaviour is unchanged and the Reference §19 table gains one row per declared fact.

**Evaluate.** `Value.VExt(type: VelleType<*>, v: Any)`. `arith` dispatches to the registry by (left type, op, right type); `compareValues` uses `ordering`; `valueEquals` uses `equal`; `evalAgg("sum")` uses `sum`; `evalFun` looks up the function table (above) and `max`/`min` compare through `ordering`. A literal in an expression is evaluated to a `VExt` once, at model build, so no literal reader runs per evaluation.

**The boundary.** `convertScalar` accepts a raw value that `is kotlinClass` and passes `validate`; otherwise the type-level refusal, with the type's reason when it gave one. A `many Money` is a set under `equal`, exactly as `many text` is.

**Storage.** The Row contract gains one sentence: a field of an extension type holds the type's Kotlin object. How a store holds it is the store's binding for that type (§2.5): `SqliteStore` asks its `SqliteTypeBinding<Money>` for the column type, the write, the read, and which comparisons the column can answer for push-down; anything the binding does not answer degrades under the superset contract. The typed store's generated row class exposes the Kotlin class directly.

**Generate.** `Codegen` emits `kotlinClass` as the field's type in input classes and model objects and casts through the registry in `readExpr`. `Read.kt` gets `ExtField<T>` (`eq`/`neq`, membership in `many`) and `OrderedExtField<T>` adding `lt`/`gt`/`ascending`, chosen by whether the type is ordered, so a query ordering by an unordered type does not compile. `sum` in the typed query builder becomes available for a field whose type declares `sum`, returning `T`. `SpecGen` draws act-field values from `samples.sample`, boundary values from `below`/`above` and the type's comparator, and renders with `kotlinSource` plus `imports`.

**Serialization keys and messages.** `DomainKeys.normalize` and `Rejection.show` call `canonical` and `display`.

### 2.4 Worked example: a `Money` type and the spec that uses it

The spec side, as the Reference already writes it (Reference §8, §19, §20; `wiki/patterns.md`):

```
shape Account {
    owner: one Customer
    balance: Money initially 0
    currency: text
}

act Deposit {
    account: one Account
    amount: Money
}

rule ApplyDeposit when Deposit {
    account.balance = account.balance + amount
}

never (Account where balance < 0)
never (Deposit where amount <= 0)
```

The extension side, the whole of it:

```kotlin
package velle.finance

import velle.ext.*
import java.math.BigDecimal
import java.math.RoundingMode

/** An exact amount of money in minor units of one unnamed currency; the currency is business data beside it. */
data class Money(val amount: BigDecimal) : Comparable<Money> {
    init { require(amount.scale() <= 2) }
    override fun compareTo(other: Money) = amount.compareTo(other.amount)
    companion object { fun of(s: String) = Money(BigDecimal(s).setScale(2, RoundingMode.UNNECESSARY)) }
}

class MoneyType : VelleType<Money> {
    override val name = "Money"
    override val namespace = "velle.finance"
    override val kotlinClass = Money::class
    override val literals = Literals<Money>(fromNumber = { Money(it.setScale(2, RoundingMode.UNNECESSARY)) })
    override fun canonical(value: Money) = value.amount.toPlainString()
    override val ordering: Comparator<Money>? = naturalOrder()
    override val operators = listOf(
        Operator<Money, Money, Money>("+", "Money", "Money", "Money", { a, b -> Money(a.amount + b.amount) }, foldStepsCommute = true, foldIdempotent = false),
        Operator<Money, Money, Money>("-", "Money", "Money", "Money", { a, b -> Money(a.amount - b.amount) }, foldStepsCommute = true, foldIdempotent = false),
        Operator<Money, BigDecimal, Money>("*", "Money", "decimal", "Money", { a, k -> Money((a.amount * k).setScale(2, RoundingMode.HALF_EVEN)) }, foldStepsCommute = true, foldIdempotent = false),
        Operator<BigDecimal, Money, Money>("*", "decimal", "Money", "Money", { k, a -> Money((a.amount * k).setScale(2, RoundingMode.HALF_EVEN)) }, foldStepsCommute = true, foldIdempotent = false),
        Operator<Money, Money, BigDecimal>("/", "Money", "Money", "decimal", { a, b -> a.amount.divide(b.amount, java.math.MathContext.DECIMAL64) }, foldStepsCommute = false, foldIdempotent = false),
    )
    override val sum = Sum(Money.of("0.00")) { a, b -> Money(a.amount + b.amount) }
    override val samples = Samples(
        sample = Money.of("10.00"),
        below = { Money(it.amount - BigDecimal("0.01")) },
        above = { Money(it.amount + BigDecimal("0.01")) },
        kotlinSource = { "Money.of(\"${it.amount.toPlainString()}\")" },
        imports = listOf("velle.finance.Money"),
    )
}
```

`META-INF/services/velle.ext.VelleType` holds one line, `velle.finance.MoneyType`. That is Alice's library (D5): it knows nothing about any store.

Bob's library, the SQLite binding for `Money`, the whole of it:

```kotlin
package velle.finance.sqlite

import velle.finance.Money
import velle.sqlite.SqliteTypeBinding
import java.math.BigDecimal
import java.sql.PreparedStatement
import java.sql.ResultSet

/** Money in SQLite: a TEXT column holding the exact plain decimal, as the builtin `decimal` is held. */
class SqliteMoneyBinding : SqliteTypeBinding<Money> {
    override val type = "velle.finance.Money"
    override val columnType = "TEXT"
    override fun write(ps: PreparedStatement, index: Int, value: Money) = ps.setString(index, value.amount.toPlainString())
    override fun read(rs: ResultSet, column: String) = Money(BigDecimal(rs.getString(column)))
    /** TEXT does not collate numerically, so only equality is pushed down; `<` is decided in memory. */
    override val answers = setOf("==", "!=")
    override fun bind(value: Money): Any = value.amount.toPlainString()
}
```

`META-INF/services/velle.sqlite.SqliteTypeBinding` holds `velle.finance.sqlite.SqliteMoneyBinding`. Chris's system depends on both jars and on `stores/sqlite`; `SqliteStore` finds the binding at construction (E4) and the spec never mentions any of it.

What the compiler now does with the spec above, and could not before:

- The spec declares no `uses`: `Money` is the only registered type of that name, so the default scope serves (D1).
- `balance: Money initially 0`: `0` is read through `fromNumber` to `Money(0.00)`. A `Money` literal of `0.001` is F2 at the literal with the reader's reason (the scale check throws).
- `account.balance = account.balance + amount`: the operator table has `(Money, +, Money) → Money`; V8 reads `foldStepsCommute = true`, so this fold is order-safe on any cadence and, with no guard, duplication-exposed exactly as a `decimal` fold is today.
- `never (Account where balance < 0)`: `Money` is ordered, so `<` is legal. Testgen commits a deposit that would leave the balance at `below(Money(0.00))`, which is `-0.01`, and expects refusal; then one leaving `0.00` and expects acceptance, rendered as `Money.of("-0.01")`.
- `Money / Money` is `decimal`, and `Money / decimal` is not in the table, so `balance / 2` is E3: "'Money' declares no '/' with a 'decimal' on the right". The engineer adds the line or the author writes `balance * 0.5`.
- `sum(account.deposits, amount)` is legal and returns `Money`. The generated `read.accounts.where { balance gt Money.of("100.00") }` compiles; `orderBy { balance.descending }` compiles; the SQLite store pushes the equality filters down and decides `gt` in memory, because Bob's binding says its TEXT column answers only `==`/`!=`.
- The `balance` column is TEXT holding the exact plain decimal, by Bob's choice, not Velle's. A Postgres binding by someone else could choose `NUMERIC(19, 2)` and answer every comparison.

### 2.5 Store bindings

A store binding is how one store holds one extension type. Each store defines its own binding interface, in the store's own vocabulary, because a column type means something to SQLite and nothing to a wire protocol. The runtime does not define it and does not see it. For `stores/sqlite`:

```kotlin
package velle.sqlite

import java.sql.PreparedStatement
import java.sql.ResultSet

/** How SqliteStore holds one extension type. Found through ServiceLoader, or passed to the store's constructor. */
interface SqliteTypeBinding<T : Any> {
    /** The type's qualified name, `velle.finance.Money`. */
    val type: String
    /** The column's SQL type in CREATE TABLE. */
    val columnType: String
    fun write(ps: PreparedStatement, index: Int, value: T)
    fun read(rs: ResultSet, column: String): T
    /** The comparison operators this column answers faithfully in SQL (`==`, `!=`, `<`, ...);
     *  the store pushes those down and leaves the rest to the evaluator (superset contract). */
    val answers: Set<String>
    /** The value bound into a pushed-down comparison. */
    fun bind(value: T): Any
}
```

What the store does with it: `columnsOf` consults the binding for `columnType`; `writeColumn`/`readColumn` call `write`/`read`; `renderCmp` pushes a comparison down only when `op in answers`, binding `bind(value)`. A `many Money` is a membership table whose value column uses the same binding. The typed store's generated row classes (`StoreGen`) carry the Kotlin class and need no binding of their own.

E4 (§2.1) is the check: at construction the store has a binding for every extension type on a stored field of the model, and each binding round-trips the type's samples. The in-memory runtime needs no binding, so a spec runs in memory before anyone writes one.

The same shape serves another store: a Postgres store defines `PostgresTypeBinding<T>` with its own column and parameter vocabulary; a store over a binary protocol defines a binding with a field encoding and no notion of a column. Once OQ67 splits the store API into an agnostic layer and a SQL layer, the binding Bob writes is a `SqlTypeBinding<Money>` at the SQL layer, dialect-independent, and serves SQLite and Postgres alike; a dialect-level binding stays possible for a column type only one database has. The runtime's one requirement on all of them is the Row contract: the row holds the type's Kotlin object, and the store returns the same object on read.

## 3. Decisions for Dave

Each leads with the one-sentence decision I recommend, then what it costs and the alternative.

### D1. Settled 2026-10-24: a registered type is in scope under its own name with nothing declared, and a `uses` line picks one of two same-named types or renames one.

Dave's ruling: "by default use the type name as is, but allow for `uses` to rename it or scope it." The design is in §2.3, "`uses`": `uses velle.finance.Money` scopes, `uses velle.finance.Money as Cash` renames, and the default stays on for every other type. What was weighed: with no `uses` at all the spec cannot say which library's `Money` it means when two are on the classpath, and cannot have a shape named `Money` beside the type; a mandatory `uses` makes every spec name a library for the common case. The qualified name is the type's own `namespace.name`, declared by the extension, never a Kotlin class name, so the spec still names no mechanism.

### D2. Settled 2026-10-24: a constant of an extension type is written as a number or a quoted text, the two literal forms Velle has, and the type reads it; a value with several parts is a shape, not a type.

Dave's ruling: "limit to the literal forms velle already has. Having multiple 'values' like `12.50 USD` is better modeled as a shape." His statement of what types are for is §2.0.

Three registered types in one spec show how a constant gets its type:

```
uses velle.finance.Money
uses velle.finance.Currency      -- a three-letter code; its reader refuses anything else
uses velle.finance.Rate          -- a fraction between 0 and 1

shape Account {
    owner: one Customer
    balance: Money initially 0            -- the number 0, read by Money as 0.00
    currency: Currency                    -- committed; no constant here
    interest: Rate initially 0.05         -- the number 0.05, read by Rate
}

never (Account where balance < 0)         -- 0 read by Money, because balance is Money
never (Account where currency == "XXX")   -- "XXX" read by Currency, because currency is Currency
never (Account where interest > 1)        -- 1 read by Rate; writing 1.5 here is a compile error, since Rate refuses it
```

The compiler looks at the other side of the comparison, or at the field `initially` is on, finds the extension type, and hands the literal to that type's reader (`literals.fromNumber` or `literals.fromText`, §2.2). The reader produces the value or refuses with a reason, and a refusal is F2 at the literal, at compile time, in the type's own words ("a rate is between 0 and 1").

A price in a currency is a shape, and its constant is two constants:

```
uses velle.finance.Money
uses velle.finance.Currency

shape Price {
    amount: Money
    currency: Currency
}

shape Quote {
    price: one Price
}

never (Quote where price.amount == 12.50 and price.currency == "USD")
```

What was weighed and set aside: a text reader that splits `"12.50 USD"` into parts, and a typed literal syntax (`12.50 USD`, `Money("12.50", "USD")`). The first hides two values inside a scalar where the compiler cannot see them; the second makes the lexer and `grammar.md` depend on what is on the classpath. `grammar.md` changes by one sentence: a number or text literal beside an extension-typed operand takes that type, and the type decides whether it is well-formed.

### D3. Settled 2026-10-24: an extension type is opaque to the spec: no path reaches into it, and no spec-visible member exists on it.

`price.currency` is not an expression; a `Money` with a currency inside is two fields in the spec (§2.0). Cost: a type cannot expose a derived view of itself to the spec. Alternative: let a type declare accessors (`price.currency`, `phone.areaCode`) as members. That is a function with a receiver, which is OQ59's custom-function half of the framework; adding it here would design the function registry on the side. Dave's ruling: opaque. Accessors, if they ever exist, arrive with OQ66's functions.

### D4. Settled 2026-10-24: a function accepts an extension type when the type's declared capabilities say it may; `max` and `min` accept any type that declares an ordering.

Dave's ruling, in full: "allow a type to partake in max/min if it's valid; if it's not valid, then the data type API needs to provide a way to indicate that to the velle compiler. ... the idea is that there's an API contract between functions/predicates and data types, and that API should inform the compiler of what's valid." The contract is §2.2's capabilities. In a spec:

```
uses velle.finance.Money
uses velle.identity.Sku          -- a stock-keeping code; equal or not, never ordered

shape Line {
    sku: Sku
    price: Money
    listPrice: Money
}

shape Order {
    lines: many Line
    highest: Money = max(lines.price)             -- legal: Money declares an ordering
    total: Money = sum(lines, price)              -- legal: Money declares sum
}

never (Line where price > listPrice)              -- legal: ordered
never (Line where sku > "A-100")                  -- E3: 'Sku' declares no ordering, so '>' is not defined on it
```

The last line's refusal names `velle.identity.Sku` as where an ordering would be added, if the business meant one. What was weighed and set aside: keeping `max`/`min` numeric (today's `Evaluator.evalFun`), which would let an ordered type be compared and sorted but not folded with `max`, and would leave the §19 whitelist row for `max`/`min` unable to reach it. One consequence for the builtins: `max(d1, d2)` over two dates becomes legal, since `Date` is ordered. Reference §5 and §10 state "ordered" once and list what it unlocks.

Dave also asked, in the same ruling, for custom functions and predicates on this contract once types are built, with Velle's own builtins implemented the same way. That is OQ66, opened the same day.

### D5. Settled 2026-10-24: how a store holds an extension type is a third mechanism, a *store binding* for that type and that store, written by whoever knows the store; the type itself says nothing about storage.

Dave's ruling, after two rounds that had the type carry its own storage encoding: "there's absolutely no way to map the vast number of custom data types in the world to a handful of restrictive types velle provides. Even if you could, the approach is fundamentally wrong on principle: suddenly velle's types intended to help product owners and engineers have a common understanding of the system become a horrifically complex type-mapping solution." And: "When it comes to custom data types, the engineer is explicitly saying they have a custom data layer that they need to fit velle onto. That means dealing with the store." His three parties:

- **Alice** publishes the open-source `Money` type library: the value, its literals, ordering, operators, `sum`, samples. Nothing about storage. (`velle.finance`, §2.4.)
- **Bob** publishes the open-source SQLite binding for `Money`: the column type, the write, the read, which comparisons the column answers. (`velle.finance.sqlite`, §2.4.) A Postgres binding is another library by whoever knows Postgres; a Spring Boot store could let Chris register the mapping inline instead.
- **Chris** builds a system on SQLite and depends on both. Chris's spec says `balance: Money` and nothing else; `SqliteStore` finds Bob's binding at startup (E4) and refuses to start without one.

What was set aside, and why: the type carrying a *carrier*, one of Velle's six builtins it encodes itself to, so that every store would hold it with no new code. It cannot cover the world's types, and it turns the type, which exists so an engineer and a product owner share the word `Money`, into a mapping exercise. The one-store stance stays as stated in §2.0: Velle provides a generic store because object-relational mapping is solved, and does not force it.

The mechanism is §2.5.

### D6. Settled 2026-10-24: `Money` is the first example of the feature, as a single-currency amount in `extensions/finance/`, with Bob's SQLite binding in `extensions/finance-sqlite/`, adopted by `examples/billing/`.

Dave's ruling: "use Money as the first example of this feature." Reference §5 promises `Money`; §8, §13, §19, §20, and `patterns.md` already write it, so every one of those examples becomes real. The type is §2.4's: an exact amount with two decimal places, ordered, with `+`, `-`, `* decimal`, `/ Money`, and `sum`, total in every operation. A `Money` that carries its currency is ruled out by §2.0: it is two values, so it is `shape Price { amount: Money, currency: Currency }` in a spec, and "never compare prices in different currencies" is a `never` the product owner can read. Billing's amounts become `Money`, its output module depends on Alice's and Bob's modules, and the generated tests pass with `Money.of(...)` values.

## 4. Implementation plan (done 2026-10-24)

Ordered so that each step leaves every example compiling and every test green; the registry with no extensions is the current behaviour.

1. **`velle.ext` API and the registry.** `VelleType`, `Literals`, `Operator`, `Sum`, `Samples`, `LiteralRejected`; `TypeRegistry.load()` over `ServiceLoader`, with the builtin table expressed in the same vocabulary so V8 and the evaluator have one lookup. E1 and E2 as `Diagnostic`s produced before the spec's own. Tests: a fixture type with deliberately false claims fails E2 on each clause.
2. **Parser and model.** `NamedType` in `parseTypeRef`; the top-level `uses <qualified> [as <Name>]` line (`uses` reserved); `Model(decls, registry)`; `VType.Ext`; resolution through `uses` lines and F1/E1. `Printer` prints `uses` lines. Tests: a spec naming an unregistered type; a name that is both shape and type with and without a renaming `uses`; two registered `Money`s with and without a picking `uses`.
3. **Validator.** `scalarTypeOf` and literal typing by the other operand; E3 sentences; `isOrderable` via the registry; the builtin function table keyed by capability (D4), with `max`/`min` as its first rows; V8 via operator facts; the arithmetic operand check F2 has never had (a `decimal + text` is refused at compile time, not at run time, for builtins too). Tests: each E3 refusal; the §2.4 spec validates.
4. **Evaluator and runtime.** `Value.VExt`; dispatch in `arith`, `compareValues`, `valueEquals`, `evalAgg`, `evalFun`; `convertScalar` with `validate`; `rawToValue` accepts the type's Kotlin object from a row. Tests: the §2.4 spec runs the deposit flow in memory.
5. **Stores.** The Row contract sentence in `Hydration.kt`; `SqliteTypeBinding<T>` in `stores/sqlite` (§2.5), loaded by ServiceLoader or passed in; `SqliteStore` consults it in `columnsOf`, `writeColumn`, `readColumn`, `renderCmp`; E4 at construction. Tests: the flow over SQLite with Bob's binding; startup refused without it; a push-down test for a binding that answers `<` and one that does not.
6. **Generators.** `Codegen` (`kotlinType`, `readExpr`, input classes), `Read.kt` (`ExtField`, `OrderedExtField`, typed `sum`), `StoreGen` (row classes decode), `SpecGen` (`samples`), `Generate.kt` loading the registry. `GeneratedSystem` loads the registry at construction.
7. **`extensions/finance/`, `extensions/finance-sqlite/`, and `examples/billing/`.** Alice's `MoneyType` and Bob's `SqliteMoneyBinding` as in §2.4, each with its service file, as two modules because they are two libraries; billing's amounts become `Money` and its output module depends on both; regenerate (memory: per-module generate, `--max-workers=1`); the generated tests pass with `Money.of(...)` values.
8. **Docs.** Reference §5 (the registered list; the `Money` sentence becomes true), §10 (D4; literal typing), §19 (the table's extension rows), §22 (the item moves to built); `grammar.md` (`typeName`, `usesDecl`); `checks.md` (F2 extensions, E1–E3, V8); `evaluation.md` (the Row contract holds the type's object; store bindings and E4; `validate` as a type-level refusal); `testgen.md` (samples); a new wiki page for engineers, "Extension types", beside `Binding-API.md`, opening with §2.0's statement of what a type is for and walking §2.4's Kotlin line by line; `GLOSSARY.md` entries for *extension type*, *store binding*, *registry*. The wiki page follows CLAUDE.md's reader rules: an engineer who has never seen Velle and wants `Money` by the end of the page.

## 6. What the build changed from the proposal above

- **Operator operand names are qualified.** `Operator.left/right/result` name a builtin by keyword (`"decimal"`; every number type is `"decimal"` in the table) and a registered type by `namespace.name`, never by the spec's name, since a `uses` line can rename a type. `VelleType.qualifiedName` exists for this. §2.2's code block said "as the spec names them"; the KDoc in `VelleType.kt` is the truth.
- **`SqliteTypeBinding` has `bind`/`read` only.** `write(ps, index, value)` was redundant with `bind` (the store calls `setObject`); §2.5's sketch kept both.
- **Bob's binding is an INTEGER of cents, answering all six comparisons.** §2.4's sketch was a TEXT decimal answering `==`/`!=`. The TEXT form lives in `extensions/finance-sqlite`'s tests as the binding that degrades.
- **`Money / Money → decimal` is not declared.** Alice's table is `+`, `-`, `Money * decimal`, `decimal * Money`. `Money.of(text)`, `Money.ofCents`, `Money.ZERO`; a `Money` is `cents: Long`, not a `BigDecimal`.
- **The worked spec's `never (Account where balance < 0)` does not validate**: it is a guarantee over a field a rule writes, and V10 fails closed on it (OQ16). The tests spell the same business fact as a refinement `shape Overdrawn = Account where balance < 0`, and refuse the act with `never (Deposit where amount <= 0)`. Nothing about types; the sketch was wrong about V10.
- **`many X` for a registered type `X`** parses as a relationship (the parser has no registry) and the model rewrites it to an owned set of values (`Model.decls` is the rewritten list). `one X` is F1 with the field form named.
- **A `uses` alias frees the name only when no other registered type takes it by default.** Two libraries' `Money` with `uses a.Money as Cash` leaves `b.Money` in scope as `Money`, so a shape `Money` is still E1. That is D1's rule ("a `uses` line never turns the default off") followed through.
- **Builtins in the same tables, partially.** `TypeRegistry.foldFacts` answers V8 for `decimal`'s `+`/`-` and for extension operators in one call; `max`/`min` are one branch keyed on the *ordered* capability in `Validator.checkFunctionCall` and `Evaluator.evalFun`. The rest of the builtin arithmetic (durations, collections, text) stays hard-coded; expressing it as registered rows is OQ66's work.
- **Literals are coerced at the write, not rewritten in the AST.** `Evaluator.coerceLiteral` reads a `VNum`/`VText` as the field's or the other operand's type at evaluation; the validator proved the read succeeds at compile time (`checkConstantAs`), so the runtime path is the same reader with a "validator gap" error behind it. Cheap and local; §2.3 said "once, at model build".
- **The in-memory store needs no binding** (as §2.5 says), and the SQLite store's E4 round-trips through a temporary table.
- **Where the fixtures live.** The compiler's own tests register `velle.test.Price`, `velle.testalt.Price` (two types, one name, so `uses` is exercised), and `velle.test.Badge` (unordered, text literal, a `validate`) under `compiler/src/test/resources/META-INF/services`. Names were chosen not to collide with any example's shape: `Tag` collided with enrollment. The compiler's tests also depend on `extensions/finance` because the billing fixture now names `Money`.
- **`velle.ext` lives in the compiler module.** A type library therefore depends on `:compiler`. A separate API module (`velle-ext`) would let Alice depend on the API alone; not done, noted below.

## 5. Residue, not for this question

- **A separate `velle-ext` API module**, so a type library depends on the API and not on the compiler. The interfaces are self-contained (`velle.ext.*` imports nothing from `velle`); splitting the module is Gradle work.
- **The remaining builtin arithmetic as registered rows** (durations on `Date`/`DateTime`, collection `+`/`-`, the text operators), so one table answers every V8 and every evaluator dispatch: OQ66, with the functions.
- **Custom functions and predicates** are OQ66 (opened 2026-10-24 at Dave's request): the same registry, a function declaring the capability each argument needs and the same two fold facts, the builtins re-expressed the same way; accessors on extension types (D3) arrive there if anywhere. OQ59 keeps the codegen half of the escape hatch.
- **Generators**: `initially randomUUID` has no extension analog; a type that wants `initially nextInvoiceNumber` is a function with no arguments and a non-determinism flag, also OQ66.
- **Durations** on extension types (`deadline + 3 days` where `deadline` is an extension type): not supported; `Date`/`DateTime` keep duration arithmetic to themselves.
- **A Spring Boot store**, the "one store implementation" of §2.0: whether its binding interface lets Chris register a type's mapping inline (a lambda pair) rather than as a library, and whether Velle ships bindings for common types with it.
- **UI hints** (`Velle-UI.md`): a type could later carry how a value is entered and rendered; nothing in this interface blocks it.
