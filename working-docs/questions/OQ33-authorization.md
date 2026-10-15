# OQ33 — Authorization: the caller, roles, and what a caller may commit and see

**Status:** open; investigation written 2026-10-14 at Dave's request (the question was deferred post-v0 as `visible to` field visibility; it now carries the whole authorization question, since the read side and the act side turned out to be one construct seen from two places)
**In plain terms:** the engineer's code authenticates a person and, through one resolver registered when the system is built, hands velle an instance of the shape the spec declares for callers, stored when the system owns its users and built from an identity provider's claims when it does not. The spec then says, in its own predicate language, which callers may commit which acts and which records and fields a caller may see, with roles as refinements over the person's shape and data-driven conditions as ordinary predicates relating the caller to the record. A scheduled rule has no caller. The engineer's own reads use a system view the surface does not filter.
**See:** Reference §8 ("Every captured value traces to data"), §21 (prohibitions), §22 ("External input (`act`)", the query API) · `checks.md` V21, V29 · `evaluation.md` "An exposed call" · `examples/pharmacy/pharmacy.velle` (the role gates as they are written today) · OQ28 (bootstrapping the first caller) · OQ56 (generated cases for role refusals) · OQ57 (the read API's follow-ons)

---

## 0. What Dave asked for (2026-10-14)

- A principal, in the old Java Authentication and Authorization Service sense: the authenticated party on whose behalf a call is made.
- Roles associated to the authenticated user.
- Data-driven constraints: "the user can see products in the vendors they are assigned to".
- The principal is supplied by the binding API, presumably as another parameter to the generated commit functions.
- Scheduled rules use a system principal when there is no caller, configured by the engineer through the velle API.

This document maps each of those onto what velle already has, names the three settled lines the request moves, proposes one design, and ends with the decisions that are Dave's. The word used below is **caller**, not principal: the Reference already calls the party a prohibition binds "the caller" and the party who supplies an act's fields "the committer", and the language avoids computer-science vocabulary where a plain word exists.

## 1. What the request moves

Three settled lines push back on the request as framed. Each has an answer, and the answers shape the design.

**Reference §22 retires who-may-commit to the engineer's wrapper code.** "Who-may-commit conditions (caller identity, ambient policy, rate — OQ20's residue) live in the engineer's wrapper code before the commit call, outside the language." That ruling was right for identity on its own: whether a token is valid is transport. It breaks on the data-driven case. "A pharmacist may verify only fills of their own pharmacy" and "a caller sees products of the vendors they are assigned to" are predicates over the data graph, written in the vocabulary the spec owns; a wrapper that checks them re-implements the spec's relationships and refinements by hand, and the read API made it worse: a surface the caller composes (Reference §22, OQ51) cannot be gated query by query from outside without the wrapper understanding every query. So the residue splits. Authenticating the request and finding the user's record stay the engineer's job, outside velle, along with rate limits, quotas, and which machine clients may call which endpoints. Once the engineer hands a reference to that record into the call, what the user may commit and may see is the spec's job. "Transport" throughout this document is the Reference's word (§22) for the code that carries a request from the outside world to the generated commit function: a REST endpoint, a GraphQL resolver, a message-bus consumer.

**Reference §8 forbids an ambient current user.** "There is no ambient execution context — no 'current user', no request-scoped magic." A caller handed to every commit is a current user. The principle survives if the caller is a fact *of the act's commit*, carried with the act like its fields, and readable exactly where the act's fields are readable: in a prohibition over the act, in a refinement of the act, in the act's own derived properties, and in a rule whose subject is the act. It is not readable from a persistent shape's body, from a refinement over a persistent shape, from a rule whose subject is some other record, or at a tick. A durable record that needs it copies it, which is precisely how §8 already treats `ArchiveRequest.requestedBy`: the act carries the person, the capture reaches the person through the act. Nothing about the principle changes; the caller becomes one more thing the act carries.

**V21 bans language-populated fields on an act.** "A system-maintained value is no part of an external submission." The caller is the one system-known fact that *is* part of the submission, and it is in the same category as `timestamp`: a fact about the commit the committer may not claim (Reference §5: "a fact about the commit can't be claimed by the client"). Today the pharmacy spells every role gate over a committer-supplied field, `VerifyFill requires pharmacist to be LicensedPharmacist` with `pharmacist: one Staff` in the act's body (`examples/pharmacy/pharmacy.velle`, the verification section), so any caller can claim to be any pharmacist. The gate is a business statement with no enforcement behind it. The caller must be readable on the act itself, because a refusal is checked at the door before any rule could materialize anything, so V21 gains exactly one exception: the field whose value is the caller.

