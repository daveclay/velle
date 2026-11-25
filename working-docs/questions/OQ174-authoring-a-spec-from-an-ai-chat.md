# OQ174 — How an AI assistant helps a product owner and an engineer write a spec during a design discussion

**Status:** opened 2026-11-25 as a one-line question, kept apart from OQ173 (settled table, `working-docs/QUESTIONS.md`), which serves a running system; given this file the same day from Dave's chat. D1 to D9 decided. The build plan (§6) awaits Dave's review; nothing built. Nothing built.

**In plain terms:** a product owner and an engineer talk through how their business works, and an AI assistant listening in writes what they decide into a Velle spec as they go. After each change the assistant reads back what the spec now says, in words the compiler wrote, so the product owner can confirm it. The first version works in an AI chat. Listening to a live meeting's audio comes later.

**See:** `wiki/Guides/Comparing-specs.md` (the report the read-back is made of); `wiki/Guides/AI-chat.md` and `wiki/Guides/Serving-a-system.md`, "An AI chat session" (the MCP server for a running system, which this question does not change); `compiler/src/main/kotlin/velle/Sentences.kt` (the words the compiler has for each form); `compiler/src/main/kotlin/velle/Compare.kt` and `Generate.kt` (the two commands that exist today); `apis/mcp/` (where a second server would sit); OQ152 (the console, where a meeting view could live later); OQ172 (pointing each consequence at the edit that causes it, which the read-back needs).

---

## 1. Terms used in this file

- **Skill**: a folder an AI assistant loads when a task calls for it. The folder holds a file named `SKILL.md`, whose first lines give the skill a name and a one-sentence description, and whose body is the instructions. It may also hold scripts the assistant runs and reference files it reads only when it needs them. The format is an open one. Claude Code and claude.ai read it, and other assistants read it as well (D1 names what is not yet checked).
- **Host**: the AI assistant application that loads the skill, such as Claude Code, claude.ai, or another vendor's assistant.
- **Design session**: one sitting in which a product owner and an engineer change the rules of a system with the assistant writing the spec.
- **Draft**: the `.velle` file being changed during a design session. Most sessions start from a spec that already exists; only a system's first session starts from an empty file.
- **Starting version**: the draft as it stood when the session began.
- **Read-back**: what the assistant shows the product owner after an edit: the comparison's sentences for that edit, written by the compiler, never the assistant's own summary.

## 2. What runs today

Checked against HEAD on 2026-11-25.

- **Comparing two versions.** `velle.CompareKt <before.velle> <after.velle>` prints what was edited, what that changes for declarations nobody edited, and what it does to a record, each sentence pointing at a line of the spec (`compiler/src/main/kotlin/velle/Compare.kt`, `wiki/Guides/Comparing-specs.md`, `SpecComparisonTest`). It refuses a draft that does not validate, with the diagnostics as an exception message (`Compare.kt`, `readValidated`).
- **Validating.** There is no command that only validates. `velle.GenerateKt` validates first and then writes generated Kotlin into the directory it runs in (`compiler/src/main/kotlin/velle/Generate.kt`). `working-docs/examples/survey/check.sh` uses it for validation alone and discards the generated files.
- **Describing a whole spec.** There is no command for it. `Sentences` has words for many forms and returns null for a form it has no words for, and its caller then prints the Velle (`compiler/src/main/kotlin/velle/Sentences.kt`, the comment on `object Sentences`). So a description of a whole spec would show some Velle to the product owner.
- **Running a draft.** A spec runs in memory over `MemoryStore` (`compiler/src/main/kotlin/velle/MemoryStore.kt`), and a dry run of an act exists (`DryRunTest`). Neither is reachable from a command line.
- **Running the compiler outside Gradle.** `compiler/build/libs/compiler.jar` (1.9 MB) does not hold the Kotlin standard library. `working-docs/examples/survey/check.sh` adds it to the classpath by a path into one machine's Gradle cache. Another machine, or a host's sandbox, has no such path.
- **Extension libraries.** A spec's `uses` lines name types and functions found on the classpath through Java's `ServiceLoader` (`compiler/src/main/kotlin/velle/TypeRegistry.kt`). `examples/billing/billing.velle` uses `Money` from `extensions/finance`, so it does not validate with the compiler jar alone.
- **The repo's own skills.** `oq`, `implement`, and `ub-mode` live in `.claude/skills/`, which only Claude Code reads. They use only `name` and `description` in their first lines, which is the open format.

