# Stress test: separating `trigger` from `rule`

`LANGUAGE.md` §10 already states the principle: a rule declares *what* effect corresponds to *what* refinement, not *when* or *how* that correspondence gets checked — mechanism (write-time check, scheduled sweep, event stream, or a mix of these for the same rule) is a compiling concern, not part of what the rule means. The syntax hasn't caught up to that yet: every rule declared so far still bakes exactly one trigger, in exactly one position, directly into the rule's own header or footer — prefix `on Refinement` or postfix `on Schedule`, never both, never more than one. If the same rule can legitimately be triggered more than one way, the rule declaration shouldn't be the thing that owns "when" at all.

`break_velle.md` already has a sketch of the fix, sitting next to the old form for comparison:

```
rule FlagOverdueAccounts {
    each FlaggedCustomer produces AccountFlag {
        AccountFlag for this basedOn: (this.invoices where OverdueInvoice) flaggedOn: now
    }
}

trigger FlagOverdueAccounts on Daily
trigger FlagOverdueAccounts when FlaggedCustomer is created
```

Same method as `example_predicates.md`: don't invent syntax speculatively, work through concrete cases already in the docs, and resolve each question a worked case actually forces.

## Inventory: every distinct rule/trigger form used so far

- Prefix, data-driven: `rule SendReceipt on SettledInvoice { ... }` — `LANGUAGE.md` §10
- Prefix, data-driven, with a `where`-narrowed refinement to react to: `rule ResolveFlagIfCleared { each Customer where exists ActiveAccountFlag for this and not (this is FlaggedCustomer) produces AccountFlagResolved { ... } } on Daily` — `example_invoice_payment.md` #5
- Postfix, schedule-driven, alone: `rule FlagOverdueAccounts { each FlaggedCustomer produces AccountFlag { ... } } on Daily` — `LANGUAGE.md` §14/§15
- Postfix, schedule-driven, comma list: `on Daily, Hourly` — `LANGUAGE.md` §15
- The new, not-yet-formalized sketch: no `on` anywhere in the `rule` block at all, one or more standalone `trigger <RuleName> on <Schedule>` / `trigger <RuleName> when <Refinement> is created` declarations elsewhere — `break_velle.md` (most recent edit)

## 1. Why the current syntax doesn't match the stated semantics

§10's contract is symmetric across mechanisms — write-time check, sweep, event stream, or a mix, all equally valid, all meaning the same thing. But the grammar today only has room for one `on`, attached to one rule, in one of two fixed positions. That's not neutral among mechanisms; it's a hard commitment to exactly one, chosen at the moment the rule is written. Wanting `FlagOverdueAccounts` to run both eagerly (react the instant a customer newly qualifies) and as a safety-net sweep (catch anything a missed event left stale) is exactly the "mix of these for the same rule" case §10 already claims is fine — and today's syntax has no way to write it at all without duplicating the entire rule body under two different names.

**Resolution:** pull triggering out of `rule` into its own top-level declaration, referencing the rule by name. A rule becomes purely a name plus a body (the effect); a `trigger` declaration is a separate, independent statement that wires a name to a way of invoking it. Zero, one, or several `trigger` declarations can point at the same rule name — the cardinality mismatch (one rule, one `on`) simply goes away, because triggering is no longer a property of the rule at all.

## 2. Does `trigger` retire prefix/postfix `on`, or does inline `on` survive as sugar?

The overwhelmingly common case — `rule SendReceipt on SettledInvoice { Receipt for invoice sentOn: now }` — has exactly one trigger and always will. Forcing every such rule to split into two lines (`rule SendReceipt { ... }` plus a standalone `trigger SendReceipt when SettledInvoice is created`) is the same shape of ceremony `example_composition_depth.md` flagged for `as`: requiring the general mechanism when nothing is actually ambiguous or plural leaks the computer layer into the logic layer for no payoff in the common case.

**Resolution: inline `on` stays, as sugar for the single-trigger case, and desugars to exactly the same underlying model a standalone `trigger` produces.**

```
rule SendReceipt on SettledInvoice {
    Receipt for invoice sentOn: now
}
```

is exactly

```
rule SendReceipt {
    Receipt for invoice sentOn: now
}
trigger SendReceipt when SettledInvoice is created
```

— not two different mechanisms, one sugared spelling of the other. Postfix `on Schedule` desugars the same way, into `trigger <RuleName> on <Schedule>`. Nothing about `## 14`/`## 15`'s existing examples needs to change in spirit, only in what they're understood to desugar to.

