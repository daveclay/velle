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

# Transpilation Architecture & Design

A developer will be able to provide new data types. Velle will provide an API that allows the developer to write some code that tells the velle "compiler" about the data type when they execute the velle compiler.

A developer will be able to provide new predicate functions. Velle provides an API for this in the same way. Velle's registration API should include any metadata to tell the velle validator whether it is idempotent or potentially any `tolerates` it might require/support.

Same for "expose" mechanisms.

These could be sharable via libraries, open source, or custom code on file that Velle can locate.

This sets up an architecture:

1. The Velle language + compiler + environment
    - the compiler/validator executable
    - Velle looks for extensions in the developer's workspace
2. The Velle language extension framework
    - custom data types
    - custom predicate functions
    - packaged, installed options, not just custom code
    - open source, shared (think of shared business domain extensions, like a "Velle finance extensions" library)
3. The transpile output from Velle
    - in the developer's workspace
    - developer owns that code
    - separate from the developer's Velle extensions (separate concerns: custom extensions won't change when the Velle spec changes. Could be a shared library, or open source extensions)
    - produces executable runtime
    - produces executable tests that verify the runtime.

## Escape Hatch

Existing escape hatches in other frameworks are absolute garbage.

An engineer uses framework X, it produces code, and as soon as the product owner asks for a single unsupported use case, the engineer is forced to abandoned the framework.

The same problem happens with all codegen: it only works for use cases foreseen by the framework. Reality is always more complicated.

Velle's codegen needs to address this.

Velle's codegen must be _open_: allow for customization, extension. Codegen is not a blind, one-way dump. Velle should "interact" with the engineer's customizations in a structured way. 

In practice, this would mean some way for an engineer to demarcate customizations/extensions that Velle can read and comprehend and maybe even validate. If an engineer's customizations are incompatible with the Velle spec, the transpiler can raise an error.

## Persistence & Velle Commits

Velle's concept of "commit" is an abstraction, not an implementation. Real systems use real databases, real transactions, real ORMs.


## Framework or Language?

Velle could be a language as a _starting_ point:
- write velle
- transpile into code
- build API/db etc around it

Velle could be a _framework_:
- write an application
- add the Velle library
- write velle
- initialize velle runtime using the velle spec
- transpile into code that the app uses

Either way, the "hard" part is the integration points where we have to generate code for what velle manages, and expose lower-level constructs for things like APIs and DBs.

### Realistic Use Case

No one is going to run a webserver in Velle, and in fact that goes against the concept that the language is a higher-level abstraction. There's far too many grimy nuances in the real world to support as "plugins" to some velle "runtime" framework.

An engineer would choose to use Velle to capture the business logic and shapes, then create a new spring boot app for the actual runtime service.

This means Velle produces an artifact that the engineer can then drop into the spring boot app.

The question now is: what is that artifact? What is it's API?

Does `expose` stay as a way to declare a shape as an argument to a function? Yeah, right? Otherwise you'd be able to commit any shape or _no_ shape. If it's just a function, why bother with `using X` since we're not trying to capture _where_ a thing came from.

Oh - the idea was a separate springboot-velle libarary that would implement a default "I'll take your `expose` shapes and make REST APIs for them" and an ORM that implements a persistence layer. There's almost _assuredly_ a need to wire in custom code to a "commit" and "transaction" - calling some API or other code within a transaction committing a shape to persistence. If using this springboot-velle framework does the REST API, _where_ could an engineer wire in custom logic, since the entry point is hidden?

# Shape "Interfaces"

What if a rule applies to multiple shapes that act the same? Can you declare a refinement as a union of states? Probably! Probably useful! 

shape Vehicle = Car | Truck | Bicycle | Boat

rule DriveVehicle when Vehicle ... {
    -- startedOn is shared, so valid
    startedOn: now
}

# Typical Language Constructs
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

# Language Characteristics

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

Velle isn't really "compiled" in the traditional sense, it's _validated_ for consistency. Errors related to system design using that language and at that layer of abstraction. The output is transpiled code in a traditional language, plus executable tests that verify the behavior of the system runtime.

assume we're transpiling to an existing traditional language (leaning towards kotlin but maybe rust)
 
## Codegen/Modifying Velle output
- when Velle "compiles", it produces separate files for every shape/rule/refinement
- those files can be modified by the user or AI
- AI can be given rules about implementing transpiled code from Velle (override within these files, structured file layouts)
- a way to demarcate/introspect generated code "modules"/files
- "escape hatch" isn't one-way; you can pick and choose which files to override or re-generate
- right-hand side can be extended with "plugins".
  - might need the author to specify whether custom RHS functions are idempotent or not, if the Velle validation step is doing RHS idempotent validation 

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

# More random notes

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