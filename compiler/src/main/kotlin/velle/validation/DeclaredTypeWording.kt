package velle.validation

// ── how each site words a declared type against its value (Reference §7, §12, §14; checks F2) ──
//
// [checkDeclaredType] asks one question at four sites: a derived or captured
// property, an `initially`, a `from` block, and an assignment. Each site says
// the answer in its own verb.

/** How a site words the declared type against the expression's: a derived property computes, an
 *  `initially` starts, a `from` block supplies, an assignment assigns. */
internal class Wording(
    val mismatch: (site: String, field: String, declared: String, actual: String, actualSpelled: String) -> String,
    val absent: (site: String, field: String, declared: String, optionalSpelled: String) -> String,
    val none: (site: String, field: String, declared: String, optionalSpelled: String) -> String,
    /** How the site refuses a selection no test shows to match (Reference §5, §7), the last two
     *  arguments being the selection and the test that would show it matches: a derived or
     *  captured property and an `initially` refuse one, each computing its value where no rule's
     *  condition can show the selection matches. Null where the site takes the selection and a
     *  miss is the runtime's to refuse, a `from` block and an assignment (Reference §14). */
    val unshownSelection: ((site: String, field: String, declared: String, optionalSpelled: String, selection: String, test: String) -> String)? = null,
)

internal val COMPUTED = Wording(
    { site, field, declared, actual, spelled -> "$site is declared $declared and computed as $actual; declare it '$field: $spelled', or compute $declared" },
    { site, field, declared, spelled -> "$site is declared $declared and may be absent; declare it '$field: $spelled', or test the value with 'is some' first" },
    { site, field, declared, spelled -> "$site is declared $declared and computed as none; declare it '$field: $spelled'" },
    { site, field, declared, spelled, selection, test -> "$site is declared $declared and may be absent: '$selection' may match nothing; declare it '$field: $spelled', " +
        "or compute it where a test shows the selection matches, such as '$test' (Reference §10)" },
)
internal val INITIALLY = Wording(
    { site, field, declared, actual, spelled -> "$site is declared $declared and starts as $actual; declare it '$field: $spelled', or start it with $declared" },
    { site, field, declared, spelled -> "$site is declared $declared and may start absent; declare it '$field: $spelled'" },
    { site, field, declared, spelled -> "$site is declared $declared and starts as none; declare it '$field: $spelled'" },
    { site, field, declared, spelled, selection, test -> "$site is declared $declared and may start absent: '$selection' may match nothing; declare it '$field: $spelled', " +
        "or start it where a test shows the selection matches, such as '$test' (Reference §10)" },
)
internal val SUPPLIED = Wording(
    { site, _, declared, actual, _ -> "$site — declared $declared, given $actual; supply $declared" },
    { site, field, declared, spelled -> "$site — declared $declared, given a value that may be absent; test it with 'is some' first, or declare the field '$field: $spelled'" },
    { site, field, declared, spelled -> "$site — declared $declared, given none; declare the field '$field: $spelled'" },
)
internal val ASSIGNED = Wording(
    { site, _, declared, actual, _ -> "$site — declared $declared, given $actual; assign $declared" },
    { site, field, declared, spelled -> "$site — declared $declared, given a value that may be absent; test it with 'is some' first, or declare the field '$field: $spelled'" },
    { site, field, declared, spelled -> "$site — declared $declared, given none; declare the field '$field: $spelled'" },
)
