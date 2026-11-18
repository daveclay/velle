# OQ143 — may a property declared required be computed through a selection that may match nothing?

**Status:** opened 2026-11-17 from a stress pass of a domain spec. Investigated 2026-11-18. D1 to D6 decided 2026-11-18. The build section is written and awaits Dave's look before building.

**In plain terms:** A spec can say "the key is always there" about a value it gets by picking the first matching row. When no row matches there is no key, and today nothing notices: the compiler accepts the spec, and the running system hands rules a key that is not there.

**See:** Reference §7 "The declared type" (the rule that lets a selection into a required declaration); Reference §10 "Reaching through relationships" (the rest of the path is absent when a selection matched nothing); Reference §14 "A required field given an absent value" (the runtime refusal in a `from` block); `checks.md` F2 and V11; `patterns.md` "An act that names a record by its values" (the lookup that relies on §14); `working-docs/examples/survey/21-*.velle`; `compiler/src/test/kotlin/velle/RequiredThroughSelectionProbeTest.kt`.

---

## 1. What runs today

Two words are kept apart in this file. A value is **absent by declaration** when the spec's own types say it may be missing: a field declared with `?`, a `none`, a `?.`. A value is **absent by data** when the types say nothing and the records decide: `first`, `latest`, and `(Shape for expr)` when no record matches, `max` and `min` over an empty collection, a walk with `until` that never finds its record.

A **selection** here is `first(...)`, `latest(...)`, or `(Shape for expr)`.

The rule today (Reference §7; `checks.md` F2; `ExprTypes.absenceDeclared` in `compiler/src/main/kotlin/velle/ExprTypes.kt`): a required declaration refuses a value absent by declaration, and accepts a selection, whose absence is "left to the runtime". The cases below were compiled against HEAD `bd391b8` on 2026-11-18 with `working-docs/examples/survey/check.sh`.

### Compiler cases

| Case | Survey file under `working-docs/examples/survey/` | What it spells | Compiler today |
|---|---|---|---|
| C1 | `21-first-into-required-by-data.velle` | `keyFromFirst: text = first(upload.rows by key).key`, `key` required | accepted, as Reference §7 says |
| C2 | `21-first-into-required-accepted.velle` | `keyFromFirst: text = first(upload.rows by newKey).newKey`, `newKey: text?` | accepted, though the field is absent by declaration |
| C3 | `21-first-into-required-from-accepted.velle` | `Kept from { label: first(upload.rows by key).newKey }`, `label: text`, `newKey: text?` | accepted; the stored twin `label: row.newKey` is refused by F2 |
| C4 | `21-first-into-required-optional-step-accepted.velle` | `first(upload.rows by key).parent.name`, `parent: one Parent?` | accepted; the stored twin `row.parent.name` is refused by V11 |
| C5 | `21-first-into-required-qdot-refused.velle` | C1 with `?.` after the selection | refused by F2, though Reference §10 gives `.` and `?.` one meaning here |
| C6 | `21-first-into-required-untyped-refused.velle` | C1 with the type left out, then `keyAgain: text = keyFromFirst` | the untyped property is `text?`, and `keyAgain` is refused by F2 |
| C7 | `21-max-into-required-refused.velle` | `best: integer = max(scores)` | refused by F2, though the absence is by data |
| C8 | `21-max-into-required-guarded-refused.velle` | `best: integer = if scores is empty then 0 else max(scores)` | refused by F2, though the empty case is answered |
| C9 | `21-walk-until-into-required-refused.velle` | `topKind: one Kind = walk parent until top` | refused by F2, though `checks.md` F2 lists it as left to the runtime |
| C10 | `12-declared-type-absent-in-required-refused.velle` | `parent: text = parentName`, `parentName: text?` | refused by F2, the plain case |

What the cases show:

- The selection is the only source of absence by data that a required declaration accepts. `max` (C7) and a walk with `until` (C9) are refused.
- Stating the type changes the answer. The compiler itself reads `text?` off the expression (C6), and accepts the author writing `text` over it (C1).
- A selection in front of a path switches off two checks that have nothing to do with the selection: the optional field at the end (C2, C3) and the optional step in the middle (C4).

### Runtime cases

`RequiredThroughSelectionProbeTest` runs the C1 and C3 specs. Each line is a test in that file.

| Case | Test name | What the runtime does |
|---|---|---|
| R1 | `a required derived property over a selection that matches nothing is read` | the act commits; reading `keyFromFirst` gives nothing, with no failure |
| R2 | `a rule condition compares a required derived property that is absent` | `keyFromFirst != "PLOT-1"` holds on the missing key and the rule fires |
| R3 | `a rule stores a required derived property that is absent` | refused, `'Kept.label' is required and 'keyFromFirst' is absent: the selection matched nothing`; the first refusal is at the store, not at the property |
| R4 | `a rule stores an optional field read off a selected record that exists` | refused with `the selection matched nothing`, though a row matched and its `newKey` was absent |

