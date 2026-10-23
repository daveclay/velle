package velle

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The actor (Reference §22, "The actor"; OQ33): an act declared `by Shape [as name]`
 * is committed by someone, the gate in its header refuses the wrong role, a durable
 * act stores its actor, a rule on a product reads the transaction's actor through
 * its own `by` clause, typed by reachability, and a seeded record arrives outside
 * the trust boundary.
 */
class ActorTest {

    private val spec = """
        visible to Staff

        shape Staff { name: text  role: text }
        Staff requires role == "pharmacist" or role == "admin"
        shape Pharmacist = Staff where role == "pharmacist"
        shape Administrator = Staff where role == "admin"

        transient act HireStaff by Administrator { name: text  role: text }
        rule RecordHire when HireStaff { Staff from { name: name, role: role } }

        act Renewal by Administrator as admin { pharmacist: one Staff  expiresOn: Date }
        Renewal requires pharmacist to be Pharmacist
        never (Renewal where pharmacist == admin)

        shape Reminder { renewal: one Renewal  sentTo: one Staff }
        rule RemindRenewal when (Renewal where not exists Reminder for this) on Daily {
            Reminder from { renewal: this, sentTo: admin }
        }

        shape Lot { number: text }
        act Recall by Staff { lot: one Lot  reference: text }
        act ExpireLot { lot: one Lot }
        shape Quarantine { lot: one Lot  reason: text  quarantinedBy: one Staff? }
        rule QuarantineRecalled when Recall { Quarantine from { lot: lot, reason: "recalled", quarantinedBy: actor } }
        rule QuarantineExpired when ExpireLot { Quarantine from { lot: lot, reason: "expired", quarantinedBy: none } }
        shape QuarantineLog { quarantine: one Quarantine  actorName: text }
        rule LogQuarantine when Quarantine by Staff? {
            QuarantineLog from { quarantine: this, actorName: if actor is some then actor.name else "system" }
        }
    """.trimIndent()

    private fun codes(src: String) = Validator.validate(src).map { it.code }
    private fun diags(src: String) = Validator.validate(src)

    private fun newSystem(): VelleSystem {
        val d = Validator.validate(spec)
        check(d.isEmpty()) { d.toString() }
        return VelleSystem(Model(Parser.parse(spec)))
    }

    private fun VelleSystem.admin(): Long = idOf(seed("Staff", mapOf("name" to "Ada", "role" to "admin")))
    private fun VelleSystem.pharmacist(name: String = "Priya"): Long = idOf(seed("Staff", mapOf("name" to name, "role" to "pharmacist")))
    private fun VelleSystem.lot(): Long = idOf(seed("Lot", mapOf("number" to "L1")))

    // ── parsing and printing ─────────────────────────────────────────────────

    @Test
    fun `the by clause parses on acts and rules, with as and the optional mark`() {
        val decls = Parser.parse(spec)
        val renewal = decls.filterIsInstance<ShapeDecl>().single { it.name == "Renewal" }
        assertEquals(ActorProp("admin", "Administrator", named = true), renewal.actor)
        val hire = decls.filterIsInstance<ShapeDecl>().single { it.name == "HireStaff" }
        assertEquals(ActorProp("actor", "Administrator"), hire.actor)
        val log = decls.filterIsInstance<RuleDecl>().single { it.name == "LogQuarantine" }
        assertEquals(RuleActor("Staff", optional = true, name = "actor"), log.by)
        assertNull(decls.filterIsInstance<ShapeDecl>().single { it.name == "ExpireLot" }.actor)
        assertTrue(Printer.print(renewal).startsWith("act Renewal by Administrator as admin {"))
        assertTrue(Printer.print(log).startsWith("rule LogQuarantine when Quarantine by Staff? {"))
        assertEquals(Parser.parse(Printer.print(renewal)).single(), renewal)
    }

    @Test
    fun `an act's actor is never optional`() {
        assertFailsWith<VelleSyntaxError> { Parser.parse("shape Staff { name: text }\nact Recall by Staff? { reference: text }") }
    }

    // ── validation ───────────────────────────────────────────────────────────

    @Test
    fun `the spec validates, and by Administrator desugars to the requirement in the header`() {
        assertEquals(emptyList(), diags(spec))
        val model = Model(Parser.parse(spec))
        val gate = model.nevers.filter { it.fromActorClause }
        assertEquals(listOf("HireStaff requires actor to be Administrator", "Renewal requires admin to be Administrator"),
            gate.map { Printer.prohibition(it) })
    }

    @Test
    fun `the actor's shape is never an act`() {
        val src = "visible to anyone\nact Staff { name: text }\nact Recall by Staff { reference: text }"
        assertTrue(diags(src).any { it.code == "F2" && "never an act" in it.message }, diags(src).toString())
    }

