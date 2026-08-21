# OQ43 — Transient containers: a closure whose container is `expose transient`

**Status:** open — spun off from OQ39 (2026-08-21)
**In plain terms:** an order form that is pure request — the act and the inline parts it arrives with all vanish at transaction close, and only what rules copied survives. Can an `expose transient` container carry a closure at all, what must V17 (transient isolation) be amended to allow before even the coherent version is declarable, and is a durable act ever allowed to carry a transient *branch* — payload that must never be stored?
**See:** OQ39 (the closure: one commit = one statically-shaped closure; v0 trees; the `expose ... with` sketch) · README §4 "Transient acts (`expose transient`)" · `checks.md` V17 (transient isolation), V18 (transient totality)

---

## What V17 already decides: transience is downward-closed over the tree

OQ39 originally posed a binary — the parts fall with the container, or the combination is rejected — and V17's reference-direction law picks the branch. V17's first error is "a stored or derived property typed `one`/`many` of a transient shape, anywhere" (`checks.md` V17), and every part declares a back-reference to its parent, so a durable part under a transient container is that error at the declaration site. It cascades level by level: if `OrderLine` must be transient, then `Customization.line: one OrderLine` forces `Customization` transient too. So transience is **downward-closed over the closure tree**, and a transient root leaves exactly one coherent reading: the whole tree falls together at transaction close, only copied consequences surviving. There is no partial version on this side.

## The carve-out even the all-transient tree needs

V17 as written refuses to let the forced combination be *declared*. Its "anywhere" makes no exception for the referencing shape being transient itself — `OrderLine.order: one Order` is a property typed of a transient shape regardless of `OrderLine`'s own transience — and the parenthetical "no references, hence no inferred inverses" removes the very name (`orderLines`) that OQ39's candidate `expose ... with` spelling resolves against. The amendment OQ39 anticipated ("V17 changes if it must") can now be scoped precisely:

- references to a transient shape are legal exactly from shapes falling in the **same closure**;
- inverse inference happens for closure edges, with the inferred name existing **only for `with`-resolution, never in expressions** — so V17's cross-act-read ban stays whole.

## The converse mix: durable container, transient branch

Nothing forces this combination out — a transient shape referencing a durable one is the normal direction (README §4, `ChangeDueDate.invoice: one Invoice`). The business case is ephemeral payload riding a durable act: card details that arrive inline with the order, fire the charge-intent rules inside the transaction, and must never be stored. What's missing is a **declaration site**: `transient` is a modifier on the exposure, and parts aren't exposed, so per-branch transience is new surface (a `with transient cardDetails` shape of thing). This is a separate decision from the carve-out, and plausibly deferred past the closure's v0.

## What must be decided

### 1. The scoped V17 carve-out — sign it or reject the combination

Either amend V17 with the same-closure exception above (making the all-transient tree declarable), or rule that a transient container refuses a closure in v0. The carve-out is small and precisely bounded; the rejection is smaller still and additive to lift later. Note OQ39's tree restriction is what makes the carve-out tractable at all: every in-closure edge points at the container's tree, so "falls in the same closure" is statically known.

### 2. Verification reach — does V18 lift to the parts?

V18 (every request gets a response) applies "for each transient shape," and transient parts are transient shapes: a part could be individually unanswered while the container is answered. Whether the container's answer counts as the response to the whole submission — the closure as one request — or each part shape owes its own coverage is a verification-semantics question the carve-out must settle alongside itself.

### 3. The durable-container / transient-branch mix — defer explicitly or design the declaration site

If deferred, say so where the closure's design lands, so the ephemeral-payload use case is a recorded non-goal rather than a gap.
