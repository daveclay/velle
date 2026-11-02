# OQ73 — Velle UI: a frontend built from the model description, with the UX declared apart from the UI

**Status:** opened 2026-10-29 at Dave's request, from his scratch note `velle_ui.md` (IntelliJ scratches) and the wiki's `Velle-UI.md` notes. Not designed. This file is the place the design is talked through; the decisions in §6 are for Dave to answer inline. §8, added 2026-10-30, is a hypothetical sketch of the UX language, written ahead of D8 so that D2, D5, D6, and D9 to D14 can be judged against something concrete. Dave redirected it 2026-11-02: the sketch starts from a shape, and each member says how it appears in each of the shape's views, instead of each view listing its members.
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

Three things the model fixes and the view does not say: an act that takes `one Customer` is a row action on customer lists and nowhere else; a selection action over rows is one act per selected record unless the act's field is `references`, because Velle has no bulk commit; and a filter is a predicate in the spec's grammar on every transport, so the view never writes a second query language. A shape's list is shown in several places: the customers list on its own page, the same list as the embedded `invoices` member on a customer's detail, a third time as the search box behind a `reference` field. A shape with lists that need different columns declares a list per need, and each member says which of the lists it is in (§8.1). This answers the `Velle-UI.md` note that a list has different columns in different contexts.

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

The four decisions below were added 2026-10-30 with the sketch in §8 and are judged against it. D13 and D14 were added 2026-11-02 when the sketch was turned shape-first at Dave's direction; the inversion itself is decided and is not asked here.

### D9. The UX language is a surface syntax of its own, with Velle's lexical conventions, the spec's predicate grammar unchanged, and only two names added to scope: `actor` for the viewer and, inside a form, the form's fields.

A frontend engineer who has the spec open reads a filter in the same words. Cost: a hand-written description for a non-Velle API has no server that evaluates the predicate grammar, so a filter on such a list is either evaluated by the frontend's own evaluator (D3) over the pages it has loaded, or translated by the configuration into that API's query parameters, and the sketch does not say which.

### D10. A declared view replaces its default clause by clause, and a member's view lines replace the default membership of each view they name, so an omitted clause keeps the default.

A declaration says only what is wrong with the default, which is the goal in §1. A list or detail that no member names shows every member; one that any member names shows exactly the members that name it. Cost: a reader cannot see a whole view from its declaration without knowing the defaults, so the compiler should be able to print the resolved view, and a member's view lines are spread over the shape rather than gathered in one place, so the printed view is the only place the whole list is read in one piece.

### D11. A workspace is named by an actor shape and offers by default every act declared `by` that shape or by a shape it refines; a spec with no actors has one workspace with no name.

The pharmacy's three workspaces in §8.4 fall out of the spec's `by` clauses, and each page is one line. Cost: a hand-written description for a non-Velle API has to name actor shapes with no `by` clause behind them, so the description grows an `actors` list, and a check on the actor written as `requires` (§8.6) reaches the workspace only through D3.

### D12. D3 widens to a third class: a check or expression that reads the act's fields and members of records the form has already chosen is pre-checked once those records are loaded.

`amountPaid < fill.amountDue` is checkable the moment a fill is chosen. Cost: the form loads a chosen record's members, which it does anyway to show the choice; a check over a collection of a chosen record (`count(prescription.fills where ...)`) needs the collection loaded and stays the server's when it is not.

### D13. A shape's `title` names the members that stand for a record wherever it appears inside another view, and the default title is the shape's first text member; a shape with none that appears in another view must declare one.

A customer is their name in the overdue list's `customer` column, in the choice behind `BillCustomer`, and in the heading of their detail. The description cannot supply this: the model has no notion of which member names a record, and `Prescription` has no single one, so the pharmacy says `order.patient.name, drug.name`. Cost: the default is a guess that is right for `Customer` and wrong for `Fill`, which has no text member and would be rejected until it says `title prescription.drug.name, fillNumber`, and a shape whose title is a path through a reference costs a read per row unless the read API returns it with the record.

### D14. A form always shows every field of its act, and a member's `form` line says only how the field is entered; `hidden` is the one way to take a field off the screen.

Naming a view on a member puts the member in it, and a list or detail with no member naming it shows every member (D10). A form cannot follow that rule, because an act with an unmentioned required field could not be committed. Cost: forms and lists read the same syntax two ways, and a reader has to know which. The alternative, a form that drops unmentioned fields and the compiler rejecting a dropped field that is required, keeps one rule and makes every form that configures one field name all of them.

