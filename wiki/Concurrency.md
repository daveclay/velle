# Concurrency

A Velle system handles many commits and reads at the same time, and the author writes no locks for it. The compiler reads each act's rules and prohibitions and works out which records and which values the commit is about. Two commits about the same record or the same value take turns. Every other pair runs together. After this page you can say which commits in your spec wait for which, read the queue keys the compiler reports, answer the warning it gives when a commit would hold up everything on a shape, and know what a read sees while commits are running.

## The situation

A studio with one room sells it by the hour over the counter. Two walk-ins can ask for the same slot in the same second, and only one may hold it. A walk-in asking for a different slot should not wait for either of them. Someone who wanted a taken slot leaves a name, and is told once the slot comes free. The hourly sweep that tells them runs during the busy hour, and the busy hour must not stop for it. The waitlist holds twenty names at most.

## The code

From `examples/bookings/bookings.velle`:

```
transient act BookNow {
    slot: DateTime
    bookedFor: text
}

shape Booking {
    slot: DateTime
    bookedFor: text
    bookedOn: timestamp on create
}

shape Cancellation {
    booking: one Booking
    cancelledOn: timestamp on create
}

shape StandingBooking = Booking where not exists (Cancellation where booking == this)

never (StandingBooking where exists (StandingBooking as other where other.slot == this.slot and not (other == this)))

rule Book when BookNow {
    Booking from { slot: slot, bookedFor: bookedFor }
}

transient act Cancel {
    booking: one Booking
}

never (Booking where count(cancellations) > 1)

rule ApplyCancel when Cancel {
    Cancellation from { booking: booking }
}

act Waitlist {
    slot: DateTime
    name: text
}

never (Waitlist where count(Waitlist) > 20) tolerates contention

shape Unserved = Waitlist where not exists (StandingBooking where slot == this.slot)
shape Untold = Unserved where not exists (Notice where entry == this)

shape Notice {
    entry: one Waitlist
    sentOn: timestamp on create
}

rule TellWaitlist when Untold on Hourly {
    Notice from { entry: this }
}
```

## What each line does

