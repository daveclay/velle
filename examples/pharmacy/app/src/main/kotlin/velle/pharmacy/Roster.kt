package velle.pharmacy

import java.time.LocalDate

/**
 * The reference data the seed loads before any day of business: the staff, the
 * formulary with its ingredients, interactions, and prices, the insurance plans,
 * the prescribers, and the patients with what each one takes. Plain data, no
 * Velle in it; [PharmacySeed] turns it into acts.
 */
internal object Roster {

    // ── staff ────────────────────────────────────────────────────────────────

    /** The first administrator, seeded outside the trust boundary; everyone else is hired by her. */
    const val FOUNDER = "Margaret Hale"

    class Hire(val name: String, val role: String, /** days from the seed's first day; a pharmacist's licence expires then */ val licenceDays: Int? = null)

    val hires = listOf(
        Hire("Devon Okoro", "admin"),
        Hire("Priya Raman", "pharmacist", licenceDays = 70),      // renewed during the walk
        Hire("Luis Ortega", "pharmacist", licenceDays = 110),     // within thirty days of lapsing as the ninety days end
        Hire("Hannah Weiss", "pharmacist", licenceDays = 48),     // lapses during the walk and is not renewed: she stops verifying that day
        Hire("Samuel Adeyemi", "pharmacist", licenceDays = 400),
        Hire("Tom Brandt", "technician"),
        Hire("Aisha Khan", "technician"),
        Hire("Marco Russo", "technician"),
        Hire("Jade Nguyen", "technician"),
        Hire("Chen Wei", "technician"),
    )

    // ── the formulary ────────────────────────────────────────────────────────

    class DrugSpec(
        val name: String,
        val strength: String,
        val form: String,
        val ingredients: List<String>,
        val schedule: Int = 0,
        val counselling: Boolean = false,
        val reorderPoint: Int,
        val price: String,
        /** stock on the shelf on the first day: a lot size per lot */
        val lots: List<Int>,
    )

