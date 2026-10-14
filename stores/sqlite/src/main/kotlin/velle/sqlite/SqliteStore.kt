package velle.sqlite

import velle.CommitCallback
import velle.CommitSet
import velle.Model
import velle.QConst
import velle.QF
import velle.Ref
import velle.RowRef
import velle.Row
import velle.StateResolver
import velle.StoreKey
import velle.StoredProp
import velle.TEXT_OPERATORS
import velle.TimestampProp
import velle.VType
import velle.VelleSystem
import java.math.BigDecimal
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate

/**
 * A generic, model-driven store over SQLite: the framework role in README §22's
 * architecture, implementing the runtime's resolver and commit-callback
 * protocol (compiler/.../Hydration.kt; evaluation.md "The universal
 * transaction") for any spec. It contains no business names — every example's
 * output module runs its scenarios over this one class — and it is the
 * existence proof that the generic protocol is enough for a store to implement
 * once for all specs.
 *
 * The mapping, driven entirely by the compiled [Model]:
 *
 * - One table per non-transient shape, one column per stored scalar, to-one
 *   reference, or timestamp field. Derived properties have no columns — they
 *   recompute from these rows on read.
 * - A reference typed with a static union (README §9, `target: one Content`)
 *   gets a sibling discriminator column, `<field>__shape`, holding the concrete
 *   base shape: the protocol's [Ref.Persisted] always names the concrete shape,
 *   and keys are only unique per table, so the key alone cannot say which row
 *   a union-typed column points at.
 * - A collection field (`many Course`, `many text`; README §6) is a junction
 *   table `<Shape>__<field>` — one row per member, in membership order, with a
 *   discriminator column when the element type is a union.
 * - Per-membership capture memory (README §8) is a table per capture-carrying
 *   refinement, one row per current membership, deleted on exit
 *   ([Model.captureSchemas] states exactly what must survive).
 * - Keys are `INTEGER PRIMARY KEY AUTOINCREMENT`: a deleted row's key is never
 *   reused. A surviving row may still hold a reference at a deleted one — the
 *   absorbing reference (README §12), which the runtime reads as absent — and
 *   that reading is only sound while no new row can inherit the dead key.
 *
 * Column encoding: references and integer/long as INTEGER, decimal/double as
 * TEXT (exact BigDecimal round-trip), boolean as INTEGER 0/1, Date/DateTime as
 * ISO-8601 TEXT.
 */
class SqliteStore(private val model: Model, private val conn: Connection) : StateResolver, CommitCallback {

    companion object {
        /**
         * The one-line setup an example's tests use: create the schema for
         * [system]'s model in [conn] and connect the system through this store,
         * both halves of the protocol.
         */
        fun attach(system: VelleSystem, conn: Connection): SqliteStore =
            SqliteStore(system.model, conn).also {
                it.createSchema()
                system.connect(it, it)
            }
    }

    /** A scalar, to-one reference, or timestamp column. [union] marks a
     *  reference whose declared target is a static union: the concrete shape
     *  lives in the sibling discriminator column [shapeColumn]. */
    private data class Col(val name: String, val type: VType, val union: Boolean)

    /** A collection field, persisted as a junction table. [elem] is the element
     *  type; [union] marks a reference collection typed with a static union. */
    private data class Coll(val name: String, val elem: VType, val union: Boolean)

    private fun isUnion(shape: String) = model.isStaticUnion(shape)

    private fun columnsOf(shape: String): List<Col> =
        model.shapes.getValue(shape).members.mapNotNull { m ->
            when (m) {
                is StoredProp -> model.typeOf(m.type).let { t ->
                    if (t is VType.Coll || t is VType.CollS) null
                    else Col(m.name, t, t.instanceShape()?.let { isUnion(it) } == true)
                }
                is TimestampProp -> Col(m.name, VType.DateTimeT, union = false)
                else -> null
            }
        }

