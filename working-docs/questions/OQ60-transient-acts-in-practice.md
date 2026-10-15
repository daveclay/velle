# OQ60 — Transient acts in practice: the specs still spelled durable, and whether transient should be the default

**Status:** open (2026-10-14) — two threads left over from the transient-acts decision (2026-08-10), carried in the investigation's "still open" list until it was retired; not being worked
**In plain terms:** when the language decided that a request can be a message to the state rather than a member of it (`transient act`, Reference §4), two things were left for practice to answer. First, two example specs were written before the decision and still handle their requests the old way, by keeping the request as a record and anchoring its handling so it cannot drift; should they be rewritten as transient requests, and what does the rewrite cost? Second, the decision took "transient is opt-in, persistence is the default" as its position and said to revisit it once real specs had been written; two have, and both lean transient.
**See:** Reference §4 "Transient acts" (the decision, C0 pinning, the correlation-key idiom) · `checks.md` V17 (isolation), V18 (totality), A4 (drift-exposed partition) · `GLOSSARY.md` (drift, handled-once act partition, anchor) · `examples/partition-drift/`, `examples/payments/payments.velle` (the anchored spelling, live) · `examples/pharmacy/pharmacy.velle`, `examples/peptides/peptides.velle` (the transient spelling, throughout) · OQ43 (transient containers; holds the decision's other residue)

---

## 1. The two specs still spelled durable

`examples/partition-drift/` is the exhibit of the hazard the decision removed: a persistent act partitioned over mutable state is re-evaluated at every later change to that state, so an act handled long ago drifts between partition sides forever (a spurious refusal per flip, a stale re-fired write per flip back). `examples/payments/payments.velle` handles `ChangeShippingAddress` the pre-decision way on purpose: the request persists, and the **handled-once act partition** scopes each side to *unhandled* requests, anchored by the rule's own outcome evidence (`... where not exists AddressChangeApplication for this`). `AdvisorySweepTest` asserts that payments carries no A4 advisory under that spelling, and the glossary points at payments as where the idiom lives.

The investigation's plan was to respell both under the transient design, expecting a ceremony win (no anchor conjuncts, no outcome evidence kept only to pin the partition) and wanting to measure the cost: every outcome record copies the payload it needs from the vanished request. Billing's `ChangeDueDate` and membership's `AssignTicket` were migrated at the time; these two were not, and payments was kept as the live exhibit of the anchored idiom.

**To decide:** whether the anchored spelling should stay in the corpus as a documented idiom with a live exhibit (then payments stays as it is, and the exhibit's only job is to show the idiom and keep A4 honest), or whether every example should use the transient spelling (then payments is respelled, the copied-field cost is recorded, and the handled-once idiom survives only in the glossary and Reference §4 as the choice an author makes when the request itself must be a durable record). The partition-drift exhibit is separate: it exists to be broken, and `PartitionDriftSpecTest` asserts its deliberately bare family drifts.

## 2. Is transient the default?

The decision's own philosophy taken to its limit says every incoming mutation is a message, and durable "acts" (`Payment`, `EmailChange`) are messages whose handlers record facts, expressible today by a handler copying into a durable record shape. What blocked message-as-default was ergonomics the language leans on: "committing a shape instance *is* persisting the record" (Reference §12, "No act-level sugar"), so the common submission case needs no rule at all, and a message default would demand a recording rule per submission, ceremony where there was none. The position taken: transient is opt-in, persistence stays the default, revisit only if practice suggests otherwise.

Practice so far:

- **Pharmacy** makes every request transient and answers each with a record or a refusal; its author's note is that a refusal over a request may read only what the request's handling leaves alone (OQ52 §2).
- **Peptides** spells its stage gates as transient requests answered by records, after the persistent spelling turned out to be a constraint on the books rather than the refusal the sentence sounded like (OQ52 §3).
- **Billing, membership, enrollment, orders, gallery, loyalty, moderation, shipping** keep most acts persistent, and nothing in them drifts because nothing partitions those acts over mutable state.

**To decide:** whether the default stands, or whether the data points are enough to flip it (every act transient unless declared otherwise, with a recording rule as the ordinary spelling), or whether the right answer is neither default but a check: an advisory when a persistent act is never read after its commit, which is the case where transient costs nothing and persistence keeps a record nobody consults.
