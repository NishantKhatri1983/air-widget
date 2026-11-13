package com.nishant.airwidget;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Random;

/**
 * Photo of the Mi Air Purifier 2 with a live "filtering air" animation on top: dusty air is carried in
 * from both sides by white arrows and pulled into the perforated body, clean air fans out of the top
 * grille as blue streaks, a coloured aura ripples around the unit and the front LED glows in the
 * air-quality colour. Motion speed follows the fan speed; everything stops when the purifier is off.
 */
public class AirView extends View {
    private static final class P { float x, y, vx, vy, size, age, life, phase; }

    // Where things are on the photo, as fractions of its width / height.
    private static final float BODY_L = 0.10f, BODY_R = 0.97f;
    private static final float GRILLE_X = 0.545f, GRILLE_Y = 0.08f, GRILLE_RX = 0.28f, GRILLE_RY = 0.045f;
    private static final float LED_X = 0.70f, LED_Y = 0.905f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rf = new RectF();
    private final ArrayList<P> dirty = new ArrayList<>();
    private final ArrayList<P> clean = new ArrayList<>();
    private final Random rnd = new Random();
    private final float u;
    private Bitmap photo;

    private boolean on;
    private float speed = 0.5f;        // target 0..1
    private float shown = 0f;          // smoothed
    private int ring = 0xFF66BB6A;
    private String number = "--";

    private long last;
    private float t, spawnD, spawnC;
    private Shader glowShader, streakShader, blueGlowShader;

    // geometry, recomputed per draw from the view size
    private float pw, ph, pl, pt;

    public AirView(Context c, AttributeSet a) {
        super(c, a);
        u = c.getResources().getDisplayMetrics().density;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inScaled = false;
        photo = BitmapFactory.decodeResource(c.getResources(), R.drawable.mi_purifier, o);
    }

    /** @param speed01 0..1 how fast the air moves; @param ringColor colour of the AQI light. */
    void setState(boolean on, float speed01, String number, int ringColor) {
        boolean wasOn = this.on;
        this.on = on;
        this.speed = Math.max(0f, Math.min(1f, speed01));
        this.number = number;
        if (ringColor != ring) { ring = ringColor; glowShader = null; }
        if (on != wasOn || last == 0) postInvalidateOnAnimation();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        glowShader = streakShader = blueGlowShader = null;
    }

    private void layoutPhoto() {
        ph = 182 * u;
        pw = ph * (photo == null ? 0.62f : photo.getWidth() / (float) photo.getHeight());
        pl = getWidth() / 2f - pw / 2f;
        pt = getHeight() - 24 * u - ph;
    }

    private float bodyL() { return pl + BODY_L * pw; }
    private float bodyR() { return pl + BODY_R * pw; }
    private float bodyCx() { return (bodyL() + bodyR()) / 2f; }
    private float vx() { return pl + GRILLE_X * pw; }          // top vent centre x
    private float vy() { return pt + GRILLE_Y * ph; }          // top vent centre y

    // ------------------------------------------------------------------ simulation

