package com.dennis.masterroom.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

import java.util.Locale;

/**
 * Compact precision monitor. Golden flag = selected loudness target; white needle = real
 * measured source LUFS. No measured needle or displayed loudness is ever fabricated.
 */
final class MeterView extends View {
    private static final float W = 342f, H = 138f;
    private static final float CX = 171f, CY = 142f, R = 105f, LOW = -24f, HIGH = -6f;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF full = new RectF(0, 0, W, H);
    private final RectF arc = new RectF(CX - R, CY - R, CX + R, CY + R);
    private final RectF glass = new RectF(4, 4, W - 4, 105);
    private float target = -14, measured = Float.NaN;

    MeterView(Context c) { super(c); describe(); }
    void setTarget(double lufs) { target = (float) lufs; describe(); invalidate(); }
    void setMeasured(double lufs) { measured = (float) lufs; describe(); invalidate(); }
    private void describe() {
        setContentDescription(String.format(Locale.US, "Target %.1f LUFS. ", target)
                + (Float.isFinite(measured) ? String.format(Locale.US, "Measured source %.1f LUFS", measured)
                : "Source not measured yet"));
    }
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        setMeasuredDimension(width, Math.round(width * H / W));
    }
    private static float clamp(float n) { return Math.max(LOW, Math.min(HIGH, n)); }
    private static double angle(float n) {
        return Math.toRadians(-62 + (clamp(n) - LOW) / (HIGH - LOW) * 124);
    }
    private static float x(float n, float r) { return CX + r * (float) Math.sin(angle(n)); }
    private static float y(float n, float r) { return CY - r * (float) Math.cos(angle(n)); }
    private static float arcAngle(float n) { return -90 + (float) Math.toDegrees(angle(n)); }

    @Override protected void onDraw(Canvas c) {
        float s = getWidth() / W;
        c.save();
        c.scale(s, s);
        p.setStyle(Paint.Style.FILL);
        p.setShader(new LinearGradient(0, 0, 0, H,
                new int[]{0xFF2C333E, 0xFF080B10, 0xFF171D26}, null, Shader.TileMode.CLAMP));
        c.drawRoundRect(full, 8, 8, p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.25f);
        p.setColor(0xFF56515A);
        c.drawRoundRect(full, 8, 8, p);

        // Smoked glass, inset specular highlights and calibrated arced scale.
        p.setStyle(Paint.Style.FILL);
        p.setShader(new LinearGradient(0, 3, 0, 105,
                new int[]{0xFF161D27, 0xFF0B1018, 0xFF04070A}, null, Shader.TileMode.CLAMP));
        c.drawRoundRect(glass, 7, 7, p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1);
        p.setColor(0xFF353D47);
        c.drawRoundRect(glass, 7, 7, p);

        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(2f);
        p.setColor(0xFFBAAB8D);
        c.drawArc(arc, arcAngle(LOW), arcAngle(-9) - arcAngle(LOW), false, p);
        p.setStrokeWidth(3.5f);
        p.setColor(0xFFCB6550);
        c.drawArc(arc, arcAngle(-9), arcAngle(HIGH) - arcAngle(-9), false, p);

        for (int n = (int) LOW; n <= (int) HIGH; n++) {
            boolean major = (n % 4 == 0);
            p.setColor(n >= -9 ? 0xFFE17960 : 0xFFCCC4B3);
            p.setStrokeWidth(major ? 1.8f : 0.95f);
            float length = major ? 10f : n % 2 == 0 ? 7f : 4f;
            c.drawLine(x(n, R), y(n, R), x(n, R + length), y(n, R + length), p);
        }

        p.setStyle(Paint.Style.FILL);
        p.setTypeface(Look.label);
        p.setTextSize(13);
        p.setTextAlign(Paint.Align.CENTER);
        p.setColor(0xFFF1E8D8);
        for (int n : new int[]{-24, -20, -16, -12, -8}) {
            c.drawText(String.valueOf(n), x(n, R + 20), y(n, R + 20) + 4, p);
        }

        // Selectable target: one short triangular marker. It does not impersonate a reading.
        float ax = x(target, R - 3), ay = y(target, R - 3);
        float bx = x(target, R - 17), by = y(target, R - 17);
        float dx = (ay - by) * 0.40f, dy = (bx - ax) * 0.40f;
        path.reset();
        path.moveTo(ax, ay);
        path.lineTo(bx + dx, by + dy);
        path.lineTo(bx - dx, by - dy);
        path.close();
        p.setColor(Look.AMBER);
        c.drawPath(path, p);

        if (Float.isFinite(measured)) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2.15f);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setColor(0xFFFAF5ED);
            c.drawLine(CX, 101, x(measured, R + 2), y(measured, R + 2), p);
        } else {
            p.setTypeface(Look.label);
            p.setTextSize(12);
            p.setColor(0xFF97A3B2);
            c.drawText("AWAITING ANALYSIS", CX, 98, p);
        }

        // Small built-in readout, with clear distinction between chosen and measured data.
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF101721);
        c.drawRoundRect(new RectF(5, 107, W - 5, 135), 4, 4, p);
        p.setTypeface(Look.label);
        p.setTextSize(11);
        p.setTextAlign(Paint.Align.LEFT);
        p.setColor(0xFFB8A37D);
        c.drawText("TARGET", 13, 118, p);
        p.setTypeface(Look.mono);
        p.setTextSize(14);
        p.setColor(Look.AMBER);
        c.drawText(String.format(Locale.US, "%.1f", target), 13, 132, p);

        p.setTextAlign(Paint.Align.RIGHT);
        p.setTypeface(Look.label);
        p.setTextSize(11);
        p.setColor(0xFF9AA9BA);
        c.drawText("SOURCE  ·  LUFS", W - 13, 118, p);
        p.setTypeface(Look.mono);
        p.setTextSize(14);
        p.setColor(Look.TEXT);
        c.drawText(Float.isFinite(measured) ? String.format(Locale.US, "%.1f", measured) : "--", W - 13, 132, p);
        c.restore();
    }
}
