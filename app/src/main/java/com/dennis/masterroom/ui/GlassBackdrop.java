package com.dennis.masterroom.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

/** The soft studio light behind the glass panels. Static, so it costs nothing to keep. */
final class GlassBackdrop extends View {
    private static final int GROUND = 0xFF0B0E13;
    private static final float[][] LIGHTS = {
            // x, y, radius (all as parts of the screen width or height)
            {0.20f, 0.14f, 0.62f}, {0.85f, 0.70f, 0.66f}, {0.26f, 0.84f, 0.56f}};
    private static final int[] COLOURS = {0x9CE0922A, 0x8C178E96, 0x806D45C4};
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final RadialGradient[] glows = new RadialGradient[LIGHTS.length];

    GlassBackdrop(Context c) { super(c); }

    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        for (int i = 0; i < LIGHTS.length; i++) {
            glows[i] = new RadialGradient(LIGHTS[i][0] * w, LIGHTS[i][1] * h, Math.max(1, LIGHTS[i][2] * w),
                    new int[]{COLOURS[i], COLOURS[i] & 0x00FFFFFF}, new float[]{0, 1}, Shader.TileMode.CLAMP);
        }
    }

    @Override protected void onDraw(Canvas c) {
        c.drawColor(GROUND);
        for (RadialGradient g : glows) {
            if (g == null) continue;
            paint.setShader(g);
            c.drawRect(0, 0, getWidth(), getHeight(), paint);
        }
    }
}
