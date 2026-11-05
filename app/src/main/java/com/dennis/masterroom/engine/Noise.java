package com.dennis.masterroom.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Looks for noise that never stops: hiss, and steady whine, buzz or hum. It can only be
 * measured where the music drops away, so it listens to the song's quiet moments and
 * asks whether the same sound is there each time. A reverb tail or a fade changes from
 * moment to moment, so it is left alone.
 *
 * When noise is clearly there, each frequency is eased back only while the music at
 * that frequency is close to the noise. Anything well above it passes untouched, so the
 * top end of the loud parts keeps its life. With nothing found, the song is not touched.
 */
final class Noise {
    static final class Finding {
        /** False when the song has too few quiet moments to judge. */
        boolean measured;
        boolean hiss;
        double hissDb = Double.NEGATIVE_INFINITY;
        final List<Double> tonesHz = new ArrayList<>();
        double most, quietSeconds;
        private double[] floor;
        private boolean[] treat;
        private double[] maxCut;

        boolean found() { return hiss || !tonesHz.isEmpty(); }

        String describe() {
            if (!found()) return measured ? "Hiss, whine and hum: none found"
                    : "Hiss and hum: no quiet moments to measure. Whine: none found";
            StringBuilder b = new StringBuilder();
            if (hiss) b.append(String.format(Locale.US, "Hiss at %.0f dBFS", hissDb));
            if (!tonesHz.isEmpty()) {
                b.append(b.length() == 0 ? "Steady tone at " : ". Steady tone at ");
                for (int i = 0; i < tonesHz.size(); i++) b.append(i == 0 ? "" : ", ").append(hz(tonesHz.get(i)));
            }
            if (!measured) b.append(". Hiss and hum: no quiet moments to measure");
            return b.toString();
        }

        private static String hz(double f) {
            return f >= 1000 ? String.format(Locale.US, "%.1f kHz", f / 1000) : String.format(Locale.US, "%.0f Hz", f);
        }
    }

    private static final double SILENT_DB = -96, QUIET_BELOW_LOUD_DB = 22;
    private static final double MIN_QUIET_SECONDS = 0.6, MIN_HISS_DB = -74;
    private static final double HISS_FROM_HZ = 3000, HISS_BAND_LOW = 6000, HISS_BAND_HIGH = 14000;
    private static final double MOST_HISS_DB = 6, MOST_TONE_DB = 10, RELEASE_SECONDS = 0.15;
    private static final double OPEN_ABOVE_DB = 12, TONE_ABOVE_DB = 12, WHINE_ABOVE_DB = 9, WHINE_FROM_HZ = 6000;

    private Noise() {}

    private static int sizeFor(int rate) { return rate > 60000 ? 8192 : 4096; }

