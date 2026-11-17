# OQ140 — Does a `never` over a union or a taken shape show a step present on each operand or taker?

**Status:** opened 2026-11-17 as OQ134's residue. D1 awaits Dave.

**In plain terms:** the compiler refuses a spec that it accepts when one prohibition is split into two. "A content item with a source label always has a source", written once over the union `Content`, is enforced on every post and every photo. But a post's derived property that reads the source's name, under the test that the label is there, is refused as reading a field that may be absent. Write the identical prohibition over `Post` alone and the same property compiles. The gap is that the compiler enforces the one-sentence prohibition but does not reason from it.

**See:** Reference §10 "Reaching through relationships" (the `is some` test that lets a plain dot read through an optional step); Reference §21 "What the compiler checks" (a prohibition the rules cannot move as a presence fact); `checks.md` V11; `compiler/src/main/kotlin/velle/PresenceFacts.kt`; the OQ140 probe in `compiler/src/test/kotlin/velle/UnionStressTest.kt`; OQ134 in the settled table, which made the prohibition over a union enforced on every operand.

---

## 1. What runs today

This spec is refused today. Every comment below says what the compiler does with the line now.

```
act Member { name: text unique }                 -- a shape; nothing special

shape Attributed {                               -- a shape two acts take with `is`; it has no records of its own
    source: one Member?                          -- optional: a record may have no source
    sourceLabel: text?                           -- optional: a record may have no label
}

act Post is Attributed {                         -- takes `source` and `sourceLabel` from Attributed
    body: text
    credit: text = if sourceLabel is some then source.name else ""
                                                 -- REFUSED today, V11: "'source.name' reads through 'source',
                                                 -- which may be absent; test 'source is some' first, or write
                                                 -- 'source?.name' to read absent when it is"
}

act Photo is Attributed { caption: text }        -- also takes `source` and `sourceLabel`

shape Content = Post or Photo                    -- a static union; both operands take Attributed

never (Content where sourceLabel is some and source is none)
                                                 -- enforced today on every Post and every Photo: a post or a
                                                 -- photo arriving with a label and no source is refused at the
                                                 -- door (OQ134). No rule assigns either field. The compiler
                                                 -- does NOT use it when checking `credit` above.
```

The refusal comes from V11 reading `source.name`. V11 lets a plain dot through an optional step only where something shows the step present: an `is some` test in the same conjunction or the governing `if`, or a prohibition the rules cannot move that says the step is never absent there (Reference §21). The `if` tests `sourceLabel`, not `source`, so only the prohibition could license the read, and the prohibition is not consulted.

Why it is not consulted: `PresenceFacts.kt` turns a prohibition into facts of the form "on every record of shape S where tests T hold, path P is present". It resolves the prohibition's subject to one base shape and gives up on `Content`, which has two. For a prohibition over `Attributed` it does produce a fact with subject `Attributed`, but when it asks which scopes a `Post` is within, the answer is `Post` and the refinements over `Post`, so the fact never applies.

What compiles today, with the same `credit` line, are these two spellings:

```
never (Post where sourceLabel is some and source is none)      -- ACCEPTED today: the fact is on Post, and
never (Photo where sourceLabel is some and source is none)     -- `credit` compiles. Two prohibitions for one rule.
```

```
    credit: text = if sourceLabel is some then source?.name else ""
                                                 -- ACCEPTED today with the prohibition over Content: `?.` reads
                                                 -- absent when `source` is absent, which the prohibition says
                                                 -- never happens here. The spelling contradicts the prohibition.
```

The probe `a never over a union or a taken shape is not yet a presence fact on an operand` in `compiler/src/test/kotlin/velle/UnionStressTest.kt` pins all three: the V11 refusal over `Content`, the same refusal over `Attributed`, and the acceptance over `Post`.

## 2. What to decide

### D1. Whether the first spec in §1 compiles as written

Should `never (Content where sourceLabel is some and source is none)` let `credit` on `Post` read `source.name` under `sourceLabel is some`, and should the same prohibition written over `Attributed` do the same on every shape that takes `Attributed`?

Recommendation: yes. The prohibition is enforced on every operand since OQ134, so what it promises is true of each operand, and the compiler should reason from what it enforces. The author who declares the fields once on `Attributed` (OQ35) and states the rule once over `Content` should not be sent back to one prohibition per kind. Nothing is removed: a prohibition over one operand keeps licensing what it does today.

Costs:

- `PresenceFacts.kt` emits one fact per base shape the prohibition is over, instead of giving up on two. The spend test already unspends on an assignment to the field by name anywhere in the spec, so a rule assigning `photo.source` unspends the prohibition for `Post` as well. That is the fail-closed side and costs nothing new.
- The scopes a record is within grow: a `Post` is within `Post`, the refinements over `Post`, every union `Post` is an operand of, and every shape `Post` takes with `is`. One more lookup in `PresenceFacts.withinOf`, read from the declarations.
- The wiki states the rule in one sentence, in `checks.md` V11 and Reference §21.

The alternative is to keep today's behaviour and state it in Reference §21: a prohibition is a presence fact only when written over one shape. The cost is that the one-sentence prohibition over `Content` is enforced but never reasoned from, and the author learns that by hitting the V11 refusal.

#### Proposed changes

- `compiler/src/main/kotlin/velle/PresenceFacts.kt`: a prohibition over a union or a taken shape yields a fact per operand or taker; `withinOf` includes the unions a shape is an operand of and the shapes it takes.
- `wiki/Normative/checks.md` V11: one sentence, a prohibition over a union or a taken shape shows the step present on each operand or taker.
- `wiki/Reference-21-never.md` "What the compiler checks": the same sentence, with the `Content` example.
- `compiler/src/test/kotlin/velle/UnionStressTest.kt`: the OQ140 probe flips to asserting the first spec in §1 compiles over `Content` and over `Attributed`, and is still refused when a rule assigns `source` on either operand.
- `examples/gallery/gallery.velle`: if a credited source fits the gallery, one prohibition over `Content` and a read it licenses; otherwise no example change.

## 3. Work items

- [ ] Decide D1.
