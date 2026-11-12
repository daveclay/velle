package velle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Walks (Reference §7, "Walks"): `walk R`, `walk R for N steps`, `walk R until P`.
 * The parser's reading of the contextual words, the validator's checks, and the
 * runtime over a social ring, a parent chain, and the loop prohibition that reads
 * a named walk.
 */
class WalkTest {

    private fun assertHas(code: String, source: String) {
        val diags = Validator.validate(source.trimIndent())
        assertTrue(diags.any { it.code == code }, "expected a $code, got: $diags")
    }

    private fun assertClean(source: String) {
        val diags = Validator.validate(source.trimIndent())
        assertTrue(diags.isEmpty(), "expected no diagnostics, got: $diags")
    }

    private fun system(src: String): VelleSystem {
        val diags = Validator.validate(src.trimIndent())
        check(diags.isEmpty()) { diags.toString() }
        return VelleSystem(Model(Parser.parse(src.trimIndent())))
    }

    private fun VelleSystem.mustCommit(shape: String, vararg fields: Pair<String, Any?>): Long {
        val r = commit(shape, fields.toMap())
        return assertIs<CommitResult.Accepted>(r, "commit of $shape refused: $r").id
    }

    private fun coll(v: Any?): List<*> = assertIs<List<*>>(v)

    // ── the parser ──────────────────────────────────────────────────────────

    private fun derived(src: String, shape: String, name: String): Expr =
        (Parser.parse(src.trimIndent()).filterIsInstance<ShapeDecl>().single { it.name == shape }
            .members.filterIsInstance<DerivedProp>().single { it.name == name }).expr

    @Test
    fun `the three forms parse`() {
        val src = """
            act Kind {
                name: text
                parent: one Kind?
                kids: many Kind
                hasLocation: boolean
                all: many Kind = walk kids
                near: many Kind = walk kids for 2 steps
                holder: one Kind? = walk parent until hasLocation
                verified: many Kind = (walk kids where hasLocation)
            }
        """
        assertEquals(WalkExpr("kids"), derived(src, "Kind", "all"))
        assertEquals(WalkExpr("kids", steps = 2), derived(src, "Kind", "near"))
        assertEquals(WalkExpr("parent", until = PathExpr("hasLocation")), derived(src, "Kind", "holder"))
        val filtered = assertIs<SetExpr>(derived(src, "Kind", "verified"))
        assertEquals(WalkExpr("kids"), filtered.collection.bindings.single().source)
        assertEquals(PathExpr("hasLocation"), filtered.collection.where)
    }

    @Test
    fun `walk, until, and steps stay ordinary identifiers elsewhere`() {
        assertClean("""
            act Route {
                walk: text
                until: text
                steps: integer
            }
            shape Hilly = Route where walk contains "hill" and steps > 3 and until is not empty
        """)
    }

    @Test
    fun `a walk prints back as it was written`() {
        assertEquals("walk kids for 2 steps", Printer.expr(WalkExpr("kids", steps = 2)))
        assertEquals("walk parent until hasLocation", Printer.expr(WalkExpr("parent", until = PathExpr("hasLocation"))))
    }

    // ── the validator ───────────────────────────────────────────────────────

    @Test
    fun `the relationship must exist, be a relationship, and point back at the shape`() {
        assertHas("F1", """
            act Profile { name: text, network: many Profile = walk pals }
        """)
        assertHas("F2", """
            act Profile { name: text, network: many Profile = walk name }
        """)
        assertHas("F2", """
            act Company { name: text }
            act Profile { name: text, employer: one Company?, network: many Profile = walk employer }
        """)
    }

    @Test
    fun `until walks a one`() {
        assertHas("F2", """
            act Profile {
                name: text
                verified: boolean
                friends: many Profile
                sponsor: one Profile? = walk friends until verified
            }
        """)
    }

    @Test
    fun `the depth is a number that does something`() {
        assertHas("F2", """
            act Profile { friends: many Profile, nearby: many Profile = walk friends for 0 steps }
        """)
        val advisories = Validator.advisories("""
            act Profile { friends: many Profile, nearby: many Profile = walk friends for 1 steps }
        """.trimIndent())
        assertTrue(advisories.any { it.code == "A2" }, "expected an A2, got: $advisories")
    }

    @Test
    fun `a walk property declares the type the walk has`() {
        assertHas("F2", """
            act Profile { friends: many Profile, network: one Profile? = walk friends }
        """)
        assertHas("F2", """
            act Kind { parent: one Kind?, hasLocation: boolean, holder: many Kind = walk parent until hasLocation }
        """)
        assertHas("F2", """
            act Kind { parent: one Kind?, hasLocation: boolean, holder: one Kind = walk parent until hasLocation }
        """)
        assertHas("F2", """
            act Profile { friends: many Profile, network: text = walk friends }
        """)
    }