What the cases show:

- Reference §7 says the runtime "refuses at the read". It does not (R1). The only runtime check is the one in a `from` block (`Runtime.kt`, the `is required and` failure), so a required derived property is never checked anywhere.
- A rule can act on the missing value (R2). The declaration `text` told every reader no test was needed.
- The `from` block refusal of Reference §14 works (R3, and `LookupProbeTest`), but its sentence is wrong when the selection matched and the field was absent (R4).

### Where the examples stand

Required properties computed through a selection in the checked-in examples, found by grep on 2026-11-18. Each answers the empty case, in a spelling the compiler does not read today:

- `examples/peptides/peptides.velle`, `FieldPlot.yieldKgPerHa`: `if harvests is empty then 0.0 else latest(harvests by harvestedOn).yieldKgPerHa`.
- `examples/loyalty/loyalty.velle`, `streak`: `if exists DuesPayment for this then latest(DuesPayment for this by paidOn).streakAfter else 0`.
- `examples/payments/payments.velle`, `CompletedAttempt.outcome`: the refinement says `where exists ChargeResponse for this`, the property says `latest(ChargeResponse where attempt == this by respondedOn).outcome`. The test and the selection spell the collection two ways.
- `examples/membership/membership.velle`, `ClosedTicket.closedBy`: `where exists TicketClosure for this`, then `latest(TicketClosure where ticket == this by closedOn).closedBy`. Two spellings again.

## 2. What to decide

Roster: D6 adds an advisory and refuses nothing. D1 and D4 make the compiler stop accepting something. D2 and D5 make it accept something it refuses today. D3 keeps today's behaviour under its recommended answer A, and stops accepting something under its answers B and C.

### D1. A required derived or captured property is refused when it is computed through a selection that may match nothing · Decided 2026-11-18

Does the compiler refuse a derived or captured property declared required whose expression is a selection, or reads through one, when nothing shows the selection matched?

Recommendation: yes. The author declares it `text?`, or answers the empty case (D2).

What runs today and is lost: C1 compiles and, when no row matches, hands readers a missing value (R1, R2). No working behaviour is lost, because nothing at runtime ever enforced the declaration.

Costs:

- This reverses the rule Reference §7 states, that absence by data is left to the runtime. For a derived property there is no runtime check to leave it to (R1).
- It leans on "a design in which a wrong state cannot be represented beats one in which it is checked". Today `text` can hold nothing.
- It makes selections agree with `max` (C7) and a walk with `until` (C9), which are already refused.
- A product owner who says "the plot's yield is the latest harvest's" must also say what it is before any harvest, or say the yield may be missing. That is a true statement about the domain that the spec skips today.
- Without D2, the four example properties in §1 are refused although each answers the empty case.

```velle
act Upload { note: text? } with rows     -- an upload arrives with its rows as parts
shape Row {
  upload: one Upload                     -- the upload this row is a part of
  key: text                              -- required: every row has one
}
act Other {
  upload: one Upload                     -- required: the upload this act names
  keyFromFirst: text = first(upload.rows by key).key
                                         -- today: accepted (C1). Proposed: refused by F2,
                                         -- "'Other.keyFromFirst' is declared a text and may be
                                         -- absent", since the upload may have no rows
  keyOrNone: text? = first(upload.rows by key).key
                                         -- accepted today and after: the declaration admits
                                         -- the upload with no rows
}
```

Source: `working-docs/examples/survey/21-first-into-required-by-data.velle`, property `keyFromFirst`.

#### Proposed changes

- `compiler/src/main/kotlin/velle/ExprTypes.kt`, `absenceDeclared`: a selection counts as a value that may be absent when the site is a derived or captured property.
- `wiki/Reference/Reference-07-Derived-properties.md`: the sentence that a selection is "absent by data, which the runtime refuses at the read" is replaced by the refusal.
- `wiki/Normative/checks.md` F2: the sentence "A selection that matches nothing ... is absent by data and left to the runtime" is narrowed to the `from` block and the assignment (D3), and loses the walk with `until`, which is refused already (C9).
- `working-docs/examples/survey/21-first-into-required-by-data.velle` is renamed `-refused`.
- `compiler/src/test/resources/diagnostics/`: one refused spec for the message.

**Decided:** yes (Dave, 2026-11-18).

### D2. A test that the collection is not empty shows a selection over that collection present · Decided 2026-11-18

When a required property answers the empty case first, `if harvests is empty then 0.0 else latest(harvests by harvestedOn).yieldKgPerHa`, does the compiler read the selection as present in the branch where the collection is not empty?

Recommendation: yes, for a test and a selection that spell the collection the same way: `C is empty` governing the other branch of an `if`, and `exists C` in the `if` or in the enclosing refinement's `where`. The same test shows `max(C)` and `min(C)` present, which fixes C8.

