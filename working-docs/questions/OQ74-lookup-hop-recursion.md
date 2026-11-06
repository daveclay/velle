# OQ74 — A walk up a tree whose edges are held as names: V14's certificate for a derived to-one hop

**Status:** opened 2026-11-06 from a stress pass over a system whose record types are data (`working-docs/examples/survey/`, the `02-lookup-recursion-*` files). Not designed. Dave's direction: list and work through.
**In plain terms:** a schema editor stores a kind's parent as the parent's *name*, so a spec spells the parent as a lookup, `parent: one Kind? = if ... then first(Kind where name == this.parentName by name) else none`, and then wants "the nearest kind up the tree that records a location", `locationHolder: one Kind? = if hasLocation then this else parent?.locationHolder`. The compiler refuses the walk: a derived property may read itself only through a hop it can certify, and v0 certifies a stored to-one with a spent `never (Kind where parent == this)`, or a derived `latest`/`first` that strictly descends a creation-fixed datum. A lookup by name is neither. The same walk over a stored `parent: one Kind?` with the same `never` is accepted. The author's only spelling today is the walk unrolled to a fixed depth, which is wrong for a deeper tree and silently so.
**See:** `checks.md` V14 · Reference §7 (derived properties), §19 (self-referential folds), §21 (`never`) · `compiler/src/main/kotlin/velle/Validator.kt` (`checkWellFoundedness`, `certifiedSelfRecursion`, `hopCertified`, `strictDescentSelector`) · OQ16 (carries the V14 certificate-whitelist calibration) · OQ53 (the other fail-closed checks a realistic spec meets)

---

## 1. The case

`working-docs/examples/survey/02-lookup-recursion-refused.velle`:

```
act Kind {
    name: text
    parentName: text?
    hasLocation: boolean

    parent: one Kind? =
        if parentName is none then none
        else if exists (Kind where name == this.parentName) then first(Kind where name == this.parentName by name)
        else none

    locationHolder: one Kind? =
        if hasLocation then this
        else parent?.locationHolder
}

never (Kind where count(Kind where name == this.name) > 1)
never (Kind where parent == this)
```

Diagnostic today:

```
[V14] definition cycle: Kind.locationHolder -> Kind.locationHolder — no certificate from the whitelist applies: v0 certifies a derived property that reads itself one hop through an optional to-one on its own shape, where the hop either strictly descends a creation-fixed datum (a derived `latest ... by` predecessor) or is proven acyclic by a spendable `never (Shape where hop == this)`; restructure, or supply a certificate (checks.md V14)
```

`02-lookup-recursion-stored-accepted.velle` is the same walk with `parent: one Kind?` stored and the same `never`; it passes. `02-lookup-recursion-workaround.velle` unrolls the walk four levels.

## 2. What v0 certifies, and why the lookup falls outside it

`hopCertified` accepts a hop when it is stored and `neverForbidsSelfReference` finds a spent `never (S where hop == this)`, or when it is derived and `strictDescentSelector` finds a `latest`/`first` over the own shape whose predicate strictly compares a creation-fixed datum against the subject's. The lookup is derived, and its selector compares `name == this.parentName`, an equality, not a strict descent. So neither branch applies.

The acyclicity argument the stored case rests on is about the hop's *value*: no instance's parent is itself. That argument does not depend on where the value is kept. A `never (Kind where parent == this)` over a derived `parent` says the same thing, and V10 treats it the same way (a refusal or a constraint over inputs, spendable once discharged).

What the stored case does not prove either: a cycle longer than one. `never (Kind where parent == this)` rules out the self-loop only; a two-kind cycle (A's parent is B, B's parent is A) passes the certificate today. That coarseness is the same for both forms and is OQ16's calibration, not this question's.

## 3. What to decide

### D1. Accept a derived to-one hop on the same shape when a spendable `never (S where hop == this)` is present

The change is in `hopCertified`: drop the `m.stored` condition for the acyclicity branch, keeping the derived branch's strict-descent alternative. The hop must still be one optional to-one on the same shape, and the `never` must be spent (V10). The diagnostic text changes to say "a to-one hop" rather than "a stored to-one".

Cost: nothing new to prove; the direct-case argument is unchanged. The runtime's unfolding is the same.

### D2. Or keep the stored requirement and document the workaround

The Reference would say: a tree whose edges are held as names must be unrolled, or the reference must be stored. Unrolling is wrong silently for a deeper tree; storing changes the model a system already has.

### D3. Whether the lookup form earns its own spelling

Every reference held as a name is today two expressions, `if exists (X where k == this.k) then first(X where k == this.k by k) else none`, plus a `never` making `k` unique so `first` cannot fail on a tie. A system whose record types are data has a dozen of these. Whether Velle gains a form for "the one X whose k is this.k, or none" is a separate question from the certificate and may belong with the singular-reference work (`singular_references.md`, V12).
