package velle.api.graphql

import graphql.GraphQL
import graphql.GraphQLContext
import graphql.GraphqlErrorBuilder
import graphql.Scalars
import graphql.execution.CoercedVariables
import graphql.execution.DataFetcherExceptionHandler
import graphql.execution.DataFetcherExceptionHandlerParameters
import graphql.execution.DataFetcherExceptionHandlerResult
import graphql.language.BooleanValue
import graphql.language.FloatValue
import graphql.language.IntValue
import graphql.language.StringValue
import graphql.language.Value
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
import velle.Ref
import velle.RelType
import velle.ScalarType
import velle.StoredProp
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
        GraphQL.newGraphQL(schema(api, ticks)).defaultDataFetcherExceptionHandler(ExceptionHandler).build()

    /** Turns an [ApiException] into a GraphQL error carrying `extensions.kind` (BAD_REQUEST, NOT_FOUND, UNAUTHORIZED) and `extensions.details`. */
    object ExceptionHandler : DataFetcherExceptionHandler {
        override fun handleException(params: DataFetcherExceptionHandlerParameters): CompletableFuture<DataFetcherExceptionHandlerResult> {
            val e = params.exception.let { if (it is java.util.concurrent.CompletionException && it.cause != null) it.cause!! else it }
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
        for (r in readable) additional[r] = if (model.isCrossBase(r)) union(r) else objectType(r)
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
    private fun wireScalar(name: String, velleName: String, description: String): GraphQLScalarType = scalars.getOrPut(name) {
        GraphQLScalarType.newScalar().name(name).description(description).coercing(object : Coercing<Any, Any> {
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

    private val decimal get() = wireScalar("Decimal", "decimal", "An exact decimal number, as a JSON number.")
    private val long get() = wireScalar("Long", "long", "A whole number beyond 32 bits, as a JSON number.")
    private val date get() = wireScalar("Date", "Date", "A calendar date as ISO text: 2026-04-01.")
    private val dateTime get() = wireScalar("DateTime", "DateTime", "A moment as ISO text: 2026-04-01T09:00:00Z.")
    private val time get() = wireScalar("Time", "time", "A time of day as ISO text: 09:30:00.")
    private val uuid get() = wireScalar("UUID", "uuid", "A UUID as text.")
    private val duration get() = wireScalar("Duration", "duration", "A span of time as ISO-8601 text (PT36H) or an amount and unit (3 days).")
    private val bytes get() = wireScalar("Bytes", "bytes", "Binary data as base64 text.")
    private val json get() = scalars.getOrPut("JSON") {
        GraphQLScalarType.newScalar().name("JSON").description("Any JSON value, passed through as is.").coercing(object : Coercing<Any, Any> {
            override fun serialize(v: Any, ctx: GraphQLContext, locale: Locale): Any = v
            override fun parseValue(input: Any, ctx: GraphQLContext, locale: Locale): Any = input
            override fun parseLiteral(input: Value<*>, variables: CoercedVariables, ctx: GraphQLContext, locale: Locale): Any =
                throw CoercingParseLiteralException("JSON is read from variables only")
            override fun valueToLiteral(input: Any, ctx: GraphQLContext, locale: Locale): Value<*> = StringValue.newStringValue(input.toString()).build()
        }).build()
    }

    private fun scalarType(name: String): GraphQLScalarType = when (name) {
        "text" -> Scalars.GraphQLString
        "boolean" -> Scalars.GraphQLBoolean
        "integer" -> Scalars.GraphQLInt
        "long" -> long
        "decimal", "double" -> decimal
        "Date" -> date
        "DateTime" -> dateTime
        "time" -> time
        "uuid" -> uuid
        "duration" -> duration
        "bytes" -> bytes
        else -> wireScalar(name, name, "A value of the extension type $name, as its own text.")
    }

    // ── output types ────────────────────────────────────────────────────────────

    private fun outType(t: VType, gated: Boolean): GraphQLOutputType {
        val bare = (t as? VType.Optional)?.inner ?: t
        val type: GraphQLOutputType = when (bare) {
            is VType.Inst -> GraphQLTypeReference.typeRef(bare.shape)
            is VType.Coll -> GraphQLList.list(GraphQLNonNull.nonNull(GraphQLTypeReference.typeRef(bare.shape)))
            is VType.CollS -> GraphQLList.list(GraphQLNonNull.nonNull(scalarType(bare.name)))
            VType.Id -> Scalars.GraphQLID
            else -> scalarType(describe.scalarName(bare))
        }
        return if (t is VType.Optional || gated) type else GraphQLNonNull.nonNull(type)
    }

    private fun objectType(scope: String): GraphQLObjectType {
        val b = GraphQLObjectType.newObject().name(scope)
        b.description(if (scope in model.refinements) "The refinement $scope: a ${model.basesOf(scope).joinToString(" or ")} read as a member of it." else "The shape $scope.")
        b.field { it.name("shape").type(GraphQLNonNull.nonNull(Scalars.GraphQLString)).description("The record's own shape.") }
        b.field { it.name("key").type(GraphQLNonNull.nonNull(Scalars.GraphQLID)).description("The store's key for the record.") }
        for (m in model.membersOf(scope).values) {
            if (m.name == "id") continue
            val gated = m.visible.isNotEmpty()
            val field = memberField(m.name)
            b.field { it.name(field).type(outType(m.type, gated)) }
            val bare = (m.type as? VType.Optional)?.inner ?: m.type
            when (bare) {
                is VType.Inst -> registry.dataFetcher(FieldCoordinates.coordinates(scope, field), DataFetcher { env ->
                    val ref = (env.getSource<Map<String, Any?>>()!![m.name] as? Map<*, *>) ?: return@DataFetcher null
                    try { api.read(api.refTo(ref["shape"] as String, ref["key"]!!), VelleGraphQL.actorOf(env)) }
                    catch (e: ApiException) { if (e.kind == ApiException.Kind.NOT_FOUND) null else throw e }
                })
                is VType.Coll -> registry.dataFetcher(FieldCoordinates.coordinates(scope, field), DataFetcher { env ->
                    val refs = (env.getSource<Map<String, Any?>>()!![m.name] as? List<*>).orEmpty()
                        .map { r -> (r as Map<*, *>).let { api.refTo(it["shape"] as String, it["key"]!!) } }
                    api.readEach(refs, VelleGraphQL.actorOf(env)).filterNotNull()
                })
                else -> if (field != m.name) registry.dataFetcher(FieldCoordinates.coordinates(scope, field), DataFetcher { env -> env.getSource<Map<String, Any?>>()!![m.name] })
            }
        }
        if (scope in model.shapes) for (flag in api.wire.flagsOf(scope))
            b.field { it.name("is$flag").type(GraphQLNonNull.nonNull(Scalars.GraphQLBoolean)).description("Is the record a $flag now?") }
        return b.build()
    }

    private fun union(name: String): GraphQLUnionType {
        val b = GraphQLUnionType.newUnionType().name(name).description("The static union $name: one of its operands.")
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
                    try { api.read(r, env.getArgument<String>("key")!!, VelleGraphQL.actorOf(env)) }
                    catch (e: ApiException) { if (e.kind == ApiException.Kind.NOT_FOUND) null else throw e }
                })
            }
            val page = GraphQLObjectType.newObject().name("${r}Page").description("One page of $r records and the cursor for the next.")
                .field { it.name("items").type(GraphQLNonNull.nonNull(GraphQLList.list(GraphQLNonNull.nonNull(GraphQLTypeReference.typeRef(r))))) }
                .field { it.name("next").type(Scalars.GraphQLString).description("Pass as `after` for the next page; null when this was the last.") }
                .build()
            additional[page.name] = page
            q.field { it.name(many).description("The $r records satisfying `where`, a predicate in the spec's own language over the members of $r, in the order `orderBy` names (\"due\", \"-total\").")
                .argument(GraphQLArgument.newArgument().name("where").type(Scalars.GraphQLString))
                .argument(GraphQLArgument.newArgument().name("orderBy").type(GraphQLNonNull.nonNull(GraphQLList.list(GraphQLNonNull.nonNull(Scalars.GraphQLString)))))
                .argument(GraphQLArgument.newArgument().name("first").type(Scalars.GraphQLInt))
                .argument(GraphQLArgument.newArgument().name("after").type(Scalars.GraphQLString))
                .type(GraphQLNonNull.nonNull(page)) }
            registry.dataFetcher(FieldCoordinates.coordinates("Query", many), DataFetcher { env ->
                api.query(r, env.getArgument("where"), env.getArgument<List<String>>("orderBy").orEmpty(), env.getArgument("first"), env.getArgument("after"), VelleGraphQL.actorOf(env))
            })
            q.field { it.name("${one}Count").description("How many $r records satisfy `where`.")
                .argument(GraphQLArgument.newArgument().name("where").type(Scalars.GraphQLString))
                .type(GraphQLNonNull.nonNull(Scalars.GraphQLInt)) }
            registry.dataFetcher(FieldCoordinates.coordinates("Query", "${one}Count"), DataFetcher { env ->
                api.count(r, env.getArgument("where"), VelleGraphQL.actorOf(env))
            })
        }
        return q.build()
    }

    // ── mutations: one per act ──────────────────────────────────────────────────

    private fun mutationType(): GraphQLObjectType {
        val m = GraphQLObjectType.newObject().name("Mutation")
        for (act in model.exposed) {
            val input = actInput(act)
            val outcome = outcomeType(act)
            m.field { it.name(lower(act)).description(actDescription(act))
                .argument(GraphQLArgument.newArgument().name("input").type(GraphQLNonNull.nonNull(input)))
                .type(GraphQLNonNull.nonNull(outcome)) }
            registry.dataFetcher(FieldCoordinates.coordinates("Mutation", lower(act)), DataFetcher { env ->
                api.commit(act, toWire(env.getArgument<Map<String, Any?>>("input")!!) as Map<String, Any?>, VelleGraphQL.actorOf(env))
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
            .field { it.name("ref").type(GraphQLTypeReference.typeRef("Reference")).description("The record the act became; null for a transient act and for a refusal.") }
            .field { it.name("reason").type(Scalars.GraphQLString) }
            .field { it.name("rejection").type(GraphQLTypeReference.typeRef("Rejection")) }
        if (act !in model.transients) {
            b.field { it.name("record").type(GraphQLTypeReference.typeRef(act)).description("The $act record, read back after the commit.") }
            registry.dataFetcher(FieldCoordinates.coordinates("${act}Outcome", "record"), DataFetcher { env ->
                val ref = env.getSource<Map<String, Any?>>()!!["ref"] as? Map<*, *> ?: return@DataFetcher null
                try { api.read(act, ref["key"]!!, VelleGraphQL.actorOf(env)) } catch (e: ApiException) { if (e.kind == ApiException.Kind.NOT_FOUND) null else throw e }
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
        val out = mutableListOf<GraphQLInputObjectField>()
        for (p in decl.members.filterIsInstance<StoredProp>()) {
            if (p.name == backRef || p.name == actor || p.initially != null) continue
            val edge = edges.find { it.byValue && it.prop == p.name }
            val type: GraphQLInputType = when (val t = p.type) {
                is RelType -> when {
                    edge != null -> byValueInput(act, edge)
                    p.name in siblings || model.isCrossBase(t.shape) || t.shape in model.refinements -> GraphQLTypeReference.typeRef("RefInput")
                    else -> Scalars.GraphQLID
                }.let { if (t.many) GraphQLList.list(GraphQLNonNull.nonNull(it)) else it }
                is ScalarType -> scalarType(t.name).let { if (t.many) GraphQLList.list(GraphQLNonNull.nonNull(it)) else it }
            }
            val optional = when (val t = p.type) {
                is RelType -> t.optional || t.many
                is ScalarType -> t.optional || t.many
            } && !p.initiallyRequired
            out.add(GraphQLInputObjectField.newInputObjectField().name(p.name).type(if (optional || edge != null) type else GraphQLNonNull.nonNull(type)).build())
        }
        for (edge in edges.filter { !it.byValue })
            out.add(GraphQLInputObjectField.newInputObjectField().name(edge.prop)
                .description("The ${edge.partShape} parts created with this ${shape}.")
                .type(GraphQLList.list(GraphQLNonNull.nonNull(nestedInput(act, edge.partShape, edge.childrenFor(edge.partShape), edge.backRef)))).build())
        if (nested) out.add(GraphQLInputObjectField.newInputObjectField().name("asNew").type(Scalars.GraphQLInt)
            .description("Tag this value with a number so a sibling field may point at it with `{new: n}`.").build())
        return out
    }

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
    private fun toWire(v: Any?): Any? = when (v) {
        null -> null
        is Map<*, *> -> {
            val entries = v.entries.filter { it.value != null }
            val byValue = entries.singleOrNull { it.key == "existing" } ?: entries.singleOrNull { (it.key as String).startsWith("new") && it.key != "new" && it.value is Map<*, *> }
            when {
                entries.any { it.key == "existing" } || entries.any { (it.key as String).startsWith("new") && it.key != "new" && it.value is Map<*, *> } -> {
                    if (entries.size != 1) throw ApiException.badRequest("a by-value field takes `existing` or exactly one `new...`, not ${entries.map { it.key }}")
                    val e = byValue!!
                    if (e.key == "existing") toWire(e.value)
                    else (toWire(e.value) as Map<String, Any?>) + ("@shape" to (e.key as String).removePrefix("new"))
                }
                else -> entries.associate { (k, value) -> (if (k == "asNew") "@new" else k as String) to toWire(value) }
            }
        }
        is List<*> -> v.map { toWire(it) }
        else -> v
    }
}