**Corollary — inline `on` and standalone `trigger` compose, they don't conflict.** Since every trigger for a given rule name is additive (resolved fully in #3, below), there's no ambiguity in also writing a standalone `trigger FlagOverdueAccounts on Daily` for a rule that already carries no inline `on` at all, or even (if a later, unrelated change needs to add a second path to an already-`on`-sugared rule) leaving the original inline `on` as-is and bolting on one more standalone `trigger` beside it. The two forms were never competing mechanisms — inline `on` is just a `trigger` written in a shorthand position. A rule is never required to "pick a style"; it only accumulates however many triggers it actually has, however they're spelled.

## 3. Multiple triggers for one rule — confirmed as OR, and confirmed safe

```
rule FlagOverdueAccounts {
    each FlaggedCustomer produces AccountFlag {
        AccountFlag for this basedOn: (this.invoices where OverdueInvoice) flaggedOn: now
    }
}

trigger FlagOverdueAccounts on Daily
trigger FlagOverdueAccounts when FlaggedCustomer is created
```

Two independent paths to the same effect: a `Daily` sweep, and an immediate reaction the moment some customer's own data change makes them newly a `FlaggedCustomer`. Multiple `trigger`s for one rule are a disjunction, not a conjunction — "run this rule's body whenever *any* of its triggers fire," never "wait for all of them." A rule with two triggers is not a rendezvous/join condition (`break_velle.md` #4, which is about a single firing needing evidence from two different producers before it may proceed) — it's the same effect reachable by two unrelated doors.

This composes with zero new mechanism because `produces` was already required to be safe under concurrent, independent firings (`break_velle.md` #4's "blanket obligation," `LANGUAGE.md`'s Compiled guardrails). If the `Daily` sweep and the eager event-driven path both happen to notice the same customer and both attempt to fire, `produces AccountFlag`'s guard already guarantees only one `AccountFlag` results — the same guarantee that already had to hold for two independent human writers, now covering two independent triggers on the same rule instead. Nothing about supporting multiple triggers per rule adds a *new* safety obligation; it just means an existing one (produces-is-safe-under-concurrency) gets exercised by a new kind of concurrency (trigger-level, not just writer-level).

## 4. Why `on Schedule` but `when Refinement is created` — deliberately different words, not drift

`LANGUAGE.md` §10 already establishes that prefix and postfix `on` "don't read as the same kind of thing even though both mechanically react to a shape existing" — the position difference was standing in for a real conceptual difference (reacting to a data condition vs. reacting to a schedule tick). Once triggering moves out of position-based sugar into an explicit keyword, that same distinction has to be carried by the keyword instead:

- `trigger Name on Schedule` — reacts to a named schedule tick, a thing that exists independent of any particular shape's data (`Daily`, `Hourly`).
- `trigger Name when Refinement is created` — reacts to some shape instance newly satisfying a refinement; "is created" reads this as an event (an instance becoming a member of the refined set) rather than a poll of current state, consistent with refinements already being pure, timeless predicates (§7) that something else has to notice changing.

Keeping two different keywords rather than collapsing to one (`trigger Name on X` for both) preserves the same "don't let two different kinds of thing read identically" intent §10 already committed to — just relocated from syntax position to a keyword choice.

## 5. Can a rule have zero triggers?

Not exercised by any real case yet, and not invented here. A `rule` block with no inline `on` and no standalone `trigger` pointing at it is inert — it can never fire, since nothing declares when it should. That's very likely a compile error (dead code: a rule that's structurally unreachable, the same category of thing as an unreferenced refinement might or might not be flagged as), but nothing in the docs has forced a real case that needs this decided. Flagging rather than resolving.

## Toward a formal grammar

```
ruleDecl     := "rule" Identifier ("on" RefinementName)? "{" ruleBody "}"
                -- inline "on" is sugar: "rule" Identifier "on" R "{" body "}"
                --   ≡ "rule" Identifier "{" body "}"  +  "trigger" Identifier "when" R "is" "created"

triggerDecl  := "trigger" Identifier ( "on" ScheduleList | "when" RefinementName "is" "created" )
ScheduleList := Identifier ("," Identifier)*
                -- postfix "on Schedule" / "on Daily, Hourly" after a rule body is the same sugar,
                --   desugaring to one or more "trigger" Identifier "on" Schedule declarations

ruleBody     := (effectStatement | eachProduces)+
```

