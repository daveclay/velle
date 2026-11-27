# OQ214 — How an author writes a field on some members of a collection, or with a value that differs per member

**Status:** opened 2026-11-27 while building OQ207, as a one-line question about what the V34 message should advise. Given a file on 2026-11-27 because the message cannot be fixed until the language question is answered. D1 decided 2026-11-27 and being built; D2 awaits Dave.

**In plain terms:** "Flag every late loan of this member" and "count one more renewal on each of this member's loans" are ordinary things for a business to say. Velle can write one field on every member of a collection with the same value. It can write only some of the members when the shape names them as a collection of its own. It has no direct way to give each member a value worked out from that member; the only spelling that works for that today makes the author keep an extra record per member.

**See:** Reference §6 "Committing and assigning collections" (the one spelling for writing through a collection) · Reference §12 (an assignment's left side is a fixed path) · Reference §14 "Creating one record per element (`for each`)" (a block holds creations and blocks only) · `checks.md` V34, V1, V17 · `compiler/src/main/kotlin/velle/validation/ForEachCheck.kt` (`checkBlockStatementKind`, where the V34 message is written) · OQ207, settled, which made the message name `items.n = 1` when that spelling exists · OQ64, settled 2026-10-23, where Dave decided a block holds "creations and nested blocks only"

---

## 1. What runs today

Every spec below is checked in under `working-docs/examples/survey/` and shares these two declarations:

```
act Member { name: text }

act Loan {
    member: one Member                       -- the member who holds the loan
    dueOn: Date
    renewals: integer initially 0            -- how many times the loan was renewed
    flagged: boolean initially false
}
```

**The one spelling that exists writes every member, with one value.** `member.loans.flagged = true` in a rule writes `flagged` on every loan of the member (Reference §6). The value on the right is worked out once, from the rule's subject.

**Writing some members cannot be said in the rule alone.** `working-docs/examples/survey/57-write-some-members-filter-on-left-refused.velle`:

```
transient act FlagLate { member: one Member }

rule Flag when FlagLate {
    (member.loans where dueOn < today).flagged = true    -- the parser refuses: a filter cannot stand on the left
}
```

**Writing some members works when the shape names them** (Dave's question, 2026-11-27). `working-docs/examples/survey/61-write-some-members-derived-collection-accepted.velle`:

```
act Member {
    name: text
    overdue: many Loan = (loans where dueOn < today)     -- a derived collection: the member's late loans
}

transient act FlagLate { member: one Member }

rule Flag when FlagLate {
    member.overdue.flagged = true                        -- writes `flagged` on each late loan only
}
```

It validates. I ran it once with `velle try`: of a member's two loans, the late one was flagged and the other was not. No test pins that run yet (work item below). `checks.md` V20 says a derived collection is never the target of a whole-collection assignment; a write of one field through it is accepted. The filter must be one the member shape can state, so a filter that reads the request, such as "loans due before the date this request names", has no such spelling.

**A value that differs per member has no spelling.** `working-docs/examples/survey/58-write-each-member-own-value-refused.velle`:

```
transient act Renew { member: one Member }

rule Extend when Renew {
    member.loans.renewals = member.loans.renewals + 1    -- F2: the right side is every loan's count at once, a collection
}
```

**Inside `for each`, where each member has a name, an assignment is refused.** V34 refuses `loan.renewals = loan.renewals + 1` inside `for each loan in member.loans`. The catalogue specs are `compiler/src/test/resources/diagnostics/V34-assigns-inside-for-each.velle` and `V34-assigns-inside-for-each-over-filter.velle`. For these two cases the message advises "write the assignment in the rule, outside the block, through the path to the collection", which is the spelling files 57 and 58 show does not exist. For the filter, the advice should name file 61's spelling. That is the fault OQ207 left behind.

**What works: keep a record per member, and let a rule on that record write.** `working-docs/examples/survey/59-write-each-member-kept-record-workaround.velle`:

```
transient act Renew { member: one Member }

act RenewalOfLoan { loan: one Loan }                     -- a kept record, one per late loan per request

rule Spread when Renew {
    for each loan in (member.loans where dueOn < today) {    -- the filter chooses the members
        RenewalOfLoan from { loan: loan }                    -- a block may create
    }
}

rule Count when RenewalOfLoan {
    loan.renewals = loan.renewals + 1                    -- one loan per firing, so the value is that loan's own
}
```

It validates. I ran it once with `velle try`: a member with one late loan and one loan not yet due, two `Renew` requests, and the late loan ended with `renewals` 2 while the other stayed at 0. No test pins that run yet (work item below).

**The record per member must be kept.** `working-docs/examples/survey/60-write-each-member-transient-record-refused.velle` is file 59 with `transient act RenewLoan`. V17 refuses it: a rule cannot create a transient act.

**A rule over the member itself is not a general answer.** `rule Extend when (Loan where exists (Renewal where member == this.member)) { renewals = renewals + 1 }` validates. A rule fires when a record comes to match its condition (Reference §11), so I expect it to fire for a loan at the member's first `Renewal` and not at the second. I have not run it.

## 2. What to decide

Roster: D1 is whether a `for each` block may assign through its own name. D2 is whether a filter may stand on the left of an assignment.

### D1. Whether a `for each` block may assign a field through its own name · Decided 2026-11-27

**May a `for each` block with one name, over a collection of records, hold an assignment to a field of that name's record?**

```
transient act Renew { member: one Member }

rule Extend when Renew {
    for each loan in (member.loans where dueOn < today) {    -- the filter chooses the members
        loan.renewals = loan.renewals + 1                    -- written once per late loan, with that loan's own count
        loan.flagged = true
    }
}
```

I recommend yes, within the limits below. They are there so that one request never writes the same field of the same record twice. Velle already insists on that everywhere (Reference §12, "One writer per field, per commit"): within one commit there is no order between writes, so a field written twice with two values would have no defined value. A block could break it like this:

```
transient act RenewTimes { member: one Member, times: integer }

rule Extend when RenewTimes {
    for each loan in member.loans, step from 1 to times {    -- two names: each loan is met once per step
        loan.renewals = step                                 -- one loan written `times` times, with 1, 2, 3...
    }
}
```

The limits:

- The block has exactly one name, and its source is a collection of records. A collection is a set, so each record is met once.
- The block is not inside another block. Inside two blocks one record could be met several times with different values, and the field would have no defined value.
- The left side is `name.field`, one stored field of the named record. The right side may read the name.

On 2026-10-23, in OQ64, you chose "creations and nested blocks only" because assignment already had its one spelling, the write through a collection. File 58 shows that spelling does not cover a value per member, and file 61 shows it covers a filter only when the shape can state the filter. This decision would change that answer for the cases above and nothing else.

Costs of yes:

- **Two spellings for one statement.** `for each loan in member.loans { loan.flagged = true }` would say what `member.loans.flagged = true` says. The compiler could advise the shorter one, or accept both.
- **Nothing is lost for the compiler's proofs.** The left side is still a fixed field of a known shape. The one-writer check (V1) already counts a write through a collection as a write to that field on every record of the member shape, and would count this the same way. `velle compare` and the diagrams read the same summary.
- **`loan.renewals = loan.renewals + 1` is a running total**, with the care Reference §19 already asks of one. That is the same as in rule `Count` of file 59.
- **The limits are checks, not something the grammar rules out.** A block with two names, or a nested block, that assigns would be refused with a message. That leans against "a design in which a wrong state cannot be represented beats one in which it is checked", although the wrong state is still never accepted.

Costs of no:

- For a filter the shape can state, nothing is lost: the author names the collection on the shape, as in file 61, and the name says what the filter means.
- For a value per member, or a filter that reads the request, an author keeps a record per member per request, as in file 59, whether or not the business wants that record. Where the business does want it, as a ledger of renewals, file 59 is the better spec anyway.
- The V34 message would advise file 61's spelling for a filter, and file 59's spelling in words for a value per member: create a record per member in the block, and write the field in a rule on that record.

Not offered, and why:

- A filter on the left side was listed here at first. Dave asked about it on 2026-11-27, and it is now D2.
- **Letting a rule create a transient act**, as in file 60. It would remove the kept record from file 59, but it goes against "an incoming act is an input to the state, not a member of it": a transient act arrives from outside.

#### Proposed changes

- `compiler/src/main/kotlin/velle/validation/ForEachCheck.kt`: `checkBlockStatementKind` accepts an assignment within the limits above and refuses any other with a message naming the limit it breaks.
- The evaluator runs the assignment once per element; the rule's write summary records the field on the member shape.
- `wiki/Reference/Reference-14-for.md`, "Creating one record per element": a block holds creations, blocks, and an assignment through its one name, with the Renew example.
- `wiki/Reference/Reference-06-Relationships.md`, "Write one field on every member": one sentence pointing at the block for some members or a value per member.
- `wiki/Normative/checks.md` V34 and `wiki/Normative/grammar.md`.
- `compiler/src/test/resources/diagnostics/`: the two V34 assignment specs are replaced by specs for the limits, and `wiki/Normative/diagnostics.md` is regenerated.
- Survey files 57 to 61 leave `working-docs/examples/survey/` as the `implement` skill §4 says.

**Decided:** yes, with the real rule and not the simple limits above (Dave, 2026-11-27: "implement the real rule from the start"). The limits in the proposal refuse every assignment in a nested block. The rule Dave chose refuses an assignment only where a record could be written twice, so a nested block that reaches each record once may assign. The rule is spelled out in §3.

### D2. Whether a filter may stand on the left of an assignment

**May a rule write one field on the members of a collection that a filter chooses, with the filter written in the rule?**

```
transient act FlagLate {
    member: one Member
    before: Date                                              -- the request names the date
}

rule Flag when FlagLate {
    (member.loans where dueOn < before).flagged = true        -- writes `flagged` on each loan the filter chooses
}
```

I recommend yes. I found no reason for the refusal beyond the grammar: a statement may not begin with `(`.

- **The compiler loses no proof.** The statement still names one stored field of one known shape, `Loan.flagged`. The one-writer check (V1) counts a write through a collection as a write to that field on every record of the member shape, filtered or not. `velle compare` and the diagrams read the same summary.
- **The runtime already does it.** File 61 writes through a derived collection, which is the same filter stated on the shape.
- **It says something file 61 cannot.** A filter that reads the request, `dueOn < before` above, cannot be stated on `Member`.
- **It does not cover a value per member.** The right side still has no name for the member, so D1 stands on its own.

Costs of yes:

- Two places to state a filter, the shape and the rule. Naming it on the shape stays the better practice when the filter means something to the business, and that goes in `wiki/Guides/Best-practices.md`.
- The parentheses are required, so that `where` is not read as part of the value.

Costs of no: a filter that reads the request needs file 59's kept record per member.

Not checked: that the parser can tell this statement from every other that could begin with `(`, and that a filter reading the field it writes, `(loans where not flagged).flagged = true`, is evaluated before any write. Both are work items.

#### Proposed changes

- `wiki/Normative/grammar.md` and the parser: an assignment's left side may be `(path where condition).field`.
- The validator checks the filter as it checks any filter, and V20's limits on a path through a collection apply unchanged.
- `wiki/Reference/Reference-06-Relationships.md`, "Write one field on every member", and `wiki/Reference/Reference-12-Assignment.md`, "The left side is a fixed path": the filtered form, with the FlagLate example.
- `wiki/Guides/Best-practices.md`: name a filter on the shape when it means something to the business.
- Survey file 57 becomes the settled spelling and moves as the `implement` skill §4 says.

## 3. The build

D1 only. D2 is not decided, so this build does not settle the question and the file stays.

**The rule.** One request never writes the same field of the same record twice. An assignment inside a `for each` block is accepted when the compiler can see that from the declarations:

1. The assignment's left side is `name.field`, where `name` is the name of the block the assignment stands directly in, and `field` is a stored field of that name's record. An assignment through an outer block's name from inside an inner block is refused: the outer record would be written once per inner element.
2. That block and every block around it has exactly one name, and each runs over a collection of records. A range, a collection of values, or a second name repeats a record by construction.
3. The outermost block may run over any collection of records the rule reaches. A collection is a set, so each record is met once.
4. Every inner block runs over a collection reached from the name of the block directly around it through the other side of a `one` reference, with or without a filter. With `Book { shelf: one Shelf }`, `shelf.books` holds each book under one shelf only. A declared `many`, a derived collection, or a source that does not start from the enclosing name is refused: one record could be reached through two outer records.
5. A field written in a block is written nowhere else in the same rule. Across rules the one-writer check (V1) counts it as a write to that field on every record of the member shape, as it counts a write through a collection.

Each refusal names which of these the assignment breaks, in plain words, with what to write instead where there is something.

**Changes, in build order.**

- `compiler/src/main/kotlin/velle/validation/ForEachCheck.kt`: `checkBlockStatementKind` applies the rule above. The existing V34 advice stays for an assignment the rule refuses, where its spelling exists.
- The evaluator runs the assignment once per element. Every value and every filter is read from the state before the rule's writes, as for any assignment (`evaluation.md`).
- The rule's write summary records the field on the member shape, so V1, `velle compare`, the diagrams, and the runtime's recheck of conditions all see the write.
- `wiki/Reference/Reference-14-for.md`, "Creating one record per element": a block holds creations, blocks, and an assignment through its own name, with the Renew example and the shelf example.
- `wiki/Reference/Reference-06-Relationships.md`, "Write one field on every member": one sentence pointing at the block for a value per member.
- `wiki/Normative/checks.md` V34, `wiki/Normative/grammar.md`, `wiki/Normative/evaluation.md`.
- `compiler/src/test/resources/diagnostics/`: the two V34 assignment specs are replaced by one spec per refusal of the rule, and `wiki/Normative/diagnostics.md` is regenerated.
- Tests: each accepted form at runtime (one block, a block over a filter, a nested block through the other side of a `one` reference), each refusal, V1 across two rules, and what `velle compare` says for a rule that assigns in a block.
- The survey files stay until D2 is decided, since file 57 belongs to D2.

## 4. Work items

- [ ] Pin with a compiler test what file 59 does at runtime: two `Renew` requests leave the late loan with `renewals` 2 and the other loan with 0.
- [ ] Pin with a compiler test what file 61 does at runtime: only the late loan is flagged.
- [ ] Run the rule over the member itself from §1 and record whether it fires at a member's second `Renewal`.
- [ ] Whatever D1 decides: make the V34 message for an assignment over a filter name the derived-collection spelling of file 61.
- [ ] Before building D2: confirm the parser can tell `(path where condition).field = value` from every other statement, and pin with a test that a filter reading the field it writes is evaluated before any write.
- [ ] After D1: reword the V34 message for an assignment over a filter and for a value that reads the block's name, so that it names a spelling that exists.