    @Test
    fun `a field under the bound name demands as`() {
        val src = "visible to anyone\nshape Staff { name: text }\nact Recall by Staff { actor: text }"
        val d = diags(src).single { it.code == "F3" }
        assertTrue("name the actor" in d.message && "as <name>" in d.message, d.message)
        assertTrue(diags("visible to anyone\nshape Staff { name: text }\nact Recall by Staff as who { reference: text }").isEmpty())
    }

    @Test
    fun `V31 - a rule's by must say ? when some path to its trigger has no actor`() {
        val src = spec.replace("when Quarantine by Staff? {", "when Quarantine by Staff {")
        val d = diags(src).filter { it.code == "V31" }
        assertEquals(1, d.size, diags(src).toString())
        assertTrue("act 'ExpireLot', which declares no actor" in d.single().message, d.single().message)
        assertTrue("declare `by Staff?`" in d.single().message, d.single().message)
    }

    @Test
    fun `V31 - a tick path makes the actor optional too`() {
        val src = spec.replace("rule QuarantineExpired when ExpireLot {", "shape StaleLot = Lot where not exists (Quarantine where lot == this)\nrule QuarantineExpired when StaleLot on Nightly {")
            .replace("lot: lot, reason: \"expired\"", "lot: this, reason: \"expired\"")
            .replace("act ExpireLot { lot: one Lot }\n", "")
            .replace("when Quarantine by Staff? {", "when Quarantine by Staff {")
        val d = diags(src).filter { it.code == "V31" }
        assertEquals(1, d.size, diags(src).toString())
        assertTrue("rule 'QuarantineExpired', which fires on Nightly" in d.single().message, d.single().message)
    }

    @Test
    fun `V31 - a rule outside any act's transaction cannot read an actor`() {
        val src = spec.replace("on Daily {\n    Reminder from { renewal: this, sentTo: admin }", "on Daily by Staff {\n    Reminder from { renewal: this, sentTo: actor }")
        val d = diags(src).filter { it.code == "V31" }
        assertEquals(1, d.size, diags(src).toString())
        assertTrue("fires outside any act's transaction" in d.single().message, d.single().message)
    }

    @Test
    fun `V31 - a rule on an act that carries its own actor declares none`() {
        val src = spec.replace("rule QuarantineRecalled when Recall {", "rule QuarantineRecalled when Recall by Staff {")
        val d = diags(src).filter { it.code == "V31" }
        assertEquals(1, d.size, diags(src).toString())
        assertTrue("already in scope" in d.single().message, d.single().message)
    }

    @Test
    fun `V31 - a rule nothing with an actor can fire declares none`() {
        val src = spec.replace("act Recall by Staff {", "act Recall {").replace("quarantinedBy: actor }", "quarantinedBy: none }")
        val d = diags(src).filter { it.code == "V31" }
        assertEquals(1, d.size, diags(src).toString())
        assertTrue("no path to its trigger starts at an act declared `by`" in d.single().message, d.single().message)
    }

    @Test
    fun `an optional actor is read only after is some`() {
        val src = spec.replace("if actor is some then actor.name else \"system\"", "actor.name")
        val d = diags(src).filter { it.code == "F2" }
        assertEquals(1, d.size, diags(src).toString())
        assertTrue("narrow with 'actor is some' first" in d.single().message, d.single().message)
    }

    @Test
    fun `the actor is never supplied by a rule and never assigned`() {
        val supplied = spec.replace("Quarantine from { lot: lot, reason: \"recalled\", quarantinedBy: actor }",
            "Quarantine from { lot: lot, reason: \"recalled\", quarantinedBy: actor }\n    Renewal from { pharmacist: actor, expiresOn: today, admin: actor }")
        assertTrue(diags(supplied).any { it.code == "F3" && "never supplied" in it.message }, diags(supplied).toString())
        val assigned = spec.replace("Quarantine from { lot: lot, reason: \"recalled\", quarantinedBy: actor }",
            "actor = actor")
        assertTrue(diags(assigned).any { it.code == "F3" && "never assigned" in it.message }, diags(assigned).toString())
    }

    @Test
    fun `actor outside any by clause is unresolved, with the hint`() {
        val src = spec.replace("rule QuarantineExpired when ExpireLot { Quarantine from { lot: lot, reason: \"expired\", quarantinedBy: none } }",
            "rule QuarantineExpired when ExpireLot { Quarantine from { lot: lot, reason: \"expired\", quarantinedBy: actor } }")
        val d = diags(src).filter { it.code == "F1" }
        assertTrue(d.any { "'actor' is not a member of 'ExpireLot'" in it.message && "`by` clause" in it.message }, diags(src).toString())
    }

    // ── the runtime ──────────────────────────────────────────────────────────

