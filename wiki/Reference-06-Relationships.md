# 6. Relationships (`one`, `many`)

A relationship is a link between records: an invoice has a customer, a student takes courses. In Velle you declare a relationship once, on the shape that stores the link, and the other side appears on its own. Declaring `customer: one Customer` on `Invoice` gives every `Customer` an `invoices` collection with no declaration at all. The two keywords say how many records the declaring side points at: `one` points at a single record, `many` holds a set of them. This page shows how to declare each kind, what the other side is called, how an act supplies collections and how a rule changes them, how to name the inferred side yourself, and how an act can create a record together with its parts in one commit.

The code on this page comes from `examples/enrollment/enrollment.velle`, `examples/orders/orders.velle`, `examples/gallery/gallery.velle`, and `examples/batches/batches.velle`.

## Declaring a relationship

```
shape Invoice {
    customer: one Customer
}

shape Payment {
    invoice: one Invoice
}
```

Each line declares one relationship from the shape that stores it:

- `Invoice.customer: one Customer` says each invoice points at one customer. Many invoices may point at the same customer, so this is a one-to-many relationship, written from the many side. It is the only way to write one-to-many.
- `Customer` gets a collection called `invoices` without declaring anything. The name is the declaring shape's name, lowercased and with an `s` added. `Payment.invoice` gives `Invoice` a `payments` collection, so `sum(payments, amount)` works on an invoice.
- The inferred side is a view. Rules never assign it, and committers never supply it. Both sides can be traversed in expressions.

`many` declares a set of links owned by the declaring shape:

```
act Student {
    name: text
    advisor: one Advisor?
    tags: many text
    courses: many Course
}
```

- `courses: many Course` says a student holds a set of courses. Nothing limits how many students hold the same course, so this is many-to-many, with no join shape needed. `Course` gets an inferred `students` collection.
- `tags: many text` is a set of values rather than links. Scalars have no identity to point at, so the shape owns the values.
- `advisor: one Advisor?` is an optional link. The `?` means the student may have no advisor.

A `many` set carries no data about each link. If a link needs data of its own, such as when it was made or a grade, write a shape for it with a `one` field to each end. That shape is a business fact, not ceremony.

