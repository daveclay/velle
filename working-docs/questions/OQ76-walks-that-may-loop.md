# OQ76 — Walks over records that may form a loop

**Status:** opened 2026-11-07 from Dave's question in the OQ74 session: a social graph where Alice is friends with Bob, Bob with Chris, and Chris with Alice is a legal model, so could Velle allow the loop in the data, check each walk over it for whether it ends, and give the author (A) spellings that provably end and (B) a `tolerates` that halts one that may not? Decided 2026-11-09: D1 (with D1a–D1c) and D2 agreed, D3 rejected, D4 settled by D1, D5 to its own OQ later. The language is in §4; its spelling is under iteration.
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

A **walk** is a derived property whose value is found by following one relationship from the record, step after step, until no new record turns up. The author names the walk; the author never writes the step. The relationship followed is stored or derived, `one` or `many`, on the same shape. A step through an empty `one?` stops there. Each record is visited once, so a walk ends over any data, looping or not, and the compiler has nothing to prove.

A relationship name on its own is one step and gives the flat list. `all through` in front of the name is every step. Nothing about the relationship changes; the keywords decide whether the collection is read or walked (Dave, 2026-11-09). `through` names the relationship followed; the word before it says how much is collected: `all`, or `nearest` with a condition.

```velle
    friends                                -- one step: the flat list
    (friends where verified)               -- one step, filtered
    all through friends                    -- every step: the walk
    (all through friends where verified)   -- every step, then filtered
```

Three forms, one per walk in §3:

| Form | Value | Type |
|---|---|---|
| everything reached | every record reached in one or more steps, the start record not among them (D1a) | `many S` |
| within a number of steps | the same set, cut off at the number; the number is a business fact and part of the meaning | `many S` |
| the nearest match | the first record, counting from the record itself (D1b), that satisfies a condition; `none` when no record reached does | `one S?` |

A walk's value is a collection like any other: `count`, `in`, `where`, and `+` apply to it, and a `never` may read it (D2).

The primary spelling below is a candidate, chosen so the words are ones a product owner says and each new word is contextual, the way `by`, `with`, and the duration units already are. Every example is complete and compiles against nothing yet. Alternatives follow each example; strike or add.

### 4.1 Everything reached

A social graph that loops on purpose. Alice lists Bob, Bob lists Chris, Chris lists Alice.

```velle
act Profile {
    name: text
    friends: many Profile

    network: many Profile = all through friends
}
```

`friends` is a flat `many Profile`: Alice's friends are Bob and no one else. `all through friends` walks it: Alice's `network` is Bob and Chris. Alice is not in it (D1a); `network + this` when the business wants the person included. Over a ring the walk reaches every member once and stops.

`through` alone was tried first and dropped (Dave, 2026-11-09): it names the relationship followed but does not say that every step is taken. `all` says it. Both words are contextual: `all` reads as the walk head only directly before `through`, and `through` only directly after `all` or `nearest`; everywhere else both are ordinary identifiers, so a property named `all` or `through` stays legal.

Alternatives for the same line:

```velle
    network: many Profile = everything through friends
    network: many Profile = every Profile through friends
    network: many Profile = reached through friends
    network: many Profile = friends and theirs
    network: many Profile = friends onward
```

The last two read well for a social graph and badly for `parent` or `drawsFrom`.

Verb spellings (asked for by Dave, 2026-11-09). A verb brings its own stop word and depth word, `until` and `for N steps`, which read better than `nearest ... where` and `within N steps`. Each shown on the four test lines where it differs:

```velle
    network: many Profile = follow friends
    ancestors: many Person = follow parents
    locationHolder: one Kind? = follow parent until hasLocation
    sources: many Batch = follow drawsFrom
    nearby: many Profile = follow friends for 2 steps

    network: many Profile = trace friends
    locationHolder: one Kind? = trace parent until hasLocation

    network: many Profile = keep following friends
    locationHolder: one Kind? = keep following parent until hasLocation

    network: many Profile = connected by friends
    ancestors: many Person = connected by parents

    network: many Profile = gather through friends
```