    val drugs = listOf(
        DrugSpec("Amoxicillin", "500 mg", "capsule", listOf("amoxicillin"), reorderPoint = 200, price = "0.42", lots = listOf(500, 500)),
        DrugSpec("Amoxicillin-Clavulanate", "875-125 mg", "tablet", listOf("amoxicillin", "clavulanate"), reorderPoint = 100, price = "1.85", lots = listOf(200, 100)),
        DrugSpec("Penicillin VK", "500 mg", "tablet", listOf("penicillin"), reorderPoint = 100, price = "0.35", lots = listOf(300)),
        DrugSpec("Lisinopril", "10 mg", "tablet", listOf("lisinopril"), reorderPoint = 300, price = "0.12", lots = listOf(1000, 500)),
        DrugSpec("Lisinopril-HCTZ", "20-12.5 mg", "tablet", listOf("lisinopril", "hydrochlorothiazide"), reorderPoint = 100, price = "0.28", lots = listOf(300)),
        DrugSpec("Metformin", "500 mg", "tablet", listOf("metformin"), reorderPoint = 400, price = "0.09", lots = listOf(1000, 1000)),
        DrugSpec("Metformin ER", "1000 mg", "tablet", listOf("metformin"), reorderPoint = 200, price = "0.31", lots = listOf(500)),
        DrugSpec("Atorvastatin", "20 mg", "tablet", listOf("atorvastatin"), reorderPoint = 300, price = "0.18", lots = listOf(1000)),
        DrugSpec("Simvastatin", "40 mg", "tablet", listOf("simvastatin"), reorderPoint = 150, price = "0.15", lots = listOf(500)),
        DrugSpec("Amlodipine", "5 mg", "tablet", listOf("amlodipine"), reorderPoint = 300, price = "0.10", lots = listOf(1000)),
        DrugSpec("Levothyroxine", "50 mcg", "tablet", listOf("levothyroxine"), counselling = true, reorderPoint = 300, price = "0.22", lots = listOf(1000)),
        DrugSpec("Omeprazole", "20 mg", "capsule", listOf("omeprazole"), reorderPoint = 250, price = "0.20", lots = listOf(500, 500)),
        DrugSpec("Sertraline", "50 mg", "tablet", listOf("sertraline"), counselling = true, reorderPoint = 200, price = "0.16", lots = listOf(500)),
        DrugSpec("Fluoxetine", "20 mg", "capsule", listOf("fluoxetine"), counselling = true, reorderPoint = 150, price = "0.14", lots = listOf(300)),
        DrugSpec("Warfarin", "5 mg", "tablet", listOf("warfarin"), counselling = true, reorderPoint = 150, price = "0.24", lots = listOf(300)),
        DrugSpec("Clopidogrel", "75 mg", "tablet", listOf("clopidogrel"), reorderPoint = 150, price = "0.33", lots = listOf(300)),
        DrugSpec("Aspirin EC", "81 mg", "tablet", listOf("aspirin"), reorderPoint = 200, price = "0.04", lots = listOf(1000)),
        DrugSpec("Ibuprofen", "800 mg", "tablet", listOf("ibuprofen"), reorderPoint = 200, price = "0.11", lots = listOf(500)),
        DrugSpec("Metoprolol Tartrate", "50 mg", "tablet", listOf("metoprolol"), reorderPoint = 250, price = "0.13", lots = listOf(500)),
        DrugSpec("Losartan", "50 mg", "tablet", listOf("losartan"), reorderPoint = 200, price = "0.17", lots = listOf(500)),
        DrugSpec("Albuterol HFA", "90 mcg/actuation", "inhaler", listOf("albuterol"), counselling = true, reorderPoint = 30, price = "28.50", lots = listOf(40)),
        DrugSpec("Prednisone", "10 mg", "tablet", listOf("prednisone"), reorderPoint = 150, price = "0.08", lots = listOf(300)),
        DrugSpec("Azithromycin", "250 mg", "tablet", listOf("azithromycin"), reorderPoint = 120, price = "0.95", lots = listOf(180)),
        DrugSpec("Ciprofloxacin", "500 mg", "tablet", listOf("ciprofloxacin"), reorderPoint = 100, price = "0.40", lots = listOf(200)),
        DrugSpec("Sulfamethoxazole-Trimethoprim", "800-160 mg", "tablet", listOf("sulfamethoxazole", "trimethoprim"), reorderPoint = 100, price = "0.30", lots = listOf(200)),
        DrugSpec("Gabapentin", "300 mg", "capsule", listOf("gabapentin"), reorderPoint = 300, price = "0.12", lots = listOf(500, 500)),
        // controlled substances: schedule II allows no refills, III to V at most five
        DrugSpec("Oxycodone", "5 mg", "tablet", listOf("oxycodone"), schedule = 2, counselling = true, reorderPoint = 100, price = "1.10", lots = listOf(200, 100)),
        DrugSpec("Oxycodone-Acetaminophen", "5-325 mg", "tablet", listOf("oxycodone", "acetaminophen"), schedule = 2, counselling = true, reorderPoint = 100, price = "0.95", lots = listOf(200)),
        DrugSpec("Hydrocodone-Acetaminophen", "5-325 mg", "tablet", listOf("hydrocodone", "acetaminophen"), schedule = 2, counselling = true, reorderPoint = 100, price = "0.70", lots = listOf(300)),
        DrugSpec("Methylphenidate", "10 mg", "tablet", listOf("methylphenidate"), schedule = 2, counselling = true, reorderPoint = 60, price = "1.40", lots = listOf(120)),
        DrugSpec("Codeine-Acetaminophen", "30-300 mg", "tablet", listOf("codeine", "acetaminophen"), schedule = 3, counselling = true, reorderPoint = 100, price = "0.45", lots = listOf(200)),
        DrugSpec("Tramadol", "50 mg", "tablet", listOf("tramadol"), schedule = 4, counselling = true, reorderPoint = 100, price = "0.25", lots = listOf(300)),
        DrugSpec("Alprazolam", "0.5 mg", "tablet", listOf("alprazolam"), schedule = 4, counselling = true, reorderPoint = 100, price = "0.20", lots = listOf(200)),
        DrugSpec("Lorazepam", "1 mg", "tablet", listOf("lorazepam"), schedule = 4, counselling = true, reorderPoint = 100, price = "0.22", lots = listOf(200)),
        DrugSpec("Zolpidem", "10 mg", "tablet", listOf("zolpidem"), schedule = 4, counselling = true, reorderPoint = 80, price = "0.35", lots = listOf(150)),
    )

