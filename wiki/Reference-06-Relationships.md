# 6. Relationships (`one`, `many`)

```
shape Invoice {
    customer: one Customer
}

shape Payment {
    invoice: one Invoice
}
```

`one`/`many` declare **ownership of the relationship, not its direction**. The declaring shape owns the relationship: the declared property is its stored side — the side rules assign and committers supply — and its cardinality says how many the *owner* points at. The inverse side is never declared and never chosen: it is inferred — derived, unassignable — and its cardinality is structural fallout from the declared side. `Invoice.customer: one Customer` gives `Customer` an inferred `invoices` collection with no declaration on `Customer` (`Customer where count(invoices ...)` just works); the inferred name is the related shape's name, decapitalized and pluralized with `s` (`Payment.invoice: one Invoice` gives `Invoice` a `payments` collection — the `sum(payments, amount)` of `## Derived properties`).

Cardinality reads off the declared side alone:

- **`one` declared** — each owner points at one target; nothing constrains how many owners point at the same target, so the relationship is **one-to-many**, declared from the "many-of" side. This is the only spelling of one-to-many, deliberately: the pointer on that side makes "exactly one per member" structurally impossible to violate, where declaring a collection on the other side would degrade the same invariant to a checked global disjointness constraint (no member in two collections, none in zero) — and structural impossibility beats checked discipline (`## 4`, "Transient acts", makes the same trade).
- **`many` declared** — the owner holds an edge set, and nothing constrains how many owners' sets a target appears in, so the relationship is **many-to-many**, with no intermediary join shape required. A declared `many` is exactly the *data-free* edge set: the moment an edge carries data (when it was made, by whom, a grade), it is a business fact wanting its own shape — a graduation point, never ceremony. `many` also takes a scalar (`tags: many text`) — an owned collection of values, since scalars have no identity to reference. The commit and assignment story for collections is below.

Direction is not encoded because it would carry no information: inference means both sides always traverse. The traditional parent/child framing conflates three separate things, and these descriptors take exactly one of them — traversal direction (symmetric and free, per the above), **write ownership** (what `one`/`many` declare), and lifecycle dependency ("children die with the parent" — no part of the relationship descriptor; existence-dependency is delete territory (OQ37)). "Invoice has payments" and "payment belongs to invoice" were never two directions of one arrow: only the second is a declaration.

