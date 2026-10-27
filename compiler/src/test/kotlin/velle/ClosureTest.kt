package velle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The exposure closure (Reference §6, "Inline part creation"; checks V22, V1's
 * closure extension): `expose ... with` — parsing, edge resolution, the V22
 * refusals, the V1 self-pair refusal, the runtime's one-commit landing, and
 * the generated input types.
 */
class ClosureTest {

    private val ORDERS = """
        act Customer {
            name: text
        }

        act Product {
            sku: text
        }

        act Order {
            customer: one Customer
            placedOn: Date
        } with { orderLines with { customizations } }

        shape OrderLine {
            order: one Order
            product: one Product
            quantity: integer
        }

        shape Customization {
            line: one OrderLine
            note: text
        }

        shape Reservation {
            line: one OrderLine
            quantity: integer
        }

        rule ReserveStock when OrderLine {
            Reservation from { line: this, quantity: this.quantity }
        }
    """.trimIndent()

    private fun system(spec: String = ORDERS): VelleSystem {
        val diags = Validator.validate(spec)
        check(diags.isEmpty()) { diags.toString() }
        return VelleSystem(Model(Parser.parse(spec)))
    }

    private fun VelleSystem.mustCommit(
        shape: String,
        fields: Map<String, Any?>,
        parts: Map<String, List<Map<String, Any?>>> = emptyMap(),
    ): Long {
        val r = commit(shape, fields, parts)
        return assertIs<CommitResult.Accepted>(r, "commit of $shape refused: $r").id
    }

    // ── parsing ──────────────────────────────────────────────────────────────

    @Test
    fun `flat and nested with clauses parse`() {
        val decls = Parser.parse(
            """
            act A { t: text } with bs, ds
            shape B { a: one A, t: text }
            shape D { a: one A, t: text }
            """.trimIndent()
        )
        val a = decls.filterIsInstance<ShapeDecl>().single { it.name == "A" }
        assertEquals(listOf("bs", "ds"), a.with.map { it.name })

        val nested = Parser.parse(
            """
            act A { t: text } with {
                bs with { cs }
            }
            shape B { a: one A, t: text }
            shape C { b: one B, t: text }
            """.trimIndent()
        ).filterIsInstance<ShapeDecl>().single { it.name == "A" }
        assertEquals("bs", nested.with.single().name)
        assertEquals("cs", nested.with.single().children.single().name)
    }

    @Test
    fun `with stays an ordinary identifier outside the act position`() {
        val decls = Parser.parse(
            """
            act A { with: text }
            """.trimIndent()
        )
        val a = decls.filterIsInstance<ShapeDecl>().single()
        assertEquals("with", a.members.filterIsInstance<StoredProp>().single().name)
    }

    // ── V22: closure declaration legality ────────────────────────────────────

    private fun diagnostics(spec: String) = Validator.validate(spec)

    @Test
    fun `an unknown edge name is refused`() {
        val d = diagnostics(
            """
            act A { t: text } with nonsense
            """.trimIndent()
        )
        assertTrue(d.any { it.code == "V22" && "no such collection" in it.message }, d.toString())
    }

    @Test
    fun `an arbitrary-predicate view cannot ride a closure`() {
        val d = diagnostics(
            """
            act A {
                t: text
                bigBs: many B = (B where flagged == true)
            } with bigBs
            shape B {
                a: one A
                flagged: boolean
            }
            """.trimIndent()
        )
        assertTrue(d.any { it.code == "V22" && "arbitrary-predicate" in it.message }, d.toString())
    }

