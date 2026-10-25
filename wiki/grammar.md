# Velle Grammar

The normative whole-surface grammar: a parser is written from this document. It extends [Reference §10](Reference-10-Predicate-expressions)'s treatment — the same informal-EBNF register — from predicates to the full language; [§10](Reference-10-Predicate-expressions) remains the normative statement of predicate internals (`is`, `exists`, aggregates, selectors, `as` bindings, narrowing), incorporated here by reference.

Out of scope, per the v0 construct set ([Reference §22](Reference-22-Open-unresolved)'s scope statement): schedule definition, `states of`, Mapping, `requires`, `visible to`. Each grows this grammar when its construct lands post-v0. The `act` declaration itself is *in*: it names no mechanism — the transpiler generates a per-act input function, and everything below it (transport, persistence, serialization) is the engineer's code ([Reference §22](Reference-22-Open-unresolved), "External input").

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
                   -- surface spelling of the duration constructors: "3 days" is days(3) (Reference §10)
```

Casing is **enforced, not convention**: the case of a name's first letter is load-bearing for parsing — `invoices where OverdueInvoice` reads a lowercase name as a path and an uppercase name as a refinement-membership test, and no other signal distinguishes them.

Keywords (reserved, never identifiers): `shape` `act` `rule` `never` `requires` `transient` `uses` `when` `leaving` `deleted` `on` `after` `commit` `where` `and` `or` `not` `is` `exists` `for` `from` `then` `if` `else` `as` `this` `none` `some` `empty` `one` `many` `initially` `captured` `frozen` `tolerates` `delete` `undeletable` `timestamp` `create` `update` `true` `false` — plus the scalar type names, `now`, and `today`. `required` is **contextual**: it reads as the creation-side requiredness marker only immediately after `initially` (OQ37-R10); everywhere else it is an ordinary identifier. The duration units (`seconds` `minutes` `hours` `days` `weeks`) are **contextual**, not reserved: a unit word reads as a unit only immediately after an integer literal (`3 days`), the one position DurationLiteral gives it; everywhere else it is an ordinary identifier, so `minutes: integer` is a legal property. `by` is contextual the same way: it reads as the selector-ordering keyword only after a `latest`/`first` collection argument ([Reference §10](Reference-10-Predicate-expressions), selectorCall — the clause is mandatory there), and as the actor clause only in the two header positions `byClause` and `ruleByClause` give it ([Reference §22](Reference-22-Open-unresolved), "The actor"); everywhere else it is an ordinary identifier. `visible`, `to`, and `anyone` are contextual the same way: `visible to` reads as the visibility clause only where `visibleClause` places it — the start of a body member, after a property's declaration on the same line (a clause on its own line, or after a comma, is the record's), and at the top level — and `anyone` only after `visible to`; everywhere else all three are ordinary identifiers. The name an actor clause binds — `actor` by default, the `as` name otherwise — is not reserved either: it is an identifier the clause puts in scope, and a scope that already declares a field under it is the validator's error, not the lexer's (checks [F3](checks#f3--position-rules)). `with` is contextual too: it reads as the closure keyword only where the `act` production places it ([Reference §6, "Inline part creation"](Reference-06-Relationships#inline-part-creation-with)); everywhere else it is an ordinary identifier. `to be` is contextual the same way: the two words together spell refinement membership only inside the predicate of a `requires` declaration ([Reference §21](Reference-21-never)); everywhere else `to` and `be` are ordinary identifiers. `each`, `in`, and `to` are contextual too: `for each` opens a block only when `each` directly follows `for` at the start of a statement, `in` reads as the binding word only between a `for each` name and its collection, and `to` as the range word only between the two bounds after `from` ([Reference §14](Reference-14-for)); everywhere else all three are ordinary identifiers. The text operators are contextual too: `contains`, `like`, and the pairs `starts with` and `ends with` read as comparison operators only between two value expressions, the one position `comparison` gives them ([Reference §10](Reference-10-Predicate-expressions)); everywhere else `contains`, `starts`, `ends`, `with`, and `like` are ordinary identifiers (`with` already is, for the `act` closure). Builtin function names (`count`, `sum`, `latest`, `first`, `lowercase`, `uppercase`, `trim`, `length`, `max`, `min`) and the generator `randomUUID` are ordinary identifiers resolved as builtins, not keywords.

**Statements and declarations are line-oriented** — no semicolons, no braces around individual statements; a newline ends a statement, and `then` joining two effects stands on its own line ([Reference §15](Reference-15-then)'s example is already written this way). This is what keeps `then`-the-statement-connector and `then`-the-conditional-keyword unambiguous. Within braces, a comma is equivalent to a newline as a member separator — `{ customer: one Customer, corrected: text }` and `from { invoice: this, sentOn: now }` are the one-line spellings of the multi-line forms. Inside parentheses, newlines are insignificant (a long predicate may wrap freely).

## Spec structure

```
spec        := declaration*
declaration := usesDecl | shapeDecl | actDecl | refinementDecl | ruleDecl | neverDecl | requiresDecl | visibleClause

usesDecl    := "uses" qualifiedType ("as" TypeName)?    -- Reference §5, "Extension types": picks one of two
qualifiedType := Identifier ("." Identifier)* "." TypeName   -- registered types sharing a name, or renames one
TypeName    := UppercaseLetter (Letter | Digit)*         -- an extension type's name: its library's `name`, or the `as` name
```

File boundaries carry no meaning. A spec may span any number of `.velle` files; declarations appear in any order, and forward references are always legal — the compiler validates the whole spec as one unit ([Reference §1](Reference-01-Principles)), so there is nothing an ordering could add.

## Shape declarations

```
shapeDecl     := "shape" ShapeName "{" property* "}"

property      := storedProp | derivedProp | timestampProp

storedProp    := Identifier ":" propType ("initially" (initializer | "required"))? ("tolerates" foldHazard)?
initializer   := valueExpr | GeneratorName          -- generator: bare name, no call syntax ("initially randomUUID")
                 -- "initially required" (OQ37-R10): the type must be optional — read-side optionality
                 -- with creation-side requiredness; never-set is impossible, so `is none` means
                 -- "was present, now gone" (target deleted, or a rule assigned `none`)
foldHazard    := "duplication" | "reordering"       -- Reference §19; "loss" is rule-position only
                                                    -- on a `many` of values, "duplication" also makes the
                                                    -- collection keep duplicates and order (Reference §5,
                                                    -- "Collections of values"); on a `many` of records it is
                                                    -- a validator error (checks catalog, F3)

derivedProp   := Identifier ":" propType "=" valueExpr
              | Identifier ":" "many" (ShapeName | scalarType) "=" setExpr
                                                    -- derived collection: a view (Reference §6, "Renamed and
                                                    -- derived collections"); setExpr only in this position
setExpr       := "(" ShapeName ("where" predicate)? ")"

timestampProp := Identifier ":" "timestamp" "on" ("create" | "update")

propType      := scalarType "?"?
              | "one" ShapeName "?"?                -- owned to-one side; "?" marks it optional
              | "many" ShapeName                    -- owned edge set (m2m); no "?" (empty collection is the absence)
                                                    -- ShapeName in either relationship position must resolve to a
                                                    -- base shape or a static union (a pure `or` of base shapes,
                                                    -- Reference §6); any other refinement name is a validator error
                                                    -- (checks catalog, F2), not a grammar concern
              | "many" scalarType                   -- owned collection of values; no "?"
scalarType    := "text" | "integer" | "long" | "decimal" | "double" | "boolean" | "Date" | "DateTime"
              | "uuid" | "duration" | "time" | "bytes"
                                                    -- Reference §5; a duration field holds a DurationLiteral's value
              | TypeName                            -- an extension type in scope (Reference §5, "Extension types"):
                                                    -- a registered type's own name, or the name a usesDecl gives it;
                                                    -- a capitalized name that is neither is a validator error (F1)
```

Relationships always carry `one`/`many` — a bare shape name is not a type ([Reference §7](Reference-07-Derived-properties)'s examples are normalized to `parent: one Foo?`). One keyword, one meaning: the cardinality is always visible, and the parser never guesses whether `Foo` is a forgotten scalar or a relationship. The descriptors declare *ownership of the relationship*, not direction ([Reference §6](Reference-06-Relationships)): a declared `one` is the stored side of a one-to-many, a bare declared `many` is the stored edge set of a many-to-many; the inverse side is always inferred, and declaring both sides of one relationship is a validator error ([checks catalog](checks), [V19](checks#v19--relationship-declaration-coherence)), not a grammar concern.

`initially` attaches to any stored property, relationships included ([Reference §5](Reference-05-Scalars)). It cannot attach to `derivedProp` or `timestampProp` — not grammar's doing; those properties' own rules exclude it (nothing stored to initialize; commit metadata is never author-supplied).

## Refinement declarations

```
refinementDecl := "shape" ShapeName "=" refExpr refinementBody?

refExpr        := refTerm ("or" refTerm)*                -- same precedence as predicates:
refTerm        := refFactor ("and" refFactor)*           -- "not" > "and" > "or" (Reference §9)
refFactor      := "not"? refAtom
refAtom        := ShapeName ("where" predicate)?         -- base or named refinement, optionally narrowed
               | "(" refExpr ")"

refinementBody := "{" refinementMember* "}"
refinementMember := "captured" Identifier ":" propType "=" valueExpr
                 | Identifier ":" propType "=" valueExpr                -- derived (live)
                 | "frozen" (Identifier ("," Identifier)*)?             -- bare "frozen" = every stored field
                 | "undeletable"                                        -- the deletion gate (OQ37-R1): state-scoped, frozen's sibling
```

The `Base where predicate` form and composition are one grammar: `shape OverdueInvoice = Invoice where balance > 0 and due < today` is a `refAtom` with a `where` clause; `shape UrgentOverdueTicket = Overdue and HighPriority and Open` is a `refTerm`; mixing is legal (`Quoted and (Invoice where total > 100)`). `or` may compose unrelated base shapes — a cross-base union, `shape Content = Post or Photo` — while `and` across distinct bases is provably empty; both are type checks, not grammar rules ([Reference §9](Reference-09-Composing-refinements), [checks catalog](checks) [F2](checks#f2--type-checking)/[A2](checks#a2--dead-machinery)). A rule's `condition` composing unrelated shapes inline must name the union first ([F2](checks#f2--type-checking)).

## Rule declarations

```
ruleDecl      := "rule" ShapeName
                 "when" ("leaving" (("or" | "not") "deleted")? | "deleted")? condition
                 -- the trigger family (Reference §13): `when X` entry; `when leaving X` predicate exit;
                 -- `when deleted X` deletion; `when leaving or deleted X` either cause;
                 -- `when leaving not deleted X` predicate exit, stated (checks catalog, A6)
                 triggerClause?
                 ruleByClause?
                 ("tolerates" ruleTolerance ("," ruleTolerance)*)?
                 "{" ruleBody "}"

ruleTolerance := "loss" | "contention"   -- contention: OQ40's width acceptance (advisory A5)

condition     := ShapeName
              | "(" refExpr ")"          -- inline refinement, e.g. (Delinquent where not suspended)

triggerClause := ("on" | "after") trigger ("," trigger)*
trigger       := "commit" | ShapeName    -- a ShapeName here names a schedule
```

Omitted `triggerClause` means `on commit` ([Reference §11](Reference-11-rule-when-on)). The `on`/`after` preposition is meaningful only for the `commit` entry; schedule entries inherently begin their own transactions either way ([Reference §17](Reference-17-Schedule-triggers)). That `after` requires `commit` in its list, and the boundary/apparatus checks, are validator obligations, not grammar.

```
ruleBody      := statement (thenLine? statement)*
thenLine      := "then"                  -- on its own line, between two statements (Reference §15)

statement     := assignment | creation | deletion | forEach

assignment    := path "=" valueExpr
                 -- the target may traverse at most one `many` hop (fan-out assignment: one write per
                 -- member, `this.invoices.customer = ...`); depth and target legality are validator
                 -- rules (checks catalog, V20), not grammar

deletion      := "delete" path
                 -- instance-granularity mutation (OQ37): the target is a literal static to-one path
                 -- (`delete this`, `delete draft`); no fan-out. Legality — referential completeness,
                 -- existence-dependency, the `undeletable` gate, stranding — is the validator's
                 -- (checks catalog, V23–V28), not grammar

forEach       := "for" "each" loopBinding ("," loopBinding)* "{" ruleBody "}"
loopBinding   := Identifier "in" valueExpr
              | Identifier "from" valueExpr "to" valueExpr
                 -- the block's statements run once per element of each binding, nested for a
                 -- product (Reference §14); `in` names a collection the rule can reach, `from ... to`
                 -- the whole numbers from the first bound to the second, both included, empty when the
                 -- second is below the first; a block holds creations and blocks only, and a name is
                 -- new in the rule — validator rules (checks catalog, V34), not grammar

creation      := ShapeName "from" "{" fieldInit* "}" ("as" Identifier)?
              | ShapeName "for" valueExpr (fieldInit)* ("as" Identifier)?
                 -- the compact form: Receipt for invoice sentOn: now; `as` names the created
                 -- record for the body's other statements (Reference §14; checks V33)
fieldInit     := Identifier ":" valueExpr                  -- newline- or comma-separated inside "from { }"
```

`from { ... }` is the general, totality-checked form; `for` populates the one type-matched field and stops applying when more than one field matches ([Reference §14](Reference-14-for)). Statements not joined by `then` are unordered ([Reference §15](Reference-15-then)).

## `never` declarations

```
neverDecl := "never" (ShapeName | "(" refExpr ")") ("tolerates" "contention")?
```

Same operand shapes as a rule's condition — a named refinement or an inline one ([Reference §21](Reference-21-never)).

## `requires` declarations

```
requiresDecl := ShapeName "requires" predicate ("tolerates" "contention")?
```

The requirement spelling of a prohibition ([Reference §21](Reference-21-never)): `Subject requires P` means `never (Subject where not P)`, and the parser derives the complement, pushing the negation to the atoms (comparisons flip, `and`/`or` exchange, `is`/`is not` and `is some`/`is none` and `is empty`/`is not empty` exchange; anything else is wrapped in `not`). The subject is any shape or refinement name. This is the one declaration that begins with a name rather than a keyword; the `requires` token after it decides the parse. Within the predicate, refinement membership has a second spelling, `valueExpr "to" "be" RefinementName`, the same atom as `valueExpr "is" RefinementName`; it takes a refinement name only, and non-membership, optionality, and emptiness keep their `is` spellings. The derived complement is spelled with `is`.

## `act` declarations

```
actDecl       := "transient"? "act" ShapeName byClause? "{" members "}" withClause?

byClause      := "by" ShapeName ("as" Identifier)?            -- the actor (Reference §22, "The actor")
visibleClause := "visible" "to" (ShapeName ("as" Identifier)? ("where" predicate)? | "anyone")
                                                              -- who may see (Reference §8, "Who may see a record"):
                                                              -- a member of a shape body (record-level), after a
                                                              -- property's declaration (field-level), or a declaration
                                                              -- of its own at the top level with a ShapeName or
                                                              -- "anyone" and nothing else (the spec-wide default)
ruleByClause  := "by" ShapeName "?"? ("as" Identifier)?       -- a rule's actor, `?` when some path has none (checks V31)

withClause    := "with" withEntry ("," withEntry)*
              | "with" "{" withEntry ((","|newline) withEntry)* "}"
withEntry     := Identifier withClause?    -- an inverse edge (or recognized-form view) of the enclosing level,
                                           -- or a `one` field whose reference may be supplied by value (Reference §6)
```

An act is a base shape whose instances are externally committable — transpilation generates a function taking that shape as input, the thing the engineer's own transport code calls to submit a mutation; a `shape` enters state only as a rule's effect ([Reference §22](Reference-22-Open-unresolved), "External input"). The body is `shapeDecl`'s member block; a refinement (`=`) cannot be declared `act`. An act is declared in exactly one place, where its fields are — there is no second statement that opens a shape declared elsewhere (OQ50, settled 2026-10-12). The language names no mechanism: transport, persistence, and serialization are the engineer's code, outside the spec ([Reference §22](Reference-22-Open-unresolved), "External input"). `transient` marks the act as an input to the state rather than a member of it — the instance exists only within its own commit's transaction ([Reference §4, "Transient acts"](Reference-04-State-and-commits#transient-acts-transient-act); semantics in [`evaluation.md`](evaluation); the isolation and totality obligations are checks [V17](checks#v17--transient-isolation)–[V18](checks#v18--transient-totality-every-request-gets-a-response-coarse)). A `withClause` declares the act's **closure**: each named edge's parts arrive inline with the act as created values rather than references ([Reference §6, "Inline part creation"](Reference-06-Relationships#inline-part-creation-with)), and a nested `withClause` resolves against the part shape the same way. An entry must name an inferred inverse of the enclosing level's shape, or a declared view of exactly the recognized-inverse form — legality, the tree restriction, and the transient-container refusal are validator territory (checks [V22](checks#v22--closure-declaration-legality)). A `byClause` names who commits the act ([Reference §22](Reference-22-Open-unresolved), "The actor"): a shape, a static union, or a refinement — the last being the role gate in the header, desugared to `Act requires actor to be Refinement`; `as` binds the actor under the author's name. `act` and `transient` join the keyword list; `with` is contextual, not reserved — it reads as the closure keyword only in the position this production gives it, and everywhere else it is an ordinary identifier.

## Value expressions

The formalization [Reference §22](Reference-22-Open-unresolved) lacked. Predicates (below) and value expressions share atoms, paths, narrowing, and calls; a predicate *is* the boolean case of this grammar.

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
            | "empty"                    -- the empty collection; types only where a `many` is expected
            | path                       -- Reference §10: pathRoot (accessor Identifier)*, aggregates, selectors,
                                         -- the sugared ("Shape" "for" expr) singular query
            | funcCall
            | "(" valueExpr ")"

funcCall    := BuiltinName "(" valueExpr ("," valueExpr)* ")"      -- closed list: lowercase, uppercase, trim, length, max, min (Reference §5)
```

The conditional is **`if p then a else b`**, always fully spelled — `else` is mandatory (a conditional that can produce no value would need an implicit `none`, and optionality should be visible in the type, not manufactured by a branch). [Reference §7](Reference-07-Derived-properties)'s former postfix spelling (`none if parent is none else ...`) is normalized to this form; [§12](Reference-12-Assignment)'s ledger example already used it.

Narrowing is shared with predicates and branch-sensitive ([Reference §10](Reference-10-Predicate-expressions)): inside a `then` branch, the governing predicate's `is some` / `is none` / `is Refinement` checks license `.` exactly as they do within a conjunction; `?.` remains the no-narrowing escape.

Type rules ride on the Kotlin grounding ([Reference §5](Reference-05-Scalars)): arithmetic over the numeric scalars, `+`/`-` between `Date`/`DateTime`/`time` and a duration (a literal or a `duration` field; calendar step on `Date`, exact time on `DateTime`, wrapping on `time`), `+`/`-` between two durations, `-` between two `Date`s, two `DateTime`s, or two `time`s giving a duration, and `+`/`-` on a declared `many` as set union and removal, or as append and removal of every occurrence on a `many` of values that tolerates duplication (element or collection on the right; [Reference §6, "Committing and assigning collections"](Reference-06-Relationships#committing-and-assigning-collections), [Reference §5, "Collections of values"](Reference-05-Scalars#collections-of-values-many)). `+` as text concatenation only if we ever admit it — currently **not** admitted; the duration and collection cases are the only operator overloads.

## Predicates

[Reference §10](Reference-10-Predicate-expressions)'s grammar, unchanged, with one generalization: `comparison := valueExpr compareOp valueExpr`, where `compareOp` is `==` `!=` `<` `<=` `>` `>=` or one of the text operators `contains`, `starts with`, `ends with`, `like` (text operands only, exact and case-sensitive; `like` takes a pattern with `%` for any run of characters, `_` for any one, `\` escaping either; [Reference §10](Reference-10-Predicate-expressions)) — its operands are value expressions (they always were; [§10](Reference-10-Predicate-expressions)'s `expr := path (+/- duration)?` was the fragment of value grammar predicates needed before this document existed). [§10](Reference-10-Predicate-expressions) remains the normative statement of `is`, `exists`, bare boolean atoms, aggregates, selectors, `as` bindings, sibling joins, and `for`-sugar legality.

