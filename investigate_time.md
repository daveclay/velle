# Time & Immutability

The residue of the mapping dissolution is a single new idea: **occurrence** — *when* a thing happens or becomes true, treated as a first-class fact. It sits alongside the others as a distinct kind:

- **shapes** — *what* exists
- **relationships** (`one`/`many`) — *how* facts connect
- **refinements** — *which* facts qualify
- **rules** — *what* follows from *what*
- **occurrence** — *when* things occur

Per this repo's method the thread names and scopes the concept and records what forces it; it does **not** invent syntax (`when this occurred` above was illustrative).

## It is the genus of things already scattered through the docs

Occurrence isn't bolted on — three constructs already in play are all faces of it, previously unnamed:

- `trigger X when FlaggedCustomer is created` — a rule **reacts to** an occurrence (a fact entering a refinement).
- `on Daily` — a schedule **generates** recurring occurrences.
- `loggedOn: now` → "when this occurred" — a field **refers to** an occurrence.

So the `trigger`/`rule` stress-test at the top of this file was already building the *reactive* half of this concept without naming the genus; timestamp fields are its *referential* half. Unifying them is most of the work.

## What forces it

1. The mapping dissolution: capture had to land somewhere, and it lands here.
2. Unification: triggers, schedules, and timestamps are three uses of one idea; leaving them un-unified is the same "syntax hasn't caught up to the semantics" gap the `trigger`/`rule` split opened with.

## Open questions (recording, not resolving)

- **"As of a moment" depth — the load-bearing one.** Being worked in `example_time.md`. Nounifying time makes every derived value implicitly *as of* some moment. `loggedOn` (a fact's own moment) is trivial; a genuinely-changing quantity is not. Forcing case: an `Order`'s `total` while line items are still being added — is `total` a live sum, or "the sum as of when this occurred"? Current standing (see that doc): **live by default, pinned where a mutable source must be frozen; the freeze/live choice is per *read*, not per shape** (so `record`/`entity` is not a Velle concept), and if freezing surfaces at all its locus is **relationship membership as of an occurrence**, not a scalar operator. Open hunt: is every freeze-point nameable as such a membership? If yes this closes with no new scalar syntax.
- **What kinds of occurrence there are, and whether they are one kind.** A fact's origination ("when this was created"), a fact entering a refinement ("when it became a `FlaggedCustomer`"), a schedule tick (`Daily`), an externally-asserted event ("when the payment was made" — is that just the `Payment` fact's origination, or a separately declared event?). Candidate: all are occurrences of one kind; not yet tested.
- **Reference vs. reaction — one thing or two?** Timestamps *refer to* occurrences; triggers *react to* them. Leaning: one concept consumed two ways, mirroring how a refinement is both a queryable set and a trigger condition. Unconfirmed.
- **Is `now` ever irreducible?** Is there a legitimate "the actual wall-clock instant of execution" that is *not* expressible as "the moment of some event" (a genuinely external / nondeterministic reading)? If so, `now` survives as a narrow primitive rather than fully dissolving. Flag.
- **Ties to parked items.** §17 provenance and the executable-vs-spec fork both live here — provenance only means something if occurrences persist and accumulate rather than overwrite, which is the same immutable-leaning world the "as of" answer relies on.

## Not yet touched

- Any surface syntax for referring to, reacting to, or scheduling occurrences (deliberately deferred).
- Whether "occurrence" is the right *name* (event / moment / when are alternatives).
- Whether derived properties need explicit temporal qualification at all, or whether the common cases (own-moment references, naturally-immutable event-facts) cover enough that "as of" rarely surfaces.
