package velle.sqlite

import org.sqlite.SQLiteDataSource
import velle.Model
import velle.sql.SqlStore
import velle.sql.SqlStoreConformance
import velle.sql.SqlTypeBinding
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.IdentityHashMap
import javax.sql.DataSource

/**
 * The SQL layer's scenarios over SQLite. The database is a temporary file in WAL
 * mode, so each unit of work really takes a connection of its own and a reader
 * keeps its snapshot while a writer commits, as a deployment's file database
 * would; an in-memory database lives in one connection and cannot show that.
 */
class SqliteConformanceTest : SqlStoreConformance() {

    private val files = IdentityHashMap<DataSource, Path>()

    override fun dataSource(): DataSource {
        val file = Files.createTempFile("velle-conformance-", ".db")
        Files.delete(file)
        return SQLiteDataSource().apply {
            url = "jdbc:sqlite:${file}?journal_mode=WAL"
            files[this] = file
        }
    }

    override fun release(dataSource: DataSource) {
        val file = files.remove(dataSource) ?: return
        for (suffix in listOf("", "-wal", "-shm")) Files.deleteIfExists(Path.of("$file$suffix"))
    }

    override fun store(model: Model, dataSource: DataSource, bindings: List<SqlTypeBinding<*>>, lockTimeout: Duration): SqlStore =
        SqliteStore(model, dataSource, bindings)
}
