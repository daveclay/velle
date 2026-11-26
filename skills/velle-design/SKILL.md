---
name: velle-design
description: Write or change a Velle spec (a .velle file of business rules) with a product owner and an engineer, confirming each change in the compiler's own sentences. Use for any work on a Velle spec.
compatibility: Requires Java 21 or later and a POSIX shell to run scripts/velle. Needs no network.
---

# Running a Velle design session

A Velle spec records how a business works: what it keeps, what may happen to it, what follows, and what is never allowed. In a design session a product owner and an engineer talk through those rules and you write what they decide into the spec.

One rule governs everything below. **The product owner confirms what the compiler says the spec means, never what you say it means.** After every change you run the compiler, show its sentences word for word, and ask whether that is what they meant. You do not summarize a rule in your own words, because nobody could then tell whether your words and the spec agree.

## The commands

Reach the compiler one of two ways. Use the first that is available.

1. Tools named `validate`, `compare`, and `describe` from a connected Velle authoring server.
2. The script in this skill: `scripts/velle <subcommand> ...`. It needs Java 21 or later. If it reports that Java or its jar is missing, tell the people in the session exactly that and stop. Do not write Velle without a compiler to check it.

| Command | What it prints |
|---|---|
| `velle validate <draft.velle>` | That the draft validates, or each thing the compiler refuses and why. For a draft that validates, also what the compiler advises: places where the meaning is defined but the author may not have meant it. |
| `velle compare <before.velle> <after.velle>` | What was edited, what that changes for rules nobody edited, and what it does to a record, each sentence pointing at a line of the spec. Both files must validate. |
| `velle describe <draft.velle>` | The whole spec as sentences. |
| `velle describe <draft.velle> <Name>` | One shape or act, with its refinements, the rules and prohibitions over it, and the rules elsewhere that create or change it. The name of a refinement works too. |

Every command prints Markdown and changes no file. A command exits with 0 when it did what was asked, 1 when a draft does not validate or a name is not in it, 2 when it was called wrongly, and 3 when the machine cannot run the compiler.

## Before the first change

1. **Find the draft.** Ask which `.velle` file the session works on. Most sessions change a spec that already exists. A system's first session starts from a new, empty file, and you ask where to put it. Work on the file where it is. Never copy it elsewhere in the team's folders, create a branch, or push. How a team keeps and reviews its spec is the team's choice.
2. **Check it validates.** Run `validate`. If an existing spec does not validate, show what the compiler says and ask the engineer how to proceed before changing anything.
3. **Keep the starting version.** Copy the draft as it stands to a temporary folder outside the team's folders. You compare against it at the end.
4. **Read the best practices.** Read `references/Best-practices.md`. Then look for a file named `best-practices.md` in the same folder as the draft. It holds the team's own best practices. Follow both, and where they disagree follow the team's.
5. **Say what will be kept.** If the draft is in a git repository, say that you can commit each accepted decision with the words that led to it, and ask whether to do so. If it is not, or git is not available, say once that the session will keep no history, and carry on. A session never needs git.
6. **Ask who is in the session.** A product owner and an engineer in a meeting get everything below as written. An engineer working alone gets the shorter loop under "When an engineer works alone". If you cannot tell, ask.
7. **Show where things stand.** For an existing spec, ask what they came to change and run `describe` with that name, so everyone starts from what the spec says today. Describe the whole spec only when asked, or when it is short.

## For each change

1. **Listen for a decision.** People think out loud and change their minds, so do not edit on every sentence. A statement by either person that the other does not challenge is a decision. If they disagree, or you cannot tell what was decided, ask.
2. **Take one decision at a time.** When one turn holds several, make them as separate changes, each with its own read-back, in the order they were said. Things that only make sense together, such as a record and the parts that arrive with it, are one change.
3. **Save the version before the edit** to the temporary folder.
4. **Edit the draft.** Read `references/language.md` when you are not certain how a construct is written. Follow the best practices in what you write. Some choices are the engineer's and the product owner will not have made them: whether something is kept or only requested, what type a field has, what a thing is named. Make the choice the reference points to, and tell the engineer in one line what you chose, so they can object.
5. **Validate.** Run `validate` and act on what it says:
   - A mistake in how you wrote the Velle is yours. Fix it and validate again without troubling anyone.
   - Anything that turns on what the business means is theirs. This includes a statement whose meaning the compiler says is undefined, a prohibition the compiler cannot prove, and a case the rules leave unanswered. Do not pick an answer. Put it to them as a business question, such as "Can a payment arrive after an invoice is archived?"
   - Advice the edit added is theirs too. Compare what the compiler advises for the saved version with what it advises for the draft. An existing spec may carry many advisories already, so raise only the ones that are new, as printed, and ask whether the author meant what the advisory describes.
6. **Read it back, in one reply.** Run `compare` from the saved version to the draft and show the report as printed, all of it. Lines that point into the saved copy point at the spec as it was before the edit; say so if a pointer names your temporary file. Then, in the same reply:
   - Under "This also follows", quote each line of the part headed "What that changes" that names a rule or a record the people did not mention. That part is what a change does to rules nobody edited, and it is what people most need to see. Lines under "The spec as a whole" about example tests are for the engineer. Show them and ask nothing about them.
   - When one of those lines says something a business would likely not want, ask about that line alone and wait for its answer. "OpenInvoice can now cause SendReceipt to run" may mean every new invoice gets a receipt, and nobody said they wanted that. Ask about one line at a time, the most worrying first.
   - Otherwise end with one question: do they want this change, with what follows from it?
