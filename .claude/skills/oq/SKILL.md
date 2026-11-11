---
name: oq
description: How to work with working-docs/QUESTIONS.md and the OQ files under working-docs/questions/. Use whenever a task opens, edits, asks Dave about, settles, builds, or deletes an open question (OQ), adds or re-homes a `- [ ]` work item, or touches the settled table. Load before editing any of those files.
---

# Open questions (OQ) and QUESTIONS.md

An OQ is a work item whose context does not fit in one line. There is one
list of work, `working-docs/QUESTIONS.md`. An OQ file is where a line's
context lives, not a different kind of thing. Everything below follows from
that and from corrections Dave has given; each rule names the correction so
the reason is not lost.

This skill covers the first four phases of a piece of Velle work: pick or
open an OQ (§2), investigate it (§3), decide it with Dave, one decision at a
time (§4), and write the implementation plan (§6). The `implement` skill takes
over from there. The writing rules for everything shown to Dave are in the
root `CLAUDE.md` under "Writing for Dave"; the design principles that decide
a question by default are under "Design principles" there.

## 1. The files

`working-docs/` holds exactly three things: `QUESTIONS.md`, `questions/`,
and `examples/`. Nothing else. No TODO.md, no investigate-, break-, or
audit- files. An investigation is part of an OQ file and dies with it.

### QUESTIONS.md, the index

- Opening paragraphs state the rules and the next free tag
  (`next free: OQ<n>`). Bump the number every time an OQ is opened.
- Sections are the part of Velle an item changes (Calibration, Prohibitions,
  Validator proofs, Expressions, Acts, Serialization domains, Spec
  generation, Reads, Serving, Velle UI, Runtime, Deletion, Larger language
  questions, Examples and docs). File an item by what it changes, never by
  where it was found.
- An OQ line: `- **[OQ<n>](questions/OQ<n>-<slug>.md)** — <the question in
  plain words> · <status: opened when, from where, what awaits whom>`.
- A work item: `  - [ ] <verb-first action>, one line.` It sits under the OQ
  that holds its context, or stands alone when its whole context fits in
  the line. Context never goes in QUESTIONS.md itself.
- The settled table at the bottom: `| OQ<n> — <title> | <where it retired>
  |`. New rows go at the top of the table.

### An OQ file, `questions/OQ<n>-<slug>.md`

Header: three paragraphs, each on its own with a blank line between,
then a `---` rule.

```
# OQ<n> — <the question in plain words>

**Status:** opened <date> from <where it surfaced>. <Where it stands: not designed; D1 and D2 await Dave; settled, build pending; and so on.>

**In plain terms:** one or two sentences a product owner could read, zero jargon.

**See:** <Reference §N, wiki pages, example files, related OQs, each with a few words saying why>

---
```

Rules for the header (Dave, 2026-11-11):

- Status, In plain terms, and See are three separate paragraphs. Never
  stack them as consecutive lines of one paragraph.
- Status says where the question stands and nothing more. It does not
  restate what any decision decided; that lives in the decision's own
  `### D<n>.` section and nowhere else in the file. "D1 and D3 settled, D2
  awaits Dave" is status. "D1: the compiler refuses a comparison between
  differing kinds" in the status line is a duplicate, and when Dave reverses
  a decision one of the two copies goes stale.
- The same holds for any opening or summary section above the decisions:
  it points at the decision sections, it does not repeat them.

Below the rule, numbered sections `## 1.`, `## 2.` ... Typical shape:

1. What runs today, checked against HEAD, with the example files that show it.
2. What to decide: one decision section per decision, in the shape below.
3. The build, once decided: the files and docs that change.

A decision section (Dave, 2026-11-11):

```
### D<n>. <the decision in one sentence>

<The decision text: the yes-or-no as Dave would say it, the recommendation,
the costs as a short list, the full-context Velle. Written once, when the
decision is proposed, and not rewritten when it is answered.>

#### Proposed changes

- <one change per line: what the compiler stops accepting or starts
  accepting, what a page of the wiki gains or loses, what an example is
  rewritten to, each naming the file>

**Decided:** <Dave's answer in one sentence, with the date>. <His reasoning,
and any reversal with its date.>
```

