# OQ63 — A record and the record it points at, arriving in one commit

**Status:** open (2026-10-16) — found by the batches pass (`examples/batches/`, finding B2); no design yet. Reframed 2026-10-20: the tree rule is a limitation, not a principle (§3, "Trees"), so the question is how, not whether; and this question now owns the mechanism OQ64's nested creation needs (§5), so it is the one Dave is focusing on.
**In plain terms:** a peptide batch points at its provenance (`origin: one Origin`), and the two are one event in the lab: the batch and where it came from are known together. Today they are two commits, origin first, because the only construct that creates several records in one commit is the act's closure, and a closure creates parts that point *back* at the act. Can a commit also create the record an act's own field points *forward* at, without giving up what the forward pointer buys?
**See:** Reference §6 ("Inline part creation"; "Closures are trees") · Reference §4 (one commit, one statically-shaped closure) · `checks.md` V22 · `examples/batches/batches.velle` (the two-commit spelling, with `UnclaimedOrigin` as its residue) · `examples/batches/output/src/test/kotlin/velle/BatchesProbesTest.kt` (B2: the books rule that would close the gap refuses the origin's own commit)

---

## 1. The want

The business: a lab keeps peptides in batches. Every batch came from one of three places. It was fermented in-house from a bacteria strain, it was ordered from an outside vendor, or it was mixed from other batches already on the shelf. Where a batch came from is its *origin*, and a batch has exactly one.

The spec, from `examples/batches/batches.velle`, cut down to the records involved:

```
act Peptide { code: text }
act BacteriaStrain { name: text }
act FermenterRun { vessel: text, startedOn: Date }
act Vendor { name: text }

-- The three kinds of origin. Each is a record of its own, holding only what that kind knows.
act Fermentation  { strain: one BacteriaStrain, run: one FermenterRun }
act ExternalOrder { vendor: one Vendor, orderItem: text }
act Composition   { mixedOn: Date } with components

-- One name for "any of the three", so a field can point at whichever one applies.
shape Origin = Fermentation or ExternalOrder or Composition {
    peptideBatches: many PeptideBatch = (PeptideBatch where origin == this)
}

-- The batch. It points at its origin.
act PeptideBatch {
    peptide: one Peptide
    quantityMg: decimal
    origin: one Origin          -- must already exist when the batch is committed
}
```

In order:

- `Fermentation`, `ExternalOrder`, and `Composition` are the three kinds of origin. They share nothing but the fact that a batch can come from them.
- `Origin` is a static union: a name that means "a Fermentation, or an ExternalOrder, or a Composition" (Reference §9). Its body declares `peptideBatches`, the batches that point at this origin.
- `PeptideBatch.origin: one Origin` is the pointer. A `one` field holds exactly one value, so "a batch has exactly one origin" needs no rule; it is how the field works (Reference §6).

The pointer is what forces two commits. The batch's input must name an origin that exists, so the lab commits the fermentation first and the batch second, even though in the lab they are one event: the fermenter run finished and produced this batch. Between the two commits the fermentation is on the books with no batch. The spec can show that state (`shape UnclaimedOrigin = Origin where peptideBatches is empty`) and cannot forbid it: `never (Fermentation where peptideBatchs is empty)` is checked at the fermentation's own commit, when no batch can exist yet, so it refuses every fermentation (probe B2).

## 2. The two spellings available today, and what each gives up

- **Two commits, forward pointer.** What the example does. Keeps exclusivity structural. Costs: the unclaimed state exists and is observable; a caller that fails between the two commits leaves an orphan the business never meant; the batch's refusals (`never (PeptideBatch where count(origin.peptideBatches) > 1)`, the one-peptide rule) fire at the second commit, so a refused batch leaves the origin behind (`BatchesFlowTest`, "a mixture is of one peptide").
- **One commit, pointer reversed.** `Fermentation.batch: one PeptideBatch` and `act PeptideBatch { ... } with fermentations, externalOrders, compositions`. One closure, no unclaimed state. Costs: exclusivity degrades from a `one` field to a checked `never` over three inferred inverses ("exactly one of the three collections has one member"), which is the trade Reference §6 names as the reason the one-to-many has one spelling; the input type carries three optional part collections where the domain has one required choice; and `kind`, `Fermented`, and every narrowing become reads through collections instead of through a field.

Neither is the sentence the owner said: "a batch arrives with its provenance, and has exactly one."

## 3. What a construct would have to settle

- **Shape of the input.** A forward edge in a closure means the act's input carries, for the field, *either* a reference to an existing record *or* an inline value creating one. For a union-typed field the inline value is one of three input types. Reference §6's "references-always" for act fields would gain a declared exception, as `with` already is for inverse edges.
- **Trees.** Reference §6's rule that a part points only at the record it arrived under was not a design goal. Dave (2026-10-20): at the time there was no worked-out way to let a value in the input refer to another value in the same input, so the rule was stated as a limit. Do not defend it. A forward edge is still a tree edge (the pointed-at record is created once, by the one field pointing at it), so the tree property survives; what changes is the direction of the back-reference the language populates — none is needed, the field itself is the edge.
- **Which commit the pointed-at record "is".** Today an inline part is an ordinary instance after the commit. A forward-created origin would be one too; the trigger analyses (`when Fermentation`) fire once at the closure's commit as a part does now (Reference §6, the closure's entrants).
- **Prohibitions.** `never (Fermentation where peptideBatchs is empty)` becomes satisfiable at the closure's commit, since the batch lands in the same commit; the door check (Reference §21) sees both.
- **Interaction with OQ43.** A transient act with a closure is refused today; a forward-created record under a transient act would be the materialize-then-decide pattern with the materialization inline.

