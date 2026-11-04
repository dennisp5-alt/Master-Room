package com.dennis.masterroom.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

import com.dennis.masterroom.engine.Audio;

/** The song as a strip. Tap or drag anywhere to jump there. */
final class WaveView extends View {
    interface Seek { void to(double fraction); }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float[] bars = new float[0];
    private double fraction;
    private Seek seek;

    WaveView(Context c) {
        super(c);
        setContentDescription("Waveform. Tap to move through the song");
    }

    void setBars(float[] b) { bars = b; invalidate(); }
    void setSeek(Seek s) { seek = s; }
    void setFraction(double f) { fraction = f; invalidate(); }

    /** Loudness of each slice, scaled so the shape reads clearly at phone size. */
    static float[] measure(Audio a, int count) {
        float[] out = new float[count];
        int step = Math.max(1, a.frames() / count);
        float top = 1e-6f;
        for (int b = 0; b < count; b++) {
            double sum = 0;
            int from = b * step;
            int to = Math.min(a.frames(), from + step);
            for (int i = from; i < to; i++) sum += a.l[i] * a.l[i] + a.r[i] * a.r[i];
            out[b] = (float) Math.sqrt(sum / Math.max(1, to - from));
            top = Math.max(top, out[b]);
        }
        for (int b = 0; b < count; b++) { float v = out[b] / top; out[b] = v * v; }
        return out;
    }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        float step = bars.length == 0 ? 0 : (float) w / bars.length;
        float width = Math.max(1, step * 0.62f), mid = h / 2f;
        int head = (int) Math.round(fraction * bars.length);
        for (int i = 0; i < bars.length; i++) {
            float size = Math.max(Look.dp(3), bars[i] * (h - Look.dp(4)));
            paint.setColor(i < head ? Look.AMBER : Look.BAR);
            c.drawRect(i * step, mid - size / 2, i * step + width, mid + size / 2, paint);
        }
        paint.setColor(Look.TEXT);
        float x = (float) fraction * w;
        c.drawRect(x, 0, x + Look.dp(2), h, paint);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP) {
            getParent().requestDisallowInterceptTouchEvent(action != MotionEvent.ACTION_UP);
            double f = Math.max(0, Math.min(1, e.getX() / Math.max(1, getWidth())));
            fraction = f;
            if (seek != null) seek.to(f);
            invalidate();
            if (action == MotionEvent.ACTION_UP) performClick();
            return true;
        }
        return super.onTouchEvent(e);
    }

    @Override public boolean performClick() { return super.performClick(); }
}
