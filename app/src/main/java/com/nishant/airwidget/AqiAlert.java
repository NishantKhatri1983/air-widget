package com.nishant.airwidget;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;

/**
 * "Very poor air" reminder: a notification that appears when the AQI shown in the widget goes above {@link #HIGH},
 * stays (updating its number quietly) until it is swiped away, and removes itself when the AQI falls below {@link #LOW}.
 * After a swipe it only comes back once the AQI has been below LOW and then goes above HIGH again.
 * Tapping it opens Mi Home if installed, otherwise this app's control sheet.
 */
final class AqiAlert {
    static final int HIGH = 225;
    static final int LOW = 100;
    private static final String CHANNEL = "aqialert";
    private static final int NOTIF_ID = 9;
    private static final String MI_HOME = "com.xiaomi.smarthome";
    private static final String STATE = "aqiAlertState";     // "", "shown", "dismissed"
    private static final String SHOWN = "aqiAlertShown";      // AQI number currently in the notification

    private AqiAlert() { }

    /** Called with every fresh PM2.5 reading. */
    static synchronized void update(Context ctx, Double pm25) {
        if (pm25 == null || pm25.isNaN()) return;
        Prefs p = new Prefs(ctx);
        int aqi = Aqi.fromPm25(pm25, p.aqiStd());
        String state = p.get(STATE);
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);

        if (aqi < LOW) {
            if (!state.isEmpty()) {
                nm.cancel(NOTIF_ID);
                p.put(STATE, "");
                p.put(SHOWN, "");
                EventLog.event(ctx, "AQI alert closed (AQI " + aqi + ")");
            }
            return;
        }
        if (state.equals("dismissed")) return;
        if (state.isEmpty() && aqi <= HIGH) return;
        if (state.equals("shown") && p.get(SHOWN).equals(String.valueOf(aqi))) return;   // nothing new to show

        boolean first = state.isEmpty();
        post(ctx, nm, aqi);
        p.put(STATE, "shown");
        p.put(SHOWN, String.valueOf(aqi));
        if (first) EventLog.event(ctx, "AQI alert shown (AQI " + aqi + ")");
    }

    private static void post(Context ctx, NotificationManager nm, int aqi) {
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Very poor air alert", NotificationManager.IMPORTANCE_HIGH);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);

        int flags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        Intent open = ctx.getPackageManager().getLaunchIntentForPackage(MI_HOME);
        boolean miHome = open != null;
        if (open == null) open = new Intent(ctx, ControlActivity.class);
        PendingIntent tap = PendingIntent.getActivity(ctx, 9, open, flags);
        PendingIntent swipe = PendingIntent.getBroadcast(ctx, 10,
                new Intent(ctx, Dismissed.class), flags);

        // A "Live Update": ongoing + a request to be promoted, so Android 16+ shows a pill with the number in the
        // status bar. Older versions just show it as an ordinary notification.
        Bundle extras = new Bundle();
        extras.putBoolean("android.requestPromotedOngoing", true);
        Notification.Builder b = new Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle("Air quality very poor · AQI " + aqi)
                .setContentText(miHome ? "Tap to open Mi Home · swipe to dismiss" : "Tap to open · swipe to dismiss")
                .setColor(0xFFD32F2F)
                .setContentIntent(tap)
                .setDeleteIntent(swipe)
                .addExtras(extras)
                .setOnlyAlertOnce(true)
                .setAutoCancel(false)
                .setOngoing(true)
                .setShowWhen(false);
        if (Build.VERSION.SDK_INT >= 36) b.setShortCriticalText("AQI " + aqi);
        nm.notify(NOTIF_ID, b.build());
    }

    /** Swiping the notification away: stay quiet until the air has cleared and got bad again. */
    public static final class Dismissed extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            Prefs p = new Prefs(c);
            if (p.get(STATE).equals("shown")) {
                p.put(STATE, "dismissed");
                EventLog.event(c, "AQI alert swiped away");
            }
        }
    }
}
