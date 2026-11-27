package com.mendhak.gpslogger.widgets;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * What the widgets show that isn't a setting: the last Dawarich upload result and the cached
 * stats. Kept in its own file so it never ends up in a profile.
 */
public final class WidgetState {

    private static final String FILE = "gpslogger_widgets";
    private static final String LAST_SENT = "dawarich_last_sent";
    private static final String LAST_FAILED = "dawarich_last_failed";
    private static final String STATS = "dawarich_stats";
    private static final String STATS_ERROR = "dawarich_stats_error";
    private static final String STATS_ERROR_TIME = "dawarich_stats_error_time";

    private WidgetState() { }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static void setUploadSucceeded(Context context) {
        prefs(context).edit().putLong(LAST_SENT, System.currentTimeMillis()).remove(LAST_FAILED).apply();
    }

    public static void setUploadFailed(Context context) {
        prefs(context).edit().putLong(LAST_FAILED, System.currentTimeMillis()).apply();
    }

    public static long getLastSent(Context context) {
        return prefs(context).getLong(LAST_SENT, 0);
    }

    /** When the last upload failed, or 0 if the last upload worked */
    public static long getLastFailed(Context context) {
        return prefs(context).getLong(LAST_FAILED, 0);
    }

    static void setStats(Context context, DawarichWeekStats stats) {
        prefs(context).edit().putString(STATS, stats.toJson()).remove(STATS_ERROR).remove(STATS_ERROR_TIME).apply();
    }

    static DawarichWeekStats getStats(Context context) {
        return DawarichWeekStats.fromJson(prefs(context).getString(STATS, null));
    }

    static void setStatsError(Context context, String message) {
        prefs(context).edit().putString(STATS_ERROR, message).putLong(STATS_ERROR_TIME, System.currentTimeMillis()).apply();
    }

    static String getStatsError(Context context) {
        return prefs(context).getString(STATS_ERROR, null);
    }
}
