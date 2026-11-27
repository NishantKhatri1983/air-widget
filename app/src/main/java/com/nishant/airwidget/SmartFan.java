package com.nishant.airwidget;

import android.content.Context;
import android.util.Log;

import java.io.IOException;

/**
 * "Smart fan": the app picks the Favorite-mode fan level from the AQI you see in the widget.
 * Default rule: level 2 from AQI 50, one level more for every 25 AQI (so 3 at 75, 4 at 100, 5 at 125 ... capped at the
 * purifier's top level), and level 1 below 50. Runs from the live polling loop; off by default.
 *
 * Calming rules so the fan doesn't hunt around a threshold: AQI is averaged over the last few readings, going up
 * needs two readings in a row, going down needs the air to stay clearly (5 AQI) under the lower threshold for 90 s (both editable: wait and extra margin),
 * and the fan is never re-commanded more than once every 45 s. If someone changes the mode or fan level somewhere
 * else (Mi Home, the purifier's buttons, this app's sheet) the smart fan switches itself off instead of fighting.
 */
final class SmartFan {
    private static final String TAG = "AirWidget";
    private static final long MIN_GAP_MS = 45_000;
    private static final long GRACE_MS = 20_000;
    private static final long FLIP_WINDOW_MS = 60_000;   // a mode change this soon after our command = the purifier's own doing
    private static final long FLIP_PERIOD_MS = 10 * 60_000;   // at most 2 restores per this period
    private static final long FAST_GAP_MS = 10_000;   // fast ramp-up: minimum time between two "raise" commands
    private static final long FAST_POLL_MS = 20_000;   // fast ramp-up: screen-off reading pace while the air is high / rising
    private static final double RISE_AQI = 5;          // a jump this big since the previous reading counts as "rising"

    /** The AQI -> fan level rule. */
    static final class Rule {
        int start = 50, step = 25, startLevel = 2, low = 1, max = 10;
        int holdSec = 90;      // air must stay at the lower level this long before the fan is lowered
        int margin = 5;        // extra AQI under the threshold required before lowering (0 = exactly at the step)
        boolean fast;          // fast ramp-up: raise the fan on the latest reading, no average / confirmation / cooldown

        int levelFor(double aqi) {
            int lv;
            if (aqi < start) lv = low;
            else lv = startLevel + (int) Math.floor((aqi - start) / Math.max(1, step));
            return Math.max(0, Math.min(max, lv));
        }

        String describe() {
            return "AQI " + start + "+ → level " + startLevel + ", then +1 for every " + step
                    + " AQI (max " + max + "). Below " + start + " → level " + low + ".";
        }
    }

    static Rule rule(Prefs p) {
        Rule r = new Rule();
        r.max = Math.max(1, PurifierControl.maxLevel(p));
        r.start = p.getInt("smartStart", 50);
        r.step = Math.max(1, p.getInt("smartStep", 25));
        r.startLevel = p.getInt("smartFirst", 2);
        r.low = p.getInt("smartLow", 1);
        r.holdSec = Math.max(0, p.getInt("smartHold", 90));
        r.margin = Math.max(0, p.getInt("smartMargin", 5));
        r.fast = p.getInt("smartFast", 0) == 1;
        return r;
    }

    static void saveRule(Prefs p, int start, int step, int first, int low, int holdSec, int margin, boolean fast) {
        p.putInt("smartFast", fast ? 1 : 0);
        p.putInt("smartHold", holdSec);
        p.putInt("smartMargin", margin);
        p.putInt("smartStart", start);
        p.putInt("smartStep", step);
        p.putInt("smartFirst", first);
        p.putInt("smartLow", low);
    }

    static void setFast(Prefs p, boolean fast) { p.putInt("smartFast", fast ? 1 : 0); }

    static boolean enabled(Prefs p) { return p.getInt("smart", 0) == 1; }
    static String status(Prefs p) { return p.get("smartStatus"); }

    /** Screen-off polling interval while fast ramp-up is on and the air is high or climbing; 0 = no special pace. */
    static long hotPollMs(Prefs p) {
        return enabled(p) && p.getInt("smartFast", 0) == 1 && hot ? FAST_POLL_MS : 0;
    }

    // ---- state of the running controller (lives as long as the process)
    private static final double[] recent = new double[3];
    private static int recentN;
    private static Integer expected;       // the level we last commanded
    private static long lastCmdAt, downSince;
    private static int upCount, mismatch;
    private static boolean takeover;       // first reading after enabling: switch to Favorite and set the level
    private static volatile boolean hot;   // latest reading is at/above the start AQI or jumped up
    private static double prevAqi = Double.NaN;
    private static long flipA, flipB;          // times of the two latest "purifier flipped to Auto" restores (kept across reset)

    static synchronized void setEnabled(Context ctx, boolean on) {
        Prefs p = new Prefs(ctx);
        p.putInt("smart", on ? 1 : 0);
        reset();
        takeover = on;
        p.put("smartStatus", on ? "Starting…" : "Off");
        EventLog.event(ctx, "smart fan " + (on ? "switched on" : "switched off") + " (" + EventLog.source() + (rule(p).fast ? ", turbo" : "") + ")");
        if (on) LiveService.pollNow();
    }

    /** Called when the user takes the controls themselves (mode buttons, slider): stop controlling. */
    static synchronized void yieldToUser(Context ctx) {
        Prefs p = new Prefs(ctx);
        if (!enabled(p)) return;
        p.putInt("smart", 0);
        p.put("smartStatus", "Off — you changed the fan");
        EventLog.event(ctx, "smart fan off: you changed the fan in the app");
        reset();
        AirWidget.render(ctx);
    }