    @Test
    fun `the recognized inverse-form view resolves and pins the back-reference`() {
        // two same-target `one` fields: no inverse is inferred (V19's ambiguity
        // posture), and the declared view of recognized form pins the back-reference
        val spec = """
            act Account {
                name: text
                outgoing: many Transfer = (Transfer where source == this)
                incoming: many Transfer = (Transfer where target == this)
            } with outgoing
            shape Transfer {
                source: one Account
                target: one Account
                amount: decimal
            }
        """.trimIndent()
        assertTrue(diagnostics(spec).isEmpty(), diagnostics(spec).toString())
        val model = Model(Parser.parse(spec))
        val edge = model.closures.getValue("Account").single()
        assertEquals("Transfer", edge.partShape)
        assertEquals("source", edge.backRef)

        // no view declared — V22 demands one rather than guessing
        val spec2 = """
            act Account { name: text } with outgoing
            shape Transfer {
                source: one Account
                target: one Account
                amount: decimal
            }
        """.trimIndent()
        assertTrue(diagnostics(spec2).any { it.code == "V22" }, diagnostics(spec2).toString())
    }

    @Test
    fun `a transient container refuses a closure`() {
        val d = diagnostics(
            """
            transient act A { t: text } with bs
            shape B { a: one A, t: text }
            """.trimIndent()
        )
        assertTrue(d.any { it.code == "V22" && "OQ43" in it.message }, d.toString())
    }

    @Test
    fun `an m2m inverse cannot ride a closure`() {
        val d = diagnostics(
            """
            act Course { title: text } with students
            shape Student {
                name: text
                courses: many Course
            }
            """.trimIndent()
        )
        assertTrue(d.any { it.code == "V22" }, d.toString())
    }

    // ── V1's closure extension: parts never write the container ─────────────

    @Test
    fun `a part-triggered rule assigning through the back-reference is refused`() {
        val d = diagnostics(
            """
            act Order {
                flagged: boolean
            } with orderLines
            shape OrderLine {
                order: one Order
                quantity: integer
            }
            rule FlagOrder when OrderLine {
                this.order.flagged = true
            }
            """.trimIndent()
        )
        assertTrue(d.any { it.code == "V1" && "write-write" in it.message }, d.toString())
    }

    // ── the runtime: one commit, container plus parts ────────────────────────

    @Test
    fun `a closure commit lands container and parts with back-references populated`() {
        val sys = system()
        sys.scope {
            val cust = sys.mustCommit("Customer", mapOf("name" to "Ada"))
            val prod = sys.mustCommit("Product", mapOf("sku" to "X1"))
            val order = sys.mustCommit(
                "Order",
                mapOf("customer" to cust, "placedOn" to java.time.LocalDate.parse("2026-08-21")),
                mapOf("orderLines" to listOf(
                    mapOf("product" to prod, "quantity" to 2),
                    mapOf("product" to prod, "quantity" to 5),
                )),
            )
            val lines = sys.instancesOf("OrderLine")
            assertEquals(2, lines.size)
            lines.forEach { assertEquals(order, sys.get(it, "order")) }
            // the inferred inverse reads the parts
            assertEquals(lines.toSet(), (sys.get(order, "orderLines") as List<*>).toSet())
        }
    }

    @Test
    fun `a part-triggered rule fires once per inline part in the act's transaction`() {
        val sys = system()
        sys.scope {
            val cust = sys.mustCommit("Customer", mapOf("name" to "Ada"))
            val prod = sys.mustCommit("Product", mapOf("sku" to "X1"))
            sys.mustCommit(
                "Order",
                mapOf("customer" to cust, "placedOn" to java.time.LocalDate.parse("2026-08-21")),
                mapOf("orderLines" to listOf(
                    mapOf("product" to prod, "quantity" to 1),
                    mapOf("product" to prod, "quantity" to 2),
                    mapOf("product" to prod, "quantity" to 3),
                )),
            )
            assertEquals(3, sys.instancesOf("Reservation").size)
        }
    }

    @Test
    fun `nested closure parts land pointing at their own level`() {
        val sys = system()
        sys.scope {
            val cust = sys.mustCommit("Customer", mapOf("name" to "Ada"))
            val prod = sys.mustCommit("Product", mapOf("sku" to "X1"))
            sys.mustCommit(
                "Order",
                mapOf("customer" to cust, "placedOn" to java.time.LocalDate.parse("2026-08-21")),
                mapOf("orderLines" to listOf(
                    mapOf("product" to prod, "quantity" to 1,
                        "customizations" to listOf(mapOf("note" to "gift wrap"), mapOf("note" to "no logo"))),
                )),
            )
            val line = sys.instancesOf("OrderLine").single()
            val custs = sys.instancesOf("Customization")
            assertEquals(2, custs.size)
            custs.forEach { assertEquals(line, sys.get(it, "line")) }
        }
    }