7. **Take the answer.**
   - **Accepted:** a yes to that one question accepts the change. A yes that could be answering a question about a single line accepts only that line. Ask the closing question then.
   - **Rejected:** restore the saved version. Ask what they meant instead, unless they have already said to leave it or to park it.
   - **Partly right:** restore the saved version and make the corrected change as a new edit.
   - **No answer, and they move on:** do not hold the conversation up, and do not lose the decision. Keep the change in the draft, and mark it there with a comment on the line above what you changed, or where a removed line stood: `-- Not confirmed, 2026-11-26: "Only issued invoices get reminders"`, with the date and the decision in the words of the person who made it. Say in one line at the top of your next reply that it is in but not yet confirmed. You ask about it again at the end of the session, and remove the comment when it is confirmed or undone.
8. **Keep a record, if they agreed to commits.** Commit the draft and the questions file, and nothing else. The first line of the message is the decision in the words of the person who made it, shortened to fit one line: "Only issued invoices get reminders". The body holds the report's "What was edited" part as printed, then the quoted discussion, with any question about a line and its answer. For a change not yet confirmed, the body says so and holds the lines you quoted under "This also follows". When a question is parked and the draft did not change, commit the questions file alone, with a first line that starts "Parked:" and gives the question. Never create a branch and never push. Without git there is nothing to do here.

## When an engineer works alone

An engineer also uses this skill without a product owner, to write a spec or tune one in a quick exchange. The compiler's report still follows every edit, because it is how the engineer sees what an edit did to rules they did not touch. What changes:

- Show Velle code freely, and take instructions given in Velle's own terms.
- Make several changes in one go when the engineer asks for them together, then show one report for all of them.
- After each edit show the `compare` report as printed, with the lines under "What that changes" that the engineer did not mention quoted beneath it. Ask nothing. The engineer says so when something is wrong. Do still ask about a line a business would likely not want.
- Mark nothing as not confirmed. What the engineer does not object to stands.
- Anything that turns on what the business means still goes to the engineer as a question. If they cannot answer for the business, it goes in the questions file.
- Commit when the engineer says to, with their instruction as the first line.

Saving the version before each edit, validating, raising new advisories, the best practices, and the end of the session are the same.

## Explaining what the report names

The report names rules and refinements, and a product owner may not know what one does. Never explain one in your own words. Run `describe` with the name of the shape or act it belongs to and quote the compiler's sentence for it.

## Speaking with the product owner

- Show the compiler's sentences. Do not show Velle code unless someone asks to see it. The report quotes lines of the spec beside its sentences, and that is fine.
- Where the compiler has no words for a construct it prints the Velle and says so. Show it as printed. Ask the engineer to explain it. Do not translate it yourself.
- Use the names the spec uses. If they say "late invoice" and the spec says `OverdueInvoice`, ask whether those are the same thing before using either. When a word they use could be two things in the spec, such as "issued" where the spec has both an `IssuedInvoice` and an `issuedOn`, ask which they mean.
- The report can print something that is not a name in the spec or that reads as nonsense. Show it as printed, say that you cannot tell what it refers to, and ask the engineer alone. Do not guess at it.
- Ask one question at a time, and make it one a product owner can answer without knowing Velle.
- "Only the accounts team may do this" needs the system to know who its people are. When the spec keeps no record of them, that is a question for the engineer first. Park it, and add no rule about who may do what until it is answered.

## What stays undecided

When the discussion leaves something open, write it as a question in a file beside the draft, named after it: for `billing.velle`, `billing.questions.md`. An entry is the question in business words, the date, and who can answer it if they said. It may quote the compiler's sentence that raised it. It does not restate what the spec says in your words. Leave the spec as it was on that point: add no rule for it, and remove no rule that is already there. Never write your best guess into the spec. Remove a question from the file when a later change answers it.

## Comments in the spec

A spec's comments are the team's own words, and the compiler does not read them, so `compare` never shows them. When your edit makes a comment near it untrue, do not rewrite the comment quietly. After the change is accepted, show the engineer the comment and the wording you propose, and change it only on their word. It goes in the next commit.

The one comment you write unasked is `-- Not confirmed` on a change nobody answered. It is yours to add and yours to remove.

## Best practices on an existing spec

Everything you write follows the best practices. When a rule already in the spec does not follow one, never rewrite it on your own. Propose the rewrite as a change, name the best practice and whether it is Velle's or the team's, and take it through the same read-back. A rewrite that changes no meaning shows in the report as meaning the same, and the product owner accepts or rejects it like any other change.

## Ending the session

1. Run `compare` from the starting version to the draft and show the whole report. It is every change of the session at once.
2. Find every `-- Not confirmed` comment in the draft, name each of those changes, and ask about each. Remove the comment of one they confirm. One they reject is undone as a new change, with its own read-back. If the session ends before they answer, the comments stay, so the next reader of the spec sees what nobody confirmed.
3. List the questions still open in the questions file.
4. Say where the draft is, whether it validates, and what was committed, if anything. Do not push, merge, or open a review. Say that those are theirs to do.

## References

- `references/language.md`: how each Velle construct is written, what the compiler refuses, and which construct says what. It links the other files in that folder.
- `references/Best-practices.md`: Velle's best practices for a readable spec. Its links and the example files it names belong to Velle's full documentation, which is not in this skill. `references/language.md` covers the constructs it mentions.
