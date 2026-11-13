package com.nishant.airwidget;

import android.content.Context;
import android.content.SharedPreferences;

/** All persisted state (private app storage). */
final class Prefs {
    private final SharedPreferences sp;

    Prefs(Context c) {
        sp = c.getApplicationContext().getSharedPreferences("air", Context.MODE_PRIVATE);
    }

    String get(String k) { return sp.getString(k, ""); }
    void put(String k, String v) { sp.edit().putString(k, v).apply(); }
    int getInt(String k, int d) { return sp.getInt(k, d); }
    void putInt(String k, int v) { sp.edit().putInt(k, v).apply(); }
    long getLong(String k) { return sp.getLong(k, 0L); }
    void putLong(String k, long v) { sp.edit().putLong(k, v).apply(); }
    void remove(String... ks) {
        SharedPreferences.Editor e = sp.edit();
        for (String k : ks) e.remove(k);
        e.apply();
    }

    // ---- Xiaomi account session
    String userId() { return get("userId"); }
    String passToken() { return get("passToken"); }
    String deviceId() { return get("deviceId"); }
    String ssecurity() { return get("ssecurity"); }
    String serviceToken() { return get("serviceToken"); }
    String region() { return get("region"); }
    boolean loggedIn() { return !userId().isEmpty() && !passToken().isEmpty(); }

    // ---- chosen device
    String did() { return get("did"); }
    String model() { return get("model"); }
    String devName() { return get("devName"); }
    String token() { return get("token"); }
    String ip() { return get("ip"); }
    boolean configured() { return !did().isEmpty(); }

    // ---- last reading (so the widget can draw instantly)
    String lastTemp() { return get("lastTemp"); }
    String lastHum() { return get("lastHum"); }
    String lastPm() { return get("lastPm"); }
    String lastSrc() { return get("lastSrc"); }
    long lastTime() { return getLong("lastTime"); }

    /** "MI" = same number as Mi Home (raw PM2.5), "IN" = CPCB India, "US" = EPA. */
    String aqiStd() { String s = get("aqiStd"); return s.isEmpty() ? "MI" : s; }
}
