# OQ39 — Inline part creation: multi-part acts through the generated commit function

**Status:** open — not designed; born from OQ30-R7 (2026-08-16)
**In plain terms:** an order committed together with its line items: the parts don't exist yet, so they can't be references — they're values created in the act's commit. How do parts arrive through the generated commit function, and what does that do to "one commit = one act instance"?
**See:** README §4 ("one commit carries exactly one instance"; the container pattern), §6 (references-always `many`), §20 ("All-or-nothing batches"), §22 "External input (`expose`)" · OQ30-R7's spin-off; OQ30 settled — `QUESTIONS.md` settled table · `checks.md` F4, V17

---

OQ30's reference reading of `many` on acts deliberately excludes this case. An act whose `many` targets *don't exist yet* is nested creation, and no current spelling serves it:

- **Parts can't be committed first.** Each `OrderLine.order: one Order` needs an order that doesn't exist yet — and even if it did, each part-commit would be its own act, firing rules per part: wrong granularity for "these arrive together."
- **The act can't reference them.** References name existing instances (OQ30); these are values.
- **The container pattern has an unspecified seam.** README §4 says a multi-part act *is* one container instance with the parts as related shapes — but §22's generated commit function takes "that shape as input," and nothing says how the parts ride along.

The gap, named: **the input closure.** In the state model the commit carries one act instance; at the transport boundary the input is plausibly the act *plus its inline part values*. Is that closure the "one instance," or a violation of it? This is the question the construct must answer before any syntax matters.

Constraints any design must respect:

- **`many X` stays references-always** (OQ30). One keyword, one meaning: inline creation needs its own *visible* spelling, never an act-position reinterpretation of `many`.
- **F4 totality reaches the parts.** Each inline part is a creation with every field covered — including the back-reference (`order: one Order`), which only the language can populate: a committer-suppliable back-reference would be a claim about an instance that doesn't exist yet, so within the closure that field is structurally language-populated, `timestamp`-style.
- **Rule granularity must be stated.** The container's rules fire once per act; are the parts' creations also matchable conditions within the same transaction (a rule `when OrderLine ...` firing per part), and is that per-part firing a feature or a hazard here?
- **Transient containers.** If the container is `expose transient`, the parts hold a relationship to a transient act — exactly what V17 bans for durable shapes. Either the parts are transient with the container (falling together at transaction close, only copied consequences surviving) or the combination is rejected; pick one.

Candidate spellings, none designed:

- **A marked propType on the exposed act** — an inline-creation cardinality visibly distinct from reference `many` (something in the family of `lines: many new OrderLine`; spelling entirely open).
- **Exposure-side declaration** — the `expose` declaration names which related shapes ride the closure (`expose Order with lines`), keeping shape declarations pure and putting the trust-boundary fact where the trust boundary is declared.
- **Refuse the construct** — multi-part input stays outside the language, the engineer's wrapper code commits… listed to be rejected explicitly: per-part commits break one-commit-per-act and rule granularity, which is the whole reason the container pattern exists.

Stress-test against README §20's all-or-nothing batch dissolution before adopting anything: several of those rungs (containers, completeness gates) are adjacent, and the construct should compose with them rather than duplicate them.
