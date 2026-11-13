package com.nishant.airwidget;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restarts live updates after a reboot or an app update. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (new Prefs(ctx).configured()) LiveService.start(ctx);
    }
}
