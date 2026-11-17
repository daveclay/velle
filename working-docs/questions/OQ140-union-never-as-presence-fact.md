# OQ140 — Does a `never` over a union or a taken shape show a step present on each operand or taker?

**Status:** opened 2026-11-17 as OQ134's residue. D1 awaits Dave.

**In plain terms:** a prohibition can promise that a field is filled in: "a content item with a source label always has a source". Today the compiler keeps that promise in mind only when the prohibition is written over one shape, `Post`. Written once over the union `Content`, or over the shape `Attributed` that both kinds take with `is`, the same promise is forgotten, and the author has to write it once per kind, or read the field with `?.` as if it could be absent.

**See:** Reference §10 "Reaching through relationships" (the `is some` tests that license a plain dot); Reference §21 "What the compiler checks" (a prohibition the rules cannot move as a presence fact); `checks.md` V10 (a prohibition over a union reads what every operand shares) and V11; `compiler/src/main/kotlin/velle/PresenceFacts.kt`; the OQ140 probe in `compiler/src/test/kotlin/velle/UnionStressTest.kt`; OQ134 and OQ139 in the settled table, which made a `never` and an `exists` over a union or a taken shape run over every operand or taker.

---

## 1. What runs today

The probe `a never over a union or a taken shape is not yet a presence fact on an operand` in `compiler/src/test/kotlin/velle/UnionStressTest.kt` compiles this spec three times, with the prohibition written over `Content`, over `Attributed`, and over `Post`:

```
act Member { name: text unique }                 -- the person a content item is credited to

shape Attributed {                               -- taken with `is` by both kinds of content: declared once
    source: one Member?                          -- who it came from, when known
    sourceLabel: text?                           -- how the source is named, when known
}

act Post is Attributed {
    body: text
    credit: text = if sourceLabel is some then source.name else ""
                                                 -- `source.name` is a plain dot through an optional step;
                                                 -- V11 allows it only where a test shows `source` present
}

act Photo is Attributed { caption: text }

shape Content = Post or Photo                    -- a static union of the two kinds

never (Content where sourceLabel is some and source is none)
                                                 -- a label without a source is refused at the door;
                                                 -- no rule assigns `source` or `sourceLabel`, so the
                                                 -- prohibition holds in every settled state
```

- Over `Post`: `credit` compiles. The prohibition is spent as a presence fact: `source` is present on every `Post` where `sourceLabel is some`, and that test governs the read.
- Over `Content`: V11 refuses `source.name`, "reads through 'source', which may be absent". `PresenceFacts.kt` resolves a prohibition's subject to one base shape, and a union has two, so the prohibition yields no fact.
- Over `Attributed`: V11 refuses the same way. The fact's subject is `Attributed`, and the scopes a `Post` is a member of are `Post` and the refinements over it, never the shape it takes from.

What the author writes today: the prohibition once per kind, `never (Post where ...)` and `never (Photo where ...)`, or the read as `source?.name`, which reads absent where the prohibition says absence cannot happen.

The runtime half is already right: since OQ134 the prohibition over `Content` or `Attributed` is evaluated over every operand or taker, so the promise is kept on the books. Only the compiler's use of the promise is missing.

## 2. What to decide

### D1. Whether a prohibition over a union or a taken shape is a presence fact on each operand or taker

Does `never (Content where sourceLabel is some and source is none)` show `source` present on every `Post` and every `Photo` where `sourceLabel is some` holds, and does the same prohibition over `Attributed` show it on every shape that takes `Attributed`?

Recommendation: yes. A prohibition over a union reads what every operand shares (`checks.md` V10), and since OQ134 it is enforced on every operand, so the fact it states is true of each of them. The spelling an author reaches for after OQ139, one prohibition over the shared declaration, should buy the same reads as the spelling per kind. Nothing is removed: a prohibition over one operand keeps its facts.

Costs:

- `PresenceFacts.kt` resolves a prohibition's subject to every base shape it is over, and emits one fact per base. The spend test already looks at assignments by field name anywhere in the spec, so a rule assigning `photo.source` unspends the prohibition for `Post` too, which is the fail-closed side.
- The scopes a record is a member of grow: a `Post` is within `Post`, the refinements over it, every union it is an operand of, and every shape it takes with `is`. That is one more lookup in `PresenceFacts.withinOf`, read from the declarations.
- A refinement over the union, `shape Credited = Content where sourceLabel is some`, is within `Content`, so a read in it is licensed the same way.

The alternative is to keep today's behaviour and say so in Reference §21: a presence fact comes only from a prohibition over one shape. That costs the author a prohibition per kind for a promise that is one sentence.

#### Proposed changes

- `compiler/src/main/kotlin/velle/PresenceFacts.kt`: a prohibition over a union or a taken shape yields a fact per operand or taker; `withinOf` includes the unions a shape is an operand of and the shapes it takes.
- `wiki/Normative/checks.md` V11: one sentence, a prohibition over a union or a taken shape shows the step present on each operand or taker.
- `wiki/Reference-21-never.md` "What the compiler checks": the same sentence, with the `Content` example.
- `compiler/src/test/kotlin/velle/UnionStressTest.kt`: the OQ140 probe flips to asserting the read compiles over `Content` and over `Attributed`, and still refuses when a rule assigns `source` on either operand.
- `examples/gallery/gallery.velle`: if a credited source fits the gallery, one prohibition over `Content` and a read it licenses; otherwise no example change.

## 3. Work items

- [ ] Decide D1.
