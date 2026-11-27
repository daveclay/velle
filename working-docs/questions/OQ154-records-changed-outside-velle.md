# OQ154 — What Velle promises, and what it reports, when a record is changed outside Velle

**Status:** opened 2026-11-20 from Dave's aside while deciding OQ153 D4. D1 decided. D2, D3, D4, D5 await Dave. Nothing built; seven probe tests checked in.

**In plain terms:** Someone with access to the database can change or remove a row without going through Velle, for example during maintenance. Velle cannot keep its promises about such a row. This question settles what Velle says about that, and makes Velle name the damaged record plainly when it meets one, where today it either says nothing or fails with a message only a reader of Velle's source could follow.

**See:** `wiki/Normative/evaluation.md`, clause U5 "No side doors", the text this question rewords. `wiki/Normative/GLOSSARY.md`, "universal transaction", which names the clause. `stores/sqlite/src/test/kotlin/velle/sqlite/OutsideChangeProbeTest.kt`, the probes. `working-docs/QUESTIONS.md` OQ153, whose D4 drops a fetch on the strength of this clause. `working-docs/QUESTIONS.md` OQ28, on rows that exist before a field is added.

---

## Roster

- **D1** to **D5**: the decisions, in §2.
- **P1** to **P8**: the probes, each one test in `OutsideChangeProbeTest`, in §1.
- **U4**, **U5**: clauses of the store contract in `wiki/Normative/evaluation.md`, "The universal transaction". U4 says what a commit wrote stays written. U5 says nothing writes to the tables but a commit.
- **An outside change**: a write to a table backing a Velle shape that is not a commit, a seed, or a tick of the running system. Hand-run SQL, a migration script, another service.
- **An unreadable record**: a record whose row the runtime cannot turn into what the spec declares: a required field with no value, a value of another type than the field's, or no row at all where a reference says there is one.

## 1. What runs today

Clause U5 forbids every outside change. It makes no room for maintenance, says the data is "simply wrong", and promises no error.

The probes use this spec, declared in `OutsideChangeProbeTest`:

```velle
-- A drug. `act` makes it a record a caller commits; the store keeps one row per Drug.
act Drug {
    name: text          -- required: the compiler refuses a commit without it
    strength: integer   -- required, and held in an integer column
}

-- An interaction, pointing at one drug.
act Interaction {
    drug: one Drug                -- a required reference: the row holds the Drug's store key
    note: text                    -- required
    drugName: text = drug.name    -- derived: computed on read by following `drug`
}

-- A constraint: the runtime refuses any commit that would leave a Drug with a negative strength.
never (Drug where strength < 0)
```

Each probe commits one `Drug` and one `Interaction`, makes one outside change with SQL, and reads through the runtime.

| Probe | The outside change | What the read answers today | Test name |
|---|---|---|---|
| P1 | `strength` set to -5, which the `never` forbids | -5, no error | "a value that breaks a never is read as it stands, with no error" |
| P2 | `strength` set to the text `strong` | 0, no error | "a text in an integer column is read as zero, with no error" |
| P3 | `name` set to NULL | absent, no error; `drugName` also absent | "a required field emptied is read as absent, with no error" |
| P4 | `Interaction.drug` set to NULL | absent, no error | "a required reference emptied is read as absent, with no error" |
| P5 | the `Drug` row removed, then `Interaction.drug` read | the reference to the `Drug`, no error (absent before OQ153 D4 was built on 2026-11-20) | "a reference to a removed row is still read as a reference, with no error" |
| P6 | the `Drug` row removed, then `Interaction.drugName` read | `VelleRuntimeError`: "missing instance 5", where 5 is a number private to the runtime (before OQ153 D4 was built: "'.' through an absent value (validator narrowing gap)") | "a member derived through a removed row fails naming the runtime's private number for it" |
| P7 | the `Drug` row removed, then the `Drug` read by its own reference | `IllegalArgumentException`: "no instance 13", where 13 is a number private to the runtime | "a removed row read by its own reference fails naming the runtime's private number for it" |
| P8 | the `Drug` row removed on a connection that enforces foreign keys | refused by the database, and the `Drug` still read | "removing a row a reference holds is refused on a connection that enforces foreign keys" |

