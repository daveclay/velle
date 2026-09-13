# OQ35 — Cross-shape structural mixins

**Status:** open — post-v0; design sketched 2026-09-13, syntax undecided. README §9 still points at a §22 item that does not exist; restore the item when this promotes.
**In plain terms:** can unrelated shapes share one declaration — a field like `due`, a trait like "overdue", a field like `member` — so that one rule or one refinement covers all of them, without pretending they share a hierarchy?

---

## 1. The want

Two sentences that today need one declaration per shape:

- "A ticket or an invoice is overdue when its due date has passed." README §9 names this as unsolved: `Overdue` over `SupportTicket.due` and `Invoice.due` is two refinements with the same predicate.
- "Any change to a member's profile touches the profile." OQ29's `ChangeEmail` and `ChangeName` each declare `member: one Member`; a union of the two exposes neither, because README §9 shares only what both operands inherit from *one* declaration, and a rule over the union reaches `member` only by a branch per operand that reads absurdly (OQ29 section 3).

Both are the same gap: there is no way to make one declaration part of several shapes.

## 2. The sketch

A **mixin** is a shape fragment other shapes include. It declares properties like any shape body; it has no instances of its own; its member set is every instance of every including shape. Syntax is a placeholder — `includes` here; `with` is taken by the exposure closure (README §6) and `=` by refinements.

```
shape MemberChange {                      -- a mixin: no instances of its own
    member: one Member
}

expose transient shape ChangeEmail includes MemberChange {
    newEmail: text
}

expose transient shape ChangeName includes MemberChange {
    newName: text
}

shape ProfileTouch {
    member: one Member
    touchedOn: DateTime
}

rule TouchProfile when MemberChange {
    ProfileTouch from { member: member, touchedOn: now }     -- one declaration, so a direct read
}
```

Because `member` is one declaration both acts inherit, README §9's existing rule exposes it — through the mixin, and through any union of includers (`ChangeEmail or ChangeName`) — with no change to the rule.

The trait case is the same construct plus a refinement over it:

```
shape Dated {                             -- a mixin
    due: Date
}

shape SupportTicket includes Dated {
    subject: text
}

shape Invoice includes Dated {
    total: decimal
}

shape Overdue = Dated where due < today   -- one refinement, every includer

rule RemindOverdue when Overdue on Daily {
    Reminder from { about: this, sentOn: today }
}

shape Reminder {
    about: one Dated                      -- a mixin is a stable field type: its includers are known whole-spec
    sentOn: Date
}
```

## 3. What a mixin is, in the language's own terms

- **A rule subject and a refinement base.** `when MemberChange` fires once per entrant of any includer; `Dated where due < today` is an ordinary refinement whose base is the mixin. Entry into a bare mixin is creation of an instance of any includer.
- **A stable field type.** `about: one Dated` is legal by the same test as OQ29's field-type rule: the member set is fixed whole-spec by the `includes` declarations, never by a predicate, so it cannot drift (README §6's base-shape rule, `checks.md` F2).
- **An open union.** Its members are whoever opts in. OQ29's cross-base `or` is the closed form — exactly the named operands. The two compose: `ChangeEmail or ChangeName` is a subset of `MemberChange`, and exposes `member` because both operands inherit it from the mixin.
- **Not a base shape.** A mixin cannot be created (`MemberChange from { ... }` is refused), cannot be exposed, and has no inverses of its own beyond those its declarations infer.

## 4. Rulings the construct forces

Proposed; each owed a home when this promotes.

- **Not instantiable.** If `MemberChange` could have instances of its own, the else branch after `is ChangeEmail` would not be `ChangeName`, and narrowing exhaustiveness over includers would die. As a pure fragment, members are exactly the includers, known whole-spec.
- **Collision.** Two mixins each declaring `member`, included by one shape, is README §9's existing same-name collision between distinct declarations — an error. One mixin reached twice (a mixin including a mixin, included by two paths) is one declaration and is fine, as §9 already says for a shared base. An includer redeclaring a mixin's field collides the same way.
- **Inverses.** `MemberChange.member: one Member` infers one `memberChanges` collection on `Member`, spanning every includer, named from the declaring mixin. A per-includer view (`changeEmails: many ChangeEmail = (ChangeEmail where member == this)`) is the derived spelling. V19's both-sides and collision checks run once, on the mixin's declaration.
- **Overlap.** Two mixins overlap exactly on the shapes including both; a mixin and a base overlap iff the base includes it; distinct bases stay disjoint. One static axiom for V1, V5, V9, and V27.
- **Transience.** A mixin included by a transient act is fine as a source of that act's fields. A field typed with the mixin (`about: one Dated`) is refused by V17 if *any* includer is transient — a connected diagnostic naming the field and the transient includer. A rule over a mixin with transient includers is OQ29's 6.1 case.
- **Exposure.** An exposed includer's generated commit function takes the mixin's stored fields as parameters like its own (README §22, "External input"); `initially` and `timestamp` declared in a mixin are V21 errors on an exposed includer exactly as if declared inline.
- **Derived properties in a mixin** read only the mixin's own declarations; `this` is the including instance. A mixin's derived property that needs an includer's field is a refinement over the mixin narrowed by `is <Includer>`, or belongs on the includer.
- **Refinement bodies over a mixin** are ordinary: `frozen due` names a stored field every member has; `captured` reads at the predicate's entry. The bare mixin's own body carries declarations, not refinement members — `frozen`/`captured` there are position errors (F3).
- **Closures.** `expose Member with memberChanges` would name a mixin-inferred inverse whose parts are of several shapes; refused — a closure edge's part shape must be one shape (V22). Per-includer views are the served spelling.
- **`for` matching.** A mixin-typed field matches an expression of any includer's type; beside a field typed with one includer, V13's two-fields ambiguity applies as is.
- **Diagrams.** The class diagram needs a mixin notation; the state-flow diagram treats a refinement over a mixin as one machine spanning includers (`diagrams.md`).

## 5. Relation to OQ29

OQ29 chose to extend `or` across unrelated bases under §9's unchanged property rule, and found the rule-subject use verbose until unrelated shapes can share a declaration. This construct is that sharing. The division of labour: a mixin is the open union with shared fields; cross-base `or` is the closed enumeration with none of its own; every OQ29 ruling about polymorphic *references* (`for` matching, comparison typing, boundary discriminators, deletion, contention) applies to mixin-typed fields unchanged.

## 6. Open

- **Syntax.** `includes` is a placeholder. Candidates must not collide with `with` (closure) or `=` (refinement), and should read as the product-owner sentence: "a support ticket is dated."
- **Inverse naming.** Whether the mixin-inferred inverse is named from the mixin (`memberChanges`) or is not inferred at all, demanding per-includer views (V19's demand-intent posture).
- **Rules and `never` declared over a mixin with an exposed transient includer** as V18 answers — one answer covering several acts.
- **Whether a mixin may include a mixin**, and whether the diamond is then worth allowing or refused outright.
