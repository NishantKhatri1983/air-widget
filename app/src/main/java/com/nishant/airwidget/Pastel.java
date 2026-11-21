package com.nishant.airwidget;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * Pastel card colours that follow the PM2.5 reading (the number Mi Home calls "AQI").
 * Six bands (India CPCB thresholds); the colour blends smoothly between neighbouring bands.
 */
final class Pastel {
    /** PM2.5 value at the centre of each band. */
    private static final double[] AT = {15, 45, 75, 105, 185, 300};
    // gradient start (top-left) and end (bottom-right) per band
    private static final int[] C0 = {0xFFE4F6EA, 0xFFF1F7D9, 0xFFFFF4D8, 0xFFFFE6D5, 0xFFFFDEE2, 0xFFEADDF7};
    private static final int[] C1 = {0xFFC8EBD5, 0xFFDDEBB2, 0xFFFFE0A6, 0xFFFFC8A6, 0xFFF7B7C2, 0xFFD2BCEA};

    static final int NEUTRAL_0 = 0xFFFAFBFE, NEUTRAL_1 = 0xFFE3EBF7;

    /** {start, end} colours for a PM2.5 value; neutral when unknown. */
    static int[] gradient(Double pm, boolean on) {
        if (pm == null || pm.isNaN() || !on) return new int[]{NEUTRAL_0, NEUTRAL_1};
        double v = Math.max(AT[0], Math.min(AT[AT.length - 1], pm));
        int i = 0;
        while (i < AT.length - 2 && v > AT[i + 1]) i++;
        float t = (float) ((v - AT[i]) / (AT[i + 1] - AT[i]));
        return new int[]{blend(C0[i], C0[i + 1], t), blend(C1[i], C1[i + 1], t)};
    }

    private static int blend(int a, int b, float t) {
        return Color.argb(255,
                Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    // one cached bitmap: the widget redraws every few seconds but the colour changes rarely
    private static Bitmap cache;
    private static int cKey0, cKey1, cW, cH;

    /** Rounded diagonal-gradient card for use as the widget background. */
    static synchronized Bitmap card(int w, int h, int c0, int c1, float radiusPx, float strokePx) {
        if (cache != null && cKey0 == c0 && cKey1 == c1 && cW == w && cH == h) return cache;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, w, h, c0, c1, Shader.TileMode.CLAMP));
        RectF r = new RectF(0, 0, w, h);
        c.drawRoundRect(r, radiusPx, radiusPx, p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(strokePx);
        p.setColor(0x80FFFFFF);
        r.inset(strokePx / 2f, strokePx / 2f);
        c.drawRoundRect(r, radiusPx, radiusPx, p);
        cache = b; cKey0 = c0; cKey1 = c1; cW = w; cH = h;
        return b;
    }
}