    private fun collectionsOf(shape: String): List<Coll> =
        model.shapes.getValue(shape).members.filterIsInstance<StoredProp>().mapNotNull { m ->
            when (val t = model.typeOf(m.type)) {
                is VType.Coll -> Coll(m.name, VType.Inst(t.shape), isUnion(t.shape))
                is VType.CollS -> Coll(m.name, scalarType(t.name), union = false)
                else -> null
            }
        }

    private fun scalarType(name: String): VType = when (name) {
        "text" -> VType.Text
        "boolean" -> VType.Bool
        "Date" -> VType.DateT
        "DateTime" -> VType.DateTimeT
        else -> VType.Num(name)
    }

    private val persistedShapes = model.shapes.keys.filter { it !in model.transients }

    private fun shapeColumn(field: String) = "${field}__shape"
    private fun junctionTable(shape: String, field: String) = "${shape}__$field"
    private fun captureTable(refinement: String) = "capture_$refinement"

    fun createSchema() {
        conn.createStatement().use { st ->
            for (shape in persistedShapes) {
                val cols = columnsOf(shape).joinToString("") { c ->
                    """, "${c.name}" ${sqlType(c.type)}""" +
                        if (c.union) """, "${shapeColumn(c.name)}" TEXT""" else ""
                }
                st.execute("""CREATE TABLE IF NOT EXISTS "$shape" (id INTEGER PRIMARY KEY AUTOINCREMENT$cols)""")
                for (coll in collectionsOf(shape)) {
                    val member = if (coll.elem is VType.Inst) {
                        "target INTEGER NOT NULL" + if (coll.union) ", target_shape TEXT NOT NULL" else ""
                    } else """value ${sqlType(coll.elem)} NOT NULL"""
                    st.execute(
                        """CREATE TABLE IF NOT EXISTS "${junctionTable(shape, coll.name)}" """ +
                            """(owner INTEGER NOT NULL, $member)"""
                    )
                }
            }
            // Capture persistence: per-membership memory for each capture-carrying
            // refinement. This store picks a table per refinement — one row per
            // current membership, deleted on exit; nullable columns on the base
            // table or a discriminator would do equally, the mapping is the
            // store's call (Hydration.kt, captureSchemas).
            for (cs in model.captureSchemas) {
                val cols = cs.props.joinToString("") { ", \"${it.name}\" ${sqlType(it.type)}" }
                st.execute("""CREATE TABLE IF NOT EXISTS "${captureTable(cs.refinement)}" (id INTEGER PRIMARY KEY$cols)""")
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

    // ── the resolver: the typed read questions (Hydration.kt, StateResolver) ──
    // Identity is this store's: rows key on the table's INTEGER PRIMARY KEY,
    // minted at insert and reported back from onCommit.

    private fun keyLong(key: StoreKey): Long = key.value as Long

    override fun fetchByKey(shape: String, key: StoreKey): Row? =
        select(shape, """WHERE id = ?""") { it.setLong(1, keyLong(key)) }.singleOrNull()

    override fun fetchAll(shape: String): List<Row> = select(shape, "")

    /** The join read. A union-typed field matches on key *and* concrete shape:
     *  Post#1 and Photo#1 are different rows behind the same key. */
    override fun fetchReferencing(shape: String, field: String, target: Ref.Persisted): List<Row> {
        val col = columnsOf(shape).first { it.name == field }
        return if (col.union)
            select(shape, """WHERE "$field" = ? AND "${shapeColumn(field)}" = ?""") {
                it.setLong(1, keyLong(target.key)); it.setString(2, target.shape)
            }
        else
            select(shape, """WHERE "$field" = ?""") { it.setLong(1, keyLong(target.key)) }
    }

    override fun fetchCaptures(instance: Ref.Persisted, refinement: String): Map<String, Any?>? {
        val cs = model.captureSchemas.find { it.refinement == refinement } ?: return null
        return conn.prepareStatement("""SELECT * FROM "${captureTable(refinement)}" WHERE id = ?""").use { ps ->
            ps.setLong(1, keyLong(instance.key))
            ps.executeQuery().use { rs ->
                if (!rs.next()) null
                else buildMap {
                    for (p in cs.props) {
                        if (rs.getObject(p.name) == null) continue
                        put(p.name, readColumn(p.type, p.name, rs, union = false))
                    }
                }
            }
        }
    }

    /**
     * The pre-filter, rendered to a WHERE clause. Two rules keep this sound
     * against the superset contract:
     *
     * - Comparisons this store's column encoding can't order or equate in SQL
     *   (decimal/double and DateTime, both stored as non-collating TEXT) degrade
     *   *by polarity*: TRUE in positive position, FALSE under an odd number of
     *   NOTs — always widening the result set, never narrowing it. So does any
     *   join the schema can't express as one table — a hop through a
     *   union-typed reference names the union, not a table.
     * - An absent column satisfies `!=` (NULL-inclusive rendering) and fails
     *   `==`/ordered comparisons — matching the runtime's in-memory semantics
     *   (QF's contract in Query.kt).
     */
    override fun fetchCandidates(shape: String, filter: QF): List<Row> {
        val r = WhereRenderer()
        val where = r.render(filter, shape, "t0", positive = true)
        if (where == "1") return fetchAll(shape)
        return conn.prepareStatement("""SELECT t0.* FROM "$shape" t0 WHERE $where""").use { ps ->
            r.binds.forEachIndexed { i, b -> ps.setObject(i + 1, b) }
            ps.executeQuery().use { rs ->
                buildList { while (rs.next()) add(readRow(shape, rs)) }
            }
        }
    }

    private inner class WhereRenderer {
        val binds = mutableListOf<Any?>()
        private var nextAlias = 1

        fun render(f: QF, shape: String, alias: String, positive: Boolean): String = when (f) {
            QF.True -> "1"
            QF.False -> "0"
            is QF.And -> "(${render(f.l, shape, alias, positive)} AND ${render(f.r, shape, alias, positive)})"
            is QF.Or -> "(${render(f.l, shape, alias, positive)} OR ${render(f.r, shape, alias, positive)})"
            is QF.Not -> "NOT (${render(f.inner, shape, alias, !positive)})"
            is QF.NullCheck -> """$alias."${f.field}" IS ${if (f.isNull) "" else "NOT "}NULL"""
            is QF.Cmp -> renderCmp(f, shape, alias, positive)
            is QF.Exists -> {
                val a = "t${nextAlias++}"
                // correlation through a union-typed back-reference must also
                // match the subject's concrete shape (keys are per table)
                val corr = f.refField?.let { rf ->
                    val col = columnsOf(f.shape).find { it.name == rf }
                    if (col?.union == true) {
                        binds.add(shape)
                        """$a."$rf" = $alias.id AND $a."${shapeColumn(rf)}" = ? AND """
                    } else """$a."$rf" = $alias.id AND """
                } ?: ""
                """EXISTS (SELECT 1 FROM "${f.shape}" $a WHERE $corr${render(f.inner, f.shape, a, positive)})"""
            }
            is QF.RelPred -> {
                if (f.shape !in persistedShapes) degrade(positive) // a union: no single table to join
                else {
                    val a = "t${nextAlias++}"
                    """EXISTS (SELECT 1 FROM "${f.shape}" $a WHERE $a.id = $alias."${f.field}" AND ${render(f.inner, f.shape, a, positive)})"""
                }
            }
        }

        private fun renderCmp(f: QF.Cmp, shape: String, alias: String, positive: Boolean): String {
            val colType = columnsOf(shape).find { it.name == f.field }?.type
                ?: return degrade(positive)
            val encoded = encode(f.value, colType) ?: return degrade(positive)
            val col = """$alias."${f.field}""""
            // the text operators (README §10) render to LIKE with `\` as the escape
            // character; SQLite's LIKE folds ASCII case, so the rendering may
            // over-return against the case-sensitive predicate — legal under the
            // superset contract, and the evaluator decides in memory
            if (f.op in TEXT_OPERATORS) {
                val text = encoded as? String ?: return degrade(positive)
                val pattern = when (f.op) {
                    "contains" -> "%" + likeEscape(text) + "%"
                    "starts with" -> likeEscape(text) + "%"
                    "ends with" -> "%" + likeEscape(text)
                    else -> text // `like`: the author's pattern, same wildcards, same escape
                }
                binds.add(pattern)
                return "$col LIKE ? ESCAPE '\\'"
            }
            binds.add(encoded)
            return when (f.op) {
                "==" -> "$col = ?"
                "!=" -> "($col IS NULL OR $col != ?)"
                else -> "$col ${f.op} ?"
            }
        }

        private fun likeEscape(s: String): String = buildString {
            for (c in s) { if (c == '\\' || c == '%' || c == '_') append('\\'); append(c) }
        }

        private fun degrade(positive: Boolean): String = if (positive) "1" else "0"

        /** SQL-comparable encoding for this store, or null where the encoding
         *  can't compare faithfully (decimal/double, DateTime — TEXT columns
         *  whose lexicographic order isn't the value order). */
        private fun encode(c: QConst, t: VType): Any? {
            val bare = if (t is VType.Optional) t.inner else t
            return when (c) {
                is QConst.QNum ->
                    if (bare is VType.Num && (bare.name == "integer" || bare.name == "long"))
                        runCatching { c.v.longValueExact() }.getOrNull()
                    else null
                is QConst.QText -> (c.v).takeIf { bare == VType.Text }
                is QConst.QBool -> (if (c.v) 1 else 0).takeIf { bare == VType.Bool }
                is QConst.QDate -> c.v.toString().takeIf { bare == VType.DateT }
                is QConst.QDateTime -> null
                // an instance constant (the query API's "this customer"): the row key
                is QConst.QRef -> (c.ref.key.value as? Number)?.toLong().takeIf { bare is VType.Inst }
            }
        }
    }

    private fun select(shape: String, where: String, bind: (PreparedStatement) -> Unit = {}): List<Row> =
        conn.prepareStatement("""SELECT * FROM "$shape" $where""").use { ps ->
            bind(ps)
            ps.executeQuery().use { rs ->
                buildList { while (rs.next()) add(readRow(shape, rs)) }
            }
        }

    private fun readRow(shape: String, rs: ResultSet): Row {
        val key = rs.getLong("id")
        val fields = buildMap {
            for (c in columnsOf(shape)) {
                if (rs.getObject(c.name) == null) continue
                put(c.name, readColumn(c.type, c.name, rs, c.union))
            }
            for (coll in collectionsOf(shape)) put(coll.name, readCollection(shape, coll, key))
        }
        return Row(shape, StoreKey(key), fields)
    }

    private fun readColumn(t: VType, name: String, rs: ResultSet, union: Boolean): Any = when (t) {
        is VType.Optional -> readColumn(t.inner, name, rs, union)
        // references leave the store as typed refs — the concrete shape from
        // the model, or from the discriminator when the model only says "union"
        is VType.Inst -> Ref.Persisted(
            if (union) rs.getString(shapeColumn(name)) else t.shape,
            StoreKey(rs.getLong(name)),
        )
        is VType.Num ->
            if (t.name == "integer" || t.name == "long") rs.getLong(name)
            else BigDecimal(rs.getString(name))
        VType.Bool -> rs.getInt(name) != 0
        VType.DateT -> LocalDate.parse(rs.getString(name))
        VType.DateTimeT -> Instant.parse(rs.getString(name))
        else -> rs.getString(name)
    }

    /** A collection's members in membership order (the junction's insertion order). */
    private fun readCollection(shape: String, coll: Coll, owner: Long): List<Any> =
        conn.prepareStatement("""SELECT * FROM "${junctionTable(shape, coll.name)}" WHERE owner = ? ORDER BY rowid""").use { ps ->
            ps.setLong(1, owner)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(
                        if (coll.elem is VType.Inst) Ref.Persisted(
                            if (coll.union) rs.getString("target_shape") else coll.elem.shape,
                            StoreKey(rs.getLong("target")),
                        )
                        else readColumn(coll.elem, "value", rs, union = false)
                    )
                }
            }
        }

    // ── the commit callback: the transaction's mutation set, atomically ──────

    /**
     * Runs inside the runtime's envelope: everything the transaction created,
     * assigned, captured, or deleted lands in one SQLite transaction, and a
     * failure here rolls the whole Velle commit back (in-envelope failure,
     * evaluation.md U2).
     *
     * Identity is minted here (Hydration.kt, StoreKey): each created row gets its table's next
     * key, and the assigned keys return in creation order. Creates arrive with
     * pending refs pointing backward, so inserting in list order always has
     * the referenced key already minted. Deletions go last, as the contract
     * orders them: a surviving row's reference at a deleted one is left in
     * place — the absorbing reference the runtime reads as absent.
     */
    override fun onCommit(commit: CommitSet): List<StoreKey> {
        conn.autoCommit = false
        try {
            val minted = mutableListOf<StoreKey>()
            for (c in commit.created) minted.add(insert(c, minted))
            for (a in commit.assigned) update(a, minted)
            for (c in commit.captured) upsertCapture(c, minted)
            for (r in commit.retracted) deleteCapture(r)
            for (d in commit.deleted) delete(d)
            conn.commit()
            return minted
        } catch (e: Exception) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = true
        }
    }