    @Test
    fun `identical inline part values are a bag - two distinct instances`() {
        val sys = system()
        sys.scope {
            val cust = sys.mustCommit("Customer", mapOf("name" to "Ada"))
            val prod = sys.mustCommit("Product", mapOf("sku" to "X1"))
            sys.mustCommit(
                "Order",
                mapOf("customer" to cust, "placedOn" to java.time.LocalDate.parse("2026-08-21")),
                mapOf("orderLines" to listOf(
                    mapOf("product" to prod, "quantity" to 1),
                    mapOf("product" to prod, "quantity" to 1), // quantity 1 twice ≠ quantity 2 once
                )),
            )
            assertEquals(2, sys.instancesOf("OrderLine").size)
        }
    }

    @Test
    fun `a committer-supplied back-reference is refused`() {
        val sys = system()
        sys.scope {
            val cust = sys.mustCommit("Customer", mapOf("name" to "Ada"))
            val prod = sys.mustCommit("Product", mapOf("sku" to "X1"))
            val r = sys.commit(
                "Order",
                mapOf("customer" to cust, "placedOn" to java.time.LocalDate.parse("2026-08-21")),
                mapOf("orderLines" to listOf(mapOf("order" to 999L, "product" to prod, "quantity" to 1))),
            )
            assertIs<CommitResult.Refused>(r)
            assertTrue("language-populated" in r.reason, r.reason)
        }
    }

    @Test
    fun `parts on an undeclared edge are refused`() {
        val sys = system()
        sys.scope {
            val cust = sys.mustCommit("Customer", mapOf("name" to "Ada"))
            val r = sys.commit(
                "Customer",
                mapOf("name" to "Bob"),
                mapOf("orders" to listOf(mapOf("placedOn" to java.time.LocalDate.now()))),
            )
            assertIs<CommitResult.Refused>(r)
            assertTrue("no closure edge" in r.reason, r.reason)
            assertEquals(1, sys.instancesOf("Customer").size) // Bob never landed
        }
    }

    @Test
    fun `a refused closure rolls back whole - no partial landing`() {
        val sys = system()
        sys.scope {
            val cust = sys.mustCommit("Customer", mapOf("name" to "Ada"))
            val prod = sys.mustCommit("Product", mapOf("sku" to "X1"))
            val r = sys.commit(
                "Order",
                mapOf("customer" to cust, "placedOn" to java.time.LocalDate.parse("2026-08-21")),
                mapOf("orderLines" to listOf(
                    mapOf("product" to prod, "quantity" to 1),
                    mapOf("product" to prod), // missing quantity — type refusal
                )),
            )
            assertIs<CommitResult.Refused>(r)
            assertEquals(0, sys.instancesOf("Order").size)
            assertEquals(0, sys.instancesOf("OrderLine").size)
            assertEquals(0, sys.instancesOf("Reservation").size)
        }
    }

    // ── the generated surface ────────────────────────────────────────────────

    @Test
    fun `codegen emits nested input types and the extended commit function`() {
        val src = Codegen.generate(ORDERS, "Orders")
        assertTrue("data class NewOrderLine(" in src, src)
        assertTrue("data class NewCustomization(" in src, src)
        assertTrue("val customizations: List<NewCustomization> = emptyList()" in src, src)
        // the projection: no back-reference, no id, on the input types
        val newLine = src.substringAfter("data class NewOrderLine(").substringBefore(") : ")
        assertTrue("order" !in newLine, newLine)
        assertTrue("val product: Ref<Product>" in newLine, newLine)
        // the act's input value takes the closure edge, absent by default, and
        // the commit overload takes the input value
        assertTrue("orderLines: List<NewOrderLine> = emptyList()" in src, src)
        assertTrue("fun commit(input: NewOrder): Commit<NewOrder, Ref<Order>> =" in src, src)
    }

