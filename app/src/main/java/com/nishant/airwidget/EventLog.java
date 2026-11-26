package com.nishant.airwidget;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Plain-text debug log of what happened to the purifier and the app (commands sent and who sent them, power / mode /
 * fan level changes seen in the readings, Smart fan on/off, unreachable gaps, service start/stop). One short line per
 * event, nothing per reading. Kept in the app's folder on shared storage so it can be pulled with adb:
 * /sdcard/Android/data/com.nishant.airwidget/files/events.log (falls back to private storage). Capped at about 1 MB.
 */
final class EventLog {
    private static final long MAX_BYTES = 1_000_000, KEEP_BYTES = 600_000;
    private static final String[] MODE = {"auto", "sleep", "favorite"};
    private static final ThreadLocal<String> SOURCE = new ThreadLocal<>();
    private static final SimpleDateFormat FMT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    private static int lastOn = -2, lastMode = -2, lastLevel = -2;
    private static boolean lastOdd;
    private static boolean failing;
    private static long failSince;

    private EventLog() { }

    /** Marks commands sent from this thread as coming from {@code who} (e.g. the home-screen widget). */
    static void as(String who) { SOURCE.set(who); }

    static void clearSource() { SOURCE.remove(); }

    /** Who is sending a command right now. */
    static String source() {
        String s = SOURCE.get();
        if (s != null) return s;
        String t = Thread.currentThread().getName();
        if ("air-live".equals(t)) return "smartfan";
        return t != null && t.startsWith("air-widget") ? "widget" : "sheet";
    }

    private static File file(Context ctx) {
        Context app = ctx.getApplicationContext();
        File dir = app.getExternalFilesDir(null);
        if (dir == null) dir = app.getFilesDir();
        return new File(dir, "events.log");
    }

    static synchronized void event(Context ctx, String msg) {
        try {
            File f = file(ctx);
            if (f.length() > MAX_BYTES) trim(f);
            String line;
            synchronized (FMT) { line = FMT.format(new Date()) + "  " + msg + "\n"; }
            try (FileOutputStream out = new FileOutputStream(f, true)) {
                out.write(line.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException | RuntimeException ignored) {
            // the debug log must never get in the way of the app
        }
    }

    /** A command sent to the purifier and what came of it. */
    static void command(Context ctx, String what, String result) {
        event(ctx, "command [" + source() + "] " + what + " -> " + result);
    }

    /** Fed with every reading that is logged to the history; writes a line when power, mode or level differ. */
    static synchronized void state(Context ctx, Double pm, Boolean onIn, Integer modeIn, Integer levelIn, Integer rpm) {
        int on = onIn == null ? lastOn : onIn ? 1 : 0;
        int mode = modeIn == null ? lastMode : modeIn;
        int level = levelIn == null ? lastLevel : levelIn;
        boolean odd = on == 0 && rpm != null && rpm >= 100;
        boolean first = lastOn == -2 && lastMode == -2 && lastLevel == -2;
        if (on != lastOn || mode != lastMode || level != lastLevel || odd != lastOdd) {
            String now = "power=" + (on < 0 ? "?" : on == 1 ? "on" : "off") + " mode=" + modeName(mode) + " level=" + (level < 0 ? "?" : level)
                    + " rpm=" + (rpm == null ? "?" : rpm) + " pm2.5=" + (pm == null || pm.isNaN() ? "?" : String.valueOf(Math.round(pm)));
            String was = first ? "first reading since the app started"
                    : "was power=" + (lastOn < 0 ? "?" : lastOn == 1 ? "on" : "off") + " mode=" + modeName(lastMode) + " level=" + (lastLevel < 0 ? "?" : lastLevel);
            event(ctx, "state " + now + "  (" + was + ")" + (odd ? "  !! reports off but the fan is running" : ""));
        }
        lastOn = on; lastMode = mode; lastLevel = level; lastOdd = odd;
    }

    /** Called after every read attempt: logs when the purifier stops answering and when it is back. */
    static synchronized void reach(Context ctx, boolean ok, String why) {
        long now = System.currentTimeMillis();
        if (!ok && !failing) {
            failing = true;
            failSince = now;
            event(ctx, "purifier not answering: " + why);
        } else if (ok && failing) {
            failing = false;
            event(ctx, "purifier answering again after " + (now - failSince) / 1000 + " s");
        }
    }

    private static long lastClock = -1;

    /**
     * Watches the purifier's own clock (which counts from its last power-on): logs it on the first reading after the
     * app starts, and when it jumps back, which means the purifier restarted (power cut, flicker, reset). Also logs
     * what miIO.info says (firmware, Wi-Fi signal, uptime) at those moments.
     */
    static void clock(Context ctx) {
        long c = MiioLocal.deviceClock;
        if (c < 0) return;
        boolean first, restarted;
        synchronized (EventLog.class) {
            first = lastClock < 0;
            restarted = !first && c + 30 < lastClock;
            lastClock = c;
        }
        if (!first && !restarted) return;
        event(ctx, restarted ? "PURIFIER RESTARTED: its clock went back to " + c + " s"
                : "purifier clock " + c + " s (about " + c / 3600 + " h since its last start)");
        try {
            Prefs p = new Prefs(ctx);
            org.json.JSONObject i = MiioLocal.info(p.ip(), p.token(), 1500);
            StringBuilder sb = new StringBuilder("purifier info:");
            for (String k : new String[]{"life", "fw_ver", "hw_ver", "model", "uptime"}) {
                if (i.has(k)) sb.append(' ').append(k).append('=').append(i.opt(k));
            }
            org.json.JSONObject w = i.optJSONObject("ap");
            if (w != null) sb.append(" wifi_rssi=").append(w.opt("rssi")).append(" ssid_ok=").append(w.has("ssid"));
            if (i.has("wifi_fw_ver")) sb.append(" wifi_fw=").append(i.opt("wifi_fw_ver"));
            event(ctx, sb.toString());
        } catch (java.io.IOException | RuntimeException e) {
            event(ctx, "purifier info not available: " + e.getMessage());
        }
    }

    private static String modeName(int m) { return m >= 0 && m < MODE.length ? MODE[m] : "?"; }

    /** Keeps the newest part of the file, starting at a line boundary. */
    private static void trim(File f) throws IOException {
        byte[] tail;
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            long len = raf.length();
            int n = (int) Math.min(len, KEEP_BYTES);
            tail = new byte[n];
            raf.seek(len - n);
            raf.readFully(tail);
        }
        int start = 0;
        while (start < tail.length && tail[start] != '\n') start++;
        start = Math.min(tail.length, start + 1);
        try (FileOutputStream out = new FileOutputStream(f, false)) {
            out.write(tail, start, tail.length - start);
        }
    }
}
