package com.learntoplay.app.util

/**
 * Child-facing wording for a time-bank balance. Pure and dependency-free so it's plain-JUnit
 * testable (see PlayTimeFormatTest).
 */
object PlayTimeFormat {

    /** "less than a minute" / "1 minute" / "25 minutes" — whole minutes rounded down, except
     * that a leftover balance under a minute reads as "less than a minute" instead of the old,
     * misleading "0 minutes" (apps are still unlocked at that point). */
    fun describe(seconds: Long): String = when {
        seconds <= 0L -> "no play time"
        seconds < 60L -> "less than a minute"
        seconds < 120L -> "1 minute"
        else -> "${seconds / 60L} minutes"
    }
}
