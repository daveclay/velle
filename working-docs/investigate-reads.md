# Investigation: reading the system — the spec is the schema, the caller composes the question

**Status:** first pass (2026-10-12) — conceptual, by analysis; nothing here runs. This is the parent of `investigate-search.md`: search was the first read-side behavior worked through, and it turned out to be one case of a general question. The general question is answered here; search stays in its own doc as the special case. Decisions for Dave at the bottom.
**Question tag:** [OQ51](questions/OQ51-reads.md)
**See:** README §4 (what "read" means), §5 (`id` is unordered), §6 (references and inferred inverses), §7 (derived properties), §8 (refinements), §10 (predicates, selectors, the mandatory `by`), §22 "Current milestone" (reading is generated typed accessors) and "External input (`act`)" (whether `act` also names a read API) · `working-docs/investigate_runtime.md` §6 (the candidate pre-filter and the one-evaluator line), §10 (the typed store surface), §11 (the query surface's order over a store) · `working-docs/investigate-search.md` (the special case) · [OQ33](questions/OQ33-visible-to.md) (who may see what) · `diagrams.md` (the projection principle: every generated artifact is a projection of the one spec)

---

Every spec written so far is written from the write side: what enters, what changes, what reacts. The read side has one sentence in the README (§22: reading is generated typed accessors, not a generic query API) and one deferred question (whether `act` also names a read API). "Read API" throughout this doc means the set of calls an engineer or a client can make to get data out of a running system, as opposed to the acts that put data in; the README and the runtime docs call the same thing a "surface" (the generated typed surface, the query surface), and the two words mean one thing. Dave's intent is broader than either: any shape can be read by an engineer making a parameterized call to the system's API, and the model for that is GraphQL. A GraphQL server owns one schema; clients compose their own questions against it, selecting the fields they need and traversing the relationships the schema declares; nobody writes a read per screen, because a read per screen couples each screen to the backend and the two must then be kept in sync by hand. Velle already owns the schema: the shapes, their properties, their relationships, their refinements, and the Kotlin types generated from them. The read API should be derived from that and declare nothing twice.

This pass works out what that means. Verdict scale as in `break-pharmacy.md`: **HOLDS** (the design handles the case), **TAX** (handles it at a cost), **GAP** (needs a new obligation or fails). Read kinds are labeled R1 through R11 and are all listed in section 2.

## 1. What "read" means in Velle today

README §4 defines it: a read is any predicate or derivation evaluated against current state, and it commits nothing. Everything below rests on that sentence and on what the generated read API already does.

**The read API has two layers, and both exist.** The per-spec accessor (`examples/billing/output/src/main/kotlin/velle/generated/Billing.kt`) gives, for every shape, a list of all instances and a lookup by key (`invoices()`, `invoice(id)`); for every refinement, the member list and a membership test on a view (`overdueInvoices()`, `InvoiceView.isOverdueInvoice()`); and on every view, each declared property, each derived property computed at the read, and navigation along declared references and their inferred inverses (`InvoiceView.customer`, `InvoiceView.lineItems`). Beneath it, the typed store (`examples/billing/output/src/main/kotlin/velle/generated/BillingStore.kt`) carries keyed reads, a read of all rows per shape, a join read per to-one field (`invoicesByCustomer`), and one named candidate method per question the spec asks, whose parameters are the evaluation-moment constants the question recognition extracts (`remindOverdueCandidates(dueBefore, sentOnAfter)`, `investigate_runtime.md` §10). Beneath both is the generic protocol the runtime speaks, with shape names as strings (`instancesOf("Invoice")`).

**The views are already the schema's types.** `InvoiceView` has a property per declared and derived property, a typed reference per `one` field, and a typed collection per `many` field and inferred inverse. They are generated from the spec, so a spec change regenerates them and the engineer's code that uses them fails to compile where it is stale. That is the projection principle (`diagrams.md`): one authored spec, every artifact a projection of it. The read API this pass proposes is one more projection of the same spec, and the views are its types.

**What a read sees.** A read evaluates over settled state: the state after the last envelope that completed, never a state in the middle of one (`evaluation.md`, the universal transaction, U1 and U2). A read does not queue. An act is serialized against every act whose domain intersects its own (U3); a read is not an act and waits for nothing, which is the structural reason `investigate-search.md` section 3a rejects spelling a search as an act. Evaluation-moment constants (`today`, `now`) bind at the moment of asking.

**What a read takes today.** A key, or a declared reference reached by navigation. No read takes a filter, an order, or a value from the asker. The accessor is a set of fixed questions; what GraphQL would call the query layer, where the caller composes, does not exist.

## 2. The catalog: what an application reads

Eleven kinds of read, in business words, each with where it stands. This list is the roster for every R-label in the doc.

- **R1 — one record by identity.** "Invoice 4711." HOLDS: the by-key lookup.
- **R2 — the records related to one record.** "This customer's invoices; this invoice's payments." HOLDS: navigation along declared references and inferred inverses (README §6), and the typed store's join reads.
- **R3 — a declared subset.** "The overdue invoices." HOLDS as a set: the refinement accessor. In no promised order (section 6).
- **R4 — a subset the asker shapes with values.** "Invoices due before a date I choose; products matching what I typed; this customer's payments between two dates." GAP: nothing composes a filter, and no value from the asker reaches a predicate. The center of the search pass (`investigate-search.md` F1) and of this one (sections 3 and 4).
- **R5 — whether one record is in a subset.** "Is this invoice overdue?" HOLDS: the membership test.
- **R6 — a number about the whole system.** "Open receivables; revenue this month; how many invoices are overdue." GAP today: a caller cannot count or add up what it reads at the read API, and a formula can only be written in the spec as a property of a shape (README §7, an invoice's `balance`). Resolved as a question the caller composes or registers, never a spec declaration (section 5).
- **R7 — a number per group.** "Revenue per customer; hits per category." HOLDS when the group is a shape: a derived property on `Customer` summing its invoices. When the group is a value rather than a shape (a category name in a `many text`), the caller asks for the count or sum once per group (section 5).
- **R8 — who may read what.** "A shopper sees sellable products; a patient sees their own record." Open as [OQ33](questions/OQ33-visible-to.md); section 7 says what this pass adds.
- **R9 — a record as it was.** "This invoice's due date before the change." By design: state is current (README §4). Where history matters the author models it as data, the ledger and episode patterns (README §12, §20), and then it is R2 or R4 over the history records. A read "as of" a past moment over in-place fields is not a read of the state and the language does not offer it.
- **R10 — why a record is what it is.** "Why is this invoice overdue?" Deferred: README §22's `why` item.
- **R11 — what the system refused.** "What happened to my request?" HOLDS: the commit function's result carries the refusal (README §22), and a refusal the business keeps is a record the caller reads back (`patterns.md`, "Validation rejection is data").

R4 and R6 are gaps and R3 carries an open promise about order; the rest hold, are open elsewhere, or are outside by design. The pass is therefore about where a question is composed (section 3), what the caller may compose from (section 4), counts and totals (section 5), order and pages (section 6), and the viewer (section 7).

## 3. Where a question lives: the spec owns definitions, the engineer owns questions, one checker serves both

There are two things an application's read can contain, and they belong to different owners.

**Meaning** is a definition the business would state and defend: what *overdue* is, what *sellable* is, what a *verified adult* is. Meaning belongs in the spec, named, once, because a rule, a prohibition, and a screen must all agree on it, and because the product owner reads it there. The spec already has the constructs for it: refinements (README §8), derived properties (§7), and composition (§9).

**Asking** is which of the declared things this caller wants, in what order, how many at a time. To *compose* a question, throughout this doc, is to assemble it at the moment of asking out of names the spec declares, the way a GraphQL client writes a query against a schema; a composed read is one nobody wrote down in advance. Asking looks like this: "the overdue invoices for this customer, oldest first, twenty per page, with the customer's name and each line item's amount". Asking belongs to the engineer who builds the screen, because it changes per screen and per client, and a backend that holds one fixed question per screen is the coupling GraphQL exists to remove.

**A question gains nothing by living in the spec.** The first draft of this pass proposed spec-side constructs for questions: a refinement with parameters, a formula outside any shape, a default order on a refinement. Dave's objection, which this draft adopts: a question names the spec's fields wherever it lives, so renaming a field means fixing the question either way, and the only things that differ are who owns the text and when the break is found. What would justify the spec holding a question is a proof the question could take part in, and there is none. The proofs that make the spec worth its weight (one writer, disarm, confluence, quiescence, README §12 and §18 through §21) are about writes; a read changes nothing, so none of them applies to it. What a read *does* get from the spec is the spec's definitions, and those reach every question checked against the model no matter where the question's text lives:

- **Type checking** of every name, comparison, traversal, and order datum, with the same rules a refinement's predicate gets (README §10).
- **Definitions by name.** A question says `is OverdueInvoice` and gets the spec's meaning of overdue; it reads `balance` and gets the derived property computed at the read (README §7).
- **Visibility** (section 7, [OQ33](questions/OQ33-visible-to.md)): declared once in the spec against the viewer, enforced on every question.
- **Freshness.** A question that reads a column the spec maintains on a cadence (README §19's snapshot rung) can be told the staleness it accepts, from the spec's own `on Hourly`.
- **Coverage.** A question that selects membership of several refinements the spec proves partition a shape (README §8's exhaustiveness check) is a screen that shows every state; one that selects a subset is a screen with a hole, and the checker can say so.
- **Provenance**, when README §22's `why` lands, for any value a question returns.

None of these needs the question's text in the spec. They need the question checked against the model, which is a moment, not a place.

**Three places a question can live, one checker, three moments.**

1. **Composed at request time.** Fully dynamic; checked on arrival; rides the generic query path. The GraphQL and REST bindings of section 8 do this for every request.
2. **Registered beside the client.** The engineer writes the question in the client's language (a GraphQL document, or the spec's own predicate syntax) and registers it with the system. At the engineer's build the framework checks it against the model with the same checker, enumerates it for the typed store as a named candidate method the engineer can override with tuned SQL, generates a test for it, and, when the spec changes, fails the build naming each registered question that broke. This is GraphQL's persisted query with Velle's checker behind it: the spec never sees the question, and the question never drifts silently. A real application puts most of its screen questions here.
3. **Declared in the spec.** Definitions only: refinements and derived properties that a rule, a prohibition, or several questions share. Never a question. A refinement no rule or prohibition reads, written so a screen can ask for it, is a question wearing a definition's clothes and belongs in tier 2.

All three produce the same read-protocol value (section 8) and are checked by the same code. The author's only choice is the tier, and the test is the ownership line above: meaning in the spec, questions with whoever asks them.

**Where the search pass's constructs land.** The parameterized refinement (`investigate-search.md` section 3c, `shape ProductSearch(terms: text, ...) = Sellable where ...`) is dropped from the proposal. Inside the spec the inputs to a definition are always some record's own fields (`SavedSearch.newMatches` reads `this.terms`), so no parameter is needed there; for the caller, the matching predicate is a registered question. The one cost is that "which products match a term" is then spelled twice, once in `SavedSearch` and once in the client's registered question, which is the duplication an engineer maintains in GraphQL today, accepted here for the same reason. If a real spec finds that cost too high, the parameterized refinement is the remedy on file. `contains` stays (`investigate-search.md` decision 4): the filter language *is* the spec's predicate language, so vocabulary added for the spec's predicates is the query language's the same day. Delegated matching stays as the search doc states it.

This closes README §22's deferred question whether `act` names a read API: it does not. `act` is input. A read is a protocol value checked against the schema the spec already is.

## 4. The composing vocabulary, and the read API derived from it

Nothing in this section is declared by anyone. It is read off the spec.

**What the caller may say**, for a read rooted at any shape or refinement:

- **Select**: any declared or derived property of the root, and recursively of anything reached by traversal. Derived properties compute at the read, as the views do today. A refinement's body properties are selectable when the root is that refinement.
- **Traverse**: along any `one` or `many` field and any inferred inverse (README §6), each traversal itself a read with its own select, filter, and order.
- **Filter**: a predicate in the spec's own predicate language (README §10) over the root: comparisons on declared and derived properties, `exists` over traversals, and membership of any refinement (`is OverdueInvoice`). The predicate language is the spec's, so a filter is typechecked against the same grammar and the same rules as a refinement's predicate; the caller cannot say anything a refinement could not.
- **Order**: by one or more orderable datums of the root, declared or derived, with a direction. The rule is README §10's for selectors: ordering comes from data, never from minted values, so `id` is not an ordering datum, and an order whose datums fail to discriminate is an unstable page (section 6).
- **Page**: a window over the ordered read.
- **Aggregate**: `count` and `sum` over a filtered root or traversal, the aggregates the spec already has (README §10); section 5.
- **Membership**: whether a given record is in a refinement, with arguments when it has parameters.

**What the read API generates**, as one more projection of the spec:

- **The types** are the views already generated (`compiler/src/main/kotlin/velle/Codegen.kt`, the view classes): one per shape and per refinement, a Kotlin type per scalar, references as views, collections as lists. They are the data classes the engineer uses, and the schema a GraphQL binding would expose. Nothing is written twice: a spec change regenerates the types, and every stale use in the engineer's code is a compile error naming the field.
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
- **A GraphQL schema and executor**, as a second binding over the same vocabulary, for callers outside the process: one object type per shape and refinement, one field per property and traversal, filter and order arguments generated from the predicate language, registered questions as named fields with their arguments, pages as connections. Worth stating as a binding because it is the shape of Dave's intent, and because the schema is free: it is the spec.
- **The generic protocol** is unchanged in kind. A composed filter is a predicate tree in the spec's language, and the runtime already compiles predicate trees to a candidate query under the superset contract and runs the authoritative check in memory (`investigate_runtime.md` §6). A read composed at the read API takes the same path as a rule's condition: the query compiler renders the column-expressible part to the store, the derived part drops out by polarity, and one evaluator decides. The one-evaluator line is not crossed, because the caller composes in the spec's language rather than in SQL.
- **The typed store** keeps its enumerated candidate methods for the questions the spec itself asks, and gains one per registered question (section 3, tier 2), each an override point for hand-tuned SQL. A composed read has no named method, because it is not enumerable; it rides the generic path, and its performance is the column-expressible part of what the caller composed. That is the trade a composed read API makes and GraphQL makes it too: the engineer who finds a hot composed read registers it and gets a method to override, which is the same move as persisting a query and tuning its resolver.

**What the read API does not let the caller do:** write, name a shape or property the spec does not declare, order by a minted value, or state a predicate the spec's grammar would refuse. Everything the caller can ask is a question the spec could have asked itself.

## 5. Counts and totals (R6, R7)

Two kinds of number appear on a screen, and under section 3 both are the caller's.

**A number nobody has defined.** "How many invoices match this screen's filter"; "the sum of the amounts shown"; "how many hits in each category". The caller already asks for the records; it can also ask for their count or the sum of one of their fields, over the same filter: `billing.read.payments.where { receivedOn gte from and receivedOn lt to }.sum { amount }`. That needs nothing from the spec. It covers the dashboard count and the facet count (`investigate-search.md` F8), and a number per group when the group is a value rather than a shape (R7): ask once per group.

**A number the business defines.** "Revenue" means something specific to the business: which payments count, which are excluded, over what dates. It must be written once so every screen agrees, and it is a registered question (section 3, tier 2) named `revenue`, owned by the engineer, checked at the build, enumerated for the store. The first draft proposed a declaration in the spec for it, a derived value outside any shape:

```
-- The remedy on file, not proposed: a formula that belongs to no shape.
value Revenue(from: Date, to: Date): decimal =
    sum(Payment where receivedOn >= from and receivedOn < to, amount)
```

It is dropped by section 3's argument: nothing in the spec can read it, since rules fire per record (README §16) and no example has a prohibition over a system-wide number, so it would be a question in the spec's clothes. It returns only if a prohibition ever needs a system-wide figure, at which point it is a definition.

R7, a number per group, needs nothing when the group is a shape: `revenue: decimal = sum(invoices, total)` on `Customer` is v0 as written, and that one *is* a definition, because a rule or a prohibition on `Customer` can read it.

## 6. Order, pages, and what a read promises

Three promises, two of them already made and one owed.

- **A snapshot of settled state.** A read evaluates over the state after the last completed envelope and sees no envelope in flight (`evaluation.md`, U1 and U2). Derived properties compute at the read. Evaluation-moment constants bind at the read. Already made.
- **No waiting, no side effects.** A read is not an act: it takes no queue key, waits for no envelope, and commits nothing (README §4). Already made. An application that wants to *record* that a read happened (the search log) commits a separate act after the read, as `investigate-search.md` section 4 spells it.
- **Order.** Owed. A collection is a set (README §6), and `instancesOf` reports the store's scan order, which `investigate_runtime.md` §11 left open as a possible contract obligation (`working-docs/TODO.md`, the item on a scan's row order). Under section 3 order is the caller's: named datums with directions, under README §10's rule that the datums must discriminate. A read with no order named is in no promised order, and the scan-order obligation is withdrawn rather than adopted: a store's row order is a minted order, and ordering comes from data. The generated specs that read `last(shape)` (`investigate_runtime.md` §11) are then relying on something unpromised and should name a datum, the same fix README §10 demands of a selector. The `by` clause on a refinement that `investigate-search.md` decision 3 proposed is dropped with the other spec-side question constructs (decision 3 below): a ranking a merchandiser owns ("sponsored first, then exact matches, then newest") is a registered question's order, and a selector's `by` inside the spec is untouched.

**Stable pages need a total order, and `id` is not one.** A page after a cursor is stable only if the order discriminates every record. README §10 says the datums the author names must discriminate, and README §5 says `id` is unordered, so a caller ordering by `due` alone has an unstable page whenever two invoices share a due date. GraphQL bindings solve this with an opaque cursor that carries a tie-break the schema does not expose. The honest options are: the read API refuses an order that cannot be proven to discriminate (the fail-loudly rule, which needs a uniqueness proof the language does not have beyond `timestamp on create` within a commit); the read API appends the store key as a tie-break inside the cursor, never exposing it as an ordering datum, so pages are stable and the ordering the caller sees is still the data's; or pages are documented as unstable under ties. Decision 4, binding-side.

## 7. The viewer (R8)

GraphQL keeps authorization on the server, in the resolvers, and a composed read API does the same: the caller composes the question, and the system decides what this caller may see of the answer. The search pass spelled the viewer as an ordinary parameter of a refinement (`shopper: one Shopper`, with `not ageRestricted or shopper.verifiedAdult` as a conjunct), and under a composed read API that spelling is wrong in a telling way: a caller who composes against `Product` instead of `ProductSearch` sees the age-restricted products, because the gate was in one named definition rather than on the data. Record-level visibility ("a shopper may see a product only if it is not age-restricted or the shopper is a verified adult") is a statement about who may see *records*, which is [OQ33](questions/OQ33-visible-to.md)'s per-field `visible to` one level up, and this pass adds that half to OQ33: a read API the caller composes needs the viewer as a parameter of the whole read, bound by the engineer's wrapper from the session, and visibility declared in the spec per shape and per field against that viewer, enforced by the read API on every composed read. Not decided here; OQ33 carries it.

## 8. The end state: a framework library, Spring Boot over Postgres, and REST and GraphQL that wire themselves

Dave's stated goal: a Velle framework library; a practical, real-world spec; a Postgres database; Spring Boot doing the binding between the framework and the database; and both a REST and a GraphQL API, on Spring Boot, that wire reads (and acts) to the system automatically. The architectural rule that governs it, Velle's and Dave's: each layer does as much as it can inside its own abstraction and never couples to the next layer's framework. The API layer is separate and pluggable. The store layer is already designed this way (`investigate_runtime.md` §10): the runtime speaks a generic protocol with no business name in it; a framework implements that protocol once for every spec; a per-spec typed layer is generated on top and regenerates with the spec. This section applies the same rule to reads and says what it changes in sections 3 through 7.

**How much of it already exists.** The generated system is built as `VelleSystem(Model(Parser.parse(SPEC)), startTime)` (`examples/billing/output/src/main/kotlin/velle/generated/Billing.kt`): the spec is parsed and modeled at startup, so the model is a runtime artifact and the whole `compiler` module (parser, validator, model, query compiler, runtime, protocols) is already on the runtime classpath. That module is the framework library; nothing new needs to be carved out. The generic write entry exists and is spec-agnostic: `commit(shape: String, suppliedFields: Map<String, Any?>, parts)` (`compiler/src/main/kotlin/velle/Runtime.kt`). The generic store question exists and is data: `fetchCandidates(shape: String, filter: QF)` (`compiler/src/main/kotlin/velle/Hydration.kt`), with `QF` the compiled filter tree (`compiler/src/main/kotlin/velle/Query.kt`). What does not exist is the read counterpart of `commit`: a generic entry that takes a composed read as data and answers it.

**The three parts, for reads.**

1. **The read protocol, in the framework.** A read is a value: a root (a shape, a refinement, or a registered question with its arguments), a selection, a filter written as the spec's predicate AST (`compiler/src/main/kotlin/velle/Ast.kt`), an order, a page, an optional aggregate, and the viewer. No business name appears in its definition. The runtime answers it: typechecks the filter and order against the model with the validator's predicate rules, compiles the filter to `QF` with the existing `QueryCompiler`, hydrates through `StateResolver`, runs the authoritative check in memory, applies the order, cuts the page, and projects the selection. The result is likewise data: rows of selected values with nested rows for traversals, plus a cursor. This is the `commit` entry's twin.
2. **The store binding, unchanged in kind.** A Spring Data, JPA, or JDBC implementation of `StateResolver` over Postgres is the framework role `stores/sqlite/` already plays, with zero business names, and a composed read reaches it through `fetchCandidates` with the pre-filter rendered to SQL. The generated typed store keeps its named methods as the override points for the questions the spec names. Postgres specifics stay below the line: `contains` renders to `ILIKE`; a full-text index is an extension predicate's concern (`investigate-search.md` decision 5).
3. **The API bindings, separate and pluggable.** The GraphQL module and the REST module each depend on the framework only. At startup each derives its read API from `Model`: a GraphQL object type per shape and refinement, a field per property and traversal, filter and order input types from the predicate vocabulary, a field with arguments per registered question, a mutation per act; or, for REST, a route per shape and refinement with filter, order, and page parameters, a route per registered question, a POST per act. Each translates an incoming request into a read-protocol value or a `commit` call, translates the result or the refusal back, and maps the authenticated principal to the viewer. Neither needs generated code: the generated views and typed query builder are an in-process convenience for an engineer writing Kotlin against the system, not a dependency of any binding. A third binding (gRPC, a command-line client, a message consumer) costs the same as the first two.

**The dependency arrows**, mirroring the store's: the runtime depends on its protocols only; the store binding depends on the store protocol; an API binding depends on the read protocol, the commit entry, and the model; no binding depends on another; generated output may depend on everything and nothing depends on it.

**What the goal changes in the proposal.** Nine consequences, labeled I1 through I9, each listed here.

- **I1 — The composing vocabulary is the protocol's data model, not the builder's API.** Section 4 described what a caller may say; under this goal that description must be a spec-agnostic value type the framework executes, with the typed Kotlin builder, the registry of section 3, and both API bindings as producers of it. Decision 5.
- **I2 — The checks run at request time for external callers.** The typed builder is checked when the engineer compiles; a GraphQL or REST query is checked when it arrives, against the same model with the same rules: names, types, narrowing, orderable datums, the discrimination rule. The validator's predicate checks become a framework entry point on a read value, and their diagnostics become API errors. One implementation, two moments.
- **I3 — The spec is the API's schema, and the API cannot drift.** A GraphQL schema or a REST route table derived from `Model` at startup is one more projection of the one authored spec (`diagrams.md`, the projection principle); a spec change redeploys the API, and clients see the change through introspection or the route listing. Nobody maintains a schema file beside the spec, which is the duplication Dave does not want.
- **I4 — The viewer is in the protocol and visibility is enforced in the framework.** If each binding decided what the caller may see, the two would diverge and a third would start from nothing; so the binding only authenticates and maps a principal to a viewer reference, and the framework applies the spec's declared visibility to every composed read (section 7). That puts [OQ33](questions/OQ33-visible-to.md) on the critical path of any public API, not on the post-v0 shelf.
- **I5 — Cursors and the tie-break are the framework's.** An opaque cursor carrying the order datums and the store key, produced and consumed by the read protocol, wrapped by GraphQL connections and REST next-links. Decision 4 resolves to the hidden tie-break, in the framework, once.
- **I6 — The selection in the protocol is what lets the framework fetch efficiently.** Today's views read one field at a time (`system.get(id, field)`), which a binding returning a page of invoices with their line items would turn into one store call per row. With the selection in the read value the runtime can plan: hydrate the root page, then each traversal level as one join read. The N+1 problem is solved once, below both bindings, rather than in each binding's data-loader.
- **I7 — Each binding needs a wire form for a filter, and the spec's own syntax is one.** GraphQL gets generated input types (a comparison per field, a membership per refinement, `and`/`or`/`not`). REST can carry the spec's predicate syntax verbatim (`?where=balance > 0 and due <= 2026-11-01`), parsed by the framework's parser and checked by its checker, which is injection-safe because it compiles to a parameterized candidate query and is evaluated authoritatively in memory. Both land in the same AST. Decision 6.
- **I8 — Acts ride the same bindings, already generically.** A POST or a mutation becomes `commit(shape, fields, parts)`; a refusal comes back with the violated prohibition's context as data (README §21), which the binding renders as a 4xx body or a GraphQL error; the correlation-key idiom (README §4, "Transient acts") is how a REST client matches a response to its request. Nothing new.
- **I9 — The typed per-spec layer stays optional.** Views, the typed store interface, and the typed query builder are developer-owned generated output for the engineer who writes Kotlin in-process. They are the Spring posture's convenience, never the bindings' foundation; a deployment with only a GraphQL API and a Postgres store runs on the framework and the spec alone.

The one-sentence version: the read API is not generated code, it is a protocol the framework executes from the model at runtime, and every API is a thin, replaceable translator onto it.

## 9. Findings

### R4 — GAP: nothing composes a read, and no value from the asker reaches a predicate

A read API the caller composes, or registers beside the client, over the spec's declared vocabulary and in the spec's predicate language. Decisions 1 and 2. Nothing is added to the spec.

### R6 — HOLDS, by decision: a number is a question, wherever it is defined

A caller counts or sums whatever records it asks for; a number the business defines is a registered question. The spec-side declaration is dropped and kept on file. Decision 3.

### R3 — TAX, resolved by decision: a read's order is the caller's, unpromised otherwise

Decisions 1 and 4.

### R1, R2, R5, R7, R11 — HOLDS

By-key, navigation, membership, a number per record (a customer's revenue as a derived property), and refusals are the read API as built; the composed read API subsumes the first three.

### R8 — open elsewhere

The viewer is a parameter of the whole read; visibility is declared in the spec and enforced by the read API. OQ33 gains the record-level half (section 7).

### R9, R10 — by design and deferred

History is data the author models; `why` is README §22's item.

## 10. What needs Dave's input

### 1. The read API is composed by the caller from the spec's declared vocabulary, and the spec declares no reads

Decision: adopt the composed read API of section 4. The caller selects, traverses, filters, orders, pages, and aggregates over declared and derived properties, references and inverses, and refinement membership, in the spec's own predicate language; the spec holds definitions and never a screen's question. The generated views are the types; a typed Kotlin query builder is the in-process binding and a GraphQL schema the out-of-process one, both projections of the spec. The accessor's fixed lists (`invoices()`, `overdueInvoices()`) remain as the degenerate composed read. This closes README §22's question: `act` is input only.

Costs: a composed read has no named candidate method in the typed store and rides the generic query path, so a hot composed read is tuned by registering it (decision 2); the read API cannot stop a caller from re-spelling a definition the spec names, so that is a review discipline; the query builder and the GraphQL binding are two new generators.

### 2. A question may be registered beside the client and checked at the engineer's build

Decision: adopt the registered tier of section 3. The engineer writes a question in GraphQL or in the spec's predicate syntax and registers it; at the build the framework checks it against the model with the validator's predicate rules, emits a named candidate method for it in the typed store, generates a test for it, and reports every registered question a spec change breaks.

Costs: a registry the framework reads at build time, in a form to be chosen (a directory of query files beside the client, or an annotation); the typed store's method set then depends on registered questions as well as the spec, so a new registration regenerates the store interface, which is the engineer's own code and expects it.

### 3. The spec-side question constructs are dropped and kept on file

Decision: drop the three constructs the first drafts proposed, by section 3's argument that a read takes part in no proof: a refinement with parameters (`investigate-search.md` decision 1), a derived value outside any shape (section 5), and a default `by` order on a refinement (`investigate-search.md` decision 3). Each stays written down as the remedy on file for the case that would justify it: a definition with an input that the spec and a client both need and that is too costly to spell twice; a prohibition over a system-wide number; a ranking a rule reads.

Costs: "which products match a term" is spelled in the spec's `SavedSearch` and again in the client's registered search; a system-wide number is defined where the product owner does not read it.

### 4. Stable pages, binding-side

Decision: how a page stays stable when the caller's order does not discriminate — refuse the order, carry the store key as a hidden tie-break in the cursor, or document the instability. Recommendation: the hidden tie-break; it keeps README §5's rule that `id` orders nothing the spec can see while giving every binding stable pages.

### 5. The composed read is a protocol value the framework executes, and every API is a translator onto it

Decision: define the read of section 4 as a spec-agnostic value type in the framework library (root, selection, filter as the spec's predicate AST, order, page, aggregate, viewer) with a generic entry beside `commit` that answers it, and build the typed Kotlin builder, the registry, the GraphQL binding, and the REST binding as producers of that value. The bindings derive their schema and routes from `Model` at startup and depend on the framework only.

Costs: the validator's predicate checks and the query compiler become build-time and request-time framework entry points with API-facing diagnostics; the read protocol is a contract the framework must keep stable the way it keeps the store protocol; the GraphQL and REST modules are new, model-driven, and carry no business names. The alternative, generating a per-spec API, duplicates the schema in a second artifact and couples each binding to codegen.

### 6. The wire form of a filter

Decision: REST carries a filter in the spec's own predicate syntax, parsed and checked by the framework; GraphQL carries it in generated input types; both produce the same AST. Decide whether REST also accepts a JSON form of the AST for clients that build filters programmatically.

Costs: the predicate grammar becomes a public wire format and gains compatibility obligations; a JSON form is a second spelling of the same thing to document.

## Scorecard

| read | kind | verdict | where it stands |
|---|---|---|---|
| R1 | one record by identity | HOLDS | by-key lookup, as built; subsumed by the composed read API |
| R2 | records related to one record | HOLDS | navigation and join reads, as built; traversal on the composed read API |
| R3 | a declared subset | TAX | order is the caller's, unpromised otherwise; decisions 1, 4 |
| R4 | a subset shaped by the asker's values | GAP | composed or registered question; nothing added to the spec; decisions 1, 2 |
| R5 | membership of one record | HOLDS | membership test, as built; gains arguments |
| R6 | a number about the whole system | HOLDS, by decision | the caller counts or sums; a defined number is a registered question; decision 3 |
| R7 | a number per group | HOLDS | derived property on the group's shape; otherwise asked once per group |
| R8 | who may read what | open | the viewer on the whole read; record-level visibility added to OQ33 |
| R9 | a record as it was | by design | history is data (README §12, §20) |
| R10 | why a record is what it is | deferred | README §22 `why` |
| R11 | what the system refused | HOLDS | commit result; rejection as data |
