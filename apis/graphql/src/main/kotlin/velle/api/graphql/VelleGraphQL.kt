package velle.api.graphql

import graphql.ExecutionResult
import graphql.GraphQL
import graphql.GraphQLContext
import graphql.GraphqlErrorBuilder
import graphql.Scalars
import graphql.execution.AsyncExecutionStrategy
import graphql.execution.AsyncSerialExecutionStrategy
import graphql.execution.CoercedVariables
import graphql.execution.DataFetcherExceptionHandler
import graphql.execution.DataFetcherExceptionHandlerParameters
import graphql.execution.DataFetcherExceptionHandlerResult
import graphql.execution.ExecutionContext
import graphql.execution.ExecutionStrategyParameters
import graphql.language.BooleanValue
import graphql.language.FloatValue
import graphql.language.IntValue
import graphql.language.StringValue
import graphql.language.Value
import graphql.schema.SelectedField
import graphql.schema.Coercing
import graphql.schema.CoercingParseLiteralException
import graphql.schema.CoercingParseValueException
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import graphql.schema.FieldCoordinates
import graphql.schema.GraphQLArgument
import graphql.schema.GraphQLCodeRegistry
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLInputObjectField
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLInputType
import graphql.schema.GraphQLInterfaceType
import graphql.schema.GraphQLList
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLOutputType
import graphql.schema.GraphQLScalarType
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLType
import graphql.schema.GraphQLTypeReference
import graphql.schema.GraphQLUnionType
import graphql.schema.TypeResolver
import velle.ClosureEdge
import velle.MemberInfo
import velle.Ref
import velle.RelType
import velle.ScalarType
import velle.StoredProp
import velle.TypeRef
import velle.TypeRegistry
import velle.VType
import velle.api.ApiException
import velle.api.Describe
import velle.api.VelleApi
import java.math.BigDecimal
import java.util.Locale
import java.util.concurrent.CompletableFuture

/**
 * A GraphQL schema for a running system, built from its model over the API layer
 * ([VelleApi]). One object type per readable shape and refinement with every
 * member and every `is<Refinement>` flag; a union type per static union; for each
 * root a fetch by key, a paged query in the spec's own predicate language, and a
 * count; one mutation per act taking an input type shaped as the act's input is,
 * answering with the acceptance or the refusal as data.
 *
 * The actor: a transport puts the resolved actor, a `Ref`, in the GraphQL context
 * under [ACTOR]; every mutation commits as it and every query reads as it.
 */
object VelleGraphQL {

    /** The GraphQL context key under which a transport places the request's actor, a `Ref<*>`. */
    const val ACTOR = "velle.actor"

    /** The schema for [api]; with [ticks], a `tick(schedule)` mutation beside the acts. */
    fun schema(api: VelleApi, ticks: Boolean = false): GraphQLSchema = SchemaBuilder(api, ticks).build()

    /** A ready engine over [schema], refusing a bad request as a GraphQL error with its kind in `extensions`. */
    fun graphQL(api: VelleApi, ticks: Boolean = false): GraphQL =
        configure(GraphQL.newGraphQL(schema(api, ticks)), api).build()

    /**
     * What an engine over [schema] needs beside the schema, for a host that builds the engine itself:
     * a bad request refused through [ExceptionHandler], and one request run in one scope of the runtime,
     * so every read of the request is answered from one snapshot and a record it reaches twice is fetched once.
     */
    fun configure(engine: GraphQL.Builder, api: VelleApi): GraphQL.Builder = engine
        .defaultDataFetcherExceptionHandler(ExceptionHandler)
        .queryExecutionStrategy(QueriesInOneScope(api))
        .mutationExecutionStrategy(MutationsInOneScope(api))

    /** A query request's fetchers, all inside one scope. */
    private class QueriesInOneScope(private val api: VelleApi) : AsyncExecutionStrategy(ExceptionHandler) {
        override fun execute(context: ExecutionContext, parameters: ExecutionStrategyParameters): CompletableFuture<ExecutionResult> =
            api.scope { super.execute(context, parameters) }
    }

    /** A mutation request's fetchers, one after another as GraphQL requires, all inside one scope. */
    private class MutationsInOneScope(private val api: VelleApi) : AsyncSerialExecutionStrategy(ExceptionHandler) {
        override fun execute(context: ExecutionContext, parameters: ExecutionStrategyParameters): CompletableFuture<ExecutionResult> =
            api.scope { super.execute(context, parameters) }
    }

