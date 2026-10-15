package velle.generated.gallery

import java.sql.Connection
import velle.generated.*
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import velle.Commit
import velle.generated.GalleryStoreOverGeneric
import velle.generated.GalleryStoreResolver
import velle.generated.GallerySystem
import velle.sqlite.SqliteStore

/**
 * The gallery flows over real storage. With a store connected the runtime
 * keeps no state of its own: every transaction opens a fresh snapshot and
 * faults rows in through the resolver (evaluation.md U1), so each
 * inherited scenario is the in-memory scenario re-run against SQLite — once
 * through the generic protocol, once through the generated typed store
 * surface (GalleryStore).
 */
class GalleryFlowOverSqliteTest : GalleryFlowTest() {
    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    @AfterTest
    fun close() = conn.close()

    override fun newSystem(): GallerySystem = GallerySystem().also { SqliteStore.attach(it.system, conn) }
}

class GalleryFlowOverTypedStoreTest : GalleryFlowTest() {
    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    @AfterTest
    fun close() = conn.close()

    override fun newSystem(): GallerySystem = GallerySystem().also { sys ->
        val backend = SqliteStore(sys.system.model, conn).apply { createSchema() }
        val store = GalleryStoreOverGeneric(backend, sys.system.model)
        sys.system.connect(GalleryStoreResolver(store, sys.system.model), backend)
    }
}

/**
 * What only storage can show (OQ44, "Hydration and the store"): a union-typed
 * reference (Reference §9) persisted with its concrete shape and read back in a
 * second process — the polymorphic association, the inverse inferred through
 * it, and the join read telling Post#1 from Photo#1 behind one key.
 */
class GalleryPersistenceTest {
    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    @AfterTest
    fun close() = conn.close()

    /** A fresh process over the same database: handles are session-local, rows are not. */
    private fun process(): GallerySystem = GallerySystem().also { SqliteStore.attach(it.system, conn) }

    private fun accept(r: Commit<*>) = assertIs<Commit.Accepted<*>>(r, "refused: $r")

    private fun rows(sql: String): List<List<Any?>> =
        conn.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                buildList {
                    while (rs.next()) add((1..rs.metaData.columnCount).map { i ->
                        rs.getObject(i).let { if (it is Number) it.toLong() else it }
                    })
                }
            }
        }

    @Test
    fun `a union-typed reference is stored with its concrete shape, and the join read matches on both`() {
        val first = process()
        accept(first.commit(NewMember("Ada")))
        val ada = first.members().last()
        accept(first.commit(NewMember("Grace")))
        val grace = first.members().last()
        accept(first.commit(NewPost(ada, "hello")))
        val post = first.posts().last()
        accept(first.commit(NewPhoto(ada, "sunset")))
        val photo = first.photos().last()
        accept(first.commit(NewComment(post, grace, "hi")))
        accept(first.commit(NewComment(photo, grace, "lovely")))
        accept(first.commit(NewComment(photo, ada, "thanks")))

        // Post#1 and Photo#1 share a key; the discriminator column tells them apart
        assertEquals(
            listOf(listOf(1L, "Post"), listOf(1L, "Photo"), listOf(1L, "Photo")),
            rows("""SELECT target, target__shape FROM Comment ORDER BY id"""),
        )

        // a second process enters from the storage side, by key
        val second = process()
        val photo2 = second.read.photo(1L)
        val post2 = second.read.post(1L)
        val grace2 = second.read.member(2L)
        assertEquals(2, second.read(photo2).comments.size, "the join read matched key and shape, not key alone")
        assertEquals(1, second.read(post2).comments.size)
        assertEquals(2, second.read(photo2).commentCount)
        assertTrue(with(second) { second.read(photo2).isPopular })
        assertEquals(listOf(photo2), second.popularityNotices().map { (second.read(second.read(it).content) as? Photo) })
        assertEquals(1, second.postComments().size)
        assertEquals(2, second.photoComments().size)
        assertEquals(setOf(post2.key, photo2.key), second.contents().map { it.key }.toSet())

        // entry into Popular is decided against stored rows: the post's second
        // comment is an entry, the photo's third is not
        accept(second.commit(NewComment(post2, grace2, "again")))
        assertEquals(setOf(post2.key, photo2.key), second.popularityNotices().map { second.read(it).content.key }.toSet())
        accept(second.commit(NewComment(photo2, grace2, "three")))
        assertEquals(2, second.popularityNotices().size)
    }

    @Test
    fun `the never's narrowing through the union is checked over stored rows`() {
        val first = process()
        accept(first.commit(NewMember("Ada")))
        accept(first.commit(NewPost(first.members().last(), "hello")))
        accept(first.commit(NewPhoto(first.members().last(), "sunset")))

        val second = process()
        val ada = second.read.member(1L)
        val post = second.read.post(1L)
        val photo = second.read.photo(1L)
        val refused = second.commit(NewComment(post, ada, "me again"))
        assertIs<Commit.Refused<*>>(refused)
        assertEquals(listOf(listOf(0L)), rows("""SELECT COUNT(*) FROM Comment"""), "a refused act reaches no row")
        accept(second.commit(NewComment(photo, ada, "my own photo")))
        assertEquals(listOf(listOf(1L)), rows("""SELECT COUNT(*) FROM Comment"""))
    }
}

