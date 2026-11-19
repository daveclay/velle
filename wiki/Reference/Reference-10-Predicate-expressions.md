# 10. Predicate expressions

A predicate is a yes-or-no question asked of one record: is this invoice overdue, does this ticket have an assignee, has a receipt been sent for this payment. Predicates are how a spec says which records a rule applies to and which configurations are forbidden. This page teaches the expressions you can write inside one. After reading it you can write the `where` clause of any [refinement](Reference-08-Refinements), any inline filter over a collection, and any [`never`](Reference-21-never) declaration.

## Comparing values

The comparison operators are `==`, `!=`, `<`, `<=`, `>`, and `>=`.

- `==` is the only spelling of equality. `=` is for defining a shape (`shape X = Y where ...`) and for [assignment](Reference-12-Assignment).
- Comparing two records with `==` asks whether they are the same record, by their `id` ([§5](Reference-05-Scalars)). It never compares them field by field.
- A collection is compared with nothing single. `inheritedBy == this` is refused, since a collection of records is not a record; whether the record is one of them is `this in inheritedBy`, below. A collection has no order, so `<` and the rest are refused the same way.

`qty between 2 and 5` is `qty >= 2 and qty <= 5`, both bounds included. It works on any ordered value: `due between today and today + 7 days`.

`status in ["open", "pending"]` asks whether the value equals one of the listed values, and `status not in ["open", "pending"]` whether it equals none. The values are written between square brackets, separated by commas, and are of the left side's type. The right side may also be a `many` of values ([§5](Reference-05-Scalars)): `qty in scores`. When the left side is a record, the right side may be a collection of records, such as a walk's set ([§7](Reference-07-Derived-properties)): `this in lineage` asks whether the record is one of them. An absent value is in nothing, and nothing is in an absent collection: `value in first(Field where name == this.fieldName by name).options` is false when no field has the name.

Text values also support `contains`, `starts with`, `ends with`, `like`, and `matches`, all case-sensitive. In a `like` pattern, `%` matches any run of characters, including none, `_` matches one character, and `\` escapes either. `matches` takes a regular expression, in the Java dialect, that the whole text must match: `email matches "[^@ ]+@[^@ ]+\\.[a-z]+"`. A backslash in a text literal is written twice, so the regular expression `\.` is spelled `"\\."` in a spec. The compiler reads the expression when the spec compiles, so a malformed one is an error there, never at run time.

A pattern may hold pieces, `${...}` around a value ([§5](Reference-05-Scalars), "Text made from other values"), and a `Regex` value ([§5](Reference-05-Scalars)) may stand where a pattern does. From `examples/orders/orders.velle`, shape `ProductOfFamily`:

```
act SkuFamily {
    prefix: text
    skuPattern: Regex
}

