package com.nishant.airwidget;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.util.List;

/** Log in to Xiaomi, pick the purifier, test, done. */
public class SetupActivity extends Activity {
    private static final String TAG = "AirWidget";
    private static final int REQ_LOGIN = 1;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView status;
    private LinearLayout deviceBox;
    private Button aqiBtn;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        col.setPadding(pad, pad, pad, pad);
        sv.addView(col);
        setContentView(sv);
        Ui.applyInsets(this, sv);

        status = new TextView(this);
        status.setTextSize(15);
        col.addView(status);

        col.addView(button("1. Log in to Xiaomi account", v ->
                startActivityForResult(new Intent(this, LoginActivity.class), REQ_LOGIN)));
        col.addView(button("2. Find my devices", v -> findDevices()));
        deviceBox = new LinearLayout(this);
        deviceBox.setOrientation(LinearLayout.VERTICAL);
        col.addView(deviceBox);
        col.addView(button("3. Read now (test)", v -> testRead()));
        col.addView(button("4. Allow background running", v -> batteryExemption()));
        aqiBtn = button("", v -> {
            Prefs p = new Prefs(this);
            String cur = p.aqiStd();
            p.put("aqiStd", cur.equals("MI") ? "IN" : cur.equals("IN") ? "US" : "MI");
            AirWidget.render(this);
            refresh();
        });
        col.addView(aqiBtn);

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 5);
        }
        refresh();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_LOGIN && res == RESULT_OK) {
            say("Logged in. Looking for devices…");
            findDevices();
        }
        refresh();
    }

    private Button button(String text, View.OnClickListener l) {
        Button bt = new Button(this);
        bt.setText(text);
        bt.setAllCaps(false);
        bt.setOnClickListener(l);
        return bt;
    }

    private void refresh() {
        Prefs p = new Prefs(this);
        StringBuilder sb = new StringBuilder();
        sb.append("Account: ").append(p.loggedIn() ? "logged in (id " + p.userId() + ")" : "not logged in").append('\n');
        sb.append("Device: ").append(p.configured() ? p.devName() + " [" + p.model() + "] ip " + p.ip() : "none chosen");
        status.setText(sb.toString() + (msg.isEmpty() ? "" : "\n\n" + msg));
        String std = p.aqiStd();
        aqiBtn.setText("AQI scale: " + (std.equals("MI") ? "Same as Mi Home" : std.equals("IN") ? "India (CPCB)" : "US EPA")
                + "  (tap to switch)");
    }

    private String msg = "";

    private void say(String m) {
        msg = m;
        Log.i(TAG, "ui: " + m);
        ui.post(this::refresh);
    }

    private void findDevices() {
        say("Contacting Xiaomi cloud…");
        new Thread(() -> {
            try {
                List<XiaomiCloud.Device> list = new XiaomiCloud(this).devices();
                ui.post(() -> showDevices(list));
                say(list.isEmpty() ? "No devices found for this account." : "Tap your air purifier below.");
            } catch (IOException e) {
                say("Failed: " + e.getMessage());
            }
        }, "air-devices").start();
    }

    private void showDevices(List<XiaomiCloud.Device> list) {
        deviceBox.removeAllViews();
        for (XiaomiCloud.Device d : list) {
            Button bt = button(d.name + "\n" + d.model, v -> choose(d));
            deviceBox.addView(bt);
        }
    }

    private void choose(XiaomiCloud.Device d) {
        Prefs p = new Prefs(this);
        p.put("did", d.did);
        p.put("model", d.model);
        p.put("devName", d.name);
        p.put("token", d.token);
        p.put("ip", d.ip);
        p.putInt("specDone", 0);
        p.putInt("localLegacy", 0);
        p.remove("lastTemp", "lastHum", "lastPm", "lastSrc", "lastTime");
        say("Chosen: " + d.name + ". Reading…");
        LiveService.start(this);
        testRead();
    }

    private void testRead() {
        new Thread(() -> {
            try {
                Poller.Reading r = Poller.read(this);
                AirWidget.render(this);
                String aqi = r.pm25 == null ? "-" : String.valueOf(Aqi.fromPm25(r.pm25, new Prefs(this).aqiStd()));
                say("OK via " + r.source + ": temp " + r.temp + "°C, humidity " + r.hum + "%, PM2.5 " + r.pm25
                        + " → AQI " + aqi + ".\nAdd the \"Air Widget\" widget from the home-screen widget list.");
            } catch (IOException e) {
                say("Read failed: " + e.getMessage());
            }
        }, "air-test").start();
    }

    private void batteryExemption() {
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm.isIgnoringBatteryOptimizations(getPackageName())) {
            say("Already allowed to run in the background.");
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            say("Could not open the battery setting: " + e.getMessage());
        }
    }
}
