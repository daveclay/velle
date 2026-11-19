# OQ152 — the console becomes a configurable frontend built from reusable React components over GraphQL

**Status:** opened 2026-11-19 from Dave's note of the same day. D1 and D2 are decided and the first slice, the rebuild, is built (§3). D3, D4, and D5 await Dave.

**In plain terms:** every Velle system serves one page, the console, that shows every shape, record, and act with nothing written per system. Dave wants three things of it, in order. It stays the out-of-the-box page for demos and tests. An engineer can configure it, starting with how a record reads to a human, "Ada Lovelace" instead of `Customer#3`. And its pieces become React components an engineer can reuse, either by starting from the console and adding to it, or by placing one piece in a frontend they already have.

**See:** `console/` (the console, rebuilt 2026-11-19; §1 describes the page it replaced, `apis/spring/src/main/resources/velle/console.html` at commit `f1bd661`) · `apis/spring/src/main/kotlin/velle/api/spring/VelleConsole.kt` (how the starter serves it) · `apis/graphql/src/main/kotlin/velle/api/graphql/VelleGraphQL.kt` (the schema the console would read) · `apis/json/src/main/kotlin/velle/api/Describe.kt` (the model description, `GET /api`) · `wiki/Guides/Serving-a-system.md`, "The console" and "GraphQL" · OQ73 (Velle UI). Dave, 2026-11-19: OQ73 is left out of this question because it is hypothetical. A last goal here is to see whether a working console can be built towards it. Nothing in this file decides anything in OQ73. · OQ148 (GraphQL names a spec may not use; D3 adds one) · OQ151 (a shape no query can order).

---

## 1. What ran before the rebuild

Checked against commit `f1bd661`, the last with the single-file console. §3 says what runs now.

- The console is one file of 619 lines, `apis/spring/src/main/resources/velle/console.html`: its styles, its markup, and plain JavaScript with no framework and no build step. `VelleConsole.kt` reads it from the jar, puts the API path and the ticks flag into two placeholders, and serves it at `/velle`.
- The repository has no JavaScript build of its own. The one `package.json` is `editors/velle/package.json`, the editor extension.
- The console talks to REST only (`callApi`, line 138). It reads the model description once from `GET /api`, then one request per table page, one per count, one per record, one per act, one per schedule.
- A reference is shown as the shape's name and the store's key, `Customer#3`, in every place: `referenceLink`, line 175, and the record page's title, line 376. A reference on the wire is the pair `{shape, key}` and nothing else, so showing a name would cost one more REST request per referenced record.
- An act's form takes a reference as a typed key (`inputFor`, line 399). There is no choosing from the records that exist.

What GraphQL has and lacks for this, in `VelleGraphQL.kt`:

- A reference member is a field that answers the referenced record (`memberFetcher`, `referencedRecord`), so one request can ask for an invoice and its customer's name together. This is what Dave's note expects of GraphQL, and it holds.
- Every act is a mutation answering the acceptance or the refusal as data (`outcomeType`), every schedule's latest run is the `ticks` field, and a tick is the `tick` mutation when ticks are exposed. The console needs nothing more of these.
- **Finding G1.** GraphQL does not serve the model description. The schema can be read by GraphQL's own introspection, but that does not say which members a query may order by (`orderable`), which members are derived or inverse collections, which acts are transient, who commits an act, or which schedules exist. `Describe.kt` says all of these and only REST serves it. D3 asks what to do.
- **Finding G2.** A collection of references answers the records themselves (`referencedRecords` reads each one through `api.readEach`). The console's table shows each inverse collection as a count (line 301). Over REST the count is the length of the list of references already in the record. Over GraphQL, asking for the collection in order to count it reads every record in it. No work is proposed yet. The work item in §4 measures it first.
- **Finding G3.** A member typed by a union of shapes is a GraphQL union, which has no fields of its own, so a display for it is asked per shape with `... on Customer { name }`. The console's query builder has to write that. No decision needed.