shape ProductInFamily = Product where exists (SkuFamily where this.sku matches skuPattern)
shape ProductOfFamily = Product where exists (SkuFamily where this.sku matches "^${prefix}-${skuPattern}$")
```

- `this.sku matches skuPattern` tests the SKU against the family's stored pattern. A `Regex` field holds only a pattern that compiles, so the right side is never malformed.
- In `"^${prefix}-${skuPattern}$"`, the piece's type says how it is read. `${prefix}` is a text, so it is characters: a prefix `A.B` matches `A.B` and not `AxB`. `${skuPattern}` is a `Regex`, so it is spliced in as pattern. The fixed runs, `^`, `-`, `$`, are pattern. The same reading holds for the literal as the argument of `regex(...)`, `regex("^${prefix}-\\d+$")`, and as the value of a `Regex` property.
- To splice a text as pattern, read it as one at the piece: `"^${prefix}-${regex(suffix)}$"`. `regex(text)` is absent when the text does not compile, and an absent piece makes the pattern absent, so the comparison is false.
- A pattern built in a `text` property and read back with `regex(...)` reads the whole text as pattern, pieces included. Build a pattern in a `Regex` property instead; the compiler warns at the `regex(...)` call ([checks.md, A7](checks#a7--a-pattern-read-from-a-text-built-from-pieces)).
- In a `like` pattern a piece is always characters: `body like "%[${tag}]%"` with a tag of `50%` matches `[50%]` and nothing else.
- The compiler checks the fixed runs of a pattern with pieces when the spec compiles. A pattern that still does not compile when its pieces are in, a `Regex` piece that closes a group a run opened, fails the evaluation rather than reading as false.

## Testing presence, emptiness, and membership with `is`

`is` has three uses.

**Presence of an optional value.** `assignee is none` and `response is some` test whether an optional field or an optional to-one relationship holds a value.

**Emptiness of a collection.** `corrections is empty` and `corrections is not empty` test a `many` relationship or any collection expression.

**Emptiness of a text.** `note is empty` holds when a text field is absent or holds nothing but spaces, tabs, and line breaks, and `note is not empty` when it holds anything else. A product owner means the same thing by a missing note and a blank one, and this test treats them alike.

**Membership of a refinement.** `alert is UnacknowledgedAlert` asks whether the record is currently in that refinement. `invoice is not IssuedInvoice` is the negation, the same as `not (invoice is IssuedInvoice)` and the spelling Velle's diagnostics use. Inside a `requires` declaration, and only there, membership may also be written `pharmacist to be LicensedPharmacist` ([§21](Reference-21-never)).

A membership test is legal when the left side and the named refinement share a base shape, or when the named shape is an operand of the left side's static union ([§6](Reference-06-Relationships)): `target is Post` on a field `target: one Content`. A test between shapes with no base in common is a compile error, not a test that is always false.

**A membership test also narrows.** Once `x is Refinement` holds earlier in the same `and` chain, the rest of the chain may read the refinement's own properties through `x` ([§8](Reference-08-Refinements); for a union operand's own fields, [§9](Reference-09-Composing-refinements)). In an `if` expression, the `else` branch of `x is A` over a union narrows to the operands the test did not name, since a record has exactly one base: the other operand of two, or the union of the rest, which a later test narrows further. `x is not A` narrows the branch it governs the same way, and its `else` narrows to `A`. A filter that names a refinement, `(claims where PaidClaim)`, holds members of `PaidClaim`, so a sum or a selector over it may read a property of `PaidClaim`'s body.

## Testing for related records with `exists`

`exists Receipt for this` asks whether any `Receipt` points at the current record, and `not exists AccountFlagResolved for this` that none does. `for` picks the field on `Receipt` whose type matches the subject.

The shape after `for` may be one that several shapes take with `is`, or a static union ([§9](Reference-09-Composing-refinements)). `for` then picks the field every one of those shapes takes from one declaration, and the test covers a record of any of them: with `shape AttemptOutcome { attempt: one ChargeAttempt }` taken by `ChargeResponse` and `AttemptTimeout`, `not exists AttemptOutcome for this` on a charge attempt holds until a response or a timeout names it, as `PendingAttempt` in `examples/payments/payments.velle` says. A field two of the shapes each declare for themselves is not shared, so `for` does not find it; declare it once on the shape they take.

That shorthand stops working when the shape has two fields of the matching type. Given `Referral { referrer: one Customer, referee: one Customer }`, `exists Referral for this` cannot say which field is meant. Write the general form, which filters the shape with `where`:

```
exists (Referral where referee == this)
exists (Referral where referee == this and referrer is VipCustomer)
```

The general form is also how to add a second condition on the related record, as the second line shows.

## Boolean fields as conditions

A boolean field is a complete condition on its own. `where applied` means `applied == true`, and `where not applied` means `applied == false`, so a flag refinement reads as the business phrase: `Deposit where not applied`.

The other way round, a condition is a value of type `boolean`: a boolean property is written as its condition, `onTime: boolean = paidOn <= dueBy` ([§7, "A boolean property is its condition"](Reference-07-Derived-properties#a-boolean-property-is-its-condition)).

## Counting and summing

```
count(<collection-expr>)
sum(<collection-expr>, <field>)
sum(<many of values>)
```

The first argument is any collection expression. A relationship filtered with `where`, such as `invoices where OverdueInvoice`, is one, so `count(invoices where OverdueInvoice)` needs no further syntax. A `many` of numbers or durations ([§5](Reference-05-Scalars)) is summed directly, with no field, as `sum(scores)`; `max(scores)` and `min(scores)` read its greatest and least value, and are absent when it is empty.

## Reaching through relationships

A relationship is followed with a dot: `response.outcome`, `ward.admissions`, including a relationship back to the same shape.

A dot through an optional value is allowed only when the value is known to be present: its type is not optional, or an `is some` or `is none` test earlier in the same `and` chain, in the matching branch of an `if`, or on the left side of an `or` whose right side reads it, has established it. Plain `.` on an optional value with no such test is a compile error. A `for each` name carries what the filter of its collection shows about each element: inside `for each r in (rows where site is some)`, `r.site.name` reads with a plain dot ([§14](Reference-14-for#creating-one-record-per-element-for-each)).

A test on a path establishes every step of the path. Once `site?.region is some` holds, `site` holds a record and that record's `region` holds one, so the branch it governs reads `site.region.name` with plain dots: `if site?.region is some then site.region.name else "unplaced"`. The same holds in the `else` branch of `site?.region is none`. The path under test is spelled with `?.`, because it is read before it is known to be present. A comparison that holds establishes the paths it compares in the same way: `attr?.kind == "relation" and x not in attr.relations` reads `attr.relations` with a plain dot, because `attr?.kind` is absent when `attr` is, and an absent value equals no present value, is in nothing, and satisfies no ordered comparison. An equality establishes a side only when the other side cannot be absent, a literal or a required field, since two absent values compare equal; `!=` and `not in` hold on an absent value and establish nothing, and neither does the `else` branch of an `if` over `==`. The right side of an `or` is read only where its left side failed, so it reads what the failure establishes: `attr is none or not attr.hasUnits` reads `attr.hasUnits` with a plain dot, and over the union of [§9](Reference-09-Composing-refinements), `target is Post or target.caption != ""` reads `target` as the other operand. A prohibition the rules cannot move establishes a step as well: `never (Submission where parentLabel is some and parent is none)` shows `parent` present wherever `parentLabel is some` is established ([§21, "What the compiler checks"](Reference-21-never#what-the-compiler-checks)).

A function whose result may be absent is read through with a plain dot and needs no test. When the result is absent, the rest of the path is absent.

### A selection that may match nothing

A selection, `first(...)`, `latest(...)`, or `(Shape for expr)`, picks one record, and picks none when nothing matches. A field is read off a selection with a plain dot and needs no test: `first(Field where name == this.fieldName by name).options` is absent when no field has the name. `?.` directly after a selection says what the plain dot already says, and the compiler warns and names the dot to write.

A test that the collection is not empty shows that a selection from that collection matched:

```
act FieldPlot {
    trial: one FieldTrial
    block: integer
    treatment: Arm
    yieldKgPerHa: decimal = if harvests is empty then 0.0 else latest(harvests by harvestedOn).yieldKgPerHa
}

