# 7. Derived properties

A property can be defined as a computation over other properties instead of stored data:

```
balance: Money = amount - sum(payments, amount)
```

Derived properties are recomputed from current data, not cached/stored — they're the mechanism by which refinement membership can change without any explicit action (see `## Refinements`, §8).

A derived property's formula may reference the same property one hop away through a relationship (self-reference) — e.g. `root: one Foo? = if parent is none then none else (if parent.root is none then parent else parent.root)`. This needs no special syntax; correctly evaluating it is a compiler obligation (`## Principles`), not a language concern — see Predicate expressions, below.
