# OQ78 — Rewrite the V14 diagnostic so an author can read it

**Status:** opened 2026-11-08 by Dave from the OQ74 session. Not designed; D1–D3 await Dave.
**In plain terms:** when a derived property is defined using itself on a related record and the compiler cannot tell the chain ends, it prints one message for every such case, and the message is written in the compiler's own vocabulary: certificate, whitelist, strictly descends a creation-fixed datum, acyclic, to-one on its own shape. An author who has never seen those words cannot tell from it what in their spec failed or what to type instead. The question is what the message should say, whether it should name the one condition this spec missed rather than recite the list, and whether the rule that governs wiki prose applies to every diagnostic.
**See:** `checks.md` V14 · `compiler/src/main/kotlin/velle/Validator.kt` (`checkWellFoundedness`, the message at the `Diagnostic("V14", ...)` call; `certifiedSelfRecursion` knows which condition failed and throws the knowledge away) · CLAUDE.md "Writing for the wiki" (the word rules) · `working-docs/examples/survey/02-lookup-recursion-refused.velle` (the survey case that drew it; OQ76's file) · OQ76 (whether the walk should be a formula at all) · the item under "Examples and docs" for invalid examples with their diagnostics

---

## 1. The message today

`working-docs/examples/survey/02-lookup-recursion-refused.velle` draws:

```
[V14] definition cycle: Kind.locationHolder -> Kind.locationHolder — no certificate from the whitelist applies: v0 certifies a derived property that reads itself one hop through an optional to-one on its own shape, where the hop either strictly descends a creation-fixed datum (a derived `latest ... by` predecessor) or is proven acyclic by a spendable `never (Shape where hop == this)`; restructure, or supply a certificate (checks.md V14)
```

What it is trying to say: `locationHolder` on a kind is defined using `locationHolder` on the kind's parent, so the compiler has to know the chain of parents ends. It accepts two ways of knowing, and this spec has neither. The message names neither the property it followed (`parent`) nor which of the conditions failed. `certifiedSelfRecursion` finds out exactly which one fails (the self-read is not one hop; the hop is not optional; the hop does not point at the same shape; the hop is derived and not a strict `latest`/`first`; the hop is stored and no spendable `never` covers it) and returns a single `false`.

## 2. What to decide

### D1. One message per reason, naming the property and the spelling to type

Sketch for the survey case:

```
[V14] Kind.locationHolder is defined using locationHolder on this kind's parent, so the chain of parents must end. The compiler cannot tell that it does, because `parent` is a derived property found by lookup. It can tell when `parent` is a stored field and the spec says `never (Kind where parent == this)`, or when `parent` is `latest` or `first` over Kind picking a strictly earlier record by a field fixed at creation. Write one of those, or write the walk out to a fixed depth. (checks.md V14)
```

Each of the five reasons in §1 gets its own sentence in place of "because ...". The cycle that is not a self-read at all (two properties defining each other) keeps the path, `A.x -> B.y -> A.x`, and says that one of them has to be defined without the other.

### D2. The wiki's word rules apply to every diagnostic

The reader of a diagnostic is the reader of the wiki: a backend engineer or a product owner writing a spec. CLAUDE.md's rules for wiki prose (every noun a Velle keyword, an everyday word, or a glossary term; no compiler-theory vocabulary; code before abstraction; say what to type) would apply to every message the validator prints. Mechanism stays in `checks.md`, which the message points at by check number as it does now. Whether to sweep the other checks' messages under the same rule, and in what order, is the cost.

### D3. Where the long form lives

`checks.md` V14 is one paragraph and uses the same vocabulary. The Reference page an author would turn to is §7 (derived properties), which today does not say that a derived property may be defined through itself at all, nor the two forms that pass. The item under "Examples and docs" asks for invalid examples with their diagnostics; the survey's `02-lookup-recursion-refused.velle` is one.
