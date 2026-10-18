# Authorization

How a Velle spec says who may commit an act and who may see a record. This page is a guide: it gathers what the [Language Reference](Home#the-velle-language-reference), [`checks.md`](checks), and [`evaluation.md`](evaluation) settle in their own places and walks it in the order an author meets it. Where the two disagree, the Reference wins ([Reference §22](Reference-22-Open-unresolved), "The actor"; [Reference §8](Reference-08-Refinements), "Who may see a record"). The worked specs are `examples/pharmacy/pharmacy.velle`, written for the commit side, and `examples/vendors/vendors.velle`, written for the read side.

## The split

Authenticating a person is the engineer's job. Deciding what that person may do and see is the spec's. The binding code the engineer writes in front of the generated system resolves an authenticated identity to a record once and hands that record to every commit and every read. The spec never sees a token, a session, or a header; it sees a record of a shape it declared and reasons about it as it reasons about any other record. Rate limits, quotas, and which machine clients may call which endpoint stay in the engineer's wrapper ([Reference §22](Reference-22-Open-unresolved), "External input").

## The actor

The person who commits an act is the **actor**. The security field calls this the *principal*; the spec uses the word `actor` ([GLOSSARY](GLOSSARY), "actor", "principal").

**The actor is a shape the spec declares.** Nothing is built in. From `examples/pharmacy/pharmacy.velle`:

```
shape Staff {
    name: text
    role: text
}

Staff requires role == "pharmacist" or role == "technician" or role == "admin"
```

**Roles are refinements of that shape.** A role is a predicate over the actor's data, not a label an administrator assigns:

```
shape Pharmacist = Staff where role == "pharmacist"
shape Technician = Staff where role == "technician"
shape Administrator = Staff where role == "admin"

shape LicensedPharmacist = Pharmacist where exists (licenceRenewals where expiresOn >= today)
```

The last one is a role no table could hold: membership depends on the calendar and on the renewal records, and it lapses by itself.

**An act names its actor in the header with `by`.** The shape after `by` is a `shape`, a static union of shapes, or a refinement of one. It is never an act: an act is an input, and the person who commits one is a record.

```
transient act CountLot by Staff { ... }
act LicenceRenewal by Administrator { ... }
transient act FillPrescription by Staff as filledBy { ... }
```

An act without the clause has no actor. A processor's verdict or an insurer's adjudication arrives from a machine and says nothing about who sent it.

**The bound name is `actor` unless `as` chooses another.** `as filledBy` above binds the committer as `filledBy` everywhere the act is in scope. Nothing is reserved: `actor` is an ordinary identifier wherever no clause binds it, and a scope that declares a field under the bound name is told to name the actor ([`checks.md`](checks) [F3](checks#f3--position-rules)). The grammar is `byClause` in [`grammar.md`](grammar).

**The actor is a fact of the commit, like `id` and `timestamp`.** It is never in the generated input value, never claimed by the committer, never assigned by a rule. On a durable act it is stored under the bound name with nothing declared, and read through any reference to the act for as long as the record exists. The pharmacy's `LicenceRenewal` is a durable act: `renewal.actor` is the administrator who recorded it. The pharmacy's `FillPrescription` is transient, so the fill it produces copies the committer into a declared field, `filledBy: filledBy` in the `Fill from { ... }` block, and later acts read it from there.

**There is no system identity.** A spec that wants a job to commit a gated act models the job's actor as business data, a staff member with a system role, and the binding provides it exactly as it provides a person. A scheduled tick has no actor. The phrase "the user if there was one, or system" is written `if actor is some then actor.name else "system"` with nothing reserved for the second word.

**The first actor is seeded.** Where hiring is gated on an administrator, the first administrator cannot be hired. The engineer writes that record outside the trust boundary, as a migration or fixture row, with no act, no rule, and no prohibition. In memory the call is `sys.system.seed("Staff", mapOf("name" to "Founder", "role" to "admin"))`, as `examples/pharmacy/output/src/test/kotlin/velle/Scenario.kt` does before hiring everyone else through the gated act ([GLOSSARY](GLOSSARY), "seed").

## Role gates

A role gate is a prohibition on who may commit an act. It has two spellings and one meaning.

**In the header**, a refinement after `by`:

```
transient act HireStaff by Administrator { ... }
transient act VerifyFill by LicensedPharmacist { ... }
```

This desugars to `HireStaff requires actor to be Administrator`. A committer outside the refinement is refused at the door with their own record among the records the refusal read through ([Reference §21](Reference-21-never)).

**In the body**, a requirement over the actor. The header then names the shape every committer belongs to, and the body narrows:

```
transient act SetReorderPoint by Staff { ... }

SetReorderPoint requires actor to be Administrator or actor to be Pharmacist
```

Both spellings are legal together, and a two-role gate is written in the body or over a composed refinement. The desugared requirement is an ordinary `never`, so it carries context into its refusal like any other, and the generated specs do not synthesize a refusal case for a header gate, since its subject is the actor and not a field ([`testgen.md`](testgen)).

## Data-driven constraints

A gate need not be a role. Any predicate over the actor and the data graph is a gate. From `examples/vendors/vendors.velle`, a staff member may change the price of a product only for a vendor they are assigned to:

```
shape VendorAssignment {
    staff: one Staff
    vendor: one Vendor
}

transient act ChangePrice by Staff {
    product: one Product
    price: decimal
}

ChangePrice requires exists (VendorAssignment where staff == this.actor and vendor == this.product.vendor)
never (ChangePrice where price < product.cost)
```

Inside the nested `where`, bare names resolve against the collection's element, a `VendorAssignment`, so the act's own members are reached through `this`: `this.actor` for the committer and `this.product.vendor` for the product's vendor ([`checks.md`](checks) [F1](checks#f1--name-resolution-and-casing)).

The second line shows a prohibition reading a value the committer may not see. The cost is hidden from staff on the read side, below, and the refusal still fires: evaluation never consults visibility.

Separation of duty is the same construction. The pharmacy forbids a pharmacist from verifying their own fill of a controlled drug:

```
never (VerifyFill where actor == fill.filledBy and fill.lot.drug is Controlled)
```

The `actor` is the verifier, bound by this act's header. `fill.filledBy` is the filler, copied from an earlier act's actor into the fill record. The two meet as data.

## Rules and the actor

Where `actor` may be read is where the compiler can prove which act is in scope.

- **The act's own scope.** Its prohibitions, refinements, and derived properties, and any rule whose subject is the act or a refinement of it, at any cadence.
- **A rule over a product of the transaction** declares what it reads in its header, as `rule LogQuarantine when Quarantine by Staff? { ... }` does in `compiler/src/test/kotlin/velle/ActorTest.kt`. The clause is typed by reachability over the condition graph ([`checks.md`](checks) [V31](checks#v31--a-rules-actor-typed-by-reachability)). When every path to the trigger starts at an act declared `by`, the clause is `by Staff` and the actor is present. When some path starts at a tick, an `after commit` firing, or an act without `by`, the clause must say `by Staff?` and the body narrows with `actor is some`, as it narrows any optional. The grammar is `ruleByClause` in [`grammar.md`](grammar); `as` renames here too.
- **An `after commit` or scheduled rule cannot declare `by`.** It fires outside any act's transaction, where nobody is asking. What it wants is the stored actor of a durable act, read through the data, and that read is the same on every firing path. This is why a durable act stores its actor: the memory is data, and the tick law ([Reference §17](Reference-17-Schedule-triggers)) is untouched.

## Visibility

The read side is the same person seen from the other direction. The person a read is for is the **viewer**, the same kind of thing as an actor ([GLOSSARY](GLOSSARY), "viewer").

**A shape's body says who its records are returned to.** From `examples/vendors/vendors.velle`:

```
shape Product {
    vendor: one Vendor
    name: text
    price: decimal
    cost: decimal visible to Administrator
    margin: decimal = price - cost visible to Administrator
    visible to Staff where exists (VendorAssignment where staff == actor and vendor == this.vendor)
    visible to Administrator
}
```

- A **record-level clause** stands on its own line in the body (or after a comma). Several clauses disjoin: a product is shown to assigned staff and to every administrator.
- A **field-level clause** follows the field's type on the same line and narrows within the record's: of the staff who see a product, only administrators see its cost.
- The viewer is bound as `actor`, or under the `as` name, exactly as an act's `by` clause binds its committer. Here it is an alias in scope for the whole predicate, so it reaches into the nested `where` bare, while `this.vendor` still needs `this`.
- `visible to anyone` makes a record or field public: `shape Vendor { name: text  visible to anyone }` would attach the clause to the field; the record form needs its own line or a comma.
- A **derived property is calculated from every input whoever asks.** The margin is computed from a cost the viewer may not see. What a derivation reveals is the author's to close, which is why `margin` carries its own clause. An author who wants a value calculated only for some viewer scopes the shape to that viewer instead ([Reference §7](Reference-07-Derived-properties)).

**The spec-wide default** is one clause at the top of the file, `visible to Staff`. Every readable shape without a clause of its own takes it. A shape's own clauses replace the default rather than add to it: the pharmacy's `Claim` declares `visible to Pharmacist` and `visible to Administrator`, so a technician who sees everything else does not see claims.

**The declaration is required once a spec has any authorization.** Any `by` or any `visible to` anywhere, and every readable shape (every shape and durable act, never a transient act) must have a record-level clause or the spec-wide default ([`checks.md`](checks) [V32](checks#v32--every-readable-shape-declares-its-visibility)). Neither visible-by-default nor hidden-by-default is guessed: the first would show a forgotten field to everyone, the second would blank every existing spec at its first clause.

**Visibility is applied by the read surface only.** No rule, derivation, or prohibition ever depends on who is reading. The clauses filter what a viewer's query returns, resolve a fetch of an invisible record as absent, and project a model object with its hidden fields absent. A read with no viewer is the engineer's own view of their store and is never filtered ([GLOSSARY](GLOSSARY), "the engineer's view").

## The generated surface

For the engineer binding a transport, from `examples/vendors/output/src/main/kotlin/velle/generated/Vendors.kt` and the tests in `examples/vendors/output/src/test/kotlin/velle/VendorsFlowTest.kt`:

- **Commit takes the actor exactly when the act declares `by`.** `sys.commit(NewChangePrice(widget, price), sam)` where `sam` is a `Ref<Staff>`. A missing actor, or one of the wrong base shape, is a type-level refusal before anything begins. One outside the named refinement is the requirement's refusal.
- **Read takes a viewer.** `sys.read(sam)` returns a `StaffRead` carrying the same roots as the engineer's read, with every query filtered and every fetch resolving absent for a record the clauses do not admit. `sys.read(sam).product(widget)` is a `ProductForStaff` whose `cost` is null for Sam and set for an administrator.
- **One class per shape, fields declared once.** `Product` is one data class with `cost: BigDecimal?` and `margin: BigDecimal?` nullable because some viewer may not see them. `ProductForStaff` is the interface the viewer read returns, so a screen written for staff is typed against what staff may see. The engineer's own `sys.read(widget)` returns the class with every field.
- **A refusal is projected for the actor.** The rejection data handed back to Sam names the product without its cost, and the same refusal handed to an administrator carries it.
- **Seeding** is `sys.system.seed(shape, fields)`, with no door, no rule, and no prohibition.

The generated executable specs follow suit ([`testgen.md`](testgen)): an act declared `by` demands a given for its actor, `someStaff()` or `someAdministrator()` when the header names the role, and passes it beside the input.

## Compared with role-based access control

Velle has the shape of role-based access control (RBAC) without its machinery.

**Matches.** Users are instances of the actor shape. Roles are refinements of it. Permission-to-role assignment is per operation, through `by` and `requires actor to be`, with the visibility clause as read permission. Role hierarchy falls out of refinement nesting: `LicensedPharmacist` is a subset of `Pharmacist`, so a gate on the latter admits the former. Separation of duty is a `never`, including the object-level dynamic kind RBAC proper can only approximate.

**Departures.** Role membership is computed from data, not assigned in a table; "who holds this role" is a query and the answer can change with no administrative act. Permission-to-role assignment is static in the spec and changes by redeploying it; there is no runtime grant. There are no sessions and no role activation: every role the actor qualifies for is in effect on every commit. There is no first-class permission object; the permission is the act or the clause itself.

**Beyond.** A gate may read any relationship between the actor and the object, as `ChangePrice` reads the vendor assignment, which RBAC cannot express without a role per vendor. Visibility works at the row and the field, not at the operation. The whole is closer to attribute-based access control with roles as the common case than to RBAC proper.

## Not yet built

An actor built from an identity provider's claims rather than stored, `transient shape Staff`, is designed and listed in the repository's `working-docs/QUESTIONS.md`: an unstored shape the resolver builds per call, correlated to stored records by a business key, with nothing stored referencing it. Until it lands, the actor is a stored record.

## Where it is settled

- [Reference §22](Reference-22-Open-unresolved), "The actor": the construct and every decision behind it.
- [Reference §8](Reference-08-Refinements), "Who may see a record": the visibility clause.
- [`grammar.md`](grammar): `byClause`, `ruleByClause`, `visibleClause`, and the contextual words `by`, `visible`, `to`, `anyone`.
- [`checks.md`](checks): [F1](checks#f1--name-resolution-and-casing) (scope), [F2](checks#f2--type-checking) (dot through an optional actor), [F3](checks#f3--position-rules) (the bound name against a field), [V31](checks#v31--a-rules-actor-typed-by-reachability) (a rule's actor), [V32](checks#v32--every-readable-shape-declares-its-visibility) (every readable shape declares its visibility).
- [`evaluation.md`](evaluation): "The actor", "A read for a viewer", and seeding under "An exposed call".
- [`testgen.md`](testgen): the actor given.
- [GLOSSARY](GLOSSARY): actor, principal, role gate, viewer, visibility clause, the engineer's view, seed.
- Tests: `compiler/src/test/kotlin/velle/ActorTest.kt`, `compiler/src/test/kotlin/velle/VisibilityTest.kt`, `examples/pharmacy/output/src/test/kotlin/velle/PharmacyFlowTest.kt`, `examples/vendors/output/src/test/kotlin/velle/VendorsFlowTest.kt`.
