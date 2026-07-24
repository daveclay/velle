# Velle

A declarative language to describe a system. It abstracts away the stack, functions, variables, scope, closures in favor of shapes, relationships, and rules.

## Philosophy
- Computers execute code using stacks, registers, addresses, etc.
- We humans don't think about solving problems using stacks, registers, addresses, etc.
- software engineering solves problems by mapping the human problems into computer mechanics
    - translate solutions into stack-based concepts: functions, variables, scope, closures
- We use layers of abstraction to try and abstract away technical details from the real-world problem we're trying to solve
- What if we abstract away the stack, functions, variables, scope, closures?
- What would a language look like where we declare how the system should work without using stacks, functions, variables, scope, closures?
- Interactions are "shapes" that describe the states of an interaction.
    - instead of thinking about "parameters" to a "function" that "returns a result" or "throws an error"
    - there's an input state and several potential resulting state (success, error, retry, etc)
- The language intends to separate human concerns from computer concerns.
    - Velle captures system design choices made with human judgement, capturing rules and data shapes, interactions, relationships, conditions.
    - "Compiling" Velle results in code that can be executed. The code is fungible. Today, AI writes a lot of code. But even before AI, software engineering was _mostly_ changing existing code to support business changes rather than greenfield projects. Code should be thought of as _change_. Now that AI writes code, we expect code to change even more often, and treat it as some intermediary commodity.
    - The problem with code today is that it's complex and noisy and requires interpretation. The more code AI writes, the less the engineers can be confident it does what they intend.
    - Humans can write tests/specs to ensure the "arbitrary" code does what they intend, current test frameworks have no opinion about their structure in terms of use cases (Cucumber BDD attempts to capture this more than junit, which is focused specifically around code, not system use cases).
    - Velle attempts to provide a concise use-case system design language that helps humans capture requirements and make judgements without translating those into the domain of computer science and code.
    - But then extrapolate those requirements into executable tests, modular code, tools to be able to organize and read AI-generated (or human-generated) code.

## Goals
- The language should define "shapes":
    - a "shape" is an object, function, or relationship between shapes
    - has properties that are scalars, or relationships to other shapes
- Rules are defined as top-level structures associated with shapes, rather than buried in nested functions calls
- Needs a way to for conditions to be described without resorting to nesting `if` stanzas
    - I'm thinking a condition is a shape that can be associated with another shape (putting conditions on relationships, for example)
- "Compiling" Velle results in 1) validating shapes and relationships through strong types and 2) deterministic, executable spec tests that AI or human-generated code can be verified against.

## Typical Language Constructs
What makes up the definition of a system?

- Data - data comes in from an API or DB and then written to an API or DB. A huge amount of code is spent mapping one to the other.
- Conditions - largely rules around data.
- Loops - Doing something for each datum
- Calculations - maths, adding, subtracting, multiplying, dividing, etc.

### Conditions
- how do I describe the behavior of a condition?
    - Generally, conditions are defined by branching. Do I keep the branch syntax, where a condition has an if/else declaration?
    - Let's say a "rule" simply includes a referenced shape or not. Could that be used to build up a coherent system?
    - Typically in code, a condition is a structure that tells the computer to compare values "at runtime, in the moment, these values that are in scope." The code is written without knowing what those values will be, but still structures the values in terms of scope and function. Can we move even more abstract, by just referencing shapes?

### For loops
- Similar philosophy to conditions
- Typically in code a loop tells the computer to iterate over some values that will be present at runtime, and the computer then executes the loop using the actual values.
- Abstract away stack/function, make the iteration about shapes: for each row in a file, for each record in this SQL result set, etc. Iterations are declared over shapes/concepts instead of runtime variables/scope.

### Calculations
- Calculations are mapped more straightforward: math is already a declarative language. The "computer" (in the original sense) does the actual calculations given the inputs, but the mathematical functions are abstract.

## Inputs and Outputs
Systems operate on inputs and produce outputs. Inputs are the data that the system receives, and outputs are the data that the system produces. APIs and DBs are typical input/output endpoints of a system.

### Translating to traditional software terms
- An object has a constructor that takes arguments. These are inputs to a shape. Inputs to shapes are other shapes or scalar values.
- A function takes arguments - same as a constructor. The arguments to a function are identical to the constructor of an object. Once a function is "instantiated", it is then "executed".
- An object shape is a function shape that doesn't do anything - it's output is itself.

