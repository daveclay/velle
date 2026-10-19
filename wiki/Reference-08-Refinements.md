# 8. Refinements (`where`)

A refinement is a named subset of a shape: every record that satisfies a condition. "Overdue invoices" and "issued invoices" are refinements. A refinement is not a status field and not a branch in code. It is a name for a condition, and a record is a member whenever the condition is true of it. Rules fire when records enter or leave refinements, queries filter by them, and the data and permissions that belong to one state of a record are declared on that state's refinement. After reading this page you can define a refinement, give it properties, lock fields while a record is in it, forbid deleting it, and say who may see a record.

Code blocks from an example file name it. The rest are illustrations.

## Defining a refinement

A product owner says "an invoice is overdue when it has a balance and its due date has passed."

```
shape OverdueInvoice = Invoice where balance > 0 and due < today
```

From `examples/billing/billing.velle`. `OverdueInvoice` is now a name you can use anywhere a shape name goes. An invoice is a member while the condition holds and stops being one when it no longer holds. Nothing is stored and nothing fires. Only a [rule](Reference-11-rule-when-on) reacts to a record entering or leaving it.

The condition is a [predicate expression](Reference-10-Predicate-expressions). It may read the record's own fields, as above, or reach other records through its [relationships](Reference-06-Relationships) and through `exists`.

The same `where` filters a collection inline: `count(invoices where OverdueInvoice)` counts a customer's overdue invoices. See [§10](Reference-10-Predicate-expressions).

## Properties of a refinement

Some data only makes sense for a record in a particular state. Only an archived invoice has an archive date. Rather than add an optional field to `Invoice` that is empty for every unarchived invoice, declare the property on the refinement. The base shape says what every invoice has, and each refinement says what membership adds.

```
shape ArchivedInvoice = Invoice where exists ArchiveRequest for this {
    captured archivedBy: one User = (ArchiveRequest for this).requestedBy
    captured archivedOn: Date = today
}
```

A refinement body declares properties of the same two kinds a shape body does:

- **Derived**, written `name: Type = expr`. It is recalculated from current data every time it is read, exactly like a [derived property](Reference-07-Derived-properties) on a shape. It can be read only on a member.
- **Captured**, written `captured name: Type = expr`. It is calculated once, at the commit where the record enters the refinement, and keeps that value for as long as the record stays a member. It does not exist before entry. When the record leaves, the value is dropped at the end of the exit commit, so a rule that fires on leaving may still read it ([§13](Reference-13-Exit-triggers)). If the record enters again later, the value is calculated again.

Capturing `today` or `now` records the moment of entry. `archivedOn` above is the date the invoice was archived.

`captured` is the refinement's counterpart of `initially` on a shape field ([§5](Reference-05-Scalars)). Both calculate their expression once, when their scope begins. A record lasts until deleted, so an `initially` value is stored and may be assigned later. A membership can end, so a captured value is fixed while it lasts and dropped when it ends. `captured` is allowed only in a refinement body.

