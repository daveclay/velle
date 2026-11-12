# OQ69 — A function's store side: how a function a spec calls says what a store may run for it

**Status:** opened 2026-10-26 at Dave's request after `matches` landed. Settled 2026-11-11, every decision answered; build pending. §3 is the implementation plan, with one build question for Dave; §4 holds the work items.

**In plain terms:** A spec may call a function on a stored value, such as the lowercase form of a name, or whether a product code fits a pattern. When a read filters a million stored records by that call, the database should do the narrowing and hand back only the records that fit, rather than every record for Velle to check one by one in memory. Today only a few of Velle's own functions get that, each written by hand into the SQL store, and a function an engineer registers never does. This question is how a function says what a database may run for it, database by database, and what a store does when it cannot.

**See:** OQ66 (settled table in `working-docs/QUESTIONS.md`: a registered function's signature and body; this file is the store side and nothing else) · OQ67 (settled table: the SQL layer `stores/sql` and the two dialects `stores/sqlite` and `stores/postgres`; a rendering is written at the layer or at a dialect) · `stores/sql/src/main/kotlin/velle/sql/SqlTypeBinding.kt` (the type side of the same split: a binding per type and store, with `answers` naming the comparisons the column answers faithfully) · OQ103 (whether a dialect may override a type binding, the same question D2 asks for a function) · `compiler/src/main/kotlin/velle/Query.kt` (`compileCmp`, `tryCmp`: a column test needs a path on one side and a constant on the other, so a call is never a column test today; `constFold`, `foldInterpolation`) · `stores/sql/src/main/kotlin/velle/sql/SqlStore.kt` (`WhereRenderer.render`, `renderCmp`, `degrade`) · `stores/sqlite/src/main/kotlin/velle/sqlite/SqliteDialect.kt` (`prepare`, `regexMatch`: the one function rendering that lives at a dialect) · `stores/postgres/src/main/kotlin/velle/postgres/PostgresDialect.kt` (`regexMatch` returns null) · `stores/sqlite/src/test/kotlin/velle/sqlite/RegexOverSqliteTest.kt` (`matches` in the store) · `wiki/evaluation.md` "The pre-filter" and `wiki/GLOSSARY.md` "candidate pre-filter / superset contract" (over-returning is always legal; the predicate decides in memory) · Reference §10 (the predicate forms), Reference §5 (the function list, "Text made from other values") · `wiki/Extension-functions.md` (a function needs no store binding) · `wiki/Extension-types.md` "Holding a type in a store" (the shape a function's section would mirror) · `wiki/Read-API.md` (`where { title.length gt 10 }`, the query handles) · OQ115 (a generated read-API handle per registered function) · OQ96 (a walk is never rendered) · OQ80 (settled table: a text literal with a column piece stays with the evaluator until this question renders it) · OQ75 (settled table: a SQL rendering of `number` is this file's work item)

---

Terms used in this file. The **pre-filter** is the SQL `WHERE` clause Velle compiles from a predicate and sends to the store to narrow the candidate records. The **superset contract** is the rule that the pre-filter may return more records than match, never fewer, and the evaluator decides in memory (`wiki/GLOSSARY.md`). A **rendering** is the SQL the pre-filter writes for one function call. The **store side** of a function is its rendering, per store. A **binding** is a library object that pairs one function, or one type, with what one store layer does with it.

## 1. What runs today

Three ways a function reaches the store, none declared by the function. Checked against HEAD on 2026-11-11.

**Rendered as SQL by the SQL layer.** `is empty` on a text renders `(col IS NULL OR TRIM(col) = '')` (`SqlStore.kt`, `WhereRenderer.render`, the `QF.TextBlank` case). `contains`, `starts with`, `ends with`, and `like` render `LIKE ... ESCAPE '\'` (`SqlStore.kt`, `renderCmp`). The layer knows each by name.

**Installed on the connection and rendered as a call.** `matches` renders `col REGEXP ?` on SQLite (`SqliteDialect.kt`, `regexMatch`) after `prepare` creates a `REGEXP` function on the connection, once per underlying connection. A connection that is not SQLite's own and cannot be unwrapped to one throws at `prepare` with the message "SQLite store: the connection is not SQLite's own and cannot be unwrapped to one", so a failed installation stops the store; nothing degrades silently any more. PostgreSQL's `regexMatch` returns null, so `matches` degrades to the superset there and runs in memory (`PostgresDialect.kt`). `RegexOverSqliteTest.kt` shows the SQLite side.

**Not pushed down.** `lowercase`, `uppercase`, `trim`, `length`, `abs`, `%`, `year`, `month`, `day`, `dayOfWeek`, `hour`, `minute`, `number`, `date`, `datetime`, a text literal with a column piece (Reference §5, "Text made from other values"), and `sum`, `max`, `min` over a `many` of values. The compiler's column test takes a path on one side and a constant on the other (`Query.kt`, `tryCmp`); a call on either side is not a column test, the clause degrades to `TRUE` in positive position (`SqlStore.kt`, `degrade`), and the evaluator decides over every candidate record. Correct under the superset contract, and a table scan. A text literal whose pieces are all constants folds to one text (`Query.kt`, `foldInterpolation`) and renders as a plain column test.

The third way is the baseline every function starts from: a function with no store side works (`wiki/Extension-functions.md`, "A function needs no store binding"). So the question is not whether a function must push down but how one that can says so, and how a store that cannot honour it says that.

The two spellings the decisions below are written against, both validated on 2026-11-11, with `Product` taken from `examples/orders/orders.velle` and a `name` field added:

```velle
act Product {                                    -- a record the world creates
    name: text                                   -- a stored text
    sku: text                                    -- a stored text
}

shape AdaProduct = Product where lowercase(name) starts with "ada"   -- a call on the left of a text operator: tryCmp finds no column test, the clause degrades to TRUE, the evaluator reads every Product
shape KitProduct = Product where sku matches "^KIT-[0-9]+$"          -- a path beside a constant: a column test; SQLite renders `"sku" REGEXP ?`, PostgreSQL degrades to TRUE
```

A query from the read API, `products.where { name.lowercase startsWith "ada" }` (`wiki/Read-API.md`), compiles to the same pre-filter and scans the same table. The engineer cannot see that from the handle; a generated handle per registered function is OQ115.

## 2. What to decide

### D1. Whether a function's rendering is a binding of its own, per function and store · Decided 2026-11-11

Is a function's rendering a separate binding, one per function and store layer, written by whoever knows the store, as a type's `SqlTypeBinding` is? The alternative is one function object carrying all three parts (signature, body, rendering), so a function library depends on every store it renders for.

Recommendation: a binding of its own, `SqlFunctionBinding` in `stores/sql`, registered through `META-INF/services/velle.sql.SqlFunctionBinding` as a type binding is through `META-INF/services/velle.sql.SqlTypeBinding`. The function library stays free of every store, which is the choice OQ65 D5 made for types. The builtins' renderings move out of `SqlStore` into bindings Velle ships, so Velle codes to its own extension interface (`compiler/CLAUDE.md`).

Costs:

- The engineer who writes both the function and its rendering writes two classes in two libraries.
- A binding naming a function that is not on the classpath is a misconfiguration the store refuses at construction, so a typo in the qualified name is loud.
- Until the builtins move, two mechanisms render functions.

Against the spec in §1, the binding Velle would ship for its own `lowercase`, whose qualified name is `velle.lowercase` (`BuiltinFunctions.kt`, `namespace = "velle"`):

```kotlin
// stores/sql: the binding names the function and speaks SQL-layer SQL; it names no database (D2 decides what a dialect may replace)
object LowercaseSql : SqlFunctionBinding {
    override val function: String = "velle.lowercase"                       // the qualified name the function registers under (OQ66)
    override fun render(operands: List<String>): String = "LOWER(${operands[0]})"   // D4 decides whether this is a template or a function
}
```

With it, `AdaProduct`'s clause renders `LOWER("name") LIKE ? ESCAPE '\'` and the evaluator still decides each returned row (D5).

#### Proposed changes

- `stores/sql/src/main/kotlin/velle/sql/SqlFunctionBinding.kt`: a new interface beside `SqlTypeBinding`, loaded from the classpath or an explicit list, keyed by the function's qualified name.
- `stores/sql/src/main/kotlin/velle/sql/SqlStore.kt`: `renderCmp` stops knowing `contains`, `starts with`, `ends with`, `like`, `matches`, and `is empty` by name; each becomes a shipped binding, and a call in the pre-filter renders through its binding or degrades.
- `wiki/Extension-functions.md`: a section "Holding a function in a store", mirroring `wiki/Extension-types.md` "Holding a type in a store".
- `wiki/Store-API.md`: the binding named beside the type binding.

**Decided:** yes, 2026-11-11. Dave: the binding is a separate thing from the function, and where it is packaged is the author's choice. Alice may own `finance` and Bob `finance-sql`; Alice may ship the SQL bindings inside `finance` herself, taking the dependency on `stores/sql`; Alice may ship MySQL and PostgreSQL bindings and Dana the SQLite one in a `finance-sqlite` library. The first cost above is therefore not a cost: two classes, in one library or several. For the API it means anyone providing a custom function has a way to provide the dialect-specific implementations, which D10 gives the shape of.

### D2. Whether a dialect may override a binding's rendering · Decided 2026-11-11

May a dialect replace, by the function's qualified name, the rendering a binding gives for that dialect, as the last word on its own database?

Under D10 the database-specific renderings belong to the function's bindings, so a dialect has nothing to contribute in the ordinary case. The override is for a rendering the binding author got wrong on a database they do not run, fixed by the dialect author without waiting on the binding's next release. OQ103 asks whether a dialect may override a type binding; the two should be answered alike.

Recommendation: yes, with the precedence fixed once: the dialect's override, then the binding claiming that dialect, then the SQL-wide binding, then degradation.

Costs:

- Two places a rendering can come from, so a surprising rendering has two files to look in.
- A dialect override is a second thing to test per database; `SqlStoreConformance` (`stores/sql`) gains a case for it.

#### Proposed changes

- `stores/sql/src/main/kotlin/velle/sql/SqlDialect.kt`: a rendering per qualified function name the dialect chooses to override, defaulting to none; `regexMatch` is removed, its work moving to the `matches` bindings under D10.
- `wiki/Store-API.md`: the order of precedence.

**Decided:** no, 2026-11-11. A dialect never renders a function, so `SqlDialect` gains no override hook and the first proposed change above does not happen; `regexMatch` is still removed, under D10, and there is no precedence to document. Dave's reasoning: a binding is a library. Bob owns `finance-sql`, Chris uses it; when Chris finds a bug he removes the library and replaces it with his own or an alternative, which is normal library behaviour in any system. The owner of the spec decides which binding runs, and Velle cannot override that choice, even to fix a bug. A dialect could not do so in any case: for `PostgresDialect` to render `velle.finance.roundToCents` Velle's source would have to name a library it has never heard of, so the only functions a dialect could ever render are Velle's own, and D10 makes those bindings too. No override mechanism of any kind is added; two classpath bindings claiming one function and one dialect are refused at construction (D10) and the owner removes one.

### D3. Whether `matches` keeps running in memory on PostgreSQL · Decided 2026-11-11

Does `matches` keep running in memory on PostgreSQL until the dialect translates a Java pattern to PostgreSQL's syntax, rather than rendering PostgreSQL's `~` and accepting that a pattern PostgreSQL cannot read fails the query?

The ways out: render `~` anchored and accept the syntax risk; translate Java patterns to PostgreSQL's syntax in the dialect, degrading what has no translation; or restrict `matches` to a portable subset the compiler checks. A failed query is worse than a scan: it breaks the promise, D5, that a rendering is only ever a performance surface.

Recommendation: keep it in memory now. A translation is a dialect replacement under D2, added as a work item when a PostgreSQL system measures the scan slow. This decision keeps today's behaviour and changes nothing.

**Decided:** yes, 2026-11-11. `matches` runs in memory on PostgreSQL. Under D2 and D10 a translation would be a binding claiming `PostgreSQL`, not a dialect replacement, from anyone who wants it; none is opened now. Dave confirmed that "no" would have meant rendering `~` and risking a failed query, never that `matches` is unavailable without a database implementation: with no rendering it runs in memory as any function does (D5).

### D4. What a rendering is · Decided 2026-11-11

Is a rendering a text template over numbered holes, `"LOWER({0})"` and `"{0} REGEXP {1}"`, with the store binding the constants? The alternatives are a Kotlin function from rendered operands to a SQL fragment, or a value of a small expression type the SQL layer renders.

Recommendation: the template. It is the least power and the least to get wrong, and a binding is then a name and a string. Either way the compiled pre-filter changes: a column test's left side becomes a column expression, a path or a call of a registered function over a path with constant arguments, and the renderer writes the template around the column and the operator around that. A predicate function, one whose result is `boolean`, renders as a clause on its own.

Costs:

- A template cannot ask the dialect for a cast. The one case that needs one is a text literal with a non-text column piece (OQ80 D6: the piece renders as its type's `canonical` text), which is this file's second work item; a Kotlin function would take the cast from the dialect directly, so that item may force the richer form.
- A call whose arguments are all constants is folded by `constFold` before the store sees it, so a template never meets one.

Against the spec in §1, `AdaProduct` under D4: `Query.kt` compiles `lowercase(name)` to a column expression, a call of `velle.lowercase` over the path `name`; `SqlStore` renders the binding's template with `"name"` in hole 0 and wraps the text operator around it.

#### Proposed changes

- `compiler/src/main/kotlin/velle/Query.kt`: `QF.Cmp` takes a column expression, not a field name; `tryCmp` accepts a call of a registered function over a path with constant arguments on the path side.
- `stores/sql/src/main/kotlin/velle/sql/SqlStore.kt`: `renderCmp` renders the column expression through the binding's template before the operator.
- `wiki/evaluation.md` "The pre-filter": what the pre-filter now compiles.

**Decided:** the expression type, with named operands, 2026-11-11. Not the template recommended above: the template cannot write the column piece this file carries as a work item, and a Kotlin function returning text leaves bind order and quoting to every author. A binding's `render` receives the operands under the parameter names of the signature the call matched, `operands["amount"]`, a variadic parameter's arguments as a list under its one name, and returns a value of a small sealed type in `stores/sql`, `SqlExpr`: `Column`, `Param`, `Literal`, `Call(name, args)`, `Infix(left, op, right)`, `Cast(expr, StorageClass)`, and `Raw(sql)` as the escape for a form the type does not cover. The SQL layer alone renders the tree, writes `?` for a constant, queues its value, quotes a column, and asks the dialect for a cast. A binding may ask which signature the call matched, since `max` over a `many` and `max` over two scalars are different SQL. Every name a binding reads must be a parameter of a signature of the function it claims, checked when the store is built, so a wrong name is refused at construction, not at query time. The second proposed change above renders through `SqlExpr` rather than a template; the rest stand.

### D5. Whether a rendering is only ever a performance claim · Decided 2026-11-11

Does a function's rendering stay on the performance side of the line, with the evaluator always deciding in memory, so a binding never says whether its rendering is faithful or a superset?

`wiki/evaluation.md` says over-returning is always legal and only `fetchCaptures` sits on the correctness side. Under that, "faithful" is a word the binding never needs; a type binding needs `answers` only because a comparison the column does not answer faithfully would under-return.

Recommendation: yes. It is the simpler contract and the one `matches` was built under.

Costs:

- A faithful rendering can never skip the in-memory check, so a read is never served by the database alone.
- A rendering that under-returns is a correctness bug nothing in the contract catches; D8 is the guard.

#### Proposed changes

- `SqlFunctionBinding` carries no faithfulness claim and no `answers`.
- `wiki/Store-API.md`: a function rendering is a performance surface, stated once.

**Decided:** yes, 2026-11-11, in these words rather than "performance claim": a custom function is agnostic to a database-backed store. The function's body always runs and has the last word on membership; a binding's SQL only narrows the rows the store hands back for the body to decide over. The runtime calls the body everywhere a spec uses the function, a derived property, an assignment, a `never`, a `from` block, and the store is touched at one place only, `fetchCandidates`. So a function with no binding runs only in memory, a function with a binding runs in both, and no function runs only in SQL. A body that reaches a store of its own is outside OQ66's contract, which is documented and not enforced. Dave asked that the agnosticism be documented as the key point, so the wiki change is the first of these:

- `wiki/Extension-functions.md`: a section stating that a custom function is agnostic to the store, that the body always runs and decides, that a binding only narrows, and that a body reaching a store is outside the contract.
- `wiki/Store-API.md`: the same in one sentence, from the store's side.

### D6. What a store does when a binding's DDL is refused · Decided 2026-11-11

Does a store whose database refuses a binding's DDL say so when the schema is created, naming the function and the database's error, and leave that function to the body, with an option for the owner to insist, which turns the report into a refusal to start?

Revised 2026-11-11 from a draft about installing a callback on a connection, which D10's amendment set aside. A binding that needs a function the database does not have ships DDL, `CREATE OR REPLACE FUNCTION ...`, which the store runs with `createSchema`, once per database, stored in the schema beside the tables. A database can refuse it: the role may not create functions, the database is a read replica, the schema is not Velle's to change. Today nothing in Velle ships DDL for a function, so nothing is refused.

Recommendation: yes. Report and degrade by default, because D5 makes degradation safe: the body still decides, and the only loss is the scan. The report is what makes the loss visible, since a scan nobody reads is the failure mode. The insist option is a list of qualified function names the owner's system depends on rendering; a refused DDL for one of them stops the store from starting, with the database's error.

Costs:

- One more construction-time surface on `SqlStore`, beside E4.
- A system that insists ties itself to a database and a role with DDL rights, which is the owner's call and should be one line.
- SQLite's `REGEXP` is not covered: it is connection preparation in the dialect, and an un-unwrappable connection keeps failing hard in `native`, which also guards the transaction modes.

#### Proposed changes

- `stores/sql/src/main/kotlin/velle/sql/SqlFunctionBinding.kt`: an optional `ddl` the binding contributes.
- `stores/sql/src/main/kotlin/velle/sql/SqlStore.kt`: `createSchema` runs each binding's DDL for the bindings selected for the dialect; a refusal is recorded against the function, available after construction, and the function is left to the body; a constructor option naming the functions the owner insists on.
- `wiki/Store-API.md`: the report and the option.

**Decided:** yes, 2026-11-11. The store reports a refused DDL at schema creation, naming the function and the database's error, and leaves the function to the body; an owner may insist on a list of qualified function names, and a refused DDL for one of them stops the store from starting.

### D7. Where an error inside an installed function surfaces · Decided 2026-11-11

Does the validator's `checkQueryPredicate` compile a `matches` pattern that arrives from the read API before the query runs, so a malformed pattern is a query diagnostic and never a store failure?

`REGEXP` compiles a pattern the validator already compiled, so it cannot fail on a checked spec. A query from the read API (Reference §22) carries patterns the validator never saw, and today the first place they are read is inside the SQLite function at query time.

Recommendation: yes. Costs:

- The validator compiles the pattern with Java's syntax. A dialect that translates patterns (D3) checks the translated form as well, a second compile per query.

#### Proposed changes

- `compiler/src/main/kotlin/velle/Validator.kt`: `checkQueryPredicate` compiles a `matches` pattern constant and reports a malformed one as a query diagnostic.
- `wiki/Read-API.md`: a malformed pattern is refused before the query runs.

**Decided:** closed, 2026-11-11, by what is built; no changes. Dave: whether a regular expression is valid is not up to the function to decide. It is the type's: under OQ80 D9 a pattern is a value of the `Regex` type (`compiler/src/main/kotlin/velle/ext/builtin/RegexType.kt`), a literal beside `matches` is read as one when the spec or the query is checked, and a text that is not a regular expression is refused as F2 there (`Validator.kt`, `checkMatchesPattern`, `compileOrRefuse`). `Read.kt` runs `checkQueryPredicate` on every query's `where` before the query runs, so the read API gets the same refusal. The premise above, that a pattern from the read API is first read inside the database's function, was true when this file was opened and is not true at HEAD. The function, the binding, and the database never see an invalid pattern. The same holds for any constant argument: it is read through the parameter's type, in a spec and in a query alike.

### D8. Whether a binding carries samples the store checks at construction · Decided 2026-11-11

Does a function binding carry sample arguments that the store runs through both the rendering and the function's body when it is built, refusing to start on a disagreement, as E4 round-trips a type's samples through the dialect's column?

The evaluator and the store must agree, and SQL-wide names hide disagreements. SQLite's `LOWER` folds ASCII only while Kotlin's `lowercase()` folds Unicode, so `lowercase(name) == "àda"` rendered as `LOWER("name") = ?` under-returns a name spelled `ÀDA`. `strftime('%w')` counts Sunday as 0 while Velle's `dayOfWeek` is Monday 1 (`BuiltinFunctions.kt`, `DayOfWeek`, Java's `DayOfWeek.value`). Under-returning is a correctness bug the superset contract does not cover.

Recommendation: yes. A sample is one argument list and the body's answer for it; the store renders the call over a literal row holding the arguments, runs it, and compares.

Costs:

- Samples are hand-picked and catch only what they contain; a sample with no non-ASCII letter does not catch `LOWER`.
- A binding whose rendering is a superset by design, broader than the body, fails a strict comparison; the check compares for under-returning only, which is the only direction the contract forbids.

#### Proposed changes

- `SqlFunctionBinding` gains `samples`, each an argument list.
- `stores/sql/src/main/kotlin/velle/sql/SqlStore.kt`: at construction, each sample is rendered and run against the body; an under-return refuses the store as E4 does.
- `wiki/checks.md`: an E-series entry beside E4.

**Decided:** no, 2026-11-11. Dave was skeptical that samples are useful in practice unless they can feasibly cover all the edge cases of a custom function, and they cannot: a finite sample set proves the samples and nothing else, and a case such as `LOWER` under one database locale depends on a configuration the author never sees. A check that passes would give the owner confidence it has not earned. Samples on the function, samples on the binding, and a harness Velle ships were each considered and all set aside for the same reason. A binding is a library: its author tests it as any library author tests code that talks to a database, against the databases they claim, in their own build, and a binding whose SQL rejects a row the body would accept is a bug the owner replaces (D2). None of the proposed changes above happen. What Velle owns instead:

- `stores/sql` conformance suite: the renderer's handling of an absent value under `not` around a rendered call, the layer's contract, tested once with any binding.
- `wiki/Extension-functions.md` "Holding a function in a store": one sentence that a binding whose SQL rejects a row the body would accept makes the runtime miss a record, that nothing at runtime can detect it, and that the binding author tests against every database they claim.

### D9. Whether an aggregate over a `many` of values is left out of this question · Decided 2026-11-11

Is `sum(scores)` over a `many` of values, which reads a junction table, left out of this question?

Pushing it down is a correlated subquery at the SQL layer, not a rendering of a function over a column, and nothing in D1 to D8 serves it.

Recommendation: yes. It gets a tag of its own when a read over one is measured slow, as OQ96 does for a walk. This decision changes nothing.

**Decided:** yes, out, 2026-11-11, and for a stronger reason than the one above. Dave: a function can iterate over arbitrary values easily enough, but knowing what the function joins to is not merely difficult. The store API is responsible for fetching records from arbitrary data sources, and what a collection joins to might be data from an external API joined with records from the database. The function operates in memory over those records. An optimization can be made when it is a single database, but Velle will not assume that. So a function over a collection is never rendered, by principle, not by complexity, and no tag is opened for it. A proposal in chat the same day to render it as a correlated subquery the layer owns was set aside for the same reason.

### D10. Whether a function author provides a rendering per database · Decided 2026-11-11

Does a function author ship renderings for several databases themselves, one binding per database they know, with the store picking the binding for the dialect it runs on?

The case is an open-source function. Alice publishes `velle.finance.roundToCents` and knows SQLite and PostgreSQL round differently. She cannot edit `SqliteDialect` or `PostgresDialect`, which Velle owns, nor a MySQL dialect someone else will own, so a database-specific rendering that lives on the dialect is out of her hands. Nothing selects by dialect today: `SqlStore.kt` keys type bindings by type name alone, `bindings.associateBy { it.type }`, and `dialect.name` appears only in error messages. The type side never needed selection because `StorageClass` makes one binding serve every dialect; SQL text is not portable that way.

Recommendation: yes. A binding claims a function and the dialects it renders for.

- `SqlFunctionBinding` gains `dialects: Set<String>?`, null meaning any SQL database. `SqlDialect.name` becomes the stable identifier it is keyed by, `SQLite`, `PostgreSQL`, `MySQL`, no longer "for messages" only.
- The store loads every binding on the classpath, as it loads type bindings, and for each function picks the binding claiming its dialect, else the SQL-wide one, else degrades (D2 fixes where a dialect override sits).
- Two bindings on the classpath claiming the same function and the same dialect are a conflict the store refuses at construction, as two functions claiming one qualified name are refused by E1. A binding for a dialect that is not the one in use is loaded and ignored.
- A binding gains `install(conn: Connection)`, called by the store on every connection it takes, as `SqlDialect.prepare` is today, so a rendering that needs a function created on the connection, `REGEXP` on SQLite, can create it.
- Anyone may fill a gap: Chris, running MySQL, publishes `finance-mysql` holding a binding for Alice's function claiming `MySQL`. Alice did not have to know MySQL existed.

Costs:

- A binding that installs something depends on that database's driver, so `finance-sql` either declares SQLite's and PostgreSQL's drivers as optional dependencies or splits into `finance-sqlite` and `finance-postgres`. A binding that is only SQL text has no driver dependency and stays in one library.
- The dialect name is now a public contract a binding author writes against; renaming a dialect breaks bindings.
- Velle's own `matches` becomes two bindings: a SQL-wide one with no rendering, and a `SQLite` one in `stores/sqlite`, where the driver is, that installs `REGEXP` and renders `{0} REGEXP {1}`. `SqlDialect.regexMatch` goes away.

Against the spec in §1, what Alice ships for a function used as `shape Rounded = Product where roundToCents(price) == 10` once `Product` has `price: decimal`:

```kotlin
// finance-sql, depends on stores/sql only: the rendering every SQL database gets
object RoundToCentsSql : SqlFunctionBinding {
    override val function: String = "velle.finance.roundToCents"
    override val dialects: Set<String>? = null                              // any SQL database
    override fun render(operands: List<String>): String = "ROUND(${operands[0]}, 2)"   // half away from zero on most databases
}

// finance-sql as well: PostgreSQL's ROUND on a double is half to even, so the binding casts first
object RoundToCentsPostgres : SqlFunctionBinding {
    override val function: String = "velle.finance.roundToCents"
    override val dialects: Set<String>? = setOf("PostgreSQL")               // picked over RoundToCentsSql when the store runs on PostgreSQL
    override fun render(operands: List<String>): String = "ROUND(CAST(${operands[0]} AS NUMERIC), 2)"
}
```

The store on SQLite picks `RoundToCentsSql`; on PostgreSQL, `RoundToCentsPostgres`; on a MySQL dialect with no binding on the classpath, `RoundToCentsSql` again, since it claims any; and D8's samples check each against the body.

#### Proposed changes

- `stores/sql/src/main/kotlin/velle/sql/SqlFunctionBinding.kt`: `dialects` and `install` on the interface D1 adds.
- `stores/sql/src/main/kotlin/velle/sql/SqlDialect.kt`: `name` documented as the stable identifier bindings claim; `regexMatch` removed.
- `stores/sql/src/main/kotlin/velle/sql/SqlStore.kt`: bindings selected per function by dialect, the conflict check at construction, `install` called where `prepare` is.
- `stores/sqlite/src/main/kotlin/velle/sqlite/SqliteDialect.kt`: the `REGEXP` creation moves out of `prepare` into a `matches` binding claiming `SQLite`, in the same module.
- `stores/sql/src/main/kotlin/velle/sql/RegexBinding.kt` or beside it: the SQL-wide `matches` binding with no rendering.
- `wiki/Extension-functions.md` "Holding a function in a store": one binding per database the author knows, the SQL-wide one as the fallback, and how a third party adds a database.
- `wiki/Store-API.md`: the dialect name as a contract.

**Decided:** yes, 2026-11-11. Dave: support the ability for the function author to provide for multiple dialects. The case that decided it is an open-source function whose author ships one implementation for MySQL and a different one for PostgreSQL, and cannot edit any dialect. Amended 2026-11-11 on D6: the per-connection `install(conn)` hook above is dropped. Dave envisions a binding as custom SQL computed in `render`, or a function created in the database by DDL once, with the schema; registering a callback on a JDBC connection, which only SQLite's driver offers, is not worth designing for. A binding may therefore contribute DDL, run when `SqlStore.createSchema` runs, and nothing per connection. SQLite's `REGEXP` callback stays where it is, in `SqliteDialect.prepare`, as the dialect preparing its own connection, and the `matches` binding claiming `SQLite` renders `{text} REGEXP {pattern}` against it; D2's rule that a dialect never renders a function is kept, since preparing a connection is not rendering.

## 3. The build

The decided proposed changes, in build order. D3, D7, and D9 change nothing. One build question, B1, is at the end.

1. **The pre-filter carries a call** (D4). `compiler/src/main/kotlin/velle/Query.kt`: `QF.Cmp` takes a column expression, a path or a call of a registered function over a path with constant arguments, the operands held under the parameter names of the signature the call matched, a variadic parameter's as a list; `tryCmp` accepts a call on the path side; a predicate function renders as a clause of its own. A call whose arguments are all constants still folds in `constFold`.
2. **The SQL expression type** (D4). `stores/sql/src/main/kotlin/velle/sql/SqlExpr.kt`: a sealed type with `Column`, `Param`, `Literal`, `Call(name, args)`, `Infix(left, op, right)`, `Cast(expr, StorageClass)`, `Raw(sql)`; rendered only by `SqlStore`'s renderer, which writes `?` for a `Param`, queues its value, quotes a `Column`, and asks the dialect's column for a `Cast`.
3. **The binding** (D1, D4, D6, D10). `stores/sql/src/main/kotlin/velle/sql/SqlFunctionBinding.kt`: `function` (the qualified name), `dialects: Set<String>?` (null for any SQL database), `render(operands): SqlExpr` with the matched signature askable, and an optional `ddl`; loaded from `META-INF/services/velle.sql.SqlFunctionBinding` or an explicit list, as `SqlTypeBinding` is.
4. **The store selects and checks** (D2, D6, D10). `stores/sql/src/main/kotlin/velle/sql/SqlStore.kt`: at construction, for each function pick the binding claiming the dialect, else the SQL-wide one, else none; refuse two classpath bindings claiming one function and one dialect; refuse a binding naming a function not on the classpath or an operand name that is no parameter of any of its signatures. `createSchema` runs each selected binding's DDL; a refusal is recorded against the function, readable after construction, and the function left to the body; a constructor option lists the functions the owner insists on, a refused DDL for one of which stops the store. No override of any kind.
5. **The renderer** (D1, D4, D8). `SqlStore.kt`, `renderCmp`: a column expression that is a call renders through its binding's `SqlExpr` or degrades; a rendered call under `not` is guarded against an absent value as `!=` is today, so an absent argument reads as Velle reads it. `renderCmp` stops knowing `matches` by name.
6. **The dialects** (D2, D10). `stores/sql/src/main/kotlin/velle/sql/SqlDialect.kt`: `regexMatch` removed; `name` documented as the stable identifier a binding claims. `stores/sqlite/src/main/kotlin/velle/sqlite/SqliteDialect.kt`: `regexMatch` removed, `prepare` unchanged; a `matches` binding claiming `SQLite` in the same module renders `Infix(text, "REGEXP", pattern)`. `stores/postgres/src/main/kotlin/velle/postgres/PostgresDialect.kt`: `regexMatch` removed. No SQL-wide `matches` binding is needed, since a function with no binding degrades; D10's proposed change naming one is dropped.
7. **Tests.** `stores/sql` conformance suite (`SqlStoreConformance`): a registered test function with a binding renders and the body still decides; a binding claiming the dialect is picked over the SQL-wide one; two bindings claiming one function and one dialect are refused; a wrong operand name is refused; an absent argument under `not`; a refused DDL is reported and the function left to the body; the insist option stops the store. `stores/sqlite/src/test/kotlin/velle/sqlite/RegexOverSqliteTest.kt` passes unchanged through the new binding. The test function and binding live beside `compiler/src/test/kotlin/velle/TestFunctions.kt` and in `stores/sql`'s test sources.
8. **Wiki.** `wiki/Extension-functions.md`: a section "Holding a function in a store", mirroring `wiki/Extension-types.md` "Holding a type in a store": a function is agnostic to the store, the body always runs and has the last word, a binding only narrows (D5); the binding's shape and the operand names (D4); one binding per database the author knows, the SQL-wide one as the fallback, packaged in the function's library or any other, and how a third party adds a database (D1, D10); a binding is a library the owner replaces, never overridden (D2); a binding whose SQL rejects a row the body would accept makes the runtime miss a record, nothing at runtime detects it, and the author tests against every database they claim (D8); a body that reaches a store is outside the contract (D5); DDL and its refusal (D6). `wiki/Store-API.md`: the function binding beside the type binding, the dialect name as a contract, the report and the insist option, the one sentence on narrowing from the store's side. `wiki/evaluation.md` "The pre-filter": a call over a column is compiled and rendered through a binding or degrades.
9. **The settling commit.** The settled row in `working-docs/QUESTIONS.md`, this file deleted, the OQ69 line removed, OQ96's and OQ115's lines re-read for a stale pointer.

The two work items in §4 that predate the build, `number(text)` and the column piece, are built after step 8 through the same mechanism and are not part of the settling commit unless they fall out of it.

**B1.** D1's proposed change moves the text operators `contains`, `starts with`, `ends with`, `like`, and `is empty` out of `renderCmp` into shipped bindings. A binding is keyed by a registered function's qualified name, and the text operators are not registered functions; making them so is OQ114. Does the build leave the text operators in `renderCmp` as they are, moving only `matches`, until OQ114 settles? Recommendation: yes; step 5 above is written that way.

## 4. Work items

- [ ] Build steps 1 through 6 of §3: the call in the pre-filter, `SqlExpr`, `SqlFunctionBinding`, selection and checks in `SqlStore`, the renderer, and the dialects.
- [ ] Write the conformance cases of §3 step 7 and keep `RegexOverSqliteTest` passing.
- [ ] Write the wiki changes of §3 step 8.
- [ ] Land the settling commit of §3 step 9.

- [ ] Render `number(text)` (Reference §5) in SQL only behind a dialect guard: `CAST` alone is unsound, SQLite's gives 0 for a text that is not a number and PostgreSQL's throws, so until a dialect guards the cast a query that filters on it runs in memory (OQ75 residue).
- [ ] Render a text literal with a column piece (Reference §5, "Text made from other values") as `||` or `CONCAT` over the columns, with the dialect's cast of a non-text piece to its `canonical` text, through the mechanism D1 to D4 settle (OQ80 residue).