    @Test
    fun `an act declared by takes an actor, and one without refuses one`() {
        val sys = newSystem()
        val lot = sys.lot()
        val r = sys.commit("Recall", mapOf("lot" to lot, "reference" to "R1"))
        assertIs<CommitResult.Refused>(r)
        assertTrue("committed by a Staff" in r.reason, r.reason)
        val r2 = sys.commit("ExpireLot", mapOf("lot" to lot), actor = sys.pharmacist())
        assertIs<CommitResult.Refused>(r2)
        assertTrue("declares no actor" in r2.reason, r2.reason)
        val r3 = sys.commit("Recall", mapOf("lot" to lot, "reference" to "R1"), actor = lot)
        assertIs<CommitResult.Refused>(r3)
        assertTrue("is not a Staff" in r3.reason, r3.reason)
        val r4 = sys.commit("Recall", mapOf("lot" to lot, "reference" to "R1", "actor" to sys.pharmacist()), actor = sys.pharmacist())
        assertIs<CommitResult.Refused>(r4)
        assertTrue("cannot claim" in r4.reason, r4.reason)
    }

    @Test
    fun `the gate in the header refuses the wrong role as a requirement, with the actor's record in the rejection`() {
        val sys = newSystem()
        val priya = sys.pharmacist()
        val r = sys.commit("HireStaff", mapOf("name" to "Tom", "role" to "pharmacist"), actor = priya)
        assertIs<CommitResult.Refused>(r)
        val rejection = r.rejection!!
        assertEquals("HireStaff requires actor to be Administrator", rejection.declaration)
        assertTrue(r.reason.startsWith("HireStaff refused: HireStaff requires actor to be Administrator, and this HireStaff does not."), r.reason)
        assertTrue(rejection.evaluation.any { it is EvalStep.Membership && it.refinement == "Administrator" && !it.member }, rejection.evaluation.toString())
        assertEquals(1, sys.instancesOf("Staff").size, "nothing committed")

        val ada = sys.admin()
        assertIs<CommitResult.Accepted>(sys.commit("HireStaff", mapOf("name" to "Tom", "role" to "pharmacist"), actor = ada))
        assertEquals(3, sys.instancesOf("Staff").size)
    }

    @Test
    fun `a durable act stores its actor under the bound name, read by a prohibition and a scheduled rule`() {
        val sys = newSystem()
        val ada = sys.admin()
        val priya = sys.pharmacist()
        val refused = sys.commit("Renewal", mapOf("pharmacist" to ada, "expiresOn" to LocalDate.of(2027, 1, 1)), actor = ada)
        assertIs<CommitResult.Refused>(refused)
        val r = sys.commit("Renewal", mapOf("pharmacist" to priya, "expiresOn" to LocalDate.of(2027, 1, 1)), actor = ada)
        val id = assertIs<CommitResult.Accepted>(r).id
        assertEquals(ada, sys.get(id, "admin"))
        sys.tick("Daily")
        val reminder = sys.instancesOf("Reminder").single()
        assertEquals(ada, sys.get(reminder, "sentTo"), "the daily sweep read the renewal's stored actor")
        // never again: the own-renewal prohibition reads the actor in the act's scope
        val own = sys.commit("Renewal", mapOf("pharmacist" to priya, "expiresOn" to LocalDate.of(2028, 1, 1)), actor = priya)
        assertIs<CommitResult.Refused>(own)
    }

    @Test
    fun `a rule on a product reads the transaction's actor, absent where nobody asked`() {
        val sys = newSystem()
        val priya = sys.pharmacist("Priya")
        val lot = sys.lot()
        assertIs<CommitResult.Accepted>(sys.commit("Recall", mapOf("lot" to lot, "reference" to "R1"), actor = priya))
        val recalled = sys.instancesOf("Quarantine").single()
        assertEquals(priya, sys.get(recalled, "quarantinedBy"))
        assertEquals("Priya", sys.get(sys.instancesOf("QuarantineLog").single(), "actorName"))

        val lot2 = sys.lot()
        assertIs<CommitResult.Accepted>(sys.commit("ExpireLot", mapOf("lot" to lot2)))
        val logs = sys.instancesOf("QuarantineLog")
        assertEquals(2, logs.size)
        assertEquals("system", sys.get(logs.last(), "actorName"), "no actor on the path from an act without `by`")
    }

    @Test
    fun `a seeded record fires no rule and passes no door`() {
        val sys = newSystem()
        val ada = sys.admin()
        assertEquals("admin", sys.get(ada, "role"))
        assertTrue(sys.isMember(ada, "Administrator"))
        assertFailsWith<VelleRuntimeError> { sys.seed("HireStaff", mapOf("name" to "x", "role" to "admin")) }
    }
}
