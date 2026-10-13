# OQ51 — Reading the system: the spec is the schema, the caller composes the query

**Status:** answered and built 2026-10-12 (rulings R1–R11 in `investigate-reads.md`; README §22 states the query API; `Read.kt`, the codegen, `QueryApiTest.kt`); open only for the follow-on items in `TODO.md` (a registered query's store method and generated test, the generated specs' ordering datum, collection filters)
**In plain terms:** every shape can already be read by key and by following its declared references, but nothing lets a caller compose its own query (which fields, which filter, what order, how many) the way a GraphQL client does against a schema. The spec already is the schema. A read takes part in no proof, so a query gains nothing by living in the spec; the question is what the caller may compose, how a query an engineer owns is checked against the spec at build time, and what the generated read API promises.

---

The discussion and the worked examples are in `working-docs/investigate-reads.md` (the general case) and `working-docs/investigate-search.md` (search). The questions are asked in full, with a recommendation and the costs of each answer, in `investigate-reads.md` section 11; answer them there. One line each:

1. Does the spec never declare a read, with every query composed or registered by whoever asks it? — **answered 2026-10-12:** yes; README §22 updated, `act` is strictly input.
2. Do we drop the three spec-side constructs the search pass proposed (a refinement with parameters, a derived value outside any shape, a default order on a refinement)? — **answered 2026-10-12:** yes, all three dropped and kept on file as remedies.
3. What is a read request, and what is a read result, as plain data? — **answered 2026-10-12:** a query returns the shape's generated Kotlin data classes, whole instances plus a cursor, no field selection; the protocol value underneath stays generic; references are typed handles for now, and how associated records are loaded becomes configurable later, following GraphQL's lead: the query names the relationships to load, nested (TODO item).
4. Is a read a transaction? — **answered 2026-10-12:** a read-only snapshot, U1 and nothing else, never queued; in `evaluation.md`; isolation levels are a TODO item.
5. Does the first version ship without visibility enforcement, with the viewer slot present for when OQ33 lands? — **answered 2026-10-12:** yes, ship without auth.
6. How does an engineer register a query (query files in the spec's predicate syntax, or typed-builder expressions registered in Kotlin)? — **answered 2026-10-12:** Kotlin code, typed-builder expressions registered under a name.
7. What does a filter look like on the wire for REST? — **deferred 2026-10-12:** no REST or GraphQL layer is being built; returns with the first out-of-process binding.
8. How does a page stay stable when the caller's order does not tell two records apart? — **answered 2026-10-12:** the cursor carries the last row's order datums plus its store key as a hidden tie-break; the key is never an ordering datum the caller sees.
9. Should a stable row order be required, and where is it specified? — **answered 2026-10-12:** yes; for now every query names its order and the store promises no scan order; a default order on the shape is a TODO item.
10. Does `contains` join the language, and does a prefix test join it too? — **answered 2026-10-12:** yes to both, plus `ends with` and `like` with SQL-style wildcards; promoted to README §5, §10 and `grammar.md`.
11. What do we build first? — **narrowed 2026-10-12:** the Velle read/query binding API only (the generic read entry and the typed Kotlin builder); **answered 2026-10-12:** billing.

Also open, in `investigate-search.md` section 7: whether a registered extension predicate may name the store as its authority (a search engine's relevance), deferred to the extension framework.

Connections: the viewer as a parameter of the whole read and record-level visibility beside [OQ33](OQ33-visible-to.md); a site-wide search over unrelated shapes wants [OQ35](OQ35-mixins.md)'s shared trait; one notice per (saved search, product) pair is README §22's Mapping item; the digest's disarm rides `break-pharmacy.md` P7's TODO item.