shape CompletedAttempt = ChargeAttempt where exists ChargeResponse for this {
    outcome: Verdict = latest(ChargeResponse for this by respondedOn).outcome
}
```

`FieldPlot` is from `examples/peptides/peptides.velle` and `CompletedAttempt` from `examples/payments/payments.velle`.

- `harvests is empty` governs the `then` branch, so the `else` branch runs only for a plot with a harvest. `latest(harvests by harvestedOn)` is a record there, and `yieldKgPerHa` may be declared without `?`.
- `harvests is not empty` and `exists (harvests)` show the same thing in the branch they govern.
- `exists ChargeResponse for this` in the refinement's `where` shows that `latest(ChargeResponse for this by respondedOn)` matched for every `CompletedAttempt`. The condition of a [rule](Reference-11-rule-when-on) shows it for the rule's body in the same way.
- The test and the selection spell the collection the same way. `exists ChargeResponse for this` shows nothing about `latest(ChargeResponse where attempt == this by respondedOn)`, and a test of `harvests` shows nothing about a selection from `harvests where yieldKgPerHa > 0`.
- An `is some` test on the selection itself, `first(harvests by harvestedOn) is some`, shows that selection matched.
- `max` and `min` over a `many` of values are absent when it is empty. The same tests show them present: `if scores is empty then 0 else max(scores)`.
- A required stored field started by `initially` takes a selection under the same tests: `opened: Date initially if exists (patient.visits) then first(patient.visits by seenOn).seenOn else today` ([§5, "Starting values"](Reference-05-Scalars#starting-values-initially)).

The steps after a selection follow the rule for every path. A field declared with `?`, read off the selected record, may be absent even when the selection matched. A plain dot through a step declared with `?` is a compile error, so a row's optional `parent` is read as `first(rows by key).parent?.name`. Two tests show such a read present. One is `is some` on the same read: `if first(rows by key).newKey is some then first(rows by key).newKey else "none"`. The other is a filter on the collection selected from: `first(rows where newKey is some by key).newKey`.

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

Over a `many` of values ([§5](Reference-05-Scalars)) the `as` name is the value itself, and naming it is the one way to filter such a collection, since a value has no fields a bare name could mean. A reading's value must be one of its field's options:

```
never (Submission where exists (readings as r where
       r.field is some and r.value is some
       and not exists (r.field.options as option where option == r.value)))
