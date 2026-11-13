package com.nishant.airwidget;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

/** Reads temperature / humidity / PM2.5 once: local miIO first, Mi cloud as fallback. */
final class Poller {
    private static final String TAG = "AirWidget";

    static final class Reading {
        Double temp, hum, pm25;
        Boolean on;
        Integer mode, level;
        String source;
    }

    private static long localFailedAt;
    private static long devicesRefreshedAt;

    static synchronized Reading read(Context ctx) throws IOException {
        Prefs p = new Prefs(ctx);
        if (!p.configured()) throw new IOException("no device chosen");

        ensureSpec(p);
        boolean legacy = p.getInt("legacy", 0) == 1;

        IOException localErr = null;
        // Skip local for 12 s after a failure (e.g. on mobile data) so we don't wait on timeouts.
        if (!p.ip().isEmpty() && p.token().length() == 32
                && System.currentTimeMillis() - localFailedAt > 12000) {
            try {
                Reading r;
                if (legacy || p.getInt("localLegacy", 0) == 1) {
                    r = localLegacy(p);
                } else {
                    try {
                        r = localMiot(p);
                    } catch (IOException e) {
                        // Older purifiers (e.g. zhimi.airpurifier.m1) have a MIoT spec in the cloud but
                        // only answer the classic get_prop call over the local protocol.
                        if (e.getMessage() == null || !e.getMessage().contains("-32601")) throw e;
                        p.putInt("localLegacy", 1);
                        r = localLegacy(p);
                    }
                }
                r.source = "local";
                save(p, r);
                return r;
            } catch (IOException e) {
                localErr = e;
                localFailedAt = System.currentTimeMillis();
                Log.i(TAG, "local read failed: " + e.getMessage());
            }
        }

        if (legacy) throw localErr != null ? localErr : new IOException("legacy model needs local access");
        Reading r = cloudMiot(ctx, p);
        r.source = "cloud";
        save(p, r);

        // Local failed but cloud works: the purifier's IP may have changed -> refresh it now and then.
        if (localErr != null && System.currentTimeMillis() - devicesRefreshedAt > 10 * 60000L) {
            devicesRefreshedAt = System.currentTimeMillis();
            try {
                for (XiaomiCloud.Device d : new XiaomiCloud(ctx).devices()) {
                    if (d.did.equals(p.did())) {
                        if (!d.ip.isEmpty() && !d.ip.equals(p.ip())) {
                            p.put("ip", d.ip);
                            localFailedAt = 0;
                        }
                        break;
                    }
                }
            } catch (IOException ignored) { }
        }
        return r;
    }

    /** Looks up the model's MIoT property ids once and caches them. */
    static synchronized void ensureSpec(Prefs p) throws IOException {
        if (p.getInt("specDone", 0) < 2) {
            boolean ok = SpecResolver.resolve(p);
            p.putInt("specDone", 2);
            p.putInt("legacy", ok ? 0 : 1);
        }
    }

    private static JSONArray miotParams(Prefs p) throws IOException {
        JSONArray a = new JSONArray();
        try {
            int[][] ids = {{p.getInt("tS", 0), p.getInt("tP", 0)}, {p.getInt("hS", 0), p.getInt("hP", 0)},
                    {p.getInt("pS", 0), p.getInt("pP", 0)}, {p.getInt("oS", 0), p.getInt("oP", 0)},
                    {p.getInt("mS", 0), p.getInt("mP", 0)}, {p.getInt("fS", 0), p.getInt("fP", 0)}};
            for (int[] id : ids) {
                if (id[0] > 0) a.put(new JSONObject().put("did", p.did()).put("siid", id[0]).put("piid", id[1]));
            }
        } catch (JSONException e) {
            throw new IOException(e);
        }
        if (a.length() == 0) throw new IOException("model has no temperature/humidity/PM2.5 properties");
        return a;
    }

