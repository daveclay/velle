# OQ29 — Sum types / union shapes

**Status:** open — direction chosen 2026-09-12: extend `or` composition across unrelated base shapes, keeping README §9's property rule unchanged. Implemented 2026-09-13 in the v0 compiler and exercised by `examples/gallery/gallery.velle` (section 7). Section 4's rulings and section 6's two are built but not yet promoted to the README, `grammar.md`, or `checks.md`.
**In plain terms:** can a field point at "either a Post or a Photo", and can a rule react to "any Vehicle, whichever kind", without faking it as one optional field per shape plus an exclusivity invariant?

---

## 1. The two uses

The question surfaced while comparing Velle's predicates to Datalog, where a predicate ranges over anything and unions cost nothing. In Velle every field type and every rule subject is keyed on one base shape, and two distinct wants have been filed under "union". Both are served by the same construct; they are separated here because each has its own example, workaround, and operand rule.

### Use A — a union as a field's target type

The sentence: "a comment is on a post or on a photo." One role, filled by an instance of either shape — what conventional systems call a polymorphic association. The wanted spelling is one field whose target is either shape:

```
shape Post {
    author: one Member
    body: text
}

shape Photo {
    author: one Member
    caption: text
}

shape Comment {
    target: one Post or Photo
    body: text
}
```

Today's workaround is one optional field per target shape plus invariants that exactly one is set:

```
shape Comment {
    post: one Post?
    photo: one Photo?
    body: text
}

never (Comment where post is none and photo is none)
never (Comment where post is some and photo is some)
```

The costs grow with every target shape added: one optional field per target, one coverage invariant, one pairwise exclusion per pair of targets, and every reader branching through every field to find the one that is set. Adding a third target edits the shape, the invariants, and every reader — the author's own spec suffering the effect at a distance README §1 exists to prevent. Under the union the exclusivity is structural rather than checked, the trade README §6 already makes for one-to-many, and adding a target edits one declaration.

What the union gives a reader under README §9's unchanged property rule: `Comment.target` infers a `comments` inverse on both `Post` and `Photo` from one declaration, so `Post where count(comments) > 5` works; `target is Post` narrows to `Post`'s properties; and a property both targets happen to declare — `author` above — needs a branch per operand — `if target is Post then target.author else target.author`, identical branches resolving to two declarations (section 3 says why that reads as it does) — until OQ35's mixin gives both shapes `author` from one declaration.

**When to decompose instead.** The union is for one role filled by several shapes. Where each optional field carries its own business sentence, the fields are two roles and the spelling is one required reference per role, with no union and no invariant. The escalation chain in section 5 is the worked case: "raised from an alert" and "raised after a prior escalation" are two facts about every escalation, not one fact in two costumes.

Operand rule in this position: base shapes only. A refinement operand would put a drifting membership predicate inside a field's type; the three-commit sequence under "One construct, one positional rule" below shows the violation.

### Use B — a union as a rule's subject

The sentence: "any vehicle, whichever kind, can be driven." The wanted spelling is a refinement over unrelated shapes that one rule reacts to, reading the fields the operands have in common:

```
shape Car {
    plate: text
    startedOn: DateTime?
}

shape Bicycle {
    frameNumber: text
    startedOn: DateTime?
}

shape Vehicle = Car or Bicycle

rule DriveVehicle when Vehicle {
    this.startedOn = now      -- intended: startedOn is on every operand, so valid
}
```

Today there is no workaround short of one rule per operand shape, each restating the body.

Operand rule in this position: an operand may be a refinement. `NeedsAttention = OverdueTicket or HighPriority` in `examples/membership/membership.velle` already works this way within one base, and nothing about crossing bases changes it.

### One construct, one positional rule