## 3. What to decide

### D1. Velle ships a skill as the way an assistant writes a spec, and the authoring MCP server only serves a host that cannot run commands · Decided 2026-11-25

Does Velle ship a design-session skill in the open skill format as the main way an assistant helps write a spec, with an authoring MCP server as the route for a host whose skill cannot run a command?

Recommendation: yes. The skill holds the procedure: when to edit, what to read back, what to ask. That procedure is the same on every host. How the assistant reaches the compiler is the part that varies. A host that runs commands uses a script in the skill (D3). A host that does not, such as an assistant with no sandbox, uses the MCP server's tools of the same names. The one-line question this file grew from asked about the server alone, and the server alone gives an assistant tools without telling it how to run a design session.

Costs:

- Two ways to reach the same commands. Both must answer the same way, so the MCP server must call the same code as the script, and a test must check that they agree.
- Which hosts besides Claude Code and claude.ai read the format, and which of them let a skill run a script, has not been checked. It is a work item (§5), to be done before the skill is described as working anywhere else.
- Leans on "Velle captures the product-owner and engineer conversation as a pure business layer" (root `CLAUDE.md`): the skill is that conversation's tool, not a new part of the language.

#### Proposed changes

- A new folder holds the skill (D6).
- `apis/mcp/` gains a second server, apart from `VelleMcp`, whose tools are D3's subcommands.

**Decided:** yes, 2026-11-25.

### D2. The product owner confirms the compiler's sentences, never the assistant's summary · Decided 2026-11-25

Does the skill show the product owner, after each edit, only the sentences the compiler wrote about it, and never its own summary of what it changed?

Recommendation: yes. Otherwise the assistant states a decision in its own words, writes some Velle, and nobody can tell that the two say the same thing. The comparison's sentences come from the spec, so a product owner who confirms them has confirmed the spec. The "What that changes" part of the report names rules nobody edited, which is the side effect a room most needs to hear while it can still decide (`wiki/Guides/Comparing-specs.md`, "What it prints").

The assistant may add a sentence of its own to ask something, such as "Is that what you meant?", but never one that restates the rule.

Costs:

- Where `Sentences` has no words for a form, the read-back shows Velle (§2). A product owner will see code until those forms have words. D3 lists them as work.
- A consequence today points at every edit whose names it mentions, not at the edit that causes it (OQ172). In a session with several edits between read-backs, the product owner cannot tell which decision caused which consequence. One read-back per edit avoids it until OQ172 is built.
- The compiler's sentences are fixed English. A room that speaks another language reads them in English.

**Decided:** yes, 2026-11-25.

### D3. One command, `velle`, with four subcommands · Decided 2026-11-25

Does the skill reach the compiler through one command, `velle`, with the subcommands `validate`, `compare`, `describe`, and `try`, each printing Markdown?

- `velle validate <draft>`: the diagnostics, or a line saying the draft validates. Writes nothing.
- `velle compare <before> <after>`: the report `velle.CompareKt` prints today.
- `velle describe <draft>`: the whole spec as sentences, in the words `Sentences` and `RuleSentences` already use.
- `velle try <draft> <scenario>`: runs a list of acts over an empty in-memory store and prints, for each act, whether it was accepted, and if refused, which `never` refused it and why. Nothing is kept between runs.

Recommendation: yes, with `validate` and `compare` built first, since both exist as code today. `describe` and `try` are new. A product owner tests a rule fastest by saying an example out loud ("say the customer pays twice"), which is what `try` is for.

Costs:

- `describe` shows Velle for every form `Sentences` has no words for. Finding those forms across `examples/` is a work item, and each one is a gap in `Sentences`.
- The scenario format for `try` is new and needs its own decision once the first build shows what a scenario has to say. It is not designed here.
- Output in Markdown is for an assistant to read. A program that wants to read the result would want JSON. Nothing wants that yet.

#### Proposed changes

- A new `velle.Cli` entry point in `compiler/src/main/kotlin/velle/` with the four subcommands.
- `compiler/src/main/kotlin/velle/Compare.kt` becomes the `compare` subcommand.
- A new test per subcommand over `examples/billing/billing.velle`.

