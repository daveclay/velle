# Velle

## Writing for the wiki

`wiki/` is the documentation Velle authors read. These rules apply to every
page in it. They do not apply to `working-docs/`, which is written for Dave.

### The reader

The reader is a backend engineer or a product owner who has never seen Velle.
They have the grammar page open in another tab and want to write a spec for
their own system. They do not know compiler theory or programming-language
theory. They will not read any page twice. If a sentence would not help them
write Velle today, cut it.

### Page shape

Every Reference page, in this order:

1. One paragraph naming the concept the page teaches, in plain words, and
   what the reader can do with it afterwards. The concept comes before any
   example: a reader should be able to say what the page is about without
   having seen the code. Do not open with a summary of the examples.
2. The business situation that calls for it, in words a product owner would say.
3. The Velle code, before any explanation of it.
4. What each line does, in order.
5. What the compiler checks, stated as "the compiler rejects X when Y" or
   "the compiler warns when Y", with a link to the check in `checks.md`.
6. Links to related pages.

Write a page from an outline of what it teaches, not by editing the old
paragraphs. The old page is source material for facts and code only.

### Words

- Every noun is a Velle keyword, an everyday English word, or a link to its
  definition in `GLOSSARY.md` or the page that defines it. No other nouns.
  Rewrite rather than link when the term is only used once.
- The first use of a Velle construct on a page links to the Reference page
  that defines it, as `[§N](Reference-NN-name)`.
- Prefer the business word to the mechanism: "the flag is set again the next
  night" rather than "the latch re-arms".
- No compiler-theory vocabulary in prose: reify, discharge, witness, latch,
  rung, sugar, desugar, obligation, monotone, idempotent. If the idea is
  needed, say it in a sentence: "running the rule twice changes nothing."
- Explain what Velle does for the reader, not how the compiler or runtime
  does it inside. Internal mechanism goes in `evaluation.md` or `checks.md`.

### Sentences

- One idea per sentence, about 20 words. Code before abstraction.
- No em-dashes. Start a new sentence instead.
- Every "this", "the same", "again", "below", "as before" must point at
  something already on the page. The first sentence of a page points at nothing.
- Every mention of example code names the file it comes from, as
  `examples/<dir>/<name>.velle`.

### What never appears on a wiki page

- Design history: what was decided, when, by whom, what was settled, what
  survived scrutiny, what is untested, what is deferred. Move it to
  `working-docs/` or delete it.
- Open-question tags (OQnn) or dates of decisions.
- Arguments for why Velle has no construct for something. State what to
  write instead and stop.

### Checking a draft

Before finishing, read the page as the reader described above and list every
sentence where a word's referent is unknown, or where the reader could not
say what they would type. Fix each one. Only then report the page as done.