Costs:

- The compiler proves presence by matching spellings, as it does for `is some` on a path (Reference §10). A test spelled differently from the selection proves nothing, and the author sees a refusal on a property that is in fact safe. `CompletedAttempt.outcome` in `examples/payments/payments.velle` and `ClosedTicket.closedBy` in `examples/membership/membership.velle` are in that position and are rewritten to one spelling, `latest(ChargeResponse for this by respondedOn)`.
- A selection with its own filter, `latest(priceChanges where effectiveOn <= today by effectiveOn)`, is shown present only by a test over the same filtered collection.
- Without this decision, D1 forces `?` onto every property in §1 "Where the examples stand", and every reader of them tests `is some` for a case the property already answered.

```velle
act FieldPlot {
  block: integer                         -- required
  harvests: many Harvest = (Harvest where plot == this)
                                         -- the plot's harvests, possibly none
  yieldKgPerHa: decimal =
    if harvests is empty then 0.0        -- the empty case is answered here
    else latest(harvests by harvestedOn).yieldKgPerHa
                                         -- proposed: read as present, because this branch
                                         -- runs only when `harvests` is not empty.
                                         -- Under D1 alone: refused
}
act Harvest {
  plot: one FieldPlot                    -- required: the plot harvested
  harvestedOn: Date                      -- required: orders the selection
  yieldKgPerHa: decimal                  -- required: the value read off the latest harvest
}
```

Source: `examples/peptides/peptides.velle`, act `FieldPlot`, property `yieldKgPerHa`.

#### Proposed changes

- `compiler/src/main/kotlin/velle/ExprTypes.kt`: the narrowing an `if` condition and a refinement's `where` give gains "this collection is not empty", read by `absenceDeclared` for a selection, `max`, and `min` over the same spelling.
- `wiki/Reference/Reference-10-Predicate-expressions.md`, "Reaching through relationships": a paragraph on a test of a collection showing a selection over it present.
- `wiki/Normative/checks.md` F2 and V11: the same rule.
- `examples/payments/payments.velle`, `CompletedAttempt.outcome`, and `examples/membership/membership.velle`, `ClosedTicket.closedBy`: the selection is respelled to match the refinement's `exists`.
- `working-docs/examples/survey/21-max-into-required-guarded-refused.velle` is renamed without the suffix.

**Decided:** yes (Dave, 2026-11-18).

### D3. A `from` block and an assignment keep the runtime refusal for a selection that matches nothing · Decided 2026-11-18

Does a required field filled in a `from` block or by an assignment, `Arrival from { entry: first(Entry where key == this.entryKey by key) }`, stay accepted by the compiler and refused by the runtime when nothing matches, as Reference §14 says today?

Recommendation: yes, keep it. The compiler accepts the selection, and the author writes nothing extra. A submission whose key matches no entry is refused by the runtime, and no `Arrival` ever holds an empty `entry`. An author who wants the refusal in their own words can already write it, as answer B below shows, and nothing forces them to.

The example is a field team surveying plots. An `Entry` is a plot with the key printed on its tag. A surveyor's `Submission` names its plot by that key, as text, because the surveyor has the tag and not the record. An `Arrival` is the record that ties one submission to the entry it meant, so later rules read `arrival.entry` and never search by key again. The rule `RecordArrival` makes the `Arrival`, and the selection in its `from` block is where "the key matches no entry" can happen.

There are three answers. Roster: answer A keeps today's behaviour, answer B refuses the selection unless the rule's own condition shows it matches, answer C refuses it unless the field is declared optional.

#### Answer A, recommended: keep. The compiler accepts, the runtime refuses

```velle
act Entry {
  key: text                              -- required: the key on the plot's tag
  initialLabel: text                     -- required
}
act Submission {
  entryKey: text                         -- required: the key the surveyor read off a tag.
                                         -- A text, so nothing ties it to an Entry yet
  newLabel: text?                        -- may be absent
}

never (Entry where count(Entry where key == this.key) > 1)
                                         -- a refusal: no two entries share a key

shape Arrival {
  submission: one Submission             -- required: the submission that arrived
  entry: one Entry                       -- required: the entry it meant. This is the
                                         -- field D3 is about
}

shape Unpaired = Submission where not exists Arrival for this
                                         -- a submission with no Arrival yet

rule RecordArrival when Unpaired {
  Arrival from {
    submission: this                     -- the submission the rule fired for
    entry: first(Entry where key == this.entryKey by key)
                                         -- a selection that may match nothing, filling a
                                         -- required field. Compiler: accepted.
                                         -- Runtime, key matches: the Arrival is stored.
                                         -- Runtime, no entry has the key: the firing
                                         -- fails, the Submission is undone, and the act
                                         -- comes back refused: "rule 'RecordArrival' did
                                         -- not fire: 'Arrival.entry' is required and
                                         -- 'first(...)' is absent: the selection matched
                                         -- nothing"
  }
}
```

