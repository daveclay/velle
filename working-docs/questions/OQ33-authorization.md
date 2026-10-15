# OQ33 — Authorization: the actor (the principal), roles, and what an actor may commit and see

**Status:** open; investigation written 2026-10-14 at Dave's request (the question was deferred post-v0 as `visible to` field visibility; it now carries the whole authorization question, since the read side and the act side turned out to be one construct seen from two places)
**In plain terms:** the engineer's code authenticates a person and, through one resolver registered when the system is built, hands velle an instance of the shape the spec declares for actors, stored when the system owns its users and built from an identity provider's claims when it does not. The spec then says, in its own predicate language, which actors may commit which acts and which records and fields an actor may see, with roles as refinements over the person's shape and data-driven conditions as ordinary predicates relating the actor to the record. A scheduled rule has no actor. The engineer's own reads use a system view the surface does not filter.
**See:** Reference §8 ("Every captured value traces to data"), §21 (prohibitions), §22 ("External input (`act`)", the query API) · `checks.md` V21, V29 · `evaluation.md` "An exposed call" · `examples/pharmacy/pharmacy.velle` (the role gates as they are written today) · OQ28 (bootstrapping the first actor) · OQ56 (generated cases for role refusals) · OQ57 (the read API's follow-ons)

---

## 0. What Dave asked for (2026-10-14)

- A principal, in the old Java Authentication and Authorization Service sense: the authenticated party on whose behalf a call is made.
- Roles associated to the authenticated user.
- Data-driven constraints: "the user can see products in the vendors they are assigned to".
- The principal is supplied by the binding API, presumably as another parameter to the generated commit functions.
- Scheduled rules use a system principal when there is no actor, configured by the engineer through the velle API.

This document maps each of those onto what velle already has, names the three settled lines the request moves, proposes one design, and ends with the decisions that are Dave's. The word used below is **actor**, not principal: the Reference already calls the party a prohibition binds "the actor" and the party who supplies an act's fields "the committer", and the language avoids computer-science vocabulary where a plain word exists.

## 1. What the request moves

Three settled lines push back on the request as framed. Each has an answer, and the answers shape the design.

**Reference §22 retires who-may-commit to the engineer's wrapper code.** "Who-may-commit conditions (caller identity, ambient policy, rate — OQ20's residue) live in the engineer's wrapper code before the commit call, outside the language." That ruling was right for identity on its own: whether a token is valid is transport. It breaks on the data-driven case. "A pharmacist may verify only fills of their own pharmacy" and "an actor sees products of the vendors they are assigned to" are predicates over the data graph, written in the vocabulary the spec owns; a wrapper that checks them re-implements the spec's relationships and refinements by hand, and the read API made it worse: a surface the actor composes (Reference §22, OQ51) cannot be gated query by query from outside without the wrapper understanding every query. So the residue splits. Authenticating the request and finding the user's record stay the engineer's job, outside velle, along with rate limits, quotas, and which machine clients may call which endpoints. Once the engineer hands a reference to that record into the call, what the user may commit and may see is the spec's job. "Transport" throughout this document is the Reference's word (§22) for the code that carries a request from the outside world to the generated commit function: a REST endpoint, a GraphQL resolver, a message-bus consumer.

**Reference §8 forbids an ambient current user.** "There is no ambient execution context — no 'current user', no request-scoped magic." An actor handed to every commit is a current user. The principle survives if the actor is a fact *of the act's commit*, carried with the act like its `id`, and readable only where the compiler can prove which act is in scope (§5.4): in a prohibition over the act, in a refinement of the act, in the act's own derived properties, in any rule whose subject is the act, and in an `on commit` rule of the act's transaction, optional there when some other path to the rule's trigger has no actor. It is not readable from a persistent shape's body, from a refinement over a persistent shape, from a rule a tick can fire, or at a tick. A durable act keeps the fact as a stored property, so a later rule or query reaches the person through the act, which is how §8 already treats `ArchiveRequest.requestedBy`; a durable record a transient act produces copies it. The principle moves by one step, not in kind: a transaction has an actor, and what §8 still excludes is a user who exists outside any act.

**V21 bans language-populated fields on an act.** "A system-maintained value is no part of an external submission." The actor is the one system-known fact that *is* part of the submission, and it is in the same category as `timestamp`: a fact about the commit the committer may not claim (Reference §5: "a fact about the commit can't be claimed by the client"). Today the pharmacy spells every role gate over a committer-supplied field, `VerifyFill requires pharmacist to be LicensedPharmacist` with `pharmacist: one Staff` in the act's body (`examples/pharmacy/pharmacy.velle`, the verification section), so any actor can claim to be any pharmacist. The gate is a business statement with no enforcement behind it. The actor must be readable on the act itself, because a refusal is checked at the door before any rule could materialize anything, so V21 gains exactly one exception: `actor`, which the language populates on a durable act as it populates `id`, declared by nobody and supplied by no committer.

## 2. Mapping the request onto the language

**The actor is an instance of a shape the spec declares.** Never a token, a subject string, or a bare list of role names. This is the choice that makes everything else free: an instance has fields and relationships, so every condition about the actor is an ordinary predicate. Systems keep their users in two places, and both fit this one rule; what differs is whether the shape is stored (Dave, 2026-10-14).