# AI
Originally, my thought was to create a language that had nothing to do with AI, strictly focused on developing a language that abstracted away traditional typical code structures. However, it seems to me this language could be used to define a system in a strongly typed way that could then be used as a prompt reference and context for AI generated code.

## Context, Prompts, and Organization
When we humans use AI to write code, the only common shared understanding is the code itself. AI writes code based on human prompts, and then humans (sometimes) review the code. Over time, understanding the entirety of the system would require reading through a large amount of code that humans didn't write.

Reading code is the hard part of software engineering. Humans rarely catch bugs just be reading code. It's a poor mechanism for shared understanding, even though it is the source of truth for system behavior.

There are techniques for using markdown files to capture system requirements and providing AI context to build a system, but these are unstructured and not easily organized. Over time, they become even more messy than the code, and are removed from the source-of-truth (the AI-generated code itself).

# Language Structure

## Strongly typed
Shapes, properties, and relationships with other shapes are strongly typed.

## Compiled
The "compiler" is responsible for enforcing strong typing, ensuring the relationships are valid

## Specs & Tests
"Compiling" Velle results in:
1) validating shapes and relationships through strong types
2) deterministic, executable spec tests that AI or human-generated code can be verified against.
3) transpiled executable code that runs the system, baking in best practices (security, error handling, etc.)

# Testing, Spec'ing

- Every rule's spec-worthy content is `Given`/`Then`: the precondition (its refinement or `where` clause, plus its `produces` guard) and the effect (what gets produced).
- The "when" something happens using `on` — whether it names a refinement or a schedule — is invocation plumbing, not business behavior. It doesn't belong in the scenario describing what the rule does; it's the same category of concern as "this runs as a DB trigger vs. a cron job."
- Example:
    ```
    rule SendReceipt on SettledInvoice produces Receipt {
        Receipt for invoice sentOn: now
    }
    ```
  translates to:
    ```gherkin
    Scenario: An invoice is settled
      Given an Invoice with a positive balance
        And no Receipt exists for the invoice
      Then a Receipt is produced for the invoice
    ```
- A schedule-triggered rule works the same way — the schedule itself isn't part of the scenario:
    ```
    rule FlagOverdueAccounts {
        each FlaggedCustomer where not exists ActiveAccountFlag for this produces AccountFlag {
            AccountFlag for this flaggedOn: now
        }
    } on Daily
    ```
    ```gherkin
    Scenario: A customer with too many overdue invoices is flagged
      Given a Customer has 3 or more OverdueInvoices
        And no ActiveAccountFlag exists for the customer
      Then an AccountFlag is produced for the customer
    ```
- The schedule wiring (`FlagOverdueAccounts` runs `on Daily`) is tested separately, at the infrastructure level — it's a fact about invocation, not about the rule's logic.
- This is the concrete answer to the testing gap named in Philosophy: rules compile directly into use-case-structured (`Given`/`Then`) scenarios, rather than needing a hand-written, separately-maintained BDD layer bolted on top of code.
# Compiling Velle

assume we're transpiling to an existing traditional language (leaning towards kotlin but maybe rust)
 
## Codegen/Modifying Velle output
- when Velle "compiles", it produces separate files for every shape/rule/refinement
- those files can be modified by the user or AI
- AI can be given rules about implementing transpiled code from Velle (override within these files, structured file layouts)
- a way to demarcate/introspect generated code "modules"/files
- "escape hatch" isn't one-way; you can pick and choose which files to override or re-generate

## Language "extension"
- lightweight way to introduce new "verbs" or terms that correspond to files of custom code that velle can use
- expose new DSL to the language to support custom ... stuff
- expose DSL via `using` keyword
```
rule SendSlackMessage using CustomSlackMessage {
    // properties here can reference `CustomSlackMessage` definition in a Velle "extension"
}
```

# Visualization
can velle language generate a clear, explicit process diagram?

# Thoughts on Time

rephrase the problem in terms of someone trying to describe what the system should do in the case where an invoice has been paid, then a refund is applied, and the invoice is reverted back to unsettled. There's two things here: first, the human has to describe what the system should do - someone has to decide whether the account should be re-flagged or the custom should be re-notified. Second, the language needs to be flexible enough for that human to tell it which to do. Velle is more like trying to describe what rules exist in the system separately from "when" these rules get applied. So far, the "when" is controlled by artifact shapes, which helps Velle be self-consistent. What artifact shapes might be used by the human to describe what the system should do? The human could decide the customer should be re-flagged and re-notified, or the human could decide the customer should be in some _other_ state where they are in a grace period.