Source: `working-docs/examples/survey/09-lookup-in-rule-no-existence.velle`, rule `RecordArrival`. Evidence: `LookupProbeTest`, "first with no existence prohibition, a key no entry has".

- The author writes the rule and nothing else. There is no line to forget.
- The wrong state is checked, not unrepresentable. It never reaches the books, and the refusal reaches a human naming the rule and the field.
- The language decides the refusal and its sentence. That leans against "for each `never` the author chooses whether a violation is refused at the door or recorded as a finding; the language never decides that". It is softened by answer B's spelling staying available: an author who wants to choose, chooses.
- The rule differs by site: a derived property refuses the selection (D1), a `from` block accepts it. The difference is real. Nothing at runtime checks a derived property (R1), and a `from` block is one event that can fail and does.

#### Answer B: refuse, unless the rule's own condition shows the selection matches

This is the spelling Dave sketched on 2026-11-18: `entry` stays `one Entry`, and the business outcome is a prohibition on `Unpaired`. It compiles and runs today. Answer B would make its guarded rule the only spelling the compiler accepts.

```velle
act Entry {
  key: text                              -- required: the key on the plot's tag
  initialLabel: text                     -- required
}
act Submission {
  entryKey: text                         -- required: the key the surveyor read off a tag
  newLabel: text?                        -- may be absent
}

never (Entry where count(Entry where key == this.key) > 1)
                                         -- a refusal: no two entries share a key

shape Arrival {
  submission: one Submission             -- required: the submission that arrived
  entry: one Entry                       -- required, kept
}

shape Unpaired = Submission where not exists Arrival for this
                                         -- a submission with no Arrival yet

never (Unpaired)                         -- a refusal: no submission stays unpaired. Every
                                         -- submission is Unpaired on arrival and a rule
                                         -- can change that, so it is checked when the
                                         -- transaction ends, not at the door

rule RecordArrival when (Unpaired where exists (Entry where key == this.entryKey)) {
                                         -- fires only when an entry has the key
  Arrival from {
    submission: this                     -- the submission the rule fired for
    entry: first(Entry where key == this.entryKey by key)
                                         -- under B the compiler accepts this only because
                                         -- the rule's condition shows the selection
                                         -- matches, in the same spelling (D2's reading,
                                         -- applied to a rule's inline condition).
                                         -- Runtime, no entry has the key: the rule does
                                         -- not fire, the submission is still Unpaired at
                                         -- the transaction end, and the `never` refuses
                                         -- it: "Submission refused: this Submission would
                                         -- be an Unpaired"
  }
}
```

Source: `working-docs/examples/survey/09-lookup-in-rule-never-unpaired-guarded.velle`. Evidence: `LookupProbeTest`, "a never on Unpaired with a guarded rule, a key no entry has" and "a key that exists".

- An absent value cannot reach a required field, and the runtime check of Reference §14 is not needed for this case. That leans on "a design in which a wrong state cannot be represented beats one in which it is checked".
- The tax: the author writes the existence test in the rule's condition, spelled as the selection spells it, for every lookup. The compiler demands that part.
- The compiler cannot demand the `never`. With the guard written and the `never` left out, a submission naming no entry is accepted and sits unpaired with nobody told (`working-docs/examples/survey/09-lookup-in-rule-guarded-no-never.velle`; `LookupProbeTest`, "a guarded rule and no never on Unpaired, a key no entry has"). Under answer A the same omission still refuses the submission. So B trades a refusal the author gets for nothing for one they must remember to write.
- Writing `never (Unpaired)` without the guard does nothing visible: the firing fails first and the `never` does not speak (`working-docs/examples/survey/09-lookup-in-rule-never-unpaired.velle`; `LookupProbeTest`, "a never on Unpaired with an unguarded rule, a key no entry has").
- `(Entry where key == this.entryKey)` over a `unique` key and `(Shape for expr)` would be held to the same rule, and the lookup in `patterns.md`, `UniqueKeyTest`, and `LookupProbeTest` rewritten.

#### Answer C: refuse, unless the field is declared optional

```velle
act Entry {
  key: text                              -- required: the key on the plot's tag
  initialLabel: text                     -- required
}
act Submission {
  entryKey: text                         -- required: the key the surveyor read off a tag
  newLabel: text?                        -- may be absent
}

never (Entry where count(Entry where key == this.key) > 1)
                                         -- a refusal: no two entries share a key

shape Unpaired = Submission where not exists Arrival for this
                                         -- a submission with no Arrival yet

shape Arrival {
  submission: one Submission             -- required
  entry: one Entry?                      -- the only declaration the compiler would accept
                                         -- for the `from` block below
}

rule RecordArrival when Unpaired {
  Arrival from {
    submission: this                     -- the submission the rule fired for
    entry: first(Entry where key == this.entryKey by key)
                                         -- refused into `entry: one Entry`, accepted into
                                         -- `entry: one Entry?`
  }
}
```