Since 2026-11-27 the SQL store declares a foreign key on each reference to one shape (`wiki/Guides/Store-API.md`, "Connecting a system to a store"), so P5 to P7 remove the row with foreign keys turned off, as a connection that does not enforce them or a database created before them can. Of P1 to P7, five say nothing. The two that fail name neither the shape nor the store key, and P6 does not name the field. P2 is specific to SQLite, whose columns accept a value of any type; PostgreSQL refuses that write.

## 2. What to decide

### D1. Velle promises nothing about a record changed outside Velle, and checks its rules only when an act or a tick changes state · Decided 2026-11-20

Does clause U5 stop forbidding outside changes and instead say what Velle does not promise once one is made?

Recommendation: yes, in two parts, because an outside change has one of two results and only the first can be reported.

- An unreadable record raises an error that names it (D2 to D5 decide which cases, and when).
- A record that is readable but breaks the spec is not detected. P1 is the example. So is a removed row that a rule used as its memory that something was done: clause U4's example is a `DepositApplication` row, whose removal makes the hourly sweep apply the deposit again.

Costs:

- What the compiler proves is unchanged, and is now stated as conditional: every guarantee holds for state reached through commits and ticks only. The table "What each clause carries" in `evaluation.md` already lists what stops being true.
- A product owner reading the spec can no longer take a `never` as a statement about every row in the database, only about every row Velle wrote. This was true before; the wiki now says it.
- It leans on the principle that a system's state changes only when something external happens to it, an act or a tick. An outside change is neither, so no rule answers for it.
- A request in flight when the change is made may fail or answer inconsistently. Nothing is built to prevent that.

#### Proposed changes

- `wiki/Normative/evaluation.md`, clause U5: retitled "U5 — Velle answers only for its own commits". The guarantee sentence is kept. "In practice" is rewritten to say: reading the tables with your own SQL is always safe; an outside change is the engineer's to make and the engineer's to answer for; after one, an unreadable record raises an error naming the shape, the store key, the field, and what is wrong, a readable record that breaks the spec is not detected, and a request in flight may fail; outside data that should be checked enters as a commit.
- `wiki/Normative/evaluation.md`, the row for U5 in "What each clause carries", and the "Refusal handling" bullet: the clause's new title.
- `wiki/Normative/GLOSSARY.md`, "universal transaction": "no side doors" becomes the new title.

**Decided:** yes, 2026-11-20. Dave proposed it: "it can't promise consistency if changes are made to records outside of velle, and that Velle's validations happen from state changes coming from acts only. If records end up being inconsistent or invalid by the velle spec's definition and come in as reads, the engineer should expect runtime errors that they will need to manually fix." He agreed the same day that only an unreadable record can raise an error and a readable record that breaks the spec is not detected. The wording under "Proposed changes" is mine and he has not read it.

### D2. The two failures that exist today name the record, the field, and what is wrong

Should P6 and P7 fail with one error type that carries the shape, the store key, the field where there is one, and a sentence saying the record is missing from the store and was probably removed outside Velle?

Recommendation: yes. For P7: "Drug 1 is not in the store. No rule in the spec deletes a Drug, so the row was removed outside Velle." For P6: "Interaction 1: `drugName` reads `drug`, which points at Drug 1, and Drug 1 is not in the store."

Costs:

- None to what Velle proves or a product owner can say.
- The message "validator narrowing gap" in P6 also covers a real compiler fault, a `.` the validator should have refused. The runtime has to tell the two apart: a missing row behind a reference the row holds is an outside change, anything else is still the compiler's fault and keeps saying so.
- For a shape some rule does delete, a reference to a deleted record still reads as absent and raises nothing, as today.