## 7. Out of scope until the above settles

- Native and mobile rendering; the UX layer is written to allow it and nothing is built for it.
- GraphQL as the frontend's transport. REST with the predicate string is enough for the default views; a detail view that walks many references may want GraphQL later.
- Rendering the frontend on the server. Dave's note rules it out: the backend serves data, the CDN serves the frontend.
- Login. The actor header box is the development stand-in, as the Serving residue item says.

## 8. A sketch of the UX language

Written 2026-10-30, ahead of D8 and hypothetical: nothing here is built, and the two files below are not checked in. Rewritten 2026-11-02 at Dave's direction. The first draft put `list`, `detail`, and `form` at the top level, and each of them named the members it showed, so one member of `Invoice` was typed into the invoices list, the overdue list, the detail, and every form that chose an invoice. The sketch now starts from the shape. A shape declares the views it has, and each of its members says, once, how it appears in each of them. The sketch exists so that D2, D5, and D6 can be judged against something concrete, and so that the views the default gets wrong are found before the language is designed for real. Every word in it is drawn from §4 or from a view in `examples/billing/billing.velle` or `examples/pharmacy/pharmacy.velle` that the default cannot put up.

### 8.1 What a declaration is

A UX file is a set of declarations, checked against the model description and never against a spec. There are three kinds:

- `workspace`: who the frontend is for, named by an actor shape. It holds pages.
- `page`: a place the user goes. It shows lists and names the acts the user can commit from it.
- a shape declaration, headed by the shape's name: everything about how records of one shape are shown. It holds the shape's views, its title, and one line per member.

A shape's views are its lists, its detail, and, when the shape is an act, its form. Every readable shape has a default list and a detail without declaring them; every act has a form. A shape declares a view only to say something about the whole view: a named list, a list of a refinement, a list with a filter, the order, the page size, the acts the user can commit from it. The members a view shows are not listed in the view. Each member says which views it is in and how, on its own line, and the view shows the members that name it in the order they are declared. A form is the exception: it always shows every field of the act, because the act needs them, and a member's `form` line only says how the field is entered. A list or detail that no member names shows every member, which is the default.

A member line may be a path through a reference (`customer.name`), which puts a member of a related record into this shape's views. A shape's `title` names the members that stand for a record wherever it appears inside another view: as a reference column, as a choice in a form, as the heading of its detail. Labels sit on the line of the thing they label.

No declaration names a component, a position, a colour, or a size. Those are the UI and styling configurations, bound to the UX words by name (D5).

The lexical conventions are Velle's (`wiki/grammar.md`). Uppercase names are shapes, acts, lists, pages, and workspaces. Lowercase names are members and fields. Comments start with `--`. Declarations are line-oriented. Inside a member's braces, each view is on its own line, and the UX words after a view's colon follow one another with no separator; each starts with a keyword. A predicate or value expression is written in the spec's own grammar (Reference §10) with two names added to scope: `actor` is the viewer, and inside a `form` line the form's fields are in scope.

### 8.2 The grammar