- The books can hold an `Arrival` that ties a submission to nothing, a state the required field rules out today. Every rule reading `arrival.entry` must test `is some`.
- Not recommended under any reading.

#### Whether the compiler warns

Added 2026-11-18, after Dave asked whether answer A should come with a warning. The recommendation has three parts.

1. No warning for a rule that fires inside the act's transaction. The miss there is a refusal the surveyor sees. A warning needs a spelling that silences it, and both candidates are worse than silence. Silencing by the guard on the rule's condition moves the author to the spelling where a bad submission is accepted and nobody is told (`working-docs/examples/survey/09-lookup-in-rule-guarded-no-never.velle`). Silencing by a `never` is the same tax as answer B, since an author content with the runtime's refusal has no way to say so.

2. A warning, an advisory in the A-series of `checks.md`, for a rule behind `after commit` or on a schedule that fills a required field with a selection nothing shows to match. There the miss is not a refusal:

```velle
act Entry {
  key: text                              -- required: the key on the plot's tag
  initialLabel: text                     -- required
}
act Submission {
  entryKey: text                         -- required: the key the surveyor read off a tag
  newLabel: text?                        -- may be absent
}

never (Entry where count(Entry where key == this.key) > 1)
                                         -- a refusal: no two entries share a key

shape Arrival {
  submission: one Submission             -- required: the submission that arrived
  entry: one Entry                       -- required
}

shape Unpaired = Submission where not exists Arrival for this
                                         -- a submission with no Arrival yet

rule RecordArrival when Unpaired after commit, Hourly {
                                         -- fires after the Submission's transaction is over, and again each hour
  Arrival from {
    submission: this                     -- the submission the rule fired for
    entry: first(Entry where key == this.entryKey by key)
                                         -- compiler today: accepted, no message.
                                         -- Runtime, no entry has the key: the Submission
                                         -- is accepted and stays in the books unpaired;
                                         -- the miss is a firing failure on the result.
                                         -- Proposed: an advisory here, saying a Submission
                                         -- whose key matches no Entry is kept and the rule
                                         -- fails, and naming the condition to write
  }
}

rule RecordArrivalGuarded when (Unpaired where exists (Entry where key == this.entryKey)) after commit, Hourly {
                                         -- the spelling that silences the advisory: the
                                         -- rule's own condition shows the selection
                                         -- matches (D2's reading), so the rule waits for
                                         -- an entry and never fails on a miss
  Arrival from {
    submission: this                     -- the submission the rule fired for
    entry: first(Entry where key == this.entryKey by key)
                                         -- no advisory
  }
}
```

Source: `working-docs/examples/survey/09-lookup-in-rule-after-commit.velle`, rule `RecordArrival`; `RecordArrivalGuarded` is the proposed spelling and is in no file. Evidence: `LookupProbeTest`, "the lookup behind after commit, a key no entry has".

   - The meaning is defined and probably not intended, which is the case "the compiler advises when the meaning is defined but the author may not have intended it" names, and the guard is an explicit spelling, not a marker.
   - Here the guard loses nothing. The submission is kept either way, and a `never` cannot refuse it, because its transaction is already over.
   - The advisory reads spellings and proves nothing. A guard spelled differently from the selection still warns.

3. The in-transaction case goes to OQ62 (`working-docs/QUESTIONS.md`) as the second case for a quieter level: "rule `RecordArrival` can refuse a `Submission` when no `Entry` has its key" is something the spec entails that a product owner would want listed, and not something the author did wrong.

#### Proposed changes

- `wiki/Normative/patterns.md`, "An act that names a record by its values": gains the `never (Unpaired)` spelling with the guarded rule, as the way an author chooses the refusal's sentence.
- `wiki/Reference/Reference-14-for.md`, "A required field given an absent value": gains one sentence pointing at it, and one on the advisory.
- `compiler/src/main/kotlin/velle/Validator.kt`: a new advisory, A9, for an `after commit` or scheduled rule whose `from` block or assignment fills a required field with a selection the rule's condition does not show to match.
- `wiki/Normative/checks.md`: the A9 entry, and a test that pins it.
- `compiler/src/main/kotlin/velle/ExprTypes.kt`: D2's reading of "this collection is not empty" is taken from a rule's inline condition and its subject refinement's `where` too, for the advisory.
- Any example the advisory fires on is listed in the build commit, and either guarded or left warning, per example.

**Decided:** answer A, with the three parts of "Whether the compiler warns" (Dave, 2026-11-18: "I think that makes sense"). Before that he had said of answer B that requiring the `never` "feels like a tax".

### D4. An optional field or step read off a selected record is absent by declaration · Decided 2026-11-18

