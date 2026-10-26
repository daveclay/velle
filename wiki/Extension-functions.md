# Extension functions

An extension function is a function a spec may call that is not built into Velle: a Kotlin class an engineer writes, registered on the classpath, that a spec then calls exactly as it calls `abs(amount)` or `year(due)`. A predicate is a function whose result is `boolean`, called where a condition goes. After this page you can call a function someone else published, write one of your own against the capabilities a type declares, and know what Velle's own functions are made of, since `max`, `lowercase` and `year` are written the same way.

A function is a pure function of its arguments. Velle evaluates it whenever a rule or a refinement needs its value, as often as it likes, and expects the same answer each time. A function never changes state, never reads the clock, and never fails: when it has no answer for an argument it has, it gives an absent value, which the spec narrows as it narrows any optional ([§10](Reference-10-Predicate-expressions)).

## The situation

The fulfilment team ships on business days only. The spec should say `Order where isBusinessDay(placed)` and nothing more, and the rule about what a business day is, weekends today and public holidays next quarter, should live in one piece of Kotlin the engineers own. The analytics team wants the median of a set of scores, which Velle has no word for.

## Calling a function in a spec

From `compiler/src/test/kotlin/velle/ExtensionFunctionTest.kt`:

```
uses velle.test.isBusinessDay

act Order {
    placed: Date
    placedAt: DateTime?
    scores: many integer
    amount: decimal
    median: integer? = median(scores)
    rounded: decimal = roundTo(amount, 1)
    clamped: decimal = clamp(amount, 0, 100)
}

shape Weekday = Order where isBusinessDay(placed)
shape WeekdayMoment = Order where isBusinessDay(placedAt)
shape Topped = Order where max(scores) >= 10
```

- `uses velle.test.isBusinessDay` picks one library's function, because two libraries on this classpath register a function of that name. With one library the line is not needed: a registered function is in scope under its own name, as a registered type is.
- `isBusinessDay(placed)` calls the function on a `Date`. `isBusinessDay(placedAt)` calls the same function on a `DateTime`, which it reads in the zone the system runs in. The function was written once, against "anything with a calendar date", and both types have one.
- `isBusinessDay(placedAt)` on an order with no `placedAt` is absent, and a condition over an absent value is false, so the order is not a `WeekdayMoment`. No function is ever called with an absent argument.
- `median(scores)` is a function over a `many` of values, with the type of one of them. The result is optional because the set may be empty.
- `roundTo(amount, 1)` and `clamp(amount, 0, 100)` take constants. A constant takes the type of the argument it is paired with, so `clamp(balance, 0, 100)` on a `balance: Money` reads both constants as `Money`.
- `max(scores)` is a builtin, and nothing on this page is different for it: `max` is a registered function Velle ships, written against the same ordering capability `median` uses.

A function with a lowercase name is written in a `uses` line as a type is, with the alias in the same case:

```
uses velle.finance.roundToCents             -- this library's roundToCents, when two libraries register one
uses velle.finance.roundToCents as cents    -- the same function, written cents in this spec
```

## Writing a function

A function is a class implementing `velle.ext.VelleFunction`. It names itself, lists the shapes a call may take, and carries the body for each shape. From `compiler/src/test/kotlin/velle/ext/TestFunctions.kt`:

```kotlin
class IsBusinessDay : VelleFunction {
    override val name = "isBusinessDay"
    override val namespace = "velle.test"
    override val signatures = listOf(Signature(
        parameters = listOf(Parameter("date", Accepts.Is(HasCalendarDate::class))),
        result = Returns.Is(BooleanType::class),
        apply = { ctx, args ->
            val d = args[0].capability<HasCalendarDate<Any>>().calendarDate(args[0].value, ctx)
            d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY
        },
    ))
}
```

- `name` is what a spec calls: a lowercase identifier. `namespace` is the library's dotted lowercase path, and `namespace.name` is what a `uses` line spells.
- `signatures` lists the shapes a call may take. Most functions have one. The compiler picks the first whose parameters the arguments satisfy and refuses the call, naming what each parameter takes, when none does.
- `parameters` says what each argument must be. `Accepts.Is(HasCalendarDate::class)` accepts any type that implements the `HasCalendarDate` capability: `Date`, `DateTime`, and any extension type whose author implemented it. `Accepts.Is(DateType::class)` would accept a `Date` and nothing else. `Accepts.SameAs(0)` accepts whatever type the first argument turned out to have, which is how `clamp(value, low, high)` keeps its three arguments to one type. `Accepts.ManyOf(Ordered::class)` accepts a `many` of values of any ordered type.
- `result` says what the call is: a named type with `Returns.Is`, the type of an argument with `Returns.SameAs(0)`, or the element type of a `many` argument with `Returns.ElementOf(0)`. A signature whose result may be absent even when every argument is present, such as the median of an empty set, says `optionalResult = true`.
- `apply` is the body. It receives the system's context and one `Typed` per argument: the argument's type and its value. The body asks the type for the capability the parameter declared, `capability<HasCalendarDate<Any>>()`, and works through that, so it never looks at the Kotlin class of a value. It returns the result as the result type's Kotlin value, or `null` for absent. The context carries the zone the system runs in, which a `DateTime` needs to give its date.