- **Finding G4.** The model description loses whether a shape's member is a reference. `Describe.shape` writes a member's `kind` twice: first `value`, `values`, `reference`, or `references` from its type, then `stored`, `derived`, `inverse`, and so on over the same key (`apis/json/src/main/kotlin/velle/api/Describe.kt`, lines 104 to 112). An act's field keeps the first. The rebuilt console decides that a member is a reference by finding its type among the shapes (`console/src/graphql/documents.ts`, `memberSelection`). A work item in §4.

Roster of labels in this file: D1 to D5 are the decisions in §2. G1 to G4 are the four findings above.

## 2. What to decide

### D1. The console becomes a React project of its own in this repository, built into the starter's jar · Decided 2026-11-19

Does the console move out of the single HTML file into a React and TypeScript project at `console/` in the repository root, which Gradle builds and places in the starter's jar where `console.html` is today?

Recommendation: yes. Dave's note asks for React components that another React application could take, and that needs a project with a build.

Costs:

- Building Velle starts to need Node. Today `./gradlew build` needs only a JDK. The Gradle Node plugin can download Node itself, so a developer installs nothing by hand, but the build is slower and has a second dependency tree to keep current.
- The console can no longer be edited as one file and reloaded.
- The alternative is to commit the built files so the Gradle build stays JDK-only. That puts generated files in git and lets them drift from their source. Not recommended.
- TypeScript rather than plain JavaScript is part of the recommendation, because a component another application takes is used through its declared properties, and TypeScript is how a React component declares them.
- Nothing changes for a spec or for what Velle can prove.

#### Proposed changes

- `console/`: new, a React and TypeScript project with its own `package.json`.
- `settings.gradle.kts`, `apis/spring/build.gradle.kts`: the starter's build runs the console's build and takes its output as resources.
- `apis/spring/src/main/resources/velle/console.html`: deleted once the React console does everything it does.
- `apis/spring/src/main/kotlin/velle/api/spring/VelleConsole.kt`: serves the built page and its script files instead of one string.
- `wiki/Guides/Serving-a-system.md`, "The console", and `wiki/Guides/API-layer.md`: name the new source.

**Decided:** yes (Dave, 2026-11-19).

### D2. The first thing built is the console as it is today, in React over GraphQL, with nothing new in it · Decided 2026-11-19

Is the first slice a rebuild that does exactly what `console.html` does today, and configuration starts only after that lands?

Recommendation: yes. The rebuild changes the framework and the transport at once. Keeping behaviour fixed means the old page is the test of the new one, view by view.

Costs:

- Dave sees no new feature until the second slice. The display of a `Customer` as its name waits on the rebuild.
- The alternative is to add the display to `console.html` now, over REST, and rebuild later. That work is thrown away by the rebuild.

The slices after it, in the order of Dave's note. Only the first two are designed in this file.

1. The rebuild (D1, D2, D3).
2. Configuration, with a shape's display first (D4, D5).
3. Choosing a referenced record in an act's form from the records that exist, with configuration of how (a list, a search box).
4. The console as a starting point: an engineer adds menu items that show components of their own.
5. One console component placed in an engineer's existing React application.

One rule holds from the first slice on, from Dave's note: a component that shows a shape, a record, or an act is given everything it needs as React properties, the model description and the means to reach the API, and imports nothing from the console's own frame (the sidebar, the routes, the request-header box). Slices 4 and 5 are then two uses of the same components.

**Decided:** yes, and build it now (Dave, 2026-11-19).

### D3. GraphQL serves the model description, so the console makes no REST request

Does the GraphQL schema gain one field that answers the same model description `GET /api` answers, as one JSON value?

Recommendation: yes, as a field `model` on the query type, typed by the `JSON` scalar the schema already has (`VelleGraphQL.kt`, line 155).

Costs:

- One more GraphQL name a spec may not use. A spec with a readable shape named `Model` would want the field `model` for its fetch by key, and the schema builder would refuse it as it refuses `Tick` today. That is OQ148's question grown by one name.
- The description is one untyped JSON value, not GraphQL types, so a GraphQL client generator learns nothing from it. Typing it is more schema to build and to keep in step with `Describe.kt`. Not proposed now.
- The first alternative is to read the description from GraphQL introspection. Finding G1 lists what that lacks; each lack would become a custom directive or a description string to parse.
- The second alternative is to keep one REST request for the description and use GraphQL for everything else. It costs nothing in Velle, and it means a component placed in another application needs two addresses.
- Nothing changes for a spec or for what Velle can prove.