Does the compiler treat a path after a selection as it treats a path after a stored record: refusing `first(upload.rows by key).newKey` into a required place when `newKey: text?` (C2, C3), and refusing a plain dot through `parent: one Parent?` in `first(upload.rows by key).parent.name` (C4)?

Recommendation: yes. Reference §7 and §10 already say so for every other path. The selection is the only thing in front of which the checks go quiet.

What runs today and is lost: C2, C3, and C4 compile. C3 fails at runtime with a sentence that is false (R4). C4 reads absent with no `?.` written.

Costs:

- This applies in a `from` block too, whatever D3 says: D3 is about the selection matching nothing, D4 is about a field the spec itself declares optional.
- An author who wants "absent when the row has no parent" writes `first(upload.rows by key).parent?.name` and declares the result optional, as after a stored record.
- It leans on "the compiler errors when a statement's meaning is undefined": a required `text` given a `text?` has no defined value when the field is empty.
- Added 2026-11-18. The author needs a way to show the field present, and today the compiler reads an `is some` test only on a plain path (`ExprTypes.narrowingOf`, the `cond.subject is PathExpr` case). So `first(upload.rows by key).newKey is some` in a rule's condition would show nothing about the same read in the `from` block. Unless the build adds that reading, by the same spelling as D2 reads a collection, the only spellings left are declaring the target `label: text?` or selecting from the filtered rows, `first((upload.rows where newKey is some) by key).newKey`, which itself needs the compiler to read the filter of the selected collection as it does for `for each` (Reference §14).

How D4 differs from D3. D3 is about whether a row matches, which the types cannot say and only the records decide. D4 is about a field the spec itself declared `text?`, on a row that did match. In D3 the runtime's sentence is true and the author gets a correct refusal for nothing. In D4 the sentence is false (R4), and the compiler already refuses the same read off a stored record (C10).

```velle
act Upload { note: text? } with rows     -- an upload arrives with its rows as parts
shape Parent { name: text }              -- required name
shape Row {
  upload: one Upload                     -- the upload this row is a part of
  key: text                              -- required
  newKey: text?                          -- may be absent, by declaration
  parent: one Parent?                    -- may be absent, by declaration
}
shape Kept {
  other: one Other                       -- required
  label: text                            -- required
}
act Other {
  upload: one Upload                     -- required
  parentName: text? = first(upload.rows by key).parent.name
                                         -- today: accepted (C4). Proposed: refused by V11,
                                         -- "reads through 'parent', which may be absent",
                                         -- naming `parent?.name`
}
rule Keep when Other {
  Kept from {
    other: this                          -- the act itself
    label: first(upload.rows by key).newKey
                                         -- today: accepted, and refused at runtime with
                                         -- "the selection matched nothing" when the row has
                                         -- no newKey (R4). Proposed: refused by F2,
                                         -- "declared a text, given a value that may be absent"
  }
}
```

Sources: `working-docs/examples/survey/21-first-into-required-from-accepted.velle`, rule `Keep`, and `21-first-into-required-optional-step-accepted.velle`, property `parentName`.

#### Proposed changes

- `compiler/src/main/kotlin/velle/ExprTypes.kt`, `absenceDeclared`, the `Access` case: a segment whose member is declared optional, and not shown present, counts.
- `compiler/src/main/kotlin/velle/Validator.kt`, V11: the segments after a selection are checked as the segments of a path are.
- `compiler/src/main/kotlin/velle/ExprTypes.kt`, `narrowingOf`: an `is some` test on a read through a selection shows the same-spelled read present, and a selection over a filtered collection carries what the filter shows about the selected record.
- `wiki/Normative/checks.md` V11: the sentence "A selection or a function result that may be absent is not a step" gains "the steps after it are".
- `working-docs/examples/survey/21-first-into-required-accepted.velle`, `-from-accepted`, and `-optional-step-accepted` are renamed `-refused`.
- `compiler/src/test/resources/diagnostics/`: one refused spec per message.
- Any example the new check refuses is fixed in the same commit and listed in it.

**Decided:** yes (Dave, 2026-11-18), with the `is some` reading on a read through a selection built alongside, as recommended in chat that day.

### D5. `?.` after a selection reads as the plain dot does · Decided 2026-11-18

Does `first(upload.rows by key)?.key` get the same answer from the compiler as `first(upload.rows by key).key` at every site?

Recommendation: yes. Reference §10 gives them one meaning: the rest of the path is absent when the selection matched nothing. Today the `?.` spelling is refused into a required property (C5) where the plain dot is accepted (C1).

Costs:

