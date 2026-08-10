# Spec Index — Payments

Generated from the Velle spec (testgen.md). One file per business state;
each case below is an executable test.

## CardUpdateSpec.kt

- ApplyCardUpdate - a new CardUpdate sets customer.card

## OrderSpec.kt

- ReserveStock - a new Order produces a StockReservation
- never - a Order where amount at most 0 is refused
- never - a Order with amount 1 is accepted

## ChargeableOrderSpec.kt

- RequestInitialCharge - a new ChargeableOrder produces a ChargeAttempt
- TimeOutStaleAttempt - at the QuarterHourly tick, a StalePendingAttempt produces an AttemptTimeout
- SendReceipt - a new SuccessfulCharge produces a Receipt

## RetryableOrderSpec.kt

- RetryCharge - a new RetryableOrder produces a ChargeAttempt

## ExhaustedOrderSpec.kt

- ReleaseStockOnExhaustion - a new ExhaustedOrder produces a ReservationRelease

## SettledOrderSpec.kt

- ShipOrder - at the Nightly tick, a ReadyToShip produces a Shipment
- ApplyAddressChange - a new ApplicableAddressChange produces an AddressChangeApplication and sets order.shippingAddress
- RecordAddressRefusal - a new RefusedAddressChange produces an AddressChangeRefusal
- NoteSettlementReversal - an order that leaves SettledOrder produces a SettlementReversal

## ManualChargeSpec.kt

- ApplyManualCharge - a new ManualCharge produces a ChargeAttempt

## ExtensionRequestSpec.kt

- GrantGracePeriod - a new ExtensionRequest produces a PaymentExtension

## UnpaidOrderSpec.kt

- RemindPayment - at the Daily tick, an OverdueOrder produces a PaymentReminder
- OpenDunningEpisode - a new OverdueOrder produces a DunningFlag
- CloseDunningEpisode - at the Daily tick, a ClosableDunningFlag produces a DunningResolution

## ChargeResponseSpec.kt


## RefundSpec.kt

- never - a Refund where amount at most 0 is refused
- never - a Refund with amount 1 is accepted

## Not yet generated

- never #2: multi-conjunct predicate
