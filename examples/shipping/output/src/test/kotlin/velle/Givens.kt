package velle.generated.shipping

import java.time.LocalDate
import velle.generated.ShippingSystem

/**
 * The human-owned scenarios the generated shipping specs demand (testgen.md).
 * The spec's prohibition has a non-comparison predicate, which the spec
 * generator does not yet synthesise cases for (`SPEC_INDEX-Shipping.md`,
 * "Not yet generated"); its rejections are exercised by hand in
 * `ShippingRejectionsTest.kt`. The one given here feeds the nightly rule.
 */
class Givens(private val sys: ShippingSystem) : RequiredGivens {

    /** An order placed well over a week before the clock's start, never paid. */
    override fun orderForCancelUnpaid(): ShippingSystem.OrderView {
        sys.commitOrder("4470", LocalDate.of(2025, 12, 1))
        return sys.orders().last()
    }
}
