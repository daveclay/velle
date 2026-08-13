# OQ31 — Committer-suppliable fields

**Status:** open — pure `expose`-construct design, deferred as a construct detail
**In plain terms:** which fields of an exposed shape does the caller supply and which are internal? The answer determines each generated commit function's signature.
**See:** README §5, §22 "External input (`expose`)" · `investigate_runtime.md` §1, §8

---

Which fields the generated commit function accepts vs. which are internal: whether an ordinary `initially` field can be marked not-committer-suppliable the way `timestamp` fields inherently are (README §5). Determines the generated function's *signature* — this is what makes it `expose` design rather than mechanism configuration (`investigate_runtime.md` §1 dissolved the mechanism; this item detached and reattached to `expose`).

Its former siblings retired with `investigate_runtime.md` §8: supplied-vs-generated `id` and id minting are gone as questions — identity is the store's, Velle mints nothing persisted, and committer-side identity needs are business identifiers (ordinary fields), never `id`.