    // ── a reference supplied by value: a `one` field named in `with` (Reference §6; OQ63 §6.1) ──

    private val BATCHES = """
        act Peptide { code: text }
        act BacteriaStrain { name: text }
        act Vendor { name: text }

        shape Fermentation { strain: one BacteriaStrain }
        shape ExternalOrder { vendor: one Vendor, item: text }
        shape Composition { mixedOn: Date }
        shape Component { composition: one Composition, amountMg: decimal }

        shape Origin = Fermentation or ExternalOrder or Composition {
            peptideBatches: many PeptideBatch = (PeptideBatch where origin == this)
        }

        act PeptideBatch {
            peptide: one Peptide
            quantityMg: decimal
            origin: one Origin
        } with { origin with { components } }

        never (Fermentation where peptideBatchs is empty)
        never (PeptideBatch where count(origin.peptideBatches) > 1)

        shape FermentationNote { fermentation: one Fermentation }
        rule NoteFermentation when Fermentation { FermentationNote for this }
    """.trimIndent()

    private fun VelleSystem.strain(): Long = mustCommit("BacteriaStrain", mapOf("name" to "B. subtilis"))
    private fun VelleSystem.peptide(): Long = mustCommit("Peptide", mapOf("code" to "PEP-7"))

    @Test
    fun `a one field named in with resolves to a by-value edge with children resolved per operand`() {
        val model = Model(Parser.parse(BATCHES))
        val edge = model.closures.getValue("PeptideBatch").single()
        assertTrue(edge.byValue)
        assertEquals("origin", edge.prop)
        assertEquals("Origin", edge.partShape)
        val child = edge.children.single()
        assertEquals("components", child.prop)
        assertEquals("Component", child.partShape)
        assertEquals("composition", child.backRef)
        assertEquals("Composition", child.owner)
        assertEquals(emptyList(), edge.childrenFor("Fermentation"))
        assertEquals(listOf(child), edge.childrenFor("Composition"))
    }

    @Test
    fun `V22 refuses a with entry on a scalar, on a transient target, and a child no operand has`() {
        fun diagsOf(spec: String) = Validator.validate(spec).filter { it.code == "V22" }.map { it.message }
        val scalar = diagsOf(BATCHES.replace("with { origin with { components } }", "with quantityMg"))
        assertTrue(scalar.any { "not a collection view or a `one` field" in it }, scalar.toString())
        val transient = diagsOf(
            """
            transient act Request { note: text }
            act Thing { request: one Request } with request
            """.trimIndent()
        )
        assertTrue(transient.any { "transient act" in it }, transient.toString())
        val orphan = diagsOf(BATCHES.replace("with { origin with { components } }", "with { origin with { nothing } }"))
        assertTrue(orphan.any { "no operand of 'Origin' has 'nothing'" in it }, orphan.toString())
    }

    @Test
    fun `a batch and its new origin land as one commit, the origin first`() {
        val sys = system(BATCHES)
        sys.scope {
            val strain = sys.strain()
            val pep = sys.peptide()
            val batch = sys.mustCommit(
                "PeptideBatch",
                mapOf("peptide" to pep, "quantityMg" to java.math.BigDecimal("100")),
                mapOf("origin" to listOf(mapOf(VelleSystem.SHAPE_KEY to "Fermentation", "strain" to strain))),
            )
            val ferm = sys.instancesOf("Fermentation").single()
            assertEquals(ferm, sys.get(batch, "origin"))
            assertEquals(listOf(batch), sys.get(ferm, "peptideBatches"))
            // `never (Fermentation where peptideBatchs is empty)` held at the door: both were in
            // the rule on the nested record fired once, in the batch's transaction
            assertEquals(ferm, sys.get(sys.instancesOf("FermentationNote").single(), "fermentation"))
        }
    }

