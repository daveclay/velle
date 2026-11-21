# OQ159 — Whether GraphQL follows a stored reference without fetching its record

**Status:** opened 2026-11-20 from the build of OQ153 D4, where it was written as D8; Dave said the same day that it is a separate question. D1 awaits Dave. Nothing built.

**In plain terms:** A table in the console that shows a link to another record, and nothing else about that record, still makes the system fetch the record. For the pharmacy's table of 20 interactions that is four database statements out of six. This question is whether to skip that fetch when nothing asked for needs it.

**See:** `apis/graphql/src/main/kotlin/velle/api/graphql/VelleGraphQL.kt`, `referencedRecord`, where the fetch starts. `apis/json/src/main/kotlin/velle/api/VelleApi.kt`, `read`, which makes it. `examples/pharmacy/app/src/test/kotlin/velle/pharmacy/PharmacyStatementsTest.kt`, which bounds the table at 6 statements. `working-docs/QUESTIONS.md` OQ153, whose D4 removed the runtime's own fetch for the same case, and OQ154, on a row removed outside Velle.

---

## Roster

- **D1**: the decision, in §2.
- **U5**: a clause of the store contract in `wiki/Normative/evaluation.md`, "The universal transaction": nothing writes to the tables but a commit.

## 1. What runs today

The console's table of interactions asks for `drugA { shape key }`. `referencedRecord` in `apis/graphql/src/main/kotlin/velle/api/graphql/VelleGraphQL.kt` answers through `VelleApi.read` in `apis/json/src/main/kotlin/velle/api/VelleApi.kt`, which fetches the `Drug` three times over for reasons of its own:

- `VelleSystem.existsFor` checks that the record exists. That check is needed for a key a caller typed, and is the one OQ153 D4 made unnecessary for a key read out of a stored field.
- `VelleSystem.visibleTo` and `hiddenFields` check what the caller may see. `examples/pharmacy/pharmacy.velle` says `visible to Staff` for the whole spec, which depends on who the caller is and on nothing in the `Drug`. The runtime fetches the `Drug` before looking at the clause.
- `Wire.record` reads the record's shape from the fetched row, where the reference already names it.

So the table sends 6 statements where 2 would do: the list, the actor, and four for the drugs and their ingredients.

The count of 6 is pinned by `PharmacyStatementsTest`, "the console's table of interactions reads no drug's own members to show its key", as 6 or fewer. That 2 would do is my reading of the code, not a measurement.

## 2. What to decide

### D1. GraphQL follows a stored reference without fetching its record, when nothing asked needs the record

Should following a reference in a GraphQL query skip the fetch of the record when all three hold: no rule deletes the record's shape, every rule on who may see the record can be answered from the caller alone, and the query selects only `shape` and `key`?

Recommendation: yes.

Costs:

- This touches visibility. A mistake here shows a caller the key of a record they may not see. The check must be: a clause with a `where` that reads the record means the record is fetched, as today. Only a clause that names a kind of caller and nothing else is answered without the record.
- It leans on OQ153 D4 and on clause U5 of `wiki/Normative/evaluation.md` in the same way: a row removed outside Velle is shown as a reference to a record that is not there.
- A query that selects any member of the drug fetches it, as today. The saving is for a table that shows a link.

#### Proposed changes

- `compiler/src/main/kotlin/velle/Runtime.kt`: a read of whether a stored reference's record is shown to a viewer, which fetches only when a rule deletes the shape or a visibility clause on it reads the record.
- `apis/json/src/main/kotlin/velle/api/VelleApi.kt` and `Wire.kt`: a read for a reference taken from a stored field, apart from the read for a key a caller supplies.
- `apis/graphql/src/main/kotlin/velle/api/graphql/VelleGraphQL.kt`, `referencedRecord` and `referencedRecords`: use it.
- `PharmacyStatementsTest`: the table of interactions sends the list and the actor and nothing else.

## 3. The build

Not written. D1 is open.

## 4. Work items

- [ ] Write §3 once D1 is answered, show it to Dave, and build on his word.