- **The system owns its users.** `shape Staff { ... }`, ordinary and stored, its instances created the way every shape's are: by a rule from an act (`transient act HireStaff` and a rule producing the `Staff`), the transient-materialization idiom Reference §4 already prescribes. Relationships point at it (`VendorAssignment.staff: one Staff`), the inferred inverses work (`staff.verifications`), and the engineer resolves an authenticated identity to the record with one read through velle's own query API, on a field the spec declares (`externalId: text`). The pharmacy's `act Staff` was a shortcut for "people arrive from outside"; for an actor's shape it reads wrong, since it says the actor is a mutation, and the actor's shape is never an `act` (Dave, 2026-10-14).
- **An identity provider owns them.** `transient shape Staff { ... }`. The keyword means what it already means for acts: an input to the state, not a member of it, existing only within the call it arrived with; here it arrives as the actor of a commit or read rather than as the commit itself. The engineer's resolver builds the instance from the provider's claims for each call (`externalId`, `email`, `roles: many text`), it is gone when the call is, and stored records correlate to it by a business key (`VendorAssignment where staffId == actor.externalId`). Roles are still refinements, over the arriving fields. The word is explicit rather than inferred from "no rule produces this shape" because a spec that later gained a producing rule would silently flip from unstored to stored, and the compiler refuses to guess (Reference §1).

In the spec the two look alike: a shape, and refinements over it for roles. What the unstored form gives up is stated in §5.2. Its obligations fall out of what `transient` already carries: V17's isolation says nothing durable may reference a transient instance, so `verifiedBy: one Staff` on a stored shape is a compile error whose diagnostic names the key field as the served spelling; the one adjustment is that a transient act's name may appear in no expression, while a transient shape must be readable through `actor` and refinable for roles, so the ban narrows to stored references.

**The engineer resolves the actor once, not at every call.** The clunkiness Dave remembers from the Java Authentication and Authorization Service was not the user object; it was fetching the user outside the application code before every call, which is what led people to abandon it until Spring encapsulated the fetch behind one interface the application implements once. Velle takes that shape: the engineer registers one resolver on the system when it is built, a function from the authenticated identity as transport has it (a subject string, a claims object) to the actor instance, and the generated surface calls it for each commit and each read. Velle still never authenticates and never looks anyone up; it invokes code the engineer wrote exactly once. The system view for the engineer's own reads is the same hook with a fixed answer.

**Roles are refinements over the actor's shape.** The pharmacy already writes them: `shape Pharmacist = Staff where role == "pharmacist"`, `shape LicensedPharmacist = Pharmacist where exists (licenceRenewals where expiresOn >= today)`. A static role is a refinement with a field predicate; a role that depends on a licence in force, a vendor assignment, or a date is a refinement with a relationship or clock predicate. External role-based access control maps in as data on the actor's record, maintained by acts (an administrator assigns a role) or by a mapping. No role construct is needed.

**A who-may-commit condition is a prohibition over the act that reads the actor.** `VerifyFill requires actor to be LicensedPharmacist` is a refusal (Reference §21), and `transient act VerifyFill by LicensedPharmacist` is the same refusal spelled in the header (§3.1): checked at the door when the predicate reads nothing a rule writes, at the settled transaction otherwise, and the rejection carries the act, the actor's record as a record the prohibition read through, and the evaluation. A data-driven write condition is the same construct with a relationship in it: `FillPrescription requires exists (VendorAssignment where staff == actor and vendor == lot.vendor)`. Nothing new is enforced; the door already runs.

**A who-may-see condition is a visibility declaration the read surface applies.** This is the half OQ33 always held. Under a surface the actor composes, a gate written into one named refinement is bypassed by composing against the base shape, so visibility is declared on the shape and on the field, against an actor that is a parameter of the whole read, and the surface applies it to every composed read, every reference resolution, and every projected field. It is applied to what the surface *returns*, never to how the spec *evaluates*: a rule, a derivation, and a prohibition see the whole state, as they do today.

**A scheduled rule has no actor, and needs none.** V29 already rules that a firing with no actor cannot be refused; under the readability rule above a tick rule cannot name `actor`, so there is nothing for a system principal to be consulted by inside the spec. Where the engineer's own code acts on the system's behalf, the system identity is a binding-API concept: a system *view* for reads, which the surface does not filter, and for commits a real record the engineer chooses to model (a `Staff` with role `"system"`), since a refusal is a business prohibition and nobody bypasses it.

## 3. The proposed design

### 3.1 Spelling

Three additions, none of them a reserved word.

