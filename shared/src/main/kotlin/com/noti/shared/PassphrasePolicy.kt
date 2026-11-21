package com.noti.shared

/**
 * What a new backup passphrase must be. The backup holds the pairing keys (enough to command the
 * companion's SIM), and its key derivation is only as strong as the passphrase. Length alone let
 * `111111111111` through, which any guessing tool tries in its first second.
 *
 * Applied only when a passphrase is set - restoring accepts whatever an older backup was made with.
 */
object PassphrasePolicy {

    const val MIN_LENGTH = 16
    const val MIN_DISTINCT = 6
    private const val MAX_SAME_RUN = 3
    private const val SEQUENCE_LEN = 5
    private val KEYBOARD_ROWS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm", "1234567890")

    enum class Problem { TOO_SHORT, TOO_FEW_DISTINCT, REPEATS, SEQUENCE }

    /** The first rule [pass] breaks, or null when it's acceptable. Pure. */
    fun problem(pass: String): Problem? {
        val p = pass.lowercase()
        return when {
            pass.length < MIN_LENGTH -> Problem.TOO_SHORT
            p.toSet().size < MIN_DISTINCT -> Problem.TOO_FEW_DISTINCT
            hasLongRun(p) || isRepeatedUnit(p) -> Problem.REPEATS
            hasSequence(p) -> Problem.SEQUENCE
            else -> null
        }
    }

    /** "aaaa": the same character more than [MAX_SAME_RUN] times in a row. */
    private fun hasLongRun(p: String): Boolean {
        var run = 1
        for (i in 1 until p.length) {
            run = if (p[i] == p[i - 1]) run + 1 else 1
            if (run > MAX_SAME_RUN) return true
        }
        return false
    }

    /** "abcabcabcabcabca": one short chunk over and over. */
    private fun isRepeatedUnit(p: String): Boolean =
        (1..p.length / 2).any { k -> p.indices.all { p[it] == p[it % k] } }

    /** "abcde", "54321", "qwert", "lkjhg": a run along the alphabet, the digits or a keyboard row. */
    private fun hasSequence(p: String): Boolean {
        for (i in 0..p.length - SEQUENCE_LEN) {
            val w = p.substring(i, i + SEQUENCE_LEN)
            if (w.all { it.isLetterOrDigit() }) {
                val steps = w.zipWithNext { a, b -> b - a }
                if (steps.all { it == 1 } || steps.all { it == -1 }) return true
            }
            if (KEYBOARD_ROWS.any { it.contains(w) || it.reversed().contains(w) }) return true
        }
        return false
    }
}
