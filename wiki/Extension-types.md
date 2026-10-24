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

When two libraries register a type under one name, or a shape in the spec wants the name, a `uses` line at the top level picks or renames:

```
uses velle.finance.Money              -- this library's Money, when two libraries register one
uses velle.finance.Money as Cash      -- the same type, written Cash in this spec
```

The qualified name after `uses` is the library's namespace and the type's name. With `as`, the type's own name is no longer in scope for it in this spec, so a shape may take it. A `uses` line never removes any other registered type from scope.

## Writing a type

A type is a class implementing `velle.ext.VelleType<T>`, where `T` is the Kotlin class a value has. Every capability the spec might use is a member the author writes; a member left as `null` or empty is a capability the spec cannot use, and the compiler says so. From `extensions/finance/src/main/kotlin/velle/finance/MoneyType.kt`:

```kotlin
class MoneyType : VelleType<Money> {
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

    override val sum = Sum(Money.ZERO) { a, b -> a + b }

    override val samples = Samples(
        sample = Money.of("12.50"),
        below = { Money.ofCents(it.cents - 1) },
        above = { Money.ofCents(it.cents + 1) },
        kotlinSource = { "velle.finance.Money.of(\"$it\")" },
    )
}
```

- `name` is what a spec writes. `namespace` is the library's dotted lowercase path, and `namespace.name` is what a `uses` line spells.
- `kotlinClass` is the class a value has everywhere Kotlin sees it: in the generated input classes, in model objects, in a store's row. A committed value of any other class is refused before any rule runs.
- `literals` says which constants a spec may write where a `Money` is expected: a number, a quoted text, both, or neither. The reader turns the constant into a value, or throws `LiteralRejected` with the reason the compiler reports at the constant. `Money` reads `12.50` and refuses `0.125`.
- `canonical` gives one text per distinct value. The compiler uses it to tell values apart and to check the type's other claims.
- `ordering` is the capability behind `<`, `<=`, `>`, `>=`, `max`, `min`, a selector's `by`, and a query's order. `null` here refuses every one of those for the type.
- `operators` is the arithmetic the type takes part in, one line per combination of left type, operator, right type, and result. Types are named by qualified name: a builtin by its keyword, a registered type by `namespace.name`. Each line carries two facts about folding a value into itself, `x = x + y` ([§19](Reference-19-Self-referential-folds-and-tolerates)): whether applying two steps in either order gives the same result, and whether applying one step twice changes nothing. `+` on `Money` has the first and not the second, so a rule may fold a balance at a scheduled tick, and an unguarded fold after a commit is still refused.
- `sum` is what `sum(collection, field)` does over a field of the type: the value of an empty collection, and how two values add. `null` refuses `sum` on the type.
- `samples` is what the generated tests need: a value to commit, the nearest values below and above a given one, and the Kotlin source that constructs a value in a test file.

Two members have defaults: `validate` returns the reason a well-formed-looking value is still refused at the boundary, or `null`, and defaults to accepting; `equal` defaults to Kotlin `==`.

A type is registered by naming its class in `META-INF/services/velle.ext.VelleType` in the library's resources, one class per line. A module that depends on the library sees the type.

The compiler checks the type before any spec: the name and namespace are well formed, no two types claim one qualified name, and every claim holds on the type's own samples. A type whose `+` claims that two steps commute, while its samples show otherwise, is refused with the clause that failed and the class to fix.

## Holding a type in a store

A type says nothing about storage. How a store holds it is a separate piece, written by whoever knows the store, in the store's own vocabulary. For the SQLite store the piece is a `velle.sqlite.SqliteTypeBinding<T>`. From `extensions/finance-sqlite/src/main/kotlin/velle/finance/sqlite/SqliteMoneyBinding.kt`:

```kotlin
class SqliteMoneyBinding : SqliteTypeBinding<Money> {
    override val type = "velle.finance.Money"
    override val columnType = "INTEGER"
    override fun bind(value: Money): Any = value.cents
    override fun read(rs: ResultSet, column: String): Money = Money.ofCents(rs.getLong(column))
    override val answers = setOf("==", "!=", "<", "<=", ">", ">=")
}
```

- `type` names the registered type by qualified name.
- `columnType` is the SQL type of the column the store creates for a field of this type.
- `bind` hands the value to JDBC as the column holds it. `read` reads it back.
- `answers` lists the comparisons the column answers with the value's own meaning. An integer of cents orders as amounts order, so this column answers all six. A column that holds a decimal as text would list `==` and `!=` only, and the store would decide `<` in memory instead of asking the database.

The binding is registered in `META-INF/services/velle.sqlite.SqliteTypeBinding`. The SQLite store loads every binding on the classpath when it is built, or takes a list. It refuses to start when the spec stores a type it has no binding for, and when a binding does not read back the type's own sample values unchanged.

The three pieces are three libraries. The type library knows nothing about stores. The binding library knows the type and one store. The system that uses both depends on both, and its spec names neither.

## What the generated code looks like

The field is the type's own class everywhere. The input class for `LineItem` takes a `Money` for `price`; the `Invoice` model object has `total: Money`; the query builder accepts `read.invoices.where { balance gt Money.of("10.00") }` and `orderBy { total.descending }`, and refuses `orderBy` on a type with no ordering at compile time. `read.invoices.sumOf { total }` returns a `Money`. The generated tests commit `Money.of("12.50")` for an unconstrained amount and `Money.of("-0.01")` as the value just below a `never (LineItem where price < 0)` bound. The [Binding API](Binding-API) page covers the rest of the surface.

## What the compiler checks

- **Rejects** a registered type whose name is not a capitalized identifier or is a builtin name, two types claiming one qualified name, and a name in a spec that two registered types share or that a shape also uses, naming the `uses` line to write ([checks.md, E1](checks#e1--registered-type-names)).
- **Rejects** a registered type whose claims fail on its own samples: an ordering that disagrees with its below and above values, a reader that does not read its own canonical text, an operator fact its samples contradict ([checks.md, E2](checks#e2--a-types-claims-hold-on-its-samples)).
- **Rejects** a spec that asks a type for a capability it does not declare: `<` on a type with no ordering, `+` with no such operator line, `sum` with no sum, naming the Kotlin class where the capability would be added ([checks.md, E3](checks#e3--a-capability-the-type-does-not-declare)).
- **Refuses to start** a store that holds a type it has no binding for, or whose binding does not round-trip the type's samples ([checks.md, E4](checks#e4--a-store-holds-every-type-it-stores)).
- **Rejects** a constant the type's reader refuses, a constant of a form the type does not read, a comparison between two different types, and a value of a builtin type assigned to a field of an extension type ([checks.md, F2](checks#f2--type-checking)).
- **Rejects** a name in type position that is neither a builtin, nor a shape after `one` or `many`, nor a registered type in scope ([checks.md, F1](checks#f1--name-resolution-and-casing)).

## Related pages

- [§5 Scalars](Reference-05-Scalars), the builtin types an extension type stands beside.
- [§10 Predicate expressions](Reference-10-Predicate-expressions), where comparisons and constants are read.
- [§19 Self-referential folds](Reference-19-Self-referential-folds-and-tolerates), what the two operator facts buy a rule.
- [Binding API](Binding-API), the generated Kotlin the type appears in.
- [`evaluation.md`](evaluation), the boundary and the store contract.