## 4. Open

- ~~Whether this is a `with` extension (a forward field named in the clause) or a property of the field's declaration.~~ Proposed as the `with` extension, §6 (2026-10-20).
- ~~Whether the want is common enough to carry a construct.~~ Yes (Dave, 2026-10-20). The gap itself was never in question: a record that must point at another record cannot be committed with it, and no spelling closes the window (Dave, 2026-10-16: the pass is about gaps in Velle, not about whether a domain could live with the window).
- ~~The generated input: a Kotlin sealed type per forward field (`Existing(ref)` / `New(input)`), and how the boundary's type-level refusal reads when the inline value is of a shape outside the union.~~ Proposed in §6.

## 5. The same mechanism inside a rule

OQ64's nested layout needs the same thing from the other side. A rule creates a container, then creates that container's wells, and the wells must point at the container, which has no `id` until the commit:

```
Container from { design: this, replicate: replicate, position: position } as container
Well from { container: container, ... }
```

Today no rule can do even the two-statement case: a creation statement cannot be named, so a second statement has nothing to refer to (no example does it; `OpenAccountReview` in `examples/membership/membership.velle` and `AskPrescriber` in `examples/pharmacy/pharmacy.velle` each create two unrelated records). Whatever this question settles for a value inside an act's input, a creation statement inside a rule wants the same: a local name that stands for a record before it exists, usable wherever a reference is. Settle the mechanism here; OQ64 keeps the loops and counts.

## 6. Proposal (2026-10-20, read right by Dave)

Two constructs, settled in this order. The first closes this question's own example and needs no name. The second is what OQ64's Candidate A waits on.

### 6.1 The forward edge is declared in `with`

Today `with` names inverse collections: parts that point back at the act. Let it also name a `one` field, meaning the field's value may arrive inline:

```
act PeptideBatch {
    peptide: one Peptide
    quantityMg: decimal
    origin: one Origin
} with origin
```

It reads as the business sentence: "a batch arrives with its origin." The compiler tells the two cases apart by what the name resolves to on the act. An inverse collection (or a recognized-form view) means parts, as today. A `one` field means a forward edge.

- The pointed-at record is created once, by the one field pointing at it, so the closure is still a tree and V22's reading of the closure from the declaration is unchanged.
- The batch and its origin land or are refused as one. `never (Fermentation where peptideBatchs is empty)` becomes satisfiable at the closure's commit, since the door check sees both (Reference §21). `UnclaimedOrigin` has no members on this route.
- The forward value is an ordinary instance after the commit, and `when Fermentation` fires once for it in the batch's transaction, as a part's rule does (Reference §6, the closure's entrants).
- A forward field whose type is a base shape works the same way with one input type instead of a choice among operands.
- Nesting composes: the forward value's own `with` clause applies to it (`with { origin with { components } }` if a composition arrives with its components), read against the value's shape as nested parts are today.

**The generated input.** For a forward field the input carries a choice, a handle to an existing record or a value to create. For a union-typed field the value is one of the union's input types.

```kotlin
origin: OriginArrival

sealed interface OriginArrival {
    data class Existing(val ref: OriginRef) : OriginArrival
    data class New(val value: OriginInput) : OriginArrival   // FermentationInput | ExternalOrderInput | CompositionInput
}
```

A value of a shape outside the union has no type here, so the client cannot write it and the boundary never sees it. The refusal is the compiler's, in the client's language, not a runtime rejection.

**Checks.** V22 gains the forward case: a `with` entry that resolves to a `one` field on the enclosing level. An entry that resolves to anything else keeps today's error. A `transient act` keeps today's refusal of `with` (OQ43). The one-writer analysis (V1) treats the forward value as an entrant, as it treats a part.

**What this does not need.** No name mechanism. The origin value sits inside the batch's input at the field, so nothing refers to it. The batches case is closed by 6.1 alone.

### 6.2 A name for a record that has no `id` yet

Split out as OQ65 (2026-10-20): `as <name>` on a creation statement in a rule, and a label on a nested value in a client request, with an example of each. Settle it after 6.1 is built.
