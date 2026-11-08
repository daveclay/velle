# OQ76 — Walks over records that may form a loop

**Status:** opened 2026-11-07 from Dave's question in the OQ74 session: a social graph where Alice is friends with Bob, Bob with Chris, and Chris with Alice is a legal model, so could Velle allow the loop in the data, check each walk over it for whether it ends, and give the author (A) spellings that provably end and (B) a `tolerates` that halts one that may not? Not designed. D1–D5 await Dave.
**In plain terms:** a relationship that points at a record of the same shape, `one` or `many`, is legal today, and so is data that loops through it. What Velle refuses is a derived property defined by the same property on the related record, so that it follows the relationship until some record answers for itself, because over looping data that walk never stops. The compiler accepts the walk only in two forms it knows must end, and refuses every other. A social graph, an ancestor set, a chain of escalations, and "a batch never draws from itself" all want a walk the compiler refuses. There is no general test for whether a formula ends, so "check the walk" means a list of forms known to end, which is what the compiler has. The question is which forms belong on the list, and whether the author should write the walk as a formula at all or name it: a fixed number of steps, each step strictly down an ordering, or everything reached. A `tolerates` is the wrong tool for the third: a walk that may not end is not a hazard the business accepts a little of. A bound belongs in the spelling, with its result at the bound stated.
**See:** `checks.md` V14 · Reference §6 (relationships, a path through one `many`), §7 (derived properties), §16 (no loop construct), §19 (`tolerates`) · OQ16 (owns V14's certificate list; calibration rows B5 and B6) · OQ44 §2 item 8 (the escalation chain; the creation-fixed certificate) · OQ53 (B6) · OQ74 (an act's values meant to identify an existing record; this question surfaced in its session and owns nothing here) · `working-docs/examples/survey/02-lookup-recursion-*.velle` (the parent walk: refused as a formula, accepted over a stored `parent`, unrolled by hand) · `compiler/src/main/kotlin/velle/Validator.kt` (`checkWellFoundedness`, `certifiedSelfRecursion`, `hopCertified`)

---

## 1. What is allowed today, and what is refused

The model may loop. `friends: many Profile` on `Profile` validates, and Alice, Bob, and Chris may each list the next. `parent: one Kind?` on `Kind` validates, and nothing in the model stops two kinds from naming each other.

What is refused is a derived property that reads itself. V14 stratifies the definitions and accepts a cycle in two forms only:

1. A stored `one` relationship pointing at a record of the same shape, with a spent `never (S where hop == this)`. The prohibition says no record is its own neighbour, and the compiler takes that as the reason the walk ends.
2. A `one` relationship derived by `latest`/`first` whose selector strictly compares a value fixed at creation against the subject's. Each step lands on a strictly earlier record, so the walk runs out.

Anything else is refused, and V14 states its stance in `checks.md`: no fixpoint is ever attempted.

Probed 2026-11-07, with the compiler jar and a throwaway test that was not kept:

- `network: many Profile = friends + friends.network` validates with no diagnostic. V14 does not see a self-read behind a `many` hop (OQ16's row B6). At runtime a path through a `many` cannot be evaluated at all (row B5), so the spelling fails before it can loop. Both rows are already recorded; nothing new.
- The runtime has no guard of its own. A walk `locationHolder = if hasLocation then this else parent?.locationHolder` over a two-record loop (A's parent is B, B's parent is A) overflows the stack. The validator keeps that spec out today: for the loop to be reachable a rule must write `parent`, which makes `never (Kind where parent == this)` a guarantee V10 cannot prove, so it is not spendable and V14 refuses the walk. With no writer, `parent` is fixed at creation, and the only way to loop is two records arriving in one commit each naming the other. OQ44 §2 item 8 names this as the certificate the list is missing; whether the act's closure admits such a pair is untested.

So the author cannot ship an endless walk today. The price is that the author cannot ship most finite walks either.

## 2. What "check whether it would loop" can mean

There is no test that takes any formula and says whether it ends. V14 is a list of forms known to end, and a check that admits more walks is a longer list. Velle has no loop construct ([Reference §16]: a rule fires once per record), and a derived property that reads itself is a loop by another spelling. The question to put to the author is not "does your formula end" but "which of the walks that end do you mean".

## 3. The three walks that end

**A fixed number of steps.** Friends of friends is two steps. Reports to my manager's manager is two steps. The number is part of the meaning and the walk ends by construction. Spelling today: write each step out. Since a value expression admits no path through a `many` (§6 admits one `many` hop as an assignment target only), each step over a `many` is a selection, `(Profile where exists (this.friends where ...))`, and nothing says the two selections are one walk.

**Each step strictly down an ordering fixed at creation.** The predecessor in a ledger. V14 accepts this for a `one` relationship through `latest`/`first` ([Reference §19]). A business rarely means this over a `many`.

**Everything reached.** My whole network. Every ancestor of this batch. Whether X is reachable from me. The root of this escalation chain. It ends because the records are finite and each is visited once, not because of anything in the formula. No spelling today, and V14 says the runtime will never compute it. This is the walk a graph that loops actually needs, and the one the batches pass (B5), the union pass (OQ44 §2 item 8), and the survey pass (the `02-lookup-recursion-*` files) each wanted.

## 4. What to decide

### D1. A construct for everything reached

A derived property that names the walk instead of writing it, sketched as `network: many Profile = reachable through friends` (the spelling is mine and open). The runtime computes it with a visited set, so it ends on any data, and V14 accepts it because the construct ends, with nothing for the author to prove. Two variants carry the other two walks: a depth, `reachable through friends within 2`, is the fixed number of steps; a stop condition, `first reachable through parent where hasLocation`, is the nearest record found, a `one`, `none` when nothing is found.

Cost: a fixpoint in the evaluator; a rendering per store (a recursive query for SQL stores, OQ69's store side); the read is over the whole connected part of the shape, which the contention analysis (A5, `tolerates contention`) has to see; the cost on read over a large graph is the author's, as for any derived property.

The walk follows a relationship, a stored `one` or `many`. A reference the human gives by the target's own field values (OQ74) is still a stored reference in the spec, so a named walk follows it with nothing more; the survey's `02-lookup-recursion-stored-accepted.velle`, with `parent: one Kind?` stored, is the ancestor walk this D1 would spell.

### D2. The loop prohibition through the closure

`never (Profile where this in reachable through friends)` refuses at the door any commit that would close a loop. It replaces `never (Kind where parent == this)` as V14's certificate for a hand-written `one` walk, and it is the certificate OQ44 §2 item 8 asks for. With D1 it costs nothing more; without D1 it has no spelling.

D2 is for a relationship that must not loop, such as a parent chain. It does nothing for the friend graph this question opened with, which loops on purpose. The friend graph is covered by D1 alone: the named walk ends because each record is visited once, and asks nothing of the data.

### D3. Or grow V14's list instead

OQ16's route: accept more hand-written formulas, one certificate at a time. Candidates: a derived `one` hop under the one-step `never` (the survey's `02-lookup-recursion-refused.velle`, a parent looked up by name; a lookup is not how a reference is spelled, per OQ74, so this candidate is weak); a `many` hop with a strict-descent selector; a creation-fixed reference to a pre-existing record (OQ44 §2 item 8). Each is a formula the author writes and the compiler proves, the list stays short, and every new business sentence waits for its certificate. D1 and D3 are not exclusive; D3 without D1 leaves "everything reached" unsayable.

### D4. A bound, and why it is not a `tolerates`

`tolerates` in Velle says the business accepts a hazard: duplication, reordering, loss, contention ([Reference §19]). Each names an outcome the spec still defines. A walk that may not end has no outcome to accept, so `tolerates cycles` would say nothing. A bound belongs in the spelling as a business number with its result at the bound defined: within N steps gives the set within N; a search within N that finds nothing gives `none`. The fixed-depth unrolling in the survey's `02-lookup-recursion-workaround.velle` is this bound written by hand with no result defined past it, which is why it is wrong silently. A runtime guard that stops a looping `one` walk and answers `none` is the other way to halt, and it is worse: the answer would depend on where the walk started. Refuse the loop at the door (D2) instead.

### D5. A relationship that goes both ways

Friendship is mutual. `friends: many Profile` on `Profile` gives the inferred inverse, who lists me, which is not the same set. Whether Velle gets a declaration for a relationship that is its own inverse is a separate finding; recorded here so it is not lost, not designed.

## Housekeeping when a decision lands

The survey's `02-lookup-recursion-refused.velle` and `02-lookup-recursion-workaround.velle` are wrong spellings (a reference written as a lookup; a bound with no result defined past it) and go. `02-lookup-recursion-stored-accepted.velle` is the stored spelling of the parent, and whether its `locationHolder` stays a hand-written formula or becomes the named walk of D1 follows this question's decision. The survey README's rows for the three files change accordingly.