**A relationship targets a base shape, never a refinement.** `invoice: one Invoice` is legal; `invoice: one OverdueInvoice` is a compile error ([`checks.md`](checks) [F2](checks#f2--type-checking)). A field's type is checked once, at the commit that writes the field, while a refinement's membership drifts with every later commit that touches its predicate — so a refinement-typed field would be violated by a commit that never wrote it, and the language would have no honest response: refusing the unrelated commit enforces an invariant nobody declared, clearing the field is a write with no assignment behind it (`## Assignment (mutation in place)`), and letting it stand makes the type a lie. The business sentence "an escalation chains only from an unacknowledged escalation" is a condition on the *act* that creates the escalation — a requirement or a gate refinement over it (`## never`) — not a property of the field's type.

**The one refinement that is a legal target is a static union.** `shape Content = Post or Photo` — a pure `or` of base shapes with no predicate anywhere (`## Composing refinements`) — has a member set fixed by declarations, never by data, so it cannot drift, and `target: one Content` is legal. This is the **polymorphic association**: one role, filled by an instance of either shape ("a comment is on a post or on a photo"), where the alternative is one optional field per shape plus exclusivity invariants that grow with every shape added. The union-typed field infers its inverse onto every operand *and* the union from the one declaration — `comments` on `Post`, on `Photo`, and on `Content` — and a committed reference to an instance outside the union is a type-level refusal at the boundary, like any value that cannot inhabit the field's type. A union carrying a predicate anywhere (`Post or (Photo where published)`) is refused as a target for the same reason as any refinement. The worked spec is `examples/gallery/gallery.velle`; `examples/batches/batches.velle` works the other direction of the same association — a record whose provenance is one of three kinds, where the union-typed field on the shared record (`origin: one Origin`) replaces a supertype with three subtypes, each kind's own data living on its own shape and the shared data on the one record.

One consequence is an ambiguity rule: because a bare declared `many` *means* an owned many-to-many edge set, declaring **both** sides of one relationship (`Payment.invoice: one Invoice` *and* `Invoice.payments: many Payment`) is not a restatement — it is either two distinct relationships between the same shapes or two owners of one edge set, and the compiler refuses to guess (`## 1. Principles`): a compile error demanding intent. Renaming an inferred inverse where the derived name reads badly is therefore *not* spelled as a bare stored `many`; a renamed inverse is a view, not owned data, and its spelling is the derived form below ("Renamed and derived collections").

## Committing and assigning collections

On an act, `many X` is the plural of what `one X` already means there: the committer names several existing instances by reference (`ChangeDueDate.invoice: one Invoice` names one; `courses: many Course` names several). `many <scalar>` on an act supplies the values themselves. The act's generated input value ([§22](Reference-22-Open-unresolved), "External input") carries the collection; supplying it — possibly empty — satisfies mapping totality ([F4](checks#f4--mapping-totality)), and the empty collection *is* the absence, which is why `many` takes no `?`.

A collection is a **set**: unordered, duplicate-free. Ordering comes from declared data, never from position ([§5](Reference-05-Scalars), selectors) — a positional list would smuggle in an ordering with no datum behind it. A duplicate reference in a committed collection is refused at the boundary, a type-level refusal like any other: it is either a caller bug or a multiplicity claim `many` cannot express, and multiplicity that matters is data on an edge shape — the graduation point; the diagnostic says so.

What a rule may do with a collection follows one sentence — **you may only assign what is stored, and a statement assigns exactly the field it names** — met by the two relationship kinds:

- **Whole-set replacement.** A declared `many` is stored, so it is assignable as a value; the edge set becomes exactly the right-hand side:

  ```
  transient act SetEnrollment {
      student: one Student
      courses: many Course
  }

  rule SetEnrollment {
      this.student.courses = this.courses
  }
  ```

  Replacement is well-defined because m2m edges are free-standing: removal just deletes edges, and nothing else moves. Concurrent replacement needs no special rule — acts serialize as commits, so two full-set replacements are last-in-wins, exactly as two assignments to a scalar field are.

- **Fan-out assignment.** A one-to-many's collection side is inferred — a view over the children's stored pointers — and a view is never an assignment target: "setting" it is really a per-child write of the stored field, spelled *through* the collection:

  ```
  transient act ReassignInvoices {
      customer: one Customer
      invoices: many Invoice       -- a selector: which children get this parent
  }

  rule ReassignInvoices {
      this.invoices.customer = this.customer
  }
  ```

  One write per member, the mutated field named in the statement. **One hop only**: a collection path assigns a field *of* the members, never through them (`this.invoices.customer`, never `this.invoices.customer.tier`) — the same instinct as the derived-property self-reference rule ([§7](Reference-07-Derived-properties)). The act's collection selects; nothing is removed, so totality is trivial — removal is its own explicit act, spelled on the child (reassignment, or `none` where the pointer is optional). For one-writer analysis a collection-path write counts as a write to that field on *every* instance of the member shape — the coarse fail-closed rule ([checks catalog](checks), [V1](checks#v1--one-writer-disjointness)); refining it with disjointness proofs is post-v0 calibration.

- **Incremental edits.** `+` and `-` on a declared `many` are union and removal — `this.student.courses = this.student.courses + this.course` — element or collection on the right. Set semantics keep the edges quiet: adding a present member and removing an absent one are no-ops. This and the duration case are the only operator overloads ([`grammar.md`](grammar), value expressions).

- **`empty`.** The empty collection, legal wherever a `many` value is expected: `tags: many text initially empty`; `courses: empty` as a `from { ... }` entry. There are no collection literals — composing a collection out of elements in-spec is the same territory as composing text out of data ([`grammar.md`](grammar), TextLiteral): collections enter from the boundary, from traversals (`this.invoices where OverdueInvoice`), or start `empty`.

## Renamed and derived collections

An inferred inverse under an author-chosen name is a **derived `many` property with a set-denotation formula** — visibly a view over the declared side:

```
shape Person {
    employer: one Company
}

shape Company {
    employees: many Person = (Person where employer == this)
}
```

`many` is the type in both stored and derived positions — the type word says what the value *is*, and `=` says where it comes from, exactly as `applied: boolean initially false` vs `applied: boolean = false` ([§5](Reference-05-Scalars)). The misread this could invite — forgetting the `=` and declaring an owned edge set — is structurally caught: a renamed inverse's other side is declared by definition, so the omission trips the both-sides error above, and the diagnostic suggests the `=` form.

Renaming is *necessary*, not cosmetic, when two declared sides share a target: the decapitalized-pluralized name would be ambiguous (which fields feed `Account.transfers`?), so no inverse is inferred and the spec declares its views — ambiguity is a compile error ([§1](Reference-01-Principles)):

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

Derived `many <scalar>` collections use the same form. A derived collection is a view like any derived property: computed on read, never stored, never assigned ([§7](Reference-07-Derived-properties)).

## Inline part creation (`with`)

An order committed together with its line items is a different arrival than anything above: the parts *don't exist yet*, so they can't be references — they are values created in the act's commit. Committing the parts first can't serve it (each `OrderLine.order: one Order` needs an order that doesn't exist, and per-part commits are the wrong granularity for "these arrive together"), and `many` never will (references-always, above). The act's declaration declares it, with a `with` clause after the body:

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

`with` names a **collection-typed property of the act** — here the inferred inverse — and that one name determines everything: the part shape (`OrderLine`), the language-populated back-reference (`order`), and the addition to the act's generated input value ([§22](Reference-22-Open-unresolved), "External input"): per part, every stored field minus the back-reference, which only the language can populate — a committer-suppliable back-reference would be a claim about an instance that doesn't exist yet. References-always survives trivially: the inferred inverse is derived, not stored, so `orderLines` would never appear in the input value at all without the `with` — the clause adds a field that had no other way to exist, and a declared `many` on an act keeps meaning references, untouched.

The commit carries the act *plus* its declared parts as **one statically-shaped closure** ([§4](Reference-04-State-and-commits)): membership is declared, never data-dependent, so trigger and one-writer analyses read it from the declaration exactly as they read a single-instance commit. At the closure's commit the entrants are the container and each part — `rule ... when Order` fires once, `rule ... when OrderLine` once per line, all sharing the act's transaction ([§20](Reference-20-State-change-patterns)'s fan-out precedent); an author who wants once-per-act granularity writes the condition on the container. The order the records physically land in is compilation's business (the topological order over the closure's reference tree, container first) and is never observable: pre-state and post-state are defined at the one commit, and no state where the order exists without its lines ever exists to observe.

Nesting and multiple edges — a flat list for multiple edges; braces when an edge carries its own closure:

```
act Order { customer: one Customer, placedOn: Date } with orderLines, shipments

act Order { customer: one Customer, placedOn: Date } with {
    orderLines with { customizations }
    shipments
}
```

Each nested `with` resolves against the part shape the same way. Where no inverse is inferred — two `one` fields sharing a target, the `Transfer` situation above — `with` names the declared view, and the view's recognized form pins the back-reference: `act Account { ... } with outgoing` populates `source`, because `outgoing = (Transfer where source == this)`. An arbitrary-predicate view is a compile error: a closure edge must be the inverse of a declared `one` field ([checks catalog](checks), [V22](checks#v22--closure-declaration-legality)).

The rules that keep the construct honest:

- **Closures are trees.** `with` only ever names inverse edges pointing at the enclosing level, so a sibling reference is unspellable — and an in-closure reference targeting an instance created in the same closure is refused: commit it and name it in a later act, or restructure. The tree matches the write side's grain (one hop only, above), and it is what makes the generated input plainly serializable — strictly nested values, no closure-local naming scheme.
- **Inline parts are a bag, not a set.** The set semantics above are about references — two identical handles name the same instance. Two identical inline part values mint two *distinct* instances, each with its own `id`: quantity 1 twice is not quantity 2 once. The post-commit inverse is trivially a set of distinct instances.
- **Creation-only.** The closure exists because its targets can't be referenced. Anything that exists is referenceable, and changing it is already served — an act carrying references plus a rule's assignment ([§12](Reference-12-Assignment)) or fan-out (above); nothing update-shaped ever rides the closure, and "create it if it doesn't exist" is a spec decision (a rule, a gate, a `never`), never input structure.
- **Parts never write the container.** A part-triggered rule assigning through the back-reference is N sibling firings writing one field of one instance — a proven write-write conflict, refused totally ([checks catalog](checks), [V1](checks#v1--one-writer-disjointness)). The served spellings: derive the aggregate on the container (`total: Money = sum(orderLines.subtotal)` — usually the value was a denormalized derivation all along), or condition on the container for a once-per-act effect.
- **Ordinary instances after the commit.** The closure is a fact about one commit's input, never a property of the instances it creates: once landed, container and parts are updated, frozen, exited, and referenced like anything else.

The generated input structure is a **projection, not a mirror**: the boundary type is the shape minus everything the committer may not claim — no `id`, no `timestamp` fields, no derived properties ([§22](Reference-22-Open-unresolved)), and no back-references on inline parts. An out-of-closure relationship generates a typed reference handle (how `id` opacity survives the boundary, [§5](Reference-05-Scalars)); a closure edge generates a nested input type, recursively. Everything unrepresentable in the input type needs no runtime refusal, so the runtime surface shrinks to what only runtime can know: the referenced instance exists, no duplicate references, and the `never` guardrails at this act's boundary — the refusals and constraints ([§21](Reference-21-never)). Minimum-parts policy needs no closure feature — "an order must have at least one line" is a `never` or gate refinement over committed state ([§20](Reference-20-State-change-patterns), [§21](Reference-21-never)); the empty part collection is the absence, as ever. A `transient act` with a closure is refused for now (OQ43).