    /** Turns an [ApiException] into a GraphQL error carrying `extensions.kind` (BAD_REQUEST, NOT_FOUND, UNAUTHORIZED) and `extensions.details`. */
    object ExceptionHandler : DataFetcherExceptionHandler {
        override fun handleException(params: DataFetcherExceptionHandlerParameters): CompletableFuture<DataFetcherExceptionHandlerResult> {
            val e = params.exception
            val builder = GraphqlErrorBuilder.newError(params.dataFetchingEnvironment).message(e.message ?: e::class.simpleName)
            if (e is ApiException) builder.extensions(mapOf("kind" to e.kind.name, "details" to e.details))
            return CompletableFuture.completedFuture(DataFetcherExceptionHandlerResult.newResult().error(builder.build()).build())
        }
    }

    fun actorOf(env: DataFetchingEnvironment): Ref<*>? = env.graphQlContext.get<Ref<*>?>(ACTOR)
}

private class SchemaBuilder(private val api: VelleApi, private val ticks: Boolean) {

    private val model = api.model
    private val describe = Describe(model)
    private val registry = GraphQLCodeRegistry.newCodeRegistry()
    private val additional = linkedMapOf<String, GraphQLType>()
    private val scalars = linkedMapOf<String, GraphQLScalarType>()

    fun build(): GraphQLSchema {
        val readable = describe.readable()
        for (r in readable) additional[r] = when {
            r in model.sharedShapes -> interfaceType(r)
            model.isCrossBase(r) -> union(r)
            else -> objectType(r)
        }
        additional["Reference"] = referenceType()
        additional["RefInput"] = refInput()
        additional["Snapshot"] = snapshotType()
        additional["EvalStep"] = evalStepType()
        additional["Rejection"] = rejectionType()
        val schema = GraphQLSchema.newSchema()
            .query(queryType(readable))
            .mutation(mutationType())
            .codeRegistry(registry.build())
        additional.values.forEach { schema.additionalType(it) }
        scalars.values.forEach { schema.additionalType(it) }
        return schema.build()
    }

    // ── naming ──────────────────────────────────────────────────────────────────

    private fun lower(s: String) = s.replaceFirstChar { it.lowercase() }
    private fun plural(s: String) = lower(s) + "s"
    private fun memberField(name: String) = if (name == "shape" || name == "key") "${name}_" else name

    // ── scalars: the wire form in and out ───────────────────────────────────────

    /** A scalar that carries the wire form: what [velle.api.Wire] writes, read back by it on the way in. */
    private fun wireScalar(scalar: WireScalar): GraphQLScalarType = scalars.getOrPut(scalar.graphQLName) {
        val name = scalar.graphQLName
        val velleName = scalar.velleName
        GraphQLScalarType.newScalar().name(name).description(scalar.description).coercing(object : Coercing<Any, Any> {
            override fun serialize(v: Any, ctx: GraphQLContext, locale: Locale): Any = v
            override fun parseValue(input: Any, ctx: GraphQLContext, locale: Locale): Any {
                try { api.wire.scalar(input, velleName, name) } catch (e: ApiException) { throw CoercingParseValueException(e.message) }
                return input
            }
            override fun parseLiteral(input: Value<*>, variables: CoercedVariables, ctx: GraphQLContext, locale: Locale): Any {
                val v: Any = when (input) {
                    is StringValue -> input.value as Any
                    is IntValue -> input.value as Any
                    is FloatValue -> input.value as Any
                    is BooleanValue -> input.isValue as Any
                    else -> throw CoercingParseLiteralException("a $name is written as a string or a number")
                }
                try { api.wire.scalar(v, velleName, name) } catch (e: ApiException) { throw CoercingParseLiteralException(e.message) }
                return v
            }
            override fun valueToLiteral(input: Any, ctx: GraphQLContext, locale: Locale): Value<*> =
                if (input is Number) FloatValue.newFloatValue(BigDecimal(input.toString())).build() else StringValue.newStringValue(input.toString()).build()
        }).build()
    }

    private val json get() = scalars.getOrPut("JSON") {
        GraphQLScalarType.newScalar().name("JSON").description("Any JSON value, passed through as is.").coercing(object : Coercing<Any, Any> {
            override fun serialize(v: Any, ctx: GraphQLContext, locale: Locale): Any = v
            override fun parseValue(input: Any, ctx: GraphQLContext, locale: Locale): Any = input
            override fun parseLiteral(input: Value<*>, variables: CoercedVariables, ctx: GraphQLContext, locale: Locale): Any =
                throw CoercingParseLiteralException("JSON is read from variables only")
            override fun valueToLiteral(input: Any, ctx: GraphQLContext, locale: Locale): Value<*> = StringValue.newStringValue(input.toString()).build()
        }).build()
    }

