package velle.generated.batches

import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import velle.CommitResult
import velle.Model
import velle.Parser
import velle.Validator
import velle.VelleRuntimeError
import velle.VelleSystem

/**
 * The findings of the batches pass (OQ16's calibration record, B1–B6), each
 * pinned as a probe over a small spec so the record points at something that
 * runs. B2 is retired: the probe now shows the construct that closed it. Every probe asserts what the compiler and runtime do *today*; where
 * the Reference says otherwise, the comment says so and names the work item.
 * A probe that starts failing is a finding that moved.
 */
class BatchesProbesTest {

    private fun codes(src: String) = Validator.validate(src).map { it.code }
    private fun messages(src: String) = Validator.validate(src).joinToString("\n") { "[${it.code}] ${it.message}" }
    private fun system(src: String) = VelleSystem(Model(Parser.parse(src)))
    private fun VelleSystem.accept(shape: String, vararg fields: Pair<String, Any?>, parts: Map<String, List<Map<String, Any?>>> = emptyMap()): Long =
        commit(shape, fields.toMap(), parts).let { assertIs<CommitResult.Accepted>(it, "refused: $it").id }

    /** The spelling the example settled on, minus what each probe varies. */
    private val inverted = """
        act Peptide { code: text }
        act Strain { name: text }
        act Fermentation { strain: one Strain }
        act ExternalOrder { orderItem: text }
        act Composition { mixedOn: Date } with components
        shape Component {
            composition: one Composition
            source: one PeptideBatch
            amountMg: decimal
        }
        shape Origin = Fermentation or ExternalOrder or Composition
        act PeptideBatch {
            peptide: one Peptide
            quantityMg: decimal
            origin: one Origin
        }
    """.trimIndent()

    // ── B1: the direct translation — a cross-base union's predicates are not name-checked ──

    private val direct = """
        act Peptide { code: text }
        act Fermented { peptide: one Peptide, quantityMg: decimal, strain: text }
        act Ordered   { peptide: one Peptide, quantityMg: decimal, vendor: text }
        act Composite { peptide: one Peptide, quantityMg: decimal }
        shape PeptideBatch = Fermented or Ordered or Composite
    """.trimIndent()

    @Test
    fun `B1 - a never over the union reading a field declared three times is accepted, against Reference 9`() {
        // Reference §9: `Post.author` and `Photo.author` "are two declarations
        // that happen to agree, and are *not* shared: a rule over `Content`
        // reading `this.author` is a compile error." The rule-body read is
        // caught (below); the predicate read is not, because the declaration
        // walk skips a predicate whose subject has no single base
        // (`Validator.walkRefExpr`, `Model.baseOf` null for a cross-base
        // union). OQ35 is the construct that would make the read legal.
        val src = direct + "\nnever (PeptideBatch where quantityMg <= 0)"
        assertEquals(emptyList(), codes(src), messages(src))

        // and the runtime then resolves the name against each concrete
        // instance — the by-coincidence sharing Reference §9 rejects
        val sys = system(src)
        val pep = sys.accept("Peptide", "code" to "P")
        assertIs<CommitResult.Refused>(sys.commit("Fermented", mapOf("peptide" to pep, "quantityMg" to BigDecimal.ZERO, "strain" to "x")))
        sys.accept("Fermented", "peptide" to pep, "quantityMg" to BigDecimal.ONE, "strain" to "x")
    }

    @Test
    fun `B1 - the same read in a rule body is refused, and a nonsense name in a union predicate is not`() {
        val body = direct + """

            shape Label { batch: one PeptideBatch, mg: decimal }
            rule MakeLabel when PeptideBatch { Label from { batch: this, mg: this.quantityMg } }
        """.trimIndent()
        assertTrue(codes(body).contains("F1"), messages(body))

        val nonsense = inverted + "\nshape Odd = Origin where bogus is empty\nnever (Origin where alsoBogus > 1)"
        assertEquals(emptyList(), codes(nonsense), "a predicate over a cross-base union is not name-checked: " + messages(nonsense))
    }

    // ── B2, retired: the batch and its origin arrive in one commit (OQ63 §6.1) ──