```
ux            := (workspaceDecl | pageDecl | shapeDecl)*

workspaceDecl := "workspace" ShapeName "{" pageDecl* "}"
                 -- ShapeName: a shape some act is declared `by`, or a refinement of one
pageDecl      := "page" PageName "{" pageClause* "}"
pageClause    := "show" viewRef ("," viewRef)*          -- a list to show
              | "acts" ActName ("," ActName)*           -- the acts the user can commit from the page
viewRef       := ShapeName ("." ListName)?              -- a shape's default list, or one of its named lists

shapeDecl     := ShapeName labelClause? ("{" shapeClause* "}")?
labelClause   := "label" TextLiteral ("plural" TextLiteral)?
shapeClause   := viewDecl | "title" path ("," path)* | memberDecl

viewDecl      := "list" ListName? ("of" ShapeName)? ("where" predicate)? ("{" listClause* "}")?
                 -- no ListName: the shape's default list; "of": a refinement of the shape
              | "detail" "{" detailClause* "}"
listClause    := "order" path ("descending")?            -- the default order
              | "page" IntegerLiteral                   -- the page size
              | "on a row:" rowAction ("," rowAction)*
              | "on a selection:" ActName ("," ActName)*
              | "on the list:" ActName ("," ActName)*
rowAction     := ActName ("from" "{" binding ("," binding)* "}")? | "detail"
binding       := Identifier ":" path                    -- an act field, filled from a path on the row
detailClause  := "acts" ActName ("," ActName)*          -- the acts the user can commit from the record

memberDecl    := path labelClause? ("{" memberView* "}")?
memberView    := viewName (":" ux+)?                    -- one per line; naming a view puts the member in it
viewName      := "list" | ListName | "detail" | "form"
ux            := listUx | detailUx | formUx
listUx        := "filter"                               -- the user may filter on it
              | "search"                                -- the one search box runs over it
detailUx      := "group" TextLiteral                    -- the heading it is shown under
              | "as" viewRef                            -- a collection member shown as this list
formUx        := "fill in" ("a paragraph" | "a secret")?
              | "choose one" ("from" viewRef)? ("where" predicate)?
              | "choose many" ("from" viewRef)? ("where" predicate)?
              | "choose one of" TextLiteral ("or" TextLiteral)*
              | "see"
              | "initially" (valueExpr | GeneratorName)
              | "hidden"

path          := Identifier ("." Identifier)*           -- members, read through references
predicate, valueExpr := Reference §10, with `actor` in scope and, in a form line, the form's fields
PageName, ListName := UppercaseLetter (Letter | Digit)*
```

### 8.3 Billing

The billing spec has no actors, so there is no workspace: pages stand at the top level. The file is hypothetical and would be `examples/billing/ux/billing.ux`.

```
-- A billing clerk's frontend. Everything not said here is the default.

page Home {
    show Invoice.Overdue, Customer
}

Customer {
    title name
    list {
        order name
        on a row: BillCustomer, CorrectEmail, detail
        on the list: SignUp
    }
    detail { acts BillCustomer, CorrectEmail }

    name {
        list: search
        detail: group "Customer"
    }
    email {
        list: search
        detail: group "Customer"
    }
    signedUpOn {
        detail: group "Customer"
    }
    largestPayment {
        list
        detail: group "Payments"
    }
    invoices {
        detail
    }
}

Invoice {
    title reference
    list {
        order due descending
    }
    list Overdue of ActionableOverdue {
        order due
        on a row: SubmitPayment, ArchiveRequest, detail
    }
    detail { acts LineItem, SubmitPayment, Issuance, ChangeDueDate, ArchiveRequest, UnarchiveRequest }

    reference {
        list
        Overdue
        detail: group "Invoice"
    }
    customer {
        Overdue
        detail: group "Invoice"
    }
    due {
        list
        Overdue
        detail: group "Invoice"
    }
    issuedOn {
        detail: group "Invoice"
    }
    status {
        list: filter
        detail: group "Invoice"
    }
    isIssuedInvoice {
        detail: group "Invoice"
    }
    isArchivedInvoice {
        detail: group "Invoice"
    }
    total {
        list
        detail: group "Money"
    }
    balance {
        list
        Overdue
        detail: group "Money"
    }
    lineItems {
        detail
    }
    payments {
        detail
    }
}

LineItem {
    description {
        list
        form: fill in a paragraph
    }
    price {
        list
    }
    quantity {
        list
        form: initially 1
    }
    amount {
        list
        form: see
    }
}

Payment {
    receivedOn {
        list
    }
    amount {
        list
    }
}

SubmitPayment label "Record a payment" {
    invoice {
        form: choose one where balance > 0
    }
    amount {
        form: initially invoice.balance
    }
}

Issuance label "Issue the invoice"

Receipt {
    sentOn label "Emailed on"
}
```

What each declaration does:

