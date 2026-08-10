# Investigation: transient acts — is an act part of the state after its commit?

The central question. An act arrives from outside (an API request, a message). Today, committing it makes it a permanent fact — which is what makes the drift hazard possible (`examples/partition-drift/`): the act persists, so refinements over it are re-evaluated at every later change to the state they read, and an act handled long ago drifts between partition sides forever. Two designs answer the hazard, and they disagree on the question in the title:

- **Design A — the act persists; a declared contract disciplines its handling.** Drift is *checked* (upgraded from the A4 advisory to an error for marked shapes).
- **Design B — the act is truly transient: it exists only within its own commit's transaction.** Drift is *structurally impossible* — there is no later re-evaluation, because there is no later act.

An earlier draft of this file dismissed B with a circular argument ("removal breaks the handled-once anchor" — but the anchor only exists because acts persist; B doesn't need it). This version develops both honestly. Tracked in `working-docs/TODO.md`.

## Shared ground

Three things hold under either design:

- **Transience cannot be the default.** Most acts are simultaneously requests and durable business records, and the language's idioms depend on reading them later: guards (`UnappliedDeposit = Deposit where not applied`), the ledger (`EmailChange` read forever by `latest`), episodes (`DelinquencyFlag`, counted by `ChronicDelinquent`), audit (`count(RefusedVoid)`), captures and `why`. Whatever the marker means, it is opt-in, per shape, and `Payment`/`EmailChange`-style acts never carry it.
- **Boundary-only.** The marker is coherent on a produced shape but false on every produced shape worth having — pending-ness across time is exactly what produced work items exist to carry (`ChargeAttempt` is *deliberately* unresolved across the world gap). The one produced shape it would fit — a pure relay minted only to trigger same-transaction consequences — is a call graph wearing a shape costume, which idiomatic Velle already rejects (A2 territory). So the marker is a modifier on `expose`: transience is a statement about the trust boundary, the one place arrival-time discipline can't be derived statically.
- **A4 stays regardless.** Unmarked acts keep the drift hazard, and the advisory (implemented; checks.md) keeps catching it. Its current exposed-only scope is a separate conservatism worth revisiting — an unanchored partition of a produced shape over mutable state drifts identically.

## Design A: the act persists; `handled by` declares the contract

Nothing changes about storage or evaluation. The marker is a static contract — "this act's fate is decided by producing one of these outcomes, exactly once" — with the outcome set author-declared and the anchor derived:

```
expose shape SafeEdit {
    note: one Note
    newTitle: text
} using MockHarness handled by EditApplication, EditRefusal

shape EditApplication {
    edit: one SafeEdit
    appliedOn: DateTime
}

shape EditRefusal {
    edit: one SafeEdit
    reason: text
    refusedOn: DateTime
}

-- `unhandled SafeEdit` is an operator (no compiler-minted name), desugaring to:
--   SafeEdit where not exists EditApplication for this
--                  and not exists EditRefusal for this
shape ApplicableSafeEdit = unhandled SafeEdit where not note is LockedNote
shape RefusedSafeEdit    = unhandled SafeEdit where note is LockedNote

rule ApplySafeEdit when ApplicableSafeEdit {
    note.title = newTitle
    EditApplication from { edit: this, appliedOn: now }
}

rule RefuseSafeEdit when RefusedSafeEdit {
    EditRefusal from { edit: this, reason: "note is locked", refusedOn: now }
}
```

The outcome list must be *declared*, never inferred. Inference ("outcomes are whatever act-referencing shapes the act's rules produce") breaks at a distance: add an innocent audit rule —

```
shape EditAuditEntry {
    edit: one SafeEdit
    loggedOn: DateTime
}

rule AuditEdit when SafeEdit {
    EditAuditEntry from { edit: this, loggedOn: now }
}
```

— and `EditAuditEntry` silently matches the inference criteria (produced by a rule triggered off `SafeEdit`; carries `edit: one SafeEdit`), the derived anchor silently grows a third conjunct, and since the audit fires at every edit's creation commit, every edit is born "handled": `ApplySafeEdit`/`RefuseSafeEdit` never fire again, with no diagnostic. That is README §1's forbidden schema-dependent re-resolution. With the declared list, the audit rule is harmless, and what the contract refuses instead is a rule that touches the partition without producing a declared outcome (error, with add-to-the-list as one visible fix).

Derived obligations: partitions of the act on mutable state must hang off `unhandled X` (A4 → error); every such rule produces exactly one declared outcome (disarm proof, specialized); an unhandled act must always be handleable (no stranding); only handling rules may produce a declared outcome shape.

**Under A, references to the act are not just legal — they're the mechanism**: the anchor conjunct is a reference from outcome to act, and audit/`why` read through it. Later reads of handled acts stay unrestricted (ledgers over acts, audit counts): the contract disciplines handling-time rules only.

**Costs of A**: the act's permanence is preserved, but so is the entire hazard class it enables — every discipline is a check the author can only get right because the compiler is watching. The mental model mismatch also survives: the spec still says "this request is a permanent fact," and the author must learn why.

## Design B: the act exists only within its own commit's transaction

The semantics the word "transient" actually implies, taken seriously. A transient act enters, its commit's transaction runs to completion — every triggered rule fires, partitions are evaluated against the state *at that moment* — and at the transaction's close the instance leaves the state. Consequences:

- **Drift is impossible by construction.** Partition refinements over the act are evaluated at exactly one moment. No anchor apparatus, no `unhandled` operator, no handled-once idiom, no A4 for marked shapes — the entire exhibit's failure mode has nothing to attach to. This is strictly stronger than any check.
- **References to the act are forbidden; data flows forward by copy.** A durable shape holding `edit: one SafeEdit` would dangle, so it's a compile error. Outcomes copy what they need at the handling commit — which is ordinary `from { }` mapping, no new machinery:

  ```
  expose transient shape SafeEdit {
      note: one Note
      newTitle: text
  } using MockHarness

  shape EditRefusal {
      note: one Note            -- durable referent: fine
      requestedTitle: text      -- the act's payload, copied
      reason: text
      refusedOn: DateTime
  }

  rule RefuseSafeEdit when (SafeEdit where note is LockedNote) {
      EditRefusal from { note: note, requestedTitle: newTitle, reason: "note is locked", refusedOn: now }
  }
  ```

  No propagation question arises — not because references are safe, but because they don't exist; outcomes are ordinary durable shapes.
- **Handling must complete in-transaction.** An `after commit` or tick rule can't be triggered by the act (it's gone by then), so a transient act's whole consequence graph settles within its transaction. An act whose handling needs a declared boundary or an external effect simply can't be transient — a real restriction, but a legible, declared one.
- **Correlation needs a key, not a reference.** How does the caller match a refusal to their request? Under B the act's `id` dies with it, so correlation is a business key the act carries and outcomes copy (`requestKey: text` — the idempotency-key pattern real APIs already use). This is a genuine design demand B creates: either an id-valued copyable field kind, or the convention that transient acts carry client-supplied keys.
- **Audit is what outcomes chose to record.** "Show me last month's refused edits" reads `EditRefusal` rows — complete exactly to the extent the outcome copied the payload. Under A the act itself is the audit record; under B the author decides what survives, per outcome.
- **Erasure falls out for free, for the marked shapes.** A transient act's payload never persists — request-body retention (often the GDPR-sensitive part) is solved by construction, connecting to the deletion section below.
- **The principle tension is real but answerable.** "A produced fact records something that happened, and deleting the record would make the description lie" (README §4) — B's answer is that a transient act is not a *fact of the domain* but a *message to it*: what happened, as far as the model cares, is what its outcomes recorded. §4's "committing an instance is persisting the record" becomes, for transient shapes, "committing the instance is persisting its *consequences*." Whether that refinement of the philosophy is acceptable is the heart of the open question.
- **Provenance thins.** `why` currently walks reified acts; under B it walks outcomes, and the step from outcome back to the vanished act exists only if compilation keeps its own sub-language trace (plausible — provenance is tooling, not state — but it's a commitment).

## Decision: Design B is the working direction (2026-08-10)

The deciding rationale, from the project's own philosophy: *the black-box state of a system never changes until an external thing happens to it — by convention, ticks of an arbitrary scheduler, or incoming mutation from outside.* Incoming-mutation-as-shape was attractive, but Design A turns it into an awkward categorization of state around incoming-vs-state shapes: handled/unhandled isn't intuitive, and rules over incoming shapes are hard to read for what they describe. Design B gives the philosophy its missing corollary — **an incoming mutation is an input to the state, not automatically a member of it** — and the drift hazard, the anchor apparatus, and the handled/unhandled vocabulary all vanish with the premise that created them.

Design A is retained above as the documented fallback: if B produces gaps or clunky specs in practice, revisit A with a more intuitive surface.

**Validation plan** (the former deciders, now B's work list) — first adversarial pass done: `break-b.md` works an auction house (history acts, evidence acts, double-submit, async fraud review, external effects) against B. Results:

- **B's sweet spot confirmed** — synchronous accept/refuse partitions lose the entire anchor apparatus; and the expected wall cases *dissolved*: async decisions and external effects are handled by "materialize, then decide/effect" — the handler copies the act into a durable intent within its transaction, and the boundary machinery hangs off the intent (intent-before-effect, already canonical).
- **The one GAP: a request no rule answers.** In some reachable state, a transient act can arrive that triggers no rule — and because the act isn't kept, no record that it arrived exists anywhere afterward (under persistence it would at least sit visible as an unprocessed request). B therefore owes an **every-request-gets-a-response obligation**: the act must provably match at least one rule in every reachable state, which lands on README §8's unbuilt exhaustiveness checker (kin to `states of`). Fail closed until provable. Desirable independent of the gap — it's the compiler asking the product question "what should happen to this request in this situation?" and refusing to proceed until the spec answers. This is the make-or-break work item.
- **TAX cases, all one idiom** — history acts, evidence acts, and idempotent ingestion each materialize a durable record and copy the payload forward; the durable/transient split becomes an explicit authoring decision. Double-submit confirms the **correlation-key design** as real work (client-supplied keys threaded through outcomes).
- **The static ban list B must ship with** (each a one-line consequence of "not part of the state," and what makes misuse a compile error rather than misbehavior): refinements of a transient act read outside its commit; `when leaving` over them; tick or `after commit` triggers on them; inferred inverse collections; captures/derivations reading the act from state shapes; `(TransientShape for x)` beyond the act's transaction.
- **Still open from the original plan:** respell the drift exhibit and payments' `ChangeShippingAddress` under B (expected ceremony win; measure copied-field cost); confirm outcome-mediated provenance satisfies `why`.

**A question the decision raises: is transient the default?** The philosophy taken to its limit says *every* incoming mutation is a message, and durable "acts" (`EmailChange`, `Deposit`) are really messages whose handlers record facts — expressible under B by a handler copying into a durable record shape. What blocks message-as-default is ergonomics the language already leans on: today "committing a shape instance *is* persisting the record" (§12, "No act-level sugar"), so the common submission case needs no rule at all; message-default would demand a recording rule per submission — ceremony where there was none. Current position: transient is opt-in, persistence stays the default; revisit only if B-in-practice suggests otherwise.

## Deletion proper: a storage concern, tracked separately

Velle's "delete has no primitive" stance (README §4) is about *description*: facts don't un-happen, and a spec that erases records lies. But real systems owe **erasure** — retention windows, right-to-be-forgotten — and that obligation is about *storage*, not description: "an edit occurred" can remain true in the model while its payload ceases to be physically retrievable. That places retention/erasure with compilation (the same layer as "which database"), plausibly as declared policy the transpiler enforces (annotations on shapes/fields; crypto-shredding or hard deletion as mechanism). What the language owes is at most the policy vocabulary — and a check that no derivation or guard depends on data the policy allows to vanish. Design B above solves the *request-payload* slice of this by construction; the durable-record slice (retention on facts the model keeps) remains its own question. Not designed; tracked as its own TODO item.
