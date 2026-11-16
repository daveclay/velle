# OQ35 — Can several unrelated shapes share one declaration?

**Status:** opened 2026-09-13 at OQ29's close. Investigated against HEAD on 2026-11-15, six survey files. D1 to D4 decided 2026-11-16, D4 after a reframe the same day. D5 to D7 await Dave. Nothing is removed from the language by any decision here.

**In plain terms:** a product owner says "a site visit or a plot inspection is overdue when its due date has passed" and "any change to an entry touches the entry". Each sentence is one rule over several kinds of record that have a field in common. Velle has no way to declare that field once for all of them, so the author writes the rule once per kind, or turns the records inside out so the common part is the container.

**See:** Reference §9 (`or` across shapes shares only what both operands get from one declaration), Reference §6 (a static union as a field type; a reference supplied by value, the inverted spelling), `checks.md` V17 (the refusal that names OQ35), OQ44 (the union stress pass; its §2 item 10 is why one probe here is accepted by accident), `examples/gallery/gallery.velle` rule `NotifyAuthor` (the branch per operand), `examples/batches/batches.velle` (the inverted hierarchy), `working-docs/examples/survey/17-shared-field-*.velle` (the probes).

---

## 1. What runs today

Checked 2026-11-15 against `compiler/build/libs/compiler.jar` built from commit 4752956. Each probe is a file under `working-docs/examples/survey/`, compiled by that folder's `check.sh`. The survey domain: an entry is one surveyed thing at a site; a rename and a move are two acts that change an entry; a site visit and a plot inspection each have a due date.

A static union is Reference §6's name for `or` over base shapes with no `where`, `shape EntryChange = RenameEntry or MoveEntry`. Its operands are the shapes it names.

| File | What it writes | What the compiler does |
|---|---|---|
| `17-shared-field-union-rule-refused.velle` | `act RenameEntry` and `act MoveEntry` each declare `entry: one Entry`; `shape EntryChange = RenameEntry or MoveEntry`; `rule TouchEntry when EntryChange` creates `Touch from { entry: this.entry }` | refused: `[F1] 'entry' is not a member of 'EntryChange'`. Reference §9's rule: two declarations that agree are not shared. Written as bare `entry`, the message adds "use this.<field> for the subject", which does not help here; that is OQ44's, recorded there |
| `17-shared-field-union-branch-workaround.velle` | the same, with `entry: if this is RenameEntry then this.entry else this.entry` | passes. Reference §9's "a same-named field is read per shape". `examples/gallery/gallery.velle` rule `NotifyAuthor` writes the same branch for `author` and comments that it waits on OQ35 |
| `17-shared-field-union-predicate-accepted.velle` | `shape SiteVisit` and `shape PlotInspection` each declare `due: Date`; `shape Dated = SiteVisit or PlotInspection`; `shape Overdue = Dated where due < today`; `rule RemindOverdue when Overdue on Daily` creates a `Reminder` with `about: one Dated` | passes, although Reference §9 does not admit `due` on `Dated`. The predicate of a refinement over a static union is not name-checked (OQ44 §2 item 10; `examples/batches/output/src/test/kotlin/velle/BatchesProbesTest.kt`, B1). The runtime resolves `due` on each instance, so the rule works because the two declarations happen to agree. When OQ44 closes that gap this file is refused, and the sentence has no direct spelling at all |
| `17-shared-field-per-shape-workaround.velle` | `shape OverdueVisit = SiteVisit where due < today` and `shape OverdueInspection = PlotInspection where due < today`, one rule over each; `Reminder.about: one Dated` keeps the union as the field type | passes. The sentence is written twice, and a third dated shape means a third refinement and a third rule |
| `17-shared-field-inverted-workaround.velle` | one `act EntryChange { entry: one Entry; change: one Change } with change`, `shape Change = Rename or Move`, the kind arriving by value inside the act (Reference §6, "A reference supplied by value"); one rule reads `entry` | passes. This is `examples/batches/batches.velle`'s spelling. It gives up two acts at the trust boundary: one `by` clause and one set of refusals cover both kinds of change, and the kind is read by narrowing `change` rather than by the act's name |
| `17-shared-field-transient-union-refused.velle` | the two acts as `transient act`, the same union and rule | refused: `[V17] 'EntryChange' composes transient act 'RenameEntry' with unrelated shapes — a union with a transient operand is refused until unrelated shapes can share a declaration (Reference §9; OQ35): give the acts one declaration to inherit, or write one rule per act`. `compiler/src/test/kotlin/velle/UnionTest.kt`, "a union with a transient operand is refused until OQ35" |