    @Test
    fun `B2 retired - a one field named in with lets the batch arrive with its new origin`() {
        // The finding was that a closure only created parts pointing back at
        // their container, so the origin landed a commit earlier and "no origin
        // without its batch" refused the origin's own commit. Naming the `one`
        // field in `with` lets the reference be supplied by value: the origin is
        // created in the batch's commit, the door sees both, and the books rule
        // holds (Reference §6, "A reference supplied by value").
        val src = inverted
            .replace("act PeptideBatch {", "never (Fermentation where peptideBatchs is empty)\n\nact PeptideBatch {")
            .replace("origin: one Origin\n}", "origin: one Origin\n} with origin")
        assertEquals(emptyList(), codes(src), messages(src))
        val sys = system(src)
        val strain = sys.accept("Strain", "name" to "s")
        val pep = sys.accept("Peptide", "code" to "P")
        // an origin on its own is still refused: the batch cannot exist yet
        assertIs<CommitResult.Refused>(sys.commit("Fermentation", mapOf("strain" to strain)))
        // arriving with its batch, it is accepted, and the two land together
        val batch = sys.accept("PeptideBatch", "peptide" to pep, "quantityMg" to BigDecimal.ONE,
            parts = mapOf("origin" to listOf(mapOf(VelleSystem.SHAPE_KEY to "Fermentation", "strain" to strain))))
        val ferm = sys.instancesOf("Fermentation").single()
        assertEquals(ferm, sys.get(batch, "origin"))
    }

    // ── B3: the inferred inverse is pluralized with a literal `s` ──

    @Test
    fun `B3 - PeptideBatch's inferred inverse is spelled peptideBatchs`() {
        // Reference §6: "decapitalized and pluralized with `s`" — literally
        // (`Model.computeMembers`). The example declares the readable name as a
        // view on the union (Reference §6, "Renamed and derived collections").
        val literal = inverted + "\nshape Unclaimed = Fermentation where peptideBatchs is empty"
        assertEquals(emptyList(), codes(literal), messages(literal))
        val readable = inverted + "\nshape Unclaimed = Fermentation where peptideBatches is empty"
        assertTrue(codes(readable).contains("F1"), messages(readable))
    }

    // ── B4: narrowing is lost on a collection filter's source path ──

    @Test
    fun `B4 - origin is Composition does not license origin dot components as a filter source`() {
        // OQ44 §3 lists this form (peptides S14). The example filters over the
        // declared shape instead: `exists (Component where composition == this.origin ...)`.
        val narrowed = inverted + "\nnever (PeptideBatch where origin is Composition and exists (origin.components where source.peptide != this.peptide))"
        assertTrue(codes(narrowed).contains("F1"), messages(narrowed))
        val declared = inverted + "\nnever (PeptideBatch where exists (Component where composition == this.origin and source.peptide != this.peptide))"
        assertEquals(emptyList(), codes(declared), messages(declared))
    }

    // ── B5: a field read through a `many` hop validates and cannot be evaluated ──

    private fun chain(inputs: String) = inverted.replace(
        "origin: one Origin",
        "origin: one Origin\n            $inputs",
    )

    @Test
    fun `B5 - the batches a mixture drew from, as a derived collection, has no evaluable spelling`() {
        // Both spellings of "the source of each component" pass the validator;
        // the evaluator reads no field through a collection. So the ancestor
        // set — and with it the transitive "never draws from itself" — has no
        // spelling that runs; the one-step `never` is what the example keeps.
        val filtered = chain("inputs: many PeptideBatch = (Component where composition == this.origin).source")
        val narrowed = chain("inputs: many PeptideBatch = if origin is Composition then origin.components.source else empty")
        for (src in listOf(filtered, narrowed)) {
            assertEquals(emptyList(), codes(src), messages(src))
            val sys = system(src)
            val pep = sys.accept("Peptide", "code" to "P")
            val strain = sys.accept("Strain", "name" to "s")
            val ferm = sys.accept("Fermentation", "strain" to strain)
            val b1 = sys.accept("PeptideBatch", "peptide" to pep, "quantityMg" to BigDecimal.TEN, "origin" to ferm)
            val mix = sys.accept("Composition", "mixedOn" to LocalDate.of(2026, 4, 1),
                parts = mapOf("components" to listOf(mapOf("source" to b1, "amountMg" to BigDecimal.ONE))))
            val b2 = sys.accept("PeptideBatch", "peptide" to pep, "quantityMg" to BigDecimal.ONE, "origin" to mix)
            assertFailsWith<VelleRuntimeError> { sys.get(b2, "inputs") }
        }
    }

    // ── B6: V14 does not see a derived collection that reads itself ──

    @Test
    fun `B6 - a derived collection reading itself through a many hop draws no V14`() {
        // Reference §19: every static cycle in the definition graph owes a
        // certificate, "and no certificate is a compile error". V14 certifies
        // one cycle form and is meant to fail closed on every other; a
        // self-read through a `many` is not in its definition graph at all.
        // Masked today by B5 — the read cannot be evaluated either way.
        val src = """
            act Node {
                name: text
                kids: many Node
                descendants: many Node = kids + kids.descendants
            }
        """.trimIndent()
        assertEquals(emptyList(), codes(src), messages(src))
    }
}