- `page Home` shows two lists: the named list `Overdue` of `Invoice`, and the default list of `Customer`. Without it the default frontend has one page per readable shape, which is the console.
- `Customer` declares its default list and its detail, and then its five members. `title name` says a customer reads as their name wherever a customer appears in another view: the `customer` column of the overdue list, the choice in `BillCustomer`'s form. The default list says only its order and its actions; which columns it has is said by the members. `on a row` names two acts and `detail`; both acts take `one Customer`, so the default would offer them too. `on the list: SignUp` is the default as well, because `SignUp` has no reference field. The line is here to show where a whole-list act is declared.
- `name` has two lines: `list: search` puts it in the default list as a column the one search box runs over, and `detail: group "Customer"` puts it in the detail under the heading "Customer". `largestPayment` has a bare `list` line, a column with no more said about it, and is a member of a second group. `signedUpOn` is in the detail only, so the default list has three columns. `invoices`, with its one `detail` line, shows the inverse member as an embedded list: the default list of `Invoice`, with the columns the `Invoice` declaration gives it.
- `Invoice` has two lists. The default list is never shown on a page of its own: the customer detail shows it as `invoices`, and the payment form chooses from it. `Overdue` is a list of the refinement `ActionableOverdue`, which the spec already composes as overdue and not archived. The predicate lives in the spec, so the list carries none.
- Each member of `Invoice` says which of the three views it is in. `reference` is in all three; `issuedOn` and the two refinement flags are in the detail only; `customer` is in the overdue list, where it shows as the customer's name by `Customer`'s title, and in the detail. `status` has `list: filter`, which lets the user filter the default list on a derived member. The column order of each list and the member order of each group is the order of these lines.
- `lineItems` and `payments`, each with one `detail` line, show two more inverse members as embedded lists. Their columns are declared where they belong: `LineItem` and `Payment` each say which members are in their default list.
- `LineItem` is a kept act, so it has a list and a form. `description` is a column in the list and, by `form: fill in a paragraph`, a paragraph in the form, which the model cannot say about a `text`. `quantity` is prefilled with 1 and still editable. `amount` has `form: see`: `see` is the default for a derived member and is here only to say that the amount is live once D3 publishes `price * quantity`. The field `invoice` has no line: it is left out of the list, and the form has it anyway because the act needs it. When the form is opened from an invoice's detail, it is already chosen.
- `Payment` narrows its default list to two columns, for the invoice detail that embeds it.
- `SubmitPayment` is a transient act, so it has a form and nothing else. `invoice`'s line `form: choose one where balance > 0` chooses from the default list of `Invoice`, which the field's type fixes, narrowed to invoices with a balance. `amount` is prefilled from the chosen invoice's balance. The predicate and the expression read members of a record the form has chosen (see D12).
- `SubmitPayment label ...` and `Issuance label ...` override two humanized act names. A shape with only a label needs no braces. `Receipt` overrides the label of one member. Every other name keeps its default: `largestPayment` is "Largest payment", `PaidInvoice` is "Paid invoice".

### 8.4 Pharmacy

The pharmacy has four kinds of staff and a spec-wide `visible to Staff` (`examples/pharmacy/pharmacy.velle`, line 56). The default frontend would put up seventy lists, one per readable shape. What a technician needs is one queue. The file is hypothetical and would be `examples/pharmacy/ux/pharmacy.ux`.

```
-- One workspace per kind of staff. Each page shows the queue that person works.

workspace Technician {
    page Fills { show RefillTask.ToFill }
    page Stock { show LowStock, StockLot }
}

workspace Pharmacist {
    page Verify { show Fill.AwaitingVerification }
    page Counselling { show CounsellingDue }
    page Counter { show ReadyForPickup }
    page Alerts { show PharmacistAlert.Open }
}

workspace Administrator {
    page Staff { show Staff }
    page Claims { show PendingClaim }
}

-- How a record reads when it stands in another shape's view.
Patient      { title name }
Staff        { title name }
Drug         { title name, strength }
Prescription { title order.patient.name, drug.name }

RefillTask {
    list ToFill of OpenRefillTask {
        order requestedOn
        on a row: FillPrescription from { prescription: prescription }
    }

    prescription {
        ToFill
    }
    requestedOn {
        ToFill
    }
}

StockLot label "Lot" plural "Lots" {
    title lotNumber
    list {
        on a row: CountLot
    }

    drug {
        list: filter search
    }
    lotNumber {
        list: search
    }
    expiresOn {
        list
    }
    onHand {
        list
    }
    isUsableLot {
        list
    }
}

Fill {
    list AwaitingVerification where verifications is empty
                              and not (filledBy == actor and lot.drug is Controlled) {
        order filledAt
        on a row: VerifyFill
    }

    prescription {
        AwaitingVerification
    }
    lot.drug {
        AwaitingVerification
    }
    quantity {
        AwaitingVerification
    }
    filledBy {
        AwaitingVerification
    }
    filledAt {
        AwaitingVerification
    }
}

PharmacistAlert {
    list Open where prescription is not Cleared {
        on a row: ConflictOverride from { prescription: prescription }
    }

    prescription {
        Open
    }
    kind {
        Open
    }
    raisedOn {
        Open
    }
}

FillPrescription {
    requestKey {
        form: hidden initially randomUUID
    }
    prescription {
        form: choose one from Fillable
    }
    lot {
        form: choose one from UsableLot where drug == prescription.drug
    }
    quantity {
        form: initially prescription.quantity
    }
    fillNumber {
        form: initially prescription.fillsRecorded + 1
    }
}

VerifyFill {
    outcome {
        form: choose one of "approved" or "rejected"
    }
    note {
        form: fill in a paragraph
    }
}

PickUp {
    amountPaid {
        form: initially fill.amountDue
    }
}
```

