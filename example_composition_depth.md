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
shape CustomerCurrentlyFlagged = Customer where exists (RiskFlag as rf where rf.customer == this and rf is ActiveRiskFlag)

-- referral_risk
shape ReferralLedToRisk      = Referral where exists (RiskFlag as rf where rf.customer == referee and rf.flaggedOn <= referredOn + 30 days)
shape CustomerWhoReferredRisk = Customer where exists (Referral as ref where ref.referrer == this and ref is ReferralLedToRisk)

-- final SELECT
shape HighRiskCustomer =
    MultipleRecentOverdues
    and HighLifetimeValue
    and (CustomerWithDisputedIssue or CustomerWhoReferredRisk)
    and not CustomerCurrentlyFlagged
```

Nine named refinements, mirroring the four CTEs plus the final `WHERE`. Every line reduces to grammar already resolved elsewhere: `and`/`or`/`not` (`example_predicates.md` #2, `example_refinements.md`), `is`/`exists` (#3), `as` bindings (#7), `count` (#6), duration arithmetic (#1's grammar), and the `exists (Shape where predicate)` form (`example_predicates.md` #12, revised) for existence checks that need more than one condition on the matched instance.

**`CustomerCurrentlyFlagged` is the one worth reading twice.** Inside `RiskFlag as rf where rf.customer == this and rf is ActiveRiskFlag`, `rf is ActiveRiskFlag` only typechecks *because* `rf` is bound with `as`. Without the binding, bare `this` inside that nested `where` means the outer `Customer` (#5's rule: `this` never rebinds, unqualified names mean the innermost element) — so `this is ActiveRiskFlag` would silently ask "is this Customer an ActiveRiskFlag," which typechecks as false-but-meaningless rather than erroring, since nothing here forces `Customer` and `RiskFlag` to share a base shape at that position (`is`'s compatibility guardrail only kicks in when both sides *do* share ancestry — a `Customer` and a `RiskFlag` refinement simply don't, so it should actually be a compile error there, not a silent wrong answer — but that's exactly why the explicit `as rf` binding is what makes the correct reference possible in the first place, not just clearer).

## The actual lesson

Try inlining `HighRiskCustomer` as one expression, no named intermediate refinements — it's exactly as unreadable as pasting all four CTEs into the `SELECT` as nested subqueries would be. That's not a coincidence; it's the same problem SQL CTEs exist to solve, hit from the language-design side instead of the query-authoring side.

The ceiling this stress test actually finds isn't an expression-depth ceiling — nothing here needed new mechanism, and `where` clauses never got more than two or three conditions deep at any single level. It's a **composition-depth** ceiling: how many named refinements you're willing to combine at the top before the *chain* itself gets hard to hold in your head. Velle's answer to "how do you handle CTE-level complexity" was never a syntax feature — it's the same discipline `example_refinements.md` established for mixins: decompose into small, named, reusable pieces, and let `and`/`or`/`not` do the combining. The difference from a real CTE: a CTE only exists for the lifetime of one query; a named Velle refinement (`DisputedInvoice`, `ActiveRiskFlag`) is permanent, reusable vocabulary that shows up in the *next* query too, unlabeled and forgotten in SQL, but sitting right there in `LANGUAGE.md`'s frame of reference in Velle.

This example is also what finally settled `example_predicates.md` #12: the first-attempt colon-pair form (`for referee: this`) read badly and didn't survive the `ReferredByVip`-style two-condition case above, which is what pushed the resolution to retiring it in favor of `exists (Shape where predicate)` — now the documented resolution there and in `LANGUAGE.md`.
