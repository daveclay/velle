# OQ173 — How does a person reach a running Velle system from an AI chat session?

**Status:** opened 2026-11-25 from Dave's note in chat. Every decision decided; the build plan in §3 awaits Dave's review. Nothing built.

**In plain terms:** someone using an AI assistant such as Claude should be able to ask it "which invoices are overdue?" or "record a payment of $40 against invoice 12", and the assistant does it against the live system, under that person's own permissions, and tells them in the business's words when the system refuses.

**See:** `wiki/Guides/Serving-a-system.md` and `wiki/Guides/API-layer.md` for the API layer this sits on; `apis/CLAUDE.md` for the rules every transport keeps; `apis/json/src/main/kotlin/velle/api/VelleApi.kt` for the operations; `apis/spring/src/main/kotlin/velle/api/spring/ActorResolver.kt` for how a caller becomes an actor; OQ110, which asks whether the model description becomes an OpenAPI document, a neighbouring question about what a client generator reads; OQ174 for a second server, for authoring a spec rather than running one.

---

## 1. What runs today

Terms used in this file:

- **MCP** (Model Context Protocol) is the open protocol AI chat clients use to call tools on a server. A server offers *tools* (operations the assistant may call, each with a name, a description, and a JSON Schema for its input) and *resources* (documents the client may attach to the conversation). Claude, ChatGPT, and most coding assistants speak it. It runs over standard input and output for a local process, or over HTTP for a remote server, where sign-in uses OAuth.
- **UTCP** (Universal Tool Calling Protocol) is a younger alternative that describes an existing HTTP API so a client calls it directly, with no server in between. Few chat clients support it.

What a running system offers today, checked against HEAD on 2026-11-25:

- `apis/json/src/main/kotlin/velle/api/VelleApi.kt` is the one set of operations every transport maps onto: `commit`, `read`, `readEach`, `query`, `count`, `tick`, `latestTick`, and `description`. It names no transport.
- `commit` answers `{"accepted": false, "reason": ..., "rejection": {...}}` when a `never` refuses the act. A refusal is data, not an error. Malformed input is an `ApiException` of kind `BAD_REQUEST`, an unknown record `NOT_FOUND`, a missing actor `UNAUTHORIZED`.
- `apis/json/src/main/kotlin/velle/api/Describe.kt` describes every exposed act (its fields, its parts, its actor) and every readable shape. That is enough to build a JSON Schema per act.
- `apis/spring/src/main/kotlin/velle/api/spring/ActorResolver.kt` turns an authenticated `HttpServletRequest` into the actor. Velle never authenticates; the application's own security filter does.
- A spec with no authorization serves the engineer's view: every read sees everything, and every act is committed with no actor. `Model.hasAuthorization` (`compiler/src/main/kotlin/velle/Model.kt`) is true once any act declares `by` or any `visible to` is written. `examples/billing/billing.velle` declares neither; `examples/pharmacy/pharmacy.velle` declares both.
- Comments do not reach the model. `compiler/src/main/kotlin/velle/Lexer.kt` skips everything from `--` to the end of the line, so the business story in the comments of `examples/billing/billing.velle` is not available to anything built from the model.
- No operation evaluates an act without committing it. The runtime already undoes a refused act's changes before anything reaches the store (`rollback` in `compiler/src/main/kotlin/velle/Envelope.kt`), which is what a dry run would reuse.
- Ticks over REST and GraphQL are for development only (`apis/CLAUDE.md`). This OQ follows that rule: no tick tool is proposed.

Size of the tool list, if every exposed act became a tool (counted by `grep -cE '^(transient )?act '` over each spec on 2026-11-25): `examples/billing/billing.velle` has 9 acts, `examples/peptides/peptides.velle` 31, `examples/pharmacy/pharmacy.velle` 30.

## 2. What to decide

### D1. Velle offers an MCP server, part of the API layer, that calls `VelleApi` directly · Decided 2026-11-25

Does Velle offer AI chat access as an MCP server in a new module, `apis/mcp`, served by the Spring Boot starter at a configured path beside REST and GraphQL, calling `VelleApi` directly rather than going through the GraphQL endpoint?

Recommendation: yes.