    private fun scalarType(name: String): GraphQLScalarType =
        BUILT_IN_SCALARS[name] ?: wireScalar(WIRE_SCALARS[name] ?: WireScalar(name, name, "A value of the extension type $name, as its own text."))

    // ── output types ────────────────────────────────────────────────────────────

    /** The field type for a member of [type]: nullable when the member is optional or its visibility is gated, else non-null. */
    private fun outType(type: VType, gated: Boolean): GraphQLOutputType {
        val bare = bareOutType(bareType(type))
        return if (type is VType.Optional || gated) bare else GraphQLNonNull.nonNull(bare)
    }

    private fun bareOutType(type: VType): GraphQLOutputType = when (type) {
        is VType.Inst -> GraphQLTypeReference.typeRef(type.shape)
        is VType.Coll -> GraphQLList.list(GraphQLNonNull.nonNull(GraphQLTypeReference.typeRef(type.shape)))
        is VType.CollS -> GraphQLList.list(GraphQLNonNull.nonNull(scalarType(type.name)))
        VType.Id -> Scalars.GraphQLID
        else -> scalarType(describe.scalarName(type))
    }

    private fun bareType(type: VType): VType = (type as? VType.Optional)?.inner ?: type

    /** [read] a record, or null when the API says there is no such record; any other refusal passes through. */
    private fun <T> readOrAbsent(read: () -> T): T? =
        try { read() } catch (refusal: ApiException) { if (refusal.kind == ApiException.Kind.NOT_FOUND) null else throw refusal }

    private fun objectType(scope: String): GraphQLObjectType {
        val type = GraphQLObjectType.newObject().name(scope)
        type.description(if (scope in model.refinements) "The refinement $scope: a ${model.basesOf(scope).joinToString(" or ")} read as a member of it." else "The shape $scope.")
        type.field { it.name("shape").type(GraphQLNonNull.nonNull(Scalars.GraphQLString)).description("The record's own shape.") }
        type.field { it.name("key").type(GraphQLNonNull.nonNull(Scalars.GraphQLID)).description("The store's key for the record.") }
        for (member in model.membersOf(scope).values) {
            if (member.name == "id" || member.transient) continue // a transient field is never in the record (Reference §4)
            val field = memberField(member.name)
            type.field { it.name(field).type(outType(member.type, member.visible.isNotEmpty())) }
            memberFetcher(member, field)?.let { registry.dataFetcher(FieldCoordinates.coordinates(scope, field), it) }
        }
        if (scope in model.shapes) for (flag in api.wire.flagsOf(scope))
            type.field { it.name("is$flag").type(GraphQLNonNull.nonNull(Scalars.GraphQLBoolean)).description("Is the record a $flag now?") }
        // a shape taken with `is` is an interface the taker implements (Reference §9, "Sharing a field across shapes")
        for (taken in model.sharedShapes.keys.filter { scope in model.basesOf(it) && it in describe.readable() })
            type.withInterface(GraphQLTypeReference.typeRef(taken))
        return type.build()
    }

    /** A shape other shapes take with `is` (Reference §9): an interface carrying its own members, which every taker's type implements. */
    private fun interfaceType(name: String): GraphQLInterfaceType {
        val type = GraphQLInterfaceType.newInterface().name(name)
        type.description("The shape $name, which ${model.basesOf(name).sorted().joinToString(" and ")} take: a record of any of them, read as a $name.")
        type.field { it.name("shape").type(GraphQLNonNull.nonNull(Scalars.GraphQLString)).description("The record's own shape.") }
        type.field { it.name("key").type(GraphQLNonNull.nonNull(Scalars.GraphQLID)).description("The store's key for the record.") }
        for (member in model.membersOf(name).values) {
            if (member.name == "id" || member.transient) continue
            type.field { it.name(memberField(member.name)).type(outType(member.type, member.visible.isNotEmpty())) }
        }
        registry.typeResolver(name, TypeResolver { env -> env.schema.getObjectType((env.getObject<Map<String, Any?>>())["shape"] as String) })
        return type.build()
    }

    /**
     * How [field] is read from the record's wire map: a reference is followed to its record, a collection
     * of references to its records, and a renamed member is read under its own name. Null leaves the
     * default fetcher, which reads the map under [field].
     */
    private fun memberFetcher(member: MemberInfo, field: String): DataFetcher<*>? = when (bareType(member.type)) {
        is VType.Inst -> DataFetcher { env -> referencedRecord(env, member.name) }
        is VType.Coll -> DataFetcher { env -> referencedRecords(env, member.name) }
        else -> if (field != member.name) DataFetcher { env -> env.getSource<Map<String, Any?>>()!![member.name] } else null
    }

