# Velle

## Philosophy
- Computers execute code using stacks, registers, addresses, etc.
- We humans don't think about solving problems using stacks, registers, addresses, etc.
- software engineering solves problems by mapping the human problems into computer mechanics
    - translate solutions into stack-based concepts: functions, variables, scope, closures
- We use layers of abstraction to try and abstract away technical details from the real-world problem we're trying to solve
- What if we abstract away the stack, functions, variables, scope, closures?
- What would a language look like where we declare how the system should work without using stacks, functions, variables, scope, closures?

## Goals
- The language should define "shapes":
    - a "shape" is an object, function, or relationship between shapes
    - has properties that are scalars, or relationships to other shapes
- Rules are defined as top-level structures associated with shapes, rather than buried in nested functions calls
- Needs a way to for conditions to be described without resorting to nesting `if` stanzas
    - I'm thinking a condition is a shape that can be associated with another shape (putting conditions on relationships, for example)

## Practical Problems
- how do I describe the behavior of a condition?
    - Generally, conditions are defined by branching. Do I keep the branch syntax, where a condition has an if/else declaration?
    - Let's say a "rule" simply includes a referenced shape or not. Could that be used to build up a coherent system?


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