**Decided:** yes, 2026-11-25.

### D4. The compiler ships inside the skill as one self-contained jar · Decided 2026-11-25

Is the compiler shipped inside the skill folder, as one jar that holds the Kotlin standard library, rather than downloaded the first time the skill runs?

Recommendation: yes, inside the skill. The compiler jar is 1.9 MB and the standard library 1.8 MB (§2), so the jar is about 3.7 MB. A download needs network access from the host's sandbox, which not every host allows.

Costs:

- The host must have Java 21 (`compiler/build.gradle.kts`, `jvmTarget`). A host whose sandbox has no Java cannot run the skill's script and needs the MCP server (D1).
- A skill may be refused by a host that limits the size of an uploaded skill. The limits are not checked (§5).
- A copy of the compiler goes out with every published skill. A fix to the compiler reaches users only when they take a new copy of the skill.

#### Proposed changes

- A Gradle task in `compiler/build.gradle.kts` that builds the self-contained jar and copies it into the skill folder.

**Decided:** yes, 2026-11-25. Dave presumes separate jars per tool could be built later to keep the size down.

### D5. The skill carries Velle's own extension libraries and no others · Decided 2026-11-25

Does the skill's jar include the extension libraries under `extensions/`, so a spec that uses `Money` validates, while a spec using any other library cannot be run through the skill?

Recommendation: yes. `examples/billing/billing.velle` uses `Money` (§2), and it is the spec the guides use. A system's own libraries are the owner's choice of classpath ("A binding is a library", root `CLAUDE.md`), and the skill cannot know them.

Costs:

- A team with its own extension types cannot use the skill on their spec without building their own copy of the jar, or using the MCP server running on their own classpath.
- `extensions/finance-sql` is a store binding, not needed to validate or to `try` in memory, and stays out.

**Decided:** yes, 2026-11-25.

### D6. The skill lives in its own folder at the repo root · Decided 2026-11-25

Does the skill live in `skills/velle-design/` at the repo root, outside `.claude/`, and is it published from there?

Recommendation: yes. `.claude/skills/` is read only by Claude Code and holds this repo's own working procedures (`oq`, `implement`), which no other team should load. A folder of its own can be copied, zipped, or uploaded to any host. Claude Code loads it from there when pointed at it.

The folder:

- `SKILL.md`: the procedure, and how to speak to a product owner (D2).
- `scripts/velle` and the jar (D3, D4).
- `references/`: a condensed Velle reference, read only when the assistant needs it, since the wiki is not there when another team's assistant loads the skill.

Costs:

- The condensed reference is a second copy of what `wiki/Reference/` says and goes stale when the wiki changes. A test that fails when they drift is a work item.
- A new top-level folder, which the root `CLAUDE.md` list of folders must name.

#### Proposed changes

- A new `skills/velle-design/` with the files above.
- The root `CLAUDE.md` names the folder.

**Decided:** yes, 2026-11-25.

### D7. Each accepted decision is one commit quoting the discussion · Decided 2026-11-25

When the draft is in a git repository, does the skill commit each decision the product owner accepts as its own commit, whose message quotes what was said that led to it?

Recommendation: yes. A rule in the spec can then be traced back to the conversation that made it with `git blame`, and nothing else records why it is there, as Velle keeps no reasoning beside the spec. A decision the product owner rejects is undone before the next edit, and leaves no commit.

Costs:

- A host without git, or a draft not in a repository, has no history. The skill then keeps the quotes nowhere, and says so at the start of the session.
- A quoted message carries what people said in a meeting into a repository others read. The skill asks before the first commit of a session.

**Decided:** yes, 2026-11-25, on the condition that a machine without git can still produce a Velle spec. Git is never required: the `velle` command never calls it, and every step of a design session but the commit runs without it, so a session on such a machine ends with the same draft and no history. Dave, 2026-11-25: whether a meeting works on a copy of a checked-in spec or on a branch is the team's choice and outside Velle's scope, so the skill commits only where it finds the draft, and never creates a branch, copies a spec, or pushes.

### D8. What the room leaves undecided is a list beside the draft, never a guess inside it · Decided 2026-11-25

When the discussion leaves something undecided, does the skill write it as a question in a file beside the draft, and leave the spec without it, rather than write its own best guess into the spec?