`follow`: the plainest verb for a chain; in a social app it also means subscribe. `trace`: the business verb for provenance and ancestry, weak for a social network. `keep following`: "keep" says every step; chatty. `connected by`: a participle, the house style of `captured` and `frozen`; no natural stop word, so the nearest form would keep `nearest through`. `gather`/`collect through`: says the walk, sounds like work being done. `climb`, `descend`, `expand`, `repeat`, `walk`: direction or mechanism, not what an author says. Style note: every value in Velle today is a noun phrase, and a bare verb to the right of `=` would be the first that reads as a command; `latest`, `first`, and `count` are verbs, but wear parentheses.

### 4.2 Within a number of steps

Friends of friends is two steps, and the two is the meaning.

```velle
act Profile {
    name: text
    friends: many Profile

    nearby: many Profile = all through friends within 2 steps
}
```

`steps` is contextual the way `days` is: a unit word only directly after a whole number. `within 1 steps` is `friends` itself, so the compiler should warn.

Alternatives:

```velle
    nearby: many Profile = all through friends, 2 steps
    nearby: many Profile = all within 2 steps through friends
    nearby: many Profile = friends through 2 steps
```

### 4.3 A criterion on the walk (D1c)

The criterion is a derived property. The walk names it.

```velle
act Person {
    name: text
    mother: one Person?
    parents: many Person

    ancestors: many Person = all through parents
    maternalAncestors: many Person = mother.ancestors + mother
}
```

```velle
act Profile {
    name: text
    verified: boolean
    friends: many Profile

    verifiedFriends: many Profile = (friends where verified)
    trustedNetwork: many Profile = all through verifiedFriends
}
```

No alternative spelling: nothing here is new beyond 4.1. To check at build: `mother.ancestors` as a `one` hop into a `many`, and `+` on two collections, inside a derived `many`.

### 4.4 The nearest match

The surveyed kinds from `working-docs/examples/survey/02-lookup-recursion-stored-accepted.velle`. The nearest kind up the chain that records a location, the kind itself first (D1b).

```velle
act Kind {
    name: text
    parent: one Kind?
    hasLocation: boolean

    locationHolder: one Kind? = nearest through parent where hasLocation
}
```

A root kind with no location gives `none`. The hand-written `if hasLocation then this else parent?.locationHolder` and its `never (Kind where parent == this)` certificate are replaced by this line and the D2 prohibition in 4.6.

The root of an escalation chain is the same form with the condition "has nothing above it":

```velle
act Queue {
    name: text
    escalatesTo: one Queue?

    finalQueue: one Queue = nearest through escalatesTo where escalatesTo is none
}
```

`finalQueue` is `one Queue`, not `one Queue?`: the record itself satisfies the condition when nothing is above it, so the value is never `none`. Whether the compiler can see that is a build question; the first cut may require `one Queue?`.

Alternatives:

```velle
    locationHolder: one Kind? = first through parent where hasLocation
    locationHolder: one Kind? = closest through parent where hasLocation
    locationHolder: one Kind? = nearest parent where hasLocation
```

`first` already exists with a mandatory `by` and a different reading (the earliest in an ordering), so it is the weakest. `where` after a walk belongs to `nearest` only, as its stop condition. A filter on the set a walk gives is the existing parentheses form, `(network where verified)`, so the two readings of `where` never meet. Through a `many`, two records at the same depth may both satisfy the condition; the first cut allows `nearest` through a `one` only, or demands a `by` through a `many`.

### 4.5 Reachability as a yes or no

A walk is a collection, so `in` asks the question.

```velle
act Profile {
    name: text
    friends: many Profile
    blocked: one Profile?

    network: many Profile = all through friends
    blockedIsNear: boolean = blocked is some and blocked in network
}
```

### 4.6 The loop prohibition (D2)

A relationship that must not loop. A batch never draws, at any remove, from itself. The walk is named, and the prohibition reads the name.

```velle
act Batch {
    code: text
    drawsFrom: many Batch

    sources: many Batch = all through drawsFrom
}

never (Batch where this in sources)
```

The kinds from 4.4:

```velle
act Kind {
    name: text
    parent: one Kind?
    hasLocation: boolean

    ancestors: many Kind = all through parent
    locationHolder: one Kind? = nearest through parent where hasLocation
}

never (Kind where this in ancestors)
```