    @Test
    fun `a record may be tested for membership in a walk, a value may not`() {
        assertClean("""
            act Kind { name: text, parent: one Kind?, ancestors: many Kind = walk parent }
            never (Kind where this in ancestors)
        """)
        assertHas("F2", """
            act Kind { name: text, parent: one Kind?, ancestors: many Kind = walk parent }
            never (Kind where name in ancestors)
        """)
    }

    @Test
    fun `a walk is not a definition cycle`() {
        assertClean("""
            act Kind {
                name: text
                parent: one Kind?
                hasLocation: boolean
                ancestors: many Kind = walk parent
                locationHolder: one Kind? = walk parent until hasLocation
            }
        """)
    }

    // ── the runtime ─────────────────────────────────────────────────────────

    private val ring = """
        act Profile {
            name: text
            friends: many Profile
            network: many Profile = walk friends
            nearby: many Profile = walk friends for 2 steps
        }
        transient act Befriend {
            who: one Profile
            friend: one Profile
        }
        rule ApplyBefriend when Befriend {
            this.who.friends = this.who.friends + this.friend
        }
    """

    @Test
    fun `a ring is walked once, and holds the start record because a step reaches it`() {
        val sys = system(ring)
        sys.scope {
            val alice = sys.mustCommit("Profile", "name" to "Alice", "friends" to emptyList<Long>())
            val bob = sys.mustCommit("Profile", "name" to "Bob", "friends" to listOf(alice))
            val chris = sys.mustCommit("Profile", "name" to "Chris", "friends" to listOf(bob))
            assertEquals(setOf(bob, alice), coll(sys.get(chris, "network")).toSet(), "a chain: the start is not reached")
            sys.mustCommit("Befriend", "who" to alice, "friend" to chris)
            assertEquals(setOf(bob, alice, chris), coll(sys.get(chris, "network")).toSet())
            assertEquals(setOf(chris, bob, alice), coll(sys.get(alice, "network")).toSet())
            assertEquals(3, coll(sys.get(alice, "network")).size, "each record once, the start among them")
        }
    }

    @Test
    fun `a depth cuts the walk off`() {
        val sys = system(ring)
        sys.scope {
            val a = sys.mustCommit("Profile", "name" to "A", "friends" to emptyList<Long>())
            val b = sys.mustCommit("Profile", "name" to "B", "friends" to listOf(a))
            val c = sys.mustCommit("Profile", "name" to "C", "friends" to listOf(b))
            val d = sys.mustCommit("Profile", "name" to "D", "friends" to listOf(c))
            assertEquals(listOf(c, b, a), coll(sys.get(d, "network")))
            assertEquals(listOf(c, b), coll(sys.get(d, "nearby")))
        }
    }

    @Test
    fun `a walk holds its start when a step reaches it, so this in a walk is true on a ring`() {
        // the loop prohibition `never (S where this in W)` reads exactly this
        val sys = system("""
            act Profile {
                name: text
                friends: many Profile
                network: many Profile = walk friends
                reachesItself: boolean = if this in network then true else false
            }
            transient act Befriend {
                who: one Profile
                friend: one Profile
            }
            rule ApplyBefriend when Befriend {
                this.who.friends = this.who.friends + this.friend
            }
        """)
        sys.scope {
            val alice = sys.mustCommit("Profile", "name" to "Alice", "friends" to emptyList<Long>())
            val bob = sys.mustCommit("Profile", "name" to "Bob", "friends" to listOf(alice))
            assertEquals(false, sys.get(alice, "reachesItself"), "a chain: no step reaches the start")
            sys.mustCommit("Befriend", "who" to alice, "friend" to bob)
            assertEquals(setOf(bob, alice), coll(sys.get(alice, "network")).toSet(), "Alice is reached from Alice through Bob")
            assertEquals(true, sys.get(alice, "reachesItself"), "the start is in its own walk on a ring")
            assertEquals(true, sys.get(bob, "reachesItself"))
        }
    }

    private val kinds = """
        act Kind {
            name: text
            parent: one Kind?
            hasLocation: boolean
            ancestors: many Kind = walk parent
            locationHolder: one Kind? = walk parent until hasLocation
        }
        never (Kind where this in ancestors)
    """