Both uses are valid at once, and one declaration can serve both: `shape Vehicle = Car or Bicycle` may be a rule's subject and a field's type in the same spec. Nothing about the two uses conflicts. What differs by position is which operands are allowed there. In a rule subject any operand goes, because a subject is re-evaluated at every commit and is supposed to drift. In a field type every operand must be a base shape, because a field type is checked only when the field is written. This is the position rule the language already has — `when OverdueInvoice` is legal, `one OverdueInvoice` is not — applied to each operand of a union instead of to one name. This is not new: a relationship already targets a base shape, never a refinement (README §6; `checks.md` F2), so `target: one PublishedPhoto` cannot be written today. The union does not create the problem; it would reopen it if a refinement could hide inside an operand. The sequence below is why the existing rule exists. Suppose a comment may be on a post or on a photo that is still published, spelled as a field type:

```
shape Post {
    body: text
}

shape Photo {
    caption: text
}

shape Unpublication {
    photo: one Photo
    unpublishedOn: DateTime
}

shape PublishedPhoto = Photo where not exists Unpublication for this

shape Comment {
    target: one Post or PublishedPhoto
    body: text
}
```

Three commits, in order:

1. Photo `P1` is created. No unpublication exists for it, so `P1` is in `PublishedPhoto`.
2. Comment `C1` is created with `target` pointing at `P1`. The type check passes: `P1` is in the union at this commit.
3. An `Unpublication` for `P1` is committed. `P1` leaves `PublishedPhoto`. `C1.target` still points at `P1`, which is now outside `Post or PublishedPhoto`. The commit wrote no field of `C1`, and no rule anywhere assigns `C1.target`.

The language has no good answer at commit 3. Refusing the unpublication would let a field type on `Comment` constrain commits to `Unpublication`, an invariant nobody wrote. Clearing `C1.target` would be a write with no assignment behind it, which README §12's static-path rule forbids. Letting it stand means the declared type is false. A rule subject has none of this trouble: `P1` leaving the refinement at commit 3 is exactly the transition a `when leaving` rule exists to observe. So `Post or PublishedPhoto` is legal as a rule subject and illegal as a field type, while `Post or Photo` is legal in both positions. The check is syntactic — does any operand carry a predicate — and section 4's first ruling states it: the existing F2 refusal extended from a single name to every operand of a union.

## 2. Why extending `or` fits

- **The grammar already parses it.** `refExpr` in `grammar.md` accepts `Car or Bicycle` and `Car or (Bicycle where startedOn is some)` today; only README §9's base-sharing type check rejects them. The change is one rule.
- **The property rule already gives the right answer.** §9 exposes only what both operands inherit from a shared declaration. Across bases there is none, so a cross-base `or` exposes `id` and nothing else — plus the one thing genuinely shared: an inverse inferred through a field typed with the union. `Comment.target: one Post or Photo` puts `comments` on every operand from one declaration, so `Post where exists Comment for this` is legal. Structural sharing never enters; the sharing that is wanted is nominal, and OQ35's mixin supplies it — a declaration several shapes include — with this rule unchanged.
- **`is` needs no new meaning.** `target is Post` is refinement membership where the named set happens to be a base, and narrowing licenses `Post`'s properties exactly as `is OverdueInvoice` does (README §10). After an operand test fails, the else branch narrows to the remaining operands.
- **Disjointness gets cheaper.** Two distinct bases are disjoint by construction — the simplest axiom V9 could have — and V1 and V5 read overlap off it: `Car` overlaps `Vehicle`, `Car` never overlaps `Bicycle`.
- **Entry and exit are unchanged.** Entering a bare union is creation of an instance of any operand; `when leaving Vehicle` is V3's bare-shape error offering `when deleted`.
- **It reads.** "A Vehicle is a Car or a Bicycle" is the sentence a product owner would say, with the word already in the language.

## 3. What a rule over a cross-base union may do

Under the §9 rule, Use B's `DriveVehicle` rule does not compile: `Car.startedOn` and `Bicycle.startedOn` are two separate declarations that happen to agree, not one declaration both operands inherit, so nothing is shared and the direct read `this.startedOn` is refused. What a rule over a cross-base union may do without narrowing: read `id` and inferred inverses, create facts whose fields are typed with the union, count, test existence, delete. Everything else is reachable by narrowing per operand, since `this is Car` licenses `Car`'s properties inside its branch (README §10):

