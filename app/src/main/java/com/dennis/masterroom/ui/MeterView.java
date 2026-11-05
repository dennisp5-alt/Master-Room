package com.dennis.masterroom.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

import java.util.Locale;

/**
 * Light Arc monitor. The gold marker is the chosen target. The lights show the source's
 * measured loudness, or follow the song's real level while it plays. Nothing here is
 * ever invented: with nothing measured, the arc stays dark.
 */
final class MeterView extends View {
    /** The song while it plays. */
    interface Live {
        boolean on();
        /** Fast level for the lights. */
        float light();
        /** Momentary loudness for the number. */
        float lufs();
    }

    private static final float W = 342f, H = 188f, CX = 171f, CY = 160f, R = 128f, LENGTH = 22f;
    private static final float LOW = -24f, HIGH = -6f, FALL = 0.14f;
    private static final int SEGMENTS = 33;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF full = new RectF(0, 0, W, H);
    private float target = -14, measured = Float.NaN, shown = LOW;
    private Live live;

    MeterView(Context c) { super(c); describe(); }
    void setTarget(double lufs) { target = (float) lufs; describe(); invalidate(); }
    void setMeasured(double lufs) { measured = (float) lufs; describe(); invalidate(); }
    void setLive(Live l) { live = l; invalidate(); }

    private void describe() {
        setContentDescription(String.format(Locale.US, "Target %.1f LUFS. ", target)
                + (Float.isFinite(measured) ? String.format(Locale.US, "Measured source %.1f LUFS", measured)
                : "Source not measured yet"));
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        setMeasuredDimension(width, Math.round(width * H / W));
    }

    private static double angle(float lufs) {
        float at = (Math.max(LOW, Math.min(HIGH, lufs)) - LOW) / (HIGH - LOW);
        return Math.toRadians(-82 + at * 164);
    }

    private static int colour(float lufs) {
        return lufs < -14 ? 0xFF27E36B : lufs < -10 ? 0xFFFFB83D : 0xFFFF3B30;
    }

    @Override protected void onDraw(Canvas c) {
        float s = getWidth() / W;
        boolean playing = live != null && live.on();
        float wanted = playing ? live.light() : Float.isFinite(measured) ? measured : LOW - 1;
        shown = wanted > shown ? wanted : shown + (wanted - shown) * FALL;
        if (playing || Math.abs(wanted - shown) > 0.05f) postInvalidateOnAnimation();

        c.save();
        c.scale(s, s);
        p.setStyle(Paint.Style.FILL);
        p.setShader(null);
        p.setColor(0x24000000);
        c.drawRoundRect(full, 8, 8, p);

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        for (int i = 0; i < SEGMENTS; i++) {
            float lufs = LOW + (HIGH - LOW) * i / (SEGMENTS - 1);
            double a = angle(lufs);
            float sin = (float) Math.sin(a), cos = (float) Math.cos(a);
            float x1 = CX + R * sin, y1 = CY - R * cos, x2 = CX + (R - LENGTH) * sin, y2 = CY - (R - LENGTH) * cos;
            boolean lit = lufs <= shown;
            int colour = colour(lufs);
            if (lit) {
                p.setColor(colour & 0x00FFFFFF | 0x55000000);
                p.setStrokeWidth(13);
                c.drawLine(x1, y1, x2, y2, p);
            }
            p.setColor(lit ? colour : colour & 0x00FFFFFF | 0x26000000);
            p.setStrokeWidth(7);
            c.drawLine(x1, y1, x2, y2, p);
        }

        // The chosen target, outside the lights so it never reads as a measurement.
        double t = angle(target);
        float sin = (float) Math.sin(t), cos = (float) Math.cos(t);
        float ax = CX + (R + 6) * sin, ay = CY - (R + 6) * cos, bx = CX + (R + 19) * sin, by = CY - (R + 19) * cos;
        float dx = (ay - by) * 0.45f, dy = (bx - ax) * 0.45f;
        path.reset();
        path.moveTo(ax, ay);
        path.lineTo(bx + dx, by + dy);
        path.lineTo(bx - dx, by - dy);
        path.close();
        p.setStyle(Paint.Style.FILL);
        p.setColor(Look.AMBER);
        c.drawPath(path, p);

        p.setTextAlign(Paint.Align.CENTER);
        p.setTypeface(Look.mono);
        p.setTextSize(30);
        p.setColor(Look.TEXT);
        float number = playing ? live.lufs() : measured;
        c.drawText(Float.isFinite(number) ? String.format(Locale.US, "%.1f", number) : "--", CX, 118, p);
        p.setTypeface(Look.label);
        p.setTextSize(11);
        p.setColor(0xFFA5ADB9);
        c.drawText(playing ? "NOW  ·  LUFS" : Float.isFinite(measured) ? "SOURCE  ·  LUFS" : "AWAITING ANALYSIS", CX, 134, p);
        p.setTextSize(13);
        p.setColor(Look.AMBER);
        c.drawText(String.format(Locale.US, "TARGET %.1f LUFS", target), CX, 156, p);
        p.setTextAlign(Paint.Align.LEFT);
        p.setColor(0xFFA5ADB9);
        p.setTextSize(11);
        c.drawText("-24", 12, 178, p);
        p.setTextAlign(Paint.Align.RIGHT);
        c.drawText("-6", W - 12, 178, p);
        c.restore();
    }
}