    @Test
    fun `the reference may still be supplied by handle, and the books rule refuses a second claim`() {
        val sys = system(BATCHES)
        sys.scope {
            val strain = sys.strain()
            val pep = sys.peptide()
            val batch = sys.mustCommit(
                "PeptideBatch", mapOf("peptide" to pep, "quantityMg" to java.math.BigDecimal("100")),
                mapOf("origin" to listOf(mapOf(VelleSystem.SHAPE_KEY to "Fermentation", "strain" to strain))),
            )
            val ferm = sys.get(batch, "origin") as Long
            val second = sys.commit("PeptideBatch", mapOf("peptide" to pep, "quantityMg" to java.math.BigDecimal("5"), "origin" to ferm))
            assertIs<CommitResult.Refused>(second, "one origin, one batch")
            assertEquals(1, sys.instancesOf("PeptideBatch").size)
        }
    }

    @Test
    fun `a nested value carries its own parts, resolved on the operand that declares them`() {
        val sys = system(BATCHES)
        sys.scope {
            val pep = sys.peptide()
            val batch = sys.mustCommit(
                "PeptideBatch", mapOf("peptide" to pep, "quantityMg" to java.math.BigDecimal("30")),
                mapOf("origin" to listOf(mapOf(
                    VelleSystem.SHAPE_KEY to "Composition",
                    "mixedOn" to java.time.LocalDate.parse("2026-04-01"),
                    "components" to listOf(mapOf("amountMg" to java.math.BigDecimal("10")), mapOf("amountMg" to java.math.BigDecimal("20"))),
                ))),
            )
            val comp = sys.instancesOf("Composition").single()
            assertEquals(comp, sys.get(batch, "origin"))
            val parts = sys.instancesOf("Component")
            assertEquals(2, parts.size)
            parts.forEach { assertEquals(comp, sys.get(it, "composition")) }
        }
    }

    @Test
    fun `type refusals - both ways at once, two values, a shape outside the union, neither way`() {
        val sys = system(BATCHES)
        sys.scope {
            val strain = sys.strain()
            val pep = sys.peptide()
            val fields = mapOf("peptide" to pep, "quantityMg" to java.math.BigDecimal("1"))
            val ferm = mapOf(VelleSystem.SHAPE_KEY to "Fermentation", "strain" to strain)
            val existing = sys.idOf(sys.seed("Fermentation", mapOf("strain" to strain)))
            val both = sys.commit("PeptideBatch", fields + ("origin" to existing), mapOf("origin" to listOf(ferm)))
            assertTrue(assertIs<CommitResult.Refused>(both).reason.contains("both"), both.toString())
            val two = sys.commit("PeptideBatch", fields, mapOf("origin" to listOf(ferm, ferm)))
            assertTrue(assertIs<CommitResult.Refused>(two).reason.contains("one value"), two.toString())
            val outside = sys.commit("PeptideBatch", fields, mapOf("origin" to listOf(mapOf(VelleSystem.SHAPE_KEY to "Peptide", "code" to "x"))))
            assertTrue(assertIs<CommitResult.Refused>(outside).reason.contains("cannot arrive"), outside.toString())
            val neither = sys.commit("PeptideBatch", fields)
            assertTrue(assertIs<CommitResult.Refused>(neither).reason.contains("requires"), neither.toString())
            assertEquals(0, sys.instancesOf("PeptideBatch").size)
            assertEquals(listOf(existing), sys.instancesOf("Fermentation"))
        }
    }

    @Test
    fun `a base-shape target and an optional one`() {
        val spec = """
            act Vendor { name: text }
            shape Order { vendor: one Vendor, item: text }
            act Receipt { order: one Order, memo: one Memo? } with { order, memo }
            shape Memo { note: text }
        """.trimIndent()
        val sys = system(spec)
        sys.scope {
            val vendor = sys.mustCommit("Vendor", mapOf("name" to "Bachem"))
            val r = sys.mustCommit("Receipt", emptyMap(), mapOf("order" to listOf(mapOf("vendor" to vendor, "item" to "PO-1"))))
            assertEquals(sys.instancesOf("Order").single(), sys.get(r, "order"))
            assertEquals(0, sys.instancesOf("Memo").size)
            val r2 = sys.mustCommit("Receipt", emptyMap(), mapOf(
                "order" to listOf(mapOf("vendor" to vendor, "item" to "PO-2")),
                "memo" to listOf(mapOf("note" to "rush")),
            ))
            assertEquals(sys.instancesOf("Memo").single(), sys.get(r2, "memo"))
        }
    }

