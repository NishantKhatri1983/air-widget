package com.nishant.airwidget;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Bottom sheet opened by tapping the widget: animated purifier, power, Auto / Sleep / Favorite, fan speed. */
public class ControlActivity extends Activity {
    private static final int BLUE = 0xFF2F6BFF, GREY = 0xFF7A8798;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final PurifierControl.State st = new PurifierControl.State();

    private View sheet;
    private TextView title, subtitle, speedLabel, status, statusTitle, statusSub, tempVal, humVal, aqiVal;
    private TextView lbAuto, lbSleep, lbFav;
    private ImageView power, icAuto, icSleep, icFav;
    private SeekBar speed;
    private AirView air;

    private volatile boolean resumed;
    private volatile long holdUntil;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_control);
        sheet = findViewById(R.id.sheet);
        title = findViewById(R.id.title);
        subtitle = findViewById(R.id.subtitle);
        power = findViewById(R.id.power);
        air = findViewById(R.id.air);
        tempVal = findViewById(R.id.tempVal);
        humVal = findViewById(R.id.humVal);
        aqiVal = findViewById(R.id.aqiVal);
        icAuto = findViewById(R.id.icAuto);
        icSleep = findViewById(R.id.icSleep);
        icFav = findViewById(R.id.icFav);
        lbAuto = findViewById(R.id.lbAuto);
        lbSleep = findViewById(R.id.lbSleep);
        lbFav = findViewById(R.id.lbFav);
        speedLabel = findViewById(R.id.speedLabel);
        speed = findViewById(R.id.speed);
        statusTitle = findViewById(R.id.statusTitle);
        statusSub = findViewById(R.id.statusSub);
        status = findViewById(R.id.status);

        findViewById(R.id.root).setOnClickListener(v -> finish());   // tap outside = close
        sheet.setOnApplyWindowInsetsListener((v, insets) -> {
            int extra = (int) (16 * getResources().getDisplayMetrics().density);
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(),
                    extra + insets.getSystemWindowInsetBottom());
            return insets;
        });
        sheet.requestApplyInsets();

        Prefs p = new Prefs(this);
        title.setText(p.devName().isEmpty() ? "Air purifier" : p.devName());
        speed.setMax(PurifierControl.maxLevel(p));

        // Show the last known state immediately; live values replace it within a moment.
        st.on = p.get("lastOn").isEmpty() ? null : p.get("lastOn").equals("1");
        st.mode = p.get("lastMode").isEmpty() ? null : Integer.valueOf(p.get("lastMode"));
        st.level = p.get("lastLevel").isEmpty() ? null : Integer.valueOf(p.get("lastLevel"));
        render();

        power.setOnClickListener(v -> {
            boolean target = !Boolean.TRUE.equals(st.on);
            st.on = target;
            act(() -> PurifierControl.setPower(this, target));
        });
        findViewById(R.id.optAuto).setOnClickListener(v -> pickMode(PurifierControl.AUTO));
        findViewById(R.id.optSleep).setOnClickListener(v -> pickMode(PurifierControl.SLEEP));
        findViewById(R.id.optFav).setOnClickListener(v -> pickMode(PurifierControl.FAVORITE));

        speed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (fromUser) speedLabel.setText("Fan speed  " + progress + " / " + s.getMax());
            }
            @Override public void onStartTrackingTouch(SeekBar s) { holdUntil = Long.MAX_VALUE; }
            @Override public void onStopTrackingTouch(SeekBar s) {
                final int level = s.getProgress();
                final boolean needFav = !Integer.valueOf(PurifierControl.FAVORITE).equals(st.mode);
                final boolean wasOn = Boolean.TRUE.equals(st.on);
                st.level = level;
                st.mode = PurifierControl.FAVORITE;
                st.on = true;
                act(() -> {
                    // Like Mi Home: moving the slider means "Favorite" mode at that speed.
                    if (!wasOn) { PurifierControl.setPower(ControlActivity.this, true); sleep(400); }
                    if (needFav) PurifierControl.setMode(ControlActivity.this, PurifierControl.FAVORITE);
                    PurifierControl.setLevel(ControlActivity.this, level);
                });
            }
        });
    }

    private void pickMode(int mode) {
        final boolean wasOn = Boolean.TRUE.equals(st.on);
        st.mode = mode;
        st.on = true;
        act(() -> {
            if (!wasOn) {            // picking a mode while off turns the purifier on first
                PurifierControl.setPower(this, true);
                sleep(400);
            }
            PurifierControl.setMode(this, mode);
        });
    }

    private interface Job { void run() throws IOException; }

    /** Optimistic UI is already updated; run the command, then re-read the real state. */
    private void act(Job job) {
        holdUntil = System.currentTimeMillis() + 5000;
        render();
        io.submit(() -> {
            try {
                job.run();
                sleep(350);
            } catch (IOException e) {
                ui.post(() -> Toast.makeText(this, "Failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
            holdUntil = 0;
            refreshOnce();
            LiveService.pollNow();
        });
    }

    private void refreshOnce() {
        try {
            PurifierControl.State s = PurifierControl.read(this);
            ui.post(() -> apply(s));
        } catch (IOException | RuntimeException e) {
            ui.post(() -> status.setText("Can't reach the purifier: " + e.getMessage()));
        }
    }

    private void apply(PurifierControl.State s) {
        if (s.on != null) st.on = s.on;
        if (s.mode != null) st.mode = s.mode;
        if (s.level != null) st.level = s.level;
        st.temp = s.temp; st.hum = s.hum; st.pm25 = s.pm25; st.source = s.source;
        render();
    }

    private void render() {
        Prefs p = new Prefs(this);
        boolean on = Boolean.TRUE.equals(st.on);
        Integer mode = st.mode;
        boolean auto = on && Integer.valueOf(PurifierControl.AUTO).equals(mode);
        boolean sleepM = on && Integer.valueOf(PurifierControl.SLEEP).equals(mode);
        boolean fav = on && Integer.valueOf(PurifierControl.FAVORITE).equals(mode);

        power.setBackgroundResource(on ? R.drawable.bg_power_on : R.drawable.bg_power_off);
        power.setColorFilter(on ? 0xFFFFFFFF : 0xFF6B7785);
        String modeName = mode == null ? "" : mode == PurifierControl.AUTO ? "Auto"
                : mode == PurifierControl.SLEEP ? "Sleep" : "Favorite";
        subtitle.setText(st.on == null ? "…" : on ? "On" + (modeName.isEmpty() ? "" : " · " + modeName) : "Off");

        opt(icAuto, lbAuto, auto);
        opt(icSleep, lbSleep, sleepM);
        opt(icFav, lbFav, fav);

        int max = speed.getMax();
        int lvl = st.level == null ? 0 : Math.max(0, Math.min(max, st.level));
        if (!speed.isPressed()) speed.setProgress(lvl);
        speed.setAlpha(on ? 1f : 0.45f);
        speedLabel.setText(st.level == null ? "Fan speed" : "Fan speed  " + lvl + " / " + max
                + (fav ? "" : "   (used in Favorite mode)"));

        Double t = valid(st.temp) ? st.temp : num(p.lastTemp());
        Double h = valid(st.hum) ? st.hum : num(p.lastHum());
        Double pm = valid(st.pm25) ? st.pm25 : num(p.lastPm());
        tempVal.setText(t == null ? "--°" : fmt(t) + "°");
        humVal.setText(h == null ? "--%" : Math.round(h) + "%");
        String aqiText = pm == null ? "--" : String.valueOf(Aqi.fromPm25(pm, p.aqiStd()));
        int color = pm == null ? 0xFF9AA5B4 : Aqi.colorFor(pm, p.aqiStd());
        aqiVal.setText(aqiText);
        aqiVal.setTextColor(darken(color));

        // How fast the animated air moves.
        float sp;
        if (!on) sp = 0f;
        else if (fav) sp = Math.max(0.12f, lvl / (float) Math.max(1, max));
        else if (sleepM) sp = 0.15f;
        else sp = pm == null ? 0.4f : (float) Math.max(0.25, Math.min(1.0, 0.25 + pm / 150.0 * 0.75));
        air.setState(on, sp, aqiText, color);

        if (!on) {
            statusTitle.setText(st.on == null ? "Connecting…" : "Purifier is off");
            statusSub.setText(st.on == null ? "" : "Tap the power button to start");
        } else {
            statusTitle.setText("Filtering air");
            statusSub.setText((pm == null ? "" : Aqi.category(pm) + " · PM2.5 " + Math.round(pm) + " µg/m³ · ")
                    + (fav ? "fan " + lvl + "/" + max : auto ? "auto fan" : "quiet fan"));
        }
        if (st.source != null) status.setText("live · " + st.source);
    }

    private static void opt(ImageView ic, TextView lb, boolean selected) {
        ic.setBackgroundResource(selected ? R.drawable.bg_opt_on : R.drawable.bg_opt_off);
        ic.setColorFilter(selected ? 0xFFFFFFFF : GREY);
        lb.setTextColor(selected ? BLUE : GREY);
    }

    private static boolean valid(Double d) { return d != null && !d.isNaN(); }

    /** The AQI colours are bright for the animation; make the number readable on white. */
    private static int darken(int c) {
        float[] hsv = new float[3];
        android.graphics.Color.colorToHSV(c, hsv);
        hsv[2] = Math.min(hsv[2], 0.72f);
        return android.graphics.Color.HSVToColor(hsv);
    }

    private static Double num(String s) {
        try { return s == null || s.isEmpty() ? null : Double.valueOf(s); } catch (NumberFormatException e) { return null; }
    }

    private static String fmt(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.format(Locale.US, "%.1f", d);
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        new Thread(() -> {
            while (resumed) {
                if (System.currentTimeMillis() > holdUntil) {
                    try {
                        PurifierControl.State s = PurifierControl.read(this);
                        if (System.currentTimeMillis() > holdUntil) ui.post(() -> apply(s));
                    } catch (IOException | RuntimeException e) {
                        ui.post(() -> status.setText("Can't reach the purifier: " + e.getMessage()));
                    }
                }
                sleep(2000);
            }
        }, "air-sheet-poll").start();
    }

    @Override
    protected void onPause() {
        resumed = false;
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        resumed = false;
        io.shutdown();
        super.onDestroy();
    }
}
