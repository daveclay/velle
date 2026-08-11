package velle.app

import velle.CommitCallback
import velle.CommitSet
import velle.Model
import velle.Row
import velle.StateResolver
import velle.StoredProp
import velle.TimestampProp
import velle.VType
import java.math.BigDecimal
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate

/**
 * Engineer-owned storage for the billing system, backed by SQLite — the spike's
 * stand-in for the resolver interfaces and commit callback the transpiler would
 * generate (working-docs/investigate_runtime.md §2–3). Hand-written, but driven
 * entirely by the compiled Model: one table per non-transient shape, one column
 * per stored or timestamp field. Derived properties have no columns — they
 * recompute from these rows on read.
 *
 * Column encoding: references and integer/long as INTEGER, decimal/double as
 * TEXT (exact BigDecimal round-trip), boolean as INTEGER 0/1, Date/DateTime as
 * ISO-8601 TEXT.
 */
class SqliteStore(private val model: Model, private val conn: Connection) : StateResolver, CommitCallback {

    private data class Col(val name: String, val type: VType)

    private fun columnsOf(shape: String): List<Col> =
        model.shapes.getValue(shape).members.mapNotNull { m ->
            when (m) {
                is StoredProp -> Col(m.name, model.typeOf(m.type))
                is TimestampProp -> Col(m.name, VType.DateTimeT)
                else -> null
            }
        }

    private val persistedShapes = model.shapes.keys.filter { it !in model.transients }

    fun createSchema() {
        conn.createStatement().use { st ->
            for (shape in persistedShapes) {
                val cols = columnsOf(shape).joinToString("") { ", \"${it.name}\" ${sqlType(it.type)}" }
                st.execute("""CREATE TABLE IF NOT EXISTS "$shape" (id INTEGER PRIMARY KEY$cols)""")
            }
        }
    }

    private fun sqlType(t: VType): String = when (t) {
        is VType.Optional -> sqlType(t.inner)
        is VType.Inst -> "INTEGER"
        is VType.Num -> if (t.name == "integer" || t.name == "long") "INTEGER" else "TEXT"
        VType.Bool -> "INTEGER"
        else -> "TEXT"
    }

    // ── the resolver: the three read questions (investigate_runtime.md §2) ───

    override fun maxId(): Long = persistedShapes.maxOf { shape ->
        conn.prepareStatement("""SELECT COALESCE(MAX(id), 0) FROM "$shape"""").use { ps ->
            ps.executeQuery().use { rs -> rs.getLong(1) }
        }
    }

    override fun fetchById(shape: String, id: Long): Row? =
        select(shape, """WHERE id = ?""") { it.setLong(1, id) }.singleOrNull()

    override fun fetchAll(shape: String): List<Row> = select(shape, "")

    override fun fetchReferencing(shape: String, field: String, targetId: Long): List<Row> =
        select(shape, """WHERE "$field" = ?""") { it.setLong(1, targetId) }

    private fun select(shape: String, where: String, bind: (PreparedStatement) -> Unit = {}): List<Row> {
        val cols = columnsOf(shape)
        return conn.prepareStatement("""SELECT * FROM "$shape" $where""").use { ps ->
            bind(ps)
            ps.executeQuery().use { rs ->
                buildList { while (rs.next()) add(readRow(shape, cols, rs)) }
            }
        }
    }

    private fun readRow(shape: String, cols: List<Col>, rs: ResultSet): Row {
        val fields = buildMap {
            for (c in cols) {
                if (rs.getObject(c.name) == null) continue
                put(c.name, readColumn(c.type, c.name, rs))
            }
        }
        return Row(shape, rs.getLong("id"), fields)
    }

    private fun readColumn(t: VType, name: String, rs: ResultSet): Any = when (t) {
        is VType.Optional -> readColumn(t.inner, name, rs)
        is VType.Inst -> rs.getLong(name)
        is VType.Num ->
            if (t.name == "integer" || t.name == "long") rs.getLong(name)
            else BigDecimal(rs.getString(name))
        VType.Bool -> rs.getInt(name) != 0
        VType.DateT -> LocalDate.parse(rs.getString(name))
        VType.DateTimeT -> Instant.parse(rs.getString(name))
        else -> rs.getString(name)
    }

    // ── the commit callback: the transaction's mutation set, atomically ──────

    /**
     * Runs inside the runtime's envelope: everything the transaction created or
     * assigned lands in one SQLite transaction, and a failure here rolls the
     * whole Velle commit back (in-envelope failure, investigate_runtime.md §3).
     */
    override fun onCommit(commit: CommitSet) {
        conn.autoCommit = false
        try {
            for (row in commit.created) insert(row)
            for (a in commit.assigned) update(a)
            conn.commit()
        } catch (e: Exception) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = true
        }
    }

    private fun insert(row: Row) {
        val cols = columnsOf(row.shape)
        val names = listOf("id") + cols.map { it.name }
        val sql = """INSERT INTO "${row.shape}" (${names.joinToString(", ") { "\"$it\"" }}) """ +
            """VALUES (${names.joinToString(", ") { "?" }})"""
        conn.prepareStatement(sql).use { ps ->
            ps.setLong(1, row.id)
            cols.forEachIndexed { i, c -> ps.setObject(i + 2, writeColumn(c.type, row.fields[c.name])) }
            ps.executeUpdate()
        }
    }

    private fun update(a: CommitSet.Assign) {
        val col = columnsOf(a.shape).first { it.name == a.field }
        conn.prepareStatement("""UPDATE "${a.shape}" SET "${a.field}" = ? WHERE id = ?""").use { ps ->
            ps.setObject(1, writeColumn(col.type, a.value))
            ps.setLong(2, a.id)
            ps.executeUpdate()
        }
    }

    private fun writeColumn(t: VType, v: Any?): Any? = when {
        v == null -> null
        t is VType.Optional -> writeColumn(t.inner, v)
        t is VType.Inst -> v as Long
        t is VType.Num ->
            if (t.name == "integer" || t.name == "long") (v as BigDecimal).longValueExact()
            else (v as BigDecimal).toPlainString()
        t == VType.Bool -> if (v as Boolean) 1 else 0
        else -> v.toString() // Date/DateTime ISO-8601, text as-is
    }
}
