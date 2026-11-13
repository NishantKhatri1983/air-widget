package com.nishant.airwidget;

import android.app.Activity;
import android.util.TypedValue;
import android.view.View;

final class Ui {
    /** targetSdk 36 draws edge-to-edge: keep content clear of the status bar, action bar and nav bar. */
    static void applyInsets(Activity a, View v) {
        TypedValue tv = new TypedValue();
        int bar = a.getTheme().resolveAttribute(android.R.attr.actionBarSize, tv, true)
                ? TypedValue.complexToDimensionPixelSize(tv.data, a.getResources().getDisplayMetrics()) : 0;
        final int actionBar = bar;
        v.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(),
                    insets.getSystemWindowInsetTop() + actionBar,
                    insets.getSystemWindowInsetRight(),
                    insets.getSystemWindowInsetBottom());
            return insets;
        });
        v.requestApplyInsets();
    }
}
