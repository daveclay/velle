---
name: implement
description: How to build a decided OQ into Velle, from the implementation plan to the commit that settles it. Use when every decision in an OQ is decided and the work is to implement it in the compiler, document it in the wiki, write or update the examples that exercise it, regenerate the examples' output, run the suite, and land the settling commit that deletes the OQ file. Also use for any compiler change that needs the examples regenerated.
---

# Building a decided OQ

The `oq` skill covers opening, investigating, and deciding. This skill
starts when every decision in the file carries `· Decided` and ends with the
commit that deletes the file. The plan is the OQ file's build section, which
gathers each decision's "Proposed changes" list. Do not re-derive the plan
from the discussion; if the build section is missing or thin, write it from
the decisions first and show Dave where it is.

Directory rules load on their own when files are touched:
`compiler/CLAUDE.md`, `wiki/CLAUDE.md`, `examples/CLAUDE.md`.

## 0. Flag the OQ before any work

Dave, 2026-11-27: "so I don't accidentally work one twice". Before the first
edit of a build, and before a worker is started for it, the OQ's line in
`working-docs/QUESTIONS.md` is flagged on `main`, committed alone, and
pushed:

- The flag is the words `(being built since <date>)` straight after the
  dash: `- **OQ185** — (being built since 2026-11-27) Stop V10 ...`. It
  stands at the front of the line so a scan of the section shows it.
- Read the line first. A line that already carries the flag is being built
  by another session: stop and tell Dave, do not start a second build.
- Commit message: `OQ<n>: build started`. Several OQs started together are
  flagged in one commit, `OQ<a>, OQ<b>: build started`.
- Under §6 the managing session flags every OQ of the round and pushes
  before it creates the first worktree, so each branch starts from the
  flagged line and its settling commit removes it without a conflict.
- The settling commit removes the line, and the flag with it. A build that
  is abandoned or handed back to Dave undecided has its flag removed in a
  commit that says why.

## 1. Order of work

1. **Code.** Compiler, stores, APIs, extensions. Hand-written callers under
   `main` migrate first, then generated output, then tests.
2. **Regenerate** every example's output (§2).
3. **Wiki.** The Reference page, `checks.md`, `evaluation.md`, `grammar.md`,
   `patterns.md`, `GLOSSARY.md`, the guides, as the build section lists. The
   result is phrased positively, with no design history and no OQ tag on any
   wiki page. Generated-Kotlin facts go in `wiki/Guides/Act-API.md` and
   `wiki/Guides/Read-API.md`, never in the Reference.
4. **Examples.** A new feature gets an example that exercises it under
   `examples/`, or an existing one grows. The survey examples under
   `working-docs/examples/survey/` that showed the gap leave that folder
   when the OQ settles (§4); list them now, with the home each one moves to.
5. **Tests.** A test pins each new check, each new runtime behaviour, and
   each bug the OQ found. Run the full suite (§3).
6. **Settle** (§4).

When several OQs are being built at once, each on its own branch, steps 2
and 5 change. See §6 before starting.

## 2. Regenerating the examples

- Modules: `ls -d examples/*/output`. Do not trust a remembered list; a
  module is added with every use case.
- Per module, sequentially: `./gradlew :examples:<m>:output:generate -q
  --max-workers=1`. Parallel runs run out of memory. Each module's
  `generate` task runs on that module's main runtime classpath, so its main
  must compile first.
