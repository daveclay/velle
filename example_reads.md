# Stress test: reading — using the system at the boundary

Every thread until now describes *system design* — the conceptual model (shapes, relationships, refinements, rules, occurrences). This thread is the first about **using** that system: a reader outside the system asks it for data. The forcing example is the one from the time thread — *"show me my account balance as of any date"* — because it is obviously needed and trivially understood.

Scope discipline:
- **Transport-agnostic.** We describe *what questions the system can answer*, never *how* the answer travels (REST, GraphQL wire protocol, SQL, gRPC — all out of scope). A read is a described capability, not an act.
- **Reads originate nothing.** Asking a question is not producing a fact (only `rule` + `produces` originates facts). A read is a *projection* over facts that already exist or are already derivable — transient, evaluated on demand.
- Method as everywhere: illustrative spellings are marked; nothing here is proposed surface yet.

The goal of *this* entry: **exhaust the implicit angle** — the claim that the read surface falls out of the model for free, with no read construct at all — and find exactly where it strains. What strains is what will force *declared* reads (suspected, not yet designed — its own entry later).

---

## The core implicit claim

A read computes nothing new. Given the design

```
shape Account {
    transactions: many Transaction
    balance: sum of transactions.amount        -- as of the reading moment
}
```

the model *already* determines a value for every (account, moment) pair. So a read is not a function that does work — it is a **window onto an already-described relation**, with the reader supplying coordinates. The computation (`balance`) was already there and homed on a shape; a read just opens a window and labels the handles. That is what stops a read from being the function/`mapping` we dissolved: the transformation already exists; the read only *exposes* it.

Pushed to its limit, the implicit claim is:

> A Velle model **is** its own read surface. Because it is already a typed graph — shapes are nodes, `one`/`many` are edges (with free inverses), derived properties are computed fields — an outside reader can navigate it exactly as a GraphQL client navigates a schema. No read construct is declared; the surface is the model.

GraphQL is the near-exact template:

| Velle | GraphQL |
|---|---|
| `shape` | object type |
| `one X` / `many X` | field returning `X` / `[X]` |
| inverse relationship (free) | back-reference (hand-written resolver in GQL; free here) |
| derived property (§6) | computed field / resolver |
| named `where`-refinement | a filtered root field / subset-returning field |
| `as of T` (time thread) | field argument |
| identity / key (**missing**) | `id` + root `thing(id:)` |
| the set of all `X` | root `things` / aggregate |

The rest of this entry walks each capability and marks where "the model is the surface" holds **free** and where it **strains**.

---

## Holds free

**Navigation from a known fact.** `account.balance`, `account.transactions`, `order.customer.name` — arbitrary-depth traversal is just following relationship edges. This is the bulk of what a reader does, and it is entirely the existing model. Free.

**Derived properties as fields.** `balance`, `Invoice.total` — a reader selecting them is selecting a computed field. No difference from a stored one at the read boundary. Free.

**The reading moment as a query-wide coordinate — an actual payoff.** "Show me the account — balance, owner, everything — *as of Jan 10*." From the time thread, a bare read is already "as of the observer's moment," defaulting to now. A read simply **sets that observer-moment** for the whole traversal:

```
-- illustrative
read Account acct-1 as of Jan 10        -- the whole projection observes from Jan 10
```

Everything reached inherits Jan 10 as its reading moment → a coherent point-in-time snapshot, for free, with no per-field threading. A per-field `as of` (`balance as of someOtherDate`) remains available as an override. This is a clean win: the query-wide `as of` is just *"pick the observer's moment,"* which the time thread already established as the only temporal handle. **Reads are also the forcing case that confirmed Path B** (arbitrary-date reads) from the time thread — a user reading balance-as-of-any-date is exactly the case that requires reifiable moments.

**Named refinements as pre-built entry sets.** `where balance < 0` named in the model exposes, for free, "the negative-balance accounts" as a readable collection — a GraphQL root field that already exists. Free, *for the refinements you named*.

---

## Strains — and each strain is a push toward declared reads

