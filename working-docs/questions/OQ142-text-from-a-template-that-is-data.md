# OQ142 — Can a spec fill a naming template that is data, and mint a sequence number?

**Status:** opened 2026-11-17 from a stress pass of a domain spec, re-verified at commit 76cff78. D1 and D2 await Dave.

**In plain terms:** a system's setup holds a naming pattern for new items, such as `{parent}-{sequence}`, and each new item is named by filling it in with its parent's key and the next number in a running count. Velle can build a text from a pattern written in the spec, but not from one held in a field, and it has no running count. Today the caller supplies the key.

**See:** Reference §5 "Text made from other values" (a text literal with pieces); OQ80 in the settled table of `working-docs/QUESTIONS.md` (joining texts is the literal with pieces, never `+`); OQ88 in `working-docs/QUESTIONS.md` (a format argument, which covers zero-padding the number); `wiki/Guides/Extension-functions.md` (a function a system registers for itself); the survey files `working-docs/examples/survey/19-*`.

---

## 1. What runs today

`working-docs/examples/survey/19-template-that-is-data.velle` validates:

```
act Setup { namingTemplate: text }               -- the pattern, held as data: "{parent}-{sequence}"
act Item { setup: one Setup, parentKey: text, sequence: integer
  fromLiteral: text = "${parentKey}-${sequence}" }
                                                 -- ACCEPTED: a text literal with pieces (Reference §5). The
                                                 -- pattern is fixed in the spec; `namingTemplate` is not read.
```

`working-docs/examples/survey/19-plus-on-text-refused.velle`:

```
act Item { key: text
  stem: text = key + "-" }                       -- REFUSED, F2: "'+' is not defined on a text; a text made from
                                                 -- other values is a text literal with pieces, "${key}-"" (OQ80)
```

Three things the first spec cannot say:

- a text built by filling `setup.namingTemplate`, whose pieces are known only at run time;
- `sequence` rendered as three digits, `007` (that is OQ88's format argument and is not decided here);
- a `sequence` the system mints, one more than the last item under the same parent.

What a spec does today: the committer supplies the key, and the template stays with the API that computes it.

## 2. What to decide

### D1. Whether a spec fills a template held in a field

Does Velle get a function that fills a text pattern held as data with named values, `fill(setup.namingTemplate, parent: parentKey, sequence: sequence)`?

Recommendation: no, not as a builtin. The design principle is that arbitrary input from a form or a sync is not part of Velle; it is pushed to the APIs. A template held in a field is exactly that: text whose meaning is decided outside the spec, and a spec that fills it cannot say what the result is. The compiler can check nothing about a pattern it has not seen: not that every piece it names is supplied, not that the result is a key. A system that wants it writes an extension function (`wiki/Guides/Extension-functions.md`) and chooses its own placeholder syntax, as custom functions outrank custom types.

The alternative that keeps the choice in Velle: the setup holds a word from a small set, `namingStrategy: NamingStrategy` with the words declared in the spec (Reference §5, "A field that takes one of a few words"), and the item's key is `if setup.namingStrategy == "parent-sequence" then "${parentKey}-${sequence}" else ...`. Every strategy is a literal with pieces the compiler sees, and V18's word slice reports a word no branch names.

Costs:

- A product owner who wants an admin to type a new pattern without a release cannot have it in Velle. That is the principle applied, not a new limit.
- A system that does want it writes one function and loses the compiler's view of the result, as with every extension function.

#### Proposed changes

- `wiki/Normative/patterns.md`: a worked pattern "A key from a naming strategy", the words-declared spelling above, naming the extension-function route for a pattern that must be data.
- `working-docs/examples/survey/19-template-that-is-data.velle` rewritten to the strategy spelling, README line updated.

### D2. Whether a spec mints a sequence number

Does Velle get a way to say "one more than the highest `sequence` among this item's siblings", assigned when the item is created?

Recommendation: yes, but as nothing new. It is a derived property the spec can write today once the collection is in view: `sequence: integer = count(setup.items)` at creation, or `max(...) + 1` once OQ93's maximum exists. What is missing is the guarantee: two items created in the same instant under the same parent must not take the same number. That is a uniqueness key, `unique (parentKey, sequence)` (Reference §21, "Keys"), and the question is whether a derived value may be part of a key and what the store does when two commits race. The recommendation is to put that question to OQ77's successor in the serialization-domains section rather than invent a counter.

Costs:

- A derived sequence is recomputed from the records, so it is right by construction and needs no counter record. A design in which a wrong state cannot be represented beats one in which it is checked.
- A gap in the numbering after a deletion is visible; a system that forbids gaps keeps the deleted item.
- The racing-commit case is the store's, under the serialization domain the shape is in (Reference §4).

#### Proposed changes

- Decide whether a derived property may take part in a `unique` key; if yes, `wiki/Reference/Reference-21-never.md` "Keys" says so and `compiler/src/main/kotlin/velle/Keys.kt` admits it; if no, the pattern in D1 says the sequence is `count(setup.items)` and the race is documented as the domain's.
- `working-docs/examples/survey/19-template-that-is-data.velle` gains the minted sequence in whichever spelling is decided.

## 3. Work items

- [ ] Decide D1.
- [ ] Decide D2.
- [ ] Weigh in D2's build the stored spelling that runs since 2026-11-19, `sequence: integer initially if exists (Item where setup == this.setup) then latest(Item where setup == this.setup by sequence).sequence + 1 else 1`: a starting value reads the records that existed before the new one (Reference §5 "Starting values"; `InitiallyOrderTest`), so the number is stored, may sit in a `unique` key, and does not move when an item is deleted.
