package velle.pharmacy

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.request.RequestPostProcessor
import tools.jackson.databind.ObjectMapper
import velle.generated.PharmacySystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The application booted over an in-memory SQLite database in place of the
 * PostgreSQL that `bootRun` starts, with five weeks of seeded business, and with
 * no identity provider: a signed-in person is presented through Spring
 * Security's test support, a token whose `sub` is the identityId Roster gives a
 * member of staff. The starter finds the system bean, creates the schema, the
 * seed walks the weeks through the spec's own acts, and the records read back
 * for whoever signed in. PharmacySignInTest runs against a real Authentik.
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

    private fun signedInAs(staffMember: Roster.Hire): RequestPostProcessor = jwt().jwt { token -> token.subject(staffMember.identityId) }

    private val margaret = signedInAs(Roster.founder)
    private val tom = signedInAs(Roster.hires.first { hire -> hire.username == "tom" })
    private val strangerTheProviderKnows = jwt().jwt { token -> token.subject("9999") }

    @Suppress("UNCHECKED_CAST")
    private fun parse(body: String): Map<String, Any?> = json.readValue(body, Map::class.java) as Map<String, Any?>

    private fun count(root: String, where: String? = null, person: RequestPostProcessor = margaret): Int =
        parse(mvc.get("/api/shapes/$root/count") { with(person); where?.let { param("where", it) } }
            .andExpect { status { isOk() } }.andReturn().response.contentAsString)["count"] as Int

    @Test
    fun `the pharmacy system is served over sqlite to whoever signed in`() {
        val described = parse(mvc.get("/api") { with(margaret) }.andExpect { status { isOk() } }.andReturn().response.contentAsString)
        assertTrue((described["acts"] as List<*>).isNotEmpty(), described.toString())
        assertEquals(listOf("Daily", "Hourly", "Monthly", "Nightly"), (described["schedules"] as List<*>).map { schedule -> schedule.toString() }.sorted())
        assertEquals(true, described["authorization"])
        mvc.get("/velle") { with(margaret) }.andExpect { status { isOk() } }
    }

    @Test
    fun `nobody signed in is answered 401 at the api and sent to sign in at the console`() {
        mvc.get("/api/shapes/Patient/count").andExpect { status { isUnauthorized() } }
        mvc.get("/velle").andExpect {
            status { is3xxRedirection() }
            header { string("Location", org.hamcrest.Matchers.endsWith("/oauth2/authorization/authentik")) }
        }
    }

    @Test
    fun `the header no longer names an actor`() {
        mvc.get("/api/shapes/Patient/count") { header("X-Velle-Actor", "Staff:1") }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `a person the provider knows and no staff record names is no actor`() {
        mvc.get("/api/shapes/Patient/count") { with(strangerTheProviderKnows) }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `each member of staff is on the books with the identity the provider knows them by`() {
        for (staffMember in listOf(Roster.founder) + Roster.hires) {
            val onTheBooks = pharmacy.read.staffByIdentityId(staffMember.identityId)
            assertEquals(staffMember.name, onTheBooks?.name, staffMember.username)
            assertEquals(staffMember.email, onTheBooks?.email, staffMember.username)
        }
    }

    @Test
    fun `the seed put five weeks of business on the books, read back as the first administrator`() {
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
        val latestTicks = mvc.get("/api/ticks") { with(margaret) }.andExpect { status { isOk() } }.andReturn().response.contentAsString
        assertTrue("\"schedule\"" in latestTicks && "\"at\":null" !in latestTicks, "every schedule ticked through the weeks: $latestTicks")

        // the fills were filled on their days, not today: the seed walked the clock
        val fills = parse(mvc.get("/api/shapes/Fill?orderBy=filledOn&size=1") { with(margaret) }.andReturn().response.contentAsString)
        val first = (fills["items"] as List<*>).single() as Map<*, *>
        assertTrue(first["filledOn"].toString() < pharmacy.now.toString().take(10), "first fill on ${first["filledOn"]}")

        // a technician does not see the claims
        assertEquals(0, count("Claim", person = tom))
    }
}
