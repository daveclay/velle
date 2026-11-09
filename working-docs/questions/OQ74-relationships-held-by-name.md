# OQ74 — Finding the record an act's values mean: a lookup in a rule

**Status:** opened 2026-11-06 from a stress pass over a system whose record types are data (`working-docs/examples/survey/`). Reframed through 2026-11-08 with Dave, in conversation, to the position in sections 3 and 4: how values reach an act is not Velle's concern, and finding the record those values mean is a selection written where a rule or a prohibition needs that record, not a step at the door and not a declaration on the target shape. Two items to build or probe are in section 4. One decision remains, D1 in section 5, and awaits Dave.

**Two words kept apart on this page.** The *store key* is the value the store assigns to a row, a `Long` in every generated store today, carried by `Ref<Entry>` and `StoreKey`. The *identifying values* are values of the target record's own fields, supplied by a human, that pick out exactly one record: the key printed on an entry's tag, or a field's name together with its kind's name. A human never sees a store key. This page never uses "id" or "identifier" alone.

**The premise.** A human fills in an act. They do not have the store key of the record they mean. They have identifying values of it. So the act carries values that are *intended* to pick out an existing record, and the spec has to be able to say what those values mean and what the system does with the record they pick out. The survey specs worked around it by searching for the record in a derived formula, which costs the relationship and gives the wrong answer when a key is reused.

