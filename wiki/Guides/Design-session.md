# Running a design session

A design session is a conversation in which people decide their system's business rules, and an AI assistant writes each decision into the spec. Often it is a product owner and an engineer in a meeting, and often an engineer alone. After every change the assistant runs the compiler and shows the compiler's own sentences about what changed. Nobody has to take the assistant's word for what the spec means. Velle ships what the assistant needs as a [skill](GLOSSARY), the folder `skills/velle-design/` in the Velle repository. After reading this page you can put the skill into your assistant, run a session on a spec, and know what the assistant will and will not do.

[Using a system from an AI chat](AI-chat) is about an assistant reading records and committing acts in a running system. This page is about an assistant helping you write the spec in the first place.

## The situation

The billing team keeps its invoicing rules in a spec, `billing.velle`. The product owner wants overdue reminders to change. The engineer could write the Velle, but then the product owner has to read Velle, or trust the engineer's account of what it says. In a design session the assistant edits the spec while they talk. The compiler then says in plain sentences what the edit changed, including for rules nobody touched.

## What the product owner is asked to trust

After each change the assistant shows the compiler's report as printed. When someone asks what a rule means, it quotes the compiler. Its own words are for a question, for a choice it made between two ways of writing something, and for what the spec does not have yet.

So the product owner trusts the compiler, not the assistant. If the assistant wrote something other than what was agreed, the compiler's sentences say so. The engineer can still read the Velle, and every sentence of a report names the line of the spec it comes from.

## What the machine needs

- **Java 21 or later.** The skill runs the compiler from a jar file. `java -version` says which Java a machine has.
- **No network.** The compiler runs on the machine where the assistant runs its commands, and reads only the spec.
- **git, if you want history.** When the spec is in a git repository and you ask for it, the assistant makes one git commit per accepted decision. The message holds the decision in the words of the person who made it, the compiler's account of the edit, and the discussion. Without git the session works the same and keeps no history. Only the end-of-session report, every change at once, says what the session did.

## Building the skill folder

The compiler's jar is not checked in to the repository. Build it once in a clone of the Velle repository:

```bash
./gradlew :compiler:skill
```

- It writes the jar to `skills/velle-design/scripts/velle.jar`.
- It copies the [Best practices](Best-practices) page into `skills/velle-design/references/`, so the skill carries the best practices of the compiler it runs.

Run it again after you update your clone, so the skill runs the same compiler as the repository.

## Getting the skill into your assistant

The skill is the whole `skills/velle-design/` folder: `SKILL.md`, which tells the assistant what to do, the `scripts/` folder with the compiler, and the `references/` folder on how Velle is written. Build it first, so the folder holds the jar.

**Claude Code** looks for skills in `.claude/skills/` at the root of a project, and in `~/.claude/skills/` for every project of yours. Link the folder there, so a rebuild reaches the assistant:

```bash
mkdir -p .claude/skills
ln -s /path/to/velle/skills/velle-design .claude/skills/velle-design
```

A copy of the folder works too, but has to be copied again after each rebuild. Claude Code then uses the skill when you ask it to work on a Velle spec.

**claude.ai** takes a skill as a ZIP file of the folder, uploaded where its settings add skills, with code execution turned on. The skill needs Java 21 where the assistant runs its code. When that place has no Java, the assistant says so at the first command and the session cannot run there. Use an assistant on a machine that has Java.

**Another assistant that reads skills as folders with a `SKILL.md` in them** takes the folder wherever it looks for skills. Its own documentation says where.

## A session on the billing spec

To follow along, copy `examples/billing/changes/00-billing.velle` to a folder of your own as `billing.velle`. Start the assistant in that folder and say which file the session works on.

### Starting

The assistant runs `velle validate billing.velle`, which prints:

```
`billing.velle` validates.
```

It copies the spec as it stands to a temporary folder outside your folders, to compare against at the end. It reads Velle's best practices, and the team's own, if there is a `best-practices.md` beside the spec. If the spec is in a git repository, it says once that it can make a git commit for each accepted decision, and does so only when you ask. Then it starts from whatever you say first.

### Reading what the spec says

The product owner asks: "Today is about reminders. What does the spec say about reminders right now?"

The spec's word is `Reminder`, so the assistant runs `velle describe billing.velle Reminder`. Part of what it prints:

```
- RemindOverdue: at each Weekly tick, for every Invoice of which it is true that it is an ActionableOverdue and there is no Reminder whose invoice is it and whose sentOn is later than 7 days ago, it creates a Reminder with invoice set to it, sentOn set to today.
  In the spec: billing.velle, line 160, `rule RemindOverdue`
```

