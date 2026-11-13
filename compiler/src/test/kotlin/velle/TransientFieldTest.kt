package velle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A transient field of a kept act (Reference §4, "Transient fields of a kept act"):
 * `transient inviteCode: text` is in the act's input and not in its record. It is
 * read by what runs at the act's commit, a `never` over the act and a rule on the
 * bare act, and by nothing else (checks V37). The store never sees it, and the
 * generated model object has no such field.
 */
class TransientFieldTest {

    private val signUp = """
        act Invite {
            code: text unique
        }

        act SignUp {
            email: text
            transient inviteCode: text
        }

        shape InviteUse {
            invite: one Invite
            signUp: one SignUp
        }

        never (SignUp where not exists (Invite where code == this.inviteCode))

        rule UseInvite when SignUp {
            InviteUse from { invite: (Invite where code == this.inviteCode), signUp: this }
        }
    """.trimIndent()

    private val order = """
        act Customer { name: text }
        act Product { sku: text }

        act Order {
            customer: one Customer
            placedOn: Date
            transient cardNumber: text
            transient cardholder: text
        } with orderLines

        shape OrderLine {
            order: one Order
            product: one Product
            quantity: integer
        }

        shape ChargeIntent {
            order: one Order
            cardholder: text
        }

        rule Charge when Order {
            ChargeIntent from { order: this, cardholder: cardholder }
        }
    """.trimIndent()

    private fun system(spec: String): VelleSystem {
        val diagnostics = Validator.validate(spec)
        check(diagnostics.isEmpty()) { diagnostics.toString() }
        return VelleSystem(Model(Parser.parse(spec)))
    }

    private fun codes(spec: String) = Validator.validate(spec).map { it.code }

    private fun messages(spec: String) = Validator.validate(spec).joinToString("\n") { "[${it.code}] ${it.message}" }

    // ── the field form and the case with parts validate ──────────────────────

    @Test
    fun `a kept act with a transient field validates clean`() {
        assertEquals(emptyList(), Validator.validate(signUp))
    }

    @Test
    fun `a kept act with transient fields beside its parts validates clean`() {
        assertEquals(emptyList(), Validator.validate(order))
    }

    // ── V37: where a transient field may be read ─────────────────────────────

    @Test
    fun `V37 - transient is refused on a field of a shape`() {
        val spec = signUp.replace("shape InviteUse {\n    invite: one Invite", "shape InviteUse {\n    transient note: text\n    invite: one Invite")
        assertTrue("V37" in codes(spec), messages(spec))
    }

    @Test
    fun `V37 - a refinement over the act may not read the field`() {
        val spec = signUp + "\n\nshape Coded = SignUp where inviteCode != \"\"\n"
        assertTrue("V37" in codes(spec), messages(spec))
    }

    @Test
    fun `V37 - a derived property on the act may not read the field`() {
        val spec = signUp.replace("transient inviteCode: text", "transient inviteCode: text\n    codeLength: integer = length(inviteCode)")
        assertTrue("V37" in codes(spec), messages(spec))
    }

    @Test
    fun `V37 - a rule with an inline condition may not read the field`() {
        val spec = signUp.replace("rule UseInvite when SignUp {", "rule UseInvite when (SignUp where inviteCode != \"\") {")
        assertTrue("V37" in codes(spec), messages(spec))
    }

    @Test
    fun `V37 - another subject may not read the field through a reference`() {
        val spec = signUp + "\n\nshape Note { body: text }\nrule Echo when InviteUse { Note from { body: signUp.inviteCode } }\n"
        assertTrue("V37" in codes(spec), messages(spec))
    }

    @Test
    fun `V37 - a never over another subject may not read the field`() {
        val spec = signUp + "\n\nnever (InviteUse where signUp.inviteCode == \"\")\n"
        assertTrue("V37" in codes(spec), messages(spec))
    }

    @Test
    fun `V37 - the field cannot be assigned`() {
        val spec = signUp + "\n\nact Nudge { signUp: one SignUp }\nrule Clear when Nudge { signUp.inviteCode = \"\" }\n"
        assertTrue("V37" in codes(spec), messages(spec))
    }

    // ── the runtime: read at the commit, never stored ─────────────────────────

    @Test
    fun `the field is read at the commit and is not in the record`() {
        val sys = system(signUp)
        sys.scope {
            val invite = assertIs<CommitResult.Accepted>(sys.commit("Invite", mapOf("code" to "WELCOME"))).id
            val signedUp = assertIs<CommitResult.Accepted>(sys.commit("SignUp", mapOf("email" to "a@b.c", "inviteCode" to "WELCOME"))).id
            val uses = sys.instancesOf("InviteUse")
            assertEquals(1, uses.size)
            assertEquals(invite, sys.get(uses.single(), "invite"))
            assertEquals(signedUp, sys.get(uses.single(), "signUp"))
            assertEquals("a@b.c", sys.get(signedUp, "email"))
            assertNull(sys.get(signedUp, "inviteCode"))
        }
    }

    @Test
    fun `a never over the act reading the field refuses at the door`() {
        val sys = system(signUp)
        sys.scope {
            val result = sys.commit("SignUp", mapOf("email" to "a@b.c", "inviteCode" to "NOPE"))
            assertIs<CommitResult.Refused>(result, "a sign-up with no invite should be refused: $result")
            assertEquals(emptyList(), sys.instancesOf("SignUp"))
        }
    }

    // ── the generated surface: in the input value, not in the model object ───

    @Test
    fun `the input value carries the field and the model object does not`() {
        val src = Codegen.generate(signUp, "SignUps")
        val input = src.substringAfter("data class NewSignUp(").substringBefore(")")
        assertTrue("inviteCode" in input, input)
        val modelObject = src.substringAfter("data class SignUp(").substringBefore(")")
        assertFalse("inviteCode" in modelObject, modelObject)
    }

    @Test
    fun `the store schema has no column for the field`() {
        val src = StoreGen.generate(signUp, "SignUps")
        assertFalse("inviteCode" in src, src.lines().filter { "inviteCode" in it }.joinToString("\n"))
    }
}
