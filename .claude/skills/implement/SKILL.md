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

## 1. Order of work

1. **Code.** Compiler, stores, APIs, extensions. Hand-written callers under
   `main` migrate first, then generated output, then tests.
2. **Regenerate** every example's output (§2).
3. **Wiki.** The Reference page, `checks.md`, `evaluation.md`, `grammar.md`,
   `patterns.md`, `GLOSSARY.md`, the guides, as the build section lists. The
   result is phrased positively, with no design history and no OQ tag on any
   wiki page. Generated-Kotlin facts go in `wiki/Act-API.md` and
   `wiki/Read-API.md`, never in the Reference.
4. **Examples.** A new feature gets an example that exercises it under
   `examples/`, or an existing one grows. Survey examples under
   `working-docs/examples/survey/` that showed the gap are rewritten to the
   settled spelling and the survey README's line for each is updated; one
   that no longer shows anything is deleted.
5. **Tests.** A test pins each new check, each new runtime behaviour, and
   each bug the OQ found. Run the full suite (§3).
6. **Settle** (§4).

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

`./gradlew test --continue -q --max-workers=2`. There is a baseline of
failures in `compiler` from fixture drift; the number was 16 on 2026-10-24
(BillingFixtureTest 2, DiagramGenTest 1, QueryCompilerTest 1,
RuleGraphGenTest 2, RuntimeTest 9, StateFlowGenTest 1). Compare names, not
counts: parse `**/build/test-results/test/*.xml` for `<testcase>` elements
holding a `<failure>` and diff against the baseline. Anything outside it is
new and yours.

PostgreSQL tests (`stores/postgres`, the billing, enrollment, and gallery
flows) run through Testcontainers, need Docker, and are skipped without it.

Test-spec facts that cost a round each when forgotten: a spec with any `by`
or `visible to` must declare visibility on every readable shape or carry a
top-level `visible to anyone`, or V32 fires; a fixture record no act can
create is `sys.system.seed(shape, fields)`; inside a nested collection
`where` the act's own actor is `this.actor`; compiler tests run each body
inside `sys.scope { }`.

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
4. `git rm` the OQ file. Never keep it for its reasoning; history is git.
5. Commit message: `OQ<n> settled: <what Velle can now say>`.

A settled OQ whose build has not landed stays on disk until this commit.
See the `oq` skill §5.

## 5. Before reporting done

- Every module regenerated and its golden test green.
- The suite run, with the failures outside the baseline named in the
  report, or none.
- Every wiki page touched read once as a Velle author who has never seen
  the language (`wiki/CLAUDE.md`).
- No tag of the settled OQ left anywhere but the settled table and test or
  comment citations of it.
- `QUESTIONS.md` still names the right next free tag.