A rule name may have any number of `triggerDecl`s pointing at it (inline-sugared, standalone, or both) — cardinality is additive, never conflicting, per #2–#3 above. Zero triggers is unresolved (#5).

## Not yet touched by this pass

- Whether `trigger` should also cover a "for each existing member of a refinement, once, immediately" bootstrap case (e.g. running `FlagOverdueAccounts` retroactively when the rule is first introduced against already-overdue accounts) — different question from ongoing triggering, not raised by any case here.
- `requires`, `## 12`/`## 13`'s `for`/`from` overlap, and the schedule-*definition* mechanism itself (what `Daily` actually is) are all separate, already-tracked gaps in `TODO.md` — untouched here, since none of them bear on the `trigger`/`rule` split specifically.

---

# Stress test: DRY across rule bodies (`mapping`)

A separate thread from the `trigger`/`rule` split above, sharing the same method. Two unrelated rules can need an identical effect fragment. `InitiateCharge` (reacting to a new `Order`) and a hypothetically extended `ReleaseInventory` (reacting to a `FailedCharge`) both need to write the same `AuditLogEntry`, differing only in the source expression for `order`:

```
rule InitiateCharge on Order produces ChargeAttempt for order {
    AuditLogEntry from { order: this, loggedOn: now }
    then
    ChargeAttempt from { order: this, requestedOn: now }
}

rule ReleaseInventory on FailedCharge produces InventoryRelease for order {
    AuditLogEntry from { order: this.order, loggedOn: now }
    then
    InventoryRelease from { order: this.order, releasedOn: now }
}
```

The `AuditLogEntry from { order: ..., loggedOn: now }` fragment is pure copy-paste. There's no mechanism today to name it once and reuse it. Crucially, the fix must *not* be one rule referencing another — that's forbidden as a durable design principle (rule-to-rule reference reintroduces function-call/stack semantics, exactly the computer-mechanics layer Velle exists to replace; see persistent memory `feedback_velle_no_rule_chaining`). The reusable unit has to be *smaller than a rule*. This is the long-parked **Mapping** goal — `LANGUAGE.md` §17 lists it as unfinished, and §12 already notes `produces`/`from` "is a small inline Mapping." What's never been designed is a *named, reusable* one.

## 1. Candidate — reuse §16 function-shapes (`output:`) — rejected

Reuse §16 by giving a shape an `output:` and invoking it inside a rule body:

```
shape LogAudit {
    order: one Order
    output: AuditLogEntry from { order: order, loggedOn: now }
}
```

**Rejected**, on a type-level ground (not efficiency). §16's `output` is a **derived property** (§6), and a derived property is by its own definition a recomputed *view* over facts that already exist — it can never *originate* a new, independently-identified fact. Only `rule` + a rule-body effect statement originates facts. So invoking `LogAudit` could make an `AuditLogEntry` a computable *value* reachable by traversal, but never a genuine independent fact that other rules/refinements can query directly (`exists AuditLogEntry for this`) — which is exactly what the use case needs.

An earlier framing of this rejection ("it leaves redundant rows in storage") was **wrong** and worth recording as wrong: that smuggles in an execution/storage-layer concern Velle explicitly has no opinion about (`## 1. Principles`). The real objection is purely the type-level one above.

## 2. Candidate — a `mapping` construct — favored

A named, parameterized *value template*: a record literal with holes, never itself instantiated or persisted, evaluated inline wherever referenced.

```
mapping AuditLog(order: Order) = {
    order: order
    loggedOn: now
}

rule InitiateCharge on Order produces ChargeAttempt for order {
    AuditLogEntry from AuditLog(this)
    then
    ChargeAttempt from { order: this, requestedOn: now }
}

rule ReleaseInventory on FailedCharge produces InventoryRelease for order {
    AuditLogEntry from AuditLog(this.order)
    then
    InventoryRelease from { order: this.order, releasedOn: now }
}
```

The mapping never claims to originate a fact on its own — the enclosing rule's effect statement (`AuditLogEntry from ...`) still does that. So "only `rule` + a rule-body effect creates facts" stays true without exception. It's the same category of pure, non-triggered, non-effectful thing as a derived property or `count`/`sum`, safe for the same reasons.

## 3. Why `mapping` is the right shape — it's the named form of `from { }`

The decisive framing: **a `mapping` is to `from { }` exactly what a refinement is to an inline `where`.** The language already commits to this "name-it-or-inline-it" duality twice, and `mapping` is just its missing third corner:

