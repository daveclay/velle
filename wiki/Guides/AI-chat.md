# Using a system from an AI chat

A running Velle system can be used from an AI chat client, such as Claude, in plain words: "which invoices are overdue?", "record a payment of 40.00 against invoice 12". The assistant reads the records and commits the acts for you, through the system's [MCP](GLOSSARY) server, and the spec's rules apply to every act exactly as they do over REST. When a rule refuses an act, the assistant tells you which rule and why. After reading this page you can connect a chat client to a system, know what the assistant can and cannot do there, and know what the person running the system has to set up so that the assistant acts as you.

## The situation

The billing team's invoicing system, `examples/billing/billing.velle`, runs behind the billing application. Someone in finance wants to ask about overdue invoices and record the payments that arrive, without learning the API or opening the console. They already use an AI assistant at work.

## Turning the server on

The engineer running the application sets one property, `velle.mcp.path`, in `examples/billing/app/src/main/resources/application.yaml`:

```yaml
server:
  port: 8090
velle:
  mcp:
    path: /mcp
    unauthenticated: true
```

- The server answers at `http://localhost:8090/mcp` while the application runs on the engineer's machine, and at the same path on the application's own address once it is deployed.
- `unauthenticated: true` is there because the billing spec says nothing about who may commit or see what ([Authorization](Authorization)). Anyone who connects can then read every record and commit every act. A spec that declares who may do what needs no such setting, and a spec that declares nothing stops the application at startup without it.