## 2. Mapping the request onto the language

**The caller is an instance of a shape the spec declares.** Never a token, a subject string, or a bare list of role names. This is the choice that makes everything else free: an instance has fields and relationships, so every condition about the caller is an ordinary predicate. Systems keep their users in two places, and both fit this one rule; what differs is whether the shape is stored (Dave, 2026-10-14).

- **The system owns its users.** `shape Staff { ... }`, ordinary and stored, its instances created the way every shape's are: by a rule from an act (`transient act HireStaff` and a rule producing the `Staff`), the transient-materialization idiom Reference §4 already prescribes. Relationships point at it (`VendorAssignment.staff: one Staff`), the inferred inverses work (`staff.verifications`), and the engineer resolves an authenticated identity to the record with one read through velle's own query API, on a field the spec declares (`externalId: text`). The pharmacy's `act Staff` was a shortcut for "people arrive from outside"; for a caller's shape it reads wrong, since it says the caller is a mutation, and the caller's shape is never an `act` (Dave, 2026-10-14).
- **An identity provider owns them.** `transient shape Staff { ... }`. The keyword means what it already means for acts: an input to the state, not a member of it, existing only within the call it arrived with; here it arrives as the caller of a commit or read rather than as the commit itself. The engineer's resolver builds the instance from the provider's claims for each call (`externalId`, `email`, `roles: many text`), it is gone when the call is, and stored records correlate to it by a business key (`VendorAssignment where staffId == caller.externalId`). Roles are still refinements, over the arriving fields. The word is explicit rather than inferred from "no rule produces this shape" because a spec that later gained a producing rule would silently flip from unstored to stored, and the compiler refuses to guess (Reference §1).

In the spec the two look alike: a shape, and refinements over it for roles. What the unstored form gives up is stated in §5.2. Its obligations fall out of what `transient` already carries: V17's isolation says nothing durable may reference a transient instance, so `verifiedBy: one Staff` on a stored shape is a compile error whose diagnostic names the key field as the served spelling; the one adjustment is that a transient act's name may appear in no expression, while a transient shape must be readable through `caller` and refinable for roles, so the ban narrows to stored references.

**The engineer resolves the caller once, not at every call.** The clunkiness Dave remembers from the Java Authentication and Authorization Service was not the user object; it was fetching the user outside the application code before every call, which is what led people to abandon it until Spring encapsulated the fetch behind one interface the application implements once. Velle takes that shape: the engineer registers one resolver on the system when it is built, a function from the authenticated identity as transport has it (a subject string, a claims object) to the caller instance, and the generated surface calls it for each commit and each read. Velle still never authenticates and never looks anyone up; it invokes code the engineer wrote exactly once. The system view for the engineer's own reads is the same hook with a fixed answer.

**Roles are refinements over the caller's shape.** The pharmacy already writes them: `shape Pharmacist = Staff where role == "pharmacist"`, `shape LicensedPharmacist = Pharmacist where exists (licenceRenewals where expiresOn >= today)`. A static role is a refinement with a field predicate; a role that depends on a licence in force, a vendor assignment, or a date is a refinement with a relationship or clock predicate. External role-based access control maps in as data on the caller's record, maintained by acts (an administrator assigns a role) or by a mapping. No role construct is needed.

**A who-may-commit condition is a prohibition over the act that reads the caller.** `VerifyFill requires caller to be LicensedPharmacist` is a refusal (Reference §21): checked at the door when the predicate reads nothing a rule writes, at the settled transaction otherwise, and the rejection carries the act, the caller's record as a record the prohibition read through, and the evaluation. A data-driven write condition is the same construct with a relationship in it: `FillPrescription requires exists (VendorAssignment where staff == caller and vendor == lot.vendor)`. Nothing new is enforced; the door already runs.

**A who-may-see condition is a visibility declaration the read surface applies.** This is the half OQ33 always held. Under a surface the caller composes, a gate written into one named refinement is bypassed by composing against the base shape, so visibility is declared on the shape and on the field, against a caller that is a parameter of the whole read, and the surface applies it to every composed read, every reference resolution, and every projected field. It is applied to what the surface *returns*, never to how the spec *evaluates*: a rule, a derivation, and a prohibition see the whole state, as they do today.

