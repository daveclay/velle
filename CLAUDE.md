# Velle

Velle is a language that captures the discussion between a product 
owner and an engineer. It documents a system's business rules in
an intuitive, expressive syntax, and provides the ability to 
validate them with proofs. It intentionally avoids traditional code
structures and syntax. Traditional code requires the engineer to 
translate business domain problems into the domain of running on a
digital computer with functions, variables, stack frames and memory.

The 
repo holds the compiler and runtime
(`compiler/`), stores (`stores/`), the API layer (`apis/`), extension
libraries (`extensions/`), the example systems (`examples/`), the
documentation (`wiki/`), and the working documents (`working-docs/`).

Each of `compiler/`, `wiki/`, `examples/`, and `apis/` has its own
`CLAUDE.md` with the rules for files under it. Two skills hold the
procedures: `oq` for opening, investigating, and deciding an open question,
and `implement` for implementing a decided one through to the commit that
settles it.

## Working documents

`working-docs/` holds exactly `QUESTIONS.md`, `questions/` (one file per
open question, `OQ<n>-<slug>.md`), and `examples/` (survey specs that probe
a gap). Nothing else: no TODO file, no investigation or audit files.
`QUESTIONS.md` is a directory, one line per open question, and a question
whose context fits in its line has no file. Every work item has an OQ tag:
it is a `- [ ]` line in its OQ's file, or a tagged line of its own in
`QUESTIONS.md`, never a `- [ ]` there. See the `oq` skill before touching
any of it.

References run one way: working documents may cite the wiki and the code;
the wiki and the code never cite `working-docs/`, except `QUESTIONS.md` by
name and a bare OQ tag. Reasoning that settled a question is not kept
anywhere; history is git.

## Writing for Dave

These rules apply to every OQ file, every working document, every commit
message, and every chat message.

- Lead with the decision or the answer in one plain sentence Dave would
  say, yes-or-no where possible. Costs and tradeoffs come after, as a short
  list.
- No shorthand, and never a term of your own invention. A term is a Velle
  keyword, an everyday word, or a term shared among professionals that is in
  `wiki/GLOSSARY.md` and glossed at first use. A document-local term is
  defined in a sentence the first time it appears.
- Every mention of example code names its file,
  `examples/<system>/<system>.velle` or
  `working-docs/examples/survey/<name>.velle`, and the rule or shape by name.
- Velle code shown to Dave is full-context: the `shape` and `act`
  declarations with field types in view, the `uses` lines, and a comment per
  line saying what the compiler does with it. Never a bare fragment.
- Every evidence claim points at a checked-in artifact: a test, a file, a
  source line. Session output is not evidence. If the artifact does not
  exist, create it.
- A document that uses labels (D3, probe J, finding C1) holds the complete
  roster resolving every label it uses, updated in the same edit.
- Say "query" for one read request. "Question" is reserved for the
  questions for Dave.
- No em-dashes. Start a new sentence instead. Dates are absolute.
- Every decision for Dave is written into the OQ file as its own section
  before it is mentioned in chat; the chat message points at the file and
  section. When Dave says "let's talk about this", stop editing and reason
  in chat; the document is written once, afterwards.
- When Dave calls a construct awkward while saying the use case is
  legitimate, step back to whether the construct should exist at all before
  fixing its form.

## Kotlin code

Dave's rules for every Kotlin file in the repo, hand-written or emitted by
the compiler. For emitted code the rule binds the generator that writes it.

- A variable is named for what it contains. The name says what the value
  is, not what it is used for or where it came from.
- Never a single-character variable name, including loop and lambda
  parameters. Write `it` out as a named parameter when the body is more than
  one expression.
- Prefer human readability over short code. A longer spelling that reads at
  a glance beats a shorter one that has to be worked out.
- Keep indentation to a minimum. A nested loop or block is refactored into
  its own named function rather than indented further.

## Design principles

Dave's, stated while deciding questions. They decide new questions by
default and are not applied back over existing choices without his say.

- A system's state changes only when something external happens to it: a
  tick of a scheduler or an incoming act. An incoming act is an input to the
  state, not a member of it; what lasts is what the rules record.
- A design in which a wrong state cannot be represented beats one in which
  it is checked.
- The compiler errors when a statement's meaning is undefined. It advises
  when the meaning is defined but the author may not have intended it, and
  the advisory is silenced by an explicit spelling.
- Rejections are as much the system's information as committed state. For
  each `never` the author chooses whether a violation is refused at the door
  or recorded as a finding; the language never decides that. A rejection,
  from an act or from a tick, must reach a human with what was rejected and
  why.
- A `never` declares a prohibition: a refusal (binds the caller, over a
  transient act), a constraint (binds the books, over a persistent shape),
  or a guarantee (binds the spec's own rules, proven). Use those words.
- A text is read as another type only by a function with an absent result,
  `number(value)`, never by a comparison that reads it silently.
- An extension type is one value and its mapping; a value with parts is a
  shape. A type says nothing about storage; storage is a binding per type
  and store. Custom functions outrank custom types.
- Velle codes to its own extension interfaces (`compiler/CLAUDE.md`).
- A binding is a library. The owner of the spec chooses which bindings
  their system runs with by what they put on the classpath, and replaces one
  with a bug as they would any library. Velle never overrides that choice.
- The store API is agnostic to how and where records are stored
  (`compiler/CLAUDE.md`).
- Velle captures the product-owner and engineer conversation as a pure
  business layer. Arbitrary input from a form or a sync is not part of
  Velle; it is pushed to the APIs.
- Velle UI, a separate sub-project (OQ73), separates the UX description,
  the UI component mapping, and styling into three languages, as Velle
  separates its domains. Nothing in a UX declaration names a widget, a
  position, a colour, or a size.
- The closure tree rule in Reference §6 is a limitation, not a principle.
  Do not defend it.
- Do not extend one stated goal into a principle that decides other
  questions.