#### Proposed changes

- `apis/graphql/src/main/kotlin/velle/api/graphql/VelleGraphQL.kt`: a `model` field on the query type answering `Describe.describe()`, and `Model` added to the names the builder refuses a spec's shape.
- `apis/graphql/src/test/kotlin/velle/api/graphql/VelleGraphQLTest.kt`: a test that the field answers what `GET /api` answers, and a test of the refusal.
- `wiki/Guides/Serving-a-system.md`, "GraphQL": the field.
- `working-docs/QUESTIONS.md`, OQ148's line: the name `model` added.

### D4. The console's configuration is the engineer's, kept beside the application, and never in the spec

Is the configuration one object that the engineer writes apart from the `.velle` file, which the starter's console reads from a JSON file in the application and which an engineer's own React code hands to the same components directly?

Recommendation: yes. The starter looks for `velle/console.json` in the application's resources and serves it to the page; with no such file the console is as it is today. An engineer who builds a frontend of their own passes the same object in code, where it may also hold React components, which a JSON file cannot.

Costs:

- The compiler never sees the configuration. A configuration naming a member the shape does not have is found when the console loads, checked against the model description, and not when the spec compiles. The principle that a design in which a wrong state cannot be represented beats one in which it is checked leans against this; the console checking at load is the mitigation.
- A spec renamed a member breaks the configuration silently until the console is next opened.
- The alternative is to say it in the spec, a `display` clause on the shape. That puts a frontend concern into the language that captures the product-owner and engineer conversation as a pure business layer, and it could never hold a React component, which slices 4 and 5 need. Not recommended.
- What a product owner can say in Velle does not change. See D5 for the one overlap, a derived property.

#### Proposed changes

- `console/`: the configuration's TypeScript type, and the check of a configuration against the model description at load, shown in the page when it fails.
- `apis/spring/src/main/kotlin/velle/api/spring/VelleConsole.kt`: serves the application's `velle/console.json` when present.
- `wiki/Guides/Serving-a-system.md`, "The console": the file and what it may say.

### D5. A shape's display is a text with members in it, and without one a record reads as it does today

Is a shape's display written as a text with `${...}` pieces, each piece a member of the shape or a path through its references, and does a shape with no display keep reading `Customer#3`?

Recommendation: yes to both. The spelling is the one Reference §5 gives a text made from other values, limited to member paths. Anything more than a path, a condition or arithmetic, is a derived property in the spec, and the display names it.

The spec, from `examples/billing/billing.velle`, shapes `Customer` and `Invoice`, unchanged by this decision:

```
-- no `uses` line: Money is an extension type from the velle.finance library on the classpath, in scope by name

shape Customer {
    name: text                                -- stored; the console may put it in a display
    email: text                               -- stored
    signedUpOn: timestamp on create           -- set by the runtime when the record is created
    largestPayment: Money initially 0         -- stored, starts at 0
}

shape Invoice {
    customer: one Customer                    -- a reference; the console shows it by Customer's display
    due: Date                                 -- stored
    reference: text initially randomUUID      -- stored, set once at creation
    issuedOn: timestamp on create             -- set by the runtime
    total: Money = sum(lineItems, amount)     -- derived; computed on read
    balance: Money = total - sum(payments, amount)  -- derived
    status: Status = if balance <= 0 then "paid" else if due < today then "overdue" else "open"  -- derived
}
```

The configuration, `velle/console.json` in the application:

```
{
  "shapes": {
    "Customer": { "display": "${name}" },
    "Invoice":  { "display": "${reference} for ${customer.name}" }
  }
}
```

With it, an invoice's `customer` cell reads "Ada Lovelace" and links to the record, and an invoice's page is titled by its reference and its customer's name. The console builds each GraphQL request to ask for the members the displays in view name, and no others, which is the use of GraphQL Dave's note expects.

