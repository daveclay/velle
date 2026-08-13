# OQ17 — Rejection scope

**Status:** open — deferrable past v0; a minimal v0 answer is settled
**In plain terms:** when the system refuses an incoming request, what exactly is undone, what is the caller told, and can a request be refused in part ("accept the deposit, refuse only the tier change")?
**v0 stance:** the settled harness boundary (README §22's scope statement) fixes a minimal answer — a refusal names the violated `never` and nothing commits; the general question stays open here.
**See:** patterns.md ("Conditioned acceptance is a definition", "Validation rejection is data") · OQ20 in QUESTIONS.md's settled table (commit-refusal is not primitive)

---

If a refusal unwinds the act (commit-refusal), what exactly unwinds, what the committer is told, and whether rejection can be partial — declarable policy or always incoherent? Reified refusal ("Validation rejection is data," `patterns.md`) dissolves most of this — nothing unwinds and "what the committer is told" is a shape — leaving only the genuinely commit-refusing subset, which OQ20's resolution delimited: refusal is compiled boundary code sourced from `never` (README §21), and the who-may-commit residue lives in the engineer's wrapper code outside the language (README §22 "External input"; `investigate_runtime.md` §1).

What remains open here: the general shape of "what the committer is told" beyond the v0 minimal answer (a refusal names the violated `never`), whether an author can supply the refusal message (TODO.md carries that as a work item), and whether partial rejection is ever coherent or always the modeling error the partition idioms suggest it is.