What each declaration does:

- `workspace Technician` is named by a refinement of `Staff`. Every act declared `by Staff` is offered in it by default (D11). `Technician` itself appears in no `by` clause, only in `requires actor to be Technician`, which is why the grammar admits a refinement of an actor shape.
- `page Stock { show LowStock, StockLot }` shows a refinement by name, which is its default list, next to a shape whose default list is declared below.
- The four one-line shapes say only how their records read elsewhere. `Prescription { title order.patient.name, drug.name }` means that the `prescription` column of a refill task, a fill, or an alert shows the patient and the drug, and that the choice in `FillPrescription`'s form reads the same way. The first draft of this sketch wrote `prescription.order.patient.name, prescription.drug.name` as two columns in three lists.
- `RefillTask` declares the technician's queue. `FillPrescription` takes `one Prescription`, not a refill task, so §4's default rule finds no row action. `from { prescription: prescription }` says which field the row fills and from which path. Two members are in the queue; `fillsAtRequest` and `requestKey` are not.
- `StockLot` says it is called a lot, and that a lot reads as its lot number. `drug`'s line `list: filter search` lets the user filter on a reference, which is a choose-one over drugs, and runs the search box over the drug's title. `isUsableLot` shows the flag as a column.
- `Fill` declares a list with a predicate in the spec's grammar. `actor` is the viewer, so a pharmacist does not see their own controlled fills in the queue. The spec refuses `VerifyFill` on those anyway (line 767); the list hides what would be refused. `lot.drug` is a path member: the column shows the drug, by `Drug`'s title, rather than the lot.
- `PharmacistAlert.Open` tests refinement membership in its predicate, `prescription is not Cleared`, and fills `ConflictOverride` from a path on the row.
- `FillPrescription` is a transient act: a form only. `requestKey` is a text the user never sees, generated by the frontend with Velle's own generator name. `prescription`'s line `form: choose one from Fillable` narrows the choice to a refinement the spec defines. `lot` is chosen from usable lots of the chosen prescription's drug, and the form's own field `prescription` is in scope of the predicate. `quantity` and `fillNumber` are prefilled from members of the chosen prescription, including the derived `fillsRecorded`. The spec's `Fill requires fillNumber == count(prescription.fills where fillNumber <= this.fillNumber)` (line 674) is what the prefill satisfies.
- `VerifyFill`: `outcome` is a choice between two words. The spec says the same as `VerifyFill requires outcome == "approved" or outcome == "rejected"` (line 768). Once D3 publishes that check, the frontend can infer the choice and the line becomes the override for when it cannot.
- `PickUp` prefills the amount from the chosen fill's `amountDue`. The spec refuses a smaller amount (line 858).
- `filledBy` needs no label: "Filled by" is the default, and the column shows the staff member's name by `Staff`'s title.

A page in `workspace Technician` that showed `PendingClaim` would be rejected: `Claim` is `visible to Pharmacist` and `visible to Administrator` (lines 1002 and 1003), and the description says so.

### 8.5 What the compiler checks

The UX compiler reads the model description and the UX file and nothing else.

