# Spec Index — Payments

Generated from the Velle spec (testgen.md). One file per business state;
each case below is an executable test.

## CardUpdateSpec.kt

- ApplyCardUpdate - a new CardUpdate sets customer.card

## PlaceOrderSpec.kt

- AcceptOrder - a new PlaceOrder produces an Order
- RequestInitialCharge - a new ChargeableOrder produces a ChargeAttempt
- RetryCharge - a new RetryableOrder produces a ChargeAttempt
- TimeOutStaleAttempt - at the QuarterHourly tick, a StalePendingAttempt produces an AttemptTimeout
- ReleaseStockOnExhaustion - a new ExhaustedOrder produces a ReservationRelease after the commit
- SendReceipt - a new SuccessfulCharge produces a Receipt
- ShipOrder - at the Nightly tick, a ReadyToShip produces a Shipment
- ApplyAddressChange - a new ApplicableAddressChange produces an AddressChangeApplication and sets order.shippingAddress
- RecordAddressRefusal - a new RefusedAddressChange produces an AddressChangeRefusal
- NoteSettlementReversal - an order that leaves SettledOrder produces a SettlementReversal
- RemindPayment - at the Daily tick, an OverdueOrder produces a PaymentReminder
- OpenDunningEpisode - a new OverdueOrder produces a DunningFlag
- CloseDunningEpisode - at the Daily tick, a ClosableDunningFlag produces a DunningResolution
- never - a PlaceOrder where amount at most 0 is refused
- never - a PlaceOrder with amount 1 is accepted

## ProcessorVerdictSpec.kt

- RecordVerdict - a new ProcessorVerdict produces a ChargeResponse

## OrderSpec.kt

- ReserveStock - a new Order produces a StockReservation

## IssueRefundSpec.kt

- RecordRefund - a new IssueRefund produces a Refund
- never - a IssueRefund where amount at most 0 is refused
- never - a IssueRefund with amount 1 is accepted

## ManualChargeSpec.kt

- ApplyManualCharge - a new ManualCharge produces a ChargeAttempt

## ExtensionRequestSpec.kt

- GrantGracePeriod - a new ExtensionRequest produces a PaymentExtension

## Not yet generated

- never #2: multi-conjunct predicate
