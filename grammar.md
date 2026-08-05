# Velle Grammar

The normative whole-surface grammar: a parser is written from this document. It extends README §10's treatment — the same informal-EBNF register — from predicates to the full language; §10 remains the normative statement of predicate internals (`is`, `exists`, aggregates, selectors, `as` bindings, narrowing), incorporated here by reference.

Out of scope, per the v0 construct set (README §22's scope statement): `configure` and mechanism plugins, schedule definition, `states of`, Mapping, `requires`, `visible to`. Each grows this grammar when its construct lands post-v0. The `expose ... using` declaration itself is *in* — v0 exercises it with the single builtin mechanism `MockHarness` (README §22's scope statement).

## Lexical layer

```
comment         := "--" <everything to end of line>

ShapeName       := UppercaseLetter (Letter | Digit)*      -- shapes, refinements, schedules
Identifier      := LowercaseLetter (Letter | Digit)*      -- properties, aliases

IntegerLiteral  := Digit+
DecimalLiteral  := Digit+ "." Digit+
TextLiteral     := '"' <single line; escapes: \" \\ \n \t \r \uXXXX> '"'
                   -- Kotlin's string lexer minus "\$": no interpolation/templates, no raw/triple-quoted
                   -- strings, no literal newlines. Composing text out of data is presentation logic —
                   -- compiling territory; a spec that seems to need it wants separate fields on a fact shape.
BooleanLiteral  := "true" | "false"
DurationLiteral := IntegerLiteral ("seconds" | "minutes" | "hours" | "days" | "weeks")
                   -- surface spelling of the duration constructors: "3 days" is days(3) (README §10)
```

Casing is **enforced, not convention**: the case of a name's first letter is load-bearing for parsing — `invoices where OverdueInvoice` reads a lowercase name as a path and an uppercase name as a refinement-membership test, and no other signal distinguishes them.

Keywords (reserved, never identifiers): `shape` `rule` `never` `expose` `using` `when` `leaving` `on` `after` `commit` `where` `and` `or` `not` `is` `exists` `for` `from` `then` `if` `else` `as` `this` `none` `some` `empty` `one` `many` `initially` `captured` `frozen` `tolerates` `timestamp` `create` `update` `true` `false` — plus the scalar type names, the duration units, `now`, and `today`. Builtin function names (`count`, `sum`, `latest`, `first`, `lowercase`, `max`, `min`) and the generator `randomUUID` are ordinary identifiers resolved as builtins, not keywords.