- Under D1 both are refused in a derived property, and this decision changes nothing there. It matters in a `from` block under D3, where the plain dot is accepted and `?.` would otherwise still be refused.
- The alternative is to refuse `?.` after a selection as a spelling with no use. That takes a spelling away from an author who writes `?.` wherever a value may be missing, and gains nothing the compiler can prove.
- Added 2026-11-18, after Dave asked whether the `?` is redundant or meaningless. It is redundant and not meaningless: Reference §10 makes the plain dot after a selection read absent when nothing matched, which is the job `?.` does after an optional field, so the `?` says again what the dot already says. A third answer would make it mean something: require `?.` after a selection wherever nothing shows the selection matched, as after an optional field, and keep the plain dot for where D2 shows it present. That is the consistent rule, and it is not recommended here, for two reasons. It reverses Reference §10 for every `first(...).field` and `latest(...).field` already written, about twenty in `examples/` by grep on 2026-11-18. And it collides with D3: `label: first(upload.rows by key)?.key` into a required field would then be absent by declaration and refused, which is the tax D3 declined.

```velle
act Upload { note: text? } with rows     -- an upload arrives with its rows as parts
shape Row {
  upload: one Upload                     -- the upload this row is a part of
  key: text                              -- required
}
shape Kept {
  other: one Other                       -- required
  label: text                            -- required
}
act Other { upload: one Upload }         -- required upload
rule Keep when Other {
  Kept from {
    other: this                          -- the act itself
    label: first(upload.rows by key)?.key
                                         -- today: refused by F2. Proposed: accepted as
                                         -- `first(upload.rows by key).key` is, and refused
                                         -- by the runtime when no row matches (D3)
  }
}
```

Source: `working-docs/examples/survey/21-first-into-required-qdot-refused.velle`, which spells it in a derived property; the `from` block form above is the one D5 changes.

#### Proposed changes

- `compiler/src/main/kotlin/velle/ExprTypes.kt`, `absenceDeclared`, the `Access` case: a `?.` directly after a selection does not count on its own.
- `wiki/Normative/checks.md` F2: one sentence.

**Decided:** yes (Dave, 2026-11-18): the two spellings get one answer. He asked first whether the `?` is redundant or meaningless, and chose this over making it required.

### D6. A `?.` directly after a selection draws an advisory · Decided 2026-11-18

Opened 2026-11-18 from Dave's remark after deciding D5: "I wonder if D5 should produce a notification during validation".

Does the compiler advise, without refusing, when a spec writes `?.` directly after a selection, saying the `?` adds nothing and the plain dot reads the same?

Recommendation: yes, as advisory A10 in the A-series of `checks.md`.

- It fits "the compiler advises when the meaning is defined but the author may not have intended it, and the advisory is silenced by an explicit spelling". The meaning is defined (D5). The silencing spelling is the plain dot.
- There is a precedent for advising on a spelling that says nothing new: A8, "A prohibition that is a required declaration", in `checks.md`.
- Unlike the warning declined in D3, the fix costs the author nothing and cannot make the system quieter: deleting the `?` changes no behaviour.
- What the author may not have intended: an author who writes `?.` here probably believes the plain dot would fail on a miss, or that the `?` guards something. The advisory teaches the rule of Reference §10 at the place it applies.
- Cost: it is a note about style, not about the books. Nothing the spec means changes whether the author heeds it. If that is too slight for a warning at the line, the alternative is no message at all; OQ62's quieter level does not fit, since there is nothing the spec entails to record.
- It advises only on a `?.` directly after the selection. A `?.` further along, `first(upload.rows by key).parent?.name`, is doing its job (D4) and draws nothing.

```velle
act Upload { note: text? } with rows     -- an upload arrives with its rows as parts
shape Parent { name: text }              -- required name
shape Row {
  upload: one Upload                     -- the upload this row is a part of
  key: text                              -- required
  parent: one Parent?                    -- may be absent, by declaration
}
act Other {
  upload: one Upload                     -- required
  keyOrNone: text? = first(upload.rows by key)?.key
                                         -- accepted (D5). Proposed: advisory A10, "the '?'
                                         -- after 'first(upload.rows by key)' adds nothing:
                                         -- a read through a selection is absent when it
                                         -- matched nothing; write
                                         -- 'first(upload.rows by key).key'"
  sameKey: text? = first(upload.rows by key).key
                                         -- accepted, no advisory: the silencing spelling
  parentName: text? = first(upload.rows by key).parent?.name
                                         -- accepted, no advisory: this `?.` is after
                                         -- `parent`, which is optional by declaration (D4)
}
```

Source: `working-docs/examples/survey/21-first-into-required-qdot-refused.velle`, property `keyFromFirst`, here declared `text?` so that D1 does not refuse it.

#### Proposed changes

- `compiler/src/main/kotlin/velle/Validator.kt`: advisory A10 on a `?.` directly after a selection.
- `wiki/Normative/checks.md`: the A10 entry, and a test that pins it.
- `wiki/Reference/Reference-10-Predicate-expressions.md`, "Reaching through relationships": one sentence beside D5's.
- Any example A10 fires on loses the `?`, named in the build commit.

