# 8. Refinements (`where`)

The core idea of the language: a condition is a named subset of a shape, defined by a predicate — not a branch.

```
shape OverdueInvoice = Invoice where balance > 0 and due < today
```

`where` is reused for two purposes with the same meaning: defining a refinement (above), and filtering a collection inline by one (`invoices where OverdueInvoice`, used inside `count(...)`). The compiler goal (not yet exercised in examples) is to check refinement **exhaustiveness and overlap** — proving a set of refinements fully partitions a shape, or flagging when two overlap.

Refinements are **pure predicates, not triggers** — `OverdueInvoice` doesn't "fire" when it becomes true; it's simply evaluable against current data at any time. Only rules (`## rule`, [§11](Reference-11-rule-when-on)) react to something actually happening.

A refinement's predicate may reference the shape it refines, directly or through a relationship — self-reference needs no special syntax (see `## Derived properties`, [§7](Reference-07-Derived-properties), and `## Predicate expressions`, [§10](Reference-10-Predicate-expressions)).

## Refinement properties

A refinement can declare properties of its own, in a body after its predicate — data that belongs to the refined state rather than to the base shape:

```
shape ArchivedInvoice = Invoice where exists ArchiveRequest for this {
    captured archivedBy: one User = (ArchiveRequest for this).requestedBy
    captured archivedOn: Date = today
}
```

Asking an `Invoice` for `archivedBy` is nonsensical — only an archived invoice has an archiver. Rather than polluting the base shape with an optional field for every refined state's data (secretly correlated with that state), the property lives on the refinement, where membership itself guarantees its presence. The base shape's declaration stays a clean statement of what *every* instance has; each refinement's body states what membership *adds*.

Refinement properties come in exactly the same two kinds as base-shape properties — there is no third, "assigned" kind:

- **Derived** — plain `= expr`, recomputed live from current data, exactly as in `## Derived properties`. The only novelty is scope: it's evaluable exactly where membership holds (`priceDrift` below is one).
- **Captured** — marked with the leading keyword `captured`: evaluated once at the moment the current membership begins, fixed for the duration of that membership, absent before entry, retracted at the close of the exit commit — an exit rule may still read it, as its last reader (`## Exit triggers`) — and re-captured on re-entry. The marker is required because a bare `= expr` in body position is a live derivation — the two kinds must read differently. Capturing `today`/`now` anchors them to the entry moment: `archivedOn` above is the membership's start date, recorded as model data.

`captured` is `initially`'s refinement-position sibling (`## Scalars`): each evaluates its expression once, at the moment its declaring scope begins — `initially` at the creation commit, `captured` at the entry commit. The differences follow from those scopes, not from separate machinery: a creation is forever, so an `initially` field is stored and assignable ever after; a membership can end, so a capture is fixed for its duration and retracted on exit. This is also why `captured` is legal only in a refinement body — only a refinement's predicate provides an entry moment to capture at; a base shape's one such moment is creation, and `initially` is its spelling.

**Who may see a record is scoped the same way (`visible to`).** A shape's body — a base shape's, since a refinement takes its base's — may declare which viewers its records are returned to: `visible to Staff where exists (VendorAssignment where staff == actor and vendor == this.vendor)`, `visible to Administrator`, `visible to anyone` for a public one, several clauses disjoining; a field narrows within its record's with the same clause after its type (`cost: Money visible to Administrator`), a derived property included, since a derivation is calculated from every input whoever asks and what it reveals is the author's to close. The viewer is bound in the predicate as `actor`, or under the name `as` chooses, exactly as an act's `by` clause binds its committer ([§22](Reference-22-Open-unresolved), "The actor"); the clause is applied by the read surface only — a query for a viewer admits what the clauses admit, a fetch of an invisible record resolves absent, a model object is projected with its hidden fields absent — and never inside evaluation, so no rule, derivation, or prohibition ever depends on who is reading. Once a spec has any `by` or `visible to`, every readable shape declares its visibility, in its body or through a spec-wide `visible to Staff` written once at the top that a shape's own clauses replace rather than add to ([`checks.md`](checks) [V32](checks#v32--every-readable-shape-declares-its-visibility)). The worked spec is `examples/vendors/vendors.velle`.