    @Test
    fun `codegen emits the reference type, the union's New interface, and the nested value`() {
        val src = Codegen.generate(BATCHES, "Batches")
        assertTrue("sealed interface OriginReference {" in src, src)
        assertTrue("data class Existing(val ref: Ref<Origin>) : OriginReference" in src, src)
        assertTrue("data class New(val value: NewOrigin) : OriginReference" in src, src)
        assertTrue("sealed interface NewOrigin {" in src, src)
        assertTrue("data class NewFermentation(" in src && ") : NewOrigin, NewValue {" in src, src)
        assertTrue("val origin: OriginReference," in src, src)
        assertTrue("val components: List<NewComponent> = emptyList()" in src, src)
        assertTrue("(origin as? OriginReference.Existing)?.let { put(\"origin\", system.idOf(it.ref)) }" in src, src)
        assertTrue("(origin as? OriginReference.New)?.let { put(\"origin\", listOf(it.value.asValue(system, reg))) }" in src, src)
    }

    // ── a reference to a new record elsewhere in the same act (Reference §6; OQ63 §6.2.2) ──

    private val PLATE = """
        act ContainerType { name: text, wellsPerContainer: integer }

        act ExperimentDesign {
            containerType: one ContainerType
            containerCount: integer
            replicates: integer
        } with { containers with { wells }, conditions }

        shape Condition { design: one ExperimentDesign, block: integer, value: decimal }
        shape Container { design: one ExperimentDesign, replicate: integer, position: integer }
        shape Well { container: one Container, position: integer, condition: one Condition }
    """.trimIndent()

    private fun VelleSystem.plateType(): Long = mustCommit("ContainerType", mapOf("name" to "96-well", "wellsPerContainer" to 96))

    @Test
    fun `the model names the fields that may point at a sibling`() {
        val model = Model(Parser.parse(PLATE))
        assertEquals(setOf("ExperimentDesign", "Container", "Well", "Condition"), model.closureShapes("ExperimentDesign"))
        val edges = model.closures.getValue("ExperimentDesign")
        val wells = edges.single { it.prop == "containers" }.children.single()
        assertEquals(listOf("condition"), model.siblingFields("ExperimentDesign", "Well", wells.children, wells.backRef).map { it.name })
        assertEquals(emptyList(), model.siblingFields("ExperimentDesign", "Condition", emptyList(), "design").map { it.name })
        assertEquals(emptyList(), model.siblingFields("ExperimentDesign", "ExperimentDesign", edges, null).map { it.name })
    }

    @Test
    fun `a well points at a condition in another branch, whatever the order of the branches`() {
        val sys = system(PLATE)
        sys.scope {
            val type = sys.plateType()
            val low = mapOf(VelleSystem.NEW_KEY to 1, "block" to 1, "value" to java.math.BigDecimal("0.1"))
            val high = mapOf(VelleSystem.NEW_KEY to 2, "block" to 2, "value" to java.math.BigDecimal("1.0"))
            // the containers branch is declared first and references the conditions branch: a forward reference
            val design = sys.mustCommit(
                "ExperimentDesign", mapOf("containerType" to type, "containerCount" to 1, "replicates" to 1),
                mapOf(
                    "containers" to listOf(mapOf("replicate" to 1, "position" to 1, "wells" to listOf(
                        mapOf("position" to 1, "condition" to NewRef(1)),
                        mapOf("position" to 2, "condition" to NewRef(2)),
                        mapOf("position" to 3, "condition" to NewRef(1)),
                    ))),
                    "conditions" to listOf(low, high),
                ),
            )
            val conditions = sys.instancesOf("Condition").sortedBy { (sys.get(it, "block") as Number).toLong() }
            assertEquals(2, conditions.size)
            val wells = sys.instancesOf("Well").sortedBy { (sys.get(it, "position") as Number).toLong() }
            assertEquals(listOf(conditions[0], conditions[1], conditions[0]), wells.map { sys.get(it, "condition") })
            assertEquals(design, sys.get(conditions[0], "design"))
            assertEquals(sys.instancesOf("Container").single(), sys.get(wells[0], "container"))
        }
    }

