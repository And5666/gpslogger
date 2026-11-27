package com.mendhak.gpslogger.common;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import androidx.test.filters.SmallTest;
import org.junit.Test;

@SmallTest
public class StationaryDetectorTest {

    // ~1 m of latitude in degrees
    private static final double M = 1.0 / 111_320.0;
    private static final double LAT = 51.5;
    private static final double LON = -0.12;
    private static final double RADIUS = 50;

    private long time = 1_000_000;

    private StationaryDetector.State feed(StationaryDetector.State s, double northMeters, int required) {
        return feed(s, northMeters, 10, required);
    }

    private StationaryDetector.State feed(StationaryDetector.State s, double northMeters, double accuracy, int required) {
        time += 30_000;
        return StationaryDetector.next(s, LAT + northMeters * M, LON, accuracy, time, RADIUS, required);
    }

    private static double northOfStart(double lat) {
        return (lat - LAT) / M;
    }

    @Test
    public void firstPoint_SetsAnchor_NotStationary() {
        StationaryDetector.State s = feed(StationaryDetector.State.initial(), 0, 3);
        assertThat(s.hasAnchor, is(true));
        assertThat(s.count, is(1));
        assertThat(s.stationary, is(false));
        assertThat(s.clusterStartTime, is(time));
    }

    @Test
    public void becomesStationary_AfterExactlyRequiredPointsWithinRadius() {
        StationaryDetector.State s = StationaryDetector.State.initial();
        s = feed(s, 0, 3);
        s = feed(s, 10, 3);
        assertThat(s.stationary, is(false));
        s = feed(s, -10, 3);
        assertThat(s.stationary, is(true));
        assertThat(s.count, is(3));
    }

    @Test
    public void jitterWithinRadius_StaysStationary() {
        StationaryDetector.State s = StationaryDetector.State.initial();
        double[] jitter = {0, 20, -30, 45, -45, 10, 49, -49, 0};
        for (int i = 0; i < jitter.length; i++) {
            s = feed(s, jitter[i], 3);
            // The odd swing to the far side of the moving centre is a strike, never an exit
            assertThat(s.stationary, is(i >= 2));
        }
        assertThat(s.isSettled(), is(true));
    }

    @Test
    public void centreIsWeightedByAccuracy() {
        // A rough fix then an accurate one: the centre should sit close to the accurate one
        StationaryDetector.State s = StationaryDetector.State.initial();
        s = feed(s, 0, 40, 3);
        s = feed(s, 40, 5, 3);
        double centre = northOfStart(s.anchorLat);
        assertThat("centre " + centre, centre > 38 && centre < 40, is(true));
    }

    @Test
    public void inaccurateFirstPoint_DoesNotResetTheCluster() {
        // The user's example: a bad first fix, then a good one at the edge, then one slightly further
        StationaryDetector.State s = StationaryDetector.State.initial();
        s = feed(s, 0, 40, 3);    // inaccurate first fix
        s = feed(s, 48, 5, 3);    // the real position, at the edge of the first fix's radius
        s = feed(s, 60, 5, 3);    // 60 m from the first fix, but close to the real position
        assertThat(s.strike, is(false));
        assertThat(s.count, is(3));
        assertThat(s.stationary, is(true));
    }

    @Test
    public void oneStrayPoint_WhileStationary_IsAStrikeThenForgotten() {
        StationaryDetector.State s = StationaryDetector.State.initial();
        s = feed(s, 0, 3);
        s = feed(s, 5, 3);
        s = feed(s, 5, 3);
        assertThat(s.isSettled(), is(true));

        s = feed(s, 120, 3);
        assertThat(s.stationary, is(true));
        assertThat(s.strike, is(true));
        assertThat(s.isSettled(), is(false));
        assertThat(s.count, is(3));

        s = feed(s, 3, 3);
        assertThat(s.isSettled(), is(true));
        assertThat(s.count, is(4));
        // The stray point never moved the centre
        assertThat(Math.abs(northOfStart(s.anchorLat)) < 10, is(true));
    }