**Statements and declarations are line-oriented** — no semicolons, no braces around individual statements; a newline ends a statement, and `then` joining two effects stands on its own line (README §15's example is already written this way). This is what keeps `then`-the-statement-connector and `then`-the-conditional-keyword unambiguous.

## Spec structure

```
spec        := declaration*
declaration := shapeDecl | refinementDecl | ruleDecl | neverDecl | exposeDecl
```

File boundaries carry no meaning. A spec may span any number of `.velle` files; declarations appear in any order, and forward references are always legal — the compiler validates the whole spec as one unit (README §1), so there is nothing an ordering could add.

## Shape declarations

```
shapeDecl     := "shape" ShapeName "{" property* "}"

property      := storedProp | derivedProp | timestampProp

storedProp    := Identifier ":" propType ("initially" initializer)? ("tolerates" foldHazard)?
initializer   := valueExpr | GeneratorName          -- generator: bare name, no call syntax ("initially randomUUID")
foldHazard    := "duplication" | "reordering"       -- README §19; "loss" is rule-position only

derivedProp   := Identifier ":" propType "=" valueExpr

timestampProp := Identifier ":" "timestamp" "on" ("create" | "update")

propType      := scalarType "?"?
              | "one" ShapeName "?"?                -- to-one relationship; "?" marks it optional
              | "many" ShapeName                    -- to-many; no "?" (an empty collection is the absence)
scalarType    := "text" | "integer" | "long" | "decimal" | "double" | "boolean" | "Date" | "DateTime"
```

Relationships always carry `one`/`many` — a bare shape name is not a type (README §7's examples are normalized to `parent: one Foo?`). One keyword, one meaning: the cardinality is always visible, and the parser never guesses whether `Foo` is a forgotten scalar or a relationship.

`initially` attaches to any stored property, relationships included (README §5). It cannot attach to `derivedProp` or `timestampProp` — not grammar's doing; those properties' own rules exclude it (nothing stored to initialize; commit metadata is never author-supplied).

## Refinement declarations

```
refinementDecl := "shape" ShapeName "=" refExpr refinementBody?

refExpr        := refTerm ("or" refTerm)*                -- same precedence as predicates:
refTerm        := refFactor ("and" refFactor)*           -- "not" > "and" > "or" (README §9)
refFactor      := "not"? refAtom
refAtom        := ShapeName ("where" predicate)?         -- base or named refinement, optionally narrowed
               | "(" refExpr ")"

refinementBody := "{" refinementMember* "}"
refinementMember := "captured" Identifier ":" propType "=" valueExpr
                 | Identifier ":" propType "=" valueExpr                -- derived (live)
                 | "frozen" (Identifier ("," Identifier)*)?             -- bare "frozen" = every stored field
```

The `Base where predicate` form and composition are one grammar: `shape OverdueInvoice = Invoice where balance > 0 and due < today` is a `refAtom` with a `where` clause; `shape UrgentOverdueTicket = Overdue and HighPriority and Open` is a `refTerm`; mixing is legal (`Quoted and (Invoice where total > 100)`). Operands must share a base shape or refine one another — a type check, not a grammar rule (README §9).

## Rule declarations

```
ruleDecl      := "rule" ShapeName
                 "when" "leaving"? condition
                 triggerClause?
                 ("tolerates" "loss")?
                 "{" ruleBody "}"

condition     := ShapeName
              | "(" refExpr ")"          -- inline refinement, e.g. (Delinquent where not suspended)

triggerClause := ("on" | "after") trigger ("," trigger)*
trigger       := "commit" | ShapeName    -- a ShapeName here names a schedule
```

Omitted `triggerClause` means `on commit` (README §11). The `on`/`after` preposition is meaningful only for the `commit` entry; schedule entries inherently begin their own transactions either way (README §17). That `after` requires `commit` in its list, and the boundary/apparatus checks, are validator obligations, not grammar.

```
ruleBody      := statement (thenLine? statement)*
thenLine      := "then"                  -- on its own line, between two statements (README §15)

statement     := assignment | creation

assignment    := path "=" valueExpr

creation      := ShapeName "from" "{" fieldInit* "}"
              | ShapeName "for" valueExpr (fieldInit)*     -- the compact form: Receipt for invoice sentOn: now
fieldInit     := Identifier ":" valueExpr                  -- one per line inside "from { }"
```

`from { ... }` is the general, totality-checked form; `for` populates the one type-matched field and stops applying when more than one field matches (README §14). Statements not joined by `then` are unordered (README §15).

## `never` declarations

```
neverDecl := "never" (ShapeName | "(" refExpr ")")
```

Same operand shapes as a rule's condition — a named refinement or an inline one (README §21).

## `expose` declarations

```
exposeDecl    := "expose" ShapeName "using" MechanismName          -- standalone
              | "expose" shapeDecl "using" MechanismName           -- inline at the shape's declaration
MechanismName := ShapeName        -- v0: "MockHarness" is the only mechanism (README §22's scope statement)
```

An exposed shape is externally committable via the named mechanism; an unexposed act shape enters state only as a rule's effect (README §22, "External input mechanisms"). `MockHarness` transpiles each exposed shape to a function taking that shape as input; `configure` blocks and mechanism plugins are post-v0 and not in this grammar. `expose` and `using` join the keyword list.

## Value expressions

The formalization README §22 lacked. Predicates (below) and value expressions share atoms, paths, narrowing, and calls; a predicate *is* the boolean case of this grammar.

```
valueExpr   := condExpr
condExpr    := "if" predicate "then" valueExpr ("else" "if" predicate "then" valueExpr)* "else" valueExpr
            | addExpr

addExpr     := mulExpr (addOp mulExpr | addOp DurationLiteral)*
addOp       := "+" | "-"
mulExpr     := unaryExpr (("*" | "/") unaryExpr)*
unaryExpr   := "-"? primary

primary     := IntegerLiteral | DecimalLiteral | TextLiteral | BooleanLiteral | DurationLiteral
            | "none" | "now" | "today"
            | path                       -- README §10: pathRoot (accessor Identifier)*, aggregates, selectors,
                                         -- the sugared ("Shape" "for" expr) singular query
            | funcCall
            | "(" valueExpr ")"

funcCall    := BuiltinName "(" valueExpr ("," valueExpr)* ")"      -- closed list: lowercase, max, min (README §5)
```

The conditional is **`if p then a else b`**, always fully spelled — `else` is mandatory (a conditional that can produce no value would need an implicit `none`, and optionality should be visible in the type, not manufactured by a branch). README §7's former postfix spelling (`none if parent is none else ...`) is normalized to this form; §12's ledger example already used it.

Narrowing is shared with predicates and branch-sensitive (README §10): inside a `then` branch, the governing predicate's `is some` / `is none` / `is Refinement` checks license `.` exactly as they do within a conjunction; `?.` remains the no-narrowing escape.

Type rules ride on the Kotlin grounding (README §5): arithmetic over the numeric scalars, `+`/`-` between `Date`/`DateTime` and a duration (calendar step on `Date`, exact time on `DateTime`), `+` as text concatenation only if we ever admit it — currently **not** admitted; no operator is overloaded beyond the duration case.

## Predicates

README §10's grammar, unchanged, with one generalization: `comparison := valueExpr compareOp valueExpr` — its operands are value expressions (they always were; §10's `expr := path (+/- duration)?` was the fragment of value grammar predicates needed before this document existed). §10 remains the normative statement of `is`, `exists`, bare boolean atoms, aggregates, selectors, `as` bindings, sibling joins, and `for`-sugar legality.

