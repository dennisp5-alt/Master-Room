package com.dennis.masterroom.engine;

import java.util.Arrays;

/**
 * Fixes two things an EQ cannot, because they move: narrow resonances that ring for a
 * moment, and the fizzy swirl generated music often has in the top end.
 *
 * Each short frame is compared with its neighbours in pitch and in time. A band that
 * jumps well above what is normal for this song, and stays there, is turned down only
 * while it rings. A top-end speck that stands alone in time is eased back to its
 * surroundings. Anything sudden in the whole top end is a hit, and is left alone.
 */
final class CleanUp {
    static final class Outcome {
        double resonanceMaxDb, resonancePercent, swirlMaxDb, swirlPercent;
    }

    private static final double LOW_HZ = 250, HIGH_HZ = 14000, BANDS_PER_OCTAVE = 6;
    private static final double SWIRL_FROM_HZ = 7000, SWIRL_TO_HZ = 16000;
    private static final int SPAN = 7;

    private CleanUp() {}

    /** Works in place, so a long song needs no second copy of itself. */
    static Audio apply(Audio in, Outcome out) {
        int n = in.frames(), rate = in.rate;
        float[] mid = in.l, side = in.r;
        for (int i = 0; i < n; i++) {
            float l = mid[i], r = side[i];
            mid[i] = (l + r) * 0.5f;
            side[i] = (l - r) * 0.5f;
        }
        int size = rate > 60000 ? 4096 : 2048, hop = size / 4, frames = (n + size) / hop + 1;
        double[] window = new double[size];
        for (int i = 0; i < size; i++) window[i] = Math.sin(Math.PI * (i + 0.5) / size);
        Fft fft = new Fft(size);
        double hz = (double) rate / size;

        int bandCount = (int) Math.floor(Target.log2(HIGH_HZ / LOW_HZ) * BANDS_PER_OCTAVE) + 1;
        double[] centre = new double[bandCount];
        int[] from = new int[bandCount], to = new int[bandCount];
        for (int b = 0; b < bandCount; b++) {
            centre[b] = LOW_HZ * Math.pow(2, b / BANDS_PER_OCTAVE);
            from[b] = Math.max(1, (int) Math.round(centre[b] / Math.pow(2, 1 / (2 * BANDS_PER_OCTAVE)) / hz));
            to[b] = Math.max(from[b], (int) Math.round(centre[b] * Math.pow(2, 1 / (2 * BANDS_PER_OCTAVE)) / hz));
        }

        float[][] cut = resonance(mid, side, window, fft, frames, hop, rate, from, to, centre, out);
        double[] gainDb = new double[size / 2 + 1];
        float[] outMid = new float[size], outSide = new float[size];

        int first = (int) Math.ceil(SWIRL_FROM_HZ / hz), last = Math.min(size / 2 - 2, (int) Math.floor(Math.min(SWIRL_TO_HZ, rate * 0.45) / hz));
        double[][] re = new double[SPAN][size], im = new double[SPAN][size];
        float[][] power = new float[SPAN][size / 2 + 1];
        double[] top = new double[SPAN];
        double[] line = new double[SPAN], cuts = new double[size / 2 + 1];
        int swirlFrames = 0, loudFrames = 0;
        double swirlMax = 0;
        for (int t = 0; t < frames + SPAN / 2; t++) {
            if (t % 256 == 0) Engineer.checkStop();
            int slot = t % SPAN;
            if (t < frames) {
                load(mid, side, window, t * hop - size, re[slot], im[slot]);
                fft.forward(re[slot], im[slot]);
                top[slot] = spread(re[slot], im[slot], power[slot], first, last, size);
            } else {
                Arrays.fill(re[slot], 0); Arrays.fill(im[slot], 0); Arrays.fill(power[slot], 0); top[slot] = 0;
            }
            int now = t - SPAN / 2;
            if (now < 0) continue;
            int at = now % SPAN;
            Arrays.fill(gainDb, 0);
            shapeResonance(cut[now], centre, hz, gainDb);

            for (int i = 0; i < SPAN; i++) line[i] = top[i];
            Arrays.sort(line);
            boolean hit = top[at] > 2.5 * Math.max(1e-12, line[SPAN / 2]);
            Arrays.fill(cuts, 0);
            boolean any = false;
            if (!hit) {
                for (int k = first; k <= last; k++) {
                    for (int i = 0; i < SPAN; i++) line[i] = power[i][k];
                    Arrays.sort(line);
                    double ratio = power[at][k] / Math.max(1e-14, line[SPAN / 2]);
                    if (ratio > 4) {
                        cuts[k] = Math.min(4, 0.6 * (10 * Math.log10(ratio) - 6));
                        any = true;
                    }
                }
            }
            if (top[at] > 1e-9) loudFrames++;
            if (any) {
                swirlFrames++;
                for (int k = first; k <= last; k++) {
                    double c = Math.max(cuts[k], Math.max(cuts[k - 1], cuts[k + 1]) * 0.5);
                    gainDb[k] -= c;
                    swirlMax = Math.max(swirlMax, c);
                }
            }
            write(re[at], im[at], gainDb, window, fft, outMid, outSide);
            // These samples are finished, and no frame still to come will read them.
            int start = now * hop - size;
            for (int i = 0; i < hop; i++) {
                int p = start + i;
                if (p >= 0 && p < n) { mid[p] = outMid[i]; side[p] = outSide[i]; }
            }
            System.arraycopy(outMid, hop, outMid, 0, size - hop);
            System.arraycopy(outSide, hop, outSide, 0, size - hop);
            Arrays.fill(outMid, size - hop, size, 0);
            Arrays.fill(outSide, size - hop, size, 0);
        }
        out.swirlMaxDb = swirlMax;
        out.swirlPercent = 100.0 * swirlFrames / Math.max(1, loudFrames);
        for (int i = 0; i < n; i++) {
            float m = mid[i], d = side[i];
            mid[i] = m + d;
            side[i] = m - d;
        }
        return in;
    }