**A scheduled rule has no caller, and needs none.** V29 already rules that a firing with no caller cannot be refused; under the readability rule above a tick rule cannot name `caller`, so there is nothing for a system principal to be consulted by inside the spec. Where the engineer's own code acts on the system's behalf, the system identity is a binding-API concept: a system *view* for reads, which the surface does not filter, and for commits a real record the engineer chooses to model (a `Staff` with role `"system"`), since a refusal is a business prohibition and nobody bypasses it.

## 3. The proposed design

### 3.1 Spelling

Three additions, one of them a reserved name.

- **The caller's shape, declared once at the top level.** `caller: one Staff`, where `Staff` is a `shape` or a `transient shape` (§2), never an `act`. The target may be a static union (`caller: one Person` with `shape Person = Staff or Customer`), which is already a legal relationship target (Reference §6), and narrowing by `caller is Staff` already works (Reference §10). A spec with no such line has no caller anywhere, and nothing below applies to it: every spec written so far keeps compiling and keeps its generated surface.
- **`caller`, a reserved name**, typed by that declaration, in the family of `this`, `today`, and `id`. Readable where §1 says: a `never` or `requires` whose subject is an act or a refinement of an act, a refinement over an act, a derived property of an act, and the body of a rule `on commit` whose subject is the act, one of its refinements, or a part of its closure. Anywhere else, a compile error naming the rule: "`caller` is a fact of an act's commit; this rule's subject is `Fill`, read the person from the data."
- **A named alias on the act**, for business words and for durability: `pharmacist: one Staff = caller`. A derived property reading a commit-fixed fact, so it is constant for the act's lifetime; on a transient act it is the existing once-evaluated arrival-time derivation (Reference §22), on a durable act the runtime stores it at the creation commit exactly as it stores `timestamp on create`, and it is never in the generated input value, never assignable, never committer-suppliable. This is the one language-populated declaration V21 admits on an act. An act may declare at most one.
- **Visibility.** A body clause in the family of `frozen` and `undeletable`, record-level and field-level, each a predicate over `this` and `caller`:

  ```
  shape Product {
      vendor: one Vendor
      name: text
      cost: Money visible where caller is Administrator
      visible where exists (VendorAssignment where staff == caller and vendor == this.vendor)
  }
  ```

  `visible to Administrator` is sugar for `visible where caller is Administrator`, the same one-desugaring sugar as `requires ... to be`. A field with no clause is visible wherever its record is; a shape with no clause is visible to every caller (the default is §5's question). A derived property is a field like any other and carries its own clause; the spec does not reason about what a derivation reveals of its inputs, and an advisory can later point at a visible derivation over an invisible field if that proves to matter.

### 3.2 The pharmacy, rewritten

What the verification counter looks like once the caller is real. Today's version is in `examples/pharmacy/pharmacy.velle`, the "Verification: the pharmacist's check" section, where `pharmacist` is a field the committer supplies. The velle text barely changes, and that is the point: the one declaration that changes, `pharmacist: one Staff = caller`, turns a claim any caller could make into a fact the caller cannot. Today's `act Staff` becomes a stored shape with a hiring act, the spelling §2 gives for a system that owns its users; with an identity provider the first declaration would read `transient shape Staff` and `HireStaff` would not exist.

```
caller: one Staff

shape Staff {
    name: text
    role: text
    externalId: text            -- what the engineer's resolver matches the authenticated identity to
}

Staff requires role == "pharmacist" or role == "technician" or role == "admin" or role == "system"

transient act HireStaff {
    name: text
    role: text
    externalId: text
}

HireStaff requires caller to be Administrator

rule RecordHire when HireStaff {
    Staff from { name: name, role: role, externalId: externalId }
}

shape Pharmacist = Staff where role == "pharmacist"
shape Administrator = Staff where role == "admin"
shape LicensedPharmacist = Pharmacist where exists (licenceRenewals where expiresOn >= today)

-- An administrator records a renewal for a pharmacist. Two people, two roles:
-- the pharmacist is a field the caller names; the caller is the administrator.
act LicenceRenewal {
    pharmacist: one Staff
    licenceNumber: text
    expiresOn: Date
}

LicenceRenewal requires pharmacist to be Pharmacist
LicenceRenewal requires caller to be Administrator

transient act VerifyFill {
    fill: one Fill
    pharmacist: one Staff = caller          -- the business name for the caller, read by the rule below
    outcome: text
    note: text
}

VerifyFill requires pharmacist to be LicensedPharmacist
never (VerifyFill where pharmacist == fill.filledBy and fill.lot.drug is Controlled)
VerifyFill requires outcome == "approved" or outcome == "rejected"

shape Verification {
    fill: one Fill
    pharmacist: one Staff
    outcome: text
    note: text
    verifiedAt: timestamp on create
}

rule RecordVerification when VerifyFill {
    Verification from { fill: fill, pharmacist: pharmacist, outcome: outcome, note: note }
}
```

The three prohibitions over `VerifyFill` are unchanged from today's file; only the field declaration changed. The generated `NewVerifyFill` loses its `pharmacist` field, the generated commit function gains a `caller: Ref<Staff>` parameter, and `LicenceRenewal`, which reads `caller` only in a prohibition, gains the parameter without any field. Every one of the pharmacy's eighteen role gates converts the same way.

### 3.3 The binding API

- **The resolver.** Registered once when the system is built: `PharmacySystem(resolveCaller = { identity -> ... })`, where the engineer's function turns the authenticated identity transport hands it into the caller instance, by a read through the query API when the shape is stored or by building the instance from claims when it is not. Transport then passes the identity, not the user, and the surface resolves it. An engineer who prefers to resolve by hand may pass the instance directly; the resolver is the default path, not the only one.
- **Commit.** `commit(input: NewVerifyFill, caller: Ref<Staff>): Commit<NewVerifyFill>` for an act whose spec reads `caller` anywhere (a prohibition, a partition, an alias, a rule on it, a visibility clause does not count); `commit(input)` as today for an act that never reads it, so an inbound processor verdict or a legacy import needs no invented person. For a stored caller shape, a reference to a record that does not exist, or whose shape is not the declared caller shape, is a type-level refusal at the boundary like any bad reference; for an unstored one, the instance is typed by the generated class and nothing more is checked. The door evaluates the refusal over pre-state plus the act's closure, and the caller's record is already in pre-state, so the door's moment (`evaluation.md`, "An exposed call") is untouched.
- **Read.** The viewer becomes a parameter of the whole read: `sys.read(caller).products.where { ... }.orderBy { ... }.list()`, `sys.read(caller)(ref)`, `sys.read(caller).product(key)`. The surface conjoins the record-level visibility predicate to every query rooted at the shape or at one of its refinements (through the existing candidate pre-filter, which may over-return, with the authoritative check in memory, so the superset contract in `compiler/src/main/kotlin/velle/Query.kt` already fits), resolves a reference to an invisible record as absent, and projects a model object with invisible fields absent. The engineer's own code reads through the system view, `sys.read.products...` as today, which no visibility clause filters; it is the engineer reading their own store, a power they already have.
- **Tick.** `tickDaily()` is unchanged. A tick has no caller.
- **The system identity.** Nothing in the spec names it. For reads it is the unfiltered view above. For a commit of an act whose spec reads `caller`, the engineer's automation passes a record it models for the purpose (the `role == "system"` staff member above), visibly, in the spec, as business data. An alternative is in §5.

### 3.4 What else the caller touches

- **Serialization domains** (`evaluation.md` U3, `compiler/src/main/kotlin/velle/Domains.kt`). A prohibition reading `caller.licenceRenewals` reads a record outside the act's own footprint, so `caller` becomes a path root the domain derivation keys on: verifications by one pharmacist queue together. Correct, and the contention map should say so in business words.
- **The validator.** One new form check: where `caller` may be read (§3.1). One new check for visibility clauses: the predicate reads `this`, `caller`, and the data graph, nothing else. V21 gains its exception. F2 types `caller` from the top-level declaration.
- **Spec generation** (`testgen.md`, OQ56). A refusal that reads `caller` gets a generated case per role: the caller who passes, the caller who does not. OQ56 already holds the item "let a generated boundary case name the role or relationship refusals on its act"; this gives it the data it was missing.
- **Diagrams.** The class diagram marks the caller shape; the rule graph can label an act's edge with the role that may commit it.
- **Bootstrapping.** `Staff requires caller to be Administrator` cannot admit the first administrator. This is OQ28's shape (a new rule against pre-existing state, here an empty state) and should be answered there; the likely answers are that the act creating people does not read `caller`, or that the first record arrives through a mapping, which is outside the trust boundary by construction.
- **Rejections.** A refusal that reads `caller` renders today's sentence ("VerifyFill refused: the requirement `pharmacist to be LicensedPharmacist` is unmet") with the caller's record among the records read through. No change to `compiler/src/main/kotlin/velle/Rejection.kt`'s shape.

## 4. What stays outside the language

- Authentication: tokens, sessions, identity providers, and the lookup from an external identity to the caller's record.
- Rate limits, quotas, which machine clients may call which endpoints, request size and timeouts: Reference §22's residue, minus what the user may commit and see.
- Row-level security in the store: the surface enforces visibility; whether the engineer also mirrors it into database policies is their call.
- Who may *tick*: schedules are the harness's calls, as today.

## 5. Decisions for Dave

Each section leads with the decision this document recommends and follows with what it costs.

### 5.1 Authenticating the user and handing them to velle is the engineer's job; what that user may do and see is the spec's

This is velle's separation of layers (Reference §1, §2) applied to authorization. Where a user is authenticated, and how, is a low-level concern: a token, a session, an identity provider. What concerns the product owner is which users may see which data and mutate which data, and that is a statement about the system's shapes and relationships, so it belongs in the spec. The engineer's code, outside velle, authenticates the request, finds the user's record, and passes a reference to it into the commit or read call. Velle never sees a token and never looks a user up. From the reference on, the spec decides: which acts that user may commit and which records and fields they may see. This reverses the half of OQ20's residue that put caller identity in wrapper code and keeps the half about ambient policy and rate. The cost: Reference §22's "External input" paragraph and the OQ20 row in the settled table are rewritten, and every role gate in the pharmacy is enforcement the wrapper used to be trusted for, so the wrapper that authenticates must now also resolve the caller to a record before every gated commit.

### 5.2 The caller is an instance of a shape the spec declares, stored or not, and roles are refinements over it; the engineer registers one resolver

Choose the shape instance over an opaque principal carrying role names, let the shape be stored (the system owns its users) or unstored (an identity provider owns them), and let the engineer register the identity-to-caller function once rather than fetch the user before every call. The gain is the whole data-driven case for free, no second vocabulary for roles, and no repeated fetching at call sites.

The costs, in plain terms:

- **With an identity provider, the spec cannot point at a user.** A stored record can be the target of `one Staff`, so "the fills this pharmacist verified" is a traversal, `staff.verifications`, and the compiler infers it. An unstored caller has no record to point at, so every record that must remember who acted stores a key the provider issued (`verifiedBy: text` holding `caller.externalId`), and "the fills this pharmacist verified" is a query matching that key. It works, but it is a join the author writes rather than a relationship the compiler knows about, and the compiler cannot check that the key names anyone.
- **With an identity provider, roles arrive as text.** The caller's roles are whatever the provider sent, a list of names, and a role refinement tests for a name in that list. Velle has no "this value is in that collection" test today, so `Administrator = Caller where roles contains "admin"` needs the text operator `contains` extended to collections, or a new word. A small addition, but a language change this question forces.
- **Every rule that reads the caller runs through engineer code.** The resolver is the engineer's function, called by the generated surface on every commit and read. If it is slow, every call is slow; if it is wrong, every gate is wrong. That is already true of authentication today and the resolver does not change it, but the dependency becomes explicit and sits in velle's call path.
- **A stored user shape must be kept in step with the system that authenticates.** When the system owns its users this is nothing new. When an identity provider owns them and the author still wants references, the author mirrors the provider's users into a stored shape (an act on first sign-in, or a mapping), and a role that changes in the provider is stale in the mirror until the mirror runs. The unstored form avoids this by reading the provider's claims fresh on every call, which is the trade between the two forms.

### 5.3 The spelling: `caller: one Staff` at the top level naming a `shape` or `transient shape`, `caller` reserved, `field: one Staff = caller` as the alias

Choose these three, with the caller's shape a `shape`, stored or `transient`, never an `act` (settled with Dave 2026-10-14, §2). The costs: `caller` joins the keyword list, so a spec with a field named `caller` breaks; `transient` gains a second position, before `shape`, with the same meaning it has before `act`, and V17 is restated so that a transient shape stays unreferenceable from stored records but readable through `caller`; the top-level declaration is the second declaration that begins with a name rather than a keyword (`requires` is the first); and the alias is a derived property whose value the runtime stores on a durable act, which is a new reading of `=` on an act that the Reference has to explain beside `timestamp on create`. The alternative spellings considered: a `committer` keyword in type position mirroring `timestamp` (`pharmacist: committer`), rejected because the read side needs a predicate-position name anyway and one name should serve both; a per-act clause (`act VerifyFill by Staff { ... }`), rejected because it cannot name a union without repeating it per act.

### 5.4 `caller` is readable only where the act is

Choose the narrow rule from §1: prohibitions, refinements, and derived properties of the act, and `on commit` rules whose subject is the act, one of its refinements, or a closure part. The cost: an `after commit` or scheduled rule over a durable act cannot name `caller` even though the act is its subject, and must read the stored alias instead, which the author has to have declared. The gain is that Reference §8 stays true as written, and a reader can see from a rule's header whether it can know who asked.

Why `after commit` cannot keep the caller, even though the runtime could carry it (Dave asked, 2026-10-14). Every `after commit` rule carries a guard and a backstop schedule (Reference §11, §18; check V4), so it has two firing paths: promptly after the act's transaction, and again at the backstop tick for anything the gap dropped. The queued prompt firing knows which act it follows and could be handed the caller; the tick firing is re-found from current state and has no caller. The same rule would then mean one thing when it fires promptly and another when the backstop heals it, which is the hidden memory the tick law forbids: what the backstop cannot see, the prompt firing may not use. The caller is in the same position as the transition itself: a rule outside the act's transaction that needs to know who asked must have that knowledge reified as data first, the stored alias or a field the materializing rule copied, and then both paths read the same thing. For a transient act the question does not arise, since V17 already forbids `after commit` on it.

### 5.5 The commit function takes a caller exactly when the act's spec reads one

Choose the per-act parameter over a uniform signature. The cost: the engineer's transport code learns per act whether to pass the caller, and adding a role gate to an act later is a signature change in generated code the engineer calls, which the compiler will flag. The uniform alternative (every commit takes a caller once the spec declares one) is simpler to call and forces an invented person onto acts that have none, such as a processor verdict. A third option is a top-level optional caller, `caller: one Staff?`, where every read of `caller` narrows with `is some` as any optional does and an anonymous commit passes the absence; it admits anonymous and automated callers without a modeled system record, at the price of a narrowing conjunct in every gate.

### 5.6 The system identity is a binding concept, not a spec name

Choose no reserved `system` value. For reads, the unfiltered system view; for a gated commit by automation, a record the author models. The cost: a system that wants its automation to commit gated acts must declare a staff member with a system role, visibly, and that record is data someone can edit. The alternative is a reserved `system` value typed as the caller shape and passing every gate; it is the first ambient value the language would have, it is a caller no record describes, and §1 argues against it. It would also be the thing a tick would carry, which V29 says a tick does not need.

### 5.7 Visibility: body clauses, visible by default, applied by the surface only

Choose `visible where` and `visible to` as body clauses on shapes and fields, visible when undeclared, enforced only on what the read surface returns. The costs, one per choice: a body clause means a shape that is sensitive says so and an insensitive one says nothing, so forgetting the clause fails open, which is the opposite of the fail-closed stance the checks take elsewhere (the alternative, hidden by default, would require every existing spec to declare visibility on every shape before a caller-aware read returns anything, and would make the read side unusable until a spec is fully annotated); and applying visibility only at the surface means a rule or derivation can reveal, through a visible value, an input the caller may not see, which this document leaves to the author and a possible later advisory.

## 6. Work, once the decisions land

- Reference: §5 (the alias beside `timestamp`), §8 (the caller as the act's fact), §21 (a prohibition reading `caller`), §22 (the scope statement, "External input", the query API's viewer); `grammar.md` (the top-level declaration, the reserved name, the visibility clauses); `checks.md` (V21's exception, the two new checks); `evaluation.md` ("An exposed call" with the caller in the input, "A read is a read-only snapshot" with the viewer); `testgen.md`; `diagrams.md`; the glossary (caller, system view).
- The compiler: lexer and parser, the model's caller shape, the validator's form checks, `Domains.kt` with `caller` as a path root, `Codegen.kt`'s commit function and read surface, `Read.kt` with the viewer and the visibility conjunct, `SpecGen.kt`'s role cases.
- The pharmacy rewritten onto the caller, regenerated, its tests passing the caller; a small vendor-and-products spec exercising visibility end to end, the first spec whose tests read through two callers and the system view.
- QUESTIONS.md: the OQ20 row amended; the OQ56 item gains its data; OQ28 gains the bootstrapping item.
