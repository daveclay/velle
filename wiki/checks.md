# Velle Validator Check Catalog (v0)

Every check the v0 validator runs, one entry each: what it proves, what it reads, and the shape of its diagnostic. This consolidates obligations settled across the Reference. The checks marked *coarse* are deliberately conservative v0 slices: they fail closed today over patterns a finer analysis could accept, and later calibration revises those entries, not the model.

Three principles govern every entry (Reference §1, §12):

- **Whole-spec.** Compiling means re-validating the entire spec as one unit — every check may read every declaration, and a declaration added anywhere can trip a check on an untouched line.
- **Fail-closed.** Where a proof can't be completed, the spec is rejected — uncertainty is an error, never a warning. Legit-but-unprovable and dangerous-but-unprovable get the same diagnostic; the legit author has an answer to give (guard, prohibition, restructure, tolerance).
- **Connected diagnostics.** A check that relates two constructs reports one diagnostic naming both sides (the writer and the freeze; the declaration that created an ambiguity and every reference it broke) — never an isolated error at either site.

Severity: **F** foundations (name/type layer), **V** required semantic checks (errors), **A** advisories (guidance the author may ignore, §20).

## Foundations

### F1 — Name resolution and casing

Every reference resolves to exactly one declaration; uppercase-initial names are shapes/refinements/schedules/mechanisms, lowercase-initial are properties/aliases (`grammar.md`). A bare name that doesn't exist in its innermost scope errors — never a scope-walk to an enclosing scope, even when unambiguous; the fix is explicit `this.field` (§22, compiled guardrails). `id` is reserved (§5).

### F2 — Type checking

The Kotlin-grounded scalar rules (§5): operator/operand compatibility, duration arithmetic only on `Date`/`DateTime` (receiver-dependent), predicates boolean, comparisons like-typed, `==` on instances is identity. Calls only to the closed builtin list (§5) with correct arity — including the selectors' mandatory `by` list (§10): each named datum must be an orderable member of the element shape. The text operators (`contains`, `starts with`, `ends with`, `like`, §10) take text on both sides. The same rules check a query from the read API (§22): its filter is checked as a refinement's predicate over the root, and its order must name one or more orderable members of the root, never `id`. A relationship's target (`one X`, `many X`) is a base shape or a **static union** — a pure `or` of base shapes with no predicate anywhere (Reference §6, §9) — never any other refinement: a field type is fixed at the write, a membership drifts after it, so a refinement-typed field could be violated by a commit that never wrote it; the diagnostic prescribes a requirement on the creating act (`VerifyFill requires pharmacist to be Pharmacist`) or a gate refinement, or names the operand carrying the predicate. A membership test `x is R` whose two sides have no base shape in common is an error, never trivially false. `and` across distinct bases is provably empty (A2); a rule condition composing unrelated shapes inline must name the union (Reference §9). Relationship traversal follows declared `one`/`many` types; `?` optionality is part of the type. Collection typing (Reference §6, "Committing and assigning collections"): `+`/`-` on a declared `many` are union/removal with an element or collection right-hand side; `empty` types only where a `many` is expected; a `many` never takes `?` (the empty collection is the absence).

### F3 — Position rules

Shape bodies contain no effects; rule bodies contain no definitions (`=` is positional, §12). `captured` only in refinement bodies (§8); `frozen` only names stored fields of the base shape (§8); a cross-base union's body admits derived properties only — `frozen` has no base to name, `captured` has no entry moment but creation (the spelling is `initially` on each operand), `undeletable` gates a refinement of each operand (§9); `initially` only on stored properties (§5); `timestamp` fields and `id` never assigned, never committer-supplied (§5); `tolerates duplication|reordering` on fields, `tolerates loss` on rules (§19).

### F4 — Mapping totality

A `from { ... }` block supplies every field the created shape declares (minus language-populated ones); a missing field is an uncovered-mapping error (§14). Declared `many` fields are fields: a creation supplies each one a collection value — a traversal, an act's collection field, or `empty` (§6).

## Required checks

### V1 — One-writer disjointness

(§12.) For every pair of assignments targeting the same field: prove their triggers can never coincide at one commit (unrelated act shapes → disjoint; same shape, refinement-and-base, or overlapping refinements → error; a cross-base union overlaps each of its operands, while distinct bases never overlap — §9). Uses the refinement-overlap machinery (V9) on trigger shapes; spends established `never` prohibitions for alias cases (V11). Connected diagnostic names both rules.

