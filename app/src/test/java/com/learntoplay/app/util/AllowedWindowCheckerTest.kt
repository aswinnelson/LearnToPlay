package com.learntoplay.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain-JVM tests for [AllowedWindowChecker] — the Allowed Hours curfew math that both
 * AppLockAccessibilityService (show the lock) and TimeBankTrackerService (stop spending) rely
 * on. The midnight-crossing case is the one most worth pinning down: a naive range check
 * silently turns a 10 PM - 7 AM window into "never allowed".
 */
class AllowedWindowCheckerTest {

    private fun min(hour: Int, minute: Int = 0) = hour * 60 + minute

    // --- Same-day window, e.g. 4:00 PM - 6:00 PM ---

    @Test
    fun sameDayWindow_insideIsAllowed() {
        assertTrue(AllowedWindowChecker.isWithinWindow(min(17), min(16), min(18)))
    }

    @Test
    fun sameDayWindow_startIsInclusive() {
        assertTrue(AllowedWindowChecker.isWithinWindow(min(16), min(16), min(18)))
    }

    @Test
    fun sameDayWindow_endIsExclusive() {
        assertFalse(AllowedWindowChecker.isWithinWindow(min(18), min(16), min(18)))
        assertTrue(AllowedWindowChecker.isWithinWindow(min(17, 59), min(16), min(18)))
    }

    @Test
    fun sameDayWindow_beforeAndAfterAreLocked() {
        assertFalse(AllowedWindowChecker.isWithinWindow(min(15, 59), min(16), min(18)))
        assertFalse(AllowedWindowChecker.isWithinWindow(min(23), min(16), min(18)))
        assertFalse(AllowedWindowChecker.isWithinWindow(0, min(16), min(18)))
    }

    // --- Window crossing midnight, e.g. 10:00 PM - 7:00 AM ---

    @Test
    fun overnightWindow_lateEveningIsAllowed() {
        assertTrue(AllowedWindowChecker.isWithinWindow(min(22), min(22), min(7)))
        assertTrue(AllowedWindowChecker.isWithinWindow(min(23, 59), min(22), min(7)))
    }

    @Test
    fun overnightWindow_afterMidnightIsAllowed() {
        assertTrue(AllowedWindowChecker.isWithinWindow(0, min(22), min(7)))
        assertTrue(AllowedWindowChecker.isWithinWindow(min(6, 59), min(22), min(7)))
    }

    @Test
    fun overnightWindow_daytimeIsLocked() {
        assertFalse(AllowedWindowChecker.isWithinWindow(min(7), min(22), min(7)))
        assertFalse(AllowedWindowChecker.isWithinWindow(min(12), min(22), min(7)))
        assertFalse(AllowedWindowChecker.isWithinWindow(min(21, 59), min(22), min(7)))
    }

    // --- Degenerate window ---

    @Test
    fun equalStartAndEnd_isNeverAllowed() {
        // The Admin Dashboard warns the parent about this; the checker treats it as an empty
        // window (locked all day) rather than guessing "always open".
        for (t in listOf(0, min(9), min(9, 1), min(23, 59))) {
            assertFalse(AllowedWindowChecker.isWithinWindow(t, min(9), min(9)))
        }
    }

    // --- 12-hour formatting ---

    @Test
    fun format12Hour_midnightAndNoon() {
        assertEquals("12:00 AM", AllowedWindowChecker.format12Hour(0))
        assertEquals("12:00 PM", AllowedWindowChecker.format12Hour(min(12)))
    }

    @Test
    fun format12Hour_regularTimes() {
        assertEquals("8:00 AM", AllowedWindowChecker.format12Hour(min(8)))
        assertEquals("1:05 PM", AllowedWindowChecker.format12Hour(min(13, 5)))
        assertEquals("11:59 PM", AllowedWindowChecker.format12Hour(min(23, 59)))
    }

    @Test
    fun format12Hour_wrapsOutOfRangeValues() {
        assertEquals("12:00 AM", AllowedWindowChecker.format12Hour(24 * 60))
        assertEquals("11:59 PM", AllowedWindowChecker.format12Hour(-1))
    }
}
