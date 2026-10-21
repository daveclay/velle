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

## Naming the record a statement creates (`as`)

A rule that creates two records, one pointing at the other, needs a name for the first. `as` gives it one. The name stands for the created record as a reference, for the other statements of the same body.

"Open an account review, and log an audit entry that names the review it opened," from `examples/membership/membership.velle`:

```
rule OpenAccountReview
    when (ChronicDelinquent where not exists (AccountReview where member == this))
    on commit, Daily {
    AccountReview from { member: this, openedOn: now } as review
    AuditEntry from { member: this, review: review, note: "account review opened", loggedOn: now }
}
```

- `as review` names the record the first statement creates.
- `review: review` in the second statement points the audit entry at it. The name may stand wherever a reference is taken: a field of another creation, or the target of `for`.
- The second statement reads `review`, so it runs after the first. No `then` is needed ([§15](Reference-15-then)). The two records land in one commit.
- The name is the record and nothing more. The record has no fields until the commit lands, so `review.member` is not allowed. Read `this`, or the values the review was built from.

## What the compiler checks

- The compiler rejects a `from` block that omits a declared field, other than ones Velle fills itself such as `id` ([checks.md, F4](checks#f4--mapping-totality)).
- The compiler rejects `for` when more than one field matches the value's type ([checks.md, V13](checks#v13--ambiguity-is-a-connected-diagnostic)).
- The compiler rejects an `as` name that is already a name in the rule, a read through the name, the name in an assignment or a `delete`, and two named creations pointing at each other ([checks.md, V33](checks#v33--a-creations-as-name)).

## Related pages

- [§10 Predicate expressions](Reference-10-Predicate-expressions): `for` inside a predicate.
- [§15 then](Reference-15-then): ordering statements that do not read each other.
- [§6 Relationships](Reference-06-Relationships), "A reference supplied by value": the same want on the committer's side.