    @Test
    public void twoPointsOutside_ExitsAndStartsFromTheFirstOfThem() {
        StationaryDetector.State s = StationaryDetector.State.initial();
        s = feed(s, 0, 3);
        s = feed(s, 5, 3);
        s = feed(s, 5, 3);
        assertThat(s.stationary, is(true));

        s = feed(s, 120, 3);
        long strikeTime = time;
        s = feed(s, 130, 3);
        assertThat(s.stationary, is(false));
        assertThat(s.strike, is(false));
        assertThat(s.count, is(2));
        assertThat(s.clusterStartTime, is(strikeTime));
        double centre = northOfStart(s.anchorLat);
        assertThat("centre " + centre, centre > 120 && centre < 130, is(true));
    }

    @Test
    public void walkingOutOfTheHouse_ExitsOnTheSecondPoint() {
        StationaryDetector.State s = StationaryDetector.State.initial();
        for (int i = 0; i < 10; i++) {
            s = feed(s, (i % 2) * 10, 3);
        }
        assertThat(s.isSettled(), is(true));

        // Walking at ~1.4 m/s, one point every 30 s
        s = feed(s, 42, 3);       // still inside
        assertThat(s.isSettled(), is(true));
        s = feed(s, 84, 3);       // outside: strike
        assertThat(s.strike, is(true));
        s = feed(s, 126, 3);      // outside again: moved
        assertThat(s.stationary, is(false));
    }

    @Test
    public void slowCreep_CannotDragTheCentreAway() {
        StationaryDetector.State s = StationaryDetector.State.initial();
        s = feed(s, 0, 3);
        s = feed(s, 0, 3);
        s = feed(s, 0, 3);
        assertThat(s.stationary, is(true));

        // Creep north 10 m per point, always staying inside the radius of the current centre
        double pos = 0;
        for (int i = 0; i < 200 && s.isSettled(); i++) {
            double candidate = pos + 10;
            if (StationaryDetector.distanceFromAnchor(s, LAT + candidate * M, LON) > RADIUS) {
                break;
            }
            pos = candidate;
            s = feed(s, pos, 3);
        }

        // The centre stays within half the radius of home, so the creep is capped at 1.5x the radius
        assertThat("centre " + northOfStart(s.anchorLat), northOfStart(s.anchorLat) <= RADIUS / 2 + 0.5, is(true));
        assertThat("pos " + pos, pos <= RADIUS * 1.5, is(true));

        // and the next steps out are caught
        s = feed(s, pos + 40, 3);
        s = feed(s, pos + 80, 3);
        assertThat(s.stationary, is(false));
    }

    @Test
    public void movingTrack_NeverStationary() {
        StationaryDetector.State s = StationaryDetector.State.initial();
        for (int i = 0; i < 20; i++) {
            s = feed(s, i * 60, 3);
            assertThat(s.stationary, is(false));
        }
    }

    @Test
    public void requiredCountBelowMinimum_StillNeedsTwoPoints() {
        StationaryDetector.State s = feed(StationaryDetector.State.initial(), 0, 1);
        assertThat(s.stationary, is(false));
        s = feed(s, 5, 1);
        assertThat(s.stationary, is(true));

        // Moving away must not immediately count as stationary again
        s = feed(s, 500, 1);
        s = feed(s, 1000, 1);
        assertThat(s.stationary, is(false));
    }

    @Test
    public void missingAccuracy_StillWorks() {
        StationaryDetector.State s = StationaryDetector.State.initial();
        s = feed(s, 0, 0, 2);
        s = feed(s, 10, Double.NaN, 2);
        assertThat(s.stationary, is(true));
        double centre = northOfStart(s.anchorLat);
        assertThat("centre " + centre, centre > 4.9 && centre < 5.1, is(true));
    }

    // Rough fixes (accuracy worse than the radius) can't end stationary mode
    @Test
    public void roughFixesOutsideRadius_DoNotEndStationary() {
        StationaryDetector.State s = StationaryDetector.State.initial();
        for (int i = 0; i < 3; i++) s = feed(s, 0, 3);
        assertThat(s.isSettled(), is(true));
        s = feed(s, 200, 120, 3);
        s = feed(s, 220, 150, 3);
        assertThat(s.isSettled(), is(true));
        assertThat(s.strike, is(false));
    }

    @Test
    public void accurateFixesOutsideRadius_StillEndStationary() {
        StationaryDetector.State s = StationaryDetector.State.initial();
        for (int i = 0; i < 3; i++) s = feed(s, 0, 3);
        s = feed(s, 200, 10, 3);
        s = feed(s, 230, 10, 3);
        assertThat(s.stationary, is(false));
    }
}
