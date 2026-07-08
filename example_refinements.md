# Stress test: refinement noise at scale

Concern: `where` could get noisy as the number of refinements on a shape grows. This is a worked example with ~10 refinements on one shape, to see where that noise actually shows up and whether existing syntax already has an answer for it.

## The shape

```
shape SupportTicket {
    status: text
    priority: text
    assignee: one User?
    due: Date
    escalations: integer
    customerTier: text
    reopenedCount: integer
    lastUpdated: Date
}
```

## Naive refinements

```
shape UnassignedTicket       = SupportTicket where assignee is none and status != "closed"
shape OverdueTicket          = SupportTicket where due < today and status != "closed"
shape UrgentTicket           = SupportTicket where priority == "high" and status != "closed"
shape UrgentUnassignedTicket = SupportTicket where assignee is none and status != "closed" and priority == "high"
shape UrgentOverdueTicket    = SupportTicket where due < today and status != "closed" and priority == "high"
shape EscalatedTicket        = SupportTicket where escalations >= 1 and status != "closed"
shape BreachedSlaTicket      = SupportTicket where due < today and status != "closed" and priority == "high" and escalations >= 1
shape VipTicket              = SupportTicket where customerTier == "vip" and status != "closed"
shape VipUnassignedTicket    = SupportTicket where assignee is none and status != "closed" and customerTier == "vip"
shape VipOverdueTicket       = SupportTicket where due < today and status != "closed" and customerTier == "vip"
shape StaleTicket            = SupportTicket where lastUpdated < (today - 7 days) and status != "closed"
shape ReopenedTicket         = SupportTicket where reopenedCount >= 1 and status != "closed"
```

## Where the noise actually is

Countable repetition across 12 refinements:

- `status != "closed"` — 12 of 12
- `due < today` — 3
- `assignee is none` — 3
- `priority == "high"` — 3
- `customerTier == "vip"` — 3

Every combination restates its ingredients from scratch instead of building on something already named.

## Partial fix: refine a refinement, not just the base shape

A refinement's base doesn't have to be the root shape — it can be another refinement, since a refinement is just a shape:

```
shape UrgentOverdueTicket = OverdueTicket where priority == "high"
```

instead of restating `due < today and status != "closed"`. This linearizes cleanly for single-dimension specialization: ticket → overdue → urgent-overdue.

## Where the partial fix breaks down

`priority == "high"` and `customerTier == "vip"` each need to combine independently with multiple unrelated branches (`Unassigned`, `Overdue`, `Sla`) — that's a diamond, not a chain. Nothing so far lets you compose two *named* refinements together, only raw predicates inside one `where`. What's missing looks like conjunction of shapes, not conditions:

```
shape UrgentOverdueTicket = OverdueTicket and UrgentTicket
```

This combinator doesn't exist yet.

## Resolution: mixins are just small refinements, composed with `and`

A mixin isn't a new construct — it's a refinement, same as always, just deliberately atomic and named to read like a trait rather than a full entity state:

```
shape Open         = SupportTicket where status != "closed"
shape Unassigned   = SupportTicket where assignee is none
shape Overdue      = SupportTicket where due < today
shape HighPriority = SupportTicket where priority == "high"
shape Escalated    = SupportTicket where escalations >= 1
shape Vip          = SupportTicket where customerTier == "vip"
shape Stale        = SupportTicket where lastUpdated < (today - 7 days)
shape Reopened     = SupportTicket where reopenedCount >= 1
```

The composite, business-facing refinements become pure intersections of these, by name:

```
shape UnassignedTicket       = Unassigned and Open
shape UrgentTicket           = HighPriority and Open
shape UrgentUnassignedTicket = Unassigned and HighPriority and Open
shape OverdueTicket          = Overdue and Open
shape UrgentOverdueTicket    = Overdue and HighPriority and Open
shape BreachedSlaTicket      = Overdue and HighPriority and Escalated and Open
shape VipTicket              = Vip and Open
shape VipUnassignedTicket    = Unassigned and Vip and Open
shape VipOverdueTicket       = Overdue and Vip and Open
```

Zero raw predicates duplicated anywhere in the composite list — every line reads as a plain intersection of named, self-explanatory traits. `BreachedSlaTicket` went from a four-clause boolean expression to four trait names, and reads better, not just shorter.

Two things worth naming about `and` here:

- **It isn't new syntax, just a new position for `and`.** `shape X = A and B` desugars to `shape X = SupportTicket where (A's predicate) and (B's predicate)` — the same boolean composition `where` already does, just operating on named predicates instead of anonymous ones. One operator, reused, not a second mechanism bolted on.
- **It only typechecks when the operands share a base shape** (or one refines the other). `Overdue and HighPriority` works because both are `SupportTicket` refinements; `Overdue and SuccessfulCharge` should be a compile error — they refine unrelated shapes and intersecting them is meaningless. The compiler gets this guardrail for free from the design, no separate rule needed.

The `Ticket` suffix was dropped from the atomic mixins (`Open`, `Overdue`, `HighPriority`, not `OpenTicket`, `OverdueTicket`, `HighPriorityTicket`) — they read more like traits/adjectives that way, matching how they're meant to be used: combined, not referenced standalone.

**Not solved here:** true mixins in the OOP sense are usually reusable *across* unrelated shapes (e.g. `Overdue` applying to both `SupportTicket.due` and `Invoice.due`), and everything above is still scoped to one base shape (`SupportTicket`). Whether that structural, cross-shape reuse is worth designing for is a separate, bigger question — flagged, not decided.

>
