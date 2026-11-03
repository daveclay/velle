# OQ73 — Velle UI: a frontend built from the model description, with the UX declared apart from the UI

**Status:** opened 2026-10-29 at Dave's request, from his scratch note `velle_ui.md` (IntelliJ scratches) and the wiki's `Velle-UI.md` notes. Not designed. This file is the place the design is talked through; the decisions in §6 are for Dave to answer inline. §8, added 2026-10-30, is a hypothetical sketch of the UX language, written ahead of D8 so that D2, D5, D6, and D9 to D15 can be judged against something concrete. Dave redirected it 2026-11-02: the sketch starts from a shape, and each member says how it appears in each of the shape's views, instead of each view listing its members.
**In plain terms:** every Velle system already serves one page that builds itself from the model: the console lists every shape, shows every record, and puts up a form for every act, with no code written per spec. Dave wants that idea grown into a frontend framework in its own right. Three ideas drive it. First, a frontend engineer should describe what the user does (choose one customer, fill in a line item, see the overdue invoices) in a language that says nothing about checkboxes or dropdowns, and bind the look separately. Second, the rules the spec already states (a line item's quantity is positive, an amount is `price * quantity`) should reach the form from the spec, never typed again in the frontend. Third, the framework should work against a system that is not Velle at all: Velle generates the configuration it reads, and anyone else writes that configuration by hand.
**See:** `wiki/Serving-a-system.md` ("The console", `GET /api`) · `apis/json/src/main/kotlin/velle/api/Describe.kt` (the model description today) · `apis/spring/src/main/resources/velle/console.html` (the one self-building page) · `wiki/Act-API.md` (a refused act's rejection as data) and `Wire.rejection` · `wiki/Velle-UI.md` (the earlier notes; they fold into this file and the wiki page goes when this settles) · Reference §21 (`never`, `requires`) · Reference §7 (derived properties) · OQ66 (extension functions), OQ65 (extension types), OQ69 (a function's store side: the same question has a client side) · the Serving residue items on the console and on an OpenAPI description.

---

## 1. The goal

**The philosophy** (Dave, 2026-11-02). Velle UI, like Velle, separates problem domains so that a different language and different tools can solve each one more effectively and efficiently. Each domain is then expressed in a syntax that is concise and intuitive for that domain alone. The core is a description of the UX: the models, their relationships, and the general ways humans interact with the data. Those ways are list views, forms that submit acts, detail views, and the different ways one model is presented in different contexts. Separately, the UI describes how each UX component is given a UI component: a select list, a checkbox, a radio group, a combo box. Lastly, styling is applied to the UI components and describes the unified visual design: where things sit on the screen, the colours, the fonts, the containers. §8 is the UX domain's language; the UI and styling domains have no sketch yet.

Dave's note, condensed into what the framework must do:

- Given a valid configuration and nothing else, put up a usable frontend: a list view for every readable shape, a detail view for every record, a form for every act. The type of a property decides how it is shown and entered. An engineer declares only where the default is wrong.
- Describe the user's experience apart from the components that render it. "Choose one of these records" is one idea whether it ends up a dropdown, a radio group, or a search box. A validation is stated once, against the field, never against a DOM element.
- Bind components to that description once. There is one "choose one" component and one styling of it. The page is the only place where a hundred instances exist.
- Take validation from the backend instead of duplicating it. The system that knows the rules publishes them, and the frontend reads them.
- Keep the frontend a static artifact served from a CDN, deployed apart from the backend, and tolerant of the two landing at different times.
- Stay independent of Velle. Velle produces the configuration; a team on a REST or GraphQL API of their own writes it by hand.

The four configurations the note names, in the order they are combined to build a frontend:

1. the model: shapes, properties, relationships, acts (Velle generates this one);
2. the UX: which views exist, what each shows, what the user can do from it, in UX vocabulary ("choose one", "choose many", "fill in", "see a list filtered by");
3. the UI: one component bound to each UX word, custom components injected here;
4. the styling, applied across all UI components.

## 2. What Velle serves today that the frontend would read

The console is the proof that configuration 1 is enough for a default frontend. `GET /api` (`Describe.describe()`) answers:

- every act, its fields with type and kind (`value`, `values`, `reference`, `references`), whether a field is optional, whether a reference `mayBeNew`, the parts a committer creates inline and their fields, and for a by-value reference the operand shapes a new value may take;
- every readable shape and refinement, its members with kind (`stored`, `derived`, `inverse`, `captured`, `actor`, `timestamp`), a refinement's bases, a shape's membership flags (`isPaidInvoice`), and who may see a record or a member when the spec has authorization;
- the schedules, and whether the system has authorization.

A refused act answers `{"accepted": false, "reason", "rejection"}` where the rejection names the `never` or `requires` declaration, the act as submitted, the violating and witnessing records, and a rendered message. Queries take a predicate string in the spec's own grammar. So the frontend already has: a model to build from, a query language to filter with, and refusals as data to show. The console uses all three with 229 lines and nothing per spec.

## 3. What Velle does not serve yet, and the frontend needs

Each of these is a change to `Describe`, and each is a decision about what the description promises to anyone writing it by hand.

- **The door checks as data.** `never (LineItem where quantity <= 0)` and `SignUp requires email matches "..."` are not in the description. A form cannot pre-check them. The compiler can tell which prohibitions read only the act's own fields and which read the books; see D3.
- **Derived expressions on acts.** `amount: Money = price * quantity` is a member of kind `derived` with no expression, so a form cannot show the amount as the user types.
- **A refinement's predicate.** `PaidInvoice = Invoice where total > 0 and balance <= 0` is served as a name with bases. A list of paid invoices can query by name, so this one may not be needed; it is here because a hand-written configuration for a non-Velle API has no server-side refinement to query.
- **Visibility predicates.** A `visible to` clause is served as `{"shape", "where": true}`. The frontend can hide what it knows the viewer cannot see; it cannot evaluate a `where`.
- **Extension types' entry and display.** `Money` is served as the name `Money`. How it is typed in and shown (an input mask, a format) is the type library's knowledge and has no place in `VelleType` today (`Velle-UI.md`'s first note).
- **Extension functions' client side.** A `never` that calls a registered function cannot be pre-checked unless the function also exists in the browser. This is OQ69's question with the store replaced by the client.
- **A version.** The description carries nothing a frontend built against one version can compare with the server it meets.

## 4. The UX vocabulary Velle's model implies

The default views need no declaration because the model fixes the UX word for every member and field. Listed so the vocabulary is drawn from the model rather than invented:

| in the model | the UX word | the default UI |
|---|---|---|
| a readable shape or refinement | a list: filter (`where`), order, page | a table with a filter box and sortable headers |
| a record | a detail: its members | a page of labelled values |
| an act | a form, submitted as one act | a form with a submit and the refusal shown against a field |
| a `value` field by scalar type | fill in a text, a number, a date, a yes/no | the input the type calls for |
| a `reference` field | choose one from the target shape | a search box over a query of that shape |
| a `reference` field that `mayBeNew` | choose one or describe a new one | the search box with a "new" branch opening the part's form |
| a `references` field | choose many | a multi-select over a query |
| a part | fill in a nested record, repeated for `many` | a nested form, with add and remove rows |
| a by-value reference with `newValue` operands | pick which kind, then fill it in | a kind selector, then the operand's form |
| a `derived` member | see a value you cannot edit | read-only, live when the expression is published (§3) |
| an `inverse` member | see the records that point here | an embedded list |
| a refinement flag `isX` | see a status | a badge |
| a schedule | run a sweep now | a button, development only |
| a refusal | see why and where | the rejection's message on the field the `never` names |

The note's "choosing one or more records to associate to a given record" is the `reference` and `references` rows. "Validations without DOM elements" is the refusal row plus D3.

**Lists are declared in UX words too, not only form fields.** The table above gives a list one row because the default needs no more. A declared list says more, all of it without naming a component:

| the list says | drawn from the model | the default UI |
|---|---|---|
| which columns, in what order | any member, or a path through a reference (`customer.name`, `invoice.customer.email`), or a derived value | a table column |
| what the user may do to one row | an act whose reference field takes the row's record (`CorrectEmail` on a `Customer` row), or opening the record's detail | a per-row action menu, or the row as a link |
| what the user may do to the whole list | an act with no field on a row (`SignUp` on the customer list); an act over the user's selection when its field is `references` to the row's shape | a toolbar action; checkboxes and a toolbar action when there is a selection |
| how it is filtered | a fixed predicate the view carries, in the spec's grammar (`status = "overdue"`), and the members the user may filter on, each a predicate the frontend builds | a filter per filterable column |
| how it is searched | the text members a free-text search runs over, as `contains` across them | one search box |
| how it is ordered | the members the user may order by, and the default order | sortable headers |
| how it is paged | the page size; the cursor is the read API's | next and previous |

Three things the model fixes and the view does not say: an act that takes `one Customer` is a row action on customer lists and nowhere else; a selection action over rows is one act per selected record unless the act's field is `references`, because Velle has no bulk commit; and a filter is a predicate in the spec's grammar on every transport, so the view never writes a second query language. A shape's list is shown in several places: the customers list on its own page, the same list as the embedded `invoices` member on a customer's detail, a third time as the search box behind a `reference` field. A shape with lists that need different columns declares a list per need, and each field says which of the lists it is in (§8.1). This answers the `Velle-UI.md` note that a list has different columns in different contexts.

A detail view is declared the same way: which members, in what grouping, which acts the user may commit from it (every act with a `reference` field to the shape, by default), and which inverse members are shown as embedded lists.

**Labels.** Every shape, act, and member is shown under a label, and no declaration is required for any of them. The default label is the name humanized by a library made for it: `largestPayment` becomes "Largest payment", `SignUp` becomes "Sign up", `PaidInvoice` becomes "Paid invoice", `isPaidInvoice` becomes "Paid invoice" as a badge. The UX configuration overrides a label where the default is wrong (`Receipt.sentOn` as "Emailed on"), one line per override, never one per name. A hand-written description for a non-Velle API gets the same defaults from its own names. Plural labels for lists come from the same library ("Line items"). Whether a label may be given in the spec itself, beside the declaration, is a Velle question and is not asked here; the UX configuration is the first place for it.

## 5. Findings against Velle itself

Kept per the stress-pass stance: the frontend is a use case and these are gaps it found.

- An extension type has no entry or display side (`VelleType` says how a value is stored and compared, never how it is typed in). Alice's type library is the place that knows `Money` is two decimals with a currency sign.
- An extension function has no client side. The capability interfaces say what a function accepts; nothing says how a browser would compute it. Pure functions could be published as expression bodies when they are written in Velle's own expression grammar; Kotlin bodies cannot.
- The rejection data does not name the field a refusal is about. `never (LineItem where quantity <= 0)` refused names the declaration and the violating record; the frontend has to parse the predicate to find `quantity`. The compiler knows the fields a prohibition reads and could publish them.
- `Describe` is undocumented as a contract. It is read by the console and the GraphQL schema builder and nothing else, so its shape has never had to be stable.

## 6. Decisions for Dave

Each leads with the one-sentence decision I would take, then the cost. Answer inline.

### D1. The contract between Velle and the frontend is the model description, a documented JSON document that Velle generates and anyone may write by hand.

The frontend compiles against the description, never against a spec, so it has no Velle dependency. Cost: the description becomes a public interface with a version and a documented schema; every field in §3 is added to it under that discipline; the Serving residue item about an OpenAPI document folds in here, because a description that is a contract should be published in a form client generators already read, or be documented as its own format with a reason.

### D2. The UX layer is a declarative language of its own, with a compiler that checks it against the model description.

A view that names a member that is not there, or asks to choose from a shape a viewer may not read, is rejected at build time, the way a spec is. The default views need no declaration at all; the language says only where the default is wrong. Cost: a second grammar, parser, and set of checks. The alternative is a JSON configuration with a schema, which is cheaper to start and worse to read; the language can begin as JSON and gain a surface syntax later, but then two spellings of one thing exist.

### D3. The model description carries every validation and computed value whose inputs are all on the act, as expressions in the UX language's own grammar (D9), and the frontend ships one evaluator for that grammar; Velle's generator translates its `never`, `requires`, and derived expressions into it.

The Velle compiler classifies each `never` and `requires` on an act: one that reads only the act's own fields and constants is translated and published, and the form pre-checks it; one that reads the books stays the server's, and the form shows its refusal. The same evaluator computes a published computed value as the user types, and evaluates `where:` filters client-side over loaded data. The server remains the authority: a pre-check is a courtesy and the 422 is the truth. Cost: a translation from Velle's predicate grammar into the language's, which cannot express every predicate (§8.8 says what stays opaque), an evaluator in TypeScript, and the extension-function gap in §5 for any expression that calls one.

### D4. The default frontend replaces the console.

The console is the default UX with the default UI and no styling. Once Velle UI puts up the same views from the same description, the starter serves the built frontend at the console's path and the 229-line page goes. Cost: the starter grows a dependency on a built static artifact, or serves it from a jar resource built by the frontend's own build; the developer-only pieces (the actor header box, the tick buttons) become UX the default frontend offers only when the description says ticks are exposed.

### D5. One UI component per UX word, registered by that word; a custom UX word declared in the UX language must have a UI binding or the build fails.

Custom components are injected at the UI layer by naming the UX word they render. Styling is a set of tokens the UI components read, the fourth configuration. Cost: the UX vocabulary is a closed list the framework owns (§4 plus what the language adds), and growing it is a framework change.

### D6. The frontend keeps one store keyed by reference, acts are the actions, refusals are data in the store, and the description is fetched at load and compared with the one the frontend was built against.

A mismatch is shown, not hidden: a frontend built against an older description than the server's still runs, with the members it knows. Cost: the description needs a version or a hash (§3), and a rule for what a newer server may do to an older frontend (add members and acts freely; never rename or remove without a frontend release).

### D7. The sub-project lives in this repository under `ui/` as a TypeScript package with its own build, and each example's app serves its built assets in development.

TypeScript because the UI layer is React and the audience is frontend engineers; Kotlin/JS would put the audience's own layer in a language they do not write. Cost: a second toolchain in the repository, wired into Gradle for the starter's resource.

### D8. The first slice is the billing example with no UX declaration at all: extend the description (§3), write the evaluator, put up the default views, and only then design the UX language against what the default could not say.

The second data point is the pharmacy, which has authorization and ninety days of seeded data. Cost: the UX language is designed last, so its first draft waits on two examples; the gain is that every word in it is drawn from a view the default got wrong.

The four decisions below were added 2026-10-30 with the sketch in §8 and are judged against it. D13 and D14 were added 2026-11-02 when the sketch was turned shape-first at Dave's direction; the inversion itself is decided and is not asked here. D15 was added the same day when Dave asked whether a view could say which way its default runs. §9 and D16 to D19, also 2026-11-02, work the three domains (UX, UI, styling) through one example. Folding acts into the shapes they are about, as one more context on each field's line, was tried the same evening and reverted at Dave's direction: acts belong apart from models, so `act` stays a top-level declaration with its own field lines, and a shape's field lines cover its lists and detail only. Later the same day Dave cut the language loose from Velle: §8 now speaks only of shapes, acts, fields, collections, and roles, which any system has, in a spelling and an expression grammar of its own, and §8.8 holds the whole mapping from a Velle spec; D3, D9, and D11 were reworded to match.

### D9. The UX language has a spelling and a small expression grammar of its own, owing nothing to Velle's; its nouns are shape, act, field, collection, and role, which any system has; a `where:` is evaluated by the frontend over what it loads, and passed through as the query to a backend that accepts the grammar.

The language's problem is the UX of any system, so its syntax is designed for a frontend engineer against a model description, and Velle is one producer of that description (§8.8). Dave's direction, 2026-11-02: shape and act stay because they are relevant abstractions on any system; nothing else of Velle's does. Cost: a Velle author reads a filter in a second grammar, close to the spec's but not it, and the translation in D3 is a piece of work the first draft did not have.

### D10. A declared view replaces its default clause by clause, and a field's view lines replace the default membership of each view they name, so an omitted clause keeps the default.

A declaration says only what is wrong with the default, which is the goal in §1. A list or detail that no field names shows every field; one that any field names shows exactly the fields that name it. Cost: a reader cannot see a whole view from its declaration without knowing the defaults, so the compiler should be able to print the resolved view, and a field's view lines are spread over the shape rather than gathered in one place, so the printed view is the only place the whole list is read in one piece.

### D11. A workspace is named by a role the model description lists and offers by default every act the description allows that role; a description with no roles has one workspace with no name.

The pharmacy's three workspaces in §8.4 are one line each. Velle fills the roles list from the shapes its acts are declared `by` and their refinements (§8.8). Cost: a check on the actor that Velle states as `requires` (§8.6) reaches the workspace only as a published validation, through D3.

### D12. D3 widens to a third class: a validation or computed value that reads the act's fields and fields of records the form has already chosen is pre-checked once those records are loaded.

`amountPaid < fill.amountDue` is checkable the moment a fill is chosen. Cost: the form loads a chosen record's fields, which it does anyway to show the choice; a check over a collection of a chosen record (`count(prescription.fills where ...)`) needs the collection loaded and stays the server's when it is not.

### D13. A shape's `title:` names the fields that stand for a record wherever it appears inside another view, and the default title is the shape's first text field; a shape with none that appears in another view must declare one.

A customer is their name in the overdue list's `customer` column, in the choice behind `BillCustomer`, and in the heading of their detail. The description cannot supply this: a model has no notion of which field names a record, and `Prescription` has no single one, so the pharmacy says `order.patient.name, drug.name`. Cost: the default is a guess that is right for `Customer` and wrong for `Fill`, which has no text field and would be rejected until it says `title: prescription.drug.name, fillNumber`, and a shape whose title is a path through a reference costs a read per row unless the backend returns it with the record.

### D14. A form always shows every field of its act, and an act's field line says only how the field is entered; `hidden` is the one way to take a field off the screen, and a form cannot say `named fields`.

Naming a view on a shape's field puts the field in it, and a list or detail with no field naming it shows every field (D10). A form cannot follow that rule, because an act with an unmentioned required field could not be submitted. Cost: an act's field lines and a shape's field lines read alike and mean different things, and a reader has to know which. The alternative, a form that drops unmentioned fields and the compiler rejecting a dropped field that is required, keeps one rule and makes every form that configures one field name all of them.

### D15. A view may say which way its default runs, `all fields` or `named fields`, and a field line may say `hidden` in any view; a view that says neither follows D10's rule.

Under `all fields` every field is in the view, a field line configures without narrowing, and `hidden` takes a field out. Under `named fields` only the fields whose lines name the view are in it, which is what D10 does on its own once any field names a view. The two words make the rule visible on the view instead of inferred from the field lines, and they let a detail that shows everything say only its groups: `Invoice`'s detail in §8.3 drops from eleven field lines to six, and the fields no line mentions come first under no heading. Cost: three spellings of one idea, since a view with no clause still flips by D10's rule; dropping the flip and requiring `named fields` on every narrowed list would put the clause on nine of the eleven lists in the two files, so the flip stays as the default. `hidden` means two things: on an act's field, sent but not shown; in a list or detail, not shown at all.

### D16. The contract between the UX domain and the UI domain is the resolved UX tree, a closed vocabulary of UX words each with one interface, and neither domain sees the other's file.

The UX compiler emits the tree; the UI compiler reads it (§9.2, §9.6). Cost: the vocabulary is the framework's to grow, and a UX idea with no word cannot be rendered until the word exists, which is D5's cost restated for the whole stack.

### D17. A UI binding is a selector over UX nodes, by word, enclosing word, and a condition on a measure, and the most specific match wins.

`choose one when options <= 5 -> RadioGroup` is the whole decision of when a choice is a radio group, made once (§9.3). Cost: specificity rules, which stylesheets have taught everyone to distrust; the sketch keeps them to three axes and no inheritance.

### D18. Styling targets components and their declared parts, states, and shown values, never HTML, and layout is styling.

A component may change its markup without breaking the styling, and `cell money: align right` styles every money cell in the frontend (§9.4). Cost: each component declares its parts, which is a contract to keep, and the styling language is a second CSS that compiles to the first.

### D19. Data-dependent appearance, such as "overdue" shown in red, is a styling rule bound to a value the UX exposes, and the UX language gets no word for severity until a view needs it.

Cost: a value's meaning is stated where its colour is, so two projects that both want overdue red write the rule twice; a severity word in the UX would state it once and let styling bind the word.

## 7. Out of scope until the above settles

- Native and mobile rendering; the UX layer is written to allow it and nothing is built for it.
- GraphQL as the frontend's transport. REST with the predicate string is enough for the default views; a detail view that walks many references may want GraphQL later.
- Rendering the frontend on the server. Dave's note rules it out: the backend serves data, the CDN serves the frontend.
- Login. The actor header box is the development stand-in, as the Serving residue item says.

## 8. A sketch of the UX language

Written 2026-10-30, ahead of D8 and hypothetical: nothing here is built, and the two files below are not checked in. Rewritten 2026-11-02 twice at Dave's direction. First, the sketch was turned around to start from a shape: a shape declares the views it has, and each of its fields says, once, how it appears in each of them, instead of each view listing its fields. Second, the language was cut loose from Velle. Its problem is the UX of any system that has records, fields, and things a user can do, and it is written against a model description that any backend can produce. It keeps two of Velle's nouns, shape and act, because any system has shapes of data and acts a user performs, and it borrows nothing else: no spelling, no casing rule, no predicate grammar, and none of Velle's other nouns. What Velle contributes, and how its spec becomes a model description, is in §8.8 and nowhere else in this section. The sketch exists so that D2, D5, and D6 can be judged against something concrete, and so that the views the default gets wrong are found before the language is designed for real. The two example systems are billing and the pharmacy, because their model descriptions are the ones Velle can generate today; the language would read the same against a hand-written description of a REST API.

### 8.1 What the language is written against, and what a declaration is

The model description gives the language these things, and the language names nothing else:

- **shapes**, each with a key and **fields**. A field is a text, a number, a date, a date and time, a yes/no, a reference to one record of a shape, a collection of records of a shape, or a computed value with its expression in the language's own expression grammar (§8.2). A yes/no field that marks a status, such as `isUsableLot`, is a field like any other.
- **collections**: named subsets of a shape that the backend serves, such as `Invoice.ActionableOverdue`. A shape's own records are its unnamed collection.
- **acts**: what a user can do, each with typed **fields**, the role allowed to do it, and its **validations** as expressions over the fields.
- **roles**: the kinds of user the system knows, and which roles may read each shape.

A UX file is a set of declarations, checked against the model description. There are four kinds:

- `workspace`: who the frontend is for, named by a role. It holds pages.
- `page`: a place the user goes. It shows lists and names the acts the user can start from it.
- `shape`: everything about how records of one shape are shown. It holds the shape's views, its title, and one line per field.
- `act`: how one act's form is filled in, one line per field.

A shape's views are its lists and its detail. Every readable shape has a default list and a detail without declaring them; every act has a form. A shape declares a view only to say something about the whole view: a named list, a list of a collection, a filter, the order, the page size, the acts the user can start from it. The fields a view shows are not listed in the view. Each field says which views it is in and how, on its own line, and the view shows the fields that name it in the order they are declared. A list or detail that no field names shows every field, which is the default. A view may say which way its default runs: `all fields` puts every field in, and a field line then adds to how it appears or says `hidden` to take it out; `named fields` puts in only the fields whose lines name the view, which is also what happens when the view says nothing and any field names it (D15). A field line that names no view, such as one that only sets a label, leaves membership to the default. A form always has every field of its act, because the act needs them; a field line says only how it is entered (D14).

A field line may be a path through a reference (`customer.name`), which puts a field of a related record into this shape's views. A shape's `title` names the fields that stand for a record wherever it appears inside another view: as a reference column, as a choice in a form, as the heading of its detail (D13). Labels sit on the line of the thing they label.

No declaration names a component, a position, a colour, or a size. Those are the UI and styling configurations, bound to the UX words by name (D5).

**Spelling.** Comments start with `#`. A name is letters, digits, and underscores in any case, spelled exactly as the model description spells it; a path is names joined by dots. Text is in double quotes. A declaration is a keyword, a name, and a block in braces. Inside a block, each clause is on its own line and is a keyword ending in a colon followed by its items, separated by commas; a view name on a field line is followed by a colon only when words follow it. Inside a field's braces, each view is on its own line.

**Expressions.** A `where:` filter, a `default`, and a computed field use one small expression grammar that belongs to the language (§8.2). It has paths, literals, comparison, `and`, `or`, `not`, `is empty`, arithmetic, `count` and `sum` over a collection, and three names: `viewer` is the signed-in user, `today` and `now` are the clock. Inside an act, the act's fields are in scope. The frontend ships one evaluator for this grammar and uses it for filters over loaded data, for defaults and computed values as the user shapes, and for validations the model description publishes (D3). A backend that accepts the grammar receives a `where:` as the query; one that does not gets the collection, and the frontend filters what it loads (D9).

### 8.2 The grammar

```
ux            := (workspace | page | shape | act)*

workspace     := "workspace" Name "{" page* "}"            # Name: a role the model description lists
page          := "page" Name "{" pageClause* "}"
pageClause    := "show:" viewRef ("," viewRef)*
              | "acts:" Name ("," Name)*                   # acts the user can start from the page
viewRef       := ShapeName ("." Name)?                     # a shape's default list, or a list declared on it,
                                                           # or a collection of it from the model description

shape         := "shape" ShapeName "{" shapeClause* "}"
shapeClause   := "label:" Text ("," Text)?                 # singular, plural
              | "title:" path ("," path)*
              | list | detail | field

list          := "list" Name? ("of" Name)? "{" listClause* "}"
                                                           # no Name: the shape's default list
                                                           # "of": a collection of the shape, from the model description
listClause    := fieldsClause
              | "where:" expr                              # a filter the frontend applies, or the backend when it can
              | "order by:" path ("descending")?
              | "page size:" Number
              | "row actions:" rowAction ("," rowAction)*
              | "selection actions:" Name ("," Name)*
              | "list actions:" Name ("," Name)*
rowAction     := Name ("with" "{" binding ("," binding)* "}")? | "open"
                                                           # open: the record's detail
binding       := name ":" path                             # a field, filled from a path on the row
detail        := "detail" "{" detailClause* "}"
detailClause  := fieldsClause
              | "acts:" Name ("," Name)*
fieldsClause  := "all fields"                              # every field is in; `hidden` takes one out
              | "named fields"                             # only fields whose lines name the view

field         := "field" path ("{" fieldLine* "}")?
fieldLine     := "label:" Text
              | viewName (":" fieldWord+)?                 # one per line; naming a view puts the field in it
viewName      := "list" | Name | "detail"                  # Name: a list declared on this shape
fieldWord     := "hidden"
              | "filterable" | "searchable"                # in a list
              | "group" Text                               # in a detail: the heading it is shown under
              | "as" viewRef                               # in a detail: a collection field shown as this list

act           := "act" Name "{" actClause* "}"
actClause     := "label:" Text
              | "field" name (":" actFieldWord+)?
actFieldWord  := "paragraph" | "secret"                    # how a text is typed
              | "choose one from" viewRef ("where" expr)?
              | "choose many from" viewRef ("where" expr)?
              | "one of" Text ("," Text)*
              | "read only"
              | "default" expr
              | "hidden"                                   # sent, not shown
              | "generated"                                # the frontend makes a unique value

expr          := or
or            := and ("or" and)*
and           := not ("and" not)*
not           := "not" not | comparison
comparison    := sum (("=" | "!=" | "<" | "<=" | ">" | ">=") sum)?
              | sum "is" "not"? "empty"
sum           := product (("+" | "-") product)*
product       := unit (("*" | "/") unit)*
unit          := path | Text | Number | "true" | "false"
              | "viewer" | "today" | "now"
              | "count" "(" path ")" | "sum" "(" path ")"
              | "(" expr ")"

path          := name ("." name)*                          # fields, read through references;
                                                           # in an act, its fields
Name, name    := (Letter | "_") (Letter | Digit | "_")*    # as the model description spells it
```

### 8.3 Billing

The billing model description has no roles, so there is no workspace: pages stand at the top level. The file is hypothetical and would be `examples/billing/ux/billing.ux`.

```
# A billing clerk's frontend. Everything not said here is the default.

page Home {
    show: Invoice.Overdue, Customer
}

shape Customer {
    title: name
    list {
        order by: name
        row actions: BillCustomer, CorrectEmail, open
        list actions: SignUp
    }
    detail {
        acts: BillCustomer, CorrectEmail
    }

    field name {
        list: searchable
        detail: group "Customer"
    }
    field email {
        list: searchable
        detail: group "Customer"
    }
    field signedUpOn {
        detail: group "Customer"
    }
    field largestPayment {
        list
        detail: group "Payments"
    }
    field invoices {
        detail
    }
}

shape Invoice {
    title: reference
    list {
        order by: due descending
    }
    list Overdue of ActionableOverdue {
        order by: due
        row actions: SubmitPayment, ArchiveRequest, open
    }
    detail {
        all fields
        acts: LineItem, SubmitPayment, Issuance, ChangeDueDate, ArchiveRequest, UnarchiveRequest
    }

    field reference {
        list
        Overdue
    }
    field customer {
        Overdue
    }
    field due {
        list
        Overdue
    }
    field status {
        list: filterable
    }
    field total {
        list
        detail: group "Money"
    }
    field balance {
        list
        Overdue
        detail: group "Money"
    }
}

shape LineItem {
    field description { list }
    field price { list }
    field quantity { list }
    field amount { list }
}

shape Payment {
    field receivedOn { list }
    field amount { list }
}

act LineItem {
    field description: paragraph
    field quantity: default 1
    field amount: read only
}

act SubmitPayment {
    label: "Record a payment"
    field invoice: choose one from Invoice where balance > 0
    field amount: default invoice.balance
}

act Issuance {
    label: "Issue the invoice"
}

shape Receipt {
    field sentOn { label: "Emailed on" }
}
```

What each declaration does:

- `page Home` shows two lists: the list `Overdue` declared on `Invoice`, and the default list of `Customer`. Without it the default frontend has one page per readable shape.
- `shape Customer` declares its default list and its detail, and then its five fields. `title: name` says a customer reads as their name wherever a customer appears in another view: the `customer` column of the overdue list, the choice in `BillCustomer`'s form. The default list says only its order and its acts; which columns it has is said by the fields. `row acts` names two acts and `open`; both acts take one customer, so the default would offer them too. `list actions: SignUp` is the default as well, because `SignUp` takes no customer. The line is here to show where a whole-list act is declared.
- `field name` has two lines: `list: searchable` puts it in the default list as a column the one search box runs over, and `detail: group "Customer"` puts it in the detail under the heading "Customer". `largestPayment` has a bare `list` line, a column with no more said about it, and is a member of a second group. `signedUpOn` is in the detail only, so the default list has three columns. `invoices`, with its one `detail` line, shows the collection field as an embedded list.
- `shape Invoice` has two lists. The default list is never shown on a page of its own: the customer detail shows it as `invoices`, and the payment form chooses from it. `Overdue` is a list of the collection `ActionableOverdue`, which the backend serves as overdue and not archived. The filter lives in the backend, so the list carries none.
- `Invoice`'s detail says `all fields` (D15), so every field is on the page without a line: `issuedOn`, the two status fields, and the collection fields `lineItems` and `payments`, which show as embedded lists. The columns of those lists are declared where they belong: `LineItem` and `Payment` each say which fields are in their default list. Only `total` and `balance` have a `detail` line, and it says their group; the fields no line mentions come first under no heading, in the model's order, and "Money" follows. `Customer`'s detail says nothing and so follows D10's rule: all five fields name it, so the two spellings put up the same kind of page.
- The two lists of `Invoice` follow D10's rule too. `reference` and `due` are in both; `customer` is in the overdue list only, where it shows as the customer's name by `Customer`'s title; `status` has `list: filterable`, which lets the user filter the default list on a computed field. The column order of each list is the order of these lines.
- `shape LineItem` and `shape Payment` narrow their default lists for the invoice detail that embeds them. A field in one view fits on one line.
- `act LineItem` says how three of the five fields are entered. `description` is a paragraph, which the model cannot say about a text. `quantity` starts at 1 and is still editable. `amount` is read only; the model description gives it as computed from `price * quantity`, and the form shows it live as the user shapes (D3). `invoice` has no line and is in the form anyway; when the form is started from an invoice's detail, it is already chosen.
- `act SubmitPayment`: `invoice` is chosen from the default list of `Invoice`, which the field refers to, narrowed to invoices with a balance. `amount` starts at the chosen invoice's balance. The filter and the default read fields of a record the form has chosen (see D12).
- The two `label:` lines override two humanized act names. `shape Receipt` overrides the label of one field and says nothing else about it. Every other name keeps its default: `largestPayment` is "Largest payment", `ActionableOverdue` is "Actionable overdue".

### 8.4 Pharmacy

The pharmacy model description has three roles and says that every shape is readable by staff. The default frontend would put up seventy lists, one per readable shape. What a technician needs is one queue. The file is hypothetical and would be `examples/pharmacy/ux/pharmacy.ux`.

```
# One workspace per role. Each page shows the queue that person works.

workspace Technician {
    page Fills { show: RefillTask.ToFill }
    page Stock { show: Drug.LowStock, StockLot }
}

workspace Pharmacist {
    page Verify { show: Fill.AwaitingVerification }
    page Counselling { show: Fill.CounsellingDue }
    page Counter { show: Fill.ReadyForPickup }
    page Alerts { show: PharmacistAlert.Open }
}

workspace Administrator {
    page Staff { show: Staff }
    page Claims { show: Claim.PendingClaim }
}

# How a record reads when it stands in another shape's view.
shape Patient { title: name }
shape Staff { title: name }
shape Drug { title: name, strength }
shape Prescription { title: order.patient.name, drug.name }

shape RefillTask {
    list ToFill of OpenRefillTask {
        order by: requestedOn
        row actions: FillPrescription with { prescription: prescription }
    }

    field prescription { ToFill }
    field requestedOn { ToFill }
}

shape StockLot {
    label: "Lot", "Lots"
    title: lotNumber
    list {
        row actions: CountLot
    }

    field drug { list: filterable searchable }
    field lotNumber { list: searchable }
    field expiresOn { list }
    field onHand { list }
    field isUsableLot { list }
}

shape Fill {
    list AwaitingVerification {
        where: verifications is empty and not (filledBy = viewer and lot.drug.isControlled)
        order by: filledAt
        row actions: VerifyFill
    }

    field prescription { AwaitingVerification }
    field lot.drug { AwaitingVerification }
    field quantity { AwaitingVerification }
    field filledBy { AwaitingVerification }
    field filledAt { AwaitingVerification }
}

shape PharmacistAlert {
    list Open {
        where: not prescription.isCleared
        row actions: ConflictOverride with { prescription: prescription }
    }

    field prescription { Open }
    field kind { Open }
    field raisedOn { Open }
}

act FillPrescription {
    field requestKey: hidden generated
    field prescription: choose one from Prescription.Fillable
    field lot: choose one from StockLot.UsableLot where drug = prescription.drug
    field quantity: default prescription.quantity
    field fillNumber: default prescription.fillsRecorded + 1
}

act VerifyFill {
    field outcome: one of "approved", "rejected"
    field note: paragraph
}

act PickUp {
    field amountPaid: default fill.amountDue
}
```

What each declaration does:

- `workspace Technician` is named by a role the model description lists. Every act the description allows that role is offered in it by default (D11).
- `page Stock { show: Drug.LowStock, StockLot }` shows a collection of `Drug` with the drug shape's default columns, next to a shape whose default list is declared below. `Fill.CounsellingDue` and `Fill.ReadyForPickup` are collections too; `Fill.AwaitingVerification` is a list declared in this file. A name after the dot is one or the other, and a declared list may take a collection's name.
- The four one-line shapes say only how their records read elsewhere. `shape Prescription { title: order.patient.name, drug.name }` means that the `prescription` column of a refill task, a fill, or an alert shows the patient and the drug, and that the choice in `FillPrescription`'s form reads the same way. The first draft of this sketch wrote `prescription.order.patient.name, prescription.drug.name` as two columns in three lists.
- `shape RefillTask` declares the technician's queue. `FillPrescription` takes a prescription, not a refill task, so §4's default rule finds no row act. `with { prescription: prescription }` says which field the row fills and from which path. Two fields are in the queue; `fillsAtRequest` and `requestKey` are not.
- `shape StockLot` says it is called a lot, and that a lot reads as its lot number. `drug { list: filterable searchable }` lets the user filter on a reference, which is a choose-one over drugs, and runs the search box over the drug's title. `isUsableLot` shows a yes/no status field as a column.
- `shape Fill` declares a list with a `where:` in the language's own grammar. `viewer` is the signed-in user, so a pharmacist does not see their own controlled fills in the queue; `lot.drug.isControlled` is a yes/no field of the drug. The backend refuses `VerifyFill` on those anyway; the list hides what would be refused. `lot.drug` is a path field: the column shows the drug, by `Drug`'s title, rather than the lot.
- `PharmacistAlert.Open` filters on a yes/no field through a reference, `not prescription.isCleared`, and fills `ConflictOverride` from a path on the row.
- `act FillPrescription`: `requestKey` is a text the user never sees, which the frontend makes unique. `prescription` is chosen from the collection `Prescription.Fillable`. `lot` is chosen from usable lots of the chosen prescription's drug, and the act's own field `prescription` is in scope of the filter. `quantity` and `fillNumber` start from fields of the chosen prescription, including the computed `fillsRecorded`.
- `act VerifyFill`: `outcome` is a choice between two words. The model description carries the same as a validation, `outcome = "approved" or outcome = "rejected"`. Once the frontend reads validations (D3), it can infer the choice and the line becomes the override for when it cannot.
- `act PickUp` starts the amount at the chosen fill's `amountDue`. The backend refuses a smaller amount, and the model description publishes that validation.
- `filledBy` needs no label: "Filled by" is the default, and the column shows the staff member's name by `Staff`'s title.

A page in `workspace Technician` that showed `Claim.PendingClaim` would be rejected: the model description says `Claim` is readable by the pharmacist and administrator roles only.

### 8.5 What the compiler checks

The UX compiler reads the model description and the UX file and nothing else.

- It rejects a `shape`, `act`, `show:`, `acts:`, `of`, or `from` naming a shape, act, collection, or list the description or the file does not have.
- It rejects a field line whose path is not a field of the shape, or whose segments but the last are not references.
- It rejects a field line naming a list the shape does not declare, and a `detail` line on a shape no role may read.
- It rejects a field word in the wrong view: `filterable` or `searchable` outside a list, `group` or `as` outside a detail.
- It rejects `as X` on a field that is not a collection, or when X is not a list of the field's shape.
- It rejects a `title:` path that is not a text, a number, or a date, and a shape with two `title:` clauses.
- It rejects a row action when the act has no field that refers to the row's shape and no `with` says which field the row fills.
- It rejects a `with` binding when the field is not on the act, or when the path does not lead to the shape the field refers to.
- It rejects a selection action when no field of the act refers to the row's shape.
- It rejects a list action when the act has a field that refers to the row's shape, because that act belongs on a row.
- It rejects an expression that does not parse in the language's grammar, or that names anything but the shape's fields, `viewer`, `today`, `now`, and, in an act, the act's fields.
- It rejects a comparison between values of different kinds, and `is empty` on anything but a collection.
- It rejects `choose one from X` or `choose many from X` when X is not a list or collection of the shape the field refers to.
- It rejects an act's field word that disagrees with the field's kind: `choose` on a value, `paragraph` on a reference, `read only` on anything but a computed field, `choose many` on a single reference, `one of` on anything but a text.
- It rejects a `default` expression whose kind is not the field's.
- It rejects a `workspace` naming a role the description does not list.
- It rejects a page in a workspace that shows a shape the workspace's role may not read.
- It rejects two declarations of one shape, act, page, or list, and two lines for one field.
- It rejects `named fields` on a form, and a view with both `all fields` and `named fields`.
- It rejects `hidden` on a field in a view with `named fields`, because leaving the line out does the same.
- It warns when a published validation (D3) uses a function the frontend's evaluator does not have; that validation is left to the backend.
- It warns when a `label:` restates the humanized default.
- It warns when a field names a view and no other field does, because that view then has one column.
- It warns when a list is ordered by a field it does not show.

### 8.6 What the sketch found

Each of these is a view the default could not put up, or a gap in what a model description has to carry. They are the reason D8 wanted the language designed last, found early.

- **How a record reads in another view had no home.** With views at the top level, the first draft wrote `customer.name` in one list and `prescription.order.patient.name` in three, because nothing said what a customer or a prescription looks like as a single cell. Starting from the shape makes `title:` an obvious clause of it, and the description cannot supply it: a model has no notion of which field names a record. See D13.
- **The columns of an embedded list belong to the embedded shape.** The first draft configured `LineItem`'s columns from inside `Invoice`. Now `LineItem` says which of its fields are in its list, and the invoice detail embeds that list. The same list is then what a `LineItem` search box shows.
- **A form is not a list.** Naming a view on a field puts it in; a list or detail with no field naming it shows everything. A form cannot work that way, because the act needs every field. The rule in §8.1 treats a form as always full, D14 asks whether that is the right reading, and D15 gives every other view the choice.
- **A row action filled from a path.** §4's rule, an act with a field that refers to the row's shape, covers `CorrectEmail` on a customer and not `FillPrescription` on a refill task. The `with { field: path }` binding is needed, and the model fixes nothing about it.
- **A choice filtered by another field.** `lot: choose one from StockLot.UsableLot where drug = prescription.drug` needs the act's fields in scope of a filter. The model description carries the same constraint as a validation, `lot.drug = prescription.drug`. A published validation of the form `a.path = b.path` is a filter on the choice, and the UX line is only the override. That is a second thing D3 buys beyond pre-checking.
- **D3's classification is too narrow.** `quantity <= lot.onHand`, `amountPaid < fill.amountDue`, and whether the chosen prescription is in `Fillable` read fields of records the form has chosen, which are loaded by the time the user shapes. A third class between "only the fields" and "the backend's data" is needed; see D12.
- **An enumeration is a validation today.** `outcome = "approved" or outcome = "rejected"` is the only way the pharmacy's description says that a text takes one of two words. The frontend can infer a choice from the published validation. Whether the model description should say it as a kind of field is a question for whoever writes the description, Velle included.
- **A request key is a convention.** `requestKey` on `FillPrescription` and `RequestRefill` is a key the client makes up, and the description cannot mark it. Each form says `hidden generated` by hand.
- **Who may act is partly in validations.** The description gives each act's role. The pharmacy also says, as a validation on `SetReorderPoint`, that the viewer must be an administrator or a pharmacist, and a workspace can only hide the act from a technician once validations are published (D3).
- **The humanizer is enough for labels.** The two files need four labels between them, and one of those only because the pharmacy says "lot" for a stock lot. Labels are a small layer, as §4 said.
- **A filter needs a grammar the language owns.** The first draft wrote filters in Velle's predicate grammar, which no other backend evaluates. The language now has its own, small enough to evaluate in the browser, and a backend that accepts it gets the filter as the query (D9). What the pharmacy wrote as `lot.drug is Controlled`, a membership test, became `lot.drug.isControlled`, a yes/no field, because a model description has fields and collections and no third thing.

### 8.7 What the language does not say

Deliberately absent, so that the line between UX and UI holds:

- which component renders a word, where it sits, its colour, size, or icon;
- anything that depends on the viewport or the device;
- the URL of a page, which the framework derives from the workspace, page, shape, and list names;
- help text under a field, a confirmation before an act, and an empty-list message, each of which is UX and none of which the sketch has a word for yet;
- how a `where:` reaches a backend that does not accept the grammar, beyond the frontend filtering what it loads (D9).

### 8.8 Joining Velle later

Nothing in §8.1 to §8.7 knows Velle exists. Velle's part is to generate the model description from a spec, and this table is the whole of the mapping. Each row is a change to `Describe` (§3) or a reading of what it already serves (§2).

| in a Velle spec | in the model description |
|---|---|
| a `shape` and its properties | a shape and its fields, with `stored`, `derived`, `inverse`, `captured`, `actor`, and `timestamp` folded into the field kinds in §8.1 |
| an `act` | an act with the act's fields as fields; a kept act is also a shape |
| a refinement `X = Shape where ...` | a collection `Shape.X`, and a yes/no field `isX` on the shape |
| a `many` or inverse member | a collection field |
| a derived property `= expr` | a computed field, its expression translated into the language's grammar when the translation exists, else opaque |
| `never` and `requires` on an act | validations, translated the same way; the compiler says which read only the act's fields (D3) |
| `by Shape` on an act, and the shapes acts are declared `by` | the act's role, and the roles list |
| `visible to` | which roles may read a shape |
| `actor` in a predicate | `viewer` |
| `is X` in a predicate | `isX`, a yes/no field |
| a refused act's rejection (§2) | the frontend shows it against the field the validation names, once the rejection names one (§5) |

What does not map, and so is a Velle question rather than a UX one: an extension type's entry and display (§5), an extension function's client side (§5), and a predicate that the translation cannot express, which stays the backend's to evaluate.

## 9. The three domains against one example

Written 2026-11-02 at Dave's request. Workspaces and roles are set aside here. The question is what a frontend looks like when the UX, the UI, and the styling are each written in a language made for that domain, instead of in React components that fix all three at once. The example is the billing clerk's screen for overdue invoices and the form that records a payment, because §8.3 already describes them in the UX language. Everything below is hypothetical; the three languages share one spelling (§8.1) so an engineer who reads one reads the others.

### 9.1 The coupling today

The overdue list as it would be written now, in outline:

```tsx
export function OverdueInvoices() {
  const [status, setStatus] = useState("all")
  const rows = useQuery(`/api/invoices?where=status = "overdue" and not archived`)
  return (
    <div className="p-6">
      <div className="flex gap-2 mb-4">
        <select value={status} onChange={e => setStatus(e.target.value)} className="border rounded px-2">
          <option>all</option><option>open</option><option>overdue</option>
        </select>
      </div>
      <table className="min-w-full text-sm">
        <thead><tr><th>Reference</th><th>Customer</th><th>Due</th><th>Balance</th><th/></tr></thead>
        <tbody>{rows.map(r =>
          <tr key={r.id} className="hover:bg-gray-50">
            <td><Link to={`/invoices/${r.id}`}>{r.reference}</Link></td>
            <td>{r.customer.name}</td>
            <td>{format(r.due, "d MMM yyyy")}</td>
            <td className={r.balance > 0 ? "text-red-600" : ""}>{money(r.balance)}</td>
            <td><RowMenu items={[["Record a payment", () => openPayment(r)], ["Archive", () => archive(r)]]}/></td>
          </tr>)}
        </tbody>
      </table>
    </div>
  )
}
```

Three domains are fixed in one place and cannot be changed apart. That the list exists, which columns it has, which acts a row offers, and that a customer reads as their name is UX. That the filter is a `select`, the actions are a menu, and the reference is a link is UI. The padding, the hover colour, the red balance, and the date format are styling. The same thirty lines are then written again for the customers list, the lots list, and every other list, each time re-deciding all three.

### 9.2 The UX domain

The UX file says what the screen is and nothing about how it looks (§8.3):

```
shape Invoice {
    title: reference
    list Overdue of ActionableOverdue {
        order by: due
        row actions: SubmitPayment, ArchiveRequest, open
    }
    field reference { Overdue }
    field customer  { Overdue }
    field due       { Overdue }
    field balance   { Overdue }
}

act SubmitPayment {
    label: "Record a payment"
    field invoice: choose one from Invoice where balance > 0
    field amount: default invoice.balance
}
```

The UX compiler resolves this against the model description into a tree of **UX nodes**. Each node is a UX word, the data it stands for, and its place in the tree. This tree is the contract between the UX domain and the UI domain: the UI never sees the UX file, and the UX never sees a component.

```
list Invoice.Overdue
  collection: Invoice.ActionableOverdue, order: due
  column reference   text, title of Invoice, opens detail
  column customer    reference to Customer, shown by Customer.title (name)
  column due         date
  column balance     money
  row actions        SubmitPayment (invoice := row), ArchiveRequest (invoice := row), open
form SubmitPayment
  field invoice      choose one, from Invoice where balance > 0, required
  field amount       money, default invoice.balance, required
  validations        amount > 0 (published), amount <= invoice.balance (server)
  submit             "Record a payment"
  refusal            shown against the field the rejection names
```

The vocabulary of UX words is closed and small. The words that name structure: `page`, `list`, `detail`, `form`, `group`, `column`, `row actions`, `list actions`, `selection actions`, `filter`, `search`, `submit`, `refusal`. The words that name a field in a view: `text`, `paragraph`, `secret`, `number`, `money`, `date`, `date and time`, `yes/no`, `status`, `choose one`, `choose many`, `one of`, `read only`, `reference`, `embedded list`, `title`. Everything the frontend puts on screen is one of these, with the data from the model description attached.

### 9.3 The UI domain

The UI file binds UX words to components. A binding is a selector over UX nodes and the component that renders the nodes it matches, in the way a stylesheet's rule is a selector over elements and the declarations that apply. The file is hypothetical and would be `examples/billing/ui/billing.ui`.

```
# Components are implementations registered under a name. Each implements the
# interface of the UX word it is bound to (§9.5), and nothing else about it is known here.
component Table         from "@velle-ui/react/Table"
component CompactTable  from "@velle-ui/react/CompactTable"
component Link          from "@velle-ui/react/Link"
component RowMenu       from "@velle-ui/react/RowMenu"
component Button        from "@velle-ui/react/Button"
component Toolbar       from "@velle-ui/react/Toolbar"
component Select        from "@velle-ui/react/Select"
component RadioGroup    from "@velle-ui/react/RadioGroup"
component ComboBox      from "@velle-ui/react/ComboBox"
component CheckboxGroup from "@velle-ui/react/CheckboxGroup"
component MultiSelect   from "@velle-ui/react/MultiSelect"
component Checkbox      from "@velle-ui/react/Checkbox"
component TextInput     from "@velle-ui/react/TextInput"
component TextArea      from "@velle-ui/react/TextArea"
component NumberInput   from "@velle-ui/react/NumberInput"
component DatePicker    from "@velle-ui/react/DatePicker"
component MoneyInput    from "@acme/finance-ui/MoneyInput"      # the Money type library's own
component Badge         from "@velle-ui/react/Badge"
component Section       from "@velle-ui/react/Section"
component Form          from "@velle-ui/react/Form"
component FieldError    from "@velle-ui/react/FieldError"
component Banner        from "@velle-ui/react/Banner"

# Structure
list                               -> Table
list in detail                     -> CompactTable
list in choose one                 -> ComboBox                 # the search box behind a choice
title in list                      -> Link                     # opens the record
row actions                        -> RowMenu
row actions when count = 1         -> Button
list actions, selection actions    -> Toolbar
group                              -> Section
form                               -> Form
submit                             -> Button { primary: true }
refusal on field                   -> FieldError
refusal                            -> Banner                   # a refusal that names no field

# Fields, by UX word; the most specific selector wins
text                               -> TextInput
paragraph                          -> TextArea { rows: 4 }
secret                             -> TextInput { secret: true }
number                             -> NumberInput
money                              -> MoneyInput
date                               -> DatePicker
yes/no                             -> Checkbox
status                             -> Badge
choose one                         -> Select
choose one when options <= 5       -> RadioGroup
choose one when options > 50       -> ComboBox
choose many                        -> MultiSelect
choose many when options <= 8      -> CheckboxGroup
one of                             -> RadioGroup
one of when options > 5            -> Select
read only                          -> Text
```

The grammar:

```
ui         := (componentDecl | binding)*
componentDecl := "component" Name "from" Text
binding    := selector ("," selector)* "->" Name ("{" option* "}")?
selector   := uxWord ("in" uxWord)* ("when" condition)?
             # "in": the nearest enclosing node with that word
condition  := measure ("=" | "<" | "<=" | ">" | ">=") Number
            | "required" | "optional" | "empty"
measure    := "options" | "count" | "columns" | "rows"
option     := name ":" (Text | Number | "true" | "false")  # a component's own settings
```

What the file does for the example. The overdue list matches `list` and is a `Table`. Its `reference` column is the title and matches `title in list`, so it is a `Link`. The three row actions match `row actions` and are a `RowMenu`; a list with one row action would get a `Button` instead. In the payment form, `invoice` is a `choose one` over every invoice with a balance, more than fifty of them, so it is a `ComboBox`, which searches by the invoice's title; `amount` is `money`, so it is the `MoneyInput` the Money type library ships, which knows about two decimals and a currency sign (§5). A refusal of the amount comes back naming the field and is a `FieldError` under it. No line in the file names an invoice, a payment, or any other shape: the bindings are for the whole frontend, and the billing file is twenty lines because the default bindings are the ones the framework ships, and a project writes only the lines that differ, such as the `MoneyInput` line.

**Options are UI, not styling.** `rows: 4` on a text area and `primary: true` on the submit button change how the component behaves or what it is, not how it looks. Where a thing sits and what colour it is are styling (§9.4).

**A custom component** is a React component that implements one UX word's interface (§9.5) and a `component` line that registers it. The UI compiler checks the implementation against the interface. Nothing else is needed, and the UX file does not change.

### 9.4 The styling domain

The styling file is applied to UI components. It has tokens, the layout of each structural component, and the look of each component and its parts. It targets components and their named parts, never HTML, so a component can change its markup without breaking the styling. The file is hypothetical and would be `examples/billing/style/billing.style`.

```
tokens {
    color.surface:     #ffffff
    color.surface.alt: #f7f7f8
    color.text:        #1b1b1f
    color.text.muted:  #6b6b76
    color.primary:     #2b6cb0
    color.danger:      #c0392b
    color.border:      #e3e3e8
    font.body:         "Inter" 14px / 20px
    font.label:        "Inter" 12px / 16px 600
    space.1: 4px  space.2: 8px  space.3: 12px  space.4: 16px  space.6: 24px
    radius: 6px
}

Page {
    layout: header, main
    main: padding space.6, max-width 1200px
}

Table {
    density: comfortable                  # row height; compact for CompactTable
    header: font.label, color color.text.muted, border-bottom color.border
    row: border-bottom color.border
    row hover: background color.surface.alt
    cell: padding space.2 space.3
    cell money: align right
    cell date: format "d MMM yyyy"
}

CompactTable extends Table {
    density: compact
    header: hidden
}

Form {
    layout: one column, labels above, gap space.4
    actions: align right, gap space.2
}

Section {
    title: font.label, color color.text.muted, margin-bottom space.2
    gap: space.3
}

Button {
    padding space.2 space.4, radius radius, font font.body
    primary: background color.primary, color color.surface
    danger:  background color.danger,  color color.surface
}

Badge {
    padding space.1 space.2, radius 999px, font font.label
    value "overdue": background color.danger, color color.surface
    value "paid":    background color.surface.alt, color color.text.muted
}

FieldError { color: color.danger, font: font.label }
```

The grammar:

```
style      := tokens? componentStyle*
tokens     := "tokens" "{" (tokenName ":" value)* "}"
componentStyle := Name ("extends" Name)? "{" rule* "}"
rule       := part? state? ":" property ("," property)*
             # no part: the component's root
part       := name                          # a part the component declares: header, row, cell, title, actions
state      := "hover" | "focus" | "disabled" | "selected"
            | name Text                     # a cell or badge by the UX word or value it shows: `cell money`, `value "overdue"`
property   := "layout" layoutSpec | "density" ("compact" | "comfortable")
            | name value+                   # padding, color, background, font, border-*, radius, align, gap, format, hidden
value      := tokenName | Number unit? | Text | color
```

What the file does for the example. The overdue list's `Table` gets comfortable rows, muted uppercase-free headers, and a hover tint. Its `balance` cell matches `cell money` and is right-aligned; its `due` cell matches `cell date` and takes the date format. The `status` badge, when its value is "overdue", is red. The payment form is one column with labels above and its button on the right. Nothing here names a shape or a field: `cell money` styles every money cell in every table, and `value "overdue"` styles every status badge showing that word.

**Data-dependent look is styling, not UX.** That "overdue" is red is a decision about appearance, bound to a value the UX exposes. If the UX wanted to say that overdue is bad, independent of colour, it would need a word for severity, which it does not have (§8.7). The sketch leaves it in styling.

**Layout is styling.** Where the header is, how wide the main column is, whether a form is one column or two, where the buttons sit: all of it is in the styling file under `layout:`, because none of it changes what the user can do. The UI component declares the parts a layout may place; the styling places them.

### 9.5 The handful of components

The UX vocabulary in §9.2 is closed, so the components a frontend needs are countable. Each UX word has one interface, which is what the UX node gives the component and what the component gives back:

| UX word | the component receives | the component returns |
|---|---|---|
| `list` | columns (word, label, sortable), a page of rows, the order, the filters and search in effect, the row, list, and selection acts, the selection | a new order, filter, search, or page; a chosen row; a chosen act with its rows |
| `column` | the row's value, its UX word, the label | nothing; it renders |
| `title` | the record, its title text, whether it opens a detail | an open |
| `row actions`, `list actions`, `selection actions` | the acts (label, enabled) | a chosen act |
| `detail`, `group`, `embedded list` | the record, the fields and their values, the groups, the acts | a chosen act |
| `form`, `submit`, `refusal` | the fields, their values and errors, the published validations, the submit label, the refusal | a value change, a submit |
| `text`, `paragraph`, `secret`, `number`, `money`, `date`, `date and time`, `yes/no` | label, value, required, error, read only | a new value |
| `choose one`, `choose many` | label, a query for the options (with the chosen shape's title), the value, required, error | a new value, a search text, a new page |
| `one of` | label, the words, the value, required, error | a new value |
| `status` | the value, the label | nothing; it renders |

That is about twenty interfaces and, with the alternatives in §9.3 (`RadioGroup` and `Select` for one word, `Table` and `CompactTable` for another), about twenty-five components for the whole default frontend. A type library adds one per type it wants to show its own way (`MoneyInput`). A project adds one for each UX word it wants rendered differently, and that is all a project's React code is.

### 9.6 The tools

Each domain has a compiler and the compilers run in order, each reading the one before.

- **The UX compiler** reads the model description and the UX file, checks them against each other (§8.5), and emits the resolved UX tree (§9.2), one tree per page, list, detail, and form. It can print a resolved view so the author sees what the defaults filled in (D10).
- **The UI compiler** reads the UX tree and the UI file. It rejects a UX node no binding matches, a binding to a component that does not implement the word's interface (checked in TypeScript against the interface in §9.5), a `when` condition the word has no measure for, and a selector that matches nothing in any tree, as a warning. It emits the component tree: each UX node paired with the component that renders it and its options.
- **The styling compiler** reads the component tree and the styling file. It rejects a token that is not declared, a part the component does not declare, a `value` state on a component that shows no value, and emits CSS: tokens as custom properties, each rule as a selector on the component's name and part, which the components render as data attributes.
- **A gallery** renders every UX word with every bound component and every styling state, from the three files alone, so the UI and styling are seen without a backend.

The React code that remains is the components, each a function of one interface, and nothing per shape, per page, or per list.

### 9.7 What this changes about the earlier decisions

- D5 said one component per UX word, registered by that word. §9.3 widens that to a selector: a word in a context under a condition. The closed list of UX words stays.
- D2's question whether the UX language should begin as JSON applies to all three languages. The UX tree in §9.2 and the component tree in §9.6 are JSON already; the three files are the surface syntax over them.
- §8.7 listed help text, a confirmation before an act, and an empty-list message as UX words the language lacks. §9.5 shows each would be one more row in the interface table and one more binding, so adding them is cheap once the word exists.

The decisions this section raises are D16 to D19 in §6.
