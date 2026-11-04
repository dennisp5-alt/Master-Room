package com.dennis.masterroom.engine;

/**
 * Gain curves worked out before they are used. A hardware compressor can only react;
 * an offline render knows what is coming, so these curves can look both ways.
 */
final class Control {
    /** dB level of each [step]-sample block. */
    static double[] levels(float[] x, int step) {
        int n = x.length / step;
        double[] out = new double[n];
        int i = 0;
        for (int b = 0; b < n; b++) {
            double sum = 0;
            for (int k = 0; k < step; k++, i++) sum += x[i] * x[i];
            out[b] = 10 * Math.log10(Math.max(1e-14, sum / step));
        }
        return out;
    }

    /** Centred moving average in the power domain. */
    static double[] smoothPower(double[] db, int width) {
        double[] p = new double[db.length];
        for (int i = 0; i < db.length; i++) p[i] = Math.pow(10, db[i] / 10);
        p = box(p, width);
        for (int i = 0; i < p.length; i++) p[i] = 10 * Math.log10(Math.max(1e-14, p[i]));
        return p;
    }

    /** Centred moving average; edges use what exists. */
    static double[] box(double[] x, int width) {
        int n = x.length, half = Math.max(0, width / 2);
        double[] out = new double[n];
        double sum = 0; int count = 0, lo = 0, hi = -1;
        for (int i = 0; i < n; i++) {
            int wantHi = Math.min(n - 1, i + half), wantLo = Math.max(0, i - half);
            while (hi < wantHi) { hi++; sum += x[hi]; count++; }
            while (lo < wantLo) { sum -= x[lo]; lo++; count--; }
            out[i] = sum / count;
        }
        return out;
    }

    /** Largest value within +-radius of each point. */
    static double[] holdMax(double[] x, int radius) {
        int n = x.length;
        double[] out = new double[n];
        int[] q = new int[n + 1];
        int head = 0, tail = 0, next = 0;
        for (int i = 0; i < n; i++) {
            int hi = Math.min(n - 1, i + radius);
            while (next <= hi) {
                while (tail > head && x[q[tail - 1]] <= x[next]) tail--;
                q[tail++] = next++;
            }
            while (q[head] < i - radius) head++;
            out[i] = x[q[head]];
        }
        return out;
    }

    /** Applies a dB curve (one point per [step] samples) to a whole signal by interpolation. */
    static void applyDb(float[] x, float[] band, double[] db, int step) {
        int n = x.length;
        for (int i = 0; i < n; i++) {
            double pos = (double) i / step - 0.5;
            int a = (int) Math.floor(pos);
            double f = pos - a, d;
            if (a < 0) d = db[0];
            else if (a + 1 >= db.length) d = db[db.length - 1];
            else d = db[a] * (1 - f) + db[a + 1] * f;
            if (band == null) {
                x[i] *= (float) Math.pow(10, d / 20);
            } else if (d != 0) {
                x[i] += (float) ((Math.pow(10, d / 20) - 1) * band[i]);
            }
        }
    }

    static double percentile(double[] values, int n, double q) {
        double[] v = java.util.Arrays.copyOf(values, n);
        java.util.Arrays.sort(v);
        return n == 0 ? 0 : v[(int) Math.round(q * (n - 1))];
    }
}