```

`option` is each text in `options` in turn. The same test reads more directly as `r.value not in r.field.options`; the named form is for a filter `in` cannot say, such as `count(options as option where option starts with "b")`.

## Comparing two collections at once

To match records from two collections of the same subject against each other, list both bindings with a comma and write one shared `where`:

```
shape CustomerWithMatchingIssue = Customer where
    exists (tickets as tix, orders as ord
        where tix is OverdueTicket and ord is ReturnedOrder and ord.product == tix.product)
```

Every binding in the list must be a relationship of the same enclosing subject. Collections of unrelated shapes cannot be joined this way.

## Reading the one related record

Sometimes a predicate needs the related record itself, not just whether it exists: `(NurseVerification for this).nurse`. This form is allowed only when exactly one field on `NurseVerification` matches the subject's type and the compiler can prove at most one such record exists. It is one record or absent: the proof is of at most one, not of one, so when no record matches the read is absent, as a selection is, and the rest of the path with it. The proof usually comes from a rule elsewhere in the spec whose condition prevents a second record, as in [§20, "Counting and timing the episodes"](Reference-20-State-change-patterns#counting-and-timing-the-episodes), or from the relationship being to-one from the other side. The subject may be a refinement, as in `(OpenDelinquencyFlag for this)`, since "at most one" is usually true of a state rather than a shape. The full set of cases, with examples and diagnostics, is [`singular_references.md`](singular_references).

The subject after `for` is a record, never a value. To find a record by one of its values, filter with `where`. When the shape declares the value as a key ([§21, "Keys"](Reference-21-never#keys)), the filter is itself the record: `(Entry where key == this.entryKey)` is one `Entry` or absent wherever one record is expected, with no order to name, because `key: text unique` on `Entry` says no two entries share a key. The filter must name every field of one key with `==`, and each value it compares with must be present: a literal, a required field, or an optional field the enclosing refinement or `if` tests with `is some`, by itself or as a step of a longer path the test names. Two absent values compare equal, so an absent key would match every record without one. Without a key, select: `first(Entry where key == this.entryKey by key)`. The worked spelling, with the `never` that refuses a key no entry has, is [patterns, "An act that names a record by its values"](patterns#an-act-that-names-a-record-by-its-values).

When at most one cannot be proved, or two fields match, pick the record with `latest` or `first` over a filtered collection:

```
latest(Shape for expr by receivedOn)
first(Shape for expr by receivedOn)
latest(Referral where referrer == this by referredOn).referee
```

**`by` is required.** `latest` and `first` always name the property to order by, and further comma-separated properties break ties: `latest(fills by filledOn, fillNumber)`. There is no default order. To order by creation, name the shape's declared creation timestamp ([§5](Reference-05-Scalars)); `id` carries no order. If the named properties do not single out a winner, the selection fails rather than choosing silently: in a rule's firing, the act comes back refused with a reason naming the rule ([§14, "A required field given an absent value"](Reference-14-for#a-required-field-given-an-absent-value)). A selection over an empty collection is absent; a `from` block that would store it in a required field fails the firing ([§14](Reference-14-for)). Two records committed in one transaction carry identical commit timestamps, so telling them apart needs a property the business supplies, such as a sequence number.

## Dates and durations

A number or a quoted text compared with a field of an [extension type](Reference-05-Scalars#extension-types) reads as that type: in `balance < 0` with `balance: Money`, the `0` is a `Money`, read once when the spec compiles. The type's author decides which constants it reads and what arithmetic it takes part in; `balance * 2` and `balance + 10` are legal when the author declared `Money * decimal` and `Money + Money`.

`today - 7 days` and `now + 14 days` add or subtract a duration from a `Date` or `DateTime`. A duration is an integer followed by `seconds`, `minutes`, `hours`, `days`, or `weeks`, or a field of type `duration` ([§5](Reference-05-Scalars)): `received + sla`. On a `Date` the step is a calendar step: `+ 1 days` is the next calendar date. On a `DateTime` it is exact clock time: `3 days` is seventy-two hours. Two durations add and subtract, `finished - started` on two `DateTime` values is the duration between them, and `done - planned` on two `Date` values is a whole number of days. A `time` moves by a duration as a clock does, `closes - 30 minutes`, and `closes - opens` on two times is a duration. `abs(due - today)` is the distance between two dates whichever comes first, and `dayOfWeek(due)`, `month(due)`, `hour(startsAt)` read one part of a date, a moment, or a time as a whole number ([§5](Reference-05-Scalars#the-built-in-functions)): `dayOfWeek(due) >= 6` is a weekend due date.

## What the compiler checks

- The compiler rejects a comparison between values of different types, a text operator on a non-text value (except `contains` on a `many` of values, [§5](Reference-05-Scalars)), duration arithmetic on anything but a `Date`, `DateTime`, `time`, or `duration`, a `by` property that is not orderable, and `x is R` when `x` and `R` share no base shape ([checks.md, F2](checks#f2--type-checking)).
- The compiler rejects a listed value after `in` that is not of the left side's type, a right side of `in` that is neither a list, a `many` of values, nor a collection of records beside a record, a list of values anywhere but after `in`, a `matches` whose right side is neither a quoted text, a text literal with pieces, nor a `Regex`, a quoted pattern that is not a regular expression, and a pattern with pieces whose fixed runs are not one around a placeholder for each piece, `is empty` on a value that is neither a collection nor a text, and a `sum` with no field over anything but a `many` of numbers or durations, or over the values of one that pass a filter ([checks.md, F2](checks#f2--type-checking)).
- The compiler rejects a `where` over a `many` of values that does not name each value with `as`, a `by`, a sum by a field, or a second binding over one, and names the `(values as value where ...)` form ([checks.md, F2](checks#f2--type-checking)).
- The compiler rejects a constant an extension type's reader refuses or does not read, and an operator, comparison, or `sum` the type does not declare, naming the Kotlin class to extend ([checks.md, E3](checks#e3--a-capability-the-type-does-not-declare)).
- The compiler rejects plain `.` after an optional step that no presence test shows present, naming the step, the `is some` test that would show it, and the `?.` spelling that reads absent instead. The steps after a selection are checked the same way ([checks.md, V11](checks#v11--narrowing-analysis)).
- The compiler warns when `?.` is written directly after a selection, and names the plain dot to write ([checks.md, A10](checks#a10--a--directly-after-a-selection)).
- The compiler rejects `(Shape for expr)` and `exists Shape for expr` when more than one field matches the subject's type, or when it cannot prove at most one record exists. The diagnostic asks for `latest` or `first` over a filtered collection ([checks.md, V12](checks#v12--singular-reference-proofs)).
- The compiler rejects `(Shape for expr)` when `expr` is a value, such as a text or a number, rather than a record. The diagnostic names the `first(Shape where field == expr by field)` spelling ([checks.md, F2](checks#f2--type-checking)).
- The compiler rejects a filter `(Shape where ...)` where one record is expected unless it names every field of a key of `Shape` and compares each with a value that is present. The diagnostic names the key field the filter is missing, or the value that may be absent and the `is some` test that makes it present ([checks.md, V36](checks#v36--a-filter-over-a-key-is-one-record)).
- When a change to a shape, such as a second `Customer` field, makes an existing `for` ambiguous, the compiler reports one diagnostic naming the declaration and every expression it broke ([checks.md, V13](checks#v13--ambiguity-is-a-connected-diagnostic)).

## Grammar

```
predicate      := disjunction
disjunction    := conjunction ("or" conjunction)*
conjunction    := negation ("and" negation)*
negation       := "not"? atom
atom           := comparison | isExpr | existsExpr | booleanAtom | "(" predicate ")"