- a predicate is written inline (`invoices where balance > 0`) or named as a refinement (`shape OverdueInvoice = Invoice where ...`);
- §12 already states `from { }` "is what `produces` was always doing conceptually — a small inline Mapping — made visible in the syntax," and §17 parks "Mapping" as unfinished.

So `from { order: this, loggedOn: now }` **is already an anonymous mapping.** Candidate 2 isn't a new kind of thing — it names the *name-it* half of a duality whose *inline* half already ships. The grammar move is correspondingly tiny: `from` stops special-casing braces and takes *a record-valued expression*, of which the `{ }` literal and `MappingName(args)` are two forms:

```
effectStatement := ShapeName "from" recordExpr
recordExpr      := "{" (field ":" value)* "}"   -- inline literal (today)
                 | MappingName "(" args ")"       -- named mapping call (new)
```

This is a *simplification* of today's grammar (which special-cased `from { }`), not an addition — a good sign the construct is real rather than bolted on.

**This is also the crisp reason candidate 2 survives where candidate 1 failed:** a mapping has *no syntactic ability to originate a fact* — no `produces`, no trigger, no effect, no `output`, no identity. It has no way to originate a fact *at all* (`## 12` sharpens exactly where a mapping call may and may not appear); the effect statement that adopts the record originates the fact, precisely as with an inline literal today. The "only rule + produces creates facts" invariant holds **by construction**, not by argument — which is what candidate 1 (a `shape` that *looked* invocable and standalone) could never offer.

## 4. Retire inline `from { }`, or coexist? — coexist

**Resolution: coexist**, and by the same principle already applied to inline `where` vs. named refinement, and to the `as` / single-trigger ceremony rulings above (`## 2`, `## 3`). You never force someone to name a one-off. `as`/`from` retired *earlier forms* because those were worse spellings of the *same* thing; inline-vs-named is not that — it's a genuine duality where both sides earn their keep. Forcing every one-off record to be a named `mapping` would reintroduce exactly the "leak the general mechanism into the logic layer for no payoff in the common case" cost this doc has now rejected twice.

## 5. Can a mapping reference another mapping? — yes

**Resolution: yes**, the same way refinements compose (§8) and derived properties chain (§6). A mapping field's value is an ordinary `## 9` value expression, and a record-valued expression is a legal value, so a field may be `AuditLog(x)`. This isn't a bonus feature — nested mapping-in-mapping *is* the DTO→domain translation §17 originally wanted (a nested API payload becoming a nested domain record). The only constraint is the one §6 already imposes on self-referential derived properties: it must stay pure and terminating. A mapping reference cycle is that same compiler obligation, not a new language rule.

## 6. Zero params? — allowed, degenerate, unmotivated

**Resolution: allowed, no special rule either way.** `mapping DefaultAudit = { loggedOn: now }` is well-formed, and even non-constant — it reads ambient `now`, the same as any rule body or derived property does. Ambient `now` is evaluated at the *use site's* firing time, not at mapping-definition time, so two rules calling the same mapping get different `loggedOn` values — params plus ambient `now` are a mapping's only inputs (`## 11`). It simply isn't *forced* by a case, and a zero-param mapping is nearly a named constant, which Velle hasn't needed. Treat it like "can a function take zero arguments": trivially yes, nothing to design.

## 7. The boundary with §16 must be drawn explicitly

There are now *two* function-looking constructs, and a reader will ask why. The distinction is real but subtle enough that §16 and the mapping section have to point at each other and say "not this":

- a §16 **function-shape**'s `output:` is a **derived view** — it returns a *shape*, often an updated existing instance (`invoice with payments += payment`). It's a computed query over facts that already exist, and it *is* a shape (has identity, could be instantiated).
- a **mapping** returns a *bare record with no identity and no named target shape*. It becomes an `AuditLogEntry` only when a `from` adopts it. It cannot be persisted or invoked on its own.

## 8. Structural typing vs. named output shape — lean structural (flag)

`mapping AuditLog(order: Order) = { ... }` doesn't declare that it builds an `AuditLogEntry`; the `from` site's totality check (§12) does. **Leaning: structural / checked-at-use-site**, with an *optional* `:: AuditLogEntry` annotation available when you want the mapping validated independently of any use site — double colon, since single `:` is already the field/param separator (`order: Order`) — parallel to `produces ... for <field>` being inferred-but-overridable. Structural matches how inline `from { }` is already checked and is more reusable (one mapping could feed any field-compatible shape). Its cost: an unused or single-use mapping is only ever checked against that one site — the same limitation inline `from { }` has today. **Flagging, not fully resolved** — no case yet forces a mapping to be validated independent of a use site.