What the Reference says today, by page:

- Reference §9: `A or B` has only the properties both sides get from one shared declaration. A rule over a union may read `id`, the inverses inferred through fields typed with the union, and a derived body; anything else is read after narrowing by `is <Operand>`.
- Reference §6: a static union may be a field type, and a field typed with it gives every operand the inferred inverse. A refinement with a `where` may not.
- Reference §22 has no item for this construct. The old work item here, to restore one, was checked against HEAD: Reference §9 no longer points at §22, and `checks.md` V17 carries the OQ35 tag, so there is nothing to restore.

So the gap is exactly this: the only thing two unrelated shapes can share is a declaration made on a third thing that is typed with their union. A field one of them declares is never shared, however many of them declare it alike.

## 2. The want, stated apart from any example

Two or more shapes have a field in common because the business says the same thing about each of them. The author wants to write that thing once: one field declaration, and then one refinement and one rule over every shape that has it. The set of shapes that have it should be open, so a new shape joins by saying so, without the rule or the refinement changing. A shape should still be readable from its own declaration, so a reader can tell what fields it has.

The two spellings that work today each break one of those: the per-shape spelling repeats the sentence per shape, and the inverted spelling makes the shared part the record and the kinds its parts, which changes what arrives at the trust boundary.

## 3. What to decide

Nothing below removes a spelling that runs today. Every file in §1 that passes keeps passing.

### D1. Does Velle get a construct that lets several unrelated shapes share one declaration? · Decided 2026-11-16

Does Velle add a declaration that several shapes take, so that one field declared there is one declaration on every shape that takes it, and one refinement or one rule over the declaration covers all of them?

