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
6. **Show where things stand.** For an existing spec, ask what they came to change and run `describe` with that name, so everyone starts from what the spec says today. Describe the whole spec only when asked, or when it is short.

## For each change

1. **Listen for a decision.** People think out loud and change their minds. Edit when the product owner and the engineer have agreed on something, not on every sentence. If you cannot tell whether they agreed, ask.
2. **Save the version before the edit** to the temporary folder.
3. **Edit the draft.** Read `references/language.md` when you are not certain how a construct is written. Follow the best practices in what you write.
4. **Validate.** Run `validate` and act on what it says:
   - A mistake in how you wrote the Velle is yours. Fix it and validate again without troubling anyone.
   - Anything that turns on what the business means is theirs. This includes a statement whose meaning the compiler says is undefined, a prohibition the compiler cannot prove, and a case the rules leave unanswered. Do not pick an answer. Put it to them as a business question, such as "Can a payment arrive after an invoice is archived?"
   - Advice the edit added is theirs too. Compare what the compiler advises for the saved version with what it advises for the draft. An existing spec may carry many advisories already, so raise only the ones that are new, as printed, and ask whether the author meant what the advisory describes.
5. **Read it back.** Run `compare` from the saved version to the draft. Show the report as printed, all of it. You may ask a question in your own words. You may not restate the rule in your own words. Point at a sentence of the report by quoting it, never by rephrasing it.
6. **Ask about each consequence first.** The part headed "What that changes" names rules nobody edited, and it is the part people most need to see. For each line there that the people did not ask for, quote it and ask whether they want it, one question at a time, and wait for an answer to that question. "OpenInvoice can now cause SendReceipt to run" may mean every new invoice gets a receipt, and nobody said they wanted that. A consequence they do not want means the change is rejected or partly right.
7. **Then ask whether the change is what they meant.** Ask this only when every consequence has its own answer. A "yes" given while a consequence question is still open answers neither. Ask again.
8. **Take the answer.**
   - **Accepted:** if they agreed to commits, commit the draft, and the questions file if it changed, and nothing else. The first line of the message is the decision in the words of the person who made it, shortened to fit one line: "Only issued invoices get reminders". The body holds the report's "What was edited" part as printed, then the quoted discussion, with any consequence question and its answer. Never create a branch and never push.
   - **Rejected:** restore the saved version. Ask what they meant instead, unless they have already said to leave it or to park it.
   - **Partly right:** restore the saved version and make the corrected change as a new edit, so each read-back covers one decision.

Make one change per read-back. A report covering several decisions at once cannot say which decision caused which consequence.

## Speaking with the product owner

- Show the compiler's sentences. Do not show Velle code unless someone asks to see it. The report quotes lines of the spec beside its sentences, and that is fine.
- Where the compiler has no words for a construct it prints the Velle and says so. Show it as printed. Ask the engineer to explain it. Do not translate it yourself.
- Use the names the spec uses. If they say "late invoice" and the spec says `OverdueInvoice`, ask whether those are the same thing before using either. When a word they use could be two things in the spec, such as "issued" where the spec has both an `IssuedInvoice` and an `issuedOn`, ask which they mean.
- The report can print something that is not a name in the spec or that reads as nonsense. Show it as printed, say that you cannot tell what it refers to, and ask the engineer. Do not guess at it.
- Ask one question at a time, and make it one a product owner can answer without knowing Velle.

## What stays undecided

When the discussion leaves something open, write it as a question in a file beside the draft, named after it: for `billing.velle`, `billing.questions.md`. Write the question in business words, with the date and who can answer it if they said. Leave the spec as it was on that point: add no rule for it, and remove no rule that is already there. Never write your best guess into the spec. Remove a question from the file when a later change answers it.

## Comments in the spec

A spec's comments are the team's own words, and the compiler does not read them, so `compare` never shows them. When your edit makes a comment near it untrue, do not rewrite the comment quietly. After the change is accepted, show the engineer the comment and the wording you propose, and change it only on their word. It goes in the same commit.

## Best practices on an existing spec

Everything you write follows the best practices. When a rule already in the spec does not follow one, never rewrite it on your own. Propose the rewrite as a change, name the best practice and whether it is Velle's or the team's, and take it through the same read-back. A rewrite that changes no meaning shows in the report as meaning the same, and the product owner accepts or rejects it like any other change.

## Ending the session

1. Run `compare` from the starting version to the draft and show the whole report. It is every change of the session at once.
2. List the questions still open in the questions file.
3. Say where the draft is, whether it validates, and what was committed, if anything. Do not push, merge, or open a review. Say that those are theirs to do.

## References

- `references/language.md`: how each Velle construct is written, what the compiler refuses, and which construct says what. It links the other files in that folder.
- `references/Best-practices.md`: Velle's best practices for a readable spec. Its links and the example files it names belong to Velle's full documentation, which is not in this skill. `references/language.md` covers the constructs it mentions.