Recommendation: yes. The compiler errors when a statement's meaning is undefined (root `CLAUDE.md`, design principles), and an error is the assistant's signal to ask the room, not to choose for it. Likewise, when the validator cannot prove a `never`, the skill puts the case to the room as a business question: "Can a payment arrive after an invoice is archived?"

Costs:

- The file's name and form are the skill's own, with no meaning to the compiler. A spec can validate while the list is long.
- Turning a validator diagnostic into a business question is the assistant's wording, which D2 otherwise forbids for rules. It is allowed for questions because a question changes nothing in the spec.

**Decided:** yes, 2026-11-25.

### D9. `velle describe` can describe one part of a spec · Decided 2026-11-25

Does `velle describe` take an optional name of a shape or an act, and then describe only that declaration and every rule and `never` that reads or writes it?

Most sessions change a spec that already exists (Dave, 2026-11-25), and the room starts by asking what the spec says today about the thing they came to change. `examples/pharmacy/pharmacy.velle` is 1,095 lines, and a description of all of it is too long to read in a meeting. Recommendation: yes. With no name, `describe` describes the whole spec as D3 decided.

Costs:

- "Every rule that reads or writes it" is a choice of what to include. A rule that reads the shape through a refinement, such as one over `OverdueInvoice` in `examples/billing/billing.velle`, which is an `Invoice`, is included; a rule three relationships away is not. The first build includes the declaration's refinements and the rules and prohibitions over them, and the trial session (§6) shows whether that is enough.
- A part described alone hides what other parts do to it. The read-back after an edit is still the full comparison (D2), which names consequences anywhere in the spec, so nothing is hidden from the product owner when it changes.

#### Proposed changes

- `velle describe <draft> [<name>]` in `velle.Cli`, with a test describing `Invoice` in `examples/billing/billing.velle`.

**Decided:** yes, 2026-11-25. Dave expects real-world specs to be too big in general and would split one into several files to organize a large system, which is not a priority (OQ175, `working-docs/QUESTIONS.md`); reading a large spec in parts is worth having whether or not a spec can be split.

## 4. Not in this question

- Listening to a meeting's audio. Dave, 2026-11-25: "it is likely sufficient to first capture a design session in an AI chat in velle before considering audio transcription." It gets its own question once a design session in chat has been tried. It adds a speech-to-text service that labels speakers, and the question of when the assistant edits, since it should not edit on every sentence spoken.
- A meeting view in the console (OQ152).
- The MCP server for a running system (OQ173, settled table), which does not change.
- How a team iterates on a spec in git: a copy of a checked-in spec or a branch, and how the result is reviewed and merged. Dave, 2026-11-25: outside Velle's scope (D7).

## 5. Work items

- [ ] Check which assistants besides Claude Code and claude.ai read the open skill format, whether each lets a skill run a script, whether each sandbox has Java 21, and what size each allows a skill to be (D1, D4).
- [ ] List the forms in `examples/` for which `Sentences` has no words, each one a gap a product owner would see as Velle (D2, D3).
- [ ] Add `velle.Cli` with `validate`, `compare`, and `describe`, `describe` taking an optional name (D9), and make `Compare.kt` the `compare` subcommand (§6, stage 1).
- [ ] Add the Gradle task that builds the self-contained jar with `extensions/finance` and copies it into the skill folder (§6, stage 1).
- [ ] Write `skills/velle-design/`: `SKILL.md`, which tells the assistant to follow the best practices page, `scripts/velle`, `references/` (§6, stage 1).
- [ ] Add the test that fails when `references/` and `wiki/Reference/` disagree, or the copy of the best practices page differs from `wiki/Guides/Best-practices.md` (§6, stage 1).
- [ ] Try two design sessions in chat, before any audio work: one changing `examples/billing/changes/00-billing.velle` toward the changes in `examples/billing/changes/README.md`, and one rebuilding part of the billing spec from an empty draft (§4, §6 stage 1).
- [ ] Write the decision on what a scenario for `velle try` says, from what stage 1's trial session needed (§6, stage 2).
- [ ] Add `velle try` (§6, stage 2).
- [ ] Add the authoring MCP server in `apis/mcp/` over the same code as `velle.Cli` (§6, stage 3).

## 6. The build

