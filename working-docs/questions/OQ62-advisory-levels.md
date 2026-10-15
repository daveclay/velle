# OQ62 — Levels of advisory checks

**Status:** deferred (2026-10-14) — split out of OQ52 §4, where it had arrived as a passing remark from OQ47
**In plain terms:** the validator's advisories (the A-series, `checks.md` "Advisories") have one level: warn at the line. The question is whether they should have a quieter level that records what the spec entails without warning, such as a generated test that makes a constraint's rejections visible and executable, or a generated note, so a calculable case the author may not have intended is exposed without noise. It is a question about the validator as a whole, not about any one check.
**See:** `checks.md` "Advisories" (A1–A6) · `Validator.advisories(...)` in `compiler/src/main/kotlin/velle/Validator.kt` · `testgen.md`

---

## 1. Where it came from

OQ47 asked whether an advisory should tell the author that a `never` on a persistent shape is a constraint on the books and not the refusal their sentence sounds like. The ruling was no warning: a clear rejection makes the author's choice self-correcting, and the calculation that would have fed the advisory becomes a documenting artifact instead. That ruling named a possible second level for the A-series in passing. OQ55 is the only other place an advisory's volume has been an issue, where forty-one A5 advisories on one spec and fifty-seven on another were ruled acceptable.

## 2. The question

Whether the A-series gains levels, and if so which:

- **warn at the line**, what every advisory does today;
- **record what the spec entails**, no warning, but a generated artifact the author can read or run: a test that exhibits the case, or a note in the generated documentation.

**To decide, when a second case wants the quiet level:** whether a level system is worth its weight against the alternative of each advisory choosing, per check, between warning and silence. One passing remark and one accepted-volume ruling are not enough to design levels around.