```
shape DriveLog {
    vehicle: one Car or Bicycle
    label: text
    startedOn: DateTime
}

rule StartVehicle when Vehicle {
    this.startedOn = now                          -- error: two declarations, not one shared one
}

rule LogDrive when Vehicle {
    DriveLog from {
        vehicle: this
        label: if this is Car then this.plate else this.frameNumber    -- legal: each branch narrows to one operand
        startedOn: now
    }
}
```

`StartVehicle` stays refused: a write targets one literal static path (README §12), and there is no operand-narrowed spelling of a target — the served spelling is one assigning rule per operand, or the value on a fact as above.

The cost is one branch per operand, and every such expression must be edited whenever an operand is added — the boilerplate OQ35's shared declaration would collapse into one read. Where the operands agree on name and type the spelling degenerates to identical branches — `if this is ChangeEmail then this.member else this.member` (section 6.1) — which reads absurdly and is meant to: the two `this.member` resolve to different declarations, the language refuses to guess that agreeing declarations mean one thing (section 3's structural-sharing rejection, below), and identical branches are the visible symptom of the missing shared declaration. Once OQ35 lands that shape is an advisory in waiting — "every branch reads a same-named, same-typed field; declare it once" — a defined meaning the author probably did not intend to spell this way. The rule-subject use is therefore real and complete, but verbose in proportion to the operand count wherever the operands lack a shared declaration — which is OQ35's construct, and the two compose: a mixin is an open union carrying the shared declaration, cross-base `or` is the closed enumeration, and a union of includers exposes the mixin's fields under this same rule.

Structural sharing (same name, same type on every operand) was considered and rejected: it is the effect-at-a-distance README §1 forbids. `Car.weight` in kilograms and `Bicycle.weight` in grams are both `integer`, so a rule reading `weight` on the union would compile and mean nothing; adding a field to one operand can create a match, renaming one can break every union reader. Sharing must be nominal.

## 4. Rulings the extension forces

Proposed, each owed a home in `checks.md` or `grammar.md` when promoted:

- **Static versus predicate refinements.** A field type must be stable, and a refinement with a `where` anywhere in it drifts. `one Vehicle` is legal only when the named refinement is a pure union of base shapes — a syntactic classification; `one (Bicycle where startedOn is some)` draws a diagnostic saying the type carries a predicate. Inline `one Car or Bicycle` needs a grammar line in `propType`, or the union must be named first.
- **`and` across bases is provably empty.** `Car and Bicycle` has no members by construction; a dead-refinement diagnostic in the A2 family rather than silent acceptance.
- **Unscoped `not`.** `shape X = not Car` is the complement within all shapes; refused unless scoped, as in `Vehicle and not Car`.
- **Bodies.** A cross-base union may carry derived properties only (ruled 2026-09-13, section 6.2): `frozen` has no base to name, and a creation-entered `captured` is dead machinery pointing at `initially` on each operand.
- **Inverses per operand.** A union-typed `one` field infers its inverse on every operand; V19's both-sides and collision checks run against each, and an operand also declaring `comments` collides.
- **`for` matching widens.** README §14 matches a field by type equality. With `target: one Post or Photo` beside `post: one Post`, `Comment for somePost` matches two fields (V13 applies as is); passing a union-typed expression to a field typed with one operand is a partial match with no current rule and should be refused pending narrowing.
- **Comparison typing.** F2's like-typed comparisons become overlapping-typed: `target == this` inside a `Post` inverse view compares a union to one operand.
- **Boundary handles.** A union-typed reference in a generated commit function needs an operand discriminator beside the key, since README §5 allows `id` to be a legacy primary key and two tables can share key values.
- **Transient operands.** As a field type, refused per operand by V17 as it stands — no relationship to a transient shape. As a rule subject, refused until OQ35 lands (ruled 2026-09-13, section 6.1): the diagnostic names the transient operand and prescribes the mixin.
- **Deletion.** V24 is unchanged for a required union field, but `? initially required` on it loses which operand's instance was deleted. Deleting a union subject checks referrers of every operand.
- **Collection roots and contention.** A union name as a collection root means every instance of every operand; a read over it widens the serialization domain to every operand shape, which the A5 advisory should name.