    static Finding find(Audio a) {
        Finding out = new Finding();
        int size = sizeFor(a.rate), hop = size / 4, frames = (a.frames() - size) / hop;
        if (frames < 8) return out;
        double[] window = new double[size];
        double norm = 0;
        for (int i = 0; i < size; i++) { window[i] = Math.sin(Math.PI * (i + 0.5) / size); norm += window[i] * window[i]; }
        float[] mid = new float[size], side = new float[size];

        // Step one: the level of every frame, to find the quiet moments.
        double[] level = new double[frames];
        for (int t = 0; t < frames; t++) {
            double e = 0;
            int at = t * hop;
            for (int i = 0; i < size; i += 4) {
                double l = a.l[at + i], r = a.r[at + i];
                e += l * l + r * r;
            }
            level[t] = 10 * Math.log10(Math.max(1e-14, e / (size / 4.0 * 2)));
        }
        double[] sorted = level.clone();
        Arrays.sort(sorted);
        double loud = sorted[(int) (frames * 0.9)];
        boolean[] quiet = new boolean[frames];
        int count = 0;
        for (int t = 0; t < frames; t++) {
            if (level[t] > SILENT_DB && level[t] < loud - QUIET_BELOW_LOUD_DB) { quiet[t] = true; count++; }
        }
        double hopSeconds = (double) hop / a.rate;
        out.quietSeconds = count * hopSeconds;
        if (out.quietSeconds < MIN_QUIET_SECONDS) {
            whineUnderMusic(a, window, out);
            return out;
        }
        out.measured = true;

        // Step two: the spectrum in those moments, in overlapping thirds of them,
        // so sound that fades or changes can be told from sound that never moves.
        Fft fft = new Fft(size);
        int bins = size / 2 + 1;
        double[] re = new double[size], im = new double[size];
        double[][] parts = new double[3][bins];
        int[] used = new int[3];
        double[] power = new double[bins], hissFrames = new double[count];
        double hz = (double) a.rate / size;
        int hissFrom = (int) (HISS_BAND_LOW / hz), hissTo = Math.min(bins - 2, (int) (HISS_BAND_HIGH / hz));
        int seen = 0;
        for (int t = 0; t < frames; t++) {
            if (!quiet[t]) continue;
            int at = t * hop;
            for (int i = 0; i < size; i++) {
                double l = a.l[at + i], r = a.r[at + i];
                re[i] = (l + r) * 0.5 * window[i];
                im[i] = (l - r) * 0.5 * window[i];
            }
            fft.forward(re, im);
            int part = Math.min(2, seen * 3 / count);
            double band = 0;
            for (int k = 1; k < bins - 1; k++) {
                int m = size - k;
                double p = (re[k] * re[k] + im[k] * im[k] + re[m] * re[m] + im[m] * im[m]) * 0.5;
                parts[part][k] += p;
                power[k] += p;
                if (k >= hissFrom && k <= hissTo) band += p;
            }
            used[part]++;
            hissFrames[seen++] = 10 * Math.log10(Math.max(1e-14, band / Math.max(1, hissTo - hissFrom + 1)));
        }
        for (int k = 0; k < bins; k++) {
            power[k] /= Math.max(1, seen);
            for (int p = 0; p < 3; p++) parts[p][k] /= Math.max(1, used[p]);
        }
        out.floor = power;
        out.treat = new boolean[bins];
        out.maxCut = new double[bins];

        // Hiss: level high enough to matter, flat, and the same every time.
        double band = 0, logs = 0;
        for (int k = hissFrom; k <= hissTo; k++) { band += power[k]; logs += Math.log(Math.max(1e-20, power[k])); }
        int width = Math.max(1, hissTo - hissFrom + 1);
        band /= width;
        double flat = Math.exp(logs / width) / Math.max(1e-20, band);
        double[] partDb = new double[3];
        for (int p = 0; p < 3; p++) {
            double s = 0;
            for (int k = hissFrom; k <= hissTo; k++) s += parts[p][k];
            partDb[p] = 10 * Math.log10(Math.max(1e-20, s / width));
        }
        double spread = Math.max(partDb[0], Math.max(partDb[1], partDb[2])) - Math.min(partDb[0], Math.min(partDb[1], partDb[2]));
        double mean = 0, var = 0;
        for (int i = 0; i < seen; i++) mean += hissFrames[i] / seen;
        for (int i = 0; i < seen; i++) var += (hissFrames[i] - mean) * (hissFrames[i] - mean) / seen;
        double wobble = Math.sqrt(var);
        double scale = 2 / (norm * size);
        // Per-band level as a full-scale RMS figure across the 8 kHz wide band.
        out.hissDb = 10 * Math.log10(Math.max(1e-20, band * scale * (hissTo - hissFrom + 1) * 2));
        if (out.hissDb > MIN_HISS_DB && spread < 3 && wobble < 2.5 && flat > 0.75) {
            out.hiss = true;
            for (int k = (int) (HISS_FROM_HZ / hz); k < bins - 1; k++) { out.treat[k] = true; out.maxCut[k] = MOST_HISS_DB; }
        }

        // Tones: a narrow peak at the same place, at the same height, in every part.
        double hzFrom = 40;
        for (int k = Math.max(3, (int) (hzFrom / hz)); k < bins - 20; k++) {
            if (power[k] < power[k - 1] || power[k] < power[k + 1]) continue;
            double[] around = new double[32];
            int c = 0;
            for (int j = -18; j <= 18; j++) if (Math.abs(j) > 2 && k + j > 0 && c < around.length) around[c++] = power[k + j];
            Arrays.sort(around, 0, c);
            double base = Math.max(1e-20, around[c / 2]);
            double rise = 10 * Math.log10(power[k] / base);
            if (rise < TONE_ABOVE_DB) continue;
            double low = Double.MAX_VALUE, high = -Double.MAX_VALUE;
            for (int p = 0; p < 3; p++) {
                double d = 10 * Math.log10(Math.max(1e-20, Math.max(parts[p][k - 1], Math.max(parts[p][k], parts[p][k + 1]))));
                low = Math.min(low, d);
                high = Math.max(high, d);
            }
            if (high - low > 2) continue;
            if (10 * Math.log10(Math.max(1e-20, power[k] * scale)) < -90) continue;
            mark(out, power, k, hz);
        }
        return out;
    }