    @Test
    fun `until answers the record itself first, then the nearest up the chain, else none`() {
        val sys = system(kinds)
        sys.scope {
            val site = sys.mustCommit("Kind", "name" to "site", "hasLocation" to true)
            val plot = sys.mustCommit("Kind", "name" to "plot", "parent" to site, "hasLocation" to false)
            val specimen = sys.mustCommit("Kind", "name" to "specimen", "parent" to plot, "hasLocation" to false)
            val orphan = sys.mustCommit("Kind", "name" to "orphan", "hasLocation" to false)
            assertEquals(site, sys.get(site, "locationHolder"), "the record itself is checked first")
            assertEquals(site, sys.get(specimen, "locationHolder"))
            assertNull(sys.get(orphan, "locationHolder"))
            assertEquals(listOf(plot, site), coll(sys.get(specimen, "ancestors")))
            assertEquals(emptyList<Long>(), coll(sys.get(site, "ancestors")))
        }
    }

    @Test
    fun `the loop prohibition over a creation-fixed relationship validates and holds over a chain`() {
        val sys = system(kinds)
        sys.scope {
            val site = sys.mustCommit("Kind", "name" to "site", "hasLocation" to true)
            val plot = sys.mustCommit("Kind", "name" to "plot", "parent" to site, "hasLocation" to false)
            sys.mustCommit("Kind", "name" to "specimen", "parent" to plot, "hasLocation" to false)
            assertEquals(3, sys.instancesOf("Kind").size)
        }
    }

    @Test
    fun `the loop prohibition over a relationship a rule writes is a guarantee V10 cannot yet prove`() {
        // the finding recorded in OQ76: with a writer, the prohibition that would refuse the
        // commit closing a ring is refused by V10 itself, as every guarantee is in v0
        assertHas("V10", """
            act Kind {
                name: text
                parent: one Kind?
                ancestors: many Kind = walk parent
            }
            transient act Reparent {
                kind: one Kind
                newParent: one Kind
            }
            rule ApplyReparent when Reparent {
                this.kind.parent = this.newParent
            }
            never (Kind where this in ancestors)
        """)
    }

    @Test
    fun `a criterion lives in a derived relationship, and a walk follows it`() {
        val sys = system("""
            act Person {
                name: text
                mother: one Person?
                father: one Person?
                parents: many Person = (Person where id == this.mother or id == this.father)
                ancestors: many Person = walk parents
                maternalAncestors: many Person = if mother is some then mother.ancestors + mother else empty
            }
        """)
        sys.scope {
            val grandma = sys.mustCommit("Person", "name" to "grandma")
            val grandpa = sys.mustCommit("Person", "name" to "grandpa")
            val mum = sys.mustCommit("Person", "name" to "mum", "mother" to grandma, "father" to grandpa)
            val dadsMum = sys.mustCommit("Person", "name" to "dad's mum")
            val dad = sys.mustCommit("Person", "name" to "dad", "mother" to dadsMum)
            val kid = sys.mustCommit("Person", "name" to "kid", "mother" to mum, "father" to dad)
            assertEquals(setOf(mum, dad, grandma, grandpa, dadsMum), coll(sys.get(kid, "ancestors")).toSet())
            assertEquals(setOf(mum, grandma, grandpa), coll(sys.get(kid, "maternalAncestors")).toSet())
            assertEquals(emptyList<Long>(), coll(sys.get(grandma, "maternalAncestors")), "no mother: nothing on her side")
        }
    }

    @Test
    fun `a walk may be filtered and walked over in a rule`() {
        val sys = system("""
            act Profile {
                name: text
                verified: boolean
                friends: many Profile
                verifiedFriends: many Profile = (friends where verified)
                trustedNetwork: many Profile = walk verifiedFriends
                verifiedInNetwork: many Profile = (walk friends where verified)
            }
        """)
        sys.scope {
            val a = sys.mustCommit("Profile", "name" to "A", "verified" to true, "friends" to emptyList<Long>())
            val b = sys.mustCommit("Profile", "name" to "B", "verified" to false, "friends" to listOf(a))
            val c = sys.mustCommit("Profile", "name" to "C", "verified" to true, "friends" to listOf(b))
            val d = sys.mustCommit("Profile", "name" to "D", "verified" to true, "friends" to listOf(c))
            // the chain D -> C -> B -> A: walking only verified friends stops at B, which is not verified
            assertEquals(listOf(c), coll(sys.get(d, "trustedNetwork")))
            // walking everyone and then keeping the verified ones reaches A past B
            assertEquals(setOf(c, a), coll(sys.get(d, "verifiedInNetwork")).toSet())
        }
    }
}