    @Test
    fun `a sibling reference may also be supplied by handle`() {
        val sys = system(PLATE)
        sys.scope {
            val type = sys.plateType()
            val first = sys.mustCommit("ExperimentDesign", mapOf("containerType" to type, "containerCount" to 1, "replicates" to 1),
                mapOf("conditions" to listOf(mapOf("block" to 1, "value" to java.math.BigDecimal("0.1")))))
            val existing = sys.instancesOf("Condition").single()
            sys.mustCommit("ExperimentDesign", mapOf("containerType" to type, "containerCount" to 1, "replicates" to 1),
                mapOf("containers" to listOf(mapOf("replicate" to 1, "position" to 1, "wells" to listOf(mapOf("position" to 1, "condition" to existing))))))
            assertEquals(existing, sys.get(sys.instancesOf("Well").single(), "condition"))
            first.hashCode()
        }
    }

    @Test
    fun `refusals - a reference to no value, a value created twice, a wrong shape, a cycle`() {
        val sys = system(PLATE)
        sys.scope {
            val type = sys.plateType()
            val fields = mapOf("containerType" to type, "containerCount" to 1, "replicates" to 1)
            val nowhere = sys.commit("ExperimentDesign", fields, mapOf("containers" to listOf(mapOf("replicate" to 1, "position" to 1,
                "wells" to listOf(mapOf("position" to 1, "condition" to NewRef(7)))))))
            assertTrue(assertIs<CommitResult.Refused>(nowhere).reason.contains("not among this act's values"), nowhere.toString())
            val twice = sys.commit("ExperimentDesign", fields, mapOf("conditions" to listOf(
                mapOf(VelleSystem.NEW_KEY to 1, "block" to 1, "value" to java.math.BigDecimal("0.1")),
                mapOf(VelleSystem.NEW_KEY to 1, "block" to 2, "value" to java.math.BigDecimal("0.2")))))
            assertTrue(assertIs<CommitResult.Refused>(twice).reason.contains("two creating positions"), twice.toString())
            val wrong = sys.commit("ExperimentDesign", fields, mapOf(
                "containers" to listOf(mapOf(VelleSystem.NEW_KEY to 1, "replicate" to 1, "position" to 1,
                    "wells" to listOf(mapOf("position" to 1, "condition" to NewRef(1)))))))
            assertTrue(assertIs<CommitResult.Refused>(wrong).reason.contains("not a new Container"), wrong.toString())
            assertEquals(0, sys.instancesOf("Well").size)

            val cyclic = system("""
                act Pairing { note: text } with { lefts, rights }
                shape Left { pairing: one Pairing, partner: one Right? }
                shape Right { pairing: one Pairing, partner: one Left? }
            """.trimIndent())
            val cycle = cyclic.commit("Pairing", mapOf("note" to "n"), mapOf(
                "lefts" to listOf(mapOf(VelleSystem.NEW_KEY to 1, "partner" to NewRef(2))),
                "rights" to listOf(mapOf(VelleSystem.NEW_KEY to 2, "partner" to NewRef(1)))))
            assertTrue(assertIs<CommitResult.Refused>(cycle).reason.contains("cycle"), cycle.toString())
            // one way round is fine
            cyclic.mustCommit("Pairing", mapOf("note" to "n"), mapOf(
                "lefts" to listOf(mapOf(VelleSystem.NEW_KEY to 1)),
                "rights" to listOf(mapOf("partner" to NewRef(1)))))
            assertEquals(cyclic.instancesOf("Left").single(), cyclic.get(cyclic.instancesOf("Right").single(), "partner"))
        }
    }

