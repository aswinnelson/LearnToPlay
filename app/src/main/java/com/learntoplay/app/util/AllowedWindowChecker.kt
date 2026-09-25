package com.learntoplay.app.util

import java.util.Calendar
import java.util.Locale

/**
 * Pure logic for the "Allowed Hours" schedule (an optional curfew on top of the time-bank
 * mechanic — see AdminSettingsEntity.allowedWindow* fields, AppLockAccessibilityService, and
 * TimeBankTrackerService). Kept free of Android/Room dependencies (besides Calendar) so it's
 * trivially unit-testable.
 */
object AllowedWindowChecker {

    /**
     * True if [nowMinuteOfDay] (0-1439) falls inside `[startMinute, endMinute)`. Handles a
     * window that crosses midnight (startMinute > endMinute, e.g. a 22:00-06:00 overnight
     * curfew) by treating it as "from start through midnight to end", rather than the
     * always-false empty range a naive `in startMinute until endMinute` would give.
     */
    fun isWithinWindow(nowMinuteOfDay: Int, startMinute: Int, endMinute: Int): Boolean =
        if (startMinute <= endMinute) {
            nowMinuteOfDay in startMinute until endMinute
        } else {
            nowMinuteOfDay >= startMinute || nowMinuteOfDay < endMinute
        }

    /** Current local wall-clock time as minutes since midnight (0-1439) — the same unit the
     * schedule itself is stored in, so no timezone/DST conversion is needed at the call site. */
    fun currentMinuteOfDay(): Int {
        val cal = Calendar.getInstance()
        return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    }

    /** "4:00 PM" style formatting for the Admin Dashboard's time-picker buttons and the
     * outside-hours overlay message. Written by hand (no SimpleDateFormat/Calendar round-trip)
     * since a minute-of-day int is all there is to format — no date, locale-specific calendar
     * fields, or timezone involved. */
    fun format12Hour(minuteOfDay: Int): String {
        val clamped = minuteOfDay.mod(24 * 60)
        val hour24 = clamped / 60
        val minute = clamped % 60
        val amPm = if (hour24 < 12) "AM" else "PM"
        val hour12 = when (val h = hour24 % 12) { 0 -> 12; else -> h }
        return String.format(Locale.US, "%d:%02d %s", hour12, minute, amPm)
    }
}