    /** A reference value as this store's key column: persisted refs carry
     *  their key, pending refs resolve against the keys minted so far. */
    private fun refKey(v: Any, minted: List<StoreKey>): Long = when (v) {
        is Ref.Persisted -> keyLong(v.key)
        is Ref.Pending -> keyLong(minted[v.index])
        else -> throw IllegalArgumentException("reference value is not a Ref: $v")
    }

    private fun refShape(v: Any): String =
        (v as? RowRef)?.shape ?: throw IllegalArgumentException("reference value is not a Ref: $v")

    private fun insert(c: CommitSet.Creation, minted: List<StoreKey>): StoreKey {
        val cols = columnsOf(c.shape)
        val names = cols.flatMap { col -> listOf(col.name) + if (col.union) listOf(shapeColumn(col.name)) else emptyList() }
        val sql = """INSERT INTO "${c.shape}" (${names.joinToString(", ") { "\"$it\"" }}) """ +
            """VALUES (${names.joinToString(", ") { "?" }})"""
        conn.prepareStatement(sql).use { ps ->
            var i = 1
            for (col in cols) {
                val v = c.fields[col.name]
                ps.setObject(i++, writeColumn(col.type, v, minted))
                if (col.union) ps.setObject(i++, v?.let { refShape(it) })
            }
            ps.executeUpdate()
        }
        val key = conn.prepareStatement("SELECT last_insert_rowid()").use { ps ->
            ps.executeQuery().use { rs -> rs.next(); StoreKey(rs.getLong(1)) }
        }
        for (coll in collectionsOf(c.shape)) writeCollection(c.shape, coll, keyLong(key), c.fields[coll.name], minted)
        return key
    }

