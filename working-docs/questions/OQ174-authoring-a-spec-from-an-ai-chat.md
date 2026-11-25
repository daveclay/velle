# OQ174 — How an AI assistant helps a product owner and an engineer write a spec during a design discussion

**Status:** opened 2026-11-25 as a one-line question, kept apart from OQ173 (settled table, `working-docs/QUESTIONS.md`), which serves a running system; given this file the same day from Dave's chat. Not designed. D1 to D8 await Dave. Nothing built.

**In plain terms:** a product owner and an engineer talk through how their business works, and an AI assistant listening in writes what they decide into a Velle spec as they go. After each change the assistant reads back what the spec now says, in words the compiler wrote, so the product owner can confirm it. The first version works in an AI chat. Listening to a live meeting's audio comes later.

**See:** `wiki/Guides/Comparing-specs.md` (the report the read-back is made of); `wiki/Guides/AI-chat.md` and `wiki/Guides/Serving-a-system.md`, "An AI chat session" (the MCP server for a running system, which this question does not change); `compiler/src/main/kotlin/velle/Sentences.kt` (the words the compiler has for each form); `compiler/src/main/kotlin/velle/Compare.kt` and `Generate.kt` (the two commands that exist today); `apis/mcp/` (where a second server would sit); OQ152 (the console, where a meeting view could live later); OQ172 (pointing each consequence at the edit that causes it, which the read-back needs).

---

## 1. Terms used in this file

- **Skill**: a folder an AI assistant loads when a task calls for it. The folder holds a file named `SKILL.md`, whose first lines give the skill a name and a one-sentence description, and whose body is the instructions. It may also hold scripts the assistant runs and reference files it reads only when it needs them. The format is an open one. Claude Code and claude.ai read it, and other assistants read it as well (D1 names what is not yet checked).
- **Host**: the AI assistant application that loads the skill, such as Claude Code, claude.ai, or another vendor's assistant.
- **Design session**: one sitting in which a product owner and an engineer change the rules of a system with the assistant writing the spec.
- **Draft**: the `.velle` file being changed during a design session.
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

### D1. Velle ships a skill as the way an assistant writes a spec, and the authoring MCP server only serves a host that cannot run commands

Does Velle ship a design-session skill in the open skill format as the main way an assistant helps write a spec, with an authoring MCP server as the route for a host whose skill cannot run a command?

Recommendation: yes. The skill holds the procedure: when to edit, what to read back, what to ask. That procedure is the same on every host. How the assistant reaches the compiler is the part that varies. A host that runs commands uses a script in the skill (D3). A host that does not, such as an assistant with no sandbox, uses the MCP server's tools of the same names. The one-line question this file grew from asked about the server alone, and the server alone gives an assistant tools without telling it how to run a design session.

Costs:

- Two ways to reach the same commands. Both must answer the same way, so the MCP server must call the same code as the script, and a test must check that they agree.
- Which hosts besides Claude Code and claude.ai read the format, and which of them let a skill run a script, has not been checked. It is a work item (§5), to be done before the skill is described as working anywhere else.
- Leans on "Velle captures the product-owner and engineer conversation as a pure business layer" (root `CLAUDE.md`): the skill is that conversation's tool, not a new part of the language.

#### Proposed changes

- A new folder holds the skill (D6).
- `apis/mcp/` gains a second server, apart from `VelleMcp`, whose tools are D3's subcommands.

### D2. The product owner confirms the compiler's sentences, never the assistant's summary

Does the skill show the product owner, after each edit, only the sentences the compiler wrote about it, and never its own summary of what it changed?

Recommendation: yes. Otherwise the assistant states a decision in its own words, writes some Velle, and nobody can tell that the two say the same thing. The comparison's sentences come from the spec, so a product owner who confirms them has confirmed the spec. The "What that changes" part of the report names rules nobody edited, which is the side effect a room most needs to hear while it can still decide (`wiki/Guides/Comparing-specs.md`, "What it prints").

The assistant may add a sentence of its own to ask something, such as "Is that what you meant?", but never one that restates the rule.

Costs:

