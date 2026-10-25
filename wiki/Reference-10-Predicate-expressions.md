# 10. Predicate expressions

A predicate is a yes-or-no question asked of one record: is this invoice overdue, does this ticket have an assignee, has a receipt been sent for this payment. Predicates are how a spec says which records a rule applies to and which configurations are forbidden. This page teaches the expressions you can write inside one. After reading it you can write the `where` clause of any [refinement](Reference-08-Refinements), any inline filter over a collection, and any [`never`](Reference-21-never) declaration.

## Comparing values

The comparison operators are `==`, `!=`, `<`, `<=`, `>`, and `>=`.

- `==` is the only spelling of equality. `=` is for defining a shape (`shape X = Y where ...`) and for [assignment](Reference-12-Assignment).
- Comparing two records with `==` asks whether they are the same record, by their `id` ([§5](Reference-05-Scalars)). It never compares them field by field.

Text values also support `contains`, `starts with`, `ends with`, and `like`, all case-sensitive. In a `like` pattern, `%` matches any run of characters, including none, `_` matches one character, and `\` escapes either.

## Testing presence, emptiness, and membership with `is`

`is` has three uses.

**Presence of an optional value.** `assignee is none` and `response is some` test whether an optional field or an optional to-one relationship holds a value.

**Emptiness of a collection.** `corrections is empty` and `corrections is not empty` test a `many` relationship or any collection expression.

**Membership of a refinement.** `alert is UnacknowledgedAlert` asks whether the record is currently in that refinement. `invoice is not IssuedInvoice` is the negation, the same as `not (invoice is IssuedInvoice)` and the spelling Velle's diagnostics use. Inside a `requires` declaration, and only there, membership may also be written `pharmacist to be LicensedPharmacist` ([§21](Reference-21-never)).

A membership test is legal when the left side and the named refinement share a base shape, or when the named shape is an operand of the left side's static union ([§6](Reference-06-Relationships)): `target is Post` on a field `target: one Content`. A test between shapes with no base in common is a compile error, not a test that is always false.

**A membership test also narrows.** Once `x is Refinement` holds earlier in the same `and` chain, the rest of the chain may read the refinement's own properties through `x` ([§8](Reference-08-Refinements); for a union operand's own fields, [§9](Reference-09-Composing-refinements)). In an `if` expression, the `else` branch of a test over a two-operand union narrows to the other operand, since a record has exactly one base.

## Testing for related records with `exists`

`exists Receipt for this` asks whether any `Receipt` points at the current record, and `not exists AccountFlagResolved for this` that none does. `for` picks the field on `Receipt` whose type matches the subject.

That shorthand stops working when the shape has two fields of the matching type. Given `Referral { referrer: one Customer, referee: one Customer }`, `exists Referral for this` cannot say which field is meant. Write the general form, which filters the shape with `where`:

```
exists (Referral where referee == this)
exists (Referral where referee == this and referrer is VipCustomer)
```

The general form is also how to add a second condition on the related record, as the second line shows.

## Boolean fields as conditions

A boolean field is a complete condition on its own. `where applied` means `applied == true`, and `where not applied` means `applied == false`, so a flag refinement reads as the business phrase: `Deposit where not applied`.

## Counting and summing

```
count(<collection-expr>)
sum(<collection-expr>, <field>)
```

The first argument is any collection expression. A relationship filtered with `where`, such as `invoices where OverdueInvoice`, is one, so `count(invoices where OverdueInvoice)` needs no further syntax.

## Reaching through relationships

A relationship is followed with a dot: `response.outcome`, `ward.admissions`, including a relationship back to the same shape.

A dot through an optional value is allowed only when the value is known to be present: its type is not optional, or an `is some` or `is none` test earlier in the same `and` chain, or in the matching branch of an `if`, has established it. Plain `.` on an optional value with no such test is a compile error.

`?.` needs no test. If the value on its left is absent, the rest of the path evaluates to `none`:

```
shape Foo {
    parent: one Foo?
    root: one Foo? = if parent?.root is none then parent else parent?.root
}
```

## Naming a level with `as`

Inside a predicate, `this` always means the record the refinement is defined over, however deep the nesting. A bare field name means a field of the innermost collection being filtered. Most nested expressions need nothing more: `exists (invoices where OverdueInvoice and count(payments where FailedPayment) >= 1)` reads each name relative to the collection it sits in.

`as` names a middle level so a deeper level can read it. Here the filter over `payments` must compare against the invoice's own amount. `this` would mean the customer, and a bare `amount` the payment's:

```
shape CustomerWithOverpayment =
  Customer where exists (
    invoices as inv where
      count(inv.payments where amount > inv.amount) >= 1
  )
