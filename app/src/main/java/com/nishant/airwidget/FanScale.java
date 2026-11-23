package com.nishant.airwidget;

import java.util.List;

/**
 * Turns a logged fan state into the same 0..10 "fan speed" the slider uses.
 * In Favorite mode the level is known exactly. In Auto / Sleep the purifier only reports rpm, so the scale learns
 * which rpm each Favorite level produced (from the history) and converts by interpolation. Until a level has been
 * seen it assumes rpm is proportional to level, anchored at {@link #DEFAULT_TOP_RPM} for the top level.
 */
final class FanScale {
    /** Measured on a Mi Air Purifier 2 at Favorite level 10; only used until the real value has been logged. */
    static final double DEFAULT_TOP_RPM = 1700;

    private final int max;
    private final double[] rpmAt;     // index = level, NaN = not seen yet

    private FanScale(int max, double[] rpmAt) {
        this.max = max;
        this.rpmAt = rpmAt;
    }

    static FanScale defaults(int maxLevel) {
        double[] t = new double[maxLevel + 1];
        java.util.Arrays.fill(t, Double.NaN);
        return new FanScale(maxLevel, t);
    }

    /** Average rpm per Favorite level over steady readings (same mode and level as the sample before it). */
    static FanScale learn(List<History.Sample> all, int maxLevel) {
        double[] sum = new double[maxLevel + 1];
        int[] n = new int[maxLevel + 1];
        History.Sample prev = null;
        for (History.Sample s : all) {
            if (prev != null && s.on == 1 && s.mode == PurifierControl.FAVORITE && s.level >= 0 && s.level <= maxLevel
                    && s.rpm > 0 && prev.on == 1 && prev.mode == s.mode && prev.level == s.level
                    && s.t - prev.t <= HistoryStats.GAP_MS) {
                sum[s.level] += s.rpm;
                n[s.level]++;
            }
            prev = s;
        }
        double[] t = new double[maxLevel + 1];
        for (int i = 0; i <= maxLevel; i++) t[i] = n[i] >= 2 ? sum[i] / n[i] : Double.NaN;
        return new FanScale(maxLevel, t);
    }

    int maxLevel() { return max; }

    /** Fan speed 0..max for a sample; -1 when it isn't running or nothing usable was reported. */
    double speed(History.Sample s) {
        if (s.on == 0) return 0;
        if (s.mode == PurifierControl.FAVORITE && s.level >= 0) return Math.min(max, s.level);
        if (s.rpm > 0) return fromRpm(s.rpm);
        if (s.mode == PurifierControl.SLEEP) return 1;
        return -1;
    }

    /** rpm -> 0..max using the learned table (piecewise linear), proportional beyond its ends. */
    double fromRpm(double rpm) {
        int lo = -1, hi = -1;                     // nearest learned levels below / above this rpm
        int first = -1, last = -1;
        for (int i = 0; i <= max; i++) {
            if (Double.isNaN(rpmAt[i])) continue;
            if (first < 0) first = i;
            last = i;
            if (rpmAt[i] <= rpm) lo = i;
            else if (hi < 0) hi = i;
        }
        double v;
        if (first < 0) {
            v = max * rpm / DEFAULT_TOP_RPM;
        } else if (lo >= 0 && hi >= 0 && rpmAt[hi] > rpmAt[lo]) {
            v = lo + (hi - lo) * (rpm - rpmAt[lo]) / (rpmAt[hi] - rpmAt[lo]);
        } else if (hi >= 0) {                      // below every learned level
            v = rpmAt[first] > 0 ? first * rpm / rpmAt[first] : 0;
        } else {                                   // above every learned level
            double top = rpmAt[last];
            v = last == 0 || top <= 0 ? max * rpm / DEFAULT_TOP_RPM
                    : last + (rpm - top) / (top / last);
        }
        return Math.max(0, Math.min(max, v));
    }

    /** "7/10" style text for a speed value (at least 1 while running). */
    String label(double speed) {
        return Math.max(1, Math.round(speed)) + "/" + max;
    }
}