    private static Reading fromMiot(Prefs p, JSONArray res) {
        Reading r = new Reading();
        for (int i = 0; i < res.length(); i++) {
            JSONObject o = res.optJSONObject(i);
            if (o == null || o.optInt("code", 0) != 0 || !o.has("value")) continue;
            int s = o.optInt("siid"), pi = o.optInt("piid");
            if (s == p.getInt("oS", -1) && pi == p.getInt("oP", -1)) { r.on = o.optBoolean("value"); continue; }
            if (s == p.getInt("mS", -1) && pi == p.getInt("mP", -1)) { r.mode = o.optInt("value"); continue; }
            if (s == p.getInt("fS", -1) && pi == p.getInt("fP", -1)) { r.level = o.optInt("value"); continue; }
            double v = o.optDouble("value", Double.NaN);
            if (Double.isNaN(v)) continue;
            if (s == p.getInt("tS", -1) && pi == p.getInt("tP", -1)) r.temp = v;
            else if (s == p.getInt("hS", -1) && pi == p.getInt("hP", -1)) r.hum = v;
            else if (s == p.getInt("pS", -1) && pi == p.getInt("pP", -1)) r.pm25 = v;
        }
        return r;
    }

    private static Reading localMiot(Prefs p) throws IOException {
        Reading r = fromMiot(p, MiioLocal.call(p.ip(), p.token(), "get_properties", miotParams(p), 1500));
        if (r.temp == null && r.hum == null && r.pm25 == null) throw new IOException("local: empty values");
        return r;
    }

    private static Reading localLegacy(Prefs p) throws IOException {
        JSONArray names = new JSONArray().put("aqi").put("humidity").put("temp_dec")
                .put("power").put("mode").put("favorite_level");
        JSONArray res = MiioLocal.call(p.ip(), p.token(), "get_prop", names, 1500);
        Reading r = new Reading();
        if (res.length() >= 3) {
            r.pm25 = res.optDouble(0);
            r.hum = res.optDouble(1);
            r.temp = res.optDouble(2) / 10.0;
        }
        if (res.length() >= 6) {
            r.on = "on".equals(res.optString(3));
            r.mode = PurifierControl.modeFromLegacy(res.optString(4));
            r.level = res.isNull(5) ? null : Integer.valueOf(res.optInt(5));
        }
        // While the purifier is off some sensors may report nothing; keep the power/mode state anyway.
        if (r.temp != null && r.temp.isNaN()) r.temp = null;
        if (r.hum != null && r.hum.isNaN()) r.hum = null;
        if (r.pm25 != null && r.pm25.isNaN()) r.pm25 = null;
        if (r.on == null && r.temp == null && r.hum == null && r.pm25 == null) {
            throw new IOException("local legacy: bad values " + res);
        }
        return r;
    }

    private static Reading cloudMiot(Context ctx, Prefs p) throws IOException {
        XiaomiCloud cloud = new XiaomiCloud(ctx);
        JSONArray res;
        try {
            res = cloud.getProps(miotParams(p), 2);
        } catch (IOException e) {
            if (e instanceof XiaomiCloud.AuthException) throw e;
            res = cloud.getProps(miotParams(p), 1);
        }
        Reading r = fromMiot(p, res);
        if (r.temp == null && r.hum == null && r.pm25 == null) {
            res = cloud.getProps(miotParams(p), 1);
            r = fromMiot(p, res);
        }
        if (r.temp == null && r.hum == null && r.pm25 == null) throw new IOException("cloud: empty values " + res);
        return r;
    }

    private static void save(Prefs p, Reading r) {
        // Keep the previous number when a sensor reports nothing (e.g. while the purifier is off).
        if (r.temp != null) p.put("lastTemp", String.valueOf(r.temp));
        if (r.hum != null) p.put("lastHum", String.valueOf(r.hum));
        if (r.pm25 != null) p.put("lastPm", String.valueOf(r.pm25));
        if (r.on != null) p.put("lastOn", r.on ? "1" : "0");
        if (r.mode != null) p.put("lastMode", String.valueOf(r.mode));
        if (r.level != null) p.put("lastLevel", String.valueOf(r.level));
        p.put("lastSrc", r.source);
        p.putLong("lastTime", System.currentTimeMillis());
    }
}
