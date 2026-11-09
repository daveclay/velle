# OQ76 — Walks over records that may form a loop

**Status:** opened 2026-11-07 from Dave's question in the OQ74 session: a social graph where Alice is friends with Bob, Bob with Chris, and Chris with Alice is a legal model, so could Velle allow the loop in the data, check each walk over it for whether it ends, and give the author (A) spellings that provably end and (B) a `tolerates` that halts one that may not? Decided 2026-11-09: D1 (with D1a–D1c) and D2 agreed, D3 rejected, D4 settled by D1, D5 to its own OQ later. The language is in §4; spelling `walk`, `walk R for N steps`, `walk R until P`. Next: build.
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

## 4. The language

A **walk** is a derived property whose value is found by following one relationship from the record, step after step, until no new record turns up. The author names the walk; the author never writes the step. The relationship followed is stored or derived, on the same shape; `one` or `many` for a set, `one` only for `until`. A step through an empty `one?` stops there. Each record is visited once, so a walk ends over any data, looping or not, and the compiler has nothing to prove.

A relationship name on its own is one step and gives the flat list. `walk` in front of the name is every step. Nothing about the relationship changes; the keyword decides whether the collection is read or walked (Dave, 2026-11-09).

```velle
    friends                          -- one step: the flat list
    (friends where verified)         -- one step, filtered
    walk friends                     -- every step: the walk
    (walk friends where verified)    -- every step, then filtered
```

Three forms, one per walk in §3:

| Form | Spelling | Value | Type |
|---|---|---|---|
| everything reached | `walk R` | every record reached in one or more steps, the start record not among them (D1a) | `many S` |
| a number of steps | `walk R for N steps` | the same set, cut off at the number; the number is a business fact and part of the meaning | `many S` |
| the nearest match | `walk R until P`, `R` a `one` | the first record, counting from the record itself (D1b), at which `P` holds; `none` when it holds nowhere reached | `one S?` |

A walk's value is a collection like any other: `count`, `in`, `where`, and `+` apply to it, and a `never` may read it (D2).

**Spelling decided: `walk` (Dave, 2026-11-09).** Earlier candidates and the reasons they fell are kept under 4.1 for the record. Every example is complete and compiles against nothing yet.

### 4.1 Everything reached

A social graph that loops on purpose. Alice lists Bob, Bob lists Chris, Chris lists Alice.

```velle
act Profile {
    name: text
    friends: many Profile

    network: many Profile = walk friends
}
```

`friends` is a flat `many Profile`: Alice's friends are Bob and no one else. `walk friends` walks it: Alice's `network` is Bob and Chris. Alice is not in it (D1a); `network + this` when the business wants the person included. Over a ring the walk reaches every member once and stops.

`walk` is contextual: it reads as the walk keyword only where a value expression begins and an identifier follows on the same line, the way `with` reads as the closure keyword only in the `act` header. Everywhere else it is an ordinary identifier, so a property named `walk` stays legal. It is the name this question uses for the construct, has no second meaning in any business domain, and brings its own stop word and depth word, `until` and `for N steps`. Its cost: an author would not say "walk" unprompted, and it is the first value in Velle that reads as a verb without parentheses (`latest`, `first`, and `count` are verbs that wear them).

Candidates that fell, on the same line:

```velle
    network: many Profile = through friends            -- names the relationship, does not say every step
    network: many Profile = all through friends        -- says it; a noun phrase with no stop word of its own
    network: many Profile = reached through friends
    network: many Profile = everything through friends
    network: many Profile = friends and theirs         -- good for a social graph, bad for parent or drawsFrom
    network: many Profile = follow friends             -- in a social app, follow means subscribe
    network: many Profile = trace friends              -- the provenance verb; nobody traces friends
    network: many Profile = keep following friends     -- "keep" says every step; chatty
    network: many Profile = connected by friends       -- a participle, house style; no stop word
    network: many Profile = traverse friends           -- same shape as walk, a register a product owner may not know
```

### 4.2 A number of steps

Friends of friends is two steps, and the two is the meaning.

```velle
act Profile {
    name: text
    friends: many Profile

    nearby: many Profile = walk friends for 2 steps
}
```

`for` is already reserved and reads here as the depth word only directly after a walk and before a whole number. `steps` is contextual the way `days` is: a unit word only directly after a whole number. `walk friends for 1 steps` is `friends` itself, so the compiler should warn.

Candidates that fell: `all through friends within 2 steps`, `all through friends, 2 steps`.

### 4.3 A criterion on the walk (D1c)

The criterion is a derived property. The walk names it.

```velle
act Person {
    name: text
    mother: one Person?
    parents: many Person

    ancestors: many Person = walk parents
    maternalAncestors: many Person = mother.ancestors + mother
}
```

```velle
act Profile {
    name: text
    verified: boolean
    friends: many Profile

    verifiedFriends: many Profile = (friends where verified)
    trustedNetwork: many Profile = walk verifiedFriends
}
```

Nothing here is new beyond 4.1.

