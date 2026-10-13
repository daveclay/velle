package velle.generated.shipping

import velle.generated.ShippingSystem
import velle.generated.*

/**
 * The human-owned scenarios the generated shipping specs demand (testgen.md).
 * The spec's one prohibition has a non-comparison predicate, which the spec
 * generator does not yet synthesise cases for (`SPEC_INDEX-Shipping.md`,
 * "Not yet generated"), so nothing is required here today. The rejections are
 * exercised by hand in `ShippingRejectionsTest.kt`.
 */
class Givens(private val sys: ShippingSystem) : RequiredGivens