**Decided:** yes (Dave, 2026-11-18).

## 3. The build

Every decision is decided. This gathers their proposed changes in build order. Each step names the decision it comes from. Nothing here is built yet.

### Compiler

1. D4, `compiler/src/main/kotlin/velle/ExprTypes.kt`, `absenceDeclared`: in a read through a selection, a segment whose member is declared optional and not shown present counts as absent by declaration.
2. D4, `compiler/src/main/kotlin/velle/Validator.kt`, V11: the segments after a selection are checked as the segments of a path are, so a plain dot through an optional step after a selection is refused naming `?.`.
3. D4, `ExprTypes.kt`, `narrowingOf`: an `is some` test on a read through a selection shows the same-spelled read present, and a selection over a filtered collection carries what the filter shows about the selected record.
4. D5, `ExprTypes.kt`, `absenceDeclared`: a `?.` directly after a selection does not count on its own.
5. D2, `ExprTypes.kt`: a condition gives "this collection is not empty", from `C is empty` governing the other branch of an `if`, and from `exists C` in an `if`, a refinement's `where`, or a rule's condition. A selection, `max`, or `min` over the same spelling is read as present there.
6. D1, `ExprTypes.kt`, `absenceDeclared`: a selection not shown present counts as a value that may be absent when the site is a derived or captured property. A `from` block and an assignment are left as they are (D3).
7. D3, `Validator.kt`: advisory A9, for an `after commit` or scheduled rule whose `from` block or assignment fills a required field with a selection the rule's condition does not show to match.
8. D6, `Validator.kt`: advisory A10, on a `?.` directly after a selection, naming the plain dot.
9. The header of `Validator.kt` is brought up to date.

### Tests

10. `compiler/src/test/resources/diagnostics/`: one refused spec per new or newly reached message (D1's F2, D4's F2 and V11, A9, A10), and `wiki/Normative/diagnostics.md` regenerated by `DiagnosticCatalogTest`.
11. A test per check in the suite that pins F2, V11, and the advisories: D1 refused and D2 accepted in a derived property; D4 refused and its `is some` spelling accepted; D5's two spellings agreeing in a `from` block; A9 firing and silenced; A10 firing directly after a selection and not after an optional step.
12. `RequiredThroughSelectionProbeTest` is rewritten from a probe of today's behaviour into the tests of step 11, since its specs no longer compile, and `LookupProbeTest` keeps the tests added for D3.

### Examples

13. Run the suite and list every example and test spec steps 1, 2, and 6 refuse and every one A9 or A10 fires on. This is the count owed by the work item below.
14. `examples/payments/payments.velle`, `CompletedAttempt.outcome`, and `examples/membership/membership.velle`, `ClosedTicket.closedBy`: the selection is respelled to match the refinement's `exists` (D2).
15. Every other example step 13 lists is fixed, or for A9 guarded or left warning, or for A10 relieved of its `?`, each named in the commit.
16. The examples' generated output is regenerated (the `implement` skill).

### Wiki

17. `wiki/Reference/Reference-07-Derived-properties.md`: the sentence that a selection is "absent by data, which the runtime refuses at the read" is replaced by D1's refusal and D2's reading.
18. `wiki/Reference/Reference-10-Predicate-expressions.md`, "Reaching through relationships": a test of a collection shows a selection over it present (D2); the steps after a selection are checked (D4); `?.` after a selection is the plain dot (D5) and draws an advisory (D6).
19. `wiki/Reference/Reference-14-for.md`, "A required field given an absent value": one sentence on the `never (Unpaired)` spelling and one on A9 (D3).
20. `wiki/Normative/checks.md`: F2 (D1, D2, D4, D5, and the walk with `until` taken out of "left to the runtime"), V11 (D2, D4), the A9 entry (D3), and the A10 entry (D6).
21. `wiki/Normative/patterns.md`, "An act that names a record by its values": gains the `never (Unpaired)` spelling with the guarded rule (D3).

### Working documents

22. `working-docs/examples/survey/`: the `21-*` files are renamed to what they now produce (`-by-data`, `-accepted`, `-from-accepted`, `-optional-step-accepted` become `-refused`; `-qdot-refused` and `-max-into-required-guarded-refused` stay as written only if they still fail, else lose the suffix), and the README rows are updated.
23. `working-docs/QUESTIONS.md`: the OQ143 line moves to the settled table, and this file is deleted in the same commit.

## 4. Work items

- [ ] Build steps 1 to 9, the compiler.
- [ ] Build steps 10 to 12, the tests.
- [ ] Count the example and test specs D1 and D4 refuse and A9 and A10 fire on (step 13), and fix them (steps 14 to 16).
- [ ] Write the wiki (steps 17 to 21), which corrects Reference §7's "refuses at the read" (R1) and `checks.md` F2's walk with `until` (C9).
- [ ] Rename the survey files and settle (steps 22 and 23).