A captured value can only come from data. There is no "current user" or request context to read. The person who archived the invoice reaches `archivedBy` because `ArchiveRequest` is an act whose `by` clause records who committed it, and the capture reads that from the request ([§22, "The actor"](Reference-22-Open-unresolved)). The form `(ArchiveRequest for this)` picks out one record, so the compiler must be able to prove at most one archive request per invoice exists ([checks.md, V12](checks#v12--singular-reference-proofs)). If an invoice can be archived more than once, select the request with `latest(... by ...)` instead ([§10](Reference-10-Predicate-expressions)).

**Reading a refinement property.** From the base shape, a refinement's properties are not visible. `invoice.archivedBy` does not compile unless the expression has already established `invoice is ArchivedInvoice`, earlier in the same `and` chain or in the matching branch of an `if`. This works the same way `is some` lets you read through an optional field ([§10](Reference-10-Predicate-expressions)). A property that reads the properties of two refinements is declared on their intersection, where both are known to hold ([§9](Reference-09-Composing-refinements)):

```
shape Reconciled = Quoted and Delivered {
    priceDrift: Money = billedTotal - quotedTotal
}
```

Properties never affect whether a record is a member. They only change what a member has. Captured values are memory for the current membership only. If the business needs to know who archived an invoice last March, before it was unarchived, that is history. Model it as a record created per occurrence and read with `latest(... by ...)` ([§12](Reference-12-Assignment)).

## Who may see a record (`visible to`)

A shape body may say which readers its records are returned to. "Staff assigned to a vendor can see its products. Administrators see every product, and only they see the cost." From `examples/vendors/vendors.velle`:

```
shape Product {
    vendor: one Vendor
    name: text
    price: decimal
    cost: decimal visible to Administrator
    margin: decimal = price - cost visible to Administrator
    visible to Staff where exists (VendorAssignment where staff == actor and vendor == this.vendor)
    visible to Administrator
}
```

- A `visible to` line in the body names a shape of reader, with an optional `where` condition. The condition sees the reader as `actor`, or under a name chosen with `as`, as an act's `by` clause names its committer ([§22, "The actor"](Reference-22-Open-unresolved)).
- Several lines in one body add up: a reader who satisfies any of them sees the record.
- `visible to anyone` makes a shape public.
- The same clause after a field's type narrows that field within the record. A derived property takes one too, since it is calculated from all its inputs whoever asks.
- The clause goes on a base shape. A refinement is seen by whoever sees its base.

Visibility is applied only when data is read. A query returns the records the clauses admit for that reader, a fetch of a hidden record returns nothing, and a returned record has its hidden fields absent. No rule, derived property, or `never` ever depends on who is reading.

Once a spec has any `by` clause or any `visible to`, every shape that can be read must declare its visibility. Write it in each shape's body, or write a single `visible to Staff` at the top of the spec as the default. A shape's own clauses replace the default for that shape rather than adding to it. The compiler rejects a spec that has authorization but leaves a readable shape without a declaration ([checks.md, V32](checks#v32--every-readable-shape-declares-its-visibility)). The [Authorization](Authorization) guide walks through the whole feature.

## Frozen fields (`frozen`)

"Once an invoice is issued, its line items and billing address cannot change." The field is editable on a draft and locked on an issued invoice, so the lock belongs to the state, not to the field. Declare it on the refinement for that state:

```
shape IssuedInvoice = Invoice where exists Issuance for this {
    frozen lineItems, billingAddress
}
```

- `frozen` followed by field names locks those stored fields of the base shape while the record is a member.
- `frozen` alone locks every stored field, including fields added to the shape later.
- Only stored fields can be listed. Derived properties are never assigned, and captured values are fixed already.
- A write to a frozen field is illegal at any commit where the record was a member before the commit. The commit that makes it a member may still write, so a rule reacting to the `Issuance` can tidy a field in the same commit.
- When the record leaves the refinement, the fields unlock.
- Under [composition](Reference-09-Composing-refinements), each refinement's freeze applies wherever that refinement holds.

**The compiler checks every freeze against every writer.** Every write in Velle is an [assignment](Reference-12-Assignment) in a rule, so the compiler knows every rule that writes each field. It rejects the spec when a rule that writes a frozen field could fire while the record is a member, naming the rule and the freeze ([checks.md, V5](checks#v5--freeze-disjointness)). If it cannot prove they never coincide, it rejects.

The fix is to make the edit a request sorted before it is applied. Declare it as a `transient act` ([§4](Reference-04-State-and-commits)) and split it into the edits that may apply and the edits that are refused. From `examples/billing/billing.velle`:

```
shape ApplicableDueChange = ChangeDueDate where invoice is not IssuedInvoice
shape RefusedDueChange    = ChangeDueDate where invoice is IssuedInvoice
```

The writing rule triggers on `ApplicableDueChange`, which the compiler can prove never coincides with the freeze. A second rule triggers on `RefusedDueChange` and records the refusal, so the caller reads it back as data. No commit is ever rolled back. A transient act is sorted once, at its own commit, so the sort cannot change as the invoice's state moves ([checks.md, A4](checks#a4--drift-exposed-act-partition)). If the request must be kept as a durable record, scope the two refinements to requests not yet handled; see [patterns.md, "Validation rejection is data"](patterns#validation-rejection-is-data).

The compiler warns when a freeze could never be violated, because no rule writes the listed fields while the record could be a member ([checks.md, A2](checks#a2--dead-machinery)).

**A freeze reaches only the fields it names.** "Can't edit the line items" usually means the `LineItem` records too. Say so on the related shape, with the issued state as the condition:

```
shape LockedLineItem = LineItem where invoice is IssuedInvoice {
    frozen price, quantity
}
```

Each step of the lock is its own declaration, so the spec says plainly that issuing locks a line item's price and not the product's name.

**A lock that is lifted by a later event** is a freeze on a refinement whose condition includes both events:

```
shape ReceiptedInvoice = Invoice where
    exists Receipt for this and not exists ReceiptVoid for this {
    frozen lineItems
}
```

The invoice is locked while a receipt stands and unlocks when a void lands.

## Deletion permission (`undeletable`)

A refinement body may also declare `undeletable`. While the record is a member, no rule may delete it ([§12, "Deletion"](Reference-12-Assignment#deletion-delete)). From `examples/moderation/moderation.velle`:

```
shape PublishedListing = Listing where not isDraft {
    undeletable
}
```

"Cannot be edited" and "cannot be removed" are separate business sentences, so `frozen` and `undeletable` are separate declarations. Neither implies the other. A record may be deleted by default, in any state. Only the prohibition is written.

The compiler rejects the spec when a rule that deletes the base shape could fire while the record is a member, by the same proof as a freeze ([checks.md, V27](checks#v27--the-deletion-gate)). The fix is the same: sort the deletion request into the deletable and refused cases and trigger the deleting rule on the deletable one. The compiler warns when no rule could ever delete a member ([checks.md, A2](checks#a2--dead-machinery)).

## What the compiler checks

- **It rejects** two refinements that overlap where the spec needs them disjoint, and a set that fails to cover a shape where the spec needs it covered ([checks.md, V9](checks#v9--refinement-overlap-and-exhaustiveness)). The one-writer, freeze, and deletion checks rely on this proof.
- **It rejects** a captured expression that reads something the refinement's condition does not guarantee ([checks.md, V6](checks#v6--capture-entry-evaluability)). `(ArchiveRequest for this)` is allowed above only because the condition says `exists ArchiveRequest for this`. A capture that reads only the base shape's own fields, such as `captured balanceWhenOverdue: Money = balance` on `OverdueInvoice`, needs no such guarantee.
- **It rejects** reading a refinement property on a record not yet narrowed to that refinement ([checks.md, V11](checks#v11--narrowing-analysis)).

## Related pages

- [§9 Composing refinements](Reference-09-Composing-refinements), for `and`, `or`, and `not` between refinements.
- [§10 Predicate expressions](Reference-10-Predicate-expressions), for what may appear after `where`.
- [§11 rule](Reference-11-rule-when-on) and [§13 Exit triggers](Reference-13-Exit-triggers), for reacting to entry and exit.
- [Authorization](Authorization), for `by` and `visible to` together.
