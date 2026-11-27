package com.mendhak.gpslogger.widgets;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;

import androidx.test.filters.SmallTest;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Calendar;

@SmallTest
public class DawarichWeekStatsTest {

    private static Calendar day(int year, int month, int dayOfMonth) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(year, month - 1, dayOfMonth, 12, 0, 0);
        return c;
    }

    // Shape of GET /api/v1/insights, trimmed to the fields the widget reads
    private static JSONObject insights(String dailyData, int streak, String unit) throws Exception {
        return new JSONObject("{\"year\":2026,\"totals\":{\"distanceUnit\":\"" + unit + "\"},"
                + "\"activityHeatmap\":{\"dailyData\":" + dailyData + ",\"currentStreak\":" + streak + "}}");
    }

    @Test
    public void lastSevenDates_EndWithToday() {
        String[] dates = DawarichWeekStats.lastSevenDates(day(2026, 11, 27));
        assertThat(dates.length, is(7));
        assertThat(dates[0], is("2026-11-21"));
        assertThat(dates[6], is("2026-11-27"));
    }

    @Test
    public void picksTheLastSevenDays_AndMissingDaysAreZero() throws Exception {
        JSONObject json = insights("{\"2026-11-20\":99000,\"2026-11-21\":1000,\"2026-11-25\":2500,\"2026-11-27\":4200}", 3, "km");
        DawarichWeekStats s = DawarichWeekStats.fromInsights(day(2026, 11, 27), json, null, 120, 900, 5);
        assertThat(s.dailyMeters[0], is(1000L));
        assertThat(s.dailyMeters[1], is(0L));
        assertThat(s.dailyMeters[4], is(2500L));
        assertThat(s.todayMeters(), is(4200L));
        assertThat(s.weekMeters(), is(7700L));
        assertThat(s.currentStreak, is(3));
        assertThat(s.pointsToday, is(120L));
        assertThat(s.pointsWeek, is(900L));
    }

    @Test
    public void weekAcrossNewYear_UsesBothYears() throws Exception {
        JSONObject thisYear = insights("{\"2027-01-02\":3000}", 2, "km");
        JSONObject lastYear = insights("{\"2026-12-28\":1000,\"2026-12-31\":2000}", 9, "km");
        DawarichWeekStats s = DawarichWeekStats.fromInsights(day(2027, 1, 2), thisYear, lastYear, 0, 0, 0);
        assertThat(s.dates[0], is("2026-12-27"));
        assertThat(s.weekMeters(), is(6000L));
        // The streak comes from this year
        assertThat(s.currentStreak, is(2));
    }

    @Test
    public void miles_AreConverted() throws Exception {
        DawarichWeekStats s = DawarichWeekStats.fromInsights(day(2026, 11, 27),
                insights("{\"2026-11-27\":16093}", 1, "mi"), null, 0, 0, 0);
        assertThat(s.distanceUnit, is("mi"));
        assertThat(Math.abs(s.toUnit(s.todayMeters()) - 10.0) < 0.01, is(true));
    }

    @Test
    public void missingHeatmap_IsAnEmptyWeek() throws Exception {
        DawarichWeekStats s = DawarichWeekStats.fromInsights(day(2026, 11, 27),
                new JSONObject("{\"year\":2026,\"activityHeatmap\":null}"), null, -1, -1, 0);
        assertThat(s.weekMeters(), is(0L));
        assertThat(s.currentStreak, is(0));
        assertThat(s.distanceUnit, is("km"));
    }

    @Test
    public void survivesTheCacheRoundTrip() throws Exception {
        DawarichWeekStats s = DawarichWeekStats.fromInsights(day(2026, 11, 27),
                insights("{\"2026-11-26\":1234}", 4, "mi"), null, 10, 70, 123456789L);
        DawarichWeekStats back = DawarichWeekStats.fromJson(s.toJson());
        assertThat(back, notNullValue());
        assertThat(back.dailyMeters[5], is(1234L));
        assertThat(back.dates[6], is("2026-11-27"));
        assertThat(back.currentStreak, is(4));
        assertThat(back.distanceUnit, is("mi"));
        assertThat(back.pointsWeek, is(70L));
        assertThat(back.fetchedAt, is(123456789L));
    }

    @Test
    public void badCache_IsIgnored() {
        assertThat(DawarichWeekStats.fromJson("not json") == null, is(true));
        assertThat(DawarichWeekStats.fromJson(null) == null, is(true));
    }
}
