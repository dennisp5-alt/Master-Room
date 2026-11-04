package com.dennis.masterroom.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

/** The Console look: colours, type and the few shapes every screen shares. */
final class Look {
    static final int BG = 0xFF16171A, PANEL = 0xFF202226, RAISED = 0xFF2A2C31, WELL = 0xFF0E0F11, LINE = 0xFF33363C;
    static final int TEXT = 0xFFEFEAE2, MID = 0xFFC8C6C0, DIM = 0xFFA4A7AD, BAR = 0xFF5A5D64;
    static final int AMBER = 0xFFF4A93B, BLUE = 0xFF6FB7E8, FACE = 0xFFF6DFAE, FACE_INK = 0xFF1D1B18, FACE_DIM = 0xFF4A4336, HOT = 0xFFB3261E;

    static Typeface body, strong, label, title, mono;
    private static float density = 1;

    private Look() {}

    static void load(Context c) {
        density = c.getResources().getDisplayMetrics().density;
        body = font(c, "Barlow-Regular.ttf", Typeface.SANS_SERIF);
        strong = font(c, "Barlow-SemiBold.ttf", Typeface.DEFAULT_BOLD);
        label = font(c, "BarlowCondensed-SemiBold.ttf", Typeface.create("sans-serif-condensed", Typeface.NORMAL));
        title = font(c, "BarlowCondensed-Bold.ttf", Typeface.create("sans-serif-condensed", Typeface.BOLD));
        Typeface m;
        try {
            m = new Typeface.Builder(c.getAssets(), "fonts/JetBrainsMono.ttf").setFontVariationSettings("'wght' 500").build();
        } catch (RuntimeException e) {
            m = null;
        }
        mono = m != null ? m : Typeface.MONOSPACE;
    }

    private static Typeface font(Context c, String file, Typeface fallback) {
        try {
            return Typeface.createFromAsset(c.getAssets(), "fonts/" + file);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    static int dp(float v) { return Math.round(v * density); }

    static GradientDrawable shape(int fill, int stroke, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        if (stroke != 0) d.setStroke(Math.max(1, dp(1)), stroke);
        return d;
    }

    /** Touch feedback on a flat surface. */
    static void surface(View v, GradientDrawable shape) {
        v.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), shape, null));
    }

    static void type(TextView t, Typeface face, float sp, int color) {
        t.setTypeface(face);
        t.setTextSize(sp);
        t.setTextColor(color);
    }

    static void flat(Button b) {
        b.setAllCaps(false);
        b.setStateListAnimator(null);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(8), 0, dp(8), 0);
    }

    static void enable(View v, boolean on) {
        v.setEnabled(on);
        v.setAlpha(on ? 1f : 0.4f);
    }
}
