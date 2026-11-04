package com.dennis.masterroom.engine;

import java.util.List;
import java.util.Locale;

/**
 * Remembers which takes you choose. Each line is "style|how|take": how is "blind" for a
 * blind-test pick or "saved" for a take you kept. A blind pick counts for more.
 */
public final class Taste {
    private static final int ENOUGH = 8;
    private Taste() {}

    public static String record(String style, String how, String take) { return style + "|" + how + "|" + take; }

    private static double[] weights(List<String> lines, String style) {
        double[] w = new double[3];
        for (String line : lines) {
            String[] p = line.split("\\|");
            if (p.length != 3 || (style != null && !style.equals(p[0]))) continue;
            int i = p[2].equals("Clean") ? 0 : p[2].equals("Warm") ? 1 : p[2].equals("Punch") ? 2 : -1;
            if (i >= 0) w[i] += p[1].equals("blind") ? 1 : 0.5;
        }
        return w;
    }

    private static int picks(List<String> lines, String style) {
        int n = 0;
        for (String line : lines) {
            String[] p = line.split("\\|");
            if (p.length == 3 && (style == null || style.equals(p[0])) && !p[2].equals("Source") && !p[2].equals("Yours")) n++;
        }
        return n;
    }

    /** A take built from your choices, or null until there are enough of them. */
    public static Engineer.Recipe yours(List<String> lines, String style) {
        double[] w = picks(lines, style) >= ENOUGH ? weights(lines, style) : weights(lines, null);
        if (picks(lines, null) < ENOUGH || w[0] + w[1] + w[2] <= 0) return null;
        return Engineer.blend("Yours", w[0], w[1], w[2]);
    }

    public static String summary(List<String> lines) {
        double[] w = weights(lines, null);
        int n = picks(lines, null);
        if (n == 0) return "Blind-test picks and saved takes teach the app your taste.";
        String mix = String.format(Locale.US, "Clean %.1f, Warm %.1f, Punch %.1f", w[0], w[1], w[2]);
        return n >= ENOUGH ? "Your takes so far: " + mix + ". A Yours take is now added."
                : "Your takes so far: " + mix + ". A Yours take starts after " + ENOUGH + " picks (" + n + " now).";
    }
}
