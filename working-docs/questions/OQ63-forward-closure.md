# OQ63 — A record and the record it points at, arriving in one commit

**Status:** open (2026-10-16) — found by the batches pass (`examples/batches/`, finding B2); no design yet. Reframed 2026-10-20: the tree rule is a limitation, not a principle (§3, "Trees"), so the question is how, not whether; and this question now owns the mechanism OQ64's nested creation needs (§5), so it is the one Dave is focusing on.
**In plain terms:** a peptide batch points at its provenance (`origin: one Origin`), and the two are one event in the lab: the batch and where it came from are known together. Today they are two commits, origin first, because the only construct that creates several records in one commit is the act's closure, and a closure creates parts that point *back* at the act. Can a commit also create the record an act's own field points *forward* at, without giving up what the forward pointer buys?
**See:** Reference §6 ("Inline part creation"; "Closures are trees") · Reference §4 (one commit, one statically-shaped closure) · `checks.md` V22 · `examples/batches/batches.velle` (the two-commit spelling, with `UnclaimedOrigin` as its residue) · `examples/batches/output/src/test/kotlin/velle/BatchesProbesTest.kt` (B2: the books rule that would close the gap refuses the origin's own commit)

---

## 1. The want

The batches spec inverts the object-language hierarchy: one `PeptideBatch` shape holds what every batch has, each kind of provenance is a shape of its own, and `origin: one Origin` points at the static union of the three. The pointer is what makes "exactly one kind" structural (Reference §6: a `one` field holds one value). It is also what forces two commits:

```
act Fermentation { strain: one BacteriaStrain, run: one FermenterRun }

act PeptideBatch {
    peptide: one Peptide
    quantityMg: decimal
    origin: one Origin          -- must already exist when the batch is committed
}
```

Between the fermentation's commit and the batch's, the fermentation is on the books with no batch. The spec can show that state (`shape UnclaimedOrigin = Origin where peptideBatches is empty`) and cannot forbid it: `never (Fermentation where peptideBatchs is empty)` is a constraint on a persistent shape, checked at the fermentation's own commit, when no batch can exist yet, so it refuses every fermentation (probe B2).

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

- Whether this is a `with` extension (a forward field named in the clause) or a property of the field's declaration.
- Whether the want is common enough to carry a construct. The gap itself is not in question: a record that must point at another record cannot be committed with it, and no spelling closes the window (Dave, 2026-10-16: the pass is about gaps in Velle, not about whether a domain could live with the window).
- The generated input: a Kotlin sealed type per forward field (`Existing(ref)` / `New(input)`), and how the boundary's type-level refusal reads when the inline value is of a shape outside the union.

## 5. The same mechanism inside a rule

OQ64's nested layout needs the same thing from the other side. A rule creates a container, then creates that container's wells, and the wells must point at the container, which has no `id` until the commit:

```
Container from { design: this, replicate: replicate, position: position } as container
Well from { container: container, ... }
```

Today no rule can do even the two-statement case: a creation statement cannot be named, so a second statement has nothing to refer to (no example does it; `OpenAccountReview` in `examples/membership/membership.velle` and `AskPrescriber` in `examples/pharmacy/pharmacy.velle` each create two unrelated records). Whatever this question settles for a value inside an act's input, a creation statement inside a rule wants the same: a local name that stands for a record before it exists, usable wherever a reference is. Settle the mechanism here; OQ64 keeps the loops and counts.