- **The actor's shape, declared on each act that has one.** `act LicenceRenewal by Staff { ... }` and `transient act VerifyFill by Staff { ... }`, where `Staff` is a `shape` or a `transient shape` (§2), never an `act`. The target may be a static union (`by Person` with `shape Person = Staff or Customer`), declared once as a shape and named by each act that admits either, which is already a legal relationship target (Reference §6), and narrowing by `actor is Staff` already works (Reference §10). The target may also be a refinement: `transient act AssignVendor by Administrator { ... }` means `by Staff` plus `AssignVendor requires actor to be Administrator`, one desugaring, the sugar `requires ... to be` already is for a `never` (Dave, 2026-10-15). Both spellings stay legal and refuse with the same rejection data; the header form is the served spelling for a bare role, and the body `requires` is written when the condition is not a role, when two roles are wanted inline, or when the author wants the gate as a line of its own (a composed refinement declared once, `shape Dispenser = Pharmacist or Administrator`, Reference §9, serves the header form for two roles). This does not collide with Reference §6's rule that a field is never typed by a refinement, because `by` is not a field type: it is a condition on the commit, checked at the one moment the actor is in scope, and what a durable act stores keeps the base type `one Staff`, since "an administrator committed this" is a fact about then that no later commit can falsify, like a timestamp. Inside the act's transaction, where the gate has held, `actor` is typed as the refinement; through the stored reference it is the base. An act without the clause has no actor: a processor verdict or an insurer's adjudication arrives from a machine and says nothing. A spec with no `by` anywhere has no actor anywhere, and nothing below applies to it: every spec written so far keeps compiling and keeps its generated surface.
- **The name: `actor` by default, `as` to choose another.** `by LicensedPharmacist` puts the committer in scope as `actor`; `by LicensedPharmacist as pharmacist` puts it in scope as `pharmacist` (Dave, 2026-10-15). No word is reserved: `actor` is the default name a `by` clause binds when no `as` follows it, an ordinary identifier everywhere else, and a scope where the default would apply and a field named `actor` is also declared is a compile error that prescribes `as`. The same clause and the same default serve the two other places that read a principal: a rule on a product reached from several acts declares what it reads, `rule LogQuarantine when Quarantine by Staff? { ... }` with `actor` in scope, or `by Staff? as who` (§5.4), and a visibility clause binds `actor` in its predicate or renames it, `visible to Staff as viewer where ...` (§5.7). The name is typed by its clause and names the committer of the act in scope, and §5.4 says where that is provable: a `never` or `requires` whose subject is an act or a refinement of an act, a refinement over an act, a derived property of an act, any rule whose subject is the act or a refinement of it, and an `on commit` rule of the act's transaction, typed optional there when some other path to the trigger has no actor (§5.4). Anywhere else, a compile error naming the path: "`actor` is a fact of an act's commit; `UnsyncedSignup` is reached from the `Hourly` backstop, which has none; read the person from the data (`this.signup.actor`)." Through a reference to a durable act the stored property carries the act's name for it: `renewal.actor`, or `renewal.admin` for `act LicenceRenewal by Administrator as admin`.
- **A durable act stores its actor.** Undeclared, in the family of `id`: a durable act declared `by` keeps a reference to its actor whether or not anything in the spec reads it, readable as `actor` inside the act's scope and as `renewal.actor` through any reference to the act, never in the generated input value, never assignable, never committer-suppliable. This is the one language-populated property V21 admits on an act. A business name for it is an ordinary derived property, `pharmacist: one Staff = actor`, computed on read like any other. When the actor's shape is `transient` (§2) nothing can be stored, so a durable act keeps what the author copies at arrival, `staffId: text = actor.externalId`: a derived field on a durable act that reads `actor` is fixed at the creation commit and stored, since the value it reads will not exist later.
- **Visibility.** A body clause in the family of `frozen` and `undeletable`, record-level and field-level, naming the viewer's shape as an act's `by` clause names its actor, with an optional predicate over `this` and `actor`:

  ```
  shape Product {
      vendor: one Vendor
      name: text
      cost: Money visible to Administrator
      visible to Staff where exists (VendorAssignment where staff == actor and vendor == this.vendor)
  }
  ```

  `visible to <Shape>` names a shape or a refinement, and `actor` in its predicate is typed as that shape, so `visible to Administrator` with no predicate admits every actor in that refinement. A record or field with several clauses is visible to a viewer when some clause names a shape the viewer belongs to and that clause's predicate holds; a viewer whose shape no clause names sees nothing of a record that has any clause. A field with no clause is visible wherever its record is; a shape with no clause is visible to every viewer (the default is §5's question). A derived property is a field like any other and carries its own clause; the spec does not reason about what a derivation reveals of its inputs, and an advisory can later point at a visible derivation over an invisible field if that proves to matter.

### 3.2 The pharmacy, rewritten

What the verification counter looks like once the actor is real. Today's version is in `examples/pharmacy/pharmacy.velle`, the "Verification: the pharmacist's check" section, where `pharmacist` is a field the committer supplies. The velle text barely changes, and that is the point: the act's header says who commits it, the supplied field is deleted, and the three prohibitions read `actor` instead, which turns a claim any actor could make into a fact the actor cannot. Today's `act Staff` becomes a stored shape with a hiring act, the spelling §2 gives for a system that owns its users; with an identity provider the first declaration would read `transient shape Staff` and `HireStaff` would not exist.

