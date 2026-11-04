package com.dennis.masterroom.engine;

/** 4x up and down sampling around the stages that create new harmonics. */
final class Oversample {
    static final int FACTOR = 4, TAPS = 257;
    /** Round-trip delay in source frames. */
    static final int LATENCY = (TAPS - 1) / FACTOR;
    private static final double[] H = design();

    private static double[] design() {
        double[] h = new double[TAPS];
        double c = (TAPS - 1) / 2.0, beta = 10.06, sum = 0;
        for (int i = 0; i < TAPS; i++) {
            double t = i - c;
            double sinc = t == 0 ? 0.25 : Math.sin(Math.PI * t / 4) / (Math.PI * t);
            double r = t / c;
            h[i] = sinc * bessel(beta * Math.sqrt(Math.max(0, 1 - r * r))) / bessel(beta);
            sum += h[i];
        }
        for (int i = 0; i < TAPS; i++) h[i] /= sum;
        return h;
    }

    private static double bessel(double x) {
        double s = 1, t = 1;
        for (int k = 1; k < 40; k++) { t *= (x / (2 * k)) * (x / (2 * k)); s += t; if (t < 1e-14 * s) break; }
        return s;
    }

    /** Streaming upsampler for one channel. */
    static final class Up {
        private final int hist = (TAPS - 1) / FACTOR + 1;
        private final double[] x;
        Up(int block) { x = new double[hist + block]; }
        void run(float[] in, int off, int n, double[] out) {
            double[] h = H;
            for (int i = 0; i < n; i++) x[hist + i] = off + i < in.length ? in[off + i] : 0;
            int o = 0;
            for (int i = 0; i < n; i++) {
                int now = hist + i;
                for (int p = 0; p < FACTOR; p++) {
                    double s = 0;
                    for (int k = 0, j = p; j < TAPS; k++, j += FACTOR) s += h[j] * x[now - k];
                    out[o++] = s * FACTOR;
                }
            }
            System.arraycopy(x, n, x, 0, hist);
        }
    }

    /** Streaming downsampler for one channel. */
    static final class Down {
        private final double[] z;
        Down(int block) { z = new double[TAPS + block * FACTOR]; }
        void run(double[] in, int n, double[] out) {
            double[] h = H;
            int m = n * FACTOR;
            System.arraycopy(in, 0, z, TAPS, m);
            for (int i = 0; i < n; i++) {
                int now = TAPS + i * FACTOR;
                double s = 0;
                for (int j = 0; j < TAPS; j++) s += h[j] * z[now - j];
                out[i] = s;
            }
            System.arraycopy(z, m, z, 0, TAPS);
        }
    }

    /** One stage of signal path. Runs at [rate] and may delay audio by latency() samples. */
    interface Stage {
        void process(double[] l, double[] r, int n);
        int latency();
    }

    interface Factory { Stage make(int internalRate); }

    private static final int BLOCK = 4096;

    /** Runs a stage at 4x. Output has the same length and timing as the input. */
    static Audio run4x(Audio in, Factory factory) {
        Stage stage = factory.make(in.rate * FACTOR);
        int stageDelay = stage.latency();
        if (stageDelay % FACTOR != 0) throw new IllegalStateException("stage latency must divide by 4");
        int delay = LATENCY + stageDelay / FACTOR;
        int frames = in.frames();
        Audio out = new Audio(frames, in.rate);
        Up ul = new Up(BLOCK), ur = new Up(BLOCK);
        Down dl = new Down(BLOCK), dr = new Down(BLOCK);
        double[] hl = new double[BLOCK * FACTOR], hr = new double[BLOCK * FACTOR], ol = new double[BLOCK], or = new double[BLOCK];
        long total = (long) frames + delay;
        long written = 0;
        for (long pos = 0; pos < total; pos += BLOCK) {
            final int n = (int) Math.min(BLOCK, total - pos), at = (int) Math.min(pos, frames);
            both(() -> ul.run(in.l, at, n, hl), () -> ur.run(in.r, at, n, hr));
            stage.process(hl, hr, n * FACTOR);
            both(() -> dl.run(hl, n, ol), () -> dr.run(hr, n, or));
            for (int i = 0; i < n; i++, written++) {
                long idx = written - delay;
                if (idx >= 0 && idx < frames) { out.l[(int) idx] = (float) ol[i]; out.r[(int) idx] = (float) or[i]; }
            }
        }
        return out;
    }

    private static final java.util.concurrent.ExecutorService POOL =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "fir"); t.setDaemon(true); return t; });

    private static void both(Runnable a, Runnable b) {
        java.util.concurrent.Future<?> f = POOL.submit(a);
        b.run();
        try { f.get(); } catch (Exception e) { throw new RuntimeException(e); }
    }

    /** Same stage at the source rate: used for a quick search before the real render. */
    static Audio run1x(Audio in, Factory factory) {
        Stage stage = factory.make(in.rate);
        int delay = stage.latency(), frames = in.frames();
        Audio out = new Audio(frames, in.rate);
        double[] l = new double[BLOCK], r = new double[BLOCK];
        long total = (long) frames + delay, written = 0;
        for (long pos = 0; pos < total; pos += BLOCK) {
            int n = (int) Math.min(BLOCK, total - pos);
            for (int i = 0; i < n; i++) {
                long p = pos + i;
                l[i] = p < frames ? in.l[(int) p] : 0;
                r[i] = p < frames ? in.r[(int) p] : 0;
            }
            stage.process(l, r, n);
            for (int i = 0; i < n; i++, written++) {
                long idx = written - delay;
                if (idx >= 0 && idx < frames) { out.l[(int) idx] = (float) l[i]; out.r[(int) idx] = (float) r[i]; }
            }
        }
        return out;
    }

    /** ITU-style true peak in dBTP from the 4x signal. */
    static double truePeakDb(Audio a) {
        Up ul = new Up(BLOCK), ur = new Up(BLOCK);
        double[] hl = new double[BLOCK * FACTOR], hr = new double[BLOCK * FACTOR];
        double peak = 0;
        int frames = a.frames();
        for (int pos = 0; pos < frames + LATENCY; pos += BLOCK) {
            final int n = Math.min(BLOCK, frames + LATENCY - pos), at = Math.min(pos, frames);
            both(() -> ul.run(a.l, at, n, hl), () -> ur.run(a.r, at, n, hr));
            for (int i = 0; i < n * FACTOR; i++) {
                double x = Math.abs(hl[i]), y = Math.abs(hr[i]);
                if (x > peak) peak = x;
                if (y > peak) peak = y;
            }
        }
        return 20 * Math.log10(Math.max(1e-12, peak));
    }
}
