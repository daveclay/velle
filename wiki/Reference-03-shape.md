# 3. shape

A `shape` declares one kind of record your system keeps: its name and its typed properties.

```
shape Customer {
    name: text
    email: text
}
```

Each line names a property and its [type](Reference-05-Scalars). Instances of a plain `shape` are created only by [rules](Reference-11-rule-when-on). A shape declared [`act`](Reference-04-State-and-commits) can also be submitted from outside the system.

Shape names start uppercase, property names lowercase ([checks.md, F1](checks#f1--name-resolution-and-casing)).

Related: [§6 relationships](Reference-06-Relationships), [§7 derived properties](Reference-07-Derived-properties), [§8 refinements](Reference-08-Refinements).