The capabilities a parameter may ask for are interfaces in `velle.ext`, each carrying the one operation a function needs:

| Capability | What a type that implements it offers | Who asks for it |
|---|---|---|
| `Ordered<T>` | a total order over its values | `< <= > >=`, `max`, `min`, a selector's `by`, a query order |
| `Summable<T>` | a zero and how two values add | `sum` |
| `Numeric<T>` | its value as a number, and a value from one | `roundTo` above, any arithmetic function |
| `Signed<T>` | its value without its sign | `abs` |
| `HasCalendarDate<T>` | the calendar date it names, in the system's zone | `year`, `month`, `day`, `dayOfWeek`, `isBusinessDay` above |
| `HasTimeOfDay<T>` | the time of day it names, in the system's zone | `hour`, `minute` |

A function over a `many` reads its elements as a list and names the element type. From the same file:

```kotlin
class Median : VelleFunction {
    override val name = "median"
    override val namespace = "velle.test"
    override val signatures = listOf(Signature(
        parameters = listOf(Parameter("values", Accepts.ManyOf(Ordered::class))),
        result = Returns.ElementOf(0),
        optionalResult = true,
        apply = { _, args ->
            val sorted = args[0].elements.sortedWith(args[0].capability<Ordered<Any>>().ordering)
            if (sorted.isEmpty()) null else sorted[sorted.size / 2]
        },
    ))
}
```

A function that a rule folds a value into, `x = f(x, y)`, may state two facts about itself that [§19](Reference-19-Self-referential-folds-and-tolerates) reads: `foldStepsCommute`, that applying two steps in either order gives the same result, and `foldIdempotent`, that applying one step twice changes nothing. Both default to false. `max` and `min` claim both, so a rule may fold a peak at a scheduled tick with no guard; a function that claims neither is refused in the same place `+` is.

A function is registered by naming its class in `META-INF/services/velle.ext.VelleFunction` in the library's resources, one class per line. A module that depends on the library sees the function. A function needs no store binding: the runtime evaluates it in memory, and a store that cannot translate it simply hands back every candidate record for the runtime to decide over ([Store API](Store-API)).

## The builtin functions

`lowercase`, `uppercase`, `trim`, `length`, `abs`, `year`, `month`, `day`, `dayOfWeek`, `hour`, `minute`, `max` and `min` are registered functions in the `velle` namespace, implemented through the same interface. From `compiler/src/main/kotlin/velle/ext/builtin/BuiltinFunctions.kt`:

```kotlin
object Abs : BuiltinFunction("abs") {
    override val signatures = listOf(Signature(
        parameters = listOf(Parameter("value", Accepts.Is(Signed::class), takes = "a number or a duration")),
        result = Returns.SameAs(0),
        apply = { _, args -> args[0].capability<Signed<Any>>().abs(args[0].value) },
    ))
}
```

`abs` accepts anything `Signed`, which the number types and `duration` are, and returns what it was given. `takes` is the phrase a refusal uses for the parameter; without it the compiler lists the types that satisfy it. A registered type that implements `Signed` is accepted by `abs` with nothing else declared. The builtin types in `compiler/src/main/kotlin/velle/ext/builtin/BuiltinTypes.kt` are the reference implementation of each capability: what `Ordered` means is what `decimal` does with it.

The aggregates `count`, `sum`, `latest` and `first` have their own syntax, a collection and a `by` list, and are not functions. The text operators `contains`, `starts with`, `ends with`, `like` and `matches` are written between their operands and are not functions either.

## What the compiler checks

- **Rejects** a registered function whose name is not a lowercase identifier, is a builtin function's name, or is a construct with its own syntax (`count`, `sum`, `latest`, `first`, `exists`); two functions claiming one qualified name; a signature with no parameters list, a variadic parameter that is not last, or a `SameAs` naming a later parameter; and a call of a name that two registered functions share, naming the `uses` line to write ([checks.md, E1](checks#e1--registered-type-and-function-names)).
- **Rejects** a call that no signature accepts: the wrong number of arguments, an argument of a type the parameter does not take, or two arguments that must be one type and are not, naming what the parameter takes ([checks.md, F2](checks#f2--type-checking)).
- **Rejects** a call whose argument is an extension type that does not implement the capability the parameter asks for, naming the Kotlin class and the interface to implement ([checks.md, E3](checks#e3--a-capability-the-type-does-not-declare)).
- **Rejects** a constant beside an extension argument that the type's reader refuses ([checks.md, F2](checks#f2--type-checking)).
- **Rejects** a call of a name no builtin or registered function has ([checks.md, F2](checks#f2--type-checking)).
- **Rejects** a rule that folds a value through a function claiming neither fold fact, in the places [§19](Reference-19-Self-referential-folds-and-tolerates) refuses an operator that claims neither ([checks.md, V8](checks#v8--fold-obligations-and-tolerance-coverage)).

## Related pages

- [§5 Scalars](Reference-05-Scalars), the builtin functions and the types they are written over.
- [§10 Predicate expressions](Reference-10-Predicate-expressions), where a call goes and how an absent value narrows.
- [§19 Self-referential folds](Reference-19-Self-referential-folds-and-tolerates), what the two fold facts buy a rule.
- [Extension types](Extension-types), the types a function is written over, and the capabilities a type implements.
- [Store API](Store-API), why a function needs no store side.
