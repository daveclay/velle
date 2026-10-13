# Investigation: search — how an engineer builds a search feature in Velle

**Status:** first pass (2026-10-12) — conceptual, by analysis; nothing here runs. One language change is proposed (a refinement with parameters, plus a read order on a refinement and a text predicate), with the binding consequences worked through. Decisions for Dave at the bottom.
**Question tag:** [OQ51](questions/OQ51-reads.md)
**Parent:** `working-docs/investigate-reads.md` — reads in general; this doc is the search special case. That doc supersedes section 3c and decisions 1, 2, 3, and 6 below: a read takes part in no proof, so the search is a question the engineer registers beside the client or composes at request time, checked against the spec at build time, never a refinement with parameters; the price cap and the order are the caller's, the visibility gate is OQ33's, and the parameterized refinement stays on file as the remedy if spelling the match twice (in `SavedSearch` and in the client's question) ever proves too costly. Decisions 4 and 5 below stand.
**See:** README §8 (refinements), §10 (selectors and the mandatory `by`), §6 (collections are sets), §22 "External input (`act`)" (the deferred question whether `act` also names a read surface; the Mapping item; the escape hatch; the extension framework) · `working-docs/investigate_runtime.md` §6 (the candidate pre-filter) and §10 (the typed store surface and its question recognition) · [OQ33](questions/OQ33-visible-to.md) (visibility) · [OQ35](questions/OQ35-mixins.md) (a trait shared across shapes)

---

The two domain passes (`break-peptides.md`, `break-pharmacy.md`) modeled whole businesses and found the write side of the language holds. This pass turns to an ordinary application behavior whose center of gravity is the *read* side: search. Every spec so far describes what changes the state and what reacts to the change; what a caller reads back has been the generated typed surface, derived from the spec but never described by it beyond the refinements themselves. Search is the first behavior that forces the question of how much of the read side a spec should say.

The method is the one the break docs use: say what the behavior is in business terms, see which parts the language already states, name what it cannot, and propose the smallest change that closes the gap. Verdict scale as in `break-pharmacy.md`: **HOLDS**, **TAX**, **GAP**.

## 1. What a search is

Stripped of implementation, a search feature is eight things. A product owner cares about the first six; only an engineer cares about the last two.

1. **Input.** The searcher supplies values at the moment of asking: free text, filters (a price cap, a category), and who they are.
2. **Matching.** Which records count as hits, as a business definition: "a product whose name, brand, or tags mention the term, or a synonym the merchandiser registered for it".
3. **Visibility.** Which hits this searcher may see: sellable products only, age-restricted products only to verified adults, a customer's own orders only.
4. **Order.** What comes first: sponsored listings, then exact name matches, then newest. Or a relevance score the business cannot define and delegates to a search engine.
5. **Consequences.** What the business keeps about the search itself: every search is logged with its hit count, zero-hit searches go to merchandising, a shopper can save a search and be told when new products match it.
6. **Freshness.** Whether a product just listed is findable at once or at the next refresh.
7. **Result shaping.** Pages, page size, cursors, which fields come back, highlighted snippets, facet counts beside the results.
8. **The index.** The structure maintained so matching is fast: a full-text index, a search engine fed by the database, a denormalized search column.

The dividing line this pass draws, by README §1's rule ("can this be declaratively stated" versus "how should this be executed"): items 1 through 6 are statements about what the system means and belong in the spec; items 7 and 8 are how a read is executed and belong to compilation and the engineer's wrapper code, exactly as transport and persistence do for acts (README §22). Freshness sits on the line and the language already has a spelling for both sides of it (section 5).

## 2. Where the language stands today

What a search needs, against what v0 provides:

- **A read with input.** A refinement is a closed predicate: besides `this`, constants, `today`, and `now`, nothing enters it. The only way a value from outside reaches the spec is an act, and an act is a mutation. There is no construct for "a value supplied at read time". This is the gap, and everything else below is small beside it.
- **The read surface.** README §22: reading is generated typed accessors per shape and refinement (`invoices()`, `overdueInvoices()` in `examples/billing/output/src/main/kotlin/velle/generated/Billing.kt`), never a generic query API; and "whether `act` also names a read surface (querying a shape) or is strictly input" is deferred. Beneath the accessors, the typed store surface (`examples/billing/output/src/main/kotlin/velle/generated/BillingStore.kt`) carries one named candidate method per condition with a testable part, its parameters being the evaluation-moment constants (`today`-derived dates) the question recognition extracts as holes (`investigate_runtime.md` §10).
- **Order.** A collection is a set, unordered (README §6). The only ordering statements in the language are a selector's mandatory `by` clause (README §10), which picks one member. Nothing says the order in which a reader receives the members of a refinement.
- **Text.** The builtin vocabulary (README §5) has `lowercase` and `==`. No substring test, no prefix test, no full-text match.
- **Visibility.** `visible to` was cut from v0 and waits as OQ33, with its open sub-question of how a viewer is defined.

## 3. Three ways to spell a search, and which one is right

### 3a. The search as an act

The reified-request idiom the language already favors: the query is a transient act carrying the input, and the hits are a derived collection on it.

```
transient act ProductSearch {
    terms: text
    shopper: one Shopper
    hits: many Product = (Product where lowercase(name) contains lowercase(terms))
}
```

Rejected as the read, for two reasons that are both structural. A commit function returns acceptance or a refusal and nothing else (README §22; `evaluation.md`, "Errors, refusals, and retry"), so the caller cannot read `hits` back; a transient act's derived values are fixed at its commit and then gone. And a commit is serialized within its domain (`evaluation.md` U3): a search spelled as an act would queue against every write that touches what it reads, which for a product search is every product, stock count, and discontinuation in the system. A read that must wait for writes is the wrong thing by construction, and no binding change repairs that. The idiom is right for the *record* of a search (section 4, `RecordSearch`), where the business wants a durable fact and a rule to react to it.

### 3b. The search outside the spec

The engineer queries the store directly, composing the refinements the spec provides (`Sellable`) with their own text matching and ordering in SQL or a search engine. This is what v0 implies today, and it works. The cost is the one Velle exists to remove: the business definition of a hit, who may see what, and what ranks first live in engineer code, invisible to the product owner and unchecked against the rest of the spec. "Age-restricted products are shown only to verified adults" would be a line of SQL rather than a sentence beside the `never` that says the same shopper may not buy one.

### 3c. A refinement with parameters — recommended

A search is a refinement whose predicate takes values from outside. The smallest construct that says so is a parameter list on the refinement declaration:

```
shape ProductSearch(terms: text, maxPrice: decimal?, shopper: one Shopper) =
    Sellable where lowercase(name) contains lowercase(terms) and ...
```

Three properties make this the right shape, each following from something already settled:

- **It is a predicate with free variables, not a state.** An unparameterized refinement names a subset of a shape that an instance is in or out of, which is what makes it a rule's subject, a thing to capture at entry to, a thing to freeze inside. A parameterized one names a relation between an instance and some values: membership is relative to the arguments, so there is no entry moment, no exit, nothing to capture, nothing to freeze or gate, and nothing for a `never` to say is empty. The validator derives all of that from the parameter list: a parameterized refinement as a bare `when` subject, a `never` subject, or with `captured`, `frozen`, or `undeletable` in its body is a compile error. What remains legal is exactly what a predicate is good for: `is` with arguments, `exists`, `count`, `sum`, a derived collection, a selector's collection, composition with other refinements, and the generated read surface.
- **Every in-spec use is an inlining.** `this is ProductSearch(s.terms, s.maxPrice, s.shopper)` means the predicate with the arguments substituted. The trigger-set derivation (README §11), relevance gating, the read summaries, and the serialization-domain derivation see the inlined predicate and need no new reasoning: a rule whose condition reaches a parameterized refinement through a stored record's fields (the saved search in section 4) is triggered by every commit that writes what the inlined predicate reads, which is the right answer.
- **The read surface already has the hole mechanism.** The typed store's question recognition treats every evaluation-moment constant as a parameter of the named candidate method (`investigate_runtime.md` §10). A declared parameter is a hole by declaration rather than by compile-twice detection, and lands in the same place: `productSearchCandidates(terms, maxPrice)`, the one method an engineer overrides with full-text SQL, the "override the hot question" posture that section describes.

This also answers README §22's deferred question: `act` does not name a read surface. Reads are refinements, parameterized when they need input, and the trust boundary for reads is what it already is, every refinement readable through the generated surface.

## 4. The worked spec: a storefront

Full context; one construct that does not exist (the parameter list, the `by` clause on a refinement, `contains`), everything else v0 as written. Comment convention as in `examples/membership/membership.velle`.

```
-- ── Catalog ──────────────────────────────────────────────────────────────────

act Brand {
    name: text
}

act Category {
    name: text
}

act Product {
    name: text
    description: text
    brand: one Brand
    category: one Category
    price: decimal
    tags: many text
    ageRestricted: boolean
    sponsored: boolean
    listedAt: DateTime
}

-- Stock is a series of counts; the shelf holds whatever the latest count says.
act StockCount {
    product: one Product
    onHand: integer
    countedAt: DateTime
}

-- Discontinuing is a request answered by a record.
transient act Discontinue {
    product: one Product
}

shape Discontinuation {
    product: one Product
    discontinuedOn: timestamp on create
}

rule RecordDiscontinuation when Discontinue {
    Discontinuation from { product: product }
}

shape InStock      = Product where stockCounts is not empty and latest(stockCounts by countedAt).onHand > 0
shape Discontinued = Product where exists Discontinuation for this
shape Sellable     = InStock and not Discontinued

-- A merchandiser maintains synonyms: a search for "sneakers" also finds shoes.
-- velle: the search vocabulary is data, so it is the merchandiser's, not the
-- engineer's
act Synonym {
    term: text
    alias: text
}

act Shopper {
    name: text
    verifiedAdult: boolean
}

-- ── The search ───────────────────────────────────────────────────────────────

-- What a shopper's search means: a sellable product mentioning the term by
-- name, brand, tag, or registered synonym; under the price cap if one was
-- given; age-restricted products only for a verified adult. Sponsored
-- listings first, then exact name matches, then newest.
-- velle: a refinement with parameters (proposed) — a predicate with free
-- variables, never a state. The body scores a hit; `by` is the order hits are
-- read in. `contains` is the proposed substring predicate.
shape ProductSearch(terms: text, maxPrice: decimal?, shopper: one Shopper) =
    Sellable where
        (lowercase(name) contains lowercase(terms)
         or lowercase(brand.name) contains lowercase(terms)
         or exists (tags as tag where lowercase(tag) contains lowercase(terms))
         or exists (Synonym as syn where lowercase(syn.alias) == lowercase(terms)
                    and lowercase(this.name) contains lowercase(syn.term)))
        and (maxPrice is none or (maxPrice is some and price <= maxPrice))
        and (not ageRestricted or shopper.verifiedAdult)
    {
        exactName: boolean = lowercase(name) == lowercase(terms)
    }
    by sponsored descending, exactName descending, listedAt descending

-- ── What the business keeps about searches ───────────────────────────────────

-- Every search is logged with its hit count, so merchandising sees what
-- shoppers look for and fail to find. The record is separate from the read:
-- the wrapper reads the hits, then commits the record.
-- velle: the transient-request idiom (README §4); the hit count is derived
-- in the spec from the same definition the read uses, never caller-supplied
transient act RecordSearch {
    terms: text
    maxPrice: decimal?
    shopper: one Shopper
    hits: integer = count(ProductSearch(terms, maxPrice, shopper))
}

shape SearchEvent {
    terms: text
    shopper: one Shopper
    hits: integer
    searchedAt: timestamp on create
}

rule LogSearch when RecordSearch {
    SearchEvent from { terms: terms, shopper: shopper, hits: hits }
}

shape MissedSearch = SearchEvent where hits == 0

-- A shopper saves a search and gets a daily digest of products listed since
-- the last digest that match it.
-- velle: the saved search is the reified query; its matches are the same
-- parameterized refinement, read in-spec through the record's own fields
transient act SaveSearch {
    shopper: one Shopper
    terms: text
    maxPrice: decimal?
}

shape SavedSearch {
    shopper: one Shopper
    terms: text
    maxPrice: decimal?
    savedAt: timestamp on create
    lastDigestAt: DateTime = if matchDigests is empty then savedAt
                             else latest(matchDigests by sentAt).sentAt
    newMatches: many Product = (ProductSearch(terms, maxPrice, shopper) where listedAt > this.lastDigestAt)
}

rule KeepSearch when SaveSearch {
    SavedSearch from { shopper: shopper, terms: terms, maxPrice: maxPrice }
}

shape MatchDigest {
    search: one SavedSearch
    products: many Product
    sentAt: timestamp on create
}

shape SavedSearchWithNews = SavedSearch where newMatches is not empty

-- The digest's own creation moves lastDigestAt past every current match, so
-- the guard disarms in truth; see F6 for whether the disarm proof can see it.
rule SendDigest when SavedSearchWithNews on Daily {
    MatchDigest from { search: this, products: newMatches }
}
```

## 5. Findings

### F1 — GAP: a read cannot take input

The center of the pass (section 3c). Without parameters, the only spellings are the act (wrong as a read) or engineer code (invisible to the owner). The proposed construct and its derived restrictions are decision 1.

### F2 — GAP: a refinement has no read order

The members of a refinement are a set; a search result is a list. The proposed `by` clause on a refinement declaration states the order a reader receives members in, with the same datums a selector's `by` accepts (README §10: declared, orderable properties, body properties included; never `id`) plus a direction word, which selectors do not need because `latest` and `first` fix it. Two things follow for free. Stable paging is exactly the discrimination rule README §10 already imposes: an order whose datums fail to discriminate gives unstable pages, so the fail-loudly rule gains a second justification and needs no paging vocabulary. And the order belongs on unparameterized refinements too ("overdue invoices, oldest first"), where the generated accessor would honor it; the open TODO item on whether a store's scan order is a contract obligation (`working-docs/TODO.md`, runtime follow-ons) resolves the other way for any refinement that declares its order. Decision 3.

### F3 — GAP: no text predicate

`lowercase(name) contains lowercase(terms)` needs `contains`, a substring test with exact semantics, the same closed-vocabulary growth README §5 anticipates. A prefix test for suggestions is the same decision. The pre-filter renders it to `LIKE`; the authoritative check runs in memory as every predicate does (`investigate_runtime.md` §6). Decision 4.

### F4 — TAX, by design: relevance the business cannot define

"Products matching the search terms, best match first" as a search engine understands it (tokenization, stemming, a relevance score) has no meaning the spec can state, and a spec that writes `matches` would be describing nothing (README §1: a construct's meaning is the spec's, never the implementation's). The honest spelling is a registered extension predicate whose registration says its meaning is delegated, which is the escape hatch README §22 lists as deferred, now with a concrete customer. What delegation costs the machinery: an opaque predicate degrades to `TRUE` in the pre-filter and cannot be spent in a proof; a rule whose condition reaches it (a saved-search digest over a delegated matcher) evaluates in memory against a reference implementation that may disagree with the engine; and the one hard line in the runtime, authoritative evaluation in one place in memory (`investigate_runtime.md` §2, §6), would be crossed for that predicate alone if the store is to be the authority on what matches. Recommendation: v0 ships `contains` and nothing delegated; relevance arrives with the extension framework, and whether a registered predicate may name the store as its authority is decision 5.

### F5 — HOLDS: everything the owner says about a search beyond matching

By analysis against the spec above:

- **Sellability and visibility are refinements, reused.** `Sellable` serves the search, a `never` on purchases, and any report alike; the age gate is one conjunct reading the shopper parameter. Visibility as a parameter of the read is what OQ33's `visible to` would otherwise annotate per field; the two should be designed together (noted on OQ33), but search needs nothing from it today.
- **Synonyms are data.** The merchandiser's vocabulary is a shape the search reads, so "sneakers finds shoes" is a committed `Synonym`, not an engine configuration file.
- **The search log and the zero-hit refinement** are the transient-request idiom unchanged (README §4), with the hit count derived from the one definition the read uses. `MissedSearch` is a pure classification (README §20, rung 1); a rule can hang off it.
- **The saved search** is the reified query, and `newMatches` reads the parameterized refinement through the record's own fields: one definition serves the shopper's live search, the log's hit count, and the digest. This is the argument for parameters over a read-only construct: a parameterized refinement is a predicate the spec can use, not only a question the outside can ask.
- **Freshness is the author's, in existing vocabulary.** A search over live derived state (`Sellable` reads the latest stock count) means a product is findable the moment its commit lands. An engineer who feeds a search engine asynchronously is not implementing that spec. The spec can say stale is acceptable the way README §19 already says it: a rule-maintained stored field recomputed on a cadence (`searchable: boolean`, `searchText: text` on `Product`, refreshed `on Hourly`) is the snapshot rung, and the search reads the snapshot. The cadence is then the freshness promise, visible in the header, and the stored column is exactly what an index builder consumes, so the spec's statement of freshness and the engineer's indexing strategy meet at one declared field.

### F6 — TAX, probable: the digest's disarm is a temporal argument

`SendDigest` is a tick rule, so its condition must be a state its own effects provably exit (README §18, the disarm law). It does: the digest it creates moves `lastDigestAt` to now, and nothing is listed after now. But the proof runs through a derived property, a selector, and a comparison against the clock, and the disarm prover recognizes `not exists W for this` and flag assignments (`break-pharmacy.md` P7 found the same wall one step earlier, at a refinement over `is not empty`). Expected spelling the prover accepts: the time-window guard, `SavedSearch where newMatches is not empty and not exists (MatchDigest where search == this and sentAt > now - 1 days)`, the rate-limit form README §18 lists. The natural spelling is the one the business said; the accepted one is a restatement. Rides the P7 TODO item.

### F7 — TAX, open: one notice per (shopper, product) pair is the Mapping item

The digest carries the matched set as a `many` on one record, which is why it is expressible. "One notice per matching product per saved search" is a fact per pair, and a rule fires per subject with no way to name the other side of a join from its body; README §22's Mapping item names exactly this ("one `Reservation` per line item" as sugar over the guard-correlated fan-out). Nothing new; search adds a second customer for it.

### F8 — HOLDS, by design: what stays outside the spec

- **Pages, cursors, page size, snippets, which fields are returned.** The caller's consumption of an ordered read: engineer wrapper code and transport, like the mapping of a request onto a commit function (README §22). The generated accessor's own shape (a list, a lazy sequence, a window) is a binding question, decision 6, not a language one.
- **Facet counts** ("12 in Shoes") are `count(ProductSearch(terms, maxPrice, shopper) where category == c)` per category, composed by the wrapper over the read surface or written as grouped SQL in the store override. A derived property on `Category` could not state it without parameters of its own, and parameters on derived properties are functions by another name; the line stays at refinements.
- **The index.** How matching is made fast is storage strategy (README §1). The one place it touches the spec is F5's freshness column.
- **Search across unrelated shapes** ("products, articles, and people"): a cross-base union exposes nothing but `id` and inverses (README §9), so a site-wide search would narrow per operand inside one predicate, which reads as badly as §9 says it should. The shared trait (`Titled`, `Named`) is OQ35's mixin; search is a customer, not a new question.

## 6. Binding consequences

If decision 1 through 4 go as recommended, the generated surface changes in two places and the runtime contract in none.

- **The typed accessor.** Beside `products()` and `sellables()`, a parameterized refinement generates a function taking its parameters, returning the hits as views in the declared order, each view carrying the body's derived properties: `productSearch(terms: String, maxPrice: BigDecimal?, shopper: Ref<Shopper>): List<ProductSearchView>` (`compiler/src/main/kotlin/velle/Codegen.kt`, the accessor block that today wraps `instancesOf`). Evaluation is a read over settled state, the case `investigate_runtime.md` §6 marks as safe for the pre-filter with no caveat; the authoritative check and the ordering run in memory over the shortlist.
- **The typed store.** The parameterized refinement is a named question whose holes are its declared parameters plus any evaluation-moment constants, exactly the recognized form of `remindOverdueCandidates(dueBefore, sentOnAfter)` (`investigate_runtime.md` §10): `productSearchCandidates(terms: String, maxPrice: BigDecimal?)`, with the superset contract, defaulted onto the generic protocol, overridable in hand-written SQL. `shopper.verifiedAdult` is a keyed read, not a hole. The derived conjuncts (`Sellable` through the latest stock count) drop out of the candidate query by polarity, which is the performance story of search in Velle in one sentence: a search is as fast as the column-expressible part of its predicate and order, and the author moves work into that part by materializing a snapshot column (F5), a visible decision with a stated cadence.
- **The runtime's protocol** (`StateResolver`, `fetchCandidates`) is unchanged: a declared parameter is a constant in the filter tree by the time the store sees it.
- **The record beside the read.** A wrapper that logs searches calls the read, then commits `RecordSearch`; the hit count is re-derived in the commit from the same definition. Two evaluations of one predicate per search is compilation's concern, not the spec's.

## 7. What needs Dave's input

### 1. A refinement may declare parameters, and a parameterized refinement is a predicate, never a state

Decision: adopt `shape Name(param: type, ...) = Base where ...` with the validator refusing every state-only use (a bare `when` subject, a `never` subject, `captured`, `frozen`, `undeletable`) and admitting every predicate use (`is` with arguments, `exists`, aggregates, selectors, derived collections, composition, the generated read). Every in-spec use is an inlining; the analyses see the substituted predicate. This also closes README §22's deferred question: `act` is strictly input, and reads are refinements.

Costs: `shape` now declares two kinds of refinement under one keyword, distinguished by a parameter list; the diagnostics for the refused uses must explain why a relation has no entry moment; grammar, validator, codegen, and the typed store generator each grow (section 6). The alternative, a search-only construct, states less and serves fewer sentences (F5's saved search needs the in-spec use).

### 2. The word

Decision: keep `shape` for a parameterized refinement, or introduce a noun. `search` is too narrow (a shopper's own orders, open slots on a date, and a facet count are the same construct), `query` and `view` are computer words README's opening rules out, `lookup` reads as a function. Keeping `shape` costs nothing new; the awkwardness it carries is the one every refinement already carries, and if `shape` is ever replaced the parameterized form goes with it. OQ50's precedent (the keyword is the word the prose uses) applies: the prose here says "a search", "a refinement with parameters", and never finds a third noun.

### 3. A refinement may declare the order its members are read in

Decision: a `by` clause after the predicate and body, `by <datum> [ascending|descending], ...`, with the datums a selector's `by` accepts plus the body's derived properties, honored by the generated accessor and the typed store's candidate method, and legal on unparameterized refinements too. Direction words are new vocabulary (`descending` as a contextual word after a datum, like `by` itself); the discrimination rule is README §10's unchanged, now also the stable-paging rule. Decide whether a refinement with no `by` has an unspecified read order (today's scan order, the open TODO item) or whether that TODO item's question becomes "a refinement read through the surface declares its order or is read in no promised order".

### 4. `contains` joins the builtin vocabulary

Decision: `a contains b` as a text predicate with substring semantics, exact and case-sensitive, so case folding stays the author's visible `lowercase`. Decide whether a prefix test ships beside it (suggestions and autocomplete want one; `contains` serves loosely). Cost: none structural; the pre-filter renders `LIKE`, the type check is F2's.

### 5. Delegated matching, when the extension framework lands

Decision, deferrable: whether a registered extension predicate may declare the store as its authority, which crosses the one-evaluator line (`investigate_runtime.md` §2, §6) for that predicate alone, or whether every registered predicate carries a reference implementation that is the meaning and the store is only ever a pre-filter. The first gives a real search engine's relevance an honest home at the cost of a predicate the spec cannot check and a rule over it that may disagree with what the shopper saw; the second keeps the line and limits search to what a reference implementation can define. Recommendation for now: v0 ships `contains` only, the question waits for the extension framework, and the escape-hatch item in README §22 gains search as its first named customer.

### 6. The generated read's shape

Decision, binding-side: whether the accessor for a parameterized refinement returns a list, a lazy sequence, or takes a window (offset and size, or a cursor over the declared order). Engineer-facing only; the language says nothing. A window can push below the authoritative in-memory check only when the whole predicate and order are column-expressible, so a window on the accessor is honest only as "the first N after evaluation" unless the typed store's candidate method takes it too. Recommendation: the accessor returns the ordered list; a window lives on the candidate method as an engineer's optimization, and nothing promises it is applied before the authoritative check.

## Scorecard

| # | finding | verdict | where it stands |
|---|---|---|---|
| F1 | a read cannot take input | GAP | the parameterized refinement; decision 1, 2 |
| F2 | a refinement has no read order | GAP | `by` on a refinement; decision 3 |
| F3 | no text predicate | GAP | `contains`; decision 4 |
| F4 | relevance the business cannot define | TAX, by design | registered extension, store authority open; decision 5 |
| F5 | sellability, visibility, synonyms, the log, the saved search, freshness | HOLDS | section 4; OQ33 noted |
| F6 | the digest's disarm is a temporal argument | TAX, probable | the time-window spelling; rides `break-pharmacy.md` P7's TODO item |
| F7 | one notice per (shopper, product) pair | TAX, open | README §22 Mapping item |
| F8 | pages, facets, the index, cross-shape search | by design | wrapper code; OQ35 for the shared trait; decision 6 |
