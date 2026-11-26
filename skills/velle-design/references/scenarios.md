# Running an example: `velle try`

When the people give an example, such as "a member borrows a drill, a week later it is not back, is she late?", write it as a scenario and run it on the draft:

```
scripts/velle try draft.velle scenario.json
```

The scenario runs on a system that starts empty at 2026-01-01T09:00:00Z, and its clock moves only when a step says time passes, so the same scenario prints the same report every time. Show the people what the report says each step did. Every sentence in it about what a rule or a prohibition means is the compiler's own, the same words `velle describe` gives; the rest is the records as the system holds them.

Every example on this page runs on this spec, `lending.velle`:

```velle
-- A tool library: members borrow tools, and a loan not back within 7 days is late.
act Member {
    name: text
    email: text
}

act Tool {
    name: text
    replacementCost: decimal
}

-- A tool that costs nothing to replace is a typing mistake.
never (Tool where replacementCost <= 0)

-- Borrowing is a request; the loan is what the library keeps.
transient act Borrow {
    member: one Member
    tool: one Tool
}

shape Loan {
    member: one Member
    tool: one Tool
    borrowedOn: Date
}

rule Lend when Borrow {
    Loan from { member: member, tool: tool, borrowedOn: today }
}

shape LateLoan = Loan where borrowedOn < today - 7 days

shape LateNotice {
    loan: one Loan
    sentOn: Date
}

-- Every night, a late loan with no notice yet gets one.
rule NoticeLate when (LateLoan where not exists LateNotice for this) on Nightly {
    LateNotice from { loan: this, sentOn: today }
}
```

## Writing a scenario

A scenario is a JSON list of steps. Each step is an object whose kind is one of its members:

| Step | What it does |
|---|---|
| `{ "commit": "Act", "with": { ... }, "as": "name", "by": "name" }` | Commits the act. `with` holds its fields as the API layer takes them, except that a reference to a record is the name an earlier step gave it. `as` names the record the act becomes, and only a kept act becomes one. `by` names the record that makes the request, for an act declared `by`. |
| `{ "find": "Shape", "where": "condition", "as": "name" }` | Names the one record of a shape or refinement that a Velle condition holds for, usually a record a rule created. It must hold for exactly one. |
| `{ "pass": "8 days" }` | Moves the clock forward by a duration written as a spec writes one. No rule runs until the next commit or tick. |
| `{ "tick": "Nightly" }` | Runs every rule on a schedule the spec declares, at the clock's time. |
| `{ "show": "name" }` or `{ "show": "Shape", "where": "condition" }` | Prints a record's fields and the refinements it is in. |

A field's value is written as the API layer takes it: text in quotes, a number as a number or in quotes, a `Date` as `"2026-01-15"`, a `DateTime` as `"2026-01-15T09:00:00Z"`, a duration as `"3 days"`, `true` or `false`.

A field the spec fills with `initially randomUUID` differs on every run, so the report says "a random identifier" in place of its value.

## A whole example

Alice borrows the drill. Eight days later the nightly run sends her a late notice:

```json
[
  { "commit": "Member", "with": { "name": "Alice", "email": "alice@example.org" }, "as": "alice" },
  { "commit": "Tool", "with": { "name": "Drill", "replacementCost": 80 }, "as": "drill" },
  { "commit": "Borrow", "with": { "member": "alice", "tool": "drill" } },
  { "find": "Loan", "where": "member.name == \"Alice\"", "as": "loan" },
  { "pass": "8 days" },
  { "tick": "Nightly" },
  { "show": "loan" }
]
```

````text
# What `scenario.json` does on `lending.velle`

The scenario runs on a system that starts empty at 2026-01-01T09:00:00Z, whose clock moves only when a step says time passes.
It says what this spec does with these records, as the store holds them after each step.

## Step 1: commit Member with name "Alice", email "alice@example.org", named alice

Accepted.

Created:
- Member 1 (alice): name "Alice", email "alice@example.org"

## Step 2: commit Tool with name "Drill", replacementCost 80, named drill

Accepted.

Created:
- Tool 2 (drill): name "Drill", replacementCost 80

## Step 3: commit Borrow with member alice, tool drill

Accepted.

Created:
- Loan 3: member Member 1 (alice), tool Tool 2 (drill), borrowedOn 2026-01-01

Changed:
- Member 1 (alice): loans none to Loan 3
- Tool 2 (drill): loans none to Loan 3

## Step 4: find the Loan where `member.name == "Alice"`, named loan

Found Loan 3 (loan).

## Step 5: 8 days pass

The clock now reads 2026-01-09T09:00:00Z. No rule runs until a commit or a tick.

Changed:
- Loan 3 (loan): now a LateLoan

## Step 6: the Nightly tick

It ran at 2026-01-09T09:00:00Z.

