package velle

// ── Declarations (grammar.md, "Spec structure") ──────────────────────────────

sealed interface Decl

/** Base shape. `exposed` is set by the `act` declaration (Reference §22, "External input"). */
data class ShapeDecl(
    val name: String,
    val members: List<Member>,
    val exposed: Boolean = false,
    /** `transient act` — an input to the state, not a member of it (Reference §4). */
    val transient: Boolean = false,
    /** the act's closure — `act ... with` (Reference §6, "Inline part creation") */
    val with: List<WithEntry> = emptyList(),
) : Decl {
    /** The act's `by` clause, carried as a member (Reference §22, "The actor"); null when the act has no actor. */
    val actor: ActorProp? get() = members.filterIsInstance<ActorProp>().singleOrNull()
}

/** One closure edge: an inverse-edge name on the enclosing level, with its own
 *  nested closure when the part arrives with parts of its own (Reference §6,
 *  "Inline part creation"; grammar.md, withEntry). */
data class WithEntry(val name: String, val children: List<WithEntry> = emptyList())

/** `shape Name = refExpr { members }` */
data class RefinementDecl(
    val name: String,
    val expr: RefExpr,
    val members: List<Member> = emptyList(),
) : Decl

/**
 * The trigger family (Reference §13, "Deletion as a second exit cause"): what a
 * rule reacts to. A record leaves a state for two different business reasons —
 * its predicate flipped, or it was deleted — and the rule says which.
 */
enum class Trigger(val keyword: String) {
    ENTRY(""),
    LEAVING("leaving "),
    DELETED("deleted "),
    LEAVING_OR_DELETED("leaving or deleted "),
    LEAVING_NOT_DELETED("leaving not deleted ");

    /** The subject is a member whose membership ends at the commit, by any cause. */
    val exit: Boolean get() = this != ENTRY
    /** Fires when the state's predicate flips false. */
    val onPredicateExit: Boolean get() = this == LEAVING || this == LEAVING_OR_DELETED || this == LEAVING_NOT_DELETED
    /** Fires when a member is deleted. */
    val onDeletion: Boolean get() = this == DELETED || this == LEAVING_OR_DELETED
}

data class RuleDecl(
    val name: String,
    val trigger: Trigger,
    val condition: RefExpr,
    /** "on" | "after" | null (null = omitted clause, defaulting to `on commit`) */
    val preposition: String?,
    /** trigger list: "commit" and/or schedule names */
    val triggers: List<String>,
    val toleratesLoss: Boolean,
    val body: List<BodyItem>,
    /** `tolerates contention` — the author accepts the wide serialization domain
     *  this rule's condition creates (OQ40; advisory A5). */
    val toleratesContention: Boolean = false,
    /** `by Shape[?] [as name]` — the rule reads the actor of the transaction it
     *  fires in (Reference §22, "The actor"); null when it reads none. */
    val by: RuleActor? = null,
) : Decl {
    /** Exit-shaped: the subject is a leaver (any cause) — reads captures as a last reader. */
    val leaving: Boolean get() = trigger.exit
}

/**
 * A rule's actor clause: the committer of the act whose transaction the rule
 * fires in, bound under [name] (`actor` unless the author wrote `as`). [optional]
 * is the author's `?`: some path to the rule's trigger starts at a tick, an
 * `after commit` firing, or an act without `by`, and there the actor is absent
 * (checks V31).
 */
data class RuleActor(val shape: String, val optional: Boolean, val name: String, val named: Boolean = false)

/**
 * A prohibition (Reference §21). Spelled `never <condition>`, or as a requirement,
 * `Subject requires <predicate>`, which means `never (Subject where not <predicate>)`:
 * [target] is always the forbidden configuration, and [requirement] keeps the
 * declaration as the author wrote it, so rejections and diagnostics quote the
 * requirement rather than its complement.
 */