### 4.4 The nearest match

The surveyed kinds from `working-docs/examples/survey/02-lookup-recursion-stored-accepted.velle`. The nearest kind up the chain that records a location, the kind itself first (D1b).

```velle
act Kind {
    name: text
    parent: one Kind?
    hasLocation: boolean

    locationHolder: one Kind? = walk parent until hasLocation
}
```

The value is the record at which the walk stopped: the kind itself when it has a location, else the first kind up the chain that does. A root kind with no location gives `none`. The hand-written `if hasLocation then this else parent?.locationHolder` and its `never (Kind where parent == this)` certificate are replaced by this line and the D2 prohibition in 4.6.

The root of an escalation chain is the same form with the condition "has nothing above it":

```velle
act Queue {
    name: text
    escalatesTo: one Queue?

    finalQueue: one Queue? = walk escalatesTo until escalatesTo is none
}
```

Every `until` walk is `one S?`, so `finalQueue` is `one Queue?`, though the record itself satisfies the condition when nothing is above it and the value is never `none` in practice. The compiler does not narrow it.

`until` is contextual: the stop word only directly after a walk. The condition is read at each record walked, the record itself first, with `this` bound to the record being looked at. `where` after a walk is never a stop condition: `(walk friends where verified)` is the existing collection filter on the set the walk gives, so the two words never meet.

`until` walks a `one`. Through a `many`, two records at the same depth may both satisfy the condition and "the nearest" has no single answer, so the compiler rejects it; the message says to walk the `one`, or to declare the set with `walk R` and select from it with `first(... by ...)`.

Candidates that fell: `nearest through parent where hasLocation`, `first through parent where hasLocation` (`first` already means the earliest in an ordering), `closest through parent where hasLocation`.

### 4.5 Reachability as a yes or no

A walk is a collection, so `in` asks the question.

```velle
act Profile {
    name: text
    friends: many Profile
    blocked: one Profile?

    network: many Profile = walk friends
    blockedIsNear: boolean = blocked is some and blocked in network
}
```

### 4.6 The loop prohibition (D2)

A relationship that must not loop. A batch never draws, at any remove, from itself. The walk is named, and the prohibition reads the name.

```velle
act Batch {
    code: text
    drawsFrom: many Batch

    sources: many Batch = walk drawsFrom
}

never (Batch where this in sources)
```

The kinds from 4.4:

```velle
act Kind {
    name: text
    parent: one Kind?
    hasLocation: boolean

    ancestors: many Kind = walk parent
    locationHolder: one Kind? = walk parent until hasLocation
}

never (Kind where this in ancestors)
```

The commit that would close the ring is refused at the door, including two records arriving together each naming the other.

A walk written inline in the prohibition, `never (Batch where this in walk drawsFrom)`, is legal by the grammar. Naming the walk is the spelling to teach: the author gets a word for the set, and the `never` reads as a sentence.

### 4.7 Words

Contextual words, none reserved: `walk`, `until`, `steps`. `walk` reads as the walk keyword where a value expression begins and an identifier follows on the same line; `until` only directly after a walk; `steps` only after a whole number, like a duration unit. `for`, already reserved, reads as the depth word only directly after a walk and before a whole number. Everywhere else `walk`, `until`, and `steps` are ordinary identifiers, so `steps: integer` and `walk: text` stay legal properties.

What the compiler checks, for `checks.md` when this lands: the relationship followed is on the walk's own shape and points back at it, else an error; a walk is never itself a step in a definition cycle, so V14 sees it as acyclic; `for 1 steps` warns; `until` through a `many` is an error; an `until` condition that the record itself can never satisfy and that no step can reach is dead, an A-class warning.

## 5. What to decide

### D1. A construct for everything reached

A derived property that names the walk instead of writing it, spelled in §4 (`network: many Profile = walk friends`). The runtime computes it with a visited set, so it ends on any data, and V14 accepts it because the construct ends, with nothing for the author to prove. Two variants carry the other two walks: a depth, `walk friends for 2 steps`, is the fixed number of steps; a stop condition, `walk parent until hasLocation`, is the nearest record found, a `one`, `none` when nothing is found.