    private fun update(a: CommitSet.Assign, minted: List<StoreKey>) {
        val shape = a.target.shape
        collectionsOf(shape).find { it.name == a.field }?.let { coll ->
            // whole-set replacement: the junction rows become exactly the new set
            conn.prepareStatement("""DELETE FROM "${junctionTable(shape, coll.name)}" WHERE owner = ?""").use { ps ->
                ps.setLong(1, keyLong(a.target.key))
                ps.executeUpdate()
            }
            writeCollection(shape, coll, keyLong(a.target.key), a.value, minted)
            return
        }
        val col = columnsOf(shape).first { it.name == a.field }
        val set = """"${a.field}" = ?""" + if (col.union) """, "${shapeColumn(a.field)}" = ?""" else ""
        conn.prepareStatement("""UPDATE "$shape" SET $set WHERE id = ?""").use { ps ->
            var i = 1
            ps.setObject(i++, writeColumn(col.type, a.value, minted))
            if (col.union) ps.setObject(i++, a.value?.let { refShape(it) })
            ps.setLong(i, keyLong(a.target.key))
            ps.executeUpdate()
        }
    }

    private fun writeCollection(shape: String, coll: Coll, owner: Long, value: Any?, minted: List<StoreKey>) {
        val members = value as? List<*> ?: return // absent reads as the empty collection
        if (members.isEmpty()) return
        val table = junctionTable(shape, coll.name)
        val sql = if (coll.elem is VType.Inst) {
            if (coll.union) """INSERT INTO "$table" (owner, target, target_shape) VALUES (?, ?, ?)"""
            else """INSERT INTO "$table" (owner, target) VALUES (?, ?)"""
        } else """INSERT INTO "$table" (owner, value) VALUES (?, ?)"""
        conn.prepareStatement(sql).use { ps ->
            for (m in members) {
                ps.setLong(1, owner)
                ps.setObject(2, writeColumn(coll.elem, m, minted))
                if (coll.elem is VType.Inst && coll.union) ps.setString(3, refShape(m!!))
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    private fun upsertCapture(c: CommitSet.Capture, minted: List<StoreKey>) {
        val cs = model.captureSchemas.first { it.refinement == c.refinement }
        val names = listOf("id") + cs.props.map { it.name }
        val sql = """INSERT OR REPLACE INTO "${captureTable(c.refinement)}" """ +
            """(${names.joinToString(", ") { "\"$it\"" }}) VALUES (${names.joinToString(", ") { "?" }})"""
        conn.prepareStatement(sql).use { ps ->
            ps.setLong(1, refKey(c.instance, minted))
            cs.props.forEachIndexed { i, p -> ps.setObject(i + 2, writeColumn(p.type, c.values[p.name], minted)) }
            ps.executeUpdate()
        }
    }

    private fun deleteCapture(r: CommitSet.Retraction) {
        conn.prepareStatement("""DELETE FROM "${captureTable(r.refinement)}" WHERE id = ?""").use { ps ->
            ps.setLong(1, keyLong(r.instance.key))
            ps.executeUpdate()
        }
    }

    /**
     * The row removal (README §12): the row, its own collections, and any
     * capture memory keyed on it go; the key is never reused (AUTOINCREMENT).
     * References *at* the row from surviving rows stay as they are — the
     * runtime reads them as absent (the absorbing reference). A store that
     * preferred to null them out could: the model names every column and
     * junction table that can point at this shape.
     */
    private fun delete(d: CommitSet.Deletion) {
        val shape = d.target.shape
        val key = keyLong(d.target.key)
        conn.prepareStatement("""DELETE FROM "$shape" WHERE id = ?""").use { ps ->
            ps.setLong(1, key); ps.executeUpdate()
        }
        for (coll in collectionsOf(shape)) {
            conn.prepareStatement("""DELETE FROM "${junctionTable(shape, coll.name)}" WHERE owner = ?""").use { ps ->
                ps.setLong(1, key); ps.executeUpdate()
            }
        }
        for (cs in model.captureSchemas.filter { it.base == shape }) {
            conn.prepareStatement("""DELETE FROM "${captureTable(cs.refinement)}" WHERE id = ?""").use { ps ->
                ps.setLong(1, key); ps.executeUpdate()
            }
        }
    }

    private fun writeColumn(t: VType, v: Any?, minted: List<StoreKey>): Any? = when {
        v == null -> null
        t is VType.Optional -> writeColumn(t.inner, v, minted)
        t is VType.Inst -> refKey(v, minted)
        t is VType.Num ->
            if (t.name == "integer" || t.name == "long") toBigDecimal(v).longValueExact()
            else toBigDecimal(v).toPlainString()
        t == VType.Bool -> if (v as Boolean) 1 else 0
        else -> v.toString() // Date/DateTime ISO-8601, text as-is
    }

    /** Numerics arrive as BigDecimal from the runtime, but a typed store layer
     *  may hand back Int/Long/Double (the generated row types). */
    private fun toBigDecimal(v: Any): BigDecimal = when (v) {
        is BigDecimal -> v
        is Int -> BigDecimal(v)
        is Long -> BigDecimal(v)
        is Double -> BigDecimal.valueOf(v)
        else -> throw IllegalArgumentException("not numeric: $v")
    }
}