    @Test
    fun `codegen types a sibling field with the reference type and resolves it in the commit function`() {
        val src = Codegen.generate(PLATE, "Plate")
        assertTrue("sealed interface ConditionReference {" in src, src)
        assertTrue("data class New(val value: NewCondition) : ConditionReference" in src, src)
        val newWell = src.substringAfter("data class NewWell(").substringBefore(") : ")
        assertTrue("val condition: ConditionReference," in newWell, newWell)
        assertTrue("(condition as? ConditionReference.New)?.let { put(\"condition\", NewRef(reg.tokenOf(it.value))) }" in src, src)
        assertTrue("conditions.forEach { reg.add(it); it.register(reg) }" in src, src)
        assertTrue(Regex("""boundary\("\w+", input\)\.carrying\(input\)""").containsMatchIn(src), src)
        // a field no closure shape satisfies keeps the plain handle
        val newDesign = src.substringAfter("data class NewExperimentDesign(").substringBefore(") : ")
        assertTrue("val containerType: Ref<ContainerType>," in newDesign, newDesign)
    }

    // ── a `many` field whose elements are handles or new values (Reference §6) ──

    private val PLATE_MANY = PLATE.replace("condition: one Condition", "conditions: many Condition")

    @Test
    fun `a many field mixes handles and the act's own new values, and stays a set`() {
        val sys = system(PLATE_MANY)
        sys.scope {
            val type = sys.plateType()
            val fields = mapOf("containerType" to type, "containerCount" to 1, "replicates" to 1)
            sys.mustCommit("ExperimentDesign", fields, mapOf("conditions" to listOf(mapOf("block" to 1, "value" to java.math.BigDecimal("0.1")))))
            val existing = sys.instancesOf("Condition").single()
            val design = sys.mustCommit("ExperimentDesign", fields, mapOf(
                "containers" to listOf(mapOf("replicate" to 1, "position" to 1, "wells" to listOf(
                    mapOf("position" to 1, "conditions" to listOf(NewRef(1), existing, NewRef(2))),
                    mapOf("position" to 2, "conditions" to listOf(NewRef(2))),
                    mapOf("position" to 3, "conditions" to emptyList<Any>()),
                ))),
                "conditions" to listOf(
                    mapOf(VelleSystem.NEW_KEY to 1, "block" to 1, "value" to java.math.BigDecimal("0.5")),
                    mapOf(VelleSystem.NEW_KEY to 2, "block" to 2, "value" to java.math.BigDecimal("1.0"))),
            ))
            val mine = sys.instancesOf("Condition").filter { sys.get(it, "design") == design }.sortedBy { (sys.get(it, "block") as Number).toLong() }
            assertEquals(2, mine.size)
            val wells = sys.instancesOf("Well").sortedBy { (sys.get(it, "position") as Number).toLong() }
            assertEquals(setOf(mine[0], existing, mine[1]), (sys.get(wells[0], "conditions") as List<*>).toSet())
            assertEquals(listOf(mine[1]), sys.get(wells[1], "conditions"))
            assertEquals(emptyList<Long>(), sys.get(wells[2], "conditions"))

            val dup = sys.commit("ExperimentDesign", fields, mapOf(
                "containers" to listOf(mapOf("replicate" to 1, "position" to 1, "wells" to listOf(mapOf("position" to 1, "conditions" to listOf(NewRef(1), NewRef(1)))))),
                "conditions" to listOf(mapOf(VelleSystem.NEW_KEY to 1, "block" to 1, "value" to java.math.BigDecimal("0.5")))))
            assertTrue(assertIs<CommitResult.Refused>(dup).reason.contains("duplicate"), dup.toString())
        }
    }

    @Test
    fun `codegen types a many sibling field as a list of references`() {
        val src = Codegen.generate(PLATE_MANY, "Plate")
        val newWell = src.substringAfter("data class NewWell(").substringBefore(") : ")
        assertTrue("val conditions: List<ConditionReference>," in newWell, newWell)
        assertTrue("put(\"conditions\", conditions.map { when (it) { is ConditionReference.Existing -> system.idOf(it.ref); is ConditionReference.New -> NewRef(reg.tokenOf(it.value)) } })" in src, src)
    }
}