Cost: a fixpoint in the evaluator; a rendering per store (a recursive query for SQL stores, OQ69's store side); the read is over the whole connected part of the shape, which the contention analysis (A5, `tolerates contention`) has to see; the cost on read over a large graph is the author's, as for any derived property.

The walk follows a relationship, a stored `one` or `many`. A reference the human gives by the target's own field values (`wiki/patterns.md`, "An act that names a record by its values") is still a stored reference in the spec, so a named walk follows it with nothing more; the survey's `02-lookup-recursion-stored-accepted.velle`, with `parent: one Kind?` stored, is the ancestor walk this D1 would spell.

**Decision (Dave, 2026-11-09): decided.** The walk is named, not written, and ends because of what it is. The two definitions below stand as proposed. The spelling is `walk`, decided the same day; §4.

- **D1a. The start record is not in the set.** Alice, Bob, Chris in a ring: Alice is reached from Alice, and "my network" excludes me. Every record reached other than the start; the author adds `this` when wanted.
- **D1b. A nearest-match search starts at the record itself.** `locationHolder` checks the record itself first, since "nearest" in business speech includes "here".
- **D1c. The relationship followed may be derived.** Raised by Dave 2026-11-09: a walk with a criterion, "my ancestors on my mother's side". The criterion lives in an ordinary derived property, and the walk names it. A first step with a criterion: `maternalAncestors: many Person = mother.ancestors + mother` with `ancestors: many Person = walk parents`. A criterion on every step: `verifiedFriends: many Profile = (friends where verified)` and `trustedNetwork: many Profile = walk verifiedFriends`. So the construct follows a stored or derived relationship, `one` or `many`, and takes no criterion of its own. Known edge, no business sentence found for it: a filter in the derived relationship is read at each record walked, so a criterion compared against the start record is only right when the compared value is the same all along the chain. To check at build: a `one` hop to a `many` (`mother.ancestors`) and `+` on two collections are both legal in a derived `many`; the 2026-11-07 probe saw the union accepted but never ran it.

### D2. The loop prohibition through the closure

`never (Kind where this in ancestors)`, with `ancestors: many Kind = walk parent` (§4.6), refuses at the door any commit that would close a loop. It replaces `never (Kind where parent == this)` as V14's certificate for a hand-written `one` walk, and it is the certificate OQ44 §2 item 8 asks for. With D1 it costs nothing more; without D1 it has no spelling.

D2 is for a relationship that must not loop, such as a parent chain. It does nothing for the friend graph this question opened with, which loops on purpose. The friend graph is covered by D1 alone: the named walk ends because each record is visited once, and asks nothing of the data.

**Decision (Dave, 2026-11-09): agreed.** The prohibition is the certificate for a relationship that must not loop, and refuses at the door the commit that would close the ring, including two records arriving together each naming the other. Its spelling follows D1's.

### D3. Or grow V14's list instead

OQ16's route: accept more hand-written formulas, one certificate at a time. Candidates: a derived `one` hop under the one-step `never` (the survey's `02-lookup-recursion-refused.velle`, a parent looked up by name; a lookup is not how a reference is spelled, per OQ74, so this candidate is weak); a `many` hop with a strict-descent selector; a creation-fixed reference to a pre-existing record (OQ44 §2 item 8). Each is a formula the author writes and the compiler proves, the list stays short, and every new business sentence waits for its certificate. D1 and D3 are not exclusive; D3 without D1 leaves "everything reached" unsayable.

**Decision (Dave, 2026-11-09): rejected.** V14's certificate list stays as it is. Walks with criteria do not need it (D1c). If a business sentence turns up that the named walk cannot say, that is a new finding then, not a certificate added now.

### D4. A bound, and why it is not a `tolerates`

`tolerates` in Velle says the business accepts a hazard: duplication, reordering, loss, contention ([Reference §19]). Each names an outcome the spec still defines. A walk that may not end has no outcome to accept, so `tolerates cycles` would say nothing. A bound belongs in the spelling as a business number with its result at the bound defined: within N steps gives the set within N; a search within N that finds nothing gives `none`. The fixed-depth unrolling in the survey's `02-lookup-recursion-workaround.velle` is this bound written by hand with no result defined past it, which is why it is wrong silently. A runtime guard that stops a looping `one` walk and answers `none` is the other way to halt, and it is worse: the answer would depend on where the walk started. Refuse the loop at the door (D2) instead.

**Settled by D1 (2026-11-09).** With the named walk in, nothing in a spec can run forever: the named walk visits each record once, V14's two hand-written forms are proven, and the looping pair at creation is refused by D2. There is no hazard for a `tolerates` to accept. The bound is D1's `within N` variant, with the result at the bound defined by the construct.

### D5. A relationship that goes both ways

Friendship is mutual. `friends: many Profile` on `Profile` gives the inferred inverse, who lists me, which is not the same set. Whether Velle gets a declaration for a relationship that is its own inverse is a separate finding; recorded here so it is not lost, not designed.

**Decision (Dave, 2026-11-09): a separate OQ.** Opened when this question settles, not before, so the two are not confused while this one is open. It changes what `friends` means, not how a walk over it ends.

## Housekeeping when a decision lands

Before the build touches the walk: check that `mother.ancestors`, a `one` hop into a `many`, and `+` on two collections are legal inside a derived `many` today, since the maternal-ancestors example in 4.3 leans on both. The 2026-11-07 probe saw the union accepted and never ran it. If either is not legal, that is its own item, not part of the walk.

The survey's `02-lookup-recursion-refused.velle` and `02-lookup-recursion-workaround.velle` are wrong spellings (a reference written as a lookup; a bound with no result defined past it) and go. `02-lookup-recursion-stored-accepted.velle` is the stored spelling of the parent, and whether its `locationHolder` stays a hand-written formula or becomes the named walk of D1 follows this question's decision. The survey README's rows for the three files change accordingly.
