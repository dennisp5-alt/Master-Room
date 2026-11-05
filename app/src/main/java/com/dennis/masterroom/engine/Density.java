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
        final int step = Math.max(1, in.rate / 1000), blocks = in.frames() / step + 2;
        final double[][] level = new double[3][blocks];
        for (float[] channel : new float[][]{in.l, in.r}) {
            final float[] x = channel;
            lows.stream(highs, x, (i, low, high) -> {
                int b = i / step;
                level[0][b] = Math.max(level[0][b], Math.abs(low));
                level[1][b] = Math.max(level[1][b], Math.abs(x[i] - low - high));
                level[2][b] = Math.max(level[2][b], Math.abs(high));
            });
        }
        final float[][] gain = {gains(level[0], mostDb), gains(level[1], mostDb), gains(level[2], mostDb * 0.6)};
        return new Audio(ease(lows, highs, in.l, gain, step), ease(lows, highs, in.r, gain, step), in.rate);
    }

    private static float[] ease(FirFilter lows, FirFilter highs, float[] x, float[][] gain, int step) {
        float[] out = new float[x.length];
        final float[] a = gain[0], b = gain[1], c = gain[2];
        lows.stream(highs, x, (i, low, high) -> {
            int at = i / step;
            float f = (float) (i % step) / step;
            float ga = a[at] + (a[at + 1] - a[at]) * f, gb = b[at] + (b[at + 1] - b[at]) * f, gc = c[at] + (c[at + 1] - c[at]) * f;
            out[i] = (float) (low * ga + (x[i] - low - high) * gb + high * gc);
        });
        return out;
    }

    /** Linear gain every millisecond for one band. */
    private static float[] gains(double[] level, double mostDb) {
        Engineer.checkStop();
        int blocks = level.length, hold = 5;
        double[] loudness = new double[blocks];
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
        float[] out = new float[blocks];
        for (int b = 0; b < blocks; b++) out[b] = (float) Math.pow(10, -cut[b] / 20);
        return out;
    }
}
