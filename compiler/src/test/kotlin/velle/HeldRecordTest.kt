package velle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A required reference holds the record it points at, and `x is held` is the test for it
 * (Reference §6, "A required reference holds its target"; Reference §10; Reference §12,
 * "Deletion"; checks.md V24). The validator half pins which spellings let a rule delete a
 * held shape's record. The runtime half pins what each request gets.
 */
class HeldRecordTest {

    private val shapes = """
        act Seller { name: text }
        act Listing { title: text, seller: one Seller }
        transient act CloseAccount { seller: one Seller }
        transient act Withdraw { listing: one Listing }
        rule ApplyWithdraw when Withdraw { delete listing }
    """.trimIndent()

    private val refusedAtTheDoor = """
        $shapes
        never (CloseAccount where seller is held)
        rule ApplyClose when CloseAccount { delete seller }
    """.trimIndent()

    private val refusalRecorded = """
        $shapes
        shape CloseRefusal { sellerName: text }
        rule ApplyClose when (CloseAccount where seller is not held) { delete seller }
        rule RefuseClose when (CloseAccount where seller is held) { CloseRefusal from { sellerName: seller.name } }
    """.trimIndent()

    private val heldRefinement = """
        $shapes
        shape HeldSeller = Seller where this is held
        never (CloseAccount where seller is HeldSeller)
        rule ApplyClose when (CloseAccount where seller is not HeldSeller) { delete seller }
    """.trimIndent()

    private val handover = """
        act Employee { name: text }
        act Shop { name: text, manager: one Employee }
        transient act ReplaceManager { shop: one Shop, departing: one Employee, successor: one Employee }
        never (ReplaceManager where departing is held)
        rule ApplyReplace when ReplaceManager {
            shop.manager = successor
            delete departing
        }
    """.trimIndent()

    private fun diagnostics(source: String): List<Diagnostic> = Validator.validate(source)

    private fun assertValid(source: String) = assertEquals(emptyList(), diagnostics(source))

    private fun assertRefusedBy(code: String, fragment: String, source: String) {
        val found = diagnostics(source)
        assertTrue(found.any { diagnostic -> diagnostic.code == code && fragment in diagnostic.message }, "expected a $code naming '$fragment', got: $found")
    }

    private fun systemOf(source: String): VelleSystem {
        assertValid(source)
        return VelleSystem(Model(Parser.parse(source)))
    }

    private fun accepted(result: CommitResult): Long {
        assertIs<CommitResult.Accepted>(result, "commit refused: $result")
        return result.id
    }

    private fun refused(result: CommitResult): CommitResult.Refused {
        assertIs<CommitResult.Refused>(result, "commit accepted: $result")
        return result
    }

    // ── which spellings let a rule delete a held shape's record (V24) ───────

    @Test
    fun `a never over the request that refuses a held record lets the rule delete it`() = assertValid(refusedAtTheDoor)

    @Test
    fun `a rule conditioned on the record not being held may delete it`() = assertValid(refusalRecorded)

    @Test
    fun `a rule conditioned on the record being outside a refinement of held records may delete it`() = assertValid(heldRefinement)

    @Test
    fun `a rule that deletes a record a required reference can point at and says nothing of held records is refused`() {
        assertRefusedBy("V24", "seller is held", """
            $shapes
            rule ApplyClose when CloseAccount { delete seller }
        """.trimIndent())
    }

    @Test
    fun `the test must be written on the record the rule deletes`() {
        assertRefusedBy("V24", "Listing.seller", """
            $shapes
            transient act Merge { kept: one Seller, dropped: one Seller }
            never (Merge where kept is held)
            rule ApplyMerge when Merge { delete dropped }
            never (CloseAccount where seller is held)
            rule ApplyClose when CloseAccount { delete seller }
        """.trimIndent())
    }

    @Test
    fun `a hand-written exists over the pointing shape does not let the rule delete`() {
        assertRefusedBy("V24", "seller is held", """
            $shapes
            never (CloseAccount where exists (Listing where seller == this.seller))
            rule ApplyClose when CloseAccount { delete seller }
        """.trimIndent())
    }

    @Test
    fun `is held on a value that is not a record is refused`() {
        assertRefusedBy("F2", "is held", """
            $shapes
            never (CloseAccount where seller.name is held)
            rule ApplyClose when CloseAccount { delete seller }
        """.trimIndent())
    }

    @Test
    fun `a rule that deletes on a condition may not also point a required reference at that shape`() {
        assertRefusedBy("V24", "never (ReplaceManager where departing is held)", """
            act Employee { name: text }
            act Shop { name: text, manager: one Employee }
            transient act ReplaceManager { shop: one Shop, departing: one Employee, successor: one Employee }
            shape ReplaceRefusal { shopName: text }
            rule ApplyReplace when (ReplaceManager where departing is not held) {
                shop.manager = successor
                delete departing
            }
            rule RefuseReplace when (ReplaceManager where departing is held) { ReplaceRefusal from { shopName: shop.name } }
        """.trimIndent())
    }

