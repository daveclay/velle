# Extension types

An extension type is a scalar type that is not built into Velle: a Kotlin class an engineer writes, registered on the classpath, that a spec then names in type position exactly as it names `Date`. `Money` is one. After this page you can use a type someone else published, write one of your own, and write the piece that lets a store hold it.

An extension type is one value and a mapping for it, never a business concept. Business concepts live in the spec as [shapes](Reference-03-shape) and [rules](Reference-11-rule-when-on), where the product owner can read them. A value with parts, an amount and its currency, is a shape with two fields, not a type. Types exist so that the Kotlin around a system can carry the data types of diverse systems in and out of Velle without the language itself carrying lower-level mappings.

## The situation

The billing team wants amounts to be exact to the cent and to add up without anyone writing a rounding rule. The engineers already have a `Money` class their other services use, and the database team wants to choose how it is stored. The spec should say `price: Money` and nothing more.

## Using a type in a spec

From `examples/billing/billing.velle`:

```
act LineItem {
    invoice: one Invoice
    description: text
    price: Money
    quantity: integer
    amount: Money = price * quantity
}

never (LineItem where price < 0)

shape Invoice {
    customer: one Customer
    due: Date
    total: Money = sum(lineItems, amount)
    balance: Money = total - sum(payments, amount)
    status: text = if balance <= 0 then "paid" else if due < today then "overdue" else "open"
}
```

- `price: Money` declares a stored field of the type. `Money` is in scope because a library registering a type of that name is on the classpath of the module that compiles the spec. Nothing in the spec declares it.
- `amount: Money = price * quantity` multiplies a `Money` by a number. The type's author declared that `Money * decimal` is a `Money`; a line the author did not declare is a compile error.
- `never (LineItem where price < 0)` compares a `Money` with a number constant. The constant reads as a `Money`, through the reader the type declares, and `<` applies because the type declares an ordering.
- `total: Money = sum(lineItems, amount)` adds up a collection of amounts. The type declares how two amounts add and what an empty collection sums to.
- `balance <= 0` is the same constant reading again: `0` is read as `Money` once, when the spec compiles.

A field may hold a set of values of the type, written `many Money` as `many text` is written, compared with the type's own equality; a committed set with two equal values is refused, as `many text` is. `one Money` is not a field: `one` and `many` take a shape, and a single value of a type is written `price: Money`. Duration arithmetic such as `due + 3 days` applies to `Date` and `DateTime` only; an extension type takes part in the arithmetic its author declared and nothing else.

When two libraries register a type under one name, or a shape in the spec wants the name, a `uses` line at the top level picks or renames:

```
uses velle.finance.Money              -- this library's Money, when two libraries register one
uses velle.finance.Money as Cash      -- the same type, written Cash in this spec
```

The qualified name after `uses` is the library's namespace and the type's name. With `as`, the type's own name is no longer in scope for it in this spec, so a shape may take it. A `uses` line never removes any other registered type from scope: when two libraries register `Money` and the spec renames one of them, the other is still in scope as `Money`, so a shape of that name is still refused until a second `uses` line renames it too. A `uses` line naming a qualified name no library registers is a compile error listing the registered types that share its last part.

## Constants, and a value with parts

A constant takes its type from the field or the other operand beside it, and that type's reader decides whether the constant is well formed. If a library registered a `Currency` that reads a three-letter text and a `Rate` that reads a fraction between zero and one, a spec would write:

```
shape Account {
    owner: one Customer
    balance: Money initially 0            -- the number 0, read by Money as 0.00
    currency: Currency                    -- committed; no constant here
    interest: Rate initially 0.05         -- the number 0.05, read by Rate
}

never (Account where currency == "XXX")   -- "XXX" read by Currency, because currency is Currency
never (Account where interest > 1)        -- 1 read by Rate; 1.5 here is a compile error, since Rate refuses it
```

A reader refuses with a reason, and the reason is the compile error at the constant, in the type's own words. For arithmetic the operator lines decide: `balance * 2` matches `Money * decimal`, so `2` is a number; `balance + 10` matches `Money + Money`, so `10` is read as a `Money`. When more than one line could type the constant, the compiler asks for a field or a typed value instead.

A price in a currency is two values, so it is a shape with two fields, and a constant of it is two constants:

```
shape Price {
    amount: Money
    currency: Currency
}

shape Quote {
    price: one Price
}

never (Quote where price.amount == 12.50 and price.currency == "USD")
```

There is no way to write `12.50 USD` as one constant, and no reader splits a text into parts. Everything the spec can say about a price is said about its two fields, where the product owner can read it.

## A type with no ordering

A type that is only ever equal or not, a stock-keeping code for instance, declares no ordering. The spec may compare two of its values with `==` and `!=`, hold it in a field, and commit it. It may not write `sku > "A-100"`, order a query by it, or ask `max` of it: each is refused with the sentence that the type declares no ordering and the Kotlin class where one would be added, if the business meant one.

## Writing a type