    private void step(float dt) {
        float cx = bodyCx(), half = (bodyR() - bodyL()) / 2f;
        float intakeY = pt + ph * 0.70f;

        if (on) {
            spawnD += dt * (5f + 14f * shown);
            spawnC += dt * (3f + 8f * shown);
        }
        float maxExtra = Math.max(24 * u, getWidth() / 2f - half - 12 * u);
        while (spawnD >= 1f && dirty.size() < 70) {
            spawnD -= 1f;
            P p = new P();
            int side = rnd.nextBoolean() ? -1 : 1;
            float dist = half + 14 * u + rnd.nextFloat() * maxExtra;
            p.x = cx + side * dist;
            p.y = intakeY - 40 * u + rnd.nextFloat() * 80 * u;
            float spd = (22 * u + 70 * u * shown) * (0.75f + 0.5f * rnd.nextFloat());
            p.vx = -side * spd;
            p.vy = (intakeY - p.y) / Math.max(1f, (dist - half) / spd) * 0.7f;
            p.size = 1.3f * u + rnd.nextFloat() * 2.2f * u;
            p.phase = rnd.nextFloat() * 6.28f;
            dirty.add(p);
        }
        spawnD = Math.min(spawnD, 2f);
        while (spawnC >= 1f && clean.size() < 40) {
            spawnC -= 1f;
            P p = new P();
            p.x = vx() + (rnd.nextFloat() - 0.5f) * pw * 0.45f;
            p.y = vy() - 2 * u;
            p.vy = -(30 * u + 60 * u * shown) * (0.8f + 0.4f * rnd.nextFloat());
            p.vx = (p.x - vx()) * 0.9f + (rnd.nextFloat() - 0.5f) * 10 * u;
            p.life = 1.4f + rnd.nextFloat() * 1.0f;
            p.size = 1.6f * u + rnd.nextFloat() * 1.8f * u;
            p.phase = rnd.nextFloat() * 6.28f;
            clean.add(p);
        }
        spawnC = Math.min(spawnC, 2f);

        for (Iterator<P> it = dirty.iterator(); it.hasNext(); ) {
            P p = it.next();
            p.age += dt;
            if (!on) { if (rnd.nextFloat() < 0.12f) it.remove(); continue; }
            p.x += p.vx * dt;
            p.y += p.vy * dt + (float) Math.sin(t * 4f + p.phase) * 6f * u * dt;
            if (p.x >= bodyL() + 1 * u && p.x <= bodyR() - 1 * u) it.remove();
        }
        for (Iterator<P> it = clean.iterator(); it.hasNext(); ) {
            P p = it.next();
            p.age += dt;
            if (!on) p.age += dt * 2f;
            p.y += p.vy * dt;
            p.x += p.vx * dt + (float) Math.sin(t * 3f + p.phase) * 8f * u * dt;
            if (p.age > p.life || p.y < -10 * u) it.remove();
        }
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onDraw(Canvas c) {
        long now = System.nanoTime();
        float dt = last == 0 ? 0.016f : Math.min(0.05f, (now - last) / 1e9f);
        last = now;
        t += dt;
        shown += ((on ? speed : 0f) - shown) * Math.min(1f, dt * 3f);
        layoutPhoto();
        step(dt);

        drawAura(c);
        drawCleanAir(c);
        drawDust(c);

        // ground shadow
        paint.reset(); paint.setAntiAlias(true);
        paint.setColor(0x2E324B6E);
        rf.set(pl + 0.04f * pw, pt + ph - 5 * u, pl + 1.0f * pw, pt + ph + 9 * u);
        c.drawOval(rf, paint);

        // the product photo
        if (photo != null) {
            paint.reset(); paint.setAntiAlias(true); paint.setFilterBitmap(true);
            rf.set(pl, pt, pl + pw, pt + ph);
            c.drawBitmap(photo, null, rf, paint);
        }
        drawFan(c);
        drawLed(c);
        drawBadge(c);

        // clean-air specks leaving the grille (on top of everything)
        for (P p : clean) {
            float f = p.age / p.life;
            float a = (1f - f) * Math.min(1f, p.age * 6f) * 0.85f * Math.max(0f, Math.min(1f, p.y / (30 * u)));
            float r = p.size * (1f + f * 0.8f);
            paint.reset(); paint.setAntiAlias(true);
            paint.setColor(Color.argb((int) (a * 80), 90, 170, 255));
            c.drawCircle(p.x, p.y, r * 2.4f, paint);
            paint.setColor(Color.argb((int) (a * 255), 120, 190, 255));
            c.drawCircle(p.x, p.y, r, paint);
        }

        if (on || !dirty.isEmpty() || !clean.isEmpty() || shown > 0.01f) {
            postInvalidateOnAnimation();
        } else {
            last = 0;   // idle: stop drawing completely
        }
    }

    /** Coloured glow behind the unit plus ripples spreading outwards. */
    private void drawAura(Canvas c) {
        if (shown < 0.02f && !on) return;
        float cx = bodyCx(), cy = pt + ph * 0.55f;
        if (glowShader == null) {
            glowShader = new RadialGradient(cx, cy, 135 * u,
                    (ring & 0x00FFFFFF) | 0x66000000, ring & 0x00FFFFFF, Shader.TileMode.CLAMP);
        }
        paint.reset(); paint.setAntiAlias(true);
        paint.setShader(glowShader);
        rf.set(cx - 135 * u, cy - 135 * u, cx + 135 * u, cy + 135 * u);
        c.drawOval(rf, paint);
        paint.setShader(null);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.5f * u);
        for (int k = 0; k < 3; k++) {
            float ph0 = (t * (0.18f + 0.3f * shown) + k / 3f) % 1f;
            float r = 58 * u + ph0 * 90 * u;
            paint.setColor((ring & 0x00FFFFFF) | ((int) ((1f - ph0) * 70 * Math.min(1f, shown * 2f)) << 24));
            rf.set(cx - r, cy - r * 1.1f, cx + r, cy + r * 1.1f);
            c.drawOval(rf, paint);
        }
    }