## 9. Extension / spread — separate, unforced (flag)

Nesting (a mapping field valued by another mapping, `## 5`) is in. But "`AuditLog` plus one extra field" — a flat *extension* like `AuditLog(this) with severity: "high"` (reusing §16's `with`) or a record spread — is a *different* composition, and no worked case forces it. Per this doc's own method, **left open** rather than invented speculatively.

## 10. Is `mapping` even forced yet? — methodology check

Recorded honestly: the forcing case is thin — one pair of rules sharing one two-field fragment. The `## 3` reframe makes `mapping` **coherent** (the missing corner of a duality the language already commits to), but coherent is not the same as **forced**, and this repo's discipline is "don't invent syntax speculatively; resolve what a worked case actually forces." So the most defensible outcome may not be "promote `mapping` to a numbered `LANGUAGE.md` section now," but "record the resolved *design* — the `from`-takes-a-record-expr generalization (`## 3`) and the §16 boundary (`## 7`) included — in §17's Mapping note, and promote it the moment a second, independent DRY case appears." Flagging the promotion decision itself as the open call.

## 11. What's in scope inside a mapping body — no `this`

The `## 1`–`## 10` pass never said what names a mapping body can see, and the answer is load-bearing. A rule body and a §6 derived property both carry an *implicit subject* — `this`, the instance being reacted to or the shape the property hangs off. A mapping has neither a trigger nor a host shape, so **a mapping body has no `this`.** Its scope is exactly its declared params plus ambient (`now`, `## 6`) — nothing else.

```
mapping AuditLog(order: Order) = {
    order: order        -- `order` is the param; there is no ambient `this` to reach
    loggedOn: now       -- `now` is the only ambient name in scope
}
```

This is precisely why `ReleaseInventory` (`## 2`) has to pass `AuditLog(this.order)` explicitly rather than the mapping fishing the order out of the triggering instance itself — the triggering instance simply isn't a name the mapping can see.

**Resolution: a mapping body's scope is its params plus ambient `now`, and nothing else — in particular, no `this`.** This isn't a limitation to apologize for; it's the structural line between a mapping and a rule. A construct with no `this` *and* no `produces` cannot be a disguised rule, which tightens `## 3`'s "safe by construction" claim from an argument into a scoping fact: there is no name in a mapping body through which it could reach out and act on the world.

## 12. Where a mapping call may appear — any record-valued position, not only `from`

`## 3` said a mapping "can only appear in the `from` slot," but `## 5` (a mapping field valued by another mapping) already contradicts that — a nested mapping call sits in a *field-value* position, not a `from` slot:

```
mapping AuditLog(order: Order) = {
    order: order
    loggedOn: now
}

mapping DetailedAudit(order: Order, reason: Text) = {
    entry: AuditLog(order)      -- a mapping call as a field value — not a `from` slot
    reason: reason
}
```

Both can't be literally true. The precise rule: **a mapping call is an ordinary record-valued value expression (§9), legal wherever a record value is legal** — as a field value inside another mapping (`## 5`), or in the `from` slot of an effect statement. What a mapping *lacks* is any way to **originate a fact**, and that ban is the absence of `produces` / trigger / identity (`## 3`, `## 11`), *not* a restriction to one syntactic slot. `## 3`'s "only appears in the `from` slot" was shorthand for the fact-origination ban and is superseded by this: a mapping call flows freely as a value; it just never *lands* as a fact except where an effect statement's `from` adopts its record.

## 13. The one-record ceiling — sharing a *block* of effects is out of scope, by design

Every case so far shares one record. What if two rules share *two* facts — say both must write an `AuditLogEntry` **and** fire a `Notification`?

```
rule InitiateCharge on Order produces ChargeAttempt for order {
    AuditLogEntry from AuditLog(this)
    Notification  from Notify(this)
    then
    ChargeAttempt from { order: this, requestedOn: now }
}

rule ReleaseInventory on FailedCharge produces InventoryRelease for order {
    AuditLogEntry from AuditLog(this.order)
    Notification  from Notify(this.order)
    then
    InventoryRelease from { order: this.order, releasedOn: now }
}
```

Each shared *record* is already named (`AuditLog`, `Notify`) — fully DRY at record granularity, one mapping per fact. What stays duplicated is the *pairing*: the fact that an audit entry and a notification always fire together. A mapping cannot name that, and **deliberately** cannot — naming a pairing means naming a sub-body of effect statements, which is rule-fragment reuse, forbidden by `feedback_velle_no_rule_chaining` (it reintroduces the call/stack semantics Velle exists to replace).

**Resolution: a mapping is exactly one record → one `from` → one fact.** Block-level sharing isn't a missing mapping feature to design later; it lands squarely in the territory the no-rule-chaining principle already excludes. That the ceiling falls exactly on the forbidden line is a confirmation the granularity is right, not a gap: the reusable-unit-smaller-than-a-rule is a *record*, and a record is a single fact's worth of data — never a sequence of effects.

## Toward a formal grammar

```
mappingDecl  := "mapping" Identifier "(" params ")" ("::" ShapeName)? "=" recordExpr
                -- params may be empty (## 6); "::" output-shape annotation optional (## 8)
params       := (Identifier ":" TypeName ("," Identifier ":" TypeName)*)?

recordExpr   := "{" (Identifier ":" valueExpr)* "}"   -- inline literal, unchanged
              | MappingName "(" args ")"                -- named mapping call
args         := (valueExpr ("," valueExpr)*)?
valueExpr    := ... (§9 value expression) | recordExpr  -- a record value (incl. a mapping call) is
                                                        --   a legal field value, so mappings nest (## 5, ## 12)

effectStatement := ShapeName "from" recordExpr         -- "from" now takes any recordExpr (## 3)
                                                        --   a mapping call originates a fact ONLY here,
                                                        --   never by appearing as a nested value (## 12)
```

A mapping is pure, non-triggered, non-effectful, and never originates a fact — it only supplies the record an effect statement's `from` adopts. Reference cycles are a compiler obligation (`## 5`), the same as self-referential derived properties (§6).

## Not yet touched by this pass

- Extension/spread of a mapping (`## 9`) — a flat "reuse plus one more field" form, unforced.
- Whether a mapping should be promoted to its own `LANGUAGE.md` section or kept as a resolved sketch in §17 until a second forcing case (`## 10`).
- Whether the optional output-shape annotation (`## 8`) is worth its weight before a case wants a mapping validated independent of use.
- Whether a mapping *param* may be `one`/`many`/refinement-typed rather than a plain shape/scalar (`params := Identifier ":" TypeName` above leaves `TypeName` unspecified) — brushed by `## 9`'s extension flag, forced by no case yet. Every worked case passes a single shape (`order: Order`).

---

# Resolved: mapping surface syntax — `from … as`, dot-access, shallow determination

`## 1`–`## 13` settled the *semantics* of `mapping` on a function-shaped surface (`mapping AuditLog(order: Order) = { order: order … }`). This thread settled the **surface**. The decisions are below; a few genuinely separate questions (call-site invocation, mapping-vs-shape naming) stay open at the end (§E).

## A. The decided surface

A mapping populates a shape declared in the ordinary way — it never redeclares one:
```
shape AuditLogEntry {
    order:    one Order
    loggedOn: Timestamp
}
```
The mapping that builds it:
```
mapping AuditLogEntry from Order {
    order
    loggedOn: now
}
```

**Header — `from <Shape> [as <name>], …`.** Each input is a shape bound to a name. `as` is *optional*: the binding name defaults to the shape name with a lowercased initial (`from Order` ⇒ `order`, `from ExchangeRate` ⇒ `exchangeRate`). `as` is *required* only to disambiguate — when two inputs would otherwise default to the same name (`from Order as buy, Order as sell`). Same "explicit only when ambiguous" rule the language uses elsewhere, now at the binding level.

**Body — dot-access, one line per target field.** A field is `field: expr`, where `expr` uses ordinary dot-access into the bindings (`order.customer`, `payment.amount`). This is the language's single traversal syntax — the same `this.x` a rule body uses — so a mapping body reads like a rule body. Operators and aggregates stay word-based (`minus`, `times`, `count of`, `the lesser of`); only property access is dots.

**`the X's y` was never syntax.** Earlier passes of this thread wrote `the Order's customer`; that was *description*, not surface. The surface is `order.customer`. (Recorded because the description-vs-syntax ambiguity actively derailed the derivation for several rounds.)

**Bare field = pass-through, and it is the *only* thing the compiler determines.** A field written bare — just `order`, no `: value` — means `order: order`, filled from the like-named binding. Bare is permitted **only** when the field name matches a binding name; that match is unambiguous, because bindings are a small flat named set. This removes the `order: order` pun with zero guessing.

**Determination is shallow — decided, not left open.** The compiler does **not** walk a binding's shape tree to fill a field from a same-named sub-field: it will *not* fill `customer` from `payment.customer`. Walking the tree would find *a* `customer` and most likely not the one intended, and a wrong *silent* guess in a spec is a latent bug. So anything past the top level of a binding is written explicitly (`customer: payment.customer`). Name-identity against the binding set is the whole of determination.

**Why `from … as` is acceptable here, though it was flagged "shape-like" earlier.** The earlier strike was that `mapping X from Order as order { order: order, customer: order.customer … }` read like a `shape` declaration. The decided form breaks the resemblance on every point: the header lists *inputs* (`from Order`), which a shape never does; values are dot-expressions, obviously values not types; pass-throughs are bare (`order` — no shape field is ever written bare); and there is not one `: Type` pair in the body. The old governing constraint (a mapping must not read as a shape declaration) is satisfied — it just needed the body to stop looking like field declarations, which dot-access + bare pass-through accomplish.

## B. One subject at scale — `OrderSnapshot`

The fat single-subject case, in the decided syntax. Target shape:
```
shape OrderSnapshot {
    order:           one Order
    customer:        one Customer
    customerName:    Text
    customerEmail:   Text
    shippingAddress: one Address
    items:           many LineItem
    itemCount:       Whole
    subtotal:        Money
    tax:             Money
    total:           Money
    currency:        Currency
    placedOn:        Timestamp
    snapshotOn:      Timestamp
    status:          Text
}
```
built from an `Order` (`customer: one Customer`, `shippingAddress: one Address`, `items: many LineItem`, plus the scalars):
```
mapping OrderSnapshot from Order {
    order
    customer:        order.customer
    customerName:    order.customer.name
    customerEmail:   order.customer.email
    shippingAddress: order.shippingAddress
    items:           order.items
    itemCount:       count of order.items
    subtotal:        order.subtotal
    tax:             order.tax
    total:           order.total
    currency:        order.currency
    placedOn:        order.placedOn
    snapshotOn:      now
    status:          "captured"
}
```

Only `order` is bare. Everything else is explicit dot-access, and `order.` repeating down the body is **accepted**, not a problem to be optimized away: shallow determination was chosen over any implicit-subject or tree-walking compression precisely because that compression can't be done unambiguously (§A). The body is a complete, predictable manifest of the target shape with the pun removed — nothing more, nothing guessed.

## C. More than one subject

**Distinct shapes — `Reconciliation` (Invoice + Payment).** Target:
```
shape Reconciliation {
    invoice:          one Invoice
    payment:          one Payment
    customer:         one Customer
    invoiceNumber:    Text
    paymentReference: Text
    method:           Text
    invoiced:         Money
    paid:             Money
    shortfall:        Money
    currency:         Currency
    dueOn:            Timestamp
    receivedOn:       Timestamp
    reconciledOn:     Timestamp
    status:           Text
}
```
built from an `Invoice` (`amount`, `currency`, `customer: one Customer`, `number`, `dueOn`) and a `Payment` (`amount`, `currency`, `reference`, `method`, `receivedOn`):
```
mapping Reconciliation from Invoice, Payment {
    invoice
    payment
    customer:         invoice.customer
    invoiceNumber:    invoice.number
    paymentReference: payment.reference
    method:           payment.method
    invoiced:         invoice.amount
    paid:             payment.amount
    shortfall:        invoice.amount minus payment.amount
    currency:         invoice.currency
    dueOn:            invoice.dueOn
    receivedOn:       payment.receivedOn
    reconciledOn:     now
    status:           "reconciled"
}
```
- distinct shapes get distinct default binding names (`invoice`, `payment`), so no `as` is needed; `invoice` and `payment` are bare, everything else is dot-access.
- the `currency` collision (both sources have one) is handled the ordinary way — write it explicitly against the intended binding (`currency: invoice.currency`). There is nothing to be ambiguous about, because shallow determination never reaches into a binding for a field in the first place (§A).
- still **reduces to a single subject** if a relationship links the two — give `Payment` an `invoice: one Invoice` and every `invoice.x` becomes `payment.invoice.x`, dropping the second input.

**Reference-data second subject — `LedgerEntry` (Payment + ExchangeRate).** The `ExchangeRate` is looked up by key (`(fromCurrency, toCurrency, asOf)` → `rate`), never a stored link on `Payment`. Target:
```
shape LedgerEntry {
    payment:          one Payment
    customer:         one Customer
    originalAmount:   Money
    originalCurrency: Currency
    rateUsed:         Decimal
    rateAsOf:         Date
    baseAmount:       Money
    baseCurrency:     Currency
    convertedOn:      Timestamp
}
```
built from both, and originated in a rule:
```
mapping LedgerEntry from Payment, ExchangeRate {
    payment
    customer:         payment.customer
    originalAmount:   payment.amount
    originalCurrency: payment.currency
    rateUsed:         exchangeRate.rate
    rateAsOf:         exchangeRate.asOf
    baseAmount:       payment.amount times exchangeRate.rate
    baseCurrency:     exchangeRate.toCurrency
    convertedOn:      now
}

rule RecordInBaseCurrency on SettledPayment produces LedgerEntry for payment {
    -- call-site invocation syntax is still open (§E); provisional:
    LedgerEntry from this, (the ExchangeRate for this.currency)
    ...
}
```
The second subject arrives by a **lookup** (`the ExchangeRate for this.currency`), not a traversal (`this.exchangeRate` doesn't and shouldn't exist), and `baseAmount` reads from both, so neither input alone can produce it. Whether such a case is genuinely *forced* or only *clearer* than inlining the lookup is still open (§E).

**Same shape — `TradeMatch` (Order + Order).** Target:
```
shape TradeMatch {
    buy:       one Order
    sell:      one Order
    price:     Money
    quantity:  Whole
    matchedOn: Timestamp
}
```
Two `Order`s with no link between them force `as`, because the default binding name would collide:
```
mapping TradeMatch from Order as buy, Order as sell {
    buy
    sell
    price:     sell.price
    quantity:  the lesser of buy.quantity and sell.quantity
    matchedOn: now
}
```
`buy` and `sell` are the required aliases; both are then bare pass-throughs, and the rest is dot-access off them. This is the sole case where `as` is not optional — exactly the ambiguity the optional-`as` rule (§A) reserves it for.

## D. The determination rule, stated once

- **bare `field`** ⇒ `field: field`, permitted **iff** `field` names a binding. This is the *only* thing the compiler determines.
- **everything else** is explicit `field: expr`, dot-access into the bindings.
- **deep / tree-walking determination is rejected** — filling `customer` from `payment.customer` would make the compiler *guess* which reachable `customer` was meant, and a wrong silent guess in a spec is a latent bug (§A).
- **`## 9`'s spread is likewise rejected, not subsumed** — "auto-carry same-named sub-fields" *is* the deep case, and dies for the same reason. Spread stops being a candidate feature.
- **`## 11`'s "no `this`" stands** — a mapping still has no `this`; its inputs are named bindings reached by dot-access, never an implicit subject.
- **totality (`## 12`) holds** — every target field appears in the body, bare or explicit; a missing field is a compile error ("nothing fills field X"). The body is therefore a complete manifest of the target shape, with pass-throughs abbreviated to bare. (One small remaining sub-question: whether a binding-name-match field may be *omitted entirely* rather than written bare. Kept bare-and-listed here, pending a reason to allow omission.)
- **match strictness (leaning, not stress-tested):** a bare/name match must also type-check; a same-name/incompatible-type pairing is a compile error, not a silent skip. Untested against refinement-typed fields.

## E. Still open (separate questions)

- **Call-site invocation.** How a rule invokes a mapping and supplies its inputs — the provisional `LedgerEntry from this, (the ExchangeRate for this.currency)` in §C is not settled. This is the next thing to work.
- **Mapping-vs-shape naming.** These examples assume a mapping **shares the name of the shape it builds** (`mapping LedgerEntry` → a `LedgerEntry`). The alternative — a separately-named mapping feeding a differently-named shape's slot, as `## 1`–`## 13` had it (`AuditLogEntry from AuditLog(this)`) — is not yet ruled out. This blocks sweeping `## 1`–`## 13` onto the decided surface.
- **Multi-subject: forced or only clearer.** The lookup-vs-traversal criterion for a legit second subject (§C) still isn't pinned to "forced."
- **Sweep of `## 1`–`## 13`.** Those examples still use the function surface and lack target-shape declarations; they can move to `from … as` once the naming question above is settled.
