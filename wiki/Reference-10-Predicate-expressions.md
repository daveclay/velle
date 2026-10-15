# 10. Predicate expressions

The expression language usable inside `where` — refinement definitions, inline collection filters, and `never` declarations.

**Comparisons:** `==`, `!=`, `<`, `<=`, `>`, `>=`. `==` is the only spelling for value equality — `=` is reserved for shape definition (`shape X = Y where ...`). On shape instances, `==` compares identity — `a == b` is `a.id == b.id` (`## Scalars`) — never field-by-field structure.

**`is`** — one operator, three sanctioned right-hand forms:
- optionality: `assignee is none`, `response is some` — valid on an optional (`?`) field or to-one relationship
- collection emptiness: `corrections is not empty` — valid on a `many` relationship or collection expression
- refinement membership: `alert is UnacknowledgedAlert`, `this is FlaggedCustomer` (in the predicate of a `requires` declaration, and only there, also spelled `pharmacist to be LicensedPharmacist` — `## never`), and its negation `invoice is not IssuedInvoice` — the spelling of non-membership, the same predicate as the general negation `not (invoice is IssuedInvoice)`, and the one the language's own diagnostics and examples use — valid whenever the left side's shape shares a base with the named refinement, or the named set is an operand of the left side's static union (`target is Post` on a `target: one Content` — a base shape is a member set like any other); a test with no base in common is a compile error, never trivially false. Within a conjunction it also *narrows*, licensing access to the named set's own properties (`## Refinements`; an operand's own fields, `## Composing refinements`), the same way `is some` licenses `.` on an optional — and in a conditional the else-branch of a two-operand union narrows to the other operand, since an instance has exactly one base

**`exists`** — `exists Receipt for this`, `not exists AccountFlagResolved for this` — tests whether any instance of a shape references the given subject via `for`.

**Bare boolean atoms** — a boolean-typed path is a complete predicate atom on its own: `where applied` and `where not applied` are grammatical, equivalent to `applied == true` / `applied == false` — so a flag refinement reads as the business phrase (`Deposit where not applied`).

**`for` field ambiguity.** `for <expr>` matches by type: whichever field on the shape has a type matching `<expr>`'s type is the one compared against (or populated, in an effect clause). This breaks the moment a shape has two fields of the same type — e.g. `Referral { referrer: one Customer, referee: one Customer }` makes `exists Referral for this` ambiguous, and there's no reading of "this customer" that says whether it means referrer or referee. `for <expr>` is sugar for the common, unambiguous case; when it doesn't apply — two fields match, or a second condition on the same matched instance is needed — fall back to the general `where`-filtered form `exists` already shares with `count`/`sum`:

```
exists (Referral where referee == this)
exists (Referral where referee == this and referrer is VipCustomer)
```

Reads as an actual sentence and needs nothing beyond `==` and `where`. `for <expr>` never grows a second syntax to handle what it can't — it just stops applying, and the general form was already there to fall back to.

**Aggregates** take a collection expression as their first argument — a relationship filtered by `where` (`invoices where OverdueInvoice`) is already a complete collection expression on its own, not special call syntax:

```
count(<collection-expr>)
sum(<collection-expr>, <field>)
```

**Relationship traversal** is ordinary dot access (`response.outcome`, `ward.admissions`), including through a relationship back to the same shape (self-reference — no special syntax needed).

**Dot access through an optional** requires the left side to be provably non-absent — either its type isn't optional, or it's been narrowed by an `is some`/`is none` check earlier in the same conjunction (`and`) or the corresponding branch of a value-expression conditional. Plain, unnarrowed `.` on an optional is a compile error. `?.` — reusing the existing `?` optionality marker in a new position, not a new symbol — is the explicit escape valve: it short-circuits the rest of the chain to `none` if that link is absent, with no narrowing required:

```
shape Foo {
    parent: one Foo?
    root: one Foo? = if parent?.root is none then parent else parent?.root
}
```

Both forms stay legal — narrowed `.` when a predicate already needs to branch on presence anyway, `?.` when it doesn't and propagation is all that's wanted.

**`as` — naming an intermediate binding.** `this` is the built-in, always-present alias for the outermost subject a refinement is defined over; a bare unqualified field name means the innermost collection-filter's element. Plain traversal through any number of hops needs neither — `exists (invoices where OverdueInvoice and count(payments where FailedPayment) >= 1)` needs no binding at all, since each hop is evaluated relative to whatever scope is current at that point in the text. `as` only earns its keep when a *deeper* nested scope needs to reach back to a *middle* level's own field — `this` skips past the middle straight to the root, and bare names in the deeper scope mean the deeper level, not the middle one:

```
shape CustomerWithOverpayment =
  Customer where exists (
    invoices as inv where
      count(inv.payments where amount > inv.amount) >= 1
  )
```

`inv.amount`, needed from inside `payments`' own `where`, has nowhere else to come from — `inv` is what keeps the middle level reachable once a deeper scope opens. An alias is visible within the predicate it's declared over and anything nested inside that scope, the same as a SQL correlated subquery — it doesn't leak to the enclosing scope or across to a sibling `as` binding.

**Sibling joins** — correlating two independently-filtered collections against each other (not a straight chain) — take a comma-separated list of bindings instead of one, all visible to each other and to a single shared `where`:

```
shape CustomerWithMatchingIssue = Customer where
    exists (tickets as tix, orders as ord
        where tix is OverdueTicket and ord is ReturnedOrder and ord.product == tix.product)
```

Every binding in the list must be reachable as a relationship from the same enclosing subject (`this`, or the enclosing `as` alias) — a correlated join relative to a shared parent, not an arbitrary cross-product of any two shapes in the system. A single binding (`invoices where OverdueInvoice`, `invoices as inv where ...`) is just the one-binding case of this same rule.

**`for` as a query expression** (e.g. `(NurseVerification for this).nurse`) is legal only when the compiler can prove at most one matching instance exists *and* exactly one field matches by type — because a guard refinement elsewhere in the spec proves at most one can exist (the whole-spec singularity proof — `## Run-once guards` and "Episodes as data" in `## State-change patterns`), or the relationship is to-one from the other side. The subject may be a refinement (`(OpenDelinquencyFlag for this)`) — at-most-one is usually a property of a *state*, not a shape; the full legality catalog, worked examples and diagnostics included, is `singular_references.md`. Otherwise, disambiguate with `latest`/`first` over a `where`-filtered collection instead — the same fallback as the field-ambiguity case above, not a second mechanism:

```
latest(Shape for expr by receivedOn)
first(Shape for expr by receivedOn)
latest(Referral where referrer == this by referredOn).referee
```

**Selector ordering is the author's statement — explicit and mandatory.** `latest`/`first` carry a `by` clause naming one or more orderable properties of the element (`latest(payments by receivedOn)`; further comma-separated datums break ties). There is no implicit ordering source: "creation order" is spelled by naming the shape's declared creation timestamp (`by correctedOn`), and a shape with no orderable datum simply cannot be selected over until the author declares one — ordering comes from data, never from minted values (`## Scalars`: `id` is unordered). A selection whose declared datums fail to discriminate the winning position is the model underspecified, and fails loudly rather than silently picking. The sharpest instance is structural: two instances committed in one transaction carry *identical* commit timestamps by definition — a commit is one discrete moment — so discriminating same-transaction siblings always requires an author-carried datum (an act-supplied sequence, a business time), which is the tick law's cousin: ordering, like memory, must be data.

**Duration arithmetic**: `today - 7 days`, `now + 14 days` — an integer literal plus a unit (`seconds`/`minutes`/`hours`/`days`/`weeks`), added to or subtracted from a `Date`/`DateTime`. Semantically, `3 days` is the duration constructor `days(3)` — a function returning a duration value — and `+`/`-` are the duration-accepting operations on `Date`/`DateTime`: sugar the compiler lays over what `java.time` already provides, not a bespoke temporal engine. The step follows the receiver: on a `Date` it is a calendar step (`java.time.Period` semantics — `+ 1 days` is the next calendar date); on a `DateTime` it is exact clock time (`java.time.Duration` — `days(3)` is 72 hours).

Grammar, informally:

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

The **whole-language grammar** — declarations, statements, and value expressions — is `grammar.md`, which incorporates this section's predicate grammar by reference and generalizes its `expr` to full value expressions.
