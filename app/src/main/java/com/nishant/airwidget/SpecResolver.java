package com.nishant.airwidget;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;

/** Finds which MIoT (siid,piid) hold temperature / humidity / PM2.5 for the chosen device model. */
final class SpecResolver {
    private static final String TAG = "AirWidget";

    /** Fills prefs keys tS,tP,hS,hP,pS,pP (0 = absent). Returns false if the model has no MIoT spec. */
    static boolean resolve(Prefs p) throws IOException {
        String model = p.model();
        try {
            JSONArray inst = new JSONObject(Http.get("https://miot-spec.org/miot-spec-v2/instances?status=all", 30000))
                    .getJSONArray("instances");
            String type = null;
            int bestVer = -1;
            for (int i = 0; i < inst.length(); i++) {
                JSONObject o = inst.getJSONObject(i);
                if (model.equals(o.optString("model")) && o.optInt("version", 0) >= bestVer) {
                    bestVer = o.optInt("version", 0);
                    type = o.optString("type");
                }
            }
            if (type == null) {
                Log.i(TAG, "no MIoT spec for " + model);
                p.remove("tS", "tP", "hS", "hP", "pS", "pP", "oS", "oP", "mS", "mP", "fS", "fP");
                return false;
            }
            JSONObject spec = new JSONObject(Http.get(
                    "https://miot-spec.org/miot-spec-v2/instance?type=" + URLEncoder.encode(type, "UTF-8"), 30000));
            int[] t = null, h = null, pm = null, on = null, mode = null, fan = null;
            JSONArray services = spec.getJSONArray("services");
            for (int i = 0; i < services.length(); i++) {
                JSONObject sv = services.getJSONObject(i);
                JSONArray props = sv.optJSONArray("properties");
                if (props == null) continue;
                String svName = name(sv.optString("type"));
                boolean env = svName.equals("environment");
                boolean purifier = svName.equals("air-purifier");
                for (int k = 0; k < props.length(); k++) {
                    JSONObject pr = props.getJSONObject(k);
                    String n = name(pr.optString("type"));
                    int[] ids = {sv.getInt("iid"), pr.getInt("iid")};
                    if (n.equals("temperature") && (t == null || env)) t = ids;
                    if (n.equals("relative-humidity") && (h == null || env)) h = ids;
                    if (n.equals("pm2.5-density") && (pm == null || env)) pm = ids;
                    if (purifier && n.equals("on") && on == null) on = ids;
                    if (purifier && n.equals("mode") && mode == null) mode = ids;
                    if (n.equals("favorite-fan-level") && fan == null) {
                        fan = ids;
                        JSONArray range = pr.optJSONArray("value-range");
                        p.putInt("maxLevel", range == null ? 10 : range.optInt(1, 10));
                    }
                }
            }
            put(p, "t", t);
            put(p, "h", h);
            put(p, "p", pm);
            put(p, "o", on);
            put(p, "m", mode);
            put(p, "f", fan);
            Log.i(TAG, "spec " + model + ": temp=" + str(t) + " hum=" + str(h) + " pm2.5=" + str(pm)
                    + " on=" + str(on) + " mode=" + str(mode) + " fan=" + str(fan));
            return true;
        } catch (org.json.JSONException e) {
            throw new IOException("spec parse: " + e);
        }
    }

    private static String name(String urn) {
        String[] a = urn.split(":");
        return a.length > 3 ? a[3] : "";
    }

    private static void put(Prefs p, String k, int[] v) {
        p.putInt(k + "S", v == null ? 0 : v[0]);
        p.putInt(k + "P", v == null ? 0 : v[1]);
    }

    private static String str(int[] v) { return v == null ? "-" : v[0] + "." + v[1]; }
}
