package com.nishant.airwidget;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

/**
 * Reads and controls the purifier: power, mode (0 Auto, 1 Sleep, 2 Favorite) and favorite fan level.
 * Local miIO first (instant); Mi cloud as fallback when the purifier isn't reachable on the LAN.
 */
final class PurifierControl {
    private static final String TAG = "AirWidget";
    static final int AUTO = 0, SLEEP = 1, FAVORITE = 2;
    private static final String[] LEGACY_MODE = {"auto", "silent", "favorite"};

    static final class State {
        Boolean on;
        Integer mode;
        Integer level;
        Double temp, hum, pm25;
        String source;
    }

    static int maxLevel(Prefs p) { return p.getInt("maxLevel", 10); }

    private static boolean haveLocal(Prefs p) {
        return !p.ip().isEmpty() && p.token().length() == 32;
    }

    // ------------------------------------------------------------------ read

    static State read(Context ctx) throws IOException {
        Prefs p = new Prefs(ctx);
        Poller.ensureSpec(p);
        IOException localErr = null;
        if (haveLocal(p)) {
            try {
                State s = readLocal(p);
                s.source = "local";
                cache(p, s);
                return s;
            } catch (IOException e) {
                localErr = e;
            }
        }
        try {
            State s = readCloud(ctx, p);
            s.source = "cloud";
            cache(p, s);
            return s;
        } catch (IOException e) {
            throw localErr != null ? localErr : e;
        }
    }

    private static State readLocal(Prefs p) throws IOException {
        State s = new State();
        if (useLegacy(p)) {
            fillLegacy(s, p);
        } else {
            try {
                JSONArray res = MiioLocal.call(p.ip(), p.token(), "get_properties", allParams(p), 1500);
                fillMiot(s, p, res);
            } catch (IOException e) {
                if (e.getMessage() == null || !e.getMessage().contains("-32601")) throw e;
                p.putInt("localLegacy", 1);
                fillLegacy(s, p);
            }
        }
        return s;
    }

    private static void fillLegacy(State s, Prefs p) throws IOException {
        JSONArray names = new JSONArray().put("power").put("mode").put("favorite_level")
                .put("aqi").put("humidity").put("temp_dec");
        JSONArray r = MiioLocal.call(p.ip(), p.token(), "get_prop", names, 1500);
        if (r.length() < 6) throw new IOException("short get_prop reply " + r);
        s.on = "on".equals(r.optString(0));
        s.mode = modeFromLegacy(r.optString(1));
        s.level = r.isNull(2) ? null : Integer.valueOf(r.optInt(2));
        s.pm25 = r.optDouble(3);
        s.hum = r.optDouble(4);
        s.temp = r.optDouble(5) / 10.0;
    }

    /** Legacy mode string -> 0/1/2, or null for anything else (e.g. what the device reports while off). */
    static Integer modeFromLegacy(String m) {
        if ("auto".equals(m)) return Integer.valueOf(AUTO);
        if ("silent".equals(m)) return Integer.valueOf(SLEEP);
        if ("favorite".equals(m)) return Integer.valueOf(FAVORITE);
        return null;
    }

    private static boolean useLegacy(Prefs p) {
        return p.getInt("legacy", 0) == 1 || p.getInt("localLegacy", 0) == 1;
    }

    private static JSONArray allParams(Prefs p) throws IOException {
        JSONArray a = new JSONArray();
        String[] keys = {"o", "m", "f", "t", "h", "p"};
        try {
            for (String k : keys) {
                int siid = p.getInt(k + "S", 0);
                if (siid > 0) a.put(new JSONObject().put("did", p.did()).put("siid", siid).put("piid", p.getInt(k + "P", 0)));
            }
        } catch (JSONException e) {
            throw new IOException(e);
        }
        return a;
    }

    private static void fillMiot(State s, Prefs p, JSONArray res) {
        for (int i = 0; i < res.length(); i++) {
            JSONObject o = res.optJSONObject(i);
            if (o == null || o.optInt("code", 0) != 0 || !o.has("value")) continue;
            int siid = o.optInt("siid"), piid = o.optInt("piid");
            if (is(p, "o", siid, piid)) s.on = o.optBoolean("value");
            else if (is(p, "m", siid, piid)) s.mode = o.optInt("value");
            else if (is(p, "f", siid, piid)) s.level = o.optInt("value");
            else if (is(p, "t", siid, piid)) s.temp = o.optDouble("value");
            else if (is(p, "h", siid, piid)) s.hum = o.optDouble("value");
            else if (is(p, "p", siid, piid)) s.pm25 = o.optDouble("value");
        }
    }

