package com.nishant.airwidget;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Rolling log of the purifier's readings (PM2.5, power, mode, fan speed) so the control sheet can draw
 * how the air quality moved over time. One CSV line per sample in app-private storage, kept for 7 days.
 *
 * Line: {@code epochSeconds,pm25,on,mode,level,rpm,smart,temp,hum} - an empty field means "not reported"; smart
 * (1 = Smart fan in control) is missing in older rows, temp and hum in rows older than v1.5. A copy is kept in the
 * app's shared-storage folder (Android/data/com.nishant.airwidget/files/history.csv) for troubleshooting.
 */
final class History {
    private static final String TAG = "AirWidget";
    static final long KEEP_MS = 7L * 24 * 3600 * 1000;
    private static final long EVERY_MS = 60_000;        // steady-state sampling
    private static final long MIN_GAP_MS = 15_000;      // fastest we log a change in PM2.5 / fan speed
    private static final double PM_STEP = 4;            // ug/m3 change worth logging early
    private static final int RPM_STEP = 120;
    private static final long PRUNE_EVERY_MS = 6L * 3600 * 1000;

    /** One logged reading. Unknown numbers are NaN / -1. */
    static final class Sample {
        final long t;        // epoch millis
        final double pm;     // NaN = unknown
        final int on;        // 1 / 0 / -1
        final int mode;      // 0 auto, 1 sleep, 2 favorite, -1 unknown
        final int level;     // favorite level, -1 unknown
        final int rpm;       // measured fan speed, -1 unknown
        final int smart;     // 1 = the Smart fan was in control, 0 = not (older rows: 0)

        Sample(long t, double pm, int on, int mode, int level, int rpm) { this(t, pm, on, mode, level, rpm, 0); }

        Sample(long t, double pm, int on, int mode, int level, int rpm, int smart) {
            this.smart = smart;
            this.t = t; this.pm = pm; this.on = on; this.mode = mode; this.level = level; this.rpm = rpm;
        }
    }

    private static boolean loaded;
    private static long lastT, lastPrune;
    private static double lastPm = Double.NaN;
    private static int lastOn = -1, lastMode = -1, lastLevel = -1, lastRpm = -1, lastSmart = 0;
    private static volatile int version;

    private static File file(Context ctx) { return new File(ctx.getApplicationContext().getFilesDir(), "history.csv"); }

    /** Bumped on every write; lets the UI know when to reload. */
    static int version() { return version; }

    /** Logs a reading if it is time for one or something worth marking changed. Safe to call every few seconds. */
    static void record(Context ctx, Double pmIn, Boolean onIn, Integer modeIn, Integer levelIn, Integer rpmIn) {
        record(ctx, pmIn, onIn, modeIn, levelIn, rpmIn, null, null);
    }

    static synchronized void record(Context ctx, Double pmIn, Boolean onIn, Integer modeIn, Integer levelIn,
                                    Integer rpmIn, Double tempIn, Double humIn) {
        double pm = pmIn == null || pmIn.isNaN() ? Double.NaN : pmIn;
        if (Double.isNaN(pm) && onIn == null) return;
        EventLog.state(ctx, pmIn, onIn, modeIn, levelIn, rpmIn);
        File f = file(ctx);
        long now = System.currentTimeMillis();
        if (!loaded) loadLast(f);

        int on = onIn == null ? lastOn : onIn ? 1 : 0;
        int mode = modeIn == null ? lastMode : modeIn;
        int level = levelIn == null ? lastLevel : levelIn;
        int rpm = rpmIn == null || rpmIn < 0 ? -1 : rpmIn;
        if (on == 0) rpm = rpm > 0 ? rpm : 0;

        int smart = on == 1 && mode == PurifierControl.FAVORITE && SmartFan.enabled(new Prefs(ctx)) ? 1 : 0;
        boolean stateChanged = on != lastOn || mode != lastMode || level != lastLevel || smart != lastSmart;
        long dt = now - lastT;
        boolean moved = (!Double.isNaN(pm) && !Double.isNaN(lastPm) && Math.abs(pm - lastPm) >= PM_STEP)
                || (rpm >= 0 && lastRpm >= 0 && Math.abs(rpm - lastRpm) >= RPM_STEP);
        if (!(stateChanged || dt >= EVERY_MS || (moved && dt >= MIN_GAP_MS))) return;

        String line = (now / 1000) + "," + (Double.isNaN(pm) ? "" : trim(pm)) + "," + (on < 0 ? "" : on) + ","
                + (mode < 0 ? "" : mode) + "," + (level < 0 ? "" : level) + "," + (rpm < 0 ? "" : rpm) + "," + smart + ","
                + (tempIn == null || tempIn.isNaN() ? "" : trim(tempIn)) + "," + (humIn == null || humIn.isNaN() ? "" : trim(humIn)) + "\n";
        try (FileOutputStream out = new FileOutputStream(f, true)) {
            out.write(line.getBytes(StandardCharsets.US_ASCII));
        } catch (IOException e) {
            Log.w(TAG, "history write failed: " + e.getMessage());
            return;
        }
        mirror(ctx, f, line);
        lastT = now;
        if (!Double.isNaN(pm)) lastPm = pm;
        lastOn = on; lastMode = mode; lastLevel = level; lastSmart = smart;
        if (rpm >= 0) lastRpm = rpm;
        version++;
        if (now - lastPrune > PRUNE_EVERY_MS) {
            prune(f, now);
            File m = mirrorFile(ctx);
            if (m != null && m.exists()) prune(m, now);
        }
    }