    /** Blue glow and fan-shaped streaks rising from the top grille. */
    private void drawCleanAir(Canvas c) {
        if (shown < 0.02f) return;
        float vx = vx(), vy = vy();
        if (blueGlowShader == null) {
            blueGlowShader = new RadialGradient(vx, vy, 90 * u, 0x704F9BFF, 0x004F9BFF, Shader.TileMode.CLAMP);
            streakShader = new LinearGradient(0, vy, 0, vy - 90 * u, 0xE05AA0FF, 0x005AA0FF, Shader.TileMode.CLAMP);
        }
        paint.reset(); paint.setAntiAlias(true);
        paint.setShader(blueGlowShader);
        rf.set(vx - 90 * u, vy - 90 * u, vx + 90 * u, vy + 10 * u);
        c.drawOval(rf, paint);

        paint.reset(); paint.setAntiAlias(true);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setShader(streakShader);
        for (int k = -4; k <= 4; k++) {
            float spread = k * 14f * u;
            float len = 60 * u + 20 * u * (1f - Math.abs(k) / 4f);
            float sway = (float) Math.sin(t * 1.7f + k * 0.8f) * 4f * u;
            path.reset();
            path.moveTo(vx + k * 6f * u, vy);
            path.cubicTo(vx + k * 7f * u + sway, vy - len * 0.35f,
                    vx + spread * 0.9f, vy - len * 0.7f, vx + spread * 1.15f + sway, vy - len);
            float dash = 14 * u;
            paint.setPathEffect(new DashPathEffect(new float[]{dash, dash * 0.7f}, -t * 70f * u * (0.4f + shown)));
            paint.setStrokeWidth((k == 0 ? 3.6f : 2.6f) * u);
            c.drawPath(path, paint);
        }
        paint.setPathEffect(null);
        paint.setShader(null);
    }

    /** Dusty specks drifting in plus two white inflow arrows. */
    private void drawDust(Canvas c) {
        float cx = bodyCx(), half = (bodyR() - bodyL()) / 2f;
        for (P p : dirty) {
            float dist = Math.abs(p.x - cx) - half;
            float edge = Math.max(0f, Math.min(1f, dist / (16 * u)));
            float a = Math.min(1f, p.age * 2.5f) * 0.85f * (0.3f + 0.7f * edge);
            paint.reset(); paint.setAntiAlias(true);
            paint.setColor(Color.argb((int) (a * 255), 141, 118, 98));
            c.drawCircle(p.x, p.y, p.size * (0.55f + 0.45f * edge), paint);
        }
        if (shown < 0.02f) return;

        float midY = pt + ph * 0.70f;
        for (int side = -1; side <= 1; side += 2) {
            float x0 = cx + side * (half + 78 * u), y0 = midY - 24 * u;
            float xc = cx + side * (half + 42 * u), yc = midY - 34 * u;
            float x1 = cx + side * (half + 9 * u), y1 = midY - 2 * u;
            path.reset();
            path.moveTo(x0, y0);
            path.quadTo(xc, yc, x1, y1);
            float pulse = 0.55f + 0.45f * (float) Math.sin(t * (2f + 3f * shown) + (side > 0 ? 1.5f : 0f));
            int alpha = (int) (255 * Math.min(1f, shown * 2f) * pulse);
            paint.reset(); paint.setAntiAlias(true);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(3.2f * u);
            paint.setPathEffect(new DashPathEffect(new float[]{16 * u, 7 * u}, t * 38f * u * (0.4f + shown)));
            paint.setColor(Color.argb(alpha, 245, 166, 35));
            c.drawPath(path, paint);
            paint.setPathEffect(null);

            float dx = x1 - xc, dy = y1 - yc;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            dx /= len; dy /= len;
            float px = -dy, py = dx;
            path.reset();
            path.moveTo(x1 + dx * 6 * u, y1 + dy * 6 * u);
            path.lineTo(x1 - dx * 5 * u + px * 5 * u, y1 - dy * 5 * u + py * 5 * u);
            path.lineTo(x1 - dx * 5 * u - px * 5 * u, y1 - dy * 5 * u - py * 5 * u);
            path.close();
            paint.setStyle(Paint.Style.FILL);
            c.drawPath(path, paint);
        }
    }

