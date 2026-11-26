# Running a design session

A design session is a conversation in which a product owner and an engineer decide their system's business rules, and an AI assistant writes each decision into the spec. After every change the assistant runs the compiler and shows them the compiler's own sentences about what changed. The product owner confirms those sentences, never the assistant's summary of them. Velle ships what the assistant needs as a [skill](GLOSSARY), the folder `skills/velle-design/` in the Velle repository. After reading this page you can put the skill into your assistant, run a session on a spec, and know what the assistant will and will not do.

[Using a system from an AI chat](AI-chat) is about an assistant reading records and committing acts in a running system. This page is about an assistant helping you write the spec in the first place.

## The situation

The billing team keeps its invoicing rules in a spec, `billing.velle`. The product owner wants overdue reminders to change. The engineer could write the Velle, but then the product owner has to read Velle, or trust the engineer's account of what it says. In a design session the assistant edits the spec while they talk. The compiler then says in plain sentences what the edit changed, including for rules nobody touched.

## What the product owner is asked to trust

The assistant never restates a rule in its own words. After each change it shows the compiler's report as printed, and asks whether that is what was meant. It may ask a question in its own words, but it points at a sentence of the report only by quoting it.

So the product owner trusts the compiler, not the assistant. If the assistant wrote something other than what was agreed, the compiler's sentences say so. The engineer can still read the Velle, and every sentence of a report names the line of the spec it comes from.

## What the machine needs

- **Java 21 or later.** The skill runs the compiler from a jar file. `java -version` says which Java a machine has.
- **No network.** The compiler runs on the machine where the assistant runs its commands, and reads only the spec.
- **git, if you want history.** When the spec is in a git repository, the assistant offers to make one git commit per accepted decision. The message holds the decision in the words of the person who made it, the compiler's account of the edit, and the discussion. Without git the session works the same and keeps no history. Only the end-of-session report, every change at once, says what the session did.

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

It copies the spec as it stands to a temporary folder outside your folders, to compare against at the end. It reads Velle's best practices, and the team's own, if there is a `best-practices.md` beside the spec. If the spec is in a git repository, it asks whether to make a git commit for each accepted decision. Then it asks what you came to change.

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

For each line under "What that changes" that nobody asked for, the assistant quotes it and asks whether they want it, one question at a time. Here the one such line names `Issuance`, the record the spec keeps when an invoice is issued, so it is what the product owner asked for. Only when every such line has its own answer does the assistant ask whether the change is what they meant.

### Accepting a change

The product owner says yes. If you agreed to git commits, the assistant commits the spec. The message's first line is the decision in the product owner's words, "Only issued invoices get reminders". Its body holds the report's "What was edited" part and the quoted discussion. The assistant never creates a branch and never pushes.

The next decision follows the same steps. The product owner says: "Wait two weeks between reminders." The assistant changes `today - 7 days` to `today - 14 days` in `rule RemindOverdue` ([§11](Reference-11-rule-when-on)), and the report's "What was edited" part says:

```
- RemindOverdue changed. Now: at each Weekly tick, for every Invoice of which it is true that it is an ActionableOverdue and there is no Reminder whose invoice is it and whose sentOn is later than 14 days ago, it creates a Reminder with invoice set to it, sentOn set to today. Before: at each Weekly tick, for every Invoice of which it is true that it is an ActionableOverdue and there is no Reminder whose invoice is it and whose sentOn is later than 7 days ago, it creates a Reminder with invoice set to it, sentOn set to today.
```

The report has no other part, because the edit changes nothing else. The product owner accepts it.

The compiler never reads a comment in the spec, so a report never shows one. The comment above `OverdueInvoice` still says reminders go no more than once a week. So, after the change is accepted, the assistant shows the engineer that comment and proposes new wording. It changes the comment only on the engineer's word, in the same git commit.

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

Nobody asked for that consequence, so the assistant quotes it and asks about it. The product owner sees what it implies: "Hold on. Could a discount push an invoice's total below zero? I don't know what we'd want then. Undo that. We need to ask finance."

The assistant puts back the saved version, runs `velle validate` to show the spec validates again, and makes no git commit. It writes the open question into `billing.questions.md`, a file beside the spec named after it:

```markdown
- May an invoice's total go below zero? This decides whether discounts, written as line items with a negative price, can be allowed, since a discount could push a total below zero. Until it is answered the spec still refuses a line item with a negative price. Raised 2026-11-26 by the product owner. Finance can answer.
```

The spec keeps its refusal on that point until a later session answers the question. The assistant then removes the question from the file.

### Ending

At the end the assistant runs `velle compare` from the copy it kept at the start to the spec, and shows every change of the session at once. It lists the questions still open in `billing.questions.md`. It says whether the spec validates and what it committed. Pushing, merging, and opening a review are left to you.

## The three commands

The assistant reaches the compiler through `skills/velle-design/scripts/velle`. You can run the same commands by hand, from any folder. Write the script's path where the table writes `velle`, as in `/path/to/velle/skills/velle-design/scripts/velle validate billing.velle`.

| Command | What it prints |
|---|---|
| `velle validate billing.velle` | That the spec validates, or each thing the compiler refuses and why. For a spec that validates, also the compiler's advice. |
| `velle compare before.velle billing.velle` | What was edited, what that changes for rules nobody edited, and what it does to a record, each sentence with the line of the spec it comes from. Both files must validate. |
| `velle describe billing.velle` | The whole spec as sentences. |
| `velle describe billing.velle Reminder` | One shape, act, or refinement: its refinements, the rules and the `never`s over it, and the rules elsewhere that create or change it. A name not in the spec prints the names it could be. |

- Each prints Markdown and changes no file.
- Each exits with 0 when it did what was asked, 1 when a spec does not validate or a name is not in it, 2 when it was called wrongly, and 3 when the machine cannot run the compiler.
- With no arguments the script prints how to call it.

## Your team's own best practices

The assistant follows Velle's [Best practices](Best-practices) in everything it writes. A team writes its own in a file named `best-practices.md` in the same folder as the spec. Where the two disagree, the team's file wins.

A rule already in the spec that does not follow a best practice is not rewritten on the quiet. The assistant proposes the rewrite as a change, names the best practice and whether it is Velle's or the team's, and takes it through the same read-back. A rewrite that changes no meaning shows in the report as meaning the same.

## What the assistant never does

- Create a git branch, push, merge, or open a review.
- Copy the spec into another of your folders. It works on the file where it is, and keeps its saved versions in a temporary folder.
- Write a guess into the spec. Anything that turns on what the business means becomes a question to you, or a line in the questions file.
- Rewrite a rule already in the spec to meet a best practice without asking.
- Change a comment without the engineer's word.
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