Three stages, each landing on its own. Stage 1 is enough for a design session in Claude Code or claude.ai. The work items in §5 mirror this list.

### Stage 1: the skill with `validate`, `compare`, and `describe`

1. **Code.** A new `velle.Cli` in `compiler/src/main/kotlin/velle/` with the subcommands `validate`, `compare`, and `describe`, each printing Markdown (D3). `validate` prints the diagnostics or a line saying the draft validates, and writes no file. `compare` is the body of today's `Compare.kt`, which stays as a thin entry point so `wiki/Guides/Comparing-specs.md` keeps working. `describe` prints the whole spec in the words of `Sentences` and `RuleSentences`, showing the Velle for a form they have no words for, or one part of it when given a name (D9). Nothing in it calls git (D7).
2. **The jar.** A Gradle task in `compiler/build.gradle.kts` builds one jar holding the compiler, the Kotlin standard library, and `extensions/finance`, and no store binding (D4, D5). It copies the jar into `skills/velle-design/scripts/`.
3. **The skill.** `skills/velle-design/` at the repo root (D6):
   - `SKILL.md` with `name` and `description` only in its first lines (D1). Its body is the procedure: open the draft the team gives it, existing or empty, keep the starting version, and describe the part the room came to change (D9); then for each change edit the draft, run `validate`, run `compare` against the version before the edit, show the product owner only the compiler's sentences and ask whether that is what they meant (D2); on acceptance commit with the quoted discussion when the draft is in git and the room agreed at the first commit, otherwise keep going without history and say so at the start (D7); on rejection undo the edit; write what the room leaves undecided, and every proof the validator cannot make, as a question in a file beside the draft (D8). Everything it writes into the draft follows `references/Best-practices.md`, such as naming the parts of a long condition as refinements. It does not rewrite a part of an existing spec the room did not come to change to meet a practice; it offers the rewrite as a change, which the room accepts or rejects like any other. At the end of the session it runs `compare` from the starting version to the draft, so the room reads every change of the meeting at once. It works on the file where it finds it and never creates a branch, copies a spec, or pushes (D7). It says to use the authoring MCP tools when they are connected and the script otherwise (D1).
   - `scripts/velle`, a shell launcher running the jar with `java`, and the jar.
   - `references/`, a condensed Velle reference from `wiki/Reference/`, and a copy of `wiki/Guides/Best-practices.md`.
4. **Tests.** One per subcommand over `examples/billing/billing.velle`, which uses `Money`, run against the built jar so the standard library and `extensions/finance` are proven to be inside it. A test that fails when `references/` names a keyword `wiki/Reference/` does not, or the reverse, or when `references/Best-practices.md` differs from `wiki/Guides/Best-practices.md` (D6).
5. **Wiki.** A new guide, `wiki/Guides/Design-session.md`: loading the skill in Claude Code and in claude.ai, a session changing an existing spec, a first session from an empty draft, what the product owner sees, and what a machine without git or Java gets. Linked from `Home.md` and `_Sidebar.md`. `GLOSSARY.md` gains "skill". `Comparing-specs.md` gains the `velle compare` spelling.
6. **Repo.** The root `CLAUDE.md` names `skills/` in its list of folders.
7. **Trial.** Two design sessions in chat. The main one changes an existing spec: it starts from `examples/billing/changes/00-billing.velle` and makes some of the changes listed in `examples/billing/changes/README.md` from a discussion, and its reports can be checked against the reports already in that folder. The second rebuilds part of the billing spec from an empty draft. What they needed and lacked goes into this file before stage 2.

### Stage 2: `velle try`

Waits on a decision, written into this file after stage 1's trial, on what a scenario says. Then `try` runs the scenario over an empty `MemoryStore` with each act committed in turn and prints, per act, accepted or refused with the `never` that refused it (D3). The skill's procedure gains "when the room gives an example, run it". A test per outcome over `examples/billing/billing.velle`.

### Stage 3: the authoring MCP server

A server in `apis/mcp/`, apart from `VelleMcp`, whose tools are `validate`, `compare`, `describe`, and `try`, calling the same code as `velle.Cli` (D1). A test that the server and the command give the same answer for the same draft. `wiki/Guides/Design-session.md` gains connecting it, and `wiki/Guides/API-layer.md` the server. The settled row moves into the table in the commit that lands this stage, and this file is deleted in it.
