package velle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * The probes of the symmetric-evaluation audit (OQ42 item 1; the record is
 * `working-docs/audit-symmetric-evaluation.md`). Each test pins one construct
 * case's verdict: either the subject enumeration names the affected subject
 * (keys appear), or the walk falls to Unknown and widens (fail-closed), or
 * safety rests on the writer keying the row's correlatable references — and
 * the probe asserts the keys that argument requires actually derive.
 *
 * A probe failing is an audit finding: a construct where an envelope neither
 * keys nor widens is exactly the item-2 class of soundness bug.
 */
class SymmetricEvaluationAuditTest {

    private fun analysis(src: String): DomainAnalysis {
        val model = Model(Parser.parse(src))
        check(model.diagnostics.isEmpty()) { model.diagnostics.toString() }
        return DomainAnalysis(model)
    }

    private fun keys(d: SerializationDomain, noun: String) = d.renderKeys(noun).sorted()

    // ── case: same-base sibling through an uncorrelated scan → widen ─────────

    @Test
    fun `an uncorrelated same-base scan widens every commit of that shape`() {
        val a = analysis("""
            expose shape Stat {
                value: decimal
            }
            never (Stat where count(Stat) > 10)
        """.trimIndent())
        val d = a.actDomains.getValue("Stat")
        assertTrue(d.wide, "the whole-shape count must widen: ${d.paths}")
    }

    // ── case: cross-shape value correlation, forward field present ───────────
    // Both halves of the race key the committed value: the Order side through
    // the scan's ValCorr, the Customer side through the forward-correlated
    // subject evaluating the same watcher.

    private val valueCorr = """
        expose shape Customer {
            email: text
        }

        expose shape Order {
            customer: one Customer
            contactEmail: text
        }

        shape Flag {
            order: one Order
        }

        rule FlagSuspicious when (Order where exists (Customer where email == this.contactEmail)) {
            Flag from { order: this }
        }
    """.trimIndent()

    @Test
    fun `a cross-shape value correlation keys the value on both sides`() {
        // (Both sides also carry a widening today: the condition's summary
        // goes opaque — `this.contactEmail` inside the collection `where` is
        // resolved against the *element* scope, misses, and sets opaque — so
        // relevance admits the body's own Flag creation, which evaluates the
        // watcher at Unknown subjects. Fail-closed over-width; recorded in the
        // audit as precision finding P2, not a hole.)
        val a = analysis(valueCorr)
        val order = a.actDomains.getValue("Order")
        assertTrue(QueueKey.ValueOf("Customer", "email") in order.valueKeys, "order side: ${order.valueKeys}")
        val customer = a.actDomains.getValue("Customer")
        assertTrue(QueueKey.ValueOf("Customer", "email") in customer.valueKeys,
            "customer side must key the value it commits: ${customer.valueKeys}, wide=${customer.widenings}")
    }

    // ── case: cross-shape value correlation, no forward field → widen ────────

    @Test
    fun `a value-correlated watcher with no route back to the writer widens the writer`() {
        val a = analysis("""
            expose shape Customer {
                email: text
            }

            expose shape Order {
                contactEmail: text
            }

            shape Flag {
                order: one Order
            }

            rule FlagSuspicious when (Order where exists (Customer where email == this.contactEmail)) {
                Flag from { order: this }
            }
        """.trimIndent())
        val customer = a.actDomains.getValue("Customer")
        assertTrue(customer.wide || QueueKey.ValueOf("Customer", "email") in customer.valueKeys,
            "the customer commit must widen or key the value: ${customer.paths} ${customer.valueKeys} ${customer.widenings}")
    }

    // ── case: a two-hop reverse route → the writer keys one hop and widens ───
    // The mini-payments shape: the watcher's base is two hops from the touched
    // shape (result → attempt → order). The enumeration is single-hop, so the
    // subject falls to Unknown — the honest answer is width, plus the
    // correlatable one-hop key.

    @Test
    fun `a two-hop reverse route keys the middle row and widens for the rest`() {
        val a = analysis("""
            expose shape Order {
                total: decimal
            }

            expose shape Attempt {
                order: one Order
            }

            expose shape Result {
                attempt: one Attempt
            }

            shape GoodAttempt = Attempt where exists Result for this

            shape Receipt {
                order: one Order
            }

            rule NotePaid when (Order where exists (attempts where GoodAttempt)) {
                Receipt from { order: this }
            }
        """.trimIndent())
        val result = a.actDomains.getValue("Result")
        assertTrue(QueueKey.Path(listOf("attempt")) in result.paths, "got: ${result.paths}")
        assertTrue(result.wide, "the missed second hop must widen: ${result.widenings}")
    }

    // ── case: reference reassignment — old and new rows both keyed ───────────
    // `customer.card = card` affects watchers correlated to the OLD card (the
    // pre-state path key `cardUpdate.customer.card`) and to the NEW card (the
    // act's own `cardUpdate.card`). Mirrors the payments contract's derived
    // keys for ApplyCardUpdate.