# Thoughts on Traditional Languages
## what is a for loop?
- Why do we iterate?
    - Go through a set of records, operate on each
        - update each item
        - map each item into something else

## what's an if statement?
- conditional logic
    - compare states
    - "branch"
        - Does branch imply tree? Stack?
            - Eh, no, but they do lead to stack confusion
- Abstract decision language. Branch might be the wrong terminology, concept, state-of-mind

# I need a sample problem. Complex enough to test strategies and implementation.
- describe two tables with a many-to-one relationship
- API for CRUD
- read/write to DB
- basic business logic complexity
- Shape spans API and DB?
    - Shape projections? DB project, API projection
    - practical problems: serialization (graphql likely necessary to define/translate arbitrary shapes)

# Notes
- Oh, this is a BDD language. It's writing the code, defining inputs and outputs and interactions
    - declaring relationships
- I need a sample problem. Complex enough to test strategies and implementation.
    - caching - pass in experiment, but the method impl needs experiment group. Sometimes it's in scope of the calling method, sometimes it isn't. If you allow it to be passed in, then you risk validation errors where the passed-in group is not the correct one
    - logic checks - experiment group with a particular flag shouldn't create notebook entries. Where in the stack do you put that check? If it's not _right next_ to the place where notebook entries are created, then you risk places not doing that check before the call. But adding a bunch of checks next to the call makes the method a mess of random chaotic logic checks, with all that state needing to be passed in regardless of whether creating the notebook page requires this.
        - this makes me think of boundaries - public/private, who can call what. Which is another aspect of encapsulation relating to stacks/objects/dependencies.
            - which makes me think that this language is describing boundaries and relationships, shapes are coming along for the ride.
- describe some functionalities.
    - reading files
    - writing records to db
    - making API calls
    - showing form fields
    - fetching and showing data
- problem domains?
    - UI vs logic
    - API vs calculation
- hard-points:
    - databases
    - UI (browsers & mobile apps & desktop apps)
- scope between API and DB.

# shapes
- types, datastructures, relationships.
    - db shapes - active records, table translations, views/joins
        - paths to update columns across views etc
        - for example, declaring a shape joining multiple tables
        - declare how field translations are propogated
        - saved to db, calculated/updated?
        - underlying SQL queries to declare shapes
- how to describe an "if"? Use example.
    - insert, update vs upsert a given input from API.
        - use language of blocks, shapes, logics?
        - inputs, outputs? Outputs, or targets?
            - If a logic results in nothing happening it is meaningless
## Projections
- "casting" is legitimate and safe in a declarative language - it's just a type/shape.

- logics, calculations
    - connect shapes, destinations (APIs, DBs)

- "blocks"
    - can be shapes
    - relationships
    - destinations
    - logics/calculations

- what do I mean by "stackless"?
    - don't instantiate data within functions
    - functions don't call other functions "in order"
    - instead, define relationships
    - order of operations still matters, but is declarative, using relationships.
        - or are relationships
    - connections between things. Relationships.
        - They don't exist at points-in-time, they are declared
        - the system "executes" a set of shapes-and-relationships


# Verb/Noun?
```
[ experiment setup ]
ExperimentGroup where method === "SCREENING" should not create confluence pages
```

# Language Spike

What's the difference between a function and an object? Traditionally, objects have properties/state, and aren't "executable", whereas functions don't have properties and are "executable". Of course, functions do have "state" in terms of closure and stack, but it's traditionally kinda meaningless for them to have "properties".

Why not make them all the same? Well, what does it mean to make an object "executable"? That's vague. A function with properties actually makes more sense, in an abstract way: it has parameters, return values, internal state that changes during its execution...

What if we defined a function as an object, but one whose properties are it's inputs and outputs that have complex shapes. A function can execute, though one might argue that it always executes in the context of some other object. Maybe the root object is Application? Or is this just trying to justify something that doesn't need justification - we just have shapes that can be executed, and shapes that don't have any execution.

A lot of languages have the whole "apply" thing where a thing can be executed by defining an "apply" method.

objects have several methods, so "apply" on "function" shapes is just a kinda of default case.

The real value is that you define a function just like you define an object/class/shape:
- it's findable
- searchable
- you can reason about it
- define references to it and from it

