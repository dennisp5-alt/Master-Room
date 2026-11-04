package com.dennis.masterroom.engine;

import java.util.Arrays;

/** ITU-R BS.1770-4 integrated loudness and EBU Tech 3342 loudness range. */
public final class Loudness {
    public static final class Result {
        public double integrated, lra;
        /** Mean K-weighted power of each 100 ms segment. */
        public double[] segmentPower;
        public int segmentFrames;
        /** 400 ms block loudness at a 100 ms step (LUFS). */
        public double[] blocks;
    }

    private static final class Biquad {
        final double b0, b1, b2, a1, a2;
        double z1, z2;
        Biquad(double b0, double b1, double b2, double a1, double a2) { this.b0 = b0; this.b1 = b1; this.b2 = b2; this.a1 = a1; this.a2 = a2; }
        double run(double x) {
            double y = b0 * x + z1;
            z1 = b1 * x - a1 * y + z2;
            z2 = b2 * x - a2 * y;
            return y;
        }
    }

    private static Biquad shelf(int rate) {
        double f0 = 1681.974450955533, g = 3.999843853973347, q = 0.7071752369554196;
        double k = Math.tan(Math.PI * f0 / rate), vh = Math.pow(10, g / 20), vb = Math.pow(vh, 0.4996667741545416);
        double a0 = 1 + k / q + k * k;
        return new Biquad((vh + vb * k / q + k * k) / a0, 2 * (k * k - vh) / a0, (vh - vb * k / q + k * k) / a0,
                2 * (k * k - 1) / a0, (1 - k / q + k * k) / a0);
    }

    private static Biquad highPass(int rate) {
        double f0 = 38.13547087602444, q = 0.5003270373238773;
        double k = Math.tan(Math.PI * f0 / rate), a0 = 1 + k / q + k * k;
        return new Biquad(1, -2, 1, 2 * (k * k - 1) / a0, (1 - k / q + k * k) / a0);
    }

    public static Result measure(Audio a) {
        int seg = a.rate / 10, count = a.frames() / seg;
        double[] power = new double[Math.max(1, count)];
        Biquad sl = shelf(a.rate), sr = shelf(a.rate), hl = highPass(a.rate), hr = highPass(a.rate);
        int i = 0;
        for (int s = 0; s < count; s++) {
            double sum = 0;
            for (int n = 0; n < seg; n++, i++) {
                double x = hl.run(sl.run(a.l[i])), y = hr.run(sr.run(a.r[i]));
                sum += x * x + y * y;
            }
            power[s] = sum / seg;
        }
        Result out = new Result();
        out.segmentPower = power;
        out.segmentFrames = seg;
        int blocks = Math.max(0, count - 3);
        out.blocks = new double[blocks];
        double[] bp = new double[blocks];
        for (int b = 0; b < blocks; b++) {
            bp[b] = (power[b] + power[b + 1] + power[b + 2] + power[b + 3]) / 4;
            out.blocks[b] = lufs(bp[b]);
        }
        out.integrated = gated(bp);
        out.lra = range(power);
        return out;
    }

    static double lufs(double power) { return -0.691 + 10 * Math.log10(Math.max(1e-20, power)); }

    private static double gated(double[] blockPower) {
        double sum = 0; int n = 0;
        for (double p : blockPower) if (lufs(p) > -70) { sum += p; n++; }
        if (n == 0) return -70;
        double relative = lufs(sum / n) - 10;
        sum = 0; n = 0;
        for (double p : blockPower) if (lufs(p) > -70 && lufs(p) > relative) { sum += p; n++; }
        return n == 0 ? -70 : lufs(sum / n);
    }

    private static double range(double[] power) {
        if (power.length < 30) return 0;
        double[] st = new double[power.length - 29];
        double window = 0;
        for (int i = 0; i < 30; i++) window += power[i];
        for (int i = 0; i < st.length; i++) {
            st[i] = lufs(window / 30);
            if (i + 30 < power.length) window += power[i + 30] - power[i];
        }
        double sum = 0; int n = 0;
        for (double l : st) if (l > -70) { sum += Math.pow(10, (l + 0.691) / 10); n++; }
        if (n == 0) return 0;
        double gate = lufs(sum / n) - 20;
        double[] kept = new double[st.length];
        int k = 0;
        for (double l : st) if (l > -70 && l > gate) kept[k++] = l;
        if (k < 2) return 0;
        kept = Arrays.copyOf(kept, k);
        Arrays.sort(kept);
        return kept[(int) Math.round(0.95 * (k - 1))] - kept[(int) Math.round(0.10 * (k - 1))];
    }
}
