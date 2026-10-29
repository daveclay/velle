package velle.pharmacy

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import tools.jackson.databind.ObjectMapper
import velle.generated.PharmacySystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The application booted over an in-memory SQLite database in place of the
 * PostgreSQL that `bootRun` starts, with five weeks of seeded business: the
 * starter finds the system bean, creates the schema, the seed walks the weeks
 * through the spec's own acts, and the records read back for a member of staff.
 */
@SpringBootTest(properties = [
    "spring.docker.compose.enabled=false",
    "spring.datasource.url=jdbc:sqlite:file:pharmacy_app_test?mode=memory&cache=shared",
    "spring.datasource.driver-class-name=org.sqlite.JDBC",
    "pharmacy.seed.days=36",
])
@AutoConfigureMockMvc
class PharmacyApplicationTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var json: ObjectMapper
    @Autowired lateinit var pharmacy: PharmacySystem

    @Suppress("UNCHECKED_CAST")
    private fun parse(body: String): Map<String, Any?> = json.readValue(body, Map::class.java) as Map<String, Any?>

    private fun count(root: String, where: String? = null, actor: String = "Staff:1"): Int =
        parse(mvc.get("/api/shapes/$root/count") { header("X-Velle-Actor", actor); where?.let { param("where", it) } }
            .andExpect { status { isOk() } }.andReturn().response.contentAsString)["count"] as Int

    @Test
    fun `the pharmacy system is served over sqlite`() {
        val described = parse(mvc.get("/api").andExpect { status { isOk() } }.andReturn().response.contentAsString)
        assertTrue((described["acts"] as List<*>).isNotEmpty(), described.toString())
        assertEquals(listOf("Daily", "Hourly", "Monthly", "Nightly"), (described["schedules"] as List<*>).map { it.toString() }.sorted())
        assertEquals(true, described["authorization"])
        mvc.get("/velle").andExpect { status { isOk() } }
    }

    @Test
    fun `the seed put five weeks of business on the books, read back as the first administrator`() {
        // nobody is anonymous here: a read without an actor is refused
        mvc.get("/api/shapes/Patient/count").andExpect { status { isUnauthorized() } }

        assertEquals(Roster.patients.size, count("Patient"))
        assertEquals(Roster.drugs.size, count("Drug"))
        assertEquals(Roster.hires.size + 1, count("Staff"))
        assertTrue(count("Prescription") > 60, "prescriptions: ${count("Prescription")}")
        assertTrue(count("Fill") > 60, "fills: ${count("Fill")}")
        assertTrue(count("Pickup") > 40, "pickups: ${count("Pickup")}")
        assertTrue(count("Claim") > 30, "claims: ${count("Claim")}")
        assertTrue(count("PaidClaim") > 20, "paid claims: ${count("PaidClaim")}")
        assertTrue(count("Notice") > 40, "notices: ${count("Notice")}")

        // the scripted days left the states the spec names behind them
        assertTrue(count("PharmacistAlert", "kind == \"allergy\"") >= 1, "allergy alerts")
        assertTrue(count("PharmacistAlert", "kind == \"interaction\"") >= 1, "interaction alerts")
        assertTrue(count("ConflictOverride") >= 1)
        assertTrue(count("Discontinuation") >= 1)
        assertTrue(count("FillRefusal", "reason == \"no refills remaining\"") >= 1, "fill refusals")
        assertTrue(count("FillRefusal", "reason == \"prescription expired\"") >= 1, "expired fill refusal")
        assertTrue(count("RefillRefusal") >= 1 && count("AuthorisationRequest") >= 1, "refill refused and the prescriber asked")
        assertTrue(count("CorrectionRefusal") == 1 && count("VoidRefusal") == 1 && count("EntryVoid") == 1)
        assertTrue(count("PriceWithdrawal") == 1 && count("PriceWithdrawalRefusal") == 1)
        assertTrue(count("CountConfirmation") >= 2 && count("StockAdjustment") >= 1)
        assertTrue(count("RejectedFill") >= 1, "a verification rejected a fill")
        assertTrue(count("Recall") == 1 && count("RecallNotice") >= 1 && count("Quarantine", "reason == \"recalled\"") == 1)
        assertTrue(count("ControlledLogEntry") >= 3, "controlled fills were logged")
        assertTrue(count("MultiplePrescribers") >= 1, "a patient on controlled medicines from two prescribers")
        assertTrue(count("CounterAlert") >= 1, "an allergy reported while a fill waited at the counter")
        assertTrue(count("AutoRefillEnrolment") == 2)
        assertTrue(count("LicenceReminder") >= 1, "a licence within thirty days of lapsing was reminded about")
        assertTrue(count("ControlledInventorySnapshot") >= 1, "a monthly snapshot")
        assertTrue(count("ClaimFollowUp") >= 1, "a stale claim was followed up")
        assertTrue(count("Tick") > 100, "the schedules ticked through the weeks")

        // the fills were filled on their days, not today: the seed walked the clock
        val fills = parse(mvc.get("/api/shapes/Fill?orderBy=filledOn&size=1") { header("X-Velle-Actor", "Staff:1") }.andReturn().response.contentAsString)
        val first = (fills["items"] as List<*>).single() as Map<*, *>
        assertTrue(first["filledOn"].toString() < pharmacy.now.toString().take(10), "first fill on ${first["filledOn"]}")

        // a technician does not see the claims
        val technician = (parse(mvc.get("/api/shapes/Technician?orderBy=name&size=1") { header("X-Velle-Actor", "Staff:1") }.andReturn().response.contentAsString)["items"] as List<*>).single() as Map<*, *>
        assertEquals(0, count("Claim", actor = "Staff:${technician["key"]}"))
    }
}
