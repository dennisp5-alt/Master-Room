package com.dennis.masterroom.engine;

/** Rational sample-rate conversion by Kaiser-windowed sinc. */
final class Resample {
    static Audio to(Audio a, int rate) {
        if (a.rate == rate) return a;
        return new Audio(channel(a.l, a.rate, rate), channel(a.r, a.rate, rate), rate);
    }

    private static int gcd(int a, int b) { return b == 0 ? a : gcd(b, a % b); }

    private static float[] channel(float[] x, int from, int to) {
        int g = gcd(from, to), up = to / g, down = from / g;
        int zeros = 64;
        double cutoff = Math.min(1.0, (double) to / from) * 0.975;
        int perPhase = 2 * zeros + 1;
        double[][] table = new double[up][perPhase];
        double beta = 12.0, i0 = bessel(beta);
        for (int p = 0; p < up; p++) {
            double frac = (double) p / up, sum = 0;
            for (int k = 0; k < perPhase; k++) {
                double t = k - zeros - frac;
                double s = Math.abs(t) < 1e-12 ? cutoff : Math.sin(Math.PI * cutoff * t) / (Math.PI * t);
                double r = t / (zeros + 1);
                double v = s * bessel(beta * Math.sqrt(Math.max(0, 1 - r * r))) / i0;
                table[p][k] = v; sum += v;
            }
            for (int k = 0; k < perPhase; k++) table[p][k] /= sum;
        }
        int out = (int) Math.round((double) x.length * to / from);
        float[] y = new float[out];
        for (int n = 0; n < out; n++) {
            long pos = (long) n * down;
            int base = (int) (pos / up), phase = (int) (pos % up);
            double s = 0;
            double[] h = table[phase];
            for (int k = 0; k < perPhase; k++) {
                int i = base + k - zeros;
                if (i >= 0 && i < x.length) s += h[k] * x[i];
            }
            y[n] = (float) s;
        }
        return y;
    }

    private static double bessel(double x) {
        double s = 1, t = 1;
        for (int k = 1; k < 50; k++) { t *= (x / (2 * k)) * (x / (2 * k)); s += t; if (t < 1e-15 * s) break; }
        return s;
    }
}