Recommendation: yes. The want in §2 is said in every domain so far (gallery's `author`, batches' quantity, the survey's `entry` and `due`), the two workarounds each lose something a product owner would notice, and Reference §9's own rule already does the right thing once the declaration is one: the gallery's `comments` is shared over `Content` for exactly that reason.

Costs:

- A new declaration form: grammar, parser, model, validator, runtime membership (a record is a member of the shared declaration when its shape takes it), and the generated Kotlin (an interface the taking shapes implement, as a static union is today, Act API).
- Every check that OQ44 lists as keyed on one base shape meets a second kind of many-shape subject. The rulings in §4 say what each does.
- Diagrams need a notation (`diagrams.md`).

The refused file and the proposed spelling, against one spec. The word `is` is D3's, and that `EntryChange` is a plain `shape` is D4's; the construct is what D1 decides.

```
shape Entry {
    name: text unique                     -- the entry's key (Reference §21)
}

shape EntryChange {                       -- proposed: a plain shape; its records are the takers' records (D4)
    entry: one Entry                      -- proposed: one field, declared once; infers Entry.entryChanges (D5)
}

act RenameEntry is EntryChange {          -- proposed: RenameEntry takes EntryChange's declarations
    newName: text                         -- its own field, as today
}

act MoveEntry is EntryChange {            -- proposed: so does MoveEntry
    newSite: text
}

shape Touch {
    entry: one Entry                      -- a reference, as today
    touchedOn: timestamp on create        -- set by the commit (Reference §5)
}

rule TouchEntry when EntryChange {        -- proposed: fires once per RenameEntry and once per MoveEntry
    Touch from { entry: this.entry }      -- proposed: one declaration, so a direct read; today [F1] (17-shared-field-union-rule-refused.velle)
}
```

The same for the dated case, replacing `17-shared-field-per-shape-workaround.velle`'s two refinements and two rules:

```
shape Dated {                             -- proposed: a plain shape; its records are the takers' records (D4)
    due: Date                             -- proposed: one declaration of the due date
}

shape SiteVisit is Dated {                -- proposed: a site visit is dated
    site: one Site
}

shape PlotInspection is Dated {           -- proposed: so is a plot inspection
    site: one Site
    plot: text
}

shape Overdue = Dated where due < today   -- proposed: one refinement; today this read passes by accident (17-shared-field-union-predicate-accepted.velle)

shape Reminder {
    about: one Dated                      -- proposed: legal as a field type, because what takes Dated is fixed by the spec, as a static union's operands are (Reference §6)
    sentOn: Date
}

rule RemindOverdue when Overdue on Daily {        -- proposed: one rule over every dated shape
    Reminder from { about: this, sentOn: today }
}
```

If the answer is no, this OQ settles with `17-shared-field-inverted-workaround.velle` as the served spelling, written up as a pattern in `wiki/Normative/patterns.md`, and `checks.md` V17's message stops promising a construct.

#### Proposed changes

- `wiki/Normative/grammar.md`: the clause by which a shape or act takes another shape's declarations (D3); no new declaration form (D4).
- `compiler/`: the parser, model, validator, runtime, and generated Kotlin for it.
- Reference §9: the section "Treating two shapes as one" gains the shared declaration as the way to share a field, beside the union as the way to share a name.
- `checks.md` V17: the message stops naming OQ35 and names the construct.
- `examples/gallery/gallery.velle`: `author` becomes one declaration and rule `NotifyAuthor` reads it directly.
- `working-docs/examples/survey/17-shared-field-*.velle`: the three workaround and accepted files gain the direct spelling as a seventh file.

**Decided:** yes, 2026-11-16. Velle gets the construct.

### D2. Does the shape name the declaration it takes, or does the union declare what its operands get? · Decided 2026-11-16

Two directions say the same thing. Does a shape name the shared declaration it takes, as `shape SiteVisit is Dated { ... }` with `Dated` declared once elsewhere, or does the union declare the fields its operands get, as `shape Dated = SiteVisit or PlotInspection { due: Date }`?

Recommendation: the shape names it. Three reasons.

- A shape's own declaration then lists everything it has, by following one name. In the union direction, `SiteVisit`'s declaration does not mention `due`, and a reader finds it only by searching for unions that name `SiteVisit`.
- The set is open. A new dated shape says `is Dated` and the refinement and rule cover it. In the union direction, the union's line is edited.
- A shape can take two shared declarations. In the union direction, a shape in two unions with bodies gets fields from two places.

Costs:

- Whether anything marks the shape that is taken, and where it appears in the generated code, is D4.
- Reference §9's union keeps its rule unchanged, so there are two constructs in the "one name for several shapes" family: the closed union, naming its operands and sharing nothing of its own, and the open shared declaration, sharing fields and named by what takes it. The two compose: `RenameEntry or MoveEntry` is a subset of `EntryChange` and exposes `entry`, because both operands have it from one declaration.

#### Proposed changes

- Reference §9: states the two constructs side by side with those two sentences.

**Decided:** the shape names it, 2026-11-16.

### D3. The word by which a shape takes a shared declaration · Decided 2026-11-16

Which word goes between the shape's name and the shared declaration's name? Three candidates, each on the same line of the D1 spec:

```
shape SiteVisit is Dated { ... }          -- reads as the product owner's sentence, "a site visit is dated"
shape SiteVisit has Dated { ... }         -- reads for a field, "a site visit has a due date", but Dated is not a field
shape SiteVisit includes Dated { ... }    -- a code word; says the mechanism
```

Recommendation: `is`. It is the product owner's sentence. `is` is already Velle's membership test (`target is Post`, Reference §10), and "every site visit is Dated" is true of every record of the shape, so the declaration and the test say the same thing with the same word. Several shared declarations are taken with commas: `shape SiteVisit is Dated, Sited { ... }`.

Costs:

- `is` gains a second grammatical position, the shape header. The grammar keeps the two apart by position; nothing is reserved that was not.
- `with` and `=` stay out: `with` is the closure (Reference §6) and `=` the refinement.

Does the shape take a shared declaration with `is`?

#### Proposed changes

- `wiki/Normative/grammar.md`: `shapeDecl` and `actDecl` gain `("is" ShapeName ("," ShapeName)*)?` after the name, before `byClause` on an act.

**Decided:** `is`, 2026-11-16.

### D4. Where a shared shape appears in the generated code · Decided 2026-11-16

A shape that other shapes take is written as a plain `shape`, `shape Dated { due: Date }`, with nothing marking it. It is shared because something says `is Dated`. A keyword marking it, `shared shape`, was proposed and withdrawn on 2026-11-16: the compiler reads sharing off the `is` clauses, and the one question the keyword stood in for is where the shape appears in what the compiler generates.

Does a shared shape appear on the generated surfaces exactly where a static union appears today, and nowhere else?

What a static union gets at HEAD, by surface, from `examples/gallery/gallery.velle`'s `shape Content = Post or Photo`:

- Read API: `read.contents` as a query root and `read.populars` for the refinement over it; `Content` an interface the operands' model objects implement, carrying the union's derived body (`examples/gallery/output/src/main/kotlin/velle/generated/Gallery.kt`).
- GraphQL: a GraphQL union whose type resolver answers from the record's `shape` (the API layer guide).
- Act API: no input class; a `with`-named field typed with the union takes an interface the creating input classes implement, `NewOrigin` in `examples/batches/batches.velle` (the Act API guide).
- Store: nothing. `examples/gallery/output/src/main/kotlin/velle/generated/GalleryStore.kt` has no method for `Content`; the store has one table per shape, and a union-typed field carries the target's shape beside its key (the Store API guide).

Recommendation: yes, the same four answers.

- Read API: `read.dateds`, `read.overdues`, and a `Dated` interface with `due` on it. This is what the construct gives the engineer: one query for everything overdue, typed with the field the rule reads, instead of a `when` over the takers' classes.
- GraphQL: an interface with its fields, GraphQL's own construct for a type with fields that several types implement, where a union has none.
- Act API: `NewDated` where a `with`-named field is typed with it, and never an input class of its own. An act that `is Dated` keeps its own input class with `due` as one of its fields, supplied by the committer like any stored field.
- Store: nothing. The runtime asks for rows of `SiteVisit` and of `PlotInspection`, as it asks per operand today. Whether `due` is a column on each taker's table or one table joined to them is the store's own layout choice, the freedom the Store API already gives a store for captures.

So "a shared shape has no records of its own" is a statement about the generated code, not the database: there is never a `Dated` class, a `dated` table, or a `NewDated` input value, and a plain `Dated` cannot be constructed anywhere. In the spec, the compiler refuses `Dated from { ... }` and `act Dated` once anything takes `Dated`, naming the taker; a rule over `Dated` when nothing takes it is the unfireable-rule error as it stands (`checks.md` V3).

Costs:

- A query over a shared shape fans out per taker in the store, as a union's does, and no store-side method for the shared shape is generated. An engineer who wants one dated table builds it inside their store binding, where the API never sees it.
- A shape with a producing rule that later gains a taker: the producing rule becomes an error naming the taker, and the author chooses which line was wrong.

Does a shared shape appear on the generated surfaces exactly where a static union does today, and nowhere else?

#### Proposed changes

- `wiki/Normative/grammar.md`: no new declaration form; a shape that is taken is an ordinary `shapeDecl`.
- `checks.md`: creating a taken shape, or declaring one as an act, is refused naming the taker; V3 covers a rule over a shape nothing takes.
- Read API guide: a shared shape is an interface on the model objects, with its fields, and a query root, as a static union is.
- API layer guide: a shared shape becomes a GraphQL interface with its fields.
- Act API guide: `New<Shape>` for a shared shape as for a union; an act that takes one carries the shape's fields in its own input class.
- Store API guide: a shared shape has no table and no method; every read is asked per taker.

**Decided:** yes, the same four answers, 2026-11-16. A shared shape appears exactly where a static union does today and nowhere else.

### D5. The inverse of a reference declared in a shared declaration

`EntryChange.entry: one Entry` is one declaration, taken by `RenameEntry` and `MoveEntry`. Does `Entry` get one inferred inverse, `entryChanges`, holding every rename and every move, named from the shared declaration as Reference §6 names an inverse from the declaring shape? Or is no inverse inferred, so an author who wants one declares a view per taking shape, `renames: many RenameEntry = (RenameEntry where entry == this)`?

Recommendation: infer it. Reference §6's rule for a field typed with a union is already "one declaration gives the inverse to everyone", and a field declared once on a shared declaration is one declaration. A per-shape view stays the derived spelling when the author wants one shape's changes.

Costs:

- The inverse's elements are of several shapes, so a read through it narrows per shape as a union-typed read does, and a closure naming it (`act Entry { ... } with entryChanges`) is refused, since a closure edge's parts are of one shape (`checks.md` V22).
- V19's both-sides and collision checks run on the shared declaration's field once, and a taking shape that declares `entry` itself collides with it (Reference §9's same-name rule).

Does `Entry` get `entryChanges`?

#### Proposed changes

- Reference §6: the inverse paragraph names a shared declaration's field as one declaring side.
- `checks.md` V19 and V22: the two costs above, stated.

### D6. May a shared declaration take another?

May `shape Overdueable is Dated { ... }` be written where `Overdueable` is itself taken by other shapes, so that a shape taking `Overdueable` also takes `Dated`? Recommendation: no, refuse it for now. A shape that wants both names both, `shape SiteVisit is Dated, Sited`, and nothing in the three domains in §1 needs a chain. Allowing it brings the diamond, one declaration reached twice by two paths, which Reference §9 says is fine for a shared base, but there is nothing to test it against yet.

Costs:

- An author with a chain writes the list out on each shape.

Is a shape that is taken, and itself takes another, refused?

#### Proposed changes

- `checks.md`: a shape that is taken and whose header has an `is` clause is refused, naming both lines (F3).

### D7. A transient act that takes a shared declaration

`checks.md` V17 refuses a union with a transient operand "until unrelated shapes can share a declaration". Does a rule over a shared declaration fire for a transient act that takes it, so `17-shared-field-transient-union-refused.velle` written with `is EntryChange` passes, and one rule answers both requests?

Recommendation: yes. That is the case V17's message promises. The rule fires once per request inside that request's own commit, as a rule over the act does today, and V18's totality check counts a rule over the shared declaration as answering every transient act that takes it.

Costs:

- A field typed with the shared declaration, `about: one EntryChange`, is refused when any act taking it is transient, naming the field and the act: nothing durable may reference a transient record (V17 as it stands).
- A `never` over the shared declaration refuses at the door of every act that takes it (Reference §21), one prohibition over several acts.

Does a rule over a shared declaration fire for a transient act that takes it?

#### Proposed changes

- `checks.md` V17: the union refusal stays, its message now naming the construct; a field typed with a shared declaration that a transient act takes is refused with a connected diagnostic.
- `checks.md` V18: a rule over a shared declaration answers each transient act that takes it.

## 4. What follows once D1 is yes

Consequences, not decisions. Each is a sentence for the Reference or `checks.md` when this is built.

- A shared shape has no records of its own. Its members are the records of the shapes that take it, so narrowing by `is RenameEntry` over `EntryChange` is exhaustive over the taking shapes, and an `if` over a two-taker declaration narrows its `else` to the other taker, as Reference §10 does for a two-operand union.
- Two shared declarations declaring the same name, taken by one shape, is Reference §9's same-name collision between distinct declarations: an error. A taking shape redeclaring a shared field collides the same way.
- Overlap, for `checks.md` V1, V5, V9, and V27: two shared declarations overlap on the shapes that take both; a shared declaration and a base shape overlap when the shape takes it; distinct base shapes stay disjoint.
- An act that takes a shared declaration carries the shared stored fields in its generated input value as its own (Act API). `initially` and `timestamp` in a shared declaration taken by an act are the V21 errors they would be inline.
- A derived property in a shared declaration reads the shared declaration's own fields, with `this` the taking record. One that needs a taker's own field belongs on the taker, or on a refinement of the shared declaration narrowed by `is <Taker>`.
- A refinement over a shared declaration is an ordinary refinement: `frozen due` names a stored field every member has; `captured` reads at entry. The shared declaration's own body carries declarations only; `frozen` and `captured` there are position errors (F3).
- `for` matching: a field typed with a shared declaration matches an expression of any taker's type; beside a field typed with one taker, V13's two-fields ambiguity applies as it stands.
- Reference §9's closed union and this open declaration compose: `RenameEntry or MoveEntry` exposes what both take from one shared declaration, under §9's unchanged rule.
- When OQ44 §2 item 10 is built, `17-shared-field-union-predicate-accepted.velle` becomes a refused file and the shared declaration is what its message tells the author to write.

## 5. The build

Written when D1 to D7 are decided, gathering each decision's proposed changes in build order.

## 6. Work items

None until D1 to D7 are decided. The old item, to restore a Reference §22 mixin item that Reference §9 pointed at, was closed on 2026-11-15: neither page refers to the other on this topic at HEAD (§1).