- The header of a decided section ends with `· Decided <date>`, so a scan of
  the headers shows which decisions are open:
  `### D2. Whether Velle joins two texts · Decided 2026-11-11`. An open
  decision's header has no suffix.
- The decision text and the decision notes are two separate parts. The text
  above the `**Decided:**` paragraph is what was asked; the paragraph is what
  Dave answered and why. Do not fold his answer into the proposal, and do not
  rewrite the proposal to match the answer.
- A reversal is a second sentence in the `**Decided:**` paragraph with its
  date, not an edit of the first.
- When the decision proposes changes, they are listed under their own
  `#### Proposed changes` heading inside the decision, between the decision
  text and the `**Decided:**` paragraph, one change per line. The decision
  text argues; the list says what changes, by file. A decision that changes
  nothing, such as one that keeps today's behaviour, has no such list. The
  file's build section (§"The build") gathers the lists of every decided
  decision; it does not restate them in different words.

Decisions are numbered once and never renumbered. A decision moved to another
OQ keeps a one-line pointer in place.

## 2. Opening an OQ

1. **Check for an existing OQ first.** Grep `QUESTIONS.md` and
   `questions/` for the topic. Do not open an OQ when a relevant one exists
   (Dave, 2026-10-14). Residue from a settled OQ goes to an existing OQ or a
   bare `- [ ]` line; ask Dave before opening a new one for it.
2. Take the next free tag from `QUESTIONS.md`, bump it there. Tags are never
   reused or renumbered. Gaps mean settled.
3. Write the file with the header above. "In plain terms" is mandatory.
4. Add the OQ line under the right section of `QUESTIONS.md`, with its
   `- [ ]` items under it.
5. Opening at birth: any design question with discussion worth keeping, or
   referenced from more than one doc, gets its tag the moment it surfaces,
   wherever it surfaced (a wiki pass, a stress pass, a chat).

## 3. Investigating

The investigation lives in the OQ file's first sections, "What runs today"
and the cases, and nowhere else. No separate investigation file.

- **Check against HEAD, not memory.** State what the compiler does today by
  compiling a spec that shows it. Rebuild `compiler/build/libs/compiler.jar`
  from committed source first; a jar built from uncommitted edits made
  `working-docs/examples/survey/check.sh` contradict the OQ (2026-11-10).
- **One survey file per spelling.** Each case is a `.velle` file under
  `working-docs/examples/survey/`, named
  `<nn>-<topic>-<accepted|refused|workaround>.velle`, with a line in that
  folder's README saying what it produces. `accepted` means it validates
  although the Reference does not admit it; `refused` means the compiler
  refuses it; `workaround` is what an author writes today instead.
- **A probe that needs the runtime is a test**, such as
  `compiler/src/test/kotlin/velle/LookupProbeTest.kt` for OQ74. The OQ cites
  the test by file and name. A claim with no checked-in artifact behind it
  is not a finding.
- **State the premise before any example.** Dave on OQ74 (2026-11-08): a
  previous session "kept conflating multiple other problems" because the
  file used one example's vocabulary as the question's. The plain-terms
  paragraph states the problem; the examples come after.
- **Keep words apart that name different things.** OQ74 kept "store key",
  what the store assigns a row, apart from "identifying values", what a
  human supplies, and never used "id" alone.
- **What belongs to another OQ goes there** the moment it comes up, with a
  one-line pointer left behind, even mid-discussion.
- **Findings from a stress pass** are gaps in Velle, never softened because
  the domain could live with them (`examples/CLAUDE.md`).

## 4. Asking Dave for a decision