`ActionableOverdue` is a [refinement](Reference-08-Refinements), a named condition on an invoice. `Weekly` is a [schedule](Reference-17-Schedule-triggers). The assistant runs `velle describe billing.velle ActionableOverdue` too, which prints, among other lines:

```
- An Invoice is an ActionableOverdue when it is an OverdueInvoice and it is not an ArchivedInvoice.
```

### A change and its read-back

The product owner says: "Only issued invoices should get reminders." The engineer agrees.

The spec already has a refinement for an invoice that has been issued, `IssuedInvoice`. The assistant saves the spec as it is to its temporary folder, edits one line, and runs `velle validate`:

```velle
shape ActionableOverdue = OverdueInvoice and not ArchivedInvoice                     -- before
shape ActionableOverdue = OverdueInvoice and IssuedInvoice and not ArchivedInvoice   -- after
```

`validate` also prints the compiler's advice: places where the meaning is defined but the author may not have meant it. The assistant raises only advice that this edit added, and here there is none. It then runs `velle compare` from the saved version to the spec, and shows the report in full. Its first part says what was edited:

```
- An Invoice is now an ActionableOverdue when it is an OverdueInvoice and it is an IssuedInvoice and it is not an ArchivedInvoice. Before: when it is an OverdueInvoice and it is not an ArchivedInvoice.
  In the spec: billing.velle, line 154, `shape ActionableOverdue = OverdueInvoice and IssuedInvoice and not ArchivedInvoice`
```

The second part, "What that changes", names what the edit changes for rules nobody edited:

```
- A new Issuance can now cause RemindOverdue to run, later rather than in the same commit.
```

The third part says what the edit does to a record like the ones the system keeps:

```
- An Invoice stops being an ActionableOverdue by this change alone when it is an OverdueInvoice, it is not an ArchivedInvoice, and it is not an IssuedInvoice.
- At the next Weekly tick, RemindOverdue no longer runs for it.
```

[Comparing specs](Comparing-specs) explains each part.

The lines under "What that changes" are what the compiler could not rule out. Each says something can now happen, not that it will. Here the one such line names `Issuance`, the record the spec keeps when an invoice is issued, so it is what the product owner asked for.

The assistant does not ask whether each change is right. You have the compiler's sentences in front of you, and you say so when one is wrong. It asks a question, one at most, only when a line of the report says something a business would plainly not want, or when what was said could be read two ways.

### The next change

The product owner reads the report and moves on, so the change stands. If you asked for git commits, the assistant commits the spec. The message's first line is the decision in the product owner's words, "Only issued invoices get reminders". Its body holds the report's "What was edited" part and the quoted discussion. The assistant never creates a branch and never pushes.

The next decision follows the same steps. The product owner says: "Wait two weeks between reminders." The assistant changes `today - 7 days` to `today - 14 days` in `rule RemindOverdue` ([§11](Reference-11-rule-when-on)), and the report's "What was edited" part says:

```
- RemindOverdue changed. Now: at each Weekly tick, for every Invoice of which it is true that it is an ActionableOverdue and there is no Reminder whose invoice is it and whose sentOn is later than 14 days ago, it creates a Reminder with invoice set to it, sentOn set to today. Before: at each Weekly tick, for every Invoice of which it is true that it is an ActionableOverdue and there is no Reminder whose invoice is it and whose sentOn is later than 7 days ago, it creates a Reminder with invoice set to it, sentOn set to today.
```

The report has no other part, because the edit changes nothing else.

The compiler never reads a comment in the spec, so a report never shows one. The comment above `OverdueInvoice` still says reminders go no more than once a week. So the assistant shows the engineer that comment and proposes new wording. It changes the comment only on the engineer's word, in the same git commit.

### Running an example

The product owner asks: "So an invoice due on the tenth, nobody pays it. When does the reminder go?"

That is an example, and the compiler can run it. The assistant writes it as a scenario, a JSON list of steps, in its temporary folder:

```json
[
  { "commit": "SignUp", "with": { "name": "Acme", "email": "billing@acme.example" } },
  { "find": "Customer", "where": "name == \"Acme\"", "as": "acme" },
  { "commit": "BillCustomer", "with": { "customer": "acme", "due": "2026-01-10" } },
  { "find": "Invoice", "where": "customer.name == \"Acme\"", "as": "invoice" },
  { "commit": "LineItem", "with": { "invoice": "invoice", "description": "Consulting", "quantity": 2, "price": 150 } },
  { "pass": "14 days" },
  { "tick": "Weekly" },
  { "show": "invoice" }
]
```