    private fun referencedRecord(env: DataFetchingEnvironment, member: String): Any? {
        val ref = env.getSource<Map<String, Any?>>()!![member] as? Map<*, *> ?: return null
        return readOrAbsent { api.read(api.refTo(ref["shape"] as String, ref["key"]!!), VelleGraphQL.actorOf(env), selectedMembers(env.selectionSet.immediateFields)) }
    }

    /**
     * The members and flags the query asks of a record, as [velle.api.Wire.record] names them, so
     * nothing else of the record is evaluated. A field the schema renamed is named as the member is.
     */
    private fun selectedMembers(fields: List<SelectedField>): Set<String> =
        fields.map { field -> field.name }.flatMap { name -> listOf(name, name.removeSuffix("_")) }.toSet()

    private fun referencedRecords(env: DataFetchingEnvironment, member: String): List<Any?> {
        val refs = (env.getSource<Map<String, Any?>>()!![member] as? List<*>).orEmpty()
            .map { ref -> (ref as Map<*, *>).let { api.refTo(it["shape"] as String, it["key"]!!) } }
        return api.readEach(refs, VelleGraphQL.actorOf(env), selectedMembers(env.selectionSet.immediateFields)).filterNotNull()
    }

    private fun union(name: String): GraphQLUnionType {
        val b = GraphQLUnionType.newUnionType().name(name).description("The union $name: a record of one of ${model.basesOf(name).sorted().joinToString(" or ")}.")
        for (op in model.basesOf(name).sorted()) b.possibleType(GraphQLTypeReference.typeRef(op))
        registry.typeResolver(name, TypeResolver { env -> env.schema.getObjectType((env.getObject<Map<String, Any?>>())["shape"] as String) })
        return b.build()
    }

    private fun referenceType() = GraphQLObjectType.newObject().name("Reference").description("A record's durable name: its shape and its store key.")
        .field { it.name("shape").type(GraphQLNonNull.nonNull(Scalars.GraphQLString)) }
        .field { it.name("key").type(GraphQLNonNull.nonNull(Scalars.GraphQLID)) }
        .build()

    private fun snapshotType() = GraphQLObjectType.newObject().name("Snapshot").description("A record as a rejection saw it: its reference and its stored fields.")
        .field { it.name("shape").type(GraphQLNonNull.nonNull(Scalars.GraphQLString)) }
        .field { it.name("key").type(GraphQLNonNull.nonNull(Scalars.GraphQLID)) }
        .field { it.name("fields").type(GraphQLNonNull.nonNull(json)) }
        .build()

    private fun evalStepType() = GraphQLObjectType.newObject().name("EvalStep").description("One step of a prohibition's evaluation: a member read or a refinement test.")
        .field { it.name("record").type(GraphQLNonNull.nonNull(GraphQLTypeReference.typeRef("Reference"))) }
        .field { it.name("member").type(Scalars.GraphQLString) }
        .field { it.name("value").type(json) }
        .field { it.name("refinement").type(Scalars.GraphQLString) }
        .field { it.name("isMember").type(Scalars.GraphQLBoolean) }
        .also { registry.dataFetcher(FieldCoordinates.coordinates("EvalStep", "isMember"), DataFetcher { env -> env.getSource<Map<String, Any?>>()!!["member"] as? Boolean }) }
        .also { registry.dataFetcher(FieldCoordinates.coordinates("EvalStep", "member"), DataFetcher { env -> env.getSource<Map<String, Any?>>()!!["member"] as? String }) }
        .build()

    private fun rejectionType(): GraphQLObjectType {
        val snapshot = GraphQLTypeReference.typeRef("Snapshot")
        val snapshots = GraphQLNonNull.nonNull(GraphQLList.list(GraphQLNonNull.nonNull(snapshot)))
        return GraphQLObjectType.newObject().name("Rejection").description("Why a never refused the transaction, as data (Reference §21).")
            .field { it.name("never").type(GraphQLNonNull.nonNull(Scalars.GraphQLInt)) }
            .field { it.name("prohibition").type(GraphQLNonNull.nonNull(Scalars.GraphQLString)) }
            .field { it.name("declaration").type(GraphQLNonNull.nonNull(Scalars.GraphQLString)) }
            .field { it.name("requirement").type(Scalars.GraphQLString) }
            .field { it.name("targetShape").type(GraphQLNonNull.nonNull(Scalars.GraphQLString)) }
            .field { it.name("act").type(snapshot) }
            .field { it.name("rule").type(Scalars.GraphQLString) }
            .field { it.name("schedule").type(Scalars.GraphQLString) }
            .field { it.name("subject").type(snapshot) }
            .field { it.name("produced").type(snapshots) }
            .field { it.name("violating").type(snapshots) }
            .field { it.name("pivots").type(snapshots) }
            .field { it.name("witnesses").type(snapshots) }
            .field { it.name("evaluation").type(GraphQLNonNull.nonNull(GraphQLList.list(GraphQLNonNull.nonNull(GraphQLTypeReference.typeRef("EvalStep"))))) }
            .field { it.name("today").type(Scalars.GraphQLString) }
            .field { it.name("message").type(GraphQLNonNull.nonNull(Scalars.GraphQLString)) }
            .build()
    }

