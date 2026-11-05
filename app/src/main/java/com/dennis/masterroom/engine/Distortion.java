package com.dennis.masterroom.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Listens for damage the loudness stage added. It compares the audio going into the
 * clipper and limiter with what came out, removes plain gain changes, and asks whether
 * what is left could be heard over the music playing at that moment.
 */
public final class Distortion {
    public static final class Spot {
        public double startSeconds, endSeconds, scoreDb;
        /** True when it is likely to be heard, false when it is borderline. */
        public boolean audible;
    }

    public static final class Report {
        public final List<Spot> spots = new ArrayList<>();
        public int audible, borderline;
        public double worstDb = Double.NEGATIVE_INFINITY;
        public boolean passed() { return audible == 0; }
    }

    /** How far the added sound sits above what the music hides: above this, likely audible. */
    static final double AUDIBLE_DB = 6, BORDERLINE_DB = 2;
    private static final int SIZE = 1024, HOP = 512;
    private static final double[] EDGES = {20, 100, 200, 300, 400, 510, 630, 770, 920, 1080, 1270, 1480, 1720, 2000, 2320, 2700,
            3150, 3700, 4400, 5300, 6400, 7700, 9500, 12000, 15500};
    private static final int BANDS = EDGES.length - 1;
    private static final double UP_DB = 12, DOWN_DB = 27, MARGIN_DB = 16, FLOOR_DB = -78;

    private Distortion() {}

    private static double[] score(float[] before, float level, float[] after, int rate) {
        int n = Math.min(before.length, after.length), blocks = n < SIZE ? 0 : (n - SIZE) / HOP + 1;
        double[] out = new double[blocks];
        Fft fft = new Fft(SIZE);
        double[] w = new double[SIZE], re = new double[SIZE], im = new double[SIZE];
        for (int i = 0; i < SIZE; i++) w[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / SIZE);
        double norm = 0;
        for (double v : w) norm += v * v;
        int[] from = new int[BANDS], to = new int[BANDS];
        for (int b = 0; b < BANDS; b++) {
            from[b] = Math.max(1, (int) Math.ceil(EDGES[b] * SIZE / rate));
            to[b] = Math.min(SIZE / 2, (int) Math.ceil(EDGES[b + 1] * SIZE / rate));
        }
        double[] sig = new double[BANDS], res = new double[BANDS], sigDb = new double[BANDS];
        for (int s = 0; s < blocks; s++) {
            int at = s * HOP;
            double xy = 0, xx = 0;
            for (int i = 0; i < SIZE; i++) {
                double x = before[at + i] * level * w[i], y = after[at + i] * w[i];
                xy += x * y; xx += x * x;
            }
            out[s] = -200;
            if (xx < 1e-9) continue;
            double g = xy / xx;
            for (int i = 0; i < SIZE; i++) {
                double x = before[at + i] * level * w[i], y = after[at + i] * w[i];
                re[i] = x * g;
                im[i] = y - x * g;
            }
            fft.forward(re, im);
            java.util.Arrays.fill(sig, 0);
            java.util.Arrays.fill(res, 0);
            for (int b = 0; b < BANDS; b++) {
                for (int k = from[b]; k < to[b]; k++) {
                    int m = SIZE - k;
                    double xr = (re[k] + re[m]) / 2, xi = (im[k] - im[m]) / 2;
                    double rr = (im[k] + im[m]) / 2, ri = (re[m] - re[k]) / 2;
                    sig[b] += xr * xr + xi * xi;
                    res[b] += rr * rr + ri * ri;
                }
            }
            double scale = 2 / (norm * SIZE);
            for (int b = 0; b < BANDS; b++) sigDb[b] = 10 * Math.log10(Math.max(1e-20, sig[b] * scale));
            double worst = -200;
            for (int b = 0; b < BANDS; b++) {
                double added = 10 * Math.log10(Math.max(1e-20, res[b] * scale));
                if (added < FLOOR_DB) continue;
                double mask = -200;
                for (int j = 0; j < BANDS; j++) {
                    double spread = sigDb[j] - (j < b ? (b - j) * UP_DB : (j - b) * DOWN_DB);
                    if (spread > mask) mask = spread;
                }
                worst = Math.max(worst, added - (mask - MARGIN_DB));
            }
            out[s] = worst;
        }
        return out;
    }

    /** Compares the same audio before and after the loudness stage, frame for frame. */
    public static Report check(Audio before, Audio after) { return check(before, 0, after); }

    /** @param gainDb level change to allow for, so the audio need not be copied to apply it */
    static Report check(Audio before, double gainDb, Audio after) {
        float level = (float) Math.pow(10, gainDb / 20);
        double[] left = score(before.l, level, after.l, before.rate), right = score(before.r, level, after.r, before.rate);
        Report r = new Report();
        int blocks = Math.min(left.length, right.length);
        double secs = (double) HOP / before.rate;
        int gap = (int) Math.ceil(0.12 / secs);
        Spot open = null;
        int run = 0, last = -gap - 1, peakRun = 0;
        for (int i = 0; i < blocks; i++) {
            double s = Math.max(left[i], right[i]);
            if (s > r.worstDb) r.worstDb = s;
            if (s < BORDERLINE_DB) continue;
            if (open == null || i - last > gap) {
                if (open != null) finish(r, open, peakRun);
                open = new Spot();
                open.startSeconds = i * secs;
                open.scoreDb = s;
                run = 0;
                peakRun = 0;
            }
            open.endSeconds = (i + 2) * secs;
            open.scoreDb = Math.max(open.scoreDb, s);
            run++;
            peakRun = Math.max(peakRun, run);
            last = i;
        }
        if (open != null) finish(r, open, peakRun);
        return r;
    }

    private static void finish(Report r, Spot s, int blocks) {
        if (blocks < 2) return;
        s.audible = s.scoreDb >= AUDIBLE_DB;
        if (s.audible) r.audible++; else r.borderline++;
        r.spots.add(s);
    }

    /** Adds the flagged moments to a 10 ms map of where the clipper must stand back. */
    static float[] calm(Report r, float[] map, double seconds) {
        int n = (int) (seconds * 100) + 2;
        float[] out = new float[n];
        java.util.Arrays.fill(out, 1);
        if (map != null) System.arraycopy(map, 0, out, 0, Math.min(n, map.length));
        for (Spot s : r.spots) {
            int from = Math.max(0, (int) ((s.startSeconds - 0.03) * 100)), to = Math.min(n - 1, (int) ((s.endSeconds + 0.03) * 100));
            for (int i = from; i <= to; i++) out[i] = 0;
        }
        return out;
    }

    /** Flat-topped peaks already in the file: damage mastering did not add and cannot undo. */
    public static int flatPeaks(Audio a) {
        return flat(a.l) + flat(a.r);
    }

    private static int flat(float[] x) {
        float top = 0;
        for (float v : x) top = Math.max(top, Math.abs(v));
        if (top < 0.5f) return 0;
        float near = top * 0.9995f;
        int runs = 0, n = 0;
        for (float v : x) {
            if (Math.abs(v) >= near) n++;
            else { if (n >= 3) runs++; n = 0; }
        }
        return runs;
    }

    static String clock(double s) { return String.format(Locale.US, "%d:%02d", (int) s / 60, (int) s % 60); }
}