#### Proposed changes

- `compiler/src/main/kotlin/velle/Runtime.kt`: a `VelleRuntimeError` subclass carrying shape, store key, field, and reason; thrown where "no instance" is thrown at lines 2371, 2451, and 2456, and where a member is read through a reference whose row is missing.
- `apis/json` and `apis/graphql`: the error reaches the caller with those four parts, as the engineer-facing errors there already do.
- `OutsideChangeProbeTest` P6 and P7: rewritten to the new messages.
- `wiki/Guides/Read-API.md`: the error and when it is raised.

### D3. A required field with no value raises the error when it is read

Should reading a member the spec declares required, whose row holds no value, raise D2's error, where today it reads as absent (P3 and P4)?

Recommendation: yes, at the moment that member is read, not when the row is loaded. The record still appears in a list, so the engineer can find it, and only the damaged member fails.

Costs:

- It leans on the principle that a wrong state which cannot be represented beats one that is checked, from the other side: the type says the value is there, so a caller of the generated read API gets a non-null Kotlin type. Today P3 hands that caller a null in a non-null position, which fails later and elsewhere.
- A field added to a shape that already has rows is this same case: the old rows hold no value. OQ28 owns how such rows are filled. Until it is settled, this error is what an engineer sees after adding a required field, and the message must say that as the second possible cause.
- One comparison per member read. No fetch.

#### Proposed changes

- `compiler/src/main/kotlin/velle/Runtime.kt` or the evaluator's member read: a stored member whose type is not optional and whose value is absent raises D2's error.
- `OutsideChangeProbeTest` P3 and P4: rewritten to expect the error.
- `working-docs/questions/OQ28-bootstrap-backfill.md`: a pointer to this decision.

### D4. A value of another type than its field raises the error, on every store

Should the SQL store refuse to read a value whose type is not the column's, where today the SQLite store reads the text `strong` as the integer 0 (P2)?

Recommendation: yes. P2 is the worst of the seven: a wrong number, silently, that rules then compute with.

Costs:

- Only the SQLite store can hold such a value. The fix is in how that store reads a number, and the conformance tests in `stores/sql/src/testFixtures/kotlin/velle/sql/SqlStoreConformance.kt` gain the case so any later store with loose columns is held to it.
- One type check per value read on SQLite.

#### Proposed changes

- `stores/sqlite` or `stores/sql/src/main/kotlin/velle/sql/SqlStore.kt`, the row reader: a value the column's storage class cannot hold raises D2's error.
- `SqlStoreConformance`: a test for it, skipped by a dialect whose columns refuse the write.
- `OutsideChangeProbeTest` P2: rewritten to expect the error.

### D5. A reference to a removed record of a shape no rule deletes is not checked when it is read

When `Interaction.drug` points at a `Drug` row that is gone, and no rule deletes a `Drug`, should the runtime show the reference as it stands and raise D2's error only when something reads through it, where today it reads as absent (P5)?

Recommendation: yes. This is OQ153 D4 seen from this side, and one answer serves both. Reading absent hides the damage. Checking on every read of the reference is the fetch OQ153 D4 removes.

Costs:

- A table that shows only the reference shows a link to a record that is not there. The error comes when the link is followed.
- An engineer who wants every such reference found has to run a check of their own. Whether Velle offers one, a pass over the store that reports every unreadable record, is not asked here.

#### Proposed changes

- None of its own: OQ153 D4's change to `normalizeRead`, plus D2's error.
- `OutsideChangeProbeTest` P5: rewritten to expect the reference, and the error on reading through it.

## 3. The build

Not written. D2 to D5 are open.

## 4. Work items

- [ ] Write §3 once D2 to D5 are answered, show it to Dave, and build on his word.
- [ ] Find how `apis/json` and `apis/graphql` render a `VelleRuntimeError` to a caller today, with a test, before D2 is built.