    // ── queries ─────────────────────────────────────────────────────────────────

    private fun queryType(readable: List<String>): GraphQLObjectType {
        val q = GraphQLObjectType.newObject().name("Query")
        for (r in readable) {
            val one = lower(r)
            val many = plural(r)
            if (!model.isCrossBase(r)) {
                q.field { it.name(one).description("The $r with this key, or null when there is none or it is not shown to the actor.")
                    .argument(GraphQLArgument.newArgument().name("key").type(GraphQLNonNull.nonNull(Scalars.GraphQLID)))
                    .type(GraphQLTypeReference.typeRef(r)) }
                registry.dataFetcher(FieldCoordinates.coordinates("Query", one), DataFetcher { env ->
                    try { api.read(r, env.getArgument<String>("key")!!, VelleGraphQL.actorOf(env), selectedMembers(env.selectionSet.immediateFields)) }
                    catch (e: ApiException) { if (e.kind == ApiException.Kind.NOT_FOUND) null else throw e }
                })
            }
            val page = GraphQLObjectType.newObject().name("${r}Page").description("One page of $r records and the cursor for the next.")
                .field { it.name("items").type(GraphQLNonNull.nonNull(GraphQLList.list(GraphQLNonNull.nonNull(GraphQLTypeReference.typeRef(r))))) }
                .field { it.name("next").type(Scalars.GraphQLString).description("Pass as `after` for the next page; null when this was the last.") }
                .build()
            additional[page.name] = page
            q.field { it.name(many).description("The $r records satisfying `where`, a predicate in the spec's own language over the members of $r, in the order `orderBy` names (\"due\", \"-total\"), or in the order of their keys when it names none.")
                .argument(GraphQLArgument.newArgument().name("where").type(Scalars.GraphQLString))
                .argument(GraphQLArgument.newArgument().name("orderBy").type(GraphQLList.list(GraphQLNonNull.nonNull(Scalars.GraphQLString))))
                .argument(GraphQLArgument.newArgument().name("first").type(Scalars.GraphQLInt))
                .argument(GraphQLArgument.newArgument().name("after").type(Scalars.GraphQLString))
                .type(GraphQLNonNull.nonNull(page)) }
            registry.dataFetcher(FieldCoordinates.coordinates("Query", many), DataFetcher { env ->
                api.query(r, env.getArgument("where"), env.getArgument<List<String>>("orderBy").orEmpty(), env.getArgument("first"), env.getArgument("after"), VelleGraphQL.actorOf(env), selectedMembers(env.selectionSet.getFields("items/*")))
            })
            q.field { it.name("${one}Count").description("How many $r records satisfy `where`.")
                .argument(GraphQLArgument.newArgument().name("where").type(Scalars.GraphQLString))
                .type(GraphQLNonNull.nonNull(Scalars.GraphQLInt)) }
            registry.dataFetcher(FieldCoordinates.coordinates("Query", "${one}Count"), DataFetcher { env ->
                api.count(r, env.getArgument("where"), VelleGraphQL.actorOf(env))
            })
        }
        latestTicksField(q, readable)
        return q.build()
    }

    /** `ticks`: every schedule with the moment it last ran. A tick is no shape (Reference §17), so the field is built by hand. */
    private fun latestTicksField(query: GraphQLObjectType.Builder, readable: List<String>) {
        val field = "ticks"
        val type = "LatestTick"
        val claimant = readable.firstOrNull { shape -> shape == type || field in setOf(lower(shape), plural(shape), "${lower(shape)}Count") }
        if (claimant != null) throw IllegalStateException(
            "the spec's '$claimant' and the moment each schedule last ran both want the GraphQL name '$field' or '$type'; rename '$claimant' to serve this spec over GraphQL"
        )
        val latestTick = GraphQLObjectType.newObject().name(type).description("A schedule and the moment it last ran.")
            .field { it.name("schedule").type(GraphQLNonNull.nonNull(Scalars.GraphQLString)) }
            .field { it.name("at").type(Scalars.GraphQLString).description("Null when the schedule has never run.") }
            .build()
        additional[type] = latestTick
        query.field { it.name(field).description("Every schedule with the moment it last ran: ${api.schedules}.")
            .type(GraphQLNonNull.nonNull(GraphQLList.list(GraphQLNonNull.nonNull(latestTick)))) }
        registry.dataFetcher(FieldCoordinates.coordinates("Query", field), DataFetcher { api.latestTicks() })
    }