data class NeverDecl(
    val target: RefExpr,
    /** `tolerates contention` — the author accepts the wide serialization domain
     *  this invariant's read creates (OQ40; advisory A5). */
    val toleratesContention: Boolean = false,
    /** Non-null when the prohibition was written `Subject requires <predicate>`. */
    val requirement: Requirement? = null,
    /** True for the requirement an act's `by <Refinement>` clause desugars to
     *  (`Act requires actor to be Refinement`, Reference §22): the role gate in the header. */
    val fromActorClause: Boolean = false,
) : Decl {
    /** The declaration's keyword, for labels: `never` or `requires`. */
    val keyword: String get() = if (requirement == null) "never" else "requires"
}

/** The requirement spelling of a prohibition, as written: `subject requires predicate`. */
data class Requirement(val subject: String, val predicate: Expr)

/**
 * The spec-wide visibility default (Reference §22, "The actor"; OQ33 §5.9):
 * `visible to Staff` or `visible to anyone` at the top level, which every
 * readable shape without a clause of its own takes.
 */
data class VisibilityDefault(val clause: VisibleClause) : Decl

/** `uses velle.finance.Money [as Cash]` (Reference §5, extension types): picks the
 *  registered type [qualified] when two libraries register one name, or brings it
 *  into the spec under [alias]. With an alias, the type's own name is no longer in
 *  scope for it in this spec. */
data class UsesDecl(val qualified: String, val alias: String? = null) : Decl {
    /** The name the spec writes for the type. */
    val name: String get() = alias ?: qualified.substringAfterLast('.')
}

// ── Shape / refinement members ───────────────────────────────────────────────

sealed interface Member

/**
 * A visibility clause (Reference §22, "The actor"; OQ33 §5.7–§5.10):
 * `visible to <Shape> [as name] [where predicate]`, or `visible to anyone`
 * ([shape] null). On a shape body it is record-level: a member of the body
 * beside `frozen` and `undeletable`, saying which viewers a record is
 * returned to. On a field it narrows within the record's. The viewer is bound
 * in [where] under [name] (`actor` unless `as` chose another). Applied by the
 * read surface only, never inside evaluation.
 */
data class VisibleClause(val shape: String?, val name: String = "actor", val named: Boolean = false, val where: Expr? = null) : Member {
    val anyone: Boolean get() = shape == null
}

data class StoredProp(
    val name: String,
    val type: TypeRef,
    val initially: Expr? = null,
    val tolerates: String? = null,
    /** `? initially required` (OQ37-R10): the type is read-optional, but a
     *  creation must supply a value — never-set is impossible, so `is none`
     *  means "was present, now gone" (target deleted, or assigned `none`). */
    val initiallyRequired: Boolean = false,
    /** Field-level visibility (Reference §22): which viewers this field is returned to. */
    val visible: List<VisibleClause> = emptyList(),
) : Member

data class DerivedProp(
    val name: String,
    val type: TypeRef,
    val expr: Expr,
    val captured: Boolean = false,
    val visible: List<VisibleClause> = emptyList(),
) : Member

data class TimestampProp(val name: String, val on: String, val visible: List<VisibleClause> = emptyList()) : Member // "create" | "update"

/**
 * The actor an act is committed by (Reference §22, "The actor"): the act's
 * `by Shape [as name]` clause, carried as a language-populated member — never
 * in the committer's input, never assignable, stored on a durable act like a
 * timestamp. [name] is `actor` unless the author chose one with `as` ([named]).
 * [shape] is a shape, a static union, or a refinement; a refinement is the role
 * gate in the header, desugared to `Act requires <name> to be Refinement`.
 */
data class ActorProp(val name: String, val shape: String, val named: Boolean = false) : Member

/** `frozen a, b` — empty list means bare `frozen` (every stored field). */
data class FrozenClause(val fields: List<String>) : Member

/** `undeletable` — the state-scoped deletion gate (OQ37-R1): while an instance
 *  is a member of the declaring refinement, deleting it is refused. A sibling
 *  of `frozen`, deliberately separate from it (OQ37-R2): "you can't edit it"
 *  and "you can't delete it" are different business sentences. */
data object UndeletableClause : Member