    // ---- copy in the app's shared-storage folder so it can be pulled with adb for troubleshooting
    private static boolean mirrorChecked;

    private static File mirrorFile(Context ctx) {
        File dir = ctx.getApplicationContext().getExternalFilesDir(null);
        return dir == null ? null : new File(dir, "history.csv");
    }

    /** Appends the same line to the shared-storage copy; the first time it also copies what is already logged. */
    private static void mirror(Context ctx, File internal, String line) {
        try {
            File m = mirrorFile(ctx);
            if (m == null) return;
            if (!mirrorChecked) {
                mirrorChecked = true;
                if (!m.exists()) {
                    // copy everything except the line just written, which is appended below
                    try (FileInputStream in = new FileInputStream(internal); FileOutputStream out = new FileOutputStream(m)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    }
                    return;   // the copy already contains the new line
                }
            }
            try (FileOutputStream out = new FileOutputStream(m, true)) {
                out.write(line.getBytes(StandardCharsets.US_ASCII));
            }
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "history mirror failed: " + e.getMessage());
        }
    }

    /** Readings since {@code sinceMs}, oldest first. */
    static synchronized List<Sample> load(Context ctx, long sinceMs) {
        List<Sample> out = new ArrayList<>();
        File f = file(ctx);
        if (!f.exists()) return out;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.US_ASCII))) {
            String line;
            while ((line = r.readLine()) != null) {
                Sample s = parse(line);
                if (s != null && s.t >= sinceMs) out.add(s);
            }
        } catch (IOException e) {
            Log.w(TAG, "history read failed: " + e.getMessage());
        }
        return out;
    }

    static synchronized void clear(Context ctx) {
        //noinspection ResultOfMethodCallIgnored
        file(ctx).delete();
        File m = mirrorFile(ctx);
        if (m != null) //noinspection ResultOfMethodCallIgnored
            m.delete();
        mirrorChecked = false;
        loaded = false;
        lastT = 0;
        version++;
    }

    /** Parses one CSV line; null if it is damaged (e.g. a write cut short). */
    static Sample parse(String line) {
        String[] a = line.split(",", -1);
        if (a.length < 6) return null;
        try {
            long t = Long.parseLong(a[0]) * 1000L;
            double pm = a[1].isEmpty() ? Double.NaN : Double.parseDouble(a[1]);
            return new Sample(t, pm, i(a[2]), i(a[3]), i(a[4]), i(a[5]), a.length > 6 && a[6].equals("1") ? 1 : 0);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int i(String s) { return s.isEmpty() ? -1 : Integer.parseInt(s); }

    private static String trim(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.format(java.util.Locale.US, "%.1f", d);
    }

    /** Picks up where the file left off after the process was restarted. */
    private static void loadLast(File f) {
        loaded = true;
        if (!f.exists() || f.length() == 0) return;
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            long len = raf.length();
            int n = (int) Math.min(len, 512);
            byte[] buf = new byte[n];
            raf.seek(len - n);
            raf.readFully(buf);
            String[] lines = new String(buf, StandardCharsets.US_ASCII).split("\n");
            for (int k = lines.length - 1; k >= 0; k--) {
                Sample s = parse(lines[k]);
                if (s == null) continue;
                lastT = s.t; lastPm = s.pm; lastOn = s.on; lastMode = s.mode; lastLevel = s.level; lastRpm = s.rpm; lastSmart = s.smart;
                return;
            }
        } catch (IOException ignored) { }
    }

    /** Drops lines older than 7 days (rewrites the file through a temp copy). */
    private static void prune(File f, long now) {
        lastPrune = now;
        File tmp = new File(f.getPath() + ".tmp");
        long cutoff = (now - KEEP_MS) / 1000;
        boolean dropped = false;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.US_ASCII));
             FileOutputStream out = new FileOutputStream(tmp)) {
            String line;
            while ((line = r.readLine()) != null) {
                Sample s = parse(line);
                if (s == null || s.t / 1000 < cutoff) { dropped = true; continue; }
                out.write((line + "\n").getBytes(StandardCharsets.US_ASCII));
            }
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return;
        }
        if (dropped) {
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(f);    // same directory, so the rename replaces the old file
        } else {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }
}
