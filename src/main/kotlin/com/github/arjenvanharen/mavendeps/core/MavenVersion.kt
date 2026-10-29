package com.github.arjenvanharen.mavendeps.core

import java.math.BigInteger

/**
 * Maven version ordering, following `ComparableVersion` in its common cases: numbers compare numerically, and
 * qualifiers order as alpha < beta < milestone < rc < snapshot < release (ga, final) < sp < anything else.
 * Trailing zeros and release qualifiers are insignificant, so `1.0 == 1.0.0 == 1-final`.
 */
class MavenVersion(val text: String) : Comparable<MavenVersion> {
    private sealed interface Item
    private data class Num(val value: BigInteger) : Item
    private data class Qualifier(val value: String) : Item

    private val items: List<Item> = parse(text.trim().lowercase())

    /** Alpha, beta, milestone, rc or snapshot. */
    val isPreRelease: Boolean get() = items.any { it is Qualifier && rank(it.value) < RELEASE }

    override fun compareTo(other: MavenVersion): Int {
        for (i in 0 until maxOf(items.size, other.items.size)) {
            val c = compare(items.getOrNull(i), other.items.getOrNull(i))
            if (c != 0) return c
        }
        return 0
    }

    override fun equals(other: Any?) = other is MavenVersion && compareTo(other) == 0

    override fun hashCode() = items.dropLastWhile { it == ZERO || (it is Qualifier && rank(it.value) == RELEASE) }.hashCode()

    override fun toString() = text

    companion object {
        private val ZERO = Num(BigInteger.ZERO)
        private const val RELEASE = 5
        private val ranks = mapOf("alpha" to 0, "beta" to 1, "milestone" to 2, "rc" to 3, "snapshot" to 4, "" to RELEASE, "sp" to 6)
        private val aliases = mapOf("a" to "alpha", "b" to "beta", "m" to "milestone", "cr" to "rc", "ga" to "", "final" to "", "release" to "")

        private fun rank(q: String) = ranks[q] ?: 7

        private fun parse(s: String): List<Item> {
            val out = ArrayList<Item>()
            val token = StringBuilder()
            fun flush() {
                if (token.isEmpty()) return
                val t = token.toString()
                if (t[0].isDigit()) out += Num(BigInteger(t))
                // Release qualifiers (ga, final, release) mean nothing: 1-final == 1.
                else (aliases[t] ?: t).takeIf { it.isNotEmpty() }?.let { out += Qualifier(it) }
                token.clear()
            }
            for (c in s) {
                when {
                    c == '.' || c == '-' || c == '_' -> flush()
                    // A switch between digits and letters separates items too: 1rc2 -> 1, rc, 2.
                    token.isNotEmpty() && c.isDigit() != token[0].isDigit() -> { flush(); token.append(c) }
                    else -> token.append(c)
                }
            }
            flush()
            return out
        }

        /** A missing item counts as 0 or as a release qualifier, so padding never changes the order. */
        private fun compare(a: Item?, b: Item?): Int = when {
            a == null && b == null -> 0
            a == null -> -compare(b, null)
            a is Num -> when (b) {
                null -> a.value.signum()
                is Num -> a.value.compareTo(b.value)
                is Qualifier -> 1
            }
            a is Qualifier -> when (b) {
                null -> rank(a.value).compareTo(RELEASE)
                is Num -> -1
                is Qualifier -> rank(a.value).compareTo(rank(b.value)).let { if (it == 0 && rank(a.value) == 7) a.value.compareTo(b.value) else it }
            }
            else -> 0
        }

        val COMPARATOR: Comparator<String> = Comparator { a, b -> MavenVersion(a).compareTo(MavenVersion(b)) }
    }
}
