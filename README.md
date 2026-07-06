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