## 5. The escalation chain: the case that decomposes

The example this question was first filed under — an `Escalation` chaining from an `Alert` or from a prior `Escalation` — is not a polymorphic association, and it is kept here as the contrast case for Use A's decomposition criterion.

```
shape Alert {
    message: text
}

shape Escalation {
    alert: one Alert                -- every chain roots at one alert
    prior: one Escalation?          -- absent on the first escalation of a chain
    raisedOn: DateTime
}
```

Every escalation chain roots at one alert, so `alert` is a required reference on every escalation and `prior` is the optional predecessor. Two roles, two fields, no invariant, and `alert` is a direct read on every escalation where the union spelling would have needed a walk up the chain.

That walk is also where the union spelling (`source: one Alert or Escalation`) fails on its own terms: any derived property walking `source` to the root alert is a static cycle, and V14's certificate whitelist has only strict descent on a creation-fixed datum and acyclicity from a spent `never`; `never (Escalation where source == this)` rules out length-one cycles only. A "creation-fixed reference to a pre-existing instance is acyclic" certificate does not exist yet, and V22's refusal of in-closure sibling references is what would make it sound. That belongs to OQ16's certificate calibration, and the decomposed spelling above never needs it.

## 6. Ruled

### 6.1 A union declared over transient acts — ruled 2026-09-13: refused until OQ35

Two things here were already decided by V17 and are not reopened: no field may be typed with a transient shape, so `about: one Post or ChangeEmail` is refused per operand exactly as `about: one ChangeEmail` is today; and no fact may keep a reference to a transient act, so a rule over a union of transient acts cannot record which act fired it. The remaining question was whether the union declaration itself is legal when the operands are transient:

```
expose transient shape ChangeEmail {
    member: one Member
    newEmail: text
}

expose transient shape ChangeName {
    member: one Member
    newName: text
}

shape ProfileChange = ChangeEmail or ChangeName      -- refused for now

shape ProfileTouch {
    member: one Member
    touchedOn: DateTime
}

rule TouchProfile when ProfileChange {
    ProfileTouch from {
        member: if this is ChangeEmail then this.member else this.member
        touchedOn: now
    }
}
```

The declaration is not a cross-act read — the rule fires per act, at that act's own commit — so V17's wording does not refuse it, and the body is legal by per-operand narrowing (section 3). But that body is the whole of what such a union can do: no reference to the act on the fact (V17), no inferred inverses, no `delete this` (V23), and every read a branch per operand that reads absurdly. What the sentence "any profile change touches the profile" actually wants is one `member` declaration both acts include, which is OQ35's mixin (its section 2 uses this example), under which `when MemberChange` reads `member` directly and the union is never written.

**Ruling:** a cross-base `or` with a transient operand is refused until OQ35 lands, with a diagnostic naming the transient operand and prescribing the mixin. Refusing is the smaller commitment: admitting the declaration now would fix the identical-branch spelling as the served form for transient acts, and any V18 answer written over it would have to be rewritten when the mixin arrives. The one use that survived weighing — a single rule or `never` over the union as one V18 answer for several acts — is served by the mixin as well, since a `never` or a rule over `MemberChange` covers every includer.

### 6.2 Bodies on a cross-base union — ruled 2026-09-13: derived properties admitted

The question was the bare union's own body, where entry is creation. `trips` below is never declared: it is the inverse README §6 infers from `Trip.vehicle`, named from the declaring shape, landing on every operand and readable through the union — the one property a cross-base union shares under §9's rule (section 2).

