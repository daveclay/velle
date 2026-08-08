# Singular references

How a Velle spec reads "*the* one instance for this subject" — the `(Shape for expr)` query form — and exactly when that claim is legal. Companion to `README.md` §10 (`## Predicate expressions`, the `for`-query rule) and §20 ("Episodes as data"); diagnostics referenced here are `checks.md` V12/V13.

## The forms

There are exactly two ways to use a single matching instance as a value:

```
(Shape for expr)                       -- the singular query: proof-gated
latest(Shape where field == expr)      -- ordered selection: the general fallback
first(Shape where field == expr)
```

- **`(Shape for expr)`** claims there is *at most one* matching instance, and the compiler must be able to prove it. It matches by type: the one to-one field of `Shape` whose type matches `expr`'s type is the field compared. It is legal only when exactly one field type-matches (otherwise V13 — use the `where`-filtered form) *and* the at-most-one proof holds (otherwise V12 — use `latest`/`first`).
- **`latest(...)`/`first(...)`** make no singularity claim — they select from possibly-many by order (the element shape's `timestamp on create` by default). Whenever the proof doesn't hold, this is the honest spelling.

One spelling that is **not** a value: a bare parenthesized filter, `(Shape where field == expr)`, standing alone. `Shape where ...` is Velle's set-denotation — what you write inside `exists (...)`, `count(...)`, `latest(...)` — and a set expression can't stand in element position; nothing in it says "there is one." Wrap it in a selector (`latest(...)`) or, where the proof holds, use the `for` form.

## Base shapes: the to-one proof

For a base shape, at-most-one usually comes from the relationship itself — the target is to-one from the other side, or the spec proves at most one instance can reference the subject:

```
(BatchSubmission for batch)?.submittedOn     -- one submission per batch, proven
```

*(v0 note: base-shape singularity is currently enforced at runtime — a non-singular match fails the evaluation — rather than proven at compile time.)*

## Refinements: the whole-spec singularity proof

The subject of a singular reference may be a **refinement** — and this is the form's flagship use, because "at most one" is usually a property of a *state*, not a shape: at most one **open** flag per account, while flags across episodes are deliberately many.

```
shape OpenDelinquencyFlag = DelinquencyFlag where not exists DelinquencyResolution for this

flag: (OpenDelinquencyFlag for this)         -- the one open flag for this account
```

The license is an inductive proof over commits with two legs, both checked whole-spec:

1. **Every producer is guarded.** Every rule that creates the base shape must carry the entry guard — `not exists OpenDelinquencyFlag for this` — correlated on the field the creation populates. And the base must not be `expose`d: no guard can stop an external committer from landing a second instance.
2. **Membership can't recur.** The refinement's predicate must be *anti-monotone* — once an instance leaves, it can never re-enter — so members only ever appear at guarded creation. Two predicate forms are provably anti-monotone:
   - `not exists W for this` — the **evidence pair**. Facts are monotone (Velle has no delete), so once the resolution lands, the flag is closed forever.
   - `not <flag>` where **every** writer assigns `true` — a **one-way latch**. The compiler walks the writers (every assignment targets a literal static path, so it knows them all) and verifies none can re-open.

Everything else fails closed: use `latest`/`first`.

## Worked example: episodes

The delinquency-episode pattern (`README.md` §20), where the singular reference closes the episode:

```
-- an account is delinquent while its balance is negative
shape Delinquent = Account where balance < 0

-- one flag per delinquency episode; a resolution closes its flag
shape DelinquencyFlag {
    account: one Account
    flaggedOn: Date
}

shape DelinquencyResolution {
    flag: one DelinquencyFlag
    resolvedOn: Date
}

-- a flag is "open" while no resolution references it
shape OpenDelinquencyFlag = DelinquencyFlag where not exists DelinquencyResolution for this

-- when an account goes delinquent with no open flag, start a new episode;
-- the "no open flag" guard is what proves at-most-one open flag per account
rule OpenDelinquencyEpisode
    when (Delinquent where not exists OpenDelinquencyFlag for this) {
    DelinquencyFlag from { account: this, flaggedOn: today }
}

-- when the account recovers, close the episode by resolving its one open flag
rule CloseDelinquencyEpisode when leaving Delinquent {
    DelinquencyResolution from {
        flag: (OpenDelinquencyFlag for this)      -- ✔ licensed
        resolvedOn: today
    }
}
```

Both legs hold: one producer, literally guarded, base unexposed; predicate is an evidence pair. The proof even leans on the reference's own context — the entry rule's guard is what makes the exit rule's singular reference legal, a whole-spec property.

## What's illegal, and the fix

**A base shape with many per subject.** Flags accumulate across episodes by design, so nothing proves at-most-one:

```
flag: (DelinquencyFlag for this)          -- ✘ V12: nothing proves at-most-one
flag: latest(DelinquencyFlag for this)    -- ✔ the honest spelling
```

**An exposed base.** An external committer defeats any guard:

```
expose DelinquencyFlag using DefaultRestAPI
-- ✘ V12 at every `(OpenDelinquencyFlag for this)` in the spec:
--   "an external committer can create a second member while one exists"
```

Note this can break *at a distance*: adding the `expose` (or a second, unguarded producing rule) months later, in another file, makes an untouched singular reference stop compiling — deliberately. The claim was whole-spec; the diagnostic connects the new declaration to the reference it invalidates.

**An unguarded producer.** Any rule creating the base without the entry guard:

```
rule FlagManually when ManualFlag {
    DelinquencyFlag from { account: account }     -- no `not exists OpenDelinquencyFlag ...` guard
}
-- ✘ V12: "rule 'FlagManually' creates 'DelinquencyFlag' without the entry guard"
```

Guard the rule's condition, or switch the singular references to `latest`/`first`.

**A re-openable flag.** The boolean spelling of "open" is fine — until something writes it back:

```
shape OpenDelinquencyFlag = DelinquencyFlag where not resolved

rule ReopenFlag when FlagDispute {
    flag.resolved = false        -- re-entry by drift, bypassing the creation guard
}
-- ✘ V12: "rule 'ReopenFlag' writes 'resolved' non-true, re-entry by drift is possible"
```

The failure is `ReopenFlag`'s, not the boolean's: with only `resolved = true` writers the flag is a one-way latch and the proof holds. When the business genuinely needs re-opening, use the evidence pair — a dispute becomes a *new* flag (a new episode), history intact — and the singular reference stays legal.

**The bare filter as a value.**

```
flag: (OpenDelinquencyFlag where account == this)     -- ✘ not a value: a set expression
flag: (OpenDelinquencyFlag for this)                  -- ✔ where the proof holds
flag: latest(OpenDelinquencyFlag where account == this)  -- ✔ always
```

## `exists` needs no proof

The same refinement subjects work in the `exists` sugar with no singularity proof at all — existence doesn't care how many:

```
not exists OpenDelinquencyFlag for this      -- the entry guard itself uses the form
```

Only the *singular value* position demands the at-most-one proof.

## The fail-closed residue

The compiler proves the forms above and rejects the rest — including legitimate specs it can't yet see into: a producer whose condition *implies* the guard without literally containing it, predicates over values (`where balance < 0` can recur by arithmetic drift), aliasing. In every such case `latest`/`first` remains expressible and honest; growing the provable set is calibration work (`open_questions.md`, OQ16's discharge vocabulary).
