# OQ26 — Correlation keys for transient acts

**Status:** open — works today by convention; ergonomics undesigned
**In plain terms:** a transient request vanishes when its transaction closes, so there is no request id for the caller to point at — how do responses (and retry-dedup guards) get matched to the request that caused them?
**Opened by:** the Design B decision (`investigate-transient.md`); sharpened by `break-b.md` Case 4
**See:** README §4 "Transient acts" · checks.md V17–V18

---

Under persistence, the act's `id` is the correlation point. Under Design B the id dies with the act, so correlation must be a business key the act carries and outcomes copy — `requestKey: text`, the idempotency-key pattern real APIs already use. This works today with no new machinery: the caller supplies the key, outcomes copy it, and a dedup guard reads it off the durable outcome (`not exists (BidRecord where requestKey == this.requestKey)`).

What's undesigned:

- **Threading ceremony.** The key must appear on the act, be copied through every outcome, and appear as a guard conjunct on every idempotent handling rule — per-rule boilerplate B otherwise removed (break-b.md Case 4's observation: "some of the ceremony B removed comes back wherever ingestion must be idempotent").
- **Uniqueness ownership.** `requestKey` needs uniqueness only the client controls; nothing in the spec states or checks that.
- **In-flight duplicates.** Two copies of the same request racing are two transactions — last-in-wins on the guard is fine for the bid case, but the general story is unstated.
- **Mechanism choice.** Either an id-valued copyable field kind (new language surface), or the convention stays informal (client-supplied keys as documented idiom). Undecided.