    // ── mutations: one per act ──────────────────────────────────────────────────

    private fun mutationType(): GraphQLObjectType {
        val m = GraphQLObjectType.newObject().name("Mutation")
        for (act in model.exposed) {
            val input = actInput(act)
            val outcome = outcomeType(act)
            m.field { it.name(lower(act)).description(actDescription(act))
                .argument(GraphQLArgument.newArgument().name("input").type(GraphQLNonNull.nonNull(input)))
                .argument(GraphQLArgument.newArgument().name("dryRun").type(Scalars.GraphQLBoolean).defaultValueProgrammatic(false)
                    .description("Answer what a commit would answer now, for what runs inside the commit, and keep nothing. No promise: another commit can land first."))
                .type(GraphQLNonNull.nonNull(outcome)) }
            registry.dataFetcher(FieldCoordinates.coordinates("Mutation", lower(act)), DataFetcher { env ->
                val body = toWire(env.getArgument<Map<String, Any?>>("input")!!) as Map<String, Any?>
                if (env.getArgument<Boolean>("dryRun") == true) api.check(act, body, VelleGraphQL.actorOf(env))
                else api.commit(act, body, VelleGraphQL.actorOf(env))
            })
        }
        if (ticks) {
            val tick = GraphQLObjectType.newObject().name("TickOutcome").description("A tick's outcome: the schedule, the moment, and every firing a never rejected.")
                .field { it.name("schedule").type(GraphQLNonNull.nonNull(Scalars.GraphQLString)) }
                .field { it.name("at").type(GraphQLNonNull.nonNull(Scalars.GraphQLString)) }
                .field { it.name("rejections").type(GraphQLNonNull.nonNull(GraphQLList.list(GraphQLNonNull.nonNull(GraphQLTypeReference.typeRef("Rejection"))))) }
                .build()
            m.field { it.name("tick").description("Run every rule on a schedule now: one of ${api.schedules}.")
                .argument(GraphQLArgument.newArgument().name("schedule").type(GraphQLNonNull.nonNull(Scalars.GraphQLString)))
                .type(GraphQLNonNull.nonNull(tick)) }
            registry.dataFetcher(FieldCoordinates.coordinates("Mutation", "tick"), DataFetcher { env -> api.tick(env.getArgument<String>("schedule")!!) })
        }
        return m.build()
    }

    private fun actDescription(act: String): String {
        val actor = model.shapes.getValue(act).actor
        return buildString {
            append("Commit the act $act")
            if (act in model.transients) append(", which leaves no record of its own") else append(", which becomes a $act record")
            if (actor != null) append("; committed by the request's actor, a ${actor.shape}")
            append(".")
        }
    }

    private fun outcomeType(act: String): GraphQLObjectType {
        val b = GraphQLObjectType.newObject().name("${act}Outcome").description("The commit's verdict: accepted with the record it became, or refused with the reason and the rejection as data.")
            .field { it.name("accepted").type(GraphQLNonNull.nonNull(Scalars.GraphQLBoolean)) }
            .field { it.name("ref").type(GraphQLTypeReference.typeRef("Reference")).description("The record the act became; null for a transient act, a refusal, and a dry run.") }
            .field { it.name("reason").type(Scalars.GraphQLString) }
            .field { it.name("rejection").type(GraphQLTypeReference.typeRef("Rejection")) }
        if (act !in model.transients) {
            b.field { it.name("record").type(GraphQLTypeReference.typeRef(act)).description("The $act record, read back after the commit.") }
            registry.dataFetcher(FieldCoordinates.coordinates("${act}Outcome", "record"), DataFetcher { env ->
                val ref = env.getSource<Map<String, Any?>>()!!["ref"] as? Map<*, *> ?: return@DataFetcher null
                readOrAbsent { api.read(act, ref["key"]!!, VelleGraphQL.actorOf(env), selectedMembers(env.selectionSet.immediateFields)) }
            })
        }
        return b.build()
    }

    // ── input types: the act's input, its parts, its nested new values ──────────

