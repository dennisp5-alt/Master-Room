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
 * Analogue-inspired source loudness instrument. The amber flag marks the target, while the
 * white needle and numeric source readout only appear after a real BS.1770 measurement.
 * This view draws no animated or synthetic audio measurements.
 */
final class MeterView extends View {
    private static final float W = 342f, H = 221f;
    private static final float CX = 171f, CY = 185f, R = 137f;
    private static final float LOW = -24f, HIGH = -6f;
    private static final int INK = 0xFF28251F, PAPER = 0xFFF5DFAF, PALE = 0xFFECD3A0;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path flag = new Path();
    private final RectF dial = new RectF(CX - R, CY - R, CX + R, CY + R);
    private final RectF body = new RectF(0, 0, W, H);
    private final RectF paper = new RectF(5, 5, W - 5, 164);
    private float target = -14f, measured = Float.NaN;

    MeterView(Context c) { super(c); describe(); }

    void setTarget(double v) { target = (float) v; describe(); invalidate(); }
    void setMeasured(double v) { measured = (float) v; describe(); invalidate(); }

    private void describe() {
        setContentDescription(String.format(Locale.US, "Master target %.1f LUFS. ", target)
                + (Float.isNaN(measured) ? "Source awaiting measurement"
                : String.format(Locale.US, "Measured source %.1f LUFS", measured)));
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        setMeasuredDimension(width, Math.round(width * H / W));
    }

    private static float clamp(float n) { return Math.min(HIGH, Math.max(LOW, n)); }
    private static double theta(float n) {
        return Math.toRadians(-64 + 128 * (clamp(n) - LOW) / (HIGH - LOW));
    }
    private static float x(float v, float radius) { return CX + radius * (float) Math.sin(theta(v)); }
    private static float y(float v, float radius) { return CY - radius * (float) Math.cos(theta(v)); }
    private static float arcAngle(float v) { return -90f + (float) Math.toDegrees(theta(v)); }

    @Override protected void onDraw(Canvas c) {
        float scale = getWidth() / W;
        c.save();
        c.scale(scale, scale);

        // Inset enclosure with a warm, softly graduated VU-meter face.
        p.setStyle(Paint.Style.FILL);
        p.setShader(new LinearGradient(0, 0, 0, H,
                new int[]{0xFF333740, 0xFF11161D, 0xFF05070A}, null, Shader.TileMode.CLAMP));
        c.drawRoundRect(body, 10, 10, p);
        p.setShader(new LinearGradient(0, 0, 0, 164,
                new int[]{PAPER, 0xFFF0D7A5, PALE}, null, Shader.TileMode.CLAMP));
        c.drawRoundRect(paper, 7, 7, p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1f);
        p.setColor(0xFFBAA57D);
        c.drawRoundRect(paper, 7, 7, p);

        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStyle(Paint.Style.STROKE);
        p.setColor(INK);
        p.setStrokeWidth(1.7f);
        c.drawArc(dial, arcAngle(LOW), arcAngle(-9f) - arcAngle(LOW), false, p);
        p.setColor(0xFFAE4031);
        p.setStrokeWidth(4.1f);
        c.drawArc(dial, arcAngle(-9f), arcAngle(HIGH) - arcAngle(-9f), false, p);

        for (int n = (int) LOW; n <= (int) HIGH; n++) {
            boolean major = n % 4 == 0;
            p.setColor(n >= -9 ? 0xFFAE4031 : INK);
            p.setStrokeWidth(major ? 2.0f : 1.2f);
            float length = major ? 11 : (n % 2 == 0 ? 8 : 5);
            c.drawLine(x(n, R + 2), y(n, R + 2), x(n, R + length + 2), y(n, R + length + 2), p);
        }

        p.setStyle(Paint.Style.FILL);
        p.setTypeface(Look.label);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(15);
        p.setColor(INK);
        for (int n : new int[]{-24, -20, -16, -12, -8}) {
            c.drawText(String.valueOf(n), x(n, R + 27), y(n, R + 27) + 5, p);
        }

        // Short amber flag remains meaningful even before the source is analysed.
        float tipX = x(target, R - 2), tipY = y(target, R - 2);
        float baseX = x(target, R - 18), baseY = y(target, R - 18);
        float dx = (tipY - baseY) * 0.40f, dy = (baseX - tipX) * 0.40f;
        flag.reset();
        flag.moveTo(tipX, tipY);
        flag.lineTo(baseX + dx, baseY + dy);
        flag.lineTo(baseX - dx, baseY - dy);
        flag.close();
        p.setColor(0xFFCE8629);
        c.drawPath(flag, p);

        if (!Float.isNaN(measured) && !Float.isInfinite(measured)) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(2.4f);
            p.setColor(0xFF29251C);
            c.drawLine(CX, CY, x(measured, R + 2), y(measured, R + 2), p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF312B22);
            c.drawCircle(CX, CY, 4f, p);
        } else {
            p.setStyle(Paint.Style.FILL);
            p.setTypeface(Look.body);
            p.setTextSize(13);
            p.setColor(0xFF716757);
            c.drawText("AWAITING SOURCE ANALYSIS", CX, 144, p);
        }

        // The black illuminated readout is part of the meter, not a separate fake display.
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF10151B);
        c.drawRoundRect(new RectF(5, 164, W - 5, H - 5), 7, 7, p);
        p.setTextAlign(Paint.Align.LEFT);
        p.setTypeface(Look.label);
        p.setTextSize(11.5f);
        p.setColor(0xFFBBA988);
        c.drawText("TARGET", 18, 183, p);
        p.setColor(Look.AMBER);
        p.setTypeface(Look.mono);
        p.setTextSize(21);
        c.drawText(String.format(Locale.US, "%.1f", target), 18, 207, p);
        p.setTypeface(Look.label);
        p.setTextSize(11.5f);
        p.setTextAlign(Paint.Align.RIGHT);
        p.setColor(0xFFABB4BE);
        c.drawText("MEASURED SOURCE", W - 17, 183, p);
        p.setTypeface(Look.mono);
        p.setTextSize(21);
        p.setColor(Look.TEXT);
        c.drawText(Float.isNaN(measured) || Float.isInfinite(measured) ? "--"
                : String.format(Locale.US, "%.1f", measured), W - 17, 207, p);
        c.restore();
    }
}