A type is a class implementing `velle.ext.VelleType<T>`, where `T` is the Kotlin class a value has, and beside it the capability interfaces the type offers: `Ordered<T>` for ordering, `Summable<T>` for `sum`, and the ones in the [Extension functions](Extension-functions) guide that functions ask for. A capability the class does not implement is one the spec cannot use, and the compiler says so. From `extensions/finance/src/main/kotlin/velle/finance/MoneyType.kt`:

```kotlin
class MoneyType : VelleType<Money>, Ordered<Money>, Summable<Money> {
    override val name = "Money"
    override val namespace = "velle.finance"
    override val kotlinClass = Money::class

    override val literals = Literals<Money>(fromNumber = { n ->
        if (n.stripTrailingZeros().scale() > 2) throw LiteralRejected("$n has more than two decimal places; Money is whole cents")
        Money.of(n)
    })

    override fun canonical(value: Money): String = value.toString()

    override val ordering: Comparator<Money> = Comparator { a, b -> a.compareTo(b) }

    override val operators: List<Operator<*, *, *>> = listOf(
        Operator<Money, Money, Money>("+", qualifiedName, qualifiedName, qualifiedName, { a, b -> a + b }, foldStepsCommute = true, foldIdempotent = false),
        Operator<Money, Money, Money>("-", qualifiedName, qualifiedName, qualifiedName, { a, b -> a - b }, foldStepsCommute = true, foldIdempotent = false),
        Operator<Money, BigDecimal, Money>("*", qualifiedName, "decimal", qualifiedName, { a, b -> a * b }, foldStepsCommute = false, foldIdempotent = false),
        Operator<BigDecimal, Money, Money>("*", "decimal", qualifiedName, qualifiedName, { a, b -> b * a }, foldStepsCommute = false, foldIdempotent = false),
    )

    override val zero: Money = Money.ZERO
    override fun plus(a: Money, b: Money): Money = a + b

    override val samples = Samples(
        sample = Money.of("12.50"),
        below = { Money.ofCents(it.cents - 1) },
        above = { Money.ofCents(it.cents + 1) },
        kotlinSource = { "velle.finance.Money.of(\"$it\")" },
    )
}
```

- `name` is what a spec writes. `namespace` is the library's dotted lowercase path, and `namespace.name` is what a `uses` line spells.
- `kotlinClass` is the class a value has everywhere Kotlin sees it: in the generated input classes, in model objects, in a store's row. A committed value of any other class is refused before any rule runs, the same way a missing required field is, with no `never` behind it.
- `literals` says which constants a spec may write where a `Money` is expected: a number, a quoted text, both, or neither. The reader turns the constant into a value, or throws `LiteralRejected` with the reason the compiler reports at the constant. `Money` reads `12.50` and refuses `0.125`.
- `canonical` gives one text per distinct value. The compiler uses it to tell values apart, to check the type's other claims, and as the text that stands for the value wherever the runtime needs one. `display`, which defaults to it, is how a value reads in a rejection message.
- `ordering`, from `Ordered<Money>`, is the capability behind `<`, `<=`, `>`, `>=`, `max`, `min`, a selector's `by`, and a query's order. A class that does not implement `Ordered` refuses every one of those for the type.
- `operators` is the arithmetic the type takes part in, one line per combination of left type, operator, right type, and result. Types are named by qualified name: a builtin by its keyword, a registered type by `namespace.name`. Both directions of a mixed operation are separate lines, as `Money * decimal` and `decimal * Money` are above. An operator returns a value for every pair of its operands and has no way to refuse one; an exception thrown inside it fails the whole transaction and is a bug in the type. An operation that is only sometimes defined is not an operator. Each line carries two facts about folding a value into itself, `x = x + y` ([§19](Reference-19-Self-referential-folds-and-tolerates)): whether applying two steps in either order gives the same result, and whether applying one step twice changes nothing. `+` on `Money` has the first and not the second, so a rule may fold a balance at a scheduled tick, and an unguarded fold after a commit is still refused.
- `zero` and `plus`, from `Summable<Money>`, are what `sum(collection, field)` does over a field of the type: the value of an empty collection, and how two values add. A class that does not implement `Summable` refuses `sum` on the type.
- `samples` is what the generated tests need: a value to commit, the nearest values below and above a given one, and the Kotlin source that constructs a value in a test file. `below` and `above` return `null` at the type's edge. For a type with discrete values the step is one unit, a cent for `Money`; for a continuous one any strictly smaller or larger value serves. For a `never (LineItem where price < 0)` the generated test commits `below(0)` expecting refusal and `0` expecting acceptance.

Two members have defaults: `validate` returns the reason a well-formed-looking value is still refused at the boundary, or `null`, and defaults to accepting; `equal` defaults to Kotlin `==`. A reason from `validate` is about the value's form, a blank code or an impossible date. A business rule such as "a balance is never negative" is a `never` in the spec, where the product owner can read it, never a line in `validate`.

A type is registered by naming its class in `META-INF/services/velle.ext.VelleType` in the library's resources, one class per line. A module that depends on the library sees the type. The registry is read in three places, because the spec is read in three places: by the compiler when it generates, by the generated system when it starts, and by a store when it is built.

