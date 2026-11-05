package com.dennis.masterroom.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The restoration pass before mastering. It looks for damage that does not belong to the
 * music: single clicks, short dropouts and a stray burst before the song starts. Repairs
 * rebuild only the damaged samples from the audio on each side; everything else is left
 * exactly as it was.
 */
final class Repair {
    static final class Outcome {
        int clicks, dropouts;
        boolean startBurst;
        Audio audio;
    }

    private static final float SPIKE = 16;
    /** More than this many per minute means it is reading the music, not damage. */
    private static final int MAX_PER_MINUTE = 30;

    private static final class Gap { int from, to; boolean dropout; Gap(int f, int t, boolean d) { from = f; to = t; dropout = d; } }

    static Outcome apply(Audio in) { return apply(in, false); }

    /** @param own the caller is finished with this audio, so it can be repaired where it lies */
    static Outcome apply(Audio in, boolean own) {
        Outcome o = new Outcome();
        o.audio = in;
        List<Gap> left = gaps(in.l, in.rate), right = gaps(in.r, in.rate);
        int burstEnd = startBurst(in);
        double minutes = Math.max(0.25, in.seconds() / 60);
        if (count(left, false) > MAX_PER_MINUTE * minutes) dropClicks(left);
        if (count(right, false) > MAX_PER_MINUTE * minutes) dropClicks(right);
        if (left.isEmpty() && right.isEmpty() && burstEnd == 0) return o;
        Audio out = own ? in : in.copy();
        for (Gap g : left) fill(out.l, g);
        for (Gap g : right) fill(out.r, g);
        o.clicks = count(left, false) + count(right, false);
        o.dropouts = count(left, true) + count(right, true);
        if (burstEnd > 0) {
            o.startBurst = true;
            int fade = Math.min(burstEnd, out.rate / 500);
            for (int i = 0; i < burstEnd; i++) {
                float k = i < burstEnd - fade ? 0 : (float) (i - (burstEnd - fade)) / fade;
                out.l[i] *= k; out.r[i] *= k;
            }
        }
        o.audio = out;
        return o;
    }

    private static int count(List<Gap> gaps, boolean dropout) {
        int n = 0;
        for (Gap g : gaps) if (g.dropout == dropout) n++;
        return n;
    }

    private static void dropClicks(List<Gap> gaps) {
        List<Gap> keep = new ArrayList<>();
        for (Gap g : gaps) if (g.dropout) keep.add(g);
        gaps.clear();
        gaps.addAll(keep);
    }

    /** A short sound at the very start, then a clear gap before the song. */
    private static int startBurst(Audio a) {
        int step = a.rate / 200;
        int blocks = Math.min(a.rate * 2 / 5 / step, a.frames() / step);
        if (blocks < 8) return 0;
        double[] db = new double[blocks];
        for (int b = 0; b < blocks; b++) {
            double sum = 0;
            for (int i = b * step; i < (b + 1) * step; i++) sum += a.l[i] * a.l[i] + a.r[i] * a.r[i];
            db[b] = 10 * Math.log10(Math.max(1e-12, sum / (2 * step)));
        }
        int first = 0;
        while (first < blocks && db[first] < -60) first++;
        if (first >= 4 || first >= blocks || db[first] < -45) return 0;
        int end = first;
        while (end < blocks && db[end] > -60) end++;
        // The sound must stop inside 60 ms and stay quiet for at least 50 ms.
        if (end >= blocks || end - first > 12 || blocks - end < 10) return 0;
        for (int b = end; b < end + 10; b++) if (db[b] > -60) return 0;
        return end * step;
    }

    private static List<Gap> gaps(float[] x, int rate) {
        List<Gap> out = new ArrayList<>();
        findDropouts(x, rate, out);
        findClicks(x, out);
        out.sort((a, b) -> a.from - b.from);
        return out;
    }

    /** Digital silence for a few milliseconds in the middle of sound. */
    private static void findDropouts(float[] x, int rate, List<Gap> out) {
        int min = Math.max(8, rate / 4000), max = rate / 200, around = rate / 100;
        for (int i = around; i < x.length - around; i++) {
            if (Math.abs(x[i]) > 1e-6f) continue;
            int j = i;
            while (j < x.length && Math.abs(x[j]) <= 1e-6f) j++;
            if (j - i >= min && j - i <= max && j + around < x.length
                    && rms(x, i - around, i) > 3e-3 && rms(x, j, j + around) > 3e-3) {
                out.add(new Gap(i, j, true));
            }
            i = j;
        }
    }

    private static double rms(float[] x, int from, int to) {
        double sum = 0;
        for (int i = from; i < to; i++) sum += x[i] * x[i];
        return Math.sqrt(sum / Math.max(1, to - from));
    }

