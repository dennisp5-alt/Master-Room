package com.dennis.masterroom.engine;

/** In-place radix-2 complex FFT. Forward has no scaling; inverse divides by n. */
final class Fft {
    final int n;
    private final int[] rev;
    private final double[] cos, sin;

    Fft(int n) {
        if (Integer.bitCount(n) != 1) throw new IllegalArgumentException("FFT size must be a power of two");
        this.n = n;
        rev = new int[n];
        int bits = Integer.numberOfTrailingZeros(n);
        for (int i = 0; i < n; i++) rev[i] = bits == 0 ? 0 : Integer.reverse(i) >>> (32 - bits);
        cos = new double[n / 2 + 1]; sin = new double[n / 2 + 1];
        for (int i = 0; i <= n / 2; i++) { cos[i] = Math.cos(2 * Math.PI * i / n); sin[i] = Math.sin(2 * Math.PI * i / n); }
    }

    void forward(double[] re, double[] im) { run(re, im, -1); }

    void inverse(double[] re, double[] im) {
        run(re, im, 1);
        double s = 1.0 / n;
        for (int i = 0; i < n; i++) { re[i] *= s; im[i] *= s; }
    }

    private void run(double[] re, double[] im, int sign) {
        for (int i = 0; i < n; i++) {
            int j = rev[i];
            if (j > i) { double t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t; }
        }
        for (int size = 2; size <= n; size <<= 1) {
            int half = size >> 1, step = n / size;
            for (int start = 0; start < n; start += size) {
                for (int k = 0, t = 0; k < half; k++, t += step) {
                    double wr = cos[t], wi = sign * sin[t];
                    int a = start + k, b = a + half;
                    double xr = re[b] * wr - im[b] * wi, xi = re[b] * wi + im[b] * wr;
                    re[b] = re[a] - xr; im[b] = im[a] - xi;
                    re[a] += xr; im[a] += xi;
                }
            }
        }
    }
}
