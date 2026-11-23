package com.nishant.airwidget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Air-quality history: the AQI line over time (coloured by band) with a lane underneath showing what the
 * purifier was doing - mode colour, fan speed as height, grey line while it was off. Touch to inspect.
 */
public class HistoryView extends View {
    static final int C_AUTO = 0xFF2F6BFF, C_SLEEP = 0xFF8B6FD6, C_FAV = 0xFFF29E38, C_OFF = 0xFF9AA5B4, C_SMART = 0xFF1FA67A;

    /** PM2.5 band edges (India CPCB), and a representative PM2.5 inside each band for the colour stops. */
    private static final double[] EDGE = {0, 30, 60, 90, 120, 250, 500};
    private static final double[] MID = {15, 45, 75, 105, 185, 375};

    private final float d;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint zone = new Paint();
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bar = new Paint();
    private final Path path = new Path();
    private final RectF rect = new RectF();

    private List<History.Sample> data = new ArrayList<>();
    private long from, to;
    private String std = "MI";
    private FanScale fan = FanScale.defaults(10);
    private float touchX = Float.NaN, downX, downY;

    // geometry (px), recomputed on size change
    private float padL, padR, plotTop, plotH, laneTop, laneH, axisY, legendY, plotW;

    public HistoryView(Context c) { this(c, null); }

