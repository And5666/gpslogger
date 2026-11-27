package com.mendhak.gpslogger.widgets;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.Locale;
import java.util.Map;

/**
 * The last seven days of Dawarich stats shown on the stats widget, and the parsing of
 * Dawarich's {@code GET /api/v1/insights} response it comes from.
 * <p>
 * {@code activityHeatmap.dailyData} maps "yyyy-MM-dd" to the metres travelled that day.
 * Dawarich works these out from its monthly stats, which it updates in the background, so
 * today's distance can lag behind the points that have been sent.
 */
public final class DawarichWeekStats {

    public static final int DAYS = 7;
    static final double METERS_PER_MILE = 1609.344;

    /** Metres per day, oldest first, ending today */
    public final long[] dailyMeters;
    /** "yyyy-MM-dd" for each entry of dailyMeters */
    public final String[] dates;
    public final int currentStreak;
    /** "km" or "mi", from the user's Dawarich setting */
    public final String distanceUnit;
    public final long pointsToday;
    public final long pointsWeek;
    public final long fetchedAt;

    public DawarichWeekStats(long[] dailyMeters, String[] dates, int currentStreak, String distanceUnit,
                             long pointsToday, long pointsWeek, long fetchedAt) {
        this.dailyMeters = dailyMeters;
        this.dates = dates;
        this.currentStreak = currentStreak;
        this.distanceUnit = distanceUnit;
        this.pointsToday = pointsToday;
        this.pointsWeek = pointsWeek;
        this.fetchedAt = fetchedAt;
    }

    /** The seven dates ending with today, oldest first */
    public static String[] lastSevenDates(Calendar today) {
        String[] dates = new String[DAYS];
        Calendar c = (Calendar) today.clone();
        c.add(Calendar.DAY_OF_MONTH, -(DAYS - 1));
        for (int i = 0; i < DAYS; i++) {
            dates[i] = String.format(Locale.ROOT, "%04d-%02d-%02d",
                    c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
            c.add(Calendar.DAY_OF_MONTH, 1);
        }
        return dates;
    }

    /** Adds the insights response's daily distances into the map, keyed by date */
    static void readDailyData(JSONObject insights, Map<String, Long> into) throws JSONException {
        JSONObject heatmap = insights.optJSONObject("activityHeatmap");
        if (heatmap == null) {
            return;
        }
        JSONObject daily = heatmap.optJSONObject("dailyData");
        if (daily == null) {
            return;
        }
        JSONArray names = daily.names();
        if (names == null) {
            return;
        }
        for (int i = 0; i < names.length(); i++) {
            String date = names.getString(i);
            Long previous = into.get(date);
            into.put(date, (previous == null ? 0 : previous) + Math.max(0, daily.optLong(date, 0)));
        }
    }

    static int readCurrentStreak(JSONObject insights) {
        JSONObject heatmap = insights.optJSONObject("activityHeatmap");
        return heatmap == null ? 0 : heatmap.optInt("currentStreak", 0);
    }

    static String readDistanceUnit(JSONObject insights) {
        JSONObject totals = insights.optJSONObject("totals");
        String unit = totals == null ? null : totals.optString("distanceUnit", null);
        return "mi".equalsIgnoreCase(unit) ? "mi" : "km";
    }

    public long weekMeters() {
        long total = 0;
        for (long m : dailyMeters) {
            total += m;
        }
        return total;
    }

    public long todayMeters() {
        return dailyMeters[DAYS - 1];
    }

    public double toUnit(long meters) {
        return "mi".equals(distanceUnit) ? meters / METERS_PER_MILE : meters / 1000.0;
    }

    /** e.g. "42.3 km", or "128 km" once it's 100 or more */
    public String formatDistance(long meters) {
        double value = toUnit(meters);
        String number = value >= 100 ? String.format(Locale.getDefault(), "%,.0f", value)
                : String.format(Locale.getDefault(), "%.1f", value);
        return number + " " + distanceUnit;
    }

    public String toJson() {
        try {
            JSONObject o = new JSONObject();
            JSONArray m = new JSONArray();
            JSONArray d = new JSONArray();
            for (int i = 0; i < DAYS; i++) {
                m.put(dailyMeters[i]);
                d.put(dates[i]);
            }
            o.put("dailyMeters", m);
            o.put("dates", d);
            o.put("currentStreak", currentStreak);
            o.put("distanceUnit", distanceUnit);
            o.put("pointsToday", pointsToday);
            o.put("pointsWeek", pointsWeek);
            o.put("fetchedAt", fetchedAt);
            return o.toString();
        } catch (JSONException e) {
            return null;
        }
    }

    public static DawarichWeekStats fromJson(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            JSONObject o = new JSONObject(json);
            JSONArray m = o.getJSONArray("dailyMeters");
            JSONArray d = o.getJSONArray("dates");
            if (m.length() != DAYS || d.length() != DAYS) {
                return null;
            }
            long[] meters = new long[DAYS];
            String[] dates = new String[DAYS];
            for (int i = 0; i < DAYS; i++) {
                meters[i] = m.getLong(i);
                dates[i] = d.getString(i);
            }
            return new DawarichWeekStats(meters, dates, o.optInt("currentStreak", 0),
                    o.optString("distanceUnit", "km"), o.optLong("pointsToday", -1),
                    o.optLong("pointsWeek", -1), o.optLong("fetchedAt", 0));
        } catch (JSONException e) {
            return null;
        }
    }

    /**
     * Builds the week from one or two insights responses (two when the week started last year).
     */
    public static DawarichWeekStats fromInsights(Calendar today, JSONObject thisYear, JSONObject lastYearOrNull,
                                                 long pointsToday, long pointsWeek, long fetchedAt) throws JSONException {
        Map<String, Long> daily = new java.util.HashMap<>();
        readDailyData(thisYear, daily);
        if (lastYearOrNull != null) {
            readDailyData(lastYearOrNull, daily);
        }
        String[] dates = lastSevenDates(today);
        long[] meters = new long[DAYS];
        for (int i = 0; i < DAYS; i++) {
            Long v = daily.get(dates[i]);
            meters[i] = v == null ? 0 : v;
        }
        return new DawarichWeekStats(meters, dates, readCurrentStreak(thisYear), readDistanceUnit(thisYear),
                pointsToday, pointsWeek, fetchedAt);
    }
}
