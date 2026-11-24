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
import java.util.List;
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
    private TextView title, subtitle, speedLabel, status, tempVal, humVal, aqiVal;
    private TextView lbAuto, lbSleep, lbFav;
    private ImageView power, icAuto, icSleep, icFav;
    private SeekBar speed;
    private AirView air;
    private HistoryView hist;
    private int topInset;
    private android.widget.Switch smartSwitch;
    private TextView smartRule, smartStatus, smartTurbo;
    private TextView histSummary;
    private final TextView[] chips = new TextView[4];
    private static final long[] RANGE_MS = {3600_000L, 6 * 3600_000L, 24 * 3600_000L, 7 * 24 * 3600_000L};
    private final ExecutorService hio = Executors.newSingleThreadExecutor();
    private int histRange = 1, histVer = -1;
    private long histLoadedAt;

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
        status = findViewById(R.id.status);
        hist = findViewById(R.id.hist);
        smartSwitch = findViewById(R.id.smartSwitch);
        smartRule = findViewById(R.id.smartRule);
        smartStatus = findViewById(R.id.smartStatus);
        smartSwitch.setOnCheckedChangeListener((btn, on) -> {
            if (on == SmartFan.enabled(new Prefs(this))) return;      // programmatic refresh
            if (on && !Boolean.TRUE.equals(st.on)) {
                Toast.makeText(this, "Turn the purifier on first", Toast.LENGTH_SHORT).show();
                btn.setChecked(false);
                return;
            }
            SmartFan.setEnabled(this, on);
            renderSmart();
        });
        findViewById(R.id.smartEdit).setOnClickListener(v -> editSmartRule());
        smartTurbo = findViewById(R.id.smartTurbo);
        smartTurbo.setOnClickListener(v -> {
            Prefs p = new Prefs(this);
            boolean turbo = !SmartFan.rule(p).fast;
            SmartFan.setFast(p, turbo);
            if (turbo && !SmartFan.enabled(p)) {          // Turbo also starts the Smart fan
                if (Boolean.TRUE.equals(st.on)) SmartFan.setEnabled(this, true);
                else Toast.makeText(this, "Turbo is set - turn the purifier on to start Smart fan", Toast.LENGTH_SHORT).show();
            }
            renderSmart();
        });
        histSummary = findViewById(R.id.histSummary);
        int[] chipIds = {R.id.rng0, R.id.rng1, R.id.rng2, R.id.rng3};
        for (int i = 0; i < chips.length; i++) {
            final int ri = i;
            chips[i] = findViewById(chipIds[i]);
            chips[i].setOnClickListener(v -> pickRange(ri));
        }

        findViewById(R.id.root).setOnClickListener(v -> finish());   // tap outside = close
        sheet.setOnApplyWindowInsetsListener((v, insets) -> {
            int extra = (int) (8 * getResources().getDisplayMetrics().density);
            topInset = insets.getSystemWindowInsetTop();
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(),
                    extra + insets.getSystemWindowInsetBottom());
            return insets;
        });
        sheet.requestApplyInsets();
        findViewById(R.id.root).getViewTreeObserver().addOnGlobalLayoutListener(this::fitAir);

        Prefs p = new Prefs(this);
        histRange = Math.max(0, Math.min(RANGE_MS.length - 1, p.getInt("histRange", 1)));
        paintChips();
        loadHistory(true);
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
                yieldFan();
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
        if (mode != PurifierControl.FAVORITE) yieldFan();
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
            ui.post(() -> showStatus("Can't reach the purifier: " + e.getMessage()));
        }
    }

    private void apply(PurifierControl.State s) {
        showStatus(null);
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

        tintSheet(on ? pm : null, on);

        // How fast the animated air moves.
        float sp;
        if (!on) sp = 0f;
        else if (fav) sp = Math.max(0.12f, lvl / (float) Math.max(1, max));
        else if (sleepM) sp = 0.15f;
        else sp = pm == null ? 0.4f : (float) Math.max(0.25, Math.min(1.0, 0.25 + pm / 150.0 * 0.75));
        air.setState(on, sp, aqiText, color);

        // one status line replaces the old "Filtering air" card (saves a screen's worth of height)
        if (st.on == null) subtitle.setText("Connecting…");
        else if (!on) subtitle.setText("Off");
        else subtitle.setText("On" + (modeName.isEmpty() ? "" : " · " + modeName)
                + (pm == null ? "" : " · " + Aqi.category(pm) + " · PM2.5 " + Math.round(pm)));
        renderSmart();
    }

    /** Errors only: the sheet stays quiet while everything works. */
    private void showStatus(String msg) {
        if (msg == null || msg.isEmpty()) {
            status.setVisibility(View.GONE);
        } else {
            status.setText(msg);
            status.setVisibility(View.VISIBLE);
        }
    }

    /**
     * Makes the whole sheet fit on one screen. If it is too tall the animated purifier gives up height first (down to
     * a minimum), then the graph; if there is room to spare the graph grows first, then the purifier. On a very short
     * screen both stop at their minimum and the sheet scrolls instead.
     */
    private void fitAir() {
        float d = getResources().getDisplayMetrics().density;
        int rootH = findViewById(R.id.root).getHeight();
        if (rootH == 0 || sheet == null) return;
        android.view.ViewGroup.LayoutParams la = air.getLayoutParams(), lg = hist.getLayoutParams();
        int aH = la.height, gH = lg.height;
        int minA = (int) (130 * d), maxA = (int) (300 * d), minG = (int) (110 * d), maxG = (int) (172 * d);
        int avail = rootH - topInset - (int) (6 * d);
        int need = ((android.view.ViewGroup) sheet).getChildAt(0).getHeight() + sheet.getPaddingTop() + sheet.getPaddingBottom();
        int diff = need - avail;
        if (diff > 0) {
            int cutA = Math.min(diff, Math.max(0, aH - minA));
            aH -= cutA;
            diff -= cutA;
            gH -= Math.min(diff, Math.max(0, gH - minG));
        } else if (-diff > 10 * d) {
            int spare = -diff - (int) (4 * d);
            int addG = Math.min(spare, Math.max(0, maxG - gH));
            gH += addG;
            spare -= addG;
            aH += Math.min(spare, Math.max(0, maxA - aH));
        }
        if (Math.abs(aH - la.height) > 1 || Math.abs(gH - lg.height) > 1) {
            la.height = aH;
            lg.height = gH;
            air.setLayoutParams(la);
            hist.setLayoutParams(lg);
        }
    }

    private int tint0, tint1;

    /** Pastel sheet background that follows the air quality, like the widget card. */
    private void tintSheet(Double pm, boolean on) {
        int[] g = pm == null ? new int[]{0xFFF0F3FA, 0xFFF0F3FA} : Pastel.gradient(pm, on);
        if (g[0] == tint0 && g[1] == tint1) return;
        tint0 = g[0]; tint1 = g[1];
        float r = 32 * getResources().getDisplayMetrics().density;
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR, g);
        gd.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        sheet.setBackground(gd);
    }

    // ------------------------------------------------------------------ smart fan

    /** The user took the controls (mode button, slider): the smart fan steps aside. */
    private void yieldFan() {
        if (SmartFan.enabled(new Prefs(this))) {
            SmartFan.yieldToUser(this);
            Toast.makeText(this, "Smart fan off", Toast.LENGTH_SHORT).show();
            renderSmart();
        }
    }

    private void renderSmart() {
        Prefs p = new Prefs(this);
        boolean on = SmartFan.enabled(p);
        if (smartSwitch.isChecked() != on) smartSwitch.setChecked(on);
        smartRule.setText(SmartFan.rule(p).describe());
        boolean turbo = SmartFan.rule(p).fast;
        smartTurbo.setBackgroundResource(turbo ? R.drawable.bg_turbo_on : R.drawable.bg_turbo_off);
        smartTurbo.setTextColor(turbo ? 0xFFFFFFFF : 0xFF5B6676);
        String s = SmartFan.status(p);
        smartStatus.setText(s.isEmpty() ? "Off" : s);
    }

    private void editSmartRule() {
        final Prefs p = new Prefs(this);
        final SmartFan.Rule r = SmartFan.rule(p);
        android.widget.LinearLayout col = new android.widget.LinearLayout(this);
        col.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        col.setPadding(pad, pad / 2, pad, 0);
        final android.widget.EditText start = field(col, "First step starts at AQI", r.start);
        final android.widget.EditText first = field(col, "Fan level at that AQI", r.startLevel);
        final android.widget.EditText step = field(col, "Add one level for every … AQI", r.step);
        final android.widget.EditText low = field(col, "Fan level below the start AQI", r.low);
        final android.widget.EditText hold = field(col, "Wait before lowering the fan (seconds)", r.holdSec);
        final android.widget.EditText margin = field(col, "Extra AQI under the step before lowering", r.margin);
        final android.widget.CheckBox mute = new android.widget.CheckBox(this);
        mute.setText("Mute the purifier's beep when settings change");
        mute.setChecked(p.getInt("mute", 1) == 1);
        col.addView(mute);
        final android.widget.CheckBox fast = new android.widget.CheckBox(this);
        fast.setText("Fast ramp-up: raise the fan on the first high reading, check every 20 s while the screen is off");
        fast.setChecked(r.fast);
        col.addView(fast);
        final android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(col);
        new android.app.AlertDialog.Builder(this)
                .setTitle("Smart fan rule")
                .setMessage("Top level on this purifier is " + r.max + ".")
                .setView(scroll)
                .setPositiveButton("Save", (d, w) -> {
                    int a = num(start, r.start), b = num(first, r.startLevel), c = num(step, r.step), l = num(low, r.low);
                    SmartFan.saveRule(p, Math.max(0, Math.min(500, a)), Math.max(1, Math.min(200, c)),
                            Math.max(0, Math.min(r.max, b)), Math.max(0, Math.min(r.max, l)),
                            Math.max(0, Math.min(600, num(hold, r.holdSec))), Math.max(0, Math.min(25, num(margin, r.margin))), fast.isChecked());
                    if (mute.isChecked() != (p.getInt("mute", 1) == 1)) PurifierControl.setMute(this, mute.isChecked());
                    renderSmart();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private android.widget.EditText field(android.widget.LinearLayout col, String label, int value) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(13);
        col.addView(t);
        android.widget.EditText e = new android.widget.EditText(this);
        e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        e.setText(String.valueOf(value));
        e.setSelectAllOnFocus(true);
        col.addView(e);
        return e;
    }

    private static int num(android.widget.EditText e, int fallback) {
        try { return Integer.parseInt(e.getText().toString().trim()); } catch (NumberFormatException x) { return fallback; }
    }

    // ------------------------------------------------------------------ air-quality history

    private void pickRange(int i) {
        histRange = i;
        new Prefs(this).putInt("histRange", i);
        paintChips();
        loadHistory(true);
    }

    private void paintChips() {
        for (int i = 0; i < chips.length; i++) {
            boolean sel = i == histRange;
            chips[i].setBackgroundResource(sel ? R.drawable.bg_chip_on : R.drawable.bg_chip_off);
            chips[i].setTextColor(sel ? 0xFFFFFFFF : 0xFF5B6676);
        }
    }

    /** Reloads the graph when new readings were logged (or after 30 s so the "now" edge keeps moving). */
    private void loadHistory(boolean force) {
        long nowMs = System.currentTimeMillis();
        int ver = History.version();
        if (!force && ver == histVer && nowMs - histLoadedAt < 30000) return;
        histVer = ver;
        histLoadedAt = nowMs;
        final int ri = histRange;
        final String std = new Prefs(this).aqiStd();
        final int maxLv = PurifierControl.maxLevel(new Prefs(this));
        try {
            hio.submit(() -> {
                long now = System.currentTimeMillis();
                long from = now - RANGE_MS[ri];
                List<History.Sample> all = History.load(this, now - History.KEEP_MS);
                List<History.Sample> s = new java.util.ArrayList<>();
                for (History.Sample x : all) if (x.t >= from) s.add(x);
                FanScale scale = FanScale.learn(all, maxLv);
                String text = HistoryStats.describe(s, std, now, from, scale);
                ui.post(() -> {
                    if (ri != histRange || isDestroyed()) return;
                    // A young log: zoom to what exists instead of drawing a sliver at the right edge.
                    long shown = s.isEmpty() ? from : Math.max(from, Math.min(s.get(0).t - 60_000L, now - 10 * 60_000L));
                    hist.setData(s, shown, now, std, scale);
                    histSummary.setText(text);
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) { }
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
                ui.post(() -> loadHistory(false));
                if (System.currentTimeMillis() > holdUntil) {
                    try {
                        PurifierControl.State s = PurifierControl.read(this);
                        if (System.currentTimeMillis() > holdUntil) ui.post(() -> apply(s));
                    } catch (IOException | RuntimeException e) {
                        ui.post(() -> showStatus("Can't reach the purifier: " + e.getMessage()));
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
        hio.shutdown();
        super.onDestroy();
    }
}
