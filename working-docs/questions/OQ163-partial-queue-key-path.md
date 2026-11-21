# OQ163 — when a queue key's path stops at an absent reference, does a rule firing key the last record it reached?

**Status:** opened 2026-11-21 from the refactor of `Runtime.kt` into `QueueKeys.kt`, where the two behaviours were kept as they were. D1 awaits Dave.

**In plain terms:** before a transaction runs, the runtime tells the store which records it will work on, so two transactions on the same record take turns. To find a record it follows references from the act or the swept record. When a reference along the way is empty, an act names nothing for that route, but a scheduled or after-commit rule names the last record it got to. The two should probably agree.

**See:** `evaluation.md` U3 (the serialization domain, path keys "by the row they evaluate to") · `compiler/src/main/kotlin/velle/QueueKeys.kt` (`ofAct`, `ofFiring`, `hopAlong`, `lastRowReached`) · `compiler/src/main/kotlin/velle/DomainKeys.kt` (the queue-key contract's reference implementation for acts: a `none` met mid-path "names no row") · `compiler/src/test/kotlin/velle/PartialKeyPathProbeTest.kt` (both behaviours pinned) · OQ40 and OQ42, settled (the derivation and its trust)

---

## 1. What runs today

A **queue key** is one value the store serializes transactions on. A **path key** is a queue key that names the record a route of references reaches, such as `deposit.account`. The compiler derives the routes, and the runtime follows them at admission, before the transaction's work runs.

When a reference along a route is absent:

- For an act, the route contributes no key (`QueueKeys.ofAct`, through `hopAlong`). This matches `DomainKeys.evaluate` and the comment on `ofAct`: "A path that reaches no row contributes no key."
- For a rule firing that is its own transaction, a tick's or an `after commit` one, the route keys the last record it reached before the empty reference (`QueueKeys.ofFiring`, through `lastRowReached`). Nothing documents this. It was the same in `Runtime.kt` before the refactor (`firingKeys`, `for (seg in path.segs) { id = id?.let { hop(it, seg) } ?: break }`, where the `break` keeps the previous record).

The spec in `PartialKeyPathProbeTest.kt` shows both:

```velle
shape Region { name: text  flagged: boolean }        -- a record a rule writes through two hops
shape Account { region: one Region? }                -- the optional middle hop: an account may have no region
act Charge { account: one Account }                  -- an act whose rule reaches the region through its account
shape Invoice { account: one Account  overdue: boolean }   -- the record a schedule sweeps

-- an act's rule: the compiler derives the routes [account] and [account, region] for Charge
rule FlagOnCharge when (Charge where account.region is some) { this.account.region.flagged = true }

-- a scheduled rule: the compiler derives [], [account] and [account, region] for each firing
rule FlagOverdue when (Invoice where overdue) on Nightly { this.account.region.flagged = true }
```

With an account that has no region:

- `Charge` is serialized on the account alone. `[account, region]` stops at the empty region and adds nothing (test "an act whose key path meets an absent region keys only the account").
- The `FlagOverdue` firing is serialized on the invoice and the account. `[account, region]` stops at the account and keys it a second time, which the set absorbs (test "a firing whose key path meets an absent region keys the account it reached").

In this spec the two behaviours hand the store the same keys, because the shorter route `[account]` is in both domains. It is there because reaching the region means reading `region` off the account. Whether every route's prefixes are always in the domain is not established. If one is ever missing, a firing would serialize on a record its work never touches, and wait for transactions it does not conflict with.

## 2. What to decide

### D1. Does a rule firing's route that stops at an absent reference contribute no key, as an act's does?

Recommendation: yes. One rule for every transaction: a route that reaches no record names no record. `lastRowReached` goes, and `ofFiring` follows routes with `hopAlong`, as `ofAct` does.

Costs:

- The behaviour is observable only if a domain can hold a route without the routes to its intermediate records. Today's spec does not show such a domain. If none exists, the change removes a second spelling of one rule from the runtime and changes no key any store receives.
- If such a domain exists, the firing stops serializing on the intermediate record. That is sound only if the firing does not write the intermediate record, since what the firing writes is in its domain by its own route. The cases of §3 settle this before the build.
- No design principle in the root `CLAUDE.md` is at stake. It is consistency: one decision in one place.

#### Proposed changes

- `compiler/src/main/kotlin/velle/QueueKeys.kt`: `ofFiring` follows each route with `hopAlong` and skips a route that returns null; `lastRowReached` is deleted.
- `compiler/src/test/kotlin/velle/PartialKeyPathProbeTest.kt`: the firing test is renamed to "a firing whose key path meets an absent region keys only the invoice and the account", with the same expected keys, and the class comment loses "pin today's behaviour until OQ163 decides it".
- `wiki/Normative/evaluation.md` U3: one sentence saying a route that reaches no record contributes no key, for an act and a firing alike.

## 3. The build

To be gathered from D1 once it is decided.

## 4. Work items

- [ ] Find whether any example's derived domain holds a route without the routes to its intermediate records (`DomainAnalysis` over every spec in `examples/`), and record the result in §1 with a test.