**Every captured value traces to data.** There is no ambient execution context — no "current user", no request-scoped magic. The person who committed an act is a fact *of that commit*, bound by the act's `by` clause and stored on a durable act like its timestamp ([§22](Reference-22-Open-unresolved), "The actor"), so `archivedBy` reaches a `User` only through the data graph: the act carrying that data is reified as a shape (`ArchiveRequest by User`, say) before the refinement can capture from it, and the capture reads `actor` off the request. That's a feature, not a workaround: reified acts are independently required for occurrence identity under re-entry, and they are what `why`/provenance will walk. (`(ArchiveRequest for this)` above is legal only while the spec proves at most one can exist per invoice — `## Predicate expressions`' `for`-query rule; the moment re-archival enters the model, the reference must become an ordered selection — see Open/unresolved.)

**Entry-evaluability guardrail.** A captured property's expression must be provably evaluable at the moment membership begins: every reference in it must be guaranteed by the refinement's own predicate, or be unconditionally present on the base shape. `(ArchiveRequest for this)` is legal above precisely because the predicate asserts `exists ArchiveRequest for this` — the predicate narrows the capture expression, the same machinery by which `is some` licenses `.`. A capture reading something its predicate doesn't guarantee is a compile error. A refinement whose captures need nothing beyond the base shape's own data (`captured balanceWhenOverdue: Money = balance` on `OverdueInvoice`) can be entered by drift; one whose predicate requires an act-fact can only be entered by that act occurring — the compiler derives which kind each refinement is from its predicate, the human never declares it.

**Visibility and narrowing.** From the base shape, refinement properties are invisible: `invoice.archivedBy` is a compile error unless `invoice` has been narrowed by `is ArchivedInvoice` earlier in the same conjunction (or the corresponding branch of a conditional) — `is <Refinement>` narrows exactly the way `is some` does. A property whose formula reads properties of *two* refinements lives on their intersection, where both are in scope and provably present:

```
shape Reconciled = Quoted and Delivered {
    priceDrift: Money = billedTotal - quotedTotal
}
```

**Membership is unchanged.** A refinement with properties is still a pure predicate as to *membership* — properties change what a member *has*, never when membership *holds*. Captured properties are per-membership memory, state-layer through and through: they retract on exit. If the business cares about past memberships ("who archived it back in March, before it was unarchived?"), that was never a property — it's history, modeled as occurrence facts plus `latest(... by ...)`.

## Frozen fields (`frozen`)

A refinement body may constrain as well as extend: a `frozen` clause names stored fields of the base shape that may not be written while membership holds — immutability scoped to a state:

```
shape IssuedInvoice = Invoice where exists Issuance for this {
    frozen lineItems, billingAddress
}
```

Immutability in Velle is state-scoped, never field-scoped: the same field is freely editable on a draft and frozen once issued, so a declaration-site marker (`final`, `readonly`) is the wrong granularity — the business sentence is about the *state*, and the refinement is the state's name. `frozen` is a **conditional write permission**, a new kind of statement in the same family as one-writer (`## Assignment (mutation in place)`): one-writer says who may write a field; `frozen` says when writing it is legal at all. A write to a frozen field is illegal at any commit where the instance is a member in the commit's **pre-state** — so the entering commit may still write (the instance is a member only in post-state: a rule reacting to the `Issuance` can normalize a field in the same commit that freezes it), and a non-monotone predicate thaws the freeze the moment membership ends. Bare `frozen` (no list) freezes every stored field of the base shape, auto-extending to fields added later (the same virtue as derived trigger sets — `## rule`); the listed form narrows deliberately. Only stored fields are eligible: derived properties were never assignable, and captured properties are fixed by definition. Composition (`## Composing refinements`) needs no extra rule — each refinement's freeze applies wherever its own membership holds.

**The check is static, whole-spec.** Every write to stored state is a rule assignment to a literal static path (`## Assignment (mutation in place)`), so the compiler knows every writer of every field; the freeze check is the one-writer disjointness question re-aimed — *can this writer's trigger coincide with membership in the freezing refinement?* — fail-closed, reported as one connected diagnostic naming writer and freeze. The fix is the rejection-as-data idiom: make the edit a `transient act` ([§4](Reference-04-State-and-commits)), partition it (`ApplicableEdit = LineItemEdit where invoice is not IssuedInvoice` / `RefusedEdit = ...`), hang the writing rule off the applicable subset — now provably disjoint — and let the refusal land as a record the caller reads back. A transient act's partition is evaluated once, at its own commit, so it cannot drift as the invoice's state moves ([`checks.md`](checks) [A4](checks#a4--drift-exposed-act-partition)); where the request itself must be a durable record, the partition is scoped to unhandled requests and anchored by each side's outcome evidence ([`patterns.md`, "Validation rejection is data"](patterns#validation-rejection-is-data)). No commit is ever unwound: immutability needs no rejection machinery, only proofs and partitions. A freeze no rule could ever violate is advisory dead machinery ("serves no writer" — the dead-tolerance shape, `## Self-referential folds and tolerates`).

**Freeze depth is declared, not inferred.** "Can't edit line items" usually means the `LineItem` instances too; that's a conditioned refinement on the related shape, not transitive magic:

```
shape LockedLineItem = LineItem where invoice is IssuedInvoice {
    frozen price, quantity
}
```

Each hop of the freeze is its own visible business decision (issuing freezes the line item's price, not the product's name) — the same stance as "No guard sugar" (`## Run-once guards`).

**An evidence-held lien is this construct with the evidence in the predicate.** "The receipt freezes the invoice" is a freeze on an evidence-entered refinement, and lifting the lien is a non-monotone predicate — no lien vocabulary needed:

```
shape ReceiptedInvoice = Invoice where
    exists Receipt for this and not exists ReceiptVoid for this {
    frozen lineItems
}
```

Frozen while a receipt stands unvoided; thawed the moment the void lands. Provenance reads off the predicate (`why`: "frozen because a receipt stands, unvoided").

**Deletion permission is scoped the same way (`undeletable`).** A refinement body may also declare `undeletable`: while membership holds, no rule may delete the instance (`## Assignment (mutation in place)`, "Deletion"). It is `frozen`'s sibling, not its consequence — "cannot be edited" and "cannot be removed" are separate business sentences, and neither implies the other. The check is the same static, whole-spec one: every deleter of the base shape must provably exclude membership, by the same disjointness proof, and the fix-it idiom is the same partition — hang the deleter off the deletable subset, with the refusal landing as data. The gate is negative only: "may be deleted while in this state" is the default and is never written; positive-exhaustive sentences belong to the state partition declaration (`## Open / unresolved`). A gate no deleter could trip is dead machinery, reported as such.

```
shape PublishedListing = Listing where not isDraft {
    undeletable
}
```
