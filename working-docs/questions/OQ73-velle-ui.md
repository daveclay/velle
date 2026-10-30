# OQ73 — Velle UI: a frontend built from the model description, with the UX declared apart from the UI

**Status:** opened 2026-10-29 at Dave's request, from his scratch note `velle_ui.md` (IntelliJ scratches) and the wiki's `Velle-UI.md` notes. Not designed. This file is the place the design is talked through; the decisions in §6 are for Dave to answer inline. §8, added 2026-10-30, is a hypothetical sketch of the UX language, written ahead of D8 so that D2, D5, D6, and D9 to D12 can be judged against something concrete.
**In plain terms:** every Velle system already serves one page that builds itself from the model: the console lists every shape, shows every record, and puts up a form for every act, with no code written per spec. Dave wants that idea grown into a frontend framework in its own right. Three ideas drive it. First, a frontend engineer should describe what the user does (choose one customer, fill in a line item, see the overdue invoices) in a language that says nothing about checkboxes or dropdowns, and bind the look separately. Second, the rules the spec already states (a line item's quantity is positive, an amount is `price * quantity`) should reach the form from the spec, never typed again in the frontend. Third, the framework should work against a system that is not Velle at all: Velle generates the configuration it reads, and anyone else writes that configuration by hand.
**See:** `wiki/Serving-a-system.md` ("The console", `GET /api`) · `apis/json/src/main/kotlin/velle/api/Describe.kt` (the model description today) · `apis/spring/src/main/resources/velle/console.html` (the one self-building page) · `wiki/Act-API.md` (a refused act's rejection as data) and `Wire.rejection` · `wiki/Velle-UI.md` (the earlier notes; they fold into this file and the wiki page goes when this settles) · Reference §21 (`never`, `requires`) · Reference §7 (derived properties) · OQ66 (extension functions), OQ65 (extension types), OQ69 (a function's store side: the same question has a client side) · the Serving residue items on the console and on an OpenAPI description.

---

## 1. The goal

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

Three things the model fixes and the view does not say: an act that takes `one Customer` is a row action on customer lists and nowhere else; a selection action over rows is one act per selected record unless the act's field is `references`, because Velle has no bulk commit; and a filter is a predicate in the spec's grammar on every transport, so the view never writes a second query language. A list declared once is shown in several places with different columns: the customers list on its own page, the same list as the embedded `invoices` member on a customer's detail, a third time as the search box behind a `reference` field. The declaration names the list; the place it is shown picks columns and actions from it. This answers the `Velle-UI.md` note that a list has different columns in different contexts.

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

### D3. Velle publishes every door check and derived expression whose inputs are all on the act, as expressions in Velle's own grammar, and the frontend ships one evaluator for that grammar.

The compiler classifies each `never` and `requires` on an act: one that reads only the act's own fields and constants is published and pre-checked in the form; one that reads the books stays the server's, and the form shows its refusal. The same evaluator computes a published derived expression as the user types, and evaluates `where` filters client-side where the data is already loaded. The server remains the authority: a pre-check is a courtesy and the 422 is the truth. Cost: an evaluator of the predicate and value grammar in TypeScript, kept in step with the Kotlin one, and the extension-function gap in §5 for any published expression that calls one.

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

The four decisions below were added 2026-10-30 with the sketch in §8 and are judged against it.

### D9. The UX language is a surface syntax of its own, with Velle's lexical conventions, the spec's predicate grammar unchanged, and only two names added to scope: `actor` for the viewer and, inside a form, the form's fields.

A frontend engineer who has the spec open reads a filter in the same words. Cost: a hand-written description for a non-Velle API has no server that evaluates the predicate grammar, so a filter on such a list is either evaluated by the frontend's own evaluator (D3) over the pages it has loaded, or translated by the configuration into that API's query parameters, and the sketch does not say which.

### D10. A declared list, detail, or form replaces its default clause by clause, so an omitted clause keeps the default.

A declaration says only what is wrong with the default, which is the goal in §1. Cost: a reader cannot see a whole view from its declaration without knowing the defaults, so the compiler should be able to print the resolved view.

### D11. A workspace is named by an actor shape and offers by default every act declared `by` that shape or by a shape it refines; a spec with no actors has one workspace with no name.

The pharmacy's three workspaces in §8.4 fall out of the spec's `by` clauses, and each page is one line. Cost: a hand-written description for a non-Velle API has to name actor shapes with no `by` clause behind them, so the description grows an `actors` list, and a check on the actor written as `requires` (§8.6) reaches the workspace only through D3.

### D12. D3 widens to a third class: a check or expression that reads the act's fields and members of records the form has already chosen is pre-checked once those records are loaded.

`amountPaid < fill.amountDue` is checkable the moment a fill is chosen. Cost: the form loads a chosen record's members, which it does anyway to show the choice; a check over a collection of a chosen record (`count(prescription.fills where ...)`) needs the collection loaded and stays the server's when it is not.

## 7. Out of scope until the above settles

- Native and mobile rendering; the UX layer is written to allow it and nothing is built for it.
- GraphQL as the frontend's transport. REST with the predicate string is enough for the default views; a detail view that walks many references may want GraphQL later.
- Rendering the frontend on the server. Dave's note rules it out: the backend serves data, the CDN serves the frontend.
- Login. The actor header box is the development stand-in, as the Serving residue item says.

## 8. A sketch of the UX language

Written 2026-10-30, ahead of D8 and hypothetical: nothing here is built, and the two files below are not checked in. The sketch exists so that D2, D5, and D6 can be judged against something concrete, and so that the views the default gets wrong are found before the language is designed for real. Every word in it is drawn from §4 or from a view in `examples/billing/billing.velle` or `examples/pharmacy/pharmacy.velle` that the default cannot put up.

### 8.1 What a declaration is

A UX file is a set of declarations, checked against the model description and never against a spec. There are six kinds:

- `workspace`: who the frontend is for, named by an actor shape. It holds pages.
- `page`: a place the user goes. It shows lists and offers forms.
- `list`: records of one shape: the columns, what the user may filter and search on, the order, and what the user may do to a row, to a selection, or to the whole list.
- `detail`: one record: the members shown, how they are grouped, the collection members shown as lists, and the acts offered.
- `form`: one act, with a UX word per field where the model's default is wrong.
- `label`: the words a shape, act, or member is shown under.

Every readable shape, every act, and every field has a default declaration drawn from the model (§4). A declaration names what differs and nothing else. An omitted clause keeps its default, so a `list` without `columns` shows every member. No declaration names a component, a position, a colour, or a size. Those are the UI and styling configurations, bound to the UX words by name (D5).

The lexical conventions are Velle's (`wiki/grammar.md`). Uppercase names are shapes, acts, lists, pages, and workspaces. Lowercase names are members and fields. Comments start with `--`. Declarations are line-oriented. A predicate or value expression is written in the spec's own grammar (Reference §10) with two names added to scope: `actor` is the viewer, and inside a `form` the form's fields are in scope.

### 8.2 The grammar

```
ux            := declaration*
declaration   := workspaceDecl | pageDecl | listDecl | detailDecl | formDecl | labelDecl

workspaceDecl := "workspace" ShapeName "{" pageDecl* "}"
                 -- ShapeName: a shape some act is declared `by`, or a refinement of one
pageDecl      := "page" PageName "{" pageClause* "}"
pageClause    := "show" shown ("," shown)*          -- a declared list, or a readable shape's default list
              | "offer" ActName ("," ActName)*      -- a form the user opens from the page
shown         := ListName | ShapeName

listDecl      := "list" ListName "of" ShapeName ("where" predicate)? "{" listClause* "}"
listClause    := "columns" path ("," path)*
              | "filter" path ("," path)*           -- what the user may filter on
              | "search" path ("," path)*           -- the text members one search box runs over
              | "order" path ("descending")?        -- the default order
              | "page" IntegerLiteral               -- the page size
              | "on a row:" rowAction ("," rowAction)*
              | "on a selection:" ActName ("," ActName)*
              | "on the list:" ActName ("," ActName)*
rowAction     := ActName ("from" "{" binding ("," binding)* "}")? | "detail"
binding       := Identifier ":" path                -- an act field, filled from a path on the row

detailDecl    := "detail" ShapeName "{" detailClause* "}"
detailClause  := "show" path ("," path)*
              | "group" TextLiteral "{" path ("," path)* "}"
              | "show" Identifier ("as" ListName)? ("columns" path ("," path)*)?
                                                    -- an inverse or `many` member, shown as a list
              | "offer" ActName ("," ActName)*

formDecl      := "form" ActName "{" fieldClause* "}"
fieldClause   := Identifier ":" fieldUx ("," fieldUx)*
fieldUx       := "fill in" ("a paragraph" | "a secret")?
              | "choose one" ("from" shown)? ("where" predicate)?
              | "choose many" ("from" shown)? ("where" predicate)?
              | "choose one of" TextLiteral ("," TextLiteral)*
              | "see"
              | "initially" (valueExpr | GeneratorName)
              | "hidden"

labelDecl     := "label" (ShapeName | ActName | ShapeName "." Identifier) TextLiteral ("plural" TextLiteral)?

path          := Identifier ("." Identifier)*       -- members, read through references
predicate, valueExpr := Reference §10, with `actor` in scope and, inside a form, the form's fields
PageName, ListName := UppercaseLetter (Letter | Digit)*
```

### 8.3 Billing

The billing spec has no actors, so there is no workspace: pages stand at the top level. The file is hypothetical and would be `examples/billing/ux/billing.ux`.

```
-- A billing clerk's frontend. Everything not said here is the default.

page Home {
    show Overdue, Customers
}

list Customers of Customer {
    columns name, email, largestPayment
    search name, email
    order name
    on a row: BillCustomer, CorrectEmail, detail
    on the list: SignUp
}

list Overdue of ActionableOverdue {
    columns reference, customer.name, due, balance
    order due
    on a row: SubmitPayment, ArchiveRequest, detail
}

list Invoices of Invoice {
    columns reference, due, total, balance, status
    filter status
    order due descending
}

detail Customer {
    group "Customer" { name, email, signedUpOn }
    group "Payments" { largestPayment }
    show invoices as Invoices
    offer BillCustomer, CorrectEmail
}

detail Invoice {
    group "Invoice" { reference, customer, due, issuedOn, status, isIssuedInvoice, isArchivedInvoice }
    group "Money" { total, balance }
    show lineItems columns description, price, quantity, amount
    show payments columns receivedOn, amount
    offer LineItem, SubmitPayment, Issuance, ChangeDueDate, ArchiveRequest, UnarchiveRequest
}

form LineItem {
    description: fill in a paragraph
    quantity: initially 1
    amount: see
}

form SubmitPayment {
    invoice: choose one from Invoices where balance > 0
    amount: initially invoice.balance
}

label Receipt.sentOn "Emailed on"
label SubmitPayment "Record a payment"
label Issuance "Issue the invoice"
```

What each declaration does:

- `page Home` shows two lists. Without it the default frontend has one page per readable shape, which is the console.
- `list Customers` narrows the columns to three, says which two members the one search box runs over, and orders by name. `on a row` names two acts and `detail`; both acts take `one Customer`, so the default would offer them too. `on the list: SignUp` is the default as well, because `SignUp` has no reference field. The line is here to show where a whole-list act is declared.
- `list Overdue` is a list of the refinement `ActionableOverdue`, which the spec already composes as overdue and not archived. The predicate lives in the spec, so the list carries none. `customer.name` is a path through a reference.
- `list Invoices` is never shown on a page of its own. The customer detail shows it as the `invoices` member, and the payment form searches it. `filter status` lets the user filter on a derived member.
- `detail Customer` groups the members under two headings, shows the inverse member `invoices` with the columns and order the `Invoices` list declares, and offers two acts. An act offered from a detail has its reference field to that shape already chosen.
- `detail Invoice` shows the refinement flags `isIssuedInvoice` and `isArchivedInvoice` among the members. `show lineItems columns ...` shows an inverse member with the default list of `LineItem` narrowed to four columns, with no declared list behind it.
- `form LineItem` says `description` is a paragraph, which the model cannot say about a `text`. `quantity: initially 1` prefills an editable field. `amount: see` is the default for a derived member and is here only to say that the amount is live once D3 publishes `price * quantity`.
- `form SubmitPayment` narrows the invoices the user may choose from to those with a balance, and prefills the amount from the chosen invoice's balance. The predicate and the expression read members of a record the form has chosen (see D12).
- The three `label` lines override three humanized defaults. Every other name keeps its default: `largestPayment` is "Largest payment", `PaidInvoice` is "Paid invoice".

### 8.4 Pharmacy

The pharmacy has four kinds of staff and a spec-wide `visible to Staff` (`examples/pharmacy/pharmacy.velle`, line 56). The default frontend would put up seventy lists, one per readable shape. What a technician needs is one queue. The file is hypothetical and would be `examples/pharmacy/ux/pharmacy.ux`.

```
-- One workspace per kind of staff. Each page shows the queue that person works.

workspace Technician {
    page Fills { show ToFill }
    page Stock { show LowStock, Lots }
}

workspace Pharmacist {
    page Verify { show AwaitingVerification }
    page Counselling { show CounsellingDue }
    page Counter { show ReadyForPickup }
    page Alerts { show OpenAlerts }
}

workspace Administrator {
    page Staff { show Staff }
    page Claims { show PendingClaim }
}

list ToFill of OpenRefillTask {
    columns prescription.order.patient.name, prescription.drug.name, requestedOn
    order requestedOn
    on a row: FillPrescription from { prescription: prescription }
}

list Lots of StockLot {
    columns drug.name, lotNumber, expiresOn, onHand, isUsableLot
    search lotNumber, drug.name
    filter drug
    on a row: CountLot
}

list AwaitingVerification of Fill where verifications is empty
                                  and not (filledBy == actor and lot.drug is Controlled) {
    columns prescription.order.patient.name, lot.drug.name, quantity, filledBy.name, filledAt
    order filledAt
    on a row: VerifyFill
}

list OpenAlerts of PharmacistAlert where prescription is not Cleared {
    columns prescription.order.patient.name, prescription.drug.name, kind, raisedOn
    on a row: ConflictOverride from { prescription: prescription }
}

form FillPrescription {
    requestKey: initially randomUUID, hidden
    prescription: choose one from Fillable
    lot: choose one from UsableLot where drug == prescription.drug
    quantity: initially prescription.quantity
    fillNumber: initially prescription.fillsRecorded + 1
}

form VerifyFill {
    outcome: choose one of "approved", "rejected"
    note: fill in a paragraph
}

form PickUp {
    amountPaid: initially fill.amountDue
}

label StockLot "Lot" plural "Lots"
```

What each declaration does:

- `workspace Technician` is named by a refinement of `Staff`. Every act declared `by Staff` is offered in it by default (D11). `Technician` itself appears in no `by` clause, only in `requires actor to be Technician`, which is why the grammar admits a refinement of an actor shape.
- `page Stock { show LowStock, Lots }` shows a refinement by name, which is its default list, next to a declared list.
- `list ToFill` is the technician's queue. `FillPrescription` takes `one Prescription`, not a refill task, so §4's default rule finds no row action. `from { prescription: prescription }` says which field the row fills and from which path.
- `list Lots` shows the flag `isUsableLot` as a column and lets the user filter on a reference, `drug`, which is a choose-one over drugs.
- `list AwaitingVerification` carries a predicate in the spec's grammar. `actor` is the viewer, so a pharmacist does not see their own controlled fills in the queue. The spec refuses `VerifyFill` on those anyway (line 767); the list hides what would be refused.
- `list OpenAlerts` tests refinement membership in its predicate, `prescription is not Cleared`, and fills `ConflictOverride` from a path on the row.
- `form FillPrescription`: `requestKey` is a text the user never sees, generated by the frontend with Velle's own generator name. `prescription: choose one from Fillable` narrows the choice to a refinement the spec defines. `lot` is chosen from usable lots of the chosen prescription's drug, and the form's own field `prescription` is in scope of the predicate. `quantity` and `fillNumber` are prefilled from members of the chosen prescription, including the derived `fillsRecorded`. The spec's `Fill requires fillNumber == count(prescription.fills where fillNumber <= this.fillNumber)` (line 674) is what the prefill satisfies.
- `form VerifyFill`: `outcome` is a choice between two words. The spec says the same as `VerifyFill requires outcome == "approved" or outcome == "rejected"` (line 768). Once D3 publishes that check, the frontend can infer the choice and the line becomes the override for when it cannot.
- `form PickUp` prefills the amount from the chosen fill's `amountDue`. The spec refuses a smaller amount (line 858).
- `label StockLot` overrides both the singular and the plural. The default would be "Stock lot" and "Stock lots", and the pharmacy says "lot". `filledBy` needs no label: "Filled by" is the default.

A page in `workspace Technician` that showed `PendingClaim` would be rejected: `Claim` is `visible to Pharmacist` and `visible to Administrator` (lines 1002 and 1003), and the description says so.

### 8.5 What the compiler checks

The UX compiler reads the model description and the UX file and nothing else.

- It rejects a `list`, `detail`, `form`, `label`, or `show` naming a shape, act, list, or page the description or the file does not have.
- It rejects a path when a segment is not a member of the shape it is read on, or when any segment but the last is not a reference.
- It rejects a row action when the act has no reference field whose target is the row's shape and no `from` says which field the row fills.
- It rejects a `from` binding when the field is not on the act, or when the path's shape is not the field's target shape or a refinement of it.
- It rejects a selection action when no field of the act takes the row's shape.
- It rejects a whole-list action when the act has a reference field to the row's shape, because that act belongs on a row.
- It rejects a predicate or expression that does not parse in the spec's grammar over the list's shape, or that names anything but that shape's members, `actor`, and, inside a form, the form's fields.
- It rejects `choose one from X` or `choose many from X` when X's shape is not the field's target shape or a refinement of it.
- It rejects a UX word that disagrees with the field's kind: `choose` on a value field, `fill in` on a reference, `see` on a stored field, `choose many` on a `reference`, `choose one of` on anything but a value field.
- It rejects an `initially` expression whose type is not the field's.
- It rejects a `workspace` naming a shape that no act is declared `by` and that refines no such shape.
- It rejects a page in a workspace that shows a shape the workspace's actor shape may not read, per the description's `visibleTo`.
- It rejects two declarations of one list, page, detail, form, or label.
- It warns when a published check (D3) over a form's fields calls a function the frontend has no client side for; that check is left to the server.
- It warns when a `label` restates the humanized default.

### 8.6 What the sketch found

Each of these is a view the default could not put up, or a gap in what Velle publishes. They are the reason D8 wanted the language designed last, found early.

- **A row action filled from a path.** §4's rule, an act whose reference field takes the row's record, covers `CorrectEmail` on a customer and not `FillPrescription` on a refill task. The `from { field: path }` binding is needed, and the model fixes nothing about it.
- **A choice filtered by another field.** `lot: choose one from UsableLot where drug == prescription.drug` needs the form's fields in scope of a predicate. The spec states the same constraint as `FillPrescription requires lot.drug == prescription.drug` (line 667). A published check of the form `field.path == otherField.path` is a filter on the choice, and the UX line is only the override. That is a second thing D3 buys beyond pre-checking.
- **D3's classification is too narrow.** `quantity <= lot.onHand`, `amountPaid < fill.amountDue`, and `prescription is Fillable` read members of records the form has chosen, which are loaded by the time the user types. A third class between "only the act's fields" and "the books" is needed; see D12.
- **An enumeration is a `requires` today.** `outcome == "approved" or outcome == "rejected"` is the only way the pharmacy says that a text takes one of two words. The frontend can infer a choice from the published check. Whether Velle wants to say it as a type is a Velle question outside this file.
- **A request key is a convention.** `requestKey: text` on `FillPrescription` and `RequestRefill` is a key the committer makes up, and the description cannot mark it. Each form says `initially randomUUID, hidden` by hand.
- **Who may commit is partly in door checks.** The description gives each act's actor shape. `SetReorderPoint requires actor to be Administrator or actor to be Pharmacist` (line 237) is a door check on the actor, and a workspace can only hide the act from a technician once D3 publishes it.
- **The humanizer is enough for labels.** The two files need four labels between them, and one of those only because the pharmacy says "lot" for a stock lot. Labels are a small layer, as §4 said.

### 8.7 What the language does not say

Deliberately absent, so that the line between UX and UI holds:

- which component renders a word, where it sits, its colour, size, or icon;
- anything that depends on the viewport or the device;
- the URL of a page, which the framework derives from the workspace, page, and list names;
- help text under a field, a confirmation before an act, and an empty-list message, each of which is UX and none of which the sketch has a word for yet;
- what a hand-written description for a non-Velle API does with a `where` predicate, which no such API evaluates (D9).
