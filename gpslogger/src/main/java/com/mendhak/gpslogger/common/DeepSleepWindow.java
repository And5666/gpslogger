package com.mendhak.gpslogger.common;

/**
 * Time-of-day maths for smart mode's deep sleep window, e.g. 23:00 to 07:00.
 * Times are minutes after midnight. The window can cross midnight.
 * Kept free of Android classes so it can be unit tested.
 */
public final class DeepSleepWindow {

    static final int SECONDS_PER_DAY = 24 * 60 * 60;

    private DeepSleepWindow() { }

    /**
     * Whether the time is inside the window. The start is inside and the end isn't.
     * A window that starts and ends at the same time is empty.
     */
    public static boolean contains(int startMinute, int endMinute, int nowSecondOfDay) {
        int start = startMinute * 60;
        int end = endMinute * 60;
        if (start == end) {
            return false;
        }
        if (start < end) {
            return nowSecondOfDay >= start && nowSecondOfDay < end;
        }
        return nowSecondOfDay >= start || nowSecondOfDay < end;
    }

    /** Seconds from now until the next time the clock reads the given minute, 1 to 86400. */
    public static int secondsUntil(int targetMinute, int nowSecondOfDay) {
        // Not Math.floorMod, that needs API 24
        int diff = ((targetMinute * 60 - nowSecondOfDay) % SECONDS_PER_DAY + SECONDS_PER_DAY) % SECONDS_PER_DAY;
        return diff == 0 ? SECONDS_PER_DAY : diff;
    }

    /**
     * The deep sleep interval, cut short so a check happens when the window ends.
     * A few seconds are added so that check falls just after the end, outside the window.
     */
    public static int intervalSeconds(int intervalMinutes, int endMinute, int nowSecondOfDay) {
        return Math.min(intervalMinutes * 60, secondsUntil(endMinute, nowSecondOfDay) + 5);
    }

    public static String format(int minuteOfDay) {
        return String.format(java.util.Locale.ROOT, "%02d:%02d", minuteOfDay / 60, minuteOfDay % 60);
    }
}
