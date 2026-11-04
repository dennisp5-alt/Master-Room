package com.dennis.masterroom.engine;

/**
 * The loudness stack. Studios rarely ask one limiter for all the level: a converter or
 * clipper shaves the sharpest transients first, so the limiter has far less to do and
 * can release gently. Runs inside the oversampler.
 */
final class Stack implements Oversample.Stage {
    static final class Stats {
        double limiterMaxDb, limiterSumDb, clipMaxDb;
        long frames, over1, over3, clipped;
        double limiterMeanDb() { return frames == 0 ? 0 : limiterSumDb / frames; }
        double over1Percent() { return frames == 0 ? 0 : 100.0 * over1 / frames; }
        double over3Percent() { return frames == 0 ? 0 : 100.0 * over3 / frames; }
        double clippedPercent() { return frames == 0 ? 0 : 100.0 * clipped / frames; }
    }

    private final double gain, kneeStart, kneeWidth, ceiling;
    private final boolean clipOn;
    final Stats stats = new Stats();
    private final int look, rate, statEvery;
    private final double[] dl, dr;
    private int dp;
    // Sliding minimum over the lookahead window.
    private final double[] qv; private final long[] qt; private int qh, qn; private long t;
    private final double[] box; private int bp; private double boxSum;
    private double env = 1, sustained, coef, sustainCoef;

    Stack(int rate, double gainDb, double clipDb, double clipWasDb, double ceilingDb) {
        this.rate = rate;
        gain = Math.pow(10, gainDb / 20);
        double top = Math.pow(10, clipDb / 20);
        kneeStart = top * Math.pow(10, -1.5 / 20);
        kneeWidth = top - kneeStart;
        clipOn = clipWasDb > 0.01;
        ceiling = Math.pow(10, ceilingDb / 20);
        int n = (int) Math.round(0.005 * rate);
        look = Math.max(4, n - n % 4);
        dl = new double[look]; dr = new double[look];
        box = new double[look];
        java.util.Arrays.fill(box, 1);
        boxSum = look;
        qv = new double[look + 2]; qt = new long[look + 2];
        coef = 1 - Math.exp(-1 / (0.05 * rate));
        sustainCoef = 1 - Math.exp(-1 / (0.25 * rate));
        statEvery = Math.max(1, rate / 48000);
    }

    @Override public int latency() { return look; }

    private double clip(double x) {
        double a = Math.abs(x);
        if (a <= kneeStart) return x;
        double u = (a - kneeStart) / kneeWidth;
        double s = kneeStart + kneeWidth * Tone.tanh(u);
        if (s < a) { double d = a / s; if (d > clipPeak) clipPeak = d; clippedNow = true; }
        return x < 0 ? -s : s;
    }
    private double clipPeak = 1; private boolean clippedNow;

    @Override public void process(double[] l, double[] r, int n) {
        for (int i = 0; i < n; i++) {
            clippedNow = false;
            double x = l[i] * gain, y = r[i] * gain;
            if (clipOn) { x = clip(x); y = clip(y); }
            double p = Math.max(Math.abs(x), Math.abs(y));
            double req = p > ceiling ? ceiling / p : 1;
            // Sliding minimum.
            while (qn > 0 && qv[(qh + qn - 1) % qv.length] >= req) qn--;
            qv[(qh + qn) % qv.length] = req; qt[(qh + qn) % qv.length] = t; qn++;
            while (qt[qh] <= t - look) { qh = (qh + 1) % qv.length; qn--; }
            double min = qv[qh];
            // Program-dependent release: sustained limiting recovers slowly so bass is not
            // followed cycle by cycle; a lone hit recovers fast.
            if (min < env) env = min; else env += (min - env) * coef;
            if ((t & 63) == 0) {
                double gr = -20 * Math.log10(env);
                sustained += (gr - sustained) * (1 - Math.pow(1 - sustainCoef, 64));
                double tc = 0.04 + 0.26 * Math.min(1, sustained / 1.5);
                coef = 1 - Math.exp(-1 / (tc * rate));
            }
            boxSum += env - box[bp]; box[bp] = env; bp = (bp + 1) % look;
            double g = boxSum / look;
            double ox = dl[dp], oy = dr[dp];
            dl[dp] = x; dr[dp] = y; dp = (dp + 1) % look;
            l[i] = ox * g; r[i] = oy * g;
            if (t % statEvery == 0) {
                double gr = g >= 0.999999 ? 0 : -20 * Math.log10(g);
                stats.frames++; stats.limiterSumDb += gr;
                if (gr > stats.limiterMaxDb) stats.limiterMaxDb = gr;
                if (gr > 1) stats.over1++;
                if (gr > 3) stats.over3++;
                if (clippedNow) stats.clipped++;
            }
            t++;
        }
        stats.clipMaxDb = 20 * Math.log10(clipPeak);
    }
}