- It rejects a shape declaration, `show`, `acts`, `of`, or `from` naming a shape, act, or list the description or the file does not have.
- It rejects a member line whose path is not a member of the shape, or whose segments but the last are not references.
- It rejects a member line naming a list the shape does not declare, `form` on a shape that is not an act, or `detail` on a shape that is not readable.
- It rejects a UX word in the wrong view: `filter` or `search` outside a list, `group` or `as` outside a detail, a form word outside a form.
- It rejects `as X` on a member that is not a collection, or when X is not a list of the member's shape.
- It rejects a `title` path whose type is not a scalar, and a shape with two `title` clauses.
- It rejects a row action when the act has no reference field whose target is the row's shape and no `from` says which field the row fills.
- It rejects a `from` binding when the field is not on the act, or when the path's shape is not the field's target shape or a refinement of it.
- It rejects a selection action when no field of the act takes the row's shape.
- It rejects a whole-list action when the act has a reference field to the row's shape, because that act belongs on a row.
- It rejects a predicate or expression that does not parse in the spec's grammar over the shape, or that names anything but that shape's members, `actor`, and, in a form line, the form's fields.
- It rejects `choose one from X` or `choose many from X` when X's shape is not the field's target shape or a refinement of it.
- It rejects a form word that disagrees with the field's kind: `choose` on a value field, `fill in` on a reference, `see` on a stored field, `choose many` on a `reference`, `choose one of` on anything but a value field.
- It rejects an `initially` expression whose type is not the field's.
- It rejects a `workspace` naming a shape that no act is declared `by` and that refines no such shape.
- It rejects a page in a workspace that shows a shape the workspace's actor shape may not read, per the description's `visibleTo`.
- It rejects two declarations of one shape, page, or list, and two lines for one member.
- It warns when a published check (D3) over a form's fields calls a function the frontend has no client side for; that check is left to the server.
- It warns when a `label` restates the humanized default.
- It warns when a member names a view and no other member does, because that view then has one column.

### 8.6 What the sketch found

Each of these is a view the default could not put up, or a gap in what Velle publishes. They are the reason D8 wanted the language designed last, found early.

- **How a record reads in another view had no home.** With views at the top level, the first draft wrote `customer.name` in one list and `prescription.order.patient.name` in three, because nothing said what a customer or a prescription looks like as a single cell. Starting from the shape makes `title` an obvious clause of it, and the description cannot supply it: the model has no notion of which member names a record. See D13.
- **The columns of an embedded list belong to the embedded shape.** `show lineItems columns description, price, quantity, amount` in the first draft configured `LineItem` from inside `Invoice`. Now `LineItem` says which of its members are in its list, and the invoice detail embeds that list. The same list is then what a `LineItem` search box shows.
- **A form is not a list.** Naming a view on a member puts it in; a list or detail with no member naming it shows everything. A form cannot work that way, because the act needs every field. The rule in §8.1 treats `form` as always full, and D14 asks whether that is the right reading.
- **A row action filled from a path.** §4's rule, an act whose reference field takes the row's record, covers `CorrectEmail` on a customer and not `FillPrescription` on a refill task. The `from { field: path }` binding is needed, and the model fixes nothing about it.
- **A choice filtered by another field.** `lot`'s line `form: choose one from UsableLot where drug == prescription.drug` needs the form's fields in scope of a predicate. The spec states the same constraint as `FillPrescription requires lot.drug == prescription.drug` (line 667). A published check of the form `field.path == otherField.path` is a filter on the choice, and the UX line is only the override. That is a second thing D3 buys beyond pre-checking.
- **D3's classification is too narrow.** `quantity <= lot.onHand`, `amountPaid < fill.amountDue`, and `prescription is Fillable` read members of records the form has chosen, which are loaded by the time the user types. A third class between "only the act's fields" and "the books" is needed; see D12.
- **An enumeration is a `requires` today.** `outcome == "approved" or outcome == "rejected"` is the only way the pharmacy says that a text takes one of two words. The frontend can infer a choice from the published check. Whether Velle wants to say it as a type is a Velle question outside this file.
- **A request key is a convention.** `requestKey: text` on `FillPrescription` and `RequestRefill` is a key the committer makes up, and the description cannot mark it. Each form says `hidden initially randomUUID` by hand.
- **Who may commit is partly in door checks.** The description gives each act's actor shape. `SetReorderPoint requires actor to be Administrator or actor to be Pharmacist` (line 237) is a door check on the actor, and a workspace can only hide the act from a technician once D3 publishes it.
- **The humanizer is enough for labels.** The two files need four labels between them, and one of those only because the pharmacy says "lot" for a stock lot. Labels are a small layer, as §4 said.

### 8.7 What the language does not say

Deliberately absent, so that the line between UX and UI holds:

- which component renders a word, where it sits, its colour, size, or icon;
- anything that depends on the viewport or the device;
- the URL of a page, which the framework derives from the workspace, page, shape, and list names;
- help text under a field, a confirmation before an act, and an empty-list message, each of which is UX and none of which the sketch has a word for yet;
- what a hand-written description for a non-Velle API does with a `where` predicate, which no such API evaluates (D9).