Every decision Dave must make is written into the OQ file as its own
`### D<n>.` section **before** it is mentioned in chat (Dave, 2026-10-27:
"always make decisions in OQ files sections so I can answer them inline and
provide room for context"). The chat message points at the file and section.
It does not carry the decision list itself. When Dave answers, record the
answer in that section's `**Decided:**` paragraph and add `· Decided <date>`
to its header (shape in §1).

Each decision section:

- Opens with the decision as one plain sentence Dave would say, yes-or-no
  where possible. The costs come after, as a short list (Dave, 2026-10-12,
  on OQ49 S7: "worded in such a complicated way, it could have simply
  asked...").
- Carries a recommendation.
- Shows full-context Velle: the `shape` and `act` declarations with their
  field types in view, the `uses` lines, a comment per line saying what the
  compiler does with it, and the alternative spelled against the same spec.
  Bare fragments like `balance < 0` drew "it's not clear what the examples
  are in the text" (Dave, 2026-10-24, OQ65 D2).
- Names the source file of every example it mentions,
  `examples/<dir>/<name>.velle` or `working-docs/examples/survey/<name>.velle`.

Build questions that surface while a design is being settled get the same
treatment: a numbered place in the file, never only prose (Dave asked
"where are those questions" twice on 2026-11-09). When a question is answered
by a rule stated somewhere in the file, say in the same chat message exactly
where it went, by section.

### Removals apart from additions

An OQ that makes the compiler stop accepting something says so up front, as
its own half with its own decisions, apart from what is added in its place.
Each removal names what runs today and is lost: nothing, when the spelling
already fails at runtime; a working spelling, when it does not. A draft that
opens with "add these functions" and buries "the validator refuses X" inside
a recommendation hides a removal (Dave, 2026-11-10, OQ75: "this OQ is in an
inconsistent state").

### Dave's directions apart from my proposals

When recording where an OQ stands, keep what Dave said apart from what I
sketched ahead of his answer. A proposal is not settled because it is in the
file. OQ73 §8 and §9 are sketches; D1 to D19 await him. Do not present a
sketch as decided in a status line, a memory, or a chat summary.

## 5. Labels and rosters

A file that uses labeled items (D3, probe J, finding C1, S7) contains the
complete roster that resolves every label it uses. Add the label to the
roster in the same edit that first uses it (Dave, 2026-08-20: "you just
referenced 'probe J' but it's not in the list of probes").

## 6. The plan, then the build

When every decision carries `· Decided`, the file's last section, "The
build", is the implementation plan. It gathers each decision's "Proposed
changes" list in build order: code, regenerated output, wiki pages,
examples, tests, the settled row. It is mirrored by `- [ ]` items under the
OQ's line in `QUESTIONS.md`. Show Dave the section before building. Then
load the `implement` skill.

### How long the file lives

Settled means Dave has answered every decision. Built means the result is
in the code and the wiki. The file lives until the build lands.

**Do not delete the file when the last decision is answered.** It is deleted
in the commit that lands the build, and never while it is still being
revised. On 2026-11-11 I deleted OQ75 as soon as D3 was answered; Dave
reversed D3 an hour later and asked "where did OQ75 go?" and "how do you know
what to build?", and the file was rebuilt from the conversation. Precedent:
OQ74 stayed from its 2026-11-08 settlement until the 2026-11-09 build
commit.

**Do delete the file in the build commit.** Never keep a built OQ for its
reasoning or its deltas (Dave, 2026-10-24, after I kept OQ65). Reasoning is
git history. "If I want history, I'll use git." The settling commit's steps
are in the `implement` skill §4.

## 7. Reference direction

Working docs point at finalized docs. Finalized docs never point into
`working-docs/`, with one exception: `QUESTIONS.md` by name. In a wiki page
cite a bare tag, `OQ53`, with no path. In a working doc cite the tag and
`working-docs/QUESTIONS.md`, which is stable, not the file, which is deleted
at settling. Cite Reference pages as `Reference §N`, finalized docs by bare
name (`checks.md` V23).

## 8. Before reporting done

- Does `QUESTIONS.md` still say the right next free tag?
- Does every OQ line in `QUESTIONS.md` point at a file that exists, and does
  every file have a line? (`ls working-docs/questions` against a grep.)
- Does any wiki page point into `working-docs/questions/`? It must not.
- Does the chat message point at the file and section for each decision,
  rather than restating the decisions?
- Is every settled-but-unbuilt OQ still on disk?
