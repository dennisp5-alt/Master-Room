package com.dennis.masterroom.engine;

import java.util.Arrays;

/** The engineer's first pass: play the whole song and work out what it needs. */
public final class Listen {
    /** Third-octave centres, 20 Hz to 20 kHz. */
    public static final double[] BANDS = bands();

    public Loudness.Result loudness;
    public double truePeakDb, peakDb, crestDb;
    /** Level of ordinary loud peaks, ignoring the very few highest (dBFS). */
    public double robustPeakDb;
    /** Power density of mid and side per band, loud passages only (dB). */
    public double[] midDb, sideDb;
    /** Highest frequency with real content. */
    public double cutoffHz;
    /** Side level relative to mid below 120 Hz (dB). */
    public double lowSideDb;

    private static double[] bands() {
        double[] b = new double[31];
        for (int i = 0; i < 31; i++) b[i] = 1000 * Math.pow(2, (i - 17) / 3.0);
        return b;
    }

    public static Listen to(Audio a) {
        Listen out = new Listen();
        out.loudness = Loudness.measure(a);
        out.truePeakDb = Oversample.truePeakDb(a);
        double rms = 0, peak = 0;
        int frames = a.frames();
        for (int i = 0; i < frames; i++) {
            double x = a.l[i], y = a.r[i];
            rms += x * x + y * y;
            peak = Math.max(peak, Math.max(Math.abs(x), Math.abs(y)));
        }
        out.peakDb = db(peak);
        out.crestDb = out.peakDb - 10 * Math.log10(Math.max(1e-20, rms / Math.max(1, 2 * frames)));
        out.robustPeakDb = robustPeak(a);
        spectrum(a, out);
        return out;
    }

    /** A reference record that has already been measured; the audio itself is not kept. */
    public static Listen fromBands(double[] midDb, double[] sideDb) {
        Listen out = new Listen();
        out.midDb = midDb; out.sideDb = sideDb;
        return out;
    }

    static double db(double x) { return 20 * Math.log10(Math.max(1e-12, x)); }

    /** 99.5th percentile of 10 ms block peaks. */
    private static double robustPeak(Audio a) {
        int block = a.rate / 100, n = a.frames() / block;
        if (n == 0) return db(1e-6);
        float[] peaks = new float[n];
        int i = 0;
        for (int b = 0; b < n; b++) {
            float p = 0;
            for (int k = 0; k < block; k++, i++) {
                p = Math.max(p, Math.max(Math.abs(a.l[i]), Math.abs(a.r[i])));
            }
            peaks[b] = p;
        }
        Arrays.sort(peaks);
        return db(peaks[(int) (0.995 * (n - 1))]);
    }

    private static void spectrum(Audio a, Listen out) {
        int size = 8192, hop = 4096;
        Fft fft = new Fft(size);
        double[] w = new double[size];
        for (int i = 0; i < size; i++) w[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / size);
        double[] re = new double[size], im = new double[size];
        int bins = size / 2 + 1;
        double[] pm = new double[bins], ps = new double[bins];
        double gate = out.loudness.integrated - 6;
        double[] blocks = out.loudness.blocks;
        int used = 0;
        for (int s = 0; s + size <= a.frames(); s += hop) {
            int block = Math.min(blocks.length - 1, (s + size / 2) / out.loudness.segmentFrames);
            if (block < 0 || blocks[block] < gate) continue;
            for (int i = 0; i < size; i++) {
                double l = a.l[s + i], r = a.r[s + i];
                re[i] = (l + r) * 0.5 * w[i];
                im[i] = (l - r) * 0.5 * w[i];
            }
            fft.forward(re, im);
            for (int k = 1; k < bins - 1; k++) {
                int m = size - k;
                double mr = (re[k] + re[m]) / 2, mi = (im[k] - im[m]) / 2;
                double sr = (im[k] + im[m]) / 2, si = (re[m] - re[k]) / 2;
                pm[k] += mr * mr + mi * mi;
                ps[k] += sr * sr + si * si;
            }
            used++;
        }
        double hzPerBin = (double) a.rate / size;
        out.midDb = new double[31]; out.sideDb = new double[31];
        double norm = Math.max(1, used);
        for (int b = 0; b < 31; b++) {
            double lo = BANDS[b] / Math.pow(2, 1 / 6.0), hi = BANDS[b] * Math.pow(2, 1 / 6.0);
            double sm = 0, ss = 0; int n = 0;
            for (int k = Math.max(1, (int) Math.ceil(lo / hzPerBin)); k <= Math.min(bins - 2, (int) Math.floor(hi / hzPerBin)); k++) {
                sm += pm[k]; ss += ps[k]; n++;
            }
            if (n == 0) {
                int k = Math.max(1, Math.min(bins - 2, (int) Math.round(BANDS[b] / hzPerBin)));
                sm = pm[k]; ss = ps[k]; n = 1;
            }
            out.midDb[b] = 10 * Math.log10(Math.max(1e-20, sm / n / norm));
            out.sideDb[b] = 10 * Math.log10(Math.max(1e-20, ss / n / norm));
        }
        double top = Double.NEGATIVE_INFINITY;
        for (int k = 1; k < bins - 1; k++) top = Math.max(top, 10 * Math.log10(Math.max(1e-20, (pm[k] + ps[k]) / norm)));
        double hi = 0;
        for (int k = bins - 2; k > 0; k--) {
            if (10 * Math.log10(Math.max(1e-20, (pm[k] + ps[k]) / norm)) > top - 75) { hi = k * hzPerBin; break; }
        }
        out.cutoffHz = hi;
        double sumM = 0, sumS = 0;
        for (int k = 1; k * hzPerBin < 120; k++) { sumM += pm[k]; sumS += ps[k]; }
        out.lowSideDb = 10 * Math.log10(Math.max(1e-20, sumS) / Math.max(1e-20, sumM));
    }
}