    /** Every ingredient any drug or allergy names. */
    val ingredients: List<String> = (drugs.flatMap { it.ingredients } + listOf("sulfa", "latex", "shellfish", "peanut")).distinct()

    /** Interactions between two drugs, entered once in either order. */
    val interactions = listOf(
        Triple("Warfarin", "Aspirin EC", "major"),
        Triple("Warfarin", "Ibuprofen", "major"),
        Triple("Warfarin", "Sulfamethoxazole-Trimethoprim", "major"),
        Triple("Warfarin", "Ciprofloxacin", "major"),
        Triple("Azithromycin", "Warfarin", "moderate"),
        Triple("Lisinopril", "Ibuprofen", "moderate"),
        Triple("Lisinopril-HCTZ", "Ibuprofen", "moderate"),
        Triple("Lisinopril", "Losartan", "moderate"),
        Triple("Oxycodone", "Alprazolam", "major"),
        Triple("Oxycodone", "Zolpidem", "major"),
        Triple("Oxycodone-Acetaminophen", "Lorazepam", "major"),
        Triple("Hydrocodone-Acetaminophen", "Lorazepam", "major"),
        Triple("Hydrocodone-Acetaminophen", "Alprazolam", "major"),
        Triple("Codeine-Acetaminophen", "Alprazolam", "major"),
        Triple("Tramadol", "Sertraline", "major"),
        Triple("Tramadol", "Fluoxetine", "major"),
        Triple("Sertraline", "Fluoxetine", "major"),
        Triple("Clopidogrel", "Omeprazole", "moderate"),
        Triple("Simvastatin", "Amlodipine", "moderate"),
        Triple("Methylphenidate", "Sertraline", "moderate"),
    )

    /** A price that changed forty days in: the drug and its new unit price. */
    val priceRises = mapOf(
        "Albuterol HFA" to "31.20",
        "Oxycodone" to "1.25",
        "Azithromycin" to "1.05",
        "Levothyroxine" to "0.25",
    )

    // ── insurance ────────────────────────────────────────────────────────────

    class Plan(val name: String, val coversControlled: Boolean, /** what the patient pays on a paid claim, as a share of the fill */ val copayShare: String, /** or at least this */ val copayFloor: String)

    val plans = listOf(
        Plan("BlueCare PPO", coversControlled = true, copayShare = "0.20", copayFloor = "10.00"),
        Plan("Medicare Part D", coversControlled = true, copayShare = "0.25", copayFloor = "4.50"),
        Plan("Aetna Choice", coversControlled = false, copayShare = "0.30", copayFloor = "15.00"),
        Plan("Cigna Open Access", coversControlled = true, copayShare = "0.15", copayFloor = "12.00"),
    )

    // ── prescribers ──────────────────────────────────────────────────────────

    class PrescriberSpec(val name: String, val dea: String, val fax: String)

    val prescribers = listOf(
        PrescriberSpec("Dr Amara Okafor", "BO1234563", "555-0199"),
        PrescriberSpec("Dr Felix Lindqvist", "FL2847361", "555-0142"),
        PrescriberSpec("Dr Rosa Delgado", "BD9182736", "555-0177"),
        PrescriberSpec("Dr Henry Achebe", "AH5566778", "555-0163"),
        PrescriberSpec("Dr Mei Tanaka", "MT3344556", "555-0188"),
        PrescriberSpec("Dr Omar Haddad", "BH7788990", "555-0121"),
        PrescriberSpec("Dr Grace Whitfield", "FW1122334", "555-0155"),
        PrescriberSpec("Dr Ivan Petrov", "AP6677889", "555-0134"),
    )

    // ── patients ─────────────────────────────────────────────────────────────

    /** One line of a patient's regimen: the drug, the quantity per fill, the days it lasts, the refills the prescriber allows. */
    class Regimen(val drug: String, val quantity: Int, val daysSupply: Int, val refills: Int, val directions: String)

    class PatientSpec(
        val name: String,
        val birthDate: LocalDate,
        val phone: String,
        val allergies: List<String> = emptyList(),
        /** index into [plans], or null for a patient paying cash */
        val plan: Int?,
        /** index into [prescribers]: the patient's usual doctor */
        val prescriber: Int,
        val regimen: List<Regimen>,
    )

