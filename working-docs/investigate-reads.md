# Investigation: reading the system — the spec is the schema, the caller composes the query

**Status:** built (2026-10-12) — rulings R1–R11 below, implemented the same day: the read protocol and engine in `compiler/src/main/kotlin/velle/Read.kt`, the runtime's `check`/`query`/`count`/`sum` beside `commit`, the text operators through parser, validator, evaluator, query compiler and SQLite, and the codegen's data classes, fields classes, typed queries and registry; exercised by `examples/billing/output/src/test/kotlin/velle/QueryApiTest.kt` in memory and over SQLite through both store layers, and `compiler/src/test/kotlin/velle/TextOperatorsTest.kt`. The views collapsed into model objects and one reference type the next day (2026-10-13; section 1). What is not yet built is in `TODO.md`: a registered query's typed-store method and generated test, the generated specs' ordering datum, and collection filters in the builder. This is the parent of `investigate-search.md`: search was the first read-side behavior worked through, and it turned out to be one case of a general question. The general question is answered here; search stays in its own doc as the special case. Eleven questions for Dave at the bottom (section 11).
**Question tag:** [OQ51](questions/OQ51-reads.md)
**Rulings so far:**

| tag | date | ruling |
|---|---|---|
| R11 | 2026-10-12 | question 11: the first build is exercised against `examples/billing/`; all eleven questions are now answered or deferred, and the doc moves to implementation |
| R10 | 2026-10-12 | question 9: a stable row order is required and, for now, every query specifies it — orderable datums with directions, the hidden store key breaking ties (R2); a query naming no order is refused; the store promises no scan order (the store-pass item closes on that side); whether a shape may declare a default order a query inherits is a `TODO.md` item |
| R9 | 2026-10-12 | question 6: a query is registered in Kotlin code — the engineer writes it with the typed builder and registers it under a name; Kotlin checks the names, the framework checks the rest when it builds the registry, and the four registration effects follow (checked at build, a named typed-store method, a generated test, breakage named at build) |
| R8 | 2026-10-12 | question 5: the first build ships without authentication or visibility enforcement; the viewer slot is present in the read request so nothing changes shape when OQ33 lands |
| R7 | 2026-10-12 | question 1: the spec never declares a query; every query is composed or registered by whoever asks it, from the spec's declared vocabulary; promoted to README §22, which also closes its deferred sentence — `act` is strictly input |
| R6 | 2026-10-12 | question 2: all three spec-side query constructs are dropped — a refinement with parameters, a derived value outside any shape, a default `by` order on a refinement; each stays on file in this doc and `investigate-search.md` as the remedy for the one case that would justify it. Nothing enters the language |
| R5 | 2026-10-12 | question 3: a query returns the shape's generated Kotlin data classes — whole instances, every stored and derived field, references as typed handles navigated from the instance — plus a cursor; the request carries no field selection. The protocol-level value underneath stays generic. How associated records are loaded will be configurable in the query API later, following GraphQL's lead — the query names the relationships it wants loaded, nested, and the rest stay handles; a `TODO.md` item |
| R4 | 2026-10-12 | question 4: a read is a read-only snapshot transaction — U1 and nothing else, never queued, commits nothing (promoted to `evaluation.md`, "A read is a read-only snapshot"); isolation levels for reads are a `TODO.md` item |
| R3 | 2026-10-12 | question 7 deferred, and the scope of question 11 narrowed: no REST or GraphQL layer is being built; what is built is the Velle read/query binding API — the generic read entry beside `commit` and the typed Kotlin builder over it. A wire form for filters returns with the first out-of-process binding |
| R2 | 2026-10-12 | question 8: a page cursor carries the last row's order datums and its store key as a hidden tie-break; the key is never exposed as an ordering datum (README §5 stands); every binding gets stable pages from the framework, once |
| R1 | 2026-10-12 | question 10: the text operators `contains`, `starts with`, `ends with`, and `like` (SQL-style pattern: `%` any run, `_` any one character, `\` escapes) join the predicate vocabulary, exact and case-sensitive (README §5, §10; `grammar.md`); widened the same day from `contains` alone at Dave's direction |
**See:** README §4 (what "read" means), §5 (`id` is unordered), §6 (references and inferred inverses), §7 (derived properties), §8 (refinements), §10 (predicates, selectors, the mandatory `by`), §22 "Current milestone" (reading is generated typed accessors) and "External input (`act`)" (whether `act` also names a read API) · `working-docs/investigate_runtime.md` §6 (the candidate pre-filter and the one-evaluator line), §10 (the typed store surface), §11 (the query surface's order over a store) · `working-docs/investigate-search.md` (the special case) · [OQ33](questions/OQ33-visible-to.md) (who may see what) · `diagrams.md` (the projection principle: every generated artifact is a projection of the one spec)

---

Every spec written so far is written from the write side: what enters, what changes, what reacts. The read side has one sentence in the README (§22: reading is generated typed accessors, not a generic query API) and one deferred question (whether `act` also names a read API). "Query" throughout this doc means one read request: a root, which fields, a filter, an order, a page. "Read API" means the set of calls an engineer or a client can make to get data out of a running system, as opposed to the acts that put data in; the README and the runtime docs call the same thing a "surface" (the generated typed surface, the query surface), and the two words mean one thing. Dave's intent is broader than either: any shape can be read by an engineer making a parameterized call to the system's API, and the model for that is GraphQL. A GraphQL server owns one schema; clients compose their own queries against it, selecting the fields they need and traversing the relationships the schema declares; nobody writes a read per screen, because a read per screen couples each screen to the backend and the two must then be kept in sync by hand. Velle already owns the schema: the shapes, their properties, their relationships, their refinements, and the Kotlin types generated from them. The read API should be derived from that and declare nothing twice.

This pass works out what that means. Verdict scale as in `break-pharmacy.md`: **HOLDS** (the design handles the case), **TAX** (handles it at a cost), **GAP** (needs a new obligation or fails). Read kinds are labeled K1 through K11 and are all listed in section 2; rulings are labeled R1, R2, ... in the table above.

## 1. What "read" means in Velle today

README §4 defines it: a read is any predicate or derivation evaluated against current state, and it commits nothing. Everything below rests on that sentence and on what the generated read API already does.

**The read API has two layers, and both exist.** The per-spec accessor (`examples/billing/output/src/main/kotlin/velle/generated/Billing.kt`) gives, for every shape, a list of all instances and a lookup by key (`invoices()`, `invoice(id)`); for every refinement, the member list and a membership test on a view (`overdueInvoices()`, `InvoiceView.isOverdueInvoice()`); and on every view, each declared property, each derived property computed at the read, and navigation along declared references and their inferred inverses (`InvoiceView.customer`, `InvoiceView.lineItems`). Beneath it, the typed store (`examples/billing/output/src/main/kotlin/velle/generated/BillingStore.kt`) carries keyed reads, a read of all rows per shape, a join read per to-one field (`invoicesByCustomer`), and one named candidate method per question the spec asks, whose parameters are the evaluation-moment constants the question recognition extracts (`remindOverdueCandidates(dueBefore, sentOnAfter)`, `investigate_runtime.md` §10). Beneath both is the generic protocol the runtime speaks, with shape names as strings (`instancesOf("Invoice")`).

**The views were already the schema's types.** When this pass began, `InvoiceView` had a getter per declared and derived property that read the running system each time it was touched, a typed reference per `one` field, and a typed collection per `many` field and inferred inverse. Generated from the spec, so a spec change regenerated them and stale engineer code failed to compile. That is the projection principle (`diagrams.md`): one authored spec, every artifact a projection of it. The read API is one more projection of the same spec. *Built, 2026-10-13:* the views are gone. Reading through a view's getters was a second, live read path beside the query's snapshot, and Dave ruled it out once the query API existed; a per-shape handle class replaced them for a day and was duplicate code. What stands: the shape's data class is the model object, every field read once; it is itself a `Ref<T>` to its record, one reference type for both sides of the store boundary, so it passes wherever a reference is taken and equals any reference to the same record; a reference field is a `Ref<T>` and `read(ref)` resolves it; membership of one record is a flag on the model object (`invoice.isOverdueInvoice`); a store key fetches one instance (`read.invoice(k)`).

**What a read sees.** A read evaluates over settled state: the state after the last envelope that completed, never a state in the middle of one (`evaluation.md`, the universal transaction, U1 and U2). A read does not queue. An act is serialized against every act whose domain intersects its own (U3); a read is not an act and waits for nothing, which is the structural reason `investigate-search.md` section 3a rejects spelling a search as an act. Evaluation-moment constants (`today`, `now`) bind at the moment of asking.

**What a read takes today.** A key, or a declared reference reached by navigation. No read takes a filter, an order, or a value from the asker. The accessor is a set of fixed queries; what GraphQL would call the query layer, where the caller composes, does not exist.

## 2. The catalog: what an application reads

Eleven kinds of read, in business words, each with where it stands. This list is the roster for every R-label in the doc.

- **K1 — one record by identity.** "Invoice 4711." HOLDS: the by-key lookup.
- **K2 — the records related to one record.** "This customer's invoices; this invoice's payments." HOLDS: navigation along declared references and inferred inverses (README §6), and the typed store's join reads.
- **K3 — a declared subset.** "The overdue invoices." HOLDS as a set: the refinement accessor. In no promised order (section 6).
- **K4 — a subset the asker shapes with values.** "Invoices due before a date I choose; products matching what I typed; this customer's payments between two dates." GAP: nothing composes a filter, and no value from the asker reaches a predicate. The center of the search pass (`investigate-search.md` F1) and of this one (sections 3 and 4).
- **K5 — whether one record is in a subset.** "Is this invoice overdue?" HOLDS: the membership test.
- **K6 — a number about the whole system.** "Open receivables; revenue this month; how many invoices are overdue." GAP today: a caller cannot count or add up what it reads at the read API, and a formula can only be written in the spec as a property of a shape (README §7, an invoice's `balance`). Resolved as a query the caller composes or registers, never a spec declaration (section 5).
- **K7 — a number per group.** "Revenue per customer; hits per category." HOLDS when the group is a shape: a derived property on `Customer` summing its invoices. When the group is a value rather than a shape (a category name in a `many text`), the caller asks for the count or sum once per group (section 5).
- **K8 — who may read what.** "A shopper sees sellable products; a patient sees their own record." Open as [OQ33](questions/OQ33-visible-to.md); section 7 says what this pass adds.
- **K9 — a record as it was.** "This invoice's due date before the change." By design: state is current (README §4). Where history matters the author models it as data, the ledger and episode patterns (README §12, §20), and then it is K2 or K4 over the history records. A read "as of" a past moment over in-place fields is not a read of the state and the language does not offer it.
- **K10 — why a record is what it is.** "Why is this invoice overdue?" Deferred: README §22's `why` item.
- **K11 — what the system refused.** "What happened to my request?" HOLDS: the commit function's result carries the refusal (README §22), and a refusal the business keeps is a record the caller reads back (`patterns.md`, "Validation rejection is data").

K4 and K6 are gaps and K3 carries an open promise about order; the rest hold, are open elsewhere, or are outside by design. The pass is therefore about where a query is composed (section 3), what the caller may compose from (section 4), counts and totals (section 5), order and pages (section 6), and the viewer (section 7).

## 3. Where a query lives: the spec owns definitions, the engineer owns queries, one checker serves both

There are two things an application's read can contain, and they belong to different owners.

**Meaning** is a definition the business would state and defend: what *overdue* is, what *sellable* is, what a *verified adult* is. Meaning belongs in the spec, named, once, because a rule, a prohibition, and a screen must all agree on it, and because the product owner reads it there. The spec already has the constructs for it: refinements (README §8), derived properties (§7), and composition (§9).

**Asking** is which of the declared things this caller wants, in what order, how many at a time. To *compose* a query, throughout this doc, is to assemble it at the moment of asking out of names the spec declares, the way a GraphQL client writes a query against a schema; a composed read is one nobody wrote down in advance. Asking looks like this: "the overdue invoices for this customer, oldest first, twenty per page, with the customer's name and each line item's amount". Asking belongs to the engineer who builds the screen, because it changes per screen and per client, and a backend that holds one fixed query per screen is the coupling GraphQL exists to remove.

**A query gains nothing by living in the spec.** The first draft of this pass proposed spec-side constructs for queries: a refinement with parameters, a formula outside any shape, a default order on a refinement. Dave's objection, which this draft adopts: a query names the spec's fields wherever it lives, so renaming a field means fixing the query either way, and the only things that differ are who owns the text and when the break is found. What would justify the spec holding a query is a proof the query could take part in, and there is none. The proofs that make the spec worth its weight (one writer, disarm, confluence, quiescence, README §12 and §18 through §21) are about writes; a read changes nothing, so none of them applies to it. What a read *does* get from the spec is the spec's definitions, and those reach every query checked against the model no matter where the query's text lives:

- **Type checking** of every name, comparison, traversal, and order datum, with the same rules a refinement's predicate gets (README §10).
- **Definitions by name.** A query says `is OverdueInvoice` and gets the spec's meaning of overdue; it reads `balance` and gets the derived property computed at the read (README §7).
- **Visibility** (section 7, [OQ33](questions/OQ33-visible-to.md)): declared once in the spec against the viewer, enforced on every question.
- **Freshness.** A query that reads a column the spec maintains on a cadence (README §19's snapshot rung) can be told the staleness it accepts, from the spec's own `on Hourly`.
- **Coverage.** A query that selects membership of several refinements the spec proves partition a shape (README §8's exhaustiveness check) is a screen that shows every state; one that selects a subset is a screen with a hole, and the checker can say so.
- **Provenance**, when README §22's `why` lands, for any value a question returns.

None of these needs the query's text in the spec. They need the query checked against the model, which is a moment, not a place.

**Three places a query can live, one checker, three moments.**

1. **Composed at request time.** Fully dynamic; checked on arrival; rides the generic query path. The GraphQL and REST bindings of section 9 do this for every request.
2. **Registered beside the client.** The engineer writes the query in the client's language (a GraphQL document, or the spec's own predicate syntax) and registers it with the system. At the engineer's build the framework checks it against the model with the same checker, enumerates it for the typed store as a named candidate method the engineer can override with tuned SQL, generates a test for it, and, when the spec changes, fails the build naming each registered query that broke. This is GraphQL's persisted query with Velle's checker behind it: the spec never sees the query, and the query never drifts silently. A real application puts most of its screen queries here.
3. **Declared in the spec.** Definitions only: refinements and derived properties that a rule, a prohibition, or several queries share. Never a query. A refinement no rule or prohibition reads, written so a screen can ask for it, is a query wearing a definition's clothes and belongs in tier 2.

All three produce the same read-protocol value (section 9) and are checked by the same code. The author's only choice is the tier, and the test is the ownership line above: meaning in the spec, queries with whoever asks them.

**Where the search pass's constructs land.** The parameterized refinement (`investigate-search.md` section 3c, `shape ProductSearch(terms: text, ...) = Sellable where ...`) is dropped from the proposal. Inside the spec the inputs to a definition are always some record's own fields (`SavedSearch.newMatches` reads `this.terms`), so no parameter is needed there; for the caller, the matching predicate is a registered query. The one cost is that "which products match a term" is then spelled twice, once in `SavedSearch` and once in the client's registered query, which is the duplication an engineer maintains in GraphQL today, accepted here for the same reason. If a real spec finds that cost too high, the parameterized refinement is the remedy on file. `contains` stays (`investigate-search.md` decision 4): the filter language *is* the spec's predicate language, so vocabulary added for the spec's predicates is the query language's the same day. Delegated matching stays as the search doc states it.

This closes README §22's deferred question whether `act` names a read API: it does not. `act` is input. A read is a protocol value checked against the schema the spec already is.

## 4. The composing vocabulary, and the read API derived from it

Nothing in this section is declared by anyone. It is read off the spec.

**What the caller may say**, for a read rooted at any shape or refinement:

- **Return**: whole instances of the root's generated Kotlin type (R5): every stored property, every derived property computed at the read, and a refinement's body properties when the root is that refinement. References are typed handles navigated from the instance. There is no field selection.
- **Traverse**: along any `one` or `many` field and any inferred inverse (README §6), each traversal itself a read with its own select, filter, and order.
- **Filter**: a predicate in the spec's own predicate language (README §10) over the root: comparisons on declared and derived properties, `exists` over traversals, and membership of any refinement (`is OverdueInvoice`). The predicate language is the spec's, so a filter is typechecked against the same grammar and the same rules as a refinement's predicate; the caller cannot say anything a refinement could not.
- **Order**: by one or more orderable datums of the root, declared or derived, with a direction. The rule is README §10's for selectors: ordering comes from data, never from minted values, so `id` is not an ordering datum, and an order whose datums fail to discriminate is an unstable page (section 6).
- **Page**: a window over the ordered read.
- **Aggregate**: `count` and `sum` over a filtered root or traversal, the aggregates the spec already has (README §10); section 5.
- **Membership**: whether a given record is in a refinement, with arguments when it has parameters.

**What the read API generates**, as one more projection of the spec:

- **The types** are the views already generated (`compiler/src/main/kotlin/velle/Codegen.kt`, the view classes): one per shape and per refinement, a Kotlin type per scalar, references as views, collections as lists. They are the data classes a query returns (R5), and the schema a GraphQL binding would expose. Nothing is written twice: a spec change regenerates the types, and every stale use in the engineer's code is a compile error naming the field.
- **A typed query builder** per spec, in Kotlin, so the composition is checked at the engineer's compile time: the root is a shape or refinement accessor, the filter is a lambda in a generated predicate vocabulary with one method per property and refinement, the order names generated datum handles. Against `examples/billing/billing.velle`:

  ```
  // The collections screen: unpaid invoices for one customer, due by a date the clerk picks, soonest first,
  // with the customer's name and the line items' amounts, twenty at a time.
  val page = billing.read.invoices
      .where { balance gt 0 and due lte deadline and customer eq theCustomer }
      .orderBy(due.ascending, reference.ascending)
      .select { reference; due; balance; customer { name }; lineItems { description; amount } }
      .page(size = 20, after = cursor)
  ```

  The spelling above is illustrative; the point is that every name in it is generated from the spec and the whole expression is a predicate the spec's own compiler accepts.
- **A GraphQL schema and executor**, as a second binding over the same vocabulary, for callers outside the process: one object type per shape and refinement, one field per property and traversal, filter and order arguments generated from the predicate language, registered queries as named fields with their arguments, pages as connections. Worth stating as a binding because it is the shape of Dave's intent, and because the schema is free: it is the spec.
- **The generic protocol** is unchanged in kind. A composed filter is a predicate tree in the spec's language, and the runtime already compiles predicate trees to a candidate query under the superset contract and runs the authoritative check in memory (`investigate_runtime.md` §6). A read composed at the read API takes the same path as a rule's condition: the query compiler renders the column-expressible part to the store, the derived part drops out by polarity, and one evaluator decides. The one-evaluator line is not crossed, because the caller composes in the spec's language rather than in SQL.
- **The typed store** keeps its enumerated candidate methods for the questions the spec itself asks, and gains one per registered query (section 3, tier 2), each an override point for hand-tuned SQL. A composed read has no named method, because it is not enumerable; it rides the generic path, and its performance is the column-expressible part of what the caller composed. That is the trade a composed read API makes and GraphQL makes it too: the engineer who finds a hot composed read registers it and gets a method to override, which is the same move as persisting a query and tuning its resolver.

**What the read API does not let the caller do:** write, name a shape or property the spec does not declare, order by a minted value, or state a predicate the spec's grammar would refuse. Everything the caller can ask is a question the spec could have asked itself.

## 5. Counts and totals (K6, K7)

Two kinds of number appear on a screen, and under section 3 both are the caller's.

**A number nobody has defined.** "How many invoices match this screen's filter"; "the sum of the amounts shown"; "how many hits in each category". The caller already asks for the records; it can also ask for their count or the sum of one of their fields, over the same filter: `billing.read.payments.where { receivedOn gte from and receivedOn lt to }.sum { amount }`. That needs nothing from the spec. It covers the dashboard count and the facet count (`investigate-search.md` F8), and a number per group when the group is a value rather than a shape (K7): ask once per group.

**A number the business defines.** "Revenue" means something specific to the business: which payments count, which are excluded, over what dates. It must be written once so every screen agrees, and it is a registered query (section 3, tier 2) named `revenue`, owned by the engineer, checked at the build, enumerated for the store. The first draft proposed a declaration in the spec for it, a derived value outside any shape:

```
-- The remedy on file, not proposed: a formula that belongs to no shape.
value Revenue(from: Date, to: Date): decimal =
    sum(Payment where receivedOn >= from and receivedOn < to, amount)
```

It is dropped by section 3's argument: nothing in the spec can read it, since rules fire per record (README §16) and no example has a prohibition over a system-wide number, so it would be a query in the spec's clothes. It returns only if a prohibition ever needs a system-wide figure, at which point it is a definition.

K7, a number per group, needs nothing when the group is a shape: `revenue: decimal = sum(invoices, total)` on `Customer` is v0 as written, and that one *is* a definition, because a rule or a prohibition on `Customer` can read it.

## 6. Order, pages, and what a read promises

Three promises, two of them already made and one owed.

- **A snapshot of settled state.** A read evaluates over the state after the last completed envelope and sees no envelope in flight (`evaluation.md`, U1 and U2). Derived properties compute at the read. Evaluation-moment constants bind at the read. Already made.
- **No waiting, no side effects.** A read is not an act: it takes no queue key, waits for no envelope, and commits nothing (README §4). Already made. An application that wants to *record* that a read happened (the search log) commits a separate act after the read, as `investigate-search.md` section 4 spells it.
- **Order.** Owed. A collection is a set (README §6), and `instancesOf` reports the store's scan order, which `investigate_runtime.md` §11 left open as a possible contract obligation (`working-docs/TODO.md`, the item on a scan's row order). Under section 3 order is the caller's: named datums with directions, under README §10's rule that the datums must discriminate. A read with no order named is in no promised order, and the scan-order obligation is withdrawn rather than adopted: a store's row order is a minted order, and ordering comes from data. The generated specs that read `last(shape)` (`investigate_runtime.md` §11) are then relying on something unpromised and should name a datum, the same fix README §10 demands of a selector. The `by` clause on a refinement that `investigate-search.md` decision 3 proposed is dropped with the other spec-side query constructs (question 2 below): a ranking a merchandiser owns ("sponsored first, then exact matches, then newest") is a registered query's order, and a selector's `by` inside the spec is untouched.

**Stable pages need a total order, and `id` is not one.** A page after a cursor is stable only if the order discriminates every record. README §10 says the datums the author names must discriminate, and README §5 says `id` is unordered, so a caller ordering by `due` alone has an unstable page whenever two invoices share a due date. GraphQL bindings solve this with an opaque cursor that carries a tie-break the schema does not expose. The honest options are: the read API refuses an order that cannot be proven to discriminate (the fail-loudly rule, which needs a uniqueness proof the language does not have beyond `timestamp on create` within a commit); the read API appends the store key as a tie-break inside the cursor, never exposing it as an ordering datum, so pages are stable and the ordering the caller sees is still the data's; or pages are documented as unstable under ties. Question 8.

## 7. The viewer (K8)

GraphQL keeps authorization on the server, in the resolvers, and a composed read API does the same: the caller composes the query, and the system decides what this caller may see of the answer. The search pass spelled the viewer as an ordinary parameter of a refinement (`shopper: one Shopper`, with `not ageRestricted or shopper.verifiedAdult` as a conjunct), and under a composed read API that spelling is wrong in a telling way: a caller who composes against `Product` instead of `ProductSearch` sees the age-restricted products, because the gate was in one named definition rather than on the data. Record-level visibility ("a shopper may see a product only if it is not age-restricted or the shopper is a verified adult") is a statement about who may see *records*, which is [OQ33](questions/OQ33-visible-to.md)'s per-field `visible to` one level up, and this pass adds that half to OQ33: a read API the caller composes needs the viewer as a parameter of the whole read, bound by the engineer's wrapper from the session, and visibility declared in the spec per shape and per field against that viewer, enforced by the read API on every composed read. Not decided here; OQ33 carries it.

## 8. Why a read goes through Velle when the database is the engineer's

The store is outside Velle's scope: the engineer owns the ORM, the schema, and the database. So the question any engineer evaluating the framework asks first is why a query should go through Velle at all instead of straight to Postgres. The answer is that the database holds Velle's *storage* of the state, not the state's *meaning*. Everything the spec defines on top of the columns exists only in the evaluator, and a query that bypasses it either cannot ask for those things or re-implements them.

What a read through Velle gets that a direct query cannot, ranked:

- **Derived properties and refinement membership.** An invoice's `balance` and `status` and whether it is an `OverdueInvoice` (`examples/billing/billing.velle`) are not columns; they are formulas and predicates the evaluator computes. A direct query that wants "overdue invoices" re-spells the predicate in SQL, and then the reminder rule fires on Velle's definition while the screen shows SQL's. That drift is what the one-evaluator line (`investigate_runtime.md` §2, §6) exists to prevent.
- **What the rows mean.** A reference at a deleted row reads as absent through Velle and as a dangling key in SQL; captured properties live in capture tables keyed by membership; a union-typed reference needs its discriminator column (`investigate_runtime.md` §11). The store protocol knows these; a hand-written query has to.
- **Visibility.** Once [OQ33](questions/OQ33-visible-to.md) lands, who may see which records and fields is declared once and enforced on every read through the protocol (section 7). A direct query is a side door.
- **Checking against the spec.** A composed or registered query is type checked, narrowed, and reported broken when the spec changes (section 3). A SQL string is checked when it fails in production.
- **Keeping the API layer and the store layer pluggable.** If the GraphQL binding queried Postgres directly, the API layer would be coupled to the ORM layer, which is the coupling the architecture forbids (section 9). Going through the read protocol is what lets either side be swapped alone.
- **Freshness and provenance.** The spec knows which columns are snapshots and on what cadence (README §19), and only the evaluator can answer `why` (README §22).
- **The schema is free.** A GraphQL or REST surface derived from the model carries derived properties and refinements as first-class fields. One derived from the database schema cannot, and one written over JPA entities is maintained by hand.

**The honest cost.** A read through Velle is as fast as the column-expressible part of its predicate: the authoritative check runs in memory over the candidates the store returns (`investigate_runtime.md` §6), so a composed read whose filter is mostly derived is a scan. The mitigations are already in the design: the engineer writes the candidate SQL for a registered query under the superset contract, and the author materializes a snapshot column where the business accepts a cadence. A read through Velle does not mean Velle writes the SQL; it means Velle is the authority on what the rows mean, and the engineer's SQL is the pre-filter.

**When bypassing is fine.** A read that touches only stored columns and no refinement, derived property, capture, or visibility rule gets nothing from Velle but the free schema and the pluggability. Bulk exports, warehouse feeds, and analytics over raw columns belong there. The line is whether the query needs the spec's meaning, and a screen almost always does.

## 9. The end state: a framework library, Spring Boot over Postgres, and REST and GraphQL that wire themselves

*Scope note (2026-10-12, R3): the REST and GraphQL bindings are not being built. This section stays as the architecture the read protocol must allow for, so that the binding API built now is the one those layers would sit on.*

Dave's stated goal: a Velle framework library; a practical, real-world spec; a Postgres database; Spring Boot doing the binding between the framework and the database; and both a REST and a GraphQL API, on Spring Boot, that wire reads (and acts) to the system automatically. The architectural rule that governs it, Velle's and Dave's: each layer does as much as it can inside its own abstraction and never couples to the next layer's framework. The API layer is separate and pluggable. The store layer is already designed this way (`investigate_runtime.md` §10): the runtime speaks a generic protocol with no business name in it; a framework implements that protocol once for every spec; a per-spec typed layer is generated on top and regenerates with the spec. This section applies the same rule to reads and says what it changes in sections 3 through 7.

**How much of it already exists.** The generated system is built as `VelleSystem(Model(Parser.parse(SPEC)), startTime)` (`examples/billing/output/src/main/kotlin/velle/generated/Billing.kt`): the spec is parsed and modeled at startup, so the model is a runtime artifact and the whole `compiler` module (parser, validator, model, query compiler, runtime, protocols) is already on the runtime classpath. That module is the framework library; nothing new needs to be carved out. The generic write entry exists and is spec-agnostic: `commit(shape: String, suppliedFields: Map<String, Any?>, parts)` (`compiler/src/main/kotlin/velle/Runtime.kt`). The generic store query exists and is data: `fetchCandidates(shape: String, filter: QF)` (`compiler/src/main/kotlin/velle/Hydration.kt`), with `QF` the compiled filter tree (`compiler/src/main/kotlin/velle/Query.kt`). What does not exist is the read counterpart of `commit`: a generic entry that takes a composed read as data and answers it.

**The three parts, for reads.**

1. **The read protocol, in the framework.** A read is a value: a root (a shape, a refinement, or a registered query with its arguments), a selection, a filter written as the spec's predicate AST (`compiler/src/main/kotlin/velle/Ast.kt`), an order, a page, an optional aggregate, and the viewer. No business name appears in its definition. The runtime answers it: typechecks the filter and order against the model with the validator's predicate rules, compiles the filter to `QF` with the existing `QueryCompiler`, hydrates through `StateResolver`, runs the authoritative check in memory, applies the order, cuts the page, and projects the selection. The result is likewise data: rows of selected values with nested rows for traversals, plus a cursor. This is the `commit` entry's twin.
2. **The store binding, unchanged in kind.** A Spring Data, JPA, or JDBC implementation of `StateResolver` over Postgres is the framework role `stores/sqlite/` already plays, with zero business names, and a composed read reaches it through `fetchCandidates` with the pre-filter rendered to SQL. The generated typed store keeps its named methods as the override points for the questions the spec names. Postgres specifics stay below the line: `contains` renders to `ILIKE`; a full-text index is an extension predicate's concern (`investigate-search.md` decision 5).
3. **The API bindings, separate and pluggable.** The GraphQL module and the REST module each depend on the framework only. At startup each derives its read API from `Model`: a GraphQL object type per shape and refinement, a field per property and traversal, filter and order input types from the predicate vocabulary, a field with arguments per registered query, a mutation per act; or, for REST, a route per shape and refinement with filter, order, and page parameters, a route per registered query, a POST per act. Each translates an incoming request into a read-protocol value or a `commit` call, translates the result or the refusal back, and maps the authenticated principal to the viewer. Neither needs generated code: the generated views and typed query builder are an in-process convenience for an engineer writing Kotlin against the system, not a dependency of any binding. A third binding (gRPC, a command-line client, a message consumer) costs the same as the first two.

**The dependency arrows**, mirroring the store's: the runtime depends on its protocols only; the store binding depends on the store protocol; an API binding depends on the read protocol, the commit entry, and the model; no binding depends on another; generated output may depend on everything and nothing depends on it.

**What the goal changes in the proposal.** Nine consequences, labeled I1 through I9, each listed here.

- **I1 — The composing vocabulary is the protocol's data model, not the builder's API.** Section 4 described what a caller may say; under this goal that description must be a spec-agnostic value type the framework executes, with the typed Kotlin builder, the registry of section 3, and both API bindings as producers of it. Question 3.
- **I2 — The checks run at request time for external callers.** The typed builder is checked when the engineer compiles; a GraphQL or REST query is checked when it arrives, against the same model with the same rules: names, types, narrowing, orderable datums, the discrimination rule. The validator's predicate checks become a framework entry point on a read value, and their diagnostics become API errors. One implementation, two moments.
- **I3 — The spec is the API's schema, and the API cannot drift.** A GraphQL schema or a REST route table derived from `Model` at startup is one more projection of the one authored spec (`diagrams.md`, the projection principle); a spec change redeploys the API, and clients see the change through introspection or the route listing. Nobody maintains a schema file beside the spec, which is the duplication Dave does not want.
- **I4 — The viewer is in the protocol and visibility is enforced in the framework.** If each binding decided what the caller may see, the two would diverge and a third would start from nothing; so the binding only authenticates and maps a principal to a viewer reference, and the framework applies the spec's declared visibility to every composed read (section 7). That puts [OQ33](questions/OQ33-visible-to.md) on the critical path of any public API, not on the post-v0 shelf.
- **I5 — Cursors and the tie-break are the framework's.** An opaque cursor carrying the order datums and the store key, produced and consumed by the read protocol, wrapped by GraphQL connections and REST next-links. Question 8 is ruled (R2): the hidden tie-break, in the framework, once.
- **I6 — Fetching a page efficiently is the framework's problem, once.** Today's views read one field at a time (`system.get(id, field)`), which a page of invoices with their line items would turn into one store call per row. Under R5 a query returns whole instances, so the unit the runtime can plan around is the page: hydrate the root page's rows in one read, then each navigation an instance makes as a join read keyed by the page. Without a field selection the framework cannot know in advance which navigations the caller will follow, so the cost of a navigation stays per instance until the query API gains the configurable loading of associated records that R5 defers (`working-docs/TODO.md`, runtime follow-ons).
- **I7 — Each binding needs a wire form for a filter, and the spec's own syntax is one.** GraphQL gets generated input types (a comparison per field, a membership per refinement, `and`/`or`/`not`). REST can carry the spec's predicate syntax verbatim (`?where=balance > 0 and due <= 2026-11-01`), parsed by the framework's parser and checked by its checker, which is injection-safe because it compiles to a parameterized candidate query and is evaluated authoritatively in memory. Both land in the same AST. Question 7.
- **I8 — Acts ride the same bindings, already generically.** A POST or a mutation becomes `commit(shape, fields, parts)`; a refusal comes back with the violated prohibition's context as data (README §21), which the binding renders as a 4xx body or a GraphQL error; the correlation-key idiom (README §4, "Transient acts") is how a REST client matches a response to its request. Nothing new.
- **I9 — The typed per-spec layer stays optional.** Views, the typed store interface, and the typed query builder are developer-owned generated output for the engineer who writes Kotlin in-process. They are the Spring posture's convenience, never the bindings' foundation; a deployment with only a GraphQL API and a Postgres store runs on the framework and the spec alone.

The one-sentence version: the read API is not generated code, it is a protocol the framework executes from the model at runtime, and every API is a thin, replaceable translator onto it.

## 10. Findings

### K4 — GAP: nothing composes a read, and no value from the asker reaches a predicate

A read API the caller composes, or registers beside the client, over the spec's declared vocabulary and in the spec's predicate language. Questions 1 and 6. Nothing is added to the spec.

### K6 — HOLDS, by decision: a number is a query, wherever it is defined

A caller counts or sums whatever records it asks for; a number the business defines is a registered query. The spec-side declaration is dropped and kept on file. Question 2.

### K3 — TAX, resolved by decision: a read's order is the caller's, unpromised otherwise

Questions 8 and 9.

### K1, K2, K5, K7, K11 — HOLDS

By-key, navigation, membership, a number per record (a customer's revenue as a derived property), and refusals are the read API as built; the composed read API subsumes the first three.

### K8 — open elsewhere

The viewer is a parameter of the whole read; visibility is declared in the spec and enforced by the read API. OQ33 gains the record-level half (section 7).

### K9, K10 — by design and deferred

History is data the author models; `why` is README §22's item.

## 11. What needs Dave's input

Eleven questions. Each one is stated as the question, then what I recommend, then what a yes and a no cost. Answer in this section; the one-line card in `questions/OQ51-reads.md` is kept in sync.

### 1. Do we agree that the spec never declares a read, and that every query is composed or registered by whoever asks it?

**Ruled 2026-10-12 (R7): yes.** Promoted to README §22, whose deferred sentence about `act` as a read surface is closed: `act` is strictly input.

Concretely: the spec keeps definitions (refinements, derived properties), and a screen that wants "the overdue invoices for this customer, oldest first, twenty at a time" writes that query itself, against the names the spec declares, like this in Kotlin:

```
billing.read.invoices
    .where { isOverdueInvoice() and customer eq theCustomer }
    .orderBy(due.ascending)
    .page(size = 20)
```

Recommendation: yes. You have said as much twice; this is the ratification.

- **Yes costs:** a composed question has no named method in the typed store, so a slow one is tuned by registering it (question 6); nothing stops a caller from re-spelling a definition the spec already names, so that is a code-review matter.
- **No means:** reads are declared in the spec per query, and every new screen needs a spec change.

### 2. Do we drop the three spec-side constructs the search pass proposed: a refinement with parameters, a derived value outside any shape, and a default order on a refinement?

**Ruled 2026-10-12 (R6): yes, all three are dropped.** Nothing enters the grammar; each stays written down (section 3, section 5, section 6, and `investigate-search.md` section 3c) as the remedy for the case that would justify it.

They were proposed so a question could live in the spec. Under question 1 none is needed, and each stays written down as the remedy for the one case that would justify it: a definition with an input that the spec and a client both need and that is too costly to spell twice; a prohibition over a system-wide number; a ranking a rule reads.

Recommendation: yes, drop all three.

- **Yes costs:** "which products match a term" is spelled once in the spec's `SavedSearch` and once in the client's registered search; a business-defined number like revenue is defined where the product owner does not read it.
- **No means:** at least one of the three enters the grammar, validator, and codegen now.

### 3. What is a read request, and what is a read result, as plain data?

**Ruled 2026-10-12 (R5): a query returns the shape's Kotlin data classes.** The engineer gets whole instances of the generated type for the root (every stored field, every derived field computed at the read, a refinement's body fields when the root is a refinement), references as typed handles navigated from the instance as the views do today, and a cursor. The request therefore carries no field selection, which also answers both sub-questions: there is no "no fields named" case, and related records are reached from the instance, not nested in a result row. Underneath, the framework's protocol-level value stays generic (ids and rows), and the typed builder is what maps it to the data classes. Dave's addition, same day: how associated and joined records are handled will need to be configurable as part of the query API (handles navigated lazily, related instances materialized with the root, a prefetch per page), but that is too much to tackle initially. So the first build returns references as typed handles, navigating one is a further read, and the loading strategy is a `TODO.md` item under runtime follow-ons. Dave's steer for when it is tackled: follow GraphQL's lead. A GraphQL query names the nested fields it wants (`invoices { customer { name } lineItems { amount } }`), the server loads exactly those, batched per level, and anything not named is not fetched. The Velle equivalent is a query that names the relationships to load with the root, nested to any depth, with every unnamed reference left as a handle.

The framework needs one value type for "a query" that the Kotlin builder, the registry, GraphQL, and REST all produce, and one for "the answer". Proposed request: the root (a shape, a refinement, or a registered query with its arguments), which fields to return, a filter written in the spec's predicate language, an order, a page, an optional count or sum, and who is asking. Proposed result: rows of the requested fields, with nested rows where the request followed a reference, plus a cursor for the next page.

Two sub-questions. If the request names no fields, does it mean every stored and derived field of the root? And when a request follows a reference, is the related record nested inside its parent row, or returned flat with a key?

Recommendation: adopt the proposed shapes; no fields named means all of the root's own fields and no traversal; related records are nested.

- **Yes costs:** the request and result types become a contract the framework must keep stable, like the store protocol.
- **No means:** say which part should differ.

### 4. Is a read a transaction?

**Ruled 2026-10-12 (R4): for now a read is a read-only snapshot.** One clause of the contract, U1, and nothing else; promoted to `evaluation.md`. Supporting isolation levels on a read is a `TODO.md` item under runtime follow-ons.

The universal transaction contract (`evaluation.md`, U1 through U5) is written for envelopes, which commit. A read needs a settled snapshot (it must never see an envelope in flight) but commits nothing and must not wait in any queue.

Recommendation: yes, as a snapshot-only transaction: it gets U1 (a consistent snapshot) and nothing else, never takes a queue key, and this is one added sentence in the contract.

- **Yes costs:** the store binding must be able to open a read-only snapshot, which Postgres does natively.
- **No means:** a read is defined outside the contract, and we need to say separately what it may see.

### 5. Does the first version ship without visibility enforcement?

**Ruled 2026-10-12 (R8): ship without auth.** No authentication and no visibility enforcement in the first build; the viewer slot stays in the read request so OQ33 adds enforcement without changing the request's shape.

Who may see which records and fields is OQ33, which is not designed. A public API without it means every authenticated caller can read everything the spec declares.

Recommendation: yes, with the viewer slot present in the read request from day one so that nothing changes shape when OQ33 lands; the binding authenticates and fills the slot; enforcement is absent until OQ33 is settled.

- **Yes costs:** the first deployable API is only safe behind a trusted boundary.
- **No means:** OQ33 is designed and built before any binding ships.

### 6. How does an engineer register a query?

**Ruled 2026-10-12 (R9): Kotlin code.** The engineer writes the query with the typed builder and registers it under a name in code; query files wait for a binding that is not Kotlin.

Background. A registered query is one the engineer writes and owns, but hands to the framework at build time rather than at request time. Registering it is what makes four things happen, and these four are the requirement, whatever the form:

1. The framework checks the query against the spec when the engineer builds, with the same checks a refinement's predicate gets.
2. The typed store gets a named method for the query, so the engineer can hand-tune its SQL.
3. A test is generated for it.
4. When the spec changes in a way that breaks the query, the engineer's build fails and names it.

The question is only the mechanics: in what form does the engineer hand the query over? Two candidate forms, given that only the Kotlin binding API is in scope (R3):

- **A query file** beside the client, written in the spec's own predicate syntax, which the framework reads when the engineer builds.
- **Kotlin code**: the engineer writes the query with the typed builder and registers it under a name, in code. Kotlin's compiler checks the names; the framework checks everything else when it builds the registry.

Recommendation: Kotlin code. The builder is what the engineer writes anyway, and with no out-of-process client there is nothing to serve a file to. Query files can come later, with a binding that is not Kotlin.

- **Yes costs:** a registry the framework builds from the engineer's code; the typed store interface regenerates when a query is registered, which the engineer's own code expects.
- **No means:** query files, or some other form you name.

### 7. What does a filter look like on the wire for REST?

**Deferred 2026-10-12 (R3).** No REST or GraphQL layer is being built; the question returns with the first out-of-process binding.

GraphQL gets generated input types, which is settled by GraphQL itself. REST needs a spelling. Options: the spec's predicate syntax verbatim (`?where=balance > 0 and due <= 2026-11-01`), a JSON form of the predicate tree, or both.

Recommendation: the predicate syntax first; JSON only if a client that builds filters programmatically asks for it.

- **Yes costs:** the predicate grammar becomes a public wire format with compatibility obligations.
- **No means:** JSON first, or both from the start.

### 8. How does a page stay stable when the caller's order does not tell two records apart?

**Ruled 2026-10-12 (R2): the hidden store-key tie-break.** The cursor remembers the last row's order datums and its store key; the next page starts after that pair. The key is never exposed as an ordering datum. Belongs to the read protocol in the framework (section 9, I5), so GraphQL connections and REST next-links wrap one cursor format.

Two invoices with the same due date, ordered by due date only: which comes first is undefined, and the next page may repeat or skip one. The spec says ordering comes from data and `id` orders nothing (README §5, §10). Options: refuse an order that cannot tell records apart; carry the store key as a hidden tie-break inside the cursor, never exposed as an ordering datum; or document that pages may be unstable under ties. The store key is the identity the database assigns a row (the primary key in Postgres), durable and unique and never reissued, which Velle uses as identity only and never as an order (README §5; `StoreKey` in `compiler/src/main/kotlin/velle/Hydration.kt`); the hidden tie-break makes the cursor remember the last row's order datums and its key, so the next page starts after a pair that tells every row apart.

Recommendation: the hidden tie-break.

- **Yes costs:** the cursor is opaque and framework-defined.
- **No means:** refusal (callers must always name a discriminating datum) or documented instability.

### 9. Should a stable row order be required, and where is it specified?

**Ruled 2026-10-12 (R10): yes, and for now every query specifies it.** A query must name its order: one or more orderable datums of the root, each with a direction, with the hidden store key breaking ties (R2). A query that names no order is refused. The store promises no scan order, so the open item from the store pass is closed on the "no store obligation" side, and the generated specs that read `last(shape)` name an ordering datum instead, which is what README §10 already demands of every selector. Whether a shape may declare a default order that a query inherits when it names none is left open as a `TODO.md` item; it is a different thing from the default order on a refinement that R6 dropped, since it would be a property of the shape's reads, not a definition.

What the question had asked, for the record: today the runtime hands rows back in the store's scan order, and the store pass left open whether that order should be promised to callers as a store obligation. Three answers were possible: promise it (every store binding guarantees a stable scan order); promise nothing (a query with no order gets rows in no promised order); or require the caller to say (every query names its order). The ruling is the third.

- **Costs:** every query carries an order clause; the generated-spec code and a few harness tests change to name a datum.

### 10. Does `contains` join the language, and does a prefix test join it too?

**Ruled 2026-10-12 (R1): yes, and wider than recommended.** Dave: the predicate vocabulary needs the prefix test and LIKE-style matching too. Adopted: `contains`, `starts with`, `ends with`, and `like` with the SQL pattern wildcards, all exact and case-sensitive, with case folding the author's visible `lowercase`. `ends with` was not asked for; it is included because it is the same mechanism as `starts with` and leaving it out would only raise the question again. Say so if it should go.

A search needs a substring test, and the filter language is the spec's predicate language, so this is a language addition. Proposed: `a contains b` is an exact, case-sensitive substring test, with case folding left to the author's visible `lowercase` (`investigate-search.md` decision 4). A prefix test (`starts with`) would serve autocomplete.

Recommendation: `contains` yes, prefix no for now.

- **Yes costs:** one builtin, rendered to `LIKE` by the pre-filter.
- **No means:** no text predicate, and search waits for the extension framework.

### 11. What do we build first?

**Ruled 2026-10-12 (R11): billing.** Narrowed earlier the same day (R3): the scope is the Velle read/query binding API, meaning the generic read entry beside `commit` and the typed Kotlin builder over it; no REST or GraphQL layer. What remains to decide is which example spec the first build is exercised against.

Recommendation: billing, because its generated surface and tests are the most complete and the overdue-invoice reads are the natural first queries. The Spring Boot and Postgres store binding is a separate track that the read work does not depend on.

- **Yes costs:** nothing beyond ordering the work.
- **No means:** name the order you want.

## Scorecard

| read | kind | verdict | where it stands |
|---|---|---|---|
| K1 | one record by identity | HOLDS | by-key lookup, as built; subsumed by the composed read API |
| K2 | records related to one record | HOLDS | navigation and join reads, as built; traversal on the composed read API |
| K3 | a declared subset | TAX | order is the caller's, unpromised otherwise; questions 8, 9 |
| K4 | a subset shaped by the asker's values | GAP | composed or registered query; nothing added to the spec; questions 1, 6 |
| K5 | membership of one record | HOLDS | membership test, as built; gains arguments |
| K6 | a number about the whole system | HOLDS, by decision | the caller counts or sums; a defined number is a registered query; question 2 |
| K7 | a number per group | HOLDS | derived property on the group's shape; otherwise asked once per group |
| K8 | who may read what | open | the viewer on the whole read; record-level visibility added to OQ33 |
| K9 | a record as it was | by design | history is data (README §12, §20) |
| K10 | why a record is what it is | deferred | README §22 `why` |
| K11 | what the system refused | HOLDS | commit result; rejection as data |
