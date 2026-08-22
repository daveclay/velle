package velle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The exposure closure (README §6, "Inline part creation"; checks V22, V1's
 * closure extension): `expose ... with` — parsing, edge resolution, the V22
 * refusals, the V1 self-pair refusal, the runtime's one-commit landing, and
 * the generated input types.
 */
class ClosureTest {

    private val ORDERS = """
        shape Customer {
            name: text
        }

        shape Product {
            sku: text
        }

        shape Order {
            customer: one Customer
            placedOn: Date
        }

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

        expose Customer
        expose Product
        expose Order with { orderLines with { customizations } }

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
            shape A { t: text }
            shape B { a: one A, t: text }
            shape D { a: one A, t: text }
            expose A with bs, ds
            """.trimIndent()
        )
        val expose = decls.filterIsInstance<ExposeDecl>().single()
        assertEquals(listOf("bs", "ds"), expose.with.map { it.name })

        val nested = Parser.parse(
            """
            shape A { t: text }
            shape B { a: one A, t: text }
            shape C { b: one B, t: text }
            expose A with {
                bs with { cs }
            }
            """.trimIndent()
        ).filterIsInstance<ExposeDecl>().single()
        assertEquals("bs", nested.with.single().name)
        assertEquals("cs", nested.with.single().children.single().name)
    }

    @Test
    fun `with stays an ordinary identifier outside the expose position`() {
        val decls = Parser.parse(
            """
            shape A { with: text }
            expose A
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
            shape A { t: text }
            expose A with nonsense
            """.trimIndent()
        )
        assertTrue(d.any { it.code == "V22" && "no such collection" in it.message }, d.toString())
    }

    @Test
    fun `an arbitrary-predicate view cannot ride a closure`() {
        val d = diagnostics(
            """
            shape A {
                t: text
                bigBs: many B = (B where flagged == true)
            }
            shape B {
                a: one A
                flagged: boolean
            }
            expose A with bigBs
            """.trimIndent()
        )
        assertTrue(d.any { it.code == "V22" && "arbitrary-predicate" in it.message }, d.toString())
    }

    @Test
    fun `the recognized inverse-form view resolves and pins the back-reference`() {
        // two same-target `one` fields: no inverse is inferred (V19's ambiguity
        // posture), and the declared view of recognized form pins the back-reference
        val spec = """
            shape Account {
                name: text
                outgoing: many Transfer = (Transfer where source == this)
                incoming: many Transfer = (Transfer where target == this)
            }
            shape Transfer {
                source: one Account
                target: one Account
                amount: decimal
            }
            expose Account with outgoing
        """.trimIndent()
        assertTrue(diagnostics(spec).isEmpty(), diagnostics(spec).toString())
        val model = Model(Parser.parse(spec))
        val edge = model.closures.getValue("Account").single()
        assertEquals("Transfer", edge.partShape)
        assertEquals("source", edge.backRef)

        // no view declared — V22 demands one rather than guessing
        val spec2 = """
            shape Account { name: text }
            shape Transfer {
                source: one Account
                target: one Account
                amount: decimal
            }
            expose Account with outgoing
        """.trimIndent()
        assertTrue(diagnostics(spec2).any { it.code == "V22" }, diagnostics(spec2).toString())
    }

    @Test
    fun `a transient container refuses a closure`() {
        val d = diagnostics(
            """
            shape A { t: text }
            shape B { a: one A, t: text }
            expose transient A with bs
            """.trimIndent()
        )
        assertTrue(d.any { it.code == "V22" && "OQ43" in it.message }, d.toString())
    }

    @Test
    fun `an m2m inverse cannot ride a closure`() {
        val d = diagnostics(
            """
            shape Course { title: text }
            shape Student {
                name: text
                courses: many Course
            }
            expose Course with students
            """.trimIndent()
        )
        assertTrue(d.any { it.code == "V22" }, d.toString())
    }

    // ── V1's closure extension: parts never write the container ─────────────

    @Test
    fun `a part-triggered rule assigning through the back-reference is refused`() {
        val d = diagnostics(
            """
            shape Order {
                flagged: boolean
            }
            shape OrderLine {
                order: one Order
                quantity: integer
            }
            expose Order with orderLines
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

    @Test
    fun `a part-triggered rule fires once per inline part in the act's transaction`() {
        val sys = system()
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

    @Test
    fun `nested closure parts land pointing at their own level`() {
        val sys = system()
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

    @Test
    fun `identical inline part values are a bag - two distinct instances`() {
        val sys = system()
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

    @Test
    fun `a committer-supplied back-reference is refused`() {
        val sys = system()
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

    @Test
    fun `parts on an undeclared edge are refused`() {
        val sys = system()
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

    @Test
    fun `a refused closure rolls back whole - no partial landing`() {
        val sys = system()
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

    // ── the generated surface ────────────────────────────────────────────────

    @Test
    fun `codegen emits nested input types and the extended commit function`() {
        val src = Codegen.generate(ORDERS, "Orders")
        assertTrue("data class NewOrderLine(" in src, src)
        assertTrue("data class NewCustomization(" in src, src)
        assertTrue("val customizations: List<NewCustomization> = emptyList()" in src, src)
        // the projection: no back-reference, no id, on the input types
        val newLine = src.substringAfter("data class NewOrderLine(").substringBefore(") {")
        assertTrue("order" !in newLine, newLine)
        assertTrue("val product: ProductView" in newLine, newLine)
        // the commit function takes the closure edge, absent by default
        assertTrue("orderLines: List<NewOrderLine> = emptyList()" in src, src)
    }
}