defining an "object" shape implies a single "input" as a sort of "constructor" - it's the list of properties. How do you define multiple constructors? I want to say you _don't_ because that's confusing; some property values are required vs optional, and the constructor would be provided all required and zero or more optional properties. There is no stack where you care about _calling_ a constructor. "input" just calls out "I can be"... well then why bother with input at all?

If both functions and objects have a single input defined by the required and optional properties, then they're the same.

However, if a function is called with some properties, then performs some logic, it generally produces some outputs. Those outputs may be not-null. Is an object a shape with the one default input, while it's output is the thing itself? Where a function shape is something that has the one default input, and then provides some _other_ output?

shape object defines the concept, not the construction of, or intermediary state.

`shape` defines objects and functions.

```
Archivable {
    timestamp archivedAt
}

FermenterBatch {
    is Archivable
    
    // scalar types `text`, `integer`, `decimal` (precision), `boolean`, etc
    integer sequenceNumber
    integer flaskNumber
    // many-to-one relationship to ProteinSequence
    references ProteinSequence as `proteinSequence`
    references PeptideOrderItem as `peptideOrderItem`
    // many-to-many relationship to ProteinBatch implied by both sides flagged as `many`
    references many ProteinBatch as `proteinBatches`
}

ProteinBatch {
    integer sequenceNumber
    // many-to-many is implied by both sides being flagged as `many`
    many FermenterBatch as `fermenterBatches`
}
```

ok, so functions: how do we define behavior? Setup fermenter runs is complex.
```
// `via API` tells the compiler to generate a Rest API for this shape?
// API tells the compiler to look for `using` and `from param` etc?
SetupFermenterRun API {
    // "inputs" defined like properties on objects, with syntax?
    User from AuthenticatedUser
    Fermenter from param `fermenterId` using LookupFermenterById
    Workbook from param `workbook` using ExcelFileUpload
    
    produces SetupFermenterRunResult
}

SetupFermenterRun {
    Fermenter from param `fermenterId` using LookupFermenterById
    Workbook from param `workbook` using ExcelFileUpload
  
    // how does it do this? Break this down into very small references, and the `using` syntax?
    produces: {
        // pass "arguments"? FermenterRun using is redundant if CreateFermenterRun defines FermenterRun as output
        FermenterRun using CreateFermenterRun with User, Fermenter
    }
}

CreateFermenterRun {
    
}

shape FermenterRun {
    User,
    Fermenter,
    FermenterBatch[],
    ProteinBatch[]
}

shape AddFermenterBatchesToFermenterRun {
    inputs: {
        FermenterRun,
        AddFermenterBatch[]
    }
}

//////////////////

Department {
    name is text
    references many Users
}

Order {
    user references one User
    orderItems references many OrderItem
}

OrderItem {
    order references one Order // is this redundant?
    product references one Product
    quantity is integer
}

Product {
    name is text
    description is text
}

User {
    firstName is text
    lastName is text
    active is boolean
    orders references many Order
    department references one Department
   
    // This is a shorthand for defining a method on User, but not sure if it's required. I don't have "scope" to care about whether or not the user has a method defined on it, right? This would only be valuable in the language itself.
    addOrderItem is AddOrderItem with this as user
}

// "function" shapes can be thought of as "constructing a command pattern object to be passed to some logic that will perfrom the given command". So it's "state" is the things you need to provide to the "function" to execute it.

LookupUserOrderById {
    user references User from AuthenticatedUser
    id references Order by id
}

SaveOrderItemToDB {
    uses ORM
    orderItem references OrderItem
}

FindUserOrderItemForProduct {
    user from AuthenticatedUser
    order from userOrder
}

AddOrderItem {
    expose as RestAPI //  provides acces to `from request param` - but could be implied? No, probably want to control API path stuff
    
    // if user has existing orderItem for the given product, add the quantity to the existing order item quantity
    existingOrderItem from FindUserOrderItemForProduct
    
    // ok, wiring up references yeah? "This request param is this property."
    save OrderItem with {
        order references one Order from request param orderId using LookupUserOrderById
        product references one Product from request param productId using LookupProductById
        quantity from request param quantity ensure PositiveInteger // note: required is default, optional must be explicit?
    } 
}

UpdateOrderItem {
    
}


```
## Language Shorthands

```
PeptideOrderItem {
    references one Order
}
```
results in:
```
PeptideOrderItem {
    order is reference to one Order required
}
```

# From initial Claude.ai:

