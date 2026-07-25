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

# Stress test: how complex can one refinement get?

Every gap resolved in `example_predicates.md` has been about individual grammar rules — one operator, one binding form, one ambiguity at a time. This doc asks a different question: given all of that is settled, what does a genuinely complex, real-world business query actually look like in Velle — and where's the ceiling?

The forcing case: a fraud/risk-flagging query, the kind that naturally accumulates SQL CTEs (`WITH ... AS (...)`) because no single `SELECT` can hold it without becoming unreadable.

## The SQL version

```sql
WITH recent_overdue AS (
    SELECT customer_id, COUNT(*) AS overdue_count
    FROM invoices WHERE balance > 0 AND due_date < CURRENT_DATE - INTERVAL '90 days'
    GROUP BY customer_id
),
disputed_invoices AS (
    SELECT DISTINCT i.customer_id FROM invoices i
    JOIN support_tickets t ON t.invoice_id = i.id
    WHERE t.status = 'open' AND i.balance > 0
),
referral_risk AS (
    SELECT r.referrer_id FROM referrals r
    JOIN risk_flags rf ON rf.customer_id = r.referee_id
    WHERE rf.flagged_on <= r.referred_on + INTERVAL '30 days'
),
lifetime_value AS (
    SELECT customer_id, SUM(amount) AS total_paid FROM payments GROUP BY customer_id
)
SELECT c.id FROM customers c
JOIN recent_overdue ro ON ro.customer_id = c.id AND ro.overdue_count > 2
JOIN lifetime_value lv ON lv.customer_id = c.id AND lv.total_paid > 10000
LEFT JOIN active_risk_flags arf ON arf.customer_id = c.id
WHERE arf.customer_id IS NULL
  AND (c.id IN (SELECT customer_id FROM disputed_invoices) OR c.id IN (SELECT referrer_id FROM referral_risk))
```

Four CTEs, a left-join-anti-pattern (`LEFT JOIN ... WHERE ... IS NULL`, standing in for "doesn't exist"), an aggregate, date arithmetic, two `IN`-subqueries.

## The Velle version

```
shape Customer {
    name: text
    invoices: many Invoice
    payments: many Payment
    totalPaid: Money = sum(payments, amount)
}
shape Invoice {
    customer: one Customer
    balance: Money
    due: Date
}
shape SupportTicket {
    invoice: one Invoice
    status: text
}
shape Referral {
    referrer: one Customer
    referee: one Customer
    referredOn: DateTime
}
shape RiskFlag {
    customer: one Customer
    flaggedOn: DateTime
    resolvedOn: DateTime?
}

-- recent_overdue
shape RecentOverdueInvoice   = Invoice where balance > 0 and due < (today - 90 days)
shape MultipleRecentOverdues = Customer where count(invoices where RecentOverdueInvoice) > 2

-- disputed_invoices
shape DisputedInvoice           = Invoice where balance > 0 and exists (SupportTicket where invoice == this and status == "open")
shape CustomerWithDisputedIssue = Customer where exists (invoices where DisputedInvoice)

-- lifetime_value
shape HighLifetimeValue = Customer where totalPaid > 10000

-- active_risk_flags (the LEFT JOIN ... WHERE arf.customer_id IS NULL anti-pattern)
shape ActiveRiskFlag           = RiskFlag where resolvedOn is none
shape CustomerCurrentlyFlagged = Customer where exists (ActiveRiskFlag where customer == this)

-- referral_risk
shape ReferralLedToRisk      = Referral where exists (RiskFlag where customer == this.referee and flaggedOn <= this.referredOn + 30 days)
shape CustomerWhoReferredRisk = Customer where exists (ReferralLedToRisk where referrer == this)

-- final SELECT
shape HighRiskCustomer =
    MultipleRecentOverdues
    and HighLifetimeValue
    and (CustomerWithDisputedIssue or CustomerWhoReferredRisk)
    and not CustomerCurrentlyFlagged
```

Nine named refinements, mirroring the four CTEs plus the final `WHERE`. Every line reduces to grammar already resolved elsewhere: `and`/`or`/`not` (`example_predicates.md` #2, `example_refinements.md`), `is`/`exists` (#3), `count` (#6), duration arithmetic (#1's grammar), and the `exists (Shape where predicate)` form (`example_predicates.md` #12, revised) for existence checks that need more than one condition on the matched instance.

**None of these nine actually needs `as`, and that's worth dwelling on rather than skipping past.** Every earlier draft of `CustomerCurrentlyFlagged` and `ReferralLedToRisk`/`CustomerWhoReferredRisk` reached for `as rf`/`as ref` to re-check a named refinement inline or to reach the outer subject's own field — both times, the binding was doing no real work: filtering by the named refinement directly (`ActiveRiskFlag where customer == this`, `ReferralLedToRisk where referrer == this`) says the same thing with nothing bound, and `this.referee`/`this.referredOn` already reach the outer `Referral` explicitly without needing a name for the inner `RiskFlag` at all. `as` only earns its keep when a *deeper* nested scope needs a *middle* level's own field — nothing in this whole nine-refinement chain ever does that (`example_predicates.md` #7, corrected). Reaching for it defensively "just in case" is exactly the kind of computer-layer ceremony leaking into the logic layer that's worth resisting — the compiler is what catches an actual ambiguity; the human shouldn't have to pre-empt one that was never there.

## The actual lesson

Try inlining `HighRiskCustomer` as one expression, no named intermediate refinements — it's exactly as unreadable as pasting all four CTEs into the `SELECT` as nested subqueries would be. That's not a coincidence; it's the same problem SQL CTEs exist to solve, hit from the language-design side instead of the query-authoring side.

The ceiling this stress test actually finds isn't an expression-depth ceiling — nothing here needed new mechanism, and `where` clauses never got more than two or three conditions deep at any single level. It's a **composition-depth** ceiling: how many named refinements you're willing to combine at the top before the *chain* itself gets hard to hold in your head. Velle's answer to "how do you handle CTE-level complexity" was never a syntax feature — it's the same discipline `example_refinements.md` established for mixins: decompose into small, named, reusable pieces, and let `and`/`or`/`not` do the combining. The difference from a real CTE: a CTE only exists for the lifetime of one query; a named Velle refinement (`DisputedInvoice`, `ActiveRiskFlag`) is permanent, reusable vocabulary that shows up in the *next* query too, unlabeled and forgotten in SQL, but sitting right there in `LANGUAGE.md`'s frame of reference in Velle.

This example is also what finally settled `example_predicates.md` #12: the first-attempt colon-pair form (`for referee: this`) read badly and didn't survive the `ReferredByVip`-style two-condition case above, which is what pushed the resolution to retiring it in favor of `exists (Shape where predicate)` — now the documented resolution there and in `LANGUAGE.md`.
