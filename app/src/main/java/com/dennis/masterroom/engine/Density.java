package com.dennis.masterroom.engine;

/**
 * Lets a loud master get there without leaning on the limiter. Lows, mids and highs are
 * each eased down on their own loudest moments, so one instrument's peak does not pull
 * everything else down with it. The split is linear phase and adds back exactly. The
 * gain moves smoothly in both directions of time, so it changes level and not the wave:
 * no harmonics are added, and the distortion check still compares against the audio from
 * before this stage.
 */
final class Density {
    private static final double SMOOTH_SECONDS = 0.03, RATIO_SLOPE = 0.6, LOUD_SHARE = 0.85;

    private Density() {}

    static Audio apply(Audio in, double mostDb) {
        FirFilter lows = new FirFilter(hz -> 1 / Math.sqrt(1 + Math.pow(hz / 150, 4)), 8192, in.rate);
        FirFilter highs = new FirFilter(hz -> 1 / Math.sqrt(1 + Math.pow(3000 / Math.max(1, hz), 4)), 8192, in.rate);
        Audio low = new Audio(lows.apply(in.l), lows.apply(in.r), in.rate);
        Audio high = new Audio(highs.apply(in.l), highs.apply(in.r), in.rate);
        int n = in.frames();
        Audio mid = new Audio(n, in.rate);
        for (int i = 0; i < n; i++) {
            mid.l[i] = in.l[i] - low.l[i] - high.l[i];
            mid.r[i] = in.r[i] - low.r[i] - high.r[i];
        }
        ease(low, mostDb); ease(mid, mostDb); ease(high, mostDb * 0.6);
        Audio out = new Audio(n, in.rate);
        for (int i = 0; i < n; i++) {
            out.l[i] = low.l[i] + mid.l[i] + high.l[i];
            out.r[i] = low.r[i] + mid.r[i] + high.r[i];
        }
        return out;
    }

    private static void ease(Audio band, double mostDb) {
        Engineer.checkStop();
        int step = Math.max(1, band.rate / 1000), blocks = band.frames() / step + 2, n = band.frames();
        double[] level = new double[blocks];
        for (int i = 0; i < n; i++) {
            double m = Math.max(Math.abs(band.l[i]), Math.abs(band.r[i]));
            if (m > level[i / step]) level[i / step] = m;
        }
        double[] loudness = new double[blocks];
        int hold = 5;
        for (int b = 0; b < blocks; b++) {
            double m = 0;
            for (int j = Math.max(0, b - hold); j <= Math.min(blocks - 1, b + hold); j++) m = Math.max(m, level[j]);
            loudness[b] = Listen.db(m);
        }
        double[] sorted = loudness.clone();
        java.util.Arrays.sort(sorted);
        double threshold = sorted[(int) (blocks * LOUD_SHARE)];
        double[] cut = new double[blocks];
        for (int b = 0; b < blocks; b++) cut[b] = Math.min(mostDb, Math.max(0, (loudness[b] - threshold) * RATIO_SLOPE));
        double a = Math.exp(-0.001 / SMOOTH_SECONDS), y = 0;
        for (int b = 0; b < blocks; b++) { y = Math.max(cut[b], a * y + (1 - a) * cut[b]); cut[b] = y; }
        y = 0;
        for (int b = blocks - 1; b >= 0; b--) { y = a * y + (1 - a) * cut[b]; cut[b] = y; }
        for (int i = 0; i < n; i++) {
            int b = i / step;
            double f = (double) (i % step) / step;
            float g = (float) Math.pow(10, -(cut[b] + (cut[b + 1] - cut[b]) * f) / 20);
            band.l[i] *= g; band.r[i] *= g;
        }
    }
}
