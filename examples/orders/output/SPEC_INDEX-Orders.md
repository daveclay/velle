# Spec Index — Orders

Generated from the Velle spec (testgen.md). One file per business state;
each case below is an executable test.

## OrderSpec.kt

- TakePayment - a new Order produces a Charge

## OrderLineSpec.kt

- ReserveStock - a new OrderLine produces a Reservation

## PriceRequestSpec.kt

- QuoteRequest - a new PriceRequest produces a Quote and a QuoteLine

## CatalogueUpdateSpec.kt

- RecordCatalogue - a new CatalogueUpdate produces a CatalogueLine

## PriceChangeSpec.kt

- RecordPriceChange - a new PriceChange produces a PriceRecord

## Not yet generated

- never #1: complex comparison subject
- never #2: shape is not an act
- never #3: complex comparison subject
- never #4: complex comparison subject
