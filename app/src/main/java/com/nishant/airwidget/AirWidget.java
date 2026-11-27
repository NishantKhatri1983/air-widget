package com.nishant.airwidget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.RemoteViews;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 2x2 light home-screen widget: temperature, humidity, AQI, state and a power button.
 * The card turns pastel green / lime / yellow / orange / rose / lavender with the air quality.
 * Tap the card = control sheet; tap the blue power circle = toggle the purifier.
 */
public class AirWidget extends AppWidgetProvider {
    static final String ACTION_REFRESH = "com.nishant.airwidget.REFRESH";
    static final String ACTION_POWER = "com.nishant.airwidget.POWER";

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        render(ctx);
        if (new Prefs(ctx).configured()) {
            LiveService.start(ctx);
            LiveService.pollNow();
        }
    }

    @Override
    public void onAppWidgetOptionsChanged(Context ctx, AppWidgetManager mgr, int id, Bundle opts) {
        render(ctx);    // resized: redraw the background at the new size
    }

    @Override
    public void onEnabled(Context ctx) {
        if (new Prefs(ctx).configured()) LiveService.start(ctx);
    }

    @Override
    public void onDisabled(Context ctx) {
        LiveService.stop(ctx);
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String a = intent.getAction();
        if (ACTION_REFRESH.equals(a)) {
            LiveService.start(ctx);
            LiveService.pollNow();
        } else if (ACTION_POWER.equals(a)) {
            togglePower(ctx.getApplicationContext(), goAsync());
        } else {
            super.onReceive(ctx, intent);
        }
    }

    private void togglePower(Context app, PendingResult pr) {
        Prefs p = new Prefs(app);
        if (!p.configured()) { pr.finish(); return; }
        final boolean target = !p.get("lastOn").equals("1");
        p.put("lastOn", target ? "1" : "0");     // show the change straight away
        render(app);
        new Thread(() -> {
            try {
                PurifierControl.setPower(app, target);
            } catch (IOException e) {
                p.put("lastOn", target ? "0" : "1");   // command failed: put the old state back
            }
            LiveService.start(app);
            LiveService.pollNow();
            render(app);
            pr.finish();
        }, "air-widget-power").start();
    }

    static void render(Context ctx) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, AirWidget.class));
        if (ids == null || ids.length == 0) return;
        Prefs p = new Prefs(ctx);
        for (int id : ids) {
            mgr.updateAppWidget(id, build(ctx, p, mgr.getAppWidgetOptions(id)));
        }
    }

    private static RemoteViews build(Context ctx, Prefs p, Bundle opts) {
        RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget);
        int flags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        float d = ctx.getResources().getDisplayMetrics().density;

        boolean configured = p.configured();
        boolean known = !p.get("lastOn").isEmpty();
        boolean on = p.get("lastOn").equals("1");
        Double t = num(p.lastTemp()), h = num(p.lastHum()), pm = num(p.lastPm());

        // pastel card background sized to this widget instance
        int wDp = opts == null ? 0 : opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0);
        int hDp = opts == null ? 0 : opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0);
        if (wDp <= 0) wDp = 128;
        if (hDp <= 0) hDp = 128;
        int[] g = Pastel.gradient(configured ? pm : null, on);
        v.setImageViewBitmap(R.id.bg, Pastel.card(Math.min(1000, Math.round(wDp * d)),
                Math.min(1000, Math.round(hDp * d)), g[0], g[1], 28 * d, Math.max(1f, d)));

        if (!configured) {
            v.setTextViewText(R.id.name, "Air Widget");
            v.setTextViewText(R.id.state, "not set up");
            v.setTextViewText(R.id.temp, "--°");
            v.setTextViewText(R.id.hum, "--%");
            v.setTextViewText(R.id.aqi, "AQI --");
            v.setTextViewText(R.id.updated, "tap to set up");
            v.setOnClickPendingIntent(R.id.root,
                    PendingIntent.getActivity(ctx, 1, new Intent(ctx, SetupActivity.class), flags));
            return v;
        }

        String mode = p.get("lastMode");
        // Labels: Turbo / Smart (the app is steering the fan), else Auto or Sleep. A manual Favorite shows plain "On".
        String modeName = SmartFan.enabled(p) ? (SmartFan.rule(p).fast ? "Turbo" : "Smart")
                : mode.equals("0") ? "Auto" : mode.equals("1") ? "Sleep" : "";
        v.setTextViewText(R.id.name, p.devName().isEmpty() ? "Air purifier" : p.devName());
        v.setTextViewText(R.id.state, !known ? "—" : on ? "On" + (modeName.isEmpty() ? "" : " · " + modeName) : "Off");
        v.setTextColor(R.id.dot, !known ? 0xFF9AA5B4 : on ? 0xFF2E9E4F : 0xFF9AA5B4);
        v.setInt(R.id.power, "setBackgroundResource", on ? R.drawable.bg_power_on : R.drawable.bg_power_off);
        v.setInt(R.id.power, "setColorFilter", on ? 0xFFFFFFFF : 0xFF6B7785);

        v.setTextViewText(R.id.temp, t == null ? "--°" : fmt(t) + "°");
        v.setTextViewText(R.id.hum, h == null ? "--%" : Math.round(h) + "%");
        if (pm == null) {
            v.setTextViewText(R.id.aqi, "AQI --");
            v.setTextColor(R.id.aqi, 0xFF9AA5B4);
        } else {
            v.setTextViewText(R.id.aqi, "AQI " + Aqi.fromPm25(pm, p.aqiStd()));
            v.setTextColor(R.id.aqi, on ? darken(Aqi.colorFor(pm, p.aqiStd())) : 0xFF9AA5B4);
        }

        long age = System.currentTimeMillis() - p.lastTime();
        String when = p.lastTime() == 0 ? "waiting…" : age < 15000 ? "now"
                : new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(p.lastTime()));
        String cat = pm != null && on ? Aqi.category(pm) + " · " : "";
        v.setTextViewText(R.id.updated, cat + when + (age > 120000 ? " (old)" : ""));

        v.setOnClickPendingIntent(R.id.root,
                PendingIntent.getActivity(ctx, 3, new Intent(ctx, ControlActivity.class), flags));
        v.setOnClickPendingIntent(R.id.power, PendingIntent.getBroadcast(ctx, 4,
                new Intent(ctx, AirWidget.class).setAction(ACTION_POWER), flags));
        return v;
    }

    /** The bright AQI colours need darkening to be readable on the pastel card. */
    private static int darken(int c) {
        float[] hsv = new float[3];
        android.graphics.Color.colorToHSV(c, hsv);
        hsv[1] = Math.min(1f, hsv[1] * 1.1f);
        hsv[2] = Math.min(hsv[2], 0.62f);
        return android.graphics.Color.HSVToColor(hsv);
    }

    private static Double num(String s) {
        try {
            return s == null || s.isEmpty() ? null : Double.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String fmt(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.format(Locale.US, "%.1f", d);
    }
}
