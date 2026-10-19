# 7. Derived properties

Some values in a system are never entered or set. An invoice's balance is its total minus what has been paid. A derived property is how Velle expresses such a value: a formula over other properties, computed from current data every time it is read, and never stored. This page shows how to declare one and what you can and cannot do with it.

From `examples/billing/billing.velle`:

```
balance: decimal = total - sum(payments, amount)
```

- `= expression` after the type makes the property derived. Compare `applied: boolean initially false`, which stores a starting value a rule may later change ([§5](Reference-05-Scalars)).
- The value is recomputed from current data on every read. It is never stale and never cached.
- A rule cannot assign a derived property, and a committer never supplies one. On an act, a derived property is allowed but is not part of the generated input ([checks.md, V21](checks#v21--act-field-forms)).
- Because derived values track the data, a record can enter or leave a [refinement](Reference-08-Refinements) such as `Invoice where balance <= 0` without any rule acting.

A formula may reach the same property on a related record, one link away:

```
root: one Foo? = if parent is none then none else (if parent.root is none then parent else parent.root)
```

No special syntax is needed. The compiler is responsible for evaluating it correctly.

## Related pages

- [§6 Relationships](Reference-06-Relationships), for derived collections such as `employees: many Person = (Person where employer == this)`.
- [§10 Predicate expressions](Reference-10-Predicate-expressions), for what a formula may contain.
- [§19 Self-referential folds](Reference-19-Self-referential-folds-and-tolerates), for a running value each record derives from its predecessor's.
