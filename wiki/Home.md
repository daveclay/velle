# Velle

Velle is a language intended to describe a system that is agnostic to how the system is executed at runtime.

Velle captures discussions between a Product Owner and an Engineer without adding obfuscation and complexity of how that system actually runs on a computer. Reading Velle describes the system - its conceptual models and behaviors - without introducing computer science terms and concepts. The Product Owner and Engineer could read Velle together to understand the system. Velle can generate artifacts useful to Product Owners: design specs and diagrams — class, state-flow, cause-graph, and sequence views derived from the spec (`diagrams.md`).

Velle abstracts away the stack, functions, variables, scope, closures in favor of shapes, relationships, and rules. It leaves the implementation of lower-level mechanisms up to an implementor: the mechanics of publishing an event payload to a enterprise service bus, calling a legacy SOAP endpoint, etc.

Velle enforces business concepts and rules at a higher layer of abstraction, while letting existing languages and frameworks implement lower layer details.

This wiki is published from the `wiki/` folder of the [repository](https://github.com/daveclay/velle); edit it there, not here, or the next publish overwrites the change.

## The Velle Language Reference

One page per numbered section. Every document, spec comment, and validator diagnostic cites a section as "Reference §N".

- [[§1 Principles|Reference-01-Principles]]
- [[§2 Philosophy|Reference-02-Philosophy]]
- [[§3 shape|Reference-03-shape]]
- [[§4 State and commits|Reference-04-State-and-commits]]
- [[§5 Scalars|Reference-05-Scalars]]
- [[§6 Relationships (one, many)|Reference-06-Relationships]]
- [[§7 Derived properties|Reference-07-Derived-properties]]
- [[§8 Refinements (where)|Reference-08-Refinements]]
- [[§9 Composing refinements (and, or)|Reference-09-Composing-refinements]]
- [[§10 Predicate expressions|Reference-10-Predicate-expressions]]
- [[§11 rule ... when ... on ...|Reference-11-rule-when-on]]
- [[§12 Assignment (mutation in place)|Reference-12-Assignment]]
- [[§13 Exit triggers (when leaving)|Reference-13-Exit-triggers]]
- [[§14 for|Reference-14-for]]
- [[§15 then|Reference-15-then]]
- [[§16 Sweeps: rules fire per record|Reference-16-Sweeps]]
- [[§17 Schedule triggers (on <schedule>)|Reference-17-Schedule-triggers]]
- [[§18 Run-once guards|Reference-18-Run-once-guards]]
- [[§19 Self-referential folds and tolerates|Reference-19-Self-referential-folds-and-tolerates]]
- [[§20 State-change patterns|Reference-20-State-change-patterns]]
- [[§21 never|Reference-21-never]]
- [[§22 Open / unresolved|Reference-22-Open-unresolved]]

## Normative documents

Cited by bare name (`checks.md`, `evaluation.md`) throughout the Reference and the code.

- [[grammar|grammar]] — The whole-language grammar, settled and normative
- [[evaluation|evaluation]] — The operational semantics of a transpiled system, in execution order
- [[checks|checks]] — Every check the v0 validator runs, one entry each
- [[patterns|patterns]] — Worked spellings the normative documents cite
- [[testgen|testgen]] — How executable specifications are generated from a spec
- [[diagrams|diagrams]] — The design artifacts derived from a spec and the projection principle behind them
- [[singular_references|singular_references]] — The `(Shape for expr)` query form and when its claim holds
- [[GLOSSARY|GLOSSARY]] — Coined terms, one line each, with the pointer that defines them

## Guides

Walkthroughs that gather what the Reference and the normative documents settle, in the order an author meets it.

- [[Authorization|Authorization]] — who may commit an act and who may see a record: the actor, role gates, data-driven constraints, visibility, the generated surface, and how it compares with role-based access control
- [[Binding API|Binding-API]] — the generated Kotlin an engineer's code calls to commit an act: the input class, nested parts, references to records on the books and to new ones, the commit function and what it refuses

## Working notes

- [[Velle UI|Velle-UI]] — notes toward a declarative UI layer
- Open design questions and work items live in the repository under `working-docs/`, not in this wiki.