- When a runtime type the generated code names changes shape, the stale
  generated files break that compile and no module can regenerate. Run the
  generator on the compiler's classpath instead: `cd examples/<m>/output &&
  java -cp "$CP" velle.GenerateKt <args from its build.gradle.kts>`. The
  classpath must hold `compiler/build/classes/kotlin/main`, the kotlin-stdlib
  jar from `~/.gradle/caches`, and for every extension type library the spec
  uses both its classes and its resources directory, such as
  `extensions/finance/build/classes/kotlin/main` and
  `extensions/finance/build/resources/main`. Without the resources directory
  the registry does not find `META-INF/services/velle.ext.VelleType` and the
  spec fails with F1 "no registered type". In zsh split the args with
  `${=ARGS}`.
- Billing's main includes `examples/billing/output/src/main/kotlin/velle/app/BillingApp.kt`,
  which calls the generated surface. When the surface changes shape, move
  that file aside, regenerate, then restore and migrate it.
- `CodegenGoldenTest` in several modules compares the checked-in output to a
  fresh generation, so any change to generated text means regenerating every
  module.
- The survey examples are compiled by `working-docs/examples/survey/check.sh`
  against `compiler/build/libs/compiler.jar`. Rebuild that jar from
  committed source before reading its output, and never leave a jar built
  from uncommitted validator edits behind; it made the script contradict the
  survey README and the OQ (2026-11-10).

## 3. Running the suite

`./gradlew test --continue -q --max-workers=2`. The suite has no expected
failures: every failing test is a failure to fix or to report. Name them by
parsing `**/build/test-results/test/*.xml` for `<testcase>` elements holding
a `<failure>`.

PostgreSQL tests (`stores/postgres`, the billing, enrollment, and gallery
flows) run through Testcontainers, need Docker, and are skipped without it.

Test-spec facts that cost a round each when forgotten: a spec with any `by`
or `visible to` must declare visibility on every readable shape or carry a
top-level `visible to anyone`, or V32 fires; a fixture record no act can
create is `sys.system.seed(shape, fields)`; inside a nested collection
`where` the act's own actor is `this.actor`; compiler tests run each body
inside `sys.scope { }`; `VelleSystem`'s clock starts at 2026-01-01T09:00Z unless
`startTime` is passed, so a date meant to be "yesterday" is written against that,
never against `LocalDate.now()`.

## 4. The settling commit

One commit, all of the following:

1. The OQ line leaves its section of `working-docs/QUESTIONS.md`. A row goes
   at the top of the settled table: the decisions in brief and where each
   retired (Reference §N, `checks.md` V<n>, `patterns.md` heading, a test).
2. Residue finds a home: a `- [ ]` line in the work-items section of an
   existing OQ that holds its context, or a tagged line of its own in
   `QUESTIONS.md` (`- **OQ<n>** — ...`, no file while it fits in one line).
   Every work item has a tag (Dave, 2026-11-11).
3. Every reference to the file is repointed. Grep repo-wide for the tag
   across `wiki/`, `working-docs/`, `compiler/`, `stores/`, `apis/`,
   `extensions/`, `examples/` including generated output, test names,
   comments, and KDoc. Pointers from other OQ files and from code go to the
   wiki page or the code. A wiki page never points into
   `working-docs/questions/`.
4. Every survey example of the OQ leaves `working-docs/examples/survey/`
   (below), and its line leaves that folder's README.
5. `git rm` the OQ file. Never keep it for its reasoning; history is git.
6. Commit message: `OQ<n> settled: <what Velle can now say>`.

A settled OQ whose build has not landed stays on disk until this commit.
See the `oq` skill §5.

### Moving the survey examples

Nothing runs the survey folder: `check.sh` prints and asserts nothing. A
survey file is evidence while its OQ is open. Once the OQ settles, what the
file shows is pinned by the suite or the file is gone (Dave, 2026-11-22).
Each file of the settled OQ goes one of these ways, by what the compiler
does with it after the build:

- **The validator refuses it.** `git mv` it to
  `compiler/src/test/resources/diagnostics/<check>-<slug>.velle`, give it
  the opening `--` lines saying what it spells, and regenerate
  `wiki/Normative/diagnostics.md` (`compiler/CLAUDE.md`, "Diagnostics").
  The catalogue holds one file per message. When a spec there already draws
  the same message with only the names different, delete the survey file
  instead. A file that draws several messages is split into one file per
  message.
- **The parser refuses it.** The catalogue reads validator messages only.
  A compiler test asserts the syntax error's text, and the survey file is
  deleted.
- **It compiles.** This is the settled spelling, a workaround, or a guarded
  form. Its spelling must stand in a system under `examples/` or in the
  spec of a compiler test that asserts what it does. Add it there when it
  does not, then delete the survey file. A workaround the settled spelling
  replaced is deleted outright.
- **It still shows a gap.** It belongs to the OQ that received that gap as
  residue (item 2). The file stays, and its README line names that OQ.

Test comments and KDoc that cite a moved or deleted survey file are
repointed to its new home in the same commit. Code never cites
`working-docs/`.

## 5. Before reporting done

- Every module regenerated and its golden test green.
- The suite run, with every failure named in the report, or none.
- Every wiki page touched read once as a Velle author who has never seen
  the language (`wiki/CLAUDE.md`).
- No tag of the settled OQ left anywhere but the settled table and test or
  comment citations of it.
- No survey example of the settled OQ left under
  `working-docs/examples/survey/`, and no line for one in its README.
- `QUESTIONS.md` still names the right next free tag.

## 6. Several OQs at once

Dave, 2026-11-27. When the session that manages the workers says more than
one OQ is being built at the same time, each OQ is built by one worker on
its own branch in its own git worktree (a second checkout of the repository
in another directory). The example tests are slow, so they run once, after
the last branch has merged, and not once per branch. Without that word from
the managing session, §1 to §5 apply unchanged.

### On the branch

- Step 2 of §1 is skipped. Do not regenerate, and do not commit any
  generated file under `examples/*/output/`. A branch that leaves the
  generated files alone cannot conflict with another branch over them.
- The `.velle` specs under `examples/` and the hand-written files under
  `examples/*/output/` (the tests, and
  `examples/billing/output/src/main/kotlin/velle/app/BillingApp.kt`) are
  still written or migrated on the branch, as step 4 of §1 says. They are
  not compiled or run there.
- To check the feature against one example, regenerate that one module in
  the worktree, run its tests, and discard the generated files before
  committing (`git checkout -- examples/<m>/output` for the tracked ones).
- Step 5 of §1 runs every module that is not an example:
  `./gradlew :compiler:test :stores:sql:test :stores:sqlite:test
  :stores:postgres:test :apis:json:test :apis:graphql:test :apis:mcp:test
  :apis:spring:test :extensions:finance:test :extensions:finance-sql:test
  --continue -q --max-workers=2`. Check the list against
  `settings.gradle.kts`; it is every module there that does not start with
  `examples:`.
- The settling commit (§4) lands on the branch. Its grep for the OQ tag
  skips the generated files, which the final step rewrites.
- The report says "built, examples not yet run", never "done". The first
  two checks of §5 wait for the final step.

### Merging

The managing session merges one branch at a time. The branch is rebased onto
the current `main`, the command above runs again, and the branch lands as a
single merge commit, so that a later failure can be traced to one OQ. Only
the managing session resolves a conflict in `working-docs/QUESTIONS.md`.

### The final step

Once, on `main`, after the last branch has merged:

1. Regenerate every module (§2).
2. Run the full suite (§3).
3. Name every failure with the OQ that caused it. `git bisect` over the
   merge commits finds the OQ when the failure does not say.
4. Grep the generated files for the tag of every OQ settled in this round
   (§4, item 3).
5. Commit the regenerated output on its own:
   `Examples regenerated after OQ<a>, OQ<b>, OQ<c>`. A fix for a failure is
   a separate commit that names its OQ.

Until that commit, `main` is not known to pass the example tests.