*Coarse* extension for fan-out (§6, "Committing and assigning collections"): a collection-path assignment counts as a write to that field on **every** instance of the member shape — any other write to the same field whose trigger could coincide is a conflict, and instance-level disjointness of target sets is never attempted (they are runtime data). Refining this with disjointness proofs in specific patterns is post-v0 calibration.

*Closure* extension (§6, "Inline part creation"): a closure commit makes N entrants of each riding part shape, so the pair enumeration includes **self-pairs** — a rule triggered by a closure-riding shape coincides with itself across sibling entrants. A body assigning through the language-populated back-reference converges on the same container instance by construction — a proven write-write conflict, refused totally, value-equal right-hand sides included (proving RHS equality across firings is the instance-level reasoning this check already declines). The diagnostic prescribes the served spellings: derive the aggregate on the container, or move the condition to the container for once-per-act granularity.

### V2 — Disarm proof

(§18.) A guarded rule's body must provably falsify its own trigger predicate — produce the witness the `not exists` reads, or assign the flag the predicate tests. Falsifying any one conjunct falsifies the conjunction, so one disarmed guard atom discharges the proof; the trigger's other `not`-atoms are conditions, not guards (`ActiveMember`'s `not suspended` owes no disarm to a rule guarded by a witness). Failure: "this rule never leaves its trigger state."

### V3 — Unfireable rule

(§11.) Derive each rule's trigger set from the writers of what its condition reads, the spec's `act` declarations (a `shape` with no producing rule is uncommittable, §22), and its `on` schedules. Empty set → error. The time-dependence instance: a condition reading `today` with no schedule in `on` observes no entry by aging — diagnostic says exactly that. A rule whose subject is a transient act is exempt from it: the act's partition is evaluated exactly once, at the act's own commit (§4, "Transient acts"), so the act cannot age into a state, and V17 forbids the schedule the diagnostic would otherwise ask for — a time-dependent partition of a request ("is this prescription still good today?") is read at the request. The bare-shape instance (OQ37-R17): a predicate-exit trigger over a bare shape — `when leaving Listing` with `Listing` bare — has no predicate to flip and, since `when leaving` never fires at deletion, an empty trigger set; the diagnostic says the shape is bare and offers `when deleted`. A bare static union is bare the same way: entry is creation of an operand instance (§9).

### V4 — Boundary/apparatus, both ways

(§11, §18.) `after commit` without a dischargeable guard plus a backstop schedule is the stranding error ("this firing can be lost at the declared boundary, and its trigger is not data"); guard-plus-backstop on a plain `on commit` rule is dead machinery ("serves no boundary — did you mean `after commit`?"). A capture-reading rule is checked by V7, not here.

### V5 — Freeze disjointness

(§8.) For every write to a field a `frozen` clause names: prove the writer's trigger cannot coincide with membership in the freezing refinement (pre-state membership gates the write; the entering commit may still write). V1's machinery re-aimed; fail-closed; connected diagnostic names writer and freeze. A freeze no writer could ever violate is flagged dead (see A2).

### V6 — Capture entry-evaluability

(§8.) Every reference in a captured expression is guaranteed by the refinement's own predicate or unconditionally present on the base shape. Also classifies each refinement as drift-enterable vs act-entered (derived from the predicate, never declared).

### V7 — Capture transaction-boundedness

(§13.) A rule reading a leaving refinement's captures must fire within the exit commit's transaction: `after commit` or a schedule backstop on such a rule is an error in the stranding family ("reads `x`, which does not survive the boundary it declares"). Derived properties of the left refinement are unreadable in exit bodies.

### V8 — Fold obligations and tolerance coverage

(§19.) Classify each assignment RHS (act-only / recomputing / self-referential fold); for folds, compute hazard exposure per axis (duplication: unguarded + re-firable; reordering: tick-cadence) minus the insensitivity whitelist (`max`/`min`/set-union/or; `+`/`-` order-safe — subtractions commute with each other) minus discharges (guard V2, commit-cadence ordering, reconciliation sweep, declared tolerance). Undischarged hazard → error demanding a stated policy. A tolerance covering no exposure is dead (A2's family): "this rule cannot experience reordering — did you mean duplication?"

### V9 — Refinement overlap and exhaustiveness

(§8.) The predicate-level engine: can two refinements overlap; does a set of refinements partition a base shape. Consumed by V1, V5, and rejection-as-data patterns (an uncovered subset is the forgotten-`catch` error). Fail-closed where predicates aren't decidable.

### V10 — `never` obligations and spend-tracking

(§21.) Per prohibition, derive an obligation per potential violator: a guarantee → inductive proof over the statically-known writers (violator → connected diagnostic naming act and prohibition); a refusal or constraint → rejection at the runtime, at the door when no rule can move what the predicate reads and at the transaction end otherwise (`evaluation.md` "An exposed call"). Only a fully-discharged prohibition is *spendable* as a proof input by V1/V5/V15/V16; track the dependency so retracting a prohibition re-opens what spent it.

### V11 — Narrowing analysis

(§10.) Plain `.` through an optional requires provable non-absence — narrowed by `is some`/`is none`/`is Refinement` earlier in the same conjunction or the corresponding conditional branch; otherwise error (the escape is `?.`). Same analysis licenses refinement-property access after `is Refinement`. *Built in v0 for the union slice* (§9): `path is Operand` in a conditional's condition or to the left of an `and` licenses the operand's own properties on that path in the then-branch or the right conjunct, and the else-branch of a two-operand static union narrows to the other operand; the optionality slice, `not (x is A)`, and nested forms remain unimplemented.

### V12 — Singular-reference proofs

(§10.) `(Shape for expr)` and bare `for`-sugar are legal only when exactly one field type-matches *and* at-most-one instance is provable — from a guard refinement elsewhere in the spec (the whole-spec singularity proof, §20 "Episodes as data") or a to-one inverse. Otherwise: demand `latest`/`first` over a `where`-filtered collection.

### V13 — Ambiguity is a connected diagnostic

(§14, §22.) A field typed with a static union type-matches an expression of any operand's type (§6). A declaration change that creates a new type-match ambiguity for an existing bare `for` reference (a shape gaining a second `Customer`-typed field) is reported as one diagnostic naming the declaration *and* every reference it broke — an incoherence of the spec, not a syntax error at one line.

### V14 — Well-foundedness (*coarse*)

(§19.) Stratify the definition graph (derived properties, refinements referencing refinements); acyclic parts pass free. Each static cycle owes a certificate from the whitelist: strict descent on a creation-fixed datum plus a base case (`streakAfter`), or acyclicity supplied by a spent `never` (`root`/`parent`). No certificate → error; no fixpoint semantics ever attempted. Ties on an ordering datum where the result depends on them: fail closed, fix is model-side. Growth of the certificate whitelist is post-v0 calibration.

### V15 — Confluence (*coarse*)

For sibling firings of one commit with no data dependency, three legs. **Read-write**: one sibling's body reads what another writes — fields, existence and aggregates over shapes it creates, timestamps its writes advance, opaque summaries as reads-anything; condition reads are exempt (subjects are pinned per commit — a flip fires at its own commit, as causality). **After/after**: two after-commit followers of one commit run as independent transactions in unspecified relative order, checked on the full summary (their conditions re-check at drain). **Transition interference**: two siblings can each flip an observed refinement's membership — by writing predicate inputs or by creations, at the consult's polarity — and not provably in the same direction; observers are watching rules and the refinement's own captures, and a capture-value leg checks captured reads against sibling effects. Pairs are gated by co-firability (`canCoFire`: a rule gains a subject only where a commit can flip its condition in the firing direction; a fresh instance satisfies no positive correlated `exists` over a shape the commit doesn't create, and never leaves anything) and by entrant/leaver exclusivity (the episodes discharge). Any unproven pair → error naming both rules and demanding the intent, not an ordering. Remaining value-dependent cases fail closed; discharge via spent prohibitions (V10) where they apply — the direct-case instance-aliasing discharge (routes differing by a to-one self-hop, a spendable `never (S where hop == this)`) is built.

### V16 — Quiescence (*coarse*)

The static condition graph (rule effects → conditions they can newly satisfy) must be a DAG, or every cycle must be broken by a disarming guard (V2's proof reused: some rule in the cycle provably exits its trigger state). Edges use the summary's full read vocabulary — consulted shapes base-normalized, collection consults, advanced timestamps, opaque as affects-everything. Value-dependent convergence (parcel-splitting) fails closed. The runtime depth backstop (`evaluation.md` S3) sits behind this proof, never instead of it.

### V17 — Transient isolation

(§4 "Transient acts".) A `transient` act exists only within its own commit's transaction, so nothing durable or later may depend on it. A cross-base union with a transient operand is refused outright (§9): the rule would fire per act, but every read is a branch per operand and nothing durable may reference the act — one declaration for the acts to inherit is the mixin's job (OQ35). Errors: a stored or derived property typed `one`/`many` of a transient shape, anywhere (outcomes copy the act's fields instead — no references, hence no inferred inverses); the transient shape's name appearing in any expression (collection root, `exists ... for`, `(X for expr)`, aggregate) — even inside its own refinement family, since that is a cross-act read; `when leaving` over a refinement of it (there are no exits, only the one evaluation); an `after commit` preposition or any schedule trigger on a rule whose subject is the transient act (the act is gone when they run — asynchronous work hangs off a materialized durable intent).

### V18 — Transient totality: every request gets a response (*coarse*)

(§4 "Transient acts".) A transient act that nothing answers in some reachable state is ignored with no record it ever arrived — so for each transient shape, the *answers* must provably cover every instance in every state, where an answer is a rule's condition **or a `never` over the act**: the boundary refusal is a response (pure validation — the caller is told, nothing is kept; the rung below the refusal-record spelling). The v0 slice fails closed: coverage is proven when some answer is the bare act shape, or two answers' predicates are syntactic complements (`P` / `not P`) — rule/rule or rule/`never` pairs alike. Everything else — including per-reason refusal enumerations — is rejected; the prescribed v0 idiom is one complement rule whose reason is a conditional value. Note the moments: a rule's partition evaluates at the act's commit, while a `never` constrains the settled transaction (§21), evaluated at the door when the request's handling leaves what it reads alone and at the settled transaction otherwise — so for an atom the act's own consequences can flip, the `never` is the stronger statement, refusing even the act that would create the configuration. The full proof is V9's exhaustiveness engine, once built. The diagnostic states the business problem plainly: which act, unanswered, unremembered.

### V19 — Relationship declaration coherence

(§6.) The declared side owns a relationship; the inverse is inferred. Errors, each a connected diagnostic naming both declarations: **both sides declared** — a shape declaring `many B` while `B` declares `one A` (or two bare `many` declarations at each other) is either two distinct relationships or two owners of one edge set, and the compiler refuses to guess — the fix names the intent (drop one side, or make the collection a derived view with `= (B where ...)`); **ambiguous inference** — two `one` fields of one shape sharing a target means the decapitalized-pluralized inverse name has no single meaning, so no inverse is inferred for that target and each use site demands declared derived views (§6, the `Transfer` example); **inference collision** — an inferred inverse name colliding with a declared property of the target shape is the same ambiguity, resolved the same way.

### V20 — Collection assignment legality

(§6, "Committing and assigning collections".) A whole-collection assignment's target must be a *declared* `many` — the stored edge set; an inferred inverse or a derived collection is a view, never a target (diagnostic points at the fan-out or derived spelling). A collection-path assignment traverses exactly **one** `many` hop and writes a stored field of the member shape (`this.invoices.customer`, never `this.invoices.customer.tier` — the deeper write is its own rule on the shape that owns the field); the written field follows all ordinary rules (freezes V5, one-writer V1 with its fan-out extension).

### V21 — Act field forms

(§22 "External input".) A shape declared `act` is an external submission, and its declaration forms say what the committer supplies: every stored field is a field of the act's generated input value, and derived properties never are (nothing is stored, so nothing can be supplied). Errors: an `initially` clause or a `timestamp` declaration on an act — a system-maintained value is no part of an external submission; it lives on a `shape`, supplied by a materializing rule's `from` block, minted by `initially` at that record's creation commit, or populated as `timestamp` commit metadata (§4's transient materialization is the served spelling). The diagnostic is connected: it names the act and the offending field, and prescribes the split.

With a closure (`act ... with`, §6 "Inline part creation"), the inline parts extend the signature: per part, a nested input value carrying every stored field minus the language-populated back-reference — never `id`, never the back-reference (a claim about an instance that does not exist yet); out-of-closure relationships stay reference parameters, and the empty part collection is the absence. Input-side part collections are bags — two identical part values mint two distinct instances — so the duplicate refusal applies to references only.

### V22 — Closure declaration legality

(§6, "Inline part creation".) A `with` entry must resolve, on the enclosing level's shape, to an inferred inverse or a declared view of exactly the recognized-inverse form (`(P where field == this)`); an arbitrary-predicate view is an error — "a closure edge must be the inverse of a declared `one` field." The recognized view pins the language-populated back-reference (the `Transfer.source`/`target` situation; V19's demand-intent posture). The closure graph is a tree by grammar — `with` only names inverse edges pointing at the enclosing level, so sibling references are unspellable declaration-side — and an in-closure reference targeting an instance created in the same closure is refused: "commit it and name it in a later act, or restructure." The closure is creation-only: it carries references and inline creations, nothing update-shaped. A `transient act` with a closure is refused — fail closed while OQ43 is open.

### V23 — Delete statement legality (*coarse*)

(OQ37, settled; Reference §12 "Deletion".) The `delete` target is a literal static to-one path resolving to a known, non-transient shape — a transient act is an input to the state, not a member of it, so there is nothing to delete; a collection anywhere in the path is a refused fan-out (a per-member delete is its own rule). One deleter per instance per commit: two deletes of one target in a body, or two rules deleting one shape with non-disjoint co-firable triggers, is the coincidence error. A commit never both writes a field of an instance and deletes it — there is no business sentence "change it and also remove it, at once" — refused same-body at path granularity and cross-rule at shape granularity. Deleting `this` counts as the structural disarm for V2 and V16 (the trigger state loses its member).

### V24 — Referential completeness (*coarse*)

(OQ37 — cascade as a completeness check, never `ON DELETE CASCADE`'s transitive magic.) Deleting an instance that required `one` references point at demands every referrer resolved: deleted in the same commit (v0's coarse discharge: the same body also deletes the referrer's shape), declared `? initially required` (the absorbing reference — it goes absent at the target's deletion, no write anywhere), or restructured to per-field copies. Optional and `many` references absorb; transient referrers read the target within the deleting transaction as last readers and need nothing. On the deletion-side triggers (§13 "Deletion as a second exit cause": `when deleted X` and `when leaving or deleted X`) the rule's product may not keep a reference to the subject — it copies fields (OQ37-R14); a predicate-exit body may keep one.

### V25 — Existence-dependency (*coarse*)

(OQ37's genuinely new check — existence is spent in proofs.) Two v0 slices: a deleter of a shape some rule's guard reads through `not exists` re-arms the guard and the rule re-applies (the double-apply hazard, C6); a deleter of a singular reference's shape can strand `(R for ...)` mid-episode (C8). Discharge is **the shared refinement-overlap disjointness prover and nothing finer** (ruled conservative, 2026-08-14): the delete scope — the deleting rule's condition for `delete this`, the asserted `is <Refinement>` predicates for a path target — must carry a syntactic complement of the read's predicate. The accepted false positive (C7, window arithmetic) discharges by restructure — the field-witness guard — never by signature: guard re-arming is **not `tolerates`-signable** (ruled, 2026-08-14; intentional re-triggering is reversal-as-data, cleanup is disjointness or OQ27 retention). A prover sharpening is a backward-compatible relaxation riding OQ16.

### V26 — Deleters join `never` induction (*coarse*)

(OQ37; §21.) Deleters are a class of state change every prohibition's inductive proof must range over. Deleting an instance of the `never`'s own base only shrinks the forbidden set — safe; a deleter of any other shape the predicate consults can flip membership either way, and v0 fails closed on it. The same posture reaches V10's spend-tracking: a `never` whose foreign consults have deleters is not spendable, and V12's anti-monotone proof fails when a `not exists` witness has a deleter (re-entry by deletion).

### V27 — The deletion gate

(OQ37-R1/R9; §8's freeze machinery re-aimed at existence.) `undeletable` is a state-scoped, `frozen`-sibling refinement clause — deletion permission scoped exactly as write permission, deliberately *not* implied by `frozen` (different business sentences, OQ37-R2). Every deleter of the base must provably exclude membership — the same fail-closed disjointness proof and connected diagnostic; the fix-it idiom is the partition (hang the deleter off the deletable subset; refusal lands as data). The gate's polarity is negative, no second polarity (ruled 2026-08-14); positive-exhaustive sentences ride the `states of` construct when it lands (§22). A gate no deleter could trip is dead machinery (A2).

### V28 — Deletion stranding

(OQ37; the V17 mirror.) An instance becomes transient at its final commit: rules fired by the deleting commit are its last readers, and nothing after the transaction's close may read it. A `when leaving` rule over a deletable shape with an `after commit` boundary or tick backstop can be handed a deletion's leaver — a subject that does not survive to its firing — and errors unless the delete scope is provably disjoint from the left refinement. Durable reactions hang off the outcome record the deleting commit produced (the explicitly modeled deletion record; no built-in tombstone, OQ37-R6). The deletion-side forms are transaction-bound by construction — `when deleted X` and `when leaving or deleted X` refuse `after commit` and schedule backstops outright, since a record kept to be read later is the deletion memory OQ37-R5 forbids (OQ37-R14).

### V29 — A firing with no caller joins `never` induction (*coarse*)

(§21; `evaluation.md` "Ticks".) A rule on a schedule, or behind the `after commit` boundary, fires in a transaction of its own with nobody asking. A constraint that rejects the firing has no caller to answer; the rule's guard never disarms, because its effect never committed, and the same subject is refused at every tick. The spec instructs and forbids the same record for that subject. A record the firing creates whose shape the prohibition consults is the case — the prohibition's own base included, since creation can grow the forbidden set where deletion (V26) only shrinks it — and v0 fails closed on it, as V10 does for assigned fields: the discharges are a condition the prohibition cannot reach (restructure, since v0 proves no implication over the predicates), restoring the books within the firing, or retracting the prohibition. A commit-triggered rule is not checked here: a rejection inside the act's transaction answers the act, and its caller is told. A refusal — a prohibition over a transient act (§21) — is not consulted here either: it binds the caller at the request, and no instance of the act exists at a tick or behind the after-commit boundary for a firing to leave in the forbidden state; only constraints and guarantees join. The clock is such a writer too (V30). What validation never saw stays at the runtime: a record that arrived through a legacy mapping and aged into a constraint meets a firing on that state, and the tick throws the rejection to the harness.

### V30 — A constraint that reads the clock (*coarse*)

(§21; ruled 2026-10-14.) Time advancing is a commit — the tick — so `today` and `now` are data with a writer, and that writer fires with nobody asking: V29's case with the clock as the writer. A `never` or `requires` on a persistent shape whose predicate reads the clock, directly or through a refinement it consults, can be violated by aging alone, with no act to refuse; and since the end check collects every violating record, two aged records make the books unrepairable from inside the system — the act that repairs one is refused for the other. v0 proves nothing about which direction time moves a predicate, so it fails closed, and the harmless kind (`never (Planting where plantedOn > today)`, which aging can only make less true) is refused with the rest. The discharges, named in the diagnostic: state it on a transient request, where a refusal reads the clock once at the moment of the act (`never (PlantTrial where trial is not PermittedTrial)`; V3's stance for a request's time-dependent partition); record the decision as a field the prohibition reads instead of the clock; or retract it, when the sentence is the state the business acts on rather than a prohibition (an `OverdueInvoice` refinement and a scheduled rule). A refusal — a prohibition over a transient act — is exempt. *Implemented in v0*; `ValidatorTest` and `TickRejectionTest` carry the exhibits, the latter also running the refused spec unvalidated to show the runtime backstop a legacy-mapped record would meet.

## Advisories

### A1 — Rung recognition

(§20.) Classify each rule on the derivation → reconciliation → exactly-once spectrum; verify rung-specific properties (a reconciliation sweep is convergent and idempotent; a latch pair covers both directions); surface guidance ("this stored flag could be a pure classification"; "this incremental fold has a recompute twin, §19"). Advisory only — required diagnostics remain the fail-closed ones. Calibration of the advisory/required line is deferred until realistic specs exist (§20).

### A2 — Dead machinery

(§8, §9, §18, §19.) The family of "provably serves nothing" findings that aren't errors elsewhere: a freeze no writer could violate ("serves no writer"); `and` composed across distinct bases, provably empty since an instance has one base (§9); an `undeletable` gate no deleter could trip (V27's dead case); an `? initially required` field nothing can make absent — no deleter of its target, no writer of the field — which is `one X` wearing a costume (OQ37-R10's converse diagnostic); the inert `not deleted` half of `when leaving not deleted X` where no deleter reaches X, and the inert `leaving` half of `when leaving or deleted X` over a bare shape (OQ37-R17/R19). The delete-side members are *implemented in v0*, surfaced via `Validator.advisories(...)`. The required members of the family (dead tolerance, dead guard apparatus) are in V8 and V4.

### A3 — Impact analysis surfaces

(§11, §20.) Not a check but outputs the checks already compute, exposed for tooling: per-commit "these rules may fire" (forward), per-rule "fires as a consequence of: …" (backward, the PO-facing answer to "when does this run?"), and rung labels for `why`-style tooling.

### A4 — Drift-exposed act partition

(§8's rejection-as-data; worked exhibit `examples/partition-drift/`.) A rule triggered by a partition of an act on an `is <Refinement>` atom, whose body does not disarm its own trigger. Acts persist, so the partition is re-evaluated at every later flip of that state: previously handled acts drift into the other side (a spurious firing per flip) and drift back (stale re-fires). Legitimate drift-reactive rules — the compensation pattern, windowed sweeps — pass, because they disarm; the disarm proof doubles as the handled-anchor. The fix idioms: mark the act `transient` (§4 — the partition then evaluates exactly once, at the act's only commit, and drift has nothing to attach to), or scope the partition to *unhandled* acts, each side anchored by the outcome evidence its own rule produces (the persistent-act spelling, e.g. payments' `UnhandledAddressChange`). Transient shapes are skipped — the hazard cannot exist for them. Advisory rather than required: per-flip re-firing has well-defined semantics and is occasionally intended. *Implemented in v0* — the first A-series member, surfaced via `Validator.advisories(...)`, never in the required set.

### A5 — Contention width

The serialization-domain derivation (`Domains.kt`) attributes every read and write of every act envelope to a queue key; a read with no path back to any key widens the domain to the whole shape — every commit touching that shape joins a single queue. An unexamined width warns, naming the read and the declaration carrying it, and lists the discharges: correlate the read (a model fact — the relationship the model was missing), move the rule to a schedule (the read then runs once per tick instead of inside every commit — a width living only in a schedule-fired rule's own firing never warns), or declare `tolerates contention` on the declaration whose read causes the width (a rule's header, or a `never`). A tolerance covering no width is dead and flagged, like an impossible `reordering`. Advisory by design: the failure a wide domain causes is throughput, not a wrong value, so fail-open costs less here than anywhere else in the tolerance family; the two author situations the advisory weighs — the intended width and the accidental one — are recorded in `patterns.md` ("A wide domain: the two authors"), and a stress test during post-v0 calibration may still move the dial. *Implemented in v0* — with the contention map (`diagrams.md`) and the per-commit-function queue-key contract as the companion surfaces, and `CommutationTest` as the derivation's falsification harness (derivation-disjoint envelope pairs must commute under order-swap in the reference evaluator).

### A6 — Deleter reaches a bare `when leaving`

(OQ37-R18; §13 "Deletion as a second exit cause".) A `delete` whose scope is not provably disjoint (the shared prover, V25's posture) from the state of a bare `when leaving X` rule. Not an error: under the settled semantics the rule's meaning is defined — the predicate flipped, never deletion — so nothing is ambiguous; what the compiler can see is an omission hazard, an author who meant "for any reason" getting a rule that silently stops at deletion. The advisory names the rule and the deleter and lists the discharges: state the cause with an explicit form (`when leaving not deleted X`, `when leaving or deleted X`, or a separate `when deleted X` rule), or restructure the deleter to provable disjointness (the V25/V27 pattern). The explicit forms never draw it — the form is the statement. Deleters are statically known (V23), so it fires only when a reaching `delete` actually exists. *Implemented in v0* — surfaced via `Validator.advisories(...)`; `TriggerFamilyTest` and `LeavingRuleDeletionProbeTest` carry the exhibits.

