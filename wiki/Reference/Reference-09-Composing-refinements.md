# 9. Composing refinements (`and`, `or`, `not`)

A refinement can be built from other named refinements instead of repeating their conditions. "Urgent" means overdue, high priority, and still open. When each word is already a [refinement](Reference-08-Refinements), the composite is their names joined by `and`, `or`, and `not`. After reading this page you can build refinements from smaller ones, know which properties a composite has, use `or` to treat two shapes as one kind of record, and declare a field once for several shapes with `is`.

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

`or` also joins refinements of different shapes. From `examples/gallery/gallery.velle`:

```
shape LongPost = Post where length(body) >= 40
shape Captioned = Photo where caption != ""
shape Notable = LongPost or Captioned
```

- `Notable` is read as a union of its shapes, `Post or Photo`: it carries what the two share, and a rule `when Notable` fires for a long post and for a captioned photo.
- The generated code reads it as it reads `Content`: `notables` is a view, and a post and a photo each carry whether they are notable ([Read API](Read-API)).
- It is not a field type. A field holds records of a union of shapes, whose members are fixed by declarations, never of a union of refinements, whose members change with the data ([§6](Reference-06-Relationships)).

Three more rules apply to a union of shapes:

- Its body may declare derived properties only. For `captured`, write `initially` on each shape. `undeletable` goes on a refinement of each shape.
- A rule whose condition combines two shapes inline must name the union first.
- A `transient act` may not be in a union. Two transient acts answered by one rule take the fields they share from one shape with `is`, below, and the rule is written over that shape.

## Sharing a field across shapes (`is`)

A field several shapes have in common is declared once, on a shape of its own, and each shape takes it by naming that shape after `is`. The field is then one declaration on every shape that takes it. A rule or a refinement over the taken shape covers every taker, and a union of the takers shares the field too.

The gallery says "every post and every photo has an author". A post is a contribution, a photo is a contribution, and a contribution has an author. From `examples/gallery/gallery.velle`:

```
shape Contribution {
    author: one Member
}

act Post is Contribution {
    body: text
}

act Photo is Contribution {
    caption: text
}

shape Content = Post or Photo

rule NotifyAuthor when Comment {
    CommentNotice from {
        recipient: target.author
        comment: this
        excerpt: if target is Post then target.body else target.caption
    }
}
```

- `shape Contribution { author: one Member }` declares `author` once. A contribution has no records of its own: every record of it is a post or a photo, and a rule cannot create one.
- `act Post is Contribution` takes the declaration. A post has `author` as if the line were in its own body: a committed post supplies it like any field, and `post.author` reads it. A shape may take several shapes, `act SiteVisit is Dated, Sited { ... }`, and a shape that is taken may itself take another, so a taker takes everything along the chain. A shape that reaches one declaration by two paths takes it once.
- `Contribution` is read as a union of its takers, `Post or Photo`, with the shared field on it. A rule `when Contribution` fires once per post and once per photo. `shape Prolific = Contribution where count(comments) > 10` is a refinement over every taker. A field `one Contribution` holds a post or a photo. `is Post` narrows a contribution to a post's own fields, as it narrows a union, in a rule, a refinement, or a `never` over the taken shape alike: `never (EntryChange where this is RenameEntry and this.newName == "")` refuses an empty rename at its door, with `RenameEntry` a `transient act` that takes `EntryChange`.
- `Member.contributions` is the one collection `author` infers, named from the taken shape and holding posts and photos alike ([§6](Reference-06-Relationships)). A member's posts alone is a view: `posts: many Post = (Post where author == this)`.
- `Content = Post or Photo` has `author`, because both shapes take it from one declaration. The rule reads `target.author` directly; `body` and `caption` are declared separately, so those are read after narrowing.

A refinement and a rule over a taken shape, where a site visit and a plot inspection each have a due date. This example is not in the example files:

```
shape Dated {
    due: Date
}

act SiteVisit is Dated {
    site: one Site
}

act PlotInspection is Dated {
    site: one Site
    plot: text
}

shape Overdue = Dated where due < today

shape Reminder {
    about: one Dated
    sentOn: Date
}

rule RemindOverdue when Overdue on Daily {
    Reminder from { about: this, sentOn: today }
}
```

- `shape Overdue = Dated where due < today` is one refinement over every dated shape, and `RemindOverdue` is one rule. A third dated shape joins by writing `is Dated`, and the refinement and the rule cover it with no change.
- `about: one Dated` holds a site visit or a plot inspection. The engineer's read returns the record as whichever it is ([Read API](Read-API)).

When a `transient act` takes a shape, a rule over that shape fires inside each request's own commit, so one rule answers several requests ([§4](Reference-04-State-and-commits)). Nothing durable may then be typed with the taken shape, since a record of the act is gone when its commit ends.

## What the compiler checks

- **It rejects** reading a field that is not shared across a union without narrowing first ([checks.md, V11](checks#v11--narrowing-analysis)). When each operand declares the field for itself, the message names the declarations and offers narrowing and `is` ([checks.md, F1](checks#f1--name-resolution-and-casing)).
- **It rejects** a rule creating a union, which has no records of its own; the message names the operands ([checks.md, F1](checks#f1--name-resolution-and-casing)).
- **It reasons over each operand** of a refinement over a union: a prohibition's writers ([checks.md, V10](checks#v10--never-obligations-and-spend-tracking)), a singular reference's at-most-one proof ([checks.md, V12](checks#v12--singular-reference-proofs)), two rules flipping membership ([checks.md, V15](checks#v15--confluence-coarse)), and a required union-typed field stranded by a deletion ([checks.md, V24](checks#v24--referential-completeness)).
- **It rejects** `when leaving Content` over a union of base shapes, since a record enters one only by being created. The error offers `when deleted` ([checks.md, V3](checks#v3--unfireable-rule)).
- **It warns** when `and` joins refinements of two base shapes. A record has one base shape, so the composite is always empty ([checks.md, A2](checks#a2--dead-machinery)).
- **For one-writer**, a union overlaps each of its shapes, and two base shapes never overlap ([checks.md, V1](checks#v1--one-writer-disjointness)). A taken shape overlaps each shape that takes it.
- **It rejects** `is` naming a shape the spec does not declare, and a rule creating a taken shape, which has no records of its own ([checks.md, F1](checks#f1--name-resolution-and-casing)).
- **It rejects** an `act` that other shapes take, two shapes taking each other, a shape taking one field name from two shapes, and a shape declaring a field it also takes ([checks.md, F3](checks#f3--position-rules)).
- **It rejects** a field typed with a taken shape that a `transient act` takes ([checks.md, V17](checks#v17--transient-isolation)). An act's closure may name the collection a taken shape's back-reference infers; its parts are then of every shape that takes it, each saying which when it arrives ([§6, "A request that is not kept, with its parts"](Reference-06-Relationships#a-request-that-is-not-kept-with-its-parts); [checks.md, V22](checks#v22--closure-declaration-legality)).

## Related pages

- [§8 Refinements](Reference-08-Refinements), for `where` and refinement bodies.
- [§10 Predicate expressions](Reference-10-Predicate-expressions), for narrowing.