```

An `as` name is visible inside the predicate it is declared in and anything nested within it, not outside it and not to a second `as` name beside it.

## Comparing two collections at once

To match records from two collections of the same subject against each other, list both bindings with a comma and write one shared `where`:

```
shape CustomerWithMatchingIssue = Customer where
    exists (tickets as tix, orders as ord
        where tix is OverdueTicket and ord is ReturnedOrder and ord.product == tix.product)
```

Every binding in the list must be a relationship of the same enclosing subject. Collections of unrelated shapes cannot be joined this way.

## Reading the one related record

Sometimes a predicate needs the related record itself, not just whether it exists: `(NurseVerification for this).nurse`. This form is allowed only when exactly one field on `NurseVerification` matches the subject's type and the compiler can prove at most one such record exists. The proof usually comes from a rule elsewhere in the spec whose condition prevents a second record, as in [§20, "Counting and timing the episodes"](Reference-20-State-change-patterns#counting-and-timing-the-episodes), or from the relationship being to-one from the other side. The subject may be a refinement, as in `(OpenDelinquencyFlag for this)`, since "at most one" is usually true of a state rather than a shape. The full set of cases, with examples and diagnostics, is [`singular_references.md`](singular_references).

When at most one cannot be proved, or two fields match, pick the record with `latest` or `first` over a filtered collection:

```
latest(Shape for expr by receivedOn)
first(Shape for expr by receivedOn)
latest(Referral where referrer == this by referredOn).referee
```

**`by` is required.** `latest` and `first` always name the property to order by, and further comma-separated properties break ties: `latest(fills by filledOn, fillNumber)`. There is no default order. To order by creation, name the shape's declared creation timestamp ([§5](Reference-05-Scalars)); `id` carries no order. If the named properties do not single out a winner, the selection fails rather than choosing silently. Two records committed in one transaction carry identical commit timestamps, so telling them apart needs a property the business supplies, such as a sequence number.

## Dates and durations

A number or a quoted text compared with a field of an [extension type](Reference-05-Scalars#extension-types) reads as that type: in `balance < 0` with `balance: Money`, the `0` is a `Money`, read once when the spec compiles. The type's author decides which constants it reads and what arithmetic it takes part in; `balance * 2` and `balance + 10` are legal when the author declared `Money * decimal` and `Money + Money`.

`today - 7 days` and `now + 14 days` add or subtract a duration from a `Date` or `DateTime`. A duration is an integer followed by `seconds`, `minutes`, `hours`, `days`, or `weeks`, or a field of type `duration` ([§5](Reference-05-Scalars)): `received + sla`. On a `Date` the step is a calendar step: `+ 1 days` is the next calendar date. On a `DateTime` it is exact clock time: `3 days` is seventy-two hours. Two durations add and subtract, `finished - started` on two `DateTime` values is the duration between them, and `done - planned` on two `Date` values is a whole number of days.

## What the compiler checks

- The compiler rejects a comparison between values of different types, a text operator on a non-text value (except `contains` on a `many` of values, [§5](Reference-05-Scalars)), duration arithmetic on anything but a `Date`, `DateTime`, or `duration`, a `by` property that is not orderable, and `x is R` when `x` and `R` share no base shape ([checks.md, F2](checks#f2--type-checking)).
- The compiler rejects a constant an extension type's reader refuses or does not read, and an operator, comparison, or `sum` the type does not declare, naming the Kotlin class to extend ([checks.md, E3](checks#e3--a-capability-the-type-does-not-declare)).
- The compiler rejects plain `.` through an optional value with no earlier presence test, and names `?.` as the alternative ([checks.md, V11](checks#v11--narrowing-analysis)).
- The compiler rejects `(Shape for expr)` and `exists Shape for expr` when more than one field matches the subject's type, or when it cannot prove at most one record exists. The diagnostic asks for `latest` or `first` over a filtered collection ([checks.md, V12](checks#v12--singular-reference-proofs)).
- When a change to a shape, such as a second `Customer` field, makes an existing `for` ambiguous, the compiler reports one diagnostic naming the declaration and every expression it broke ([checks.md, V13](checks#v13--ambiguity-is-a-connected-diagnostic)).

## Grammar

```
predicate      := disjunction
disjunction    := conjunction ("or" conjunction)*
conjunction    := negation ("and" negation)*
negation       := "not"? atom
atom           := comparison | isExpr | existsExpr | booleanAtom | "(" predicate ")"