[Serving a system, "An AI chat session"](Serving-a-system#an-ai-chat-session) has the rest of the server's side.

## Connecting a chat client

A chat client is pointed at the server's address once, and from then on every conversation can use the system.

**Claude Code**, on the same machine as the application or able to reach it:

```bash
claude mcp add --transport http billing http://localhost:8090/mcp
```

- `billing` is the name the tools appear under in the session. `claude mcp list` shows whether it connected, and `/mcp` inside a session lists its tools.
- A system that reads who is calling from a header takes it with `--header`, as `--header "X-Velle-Actor: Staff:1"` does for the vendors test application (`apis/spring/src/test/kotlin/velle/api/spring/VendorsApp.kt`). "Acting as you", below, says which header a system reads.
- `--scope project` writes the server into `.mcp.json` at the root of the project, so everyone who works in the project's folder gets it:

```json
{
  "mcpServers": {
    "billing": {
      "type": "http",
      "url": "${BILLING_MCP_URL:-http://localhost:8090/mcp}"
    }
  }
}
```

- `${NAME:-default}` reads an environment variable, so each person can point at their own copy of the application without editing the file.
- A system that reads a header takes `"headers": {"X-Velle-Actor": "${VELLE_ACTOR}"}` beside `url`, so each person sends their own identity. Billing reads no header.
- A server that answers 401 is listed as needing sign-in. `/mcp` in a session starts the sign-in in the browser, when the application offers one ("Acting as you", below).

**Claude in the browser and Claude Desktop** add a system as a custom connector: under Customize, Connectors, add a custom connector with the server's address. On a Team or Enterprise plan an owner adds it for the organization, and each member connects to it. Claude reaches the server from Anthropic's own network, so the address must be the application's public HTTPS address. A system on `localhost`, or reachable only inside a company network, cannot be added this way. Use Claude Code for it instead. A connector can sign you in, or send a fixed header such as a token.

## A conversation

What follows is what the assistant does with the billing system when you talk to it. The tool calls are shown as the assistant sends them; you only see your own words and its answers.

**"Which invoices are overdue?"** The `query` tool lists every shape and [refinement](Reference-08-Refinements) the assistant may read. Among them is `OverdueInvoice`, declared in `examples/billing/billing.velle` as `Invoice where balance > 0 and due < today`, so the assistant calls:

```
query {"shape": "OverdueInvoice", "orderBy": ["due"], "fields": ["customer", "due", "balance"]}
→ {"items": [{"shape": "Invoice", "key": 12, "customer": {"shape": "Customer", "key": 3},
              "due": "2026-10-01", "balance": "1200.00"}], "next": null}
```

Each record names its own shape, `Invoice`, and its key. The assistant answers with the invoice, and can read the customer with `read` to name them.

**"Record a payment of 40.00 against invoice 12."** The assistant finds the act `SubmitPayment`, whose tool takes `invoice` and `amount`. It can first ask for a [dry run](GLOSSARY): whether the payment would be accepted, without recording anything:

```
SubmitPayment {"invoice": 12, "amount": "40.00", "@dryRun": true}
→ {"accepted": true}
```

Then, once you say yes, it commits it:

```
SubmitPayment {"invoice": 12, "amount": "40.00"}
→ {"accepted": true, "ref": null}
```

`ref` is null because a payment request is a [transient act](Reference-04-State-and-commits): the system keeps the `Payment` its rule records, not the request.

**"Record a payment of 0 against invoice 12."** The spec refuses it with a [`never`](Reference-21-never), `never (SubmitPayment where amount <= 0)`, and the tool's answer begins with the refusal's own words:

```
SubmitPayment refused: this SubmitPayment would be a SubmitPayment where amount <= 0.
  never #3: SubmitPayment where amount <= 0
  this SubmitPayment: invoice = Invoice#12, amount = 0.00
  ...
```

`never #3` is the spec's third `never`. The lines after it list the values that broke it.

The assistant tells you the payment was turned away because an amount must be more than zero. Nothing was recorded.

## What the assistant sees

- **One tool per act**, named for the act. Its input is the act's fields, each required unless the spec declares it optional. Its description is written from the spec's rules: who commits the act, what refuses it, which rules it can start, and what those create.
- **`read`, `query`, and `count`**, shared by every shape. `query` takes a `where` in the spec's own language ([§10](Reference-10-Predicate-expressions)), such as `balance > 1000 and due < today`, and its description teaches the assistant that language. These three only read, and say so, so a client can let them run without asking you each time.
- **Two documents** the client may attach to the conversation: `velle://model`, every act and shape with its members, and `velle://spec`, the spec as its authors wrote it, comments included. The comments are where the business story is, so an assistant that reads them can explain why a rule exists.
- **`"@dryRun": true`** on any act's tool, which answers what a commit would answer now and keeps nothing. Another act can land between the dry run and the real one, so the answer is what would happen now, not a promise.

What it does not see: anything the spec does not declare. There is no tool for running a schedule, no way to change a record except through an act, and no way around a `never`. A record or a field the spec hides from you is hidden from the assistant acting for you, because it reads as you.

## Acting as you

Every call the assistant makes is one HTTP request to the application, and the application's [actor resolver](GLOSSARY) decides who it is from, exactly as for a REST call ([Serving a system, "Who is calling"](Serving-a-system#who-is-calling)). Velle never signs anyone in. What the client sends depends on how the application knows people:

- **On an engineer's machine**, a resolver that reads a header, such as `X-Velle-Actor: Staff:1` in `apis/spring/src/test/kotlin/velle/api/spring/VendorsApp.kt`, lets a client act as any staff record by sending that header. Use it to try an act declared `by` ([Authorization](Authorization)), never in a deployment.
- **Behind an identity provider**, as in `examples/vendors/app/`, the application accepts the provider's token as `Authorization: Bearer ...` (`examples/vendors/app/src/main/kotlin/velle/vendors/VendorsSecurity.kt`), and the resolver finds the staff record whose `identityId` holds the token's `sub`. A client configured to send that header acts as that person.
- **For the client to sign you in itself**, the application has to answer the MCP server's path the way MCP's sign-in expects. A request without a token gets a 401 whose `WWW-Authenticate` header points at a document saying which identity provider issues tokens for the server. The provider supports sign-in from a client it has not met before. Every token then names the server as the one it was issued for. All of it is the application's security configuration, which Velle never touches. The example applications answer only `/api` and `/graphql` with a 401, and send every other path, `/mcp` included, to the browser's sign-in page, so a chat client cannot sign in to them by itself.

An act that needs an actor and gets none is answered with an error saying which shape commits it and that the request named no one. The assistant tells you it cannot do that as you.

## Good habits

- Let the read tools run without asking, and keep the act tools on "ask every time". Every act records something on the books.
- Ask for a dry run before an act you are unsure of. It runs every rule the act would start and checks every prohibition, and leaves nothing behind.
- When the assistant reports a refusal, the refusal is the system working. The rule it names is in `velle://spec`; ask the assistant to show you it.
- After the spec changes and the application restarts, reconnect the client. The tools are the acts of the spec that is running.

## When it does not work

- **The client cannot connect, or gets 404.** The path in the client must be the one in `velle.mcp.path`, on the application's own port. With no `velle.mcp.path` there is no server.
- **The application stops at startup with "this spec declares no authorization".** The spec says nothing about who may do what. Declare it ([Authorization](Authorization)), or set `velle.mcp.unauthenticated: true` while trying it on your own machine.
- **Every read is refused as unauthorized.** The spec declares who may see its records and the request carried no one the resolver knows. Send the header or the token the application reads.
- **An act's answer is an error naming a field.** The input did not read as the field's type, such as `"ten"` for an amount. The assistant can read the message and correct the call.

## Related pages

- [Serving a system](Serving-a-system), the same system over REST and GraphQL, and the configuration this page uses.
- [The API layer](API-layer), how the MCP server is built from the model.
- [Authorization](Authorization), who may commit and see what, which the assistant is held to as you are.
- [§21 never](Reference-21-never), the prohibitions whose refusals the assistant reports.
- [§10 Predicate expressions](Reference-10-Predicate-expressions), the language the `query` tool's `where` is written in.
