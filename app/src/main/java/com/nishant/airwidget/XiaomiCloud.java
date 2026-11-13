package com.nishant.airwidget;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * Xiaomi (Mi Home) cloud client. Login itself happens in a WebView (the user types their password on
 * Xiaomi's own page); we only keep the resulting passToken and derive the API session from it.
 */
final class XiaomiCloud {
    static final String TAG = "AirWidget";

    static final class AuthException extends IOException {
        AuthException(String m) { super(m); }
    }

    static final class Device {
        String did, name, model, token, ip;
        @Override public String toString() { return name + " (" + model + ")"; }
    }

    private final Prefs p;

    XiaomiCloud(Context c) { p = new Prefs(c); }

    private String agent() {
        String id = p.get("agentId");
        if (id.isEmpty()) {
            SecureRandom r = new SecureRandom();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 13; i++) sb.append((char) ('A' + r.nextInt(26)));
            id = sb.toString();
            p.put("agentId", id);
        }
        return "Android-7.1.1-1.0.0-ONEPLUS A3010-136-" + id + " APP/xiaomi.smarthome APPV/62830";
    }

    // ------------------------------------------------------------------ session

    /** Turns the stored passToken into a fresh ssecurity + serviceToken. */
    void refreshSession() throws IOException {
        if (!p.loggedIn()) throw new AuthException("not logged in");
        String devId = p.deviceId();
        if (devId.isEmpty()) devId = "wb_" + java.util.UUID.randomUUID();
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", agent());
        h.put("Cookie", "userId=" + p.userId() + "; passToken=" + p.passToken() + "; deviceId=" + devId);
        Http.Resp r = Http.request("GET",
                "https://account.xiaomi.com/pass/serviceLogin?sid=xiaomiio&_json=true", h, null, 15000);
        String body = r.body.replace("&&&START&&&", "").trim();
        JSONObject j;
        try {
            j = new JSONObject(body);
        } catch (JSONException e) {
            throw new AuthException("unexpected login reply: " + abbreviate(body));
        }
        String ssec = j.optString("ssecurity", "");
        String loc = j.optString("location", "");
        String nonce = j.optString("nonce", "");
        if (j.optInt("code", -1) != 0 || ssec.isEmpty() || loc.isEmpty()) {
            Log.w(TAG, "serviceLogin failed: code=" + j.optInt("code", -1) + " desc=" + j.optString("desc")
                    + " notif=" + !j.optString("notificationUrl").isEmpty());
            throw new AuthException("session expired, log in again");
        }
        String sign = Base64.encodeToString(Crypto.sha1(("nonce=" + nonce + "&" + ssec)
                .getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
        Map<String, String> h2 = new HashMap<>();
        h2.put("User-Agent", agent());
        Http.Resp r2 = Http.request("GET", loc + "&clientSign=" + URLEncoder.encode(sign, "UTF-8"), h2, null, 15000);
        String st = r2.cookie("serviceToken");
        if (st == null || st.isEmpty()) throw new AuthException("no serviceToken (HTTP " + r2.code + ")");
        p.put("ssecurity", ssec);
        p.put("serviceToken", st);
        Log.i(TAG, "session refreshed");
    }

    // ------------------------------------------------------------------ signed API calls

    private static String genNonce() {
        byte[] rnd = new byte[8];
        new SecureRandom().nextBytes(rnd);
        int mins = (int) (System.currentTimeMillis() / 60000);
        byte[] t = new byte[]{(byte) (mins >>> 24), (byte) (mins >>> 16), (byte) (mins >>> 8), (byte) mins};
        return Base64.encodeToString(Crypto.concat(rnd, t), Base64.NO_WRAP);
    }

    private static String sign(String method, String path, Map<String, String> params, String snonce) {
        StringBuilder sb = new StringBuilder(method).append('&').append(path);
        for (Map.Entry<String, String> e : params.entrySet()) {
            sb.append('&').append(e.getKey()).append('=').append(e.getValue());
        }
        sb.append('&').append(snonce);
        return Base64.encodeToString(Crypto.sha1(sb.toString().getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
    }

    private static String host(String region) {
        return (region == null || region.isEmpty() || region.equals("cn")) ? "api.io.mi.com" : region + ".api.io.mi.com";
    }

    /** One signed+encrypted call to https://{region}.api.io.mi.com/app{path}. Returns the parsed JSON. */
    JSONObject call(String region, String path, JSONObject data) throws IOException {
        if (p.serviceToken().isEmpty() || p.ssecurity().isEmpty()) refreshSession();
        try {
            return callOnce(region, path, data);
        } catch (AuthException e) {
            refreshSession();
            return callOnce(region, path, data);
        }
    }

    private JSONObject callOnce(String region, String path, JSONObject data) throws IOException {
        String ssecurity = p.ssecurity();
        String nonce = genNonce();
        byte[] snonceBytes = Crypto.sha256(Base64.decode(ssecurity, Base64.DEFAULT), Base64.decode(nonce, Base64.DEFAULT));
        String snonce = Base64.encodeToString(snonceBytes, Base64.NO_WRAP);

        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        params.put("data", data.toString());
        params.put("rc4_hash__", sign("POST", path, params, snonce));
        for (Map.Entry<String, String> e : params.entrySet()) {
            e.setValue(Base64.encodeToString(Crypto.rc4(snonceBytes, e.getValue().getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP));
        }
        params.put("signature", sign("POST", path, params, snonce));
        params.put("ssecurity", ssecurity);
        params.put("_nonce", nonce);

        StringBuilder q = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (q.length() > 0) q.append('&');
            q.append(URLEncoder.encode(e.getKey(), "UTF-8")).append('=').append(URLEncoder.encode(e.getValue(), "UTF-8"));
        }

        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", agent());
        h.put("Content-Type", "application/x-www-form-urlencoded");
        h.put("x-xiaomi-protocal-flag-cli", "PROTOCAL-HTTP2");
        h.put("MIOT-ENCRYPT-ALGORITHM", "ENCRYPT-RC4");
        TimeZone tz = TimeZone.getDefault();
        h.put("Cookie", "userId=" + p.userId() + "; yetAnotherServiceToken=" + p.serviceToken()
                + "; serviceToken=" + p.serviceToken() + "; locale=en_IN; timezone=GMT" + gmtOffset(tz)
                + "; is_daylight=0; dst_offset=0; channel=MI_APP_STORE");

        Http.Resp r = Http.request("POST", "https://" + host(region) + "/app" + path + "?" + q, h, new byte[0], 15000);
        if (r.code == 401 || r.code == 403) throw new AuthException("HTTP " + r.code);
        String body = r.body.trim();
        if (body.startsWith("{")) {   // plain JSON = error object
            JSONObject err = parse(body);
            if (err.optInt("code", 0) == 3 || err.optInt("code", 0) == 2) throw new AuthException("auth code " + err.optInt("code"));
            return err;
        }
        if (r.code != 200) throw new IOException("HTTP " + r.code + " " + abbreviate(body));
        byte[] plain = Crypto.rc4(snonceBytes, Base64.decode(body, Base64.DEFAULT));
        return parse(new String(plain, StandardCharsets.UTF_8));
    }

    private static String gmtOffset(TimeZone tz) {
        int mins = tz.getOffset(System.currentTimeMillis()) / 60000;
        return String.format("%s%02d:%02d", mins < 0 ? "-" : "+", Math.abs(mins) / 60, Math.abs(mins) % 60);
    }

    private static JSONObject parse(String s) throws IOException {
        try {
            return new JSONObject(s);
        } catch (JSONException e) {
            throw new IOException("bad JSON: " + abbreviate(s));
        }
    }

    static String abbreviate(String s) {
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }

    // ------------------------------------------------------------------ devices & properties

    private static final String[] REGIONS = {"in", "sg", "de", "us", "i2", "ru", "cn"};

    /** Lists devices; finds (and remembers) the region that actually holds them. */
    List<Device> devices() throws IOException {
        JSONObject data = new JSONObject();
        try {
            data.put("getVirtualModel", true).put("getHuamiDevices", 1)
                    .put("get_split_device", false).put("support_smart_home", true);
        } catch (JSONException ignored) { }

        List<String> order = new ArrayList<>();
        if (!p.region().isEmpty()) order.add(p.region());
        for (String r : REGIONS) if (!order.contains(r)) order.add(r);

        IOException last = null;
        for (String region : order) {
            try {
                JSONObject resp = call(region, "/home/device_list", data);
                JSONObject res = resp.optJSONObject("result");
                JSONArray arr = res == null ? null : res.optJSONArray("list");
                if (arr == null || arr.length() == 0) {
                    Log.i(TAG, "region " + region + ": no devices (code " + resp.optInt("code") + ")");
                    continue;
                }
                p.put("region", region);
                List<Device> out = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    Device d = new Device();
                    d.did = o.optString("did");
                    d.name = o.optString("name");
                    d.model = o.optString("model");
                    d.token = o.optString("token");
                    d.ip = o.optString("localip");
                    out.add(d);
                    Log.i(TAG, "device: " + d.name + " | " + d.model + " | ip=" + d.ip + " | did=" + d.did
                            + " | tokenLen=" + d.token.length());
                }
                return out;
            } catch (AuthException e) {
                throw e;
            } catch (IOException | JSONException e) {
                last = e instanceof IOException ? (IOException) e : new IOException(e);
                Log.w(TAG, "region " + region + " failed: " + e);
            }
        }
        if (last != null) throw last;
        return new ArrayList<>();
    }

    /** Cloud write of MIoT properties; params are {did,siid,piid,value}. Throws if any property is rejected. */
    void setProps(JSONArray params) throws IOException {
        try {
            JSONObject resp = call(p.region(), "/miotspec/prop/set", new JSONObject().put("params", params));
            JSONArray res = resp.optJSONArray("result");
            if (res == null) throw new IOException("prop/set: " + abbreviate(resp.toString()));
            for (int i = 0; i < res.length(); i++) {
                int code = res.getJSONObject(i).optInt("code", 0);
                if (code != 0) throw new IOException("prop/set rejected, code " + code);
            }
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    /** Cloud read of MIoT properties; params are {did,siid,piid}. Returns the "result" array. */
    JSONArray getProps(JSONArray params, int datasource) throws IOException {
        try {
            JSONObject data = new JSONObject().put("params", params).put("datasource", datasource);
            JSONObject resp = call(p.region(), "/miotspec/prop/get", data);
            JSONArray res = resp.optJSONArray("result");
            if (res == null) throw new IOException("prop/get: " + abbreviate(resp.toString()));
            return res;
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }
}
