package com.dennis.masterroom.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Backlit VU-style meter. The needle sits on the loudness you have asked for. */
final class MeterView extends View {
    private static final float W = 342, H = 196, CX = 171, CY = 240, R = 186;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF(CX - R, CY - R, CX + R, CY + R), face = new RectF(0, 0, W, H);
    private float value = -14;
    private ValueAnimator swing;

    MeterView(Context c) {
        super(c);
        setContentDescription("Loudness meter");
    }

    void setValue(double lufs) {
        if (swing != null) swing.cancel();
        swing = ValueAnimator.ofFloat(value, (float) lufs);
        swing.setDuration(420);
        swing.addUpdateListener(a -> { value = (Float) a.getAnimatedValue(); invalidate(); });
        swing.start();
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        setMeasuredDimension(w, Math.round(w * H / W));
    }

    private static double angle(float v) { return Math.toRadians(-44 + (v + 20) / 11 * 88); }
    private static float x(float v, float r) { return CX + r * (float) Math.sin(angle(v)); }
    private static float y(float v, float r) { return CY - r * (float) Math.cos(angle(v)); }
    private static float canvasAngle(float v) { return -90 + (float) Math.toDegrees(angle(v)); }

    @Override protected void onDraw(Canvas c) {
        float s = getWidth() / W;
        c.save();
        c.scale(s, s);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Look.FACE);
        c.drawRoundRect(face, 3, 3, paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStrokeWidth(2);
        paint.setColor(Look.FACE_INK);
        c.drawArc(oval, canvasAngle(-20), canvasAngle(-10) - canvasAngle(-20), false, paint);
        paint.setColor(Look.HOT);
        paint.setStrokeWidth(6);
        c.drawArc(oval, canvasAngle(-10), canvasAngle(-9) - canvasAngle(-10), false, paint);
        for (int v = -20; v <= -9; v++) {
            paint.setColor(v >= -10 ? Look.HOT : Look.FACE_INK);
            paint.setStrokeWidth(v >= -10 ? 2.5f : 2f);
            float len = v % 2 == 0 ? 14 : 8;
            c.drawLine(x(v, R), y(v, R), x(v, R + len), y(v, R + len), paint);
        }

        paint.setStyle(Paint.Style.FILL);
        paint.setTypeface(Look.label);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(17);
        paint.setColor(Look.FACE_INK);
        for (int v : new int[]{-20, -16, -14, -12, -10}) c.drawText(String.valueOf(v), x(v, R + 26), y(v, R + 26) + 5, paint);
        paint.setTextSize(15);
        paint.setLetterSpacing(0.2f);
        paint.setColor(Look.FACE_DIM);
        c.drawText("LUFS", CX, 132, paint);
        paint.setLetterSpacing(0);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(2.5f);
        paint.setColor(Look.FACE_INK);
        c.drawLine(x(value, 58), y(value, 58), x(value, R + 6), y(value, R + 6), paint);

        paint.setStyle(Paint.Style.FILL);
        c.drawRect(0, 168, W, H, paint);
        c.restore();
    }
}