    private static void reset() {
        recentN = 0;
        expected = null;
        lastCmdAt = 0;
        downSince = 0;
        upCount = 0;
        mismatch = 0;
        takeover = false;
        hot = false;
        prevAqi = Double.NaN;
    }

    /** Feed every reading in; sends a command to the purifier when the level should change. */
    static synchronized void onReading(Context ctx, Poller.Reading r) {
        Prefs p = new Prefs(ctx);
        if (!enabled(p)) return;
        if (r.pm25 == null || r.pm25.isNaN()) return;
        long now = System.currentTimeMillis();
        Rule rule = rule(p);

        double aqi = Aqi.fromPm25(r.pm25, p.aqiStd());
        if (recentN < recent.length) recent[recentN++] = aqi;
        else { recent[0] = recent[1]; recent[1] = recent[2]; recent[2] = aqi; }
        double avg = 0;
        for (int i = 0; i < recentN; i++) avg += recent[i];
        avg /= recentN;
        hot = aqi >= rule.start || (!Double.isNaN(prevAqi) && aqi - prevAqi >= RISE_AQI);
        prevAqi = aqi;

        if (r.on == null) return;
        if (!r.on) {
            p.put("smartStatus", "Waiting — purifier is off");
            expected = null; upCount = 0; downSince = 0;
            return;
        }
        boolean fav = r.mode != null && r.mode == PurifierControl.FAVORITE;

        // The purifier sometimes flips itself to Auto seconds after we raise the fan in a sharp spike. If that happens
        // soon after our own command, put Favorite and our level back (at most twice in 10 minutes), don't give up.
        if (!takeover && r.mode != null && !fav && expected != null && lastCmdAt != 0
                && now - lastCmdAt <= FLIP_WINDOW_MS && flipsAllowed(now)) {
            int lv = expected;
            long since = (now - lastCmdAt) / 1000;
            try {
                PurifierControl.setMode(ctx, PurifierControl.FAVORITE);
                sleep(400);
                PurifierControl.setLevel(ctx, lv);
            } catch (IOException e) {
                p.put("smartStatus", "Couldn't reach the purifier");
                return;
            }
            flipB = flipA;
            flipA = now;
            lastCmdAt = now;
            mismatch = 0;
            EventLog.event(ctx, "smart fan: purifier flipped to mode " + r.mode + " "
                    + since + " s after our command; restored Favorite level " + lv);
            p.put("smartStatus", "Level " + lv + " · restored after the purifier switched to Auto");
            return;
        }

        if (!takeover && now - lastCmdAt > GRACE_MS) {
            // Somebody else changed the mode or the level -> hand control back.
            if (r.mode != null && !fav) { pause(ctx, p, "mode was changed elsewhere (now " + r.mode + ")"); return; }
            if (fav && expected != null && r.level != null && r.level.intValue() != expected.intValue()) {
                if (++mismatch >= 2) { pause(ctx, p, "fan level was changed elsewhere (now " + r.level + ", expected " + expected + ")"); return; }
            } else {
                mismatch = 0;
            }
        }

        int cur = expected != null ? expected : r.level != null && fav ? r.level : -1;
        int up = rule.levelFor(rule.fast ? Math.max(aqi, avg) : avg);   // fast: react to the latest reading
        int down = rule.levelFor(avg + rule.margin);
        Integer want = null;
        if (takeover || cur < 0) {
            want = up;
        } else if (up > cur) {
            downSince = 0;
            if (++upCount >= (rule.fast ? 1 : 2)) want = up;
        } else {
            upCount = 0;
            if (down < cur) {
                if (downSince == 0) downSince = now;
                if (now - downSince >= rule.holdSec * 1000L) want = down;
            } else {
                downSince = 0;
            }
        }

        long gap = rule.fast && cur >= 0 && want != null && want > cur ? FAST_GAP_MS : MIN_GAP_MS;
        if (want != null && !takeover && now - lastCmdAt < gap) want = null;   // too soon after the last change

        if (want != null && (takeover || !fav || want != cur)) {
            try {
                if (!fav) {
                    PurifierControl.setMode(ctx, PurifierControl.FAVORITE);
                    sleep(400);
                }
                PurifierControl.setLevel(ctx, want);
                Log.i(TAG, "smart fan: AQI " + Math.round(avg) + " -> level " + want);
                expected = want;
                lastCmdAt = now;
                takeover = false;
                upCount = 0; downSince = 0; mismatch = 0;
                cur = want;
            } catch (IOException e) {
                p.put("smartStatus", "Couldn't reach the purifier");
                return;
            }
        }
        String st = (cur >= 0 ? "Level " + cur : "Level —") + " for AQI " + Math.round(avg);
        if (cur >= 0 && downSince != 0 && down < cur) {       // lowering is pending: say so
            long left = Math.max(0, rule.holdSec * 1000L - (now - downSince)) / 1000;
            st += " · to level " + down + " in " + left + " s";
        } else if (cur >= 0 && up > cur) {
            st += " · to level " + up + " next";
        }
        if (rule.fast) st += " · fast";
        p.put("smartStatus", st);
    }

    /** True while fewer than FLIP_MAX restores happened in the last FLIP_PERIOD_MS. */
    private static boolean flipsAllowed(long now) {
        return now - flipB > FLIP_PERIOD_MS;   // flipB is the older of the two latest restores
    }

    private static void pause(Context ctx, Prefs p, String why) {
        p.putInt("smart", 0);
        p.put("smartStatus", "Off — " + why);
        reset();
        Log.i(TAG, "smart fan off: " + why);
        EventLog.event(ctx, "smart fan off: " + why);
        AirWidget.render(ctx);
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