    private static void load(float[] mid, float[] side, double[] window, int start, double[] re, double[] im) {
        int n = mid.length;
        for (int i = 0; i < window.length; i++) {
            int p = start + i;
            boolean in = p >= 0 && p < n;
            re[i] = in ? mid[p] * window[i] : 0;
            im[i] = in ? side[p] * window[i] : 0;
        }
    }

    /** Power of mid and side at each bin, slightly smoothed. Returns the top-end total. */
    private static double spread(double[] re, double[] im, float[] power, int first, int last, int size) {
        for (int k = first - 2; k <= last + 2; k++) {
            int m = size - k;
            power[k] = (float) ((re[k] * re[k] + im[k] * im[k] + re[m] * re[m] + im[m] * im[m]) * 0.5);
        }
        double total = 0, before = power[first - 1];
        for (int k = first; k <= last; k++) {
            double p = power[k];
            power[k] = (float) ((before + p + power[k + 1]) / 3);
            before = p;
            total += p;
        }
        return total;
    }

    /** How far to turn each band down in each frame. */
    private static float[][] resonance(float[] mid, float[] side, double[] window, Fft fft, int frames, int hop, int rate,
                                       int[] from, int[] to, double[] centre, Outcome out) {
        int bands = from.length, size = window.length;
        float[][] level = new float[frames][bands];
        double[] re = new double[size], im = new double[size], total = new double[frames];
        for (int t = 0; t < frames; t++) {
            if (t % 256 == 0) Engineer.checkStop();
            load(mid, side, window, t * hop - size, re, im);
            fft.forward(re, im);
            double all = 0;
            for (int b = 0; b < bands; b++) {
                double p = 0;
                for (int k = from[b]; k <= to[b]; k++) {
                    int m = size - k;
                    p += re[k] * re[k] + im[k] * im[k] + re[m] * re[m] + im[m] * im[m];
                }
                all += p;
                level[t][b] = (float) (10 * Math.log10(Math.max(1e-14, p / (to[b] - from[b] + 1))));
            }
            total[t] = 10 * Math.log10(Math.max(1e-14, all));
        }
        double[] sorted = total.clone();
        Arrays.sort(sorted);
        double gate = sorted[sorted.length / 2] - 15;

        // How far each band stands above the bands around it, at each moment.
        float[][] excess = new float[frames][bands];
        for (int t = 0; t < frames; t++) {
            for (int b = 0; b < bands; b++) {
                double sum = 0; int c = 0;
                for (int j = 3; j <= 6; j++) {
                    if (b - j >= 0) { sum += level[t][b - j]; c++; }
                    if (b + j < bands) { sum += level[t][b + j]; c++; }
                }
                excess[t][b] = (float) (level[t][b] - sum / Math.max(1, c));
            }
        }
        // What is ordinary for this song is its tone, not a resonance.
        double[] normal = new double[bands];
        double[] line = new double[frames / 4 + 1];
        for (int b = 0; b < bands; b++) {
            int c = 0;
            for (int t = 0; t < frames; t += 4) if (total[t] > gate) line[c++] = excess[t][b];
            if (c == 0) continue;
            Arrays.sort(line, 0, c);
            normal[b] = line[(int) (c * 0.5)];
        }
        // It has to hold for three frames, about 30 ms. A drum hit does not ring.
        float[][] cut = new float[frames][bands];
        double fall = Math.exp(-(double) hop / (0.15 * rate));
        double[] held = new double[bands];
        int active = 0, loud = 0;
        double max = 0;
        for (int t = 1; t < frames - 1; t++) {
            boolean any = false;
            if (total[t] > gate) loud++;
            for (int b = 0; b < bands; b++) {
                double x = Math.min(excess[t - 1][b], Math.min(excess[t][b], excess[t + 1][b])) - normal[b];
                boolean low = centre[b] < 1000;
                double wanted = total[t] > gate ? Math.min(low ? 2.5 : 4.0, 0.6 * (x - (low ? 18 : 16))) : 0;
                held[b] = Math.max(wanted, held[b] * fall);
                cut[t][b] = (float) held[b];
                if (held[b] > 0.5) any = true;
                max = Math.max(max, held[b]);
            }
            if (any) active++;
        }
        out.resonanceMaxDb = max;
        out.resonancePercent = 100.0 * active / Math.max(1, loud);
        return cut;
    }