```
shape Staff {
    name: text
    role: text
    externalId: text            -- what the engineer's resolver matches the authenticated identity to
}

Staff requires role == "pharmacist" or role == "technician" or role == "admin" or role == "system"

transient act HireStaff by Administrator {
    name: text
    role: text
    externalId: text
}

rule RecordHire when HireStaff {
    Staff from { name: name, role: role, externalId: externalId }
}

shape Pharmacist = Staff where role == "pharmacist"
shape Administrator = Staff where role == "admin"
shape LicensedPharmacist = Pharmacist where exists (licenceRenewals where expiresOn >= today)

-- An administrator records a renewal for a pharmacist. Two people, two roles:
-- the pharmacist is a field the actor names; the actor is the administrator.
act LicenceRenewal by Administrator as admin {
    pharmacist: one Staff
    licenceNumber: text
    expiresOn: Date
}

LicenceRenewal requires pharmacist to be Pharmacist

transient act VerifyFill by LicensedPharmacist {
    fill: one Fill
    outcome: text
    note: text
}

never (VerifyFill where actor == fill.filledBy and fill.lot.drug is Controlled)
VerifyFill requires outcome == "approved" or outcome == "rejected"

shape Verification {
    fill: one Fill
    pharmacist: one Staff
    outcome: text
    note: text
    verifiedAt: timestamp on create
}

rule RecordVerification when VerifyFill {
    Verification from { fill: fill, pharmacist: actor, outcome: outcome, note: note }
}
```

The licence gate moved into `VerifyFill`'s header, and the two prohibitions that remain in the body read `actor` where today's file reads `pharmacist`; the rule copies the actor onto the durable `Verification`, since the transient act is gone when the rule's commit closes. The generated `NewVerifyFill` loses its `pharmacist` field and the generated commit function gains a `actor: Ref<Staff>` parameter, the base shape. `LicenceRenewal` is durable and declared `by Administrator as admin`, so it stores its actor, as `one Staff`, under the name the header chose and with no field declared: a later rule over `LicenceRenewal` at any cadence reads `admin`, and a query reads `renewal.admin`, to find who recorded the renewal. `VerifyFill` takes the default, and its body reads `actor`; an author who wants today's word writes `by LicensedPharmacist as pharmacist` and today's three body lines come back unchanged. Every one of the pharmacy's eighteen role gates converts the same way, and the acts no person commits, `Adjudication` from the insurer and `PrescriberOrder` from the prescriber's system, carry no clause and are untouched.

### 3.3 The binding API

- **The resolver.** Registered once when the system is built: `PharmacySystem(resolveActor = { identity -> ... })`, where the engineer's function turns the authenticated identity transport hands it into the actor instance, by a read through the query API when the shape is stored or by building the instance from claims when it is not. Transport then passes the identity, not the user, and the surface resolves it. An engineer who prefers to resolve by hand may pass the instance directly; the resolver is the default path, not the only one.
- **Commit.** `commit(input: NewVerifyFill, actor: Ref<Staff>): Commit<NewVerifyFill>` for an act declared `by`, the parameter and a durable act's model-object property taking the spec's name for it (`actor`, or the `as` name); `commit(input)` as today for an act without the clause, so an inbound processor verdict or a legacy import needs no invented person. For a stored actor shape, a reference to a record that does not exist, or whose shape is not the base shape the clause names, is a type-level refusal at the boundary like any bad reference; a record of the base shape outside the refinement the clause names is a prohibition refusal, the desugared requirement, carrying the rejection data and the evaluation (§2). The binding sees both as refusals, from two different places. For an unstored actor shape, the instance is typed by the generated class, the type-level check is the class, and the refinement check runs as for a stored one. A durable act declared `by` stores it, and its model object carries `actor: Ref<Staff>` (§3.1). The door evaluates the refusal over pre-state plus the act's closure, and the actor's record is already in pre-state, so the door's moment (`evaluation.md`, "An exposed call") is untouched.
- **Read.** The viewer becomes a parameter of the whole read: `sys.read(actor).products.where { ... }.orderBy { ... }.list()`, `sys.read(actor)(ref)`, `sys.read(actor).product(key)`, where `actor` is a reference to an instance of any shape a `visible to` clause names, one generated overload per such shape. The surface conjoins the record-level visibility predicate to every query rooted at the shape or at one of its refinements (through the existing candidate pre-filter, which may over-return, with the authoritative check in memory, so the superset contract in `compiler/src/main/kotlin/velle/Query.kt` already fits), resolves a reference to an invisible record as absent, and projects a model object with invisible fields absent. The engineer's own code reads through the system view, `sys.read.products...` as today, which no visibility clause filters; it is the engineer reading their own store, a power they already have.
- **Tick.** `tickDaily()` is unchanged. A tick has no actor.
- **The system identity.** There is none (§5.6). For reads, the engineer's own code uses the unfiltered view above. For a commit of an act declared `by` by a job rather than a person, the spec models the job's actor as business data (the `role == "system"` staff member above) and the binding provides it through the same resolver that provides an authenticated user.

### 3.4 What else the actor touches

- **Serialization domains** (`evaluation.md` U3, `compiler/src/main/kotlin/velle/Domains.kt`). A prohibition reading `actor.licenceRenewals` reads a record outside the act's own footprint, so `actor` becomes a path root the domain derivation keys on: verifications by one pharmacist queue together, and every durable act that stores its actor adds the same root. Correct, and the contention map should say so in business words.
- **The validator.** One new check for where `actor` may be read (§5.4): the act in scope by position, and for an `on commit` rule on a product of the transaction, a reachability typing over V16's condition graph: `actor` is optional when some path to the trigger (a tick, an `after commit` firing, an act without `by`) has none. One new check for visibility clauses: the predicate reads `this`, `actor`, and the data graph, nothing else. V21 gains its exception. F2 types `actor` from the act's `by` clause, and a visibility predicate's `actor` from its clause's shape.
- **Spec generation** (`testgen.md`, OQ56). A refusal that reads `actor` gets a generated case per role: the actor who passes, the actor who does not. OQ56 already holds the item "let a generated boundary case name the role or relationship refusals on its act"; this gives it the data it was missing.
- **Diagrams.** The class diagram marks the actor shape; the rule graph can label an act's edge with the role that may commit it.
- **Bootstrapping.** `transient act HireStaff by Administrator` cannot admit the first administrator. This is OQ28's shape (a new rule against pre-existing state, here an empty state) and should be answered there; the likely answers are that the act creating people does not read `actor`, or that the first record arrives through a mapping, which is outside the trust boundary by construction.
- **Rejections.** A refusal that reads `actor` renders today's sentence ("VerifyFill refused: the requirement `pharmacist to be LicensedPharmacist` is unmet") with the actor's record among the records read through. No change to `compiler/src/main/kotlin/velle/Rejection.kt`'s shape.

