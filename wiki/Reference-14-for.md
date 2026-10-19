# 14. `for`

A rule usually creates a record *about* another record: a welcome note for a member. `for` is the short way to say which record the new one is about. This page teaches both forms of creation.

"Every new member gets a welcome note," from `examples/membership/membership.velle`:

```
rule SendWelcome when Member {
    WelcomeNote for this sentOn: now
}
```

`WelcomeNote for this` creates a `WelcomeNote` with the current member in whichever field has type `Member`. Remaining fields follow as `name: value` pairs.

`for` matches a field by **type**. A static-union field matches any operand type ([§6](Reference-06-Relationships)). When two fields match, name the field: `Referral for referrer: this.referrer`.

The general form is `from`, which lists every field:

```
Referral from {
    referrer: this.referrer
    referee: this.referee
    referredOn: now
}
```

## What the compiler checks

- The compiler rejects a `from` block that omits a declared field, other than ones Velle fills itself such as `id` ([checks.md, F4](checks#f4--mapping-totality)).
- The compiler rejects `for` when more than one field matches the value's type ([checks.md, V13](checks#v13--ambiguity-is-a-connected-diagnostic)).

## Related pages

- [§10 Predicate expressions](Reference-10-Predicate-expressions): `for` inside a predicate.
