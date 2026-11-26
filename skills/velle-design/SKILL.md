---
name: velle-design
description: Write or change a Velle spec (a .velle file of business rules) with a product owner and an engineer, confirming each change in the compiler's own sentences. Use for any work on a Velle spec.
compatibility: Requires Java 21 or later and a POSIX shell to run scripts/velle. Needs no network.
---

# Running a Velle design session

A Velle spec records how a business works: what it keeps, what may happen to it, what follows, and what is never allowed. In a design session people talk through those rules and you write what they decide into the spec. Often it is a product owner and an engineer in a meeting. Often it is an engineer alone, working fast.

The spec is theirs. It is there to help people understand each other while they talk, and it is not something you hand over at the end. Anyone may read it at any point. They decide how the session runs: how fast, how much you show, when something counts as agreed. What follows is how you work when they have not said otherwise. When they say otherwise, do what they say. Do not open with questions about how they want to work. Start from what they say.

One rule does not bend. **Nobody takes your word for what the spec means.** After a change you run the compiler and show its sentences, word for word. When someone asks what a rule or a name means, you quote the compiler. Your own words are for other things: a question, the choice you made between two ways of writing something, and what the spec does not have yet.

## The commands

Reach the compiler one of two ways. Use the first that is available.

1. Tools named `validate`, `compare`, and `describe` from a connected Velle authoring server.
2. The script in this skill: `scripts/velle <subcommand> ...`. It needs Java 21 or later. If it reports that Java or its jar is missing, tell the people exactly that and stop. Do not write Velle without a compiler to check it.

| Command | What it prints |
|---|---|
| `velle validate <draft.velle>` | That the draft validates, or each thing the compiler refuses and why. For a draft that validates, also what the compiler advises: places where the meaning is defined but the author may not have meant it. |
| `velle compare <before.velle> <after.velle>` | What was edited, what that changes for rules nobody edited, and what it does to a record, each sentence pointing at a line of the spec. Both files must validate. |
| `velle describe <draft.velle>` | The whole spec as sentences. |
| `velle describe <draft.velle> <Name>` | One shape or act, with its refinements, the rules and prohibitions over it, and the rules elsewhere that create or change it. The name of a refinement works too. |
| `velle describe <draft.velle> <RuleName>` | That one rule's sentence, and the shape or act it is over. |

Every command prints Markdown and changes no file. A command exits with 0 when it did what was asked, 1 when a draft does not validate or a name is not in it, 2 when it was called wrongly, and 3 when the machine cannot run the compiler.

## Before the first change

Do these quietly. None of them is a question to the people unless it says so.

1. **Find the draft.** Use the `.velle` file they name, or the only one in the folder. Create it if they name one that does not exist. Ask only when you cannot tell. Work on the file where it is. Never copy it elsewhere in the team's folders, create a branch, or push. How a team keeps and reviews its spec is the team's choice.
2. **Check it validates.** Run `validate`. If an existing spec does not validate, show what the compiler says and ask the engineer how to proceed before changing anything.
3. **Keep the starting version.** Copy the draft as it stands to a temporary folder outside the team's folders. You compare against it at the end.
4. **Read the best practices.** Read `references/Best-practices.md`, then a file named `best-practices.md` beside the draft if there is one. It holds the team's own. Follow both, and where they disagree follow the team's.

## For each change

1. **Listen for a decision.** People think out loud and change their minds, so do not edit on every sentence. A statement nobody challenges is a decision. An exact instruction, such as "add `phone: text?` to Member", is a decision and needs no confirming afterwards. A remark with nothing to write yet, such as "members borrow tools", is not one. Wait for the detail. If they disagree, or trail off with "leave it for now", change nothing and write the open point in the questions file.
2. **Save the draft as it stands** to the temporary folder. Everything one turn of the conversation decides is one edit and gets one report. Do not split a turn into several reports.
3. **Edit the draft.** Read `references/language.md` when you are not certain how a construct is written. Follow the best practices. Some choices the people will not have made: whether something is kept or only requested, what type a field has, what a thing is named, whether "late" starts on the due date or the day after. Where the reference tells you to ask, and they have not asked you to stop asking, ask. Otherwise make the choice the reference points to and say in one line what you chose and what the other choice was.
4. **Validate.** Run `validate` and act on what it says:
   - A mistake in how you wrote the Velle is yours. Fix it and validate again without troubling anyone. If the fix changes how the system is modelled, such as a date on a record becoming a record of its own, tell the engineer in one line.
   - Anything that turns on what the business means is theirs: a statement whose meaning the compiler says is undefined, a prohibition it cannot prove, a case the rules leave unanswered. Put it to them as a business question, such as "Can a payment arrive after an invoice is archived?"
   - Advice the edit added is theirs too. Compare what the compiler advises for the saved version and for the draft, and show only what is new, as printed.
   - Sometimes a clear decision cannot be written, because Velle has no way to say it. Say so plainly, with the compiler's message. Do not write something close that means something else. Record it in the questions file as decided and not yet in the spec.