comparison     := expr ("==" | "!=" | "<" | "<=" | ">" | ">=") expr
               | expr "between" expr "and" expr            -- both bounds included: x >= a and x <= b
               | expr "not"? "in" (listLiteral | expr)     -- equal to one of the listed values, or to a member
                                                           -- of a `many` of values
               | expr ("contains" | "starts" "with" | "ends" "with" | "like" | "matches") expr
                                                           -- text operands only, exact and case-sensitive:
                                                           -- the right operand occurs within / begins / ends the left;
                                                           -- `like` takes a pattern where `%` matches any run of
                                                           -- characters (empty included), `_` any one character,
                                                           -- and `\` escapes either; `matches` takes a regular
                                                           -- expression (a text literal) the whole left must match
listLiteral    := "[" (expr ("," expr)*)? "]"
isExpr         := expr "is" ("none" | "some" | "empty" | "not empty" | ShapeName | "not" ShapeName)
                                                           -- `empty` on a text: absent or whitespace only
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
               | "sum" "(" path ")"                        -- a `many` of numbers or durations, summed directly
keyLookup      := "(" ShapeName "where" predicate ")"   -- one record or absent, where one record is expected,
                                                           -- when the predicate names every field of a key of the
                                                           -- shape with "==" (Reference §21, "Keys"; checks V36)
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