## 4. What stays outside the language

- Authentication: tokens, sessions, identity providers, and the lookup from an external identity to the actor's record.
- Rate limits, quotas, which machine clients may call which endpoints, request size and timeouts: Reference §22's residue, minus what the user may commit and see.
- Row-level security in the store: the surface enforces visibility; whether the engineer also mirrors it into database policies is their call.
- Who may *tick*: schedules are the harness's calls, as today.

## 5. Decisions for Dave

Each section leads with the decision this document recommends and follows with what it costs.

### 5.1 Authenticating the user and handing them to velle is the engineer's job; what that user may do and see is the spec's

This is velle's separation of layers (Reference §1, §2) applied to authorization. Where a user is authenticated, and how, is a low-level concern: a token, a session, an identity provider. What concerns the product owner is which users may see which data and mutate which data, and that is a statement about the system's shapes and relationships, so it belongs in the spec. The engineer's code, outside velle, authenticates the request, finds the user's record, and passes a reference to it into the commit or read call. Velle never sees a token and never looks a user up. From the reference on, the spec decides: which acts that user may commit and which records and fields they may see. This reverses the half of OQ20's residue that put actor identity in wrapper code and keeps the half about ambient policy and rate. The cost: Reference §22's "External input" paragraph and the OQ20 row in the settled table are rewritten, and every role gate in the pharmacy is enforcement the wrapper used to be trusted for, so the wrapper that authenticates must now also resolve the actor to a record before every gated commit.

### 5.2 The actor is an instance of a shape the spec declares, stored or not, and roles are refinements over it; the engineer registers one resolver

Choose the shape instance over an opaque principal carrying role names, let the shape be stored (the system owns its users) or unstored (an identity provider owns them), and let the engineer register the identity-to-actor function once rather than fetch the user before every call. The gain is the whole data-driven case for free, no second vocabulary for roles, and no repeated fetching at call sites.

The costs, in plain terms:

- **With an identity provider, the spec cannot point at a user.** A stored record can be the target of `one Staff`, so "the fills this pharmacist verified" is a traversal, `staff.verifications`, and the compiler infers it. An unstored actor has no record to point at, so every record that must remember who acted stores a key the provider issued (`verifiedBy: text` holding `actor.externalId`), and "the fills this pharmacist verified" is a query matching that key. It works, but it is a join the author writes rather than a relationship the compiler knows about, and the compiler cannot check that the key names anyone.
- **With an identity provider, roles arrive as text.** The actor's roles are whatever the provider sent, a list of names, and a role refinement tests for a name in that list. Velle has no "this value is in that collection" test today, so `Administrator = Actor where roles contains "admin"` needs the text operator `contains` extended to collections, or a new word. A small addition, but a language change this question forces.
- **Every rule that reads the actor runs through engineer code.** The resolver is the engineer's function, called by the generated surface on every commit and read. If it is slow, every call is slow; if it is wrong, every gate is wrong. That is already true of authentication today and the resolver does not change it, but the dependency becomes explicit and sits in velle's call path.
- **A stored user shape must be kept in step with the system that authenticates.** When the system owns its users this is nothing new. When an identity provider owns them and the author still wants references, the author mirrors the provider's users into a stored shape (an act on first sign-in, or a mapping), and a role that changes in the provider is stale in the mirror until the mirror runs. The unstored form avoids this by reading the provider's claims fresh on every call, which is the trade between the two forms.

### 5.3 The spelling: `act X by Staff [as name]` on each act that has an actor, naming a `shape`, a `transient shape`, or a refinement of either; `actor` the default name, nothing reserved