```
shape Car {
    plate: text
}

shape Bicycle {
    frameNumber: text
}

shape Vehicle = Car or Bicycle {
    frozen plate                                   -- error: no base for `frozen` to name; `plate` is Car's alone
    captured registeredOn: DateTime = now          -- dead: entry is creation, so this is `initially now` on each operand
    tripCount: integer = count(trips)              -- the one candidate: `trips` is shared (see Trip.vehicle)
}

shape Trip {
    vehicle: one Vehicle                           -- infers `trips` on Car, on Bicycle, and through Vehicle
    startedOn: DateTime
}
```

The first is refused whatever is decided: `frozen` names stored fields of a base shape, and a cross-base union has none in common. The second is dead machinery, reported as such, since a creation-entered capture is an `initially` clause the operand should carry. The third is well-defined, evaluable on every operand, and needs nothing from OQ35.

**Ruling:** a cross-base union may carry a body of derived properties only. A derived property there reads what the union exposes under §9 — `id` and inferred inverses — or narrows per operand; `frozen` is refused for want of a base, and a creation-entered `captured` is dead machinery pointing at `initially` on each operand. Refusing bodies outright would have had the author write `tripCount` once per operand, restating one formula in several places — the effect-at-a-distance README §1 exists to prevent.

Refinements *over* a union are unaffected either way: `shape BusyVehicle = Vehicle where count(trips) > 3 { captured busySince: Date = today }` is an ordinary refinement whose base happens to be a union, entered by its predicate, and its body works as README §8 says.

## 7. Implemented (2026-09-13)

Built in the v0 compiler; `examples/gallery/gallery.velle` exercises it end to end, with `compiler/src/test/kotlin/velle/UnionTest.kt` and the example's `GalleryFlowTest.kt` as the checks.

- **The model** resolves a refinement to a *set* of base shapes. `baseOf` stays single-valued for ordinary refinements; a cross-base `or` reports every operand. A *static union* — a pure `or` of base shapes with no predicate anywhere — is a legal relationship target (the field-type rule, section 4); a union carrying a predicate draws F2 with a message naming the operand that carries it.
- **Members of a union** are exactly what README §9 says: `id`, and inverses inferred through a field typed with the union, which land on every operand and on the union. Two same-named declarations on different operands are not shared. A union's body admits derived properties only; `frozen`, `captured`, and `undeletable` there are F3 errors (section 6.2, with the capture refused rather than advisory because a creation-entered union has no membership memory for the runtime to hold).
- **A transient operand** is refused with V17 (section 6.1). An inline cross-base condition on a rule must be named first (F2). `Car and Bicycle` is an A2 advisory: provably empty.
- **Narrowing**, README §10's rule applied to unions: `path is Operand` in the governing predicate licenses the operand's own properties on that path in the then-branch of a conditional and to the right of an `and`; the else-branch of a two-operand static union narrows to the other operand. A membership test with no base in common (`author is Photo` on a `Member`) is F2.
- **Reachability, co-firing, and one-writer disjointness** read operand sets: a union rule overlaps each operand's rules, and distinct bases are disjoint by construction.
- **The runtime** evaluates membership per operand, scans every operand for a union subject, accepts a committed reference only when the instance's shape is one the field's type accepts (a type-level refusal at the boundary), and matches `for` creation by acceptance rather than equality.
- **The generated surface** gives a static union a view carrying the shared members, a `shape` accessor naming the operand, and `asPost()`-style narrowing accessors returning null for another operand; each operand's view gains `asContent()` to widen. The store contract needs nothing: `Ref.Persisted` already carries the concrete shape.
- **The queue-key derivation** attributes a narrowed read to every operand that declares the name.

**Residue.** The checks that still key on a single base — V5 freezes, V6 captures, V10 `never` induction, V12 singular proofs, V14 well-foundedness, V15's transition-interference leg — skip a cross-base refinement rather than reasoning per operand: fail-open, to be tightened with OQ16's calibration. Narrowing covers `if`/`and` with a bare `is` atom, not `not (x is A)` or nested forms. The class and state-flow diagrams have no union notation yet. Promotion of sections 4 and 6 to the finalized docs is owed.
