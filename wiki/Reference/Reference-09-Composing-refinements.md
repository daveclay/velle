# 9. Composing refinements (`and`, `or`, `not`)

A refinement can be built from other named refinements instead of repeating their conditions. "Urgent" means overdue, high priority, and still open. When each word is already a [refinement](Reference-08-Refinements), the composite is their names joined by `and`, `or`, and `not`. After reading this page you can build refinements from smaller ones, know which properties a composite has, and use `or` to treat two shapes as one kind of record.

## Combining refinements of one shape

A support desk says "a ticket is urgent when it is overdue, high priority, and still open."

```
shape Open         = SupportTicket where status != "closed"
shape Overdue      = SupportTicket where due < today
shape HighPriority = SupportTicket where priority == "high"

shape UrgentOverdueTicket = Overdue and HighPriority and Open
shape NeedsAttention      = Overdue or Escalated
```

This example is not in the example files.

- `A and B` is the records that are members of both. `A or B` is the records that are members of either.
- `not` excludes one. From `examples/billing/billing.velle`: `shape ActionableOverdue = OverdueInvoice and not ArchivedInvoice`.
- `not` binds tightest, then `and`, then `or`. `A and B or C` means `(A and B) or C`.
- A composite may have a body of its own, like any refinement ([§8](Reference-08-Refinements)).
- A refinement of a refinement, `OverdueTicket = OpenTicket where due < today`, has every property of the one it narrows.

**Which properties a composite has.** `A and B` has the properties of both. If the two declare a property of the same name separately, the compiler rejects the spec. `A or B` has only the properties both sides get from one shared declaration, because a member may belong to only one side.

## Treating two shapes as one (`or` across shapes)

A gallery has posts and photos, and members comment on either. "A comment is on a piece of content" needs one name for both shapes. `or` between two base shapes gives that name. From `examples/gallery/gallery.velle`:

```
shape Content = Post or Photo {
    commentCount: integer = count(comments)     -- a derived body over the shared inverse
}
```

- `Content` is every post and every photo. It can be a field type: `target: one Content` on `Comment` lets a comment point at either ([§6](Reference-06-Relationships)).
- A field typed with the union gives all three names an inferred inverse. `Comment.target` puts `comments` on `Post`, `Photo`, and `Content`.

A union shares only what both shapes get from one declaration: `id`, and inverses inferred through fields typed with the union. `Post.author` and `Photo.author` are two declarations that happen to agree, so they are not shared. A rule over `Content` that reads `this.author` does not compile.

Over the union, a rule may read `id` and the shared inverses, create records whose fields are typed with the union, count, test `exists`, and delete. Anything else needs the record narrowed to one shape first. `target is Post` in an `if` condition, or to the left of an `and`, lets what follows read `Post`'s own fields, as in `never (Comment where target is Post and target.author == author)`. In an `if` over a two-shape union, the `else` branch is narrowed to the other shape. A same-named field is read per shape: `if target is Post then target.author else target.author`.

Three more rules apply to a union of shapes:

- Its body may declare derived properties only. For `captured`, write `initially` on each shape. `undeletable` goes on a refinement of each shape.
- A rule whose condition combines two shapes inline must name the union first.
- A `transient act` may not be in a union.

## What the compiler checks

- **It rejects** reading a field that is not shared across a union without narrowing first ([checks.md, V11](checks#v11--narrowing-analysis)).
- **It rejects** `when leaving Content` over a union of base shapes, since a record enters one only by being created. The error offers `when deleted` ([checks.md, V3](checks#v3--unfireable-rule)).
- **It warns** when `and` joins refinements of two base shapes. A record has one base shape, so the composite is always empty ([checks.md, A2](checks#a2--dead-machinery)).
- **For one-writer**, a union overlaps each of its shapes, and two base shapes never overlap ([checks.md, V1](checks#v1--one-writer-disjointness)).

## Related pages

- [§8 Refinements](Reference-08-Refinements), for `where` and refinement bodies.
- [§10 Predicate expressions](Reference-10-Predicate-expressions), for narrowing.
