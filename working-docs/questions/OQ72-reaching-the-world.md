# OQ72 — Whether a spec may ask the world a question: a check that calls out of the system

**Status:** opened 2026-10-28 at Dave's request, out of a chat about validating an email address on an incoming act. Not designed. Dave's starting position: if this exists it is probably a separate construct that runs differently from a function, and cannot be used as a refinement; leaving it out of Velle is an option, not to be decided now.
**In plain terms:** today a spec validates an incoming value with what it already has: a regular expression (`SignUp requires email matches "..."`), a registered function over the value, or a fact already on the books. None of these can ask anything outside the system, such as whether an email's domain accepts mail, whether an address geocodes, whether a card number is live with the processor. The question is whether Velle should have a way to say "ask the world this, and refuse the act if the answer is no", and if so what that is, given that a function and a refinement cannot be it.
**See:** `wiki/Extension-functions.md` (a function is pure, evaluated as often as Velle likes, never fails, never reads the clock) · Reference §21 (refusal at the door; V30, a constraint may not read the clock, the one impure read Velle lets in and the check it needed) · Reference §8 (membership is the predicate over current state, never stored) · `wiki/evaluation.md` ("Errors, refusals, and retry") · Reference §15 (`after commit`) and `examples/billing/billing.velle` (`ReceiptEmail`: the outgoing direction as a record the host acts on) · the registered-functions residue item on generators (`randomUUID`, a function with no arguments and a non-determinism flag, re-run on retry): the one non-pure function Velle already contemplates · OQ60 (transient acts in practice)

---

## 1. The question

The case that raised it:

```
transient act SignUp {
    name: text
    email: text
}
```

The owner wants sign-up refused when the email's domain does not accept mail. That is a DNS lookup. Nothing in a spec can say it today. The nearest spellings are `email matches "..."`, which checks the shape of the text, and a registered predicate `validEmail(email)`, which is Kotlin but is held to the same contract: pure, repeatable, never fails.

## 2. Why a function or a refinement cannot be the answer

These are the reasons given in chat, kept so the question does not reopen them:

- **Velle chooses how often a predicate runs.** Membership is never stored; `Customer where validEmail(email)` is re-evaluated at every read, every rule check, every `exists` or `count`, every sweep. A network call in that position runs without bound on data that has not changed.
- **The compiler reasons as if the answer cannot change.** A refusal is checked once at the door because the rules cannot change the verdict; a guarantee is proved and never checked; confluence requires one outcome in every order. A predicate whose answer depends on the world is `today` generalised, and `today` already needed V30 to keep constraints from reading it. The books could drift into a forbidden state with no act to refuse.
- **A function never fails.** Its only "no answer" is an absent value, read as false. A timeout would make an email invalid for two seconds and valid after; testgen's cases would stop being deterministic.

So whatever this is, it is not a predicate, not usable in a `shape ... where`, and not something a rule or a sweep re-asks.

## 3. What Velle offers today, without a new construct

A fact about the world at a moment is an input, and inputs arrive as acts:

- The caller does the lookup and the act carries the answer: `SignUp { ...  domainAcceptsMail: boolean }` with `SignUp requires domainAcceptsMail`. The check is the host's; the spec only trusts a field.
- The lookup is asynchronous: a second act `RecordDomainCheck { customer, acceptsMail, checkedOn }`, a refinement `UnverifiedCustomer`, and rules over it. The answer is on the books with a time, and a stale answer is a visible stale record.
- The outgoing direction is already a record behind `after commit` that the host application acts on (`ReceiptEmail`).

If the question settles as "leave it out", this section becomes the wiki's answer, as a worked spelling in `patterns.md` or on Reference §21, and this file is deleted.

## 4. Shapes an answer could take

Listed to be weighed, not chosen.

- **(a) Leave it out.** Reads from the world come in as acts; writes go out as records. Velle stays arithmetic over what is known. Cost: every system with an outside check writes the same two-act dance, and the spec cannot say that the check is required before admission.
- **(b) A check bound to an act, run once at the door.** A declaration on a transient act that names a question to ask outside, run exactly once when the act arrives and nowhere else, whose answer either refuses the act or lands as a field on the record the rule creates. It is not a predicate: it has no spelling inside a `where`, is never re-asked, and has a failure outcome of its own (unreachable, timed out) that is a refusal kind, not `false`. Things it would need: a Kotlin registration like a function's but with a different contract (may fail, may be slow, is called once); a place in the rejection data for "the world did not answer"; a rule for what the generated tests do with it (a stub, declared alongside); a rule for whether the recorded answer may be read by a constraint later (it is a field by then, so yes).
- **(c) A declared outgoing call whose answer comes back as an act.** The `after commit` record pattern made first-class: the spec names the call, the host implements it, and the result re-enters as an act Velle already knows how to admit. Closer to what (a) does by hand; the gain is that the spec names the round trip and the compiler can check the answer act exists.

Dave's framing points at (b) or (a): a thing that operates differently from a function and is not a refinement.

## 5. To settle, when it is taken up

- Whether a real spec wants it. No example spec has needed an outside check yet; the email case is from a chat, not a pass. The first realistic spec that does is the data point.
- If built: which of (b) or (c), and what the admission order is relative to the act's own `never`s (world check before or after the door checks that cost nothing).
- What a failed call means: a refusal with its own kind, a retry, or admission with the answer absent. Reference §21 says a tick's rejection must reach a human; an outside check that fails at the door has a caller, so the refusal has somewhere to go.
- How testgen stubs it, and whether the generated tests cover the failure outcome.
- Whether the generators residue item (`randomUUID`, non-deterministic, re-run on retry) is the same contract or a different one. Both are "a function that is not pure"; if one contract covers both, it belongs on `VelleFunction` as a flag and not here.
