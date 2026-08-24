# OQ27 — Erasure and retention

**Status:** open — language-side resolved via OQ37-R10 (see the 2026-08-15 section below); residue is compilation's unretrievability guarantee. Settles fully when OQ37 promotes.
**In plain terms:** how does a system built on "facts don't un-happen" satisfy real-world obligations to erase data — retention windows, right-to-be-forgotten?
**Opened by:** `investigate-transient.md` (its closing section, moved here)
**See:** README §4 (no delete primitive) · `investigate_runtime.md` §7 (capture retraction — the contract's one sanctioned delete, for memory, not records) · [OQ37](OQ37-delete.md) / `../investigate-delete.md` (deleting records through ordinary rules — the description face of what may be the same primitive; if a delete statement lands, this question's policy vocabulary plausibly compiles down to scheduled deletes plus a payload-shredding choice)

---

Velle's "delete has no primitive" stance (README §4) is about *description*: facts don't un-happen, and a spec that erases records lies. But real systems owe **erasure** — retention windows, right-to-be-forgotten — and that obligation is about *storage*, not description: "an edit occurred" can remain true in the model while its payload ceases to be physically retrievable. That places retention/erasure with compilation (the same layer as "which database"), plausibly as declared policy the transpiler enforces (annotations on shapes/fields; crypto-shredding or hard deletion as mechanism). What the language owes is at most the policy vocabulary — and a check that no derivation or guard depends on data the policy allows to vanish.

Transient acts (README §4) solve the *request-payload* slice by construction — a transient act's payload never persists, which is often the GDPR-sensitive part. The durable-record slice (retention on facts the model keeps) remains its own question. Not designed.

**Rescoped by OQ37 (2026-08-14).** The delete investigation (`../investigate-delete.md`, rulings R1–R9) absorbs the *whole-instance* slice: "purge drafts after 90 days" is now an ordinary scheduled sweep with a `delete` body, subject to the existence-dependency check like any deleter — no policy vocabulary needed there. What remains this question's own is **field-level payload erasure**: the GDPR-paradigm case erases the PII payload of records whose *existence* must persist (guards, ledgers, selectors, audits read them) — exactly where OQ37's check *forbids* whole-instance delete, correctly. The residual design question is precise: does field-level erasure deserve declared policy vocabulary (shape/field annotations the transpiler enforces, plus the existence-dependency check's field-granular sibling — no derivation or guard reads a field the policy lets vanish), or is it entirely compilation? Constraints inherited from OQ37's rulings: no erasure-memory in the language (R5's stance), and a compliance "record of erasure" is an explicitly modeled outcome shape copying the non-PII slice (R6's stance). One new benefit lands regardless: a mandated erasure colliding with a proof-bearing read is now a visible compile-time conflict, not a latent one.

**Resolved language-side (2026-08-15, via OQ37-R10): erasure is a rule over an `? initially required` field — no new constructs at all.** Two earlier sketches died on the way here: a passive `erasable` marker (said nothing about when/how/who decides) and a `clear` statement with an `unless cleared` marker (a cause-specific marker that would have been the second of an open-ended family). The adopted answer is OQ37-R10's decomposition — `?` for the read side, `initially required` for the creation side — with clearing as *ordinary assignment of `none`*:

```
shape Payment {
    invoice: one Invoice
    amount: Money
    cardholderName: text? initially required   -- required at commit; absent only by later spec-visible cause
    receivedOn: timestamp on create
}

shape UnpaidInvoice = Invoice where not exists Payment for this
-- and on Invoice:  balance: Money = amount - sum(payments, amount)

-- "clear PII once the record has been untouched for 7 years" — the when/how is
-- the rule header, where cadence decisions already live:
shape DormantPayment = Payment where receivedOn < today - 2555 days and cardholderName is some

rule ClearDormantPII when DormantPayment on Nightly {
    this.cardholderName = none
}
```

A right-to-be-forgotten request still cannot *delete* the `Payment` — `UnpaidInvoice` reads its existence, `balance` reads its `amount`; OQ37's check forbids it, correctly. But the *field* can vanish while the fact stands. Everything is existing machinery: assignment (one-writer covers the clearer; `frozen` applies naturally, so clearing a frozen field while frozen is illegal — the GDPR-vs-issued-invoice collision surfaces as a visible check, resolvable per spec); a flag-style guard (`cardholderName is some`, disarmed by the assignment — tick law satisfied by the field itself); optional-shaped reads via narrowing/`?.`. And the field-granular protection needs no bespoke check — **the type system is the check**: clearing `amount` would require typing it `Money?`, which forces every reader (`balance`, `sum`) to handle absence or fail to compile.

What remains of this question: only the storage face — the physical guarantee that a value the model no longer holds is unretrievable (crypto-shred, backups), a compiled-guardrails obligation, compilation's, not language design.
