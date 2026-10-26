package velle.generated.gallery

import velle.generated.GallerySystem
import velle.postgres.PostgresStore
import velle.postgres.PostgresTesting
import java.sql.Connection
import kotlin.test.AfterTest
import kotlin.test.BeforeTest

/** The union flows over PostgreSQL, through the generic protocol; skipped without Docker. */
class GalleryFlowOverPostgresTest : GalleryFlowTest() {
    private lateinit var conn: Connection

    @BeforeTest
    fun docker() = PostgresTesting.assumeAvailable()

    @AfterTest
    fun close() { if (::conn.isInitialized) conn.close() }

    override fun newSystem(): GallerySystem {
        conn = PostgresTesting.connection()
        return GallerySystem().also { PostgresStore.attach(it.system, conn) }
    }
}
