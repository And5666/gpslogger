package com.mendhak.gpslogger.common;

/**
 * Decides whether the user is stationary for smart stationary mode.
 * <p>
 * Points that stay within the radius of the cluster's centre add to the count; once the count
 * reaches the required number the user is stationary. The centre is the accuracy-weighted
 * average of the cluster's points, so an inaccurate first fix doesn't pin it in the wrong place.
 * <p>
 * One point outside the radius is a "strike": it might be GPS noise, so the cluster is kept and
 * the next point decides. If that one is back inside, the strike is forgotten. If it's outside
 * too, the user has moved: a new cluster starts from the strike point.
 * <p>
 * Once stationary, the centre can't drift more than half the radius from where it was when
 * the user became stationary, so slow movement can't drag it along with them.
 * <p>
 * Kept free of Android classes so it can be unit tested.
 */
public final class StationaryDetector {

    public static final int MIN_REQUIRED_COUNT = 2;

    // Accuracy used when a fix has none, and the best accuracy a fix is trusted to have
    static final double UNKNOWN_ACCURACY_METERS = 20;
    static final double MIN_ACCURACY_METERS = 3;

    private StationaryDetector() { }

    public static final class State {
        public final boolean stationary;
        public final boolean hasAnchor;
        /** Centre of the cluster */
        public final double anchorLat;
        public final double anchorLon;
        public final int count;
        /** Sum of the weights of the cluster's points */
        public final double weight;
        /** When the cluster's first point was logged, in ms */
        public final long clusterStartTime;
        /** Centre when the user became stationary; the centre can't move far from it */
        public final double homeLat;
        public final double homeLon;
        /** A single point fell outside the radius and the next point will decide */
        public final boolean strike;
        public final double strikeLat;
        public final double strikeLon;
        public final double strikeWeight;
        public final long strikeTime;

        public State(boolean stationary, boolean hasAnchor, double anchorLat, double anchorLon, int count,
                     double weight, long clusterStartTime, double homeLat, double homeLon,
                     boolean strike, double strikeLat, double strikeLon, double strikeWeight, long strikeTime) {
            this.stationary = stationary;
            this.hasAnchor = hasAnchor;
            this.anchorLat = anchorLat;
            this.anchorLon = anchorLon;
            this.count = count;
            this.weight = weight;
            this.clusterStartTime = clusterStartTime;
            this.homeLat = homeLat;
            this.homeLon = homeLon;
            this.strike = strike;
            this.strikeLat = strikeLat;
            this.strikeLon = strikeLon;
            this.strikeWeight = strikeWeight;
            this.strikeTime = strikeTime;
        }

        public static State initial() {
            return new State(false, false, 0, 0, 0, 0, 0, 0, 0, false, 0, 0, 0, 0);
        }

        /** Stationary and not waiting on a strike, so the slower interval applies */
        public boolean isSettled() {
            return stationary && !strike;
        }

        State withStrike(double lat, double lon, double w, long time) {
            return new State(stationary, hasAnchor, anchorLat, anchorLon, count, weight, clusterStartTime,
                    homeLat, homeLon, true, lat, lon, w, time);
        }
    }

    public static double distanceFromAnchor(State state, double lat, double lon) {
        return Maths.calculateDistance(state.anchorLat, state.anchorLon, lat, lon);
    }

    /**
     * How much a fix counts towards the centre: 1 / accuracy². A ±40 m fix counts 1/16 as much
     * as a ±10 m one.
     */
    static double weightFor(double accuracyMeters) {
        double acc = (accuracyMeters > 0 && !Double.isNaN(accuracyMeters) && !Double.isInfinite(accuracyMeters))
                ? Math.max(MIN_ACCURACY_METERS, accuracyMeters)
                : UNKNOWN_ACCURACY_METERS;
        return 1.0 / (acc * acc);
    }

    /**
     * @param accuracyMeters the fix's accuracy, or 0 if it has none
     * @param timeMillis     when the point was logged
     */
    public static State next(State state, double lat, double lon, double accuracyMeters, long timeMillis,
                             double radiusMeters, int requiredCount) {
        // The count includes the first point, and one point alone can't show the user has stopped
        int required = Math.max(MIN_REQUIRED_COUNT, requiredCount);
        double w = weightFor(accuracyMeters);

        if (state == null || !state.hasAnchor) {
            return startCluster(lat, lon, w, timeMillis);
        }

        if (distanceFromAnchor(state, lat, lon) <= radiusMeters) {
            return addToCluster(state, lat, lon, w, radiusMeters, required);
        }

        if (accuracyMeters > radiusMeters) {
            // A fix less accurate than the radius (a rough Wi-Fi/cell fix) can't show
            // that the user left; ignore it instead of counting it as a strike
            return state;
        }

        if (!state.strike) {
            // Could be GPS noise; keep the cluster and let the next point decide
            return state.withStrike(lat, lon, w, timeMillis);
        }

        // Two points in a row outside the radius: the user has moved. Start a new cluster from
        // the first of them so it isn't lost, then add this one.
        State seeded = startCluster(state.strikeLat, state.strikeLon, state.strikeWeight, state.strikeTime);
        return next(seeded, lat, lon, accuracyMeters, timeMillis, radiusMeters, requiredCount);
    }

    private static State startCluster(double lat, double lon, double w, long time) {
        return new State(false, true, lat, lon, 1, w, time, lat, lon, false, 0, 0, 0, 0);
    }

    private static State addToCluster(State s, double lat, double lon, double w, double radiusMeters, int required) {
        double total = s.weight + w;
        double newLat = (s.anchorLat * s.weight + lat * w) / total;
        double newLon = (s.anchorLon * s.weight + lon * w) / total;

        if (s.stationary) {
            // Don't let the centre wander off with the user
            double maxDrift = radiusMeters / 2;
            double drift = Maths.calculateDistance(s.homeLat, s.homeLon, newLat, newLon);
            if (drift > maxDrift) {
                double f = maxDrift / drift;
                newLat = s.homeLat + (newLat - s.homeLat) * f;
                newLon = s.homeLon + (newLon - s.homeLon) * f;
            }
        }

        int count = s.count + 1;
        boolean stationary = s.stationary || count >= required;
        boolean becameStationary = stationary && !s.stationary;
        double homeLat = becameStationary ? newLat : s.homeLat;
        double homeLon = becameStationary ? newLon : s.homeLon;

        return new State(stationary, true, newLat, newLon, count, total, s.clusterStartTime,
                homeLat, homeLon, false, 0, 0, 0, 0);
    }
}