    private static boolean is(Prefs p, String k, int siid, int piid) {
        return siid == p.getInt(k + "S", -1) && piid == p.getInt(k + "P", -1);
    }

    private static State readCloud(Context ctx, Prefs p) throws IOException {
        XiaomiCloud cloud = new XiaomiCloud(ctx);
        JSONArray res = cloud.getProps(allParams(p), 2);
        State s = new State();
        fillMiot(s, p, res);
        if (s.on == null && s.mode == null && s.temp == null) {
            res = cloud.getProps(allParams(p), 1);
            s = new State();
            fillMiot(s, p, res);
        }
        if (s.on == null && s.mode == null && s.temp == null) throw new IOException("cloud: empty state");
        return s;
    }

    private static void cache(Prefs p, State s) {
        if (s.on != null) p.put("lastOn", s.on ? "1" : "0");
        if (s.mode != null) p.put("lastMode", String.valueOf(s.mode));
        if (s.level != null) p.put("lastLevel", String.valueOf(s.level));
        if (s.temp != null && !s.temp.isNaN()) p.put("lastTemp", String.valueOf(s.temp));
        if (s.hum != null && !s.hum.isNaN()) p.put("lastHum", String.valueOf(s.hum));
        if (s.pm25 != null && !s.pm25.isNaN()) p.put("lastPm", String.valueOf(s.pm25));
        p.put("lastSrc", s.source);
        p.putLong("lastTime", System.currentTimeMillis());
    }

    // ------------------------------------------------------------------ write

    static void setPower(Context ctx, boolean on) throws IOException {
        write(ctx, "set_power", new JSONArray().put(on ? "on" : "off"), "o", on);
    }

    static void setMode(Context ctx, int mode) throws IOException {
        write(ctx, "set_mode", new JSONArray().put(LEGACY_MODE[mode]), "m", mode);
    }

    static void setLevel(Context ctx, int level) throws IOException {
        write(ctx, "set_level_favorite", new JSONArray().put(level), "f", level);
    }

    private static void write(Context ctx, String legacyMethod, JSONArray legacyParams, String key, Object value)
            throws IOException {
        Prefs p = new Prefs(ctx);
        Poller.ensureSpec(p);
        IOException localErr = null;
        if (haveLocal(p)) {
            try {
                if (!useLegacy(p)) {
                    // Modern MIoT device -> set_properties; an old one answers "Method not found".
                    try {
                        JSONArray prm = new JSONArray().put(miotItem(p, key, value));
                        checkSetResult(MiioLocal.call(p.ip(), p.token(), "set_properties", prm, 1500));
                        return;
                    } catch (IOException e) {
                        if (e.getMessage() == null || !e.getMessage().contains("-32601")) throw e;
                        p.putInt("localLegacy", 1);
                    }
                }
                JSONArray r = MiioLocal.call(p.ip(), p.token(), legacyMethod, legacyParams, 1500);
                if (r.length() > 0 && !"ok".equals(r.optString(0))) throw new IOException("device said " + r);
                Log.i(TAG, "local " + legacyMethod + " " + legacyParams + " -> " + r);
                return;
            } catch (IOException e) {
                localErr = e;
                Log.i(TAG, "local write failed: " + e.getMessage());
            }
        }
        try {
            new XiaomiCloud(ctx).setProps(new JSONArray().put(miotItem(p, key, value)));
            Log.i(TAG, "cloud set " + key + "=" + value + " ok");
        } catch (IOException e) {
            throw localErr != null ? localErr : e;
        }
    }

    private static JSONObject miotItem(Prefs p, String key, Object value) throws IOException {
        int siid = p.getInt(key + "S", 0), piid = p.getInt(key + "P", 0);
        if (siid == 0) throw new IOException("this model has no such control");
        try {
            return new JSONObject().put("did", p.did()).put("siid", siid).put("piid", piid).put("value", value);
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    private static void checkSetResult(JSONArray res) throws IOException {
        for (int i = 0; i < res.length(); i++) {
            JSONObject o = res.optJSONObject(i);
            if (o != null && o.optInt("code", 0) != 0) throw new IOException("set rejected, code " + o.optInt("code"));
        }
    }
}