    private fun cardSpec(transient: String) = """
        shape Card {
            label: text
        }
        expose Card

        expose shape Customer {
            card: one Card?
        }

        expose $transient shape CardUpdate {
            customer: one Customer
            card: one Card
        }

        rule Apply when CardUpdate {
            customer.card = card
        }

        shape LoadedCard = Card where exists (Customer where card == this)

        shape LoadNote {
            card: one Card
        }

        rule NoteLoad when LoadedCard {
            LoadNote from { card: this }
        }
    """.trimIndent().replace("expose  shape", "expose shape")

    @Test
    fun `a reassignment keys the old row through the pre-state path and the new row through the act`() {
        val a = analysis(cardSpec(""))
        val d = a.actDomains.getValue("CardUpdate")
        assertTrue(QueueKey.Path(listOf("card")) in d.paths, "new card: ${d.paths}")
        assertTrue(QueueKey.Path(listOf("customer", "card")) in d.paths, "old card: ${d.paths}")
        assertTrue(QueueKey.Path(listOf("customer")) in d.paths, "written row: ${d.paths}")
    }

    @Test
    fun `a transient act's reassignment still keys both rows`() {
        // a transient act infers no inverse collections, so the keys must come
        // from the walk itself: the body's read of `card`, the write to
        // `customer.card`, and the written row's correlatable references
        val a = analysis(cardSpec("transient"))
        val d = a.actDomains.getValue("CardUpdate")
        assertTrue(QueueKey.Path(listOf("card")) in d.paths, "new card: ${d.paths} ${d.widenings}")
        assertTrue(QueueKey.Path(listOf("customer", "card")) in d.paths, "old card: ${d.paths} ${d.widenings}")
    }

    // ── case: forward correlation — creates are precise, assigns key the row ─

    private val wallets = """
        expose shape Card {
            expiresOn: Date
        }

        expose shape Wallet {
            card: one Card
            owner: text
        }

        shape Alert {
            wallet: one Wallet
        }

        rule AlertExpiring when (Wallet where card.expiresOn < today and owner != "system") {
            Alert from { wallet: this }
        }

        expose shape Extend {
            card: one Card
        }

        rule ApplyExtend when Extend {
            card.expiresOn = card.expiresOn + 30 days
        }
    """.trimIndent()

    @Test
    fun `creating an unreferenced row affects no forward-correlated watcher - the domain is precisely empty`() {
        // a fresh Card can be referenced by no wallet yet, so no wallet's
        // membership can change; the derivation is exact here, not just sound
        val a = analysis(wallets)
        val card = a.actDomains.getValue("Card")
        assertEquals(emptyList(), keys(card, "card"), "widenings: ${card.widenings}")
        assertFalse(card.wide)
    }

    @Test
    fun `assigning a field of an existing row keys that row - both sides meet on it`() {
        // the Extend firing mutates card.expiresOn: every read the
        // forward-correlated Wallet evaluation makes (the back-link hop, even
        // the `owner` read outside it) collapses to the card row — and the
        // Wallet act keys the same row through its correlatable `card`
        val a = analysis(wallets)
        val extend = a.actDomains.getValue("Extend")
        assertEquals(listOf("extend.card"), keys(extend, "extend"), "widenings: ${extend.widenings}")
        assertFalse(extend.wide)
        val wallet = a.actDomains.getValue("Wallet")
        assertTrue(QueueKey.Path(listOf("card")) in wallet.paths, "got: ${wallet.paths}")
    }

    // ── case: the `is Refinement` atom — routes the condition collector skips ─
    // The condition's only consult of Transfer hides behind `account is
    // Drained`. The audit suspect: if neither the route collection nor the
    // relevance gate sees through the atom, a Transfer commit neither keys nor
    // widens — the item-2 class of hole.

    @Test
    fun `a correlated read hidden behind an is-refinement atom still keys or widens the writer`() {
        val a = analysis("""
            shape Account {
                balance: decimal initially 0
            }
            expose Account

            expose shape Transfer {
                source: one Account
                target: one Account
            }

            shape Drained = Account where exists (Transfer where source == this)

            expose shape Review {
                account: one Account
            }

            shape Cleared {
                review: one Review
            }

            rule Clear when (Review where not account is Drained) {
                Cleared from { review: this }
            }
        """.trimIndent())
        val d = a.actDomains.getValue("Transfer")
        assertTrue(QueueKey.Path(listOf("source")) in d.paths || d.wide,
            "a Transfer commit must key its source or widen — Clear's guard reads it: ${d.paths} ${d.widenings}")
    }

    // ── case: a sibling join over two inverse collections ────────────────────
    // Both collections are owner-correlated, so each side's act keys the
    // shared owner and racing pairs meet there.

    @Test
    fun `sibling-join collections key both acts at the shared owner`() {
        val a = analysis("""
            expose shape Member {
                name: text
            }

            expose shape Ticket {
                member: one Member
                subject: text
            }

            expose shape Charge {
                member: one Member
                reference: text
            }

            shape Disputed {
                member: one Member
            }

            rule NoteDispute
                when (Member where exists (tickets as t, charges as c where t.subject == c.reference)) {
                Disputed from { member: this }
            }
        """.trimIndent())
        assertEquals(listOf("ticket.member"), keys(a.actDomains.getValue("Ticket"), "ticket"))
        assertEquals(listOf("charge.member"), keys(a.actDomains.getValue("Charge"), "charge"))
    }
}