- `commit` commits an [act](Reference-04-State-and-commits) with the fields after `with`.
- `find` gives a name to a record a rule created, here the `Customer` that `rule AdmitCustomer` makes from a `SignUp` in `examples/billing/changes/00-billing.velle`. A later step uses the name where a record is wanted.
- `pass` moves the clock forward, and `tick` runs a [schedule](Reference-17-Schedule-triggers).
- `show` prints a record.

The run starts with no records, on 1 January 2026, so the same scenario prints the same report every time. `velle try billing.velle overdue.json` prints one part per step. Three of them:

```
## Step 5: commit LineItem with invoice invoice, description "Consulting", quantity 2, price 150

Accepted.

Created:
- LineItem 3: invoice Invoice 2 (invoice), description "Consulting", price 150.00, quantity 2, amount 300.00

Changed:
- Invoice 2 (invoice): total 0.00 to 300.00, balance 0.00 to 300.00, status paid to open, lineItems none to LineItem 3

## Step 6: 14 days pass

The clock now reads 2026-01-15T09:00:00Z. No rule runs until a commit or a tick.

Changed:
- Invoice 2 (invoice): status open to overdue, now an OverdueInvoice, now an ActionableOverdue

## Step 7: the Weekly tick

It ran at 2026-01-15T09:00:00Z.

Created:
- Reminder 4: invoice Invoice 2 (invoice), sentOn 2026-01-15
```

So the reminder goes at the first weekly run after the due date. A step the spec refuses prints "Refused" with the compiler's sentence for the [`never`](Reference-21-never) that refused it, and the steps after it still run.

### A question nobody answers

Suppose the product owner had said "cap a deposit at 1000", and the conversation moved on before anyone said whether exactly 1000 is allowed. The assistant does not hold the conversation up. It writes the reading it chose, and leaves its question as a comment above the change:

```velle
-- Not confirmed, 2026-11-26: is a deposit of exactly 1000 allowed?
```

It does the same when you have told it not to ask questions. The comment is removed when the question is answered. At the end of the session the assistant lists the comments that remain, and any still there stay in the spec for its next reader.

### A change undone and parked as a question

The product owner says: "We want to give discounts. A discount is a line on the invoice with a negative price." The engineer thinks the spec refuses a negative price today. The product owner says: "Then allow it."

The assistant removes `never (LineItem where price < 0)`, a [`never`](Reference-21-never) that refuses such a line item. The report says what was edited:

```
- A LineItem is no longer refused when its price is below 0.
```

and, under "What that changes":

```
- A new LineItem can now make an Invoice stop being an OverdueInvoice.
```

A business might not want that, so the assistant quotes the line and asks about it. The product owner sees what it implies: "Hold on. Could a discount push an invoice's total below zero? I don't know what we'd want then. Undo that. We need to ask finance."

The assistant puts back the saved version, runs `velle validate` to show the spec validates again, and makes no git commit. It writes the open question into `billing.questions.md`, a file beside the spec named after it:

```markdown
- May an invoice's total go below zero? This decides whether discounts, written as line items with a negative price, can be allowed, since a discount could push a total below zero. Until it is answered the spec still refuses a line item with a negative price. Raised 2026-11-26 by the product owner. Finance can answer.
```

The spec keeps its refusal on that point until a later session answers the question. The assistant then removes the question from the file.

### Ending

At the end the assistant runs `velle compare` from the copy it kept at the start to the spec, and shows every change of the session at once. It lists the `-- Not confirmed` comments that remain, each with its question. It lists the questions still open in `billing.questions.md`. It says whether the spec validates and what it committed. Pushing, merging, and opening a review are left to you.

## You decide how the session runs

The spec is yours, and it is there to help the two of you understand each other while you talk. The assistant does not ask how you want to work before it starts. What this page shows is what it does when you have said nothing.

- Read the Velle whenever you like, and ask the assistant to show it. An engineer may follow the spec as it changes, and a product owner may look at it too.
- Tell the assistant to ask less, to make several changes at once, or to hold its report until the end. A question it would have asked becomes a `-- Not confirmed` comment.
- An engineer working alone uses it the same way, usually faster and in Velle's own terms.

One thing stays fixed. The assistant never says what the spec means in its own words. It shows the compiler's sentences or the Velle.

## The commands

The assistant reaches the compiler through `skills/velle-design/scripts/velle`. You can run the same commands by hand, from any folder. Write the script's path where the table writes `velle`, as in `/path/to/velle/skills/velle-design/scripts/velle validate billing.velle`.

