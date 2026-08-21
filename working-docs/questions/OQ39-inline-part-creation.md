# OQ39 — Inline part creation: multi-part acts through the generated commit function

**Status:** open — direction settled, syntax not designed (2026-08-21)
**In plain terms:** an order committed together with its line items: the parts don't exist yet, so they can't be references — they're values created in the act's commit. How do parts arrive through the generated commit function, and what does that do to "one commit = one act instance"?
**See:** README §4 ("one commit carries exactly one instance"; the container pattern), §6 ("Committing and assigning collections": references-always `many`), §11 (firing order never specified; entrant semantics), §12 (assignment; one-writer), §20 ("All-or-nothing batches"), §22 "External input (`expose`)" · `checks.md` F4, V1, V15, V17, V19, V20

---

On an exposed act, `many` names existing instances by reference (README §6) — a reading that deliberately excludes this case. An act whose `many` targets *don't exist yet* is nested creation, and no current spelling serves it:

- **Parts can't be committed first.** Each `OrderLine.order: one Order` needs an order that doesn't exist yet — and even if it did, each part-commit would be its own act, firing rules per part: wrong granularity for "these arrive together."
- **The act can't reference them.** References name existing instances (README §6); these are values.
- **The container pattern has an unspecified seam.** README §4 says a multi-part act *is* one container instance with the parts as related shapes — but §22's generated commit function takes "that shape as input," and nothing says how the parts ride along.

The gap, named: **the input closure.** In the state model the commit carries one act instance; at the transport boundary the input is plausibly the act *plus its inline part values*. Is that closure the "one instance," or a violation of it? This is the question the construct must answer before any syntax matters.

Constraints any design must respect:

- **`many X` stays references-always** (README §6). One keyword, one meaning: inline creation needs its own *visible* spelling, never an act-position reinterpretation of `many`.
- **Creation-only, structurally.** The closure exists because its targets cannot be referenced — they do not exist yet. Anything that already exists is referenceable, and changing it is served with no boundary support at all: update intent arrives as an act carrying references, and a rule's assignment reaches the related instance (README §12, the `CorrectEmail` example) or fans out across a collection (README §6; `examples/enrollment/enrollment.velle`, the `AssignAdvisor` act and its `ApplyAdvisor` rule). So the input structure carries exactly two relationship treatments — a reference to what exists, an inline value for what does not — and nothing update-shaped ever rides the closure. This is the same line the `connectOrCreate` refusal draws below, stated as a constraint rather than left implicit in the prior art.
- **F4 totality reaches the parts.** Each inline part is a creation with every field covered — including the back-reference (`order: one Order`), which only the language can populate: a committer-suppliable back-reference would be a claim about an instance that doesn't exist yet, so within the closure that field is structurally language-populated, `timestamp`-style.
- **Rule granularity is entrant semantics — answered.** The container's rules fire once per act, and the parts' creations are also matchable conditions within the same transaction: a rule `when OrderLine ...` fires once per part, every firing standing or falling with the act — the fan-out precedent README §20 already blesses (`ReserveStock`). A feature, not a hazard; an author who wants container granularity writes the condition on the container.
- **Transient containers.** If the container is `expose transient`, the parts hold a relationship to a transient act — exactly what V17 bans for durable shapes, and V17's reference direction forces the answer: transience is downward-closed over the closure tree, so a transient container means the whole tree falls together at transaction close, only copied consequences surviving. The remaining decisions — the scoped V17 carve-out that makes even that combination declarable, V18's reach over transient parts, and the durable-container-with-transient-branch mix — are spun off as OQ43.

## Direction (settled 2026-08-21)

The instinct to distrust the construct comes from watching traditional systems mishandle it: an API receives a composite payload and the business layer decomposes it into ordered steps — insert the order before its line items, all inside one database transaction. That "how" question dissolves in Velle, because it conflates two orderings that the traditional business layer handles as one lump — and Velle already has an answer for each.

