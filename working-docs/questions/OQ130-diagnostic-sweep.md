# OQ130 — Rewrite every other check's message to the diagnostics rule

**Status:** opened 2026-11-13 from OQ78 D4, which decided the sweep gets an OQ of its own. D1 awaits Dave.

**In plain terms:** the rule in `compiler/CLAUDE.md` says a message the compiler prints is read by the author of a spec, names what they wrote and what to type instead, and uses none of the compiler's own words. V14's messages follow it. Ten other checks still print words such as "v0", "fail closed", "witness", "dischargeable", and "anti-monotone", and two wiki pages and three examples call a fold "whitelisted". This OQ rewrites each of them.

**See:** `compiler/CLAUDE.md` "Diagnostics" (the rule) · `compiler/src/main/kotlin/velle/Validator.kt` (`SelfReadProblem` and `checkWellFoundedness`, the model: one reason, one message, the names it needs carried from where the check fails) · `compiler/src/test/kotlin/velle/ValidatorTest.kt` (the `V14 message` tests, one per message asserting its text) · `wiki/checks.md` (each check's entry, which the message cites by number) · `working-docs/examples/survey/README.md` (the "Diagnostic today" column quotes V10's message twice, drawn by `03-assigned-field-refused.velle` and `09-lookup-in-never-writer-refused.velle`)

---

## 1. What runs today

Checked against HEAD on 2026-11-13 by searching the message strings in `Validator.kt` for the words the rule names and for "v0", "fail closed", "coarse", "monotone", "latch", "fixpoint", and "sugar". A line in a comment or KDoc is not a message and is not listed.

| Check | Words in its message | `Validator.kt` line |
|---|---|---|
| V4 | "a dischargeable guard", "produce a" for a witness atom | 2505, 2517 |
| V8 | "gate it on a dischargeable state" | 2826 |
| V10 | "v0 cannot yet prove it inductively over its writers; fail closed" | 2848 |
| V12 | "v0 proves plain conjunctions only", "not provably anti-monotone in v0", "one-way latch flags" (`antiMonotoneFailure`) | 3456, 3481, 3482 |
| V18 | "v0 proves coverage for an answer on the bare shape" | 3639 |
| V23 | "no fan-out deletes in v0", "fail closed" | 3718, 3729, 3768 |
| V25 | "guard reads ('not exists witness ...')" | 3836, 3837 |
| V26 | "v0 cannot prove otherwise" | 3879 |
| V29 | "v0 cannot prove the condition excludes it" | 3916 |

Beside the messages, "whitelisted fold" for a fold V8 accepts as written stands in `wiki/Reference-19-Self-referential-folds-and-tolerates.md` (the code comment at line 78), `wiki/checks.md` V8, `wiki/Reference-22-Open-unresolved.md` ("The extension framework"), and the comments of `examples/billing/billing.velle`, `examples/membership/membership.velle`, and `examples/peptides/peptides.velle`. The glossary no longer defines *whitelist*.

## 2. What to decide

### D1. Whether each rewritten message comes to Dave before it lands

Does each check's rewritten message come to Dave for approval, as OQ78's did, or does it land under the `compiler/CLAUDE.md` rule with a test pinning its text?

Recommendation: land under the rule, one check per commit, the test asserting the text and the survey README updated where the survey draws the message. Bring Dave only a message whose check has more than one reason, as V14 had, since that is where a reason and its fix have to be found before they can be worded.

Costs:

- Ten checks, so ten commits and at least ten tests. V10 first: the survey draws it twice.
- A message landed without review may still read wrong to an author. The survey README is where Dave sees it next.

**Decided:** awaiting Dave.

## 3. Work items

- [ ] Rewrite V10's message, with a test asserting its text and the two survey README rows updated (D1).
- [ ] Rewrite V4, V8, V12, V18, V23, V25, V26, and V29, one check per commit, each with its test (D1).
- [ ] Replace "whitelisted fold" in Reference §19, `checks.md` V8, Reference §22, and the three example comments with "a fold the compiler accepts as written" (D1).