    public HistoryView(Context c, AttributeSet a) {
        super(c, a);
        d = c.getResources().getDisplayMetrics().density;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2.2f * d);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        grid.setStrokeWidth(Math.max(1f, d * 0.6f));
        grid.setColor(0x1A1B2430);
        txt.setTextSize(10 * d);
        txt.setColor(0xFF8A96A6);
    }

    /** {@code samples} oldest first, covering {@code fromMs..toMs}. */
    void setData(List<History.Sample> samples, long fromMs, long toMs, String aqiStd, FanScale scale) {
        fan = scale;
        data = samples;
        from = fromMs;
        to = Math.max(toMs, fromMs + 1);
        std = aqiStd;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        padL = 30 * d;
        padR = 6 * d;
        plotTop = 20 * d;
        laneH = 30 * d;
        float legendH = 16 * d, axisH = 16 * d, gap = 8 * d;
        plotH = Math.max(40 * d, h - plotTop - gap - laneH - axisH - legendH);
        laneTop = plotTop + plotH + gap;
        axisY = laneTop + laneH + 12 * d;
        legendY = h - 4 * d;
        plotW = w - padL - padR;
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onDraw(Canvas c) {
        if (plotW <= 0) return;
        boolean any = false;
        double maxAqi = 0;
        for (History.Sample s : data) {
            if (!Double.isNaN(s.pm)) { any = true; maxAqi = Math.max(maxAqi, aqi(s.pm)); }
        }
        if (!any) {
            txt.setTextAlign(Paint.Align.CENTER);
            c.drawText("No history in this window yet", padL + plotW / 2, plotTop + plotH / 2, txt);
            return;
        }

        // y scale: always show at least up to the "Satisfactory" band
        double want = Math.max(maxAqi * 1.12, aqi(EDGE[2]) * 1.15);
        double step = niceStep(want / 3);
        final double yMax = Math.ceil(want / step) * step;

        drawZones(c, yMax);
        drawGrid(c, yMax, step);
        drawLine(c, yMax);
        drawLane(c);
        drawTimeAxis(c);
        drawLegend(c);
        drawInspect(c, yMax);
    }

    private void drawZones(Canvas c, double yMax) {
        int[] col = bandColors();
        for (int i = 0; i < col.length; i++) {
            double lo = aqi(EDGE[i]), hi = aqi(EDGE[i + 1]);
            if (lo >= yMax) break;
            zone.setColor((col[i] & 0x00FFFFFF) | 0x2A000000);
            rect.set(padL, y(Math.min(hi, yMax), yMax), padL + plotW, y(lo, yMax));
            c.drawRect(rect, zone);
        }
    }

    private void drawGrid(Canvas c, double yMax, double step) {
        txt.setTextAlign(Paint.Align.RIGHT);
        for (double v = 0; v <= yMax + 0.001; v += step) {
            float yy = y(v, yMax);
            c.drawLine(padL, yy, padL + plotW, yy, grid);
            c.drawText(String.valueOf(Math.round(v)), padL - 5 * d, yy + 3.5f * d, txt);
        }
    }

    private void drawLine(Canvas c, double yMax) {
        // downsample to ~2 px columns so a week of one-minute samples stays cheap
        int cols = Math.max(1, (int) (plotW / (2 * d)));
        double[] sumPm = new double[cols];
        double[] sumT = new double[cols];
        int[] n = new int[cols];
        for (History.Sample s : data) {
            if (Double.isNaN(s.pm) || s.t < from || s.t > to) continue;
            int k = Math.min(cols - 1, (int) ((s.t - from) * (double) cols / (to - from)));
            sumPm[k] += s.pm; sumT[k] += s.t; n[k]++;
        }
        line.setShader(vgrad(yMax, 0xFF));
        fill.setShader(vgrad(yMax, 0x66));
        fill.setStyle(Paint.Style.FILL);

        path.reset();
        float baseY = y(0, yMax);
        float segStartX = Float.NaN, lastX = Float.NaN, lastY = Float.NaN;
        long lastT = Long.MIN_VALUE;
        int inSeg = 0;
        for (int k = 0; k < cols; k++) {
            if (n[k] == 0) continue;
            long t = Math.round(sumT[k] / n[k]);
            float x = padL + (float) ((t - from) * (double) plotW / (to - from));
            float yy = y(aqi(sumPm[k] / n[k]), yMax);
            if (lastT != Long.MIN_VALUE && t - lastT > HistoryStats.GAP_MS) {
                flushSegment(c, segStartX, lastX, lastY, baseY, inSeg);
                inSeg = 0;
            }
            if (inSeg == 0) { path.reset(); path.moveTo(x, yy); segStartX = x; }
            else path.lineTo(x, yy);
            inSeg++;
            lastT = t; lastX = x; lastY = yy;
        }
        flushSegment(c, segStartX, lastX, lastY, baseY, inSeg);
        line.setShader(null);
        fill.setShader(null);
    }

    /** Draws the path built so far (a run of consecutive samples) as area + line. */
    private void flushSegment(Canvas c, float x0, float xN, float yN, float baseY, int count) {
        if (count == 0) return;
        if (count == 1) {
            line.setStyle(Paint.Style.FILL);
            c.drawCircle(xN, yN, 2.2f * d, line);
            line.setStyle(Paint.Style.STROKE);
            return;
        }
        Path area = new Path(path);
        area.lineTo(xN, baseY);
        area.lineTo(x0, baseY);
        area.close();
        c.drawPath(area, fill);
        c.drawPath(path, line);
    }

    /** Vertical gradient that colours by height: the same band colours as the widget, blended between bands. */
    private Shader vgrad(double yMax, int alpha) {
        int[] col = bandColors();
        float top = plotTop, bottom = plotTop + plotH;
        List<Float> pos = new ArrayList<>();
        List<Integer> cs = new ArrayList<>();
        for (int i = 0; i < MID.length; i++) {
            float f = (float) (aqi(MID[i]) / yMax);        // 0 = bottom, 1 = top
            if (f < 0 || f > 1) continue;
            if (!pos.isEmpty() && f <= pos.get(pos.size() - 1)) continue;
            pos.add(f);
            cs.add(col[i]);
        }
        if (pos.isEmpty()) { pos.add(0f); cs.add(col[0]); }
        if (pos.get(0) > 0f) { pos.add(0, 0f); cs.add(0, cs.get(0)); }
        if (pos.get(pos.size() - 1) < 1f) { pos.add(1f); cs.add(cs.get(cs.size() - 1)); }
        int[] colors = new int[cs.size()];
        float[] stops = new float[cs.size()];
        for (int i = 0; i < colors.length; i++) {
            colors[i] = (cs.get(i) & 0x00FFFFFF) | (alpha << 24);
            stops[i] = pos.get(i);
        }
        return new LinearGradient(0, bottom, 0, top, colors, stops, Shader.TileMode.CLAMP);
    }

    private int[] bandColors() {
        return new int[]{Aqi.color(25), Aqi.color(75), Aqi.color(150), Aqi.color(250), Aqi.color(350), Aqi.color(450)};
    }

    private void drawLane(Canvas c) {
        zone.setColor(0x0F1B2430);
        rect.set(padL, laneTop, padL + plotW, laneTop + laneH);
        c.drawRoundRect(rect, 4 * d, 4 * d, zone);

        txt.setTextAlign(Paint.Align.RIGHT);
        c.drawText("fan", padL - 5 * d, laneTop + laneH / 2 + 3.5f * d, txt);

        float colW = 2 * d;
        int n = data.size();
        for (float x = padL; x < padL + plotW; x += colW) {
            long t = from + Math.round((x - padL) * (double) (to - from) / plotW);
            int i = lastAtOrBefore(t);
            if (i < 0) continue;
            History.Sample s = data.get(i);
            if (t - s.t > HistoryStats.GAP_MS) continue;     // no data logged here
            if (s.on == 0) {
                bar.setColor(C_OFF);
                c.drawRect(x, laneTop + laneH - 2.5f * d, x + colW, laneTop + laneH, bar);
                continue;
            }
            float frac = fanFraction(s);
            bar.setColor(((s.smart == 1 ? C_SMART : modeColor(s.mode)) & 0x00FFFFFF) | 0xD0000000);
            float h = Math.max(3 * d, frac * (laneH - 2 * d));
            c.drawRect(x, laneTop + laneH - h, x + colW, laneTop + laneH, bar);
        }
    }

    /** Fan height 0..1 from the 0..10 fan speed (same scale as the slider). */
    private float fanFraction(History.Sample s) {
        double sp = fan.speed(s);
        if (sp < 0) return 0.3f;
        return (float) Math.max(0.06, Math.min(1.0, sp / fan.maxLevel()));
    }

    static int modeColor(int mode) {
        return mode == PurifierControl.SLEEP ? C_SLEEP : mode == PurifierControl.FAVORITE ? C_FAV : C_AUTO;
    }

    private void drawTimeAxis(Canvas c) {
        long span = to - from;
        long step;
        String fmt = "HH:mm";
        if (span <= 25 * 60_000L) step = 5 * 60_000L;
        else if (span <= 50 * 60_000L) step = 10 * 60_000L;
        else if (span <= 90 * 60_000L) step = 15 * 60_000L;
        else if (span <= 7 * 3600_000L) step = 3600_000L;
        else if (span <= 14 * 3600_000L) step = 2 * 3600_000L;
        else if (span <= 30 * 3600_000L) step = 4 * 3600_000L;
        else { step = 24 * 3600_000L; fmt = "EEE"; }
        SimpleDateFormat sf = new SimpleDateFormat(fmt, Locale.getDefault());
        TimeZone tz = TimeZone.getDefault();
        long off = tz.getOffset(from);
        long t = ((from + off) / step) * step - off;
        txt.setTextAlign(Paint.Align.CENTER);
        for (; t <= to; t += step) {
            if (t < from) continue;
            float x = padL + (float) ((t - from) * (double) plotW / span);
            String s = sf.format(new Date(t));
            float half = txt.measureText(s) / 2;
            if (x - half < 0 || x + half > getWidth()) continue;
            c.drawLine(x, laneTop + laneH, x, laneTop + laneH + 3 * d, grid);
            c.drawText(s, x, axisY, txt);
        }
    }

    private void drawLegend(Canvas c) {
        String[] names = {"Auto", "Sleep", "Manual", "Smart", "Off"};
        int[] cols = {C_AUTO, C_SLEEP, C_FAV, C_SMART, C_OFF};
        float x = padL;
        txt.setTextAlign(Paint.Align.LEFT);
        for (int i = 0; i < names.length; i++) {
            bar.setColor(cols[i]);
            rect.set(x, legendY - 8 * d, x + 8 * d, legendY);
            c.drawRoundRect(rect, 2 * d, 2 * d, bar);
            c.drawText(names[i], x + 11 * d, legendY, txt);
            x += 11 * d + txt.measureText(names[i]) + 12 * d;
        }
    }

    private void drawInspect(Canvas c, double yMax) {
        txt.setTextAlign(Paint.Align.LEFT);
        if (Float.isNaN(touchX)) {
            c.drawText("Touch the graph for details", padL, plotTop - 7 * d, txt);
            return;
        }
        float x = Math.max(padL, Math.min(padL + plotW, touchX));
        long t = from + Math.round((x - padL) * (double) (to - from) / plotW);
        int i = nearest(t);
        if (i < 0) return;
        History.Sample s = data.get(i);
        float sx = padL + (float) ((s.t - from) * (double) plotW / (to - from));

        grid.setColor(0x661B2430);
        c.drawLine(sx, plotTop, sx, laneTop + laneH, grid);
        grid.setColor(0x1A1B2430);
        if (!Double.isNaN(s.pm)) {
            float sy = y(aqi(s.pm), yMax);
            bar.setColor(0xFFFFFFFF);
            c.drawCircle(sx, sy, 5 * d, bar);
            bar.setColor(Aqi.colorFor(s.pm, std));
            c.drawCircle(sx, sy, 3.5f * d, bar);
        }

        String time = new SimpleDateFormat(to - from > 30 * 3600_000L ? "EEE HH:mm" : "HH:mm", Locale.getDefault())
                .format(new Date(s.t));

        // line 2: who is steering the fan and how fast
        StringBuilder sb = new StringBuilder();
        if (s.on == 0) sb.append("Purifier off");
        else if (s.on == 1) {
            String m = HistoryStats.controller(s);
            if (!m.isEmpty()) sb.append(m);
            double sp = fan.speed(s);
            if (sp >= 0) sb.append(sb.length() > 0 ? " · " : "").append("fan ").append(fan.label(sp));
        }
        String l1 = time + "  ·  AQI " + (Double.isNaN(s.pm) ? "--" : String.valueOf(aqiInt(s.pm)));
        String l2 = sb.toString();

        // time chip on the axis, right under the line, so the time sits next to your finger
        txt.setTextAlign(Paint.Align.CENTER);
        float tw = txt.measureText(time) + 12 * d;
        float cx = Math.max(tw / 2, Math.min(getWidth() - tw / 2, sx));
        bar.setColor(0xFF1B2430);
        rect.set(cx - tw / 2, axisY - 11 * d, cx + tw / 2, axisY + 4 * d);
        c.drawRoundRect(rect, 7 * d, 7 * d, bar);
        txt.setColor(0xFFFFFFFF);
        c.drawText(time, cx, axisY, txt);

        // readout bubble beside the line (flips to the roomier side)
        float bw = Math.max(txt.measureText(l1), txt.measureText(l2)) + 16 * d;
        float bh = (l2.isEmpty() ? 22 : 38) * d;
        float bx = sx < padL + plotW / 2 ? sx + 10 * d : sx - 10 * d - bw;
        bx = Math.max(padL, Math.min(padL + plotW - bw, bx));
        float by = plotTop + 4 * d;
        bar.setColor(0xF2FFFFFF);
        rect.set(bx, by, bx + bw, by + bh);
        c.drawRoundRect(rect, 8 * d, 8 * d, bar);
        grid.setColor(0x331B2430);
        grid.setStyle(Paint.Style.STROKE);
        c.drawRoundRect(rect, 8 * d, 8 * d, grid);
        grid.setStyle(Paint.Style.FILL);
        grid.setColor(0x1A1B2430);
        txt.setTextAlign(Paint.Align.LEFT);
        txt.setColor(0xFF1B2430);
        c.drawText(l1, bx + 8 * d, by + 15 * d, txt);
        if (!l2.isEmpty()) {
            txt.setColor(0xFF4A5666);
            c.drawText(l2, bx + 8 * d, by + 30 * d, txt);
        }
        txt.setColor(0xFF8A96A6);
    }

    // ------------------------------------------------------------------ helpers

    private double aqi(double pm) { return Aqi.fromPm25(pm, std); }
    private int aqiInt(double pm) { return Aqi.fromPm25(pm, std); }

    private float y(double v, double yMax) {
        return plotTop + plotH * (float) (1 - Math.max(0, Math.min(yMax, v)) / yMax);
    }

    private static double niceStep(double raw) {
        double mag = Math.pow(10, Math.floor(Math.log10(raw)));
        double f = raw / mag;
        double nice = f <= 1 ? 1 : f <= 2 ? 2 : f <= 5 ? 5 : 10;
        return nice * mag;
    }

    /** Index of the last sample at or before t, or -1. */
    private int lastAtOrBefore(long t) {
        int lo = 0, hi = data.size() - 1, ans = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (data.get(mid).t <= t) { ans = mid; lo = mid + 1; } else hi = mid - 1;
        }
        return ans;
    }

    private int nearest(long t) {
        int i = lastAtOrBefore(t);
        if (i < 0) return data.isEmpty() ? -1 : 0;
        if (i + 1 < data.size() && data.get(i + 1).t - t < t - data.get(i).t) return i + 1;
        return i;
    }

    // ------------------------------------------------------------------ touch

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX(); downY = e.getY();
                touchX = e.getX();
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                // a mostly-sideways drag scrubs the graph; a mostly-vertical one scrolls the sheet
                if (Math.abs(e.getX() - downX) >= Math.abs(e.getY() - downY) && getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                touchX = e.getX();
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                touchX = Float.NaN;
                invalidate();
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }
}
