package com.dennis.masterroom.engine;

/**
 * Variable-mu bus compressor. Ratio rises with level, so light passages barely register
 * and loud passages are held more firmly. The detector ignores the deep bass, a standard
 * mastering side-chain move that stops the kick from pumping the mix.
 */
final class VariMu {
    static final class Outcome { double thresholdDb, meanDb, maxDb; }

    private static final double[] CURVE = curve();

    private static double[] curve() {
        double[] c = new double[301];
        double sum = 0;
        for (int i = 0; i < c.length; i++) {
            double over = i * 0.1, ratio = Math.min(2.5, 1.15 + 0.12 * over);
            sum += (1 - 1 / ratio) * 0.1;
            c[i] = sum;
        }
        return c;
    }

    private static double staticGr(double over) {
        if (over <= 0) return 0;
        int i = (int) (over * 10);
        return i >= CURVE.length ? CURVE[CURVE.length - 1] : CURVE[i];
    }

    /**
     * @param goalDb average reduction wanted across the louder three quarters of the song
     * @param capDb the most this stage may ever take, approached softly
     */
    static Outcome apply(Audio a, double goalDb, double capDb, double attackMs, double fastReleaseMs, double slowReleaseMs) {
        Outcome out = new Outcome();
        if (goalDb <= 0) return out;
        int frames = a.frames(), rate = a.rate;
        // Side-chain high-pass, 2nd order at 100 Hz.
        double w = 2 * Math.PI * 100.0 / rate, alpha = Math.sin(w) / (2 * 0.707), cw = Math.cos(w), a0 = 1 + alpha;
        double b0 = (1 + cw) / 2 / a0, b1 = -(1 + cw) / a0, b2 = b0, a1 = -2 * cw / a0, a2 = (1 - alpha) / a0;
        float[] level = new float[frames];
        double x1 = 0, x2 = 0, y1 = 0, y2 = 0, u1 = 0, u2 = 0, v1 = 0, v2 = 0, power = 0;
        double det = Math.exp(-1 / (0.015 * rate));
        for (int i = 0; i < frames; i++) {
            double x = a.l[i], u = a.r[i];
            double y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2; x2 = x1; x1 = x; y2 = y1; y1 = y;
            double v = b0 * u + b1 * u1 + b2 * u2 - a1 * v1 - a2 * v2; u2 = u1; u1 = u; v2 = v1; v1 = v;
            power = det * power + (1 - det) * (y * y + v * v) * 0.5;
            level[i] = (float) power;
        }
        int step = rate / 100, blocks = frames / step;
        double[] db = new double[blocks];
        for (int b = 0; b < blocks; b++) db[b] = 10 * Math.log10(Math.max(1e-14, level[b * step + step / 2]));
        double[] sorted = db.clone();
        java.util.Arrays.sort(sorted);
        double loudFrom = sorted[blocks / 4];
        double lo = sorted[blocks / 2] - 12, hi = sorted[blocks - 1];
        for (int it = 0; it < 24; it++) {
            double t = (lo + hi) / 2, sum = 0; int n = 0;
            for (double d : db) if (d >= loudFrom) { sum += staticGr(d - t); n++; }
            if (n > 0 && sum / n > goalDb) lo = t; else hi = t;
        }
        double threshold = (lo + hi) / 2;
        out.thresholdDb = threshold;
        double attack = 1 - Math.exp(-1 / (attackMs * 0.001 * rate));
        double fast = 1 - Math.exp(-1 / (fastReleaseMs * 0.001 * rate));
        double slow = 1 - Math.exp(-1 / (slowReleaseMs * 0.001 * rate));
        double grFast = 0, grSlow = 0, sum = 0;
        int look = Math.round(0.003f * rate);
        for (int i = 0; i < frames; i++) {
            int j = Math.min(frames - 1, i + look);
            double d = 10 * Math.log10(Math.max(1e-14, level[j]));
            double want = staticGr(d - threshold);
            grFast += (want - grFast) * (want > grFast ? attack : fast);
            grSlow += (want - grSlow) * (want > grSlow ? attack * 0.25 : slow);
            double gr = capDb * Tone.tanh((0.55 * grFast + 0.45 * grSlow) / capDb);
            sum += gr;
            if (gr > out.maxDb) out.maxDb = gr;
            float k = (float) Math.pow(10, -gr / 20);
            a.l[i] *= k; a.r[i] *= k;
        }
        out.meanDb = sum / frames;
        return out;
    }
}