    private fun refInput() = GraphQLInputObjectType.newInputObject().name("RefInput")
        .description("A reference the committer supplies: a record on the books by `key` (and `shape` when the field's type is a union), or `new` naming a sibling value in this same input tagged `asNew`.")
        .field { it.name("shape").type(Scalars.GraphQLString) }
        .field { it.name("key").type(Scalars.GraphQLID) }
        .field { it.name("new").type(Scalars.GraphQLInt) }
        .build()

    private fun actInput(act: String): GraphQLInputObjectType {
        val edges = model.closures[act].orEmpty()
        val b = GraphQLInputObjectType.newInputObject().name("${act}Input").description("The fields the committer supplies for $act.")
        inputFields(act, act, edges, backRef = null, nested = false).forEach { b.field(it) }
        return b.build()
    }

    /** The input type for a [shape] arriving nested inside [act], as a part or as a value supplied by value. */
    private fun nestedInput(act: String, shape: String, edges: List<ClosureEdge>, backRef: String?): GraphQLInputType {
        val name = "$act${shape}Input"
        additional.getOrPut(name) {
            val b = GraphQLInputObjectType.newInputObject().name(name).description("A new $shape created with the $act it arrives in.")
            inputFields(act, shape, edges, backRef, nested = true).forEach { b.field(it) }
            b.build()
        }
        return GraphQLTypeReference.typeRef(name)
    }

    private fun inputFields(act: String, shape: String, edges: List<ClosureEdge>, backRef: String?, nested: Boolean): List<GraphQLInputObjectField> {
        val decl = model.shapes.getValue(shape)
        val actor = decl.actor?.name
        val siblings = model.siblingFields(act, shape, edges, backRef).map { it.name }.toSet()
        val supplied = decl.members.filterIsInstance<StoredProp>().filter { it.name != backRef && it.name != actor && it.initially == null }
        val fields = supplied.map { prop -> storedField(act, prop, siblings, edges.find { it.byValue && it.prop == prop.name }) } +
            edges.filter { !it.byValue }.map { partsField(act, shape, it) }
        return if (nested) fields + asNewField() else fields
    }

    /** A stored field as the committer supplies it, non-null unless the type is optional or many, the field is supplied by value, or it is given `initially`. */
    private fun storedField(act: String, prop: StoredProp, siblings: Set<String>, byValue: ClosureEdge?): GraphQLInputObjectField {
        val type = storedInputType(act, prop, siblings, byValue)
        val nullable = ((prop.type.isOptional || prop.type.isMany) && !prop.initiallyRequired) || byValue != null
        return GraphQLInputObjectField.newInputObjectField().name(prop.name).type(if (nullable) type else GraphQLNonNull.nonNull(type)).build()
    }

    /** A by-value `one` as its choice type; a reference as a `RefInput` when it may name a record created with the act or its shape is a refinement (a static union is one), else an ID; a value as its scalar; a list when `many`. */
    private fun storedInputType(act: String, prop: StoredProp, siblings: Set<String>, byValue: ClosureEdge?): GraphQLInputType {
        val one: GraphQLInputType = when (val type = prop.type) {
            is ScalarType -> scalarType(type.name)
            is RelType -> when {
                byValue != null -> byValueInput(act, byValue)
                prop.name in siblings || type.shape in model.refinements -> GraphQLTypeReference.typeRef("RefInput")
                else -> Scalars.GraphQLID
            }
        }
        return if (prop.type.isMany) GraphQLList.list(GraphQLNonNull.nonNull(one)) else one
    }

    private fun partsField(act: String, shape: String, edge: ClosureEdge): GraphQLInputObjectField {
        val element = if (edge.partShape in model.shapes) nestedInput(act, edge.partShape, edge.childrenFor(edge.partShape), edge.backRef)
        else mixedPartInput(act, edge)
        return GraphQLInputObjectField.newInputObjectField().name(edge.prop)
            .description("The ${edge.partShape} parts created with this ${shape}.")
            .type(GraphQLList.list(GraphQLNonNull.nonNull(element))).build()
    }

    /** One part of an edge over a shape several shapes take with `is`: exactly one `new<Taker>` carrying the part, which names its shape. */
    private fun mixedPartInput(act: String, edge: ClosureEdge): GraphQLInputType {
        val name = "$act${edge.prop.replaceFirstChar { it.uppercase() }}PartInput"
        additional.getOrPut(name) {
            val b = GraphQLInputObjectType.newInputObject().name(name)
                .description("One of the ${edge.prop} of a $act: exactly one `new...`, naming which shape that takes ${edge.partShape} the part is.")
            for (taker in model.basesOf(edge.partShape).sorted())
                b.field { it.name("new$taker").type(nestedInput(act, taker, edge.childrenFor(taker), edge.backRef)) }
            b.build()
        }
        return GraphQLTypeReference.typeRef(name)
    }