The commit that would close the ring is refused at the door, including two records arriving together each naming the other.

A walk written inline in the prohibition, `never (Batch where this in all through drawsFrom)`, is legal by the grammar and reads poorly. Naming the walk is the spelling to teach.

### 4.7 Words

Candidate contextual words, none reserved: `all`, `through`, `within`, `steps`, `nearest`. `all` and `nearest` read as the walk head only directly before `through`; `through` only directly after one of them; `within N steps` only directly after a walk; `steps` only after a whole number, like a duration unit. Everywhere else all five are ordinary identifiers, so `steps: integer` and `all: boolean` stay legal properties.

What the compiler checks, for `checks.md` when this lands: the relationship followed is on the walk's own shape and points back at it, else an error; a walk is never itself a step in a definition cycle, so V14 sees it as acyclic; `within 1 steps` warns; a condition on `nearest` that the record itself can never satisfy and that no step can reach is dead, an A-class warning.

## 5. What to decide

### D1. A construct for everything reached

A derived property that names the walk instead of writing it, spelled in §4 (`network: many Profile = all through friends` is the current candidate). The runtime computes it with a visited set, so it ends on any data, and V14 accepts it because the construct ends, with nothing for the author to prove. Two variants carry the other two walks: a depth, `all through friends within 2 steps`, is the fixed number of steps; a stop condition, `nearest through parent where hasLocation`, is the nearest record found, a `one`, `none` when nothing is found.

Cost: a fixpoint in the evaluator; a rendering per store (a recursive query for SQL stores, OQ69's store side); the read is over the whole connected part of the shape, which the contention analysis (A5, `tolerates contention`) has to see; the cost on read over a large graph is the author's, as for any derived property.

The walk follows a relationship, a stored `one` or `many`. A reference the human gives by the target's own field values (`wiki/patterns.md`, "An act that names a record by its values") is still a stored reference in the spec, so a named walk follows it with nothing more; the survey's `02-lookup-recursion-stored-accepted.velle`, with `parent: one Kind?` stored, is the ancestor walk this D1 would spell.

**Decision (Dave, 2026-11-09): decided.** The walk is named, not written, and ends because of what it is. The two definitions below stand as proposed. The spelling is iterated in §4.

- **D1a. The start record is not in the set.** Alice, Bob, Chris in a ring: Alice is reached from Alice, and "my network" excludes me. Every record reached other than the start; the author adds `this` when wanted.
- **D1b. A nearest-match search starts at the record itself.** `locationHolder` checks the record itself first, since "nearest" in business speech includes "here".
- **D1c. The relationship followed may be derived.** Raised by Dave 2026-11-09: a walk with a criterion, "my ancestors on my mother's side". The criterion lives in an ordinary derived property, and the walk names it. A first step with a criterion: `maternalAncestors: many Person = mother.ancestors + mother` with `ancestors: many Person = all through parents`. A criterion on every step: `verifiedFriends: many Profile = (friends where verified)` and `trustedNetwork: many Profile = all through verifiedFriends`. So the construct follows a stored or derived relationship, `one` or `many`, and takes no criterion of its own. Known edge, no business sentence found for it: a filter in the derived relationship is read at each record walked, so a criterion compared against the start record is only right when the compared value is the same all along the chain. To check at build: a `one` hop to a `many` (`mother.ancestors`) and `+` on two collections are both legal in a derived `many`; the 2026-11-07 probe saw the union accepted but never ran it.

### D2. The loop prohibition through the closure

`never (Kind where this in ancestors)`, with `ancestors: many Kind = all through parent` (§4.6), refuses at the door any commit that would close a loop. It replaces `never (Kind where parent == this)` as V14's certificate for a hand-written `one` walk, and it is the certificate OQ44 §2 item 8 asks for. With D1 it costs nothing more; without D1 it has no spelling.

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

The survey's `02-lookup-recursion-refused.velle` and `02-lookup-recursion-workaround.velle` are wrong spellings (a reference written as a lookup; a bound with no result defined past it) and go. `02-lookup-recursion-stored-accepted.velle` is the stored spelling of the parent, and whether its `locationHolder` stays a hand-written formula or becomes the named walk of D1 follows this question's decision. The survey README's rows for the three files change accordingly.
