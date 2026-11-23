package com.nishant.airwidget;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Turns the logged readings into a plain-words account of the latest air-quality episode:
 * when it rose, where it peaked, what the purifier did, and when it got back to "Good".
 */
final class HistoryStats {
    /** PM2.5 above this (ug/m3) is an episode; the India "Good" limit. */
    static final double GOOD_PM = 30;
    /** An episode ends once PM2.5 is back under this (a little hysteresis so noise at 30 isn't many episodes). */
    private static final double CLEAR_PM = 27;
    /** Episodes whose peak stays below this are not worth reporting. */
    private static final double MIN_PEAK_PM = 36;
    /** Samples further apart than this are treated as a gap in the log. */
    static final long GAP_MS = 15 * 60_000L;

    private static final String[] MODE = {"Auto", "Sleep", "Favorite"};

    static String modeName(int mode) { return mode >= 0 && mode < MODE.length ? MODE[mode] : ""; }

    /** Multi-line summary of the most recent episode in {@code s} (oldest first). */
    static String describe(List<History.Sample> s, String std, long now, long windowFrom, FanScale fan) {
        List<History.Sample> v = new ArrayList<>();
        for (History.Sample x : s) if (!Double.isNaN(x.pm)) v.add(x);
        if (v.isEmpty()) return "No readings recorded yet. The graph fills in as the widget reads the purifier.";

        // ---- find the latest episode
        int start = -1, peak = -1, end = -1;
        int cStart = -1, cPeak = -1;
        for (int k = 0; k < v.size(); k++) {
            double pm = v.get(k).pm;
            if (cStart < 0) {
                if (pm > GOOD_PM) { cStart = k; cPeak = k; }
            } else {
                if (pm > v.get(cPeak).pm) cPeak = k;
                if (pm <= CLEAR_PM) {
                    if (v.get(cPeak).pm >= MIN_PEAK_PM) { start = cStart; peak = cPeak; end = k; }
                    cStart = -1;
                }
            }
        }
        boolean ongoing = false;
        if (cStart >= 0 && v.get(cPeak).pm >= MIN_PEAK_PM) { start = cStart; peak = cPeak; end = -1; ongoing = true; }

        double maxPm = 0;
        for (History.Sample x : v) maxPm = Math.max(maxPm, x.pm);
        String good = String.valueOf(Aqi.fromPm25(GOOD_PM, std));
        if (start < 0) {
            return "Air stayed in the Good range (AQI " + good + " or less). Highest: AQI "
                    + Aqi.fromPm25(maxPm, std) + ".";
        }

        StringBuilder sb = new StringBuilder();
        History.Sample ps = v.get(peak);

        // ---- when it rose
        long riseT = crossingTime(v, start);
        if (start == 0) {
            boolean youngLog = v.get(0).t - windowFrom > 5 * 60_000L;   // the log itself began inside this window
            sb.append("Already above ").append(good).append(youngLog ? " when logging began at " + hm(v.get(0).t)
                    : " when this window began");
        } else {
            sb.append("Rose above ").append(good).append(" at ").append(hm(riseT));
        }
        sb.append('\n');

        // ---- peak
        sb.append("Peaked at AQI ").append(Aqi.fromPm25(ps.pm, std)).append(" at ").append(hm(ps.t));
        if (start > 0) sb.append(" (").append(dur(ps.t - riseT)).append(" after it rose)");
        sb.append('\n');

        // ---- purifier behaviour during the episode
        long from = start > 0 ? v.get(start - 1).t : v.get(start).t;
        long to = ongoing ? now : v.get(end).t;
        sb.append(purifier(s, from, to, ongoing, fan)).append('\n');

        // ---- recovery
        if (ongoing) {
            History.Sample last = v.get(v.size() - 1);
            sb.append("Still above ").append(good).append(" - now AQI ").append(Aqi.fromPm25(last.pm, std));
            if (last.t < now - GAP_MS) sb.append(" (as of ").append(hm(last.t)).append(')');
        } else {
            long clearT = clearTime(v, peak, end);
            sb.append("Back under ").append(good).append(" at ").append(hm(clearT))
                    .append(" (").append(dur(clearT - ps.t)).append(" after the peak)");
        }
        return sb.toString();
    }