Choose these, with the actor's shape a `shape`, stored or `transient`, never an `act` (settled with Dave 2026-10-14, §2), and the name chosen by the author with `as` or defaulting to `actor` (Dave, 2026-10-15). The per-act clause was Dave's suggestion on 2026-10-14, kept aside while a single top-level `actor: one Staff` line stood here, and adopted on 2026-10-15 when §5.5 showed the top-level form has to infer each act's boundary from how the spec happens to read `actor`. With the clause, who commits an act is in its header, where a product owner reads the trust boundary, and the read side names its viewer the same way (`visible to Staff where ...`, §3.1). The clause may name a refinement (`by Administrator`, Dave 2026-10-15), which is the role gate in the header, sugar for the body `requires actor to be Administrator` that remains legal beside it (§3.1); a reader has to know the word after `by` refuses, and the rejection data shows the desugared requirement. The word was `caller` until 2026-10-15, when Dave raised the conflict any reserved word has with some spec's field (`user` with every `User` shape, `principal` in lending, `caller` in telephony) and chose the default-plus-rename form: no word is reserved, `actor` is what a `by` clause binds when no `as` follows, and a spec that needs `actor` as a field, or a better business word, writes `as`. `principal` stays the concept's name in prose, the JAAS word Dave reached for first, whose problems were elsewhere than its name. The costs: a scope where the default applies and a field named `actor` is declared is a compile error until the author writes `as`, and a reader meets two spellings for one thing across specs (`by` and `as` are already keywords, `by` contextual in `latest(... by ...)`, so the grammar gains no word); `transient` gains a second position, before `shape`, with the same meaning it has before `act`, and V17 is restated so that a transient shape stays unreferenceable from stored records but readable through `actor`; one word repeats on every act a person commits, most of the pharmacy's thirty-nine; and a durable act declared `by` carries a stored property it never declared (§5.4), which the Reference has to explain beside `id`. The alternative spellings considered: the top-level declaration, rejected for the reason §5.5 gives; a `committer` keyword in type position mirroring `timestamp` (`pharmacist: committer`), rejected because the read side needs a predicate-position name anyway and one name should serve both. A union is no objection to the clause: `shape Person = Staff or Customer` is declared once and named by each act that admits either.

### 5.4 The actor is the committer of the act in scope, and a durable act stores its actor

Choose this: a durable act carries the actor of its commit as a stored, language-populated property, undeclared, in the family of `id`; the name a `by` clause binds (`actor`, or its `as` name, §5.3) names the committer of the act in scope wherever the compiler can prove there is one, and is a compile error everywhere else. This replaced the first draft's narrow rule ("readable only where the act is") and its declared alias (`pharmacist: one Staff = actor`); §1, §3.1, §3.2, §3.3, §3.4, §5.3, §5.5, and §6 are written onto it.

How it was reached (Dave, 2026-10-15). The first draft of this section forbade `actor` in `after commit` rules and said a rule outside the act's transaction must read a stored alias the author had declared. Dave's objection: the classic failure of authorization in ordinary code is that the current user must be referenced at arbitrary frames of the stack, so it gets threaded through every frame or smuggled in a thread-local; velle's founding move was to free the language from the stack; and asking the author to copy the actor into a field so the next rule can find it is that threading in a new disguise. He expected the compiler to determine whether an `after commit` rule reading `actor` was reachable from a path with no actor, and to error there. That is the right check, and running it changes the answer. The first draft's reason for the ban was that every `after commit` rule has two firing paths, promptly after the act and again at the backstop tick (Reference §11, §18; check V4), and the tick path re-finds the subject from current state with no actor. But when the subject is a durable act, the act is a record of the commit, and who committed it is a fact of that commit in exactly the way its `id` is. Store it with the act, and both paths read the same fact off the same record: the hidden memory the tick law forbids never arises, because the memory is data. The alias was the disguise because it made remembering the author's job; the implicit property makes it the act's.

What `actor` means, by where it appears:

- In an act's prohibitions, refinements, and derived properties, and in any rule whose subject is the act or a refinement of it, at any cadence: the subject act's committer. `rule ApplyDeposit when UnappliedDeposit after commit, Hourly` (Reference §18) with `Deposit` a durable act reads the deposit's stored actor on the prompt firing and on the hourly backstop alike. A scheduled rule over a durable act reads it the same way; V29 is untouched, since a refusal reading `actor` is evaluated at the act's own commit and a tick firing still has no actor of its own.
- In an `on commit` rule whose subject is a product of the transaction rather than the act, such as a rule on `Verification` created by `RecordVerification` (`examples/pharmacy/pharmacy.velle`): the transaction's root act's committer, typed by reachability. Such a rule declares what it reads in its header, `rule LogQuarantine when Quarantine by Staff? { ... }`, binding `actor` or an `as` name like an act does (§5.3), and the declared type is checked against reachability. This is Dave's check, over the graph V16 already builds (rule effects to the conditions they can newly satisfy, plus each act to the rules its commit can fire), and it types rather than refuses (Dave, 2026-10-15): when every path to the trigger starts at an act declared `by`, the rule may declare `by Staff`; when some path starts at a tick, an `after commit` firing, or an act without `by` (§5.3), the rule must declare `by Staff?`, the actor absent on that path, and narrow it as it narrows any optional; declaring `by Staff` there is the error, naming the actor-less path. A rule on a product reached from acts with different actor shapes declares the union (§5.3). A rule whose subject is a durable act needs no clause, since the act's own name is in scope. The pharmacy has the case: `Quarantine` is produced by `QuarantineRecalled` inside a `Recall` act's transaction and by `QuarantineExpired` at the nightly tick, so a rule on `Quarantine` declares `by Staff?` and writes a product owner's "the user if there was one, or system" as `actor: if actor is some then actor.name else "system"` (grammar.md, `condExpr`), with nothing reserved for the system. The better spelling, where the owner will accept it, keeps the reference and lets the absence mean the system: `quarantinedBy: one Staff?` on `Quarantine`, written as `actor` by `QuarantineRecalled` and as `none` by `QuarantineExpired`, each rule knowing its own path, with `shape SystemQuarantine = Quarantine where quarantinedBy is none` for the owner's word; the optional typing is needed only in one rule downstream of both. The typing is sound inside a transaction because whether `actor` is present is fixed by the commit that fired the rule, so the rule's output is a function of the commit.
- Anywhere else, a compile error. An `after commit` or scheduled rule over a plain shape is reachable from its backstop tick, which carries no actor, and the diagnostic says so and points at the data: `this.request.actor`, or a field the materializing rule copied. The optional typing does not extend here: the prompt firing and the backstop firing follow the same commit, and handing them different values would make the data depend on whether a crash happened, not on the commit.

