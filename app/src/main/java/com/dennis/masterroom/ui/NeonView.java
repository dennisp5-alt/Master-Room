package com.dennis.masterroom.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import com.dennis.masterroom.engine.Spectrum;

/** Rainbow EQ bars from the audio being heard. At rest, a dim picture of the take's tone. */
final class NeonView extends View {
    /** Fills the bars for this moment. Returns false when nothing is playing. */
    interface Source { boolean now(float[] out); }

    private static final float FALL = 0.86f;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final float[] wanted = new float[Spectrum.BARS], shown = new float[Spectrum.BARS];
    private final int[] colours = new int[Spectrum.BARS];
    private float[] resting = new float[Spectrum.BARS];
    private Source source;
    private float light;

    NeonView(Context c) {
        super(c);
        setContentDescription("EQ lights following the music");
        for (int i = 0; i < colours.length; i++) colours[i] = Color.HSVToColor(new float[]{i * 280f / (colours.length - 1), 0.85f, 1f});
    }

    void setSource(Source s) { source = s; }
    void setResting(float[] r) { resting = r; invalidate(); }
    /** Call when playback starts. */
    void wake() { invalidate(); }

    @Override protected void onDraw(Canvas c) {
        boolean live = source != null && source.now(wanted);
        if (!live) System.arraycopy(resting, 0, wanted, 0, wanted.length);
        boolean moving = live;
        for (int i = 0; i < shown.length; i++) {
            float before = shown[i];
            shown[i] = wanted[i] > before ? wanted[i] : wanted[i] + (before - wanted[i]) * FALL;
            if (Math.abs(shown[i] - wanted[i]) > 0.004f) moving = true;
        }
        light += ((live ? 1f : 0.4f) - light) * 0.15f;
        if (Math.abs(light - (live ? 1f : 0.4f)) > 0.01f) moving = true;
        if (moving) postInvalidateOnAnimation();

        int w = getWidth(), total = getHeight(), mirror = Look.dp(26), h = total - mirror - Look.dp(3);
        float step = (float) w / shown.length, width = step * 0.62f, glow = Look.dp(3), corner = Look.dp(3);
        int alpha = (int) (255 * light);
        for (int i = 0; i < shown.length; i++) {
            float x = i * step + (step - width) / 2, size = Math.max(Look.dp(4), shown[i] * h);
            int colour = colours[i];
            paint.setColor(colour);
            paint.setAlpha(alpha / 4);
            box.set(x - glow, h - size - glow, x + width + glow, h);
            c.drawRoundRect(box, corner, corner, paint);
            paint.setAlpha(alpha);
            box.set(x, h - size, x + width, h);
            c.drawRoundRect(box, corner, corner, paint);
            paint.setAlpha(alpha / 4);
            box.set(x, h + Look.dp(3), x + width, h + Look.dp(3) + size * mirror / h);
            c.drawRoundRect(box, corner, corner, paint);
        }
    }
}
