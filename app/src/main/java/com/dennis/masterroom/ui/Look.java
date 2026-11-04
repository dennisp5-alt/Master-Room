package com.dennis.masterroom.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

/** The studio look: colours, type and the surfaces every screen shares. */
final class Look {
    static final int BG = 0xFF121316, PANEL = 0xFF1E2024, PANEL_TOP = 0xFF2A2C31, RAISED = 0xFF2C2E34, WELL = 0xFF0A0B0D;
    static final int LINE = 0xFF3F4249, HAIR = 0xFF2D2F35, TEXT = 0xFFEFEAE2, MID = 0xFFCBC8C1, DIM = 0xFFA8ABB1;
    static final int AMBER = 0xFFF2B04A, GOLD_HI = 0xFFF8C76F, GOLD_LO = 0xFFDF9A30, AMBER_DIM = 0xFF8A6A33, BLUE = 0xFF7CBDEB, HOT = 0xFFE0694F;

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

    /** A brushed-metal rack surface: a slight top-to-bottom fall with a fine edge. */
    static GradientDrawable metal(float radiusDp) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{PANEL_TOP, PANEL});
        d.setCornerRadius(dp(radiusDp));
        d.setStroke(Math.max(1, dp(1)), LINE);
        return d;
    }

    static GradientDrawable gold(float radiusDp) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{GOLD_HI, GOLD_LO});
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    /** Touch feedback on a flat surface. */
    static void surface(View v, Drawable shape) {
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
        b.setMinHeight(dp(48));
        b.setMinimumHeight(dp(48));
        b.setPadding(dp(8), 0, dp(8), 0);
    }

    static void enable(View v, boolean on) {
        v.setEnabled(on);
        v.setAlpha(on ? 1f : 0.45f);
    }
}
