package com.dennis.masterroom.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** The controls both screens are built from, so they stay one design. */
final class Parts {
    static final int MATCH = LinearLayout.LayoutParams.MATCH_PARENT, WRAP = LinearLayout.LayoutParams.WRAP_CONTENT;
    private final Context c;

    Parts(Context c) { this.c = c; }

    static LinearLayout.LayoutParams line(int height, int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(MATCH, height);
        p.topMargin = Look.dp(topDp);
        return p;
    }

    static LinearLayout.LayoutParams share(int height, float weight, int leftDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, height, weight);
        p.leftMargin = Look.dp(leftDp);
        return p;
    }

    TextView text(String s, Typeface face, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        Look.type(t, face, sp, color);
        return t;
    }

    /** Engraved panel label. */
    TextView label(String s) {
        TextView t = text(s, Look.label, 15, Look.DIM);
        t.setLetterSpacing(0.12f);
        return t;
    }

    LinearLayout row() {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    View grow() {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1));
        return v;
    }

    /** A rack panel holding one group of controls. */
    LinearLayout panel(int topMarginDp, LinearLayout parent) {
        LinearLayout p = new LinearLayout(c);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(Look.dp(12), Look.dp(10), Look.dp(12), Look.dp(12));
        p.setBackground(Look.metal(8));
        parent.addView(p, line(WRAP, topMarginDp));
        return p;
    }

    Button button(String s, View.OnClickListener click) {
        Button b = new Button(c);
        Look.flat(b);
        b.setText(s);
        Look.type(b, Look.label, 17, Look.TEXT);
        Look.surface(b, Look.shape(Look.PANEL, Look.LINE, 6));
        b.setOnClickListener(click);
        return b;
    }

    /** Borderless amber text control. */
    Button link(String s, View.OnClickListener click) {
        Button b = new Button(c);
        Look.flat(b);
        b.setText(s);
        Look.type(b, Look.label, 16, Look.AMBER);
        b.setLetterSpacing(0.08f);
        b.setBackground(null);
        b.setOnClickListener(click);
        return b;
    }

    /** The primary action, in gold. */
    Button gold(String s, View.OnClickListener click) {
        Button b = new Button(c);
        Look.flat(b);
        b.setText(s);
        Look.type(b, Look.title, 22, Look.BG);
        b.setLetterSpacing(0.12f);
        Look.surface(b, Look.gold(7));
        b.setOnClickListener(click);
        return b;
    }

    /** One position of a selector. */
    Button segment(String s, boolean on, Typeface face, View.OnClickListener click) {
        Button b = new Button(c);
        Look.flat(b);
        b.setText(s);
        Look.type(b, face, 16, on ? Look.BG : Look.TEXT);
        Look.surface(b, on ? Look.gold(5) : Look.shape(Look.RAISED, Look.LINE, 5));
        b.setOnClickListener(click);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, Look.dp(48), 1);
        p.setMargins(Look.dp(3), 0, Look.dp(3), 0);
        b.setLayoutParams(p);
        return b;
    }

    /** A labelled numeric readout. Returns the value text so it can be updated. */
    TextView readout(LinearLayout parent, String name, String unit, int leftDp) {
        LinearLayout t = new LinearLayout(c);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setPadding(Look.dp(4), Look.dp(8), Look.dp(4), Look.dp(8));
        t.setBackground(Look.shape(Look.WELL, Look.HAIR, 5));
        TextView n = text(name, Look.label, 13, Look.DIM);
        n.setLetterSpacing(0.08f);
        n.setGravity(Gravity.CENTER);
        t.addView(n);
        TextView v = text("--", Look.mono, 21, Look.TEXT);
        v.setGravity(Gravity.CENTER);
        t.addView(v);
        TextView u = text(unit, Look.label, 13, Look.DIM);
        u.setGravity(Gravity.CENTER);
        t.addView(u);
        parent.addView(t, share(Parts.WRAP, 1, leftDp));
        return v;
    }
}