    private static void mark(Finding out, double[] power, int k, double hz) {
        double a = Math.log(Math.max(1e-20, power[k - 1])), b = Math.log(Math.max(1e-20, power[k])), c = Math.log(Math.max(1e-20, power[k + 1]));
        double f = (k + 0.5 * (a - c) / Math.min(-1e-9, a - 2 * b + c)) * hz;
        if (out.tonesHz.isEmpty() || f - out.tonesHz.get(out.tonesHz.size() - 1) > 3 * hz) out.tonesHz.add(f);
        for (int j = -2; j <= 2; j++) { out.treat[k + j] = true; out.maxCut[k + j] = MOST_TONE_DB; }
    }

    /**
     * A song with no quiet moments can still carry a whine, because a tone that never
     * stops shows up in the music's quietest instants across the whole song.
     */
    private static void whineUnderMusic(Audio a, double[] window, Finding out) {
        int size = window.length, bins = size / 2 + 1, samples = 240, last = a.frames() - size;
        if (last <= 0) return;
        Fft fft = new Fft(size);
        double hz = (double) a.rate / size;
        double[] re = new double[size], im = new double[size];
        float[][] all = new float[samples][bins];
        for (int s = 0; s < samples; s++) {
            int at = (int) ((long) last * s / (samples - 1));
            for (int i = 0; i < size; i++) {
                re[i] = (a.l[at + i] + a.r[at + i]) * 0.5 * window[i];
                im[i] = (a.l[at + i] - a.r[at + i]) * 0.5 * window[i];
            }
            fft.forward(re, im);
            for (int k = 1; k < bins - 1; k++) {
                int m = size - k;
                all[s][k] = (float) ((re[k] * re[k] + im[k] * im[k] + re[m] * re[m] + im[m] * im[m]) * 0.5);
            }
        }
        double[][] low = new double[3][bins];
        double[] line = new double[samples / 3], power = new double[bins];
        for (int p = 0; p < 3; p++) {
            for (int k = 1; k < bins - 1; k++) {
                for (int s = 0; s < line.length; s++) line[s] = all[p * line.length + s][k];
                Arrays.sort(line);
                low[p][k] = line[line.length / 5];
                power[k] += low[p][k] / 3;
            }
        }
        out.floor = power;
        out.treat = new boolean[bins];
        out.maxCut = new double[bins];
        double scale = 2 / (Arrays.stream(window).map(w -> w * w).sum() * size);
        for (int k = (int) (WHINE_FROM_HZ / hz); k < bins - 20; k++) {
            if (power[k] < power[k - 1] || power[k] < power[k + 1]) continue;
            double[] around = new double[32];
            int c = 0;
            for (int j = -18; j <= 18; j++) if (Math.abs(j) > 2 && c < around.length) around[c++] = power[k + j];
            Arrays.sort(around, 0, c);
            if (10 * Math.log10(power[k] / Math.max(1e-20, around[c / 2])) < WHINE_ABOVE_DB) continue;
            double lowest = Double.MAX_VALUE, highest = -Double.MAX_VALUE;
            for (int p = 0; p < 3; p++) {
                double d = 10 * Math.log10(Math.max(1e-20, Math.max(low[p][k - 1], Math.max(low[p][k], low[p][k + 1]))));
                lowest = Math.min(lowest, d);
                highest = Math.max(highest, d);
            }
            if (highest - lowest > 2 || 10 * Math.log10(Math.max(1e-20, power[k] * scale)) < -90) continue;
            mark(out, power, k, hz);
        }
    }

