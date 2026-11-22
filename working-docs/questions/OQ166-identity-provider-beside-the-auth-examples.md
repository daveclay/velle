# OQ166 — an example with authorization runs beside a real identity provider, and shows sign-in and the spec's rules working together

**Status:** opened 2026-11-22 from Dave's request in chat. Investigated against HEAD the same day. D1 to D6 decided 2026-11-22. The build plan in §3 awaits Dave's look before building. Nothing built.

**In plain terms:** the two examples whose specs say who may act and who may see do not sign anyone in. The caller types "I am Staff:1" into a box and is believed. Dave wants to start one of them and have a real sign-in service start with it, sign in as a real person, and see the spec's rules apply to that person.

**See:** `wiki/Guides/Serving-a-system.md`, "Who is calling" (the resolver and the 401 rules) · `wiki/Guides/API-layer.md`, "Replacing a layer", "Another identity" (says the resolver is the whole of it; nothing shows it done) · `apis/spring/src/main/kotlin/velle/api/spring/ActorResolver.kt` (the interface) · `examples/vendors/app/src/main/kotlin/velle/vendors/VendorsActors.kt` and `examples/pharmacy/app/src/main/kotlin/velle/pharmacy/PharmacyActors.kt` (the two header resolvers) · `examples/vendors/vendors.velle`, shape `Staff` · OQ94 in `working-docs/QUESTIONS.md` (the actor built from an identity provider's claims and never stored; a different question, see D3) · OQ152 (the console as a frontend toolkit; D4 touches the console)

---

Words used in this file, each as professionals use it:

- **Identity provider**: the service that holds the accounts and passwords and signs people in. Authentik and Keycloak are two.
- **Authentication**: establishing who the caller is. The identity provider does this.
- **Authorization**: deciding what that person may do and see. The Velle spec does this, with `by`, `requires`, and `visible to`.
- **OpenID Connect**: the standard protocol by which an application sends a person to an identity provider to sign in and gets back a signed statement of who they are.
- **Token**: that signed statement, a text the caller sends with each request.
- **Claim**: one named value inside a token, such as the person's username.

Decisions in this file: D1 which identity provider. D2 which example. D3 how a signed-in person becomes the spec's actor. D4 how the console signs in. D5 whether the header stays. D6 whether the field D3 adds holds the email or the identity provider's identifier.

## 1. What runs today

- Two specs declare authorization: `examples/vendors/vendors.velle` (acts `AssignVendor by Administrator`, `ListProduct by Administrator`, `ChangePrice` gated on a `VendorAssignment`; `visible to` clauses on shape `Product` and its fields `cost` and `margin`) and `examples/pharmacy/pharmacy.velle` (acts `by Staff`, `by Administrator`, `by LicensedPharmacist`, and the spec-wide `visible to`).
- Neither application authenticates. Each declares an `ActorResolver` bean that reads the header `X-Velle-Actor: Staff:1` and returns a reference to that stored `Staff` record (`VendorsActors.kt` line 29, `PharmacyActors.kt` line 20). Anyone who can reach the port can act as any staff member.
- The console sends that header from two text boxes in its sidebar (`console/src/frame/RequestHeaderBox.tsx`).
- The resolver interface returns a reference to a stored record or null (`ActorResolver.kt` line 15). With null, an act declared `by` and every read are answered 401 (`wiki/Guides/Serving-a-system.md`, "Who is calling").
- `./gradlew :examples:vendors:app:bootRun` starts the PostgreSQL in `examples/vendors/app/compose.yaml` through Spring Boot's Docker Compose support (`examples/vendors/app/build.gradle.kts`, the `developmentOnly` dependency). A second service added to that file starts the same way. This is the hook for "kick up the identity provider as well".
- No module in the repo depends on Spring Security (`grep security apis/spring/build.gradle.kts examples/*/app/build.gradle.kts` finds nothing).
- Shape `Staff` in `examples/vendors/vendors.velle` holds `name` and `role` and nothing that names an account at an identity provider. Neither does pharmacy's.

What I read about Authentik on 2026-11-22, from its own documentation, not yet run here:

- It runs as a server container and a worker container over a PostgreSQL database, and asks for a host with 2 cores and 2 GB of memory (https://docs.goauthentik.io/install-config/install/docker-compose/).
- It reads YAML files, which it calls blueprints, from `/blueprints` in the worker container and creates what they describe at startup (https://docs.goauthentik.io/customize/blueprints/). Whether one blueprint can create the two demo users with passwords, the OpenID Connect client with a fixed id and secret, and the application is not confirmed. It is the first work item.
- A script can get a token for a named user without a browser, using the user's name and an application password (https://docs.goauthentik.io/add-secure-apps/providers/oauth2/client_credentials/). Which claims that token carries is not stated there. Second work item.

## 2. What to decide

### D1. The identity provider is Authentik · Decided 2026-11-22

Is the identity provider Authentik, the one you named?

Recommendation: yes. It is what you asked for, it is a full product rather than a test double, and its blueprint files let the demo's users and client be checked in beside the example.

Costs:

- Three containers beside the example's own database (server, worker, and a PostgreSQL for Authentik), and a first start that runs Authentik's migrations. Expect `bootRun` to take a minute or more the first time, where today it takes seconds.
- The alternative is Keycloak: one container, one checked-in JSON file for users and client, starts in well under a minute. This is from my knowledge of Keycloak, not checked here. It is lighter and less like what you named.
- Either way the choice is the example application's. Nothing in `compiler/`, `apis/`, or the spec names a provider. Any provider that speaks OpenID Connect is swapped in by changing `compose.yaml` and three lines of `application.yaml`.

**Decided:** yes, the identity provider is Authentik (Dave, 2026-11-22).

### D2. The vendors example gets it first, pharmacy afterwards · Decided 2026-11-22

Is the vendors example the one that runs beside the identity provider, with pharmacy following once vendors works?

Recommendation: yes. `examples/vendors/vendors.velle` was written to show the read side of authorization, and its two seeded staff make the whole demonstration in four requests:

- Grace (role `admin`) signs in and reads shape `Product`: every product, with `cost` and `margin`.
- Linus (role `staff`) signs in and reads shape `Product`: only Acme's products, `cost` and `margin` absent.
- Linus commits `ListProduct`: refused, the act is `by Administrator`.
- Nobody signs in: 401.

Costs:

- Until pharmacy follows, the two examples resolve the actor differently, one by sign-in and one by header.
- Pharmacy has more staff (its `Roster.kt`) and each needs an account in the blueprint. That is why it is second.

**Decided:** yes, vendors runs beside the identity provider first and pharmacy follows once vendors works (Dave, 2026-11-22).

### D3. A signed-in person is matched to a stored `Staff` record by a `login` field in the spec · Decided 2026-11-22

Does shape `Staff` gain a field holding the person's username at the identity provider, with the resolver finding the `Staff` record whose `login` equals the token's username claim?

Recommendation: yes. This is the division you asked to see. The identity provider says who the person is. The spec says what a staff member with that role may do. The role stays in Velle, on the `Staff` record, and the identity provider carries no roles at all.

`examples/vendors/vendors.velle` today, then with the change:

```velle
-- today
Role is one of "staff", "admin"   -- the compiler makes Role a text limited to these two values

shape Staff {                      -- a stored shape; the actor of every `by` act is one of these records
    name: text                     -- stored
    role: Role                     -- stored; refinement Administrator below reads it
}

shape Administrator = Staff where role == "admin"   -- the role gate `by Administrator` checks the actor against this

transient act ListProduct by Administrator {   -- the compiler refuses the commit unless the request's actor is an Administrator
    vendor: one Vendor
    name: text
    price: decimal
    cost: decimal
}
```

```velle
-- proposed: one added line
shape Staff {
    name: text
    login: text unique             -- stored; the compiler emits a constraint that no two Staff share a login
    role: Role
}
```

The resolver, in the example application and not in Velle, reads the token's `preferred_username` claim and finds the one `Staff` where `login` equals it.

Costs:

- The spec now carries a field whose only reader is outside the spec. No rule, `never`, or `visible to` reads `login`. That is honest (a product owner does say "each staff member has a login") but it is a field Velle can prove nothing about beyond its uniqueness.
- A person with an account at the identity provider and no `Staff` record is authenticated and resolves to no actor. Today that is answered 401, the same answer as not signing in at all. 403 would be the accurate one. Building that distinction touches `apis/spring` and is not proposed here; it becomes a work item if you want it.
- The seeded staff and the blueprint's users must agree on the logins (`grace`, `linus`). Two files state the same two names.
- The alternative that keeps the spec untouched is a table of username to staff key kept by the application outside Velle. It hides a business fact (who is which staff member) from the spec, so I do not recommend it.
- The other alternative is OQ94: the actor is built from the token's claims and never stored, and the role comes from a group at the identity provider. That needs `transient shape`, which is not built, and it moves the role out of Velle. It stays its own question. This example would be the natural place to exercise it later.

#### Proposed changes

- `examples/vendors/vendors.velle`: shape `Staff` gains `login: text unique`.
- `examples/vendors/output/`: regenerated.
- `examples/vendors/app/src/main/kotlin/velle/vendors/VendorsActors.kt`: the fixtures seed `login`; the resolver reads the authenticated principal's username and finds the `Staff` record by it.
- `apis/spring/src/test/kotlin/velle/api/spring/VendorsApp.kt` and `VendorsOverHttpTest.kt`: seed the new field.

**Decided:** yes to a stored field on shape `Staff` that the resolver matches against a claim in the token, and no to the username: "seems like email would be more likely, or just some identity ID" (Dave, 2026-11-22). Which of the two is D6.

### D4. The application signs the browser in, and the console is not changed · Decided 2026-11-22

Does the vendors application itself send the browser to the identity provider and keep the signed-in session, so that opening `/velle` signed out lands on Authentik's sign-in page, and the console's code is untouched?

Recommendation: yes. Spring Security does the redirect and holds the session in a cookie. The console's requests to `/api` and `/graphql` carry the cookie. The same application also accepts a token in the `Authorization` header, so a script or a test can call the API as Grace or Linus without a browser.

Costs:

- The console does not show who is signed in and has no sign-out button. To switch from Grace to Linus you visit `/logout`, or use a second browser profile. A "signed in as" line needs a console change and an endpoint to ask; that belongs with OQ152.
- The console's "Request header" box stays on screen and does nothing in this application.
- Spring Security becomes a dependency of `examples/vendors/app` only. `apis/spring` gains nothing, in keeping with "Velle never authenticates" (`ActorResolver.kt`).
- The alternative is that the console signs in by itself and sends a token with each request. That is how a separately hosted frontend would do it, and it is a real change to the console: a sign-in library, token refresh, configuration for the provider's address. I would take it up under OQ152, not here.

#### Proposed changes

- `examples/vendors/app/build.gradle.kts`: Spring Security's OpenID Connect client and token-checking starters.
- `examples/vendors/app/compose.yaml`: the identity provider's services, on fixed host ports, with a health check so `bootRun` waits for it.
- `examples/vendors/app/authentik/vendors.yaml` (new): the blueprint with users `grace` and `linus`, the client, and the application.
- `examples/vendors/app/src/main/resources/application.yaml`: the provider's address, client id, and secret. These are demo values, checked in, and say so.
- `examples/vendors/app/src/main/kotlin/velle/vendors/VendorsSecurity.kt` (new): every path requires sign-in; browser sign-in and token both accepted.
- `README.md`: the vendors line says who to sign in as and with what password.
- `wiki/Guides/Serving-a-system.md`, "Who is calling": shows this resolver beside the header one, citing the file.
- `examples/CLAUDE.md`: the layout paragraph says vendors signs in through an identity provider.

**Decided:** yes, the vendors application signs the browser in itself and the console is not changed (Dave, 2026-11-22).

### D5. The vendors application drops the `X-Velle-Actor` header · Decided 2026-11-22

Once sign-in works, does the vendors application stop accepting `X-Velle-Actor` altogether?

Recommendation: yes. An application that takes both a real sign-in and "believe this header" demonstrates nothing, because the header walks around the sign-in.

Costs:

- The vendors smoke test (`examples/vendors/app/src/test/kotlin/velle/vendors/VendorsApplicationTest.kt`) runs over in-memory SQLite with no Docker and so has no identity provider. It presents a signed-in person through Spring Security's test support instead of the header. It still needs no Docker.
- The demonstration against the real provider is a second test that starts Authentik in a container, gets a token for `grace` and for `linus`, and makes the four requests of D2. Like the PostgreSQL tests it is skipped when Docker is absent. It is slow, a minute or more.
- `apis/spring`'s own `VendorsApp.kt` keeps its header resolver. It tests the starter, not sign-in.
- Poking the vendors API with `curl` now needs a token first. The README gains the two lines that fetch one.

#### Proposed changes

- `examples/vendors/app/src/main/kotlin/velle/vendors/VendorsActors.kt`: the header resolver is removed.
- `examples/vendors/app/src/test/kotlin/velle/vendors/VendorsApplicationTest.kt`: signed-in principals in place of the header.
- `examples/vendors/app/src/test/kotlin/velle/vendors/VendorsSignInTest.kt` (new): the four requests of D2 against a real Authentik container.

**Decided:** yes, the vendors application drops the header (Dave, 2026-11-22).

### D6. A signed-in person is matched to their `Staff` record by email · Decided 2026-11-22

Does shape `Staff` gain `email: text unique`, with the resolver finding the `Staff` record whose `email` equals the token's `email` claim, rather than a field holding the identity provider's own identifier for the person?

Two claims are on the table. Every OpenID Connect token carries `sub`, the identity provider's identifier for the account: unique within that provider and never given to another account. The `email` claim carries the person's email address, with `email_verified` saying whether the provider has confirmed it.

Recommendation: email. A product owner says "each staff member has an email address" and means a business fact. Nobody says "each staff member has an Authentik subject identifier".

`examples/vendors/vendors.velle` with each alternative:

```velle
-- recommended: email
Role is one of "staff", "admin"   -- the compiler makes Role a text limited to these two values

shape Staff {                      -- a stored shape; the actor of every `by` act is one of these records
    name: text                     -- stored
    email: text unique             -- stored; the compiler emits the prohibition that no two Staff share an email (Reference §21, "Keys")
    role: Role                     -- stored; refinement Administrator below reads it
}

shape Administrator = Staff where role == "admin"   -- the role gate `by Administrator` checks the actor against this
```

```velle
-- alternative: the identity provider's identifier
shape Staff {                      -- a stored shape; the actor of every `by` act is one of these records
    name: text                     -- stored
    identityId: text unique        -- stored; no two Staff share one; holds the token's `sub` claim
    role: Role                     -- stored
}
```

Costs of email:

- An email address can change at the identity provider. When it does, the person signs in and matches no `Staff` record (401, D3's cost) until the record is changed to match. Vendors has no act that changes a `Staff` record today; its staff are seeded by `VendorsActors.kt`.
- The resolver must accept only a token whose `email_verified` is true. Otherwise anyone able to set an unconfirmed address at the provider could claim Grace's.
- Velle has no email type. The field is `text`, and the compiler proves nothing about it beyond its uniqueness.
- The seeded staff and the blueprint's users must agree on two addresses, stated in two files. This is the same cost D3 named for usernames.

Costs of the identity provider's identifier:

- It never changes for the life of the account, so the match never breaks.
- It is a value the provider generates, so the spec holds a fact about one provider instead of about the business. Replacing Authentik with another provider issues a new identifier for every person and every `Staff` record must be rewritten. It leans against "Velle captures the product-owner and engineer conversation as a pure business layer" (root `CLAUDE.md`).
- The seeded staff must know each identifier before the provider has issued it. From my knowledge of Authentik, not checked here: its `sub` is by default a hash of its internal user number, and a setting on the client can make it the username, the email, or the user's UUID instead. Making it the email brings back email's costs.

#### Proposed changes

- Replaces D3's list. `examples/vendors/vendors.velle`: shape `Staff` gains `email: text unique`.
- `examples/vendors/output/`: regenerated.
- `examples/vendors/app/src/main/kotlin/velle/vendors/VendorsActors.kt`: the fixtures seed `email`; the resolver reads the token's `email` claim, refuses it unless `email_verified` is true, and finds the `Staff` record by it.
- `examples/vendors/app/authentik/vendors.yaml`: users `grace` and `linus` carry the same two addresses, marked verified.
- `apis/spring/src/test/kotlin/velle/api/spring/VendorsApp.kt` and `VendorsOverHttpTest.kt`: seed the new field.

**Decided:** yes, the field is `email` (Dave, 2026-11-22, answered under D3: "use `email`").

## 3. The build

The decided changes of D1, D2, D4, D5, and D6, in build order. D6's list replaces D3's. Nothing in `compiler/` changes. Step 1 comes first because steps 3 and 4 rest on what it finds: if the blueprint cannot fix the client's id and secret or the users' verified emails, this plan comes back to Dave before anything else is built.

1. The identity provider, proven first:
   - `examples/vendors/app/compose.yaml`: Authentik's server, worker, and PostgreSQL, on fixed host ports, with a health check so `bootRun` waits for it (D4).
   - `examples/vendors/app/authentik/vendors.yaml` (new): the blueprint with users `grace` and `linus`, each with a password and a verified email, the OpenID Connect client with a fixed id and secret, and the application (D4, D6).
   - Work items 1 and 2 below run against these two files.
2. The spec:
   - `examples/vendors/vendors.velle`: shape `Staff` gains `email: text unique` (D6).
   - `examples/vendors/output/`: regenerated (D6).
3. The application:
   - `examples/vendors/app/build.gradle.kts`: Spring Security's OpenID Connect client and token-checking starters (D4).
   - `examples/vendors/app/src/main/resources/application.yaml`: the provider's address, client id, and secret, marked as checked-in demo values (D4).
   - `examples/vendors/app/src/main/kotlin/velle/vendors/VendorsSecurity.kt` (new): every path requires sign-in; browser sign-in and token both accepted (D4).
   - `examples/vendors/app/src/main/kotlin/velle/vendors/VendorsActors.kt`: the fixtures seed `email`; the header resolver is removed; the new resolver reads the token's `email` claim, refuses it unless `email_verified` is true, and finds the `Staff` record by it (D5, D6).
4. The tests:
   - `examples/vendors/app/src/test/kotlin/velle/vendors/VendorsApplicationTest.kt`: signed-in principals through Spring Security's test support in place of the header; still no Docker (D5).
   - `examples/vendors/app/src/test/kotlin/velle/vendors/VendorsSignInTest.kt` (new): the four requests of D2 against a real Authentik container, skipped when Docker is absent (D5).
   - `apis/spring/src/test/kotlin/velle/api/spring/VendorsApp.kt` and `VendorsOverHttpTest.kt`: seed `email`; the header resolver stays (D5, D6).
5. The documents:
   - `README.md`: the vendors line says who to sign in as, with what password, and the two lines that fetch a token for `curl` (D4, D5).
   - `wiki/Guides/Serving-a-system.md`, "Who is calling": shows the sign-in resolver beside the header one, citing `VendorsActors.kt` (D4).
   - `examples/CLAUDE.md`: the layout paragraph says vendors signs in through an identity provider (D4).
6. The settling commit: the full suite passes; `QUESTIONS.md` gains the settled row for OQ166 and a one-line OQ for pharmacy following vendors (D2); this file is deleted.

## 4. Work items

- [ ] Confirm by running it that one Authentik blueprint file creates two users with passwords and verified emails, an OpenID Connect client with a fixed id and secret, and the application; check the file in as `examples/vendors/app/authentik/vendors.yaml` (§3 step 1).
- [ ] Confirm by a checked-in test which claims Authentik's token for a named user carries, `email`, `email_verified`, and `sub` among them, and what `sub` holds by default, and that a script can obtain one without a browser (§3 step 1).
- [ ] Measure the first and the second `bootRun` with the identity provider in `compose.yaml` and write both times into the README's vendors line (§3 steps 1 and 5).
- [ ] Add `email: text unique` to shape `Staff` in `examples/vendors/vendors.velle` and regenerate `examples/vendors/output/` (§3 step 2).
- [ ] Add Spring Security, `VendorsSecurity.kt`, the provider's settings, and the email resolver to `examples/vendors/app`, removing the header resolver (§3 step 3).
- [ ] Move `VendorsApplicationTest.kt` to signed-in principals, write `VendorsSignInTest.kt`, and seed `email` in `apis/spring`'s vendors tests (§3 step 4).
- [ ] Update `README.md`, `wiki/Guides/Serving-a-system.md` "Who is calling", and `examples/CLAUDE.md` (§3 step 5).
- [ ] Land the settling commit: suite green, settled row, pharmacy OQ line, this file deleted (§3 step 6).
