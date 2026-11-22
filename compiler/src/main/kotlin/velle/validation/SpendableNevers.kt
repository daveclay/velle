package velle.validation

import velle.*

// ── spendable nevers: prohibitions a proof may rely on ───────────────────
//
// A `never` is spendable when nothing the rules do can change its verdict:
// its predicate is attributable, reads no field any rule writes, and
// consults no shape other than its own that any rule deletes. A proof may
// then take the prohibition as holding in every state.

internal fun Validator.findSpendableNevers(): List<NeverDecl> {
    val writtenFields = writes.map { it.owner to it.member.name }.toSet()
    return model.nevers.filter { never -> isSpendable(never, writtenFields) }
}

private fun Validator.isSpendable(never: NeverDecl, writtenFields: Set<Pair<String, String>>): Boolean {
    val reads = model.summaryOfRefExpr(never.target)
    // deleters join the proof's state changes (OQ37): deleting the never's
    // own base only shrinks the forbidden set, but a deleter of any other
    // consulted shape can flip membership either way — not spendable
    // a never over a union or a taken shape (Reference §9) is over each operand or taker
    val ownBases = model.basesOfExpr(never.target)
    val foreignConsults = reads.touchedShapes().flatMap { model.basesOf(it) }.toSet() - ownBases
    return !reads.opaque && reads.fields.none { it in writtenFields } &&
        foreignConsults.none { it in model.deletedShapes }
}

/**
 * Does a spendable `never (S where hop == this)` hold — no instance of
 * [shape]'s [hop] relationship ever points back at its own instance? The
 * proof that a self-read's chain ends (checks.md V14) and the aliasing
 * discharge (checks.md V1/V15). The predicate must be exactly the one
 * equality over the whole base shape: a refinement scope or an extra
 * conjunct narrows what the invariant forbids, which weakens it below
 * what the proof needs — fail closed on those.
 */
internal fun Validator.neverForbidsSelfReference(shape: String, hop: String, prohibitions: List<NeverDecl> = spendableNevers): Boolean =
    prohibitions.any { never ->
        val target = never.target as? RefName ?: return@any false
        if (target.name != shape) return@any false
        val equality = target.where as? Binary ?: return@any false
        equality.op == "==" && isEqualityOfHopAndThis(equality, hop)
    }

private fun isEqualityOfHopAndThis(equality: Binary, hop: String): Boolean {
    val thisPath = PathExpr("this")
    val hopPath = PathExpr(hop)
    return (equality.left == hopPath && equality.right == thisPath) ||
        (equality.left == thisPath && equality.right == hopPath)
}