**Ordering 1 — materialization order — is a compiling concern, never spec surface.** "Insert the parent before the children" is a fact about storage: which row physically lands first so a foreign key can resolve. README §4 already claims physical keeping as compilation's business, and atomic landing is already the engineer's store's obligation under the universal-transaction contract (`evaluation.md`, U1–U5). At the spec level there is no "before": the closure lands as one commit, pre-state and post-state are both well-defined at it, and no intermediate state where the order exists without its lines is ever observable. The topological order over the closure's reference edges (the part's `order: one Order` points at the container, so the container materializes first) is fully derivable by the compiler and surfaces nowhere in the language.

**Ordering 2 — rule firing order — is already answered, and the closure changes nothing.** README §11: firing order within a transaction is never specified and must provably not matter; where data flows the dependency graph orders firings, and where it doesn't, siblings must commute (OQ16's confluence proof). At the closure's commit the entrants are the container *and* each part, so `rule ... when Order` fires once and `rule ... when OrderLine` fires once per line, all sharing the act's transaction — the rule-granularity answer stated in the constraints above, and ordinary entrant semantics rather than new machinery.

**No rules are synthesized.** The parts are not materialized by rules — rules cannot carry outside values into the state; only commits do (README §4). The parts are materialized by the commit itself, the way `timestamp` fields are populated: construction logic inside the generated commit function, below the spec. Rules only *react* to the closure's commit, under the existing no-ordering law.

**The named gap resolves by generalization, not violation.** "One commit carries exactly one act instance" exists to make "can these triggers coincide?" answerable from trigger shapes alone (README §4). A declared closure preserves that purpose: its membership is static, so a closure commit can make entrants of exactly its declared shape set — statically known, feeding one-writer coincidence analysis (V1) and reachability exactly as a single-instance commit does. So the honest statement is: **one commit = one statically-shaped closure**, of which the single instance is the degenerate case. The invariant's analytical function survives intact; only its phrasing generalizes.

**The closure is a fact about one commit, never a property of the instances it creates.** The moment the closure lands, container and parts alike are ordinary instances — updated by later acts through rule assignment (README §12), frozen, exited, referenced like any instance. Nothing about a part outlives the commit that minted it except the instance itself.

### What the compiler derives with no author input

- **Materialization order** — topological sort of the closure's reference tree, container at the root; purely compilation, never observable.
- **Back-reference population** — the closure edge *is* the inferred inverse of a declared `one` on the part (README §6), so the compiler knows which field is language-populated, and F4 totality over the parts is satisfied structurally.
- **The commit-function signature** (F4) — the container's stored fields, plus per part a tuple of the part's stored fields minus the back-reference. The empty part collection is the absence, consistent with §6's "`many` takes no `?`."
- **Trigger sets, one-writer, reachability** — the closure's shape set is static; part shapes become committable-via-closure, extending the trigger and reachability analyses with no new machinery. One consequence needs naming rather than inventing: a closure commit is a **multi-entrant transaction** — N entrants of one part shape — so a single rule can now coincide *with itself*. `rule ... when OrderLine` fires once per line (the granularity answer above), and if its body assigns through the language-populated back-reference (`this.order.…`), every firing's target path provably converges on the same container instance — the back-reference guarantees the aliasing. That is a write-write conflict among sibling firings, exactly what one-writer (README §12; `checks.md` V1) and the sibling-confluence legs (`checks.md` V15) exist to refuse, and the entrant multiplication itself is precedented — ticks and fan-out assignment already produce it (`checks.md` V1's coarse fan-out extension). So the analyses need the closure registered as an entrant multiplier, and V1's pair enumeration extended to self-pairs on closure-riding trigger shapes — not a new check.

  The refusal is total, settled 2026-08-21. The fold spelling is no escape: all of a commit's firings read one state (`evaluation.md`, U1), so N sibling folds each compute snapshot-plus-own-value and last-in-wins keeps exactly one — a lost update inside one transaction, and the §19 insensitivity whitelist never applies because it is about cross-commit reordering, where each firing folds over its predecessor's landed result; siblings under one snapshot don't chain. Value-equal writes (a part-triggered rule assigning the same constant to a container field, N identical firings) are refused coarsely too: proving right-hand-side equality across firings is instance-level reasoning V1 already declines ("instance-level disjointness of target sets is never attempted"), and the fix is one line. The diagnostic prescribes the two served spellings, both existing machinery: derive the aggregate on the container instead of denormalizing it (README §7 — `total` as a derived `sum` over the inferred inverse), or move the condition to the container for once-per-act granularity (the granularity answer above). The remaining imaginable intent — "last part wins" — is order-dependence over unordered siblings, meaningless as written, and served by `latest ... by` over a business datum where the order is real (README §19, the predecessor recurrence's selector).
