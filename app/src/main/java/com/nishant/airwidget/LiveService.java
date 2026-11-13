package com.nishant.airwidget;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import java.io.IOException;

/**
 * Foreground service that polls the purifier every few seconds while the screen is on and pushes the
 * numbers into the widget. Screen off = paused (nobody can see the widget); screen on = instant refresh.
 */
public class LiveService extends Service {
    private static final String TAG = "AirWidget";
    private static final String CHANNEL = "live";
    private static final int NOTIF_ID = 8;
    private static final long LOCAL_EVERY_MS = 4000;
    private static final long CLOUD_EVERY_MS = 13000;

    static volatile boolean alive;
    private static final Object wake = new Object();
    private static volatile boolean pollRequested;

    private volatile boolean running;
    private volatile boolean paused;
    private Thread worker;
    private BroadcastReceiver screenRcv;
    private ConnectivityManager.NetworkCallback netCb;

    static void start(Context c) {
        try {
            c.startForegroundService(new Intent(c, LiveService.class));
        } catch (Exception ignored) {
            // Android may refuse a background FGS start; opening the app or a reboot restarts it.
        }
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, LiveService.class));
    }

    /** Wakes the loop for an immediate poll. */
    static void pollNow() {
        pollRequested = true;
        synchronized (wake) { wake.notifyAll(); }
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!new Prefs(this).configured()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startForegroundCompat();
        alive = true;
        if (worker == null || !worker.isAlive()) {
            running = true;
            paused = !getSystemService(PowerManager.class).isInteractive();
            registerTriggers();
            worker = new Thread(this::loop, "air-live");
            worker.start();
        }
        pollNow();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        alive = false;
        unregisterTriggers();
        synchronized (wake) { wake.notifyAll(); }
        super.onDestroy();
    }

    private void loop() {
        long delay = LOCAL_EVERY_MS;
        while (running) {
            if (paused) {
                waitFor(60000);
                continue;
            }
            pollRequested = false;
            try {
                Poller.Reading r = Poller.read(this);
                Log.i(TAG, "read " + r.source + ": t=" + r.temp + " h=" + r.hum + " pm=" + r.pm25);
                delay = "local".equals(r.source) ? LOCAL_EVERY_MS : CLOUD_EVERY_MS;
            } catch (IOException e) {
                Log.w(TAG, "poll failed: " + e.getMessage());
                delay = e instanceof XiaomiCloud.AuthException ? 5 * 60000L : CLOUD_EVERY_MS;
            } catch (RuntimeException e) {
                Log.e(TAG, "poll crashed", e);
                delay = CLOUD_EVERY_MS;
            }
            AirWidget.render(this);
            if (!pollRequested) waitFor(delay);
        }
    }

    private void waitFor(long ms) {
        synchronized (wake) {
            if (pollRequested) return;
            try {
                wake.wait(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void registerTriggers() {
        screenRcv = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) {
                    paused = true;
                } else {
                    paused = false;
                    pollNow();
                }
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_USER_PRESENT);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenRcv, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(screenRcv, f);
        }
        netCb = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network n) { pollNow(); }
        };
        try {
            getSystemService(ConnectivityManager.class).registerDefaultNetworkCallback(netCb);
        } catch (Exception ignored) {
            netCb = null;
        }
    }

    private void unregisterTriggers() {
        try { if (screenRcv != null) unregisterReceiver(screenRcv); } catch (Exception ignored) { }
        screenRcv = null;
        try {
            if (netCb != null) getSystemService(ConnectivityManager.class).unregisterNetworkCallback(netCb);
        } catch (Exception ignored) { }
        netCb = null;
    }

    private void startForegroundCompat() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Live air readings", NotificationManager.IMPORTANCE_MIN);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, SetupActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle("Air Widget")
                .setContentText("Live air readings are on")
                .setContentIntent(open)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }
}
