# Investigation: refinement names in `(Shape for expr)`

Context: README §20's episodes example originally spelled the exit rule's singular reference as a where-filtered query, `(OpenDelinquencyFlag where account == this)` — a set expression in element position that `grammar.md` never adopted (the cardinality claim lived entirely in the prover, invisible in the syntax). That spelling was fixed to the sanctioned proof-gated form, `(OpenDelinquencyFlag for this)` — which v0 currently rejects, because both `for`-sugar paths resolve their name against base shapes only. This file works out what lifting that restriction takes. Tracked in `TODO.md`.

The restriction guts the form's main use case: whole-spec singularity proofs almost always attach to *guard refinements* (`OpenDelinquencyFlag`: at most one per account, guaranteed by the entry rule's own guard), never to base shapes (`DelinquencyFlag`: many per account across episodes, deliberately). The one place the proof machinery shines is the one place the spelling is banned.

The mechanics are small, because the machinery already exists — `bindingCandidates` (`Evaluator.kt`) already resolves refinement roots in collection position by taking the base shape's instances and filtering by membership, which is why `latest(OpenDelinquencyFlag where ...)` works today. Only the two `for`-sugar paths were never taught the same trick. The design work is entirely in the proof gate.

## The three layers

**1. Name resolution.** `Validator.kt` checks `name !in model.shapes` in both `SingularFor` and the `exists` sugar, emitting F1 "unknown shape." Extend to accept refinement names, with type-matching run against the **base** shape's stored fields (`model.baseOf(name)`) — a refinement body never declares to-one stored fields, so `OpenDelinquencyFlag`'s matchable fields are exactly `DelinquencyFlag`'s. The guard-atom extractor already anticipates this (`model.baseOf(inner.shape) ?: inner.shape`).

**2. Evaluation.** `instancesReferencing` (`Evaluator.kt`) does `model.shapes.getValue(shape)` — crashes on a refinement — and scans `system.byShape[shape]` — empty for refinements, since instances are stored under their base. Fix: resolve the base for both, then add a membership filter, the same three lines `bindingCandidates` already has:

```kotlin
val base = model.baseOf(shape) ?: shape
// field lookup against base's StoredProps, scan system.byShape[base],
// then .filter { memberOfRefExpr(it, RefName(shape)) } when shape is a refinement
```

The two sugar forms diverge here: **`exists R for x` needs only layers 1–2** — existence is cardinality-insensitive, so no proof gate. Lifting the restriction there is free, and README §20's entry rule could then tighten from `not exists (OpenDelinquencyFlag where account == this)` to `not exists OpenDelinquencyFlag for this`. Only the singular value form `(R for x)` needs layer 3.

**3. The V12 proof gate — where the design lives.** For a base shape, at-most-one comes from a to-one inverse. For a refinement, the license is the whole-spec singularity proof, and it's an *inductive invariant over commits*, the same shape as never-induction (V10): at most one member of `R` per subject, proved by showing (a) it starts true, (b) every way a member can *appear* preserves it. Members appear two ways — an instance of Base is *created* into membership, or an existing instance *drifts* into membership — so the proof has two legs:

- **Every producer of Base is guarded.** Enumerate all producers: rules creating Base, plus `expose` sites. Each producing rule's condition must carry the conjunct `not exists R for <the matched field's value>` — the entry guard. And Base must not be exposed at all: no guard can stop an external committer from landing a second flag.
- **No re-entry by drift.** Guarding creation only helps if membership, once lost, is lost for good — the predicate must be *anti-monotone*. `not exists DelinquencyResolution for this` qualifies: resolutions are immutable facts, so a resolved flag can never re-open. A predicate over a mutable field doesn't.

v0's version is the usual coarse whitelist, fail-closed: predicate of the form `Base where not exists W for this`, all producers rule-side and literally guarded, base unexposed. Everything else → "demand `latest`/`first`."

**Why the difference is decidable.** The gate's discriminations rest on three invariants the language already commits to for other checks — nothing here is heuristic:

1. *The producer set is exact.* Creation statements name their shape literally (the grammar has no dynamic creation) and `expose` declarations are literal, so "who can bring a Base into existence" is a complete closed list.
2. *Every writer of every field is known.* §12's literal-static-path requirement exists precisely so the compiler can enumerate writers — for a `not resolved` predicate it walks the writers of `resolved` and sees which direction each assigns. Same writer sets V1 (one-writer) and V8 (folds) already compute.
3. *Facts are monotone.* No delete primitive means `exists W for this`, once true, is true forever — so `not exists W for this` is anti-monotone by construction, no analysis needed.

Invariant (2) also sharpens the whitelist: the boolean spelling isn't inherently unprovable. If every writer of the flag assigns `true` — a one-way latch — `not <flag>` is exactly as anti-monotone as the evidence pair, provable by the same writer-walk. So the real whitelist criterion is "anti-monotone predicate," with two provable sub-cases: `not exists W for this` (free, by monotone facts) and `not <flag>` with only-true writers (by writer enumeration). What stays fail-closed is the honestly hard residue: producers whose condition *entails* the guard without literally containing it (entailment), value-dependent predicates (`where balance < 0` re-enters by arithmetic drift), and aliasing — OQ16's calibration territory.

## Examples

**Legal — the episodes example, walked through the proof:**

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

-- when an account goes delinquent and has no open flag yet, start a new
-- episode by creating a flag; the "no open flag" guard is what proves
-- at-most-one open flag per account
rule OpenDelinquencyEpisode
    when (Delinquent where not exists OpenDelinquencyFlag for this) {
    DelinquencyFlag from { account: this, flaggedOn: today }
}

-- when the account recovers (leaves Delinquent), close the episode by
-- resolving its one open flag — the singular reference under discussion
rule CloseDelinquencyEpisode when leaving Delinquent {
    DelinquencyResolution from {
        flag: (OpenDelinquencyFlag for this)      -- ✔ licensed
        resolvedOn: today
    }
}
```