**1. Entry points need identity — but then fall out.** Navigation assumes you are *already at* a fact. Cold, a reader is at nothing; they have an id they typed (`acct-1`), not an `Account` to navigate from. GraphQL hand-writes a `Query` root (`account(id: ID!)`). The implicit angle survives this *only if* identity is solved: give every shape a declared **key**, and two roots fall out of every shape automatically — *fetch-one-by-key* and *fetch-the-collection* (the Hasura/PostGraphile auto-schema move). So entry points can stay implicit — **but they rest entirely on identity/keys, which Velle does not have yet.** This is the real prerequisite this whole thread forces, and it is bigger than the read construct itself: *how is a fact named from outside the system?* Internally you never name a fact, you navigate to it; a reader must name one cold.

**2. Ad-hoc filtering is parameterization — the first genuine strain.** A *named* refinement is free (above). But "accounts with balance between **X** and **Y**," where X and Y are supplied at read time, is a reader-supplied predicate — parameterized, which we forbade for refinements (that road is functions). In read terms X and Y are just *coordinates*, the same category as `as of`: a filter generalizes `as of` (both narrow which facts return). But making *arbitrary* filtering implicit means auto-generating a whole filter-argument DSL over every field (again the Hasura move) — a large surface to conjure for free, and one the model never asked for. This is where "fully implicit" first hurts: either accept a generated filter DSL, or require the parameterized reads you actually want to be **declared**. First real vote for declared reads.

**3. Named/curated answer shapes.** A reader assembling `{ accountId, balance as of T, ownerName }` themselves is free (GraphQL selection set). But a *named, promised* answer shape — a view the system contracts to provide — is precisely the dissolved-`mapping`-at-the-boundary: supplied coordinates + derived fields, now legitimately homed because the reader supplies the inputs. Nothing forces you to name it, but the moment you want the read surface to be a **contract** rather than "navigate whatever you like," you are declaring reads. Second vote.

**4. Collection-level aggregates are homeless.** "Total deposits across *all* accounts as of today" is `sum of allAccounts.balance` — a derived property of no single Account. It is a homeless derived property again (the §-mapping diagnosis), and it has nowhere to hang unless the model has a root/"system"/"all-accounts" shape for it to be a property *of*. GraphQL supplies `accounts_aggregate`. Implicit Velle can only expose an aggregate that is already anchored to some shape; a genuinely cross-collection aggregate forces either a declared root shape or a declared read. Third vote.

---

## How far implicit gets

Free, with only identity/keys added: **navigate the whole model from any keyed entry, select any fields (stored or derived), at any observer-moment (query-wide or per-field).** That is a large, genuinely useful read surface — essentially "GraphQL auto-generated from the schema," and for a lot of real usage it is enough. The account-balance-as-of-any-date case is *fully* covered by the implicit surface (fetch account by key + query-wide `as of`), which is why it felt so easy.

Where implicit runs out, and declared reads start to earn their place:
- **ad-hoc reader-supplied filtering** (parameterized selection — don't want a blanket auto-DSL),
- **named answer shapes** (a read *contract*, not free-for-all navigation),
- **cross-collection aggregates** (homeless without a root shape).

And underneath all of it, the one hard prerequisite the implicit angle cannot dodge:
- **identity / keys** — how a fact is named from outside so a reader can point at it cold. Internal Velle never needed this; the boundary forces it. This likely wants its own thread before declared reads.

## Open — next

- **Fully settle the implicit surface**: is auto-exposing every shape as fetch-by-key + fetch-collection the right default, or too much? Does query-wide `as of` compose cleanly with the free inverses (can you observe an inverse relationship as-of a past moment)?
- **Identity/keys** — the forced prerequisite. What is a Velle-native key (a unique field? a declared identifying set?), is it declarative (a stated fact about a shape), and does it touch occurrences (a fact's identity vs. its moment)?
- **Declared reads** (suspected needed) — the curated/contract surface for the three strains above. Its shape is likely the dissolved `mapping`, rehomed at the boundary where the reader supplies the inputs. Deferred to its own entry.