| Command | What it prints |
|---|---|
| `velle validate billing.velle` | That the spec validates, or each thing the compiler refuses and why. For a spec that validates, also the compiler's advice. |
| `velle compare before.velle billing.velle` | What was edited, what that changes for rules nobody edited, and what it does to a record, each sentence with the line of the spec it comes from. Both files must validate. |
| `velle describe billing.velle` | The whole spec as sentences. |
| `velle describe billing.velle Reminder` | One shape, act, or refinement: its refinements, the rules and the `never`s over it, and the rules elsewhere that create or change it. A name not in the spec prints the names it could be. |
| `velle describe billing.velle RemindOverdue` | One rule's sentence, and the shape or act it is over. |
| `velle try billing.velle overdue.json` | What the spec does with an example, step by step. `skills/velle-design/references/scenarios.md` lists every kind of step. |

- Each prints Markdown and changes no file.
- Each exits with 0 when it did what was asked, 1 when a spec does not validate, a name is not in it, or a scenario cannot be run, 2 when it was called wrongly, and 3 when the machine cannot run the compiler. A scenario in which the spec refuses a step still exits with 0.
- With no arguments the script prints how to call it.

## An assistant that cannot run commands

Some assistants cannot run a script but can start an [MCP](GLOSSARY) server on your machine, as many desktop chat apps can. For those, Velle has an authoring server with the same four commands as tools. Build it in a clone of the Velle repository:

```bash
./gradlew :apis:mcp:authoringJar
```

It writes `apis/mcp/build/libs/velle-authoring-mcp.jar`. Point your assistant at it where its settings list MCP servers:

```json
{ "command": "java", "args": ["-jar", "/path/to/velle/apis/mcp/build/libs/velle-authoring-mcp.jar"] }
```

- The server runs on your machine, needs Java 21, and uses no network. Nothing of your spec leaves the machine.
- Its tools are `validate`, `compare`, `describe`, and `try`. Each takes the text of a spec, where the script takes a file, and returns what the script prints.
- It keeps nothing between calls. The spec lives in the conversation, and saving it to a file is yours to do.
- It also serves the skill's instructions and reference, as the prompt `velle-design` and as resources, for an assistant that does not read skills.

An assistant in a browser tab, which can start no program on your machine, cannot use it.

## Your team's own best practices

The assistant follows Velle's [Best practices](Best-practices) in everything it writes. A team writes its own in a file named `best-practices.md` in the same folder as the spec. Where the two disagree, the team's file wins.

A rule already in the spec that does not follow a best practice is not rewritten on the quiet. The assistant proposes the rewrite as a change, names the best practice and whether it is Velle's or the team's, and takes it through the same read-back. A rewrite that changes no meaning shows in the report as meaning the same.

## What the assistant never does

- Create a git branch, push, merge, or open a review.
- Copy the spec into another of your folders. It works on the file where it is, and keeps its saved versions in a temporary folder.
- Write a guess into the spec unmarked. Anything that turns on what the business means becomes a question to you, a `-- Not confirmed` comment that holds the question, or a line in the questions file.
- Write something close when Velle cannot say what you decided. It tells you, and records the decision in the questions file.
- Rewrite a rule already in the spec to meet a best practice without asking.
- Change a comment of yours without the engineer's word. The only comment it writes unasked is `-- Not confirmed`, which it removes itself.
- Write Velle when it cannot run the compiler. It tells you why and stops.

## When it does not work

- **"The Velle compiler needs Java 21 or later".** The machine has no `java` command, or an older one. Install Java 21 or later.
- **"The Velle compiler's jar is missing".** The skill folder was not built. Run `./gradlew :compiler:skill` in the Velle repository. If you copied the folder rather than linking it, copy it once more.
- **The spec names a type no library registers.** The skill's jar carries the compiler and Velle's own [extension type](Extension-types) library, `velle.finance`, and nothing else. A spec whose `Order` has a field `price: Euro`, with `Euro` from a library of your own, is refused:

  ```
  - [F1] 'Order.price': no builtin or registered type is named 'Euro' (Reference §5)
  ```

  The skill cannot validate such a spec. Check it where the system is built, with its libraries on the classpath ([Extension types](Extension-types)).
- **On claude.ai, the assistant cannot run anything.** Code execution is off, or the place where it runs code has no Java 21. Turn code execution on in the settings. If Java is what is missing, use an assistant on a machine that has it.

## Related pages

- [Comparing specs](Comparing-specs), the report every read-back shows.
- [Best practices](Best-practices), the ones the skill carries.
- [Using a system from an AI chat](AI-chat), an assistant using a running system.
- [Extension types](Extension-types), the types a library adds to a spec.
- [§8 Refinements](Reference-08-Refinements) and [§21 never](Reference-21-never), the constructs the session changes.