Declaring a relationship says nothing about what happens when a linked record is deleted. See [§12, "Deletion"](Reference-12-Assignment#deletion-delete) for that.

### Rules the compiler enforces

- **A relationship targets a base shape, never a refinement.** `invoice: one Invoice` is allowed. `invoice: one OverdueInvoice` is rejected ([checks.md, F2](checks#f2--type-checking)). A field's type is checked once, when the field is written, but refinement membership changes with later commits that never touched the field. To say "an escalation chains only from an unacknowledged escalation", put a requirement on the act that creates the escalation ([§21](Reference-21-never)).
- **A static union is an allowed target.** A refinement that is only `or` over base shapes, with no `where` anywhere ([§9](Reference-09-Composing-refinements)), has a fixed set of members and may be a target. From `examples/gallery/gallery.velle`, `shape Content = Post or Photo` lets a comment declare `target: one Content`: one role, filled by either kind of record. The one declaration gives `comments` to `Post`, to `Photo`, and to `Content`. A committed reference to a record outside the union is refused at the boundary like any wrong-typed value. A union with a `where` on any operand is rejected as a target. `examples/batches/batches.velle` shows the same idea from the other side: `origin: one Origin` on a shared record, where `Origin` is a union of three shapes that each carry their own data.
- **Declare each relationship on one side only.** Writing both `Payment.invoice: one Invoice` and `Invoice.payments: many Payment` is an error, because it could mean two different relationships or two owners of one set ([checks.md, V19](checks#v19--relationship-declaration-coherence)). To name the inferred side yourself, use the derived form under "Renamed and derived collections" below.

## Committing and assigning collections

On an act, `many Course` means the committer names several existing courses by reference, just as `one Course` names one. `many text` on an act means the committer supplies the values. The committer always supplies a `many` field, possibly as the empty set ([checks.md, F4](checks#f4--mapping-totality)). An empty set already means "none", so `many` never takes `?`.

A collection is a set: no order, no duplicates. To order records, name a field to order by ([§10](Reference-10-Predicate-expressions), selectors). A committed collection containing the same reference twice is refused at the boundary. If how many times a link occurs matters, that count is data and belongs on a link shape.

A rule changes a collection in one of three ways. The rule in every case is that a statement assigns exactly the stored field it names.

**Replace the whole set.** A declared `many` is stored, so a rule can assign it. The set becomes exactly the right-hand side:

```
transient act SetEnrollment {
    student: one Student
    courses: many Course
}

rule ApplyEnrollmentSet when SetEnrollment {
    this.student.courses = this.courses
}
```

Links removed by the replacement simply disappear. Nothing else changes. Two acts that replace the same set are applied one after the other, and the later one wins, as with any field.

**Add or remove one link.** `+` adds to a declared `many` and `-` removes from it. The right-hand side may be one record or a collection:

```
rule ApplyEnroll when Enroll {
    this.student.courses = this.student.courses + this.course
}

rule ApplyDrop when Drop {
    this.student.courses = this.student.courses - this.course
}
```

Adding a course the student already has, or removing one they do not have, changes nothing.

**Write one field on every member.** The collection side of a one-to-many is inferred, so a rule cannot assign it. To move several invoices to a new customer, write the stored field through the collection:

```
transient act ReassignInvoices {
    customer: one Customer
    invoices: many Invoice       -- a selector: which children get this parent
}

rule ApplyReassignment when ReassignInvoices {
    this.invoices.customer = this.customer
}
```

- The statement writes `customer` once on each invoice in the act's collection.
- A collection path goes through one `many` and stops. `this.invoices.customer` is allowed. `this.invoices.customer.tier` is not. To write the deeper field, write a rule on the shape that owns it.
- Nothing is removed from the old customer's invoices. The invoices now point elsewhere, so they leave that collection on their own. Removing an invoice from a customer entirely is a separate act on the invoice, setting the pointer elsewhere or to `none` if it is optional.

The compiler rejects assigning an inferred or derived collection, and rejects a path through more than one `many` ([checks.md, V20](checks#v20--collection-assignment-legality)). For the one-writer check, a write through a collection counts as a write to that field on every record of the member shape ([checks.md, V1](checks#v1--one-writer-disjointness)).

**`empty`** is the empty collection and may appear wherever a `many` value is expected: `tags: many text initially empty`, or `courses: empty` inside a `from { ... }` block. There is no way to write a collection of specific records inline. Collections come from the committer, from a traversal such as `this.invoices where OverdueInvoice`, or start `empty`.

## Renamed and derived collections

To give an inferred collection your own name, declare it as a derived `many` property with a formula:

```
shape Person {
    employer: one Company
}

shape Company {
    employees: many Person = (Person where employer == this)
}
```

The `=` makes it a view over the stored side, in the same way `applied: boolean = false` is a derived value and `applied: boolean initially false` is a stored one ([§5](Reference-05-Scalars)). Leave out the `=` and you have declared a second stored side, which the compiler rejects as described above. The diagnostic suggests the `=` form.

You must declare the views yourself when two `one` fields on one shape point at the same target. The inferred name would be ambiguous, so none is inferred:

```
shape Transfer {
    source: one Account
    target: one Account
}

shape Account {
    outgoing: many Transfer = (Transfer where source == this)
    incoming: many Transfer = (Transfer where target == this)
}
```

A derived collection of scalars uses the same form. Like any [derived property](Reference-07-Derived-properties), it is computed when read, never stored, and never assigned.

## Inline part creation (`with`)

Sometimes a record arrives together with its parts: an order and its line items are placed in one request. The lines do not exist yet, so the request cannot refer to them. Instead the act declares, after its body, which parts arrive with it:

```
act Order {
    customer: one Customer
    placedOn: Date
} with orderLines

shape OrderLine {
    order: one Order          -- the one-to-many, declared from the many-of side
    product: one Product
    quantity: integer
}
```

- `OrderLine.order: one Order` declares the one-to-many as usual, which gives `Order` an inferred `orderLines` collection.
- `with orderLines` names that collection. From the one name the compiler knows the part shape, `OrderLine`, and the field that points back at the order, `order`.
- The generated input for the act now carries a list of line values. Each line value has every stored field of `OrderLine` except `order`, which the system fills in. The committer cannot supply a reference to a record that does not exist yet.
- A `many` field on an act still means references. Only `with` makes parts arrive as values.

The order and its lines land as one commit. A rule `when Order` fires once. A rule `when OrderLine` fires once per line, in the order's transaction, so if any line's rule fails the whole order is refused ([§20, "All-or-nothing batches"](Reference-20-State-change-patterns#all-or-nothing-batches)). For something that should happen once per order, write the rule on the order. There is never a moment when the order exists without its lines.

Parts can have their own parts, and an act can declare several part collections. Use a flat list for several collections, and braces when a part has parts of its own:

```
act Order { customer: one Customer, placedOn: Date } with orderLines, shipments

act Order { customer: one Customer, placedOn: Date } with {
    orderLines with { customizations }
    shipments
}
```

Each nested `with` is read against the part's shape the same way. Where no inverse was inferred, as with `Transfer` above, `with` names the declared view, and the view's formula tells the compiler which field is the back-reference: `act Account { ... } with outgoing` fills in `source`.

### Rules the compiler enforces for parts

- A `with` entry must name an inferred inverse, or a derived view of the exact form `(P where field == this)`. Any other view is rejected: a part collection must be the inverse of a declared `one` field ([checks.md, V22](checks#v22--closure-declaration-legality)).
- Parts form a tree. A part can point only at the record it arrived under. A part that refers to another record created in the same commit is rejected. Commit that record first and refer to it in a later act, or restructure.
- Inline parts are not de-duplicated. Two identical line values create two distinct lines, each with its own `id`. The duplicate refusal above applies to references only.
- `with` creates records. It never updates existing ones. To change a record that exists, commit a reference to it and let a rule assign the field ([§12](Reference-12-Assignment)).
- A rule on a part may not write a field of the record it arrived under. Several lines writing one field of one order is a write conflict, and the compiler rejects it ([checks.md, V1](checks#v1--one-writer-disjointness)). Derive the aggregate on the order instead, as `examples/orders/orders.velle` does with `total: integer = sum(orderLines, quantity)`, or write a rule on the order.
- Once committed, the order and its lines are ordinary records. They are updated, frozen, exited, and referenced like any other.
- A `transient act` may not have a `with` clause.

The generated input for any act is the shape minus what the committer may not claim: no `id`, no `timestamp` fields, no derived properties, and no back-references on parts ([§22](Reference-22-Open-unresolved), "External input"). A reference to a record outside the commit is a typed handle. A part is a nested value. To require at least one line, write a `never` or a gate refinement over the committed order ([§21](Reference-21-never)). An empty part list is allowed and means no parts.

## Related pages

- [§4 State and commits](Reference-04-State-and-commits), for what one commit contains.
- [§5 Scalars](Reference-05-Scalars), for `initially`, `?`, and `id`.
- [§7 Derived properties](Reference-07-Derived-properties), for the `=` form a renamed collection uses.
- [§9 Composing refinements](Reference-09-Composing-refinements), for static unions.
- [§12 Assignment](Reference-12-Assignment), for the one-writer rule and deletion.
- [§22 Open / unresolved](Reference-22-Open-unresolved), "External input", for what the generated input carries.