sealed interface TypeRef
/** `many` marks an owned collection of values (`tags: many text`) — never optional (Reference §6). */
data class ScalarType(val name: String, val optional: Boolean, val many: Boolean = false) : TypeRef
/** `many` here is the owned edge set of a many-to-many; `one` the owned side of a one-to-many (Reference §6). */
data class RelType(val many: Boolean, val shape: String, val optional: Boolean) : TypeRef

// ── Refinement expressions (grammar.md, "Refinement declarations") ───────────

sealed interface RefExpr
data class RefName(val name: String, val where: Expr? = null) : RefExpr
data class RefAnd(val left: RefExpr, val right: RefExpr) : RefExpr
data class RefOr(val left: RefExpr, val right: RefExpr) : RefExpr
data class RefNot(val inner: RefExpr) : RefExpr

// ── Rule bodies ──────────────────────────────────────────────────────────────

sealed interface BodyItem
/** A standalone `then` line ordering the statements on either side of it. */
data object ThenMarker : BodyItem
data class Assignment(val target: PathExpr, val value: Expr) : BodyItem
data class Creation(
    val shape: String,
    /** `for <expr>` in the compact form; null for `from { ... }` */
    val forExpr: Expr? = null,
    val fields: List<FieldInit> = emptyList(),
    /** `as <name>` (Reference §14; OQ63 §6.2.1): the created record, usable as a
     *  reference by the body's other statements before its commit lands. */
    val alias: String? = null,
) : BodyItem

data class FieldInit(val name: String, val value: Expr)

/**
 * `for each <name> in <source>, ... { statements }` (Reference §14): the block's
 * statements run once per element of each source, nested for a product. A
 * statement inside is a creation or another block; the names are values for
 * the block's statements and nothing outside it (checks V34).
 */
data class ForEach(val bindings: List<LoopBinding>, val body: List<BodyItem>) : BodyItem

data class LoopBinding(val name: String, val source: LoopSource)

sealed interface LoopSource {
    /** A collection the rule can reach: a `many` field, an inferred inverse, a
     *  filter, a bare shape name. */
    data class Over(val expr: Expr) : LoopSource
    /** `from a to b` — the whole numbers from `a` to `b`, both included, the
     *  one place a number becomes a set; legal only here. */
    data class Range(val from: Expr, val to: Expr) : LoopSource
}

/** The expressions a block's sources read, for the read collectors: each
 *  collection (a bare shape name as the set it denotes), and each range's two bounds. */
fun ForEach.sourceExprs(): List<Expr> = bindings.flatMap {
    when (val s = it.source) {
        is LoopSource.Over -> listOf(if (s.expr is PathExpr && s.expr.segs.isEmpty() && s.expr.root.first().isUpperCase()) SetExpr(CollectionExpr(listOf(Binding(s.expr)))) else s.expr)
        is LoopSource.Range -> listOf(s.from, s.to)
    }
}

/** Every statement of a body, blocks included, with each block's statements after it. */
fun List<BodyItem>.statements(): List<BodyItem> =
    flatMap { if (it is ForEach) listOf(it) + it.body.statements() else listOf(it) }

/** Every creation of the rule, inside blocks or not. */
fun RuleDecl.creations(): List<Creation> = body.statements().filterIsInstance<Creation>()

/** The creations written outside any block: each creates exactly one record per firing. */
fun RuleDecl.topCreations(): List<Creation> = body.filterIsInstance<Creation>()

/** Every creation with the `for each` names enclosing it, outermost first; empty for one outside any block. */
fun RuleDecl.creationsWithLoops(): List<Pair<Creation, List<LoopBinding>>> {
    val out = mutableListOf<Pair<Creation, List<LoopBinding>>>()
    fun walk(items: List<BodyItem>, loops: List<LoopBinding>) {
        for (it in items) when (it) {
            is Creation -> out.add(it to loops)
            is ForEach -> walk(it.body, loops + it.bindings)
            else -> {}
        }
    }
    walk(body, emptyList())
    return out
}

/** The names bound in the rule's blocks, each to the block that binds it. */
fun RuleDecl.loopBindings(): List<Pair<LoopBinding, ForEach>> =
    body.statements().filterIsInstance<ForEach>().flatMap { fe -> fe.bindings.map { it to fe } }

