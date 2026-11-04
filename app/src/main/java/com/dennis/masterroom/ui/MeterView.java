package com.dennis.masterroom.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * Loudness scale. The amber marker is the target you chose. The needle is the source's
 * measured integrated loudness and is only drawn once that measurement exists.
 */
final class MeterView extends View {
    private static final float W = 342, H = 196, CX = 171, CY = 246, R = 186, LOW = -24, HIGH = -6;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mark = new Path();
    private final RectF oval = new RectF(CX - R, CY - R, CX + R, CY + R), face = new RectF(0, 0, W, H);
    private float target = -14, measured = Float.NaN;

    MeterView(Context c) {
        super(c);
        describe();
    }

    void setTarget(double lufs) { target = (float) lufs; describe(); invalidate(); }

    /** NaN means nothing has been measured yet, so no needle is drawn. */
    void setMeasured(double lufs) { measured = (float) lufs; describe(); invalidate(); }

    private void describe() {
        setContentDescription("Target " + target + " LUFS. "
                + (Float.isNaN(measured) ? "Source not measured yet" : "Source measured at " + measured + " LUFS"));
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        setMeasuredDimension(w, Math.round(w * H / W));
    }

    private static float clamp(float v) { return Math.max(LOW, Math.min(HIGH, v)); }
    private static double angle(float v) { return Math.toRadians(-40 + (clamp(v) - LOW) / (HIGH - LOW) * 80); }
    private static float x(float v, float r) { return CX + r * (float) Math.sin(angle(v)); }
    private static float y(float v, float r) { return CY - r * (float) Math.cos(angle(v)); }
    private static float canvasAngle(float v) { return -90 + (float) Math.toDegrees(angle(v)); }

    @Override protected void onDraw(Canvas c) {
        float s = getWidth() / W;
        c.save();
        c.scale(s, s);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Look.WELL);
        c.drawRoundRect(face, 6, 6, paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStrokeWidth(1.5f);
        paint.setColor(Look.MID);
        c.drawArc(oval, canvasAngle(LOW), canvasAngle(-9) - canvasAngle(LOW), false, paint);
        paint.setColor(Look.HOT);
        paint.setStrokeWidth(4);
        c.drawArc(oval, canvasAngle(-9), canvasAngle(HIGH) - canvasAngle(-9), false, paint);
        for (int v = (int) LOW; v <= HIGH; v++) {
            boolean major = v % 2 == 0;
            paint.setColor(v >= -9 ? Look.HOT : Look.MID);
            paint.setStrokeWidth(major ? 1.8f : 1f);
            float len = major ? 12 : 6;
            c.drawLine(x(v, R), y(v, R), x(v, R + len), y(v, R + len), paint);
        }

        paint.setStyle(Paint.Style.FILL);
        paint.setTypeface(Look.label);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(16);
        paint.setColor(Look.MID);
        for (int v : new int[]{-24, -20, -16, -12, -8}) c.drawText(String.valueOf(v), x(v, R + 24), y(v, R + 24) + 5, paint);

        float tx = x(target, R - 3), ty = y(target, R - 3), bx = x(target, R - 19), by = y(target, R - 19);
        float nx = (ty - by) * 0.42f, ny = (bx - tx) * 0.42f;
        mark.reset();
        mark.moveTo(tx, ty);
        mark.lineTo(bx + nx, by + ny);
        mark.lineTo(bx - nx, by - ny);
        mark.close();
        paint.setColor(Look.AMBER);
        c.drawPath(mark, paint);

        if (Float.isNaN(measured)) {
            paint.setTypeface(Look.body);
            paint.setTextSize(15);
            paint.setColor(Look.DIM);
            c.drawText("Source not measured yet", CX, 128, paint);
        } else {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(2.2f);
            paint.setColor(Look.TEXT);
            c.drawLine(x(measured, 58), y(measured, 58), x(measured, R + 4), y(measured, R + 4), paint);
        }

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Look.PANEL);
        c.drawRect(0, 164, W, H, paint);
        paint.setTypeface(Look.label);
        paint.setTextSize(13);
        paint.setLetterSpacing(0.1f);
        paint.setColor(Look.AMBER);
        paint.setTextAlign(Paint.Align.LEFT);
        c.drawText("MARKER: TARGET", 10, 185, paint);
        paint.setColor(Look.MID);
        paint.setTextAlign(Paint.Align.RIGHT);
        c.drawText("NEEDLE: MEASURED SOURCE", W - 10, 185, paint);
        paint.setLetterSpacing(0);
        c.restore();
    }
}
