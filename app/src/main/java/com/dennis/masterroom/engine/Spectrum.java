package com.dennis.masterroom.engine;

/** What the light displays show: real measurements of the audio, ready to draw. */
public final class Spectrum {
    public static final int BARS = 28;
    private static final int SIZE = 2048;
    private static final double LOW_HZ = 45, HIGH_HZ = 16000, TILT_DB_PER_OCTAVE = 4.5;
    private static final double FLOOR_DB = -2, TOP_DB = 26;

    private final Fft fft = new Fft(SIZE);
    private final double[] re = new double[SIZE], im = new double[SIZE], window = new double[SIZE];
    private final int[] from = new int[BARS], to = new int[BARS];
    private final double[] tilt = new double[BARS];

    public Spectrum(int rate) {
        double hz = (double) rate / SIZE;
        for (int i = 0; i < SIZE; i++) window[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / SIZE);
        for (int b = 0; b < BARS; b++) {
            double a = LOW_HZ * Math.pow(HIGH_HZ / LOW_HZ, (double) b / BARS), c = LOW_HZ * Math.pow(HIGH_HZ / LOW_HZ, (double) (b + 1) / BARS);
            from[b] = Math.max(1, (int) Math.floor(a / hz));
            to[b] = Math.max(from[b], Math.min(SIZE / 2 - 1, (int) Math.ceil(c / hz) - 1));
            tilt[b] = TILT_DB_PER_OCTAVE * Target.log2(Math.sqrt(a * c) / 1000);
        }
    }

    /** @param stereo at least 2048 interleaved frames; out gets one 0 to 1 value per bar */
    public void bars(float[] stereo, float[] out) {
        for (int i = 0; i < SIZE; i++) {
            re[i] = (stereo[2 * i] + stereo[2 * i + 1]) * 0.5 * window[i];
            im[i] = 0;
        }
        fft.forward(re, im);
        for (int b = 0; b < BARS; b++) {
            double sum = 0;
            for (int k = from[b]; k <= to[b]; k++) sum += re[k] * re[k] + im[k] * im[k];
            double db = 10 * Math.log10(sum / (to[b] - from[b] + 1) + 1e-12) + tilt[b];
            out[b] = (float) Math.max(0, Math.min(1, (db - FLOOR_DB) / (TOP_DB - FLOOR_DB)));
        }
    }

    /** The take's overall tone as a still shape, for when nothing is playing. */
    public static float[] resting(double[] midDb) {
        float[] out = new float[BARS];
        if (midDb == null) return out;
        double top = -999;
        double[] value = new double[BARS];
        for (int b = 0; b < BARS; b++) {
            double f = LOW_HZ * Math.pow(HIGH_HZ / LOW_HZ, (b + 0.5) / BARS);
            double at = Math.max(0, Math.min(29.999, 17 + 3 * Target.log2(f / 1000)));
            int i = (int) at;
            value[b] = midDb[i] + (midDb[i + 1] - midDb[i]) * (at - i) + TILT_DB_PER_OCTAVE * Target.log2(f / 1000);
            top = Math.max(top, value[b]);
        }
        for (int b = 0; b < BARS; b++) out[b] = (float) Math.max(0.08, 0.6 + (value[b] - top) / 36);
        return out;
    }

    /** Level every 50 ms, lined up with the measured LUFS, so a meter can follow every beat. */
    public static float[] fastLevels(Audio a, double lufs) {
        int step = Math.max(1, a.rate / 20), n = a.frames() / step;
        float[] out = new float[n];
        double mean = 0;
        int used = 0;
        for (int b = 0; b < n; b++) {
            double e = 0;
            for (int i = b * step; i < (b + 1) * step; i++) e += a.l[i] * a.l[i] + a.r[i] * a.r[i];
            out[b] = (float) (10 * Math.log10(e / (2 * step) + 1e-12));
            if (out[b] > -50) { mean += out[b]; used++; }
        }
        double shift = lufs - mean / Math.max(1, used);
        for (int b = 0; b < n; b++) out[b] += (float) shift;
        return out;
    }
}
