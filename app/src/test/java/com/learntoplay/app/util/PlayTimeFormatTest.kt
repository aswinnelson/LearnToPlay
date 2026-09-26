package com.learntoplay.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayTimeFormatTest {

    @Test
    fun zeroOrNegative_isNoPlayTime() {
        assertEquals("no play time", PlayTimeFormat.describe(0))
        assertEquals("no play time", PlayTimeFormat.describe(-5))
    }

    @Test
    fun underAMinute_isNotShownAsZeroMinutes() {
        assertEquals("less than a minute", PlayTimeFormat.describe(1))
        assertEquals("less than a minute", PlayTimeFormat.describe(59))
    }

    @Test
    fun oneMinute_isSingular() {
        assertEquals("1 minute", PlayTimeFormat.describe(60))
        assertEquals("1 minute", PlayTimeFormat.describe(119))
    }

    @Test
    fun severalMinutes_roundsDown() {
        assertEquals("2 minutes", PlayTimeFormat.describe(120))
        assertEquals("45 minutes", PlayTimeFormat.describe(45 * 60 + 59))
    }
}