Created:
- LateNotice 4: loan Loan 3 (loan), sentOn 2026-01-09

Changed:
- Loan 3 (loan): lateNotices none to LateNotice 4

## Step 7: show loan

Loan 3 (loan):

- member: Member 1 (alice)
- tool: Tool 2 (drill)
- borrowedOn: 2026-01-01
- lateNotices: LateNotice 4

It is a LateLoan.
````

`Borrow` is a request the library does not keep, so it cannot be named. The loan its rule creates is named with `find`.

## A refusal

A refused step is reported with the prohibition that refused it, in the compiler's words, and the reason the system gave. It changes nothing, and the steps after it still run:

```json
[
  { "commit": "Tool", "with": { "name": "Ladder", "replacementCost": 0 } },
  { "commit": "Tool", "with": { "name": "Ladder", "replacementCost": 120 }, "as": "ladder" },
  { "show": "ladder" }
]
```

````text
# What `scenario.json` does on `lending.velle`

The scenario runs on a system that starts empty at 2026-01-01T09:00:00Z, whose clock moves only when a step says time passes.
It says what this spec does with these records, as the store holds them after each step.

## Step 1: commit Tool with name "Ladder", replacementCost 0

Refused.

The prohibition that refused it:

- A Tool is refused when its replacementCost is at most 0.
  In the spec: lending.velle, line 13, `never (Tool where replacementCost <= 0)`

The reason the system gave:

```
Tool refused: this Tool would be a Tool where replacementCost <= 0.
  never #1: Tool where replacementCost <= 0
  this Tool: name = "Ladder", replacementCost = 0
  evaluation:
    this Tool.replacementCost = 0
```

No record was created, changed, or deleted.

## Step 2: commit Tool with name "Ladder", replacementCost 120, named ladder

Accepted.

Created:
- Tool 1 (ladder): name "Ladder", replacementCost 120

## Step 3: show ladder

Tool 1 (ladder):

- name: "Ladder"
- replacementCost: 120
- loans: none

It is in none of the refinements of Tool.

Step 1 was refused. The steps after it still ran, on the records as they stood.
````

The command exits 0: a refusal is something the spec does, not a fault of the scenario.

## When the scenario is wrong

A scenario that names something the spec does not have, or a record no step named, stops at that step, after the report of the steps before it, and the command exits 1:

```json
[
  { "commit": "Member", "with": { "name": "Alice", "email": "alice@example.org" }, "as": "alice" },
  { "commit": "Borrow", "with": { "member": "alice", "tool": "drill" } }
]
```

````text
# What `scenario.json` does on `lending.velle`

The scenario runs on a system that starts empty at 2026-01-01T09:00:00Z, whose clock moves only when a step says time passes.
It says what this spec does with these records, as the store holds them after each step.

## Step 1: commit Member with name "Alice", email "alice@example.org", named alice

Accepted.

Created:
- Member 1 (alice): name "Alice", email "alice@example.org"

## The scenario stops at step 2

`drill` is not a name an earlier step gave with `as`. The names given so far are `alice`.
````

A `find` that holds for no record, or for several, stops the same way:

```json
[
  { "commit": "Member", "with": { "name": "Alice", "email": "alice@example.org" } },
  { "commit": "Member", "with": { "name": "Alice", "email": "alice@example.net" } },
  { "find": "Member", "where": "name == \"Alice\"", "as": "alice" }
]
```

````text
# What `scenario.json` does on `lending.velle`

The scenario runs on a system that starts empty at 2026-01-01T09:00:00Z, whose clock moves only when a step says time passes.
It says what this spec does with these records, as the store holds them after each step.

## Step 1: commit Member with name "Alice", email "alice@example.org"

Accepted.

Created:
- Member 1: name "Alice", email "alice@example.org"

## Step 2: commit Member with name "Alice", email "alice@example.net"

Accepted.

Created:
- Member 2: name "Alice", email "alice@example.net"

## The scenario stops at step 3

2 records of Member are ones where `name == "Alice"`: Member 1, Member 2. A `find` names exactly one; narrow the condition.
````

A scenario that is not a list of steps, or a step that is not one of the five kinds, is refused before any step runs:

```json
[
  { "wait": "8 days" }
]
```

````text
`scenario.json` cannot be run. Step 1: a step has exactly one of `commit`, `find`, `pass`, `tick`, `show`; this one has none.
````

The other messages a scenario can draw:

- An act, a schedule, or a shape the spec does not declare: the message lists the ones it does.
- A field the act does not have: the API layer's message, naming the act and the field.
- `as` on a request the spec does not keep: use `find` for the record its rule creates.
- An act declared `by` with no `by` in the step, or a `by` on an act that is not made by anyone.
- A duration a spec cannot read, or one that is not forward.
