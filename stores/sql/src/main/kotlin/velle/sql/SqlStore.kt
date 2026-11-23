package velle.sql

import velle.ActorProp
import velle.AdmissionKey
import velle.CaptureSchema
import velle.CommitCallback
import velle.CommitSet
import velle.EnumType
import velle.Member
import velle.Model
import velle.Outcome
import velle.QArg
import velle.QCall
import velle.QConst
import velle.QF
import velle.Ref
import velle.Row
import velle.RowRef
import velle.StateResolver
import velle.StoreKey
import velle.StoredProp
import velle.TEXT_OPERATORS
import velle.TickMemory
import velle.TimestampProp
import velle.UnitOfWork
import velle.VType
import velle.VelleSystem
import velle.WorkKind
import velle.canonicalAny
import velle.equalAny
import velle.ext.Signature
import velle.ext.VelleFunction
import velle.ext.VelleType
import java.math.BigDecimal
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.time.Duration
import java.time.Instant
import javax.sql.DataSource

/**
 * A generic, model-driven store over any SQL database: the framework role in
 * Reference §22's architecture, implementing the runtime's resolver and
 * commit-callback protocol (compiler/.../Hydration.kt; evaluation.md "The
 * universal transaction") for any spec. It contains no business names, and no
 * database's name either: what one database does differently is its
 * [SqlDialect], and a store for that database is this class with that dialect.
 *
 * The mapping, driven entirely by the compiled [Model]:
 *
 * - One table per non-transient shape, one column per stored scalar, to-one
 *   reference, or timestamp field. Derived properties have no columns — they
 *   recompute from these rows on read.
 * - A reference typed with a static union (Reference §9, `target: one Content`)
 *   gets a sibling discriminator column, `<field>__shape`, holding the concrete
 *   base shape: the protocol's [Ref.Persisted] always names the concrete shape,
 *   and keys are only unique per table, so the key alone cannot say which row
 *   a union-typed column points at.
 * - A collection field (`many Course`, `many text`; Reference §6, §5) is a junction
 *   table `<Shape>__<field>` — one row per member with its position, and a
 *   discriminator column when the element type is a union.
 * - Per-membership capture memory (Reference §8) is a table per capture-carrying
 *   refinement, one row per current membership, deleted on exit
 *   ([Model.captureSchemas] states exactly what must survive).
 * - Keys are minted by the database ([SqlDialect.keyColumn]) and never reused.
 * - A deletion empties every stored reference at the deleted row before the row
 *   goes: a to-one column is set to NULL, with its discriminator column, and the
 *   row's junction rows as a member are removed.
 *
 * Connections: the store holds a `DataSource`, never a connection. Each unit of
 * work the runtime opens (Hydration.kt, [StateResolver.begin]) takes one
 * connection and one transaction, a read's snapshot or an envelope's write, and
 * returns the connection when the unit ends; the envelope's queue keys are
 * locked through the dialect inside that transaction. A read the runtime did
 * not issue runs on a connection taken for that one call.
 *
 * Every scalar column is a [StorageClass] held as the dialect says: a builtin
 * type's own class with the value as carrier, or the class an extension type's
 * [SqlTypeBinding] names, with the binding's encoding. The store refuses to
 * start without a binding for every extension type the model stores (E4),
 * [bindings] defaulting to every binding on the classpath.
 *
 * A call of a registered function in a pre-filter renders through the function's
 * [SqlFunctionBinding] for this dialect, [functionBindings] defaulting to every
 * binding on the classpath, or stays with the evaluator when there is none. The
 * body runs on every row handed back either way.
 */
