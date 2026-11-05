package com.dennis.masterroom.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.Choreographer;
import android.view.View;
import android.widget.FrameLayout;

/**
 * Red, green and violet light behind the glass. While music plays they pulse with its
 * lows, mids and highs; otherwise they rest. Only each light's opacity and position move,
 * so the screen behind the panels never has to be drawn again.
 */
final class GlassBackdrop extends FrameLayout implements Choreographer.FrameCallback {
    /** Fills lows, mids and highs from 0 to 1. Returns false when nothing is playing. */
    interface Source { boolean now(float[] three); }

    private static final float REST = 0.32f, FALL = 0.9f;
    private static final float[][] PLACES = {{0.20f, 0.13f, 0.66f}, {0.85f, 0.66f, 0.70f}, {0.26f, 0.84f, 0.62f}};
    private static final int[] COLOURS = {0xCCFF3B30, 0xB827E36B, 0xCC7A3CFF};

    private final Glow[] glows = new Glow[3];
    private final float[] wanted = new float[3], shown = {REST, REST, REST};
    private Source source;
    private boolean attached;

    GlassBackdrop(Context c) {
        super(c);
        setBackgroundColor(0xFF0A0D12);
        for (int i = 0; i < 3; i++) {
            glows[i] = new Glow(c, PLACES[i], COLOURS[i]);
            glows[i].setAlpha(REST);
            addView(glows[i], new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        }
    }

    void setSource(Source s) { source = s; }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override protected void onDetachedFromWindow() {
        attached = false;
        Choreographer.getInstance().removeFrameCallback(this);
        super.onDetachedFromWindow();
    }

    @Override public void doFrame(long nanos) {
        if (!attached) return;
        boolean live = source != null && source.now(wanted);
        boolean moving = live;
        float t = nanos / 1e9f;
        for (int i = 0; i < 3; i++) {
            float aim = live ? REST * 0.6f + 0.72f * wanted[i] * wanted[i] : REST;
            shown[i] = aim > shown[i] ? aim : aim + (shown[i] - aim) * FALL;
            if (Math.abs(shown[i] - aim) > 0.004f) moving = true;
            glows[i].setAlpha(Math.min(1f, shown[i]));
            if (live) {
                float drift = getWidth() * 0.05f;
                glows[i].setTranslationX(drift * (float) Math.sin(t * (0.31f + i * 0.13f)));
                glows[i].setTranslationY(drift * (float) Math.cos(t * (0.23f + i * 0.11f)));
            }
        }
        if (moving) Choreographer.getInstance().postFrameCallback(this);
        else postDelayed(() -> { if (attached) Choreographer.getInstance().postFrameCallback(this); }, 250);
    }

    /** One soft light. Drawn once; after that only its opacity changes. */
    private static final class Glow extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        private final float[] place;
        private final int colour;

        Glow(Context c, float[] place, int colour) {
            super(c);
            this.place = place;
            this.colour = colour;
        }

        @Override public boolean hasOverlappingRendering() { return false; }

        @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
            paint.setShader(new RadialGradient(place[0] * w, place[1] * h, Math.max(1, place[2] * w),
                    new int[]{colour, colour & 0x00FFFFFF}, new float[]{0, 1}, Shader.TileMode.CLAMP));
        }

        @Override protected void onDraw(Canvas c) {
            int edge = getWidth();
            c.drawRect(-edge, -edge, getWidth() + edge, getHeight() + edge, paint);
        }
    }
}
