package velle

// ── Declarations (grammar.md, "Spec structure") ──────────────────────────────

sealed interface Decl

/** Base shape. `exposed` is set by the inline `expose shape ...` form. */
data class ShapeDecl(
    val name: String,
    val members: List<Member>,
    val exposed: Boolean = false,
    /** `expose transient` — an input to the state, not a member of it (README §4). */
    val transient: Boolean = false,
) : Decl

/** `shape Name = refExpr { members }` */
data class RefinementDecl(
    val name: String,
    val expr: RefExpr,
    val members: List<Member> = emptyList(),
) : Decl

data class RuleDecl(
    val name: String,
    val leaving: Boolean,
    val condition: RefExpr,
    /** "on" | "after" | null (null = omitted clause, defaulting to `on commit`) */
    val preposition: String?,
    /** trigger list: "commit" and/or schedule names */
    val triggers: List<String>,
    val toleratesLoss: Boolean,
    val body: List<BodyItem>,
) : Decl

data class NeverDecl(val target: RefExpr) : Decl

/** Standalone `expose [transient] Shape`. */
data class ExposeDecl(val shape: String, val transient: Boolean = false) : Decl

// ── Shape / refinement members ───────────────────────────────────────────────

sealed interface Member
data class StoredProp(
    val name: String,
    val type: TypeRef,
    val initially: Expr? = null,
    val tolerates: String? = null,
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

sealed interface TypeRef
data class ScalarType(val name: String, val optional: Boolean) : TypeRef
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

// ── Expressions (values and predicates share one grammar) ────────────────────

sealed interface Expr

data class IntLit(val value: Long) : Expr
data class DecLit(val text: String) : Expr
data class TextLit(val value: String) : Expr
data class BoolLit(val value: Boolean) : Expr
data object NoneLit : Expr
data object NowLit : Expr
data object TodayLit : Expr
data class DurationLit(val amount: Long, val unit: String) : Expr

data class Seg(val name: String, val viaQdot: Boolean)
/** `this.a?.b`, `invoice.customer`, or a bare name / ShapeName root. */
data class PathExpr(val root: String, val segs: List<Seg> = emptyList()) : Expr

data class UnaryMinus(val inner: Expr) : Expr
data class Binary(val op: String, val left: Expr, val right: Expr) : Expr
data class NotExpr(val inner: Expr) : Expr
data class IfExpr(val condition: Expr, val thenExpr: Expr, val elseExpr: Expr) : Expr

/** kind: "none" | "some" | "empty" | "notEmpty" | "refinement" */
data class IsExpr(val subject: Expr, val kind: String, val refinement: String? = null) : Expr

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