This is a solid foundation, and there's one idea buried in your notes that I think is the actual breakthrough worth building the whole language around. Let me work through it, then sketch what the language could look like.

## The strongest idea: conditions are shapes

You wrote "a condition is a shape that can be associated with another shape." Taken seriously, this means conditions become **named refinements** — subsets of a shape defined by a predicate:

```
shape Invoice {
  customer: one Customer
  amount:   Money
  due:      Date
  paid_on:  Date?
}

shape OverdueInvoice = Invoice where paid_on is none and due < today
shape SettledInvoice = Invoice where paid_on is some
```

Now `if/else` disappears not by hiding it, but by inverting it. Instead of "if overdue, do X, else Y," you attach rules to the refined shapes:

```
rule LateFee on OverdueInvoice {
  fee = amount * 5%
}
```

And here's the payoff that no amount of nested `if` gives you: **the compiler can check exhaustiveness and overlap**. It can prove your refinements partition `Invoice` completely (no unhandled case), or warn that two rules both claim the same invoice. Branching logic becomes something the type system audits, rather than something a human traces through indentation. That's a real, demonstrable advantage — I'd make it the demo that sells the language.

## Tighten the ontology

Right now a shape is "an object, function, or relationship" — three very different things, which will make the semantics mushy. I'd propose a smaller core:

- **Shape** — a typed record (your nouns)
- **Relationship** — a typed, named edge between shapes, with cardinality (`one`, `many`, `optional`), and crucially, relationships can carry conditions and properties themselves
- **Rule** — a top-level derivation or constraint attached to a shape or relationship
- **Mapping** — a first-class construct for shape-to-shape translation

Mapping deserves its own keyword because, as you note, it's where most real code lives:

```
mapping InvoiceFromApi: api.InvoiceDto -> Invoice {
  amount   <- dto.total_cents as Money(cents)
  due      <- dto.due_date
  customer <- Customer with id = dto.customer_id
}
```

The compiler checks *totality*: every required field of the target is produced, every source field is either used or explicitly ignored. A whole category of integration bugs becomes a compile error.

Loops then fall out for free — iteration is just a rule quantified over a collection, the way SQL quantifies over rows:

```
derive MonthlyStatement for each Customer {
  total = sum(invoices where issued in current_month, amount)
}
```

## Prior art worth mining

You're in good company, which is encouraging — this space has real lessons:

**Eve** (Chris Granger's project) is the closest ancestor: records plus reactive top-level rules. It died mostly on tooling and unclear audience, not on the idea. **Alloy** proves that "shapes + relationships + constraints" can be checked mechanically — steal its relational logic. **CUE** treats types and values as one lattice, which is exactly what "conditions are shapes" implies. **Datalog** gives you the cleanest semantics for derivation rules. And **SQL** is the existence proof that ordinary developers happily write declarative logic over shapes — when the domain is scoped tightly.

## Three hard problems you'll need answers for

First, **effects and sequence**. Data, conditions, loops, and calculations describe the static world, but systems *do* things: "when payment arrives, mark invoice paid, then email a receipt." Pure declaration struggles with "then." My suggestion: declarative event rules (`when PaymentReceived on Invoice ...`) plus explicit state machines as a shape kind, statechart-style. Don't smuggle imperative code back in through a side door — but do have an answer.

Second, **the escape hatch**. Every real system has 5% that won't fit the paradigm. And this is where your AI insight becomes structural rather than bolted-on: a Velle behavior with no declarative body is a *contract* — signature, conditions, invariants — and the implementation is AI-generated (or hand-written) code that must pass the tests the compiler emits from the spec. Velle stays the source of truth; code becomes a build artifact. That's a genuinely sharp positioning: today AI-assisted development has no verifiable spec layer, just markdown that rots. A compiled, exhaustiveness-checked spec that generates the conformance tests is exactly the missing piece.

Third, **provenance**. Debugging declarative systems means asking "why did this rule fire?" or "why is this value 47?" Plan for a `why` command from day one — Prolog's biggest adoption wound was operational opacity.

## Where I'd start

The pragmatic MVP: a parser and type-checker for shapes, refinements, relationships, and mappings, plus a checker that verifies refinement exhaustiveness — no code generation yet. That alone is a useful tool (a linter for system design), and it forces the semantic questions to get answered early.

One fork in the road determines a lot of the design, though: is Velle ultimately *executable* (compiler emits a running system), or is it a *specification layer* whose output is contracts and tests that constrain AI-generated code? Which of those is the version you're most excited about?