    @Test
    fun `a rule that hands the references over and deletes, behind a never, is accepted`() = assertValid(handover)

    @Test
    fun `a requirement that the record is not held lets the rule delete it`() {
        assertValid("""
            $shapes
            CloseAccount requires seller is not held
            rule ApplyClose when CloseAccount { delete seller }
        """.trimIndent())
    }

    @Test
    fun `a scheduled rule deletes the records nothing holds`() {
        assertValid("""
            act Seller { name: text, closing: boolean initially false }
            act Listing { title: text, seller: one Seller }
            rule SweepClosing when (Seller where closing and this is not held) on Daily { delete this }
        """.trimIndent())
    }

    @Test
    fun `a rule that deletes the pointing record with the record it points at needs no test`() {
        assertValid("""
            act Seller { name: text }
            act Profile { bio: text, seller: one Seller }
            transient act CloseAccount { seller: one Seller, profile: one Profile }
            rule ApplyClose when CloseAccount {
                delete profile
                delete seller
            }
        """.trimIndent())
    }

    @Test
    fun `a required field typed with a union holds a record of each operand`() {
        assertRefusedBy("V24", "Flag.subject", """
            act Post { body: text }
            act Comment { body: text }
            shape Content = Post or Comment
            act Flag { reason: text, subject: one Content }
            transient act RemovePost { post: one Post }
            rule ApplyRemove when RemovePost { delete post }
        """.trimIndent())
    }

    @Test
    fun `a second rule that reads the record the first deletes is refused for its unstated order`() {
        assertRefusedBy("V15", "LeaveFarewell", """
            $shapes
            never (CloseAccount where seller is held)
            rule ApplyClose when CloseAccount { delete seller }
            rule LeaveFarewell when CloseAccount { Listing from { title: "Closed", seller: seller } }
        """.trimIndent())
    }

    // ── what each request gets ───────────────────────────────────────────────

    @Test
    fun `closing a seller a listing points at is refused naming the listing, and commits once the listing is gone`() {
        val sys = systemOf(refusedAtTheDoor)
        sys.scope {
            val seller = accepted(sys.commit("Seller", mapOf("name" to "Acme")))
            val listing = accepted(sys.commit("Listing", mapOf("title" to "Lamp", "seller" to seller)))

            val refusal = refused(sys.commit("CloseAccount", mapOf("seller" to seller)))
            assertTrue("Listing" in refusal.reason && "seller" in refusal.reason, "the refusal names what holds the seller: ${refusal.reason}")
            assertEquals(listOf(seller), sys.instancesOf("Seller"))

            accepted(sys.commit("Withdraw", mapOf("listing" to listing)))
            accepted(sys.commit("CloseAccount", mapOf("seller" to seller)))
            assertEquals(emptyList(), sys.instancesOf("Seller"))
        }
    }

    @Test
    fun `a rule conditioned on not held deletes a free seller and records the refusal of a held one`() {
        val sys = systemOf(refusalRecorded)
        sys.scope {
            val held = accepted(sys.commit("Seller", mapOf("name" to "Acme")))
            val free = accepted(sys.commit("Seller", mapOf("name" to "Zed")))
            accepted(sys.commit("Listing", mapOf("title" to "Lamp", "seller" to held)))

            accepted(sys.commit("CloseAccount", mapOf("seller" to held)))
            accepted(sys.commit("CloseAccount", mapOf("seller" to free)))

            assertEquals(listOf(held), sys.instancesOf("Seller"))
            assertEquals("Acme", sys.get(sys.instancesOf("CloseRefusal").single(), "sellerName"))
        }
    }

    @Test
    fun `a seller is in the refinement of held records exactly while a listing points at it`() {
        val sys = systemOf(heldRefinement)
        sys.scope {
            val seller = accepted(sys.commit("Seller", mapOf("name" to "Acme")))
            assertEquals(emptyList(), sys.instancesOf("HeldSeller"))
            val listing = accepted(sys.commit("Listing", mapOf("title" to "Lamp", "seller" to seller)))
            assertEquals(listOf(seller), sys.instancesOf("HeldSeller"))
            refused(sys.commit("CloseAccount", mapOf("seller" to seller)))
            accepted(sys.commit("Withdraw", mapOf("listing" to listing)))
            assertEquals(emptyList(), sys.instancesOf("HeldSeller"))
            accepted(sys.commit("CloseAccount", mapOf("seller" to seller)))
        }
    }