/** `delete <path>` — instance-granularity mutation (OQ37): the instance ceases
 *  to be part of the state at this commit's close. The target is a literal
 *  static path, like assignment's (Reference §12), and always a to-one instance —
 *  no fan-out deletes in v0. Semantics: the instance becomes transient at its
 *  final commit — fully present within the deleting transaction (rules fired
 *  by the commit are its last readers), removed at transaction close. */
data class DeleteStmt(val target: PathExpr) : BodyItem

// ── Expressions (values and predicates share one grammar) ────────────────────

sealed interface Expr

data class IntLit(val value: Long) : Expr
data class DecLit(val text: String) : Expr
data class TextLit(val value: String) : Expr
data class BoolLit(val value: Boolean) : Expr
data object NoneLit : Expr
data object NowLit : Expr
data object TodayLit : Expr
/** The empty collection — the one collection literal (Reference §6); types only where a `many` is expected. */
data object EmptyLit : Expr
data class DurationLit(val amount: Long, val unit: String) : Expr

/** A constant the parser never produces: a value the query API (Reference §22,
 *  the composed query) supplies at the moment of asking — a date, a decimal,
 *  or an instance (`Value.VRef`) standing for "this customer". Evaluates to
 *  itself; the pre-filter compiler folds it like any literal. */
data class ValueLit(val value: Value) : Expr

/** A reference the query API supplies at the moment of asking — "this customer"
 *  — resolved to the instance behind it when the query runs. */
data class RefLit(val ref: Keyed) : Expr

/** Set denotation as a value: `(Shape where pred)` / `(this.invoices where Overdue)` —
 *  legal in `many`-typed derived-property position and collection-valued contexts (Reference §6). */
data class SetExpr(val collection: CollectionExpr) : Expr

data class Seg(val name: String, val viaQdot: Boolean)
/** `this.a?.b`, `invoice.customer`, or a bare name / ShapeName root. */
data class PathExpr(val root: String, val segs: List<Seg> = emptyList()) : Expr

data class UnaryMinus(val inner: Expr) : Expr
data class Binary(val op: String, val left: Expr, val right: Expr) : Expr
data class NotExpr(val inner: Expr) : Expr
data class IfExpr(val condition: Expr, val thenExpr: Expr, val elseExpr: Expr) : Expr

/** kind: "none" | "some" | "empty" | "notEmpty" | "refinement" */
/** `toBe` records the requirement spelling of membership (`x to be R`, Reference §21); it is the same predicate as `x is R`. */
data class IsExpr(val subject: Expr, val kind: String, val refinement: String? = null, val toBe: Boolean = false) : Expr

/** `exists Shape for expr` (sugar) or `exists (collection)` (general). */
data class ExistsExpr(
    val shape: String? = null,
    val forExpr: Expr? = null,
    val collection: CollectionExpr? = null,
) : Expr

/** count/sum/latest/first. `field` is sum's second argument; `orderBy` is the
 *  selectors' mandatory `by` list — the author's ordering statement (Reference §10):
 *  no implicit ordering source exists. */
data class AggCall(
    val name: String,
    val collection: CollectionExpr,
    val field: String? = null,
    val orderBy: List<String> = emptyList(),
) : Expr

/** Builtin function call from the closed list: lowercase, max, min. */
data class FunCall(val name: String, val args: List<Expr>) : Expr

/** `(Shape for expr)` — the singular query form. */
data class SingularFor(val shape: String, val forExpr: Expr) : Expr

/** Postfix accessor chain on a non-path primary: `latest(...).referee`. */
data class Access(val target: Expr, val segs: List<Seg>) : Expr

data class CollectionExpr(val bindings: List<Binding>, val where: Expr? = null)
/** source is a PathExpr (relationship or ShapeName) or ShapeForSource. */
data class Binding(val source: Expr, val alias: String? = null)
/** `Payment for this` as a collection source inside latest/first/exists. */
data class ShapeForSource(val shape: String, val forExpr: Expr) : Expr
