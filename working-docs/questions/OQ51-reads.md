# OQ51 — Reading the system: the spec is the schema, the caller composes the question

**Status:** open — opened 2026-10-12 by `investigate-search.md` as the search question; widened the same day by `investigate-reads.md` to reads in general, with search as the first special case; nothing implemented
**In plain terms:** every shape can already be read by key and by following its declared references, but nothing lets a caller compose its own question (which fields, which filter, what order, how many) the way a GraphQL client does against a schema. The spec already is the schema. A read takes part in no proof, so a question gains nothing by living in the spec; the question is what the caller may compose, how a question an engineer owns is checked against the spec at build time, and what the generated read API promises.

---

The discussion and the worked examples are in `working-docs/investigate-reads.md` (the general case) and `working-docs/investigate-search.md` (search). The decisions, one line each.

General to reads (`investigate-reads.md`, section 10):

1. The read API (the calls that get data out of a running system) is composed by the caller from the spec's declared vocabulary (select, traverse, filter in the spec's predicate language, order, page, aggregate), generated from the spec with the existing views as its types, and the spec declares no reads. Closes README §22's deferred question: `act` is strictly input.
2. A question may be registered beside the client (a GraphQL document or the spec's predicate syntax) and checked against the model at the engineer's build, with a named candidate method in the typed store, a generated test, and a report of what a spec change breaks.
3. The spec-side question constructs proposed earlier are dropped and kept on file: a refinement with parameters, a derived value outside any shape, a default order on a refinement.
4. Binding-side: how a page stays stable when the caller's order does not discriminate (refuse, hidden store-key tie-break in the cursor, or documented instability).
5. The composed read is a spec-agnostic protocol value the framework executes from the model at runtime, with a generic entry beside `commit`; the typed Kotlin builder, the registry, a GraphQL binding, and a REST binding are translators onto it that depend on the framework only, so the API layer is pluggable the way the store layer is.
6. The wire form of a filter: the spec's own predicate syntax for REST, generated input types for GraphQL, and whether a JSON form of the predicate AST is accepted too.

Specific to search (`investigate-search.md`, section 7):

7. `contains` joins the builtin vocabulary as a substring predicate, because the filter language is the spec's predicate language; whether a prefix test ships beside it.
8. Deferred to the extension framework: whether a registered extension predicate may name the store as its authority (a search engine's relevance), crossing the one-evaluator line for that predicate alone.

Connections: the viewer as a parameter of the whole read and record-level visibility beside [OQ33](OQ33-visible-to.md); a site-wide search over unrelated shapes wants [OQ35](OQ35-mixins.md)'s shared trait; one notice per (saved search, product) pair is README §22's Mapping item; the digest's disarm rides `break-pharmacy.md` P7's TODO item.