- `BookNow` is a [transient act](Reference-04-State-and-commits#transient-acts-transient-act): a request decided at its own commit and not kept. `Book` is the [rule](Reference-11-rule-when-on) that turns it into a `Booking`.
- A `Cancellation` is a record beside the booking it gives back. `StandingBooking` is a [refinement](Reference-08-Refinements): a booking with no cancellation.
- The first `never` is a [prohibition](Reference-21-never): no two standing bookings hold one slot. The commit that would create the second is refused.
- `Cancel` and `ApplyCancel` give a booking back by writing its cancellation. The second `never` says a booking is given back once at most.
- `Waitlist` is an act that stays on record. The `never` over it caps the list at twenty names, and [`tolerates contention`](Reference-19-Self-referential-folds-and-tolerates#a-read-over-a-whole-shape-tolerates-contention) says the author knows what the cap costs, which the next sections explain.
- `Unserved` is an entry whose slot no standing booking holds. `Untold` is one not yet told. `TellWaitlist` runs on the hourly [schedule](Reference-17-Schedule-triggers) and writes a `Notice` for each.

## What waits and what runs together

Before a commit does any work, Velle names the records and values the commit is about. Those names are its queue keys. Two commits with a key in common run one after the other, in the order they arrived. Two commits with no key in common run at the same time. The keys come from the spec alone: no author writes one.

For this spec the compiler reports the keys in `examples/bookings/output/DIAGRAMS-Bookings.md` under "Contention map". With the values a day at the counter gives them, as `examples/bookings/output/src/test/kotlin/velle/BookingsConcurrencyTest.kt` records them:

| commit | queues on | which is |
|---|---|---|
| `BookNow` for ten o'clock | `Booking.slot=2026-01-05T10:00:00Z` | the slot the request carries |
| `Cancel` of booking 1 | `Booking#1`, `Booking.slot=2026-01-05T10:00:00Z` | the booking given back, and its slot |
| `Waitlist` | `Waitlist` | every waitlist entry there is |
| `TellWaitlist`, one hourly firing | `Waitlist#2`, `Booking.slot=2026-01-05T11:00:00Z` | the entry being told, and the slot it waits for |
| the hourly tick's own record | `Tick` | the row that says the tick ran ([§17, "What Velle records about a tick"](Reference-17-Schedule-triggers#what-velle-records-about-a-tick)) |

So two requests for ten o'clock take turns, and the second finds the first's booking and is refused. A request for eleven o'clock runs beside either of them. A cancellation waits only for work on that booking or that slot. The hourly firing for one entry waits only for a booking of the slot that entry wants.

Each key is worked out per call, from the request itself and from records read before the commit's work begins. The booking request carries its slot. The cancellation names its booking, and the slot is one step from the booking. Once a commit has its turn, everything it reads is the state the previous turn left behind. That is how the second of two requests for one slot sees the first.

A key can name a value rather than a record, as `Booking.slot=...` does. Velle reads the value from where the spec gets it: the act's own field, as `BookNow` carries the slot; a field of a record one of the act's references reaches, as `Cancel` reaches the slot through its booking; or the record a scheduled rule is running for, as the firing reads the entry's slot. When a rule computes the value instead, Velle cannot know it before the rule runs. The commit then queues on the whole shape, and every commit touching that shape takes turns. That is correct and slow. Carrying the value on the act is the way to narrow it.

## When one commit holds up a whole shape

A rule or prohibition sometimes reads records with nothing to tie them to the commit. The waitlist cap is one: `count(Waitlist)` reads every entry, and no entry in particular. The compiler cannot name a record for such a read, so the commit queues on the whole shape. Every `Waitlist` commit waits for every other `Waitlist` commit, which is the row marked `Waitlist` in the table above. Bookings and cancellations are not held up, since they touch no waitlist entry.

The compiler warns when this happens, naming the read and the rule or `never` carrying it. There are three answers, and each is a line in the spec:

1. Give the read a path back to the act, such as counting the entries for one slot, `count(Waitlist where slot == this.slot)`, instead of every entry. This is usually a relationship the model was missing.
2. Move the rule to a schedule. The read then runs once per tick, in its own commit, instead of inside every act.
3. Write `tolerates contention` on the rule or `never`, which says the wait is intended. The bookings spec does this, because the cap is over the whole list on purpose, and the contention map then shows the width as accepted.

The worked spelling of each answer is in [`patterns.md`, "A wide domain: the two authors"](patterns#a-wide-domain-the-two-authors).

## Scheduled rules

A tick does not stop the system. Each record a scheduled rule fires for is handled in a commit of its own, with its own keys, worked out from that record. The hourly `TellWaitlist` firing for one entry queues on that entry and on the slot it waits for. A booking of another slot arriving during the sweep does not wait, and a booking of that very slot takes its turn against this one firing. The rows for scheduled rules in the contention map read `this`, meaning the record the firing is for.

Once a firing has its turn, it checks the rule's condition again. Two copies of the application that tick at the same moment therefore write one `Notice` per entry, not two: the second to take an entry's turn sees the first one's notice and does nothing. That holds because the rule's own result takes the entry out of `Untold`, which is how a scheduled rule is written ([§18](Reference-18-Run-once-guards)).

## Reads

A read never waits for a commit and never makes a commit wait. It answers from one consistent state, the state some finished commit left behind, so two records read in one query agree with each other. A read holds no key and changes nothing.

Inside one operation, a read after a commit sees that commit. The engineer's code wraps each operation in the API layer's `scope`, and a read inside it goes to the store for its records, so it also sees anything the database itself changed, such as a value a trigger computed. [Binding API, "What a read remembers"](Binding-API#what-a-read-remembers) says exactly how long a read keeps the records it has fetched.

## What the engineer writes

Nothing beyond calling the system. The generated commit functions and the API layer can be called from any thread at once. `BookingsConcurrencyTest` does exactly that: eight threads ask for one slot and one booking stands; eight threads ask for eight slots and all eight stand. Each call runs on its own thread with its own working set, and the store underneath takes the turns: the in-memory store holds one lock per key, the PostgreSQL store takes one advisory lock per key inside the commit's transaction, and SQLite lets one writer through at a time. With PostgreSQL the locks live in the database, so two copies of the application over one database take turns too. The store's side of this is [Store API, "The unit of work"](Store-API#the-unit-of-work).

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
