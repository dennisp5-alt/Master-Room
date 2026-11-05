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
    static final int BG = 0xFF0A0D12, PANEL = 0xFF141922, PANEL_TOP = 0xFF242A35, RAISED = 0xFF242A33, WELL = 0xFF07090D;
    static final int LINE = 0xFF414751, HAIR = 0xFF303741, TEXT = 0xFFF7F4EE, MID = 0xFFD5D3CE, DIM = 0xFFA5ADB9;
    static final int AMBER = 0xFFFFB83D, GOLD_HI = 0xFFFFD47A, GOLD_LO = 0xFFE39C24, AMBER_DIM = 0xFF987142, BLUE = 0xFF78BDD9, HOT = 0xFFD96C4B;

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

    /** Solid surface colours become glass here, so every screen changes together. */
    private static int glassFill(int fill) {
        if (fill == WELL) return 0x5C06090E;
        if (fill == RAISED) return 0x0DFFFFFF;
        if (fill == PANEL) return 0x08FFFFFF;
        if (fill == BG) return 0x52060A0E;
        return fill;
    }

    private static int glassEdge(int stroke) {
        if (stroke == LINE) return 0x47FFFFFF;
        if (stroke == HAIR) return 0x2EFFFFFF;
        return stroke;
    }

    static GradientDrawable shape(int fill, int stroke, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(glassFill(fill));
        d.setCornerRadius(dp(radiusDp));
        if (stroke != 0) d.setStroke(Math.max(1, dp(1)), glassEdge(stroke));
        return d;
    }

    /** A clear glass panel: barely tinted, with a bright edge that catches the light. */
    static GradientDrawable metal(float radiusDp) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x12FFFFFF, 0x05FFFFFF, 0x07FFFFFF});
        d.setCornerRadius(dp(radiusDp));
        d.setStroke(Math.max(1, dp(1)), 0x47FFFFFF);
        return d;
    }

    static GradientDrawable gold(float radiusDp) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{GOLD_HI, AMBER, GOLD_LO});
        d.setCornerRadius(dp(radiusDp));
        d.setStroke(Math.max(1, dp(1)), 0xFFFFD88D);
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