    /**
     * A click is one sharp spike with nothing like it nearby. A drum or consonant is sharp
     * too, but it is followed by more of the same, so it fails the neighbour test.
     */
    private static void findClicks(float[] x, List<Gap> out) {
        int n = x.length;
        if (n < 4096) return;
        float[] h = new float[n];
        for (int i = 2; i < n; i++) h[i] = Math.abs(x[i] - 2 * x[i - 1] + x[i - 2]);
        int block = 2048;
        float[] sample = new float[block];
        for (int s = 0; s + block <= n; s += block) {
            System.arraycopy(h, s, sample, 0, block);
            Arrays.sort(sample);
            float limit = Math.max(0.01f, SPIKE * Math.max(1e-5f, sample[block / 2]));
            for (int i = Math.max(s, 300); i < s + block && i < n - 300; i++) {
                if (h[i] < limit) continue;
                int top = i;
                for (int j = i + 1; j < i + 6; j++) if (h[j] > h[top]) top = j;
                float near = h[top] * 0.3f;
                boolean alone = true;
                for (int j = top - 96; j <= top + 96 && alone; j++) {
                    if (Math.abs(j - top) >= 7 && h[j] > near) alone = false;
                }
                if (alone) out.add(new Gap(top - 4, top + 6, false));
                i = top + 96;
            }
        }
    }

    private static void autocorrelate(double[] x, int from, int to, double[] r, boolean clear) {
        if (clear) Arrays.fill(r, 0);
        for (int lag = 0; lag < r.length; lag++) {
            double sum = 0;
            for (int i = from + lag; i < to; i++) sum += x[i] * x[i - lag];
            r[lag] += sum;
        }
    }

    private static void levinson(double[] r, double[] a) {
        int p = a.length - 1;
        Arrays.fill(a, 0);
        a[0] = 1;
        double err = r[0] * 1.00001 + 1e-12;
        double[] t = new double[p + 1];
        for (int i = 1; i <= p; i++) {
            double acc = r[i];
            for (int j = 1; j < i; j++) acc += a[j] * r[i - j];
            double k = -acc / err;
            System.arraycopy(a, 0, t, 0, i);
            for (int j = 1; j < i; j++) a[j] = t[j] + k * t[i - j];
            a[i] = k;
            err *= 1 - k * k;
            if (err <= 0) break;
        }
    }

    /** Least-squares rebuild of the gap from a model of the audio on both sides. */
    private static void fill(float[] x, Gap g) {
        int n = g.to - g.from, order = Math.min(320, Math.max(32, 3 * n + 2)), context = 2048;
        int from = Math.max(0, g.from - context), to = Math.min(x.length, g.to + context);
        double[] sig = new double[to - from];
        for (int i = 0; i < sig.length; i++) sig[i] = x[from + i];
        double[] r = new double[order + 1], a = new double[order + 1];
        autocorrelate(sig, 0, g.from - from, r, true);
        autocorrelate(sig, g.to - from, sig.length, r, false);
        levinson(r, a);
        double[] b = new double[order + 1];
        for (int k = 0; k <= order; k++) {
            double sum = 0;
            for (int l = 0; l + k <= order; l++) sum += a[l] * a[l + k];
            b[k] = sum;
        }
        double[][] m = new double[n][n];
        double[] rhs = new double[n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) m[i][j] = Math.abs(i - j) <= order ? b[Math.abs(i - j)] : 0;
            m[i][i] *= 1.0001;
            int at = g.from + i;
            for (int k = at - order; k <= at + order; k++) {
                if ((k >= g.from && k < g.to) || k < from || k >= to) continue;
                rhs[i] -= b[Math.abs(k - at)] * x[k];
            }
        }
        solve(m, rhs);
        for (int i = 0; i < n; i++) x[g.from + i] = (float) rhs[i];
    }

    /** Cholesky solve, in place. */
    private static void solve(double[][] m, double[] v) {
        int n = v.length;
        for (int j = 0; j < n; j++) {
            double d = m[j][j];
            for (int k = 0; k < j; k++) d -= m[j][k] * m[j][k];
            d = Math.sqrt(Math.max(1e-18, d));
            m[j][j] = d;
            for (int i = j + 1; i < n; i++) {
                double s = m[i][j];
                for (int k = 0; k < j; k++) s -= m[i][k] * m[j][k];
                m[i][j] = s / d;
            }
        }
        for (int i = 0; i < n; i++) {
            double s = v[i];
            for (int k = 0; k < i; k++) s -= m[i][k] * v[k];
            v[i] = s / m[i][i];
        }
        for (int i = n - 1; i >= 0; i--) {
            double s = v[i];
            for (int k = i + 1; k < n; k++) s -= m[k][i] * v[k];
            v[i] = s / m[i][i];
        }
    }

    /** Very short fades so the file cannot start or stop on a step. */
    static void edges(Audio a) {
        int n = Math.min(a.frames() / 2, a.rate / 500);
        for (int i = 0; i < n; i++) {
            float k = (float) (0.5 - 0.5 * Math.cos(Math.PI * i / n));
            a.l[i] *= k; a.r[i] *= k;
            int e = a.frames() - 1 - i;
            a.l[e] *= k; a.r[e] *= k;
        }
    }
}