The compiler checks the type before any spec: the name and namespace are well formed, no two types claim one qualified name, and every claim holds on the type's own samples. A type whose `+` claims that two steps commute, while its samples show otherwise, is refused with the clause that failed and the class to fix.

## Holding a type in a store

A type says nothing about storage. How a store holds it is a separate piece, written by whoever knows the store, in the store's own vocabulary. For the SQL stores the piece is a `velle.sql.SqlTypeBinding<T>`, written once for every SQL database Velle has a store for. From `extensions/finance-sql/src/main/kotlin/velle/finance/sql/SqlMoneyBinding.kt`:

```kotlin
class SqlMoneyBinding : SqlTypeBinding<Money> {
    override val type = "velle.finance.Money"
    override val storage = StorageClass.INTEGER
    override fun encode(value: Money): Any = value.cents
    override fun decode(stored: Any): Money = Money.ofCents(stored as Long)
    override val answers = ALL_COMPARISONS
}
```

- `type` names the registered type by qualified name.
- `storage` is the kind of column a field of this type is held in: text, integer, decimal, double, boolean, date, datetime, time, duration, uuid, or bytes. The binding never names a database's own column type. Each SQL store maps the kind to its column, so an integer is `INTEGER` on SQLite.
- `encode` turns a value into the plain value that kind of column holds, here a `Long` of cents. `decode` turns it back.
- `answers` lists the comparisons the encoding answers with the value's own meaning. An integer of cents orders as amounts order, so this column answers all six. A binding that holds a decimal as text would list `==` and `!=` only, and the store would decide `<` in memory instead of asking the database.

The binding is registered in `META-INF/services/velle.sql.SqlTypeBinding`. A SQL store loads every binding on the classpath when it is built, or takes a list. It refuses to start when the spec stores a type it has no binding for, and when a binding does not read back the type's own sample values unchanged through the database's column.

A `many` field of the type is a membership table whose value column uses the same binding. The in-memory runtime needs no binding, so a spec runs, and its generated tests pass, before anyone has written one. Another store defines its own binding interface in its own vocabulary: a column type means something to a database and nothing to a wire protocol, so a store over a binary protocol would ask for an encoding and never for a column.

The three pieces are three libraries. The type library knows nothing about stores. The binding library knows the type and one store. The system that uses both depends on both, and its spec names neither.

## What the generated code looks like

The field is the type's own class everywhere. The input class for `LineItem` takes a `Money` for `price`; the `Invoice` model object has `total: Money`; the query builder accepts `read.invoices.where { balance gt Money.of("10.00") }` and `orderBy { total.descending }`, and refuses `orderBy` on a type with no ordering at compile time. `read.invoices.sumOf { total }` returns a `Money`. The generated tests commit `Money.of("12.50")` for an unconstrained amount and `Money.of("-0.01")` as the value just below a `never (LineItem where price < 0)` bound. The [Binding API](Binding-API) page covers the rest of the surface.

## What the compiler checks

- **Rejects** a registered type whose name is not a capitalized identifier or is a builtin name, two types claiming one qualified name, and a name in a spec that two registered types share or that a shape also uses, naming the `uses` line to write ([checks.md, E1](checks#e1--registered-type-and-function-names)).
- **Rejects** a registered type whose claims fail on its own samples: an ordering that disagrees with its below and above values, a reader that does not read its own canonical text, an operator fact its samples contradict ([checks.md, E2](checks#e2--a-types-claims-hold-on-its-samples)).
- **Rejects** a spec that asks a type for a capability it does not implement: `<` on a type with no ordering, `+` with no such operator line, `sum` with no sum, a function whose parameter asks for an interface the type lacks, naming the Kotlin class and the interface to implement ([checks.md, E3](checks#e3--a-capability-the-type-does-not-declare)).
- **Refuses to start** a store that holds a type it has no binding for, or whose binding does not round-trip the type's samples ([checks.md, E4](checks#e4--a-store-holds-every-type-it-stores)).
- **Rejects** a constant the type's reader refuses, a constant of a form the type does not read, a comparison between two different types, and a value of a builtin type assigned to a field of an extension type ([checks.md, F2](checks#f2--type-checking)).
- **Rejects** a name in type position that is neither a builtin, nor a shape after `one` or `many`, nor a registered type in scope ([checks.md, F1](checks#f1--name-resolution-and-casing)).

## Related pages

- [§5 Scalars](Reference-05-Scalars), the builtin types an extension type stands beside.
- [§10 Predicate expressions](Reference-10-Predicate-expressions), where comparisons and constants are read.
- [§19 Self-referential folds](Reference-19-Self-referential-folds-and-tolerates), what the two operator facts buy a rule.
- [Extension functions](Extension-functions), a function written against the capabilities a type implements.
- [Binding API](Binding-API), the generated Kotlin the type appears in.
- [Store API](Store-API), the whole contract a store implements, and where the binding sits in it.
- [`evaluation.md`](evaluation), the boundary and the store contract.
