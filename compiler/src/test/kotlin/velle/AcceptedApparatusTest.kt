package velle

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Two spellings the compiler accepts with no message: a guard and a backup schedule on a rule
 * that fires in the commit (Reference §18), and a `tolerates` on a field whose fold meets no
 * such hazard (Reference §19).
 */
class AcceptedApparatusTest {

    @Test
    fun `a guard and a backup schedule on a rule that fires in the commit validate`() {
        val spec = """
            act Receipt {
                sentOn: DateTime
            }

            shape ReceiptEmail {
                receipt: one Receipt
                queuedOn: DateTime
            }

            shape UnemailedReceipt = Receipt where not exists ReceiptEmail for this

            rule EmailReceipt when UnemailedReceipt on commit, Hourly {
                ReceiptEmail from { receipt: this, queuedOn: now }
            }
        """.trimIndent()

        assertEquals(emptyList(), Validator.validate(spec))
    }

    @Test
    fun `tolerates reordering on a field folded only in the commit validates`() {
        val spec = """
            act Account {
                balance: decimal tolerates reordering
            }

            act Deposit {
                account: one Account
                amount: decimal
            }

            rule ApplyDeposit when Deposit {
                account.balance = account.balance + amount
            }
        """.trimIndent()

        assertEquals(emptyList(), Validator.validate(spec))
    }
}
