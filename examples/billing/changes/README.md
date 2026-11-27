# A history of changes to the billing spec

This folder holds seven versions of the billing spec, each one a whole spec that validates. Each version makes one change a billing team might make between releases. For each change, `velle.CompareKt` wrote a report of what it does to the business, and the report is checked in beside the two versions it compares. Read the reports in order to see the spec change over time, or read `00-to-06.md` for everything at once.

Version 00 is `examples/billing/billing.velle` as it stood on 2026-11-24. The history is frozen, so a later edit to `billing.velle` does not change it. Each version opens with a comment saying what it changed.

## The versions and their reports

| Version | What changed | Report |
|---|---|---|
| `00-billing.velle` | The billing spec as it stood. | |
| `01-tidy.velle` | The customer section moved to the end of the file. `price * quantity` became `quantity * price`, and `balance > 0 and due < today` became `due < today and balance > 0`. | [01-tidy.md](01-tidy.md) |
| `02-discounts.velle` | `never (LineItem where price < 0)` removed, so a discount is a line item with a negative price. | [02-discounts.md](02-discounts.md) |
| `03-remind-issued.velle` | `ActionableOverdue` also requires `IssuedInvoice`, and `RemindOverdue` waits 14 days between reminders in place of 7. | [03-remind-issued.md](03-remind-issued.md) |
| `04-nudge.velle` | The shape `Reminder` renamed to `Nudge`. | [04-nudge.md](04-nudge.md) |
| `05-zero-total-receipts.velle` | `PaidInvoice` no longer requires `total > 0`. | [05-zero-total-receipts.md](05-zero-total-receipts.md) |
| `06-payments-positive.velle` | `never (Payment where amount <= 0)` added, a constraint over the kept shape `Payment`. | [06-payments-positive.md](06-payments-positive.md) |
| 00 to 06 | All six changes at once. | [00-to-06.md](00-to-06.md) |

## What was run

From the root of the repository:

```
./gradlew :examples:billing:output:compareChanges
```

The task runs `velle.CompareKt` once per change, from this folder, on the billing output module's classpath. That classpath holds the compiler and `extensions/finance`, the library that defines `Money`. For `02-discounts.md` the run is the same as:

```
cd examples/billing/changes
java -cp <the billing output module's runtime classpath> velle.CompareKt 01-tidy.velle 02-discounts.velle > 02-discounts.md
```

The task is registered in `examples/billing/output/build.gradle.kts`. `ChangeHistoryTest` in the same module checks that every version validates and that each report is exactly what the command prints today. When the comparison's wording changes, that test fails until the task is run again and the new reports are committed.

## What each report shows

- **01, tidying.** Moving the customer section and swapping the sides of `and` give no line at all. Swapping `price * quantity` is reported as meaning the same, with the condition that `velle.finance` is right that the sides of its `decimal * Money` may be swapped. `quantity` is an `integer` and is read as a `decimal`, so `decimal * Money` is the line of `Money` the multiplication uses. A reviewer who does not trust that library has the one claim to check.
- **02, discounts.** Removing the refusal is one edit. The report then names what nobody edited but the edit changes: a new line item can now lower an invoice's balance and so take it out of `OverdueInvoice`. It also says the compiler no longer writes the two example tests that checked the refusal.
- **03, reminders for issued invoices.** The report says what `ActionableOverdue` is now and what it was. It says an `Issuance` can now cause `RemindOverdue` to run. For a record, it says an overdue invoice that was never issued leaves `ActionableOverdue` by this change alone, and is no longer reminded at the next Weekly tick.
- **04, the rename.** One line, with the warning that records already stored as `Reminder` are not carried over. Nothing else about the shape changed, so nothing else is reported.
- **05, receipts for zero totals.** This is the change most likely to surprise a product owner. Opening an invoice can now cause `SendReceipt` to run, because an invoice with no line items has a balance of 0 and is paid when it is opened. Read together with 02, an invoice discounted to nothing earns a receipt.
- **06, positive payments.** The report says what the constraint forbids. It says that since every payment is now positive, recording one can no longer take an invoice out of `PaidInvoice` or into `OverdueInvoice`. For a record, it says a payment stored before the change could break the constraint, and that while one does, every new payment is refused.
- **00 to 06.** The six changes, reported as the difference between the first spec and the last. The rename is carried back into the older spec before the two are compared, so the line about `RemindOverdue` says `Nudge` in both its now and its before. The tidying in 01 still reads as means-the-same, because the line items are written the same way in 06 as in 01.

## What the reports get wrong

- In `00-to-06.md`, the line "OpenInvoice can now cause SendReceipt to run" points at all seven edits. The edit that causes it is the change to `PaidInvoice`. A consequence points at the edits whose names it mentions, and this one names `OpenInvoice` and `SendReceipt`, which no edit names, so it falls back to every edit. In the same report, "A new LineItem can now make an Invoice stop being an OverdueInvoice" also points at the `LineItem` rewording, which changed nothing. OQ172 holds this.