    private fun asNewField(): GraphQLInputObjectField = GraphQLInputObjectField.newInputObjectField().name("asNew").type(Scalars.GraphQLInt)
        .description("Tag this value with a number so a sibling field may point at it with `{new: n}`.").build()

    private val TypeRef.isOptional: Boolean get() = when (this) { is ScalarType -> optional; is RelType -> optional }
    private val TypeRef.isMany: Boolean get() = when (this) { is ScalarType -> many; is RelType -> many }

    /** A `one` field named in `with`: `existing` names a record on the books, `new<Operand>` carries a value created here. */
    private fun byValueInput(act: String, edge: ClosureEdge): GraphQLInputType {
        val name = "$act${edge.prop.replaceFirstChar { it.uppercase() }}Input"
        additional.getOrPut(name) {
            val b = GraphQLInputObjectType.newInputObject().name(name)
                .description("The ${edge.prop} of a $act: `existing` for a record on the books, or exactly one `new...` for a record created with the act.")
                .field { it.name("existing").type(GraphQLTypeReference.typeRef("RefInput")) }
            for (operand in model.basesOf(edge.partShape).sorted())
                b.field { it.name("new$operand").type(nestedInput(act, operand, edge.childrenFor(operand), backRef = null)) }
            b.build()
        }
        return GraphQLTypeReference.typeRef(name)
    }

    /**
     * A coerced GraphQL input as the API layer's wire map: nulls dropped, `asNew`
     * as `@new`, a by-value field's `existing` as the reference and its `new<Operand>`
     * as the nested value tagged with its shape, and a `RefInput` as `{shape, key, new}`.
     */
    private fun toWire(value: Any?): Any? = when (value) {
        null -> null
        is Map<*, *> -> mapToWire(value.entries.filter { it.value != null })
        is List<*> -> value.map { toWire(it) }
        else -> value
    }

    /** The entries of one input object with their nulls dropped: a by-value field's single choice, or a plain object. */
    private fun mapToWire(entries: List<Map.Entry<Any?, Any?>>): Any? {
        val choices = entries.filter { isByValueChoice(it) }
        if (choices.isEmpty()) return entries.associate { (key, value) -> (if (key == "asNew") "@new" else key as String) to toWire(value) }
        if (entries.size != 1) throw ApiException.badRequest("a by-value field takes `existing` or exactly one `new...`, not ${entries.map { it.key }}")
        val choice = choices.single()
        if (choice.key == "existing") return toWire(choice.value)
        @Suppress("UNCHECKED_CAST")
        return (toWire(choice.value) as Map<String, Any?>) + ("@shape" to (choice.key as String).removePrefix("new"))
    }

    /** `existing`, or a `new<Operand>` carrying an object: the one entry a by-value field is written with. */
    private fun isByValueChoice(entry: Map.Entry<Any?, Any?>): Boolean =
        entry.key == "existing" || ((entry.key as String).startsWith("new") && entry.key != "new" && entry.value is Map<*, *>)
}

/** A Velle scalar carried as its own GraphQL scalar: the GraphQL name, the Velle type [velle.api.Wire] reads it as, and the schema's description of it. */
private class WireScalar(val graphQLName: String, val velleName: String, val description: String)

private val BUILT_IN_SCALARS: Map<String, GraphQLScalarType> = mapOf(
    "text" to Scalars.GraphQLString,
    "boolean" to Scalars.GraphQLBoolean,
    "integer" to Scalars.GraphQLInt,
)

private val DECIMAL = WireScalar("Decimal", "decimal", "An exact decimal number, as a JSON number.")

/** The Velle scalars with a wire form of their own, by Velle type name; any other name is an extension type. */
private val WIRE_SCALARS: Map<String, WireScalar> = mapOf(
    "long" to WireScalar("Long", "long", "A whole number beyond 32 bits, as a JSON number."),
    "decimal" to DECIMAL,
    "double" to DECIMAL,
    "Date" to WireScalar("Date", "Date", "A calendar date as ISO text: 2026-04-01."),
    "DateTime" to WireScalar("DateTime", "DateTime", "A moment as ISO text: 2026-04-01T09:00:00Z."),
    "time" to WireScalar("Time", "time", "A time of day as ISO text: 09:30:00."),
    "uuid" to WireScalar("UUID", "uuid", "A UUID as text."),
    "duration" to WireScalar("Duration", "duration", "A span of time as ISO-8601 text (PT36H) or an amount and unit (3 days)."),
    "bytes" to WireScalar("Bytes", "bytes", "Binary data as base64 text."),
)