**Not this question:** a relationship to the own shape and any walk along it (OQ76; the survey's `02-lookup-recursion-*` files are its). The V14 diagnostic (OQ78). A short spelling for uniqueness (OQ77, which section 4 uses).
**See:** Reference §6 (relationships), §10 (selections), §15 and §18 (rules; firings that fail), §21 (`never`) · `wiki/singular_references.md` (a selection proven to yield one record, V12) · `wiki/checks.md` (V10, deleters) · `wiki/Act-API.md` (what a commit's input carries) · OQ77 (`unique`)

---

## 1. The use case

A field team surveys things in the world: sites, plots, specimens. An administrator decides what kinds of things there are and, for each kind, which fields a surveyor fills in. A plot might have a soil type field with a fixed list of allowed answers. Each surveyed thing is an entry, and everyone knows an entry by the key printed on its tag, something like PLOT-0417. A surveyor's submission is about one entry and carries one reading per field: the field's name and the value they wrote down.

When the office reviews a reading, they need to know which field it answers, so they can check the value against that field's allowed answers. The reading gives the field's name and the submission gives the kind. Together those pick out exactly one field, because an administrator never gives two fields of one kind the same name. In the same way the submission gives the entry's key, and that picks out exactly one entry, because no two tags carry the same key.

Nobody in the office thinks of any of this as a search. The reading is for a field. The submission is about an entry. The kind and name, or the key, are just how people say which one they mean.

**The test any spelling has to pass.** Tag PLOT-0417 is retired, and six months later a new plot is given the same key. An old submission was about the old plot. Whatever the spec stores or derives for "the entry this submission is about" has to keep saying the old plot.

## 2. The Velle written so far

The survey specs modeled the submission's entry as a derived search (`working-docs/examples/survey/03-assigned-field-workaround.velle`):

```
act Entry {
    key: text
    ...
}

act Submission {
    entryKey: text
    ...
    entry: one Entry? =
        if exists (Entry where key == this.entryKey) then first(Entry where key == this.entryKey by key)
        else none
}
```

The reading's field is spelled the same way in `working-docs/examples/survey/01-in-inside-exists-workaround.velle`, with `kindName` and `name` in place of `key`.

What that spelling costs. The relationship is derived, so the compiler infers no inverse for it and runs no deletion check through it. The reference is optional, so a key that matches nothing is recorded instead of refused, which is not what the office means. And it fails the test in section 1: a derived formula is re-evaluated at every read, so when PLOT-0417 is reused, every old submission silently starts pointing at the new plot.

## 3. What is not Velle's: how the values arrive

Velle captures the conversation between a product owner and an engineer, as a business layer with the lower-level concerns pushed out to APIs, in a language a human can read directly. A value arriving from a form field, a JSON body over REST, or a mobile sync is explicitly outside that. So:

- The act carries the surveyor's key as what it is, a `text` field. The generated input class and the JSON API take it as text. Neither gains a second form for a reference, and `Ref<Entry>` keeps its one form, the store key.
- Nothing happens at admission beyond what happens today: the act's own `never`s are checked and the act is recorded or refused. There is no step at the door that turns text into a reference. The door is the edge of the channel, and a lookup bound to it would be one more thing the channel does.
- The target shape declares nothing about which of its fields identify a record. That would teach Velle a general idea, identity by field values, to serve what is one selection in one rule.

What is left for the spec is what the product owner actually says: what the value means, and what the system does with the record it means. Both are rules and prohibitions, which Velle already has.

## 4. The spelling: a lookup where a rule needs the record

The shapes of `03-assigned-field-workaround.velle` with the derived `entry` removed. The two prohibitions are the two facts from section 1. The rule is the office's "a relabel records an outcome against the entry".

```
act Entry {
    key: text                   -- the key printed on the tag, PLOT-0417
    initialLabel: text
}

act Submission {
    entryKey: text              -- what the surveyor wrote down; a business value, stored as text
    newLabel: text?
}

never (Submission where not exists (Entry where key == this.entryKey))
                                -- a submission naming a key no entry has is refused at the door
never (Entry where count(Entry where key == this.key) > 1)
                                -- no two entries share a key (OQ77's one-word spelling, if it lands)

shape Outcome {
    submission: one Submission
    entry: one Entry
    label: text
    handledAt: timestamp on create
}

shape Relabel = Submission where newLabel is some and not exists Outcome for this

rule ApplyRelabel when Relabel {
    Outcome from {
        submission: this,
        entry: first(Entry where key == entryKey by key),
                                -- the lookup: the entry this key means, found when the rule fires,
                                -- stored on the outcome as an ordinary reference
        label: newLabel
    }
}
```

What each piece does for the office. The first `never` is the refusal: a bad key never reaches the books. The second is the fact that makes the key mean one entry. The rule does the lookup at the moment the system acts on the submission, and the outcome holds a stored reference, so the inverse is inferred, the deletion check runs, and the test in section 1 passes: the outcome keeps pointing at the plot that had the key when the rule fired.

The lookup is an expression, so it is not tied to creating a record. The reading's field, from `01-in-inside-exists-workaround.velle`, is needed in a prohibition, not a `from`: the office refuses a reading whose value is not one of the field's allowed answers.

```
act Field {
    kindName: text
    name: text
    options: many text
}

act Submission {
    kindName: text
    readings: many Reading = (Reading where submission == this)
}

shape Reading {
    submission: one Submission
    fieldName: text
    value: text?
}

never (Field where count(Field where kindName == this.kindName and name == this.name) > 1)
never (Reading where not exists (Field where kindName == this.submission.kindName and name == this.fieldName))
never (Reading where value is some
    and not exists (first(Field where kindName == this.submission.kindName and name == this.fieldName by name).options as o
                    where o == this.value))
                                -- the lookup inside a prohibition: the field this reading answers,
                                -- then its allowed answers
```

Two things have to be true of the compiler for this spelling to stand, and neither is checked yet:

- [ ] **A selection proven to yield one record is accepted where a `one Entry` is required.** `first(... by key)` says "the first of possibly several" and yields an optional value; the office meant "the one". `wiki/singular_references.md` already describes selections the compiler proves singular (V12). To probe: whether the two prohibitions above are enough for that proof, so that `entry: first(Entry where key == entryKey by key)` compiles against `entry: one Entry`, and whether a word other than `first ... by` is wanted once it does. No new word is proposed until the proof is in place.
- [ ] **A lookup that finds nothing when a rule fires is a failed firing.** If the owner keeps the first `never`, the case cannot arise and the compiler knows it. If the owner drops it because the office wants to see bad keys on the books, the rule meets a key with no entry. Velle already carries firings that fail for a reason other than a `never` on the commit, and requires a tick's failures to reach a human (Reference §18, `wiki/Act-API.md`). A lookup finding nothing or more than one record is one more reason a firing fails. To check: that the generated code and the rejection data name the selection and the values it ran with.

One limit worth knowing: if the spec has a rule that deletes entries, the compiler today refuses the first `never` in the first example, because deleting an entry could make a standing submission forbidden (`wiki/checks.md`, deleters under V10). That is a known limit of the deletion proof, not this question.

## 5. What to decide

### D1. Whether the act itself holds a reference to the record its values mean, or only what the rules make from it

The decision in one sentence: under the spelling of section 4 the submission has no `entry` field, and the reference to the entry lives only on the outcome the rule creates; is that enough, or should the submission carry the reference too?

What "only on the outcome" means in practice, with the first example of section 4:

- There is no `Entry.submissions`. The inferred inverse is `Entry.outcomes`. "All submissions about this entry" is read through outcomes, `Outcome where entry == this` and then each outcome's submission.
- A submission with no `newLabel` fires no rule, so nothing on the books links it to its entry except the text `entryKey`. If the office wants to see it against the entry, a rule has to make a record for it.
- In the second example of section 4, the reading has no `field` reference at all. Every prohibition that needs the field repeats the selection. A derived `field` cannot replace it, because a derived field is re-evaluated and fails the test in section 1 when a field is renamed.

**Option A, recommended: the relationship lives on what the rules make.** The act keeps the identifying values as text. Every record the system makes from the act holds a stored reference found by the lookup. Nothing new in Velle beyond the two items in section 4.

In its minimal form A is section 4 as written, and the three points above are its costs. In its full form, a run-once rule makes one record per submission that pairs it with the entry its key means, and every other rule and prohibition reads the entry through that record. The three points then go away.

```
act Entry {
    key: text
    initialLabel: text
    arrivals: many Arrival = (Arrival where entry == this)   -- every submission about this entry
}

act Submission {
    entryKey: text                       -- what the surveyor wrote down
    newLabel: text?
}

never (Submission where not exists (Entry where key == this.entryKey))
never (Entry where count(Entry where key == this.key) > 1)

shape Arrival {                          -- "this submission was about that entry", as a record
    submission: one Submission
    entry: one Entry                     -- required, not optional
}

rule RecordArrival when Submission where not exists Arrival for this {
    Arrival from {
        submission: this,
        entry: first(Entry where key == entryKey by key)     -- the lookup, once per submission
    }
}

shape Outcome {
    submission: one Submission
    entry: one Entry
    label: text
    handledAt: timestamp on create
}

shape Relabel = Submission where newLabel is some and not exists Outcome for this

rule ApplyRelabel when Relabel {
    Outcome from {
        submission: this,
        entry: (Arrival for this).entry,                     -- read through the pairing, no second lookup
        label: newLabel
    }
}
```

The reading's field goes the same way: a `FieldAnswered` record pairs each reading with its field once, made by a rule `when Reading where not exists FieldAnswered for this`, and the allowed-answers prohibition of section 4 becomes `not exists ((FieldAnswered for this).field.options as o where o == this.value)`.

What the full form costs: one extra shape per act whose values name a record, and the rules that use the reference depend on the pairing rule having fired, which the run-once guard orders. What it leans on: `(Arrival for this)` is accepted as one record because the guard makes exactly one, the pattern Reference §13 uses for `(ArchiveRequest for this)`; that is the same singular-selection proof as the first item in section 4.

**Option B: a rule assigns the act's own reference.** The act declares an optional reference, and a rule fills it from the lookup.

```
act Submission {
    entryKey: text
    newLabel: text?
    entry: one Entry?           -- optional, because a rule assigns it after the act is on the books
}

rule FindEntry when Submission where entry is none {
    this.entry = first(Entry where key == entryKey by key)
}
```

The submission then has a stored reference and `Entry.submissions` is inferred. The costs: the reference is optional in its type even though the rule leaves no submission without one, so every read of `entry` handles a `none` that cannot occur; and any `never` that reads `entry` is a guarantee the rule maintains, which the compiler today cannot prove over the rule that writes the field it reads and fails closed on (V10, the `03-assigned-field-refused` row of the survey README). The second cost is the one the survey ran into first.

A's full form is B with the reference moved one record over: the pairing record gives a required `one Entry` and no rule writes a field a prohibition reads, so neither of B's costs appears. B's costs could instead be removed by compiler work, a rule that fires at every creation of a shape making the field it assigns required, and a `never` over such a field proved from the rule; neither is proposed here. The choice is whether a pairing record per act is an acceptable price for a reference the act's own shape does not carry.

## 6. Housekeeping when D1 lands

The `01-` and `03-` survey files are rewritten to the spelling of section 4 once the first item there compiles, and the README rows for them change. OQ77's reference to this question points at section 4, where the uniqueness `never` is one of the two prohibitions the singular-selection proof needs. The earlier framings that left this question: a store binding as the construct; a declaration of identifying fields on the target shape; a reference filled at the door; and an equivalence with OQ72, which asks about a step at the door and so is no longer the same question. The parent example and its walk are OQ76's.