- **Minimum-parts policy** — "an order must have at least one line" needs no closure feature: it is a `never` or a gate refinement over the committed state, existing machinery (README §20, §21).

### What the author must state — the gaps the construct closes

1. **Closure membership** — the essential gap. Given `expose Order`, the compiler sees every inferred inverse (`orderLines`, `shipments`, `refunds`) and cannot know which arrive *inline with the act* versus exist independently later: that is a trust-boundary fact about what the committer supplies, not a structural fact. The author marks each edge that rides the closure, recursively (a part's own relationship is either a reference to an existing instance or another nested creation one level down); every unmarked relationship stays a reference, so §6's references-always reading never bends.
2. **Which back-reference, when ambiguous** — a part declaring two `one` fields of the container's shape (the `Transfer.source`/`target` situation, V19) leaves the language-populated field undetermined; the author names it. Same shape as the existing V19 ambiguity machinery, same compile-error-demanding-intent posture.

### v0 restriction: closures are trees

Parts referencing *each other* within one closure (`OrderLine.bundledWith: one OrderLine`) break the model: references name existing instances (README §6), and a sibling part has no `id` yet, so the input would need a closure-local naming scheme the language does not have. Restricting the closure graph to a **tree** — every in-closure reference points up, at its enclosing level — makes materialization order always derivable and back-references always language-populated. Sibling links and cycles are refused at compile time: "this reference targets an instance created in the same closure; commit it and name it in a later act, or restructure." Lifting the restriction later is additive.

The restriction also matches the write side's existing grain: a collection-path assignment traverses exactly one `many` hop and never writes *through* a member (README §6, "one hop only"; `checks.md` V20) — each level of reach is its own visible declaration, and the nested `with` grammar takes the same stance on the creation side, one level named at a time.

### The generated surface: a projection, not a mirror

The generated commit function's input structure (README §22, "External input") is a boundary between two languages, and "a data structure mirroring the shape" undersells it: the input type for an exposed shape is the shape **minus everything the committer may not claim** — no `id`, no `timestamp` fields, no derived properties (all already ruled at §22 / V21), and, new with the closure, no back-references on inline parts. The divergence between the boundary type and the state shape is the trust boundary made visible in the engineer's type system, and it is the projection principle at work (see `GLOSSARY.md`): the input types are a deterministic projection of the spec's declarations, never hand-maintained. It also carries the structural-impossibility trade the language already makes (README §4, §6): everything *unrepresentable* in the input type never needs a runtime refusal, so the runtime refusal surface shrinks to what only runtime can know — the referenced instance exists, no duplicate references in a collection (§6), and the input-constrained `never` guardrails compiled to the boundary (§21).

The closure's two relationship treatments reify as two wrapper kinds in the target language. A relationship that does not ride the closure generates a **typed reference handle** (`Ref<OrderItem>`-shaped) — which is also how `id` opacity (README §5) survives the boundary: the engineer holds a typed handle obtained from the generated read surface, never a raw string or UUID, so the spec's independence from identity representation extends into the engineer's layer. An edge that rides the closure generates a **nested input type** (`New<OrderLine>`-shaped), recursively containing its own reference-or-inline fields per the closure declaration, minus the language-populated back-reference. The wrapper names are codegen vocabulary, per target language, and never Velle syntax: the author's declaration — closure membership plus back-reference naming, above — is sufficient to derive the entire input type tree, so the Velle-side syntax never ventures into the engineer's layer of abstraction, consistent with §22's settled posture that the declaration names no mechanism.

Prior art, for and against — the input vocabulary is well-precedented; the commit semantics are not:

- **GraphQL input object types.** GraphQL (the API query language) separates input types from output types as a core design decision — the same insight that the boundary shape is not the state shape. But GraphQL input types are hand-authored per mutation and drift from the model freely; deriving them from the closure declaration is the stronger position.
- **The connect/create split.** GraphQL itself does not standardize nested creation, but its ecosystem converged on exactly the reference-versus-inline distinction: Prisma (a TypeScript database toolkit) spells it `connect: {id}` versus `create: {...}` inside a relation field of a nested write — `connect` is the reference handle, `create` is the nested input type. A notable convergence: **Prisma's nested creates are also tree-restricted** — a nested create cannot reference a sibling created in the same write. An independent design hitting the same line is evidence the v0 tree restriction is the natural one.
- **Opaque identity.** GraphQL's `ID` scalar is specified as not necessarily human-readable — the same opacity stance as Velle's `id`.
- **Deliberately not imported.** Prisma also offers `connectOrCreate` (create the target if the reference resolves to nothing — an upsert). The boundary must refuse that: it smuggles a read-then-decide into the input structure, and "create it if it doesn't exist" is a spec-level decision — a rule, a gate refinement, a `never` — never transport sugar. Likewise GraphQL has no transaction semantics for nested mutations (atomicity is whatever the server's resolver code does), whereas the closure is one commit by construction — the prior art validates the input shapes, not the semantics.
- **A cautionary contrast from object-relational mappers.** JPA/Hibernate (the Java persistence standard and its dominant implementation) decide connect-versus-create by inspecting the runtime object graph — entity state plus cascade settings — which is intent inferred from runtime state instead of declared statically, and its known failure modes (accidental cascades, ambiguous attach semantics) are exactly what the static reference/inline split avoids.

One payoff of the tree restriction lands here: because the closure is a tree, the generated input types are strictly nested values — plainly serializable, no closure-local identifier scheme, no reference cycles to resolve at parse time. A boundary structure with no internal indirection is also a materially smaller attack surface; the security dimension of the boundary is noted, not designed here.

### Syntax sketch — `expose ... with` (candidate, not settled)

A worked sketch of the exposure-side spelling, recorded to make the design concrete — every detail here is open to revision. The model:

```
shape Order {
    customer: one Customer
    placedOn: Date
}

shape OrderLine {
    order: one Order          -- the one-to-many, declared from the many-of side
    product: one Product
    quantity: integer
}

shape Customization {
    line: one OrderLine
    note: text
}
```

`Order` declares nothing about lines — `orderLines` exists only as the inferred inverse of `OrderLine.order` (README §6). The simple exposure:

```
expose Order with orderLines
```

`with` names a **collection-typed property of the exposed shape** — here the inferred inverse. That one name determines everything the compiler needs: the part shape (`OrderLine`), the language-populated back-reference (`order`), and the addition to the generated commit function's signature.

**The references-always constraint is satisfied trivially, not defended.** The inferred inverse is derived, not stored — so under §22's "every stored field is a parameter," `orderLines` would never appear in the commit function at all without the `with`. The closure is not reinterpreting a `many` in act position; it is adding a parameter that had no other way to exist. A declared `many` on an exposed shape (the many-to-many edge set) keeps meaning references, untouched — which retires the first constraint in this document's list by construction rather than by rule.

**Nesting and multiple edges.** A flat list for multiple edges; braces when an edge carries its own closure:

```
expose Order with orderLines, shipments          -- two edges, one level

expose Order with {
    orderLines with { customizations }           -- lines arrive with their customizations
    shipments
}
```

Each nested `with` resolves against the *part* shape the same way (`customizations` is the inferred inverse of `Customization.line`). The tree restriction is visible in the grammar itself: `with` only ever names inverse edges pointing back at the enclosing level, so a sibling reference is unspellable on the declaration side — the restriction needs no separate declaration-side check.

**The ambiguity case reuses V19's existing resolution.** When a part declares two `one` fields of the container's shape, no inverse is inferred and V19 already forces declared views (README §6, the `Transfer` example):

```
shape Transfer {
    source: one Account
    target: one Account
    amount: Money
}

shape Account {
    outgoing: many Transfer = (Transfer where source == this)
    incoming: many Transfer = (Transfer where target == this)
}

expose Account with outgoing
```

`with outgoing` names the view, and the view's recognized form `(Transfer where source == this)` pins the language-populated back-reference to `source`. So the second gap above needs **no new naming construct**: `with` accepts an inferred inverse or a declared view of exactly the recognized-inverse form, and anything else — an arbitrary-predicate view — is a compile error ("a closure edge must be the inverse of a declared `one` field").

**The generated surface for this sketch** (Kotlin, the first transpilation target — README §5):

```kotlin
// generated — the projection, not the mirror
data class NewCustomization(
    val note: String,
)                                     // no `line` — language-populated

data class NewOrderLine(
    val product: Ref<Product>,        // typed handle: not in the closure, must exist
    val quantity: Int,
    val customizations: List<NewCustomization>,
)                                     // no `order`, no id

fun commitOrder(
    customer: Ref<Customer>,
    placedOn: LocalDate,
    orderLines: List<NewOrderLine>,   // possibly empty — the empty collection is the absence
): CommitResult
```

Every projection rule from the previous section is visible: back-references and `id` are unrepresentable, out-of-closure relationships are typed handles, in-closure edges are nested input values, and a minimum-lines policy (if any) surfaces as a refusal in the result, never in the type.

**Wrinkles the sketch surfaced, to carry into the design:**

- **A collection of inline-part values is a bag, not a set.** README §6's duplicate refusal is about references — two identical handles name the same instance, a caller bug. But two identical inline-part values mint two *distinct* instances (each gets its own `id` at the commit), so duplicates among inline parts are legal and meaningful — quantity 1 twice is not quantity 2 once. The set semantics of §6 apply to the post-commit inverse, trivially a set of distinct instances; the input side is honestly a bag. The eventual design must state this so the asymmetry is not "fixed" into a set refusal.
- **Composition with the inline exposure form.** `with` composes with both declaration forms (README §22): standalone as shown, or inline — `expose shape Order { ... } with orderLines`. The trailing clause after the brace reads oddly; if that grates, it is a point in favor of the standalone form as the idiomatic spelling when a closure exists. Cosmetic, deferrable.

### What remains open

#### 1. Syntax — where the closure declaration lives

The gap inventory mildly favors the exposure-side family: closure membership is a fact about the trust boundary, not about the shape — an `Order` created by a rule has no closure; only the exposed commit path does — and exposure-side keeps shape declarations pure. A candidate `expose ... with` spelling is sketched above and holds up against the identified gaps; it is not settled, and a marked propType on the exposed act remains the alternative family.

**The families compared (2026-08-21).** Against the sketch's model — `OrderLine.order: one Order` declared on the part side — the two spellings:

```
-- exposure-side (the sketch above)
expose Order with orderLines

-- property marker on the container (the alternative family; keyword illustrative)
shape Order {
    customer: one Customer
    placedOn: Date
    orderLines: inline many OrderLine
}
expose Order
```

- **Nothing in `with` is a duplicate.** `with orderLines` is the *only* written occurrence of that name — the inferred inverse has no declaration anywhere in the spec (README §6). In the ambiguity case, `with outgoing` references a view V19 already forced into existence for its own reasons: one declaration, one use, ordinary naming. It is the marker family that restates: `orderLines: inline many OrderLine` on the container re-declares the relationship the part already owns — the "both sides declared" configuration `checks.md` V19 refuses today — so the marker must carve an exemption from an existing refusal, where `with` adds a parameter that had no other way to exist (the "satisfied trivially, not defended" argument above).
- **The marker writes a trust-boundary fact into the shape body**, where it is false for every rule-created instance and cannot vary per exposure: two exposures of one shape carrying different closures (a bulk-import path arriving with parts, a simple path without) are expressible only exposure-side.
- **The tree is visible at one site exposure-side** (`expose Order with { orderLines with { customizations }, shipments }`); the marker family scatters it across three shape bodies, and the marker on the grandchild edge is meaningful only when its own shape rides some exposure's closure — a fact that shape's body cannot see.
- **`inline many` skates near the banned act-position reinterpretation of `many`** (the first constraint in this document); a wholly separate keyword avoids that but keeps every cost above.
- **What the marker family honestly does better:** the part's type is written at the declaration site, where `with orderLines` asks the reader to resolve the decapitalize-pluralize inference. The cost is bounded: exactly when inference fails, V19 forces the view to be declared anyway, so the name is written out precisely when it is non-obvious.

The clause also reads identically across both exposure styles — on a transient request act (`expose transient CreateOrder with lines`, the request a rule materializes) and on a directly exposed durable container — so the choice between those styles (a §22 matter) is orthogonal to this syntax decision. The comparison leaves the exposure-side family ahead on every structural axis; the marker family's remaining advantage is the one local readability point.

#### 2. Batch composition — proving the closure composes with the batch rungs, not duplicating one

The closure is the input-side spelling of README §20's "a set that must succeed or fail together is a business object" rung. Completeness gates remain the spelling for *incremental* arrival; the closure covers atomic arrival, where no partial-observation window exists to gate. It composes with the rungs rather than duplicating them.
