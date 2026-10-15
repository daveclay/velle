# 14. `for`

Associates a newly created shape instance with the subject it's about:

```
Receipt for invoice sentOn: now
```

`for <expr>` matches by **type**, not name: whichever field on the created shape has a type matching `<expr>`'s type is the one populated — a field typed with a static union matches an expression of any operand's type (`## Relationships`). When a shape has more than one field of that type, name the field explicitly: `Referral for referrer: this.referrer`.

Inside a rule body, `from { field: value, ... }` is the general, clearer way to write this — every field as an ordinary, totality-checked mapping entry, with no field singled out syntactically:

```
Referral from {
    referrer: this.referrer
    referee: this.referee
    referredOn: now
}
```

Creating an instance is an ordinary body statement, nothing more — no header annotation is involved. When the created shape serves as a guard witness, the connection to the trigger is the disarm proof's job, checked rather than spelled. `for` as a *query* expression (`(NurseVerification for this).nurse`, `exists Shape for expr`) is unaffected by this — see `## Predicate expressions`, above.