    private fun daily(drug: String, refills: Int = 5, directions: String = "take one tablet by mouth once daily") = Regimen(drug, 30, 30, refills, directions)
    private fun twice(drug: String, refills: Int = 5, directions: String = "take one tablet by mouth twice daily") = Regimen(drug, 60, 30, refills, directions)

    val patients = listOf(
        PatientSpec("Eleanor Vance", LocalDate.of(1948, 3, 12), "555-0101", plan = 1, prescriber = 0,
            regimen = listOf(daily("Lisinopril"), daily("Atorvastatin"), twice("Metformin"), daily("Warfarin", directions = "take one tablet by mouth once daily in the evening"))),
        PatientSpec("Marcus Bell", LocalDate.of(1975, 7, 2), "555-0102", plan = 0, prescriber = 1,
            regimen = listOf(daily("Sertraline", directions = "take one tablet by mouth every morning"), daily("Omeprazole", directions = "take one capsule by mouth before breakfast"))),
        PatientSpec("Sofia Marchetti", LocalDate.of(1990, 11, 23), "555-0103", allergies = listOf("penicillin"), plan = 2, prescriber = 2,
            regimen = listOf(daily("Levothyroxine", directions = "take one tablet by mouth every morning on an empty stomach"))),
        PatientSpec("Raymond Okonkwo", LocalDate.of(1962, 1, 30), "555-0104", plan = 1, prescriber = 0,
            regimen = listOf(daily("Amlodipine"), daily("Simvastatin", directions = "take one tablet by mouth at bedtime"), twice("Metformin ER"))),
        PatientSpec("Linda Haskins", LocalDate.of(1955, 9, 14), "555-0105", allergies = listOf("sulfa", "sulfamethoxazole"), plan = 1, prescriber = 3,
            regimen = listOf(daily("Losartan"), daily("Clopidogrel"), daily("Aspirin EC"))),
        PatientSpec("Daniel Reyes", LocalDate.of(1983, 5, 8), "555-0106", plan = null, prescriber = 4,
            regimen = listOf(Regimen("Albuterol HFA", 1, 30, 5, "inhale two puffs by mouth every four to six hours as needed"))),
        PatientSpec("Grace Mwangi", LocalDate.of(1971, 12, 1), "555-0107", plan = 0, prescriber = 2,
            regimen = listOf(daily("Fluoxetine"), Regimen("Gabapentin", 90, 30, 5, "take one capsule by mouth three times daily"))),
        PatientSpec("Peter Lindgren", LocalDate.of(1944, 6, 27), "555-0108", plan = 1, prescriber = 5,
            regimen = listOf(daily("Metoprolol Tartrate", directions = "take one tablet by mouth twice daily"), daily("Lisinopril-HCTZ"), daily("Atorvastatin"))),
        PatientSpec("Yuki Sato", LocalDate.of(1996, 2, 17), "555-0109", allergies = listOf("latex"), plan = 3, prescriber = 6,
            regimen = listOf(daily("Sertraline"))),
        PatientSpec("Harold Finch", LocalDate.of(1958, 10, 5), "555-0110", plan = 1, prescriber = 7,
            regimen = listOf(Regimen("Oxycodone", 30, 10, 0, "take one tablet by mouth every six hours as needed for pain"), daily("Omeprazole"))),
        PatientSpec("Amelia Torres", LocalDate.of(1988, 8, 19), "555-0111", allergies = listOf("codeine"), plan = 0, prescriber = 4,
            regimen = listOf(Regimen("Tramadol", 60, 30, 2, "take one tablet by mouth every twelve hours as needed"))),
        PatientSpec("Kwame Asante", LocalDate.of(1967, 4, 3), "555-0112", plan = 2, prescriber = 1,
            regimen = listOf(twice("Metformin"), daily("Lisinopril"), daily("Atorvastatin"))),
        PatientSpec("Beatrice Holm", LocalDate.of(1939, 1, 9), "555-0113", plan = 1, prescriber = 5,
            regimen = listOf(daily("Levothyroxine"), daily("Amlodipine"), daily("Zolpidem", refills = 3, directions = "take one tablet by mouth at bedtime as needed for sleep"))),
        PatientSpec("Jonas Weber", LocalDate.of(1979, 3, 21), "555-0114", plan = 3, prescriber = 3,
            regimen = listOf(daily("Alprazolam", refills = 2, directions = "take one tablet by mouth twice daily as needed for anxiety"))),
        PatientSpec("Priyanka Desai", LocalDate.of(1993, 12, 12), "555-0115", plan = 0, prescriber = 6,
            regimen = listOf(Regimen("Methylphenidate", 30, 30, 0, "take one tablet by mouth every morning"))),
        PatientSpec("Walter Gibbs", LocalDate.of(1951, 7, 7), "555-0116", allergies = listOf("penicillin", "amoxicillin"), plan = 1, prescriber = 0,
            regimen = listOf(daily("Warfarin"), daily("Metoprolol Tartrate"), daily("Omeprazole"))),
        PatientSpec("Fatima Al-Sayed", LocalDate.of(1985, 5, 25), "555-0117", plan = 2, prescriber = 2,
            regimen = listOf(daily("Levothyroxine"), daily("Sertraline"))),
        PatientSpec("Oscar Brennan", LocalDate.of(1969, 9, 30), "555-0118", plan = 0, prescriber = 7,
            regimen = listOf(Regimen("Hydrocodone-Acetaminophen", 20, 5, 0, "take one tablet by mouth every six hours as needed for pain"), Regimen("Ibuprofen", 30, 10, 1, "take one tablet by mouth every eight hours with food"))),
        PatientSpec("Nadia Kowalski", LocalDate.of(2001, 6, 15), "555-0119", allergies = listOf("peanut"), plan = 3, prescriber = 6,
            regimen = listOf(daily("Fluoxetine"))),
        PatientSpec("George Abernathy", LocalDate.of(1946, 11, 2), "555-0120", plan = 1, prescriber = 5,
            regimen = listOf(daily("Lisinopril"), twice("Metformin"), daily("Simvastatin"), daily("Clopidogrel"))),
        PatientSpec("Isabel Fonseca", LocalDate.of(1977, 2, 28), "555-0121", plan = 0, prescriber = 1,
            regimen = listOf(daily("Losartan"), daily("Atorvastatin"))),
        PatientSpec("Theo Marsh", LocalDate.of(1999, 10, 10), "555-0122", plan = null, prescriber = 4,
            regimen = listOf(Regimen("Albuterol HFA", 1, 30, 2, "inhale two puffs by mouth every four to six hours as needed"))),
        PatientSpec("Ruth Adler", LocalDate.of(1953, 4, 18), "555-0123", allergies = listOf("sulfa"), plan = 1, prescriber = 3,
            regimen = listOf(daily("Amlodipine"), daily("Levothyroxine"), daily("Lorazepam", refills = 2, directions = "take one tablet by mouth at bedtime as needed"))),
        PatientSpec("Samuel Oyelaran", LocalDate.of(1981, 8, 8), "555-0124", plan = 2, prescriber = 0,
            regimen = listOf(daily("Lisinopril"), daily("Metoprolol Tartrate"))),
        PatientSpec("Claire Dubois", LocalDate.of(1992, 1, 24), "555-0125", plan = 3, prescriber = 2,
            regimen = listOf(daily("Omeprazole"))),
        PatientSpec("Victor Nakamura", LocalDate.of(1960, 12, 6), "555-0126", plan = 2, prescriber = 7,
            regimen = listOf(Regimen("Gabapentin", 90, 30, 5, "take one capsule by mouth three times daily"), Regimen("Codeine-Acetaminophen", 30, 30, 5, "take one tablet by mouth at bedtime as needed for pain"))),
        PatientSpec("Helen Carter", LocalDate.of(1972, 7, 14), "555-0127", plan = 0, prescriber = 6,
            regimen = listOf(daily("Sertraline"), daily("Omeprazole"))),
        PatientSpec("Abdul Rahman", LocalDate.of(1966, 3, 3), "555-0128", plan = 2, prescriber = 1,
            regimen = listOf(twice("Metformin"), daily("Atorvastatin"), daily("Aspirin EC"))),
        PatientSpec("Megan O'Connell", LocalDate.of(1987, 9, 9), "555-0129", allergies = listOf("penicillin"), plan = 0, prescriber = 3,
            regimen = listOf(daily("Levothyroxine"))),
        PatientSpec("Arthur Pendleton", LocalDate.of(1941, 5, 1), "555-0130", plan = 1, prescriber = 5,
            regimen = listOf(daily("Warfarin"), daily("Amlodipine"), daily("Metoprolol Tartrate"), daily("Omeprazole"))),
    )
}