comparison     := expr ("==" | "!=" | "<" | "<=" | ">" | ">=") expr
               | expr ("contains" | "starts" "with" | "ends" "with" | "like") expr
                                                           -- text operands only, exact and case-sensitive:
                                                           -- the right operand occurs within / begins / ends the left;
                                                           -- `like` takes a pattern where `%` matches any run of
                                                           -- characters (empty included), `_` any one character,
                                                           -- and `\` escapes either
isExpr         := expr "is" ("none" | "some" | "empty" | "not empty" | ShapeName | "not" ShapeName)
existsExpr     := "exists" ShapeName "for" expr           -- sugar; legal only when exactly one field matches expr's type
               | "exists" "(" collectionExpr ")"          -- general form; required when more than one field
                                                           -- matches, or more than one condition is needed
booleanAtom    := path                                     -- legal when the path is boolean-typed:
                                                           -- "applied" ≡ applied == true

expr           := path (("+" | "-") duration)?
path           := pathRoot (accessor Identifier)*
               | aggregateCall
               | selectorCall
               | "(" ShapeName "for" expr ")"   -- sugar; legal only when provably at-most-one AND exactly one
                                                 -- field matches expr's type; else use selectorCall with a
                                                 -- "where"-filtered collectionExpr instead
pathRoot       := "this" | Identifier
accessor       := "." | "?."   -- "." requires the left side provably non-absent (unoptional, or narrowed by a
                                -- prior "is some"/"is none" in the same conjunction); "?." short-circuits to
                                -- none instead, no narrowing required

aggregateCall  := "count" "(" collectionExpr ")"
               | "sum" "(" collectionExpr "," Identifier ")"
selectorCall   := ("latest" | "first") "(" collectionExpr
                      "by" Identifier ("," Identifier)* ")"      -- ordering is explicit and mandatory: the author's
                                                                 -- named orderable datums; an undiscriminated winning
                                                                 -- position fails loudly ("by" is contextual, an
                                                                 -- ordinary identifier elsewhere)
collectionExpr := binding ("," binding)* ("where" predicate)?
binding        := path ("as" Identifier)?

duration       := IntegerLiteral ("seconds"|"minutes"|"hours"|"days"|"weeks")
```

The grammar for the whole language is [`grammar.md`](grammar). It includes this predicate grammar and widens `expr` to full value expressions.

## Related pages

- [§8 Refinements](Reference-08-Refinements) and [§9 Composing refinements](Reference-09-Composing-refinements), where predicates define named subsets of a shape.
- [§14 `for`](Reference-14-for), the same keyword used when creating a record.
- [§21 `never`](Reference-21-never), a predicate naming a forbidden configuration.
