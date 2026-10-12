# OQ51 — Search: a read that takes input

**Status:** open — opened 2026-10-12 by `investigate-search.md`; nothing implemented
**In plain terms:** a search is a refinement whose predicate takes values from outside (the terms, a price cap, who is asking) and whose members are read back in a stated order. Nothing in the language lets a value from outside reach a read, and nothing states the order a reader receives members in.

---

The discussion and the worked storefront spec are in `working-docs/investigate-search.md`. The six decisions it asks for, one line each:

1. A refinement may declare parameters (`shape ProductSearch(terms: text, shopper: one Shopper) = Sellable where ...`); a parameterized refinement is a predicate with free variables, never a state, and the validator refuses every state-only use. Closes README §22's deferred question: `act` is strictly input, reads are refinements.
2. The word: keep `shape`, or a noun for the parameterized form.
3. A refinement may declare the order its members are read in (`by <datum> ascending|descending, ...`), on unparameterized refinements too, honored by the generated accessor and the typed store.
4. `contains` joins the builtin vocabulary as a substring predicate; whether a prefix test ships beside it.
5. Deferred to the extension framework: whether a registered extension predicate may name the store as its authority (a search engine's relevance), crossing the one-evaluator line for that predicate alone.
6. Binding-side: the generated read's shape (ordered list, lazy sequence, window).

Connections: visibility as a read parameter beside [OQ33](OQ33-visible-to.md); a site-wide search over unrelated shapes wants [OQ35](OQ35-mixins.md)'s shared trait; one notice per (saved search, product) pair is README §22's Mapping item; the digest's disarm rides `break-pharmacy.md` P7's TODO item.