Costs:

- The console holds a reader for `${path}` texts, a small second implementation of something the compiler already reads. Kept to paths so it cannot drift far.
- A display that reaches through a reference makes every table row cost one more record read on the server. The engineer chooses that by writing the path.
- Two records may read the same, two customers named alike. The key no longer tells them apart on screen unless the display includes it. The console could always show the key beside the display; this recommendation does not, because the link still carries it.
- The alternative spelling is to name one member, `"display": "name"`, and require a derived property in the spec for anything joined. That moves a presentation choice into the spec for every shape that wants two fields side by side.
- The alternative default is the shape's first text member in place of `Customer#3`. It guesses, and for `Invoice` above it would guess `reference`, a random identifier. Not recommended.
- Nothing changes in what Velle can prove.

#### Proposed changes

- `console/`: the display reader, its use wherever a reference or a record's title is shown, and the request builder asking for the members a display names, with `... on` per shape for a union (finding G3).
- `examples/billing/app/src/main/resources/velle/console.json`: new, the configuration above, so one example shows it.
- `wiki/Guides/Serving-a-system.md`, "The console": the display.

## 3. The build

### The first slice, built 2026-11-19 (D1, D2)

- `console/` is the React and TypeScript project. `console/src/components/` holds the four views, `ShapeTable`, `RecordView`, `ActForm`, `ScheduleView`. Each takes the model description, a client, and where a record's page is as React properties, and imports nothing from `console/src/frame/`, which holds the sidebar, the routes, and the request-header box.
- `console/src/graphql/documents.ts` builds every GraphQL request from the model description. `console/src/acts/actInput.ts` turns what was typed in an act's form into the mutation's input.
- `console/build.gradle.kts` is the Gradle project `:console`. It downloads Node, and `buildConsole` writes one file, `console/dist/index.html`. `apis/spring/build.gradle.kts` copies it into the starter's jar as `velle/console.html`, and `VelleConsole.kt` fills in the API path, the GraphQL path, and the ticks flag.
- `apis/spring/src/main/resources/velle/console.html` is deleted.
- Tests: `console/src/**/*.test.ts(x)`, 32 tests, run by `./gradlew :console:test`. `VendorsOverHttpTest`, "the console is served, told the api path, the graphql path, and that ticks are hidden", and `BillingOverSqliteTest` pin the served page.

Where the rebuilt console differs from the page it replaced. Each is forced by GraphQL or is a fault of the old page:

- The model description is still read from `GET /api`, the one REST request left, because D3 is not decided. With D3 answered yes it moves to GraphQL.
- A field that may carry a new record, and an act's parts, are typed as JSON in the form GraphQL's input takes, `{"newAddress": {...}}`, where the old page took REST's form, `{"@shape": "Address", ...}`.
- A reference that may point at a record created in the same act can be typed as `{"new": 1}`. The old page's hint offered that and its reader sent the text as a key.
- A part that may be of several shapes shows a placeholder. The old page threw on it (`renderPart` read `part.fields`, which such a part does not have).
- A key reads as GraphQL answers it, always text. Nothing on screen changes.
- A record that does not exist reads "There is no Invoice with the key 99, or it is not shown to the caller", where REST answered 404 with its own sentence.

Not checked: nobody has looked at the rebuilt page in a browser. The component tests render it without styles. The requests were checked against the running pharmacy application for every shape, refinement, record, and act input type, by scripts that were not kept.

### The later slices

Written when D3, D4, and D5 are decided.

## 4. Work items

- [ ] Look at the rebuilt console in a browser against the pharmacy and billing applications, view by view beside the old page at commit `f1bd661`, and fix what differs.
- [ ] Decide what the model description says of a shape's member that is a reference, now that `kind` holds only where the value comes from (finding G4).
- [ ] Measure what the shape table costs over GraphQL when it asks for each inverse collection to show its count (finding G2), on the pharmacy application's seeded data, and decide whether the schema needs a count field per collection before the rebuild's table is built.
