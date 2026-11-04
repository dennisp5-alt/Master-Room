package com.dennis.masterroom.engine;

/**
 * Linear-phase filter applied by FFT. Because the render is offline the filter can be
 * long and the delay can be removed exactly, so nothing shifts in time: this is what a
 * mastering studio's linear-phase EQ does, without the real-time latency worry.
 */
final class FirFilter {
    interface Curve { double linear(double hz); }

    private final int len, nfft, hop;
    private final double[] hr, hi;
    private final Fft fft;

    /** @param size power of two; the filter has size-1 taps. */
    FirFilter(Curve curve, int size, int rate) {
        if (Integer.bitCount(size) != 1) throw new IllegalArgumentException("size");
        double[] re = new double[size], im = new double[size];
        for (int k = 0; k <= size / 2; k++) {
            double hz = k * (double) rate / size;
            double m = curve.linear(hz);
            re[k] = m;
            if (k > 0 && k < size / 2) re[size - k] = m;
        }
        new Fft(size).inverse(re, im);
        len = size - 1;
        double[] h = new double[len];
        int centre = (len - 1) / 2;
        for (int i = 0; i < len; i++) {
            double w = 0.42 - 0.5 * Math.cos(2 * Math.PI * (i + 0.5) / len) + 0.08 * Math.cos(4 * Math.PI * (i + 0.5) / len);
            h[i] = re[(i - centre + size) % size] * w;
        }
        int want = Math.max(4096, Integer.highestOneBit(len * 4 - 1) << 1);
        nfft = want;
        hop = nfft - len + 1;
        fft = new Fft(nfft);
        hr = new double[nfft]; hi = new double[nfft];
        System.arraycopy(h, 0, hr, 0, len);
        fft.forward(hr, hi);
    }

    /** Same length as the input, no delay. */
    float[] apply(float[] x) {
        float[] out = new float[x.length];
        int d = (len - 1) / 2;
        double[] re = new double[nfft], im = new double[nfft];
        for (long s = -d; s - d + len - 1 < x.length + (long) hop; s += hop) {
            java.util.Arrays.fill(im, 0);
            for (int i = 0; i < nfft; i++) {
                long p = s + i;
                re[i] = p >= 0 && p < x.length ? x[(int) p] : 0;
            }
            fft.forward(re, im);
            for (int k = 0; k < nfft; k++) {
                double a = re[k] * hr[k] - im[k] * hi[k];
                im[k] = re[k] * hi[k] + im[k] * hr[k];
                re[k] = a;
            }
            fft.inverse(re, im);
            for (int j = len - 1; j < nfft; j++) {
                long idx = s + j - d;
                if (idx >= 0 && idx < x.length) out[(int) idx] = (float) re[j];
            }
        }
        return out;
    }
}