- Where `Sentences` has no words for a form, the read-back shows Velle (§2). A product owner will see code until those forms have words. D3 lists them as work.
- A consequence today points at every edit whose names it mentions, not at the edit that causes it (OQ172). In a session with several edits between read-backs, the product owner cannot tell which decision caused which consequence. One read-back per edit avoids it until OQ172 is built.
- The compiler's sentences are fixed English. A room that speaks another language reads them in English.

### D3. One command, `velle`, with four subcommands

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

### D4. The compiler ships inside the skill as one self-contained jar

Is the compiler shipped inside the skill folder, as one jar that holds the Kotlin standard library, rather than downloaded the first time the skill runs?

Recommendation: yes, inside the skill. The compiler jar is 1.9 MB and the standard library 1.8 MB (§2), so the jar is about 3.7 MB. A download needs network access from the host's sandbox, which not every host allows.

Costs:

- The host must have Java 21 (`compiler/build.gradle.kts`, `jvmTarget`). A host whose sandbox has no Java cannot run the skill's script and needs the MCP server (D1).
- A skill may be refused by a host that limits the size of an uploaded skill. The limits are not checked (§5).
- A copy of the compiler goes out with every published skill. A fix to the compiler reaches users only when they take a new copy of the skill.

#### Proposed changes

- A Gradle task in `compiler/build.gradle.kts` that builds the self-contained jar and copies it into the skill folder.

### D5. The skill carries Velle's own extension libraries and no others

Does the skill's jar include the extension libraries under `extensions/`, so a spec that uses `Money` validates, while a spec using any other library cannot be run through the skill?

Recommendation: yes. `examples/billing/billing.velle` uses `Money` (§2), and it is the spec the guides use. A system's own libraries are the owner's choice of classpath ("A binding is a library", root `CLAUDE.md`), and the skill cannot know them.

Costs:

- A team with its own extension types cannot use the skill on their spec without building their own copy of the jar, or using the MCP server running on their own classpath.
- `extensions/finance-sql` is a store binding, not needed to validate or to `try` in memory, and stays out.

### D6. The skill lives in its own folder at the repo root

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

### D7. Each accepted decision is one commit quoting the discussion

When the draft is in a git repository, does the skill commit each decision the product owner accepts as its own commit, whose message quotes what was said that led to it?

Recommendation: yes. A rule in the spec can then be traced back to the conversation that made it with `git blame`, and nothing else records why it is there, as Velle keeps no reasoning beside the spec. A decision the product owner rejects is undone before the next edit, and leaves no commit.

Costs:

- A host without git, or a draft not in a repository, has no history. The skill then keeps the quotes nowhere, and says so at the start of the session.
- A quoted message carries what people said in a meeting into a repository others read. The skill asks before the first commit of a session.

### D8. What the room leaves undecided is a list beside the draft, never a guess inside it

When the discussion leaves something undecided, does the skill write it as a question in a file beside the draft, and leave the spec without it, rather than write its own best guess into the spec?

Recommendation: yes. The compiler errors when a statement's meaning is undefined (root `CLAUDE.md`, design principles), and an error is the assistant's signal to ask the room, not to choose for it. Likewise, when the validator cannot prove a `never`, the skill puts the case to the room as a business question: "Can a payment arrive after an invoice is archived?"

Costs:

- The file's name and form are the skill's own, with no meaning to the compiler. A spec can validate while the list is long.
- Turning a validator diagnostic into a business question is the assistant's wording, which D2 otherwise forbids for rules. It is allowed for questions because a question changes nothing in the spec.

## 4. Not in this question

- Listening to a meeting's audio. Dave, 2026-11-25: "it is likely sufficient to first capture a design session in an AI chat in velle before considering audio transcription." It gets its own question once a design session in chat has been tried. It adds a speech-to-text service that labels speakers, and the question of when the assistant edits, since it should not edit on every sentence spoken.
- A meeting view in the console (OQ152).
- The MCP server for a running system (OQ173, settled table), which does not change.

## 5. Work items

- [ ] Check which assistants besides Claude Code and claude.ai read the open skill format, whether each lets a skill run a script, whether each sandbox has Java 21, and what size each allows a skill to be (D1, D4).
- [ ] List the forms in `examples/` for which `Sentences` has no words, each one a gap a product owner would see as Velle (D2, D3).
- [ ] Try a design session in chat by rebuilding part of `examples/billing/billing.velle` from an empty draft, before any audio work (§4).