open class SqlStore(
    private val model: Model,
    private val dataSource: DataSource,
    val dialect: SqlDialect,
    bindings: List<SqlTypeBinding<*>> = SqlTypeBinding.load(),
    /** How long a write unit waits for one of its queue-key locks before the envelope fails. */
    private val lockTimeout: Duration = Duration.ofSeconds(10),
    functionBindings: List<SqlFunctionBinding> = SqlFunctionBinding.load(),
    /** Qualified names of the functions this system depends on rendering: a refused DDL for one stops the store. */
    private val insistRendered: Set<String> = emptySet(),
) : StateResolver, CommitCallback, TickMemory {

    companion object {
        /**
         * The one-line setup: create the schema for [system]'s model in the
         * database behind [dataSource] and connect the system through this
         * store, every part of the protocol.
         */
        fun attach(system: VelleSystem, dataSource: DataSource, dialect: SqlDialect, bindings: List<SqlTypeBinding<*>> = SqlTypeBinding.load()): SqlStore =
            SqlStore(system.model, dataSource, dialect, bindings).also { store ->
                store.createSchema()
                system.connect(store, store, store)
            }
    }

    // ── the unit of work: a connection and a transaction for exactly as long as the runtime works ──

    /**
     * One unit of work (Hydration.kt, [UnitOfWork]) on one connection taken from
     * the `DataSource` at [begin] and returned at [end]. A read runs in the
     * transaction [SqlDialect.beginRead] opens and is rolled back at the end,
     * having written nothing; a write runs in [SqlDialect.beginWrite]'s, takes
     * its queue-key locks through [SqlDialect.lock] in sorted order, and is
     * committed at the end when the envelope was, rolled back otherwise.
     */
    private inner class SqlUnit(val connection: Connection, val kind: WorkKind) : UnitOfWork {
        override fun serialize(keys: Set<AdmissionKey>) {
            if (kind == WorkKind.WRITE && keys.isNotEmpty()) dialect.lock(connection, keys.sorted(), lockTimeout)
        }

        override fun end(outcome: Outcome) {
            try {
                if (kind == WorkKind.WRITE && outcome is Outcome.Committed) connection.commit() else connection.rollback()
            } finally {
                current.remove()
                runCatching { connection.autoCommit = true }
                connection.close()
            }
        }
    }

    /** The unit the calling thread is working in; a unit is one thread's from begin to end. */
    private val current = ThreadLocal<SqlUnit?>()

    override fun begin(kind: WorkKind): UnitOfWork {
        check(current.get() == null) { "a unit of work is already open on this thread" }
        val connection = dataSource.connection
        try {
            dialect.prepare(connection)
            if (kind == WorkKind.READ) dialect.beginRead(connection) else dialect.beginWrite(connection)
        } catch (error: Exception) {
            runCatching { connection.close() }
            throw error
        }
        return SqlUnit(connection, kind).also { unit -> current.set(unit) }
    }

    /**
     * Runs [body] on the open unit's connection, or, outside any unit (a read
     * the runtime did not issue, such as a typed store called from the
     * engineer's own code), on a connection taken for this one call and
     * returned after it.
     */
    private fun <T> onConnection(body: (Connection) -> T): T {
        current.get()?.let { unit -> return body(unit.connection) }
        return dataSource.connection.use { connection ->
            dialect.prepare(connection)
            body(connection)
        }
    }

    private val bindings: Map<String, SqlTypeBinding<*>> = bindings.associateBy { binding -> binding.type }

    /** The binding that renders each function on this dialect: the one claiming the dialect, else the one claiming any. */
    private val renderings: Map<String, SqlFunctionBinding> = selectRenderings(functionBindings)

    private val refusedDdl = HashMap<String, String>()

    /** The functions whose binding's DDL the database refused, with its error; each runs in memory. Known after [createSchema]. */
    val renderingsNotInstalled: Map<String, String> get() = refusedDdl

    private fun selectRenderings(all: List<SqlFunctionBinding>): Map<String, SqlFunctionBinding> {
        val selected = HashMap<String, SqlFunctionBinding>()
        for ((function, candidates) in all.groupBy { binding -> binding.function }) {
            val registered = model.registry.function(function)
                ?: throw IllegalStateException("${dialect.name} store: ${candidates.first()::class.qualifiedName} is a binding for '$function', and no registered function has that name")
            val chosen = chooseRendering(function, candidates) ?: continue
            checkOperandNames(registered, chosen)
            selected[function] = chosen
        }
        return selected
    }

    /** The one of [candidates] claiming this dialect, else the one claiming any, else none; two claiming the same is refused. */
    private fun chooseRendering(function: String, candidates: List<SqlFunctionBinding>): SqlFunctionBinding? {
        val claimingDialect = candidates.filter { binding -> binding.dialects?.contains(dialect.name) == true }
        val claimingAny = candidates.filter { binding -> binding.dialects == null }
        if (claimingDialect.size > 1) throw conflict(function, dialect.name, claimingDialect)
        if (claimingAny.size > 1) throw conflict(function, "any SQL database", claimingAny)
        return claimingDialect.firstOrNull() ?: claimingAny.firstOrNull()
    }

    private fun conflict(function: String, where: String, claiming: List<SqlFunctionBinding>) = IllegalStateException(
        "${dialect.name} store: two bindings claim '$function' on $where, ${claiming.joinToString { binding -> binding::class.qualifiedName ?: "?" }}; " +
            "the system's owner keeps one, by the classpath or the store's explicit list"
    )

    /** Every name a binding reads is a parameter of each signature of its function: checked here, not at query time. */
    private fun checkOperandNames(registered: VelleFunction, binding: SqlFunctionBinding) {
        for (signature in registered.signatures) {
            try {
                binding.render(probeOperands(signature))
            } catch (error: IllegalArgumentException) {
                throw IllegalStateException("${dialect.name} store: ${binding::class.qualifiedName}, the binding for '${registered.qualifiedName}', ${error.message}")
            }
        }
    }

    /** Operands for [signature] holding a stand-in column under every parameter's name. */
    private fun probeOperands(signature: Signature): SqlOperands {
        val probe = SqlExpr.Column("probe", VType.Unknown)
        val (variadic, single) = signature.parameters.partition { parameter -> parameter.variadic }
        return SqlOperands(
            signature,
            single.associate { parameter -> parameter.name to probe },
            variadic.associate { parameter -> parameter.name to listOf(probe) },
        )
    }

    /** Runs each selected binding's DDL once with the schema; a refusal is recorded, or stops the store when the system insists. */
    private fun installRenderings(connection: Connection) {
        for ((function, binding) in renderings) {
            if (binding.ddl.isNotEmpty()) installRendering(connection, function, binding)
        }
    }

    private fun installRendering(connection: Connection, function: String, binding: SqlFunctionBinding) {
        try {
            connection.createStatement().use { statement -> for (sql in binding.ddl) statement.execute(sql) }
            refusedDdl.remove(function)
        } catch (error: SQLException) {
            val message = error.message ?: error::class.simpleName ?: "refused"
            if (function in insistRendered)
                throw IllegalStateException("${dialect.name} store: the database refused the DDL of ${binding::class.qualifiedName}, the binding for '$function', which this system insists on: $message", error)
            refusedDdl[function] = message
        }
    }

    /** Reference columns and capture keys: a key as the dialect's integer column. */
    private val keyStorage: StorageColumn = dialect.column(StorageClass.INTEGER)

    init {
        // E4: a binding for every extension type a stored field holds, and the binding
        // round-trips the type's own samples through the dialect's column for its class
        for (type in model.storedExtensionTypes) {
            roundTrip(type, bindingFor(type))
        }
    }

    private fun <T : Any> roundTrip(type: VelleType<T>, binding: SqlTypeBinding<*>) = onConnection { connection ->
        val given = type.samples
        val samples: List<Any> = listOfNotNull(given.sample, given.below?.invoke(given.sample), given.above?.invoke(given.sample))
        val column = extensionColumn(binding)
        connection.createStatement().use { statement -> statement.execute("CREATE TEMP TABLE velle_e4 (v ${column.columnType})") }
        try {
            for (sample in samples) roundTripSample(connection, type, binding, column, sample)
        } finally {
            connection.createStatement().use { statement -> statement.execute("DROP TABLE velle_e4") }
        }
    }

    private fun roundTripSample(connection: Connection, type: VelleType<*>, binding: SqlTypeBinding<*>, column: ScalarColumn, sample: Any) {
        val carrier = binding.encodeAny(sample)
        if (!binding.storage.carrier.isInstance(carrier))
            throw IllegalStateException("${dialect.name} store's binding for '${model.specNameOf(type)}' (${binding::class.qualifiedName}) names " +
                "${binding.storage} storage but encodes ${type.canonicalAny(sample)} as ${carrier::class.simpleName}, not ${binding.storage.carrier.simpleName}")
        connection.prepareStatement("INSERT INTO velle_e4 (v) VALUES (?)").use { insert ->
            insert.setObject(1, column.bind(sample))
            insert.executeUpdate()
        }
        val readBack = connection.prepareStatement("SELECT v FROM velle_e4").use { select ->
            select.executeQuery().use { results -> results.next(); column.read(results, "v") }
        }
        connection.createStatement().use { statement -> statement.execute("DELETE FROM velle_e4") }
        if (!type.equalAny(sample, readBack))
            throw IllegalStateException("${dialect.name} store's binding for '${model.specNameOf(type)}' (${binding::class.qualifiedName}) does not round-trip " +
                "${type.canonicalAny(sample)}: it read back ${type.canonicalAny(readBack)}")
    }

    // ── columns: one path for builtin and extension scalars ─────────────────

    /** A scalar column: the dialect's column for a storage class, with the
     *  encoding between the field's value and the class's carrier. */
    private class ScalarColumn(
        private val storage: StorageColumn,
        private val encode: (Any) -> Any,
        private val decode: (Any) -> Any,
        claims: Set<String>,
    ) {
        val columnType: String get() = storage.columnType
        val answers: Set<String> = claims intersect storage.answers
        fun bind(value: Any): Any = storage.bind(encode(value))
        fun read(results: ResultSet, column: String): Any = decode(storage.read(results, column))
    }

    private val scalarColumns = HashMap<VType, ScalarColumn>()

    private fun scalarColumn(type: VType): ScalarColumn = scalarColumns.getOrPut(type) {
        if (type is VType.Ext) extensionColumn(bindingOf(type)) else builtinColumn(type)
    }

    private fun builtinColumn(type: VType): ScalarColumn {
        val storage = builtinStorage(type) ?: throw IllegalArgumentException("not a scalar column type: $type")
        val encode: (Any) -> Any = when (storage) {
            StorageClass.INTEGER -> { number -> toBigDecimal(number).longValueExact() }
            StorageClass.DECIMAL -> { number -> toBigDecimal(number) }
            else -> { value -> value }
        }
        return ScalarColumn(dialect.column(storage), encode, { stored -> stored }, ALL_COMPARISONS)
    }

    private fun extensionColumn(binding: SqlTypeBinding<*>): ScalarColumn =
        ScalarColumn(dialect.column(binding.storage), { value -> binding.encodeAny(value) }, { stored -> binding.decode(stored) }, binding.answers)

    /** The storage class of a builtin scalar (Reference §5), or null for a reference or collection. */
    private fun builtinStorage(type: VType): StorageClass? = when (type) {
        is VType.Optional -> builtinStorage(type.inner)
        VType.Text -> StorageClass.TEXT
        is VType.Num -> if (isWholeNumber(type)) StorageClass.INTEGER else StorageClass.DECIMAL
        VType.Bool -> StorageClass.BOOLEAN
        VType.DateT -> StorageClass.DATE
        VType.DateTimeT -> StorageClass.DATETIME
        VType.UuidT -> StorageClass.UUID
        VType.DurationT -> StorageClass.DURATION
        VType.TimeT -> StorageClass.TIME
        VType.BytesT -> StorageClass.BYTES
        else -> null
    }

    /** A number type held in the dialect's integer column; every other number type is held as a decimal. */
    private fun isWholeNumber(type: VType.Num): Boolean = type.name == "integer" || type.name == "long"

    private fun bindingOf(type: VType.Ext): SqlTypeBinding<*> =
        model.registry[type.qualifiedName]?.let { registered -> bindingFor(registered) }
            ?: throw IllegalStateException("${dialect.name} store has no binding for '${type.qualifiedName}'")

    /** The binding for an extension type: a type the spec declares by its words is held as its
     *  word by [EnumBinding], shipped here; any other type's binding is a library of its own (E4). */
    private fun bindingFor(type: VelleType<*>): SqlTypeBinding<*> =
        if (type is EnumType) EnumBinding(type)
        else bindings[type.qualifiedName]
            ?: throw IllegalStateException("${dialect.name} store has no binding for '${model.specNameOf(type)}' (${type.qualifiedName}); " +
                "add a SqlTypeBinding<${type.kotlinClass.simpleName}> and name it in META-INF/services/velle.sql.SqlTypeBinding")

    /** Numerics arrive as BigDecimal from the runtime, but a typed store layer
     *  may hand back Int/Long/Double (the generated row types). */
    private fun toBigDecimal(number: Any): BigDecimal = when (number) {
        is BigDecimal -> number
        is Int -> BigDecimal(number)
        is Long -> BigDecimal(number)
        is Double -> BigDecimal.valueOf(number)
        else -> throw IllegalArgumentException("not numeric: $number")
    }

    // ── the schema, derived from the model ──────────────────────────────────

    /** A scalar, to-one reference, or timestamp column. [union] marks a
     *  reference whose declared target is a static union: the concrete shape
     *  lives in the sibling discriminator column [shapeColumn]. */
    private data class FieldColumn(val name: String, val type: VType, val union: Boolean)

    /** A collection field, persisted as a junction table. [element] is the element
     *  type; [union] marks a reference collection typed with a static union. */
    private data class FieldCollection(val name: String, val element: VType, val union: Boolean)

    private fun isUnion(shape: String) = model.isStaticUnion(shape)

    private fun columnsOf(shape: String): List<FieldColumn> =
        model.shapes.getValue(shape).members.mapNotNull { member -> columnOf(member) }

    private fun columnOf(member: Member): FieldColumn? = when (member) {
        is StoredProp -> storedColumn(member)
        is TimestampProp -> FieldColumn(member.name, VType.DateTimeT, union = false)
        is ActorProp -> actorColumn(member)
        else -> null
    }

    /** A stored field's column; none for a transient field, which is in the act's input (Reference §4), or for a collection. */
    private fun storedColumn(stored: StoredProp): FieldColumn? {
        if (stored.transient) return null
        val type = model.typeOf(stored.type)
        if (type is VType.Coll || type is VType.CollS) return null
        return FieldColumn(stored.name, type, type.instanceShape()?.let { shape -> isUnion(shape) } == true)
    }

    /** The actor of a durable act (Reference §22): a reference column to the clause's base shape. */
    private fun actorColumn(actor: ActorProp): FieldColumn {
        val baseShape = model.baseOf(actor.shape) ?: actor.shape
        return FieldColumn(actor.name, VType.Inst(baseShape), isUnion(baseShape))
    }

    private fun collectionsOf(shape: String): List<FieldCollection> =
        model.shapes.getValue(shape).members.filterIsInstance<StoredProp>().filterNot { stored -> stored.transient }.mapNotNull { stored ->
            when (val type = model.typeOf(stored.type)) {
                is VType.Coll -> FieldCollection(stored.name, VType.Inst(type.shape), isUnion(type.shape))
                is VType.CollS -> FieldCollection(stored.name, model.scalarOf(type.name), union = false)
                else -> null
            }
        }

    private val persistedShapes = model.shapes.keys.filter { shape -> shape !in model.transients }

    /** A key (Reference §21, "Keys") kept by the database: one `UNIQUE` per key, over the
     *  key's columns and the shape column of a union-typed reference among them. Absent
     *  values are left out of the constraint, as the emitted `never` leaves them out of the
     *  count. The runtime still refuses the second record at the door, with the rejection
     *  as data; the constraint guards against two commits racing from separate processes. */
    private fun keyConstraints(shape: String): String {
        val columns = columnsOf(shape).associateBy { column -> column.name }
        return model.keysOf(shape).joinToString("") { key ->
            val names = key.fields.flatMap { field -> columns[field]?.let { column -> storedNames(column) }.orEmpty() }
            ", UNIQUE (${quoted(names)})"
        }
    }

    /** The column's own name, and its discriminator column's when it is typed with a union. */
    private fun storedNames(column: FieldColumn): List<String> =
        if (column.union) listOf(column.name, shapeColumn(column.name)) else listOf(column.name)

    private fun quoted(names: List<String>): String = names.joinToString(", ") { name -> "\"$name\"" }

    private fun shapeColumn(field: String) = "${field}__shape"
    private fun junctionTable(shape: String, field: String) = "${shape}__$field"
    private fun captureTable(refinement: String) = "capture_$refinement"

    /** The moment each schedule last ran, one row per schedule ([TickMemory]). No shape's table starts in lower case. */
    private val tickTable = "velle_tick"
    private val momentStorage: StorageColumn get() = dialect.column(StorageClass.DATETIME)
    private val textColumnType: String get() = dialect.column(StorageClass.TEXT).columnType

    private fun columnType(type: VType): String = when {
        type is VType.Optional -> columnType(type.inner)
        type is VType.Inst -> keyStorage.columnType
        else -> scalarColumn(type).columnType
    }

    fun createSchema() = onConnection { connection ->
        connection.createStatement().use { statement ->
            for (shape in persistedShapes) createShapeTables(statement, shape)
            for (captureSchema in model.captureSchemas) createCaptureTable(statement, captureSchema)
            statement.execute("""CREATE TABLE IF NOT EXISTS "$tickTable" (schedule $textColumnType PRIMARY KEY, ran_at ${momentStorage.columnType} NOT NULL)""")
        }
        installRenderings(connection)
    }

    private fun createShapeTables(statement: Statement, shape: String) {
        val columns = columnsOf(shape).joinToString("") { column ->
            """, "${column.name}" ${columnType(column.type)}""" +
                if (column.union) """, "${shapeColumn(column.name)}" $textColumnType""" else ""
        }
        statement.execute("""CREATE TABLE IF NOT EXISTS "$shape" (id ${dialect.keyColumn}$columns${keyConstraints(shape)})""")
        for (collection in collectionsOf(shape)) createJunctionTable(statement, shape, collection)
    }

    /** `pos` is the member's place in the collection: membership order is stored, never inferred from the database's row order. */
    private fun createJunctionTable(statement: Statement, shape: String, collection: FieldCollection) {
        val member = when {
            collection.element !is VType.Inst -> "value ${columnType(collection.element)} NOT NULL"
            collection.union -> "target ${keyStorage.columnType} NOT NULL, target_shape $textColumnType NOT NULL"
            else -> "target ${keyStorage.columnType} NOT NULL"
        }
        statement.execute(
            """CREATE TABLE IF NOT EXISTS "${junctionTable(shape, collection.name)}" """ +
                """(owner ${keyStorage.columnType} NOT NULL, pos ${keyStorage.columnType} NOT NULL, $member)"""
        )
    }

    /**
     * Capture persistence: per-membership memory for a capture-carrying
     * refinement. This store picks a table per refinement, one row per current
     * membership, deleted on exit; nullable columns on the base table or a
     * discriminator would do equally, the mapping is the store's call
     * (Hydration.kt, captureSchemas).
     */
    private fun createCaptureTable(statement: Statement, captureSchema: CaptureSchema) {
        val columns = captureSchema.props.joinToString("") { remembered -> ", \"${remembered.name}\" ${columnType(remembered.type)}" }
        statement.execute("""CREATE TABLE IF NOT EXISTS "${captureTable(captureSchema.refinement)}" (id ${keyStorage.columnType} PRIMARY KEY$columns)""")
    }

    // ── ticks: the moment each schedule last ran (Hydration.kt, TickMemory) ──

    override fun latestTick(schedule: String): Instant? = onConnection { connection ->
        connection.prepareStatement("""SELECT ran_at FROM "$tickTable" WHERE schedule = ?""").use { select ->
            select.setString(1, schedule)
            select.executeQuery().use { rows -> if (rows.next()) momentStorage.read(rows, "ran_at") as Instant else null }
        }
    }

    /** Replaces the schedule's row, or inserts its first. The write unit holds the schedule's queue key, so the two statements are one step. */
    override fun persistTick(schedule: String, moment: Instant) = onConnection { connection ->
        val replaced = connection.prepareStatement("""UPDATE "$tickTable" SET ran_at = ? WHERE schedule = ?""").use { update ->
            update.setObject(1, momentStorage.bind(moment))
            update.setString(2, schedule)
            update.executeUpdate()
        }
        if (replaced == 0) insertTick(connection, schedule, moment)
    }

    private fun insertTick(connection: Connection, schedule: String, moment: Instant) {
        connection.prepareStatement("""INSERT INTO "$tickTable" (schedule, ran_at) VALUES (?, ?)""").use { insert ->
            insert.setString(1, schedule)
            insert.setObject(2, momentStorage.bind(moment))
            insert.executeUpdate()
        }
    }

    // ── the resolver: the typed read questions (Hydration.kt, StateResolver) ──
    // Identity is this store's: rows key on the table's key column, minted by
    // the database at insert and reported back from onCommit.

    private fun keyLong(key: StoreKey): Long = key.value as Long

    /** Several rows by key: one statement for the rows, however many keys. */
    override fun fetchByKeys(shape: String, keys: List<StoreKey>): List<Row> =
        keys.distinct().chunked(keysPerStatement).flatMap { some ->
            select(shape, "WHERE id IN (${placeholders(some.size)})") { statement ->
                some.forEachIndexed { index, key -> statement.setLong(index + 1, keyLong(key)) }
            }
        }

    /** The scan read, in key order: creation order, since keys are never reused. */
    override fun fetchAll(shape: String): List<Row> = select(shape, "")

    /** The join read, one statement however many targets. A union-typed field matches on key
     *  *and* concrete shape, each target shape with its own keys: Post#1 and Photo#1 are
     *  different rows behind the same key. */
    override fun fetchReferencingAny(shape: String, field: String, targets: List<Ref.Persisted>): List<Row> {
        val union = columnsOf(shape).first { column -> column.name == field }.union
        return targets.distinct().chunked(keysPerStatement).flatMap { some ->
            if (union) referencingAnyOfUnion(shape, field, some)
            else select(shape, """WHERE "$field" IN (${placeholders(some.size)})""") { statement ->
                some.forEachIndexed { index, target -> statement.setLong(index + 1, keyLong(target.key)) }
            }
        }
    }

    private fun referencingAnyOfUnion(shape: String, field: String, targets: List<Ref.Persisted>): List<Row> {
        val keysByShape = targets.groupBy({ target -> target.shape }, { target -> keyLong(target.key) }).toList()
        val where = keysByShape.joinToString(" OR ") { (_, keys) -> """("${shapeColumn(field)}" = ? AND "$field" IN (${placeholders(keys.size)}))""" }
        val arguments: List<Any> = keysByShape.flatMap { (targetShape, keys) -> listOf<Any>(targetShape) + keys }
        return select(shape, "WHERE $where") { statement ->
            arguments.forEachIndexed { index, argument ->
                if (argument is String) statement.setString(index + 1, argument) else statement.setLong(index + 1, argument as Long)
            }
        }
    }

    /** How many keys one statement names: under every dialect's limit on bound parameters. */
    private val keysPerStatement = 500

    private fun placeholders(count: Int): String = List(count) { "?" }.joinToString(", ")

    override fun fetchCaptures(instance: Ref.Persisted, refinement: String): Map<String, Any?>? {
        val captureSchema = model.captureSchemas.find { schema -> schema.refinement == refinement } ?: return null
        return onConnection { connection ->
            connection.prepareStatement("""SELECT * FROM "${captureTable(refinement)}" WHERE id = ?""").use { select ->
                select.setLong(1, keyLong(instance.key))
                select.executeQuery().use { results -> if (results.next()) readCaptures(captureSchema, results) else null }
            }
        }
    }

    private fun readCaptures(captureSchema: CaptureSchema, results: ResultSet): Map<String, Any?> = buildMap {
        for (remembered in captureSchema.props) {
            if (results.getObject(remembered.name) == null) continue
            put(remembered.name, readColumn(remembered.type, remembered.name, results, union = false))
        }
    }

    /**
     * The pre-filter, rendered to a WHERE clause. Two rules keep this sound
     * against the superset contract:
     *
     * - A comparison the column does not answer faithfully (its [StorageColumn.answers],
     *   narrowed by an extension type's binding) degrades *by polarity*: TRUE in
     *   positive position, FALSE under an odd number of NOTs — always widening the
     *   result set, never narrowing it. So does any join the schema can't express
     *   as one table — a hop through a union-typed reference names the union, not
     *   a table.
     * - An absent column satisfies `!=` (NULL-inclusive rendering) and fails
     *   `==`/ordered comparisons — matching the runtime's in-memory semantics
     *   (QF's contract in Query.kt).
     */
    override fun fetchCandidates(shape: String, filter: QF): List<Row> {
        val renderer = WhereRenderer()
        val where = renderer.render(filter, shape, "t0", positive = true)
        if (where == "TRUE") return fetchAll(shape)
        return onConnection { connection ->
            val rows = connection.prepareStatement("""SELECT t0.* FROM "$shape" t0 WHERE $where ORDER BY t0.id""").use { select ->
                renderer.binds.forEachIndexed { index, bound -> select.setObject(index + 1, bound) }
                readRows(shape, select)
            }
            withCollections(connection, shape, rows, everyRow = false)
        }
    }

    private inner class WhereRenderer {
        val binds = mutableListOf<Any?>()
        private var nextAlias = 1

        fun render(filter: QF, shape: String, alias: String, positive: Boolean): String = when (filter) {
            QF.True -> "TRUE"
            QF.False -> "FALSE"
            is QF.And -> "(${render(filter.l, shape, alias, positive)} AND ${render(filter.r, shape, alias, positive)})"
            is QF.Or -> "(${render(filter.l, shape, alias, positive)} OR ${render(filter.r, shape, alias, positive)})"
            is QF.Not -> "NOT (${render(filter.inner, shape, alias, !positive)})"
            is QF.NullCheck -> """$alias."${filter.field}" IS ${if (filter.isNull) "" else "NOT "}NULL"""
            is QF.TextBlank -> """($alias."${filter.field}" IS NULL OR TRIM($alias."${filter.field}") = '')"""
            is QF.Cmp -> renderCmp(filter, shape, alias, positive)
            is QF.CallCmp -> renderCallCmp(filter, alias, positive)
            is QF.Pred -> renderPred(filter, alias, positive)
            is QF.Exists -> renderExists(filter, shape, alias, positive)
            is QF.RelPred -> renderRelPred(filter, alias, positive)
        }

        private fun newAlias(): String = "t${nextAlias++}"

        /** Some row of the filter's shape satisfies its inner filter, and points at the subject row when the filter names a reference field. */
        private fun renderExists(exists: QF.Exists, subjectShape: String, subjectAlias: String, positive: Boolean): String {
            val pointingAlias = newAlias()
            val correlation = exists.refField
                ?.let { referenceField -> correlation(exists.shape, referenceField, pointingAlias, subjectShape, subjectAlias) }
                ?: ""
            return """EXISTS (SELECT 1 FROM "${exists.shape}" $pointingAlias WHERE $correlation${render(exists.inner, exists.shape, pointingAlias, positive)})"""
        }

        /** The pointing row's reference is the subject row. Through a union-typed reference it
         *  must also match the subject's concrete shape, since keys are per table. */
        private fun correlation(pointingShape: String, referenceField: String, pointingAlias: String, subjectShape: String, subjectAlias: String): String {
            val sameKey = """$pointingAlias."$referenceField" = $subjectAlias.id AND """
            val unionTyped = columnsOf(pointingShape).find { column -> column.name == referenceField }?.union == true
            if (!unionTyped) return sameKey
            binds.add(subjectShape)
            return """$sameKey$pointingAlias."${shapeColumn(referenceField)}" = ? AND """
        }

        /** The row the subject's reference points at satisfies the inner filter. A union names no single table to join. */
        private fun renderRelPred(relation: QF.RelPred, alias: String, positive: Boolean): String {
            if (relation.shape !in persistedShapes) return degrade(positive)
            val targetAlias = newAlias()
            return """EXISTS (SELECT 1 FROM "${relation.shape}" $targetAlias WHERE $targetAlias.id = $alias."${relation.field}" AND ${render(relation.inner, relation.shape, targetAlias, positive)})"""
        }

        private fun renderCmp(comparison: QF.Cmp, shape: String, alias: String, positive: Boolean): String {
            val columnType = columnsOf(shape).find { column -> column.name == comparison.field }?.type ?: return degrade(positive)
            val bareType = bareOf(columnType)
            val column = """$alias."${comparison.field}""""
            if (comparison.op in TEXT_OPERATORS)
                return textComparison(comparison.op, comparison.value, bareType) { column } ?: degrade(positive)
            val value = constantFor(comparison.value, bareType) ?: return degrade(positive)
            if (comparison.op !in answersOf(bareType)) return degrade(positive)
            binds.add(bindValue(value, bareType))
            return comparisonSql(column, comparison.op)
        }

        /** `column op ?`, where an absent column satisfies `!=` and fails every other comparison. */
        private fun comparisonSql(column: String, op: String): String = when (op) {
            "==" -> "$column = ?"
            "!=" -> "($column IS NULL OR $column != ?)"
            else -> "$column $op ?"
        }

        /**
         * `operand op constant` for a text operator (Reference §10), or null where it is
         * left to the evaluator. `matches` is the whole text against a regular
         * expression, where the database has them. The others render to LIKE with `\`
         * as the escape character; a database whose LIKE folds case may over-return
         * against the case-sensitive predicate, which is legal under the superset
         * contract, and the evaluator decides in memory. [operand] is written out only
         * once the comparison is known to render, since writing it queues its constants.
         */
        private fun textComparison(op: String, constant: QConst, operandType: VType, operand: () -> String): String? {
            val text = (constant as? QConst.QText)?.v?.takeIf { operandType == VType.Text } ?: return null
            if (op != "matches") {
                val rendered = operand()
                binds.add(likePattern(op, text))
                return "$rendered LIKE ? ESCAPE '\\'"
            }
            if (dialect.regexMatch("x") == null) return null
            val matched = dialect.regexMatch(operand())
            binds.add(text)
            return matched
        }

        private fun likePattern(op: String, text: String): String = when (op) {
            "contains" -> "%" + likeEscape(text) + "%"
            "starts with" -> likeEscape(text) + "%"
            "ends with" -> "%" + likeEscape(text)
            else -> text // `like`: the author's pattern, same wildcards, same escape
        }

        // ── a registered function's call, through its binding (Extension-functions, "Holding a function in a store") ──

        /** The call's tree from its binding, or null when no binding renders it here: the call is then the evaluator's. */
        private fun callTree(call: QCall, alias: String): SqlExpr? {
            val binding = renderings[call.function]?.takeIf { call.function !in refusedDdl } ?: return null
            val signature = model.registry.function(call.function)?.signatures?.getOrNull(call.signature) ?: return null
            val operands = operandsOf(call, signature, alias) ?: return null
            return binding.render(operands)
        }

        private fun operandsOf(call: QCall, signature: Signature, alias: String): SqlOperands? {
            val single = HashMap<String, SqlExpr>()
            val many = HashMap<String, List<SqlExpr>>()
            for ((name, argument) in call.operands) {
                if (argument is QArg.Many) many[name] = argument.items.map { item -> operand(item, alias) ?: return null }
                else single[name] = operand(argument, alias) ?: return null
            }
            return SqlOperands(signature, single, many)
        }

        private fun operand(argument: QArg, alias: String): SqlExpr? = when (argument) {
            is QArg.Column -> SqlExpr.Column("""$alias."${argument.field}"""", bareOf(argument.type))
            is QArg.Const -> bareOf(argument.type).let { bareType -> constantFor(argument.value, bareType)?.let { value -> SqlExpr.Param(value, bareType) } }
            is QArg.Many -> null
        }

        private fun bareOf(type: VType): VType = if (type is VType.Optional) type.inner else type

        /** Writes [expression] out, queueing every constant it holds in the order its `?` appears. */
        private fun renderExpr(expression: SqlExpr): String = when (expression) {
            is SqlExpr.Column -> expression.reference
            is SqlExpr.Param -> { binds.add(bindValue(expression.value, expression.type)); "?" }
            is SqlExpr.Literal -> { binds.add(literalBind(expression.value)); "?" }
            is SqlExpr.Call -> "${expression.name}(${expression.args.joinToString(", ") { argument -> renderExpr(argument) }})"
            is SqlExpr.Infix -> "(${renderExpr(expression.left)} ${expression.op} ${renderExpr(expression.right)})"
            is SqlExpr.Cast -> "CAST(${renderExpr(expression.expr)} AS ${dialect.column(expression.storage).columnType})"
            is SqlExpr.Raw -> Regex("\\{(\\d+)\\}").replace(expression.sql) { match -> renderExpr(expression.args[match.groupValues[1].toInt()]) }
        }

        private fun bindValue(value: Any, type: VType): Any =
            if (type is VType.Inst) keyStorage.bind(value) else scalarColumn(type).bind(value)

        private fun literalBind(value: Any): Any = when (value) {
            is Int -> dialect.column(StorageClass.INTEGER).bind(value.toLong())
            is Long -> dialect.column(StorageClass.INTEGER).bind(value)
            is BigDecimal -> dialect.column(StorageClass.DECIMAL).bind(value)
            is Double -> dialect.column(StorageClass.DOUBLE).bind(value)
            is String -> dialect.column(StorageClass.TEXT).bind(value)
            is Boolean -> dialect.column(StorageClass.BOOLEAN).bind(value)
            else -> throw IllegalArgumentException("a SqlExpr.Literal holds an Int, Long, BigDecimal, Double, String, or Boolean, not ${value::class.qualifiedName}")
        }

        private fun answersOf(type: VType): Set<String> =
            if (type is VType.Inst) keyStorage.answers else scalarColumn(type).answers

        /**
         * `call op constant`. An absent result reads as an absent column (Query.kt):
         * it satisfies `!=` and fails every other comparison, in either polarity,
         * so the clause is forced to true or false and never left NULL under `not`.
         */
        private fun renderCallCmp(comparison: QF.CallCmp, alias: String, positive: Boolean): String {
            val tree = callTree(comparison.call, alias) ?: return degrade(positive)
            val resultType = comparison.call.result
            if (comparison.op in TEXT_OPERATORS)
                return textComparison(comparison.op, comparison.value, resultType) { renderExpr(tree) }
                    ?.let { matched -> "COALESCE($matched, FALSE)" }
                    ?: degrade(positive)
            if (comparison.op !in answersOf(resultType)) return degrade(positive)
            val value = constantFor(comparison.value, resultType) ?: return degrade(positive)
            if (comparison.op == "!=") return callDiffers(tree, bindValue(value, resultType))
            val rendered = renderExpr(tree)
            binds.add(bindValue(value, resultType))
            return "COALESCE($rendered ${if (comparison.op == "==") "=" else comparison.op} ?, FALSE)"
        }

        /** `call != constant`, which an absent result satisfies: the call is written out twice, its constants queued each time. */
        private fun callDiffers(tree: SqlExpr, bound: Any): String {
            val once = renderExpr(tree)
            val again = renderExpr(tree)
            binds.add(bound)
            return "($once IS NULL OR $again != ?)"
        }

        /** A registered predicate's call: true when its result is, and an absent result is false. */
        private fun renderPred(predicate: QF.Pred, alias: String, positive: Boolean): String {
            val tree = callTree(predicate.call, alias) ?: return degrade(positive)
            return "COALESCE(${renderExpr(tree)}, FALSE)"
        }

        private fun likeEscape(text: String): String = buildString {
            for (character in text) {
                if (character == '\\' || character == '%' || character == '_') append('\\')
                append(character)
            }
        }

        private fun degrade(positive: Boolean): String = if (positive) "TRUE" else "FALSE"

        /** The query constant as a value of the column's type, in [Row.fields]'
         *  vocabulary, or null where no value of that type equals the constant
         *  (a fraction against an integer column, an unparseable text against a
         *  uuid): the comparison is then the evaluator's. */
        private fun constantFor(constant: QConst, bareType: VType): Any? = when (constant) {
            is QConst.QNum -> numberFor(constant.v, bareType)
            is QConst.QText -> constant.v.takeIf { bareType == VType.Text }
            is QConst.QUuid -> constant.v.takeIf { bareType == VType.UuidT }
            is QConst.QDuration -> constant.v.takeIf { bareType == VType.DurationT }
            is QConst.QTime -> constant.v.takeIf { bareType == VType.TimeT }
            is QConst.QBytes -> constant.v.takeIf { bareType == VType.BytesT }
            is QConst.QBool -> constant.v.takeIf { bareType == VType.Bool }
            is QConst.QDate -> constant.v.takeIf { bareType == VType.DateT }
            is QConst.QDateTime -> constant.v.takeIf { bareType == VType.DateTimeT }
            // an instance constant (the query API's "this customer"): the row key
            is QConst.QRef -> (constant.ref.key.value as? Number)?.toLong().takeIf { bareType is VType.Inst }
            // an extension type's constant: the binding encodes it
            is QConst.QExt -> constant.value.takeIf { bareType is VType.Ext && bareType.qualifiedName == constant.type.qualifiedName }
        }

        /** A number constant against a number column: a whole number for an integer column, and none where the constant has a fraction. */
        private fun numberFor(number: BigDecimal, bareType: VType): Any? {
            if (bareType !is VType.Num) return null
            return if (isWholeNumber(bareType)) runCatching { number.longValueExact() }.getOrNull() else number
        }
    }

    /** The rows of [shape] under [where], and each collection field of all of them in one statement more. */
    private fun select(shape: String, where: String, bind: (PreparedStatement) -> Unit = {}): List<Row> = onConnection { connection ->
        val rows = connection.prepareStatement("""SELECT * FROM "$shape" $where ORDER BY id""").use { select ->
            bind(select)
            readRows(shape, select)
        }
        withCollections(connection, shape, rows, everyRow = where.isEmpty())
    }

    /** Runs [select] and reads every row of [shape] it returns, without their collection fields. */
    private fun readRows(shape: String, select: PreparedStatement): List<Row> =
        select.executeQuery().use { results ->
            buildList { while (results.next()) add(readRow(shape, results)) }
        }

    /** [rows] with their collection fields, each field read once for all of them; [everyRow] when the rows are the whole table. */
    private fun withCollections(connection: Connection, shape: String, rows: List<Row>, everyRow: Boolean): List<Row> {
        val collections = collectionsOf(shape)
        if (rows.isEmpty() || collections.isEmpty()) return rows
        val owners = if (everyRow) null else rows.map { row -> keyLong(row.key) }
        val membersByField = collections.associate { collection -> collection.name to readCollections(connection, shape, collection, owners) }
        return rows.map { row ->
            row.copy(fields = row.fields + membersByField.mapValues { (_, membersByOwner) -> membersByOwner[keyLong(row.key)].orEmpty() })
        }
    }

    /** The row's own columns; its collection fields are read for all rows of one statement together ([withCollections]). */
    private fun readRow(shape: String, results: ResultSet): Row {
        val key = results.getLong("id")
        val fields = buildMap {
            for (column in columnsOf(shape)) {
                if (results.getObject(column.name) == null) continue
                put(column.name, readColumn(column.type, column.name, results, column.union))
            }
        }
        return Row(shape, StoreKey(key), fields)
    }

    private fun readColumn(type: VType, name: String, results: ResultSet, union: Boolean): Any = when (type) {
        is VType.Optional -> readColumn(type.inner, name, results, union)
        // references leave the store as typed refs — the concrete shape from
        // the model, or from the discriminator when the model only says "union"
        is VType.Inst -> Ref.Persisted(
            if (union) results.getString(shapeColumn(name)) else type.shape,
            StoreKey(keyStorage.read(results, name) as Long),
        )
        else -> scalarColumn(type).read(results, name)
    }

    /**
     * A collection field's members for [owners], or for every owner when null (a scan), each
     * owner's in membership order (the junction's `pos` column): one statement per [keysPerStatement] owners.
     */
    private fun readCollections(connection: Connection, shape: String, collection: FieldCollection, owners: List<Long>?): Map<Long, List<Any>> {
        val membersByOwner = LinkedHashMap<Long, MutableList<Any>>()
        val table = junctionTable(shape, collection.name)
        if (owners == null) readMembers(connection, table, collection, "", emptyList(), membersByOwner)
        else owners.chunked(keysPerStatement).forEach { some ->
            readMembers(connection, table, collection, "WHERE owner IN (${placeholders(some.size)})", some, membersByOwner)
        }
        return membersByOwner
    }

    /** Adds to [membersByOwner] the members of the junction [table] under [where], bound with [owners]. */
    private fun readMembers(
        connection: Connection, table: String, collection: FieldCollection, where: String, owners: List<Long>,
        membersByOwner: MutableMap<Long, MutableList<Any>>,
    ) {
        connection.prepareStatement("""SELECT * FROM "$table" $where ORDER BY owner, pos""").use { select ->
            owners.forEachIndexed { index, owner -> select.setLong(index + 1, owner) }
            select.executeQuery().use { results ->
                while (results.next()) membersByOwner.getOrPut(results.getLong("owner")) { mutableListOf() }.add(readMember(collection, results))
            }
        }
    }

    private fun readMember(collection: FieldCollection, results: ResultSet): Any =
        if (collection.element is VType.Inst) Ref.Persisted(
            if (collection.union) results.getString("target_shape") else collection.element.shape,
            StoreKey(keyStorage.read(results, "target") as Long),
        )
        else readColumn(collection.element, "value", results, union = false)

    // ── the commit callback: the transaction's mutation set, atomically ──────

    /**
     * Runs inside the runtime's envelope, on the write unit the runtime began:
     * everything the transaction created, assigned, captured, or deleted lands
     * in that unit's one database transaction, and a failure here rolls the
     * whole Velle commit back (in-envelope failure, evaluation.md U2). The
     * runtime is the only caller; a commit with no write unit open is refused.
     *
     * Identity is minted here (Hydration.kt, StoreKey): each created row gets its table's next
     * key, and the assigned keys return in creation order. Creates arrive with
     * pending refs pointing backward, so inserting in list order always has
     * the referenced key already minted. Deletions go last, as the contract
     * orders them, so a reference at a deleted row that the same commit wrote
     * is emptied with every other ([delete]).
     */
    override fun onCommit(commit: CommitSet): List<StoreKey> {
        // the statements join the write unit's transaction, which the unit's end commits
        val unit = checkNotNull(current.get()) { "a commit arrived with no unit of work open: the runtime calls onCommit inside the write unit it began" }
        check(unit.kind == WorkKind.WRITE) { "a commit arrived inside a read unit of work" }
        return write(unit.connection, commit)
    }

    private fun write(connection: Connection, commit: CommitSet): List<StoreKey> {
        val minted = mutableListOf<StoreKey>()
        for (creation in commit.created) minted.add(insert(connection, creation, minted))
        for (assignment in commit.assigned) update(connection, assignment, minted)
        for (capture in commit.captured) upsertCapture(connection, capture, minted)
        for (retraction in commit.retracted) deleteCapture(connection, retraction)
        for (deletion in commit.deleted) delete(connection, deletion)
        return minted
    }

    /** A reference value as this store's key column: persisted refs carry
     *  their key, pending refs resolve against the keys minted so far. */
    private fun refKey(reference: Any, minted: List<StoreKey>): Long = when (reference) {
        is Ref.Persisted -> keyLong(reference.key)
        is Ref.Pending -> keyLong(minted[reference.index])
        else -> throw IllegalArgumentException("reference value is not a Ref: $reference")
    }

    private fun refShape(reference: Any): String =
        (reference as? RowRef)?.shape ?: throw IllegalArgumentException("reference value is not a Ref: $reference")

    /**
     * What one field's value binds to its columns, in [storedNames]' order: the
     * value, and for a union-typed reference the concrete shape after it.
     */
    private fun boundValues(column: FieldColumn, value: Any?, minted: List<StoreKey>): List<Any?> {
        val written = writeColumn(column.type, value, minted)
        return if (column.union) listOf(written, value?.let { reference -> refShape(reference) }) else listOf(written)
    }

    private fun insert(connection: Connection, creation: CommitSet.Creation, minted: List<StoreKey>): StoreKey {
        val columns = columnsOf(creation.shape)
        val names = columns.flatMap { column -> storedNames(column) }
        val bound = columns.flatMap { column -> boundValues(column, creation.fields[column.name], minted) }
        // the minted key comes back with the insert (standard SQL `RETURNING`)
        val sql = """INSERT INTO "${creation.shape}" (${quoted(names)}) VALUES (${placeholders(names.size)}) RETURNING id"""
        val key = connection.prepareStatement(sql).use { insert ->
            bound.forEachIndexed { index, value -> insert.setObject(index + 1, value) }
            insert.executeQuery().use { results -> results.next(); StoreKey(results.getLong(1)) }
        }
        for (collection in collectionsOf(creation.shape))
            writeCollection(connection, creation.shape, collection, keyLong(key), creation.fields[collection.name], minted)
        return key
    }

    private fun update(connection: Connection, assignment: CommitSet.Assign, minted: List<StoreKey>) {
        val shape = assignment.target.shape
        val key = keyLong(assignment.target.key)
        val collection = collectionsOf(shape).find { candidate -> candidate.name == assignment.field }
        if (collection != null) {
            // whole-set replacement: the junction rows become exactly the new set
            deleteByKey(connection, """DELETE FROM "${junctionTable(shape, collection.name)}" WHERE owner = ?""", key)
            writeCollection(connection, shape, collection, key, assignment.value, minted)
            return
        }
        val column = columnsOf(shape).first { candidate -> candidate.name == assignment.field }
        val assignments = storedNames(column).joinToString(", ") { name -> "\"$name\" = ?" }
        val bound = boundValues(column, assignment.value, minted)
        connection.prepareStatement("""UPDATE "$shape" SET $assignments WHERE id = ?""").use { update ->
            bound.forEachIndexed { index, value -> update.setObject(index + 1, value) }
            update.setLong(bound.size + 1, key)
            update.executeUpdate()
        }
    }

    private fun writeCollection(connection: Connection, shape: String, collection: FieldCollection, owner: Long, value: Any?, minted: List<StoreKey>) {
        val members = value as? List<*> ?: return // absent reads as the empty collection
        if (members.isEmpty()) return
        val table = junctionTable(shape, collection.name)
        val namesShape = collection.element is VType.Inst && collection.union
        val sql = when {
            collection.element !is VType.Inst -> """INSERT INTO "$table" (owner, pos, value) VALUES (?, ?, ?)"""
            collection.union -> """INSERT INTO "$table" (owner, pos, target, target_shape) VALUES (?, ?, ?, ?)"""
            else -> """INSERT INTO "$table" (owner, pos, target) VALUES (?, ?, ?)"""
        }
        connection.prepareStatement(sql).use { insert ->
            members.forEachIndexed { position, member ->
                insert.setLong(1, owner)
                insert.setLong(2, position.toLong())
                insert.setObject(3, writeColumn(collection.element, member, minted))
                if (namesShape) insert.setString(4, refShape(member!!))
                insert.addBatch()
            }
            insert.executeBatch()
        }
    }

    private fun upsertCapture(connection: Connection, capture: CommitSet.Capture, minted: List<StoreKey>) {
        val captureSchema = model.captureSchemas.first { schema -> schema.refinement == capture.refinement }
        val names = listOf("id") + captureSchema.props.map { remembered -> remembered.name }
        // re-entry overwrites the previous membership's memory (standard SQL upsert)
        val onConflict = if (captureSchema.props.isEmpty()) "DO NOTHING"
            else "DO UPDATE SET " + captureSchema.props.joinToString(", ") { remembered -> "\"${remembered.name}\" = excluded.\"${remembered.name}\"" }
        val sql = """INSERT INTO "${captureTable(capture.refinement)}" (${quoted(names)}) VALUES (${placeholders(names.size)}) ON CONFLICT (id) $onConflict"""
        connection.prepareStatement(sql).use { upsert ->
            upsert.setLong(1, refKey(capture.instance, minted))
            captureSchema.props.forEachIndexed { index, remembered ->
                upsert.setObject(index + 2, writeColumn(remembered.type, capture.values[remembered.name], minted))
            }
            upsert.executeUpdate()
        }
    }

    private fun deleteCapture(connection: Connection, retraction: CommitSet.Retraction) {
        deleteByKey(connection, """DELETE FROM "${captureTable(retraction.refinement)}" WHERE id = ?""", keyLong(retraction.instance.key))
    }

    /** Runs [sql], a DELETE naming one key. */
    private fun deleteByKey(connection: Connection, sql: String, key: Long) {
        connection.prepareStatement(sql).use { delete ->
            delete.setLong(1, key)
            delete.executeUpdate()
        }
    }

    /**
     * One statement that empties the stored references at a deleted row, bound with the
     * row's key and, where [namesShape], with the row's shape after it: a column or
     * junction table typed with a union points at rows of several tables, and a key is
     * only unique within one.
     */
    private data class EmptyingStatement(val sql: String, val namesShape: Boolean)

    /** For each shape, the statements that empty every stored reference at one of its rows: one per column and junction table the model says can point at the shape. */
    private val emptyingStatementsByShape: Map<String, List<EmptyingStatement>> by lazy {
        persistedShapes.associateWith { deletedShape -> emptyingStatements(deletedShape) }
    }

    private fun emptyingStatements(deletedShape: String): List<EmptyingStatement> =
        persistedShapes.flatMap { pointingShape -> columnEmptyings(pointingShape, deletedShape) + junctionEmptyings(pointingShape, deletedShape) } +
            captureEmptyings(deletedShape)

    private fun pointsAt(type: VType, deletedShape: String): Boolean {
        val declaredShape = type.instanceShape() ?: return false
        return deletedShape in model.basesOf(declaredShape)
    }

    /** A to-one column is set to NULL, and the discriminator column of a union-typed one with it. */
    private fun columnEmptyings(pointingShape: String, deletedShape: String): List<EmptyingStatement> =
        columnsOf(pointingShape).filter { column -> pointsAt(column.type, deletedShape) }.map { column ->
            val keyColumn = "\"${column.name}\""
            val discriminator = "\"${shapeColumn(column.name)}\""
            if (column.union) EmptyingStatement(
                """UPDATE "$pointingShape" SET $keyColumn = NULL, $discriminator = NULL WHERE $keyColumn = ? AND $discriminator = ?""",
                namesShape = true,
            )
            else EmptyingStatement("""UPDATE "$pointingShape" SET $keyColumn = NULL WHERE $keyColumn = ?""", namesShape = false)
        }

    /** A member's row leaves the junction table, so the record leaves the collection. */
    private fun junctionEmptyings(pointingShape: String, deletedShape: String): List<EmptyingStatement> =
        collectionsOf(pointingShape).filter { collection -> pointsAt(collection.element, deletedShape) }.map { collection ->
            val table = junctionTable(pointingShape, collection.name)
            if (collection.union) EmptyingStatement("""DELETE FROM "$table" WHERE target = ? AND target_shape = ?""", namesShape = true)
            else EmptyingStatement("""DELETE FROM "$table" WHERE target = ?""", namesShape = false)
        }

    /** A remembered reference is set to NULL. A capture table has no discriminator column, so one typed with a union is left as it is. */
    private fun captureEmptyings(deletedShape: String): List<EmptyingStatement> =
        model.captureSchemas.flatMap { captureSchema ->
            captureSchema.props
                .filter { remembered -> pointsAt(remembered.type, deletedShape) && remembered.type.instanceShape()?.let { declaredShape -> isUnion(declaredShape) } == false }
                .map { remembered ->
                    EmptyingStatement(
                        """UPDATE "${captureTable(captureSchema.refinement)}" SET "${remembered.name}" = NULL WHERE "${remembered.name}" = ?""",
                        namesShape = false,
                    )
                }
        }

    private fun emptyReferences(connection: Connection, emptying: EmptyingStatement, deleted: Ref.Persisted) {
        connection.prepareStatement(emptying.sql).use { statement ->
            statement.setLong(1, keyLong(deleted.key))
            if (emptying.namesShape) statement.setString(2, deleted.shape)
            statement.executeUpdate()
        }
    }

    /**
     * The row removal (Reference §12). Every stored reference at the row is
     * emptied first ([emptyingStatementsByShape]), so no surviving row is left
     * holding the key of a row that is gone. Then the row, its own collections,
     * and any capture memory keyed on it go. The key is never reused.
     */
    private fun delete(connection: Connection, deletion: CommitSet.Deletion) {
        val shape = deletion.target.shape
        val key = keyLong(deletion.target.key)
        for (emptying in emptyingStatementsByShape.getValue(shape)) emptyReferences(connection, emptying, deletion.target)
        deleteByKey(connection, """DELETE FROM "$shape" WHERE id = ?""", key)
        for (collection in collectionsOf(shape))
            deleteByKey(connection, """DELETE FROM "${junctionTable(shape, collection.name)}" WHERE owner = ?""", key)
        for (captureSchema in model.captureSchemas.filter { schema -> schema.base == shape })
            deleteByKey(connection, """DELETE FROM "${captureTable(captureSchema.refinement)}" WHERE id = ?""", key)
    }

    private fun writeColumn(type: VType, value: Any?, minted: List<StoreKey>): Any? = when {
        value == null -> null
        type is VType.Optional -> writeColumn(type.inner, value, minted)
        type is VType.Inst -> keyStorage.bind(refKey(value, minted))
        else -> scalarColumn(type).bind(value)
    }
}