What the stack concern becomes under this rule. Inside a transaction, `actor` is the request's actor at every frame, proven rather than threaded. Beyond the transaction the request is over, and the transition law (Reference §11) says only data survives it; the actor survives as a property of the act, so a rule three records away reads it by navigating the graph, not by having it passed along, and where navigation is too far the author copies it when materializing, the same choice velle makes for every other fact a later rule wants near at hand. One residue: a rule whose subject is a durable act can also fire at a later commit by a different person (a deletion that re-enters `UnappliedDeposit`); there `actor` is still the deposit's committer, and the later actor is read off the later act, which the rule cannot name unless the data links them.

The costs. Reference §8 moves further than the first draft claimed: a transaction has an actor and `actor` names it; what §8 still excludes is a user who exists outside any act, since a tick has none and the read surface is handed its viewer explicitly (§3.3). A durable act stores a reference the author never declared, the second language-populated property beside `id`, so V21's exception becomes "the language populates `actor`" rather than "the author may declare one alias", and the storage domains (§3.4) gain a path root on every durable act that stores one. With an unstored actor (`transient shape Staff`, §2) a durable act cannot store the reference (V17 as restated in §5.3), so `actor` is readable only within the transaction, an `after commit` rule reading it over such an act is the error above, and the served fix is a key copied at arrival, `staffId: text = actor.externalId`, which widens the old alias rule into "a derived field on a durable act that reads `actor` is fixed at arrival and stored"; this is the stored-versus-identity-provider trade §5.2 names. The reachability typing makes a mixed-path rule read an optional even when the author only cares about the actor path, and the narrowing is the price. For a transient act nothing changes, since V17 already forbids `after commit` on it and the actor lives exactly as long as the act does.

### 5.5 The commit function takes an actor exactly when the act declares `by`

The question: once a spec has actors, which generated commit functions take one, and what does the engineer pass for an act no person commits. Not every act is a person acting. In the pharmacy, `VerifyFill` is a pharmacist at the counter, but `Adjudication` is an insurer's system answering a claim, and in `examples/payments/payments.velle` the `ProcessorVerdict` act is a webhook from the card processor. Whatever this section decides fixes what the webhook handler passes and whether the store keeps a committer on records no person committed.

Choose the declaration: an act's commit takes an actor if and only if the act is declared `by` (§5.3), a durable act declared `by` stores the actor whether or not anything reads it, and an act without the clause takes none and stores none. `VerifyFill` takes a `Ref<Staff>`; `ProcessorVerdict` keeps today's `commit(input)` and the webhook passes no person. The costs: one word per act a person commits; adding `by` to an act later changes a generated signature the engineer calls, which is right, since the boundary changed, and the compiler flags every call site; and a durable act declared `by` pays for a stored reference even where only an audit query will ever read it, which is the point of declaring it.

The alternatives, each rejected (2026-10-15). **Inferred from reads**, the first draft here: the commit takes an actor when the spec reads that act's actor anywhere, and stores one when it takes one. It spares the author the word, and it decides a boundary and a storage fact from an absence: deleting the last read of an act's actor silently drops the parameter and, for a durable act, the stored column, and "keep who committed this" for audit alone has no spelling because nothing reads it. That is the guessing §5.3 refused for `transient shape`. **Uniform**: every act's commit takes an actor once the spec has any. Simplest to call and to document, but the webhook handler must hand over a person for the processor, the modeled system record of §5.6, and every durable act stores it, so a verdict is recorded as committed by a staff member that is a fiction for a machine. **Optional**: an actor that may be absent on every act, `is some` narrowing where it is read, the absence passed for a machine. Honest for machines, at the price of a narrowing conjunct in every one of the pharmacy's eighteen gates before any says a word about the person. The optional belongs where the absence is real, a rule reached both from an act with an actor and from a path without one, and §5.4 types `actor` optional exactly there.

### 5.6 There is no system identity; a spec that wants one models an actor and the binding provides it like any other