    /** Interpolated moment PM2.5 went above the Good limit, so a 60 s log still gives a sensible time. */
    private static long crossingTime(List<History.Sample> v, int start) {
        History.Sample b = v.get(start);
        if (start == 0) return b.t;
        History.Sample a = v.get(start - 1);
        if (b.t - a.t > GAP_MS || b.pm <= a.pm) return b.t;
        double f = (GOOD_PM - a.pm) / (b.pm - a.pm);
        return a.t + Math.round(Math.max(0, Math.min(1, f)) * (b.t - a.t));
    }

    /** Moment PM2.5 came back down through the Good limit (interpolated) after the peak. */
    private static long clearTime(List<History.Sample> v, int peak, int end) {
        for (int k = peak + 1; k <= end; k++) {
            History.Sample a = v.get(k - 1), b = v.get(k);
            if (b.pm <= GOOD_PM) {
                if (b.t - a.t > GAP_MS || a.pm <= b.pm) return b.t;
                double f = (a.pm - GOOD_PM) / (a.pm - b.pm);
                return a.t + Math.round(Math.max(0, Math.min(1, f)) * (b.t - a.t));
            }
        }
        return v.get(end).t;
    }

    /** Who is steering the fan for a sample: the Smart fan when it was in control, else the mode. */
    static String controller(History.Sample x) {
        if (x.smart == 1) return "Smart fan";
        String m = modeName(x.mode);
        return m.equals("Favorite") ? "Manual" : m;
    }

    /** What the purifier did between the rise and the recovery, and who was steering the fan. */
    private static String purifier(List<History.Sample> all, long from, long to, boolean ongoing, FanScale fan) {
        History.Sample prev = null;         // state just before the window, then the previous sample
        boolean onAtStart = false, anyOn = false;
        long switchedOn = -1;
        double maxSpeed = -1, lastSpeed = -1;
        List<String> who = new ArrayList<>();      // consecutive controllers, e.g. Auto -> Smart fan
        List<Long> whoFrom = new ArrayList<>();
        for (History.Sample x : all) {
            if (x.t <= from) { prev = x; onAtStart = x.on == 1; continue; }
            if (x.t > to) break;
            if (x.on == 1) {
                anyOn = true;
                if (switchedOn < 0 && prev != null && prev.on == 0) switchedOn = x.t;
                double sp = fan.speed(x);
                if (sp >= 0) { maxSpeed = Math.max(maxSpeed, sp); lastSpeed = sp; }
                String c = controller(x);
                if (!c.isEmpty() && (who.isEmpty() || !who.get(who.size() - 1).equals(c))) {
                    who.add(c);
                    whoFrom.add(x.t);
                }
            }
            prev = x;
        }
        // drop controller changes that lasted under 2 minutes (a stray tap), then merge neighbours that became equal
        for (int i = who.size() - 2; i >= 0; i--) {
            if (whoFrom.get(i + 1) - whoFrom.get(i) < 120_000L && who.size() > 1) { who.remove(i); whoFrom.remove(i); }
        }
        for (int i = who.size() - 1; i > 0; i--) {
            if (who.get(i).equals(who.get(i - 1))) { who.remove(i); whoFrom.remove(i); }
        }
        if (!anyOn && !onAtStart) return "Purifier: " + (ongoing ? "still off" : "stayed off during this rise");
        StringBuilder sb = new StringBuilder("Purifier: ");
        sb.append(switchedOn >= 0 ? "switched on at " + hm(switchedOn) : "was already running");
        if (!who.isEmpty()) {
            sb.append(" · fan ").append(who.get(0));
            for (int i = 1; i < who.size(); i++) sb.append(" → ").append(who.get(i)).append(" at ").append(hm(whoFrom.get(i)));
        }
        if (maxSpeed >= 0) {
            sb.append(" · peak ").append(fan.label(maxSpeed));
            if (ongoing && lastSpeed >= 0) sb.append(", now ").append(fan.label(lastSpeed));
        }
        return sb.toString();
    }

    static String hm(long t) { return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(t)); }

    static String dur(long ms) {
        long m = Math.max(0, Math.round(ms / 60000.0));
        if (m < 1) return "<1 min";
        if (m < 60) return m + " min";
        long h = m / 60;
        return h + " h" + (m % 60 == 0 ? "" : " " + (m % 60) + " min");
    }
}