    @Test
    fun `a handover to another employee commits, with the shop pointing at the successor`() {
        val sys = systemOf(handover)
        sys.scope {
            val ann = accepted(sys.commit("Employee", mapOf("name" to "Ann")))
            val bo = accepted(sys.commit("Employee", mapOf("name" to "Bo")))
            val shop = accepted(sys.commit("Shop", mapOf("name" to "North", "manager" to ann)))

            accepted(sys.commit("ReplaceManager", mapOf("shop" to shop, "departing" to ann, "successor" to bo)))

            assertEquals(listOf(bo), sys.instancesOf("Employee"))
            assertEquals(bo, sys.get(shop, "manager"))
        }
    }

    @Test
    fun `a handover naming one employee as both is refused, and nothing changes`() {
        val sys = systemOf(handover)
        sys.scope {
            val ann = accepted(sys.commit("Employee", mapOf("name" to "Ann")))
            val shop = accepted(sys.commit("Shop", mapOf("name" to "North", "manager" to ann)))

            val refusal = refused(sys.commit("ReplaceManager", mapOf("shop" to shop, "departing" to ann, "successor" to ann)))

            assertTrue("Shop" in refusal.reason && "manager" in refusal.reason, "the refusal names what holds the employee: ${refusal.reason}")
            assertEquals(listOf(ann), sys.instancesOf("Employee"))
            assertEquals(ann, sys.get(shop, "manager"))
        }
    }

    @Test
    fun `a handover is refused while a second shop still points at the departing employee`() {
        val sys = systemOf(handover)
        sys.scope {
            val ann = accepted(sys.commit("Employee", mapOf("name" to "Ann")))
            val bo = accepted(sys.commit("Employee", mapOf("name" to "Bo")))
            val north = accepted(sys.commit("Shop", mapOf("name" to "North", "manager" to ann)))
            val south = accepted(sys.commit("Shop", mapOf("name" to "South", "manager" to ann)))

            val refusal = refused(sys.commit("ReplaceManager", mapOf("shop" to north, "departing" to ann, "successor" to bo)))

            assertTrue("Shop" in refusal.reason, "the refusal names what holds the employee: ${refusal.reason}")
            assertEquals(ann, sys.get(north, "manager"))
            assertEquals(ann, sys.get(south, "manager"))
            assertEquals(listOf(ann, bo), sys.instancesOf("Employee"))
        }
    }

    @Test
    fun `a post a flag points at through a union field is held`() {
        val sys = systemOf("""
            act Post { body: text }
            act Comment { body: text }
            shape Content = Post or Comment
            act Flag { reason: text, subject: one Content }
            transient act RemovePost { post: one Post }
            never (RemovePost where post is held)
            rule ApplyRemove when RemovePost { delete post }
        """.trimIndent())
        sys.scope {
            val flagged = accepted(sys.commit("Post", mapOf("body" to "Spam")))
            val plain = accepted(sys.commit("Post", mapOf("body" to "Hello")))
            accepted(sys.commit("Flag", mapOf("reason" to "spam", "subject" to flagged)))

            refused(sys.commit("RemovePost", mapOf("post" to flagged)))
            accepted(sys.commit("RemovePost", mapOf("post" to plain)))

            assertEquals(listOf(flagged), sys.instancesOf("Post"))
        }
    }

    @Test
    fun `a reference that goes absent does not hold the record it points at`() {
        val sys = systemOf("""
            act Seller { name: text }
            act Review { body: text, seller: one Seller? initially required }
            transient act CloseAccount { seller: one Seller }
            never (CloseAccount where seller is held)
            rule ApplyClose when CloseAccount { delete seller }
        """.trimIndent())
        sys.scope {
            val seller = accepted(sys.commit("Seller", mapOf("name" to "Acme")))
            val review = accepted(sys.commit("Review", mapOf("body" to "Fine", "seller" to seller)))

            accepted(sys.commit("CloseAccount", mapOf("seller" to seller)))

            assertEquals(emptyList(), sys.instancesOf("Seller"))
            assertEquals(null, sys.get(review, "seller"))
        }
    }

    @Test
    fun `a rule that points a new record at the record it deletes is refused on every request`() {
        val sys = systemOf("""
            act Seller { name: text }
            act Listing { title: text, seller: one Seller }
            transient act CloseAccount { seller: one Seller }
            never (CloseAccount where seller is held)
            rule ApplyClose when CloseAccount {
                Listing from { title: "Closed", seller: seller }
                delete seller
            }
        """.trimIndent())
        sys.scope {
            val seller = accepted(sys.commit("Seller", mapOf("name" to "Acme")))

            refused(sys.commit("CloseAccount", mapOf("seller" to seller)))

            assertEquals(listOf(seller), sys.instancesOf("Seller"))
            assertEquals(emptyList(), sys.instancesOf("Listing"))
        }
    }
}
