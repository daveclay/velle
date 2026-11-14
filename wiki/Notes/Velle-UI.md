# Velle UI

## Random notes
- An extension type (Reference §5) could carry how a value of it is entered and rendered: an input mask, a display format. Nothing in `velle.ext.VelleType` blocks adding it later.
- We're missing an abstract declarative way to describe a view that can be rendered in different places with different concrete options. For example, a list view might have different columns when rendered in different contexts.
- UI is difficult to build using re-usable agnostic components. Why?
- UI is tedious: little differences in viewing the same underlying records are important, so there end up being lots of conditionals and toggle properties that complicate things.
- Complex UI components tend to be tightly coupled to their hierarchy/surroundings: a detail view requires several properties from its list view. Redux pattern helps, if it's used correctly.

## Solutions?
- I want a declarative UI language. Much of the UI should be built from a few base declarations.
- Split between conceptual UX and rendered UI: declare the UX: list views, detail views, how they relate, the data they reference and contain. Rendered UI moves those fields around, colors them, etc.

# UX vs UI
## UX
- "this is a list view for these kinds of models, these are the fields that may be shown"
- "a user with sufficient permissions can edit or archive a model from this view."
- "the list should be built from a search and filter component" 
## UI
- "render the sort controls here, render a filter above instead of below."
- "sorting should be buttons available on each column header on the list view"

Splitting up UX from UI allows UX to be re-used, but also de-clutters UI concerns from UX concerns. It allows UI changes to be made without breaking UX rules.

# Pushing Separate UX Components
- React provides a way to build separate UI components
- But they easily tend to get tightly coupled to where they're used
- Because they're UI components, not UX components (they describe visual rules rather than agnostic interactive rules)
- Re-usable UX components - describe a component as the data it shows and how it can be interacted with
- apply visual rules to UX components to build UI.
- "assemble" step that builds a frontend by applying UI declarations to various UX components 