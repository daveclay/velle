package velle.generated.gallery

import velle.generated.GallerySystem
import velle.postgres.PostgresStore
import velle.postgres.PostgresTesting
import kotlin.test.BeforeTest

/** The union flows over PostgreSQL, through the generic protocol; skipped without Docker. */
class GalleryFlowOverPostgresTest : GalleryFlowTest() {

    @BeforeTest
    fun docker() = PostgresTesting.assumeAvailable()


    override fun newSystem(): GallerySystem {
        return GallerySystem().also { PostgresStore.attach(it.system, PostgresTesting.dataSource()) }
    }
}
