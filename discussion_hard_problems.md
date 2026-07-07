

# Three hard problems — discussion

Context: Velle transpiles into a traditional target language rather than executing itself. Transpilation leans on AI understanding Velle's structure as a prompt/context, not just a mechanical 1:1 compiler pass — so AI is part of the codegen pipeline, not only an escape-hatch for the 5% that doesn't fit. That reframes all three problems below.

## 1. Effects and sequencing

The question isn't just how Velle expresses "when X, then Y" — it's whether Velle needs to fully determine execution order, or can leave some ordering to the transpiler/AI as long as invariants hold.

Two sub-decisions:

- Do event rules (`when PaymentReceived on Invoice: mark_paid, send_receipt`) need explicit sequencing, or is "all effects of one event happen, order unconstrained unless declared" good enough? SQL trigger semantics are a decent precedent — most systems don't actually care about order unless there's a dependency.

> Explicit sequencing is important because human problems are often sequence dependent. I want to explicitly define ordering of operations in Velle. Because "shapes" are intended to capture functions and state, the idea would be that order could be defined as an explicit sequence of referenced shapes. I think I need to answer some real-world examples to figure out if ordering requires some sort of state management (how do we pass the output of one thing to the input of another thing if there are operations in between).
 
- Are state machines a separate shape kind, or do they fall out of refinement shapes + event rules (a state is just a refinement, a transition is just a rule that changes which refinement applies)? Leaning toward the latter — fewer concepts, reuses "conditions are shapes" instead of introducing a second mechanism.
 
> every computer, every computer program is a state machine. The goal of Velle is to avoid reframing/rewording human problems into computer problems. Shapes and rules should dictate the outputs of the system. How Velle defines inputs and outputs is how this would be handled, I think.

## 2. The escape hatch

With AI in the transpile loop, the real fork is: is the whole compiler AI-assisted, or is there a hard boundary between a deterministic compiler for the declarative core and an AI-assisted layer only for what can't be expressed declaratively?

Arguing for the hard boundary — if everything goes through AI generation, the auditability that's the whole selling point of "conditions are shapes" is lost (exhaustiveness checking is worthless if the code implementing it wasn't deterministically derived). So: mechanical transpilation for shapes/relationships/rules/mappings, and contracts-plus-AI-generation only for behaviors with no declarative body. That keeps "AI understands Velle as a prompt" true for the hard 5%, without making the whole compiler non-deterministic.

> hard boundary. Velle is intended to be a deterministic spec that humans can easily read and validate, allowing the AI-generated (or human-generated or whatever) code to be fungible over time. Velle becomes the shared, structured definition. I'm thinking that if "compiling" Velle results not just in validation of the shapes and relationships, but then produces dterministic, _executable_ spec tests, then AI can generate code and the specs can verify the behavior from a common shared definition. What's missing today (and what Velle is intended to solve) is a bridge between giving AI ad-hoc markdown files and the fungible, tens-of-thousands lines of code that are executed.

## 3. Provenance

This one gets easier under transpilation, not harder — codegen is controlled, so provenance instrumentation (rule IDs, source spans) can be auto-injected into generated code as a byproduct of compiling, rather than bolted on after the fact. `why` becomes: replay/inspect a log of which named rule/refinement fired, mapped back to Velle source via something like a sourcemap. Stronger position than Prolog ever had.

> Yes. The runtime has to map back to the Velle source explicitly. Transpiling can be used to ensure the Velle "compiler" adds a ton of explict checks, uses best practices for common tasks, throws errors with explicit context all without the developer or AI having to manually write them explicit in traditional code. For example, prepared SQL statements can be enforced, rather than allowing code to be written to execute arbitrary insecure string interpolated SQL. Or having to catch and re-throw exceptions at every layer of the stack corresponding to a layer of abstraction just to collect the context data that led to an error.

## Open question to resolve first

Of (1) state-machine-vs-refinement and (2) deterministic-core-vs-AI-boundary, (2) seems to determine the most downstream design and is worth settling first.

>
