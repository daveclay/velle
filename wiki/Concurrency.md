# Concurrency

A Velle system handles many commits and reads at the same time, and the author writes no locks for it. The compiler reads each act's rules and prohibitions and works out which records and which values the commit is about. Two commits about the same record or the same value take turns. Every other pair runs together. After this page you can say which commits in your spec wait for which, read the queue keys the compiler reports, answer the warning it gives when a commit would hold up everything on a shape, and know what a read sees while commits are running.

## The situation

The billing team's system takes payments all day. Two payments on one invoice can arrive in the same second, and the second must see the first before it decides whether the invoice is now paid. A payment on a different customer's invoice should not wait for either. Sign-ups must not share an email address, even when two people sign up with the same address at once. The hourly receipt sweep runs during the busy hour, and the busy hour must not stop for it.

## The code

From `examples/billing/billing.velle`:

```
transient act SubmitPayment {
    invoice: one Invoice
    amount: Money
}

shape Payment {
    invoice: one Invoice
    amount: Money
    receivedOn: timestamp on create
}

rule RecordPayment when SubmitPayment {
    Payment from { invoice: invoice, amount: amount }
}

rule TrackLargestPayment when Payment {
    invoice.customer.largestPayment = max(invoice.customer.largestPayment, amount)
}

rule EmailReceipt when UnemailedReceipt after commit, Hourly {
    ReceiptEmail from { receipt: this, queuedOn: now }
}
```

A second spec, where the value two records may not share is the thing being guarded. It comes from `compiler/src/test/kotlin/velle/AdmissionKeysTest.kt`:

```
act BookNow { slot: text }
shape Reservation { slot: text }
never (Reservation where exists (Reservation as other where other.slot == this.slot and not (other == this)))
rule ApplyBooking when BookNow { Reservation from { slot: slot } }
```

## What each line does

- `SubmitPayment` is an [act](Reference-04-State-and-commits) carrying the invoice it pays and the amount. Each submission is one commit.
- `RecordPayment` is a [rule](Reference-14-Rules) that writes a `Payment` referencing that invoice. The commit reads the invoice and writes a row that points at it.
- `TrackLargestPayment` runs in the same commit and updates the customer the invoice belongs to. The commit therefore also reads and writes the customer.
- `EmailReceipt` runs on the hourly [schedule](Reference-17-Schedule-triggers). Each receipt it finds is handled in a commit of its own.
- The `never` on `Reservation` is a [prohibition](Reference-21-never): no two reservations hold one slot. `ApplyBooking` creates the reservation from the slot the act carries.

## What waits and what runs together

Before a commit does any work, Velle names the records and values the commit is about. Those names are its queue keys. Two commits with a key in common run one after the other, in the order they arrived. Two commits with no key in common run at the same time. The keys come from the spec alone: no author writes one.

For the billing acts, the compiler reports these keys, in `examples/billing/output/DIAGRAMS-Billing.md` under "Contention map":

| commit | queues on |
|---|---|
| `SubmitPayment` | `submitPayment.invoice`, `submitPayment.invoice.customer` |
| `LineItem` | `lineItem.invoice` |
| `SignUp` | nothing |
| `EmailReceipt`, each hourly firing | `this`, the receipt being emailed |

So two payments on one invoice take turns. Two payments on two invoices of one customer take turns as well, because both update that customer's largest payment. Two payments for two customers run together. A line item added to an invoice waits for a payment on the same invoice and for nothing else.

Each key is worked out per call, from the request itself and from records read before the commit's work begins. The payment request names its invoice, and the customer is one step from the invoice. Once a commit has its turn, everything it reads is the state the previous turn left behind. That is how the second of two payments on one invoice sees the first.

A key can also be a value rather than a record. A `BookNow` for "Tue 10:00" queues on `Reservation.slot=Tue 10:00`. A booking of another slot runs beside it, and a second booking of the same slot waits, then is refused by the prohibition. Velle reads the value from where the spec gets it: the act's own field, as here, a field of a record one of the act's references reaches, or the record a scheduled rule is running for. When a rule computes the value instead, Velle cannot know it before the rule runs. The commit then queues on the whole shape, and every commit touching `Reservation` takes turns. That is correct and slow. Carrying the value on the act is the way to narrow it.

## When one commit holds up a whole shape

A rule or prohibition sometimes reads records with nothing to tie them to the commit: every open invoice, say, rather than the invoices of this customer. The compiler cannot name a record for such a read, so the commit queues on the whole shape, and every other commit touching that shape waits for it. The compiler warns when this happens, naming the read and the rule or `never` carrying it. There are three answers, and each is a line in the spec:

1. Give the read a path back to the act, such as reading `customer.invoices` instead of every `Invoice`. This is usually a relationship the model was missing.
2. Move the rule to a schedule. The read then runs once per tick, in its own commit, instead of inside every act.
3. Write `tolerates contention` on the rule or `never`, which says the wait is intended. The contention map then shows the width as accepted.

The worked spelling of each answer is in [`patterns.md`, "A wide domain: the two authors"](patterns#a-wide-domain-the-two-authors).

## Scheduled rules

A tick does not stop the system. Each record a scheduled rule fires for is handled in a commit of its own, with its own keys, worked out from that record. The hourly `EmailReceipt` firing for one receipt queues on that receipt. A payment arriving during the sweep waits only if it is about the same receipt, which it never is. The rows for scheduled rules in the contention map read `this`, meaning the record the firing is for.

## Reads

A read never waits for a commit and never makes a commit wait. It answers from one consistent state, the state some finished commit left behind, so two records read in one query agree with each other. A read holds no key and changes nothing.

Inside one operation, a read after a commit sees that commit. The engineer's code wraps each operation in the API layer's `scope`, and a read inside it goes to the store for its records, so it also sees anything the database itself changed, such as a value a trigger computed. [Binding API, "What a read remembers"](Binding-API#what-a-read-remembers) says exactly how long a read keeps the records it has fetched.

## What the engineer writes

Nothing beyond calling the system. The generated commit functions and the API layer can be called from any thread at once. Each call runs on its own thread with its own working set, and the store underneath takes the turns: the in-memory store holds one lock per key, the PostgreSQL store takes one advisory lock per key inside the commit's transaction, and SQLite lets one writer through at a time. With PostgreSQL the locks live in the database, so two copies of the application over one database take turns too. The store's side of this is [Store API, "The unit of work"](Store-API#the-unit-of-work).

## What the compiler checks

- The compiler warns when a rule's or prohibition's read ties to no record, so the commit would queue on a whole shape, and lists the three answers above ([A5](checks#a5--contention-width)).
- The compiler warns when `tolerates contention` is written on a declaration whose reads cause no such wait ([A5](checks#a5--contention-width)).

## Related pages

- [`evaluation.md`, "The universal transaction"](evaluation#the-universal-transaction), the five guarantees a store delivers and what each protects.
- [`diagrams.md`](diagrams), the contention map the compiler emits beside the other diagrams.
- [Store API](Store-API), the unit of work and the locks each store takes.
- [The API layer](API-layer), `scope` and how one request runs end to end.
- [Binding API](Binding-API), the generated commit function and the queue keys in its documentation comment.
- [§17 Schedule triggers](Reference-17-Schedule-triggers), the rules a tick runs.
- [§21 `never`](Reference-21-never), the prohibitions a commit must hold.