    /** Spinning shimmer over the top grille so the fan looks like it is running. */
    private void drawFan(Canvas c) {
        if (shown < 0.02f) return;
        float gx = pl + GRILLE_X * pw, gy = pt + GRILLE_Y * ph;
        float rx = GRILLE_RX * pw, ry = GRILLE_RY * ph;
        float ang = t * (2f + 10f * shown);
        paint.reset(); paint.setAntiAlias(true);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(2.2f * u);
        for (int i = 0; i < 4; i++) {
            float a = ang + i * (float) Math.PI / 2f;
            paint.setColor(i % 2 == 0 ? 0x40FFFFFF : 0x30303A45);
            c.drawLine(gx, gy, gx + (float) Math.cos(a) * rx * 0.95f, gy + (float) Math.sin(a) * ry * 0.95f, paint);
        }
    }

    /** The unit's own air-quality LED at the bottom front, glowing in the AQI colour. */
    private void drawLed(Canvas c) {
        float x = pl + LED_X * pw, y = pt + LED_Y * ph;
        paint.reset(); paint.setAntiAlias(true);
        if (on) {
            float pulse = 0.6f + 0.4f * (float) Math.sin(t * (1.5f + 2f * shown));
            paint.setColor((ring & 0x00FFFFFF) | ((int) (0x40 + 0x40 * pulse) << 24));
            c.drawCircle(x, y, 7 * u, paint);
            paint.setColor((ring & 0x00FFFFFF) | 0xFF000000);
            c.drawCircle(x, y, 2.4f * u, paint);
        } else {
            paint.setColor(0xFFB7C0CC);
            c.drawCircle(x, y, 1.8f * u, paint);
        }
    }

    /** Small AQI chip beside the unit (the real unit has no display). */
    private void drawBadge(Canvas c) {
        String label = on ? "AQI " + number : "OFF";
        paint.reset(); paint.setAntiAlias(true);
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        paint.setTextSize(12 * u);
        float tw = paint.measureText(label);
        float padX = 10 * u, h = 26 * u, dot = 5 * u;
        float w = tw + padX * 2 + (on ? dot * 2 + 6 * u : 0);
        float x = Math.min(getWidth() - w - 6 * u, pl + pw + 10 * u), y = pt + 4 * u;
        paint.setColor(0x22324B6E);
        rf.set(x, y + 2 * u, x + w, y + h + 2 * u);
        c.drawRoundRect(rf, h / 2, h / 2, paint);
        paint.setColor(0xFFFFFFFF);
        rf.set(x, y, x + w, y + h);
        c.drawRoundRect(rf, h / 2, h / 2, paint);
        float tx = x + padX;
        if (on) {
            paint.setColor((ring & 0x00FFFFFF) | 0xFF000000);
            c.drawCircle(x + padX + dot, y + h / 2, dot, paint);
            tx += dot * 2 + 6 * u;
        }
        paint.setColor(on ? 0xFF1B2430 : 0xFF8996A5);
        paint.setTextAlign(Paint.Align.LEFT);
        c.drawText(label, tx, y + h / 2 + 4.3f * u, paint);
    }
}
