# OQ30 — Author-named `many` fields: the commit story

**Status:** open — decide: add syntax, or make the restriction official
**In plain terms:** no syntax lets a committer supply a collection value, so declared `many` fields can't be committed — is that a missing construct or the rule?

---

Author-named `many` fields have no commit story — F4 totality would demand a committer-supplied collection, and no syntax provides one; inferred inverses are the only working spelling. Same family: a collection-valued field init from a filtered traversal (`basedOn: (this.invoices where OverdueInvoice)`, from the retired `example_rules.md`) appears in no grammar production.

Blocks the declared-`many` line of small-construct coverage (TODO.md).
