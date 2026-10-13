package velle

// ── Declarations (grammar.md, "Spec structure") ──────────────────────────────

sealed interface Decl

/** Base shape. `exposed` is set by the `act` declaration (README §22, "External input"). */
data class ShapeDecl(
    val name: String,
    val members: List<Member>,
    val exposed: Boolean = false,
    /** `transient act` — an input to the state, not a member of it (README §4). */
    val transient: Boolean = false,
    /** the act's closure — `act ... with` (README §6, "Inline part creation") */
    val with: List<WithEntry> = emptyList(),
) : Decl

/** One closure edge: an inverse-edge name on the enclosing level, with its own
 *  nested closure when the part arrives with parts of its own (README §6,
 *  "Inline part creation"; grammar.md, withEntry). */
data class WithEntry(val name: String, val children: List<WithEntry> = emptyList())

/** `shape Name = refExpr { members }` */
data class RefinementDecl(
    val name: String,
    val expr: RefExpr,
    val members: List<Member> = emptyList(),
) : Decl

/**
 * The trigger family (README §13, "Deletion as a second exit cause"): what a
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
) : Decl {
    /** Exit-shaped: the subject is a leaver (any cause) — reads captures as a last reader. */
    val leaving: Boolean get() = trigger.exit
}

/**
 * A prohibition (README §21). Spelled `never <condition>`, or as a requirement,
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
) : Decl {
    /** The declaration's keyword, for labels: `never` or `requires`. */
    val keyword: String get() = if (requirement == null) "never" else "requires"
}

/** The requirement spelling of a prohibition, as written: `subject requires predicate`. */
data class Requirement(val subject: String, val predicate: Expr)

// ── Shape / refinement members ───────────────────────────────────────────────

sealed interface Member
data class StoredProp(
    val name: String,
    val type: TypeRef,
    val initially: Expr? = null,
    val tolerates: String? = null,
    /** `? initially required` (OQ37-R10): the type is read-optional, but a
     *  creation must supply a value — never-set is impossible, so `is none`
     *  means "was present, now gone" (target deleted, or assigned `none`). */
    val initiallyRequired: Boolean = false,
) : Member

data class DerivedProp(
    val name: String,
    val type: TypeRef,
    val expr: Expr,
    val captured: Boolean = false,
) : Member

data class TimestampProp(val name: String, val on: String) : Member // "create" | "update"

/** `frozen a, b` — empty list means bare `frozen` (every stored field). */
data class FrozenClause(val fields: List<String>) : Member

/** `undeletable` — the state-scoped deletion gate (OQ37-R1): while an instance
 *  is a member of the declaring refinement, deleting it is refused. A sibling
 *  of `frozen`, deliberately separate from it (OQ37-R2): "you can't edit it"
 *  and "you can't delete it" are different business sentences. */
data object UndeletableClause : Member

sealed interface TypeRef
/** `many` marks an owned collection of values (`tags: many text`) — never optional (README §6). */
data class ScalarType(val name: String, val optional: Boolean, val many: Boolean = false) : TypeRef
/** `many` here is the owned edge set of a many-to-many; `one` the owned side of a one-to-many (README §6). */
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
) : BodyItem

data class FieldInit(val name: String, val value: Expr)

/** `delete <path>` — instance-granularity mutation (OQ37): the instance ceases
 *  to be part of the state at this commit's close. The target is a literal
 *  static path, like assignment's (README §12), and always a to-one instance —
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
/** The empty collection — the one collection literal (README §6); types only where a `many` is expected. */
data object EmptyLit : Expr
data class DurationLit(val amount: Long, val unit: String) : Expr

/** A constant the parser never produces: a value the query API (README §22,
 *  the composed query) supplies at the moment of asking — a date, a decimal,
 *  or an instance (`Value.VRef`) standing for "this customer". Evaluates to
 *  itself; the pre-filter compiler folds it like any literal. */
data class ValueLit(val value: Value) : Expr

/** Set denotation as a value: `(Shape where pred)` / `(this.invoices where Overdue)` —
 *  legal in `many`-typed derived-property position and collection-valued contexts (README §6). */
data class SetExpr(val collection: CollectionExpr) : Expr

data class Seg(val name: String, val viaQdot: Boolean)
/** `this.a?.b`, `invoice.customer`, or a bare name / ShapeName root. */
data class PathExpr(val root: String, val segs: List<Seg> = emptyList()) : Expr

data class UnaryMinus(val inner: Expr) : Expr
data class Binary(val op: String, val left: Expr, val right: Expr) : Expr
data class NotExpr(val inner: Expr) : Expr
data class IfExpr(val condition: Expr, val thenExpr: Expr, val elseExpr: Expr) : Expr

/** kind: "none" | "some" | "empty" | "notEmpty" | "refinement" */
/** `toBe` records the requirement spelling of membership (`x to be R`, README §21); it is the same predicate as `x is R`. */
data class IsExpr(val subject: Expr, val kind: String, val refinement: String? = null, val toBe: Boolean = false) : Expr

/** `exists Shape for expr` (sugar) or `exists (collection)` (general). */
data class ExistsExpr(
    val shape: String? = null,
    val forExpr: Expr? = null,
    val collection: CollectionExpr? = null,
) : Expr

/** count/sum/latest/first. `field` is sum's second argument; `orderBy` is the
 *  selectors' mandatory `by` list — the author's ordering statement (README §10):
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