Producers of `DelinquencyFlag`: exactly one rule, guarded on the flag's absence; the shape is not exposed. Predicate: `not exists` over an immutable fact — anti-monotone, so members only ever appear at birth. Both legs hold → at most one open flag per account, forever → the singular reference is honest.

**Illegal — base shape, no proof:**

```
count(DelinquencyFlag where account == this) >= 3   -- three flags per account is the point
flag: (DelinquencyFlag for this)                    -- ✘ V12: nothing proves at-most-one
                                                    --   → demand latest(DelinquencyFlag for this)
```

**Illegal — the subtle one: same guard, mutable-flag spelling:**

```
-- same model, but "open" is a writable boolean instead of an evidence pair
shape DelinquencyFlag { account: one Account, resolved: boolean initially false }
shape OpenDelinquencyFlag = DelinquencyFlag where not resolved

-- when a customer disputes a resolution, re-open the disputed flag —
-- flipping the boolean back drifts the flag into OpenDelinquencyFlag,
-- bypassing the creation-time guard entirely
rule ReopenFlag when FlagDispute {
    flag.resolved = false        -- re-entry by drift
}
```

The entry guard is intact, but the predicate reads a writable field: an account with two *resolved* flags plus two `FlagDispute` acts ends with two *open* flags — the creation guard never saw either transition. The proof must consider every writer that can falsify the predicate, not just creators — and it can, by the writer-walk above: the diagnostic names the exact breaking site ("re-entry into `OpenDelinquencyFlag` is possible: `ReopenFlag` writes `resolved = false`"). Note the failure is `ReopenFlag`'s doing, not the boolean's: without that rule the flag is a one-way latch and the spelling passes. When it does fail, it's a rung-recognition moment — the evidence-pair spelling keeps re-opening expressible *and* provable (a dispute becomes a new flag, history intact), and the diagnostic can say so.

**Breaks at a distance — the whole-spec property doing its job:**

```
expose DelinquencyFlag using DefaultRestAPI     -- added months later, another file
```

The untouched exit rule stops compiling, as one connected diagnostic: "`(OpenDelinquencyFlag for this)` in `CloseDelinquencyEpisode` was licensed by an at-most-one proof; `expose DelinquencyFlag` breaks it — an external committer can create a second flag while one is open." Same for a second unguarded producing rule. This is README §1's thesis verbatim — a construct whose meaning would silently change under a distant edit is a compile error naming both sites, and it's exactly the connected-diagnostic pattern V1 already uses for one-writer.

## Longer range: the invariant as a stated `never`

The singularity invariant is really an implicit cardinality `never` — "never two open flags for one account" — which suggests the eventual general discharge: let the author *state* it,

```
never (OpenDelinquencyFlag where
    exists (OpenDelinquencyFlag as other where other.account == this.account and other != this))
```

the compiler proves the `never` inductively (V10's rule-maintained machinery), and V12 *spends* it — the established-`never`-as-proof-input pattern README §21 already defines. That would decouple the two hard parts: the invariant prover grows independently, and the singular form's legality reduces to "is the matching cardinality invariant established?" Not needed for the v0 lift — the coarse syntactic whitelist covers the episodes case — but it's where the calibration (OQ16's discharge-vocabulary work) naturally lands.

## Next steps

- Implement layers 1–2 (name resolution + evaluation) for both `for`-sugar forms.
- Implement the coarse V12 whitelist gate for `(R for x)`: evidence-pair predicate shape, literally-guarded rule-side producers, base unexposed; fail closed otherwise.
- Broken-spec fixtures: the unguarded second producer, the exposed base, the mutable-flag predicate.
- Tighten README §20's entry rule to `not exists OpenDelinquencyFlag for this` once the exists-side lift lands.