- MCP over UTCP: the clients people actually chat in speak MCP. UTCP would only describe the REST surface, so it adds no reach and the refusal would arrive as an HTTP 422 the client treats as a failure.
- Over `VelleApi`, not over GraphQL: `VelleApi` is the layer every transport maps onto (`apis/CLAUDE.md`). Going through GraphQL adds a network hop and a second translation, and a refusal would come back as a GraphQL error unless every tool unpicked it.
- In the starter, at `velle.mcp.path`: the application that declares the system bean gets the MCP server with no code, as it gets REST and GraphQL. It is off unless the path is set.
- A local bridge, for a desktop client that only runs local processes, can come later as a small process that forwards to a remote system's MCP path. It is not part of this decision.

Costs:

- A new dependency on an MCP library for the JVM. Which one (the official MCP Java or Kotlin SDK, or Spring AI's MCP server) is chosen at build time against what Spring Boot 4 supports, and recorded in `apis/CLAUDE.md`.
- One more transport to keep consistent with REST and GraphQL as `VelleApi` grows. Leans on the principle that Velle codes to its own interfaces: the new module sees only `VelleApi`, `Describe`, and `Wire`.
- Nothing changes in what Velle can say or prove. The spec is untouched.

#### Proposed changes

- `apis/mcp`: a new module mapping MCP tool calls and resource reads onto `VelleApi`.
- `apis/spring`: serves the MCP server when `velle.mcp.path` is set, using the same `ActorResolver` and the same `api.scope {}` per call.
- `wiki/Guides/Serving-a-system.md`: a section on reaching the system from an AI chat client, with the billing application's configuration.
- `wiki/Guides/API-layer.md`: MCP named as a fourth transport.
- `apis/CLAUDE.md`: the MCP library chosen and the facts that cost time building it.

**Decided:** yes, 2026-11-25. Dave accepted the recommendation as written.

### D2. Each exposed act is a tool of its own, and reads are three generic tools · Decided 2026-11-25

Does every exposed act become its own MCP tool, named for the act with its fields as the input schema, while reading is three tools shared by every shape (`read`, `query`, `count`) and the model description is a resource?

Recommendation: yes.

The assistant chooses a tool by its name and description. A tool named `HireStaff` with fields `name`, `email`, `identityId`, `role` is chosen correctly far more often than `commit(act: "HireStaff", body: {...})`, where the assistant has to look the act up first and the schema of `body` cannot say what it holds. Shapes are a different matter: `examples/pharmacy/pharmacy.velle` has 86 `shape` declarations, and a read tool per shape would bury the acts.

The act from `examples/pharmacy/pharmacy.velle` and the tool it becomes:

```velle
-- examples/pharmacy/pharmacy.velle
Role is one of "pharmacist", "technician", "admin"   -- an enumeration: the tool's `role` input is a JSON Schema enum of these three texts

shape Staff {                                        -- a persistent shape: readable through `read`, `query`, `count` with root "Staff"
    name: text
    email: text unique
    identityId: text unique
    role: Role
}

shape Administrator = Staff where role == "admin"    -- a refinement: the actor HireStaff demands

transient act HireStaff by Administrator {           -- an exposed act: becomes the tool `HireStaff`; `by` means the tool is refused without an actor
    name: text                                       -- input property `name`, a string, required
    email: text                                      -- input property `email`, a string, required
    identityId: text                                 -- input property `identityId`, a string, required
    role: Role                                       -- input property `role`, one of the three texts above, required
}

rule RecordHire when HireStaff {                     -- runs inside the commit; named in the tool's description (D3)
    Staff from { name: name, email: email, identityId: identityId, role: role }
}
```

The alternative is one generic `commit` tool whose input is the act's name and a free-form body, with the acts listed in a resource. It keeps the tool list at five for any spec.

Costs:

- `examples/peptides/peptides.velle` would offer 31 act tools plus 3 read tools. Some clients limit how many tools a server may offer, or degrade as the list grows. A spec that large may need a way to choose which acts are offered, which this decision does not provide.
- `query` takes `where` as a Velle predicate string (`apis/CLAUDE.md`). The assistant has to write Velle predicates, so the tool's description must teach the predicate syntax and name the readable shapes and their fields. A predicate the compiler refuses comes back with the compiler's message, as on REST, and the assistant can correct it.
- Nothing changes in what Velle can say or prove.

#### Proposed changes

- `apis/mcp`: one tool per act in `VelleApi.acts`, its input schema built from `Describe.act`; tools `read`, `query`, `count` over `VelleApi.readable`; the resource `velle://model` serving `VelleApi.description`.
- `apis/mcp` tests: the tool list and schemas for `examples/pharmacy/pharmacy.velle`, pinned.

**Decided:** yes, separate tools, 2026-11-25. Dave agreed after the reasons were laid out in chat: the assistant chooses a tool by its name and description, a tool per act carries an exact input schema, clients ask the person's permission per tool so an act can need approval while reads do not, and reads differ only by shape while acts differ in meaning.

### D3. A tool's description is generated from the model, not from the spec's comments · Decided 2026-11-25

Is an act tool's description written by the compiler from what the model already knows about the act: its fields, the prohibitions that refuse it at the door, the actor it demands, and the rules it starts, rather than taken from comments in the spec?

Recommendation: yes, generated from the model.

`compiler/src/main/kotlin/velle/Consequences.kt` already computes, for the comparison report of OQ170, the prohibitions checked at the door (`SpecFacts.checkedAtTheDoor`) and which rules each act may start (`SpecFacts.mayFire`). The description of `SubmitPayment` would read from these facts:

```velle
-- examples/billing/billing.velle
transient act SubmitPayment {                        -- becomes the tool `SubmitPayment`
    invoice: one Invoice                             -- input property `invoice`: the key of an Invoice record
    amount: Money                                    -- input property `amount`: a number, from the velle.finance extension type
}

never (SubmitPayment where amount <= 0)              -- a refusal checked at the door: the description says "refused when amount <= 0"

shape Payment {                                      -- the record the act leads to
    invoice: one Invoice
    amount: Money
    receivedOn: timestamp on create
}

rule RecordPayment when SubmitPayment {              -- the description says "starts rule RecordPayment, which creates a Payment"
    Payment from { invoice: invoice, amount: amount }
}
```

The alternative is doc comments: a comment form the lexer keeps, attached to the declaration below it. The comments in `examples/billing/billing.velle` already tell the business story ("Payments land against an invoice; zero and negative amounts are refused at the door"), and the assistant would read that story.

Costs of generating from the model:

- The description says what the act does in terms of the spec's names, not why the business wants it. The assistant can still read the spec's text if it is offered as a resource (D3's proposed changes include that).
- Nothing in the language changes, and the description cannot drift from the rules, because it is derived from them. Leans on the principle that a wrong state that cannot be represented beats one that is checked: a hand-written description can say the wrong thing; a derived one cannot.

Costs of doc comments:

- A language change: comments become part of the model, the Reference gains a form, and every example is rewritten to use it.
- The text can contradict the rules, and nothing can check it.

#### Proposed changes

- `compiler`: a function that writes an act's description from `SpecFacts` (door refusals, actor, rules started, records created), shared with the comparison report where the sentences already exist.
- `apis/mcp`: each act tool's description is that text; the resource `velle://spec` serves the spec's source, comments included.
- `apis/mcp` tests: the description of `SubmitPayment` from `examples/billing/billing.velle`, pinned.

**Decided:** yes, generated from the model, 2026-11-25. Dave accepted the recommendation as written; the spec's source, comments included, is still served as the resource `velle://spec`.

### D4. A refusal is a successful tool result that carries the rejection · Decided 2026-11-25

When a `never` refuses an act called through MCP, is the answer a normal tool result carrying `accepted: false` and the rejection, while malformed input, an unknown record, and a missing actor are tool errors?

Recommendation: yes.

This is the API layer's existing rule, a refusal is data (`apis/CLAUDE.md`), carried into MCP. MCP has two ways to answer a failed call: a protocol error, which many clients hide or retry, and a result marked as an error, which the assistant reads. A refusal is neither a fault of the call nor of the server. It is the system's information, and the assistant should tell the person what was refused and why.

Costs:

- Leans on the principle that a rejection must reach a human with what was rejected and why. The assistant is the one carrying it to the human, so the result must contain the rejection's rendered sentence, not only its data. `VelleApi.commit` already answers one as `reason`, the rejection's rendered message (`Rejection.message`, set in `compiler/src/main/kotlin/velle/ActCommitter.kt`).
- An assistant could still paraphrase the refusal badly. Velle cannot prevent that.

#### Proposed changes

- `apis/mcp`: `CommitResult.Refused` maps to a successful result holding `VelleApi.commit`'s answer, with `reason` as the result's text; `ApiException` maps to a result marked as an error, with its message and details.
- `apis/mcp` tests: `SubmitPayment` with `amount: 0` against `examples/billing/billing.velle` answers a successful result whose text names the prohibition.

**Decided:** yes, 2026-11-25. Dave accepted the recommendation as written.

### D5. The MCP server refuses to start for a spec without authorization, unless the configuration says otherwise · Decided 2026-11-25

Does the starter refuse to start the MCP server for a spec that declares no authorization, unless `velle.mcp.unauthenticated: true` is set?

Recommendation: yes.

A spec without authorization serves the engineer's view: everything is readable and every act is committed with no actor. Over REST that is a developer's choice made in code. Over MCP it hands every record and every act to whoever connects an assistant, and an assistant acts on instructions it reads, including instructions planted in a record's text. For a spec that declares authorization, nothing new is needed: the starter's `ActorResolver` runs on the MCP request as it does on a REST request, and the application's security filter does the sign-in, OAuth in MCP's case.

Costs:

- `examples/billing/billing.velle` declares no authorization (`Model.hasAuthorization` is false for it), so the billing application needs the flag to try MCP at all. That is the intended friction.
- Leans on the principle that a binding is a library the owner of the spec chooses: Velle does not refuse the configuration, it makes the choice explicit.
- A spec with authorization but an `ActorResolver` that returns null for MCP requests gets the existing answer: acts declared `by` and every read are refused as `UNAUTHORIZED`.

#### Proposed changes

- `apis/spring`: at startup, when `velle.mcp.path` is set and `model.hasAuthorization` is false, fail with a message naming the spec and the flag, unless `velle.mcp.unauthenticated` is true.
- `wiki/Guides/Serving-a-system.md`: the flag and why it exists.
- `apis/spring` tests: the billing application refuses to start with the MCP path set and no flag, and starts with it.

**Decided:** yes, 2026-11-25. Dave accepted the recommendation as written.

### D6. Each act tool can be asked whether the act would be accepted, without committing it · Decided 2026-11-25

Does the API layer gain a dry run, `VelleApi.check(act, body, actor)`, which runs the act through its rules and prohibitions, answers accepted or refused exactly as `commit` does, and then undoes everything without writing to the store, offered over MCP as a `dryRun` input on each act tool?

Recommendation: yes.

An assistant acting for a person should be able to say "this payment would be refused because the amount is zero" before the person confirms. The runtime already undoes a refused act's working-set changes before the store sees them (`rollback` in `compiler/src/main/kotlin/velle/Envelope.kt`); a dry run takes the same path after the rules have run, accepted or not.

Costs:

- A dry run says what would happen now. Another commit can land between the check and the real commit, so an accepted dry run is not a promise, and its answer must say so.
- `after commit` rules run outside the act's transaction. A dry run cannot show what they would do, and must say that it covers only what runs inside the commit.
- It is a read of the books that takes the same queue keys as a commit, so it waits behind commits on the same records and they wait behind it.
- Once it exists, REST and GraphQL should offer it too, or the transports drift apart. That widens this OQ beyond MCP.

#### Proposed changes

- `apis/json`: `VelleApi.check`, running the commit through the envelope and abandoning the transaction instead of writing the commit set.
- `compiler`: whatever the envelope needs to abandon an accepted transaction, which today it only does for a refused one.
- `apis/mcp`: a `dryRun` boolean input on every act tool.
- `apis/spring`: `POST /api/<act>?dryRun=true` and a GraphQL argument, if the transports are kept level.
- Tests: a dry run of `SubmitPayment` against `examples/billing/billing.velle` leaves no `Payment` in the store and answers as the real commit does.

**Decided:** yes, 2026-11-25. Dave accepted the recommendation as written.

## 3. The build

The decided decisions' proposed changes, in build order. The tag after each step names the decision it comes from.

1. `compiler/src/main/kotlin/velle/Envelope.kt`: abandon an accepted transaction on request, undoing the working set as a refused one is undone, so nothing reaches the store (D6).
2. `compiler`: a function that writes an act's description from `SpecFacts` in `compiler/src/main/kotlin/velle/Consequences.kt`: the act's fields, the prohibitions checked at the door, the actor it demands, the rules it may start, the records they create (D3).
3. `compiler/src/main/kotlin/velle/Codegen.kt`: the generated system offers its spec's source, which it holds today only as the private `SPEC` (`examples/billing/output/src/main/kotlin/velle/generated/Billing.kt`), so the MCP server can serve it (D3). Regenerate every example's output.
4. `apis/json/src/main/kotlin/velle/api/VelleApi.kt`: `check(act, body, actor)`, answering as `commit` does and writing nothing (D6).
5. Pick the MCP library for the JVM against Spring Boot 4 and record it in `apis/CLAUDE.md` (D1).
6. `apis/mcp`, a new module over `VelleApi`, `Describe`, and `Wire` only (D1):
   - one tool per act in `VelleApi.acts`, its input schema from `Describe.act`, its description from step 2 (D2, D3);
   - a `dryRun` boolean input on every act tool, calling step 4 (D6);
   - tools `read`, `query`, `count` over `VelleApi.readable`, the description of `query` teaching the `where` predicate syntax (D2);
   - resources `velle://model`, serving `VelleApi.description`, and `velle://spec`, serving the spec's source from step 3 (D2, D3);
   - a refusal answered as a successful result holding `commit`'s answer with `reason` as its text; an `ApiException` answered as a result marked as an error with its message and details (D4).
7. `apis/spring` (D1, D5, D6):
   - serve the MCP server when `velle.mcp.path` is set, with the same `ActorResolver` and `api.scope {}` per call as REST and GraphQL;
   - at startup, fail when `velle.mcp.path` is set, `model.hasAuthorization` is false, and `velle.mcp.unauthenticated` is not true, naming the spec and the flag;
   - `POST /api/<act>?dryRun=true` and a GraphQL argument on each act's mutation, so the three transports stay level.
8. `examples/billing/app/src/main/resources/application.yaml`: `velle.mcp.path` and `velle.mcp.unauthenticated: true`, since `examples/billing/billing.velle` declares no authorization (D1, D5).
9. Tests:
   - `apis/mcp`: the tool list and input schemas for `examples/pharmacy/pharmacy.velle` (D2); the description of `SubmitPayment` from `examples/billing/billing.velle` (D3); `SubmitPayment` with `amount: 0` answering a successful result whose text names the prohibition (D4).
   - `apis/spring`: the billing application refusing to start with the MCP path set and no flag, and starting with it (D5).
   - `apis/json`: a dry run of `SubmitPayment` leaving no `Payment` in the store and answering as the real commit does (D6).
10. Wiki:
    - `wiki/Guides/Serving-a-system.md`: a section on reaching the system from an AI chat client, with the billing application's configuration; the `velle.mcp.unauthenticated` flag and why it exists; the dry run on REST and GraphQL (D1, D5, D6).
    - `wiki/Guides/API-layer.md`: MCP named as a fourth transport; `check` beside `commit` (D1, D6).
11. The settling commit: the settled row for OQ173 in `working-docs/QUESTIONS.md`, this file deleted. OQ174 stays open.

## 4. Work items

- [ ] Let the envelope abandon an accepted transaction without writing it (§3 step 1, D6).
- [ ] Write an act's description from `SpecFacts` (§3 step 2, D3).
- [ ] Have the generated system offer its spec's source and regenerate the examples (§3 step 3, D3).
- [ ] Add `VelleApi.check` (§3 step 4, D6).
- [ ] Pick the MCP library for the JVM against Spring Boot 4 and record it in `apis/CLAUDE.md` (§3 step 5, D1).
- [ ] Build `apis/mcp`: act tools, `dryRun`, read tools, resources, refusals as results (§3 step 6, D2 to D4, D6).
- [ ] Serve MCP from the starter, refuse a spec without authorization unless flagged, add the dry run to REST and GraphQL (§3 step 7, D1, D5, D6).
- [ ] Configure MCP in the billing application (§3 step 8).
- [ ] Write the tests of §3 step 9.
- [ ] Update `wiki/Guides/Serving-a-system.md` and `wiki/Guides/API-layer.md` (§3 step 10).