    private static void shapeResonance(float[] cut, double[] centre, double hz, double[] gainDb) {
        double step = Target.log2(centre[1] / centre[0]);
        for (int b = 0; b < cut.length; b++) {
            double c = cut[b];
            if (c <= 0.02) continue;
            int lo = (int) (centre[b] * Math.pow(2, -step) / hz), hi = (int) (centre[b] * Math.pow(2, step) / hz) + 1;
            for (int k = Math.max(1, lo); k <= hi && k < gainDb.length; k++) {
                double o = Math.abs(Target.log2(k * hz / centre[b])) / step;
                if (o < 1) gainDb[k] = Math.min(gainDb[k], -c * (1 - o));
            }
        }
    }

    private static void write(double[] re, double[] im, double[] gainDb, double[] window, Fft fft, float[] mid, float[] side) {
        int size = window.length;
        double[] a = re.clone(), b = im.clone();
        for (int k = 1; k < size / 2; k++) {
            if (gainDb[k] == 0) continue;
            double g = Math.pow(10, gainDb[k] / 20);
            int m = size - k;
            a[k] *= g; b[k] *= g; a[m] *= g; b[m] *= g;
        }
        fft.inverse(a, b);
        for (int i = 0; i < size; i++) {
            mid[i] += (float) (a[i] * window[i] * 0.5);
            side[i] += (float) (b[i] * window[i] * 0.5);
        }
    }
}