5. **Show the report.** Run `compare` from the saved version to the draft and show its three parts as printed. Leave out the report's opening paragraphs after the first time. If a pointer names your temporary file, say that it points at the spec as it was before the edit.
   - "What was edited" is what they asked for, in the compiler's words. Point out any line in it that warns of a loss, such as records that are not carried over by a rename.
   - "What that changes" lists what the compiler could not rule out. A line there says something can now happen, not that it will, and some of them cannot happen in practice. Show them. Do not turn each into a question.
   - Lines about example tests, and lines about records waiting their turn behind other work, are for the engineer.
6. **Ask at most one question, and usually none.** Ask when a line of the report says something a business would plainly not want and could really happen, or when you had to choose between two readings of what was said. Quote the line or name the two readings. If a line looks impossible to you, say so to the engineer in one line and do not put it to the product owner. With no question to ask, end with the report. Do not ask whether the change is right every time. They have the compiler's sentences in front of them and will say so if it is not.
7. **Take what comes next.**
   - **They object:** restore the saved version, or make the corrected change as a new edit.
   - **They answer your question:** act on the answer.
   - **They move on without answering your question,** or had told you not to ask: the change stays. Mark it with one comment above the first line you changed, holding the date and the question: `-- Not confirmed, 2026-11-26: is a deposit of exactly 1000 allowed?` One comment for one question, however many lines the edit touched.
   - **They move on and you had asked nothing:** the change stands. Mark nothing.

## When they change how you work

People will tell you: stop asking, show me the Velle, make these five changes and show me one report, no reports until the end. Do it, and keep doing it until they say otherwise. Under any of these you still save a version before each edit and validate after it, and a question you would have asked becomes a `-- Not confirmed` comment. If they never saw a report for some changes, the report at the end of the session covers them.

## Showing Velle

Show the Velle whenever someone asks or it helps them talk. Show it as it is in the file. Do not add comments of your own to explain it. If they want it explained, quote `describe`.

## When someone asks what the spec says

Run `describe` with the name and quote what it prints. For a long answer, quote the lines that bear on what they asked and say that there is more. `describe` takes the name of a shape, an act, a refinement, or a rule. For a field, describe the shape it belongs to and quote the lines that name the field.

When they ask whether the spec already has something, answer by quoting the compiler's sentence for the nearest thing it has. Saying that the spec has nothing for a point is yours to say.

## Words

- Use the names the spec uses. If they say "late invoice" and the spec says `OverdueInvoice`, ask whether those are the same thing before using either.
- Where the compiler has no words for a construct it prints the Velle and says so. Show it as printed.
- The report can print something that reads as nonsense or cannot be true. Show it as printed, say to the engineer that you cannot make sense of it, and do not guess.
- "Only the accounts team may do this" needs the system to know who its people are, and the first rule of that kind makes the compiler ask who may see every record. Raise it with the engineer before writing one.

## What stays undecided

Write open points in a file beside the draft, named after it: for `billing.velle`, `billing.questions.md`. An entry is the question in business words, the date, and who can answer it if they said. It may quote the compiler, and it may say what the spec does not have. Three kinds go there:

- something the discussion left open;
- something decided that Velle cannot yet say;
- a question to the engineer that went unanswered.

Leave the spec as it was on an open point. Never write your best guess into the spec without a `-- Not confirmed` comment that says what you guessed between. Remove an entry when a later change answers it.

## Comments in the spec

A spec's comments are the team's own words, and the compiler does not read them, so `compare` never shows them. When your edit makes a comment near it untrue, show the engineer the comment and the wording you propose, and change it on their word. The only comment you write unasked is `-- Not confirmed`, and you remove it when its question is answered.

## Commits

Commit only when someone asks. If the draft is in a git repository you may say once, in passing, that you can commit each decision with the words that led to it. When they ask for that, each commit holds the draft and the questions file, its first line is the decision in the words of the person who made it, and its body holds the report's "What was edited" part and the quoted discussion. "Commit what we have" means one commit of everything so far, with one line per decision in the body. Never create a branch and never push. A session never needs git.

## Best practices on an existing spec

Everything you write follows the best practices. When a rule already in the spec does not follow one, never rewrite it on your own. Propose the rewrite, name the best practice and whether it is Velle's or the team's, and show its report like any other change. A rewrite that changes no meaning shows in the report as meaning the same.

## Ending the session

1. Run `compare` from the starting version to the draft and show the report. It is every change of the session at once.
2. List the `-- Not confirmed` comments still in the draft, each with its question, in one message. Do not ask them one by one. Whatever they answer, act on. The rest stay in the spec for its next reader.
3. List what is in the questions file.
4. Say where the draft is and whether it validates. Do not push, merge, or open a review. Those are theirs to do.

When someone has said they are done, keep this to one short message.

## References

- `references/language.md`: how each Velle construct is written, what the compiler refuses, and which construct says what. It links the other files in that folder.
- `references/Best-practices.md`: Velle's best practices for a readable spec. Its links and the example files it names belong to Velle's full documentation, which is not in this skill. `references/language.md` covers the constructs it mentions.