Settled with Dave, 2026-10-15: the language has no system identity and reserves no `system` value. A spec whose product owner wants to record or gate on "the system" defines an actor for it in its own terms, an instance of the actor shape the acts name (the pharmacy's staff member with `role == "system"`, §3.2), and the engineer's binding code provides that instance to a commit exactly as it provides an authenticated user, through the resolver (§3.3). Where nobody asked, a tick or an act without `by`, there is no actor and `actor` is absent (§5.4), which is what a rule reads when it wants "the user if there was one, or system". For reads, the engineer's own code uses the unfiltered view of the store (§3.3), which is not an identity but the engineer reading what they own. The cost: a modeled system actor is data someone can edit, and a spec that gates an act on a human role and also wants a job to commit it must either give the job's actor that role or widen the gate, in the spec, visibly. The alternative rejected: a reserved `system` value typed as every actor shape and passing every gate; it would be the first ambient value the language has, an actor no record describes, and the thing a tick would carry, which V29 says a tick does not need.

### 5.7 Visibility: `visible to` clauses, visible by default, applied by the read surface only

**The goal.** The third line of §0: "the user can see products in the vendors they are assigned to." A who-may-see condition is a predicate over the data graph, in the spec's own vocabulary, that the read surface applies to everything it returns to a viewer, so that the engineer's transport code never re-implements a relationship to decide what a screen may show (§1). It is the read-side twin of a who-may-commit prohibition: the same `actor`, the same refinements, written in a shape's body instead of over an act.

**The example.** A vendor-and-products spec, the one §6 lists as the first spec whose tests read through two viewers and the engineer's view:

```
shape Vendor {
    name: text
}

shape Staff {
    name: text
    role: text
}

shape Administrator = Staff where role == "admin"

shape VendorAssignment {
    staff: one Staff
    vendor: one Vendor
}

-- Two people: the actor is the administrator doing the assigning; `staff` is a
-- field the administrator supplies, the person being assigned. Nothing links
-- the two, and the committer never claims the actor (Reference §5).
transient act AssignVendor by Administrator {
    staff: one Staff
    vendor: one Vendor
}

rule RecordAssignment when AssignVendor {
    VendorAssignment from { staff: staff, vendor: vendor }
}

shape Product {
    vendor: one Vendor
    name: text
    price: Money
    cost: Money visible to Administrator
    margin: Money = price - cost
    visible to Staff where exists (VendorAssignment where staff == actor and vendor == this.vendor)
}
```

What the surface does with it (§3.3). A staff member assigned to one vendor reads `sys.read(staff).products.list()` and gets that vendor's products and no others; `sys.read(staff)(ref)` for a product of another vendor resolves absent, exactly as a reference to a deleted record does; the model object the staff member receives has no `cost`, and an administrator's has it. `Vendor` carries no clause, so every viewer sees every vendor. The engineer's own code reads `sys.read.products.list()` and sees everything, because that is the engineer reading their own store. No rule and no derivation changes: `margin` is computed from `cost` for everyone, and the spec evaluates identically whoever is asking.

**Three choices, each with its cost.**

1. **The form.** `visible to <Shape> [as <name>] [where <predicate>]` as a body clause on a shape and on a field, in the family of `frozen` and `undeletable`, naming the viewer's shape as `by` names an act's actor, binding `actor` or the `as` name in its predicate, typed by the clause, with the predicate reading `this`, that name, and the data graph. Several clauses on one record or field disjoin; a record that has any clause is hidden from a viewer whose shape no clause names. This follows from §5.3 and §5.5 and is a confirmation. The cost is a new body clause in the grammar, and that the viewer of a read is bound under the same default name as the committer of an act, so an author who finds `actor` wrong for a reader writes `as viewer`.

2. **The default.** A shape or field with no clause is visible to every viewer. The cost is that forgetting a clause fails open, the opposite of every other check's stance: an author who adds `cost` to `Product` and forgets its clause has shown it to every assigned staff member. The alternative, hidden until declared, fails closed and makes every existing spec return nothing through a viewer until it is fully annotated, which makes the read side unusable until then. A middle position is possible and would need stating as a rule of its own: visible by default until a spec has any `visible to` clause, then hidden by default for everything undeclared.

3. **Where it is applied.** Only to what the read surface returns, never inside spec evaluation. The cost is the leak through a derivation: `margin` above is visible to every assigned staff member and is `price - cost`, so a viewer who may not see `cost` can compute it. The spec does not reason about what a derivation reveals of its inputs; the author gives `margin` its own clause, and a later advisory can point at a visible derivation over an invisible field if that proves to matter. The alternative, applying visibility inside evaluation so that a derivation reads an absent `cost` for a non-administrator, would make a rule's result depend on who is reading, which Reference §8 rules out and which would make `margin` mean two things.

## 6. Work, once the decisions land

- Reference: §5 (`actor` beside `id` as a language-populated property; the arrival-fixed derived field), §8 (the actor as the act's fact), §11 and §18 (an `after commit` rule over a durable act reading `actor`), §21 (a prohibition reading `actor`), §22 (the scope statement, "External input", the query API's viewer); `grammar.md` (the `by ... [as ...]` clause on an act and on a rule, the default name, the visibility clauses); `checks.md` (V21's exception, the reachability check, the visibility-predicate check); `evaluation.md` ("An exposed call" with the actor in the input, "A read is a read-only snapshot" with the viewer); `testgen.md`; `diagrams.md`; the glossary (actor and principal, system view).
- The compiler: lexer and parser, the model's actor shape, the validator's reachability and form checks, the stored actor on durable acts in the runtime and the store, `Domains.kt` with `actor` as a path root, `Codegen.kt`'s commit function and read surface, `Read.kt` with the viewer and the visibility conjunct, `SpecGen.kt`'s role cases.
- The pharmacy rewritten onto the actor, regenerated, its tests passing the actor; a small vendor-and-products spec exercising visibility end to end, the first spec whose tests read through two actors and the system view.
- QUESTIONS.md: the OQ20 row amended; the OQ56 item gains its data; OQ28 gains the bootstrapping item.