    /** Eases noise down where it lies, in place. */
    static void apply(Audio a, Finding f) {
        if (!f.found()) return;
        int n = a.frames(), size = sizeFor(a.rate), hop = size / 4, frames = (n + size) / hop + 1, bins = size / 2 + 1;
        float[] mid = a.l, side = a.r;
        for (int i = 0; i < n; i++) {
            float l = mid[i], r = side[i];
            mid[i] = (l + r) * 0.5f;
            side[i] = (l - r) * 0.5f;
        }
        double[] window = new double[size];
        for (int i = 0; i < size; i++) window[i] = Math.sin(Math.PI * (i + 0.5) / size);
        Fft fft = new Fft(size);
        double[] re = new double[size], im = new double[size], cut = new double[bins], power = new double[bins];
        float[] outMid = new float[size], outSide = new float[size];
        double fall = Math.exp(-(double) hop / (RELEASE_SECONDS * a.rate));
        double most = 0;
        for (int t = 0; t < frames; t++) {
            if (t % 256 == 0) Engineer.checkStop();
            int start = t * hop - size;
            for (int i = 0; i < size; i++) {
                int p = start + i;
                boolean in = p >= 0 && p < n;
                re[i] = in ? mid[p] * window[i] : 0;
                im[i] = in ? side[p] * window[i] : 0;
            }
            fft.forward(re, im);
            for (int k = 1; k < bins - 1; k++) {
                int m = size - k;
                power[k] = (re[k] * re[k] + im[k] * im[k] + re[m] * re[m] + im[m] * im[m]) * 0.5;
            }
            for (int k = 1; k < bins - 1; k++) {
                if (!f.treat[k]) continue;
                double near = (power[k - 1] + 2 * power[k] + power[k + 1]) / 4;
                double above = 10 * Math.log10(Math.max(1e-20, near) / Math.max(1e-20, f.floor[k]));
                double wanted = f.maxCut[k] * Math.max(0, Math.min(1, 1 - above / OPEN_ABOVE_DB));
                // Music arriving opens the frequency at once; noise is eased down gradually behind it.
                cut[k] = wanted > cut[k] ? cut[k] + (wanted - cut[k]) * (1 - fall) : wanted;
                most = Math.max(most, cut[k]);
                if (cut[k] > 0.01) {
                    double g = Math.pow(10, -cut[k] / 20);
                    int m = size - k;
                    re[k] *= g; im[k] *= g; re[m] *= g; im[m] *= g;
                }
            }
            fft.inverse(re, im);
            for (int i = 0; i < size; i++) {
                outMid[i] += (float) (re[i] * window[i] * 0.5);
                outSide[i] += (float) (im[i] * window[i] * 0.5);
            }
            for (int i = 0; i < hop; i++) {
                int p = start + i;
                if (p >= 0 && p < n) { mid[p] = outMid[i]; side[p] = outSide[i]; }
            }
            System.arraycopy(outMid, hop, outMid, 0, size - hop);
            System.arraycopy(outSide, hop, outSide, 0, size - hop);
            Arrays.fill(outMid, size - hop, size, 0);
            Arrays.fill(outSide, size - hop, size, 0);
        }
        f.most = most;
        for (int i = 0; i < n; i++) {
            float m = mid[i], d = side[i];
            mid[i] = m + d;
            side[i] = m - d;
        }
    }
}
