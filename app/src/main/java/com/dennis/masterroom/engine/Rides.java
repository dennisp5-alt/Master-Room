package com.dennis.masterroom.engine;

/**
 * Hands on the fader. Evens out a song that swings too far, with long crossfades placed
 * before each change. Very quiet parts stay quiet on purpose.
 */
final class Rides {
    static final class Outcome { double upDb, downDb; }

    static Outcome apply(Audio a, Loudness.Result loud, double amount) {
        Outcome out = new Outcome();
        double[] power = loud.segmentPower;
        int n = power.length;
        if (n < 60 || amount <= 0) return out;
        double[] db = new double[n];
        for (int i = 0; i < n; i++) db[i] = Loudness.lufs(power[i]);
        double[] level = Control.smoothPower(db, 41);
        double[] active = new double[n];
        int m = 0;
        for (double v : level) if (v > loud.integrated - 20) active[m++] = v;
        if (m == 0) return out;
        double ref = Control.percentile(active, m, 0.85);
        double[] gain = new double[n];
        for (int i = 0; i < n; i++) {
            double dev = level[i] - ref, g;
            if (dev >= 0) g = -0.35 * dev;
            else if (dev > -6) g = -0.20 * dev;
            else if (dev > -10) g = 1.2 * (1 - (-dev - 6) / 4);
            else g = 0;
            gain[i] = amount * Math.max(-1.0, Math.min(1.0, g));
        }
        gain = Control.box(Control.box(gain, 21), 21);
        for (double g : gain) { if (g > out.upDb) out.upDb = g; if (-g > out.downDb) out.downDb = -g; }
        Control.applyDb(a.l, null, gain, loud.segmentFrames);
        Control.applyDb(a.r, null, gain, loud.segmentFrames);
        return out;
    }
}
