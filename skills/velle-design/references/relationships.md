# Relationships in depth

Open this file for collections a rule changes, deleting a record others point at, parts that arrive with an act, a field that holds either of two shapes, a field several shapes share, and values found along a chain of records. The basics of `one` and `many` are in [language.md](language.md), §6. Every example marked `velle` compiles on its own; one marked `velle refused` is refused with the messages quoted after it, and one marked `velle advisory` compiles with the advisory quoted after it ([language.md](language.md) says how the quotes are written).

## Contents

1. [What the other side is called](#1-what-the-other-side-is-called)
2. [Changing a collection](#2-changing-a-collection)
3. [Deleting a record others point at](#3-deleting-a-record-others-point-at)
4. [Parts that arrive with an act: `with`](#4-parts-that-arrive-with-an-act-with)
5. [A request that is not kept, with its parts](#5-a-request-that-is-not-kept-with-its-parts)
6. [One field for either of two shapes: `or`](#6-one-field-for-either-of-two-shapes-or)
7. [A field several shapes share: `is`](#7-a-field-several-shapes-share-is)
8. [Walks: values along a chain](#8-walks-values-along-a-chain)

## 1. What the other side is called

- `customer: one Customer` on `Invoice` gives `Customer` a collection `invoices`: the declaring shape's name with a lowercase first letter and an `s`. `courses: many Course` on `Student` gives `Course` a collection `students`.
- The inferred side is a view. Rules never assign it and committers never supply it. Both sides can be read.
- When one shape has two fields pointing at the same target, nothing is inferred. Declare views by hand: `outgoing: many Transfer = (Transfer where source == this)`.
- To give an inferred collection your own name, declare the view the same way. Without the `=`, a second stored side is declared, which is refused.
- A `many` set carries no data about each link. A link with data, such as a grade or the date it was made, is a shape of its own with a `one` field to each end.

## 2. Changing a collection

```velle
act Course { title: text }

act Student {
    name: text
    courses: many Course
    tags: many text
}

transient act SetEnrollment { student: one Student, courses: many Course }
transient act Enroll { student: one Student, course: one Course }
transient act Drop { student: one Student, course: one Course }
transient act Retag { student: one Student, tags: many text }

rule ApplyEnrollmentSet when SetEnrollment {
    student.courses = courses
}

rule ApplyEnroll when Enroll {
    student.courses = student.courses + course
}

rule ApplyDrop when Drop {
    student.courses = student.courses - course
}

rule ApplyRetag when Retag {
    student.tags = tags
}
```

- On an act, `many Course` means the committer names existing courses. `many text` means the committer supplies the values. The committer always supplies a `many` field, possibly empty.
- `student.courses = courses` replaces the whole set.
- `+` adds one record or a collection; adding one already there changes nothing. `-` removes; removing one not there changes nothing.
- A collection holds each member once and has no order. A committed collection naming the same record twice is refused at the door.
- `empty` is the empty collection: `tags: many text initially empty`, or `courses: empty` in a `from` block. A collection of specific records cannot be written inline.

**Writing one field on every member.** The collection side of a one-to-many is inferred, so it cannot be assigned. Write the stored field through the collection instead:

```velle
act Customer { name: text }
act Invoice { customer: one Customer, due: Date }
transient act ReassignInvoices { customer: one Customer, invoices: many Invoice }

rule ApplyReassignment when ReassignInvoices {
    invoices.customer = customer
}
```

- The statement writes `customer` once on each invoice in the act's collection. The invoices leave the old customer's `invoices` by themselves.
- A path may go through one `many` and stop: `invoices.customer` is allowed, `invoices.customer.name` is not.
- The compiler prints a contention advisory for this rule: every commit that writes an invoice's customer takes turns. It does not block the spec ([time-and-repetition.md](time-and-repetition.md), §6).
- Inside `for each`, assignments are refused:

```velle refused
act Item { label: text, n: integer }
act Batch { items: many Item, n: integer }

rule Tag when Batch {
    for each item in items {
        item.n = 1
    }
}
```

> [V34] rule 'Tag' assigns 'item.n' inside 'for each' — a block creates records; to write 'n' on every member of 'items', write 'items.n = 1' in the rule, outside the block

## 3. Deleting a record others point at

How a `one` link is declared says what happens when someone asks to delete the record it points at. The product owner picks one answer per link.

```velle
act Seller { name: text }

act Payout { seller: one Seller, amount: integer }

act Listing {
    title: text
    seller: one Seller? initially required
}

transient act CloseAccount { seller: one Seller }

never (CloseAccount where seller is held)

rule ApplyClose when CloseAccount {
    delete seller
}

transient act HandOverAndClose { payout: one Payout, departing: one Seller, successor: one Seller }

never (HandOverAndClose where departing is held)

rule ApplyHandOver when HandOverAndClose {
    payout.seller = successor
    delete departing
}
```

- `Payout.seller: one Seller` is required, so a payout **holds** its seller: the seller cannot be deleted while it points at them.
- `Listing.seller: one Seller? initially required` must name a seller at creation and **lets go**: if the seller is deleted, the listing stays and `seller` reads absent. An optional link and a `many` set let go too.
- `seller is held` asks whether any required link points at the seller. The `never` turns the request away while one does, and the refusal names the payout.
- A `never` is checked when the commit ends, after the rules ran. `ApplyHandOver` moves the payout first, so the request goes through when nothing is left pointing at the departing seller.

The three answers a deleting rule may give:

1. **Turn the request away**: a `never` over the request, as above.
2. **Keep the refusal as a record**: condition the deleting rule on `seller is not held`, and a second rule on `seller is held` creates a refusal record. A scheduled rule with no request uses `this is not held` in its condition.
3. **Delete the pointing record too**, in the same body.

Other rules for `delete`:

- The target is one record by a fixed path, `delete this` or `delete listing`. To delete many, write a rule whose subject is each one.
- A commit may not both assign a field of a record and delete it.
- Nothing is left behind: no tombstone, no `is deleted` test. To remember a deletion, create a record for it, copying the fields you need.
- Delete records of current state, such as a customer or a listing. Keep records of things that happened.

**Refused.** A rule conditioned on `is not held` that also points a required link at the same shape:

```velle refused
act Employee { name: text }
act Shop { manager: one Employee }
transient act ReplaceManager { shop: one Shop, departing: one Employee, successor: one Employee }
shape Refusal { note: text }

rule ApplyReplace when (ReplaceManager where departing is not held) {
    shop.manager = successor
    delete departing
}

rule RefuseReplace when (ReplaceManager where departing is held) {
    Refusal from { note: "held" }
}
```

> [V24] rule 'ApplyReplace' deletes a 'Employee' ('delete departing') on the condition 'departing is not held', and rule 'ApplyReplace' can point 'Shop.manager' at a 'Employee' in the same commit. The condition is read before the rules run, so it cannot see that reference: refuse the request with 'never (ReplaceManager where departing is held)', which is read when the commit ends, and drop the condition

## 4. Parts that arrive with an act: `with`

An order and its lines are placed in one request. The lines do not exist yet, so the request cannot refer to them. The act declares, after its body, which parts arrive with it.

```velle
act Customer { name: text }
act Product { name: text, unitPrice: integer }

act Order {
    customer: one Customer
    placedOn: Date
    total: integer = sum(orderLines, quantity)
} with {
    orderLines with { customizations }
}

shape OrderLine { order: one Order, product: one Product, quantity: integer }
shape Customization { line: one OrderLine, note: text }
shape Reservation { line: one OrderLine, quantity: integer }

never (Order where count(orderLines) == 0)
never (OrderLine where quantity <= 0)

rule ReserveStock when OrderLine {
    Reservation from { line: this, quantity: quantity }
}
```

- `OrderLine.order: one Order` gives `Order` its `orderLines` collection. `with orderLines` names it, so each line arrives inside the order's input as a value, with every stored field except `order`, which the system fills in.
- `orderLines with { customizations }` says each line brings its own parts. Several collections at one level are a comma list: `with orderLines, shipments`.
- The order and its lines land as one commit. `ReserveStock` fires once per line inside the order's transaction, so if any line fails, or a `never` refuses a line, the whole order is refused.
- A `many` field on an act still means references to existing records. Only `with` makes parts arrive as values. Two identical line values make two lines.
- `with` creates records and never updates existing ones.
- A rule on a part may not write a field of the record it arrived under: several lines writing one field of one order is a write conflict. Derive the total on the order, as above.
- An empty part list is allowed. `never (Order where count(orderLines) == 0)` requires at least one.

A `one` field may also be named in `with`. The committer may then supply that record by value, created in the same commit, or name an existing one: `act PeptideBatch { origin: one Origin, ... } with { origin }`.

## 5. A request that is not kept, with its parts

```velle
act Customer { name: text }
act Product { name: text, unitPrice: integer }

transient act PriceRequest { customer: one Customer } with requestedItems

shape RequestedItem { request: one PriceRequest, product: one Product, quantity: integer }
shape Quote { customer: one Customer, quotedOn: timestamp on create }
shape QuoteLine { quote: one Quote, product: one Product, quantity: integer, unitPrice: integer }

never (PriceRequest where count(requestedItems) == 0)

rule QuoteRequest when PriceRequest {
    Quote from { customer: customer } as quote
    for each item in requestedItems {
        QuoteLine from { quote: quote, product: item.product, quantity: item.quantity, unitPrice: item.product.unitPrice }
    }
}
```

- The request and its items are gone when the transaction closes. The quote and its lines remain.
- `RequestedItem.request` points at the transient request. That is the one place a pointer to a transient act is allowed: from a part that arrives with it.
- Each quote line copies the price at that moment, so a later price change does not move the quote.

## 6. One field for either of two shapes: `or`

A refinement that is only `or` over base shapes, with no `where`, is a union. It may be a field's type.

```velle
act Member { name: text }
act Post { author: one Member, body: text }
act Photo { author: one Member, caption: text }

shape Content = Post or Photo {
    commentCount: integer = count(comments)
}

act Comment { target: one Content, author: one Member, body: text }

shape CommentNotice { recipient: one Member, comment: one Comment, excerpt: text }

never (Comment where target is Post and target.author == author)

rule NotifyAuthor when Comment {
    CommentNotice from {
        recipient: if target is Post then target.author else target.author
        comment: this
        excerpt: if target is Post then target.body else target.caption
    }
}
```

- `target: one Content` holds a post or a photo. `Comment.target` gives `comments` to `Post`, `Photo`, and `Content`.
- A union shares only `id` and the collections inferred through fields typed with it. `Post.author` and `Photo.author` are two declarations, so `target.author` is read per shape, after narrowing with `is`. In an `if` over a two-shape union, the `else` branch is the other shape.
- A union's body may declare derived properties only.
- A rule cannot create a union, and `and` across two base shapes is always empty.
- `or` between refinements of two shapes, `shape Notable = LongPost or Captioned`, can be a rule's condition but not a field's type.

**Refused.** Reading a field each shape declares for itself without narrowing:

```velle refused
act Post { body: text, views: integer }
act Photo { caption: text, views: integer }

shape Content = Post or Photo {
    seen: integer = views
}
```

> [F1] 'views' is declared on 'Post' and 'Photo' separately, so it is not a member of 'Content'; read it after narrowing, 'if this is Post then this.views else ...', or declare 'views' once on a shape they take with 'is'

Creating a union:

```velle refused
act Post { body: text }
act Photo { caption: text }
shape Content = Post or Photo
act Ping { note: text }

rule Make when Ping {
    Content from { }
}
```

> [F1] rule 'Make' creates 'Content', which has no records of its own: a record of it is a Post or a Photo; create one of them

## 7. A field several shapes share: `is`

```velle
act Member { name: text }

shape Contribution {
    author: one Member
}

act Post is Contribution { body: text }
act Photo is Contribution { caption: text }

shape Content = Post or Photo

act Comment { target: one Content, body: text }

shape CommentNotice { recipient: one Member, excerpt: text }

rule NotifyAuthor when Comment {
    CommentNotice from { recipient: target.author, excerpt: if target is Post then target.body else target.caption }
}

shape Prolific = Contribution where count(author.contributions) > 10
```

- `author` is declared once on `Contribution`. `act Post is Contribution` takes it as if it were in its own body. A shape may take several: `act SiteVisit is Dated, Sited { ... }`.
- `Contribution` has no records of its own. It reads as the union of its takers, so a rule or refinement over it covers posts and photos, and a field `one Contribution` holds either.
- `author` gives `Member` one collection, `contributions`, holding posts and photos alike.
- `Content = Post or Photo` now shares `author`, so `target.author` reads without narrowing.
- An act cannot be taken by other shapes; declare the shared shape with `shape`.

**Refused.** A field both declared and taken:

```velle refused
shape Dated { due: Date }
act SiteVisit is Dated { due: Date, note: text }
```

> [F3] 'SiteVisit' declares 'due' and takes it from 'Dated'; keep one of the two

A taken shape declared as an act:

```velle refused
act Dated { due: Date }
act SiteVisit is Dated { note: text }
```

> [F3] 'Dated' is declared an act, and 'SiteVisit' is Dated. A shape other shapes take has no records of its own, so it cannot be committed; declare it 'shape Dated'

## 8. Walks: values along a chain

Some values lie along a chain of records of the same kind: everyone above a member in the referral chain, the first parent with a location. A walk follows one relationship step after step and visits each record once, so it ends over any data.

```velle
Tier is one of "basic", "gold"

act Member {
    name: text
    referrer: one Member?
    tier: Tier
    upline: many Member = walk referrer
    foundingReferrer: one Member? = walk referrer until referrer is none
    recruits: many Member = (Member where referrer == this)
    downline: many Member = walk recruits
    closeDownline: many Member = walk recruits for 2 steps
    goldBelow: many Member = (walk recruits where tier == "gold")
    goldCount: integer = count(goldBelow)
}

never (Member where this in upline) tolerates contention
```

- `walk referrer` is every member reached by following `referrer` one or more times, a `many Member`.
- `walk referrer until referrer is none` checks the member first, then each one up the chain, and answers the first where the condition holds, or `none`. Its type is `one Member?`. Inside the condition, bare names are the record being looked at.
- `walk recruits` follows a derived collection. `for 2 steps` cuts it off. A walk's set is filtered in parentheses, `(walk recruits where tier == "gold")`, and counted through a named property; `count(walk recruits where ...)` does not parse.
- The `never` says nobody is in their own upline. A walk reads every record it can reach, so the compiler warns unless the declaration says `tolerates contention` ([time-and-repetition.md](time-and-repetition.md), §6). A `never` over a walk is for a relationship no rule assigns.

**Refused.** `until` over a `many`:

```velle refused
Tier is one of "basic", "gold"

act Member {
    referrer: one Member?
    tier: Tier
    gold: boolean = tier == "gold"
    recruits: many Member = (Member where referrer == this)
    nearestGold: one Member? = walk recruits until gold
}
```

> [F2] 'walk recruits until gold' — 'until' walks a `one`: through the `many` 'recruits' two records at the same distance may both satisfy the condition, and "the nearest" has no single answer; walk a `one`, or declare the set with `walk recruits` and select from it with first(... by ...)
