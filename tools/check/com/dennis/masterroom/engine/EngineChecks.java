package com.dennis.masterroom.engine;

import java.util.Locale;
import java.util.Random;

/** Host checks for the parts of the engine that have a right answer. */
public final class EngineChecks {
    static int failed;
    static void check(String name, boolean ok, String detail) {
        System.out.printf(Locale.US, "%s %-46s %s%n", ok ? "PASS" : "FAIL", name, detail);
        if (!ok) failed++;
    }
    static Audio sine(double hz, double db, double seconds, int rate, double phase) {
        int n = (int) (seconds * rate);
        Audio a = new Audio(n, rate);
        double amp = Math.pow(10, db / 20);
        for (int i = 0; i < n; i++) a.l[i] = a.r[i] = (float) (amp * Math.sin(2 * Math.PI * hz * i / rate + phase));
        return a;
    }
    static Audio music(int seconds, int rate) {
        Random q = new Random(5);
        int n = seconds * rate;
        Audio a = new Audio(n, rate);
        double lp1 = 0, lp2 = 0;
        for (int i = 0; i < n; i++) {
            double e = Math.exp(-((i % (rate / 2)) / (0.08 * rate)));
            lp1 += (q.nextGaussian() - lp1) * 0.3; lp2 += (q.nextGaussian() - lp2) * 0.3;
            a.l[i] = (float) (0.2 * e * lp1 + 0.08 * Math.sin(2 * Math.PI * 110 * i / rate));
            a.r[i] = (float) (0.2 * e * lp2 + 0.08 * Math.sin(2 * Math.PI * 110 * i / rate + 0.2));
        }
        return a;
    }
    /** Level of harmonic h relative to the fundamental, dB, by correlation. */
    static double harmonic(float[] x, double hz, int rate, int h) {
        int from = x.length / 4, to = x.length * 3 / 4;
        double c1 = 0, s1 = 0, ch = 0, sh = 0;
        for (int i = from; i < to; i++) {
            double w = 2 * Math.PI * hz * i / rate;
            c1 += x[i] * Math.cos(w); s1 += x[i] * Math.sin(w);
            ch += x[i] * Math.cos(w * h); sh += x[i] * Math.sin(w * h);
        }
        return 10 * Math.log10((ch * ch + sh * sh + 1e-30) / (c1 * c1 + s1 * s1 + 1e-30));
    }
    static double nullDb(Audio a, Audio b, int skip) {
        double e = 0, p = 0;
        for (int i = skip; i < a.frames() - skip; i++) {
            double d = a.l[i] - b.l[i], f = a.r[i] - b.r[i];
            e += d * d + f * f; p += a.l[i] * a.l[i] + a.r[i] * a.r[i];
        }
        return 10 * Math.log10((e + 1e-30) / (p + 1e-30));
    }

    public static void main(String[] args) {
        int rate = 48000;
        Audio s = sine(997, -20, 6, rate, 0);
        double l = Loudness.measure(s).integrated;
        check("BS.1770: -20 dBFS stereo 997 Hz", Math.abs(l + 20) < 0.05, String.format(Locale.US, "%.3f LUFS", l));

        Audio ts = sine(12000, -6, 1, rate, Math.PI / 4);
        double tp = Oversample.truePeakDb(ts), sp = Listen.db(max(ts));
        check("True peak finds an inter-sample peak", Math.abs(tp + 6) < 0.1 && sp < -8.5, String.format(Locale.US, "samples %.2f, true %.2f dB", sp, tp));

        Audio raw = music(8, rate);
        FirFilter band = new FirFilter(hz -> hz < 18000 ? 1 : 0, 4096, rate);
        Audio mu = new Audio(band.apply(raw.l), band.apply(raw.r), rate);
        Audio through = new Audio(new FirFilter(hz -> 1, 16384, rate).apply(mu.l), new FirFilter(hz -> 1, 16384, rate).apply(mu.r), rate);
        check("Linear-phase filter is exact when flat", nullDb(mu, through, 0) < -90, String.format(Locale.US, "null %.1f dB", nullDb(mu, through, 0)));

        Audio passed = Oversample.run4x(mu, r -> new Stack(r, 0, 20, 0, 20));
        check("4x path with nothing to do changes nothing", nullDb(mu, passed, 2000) < -60, String.format(Locale.US, "null %.1f dB", nullDb(mu, passed, 2000)));
        check("4x path keeps every frame", passed.frames() == mu.frames(), passed.frames() + " frames");

        Audio hot = mu.gained(14);
        Stack[] st = new Stack[1];
        Audio lim = Oversample.run4x(hot, r -> st[0] = new Stack(r, 0, 20, 0, -1));
        double ltp = Oversample.truePeakDb(lim);
        check("Limiter holds -1 dBTP with 14 dB too much", ltp <= -0.9, String.format(Locale.US, "%.2f dBTP, limiter max %.1f dB", ltp, st[0].stats.limiterMaxDb));

        Audio up = Resample.to(sine(1000, -6, 2, 48000, 0), 44100);
        check("48 to 44.1 kHz length", up.frames() == 88200, up.frames() + " frames");
        double spur = Math.max(harmonic(up.l, 1000, 44100, 3), harmonic(up.l, 1000, 44100, 2));
        check("48 to 44.1 kHz stays clean", spur < -100, String.format(Locale.US, "worst harmonic %.1f dB", spur));

        for (double level : new double[]{-24, -12, -6}) {
            Audio t = sine(1000, level, 3, rate, 0);
            final double op = 0;
            Audio w = Oversample.run4x(t, r -> new Tone(r, op, 0.5, 0.35, 0.5));
            System.out.printf(Locale.US, "INFO Warm path at %5.0f dBFS, 1 kHz: 2nd %.1f dB, 3rd %.1f dB%n", level, harmonic(w.l, 1000, rate, 2), harmonic(w.l, 1000, rate, 3));
            Audio b = sine(60, level, 3, rate, 0);
            Audio wb = Oversample.run4x(b, r -> new Tone(r, op, 0.5, 0.35, 0.5));
            System.out.printf(Locale.US, "INFO Warm path at %5.0f dBFS,  60 Hz: 2nd %.1f dB, 3rd %.1f dB%n", level, harmonic(wb.l, 60, rate, 2), harmonic(wb.l, 60, rate, 3));
        }
        // Different source formats and awkward files.
        Audio cd = music(12, 44100);
        Engineer.Session cs = Engineer.prepare(cd, Target.builtIn(Target.Style.ROCK), (x, y) -> { });
        Engineer.Result cr = Engineer.master(cs, Engineer.Take.WARM, -12, (x, y) -> { });
        check("44.1 kHz source keeps rate and length", cr.master.rate == 44100 && cr.master.frames() == cd.frames(), cr.master.frames() + " frames");
        check("44.1 kHz source reaches target", Math.abs(cr.after.loudness.integrated + 12) < 0.3 && cr.after.truePeakDb <= -2.0, String.format(Locale.US, "%.2f LUFS, %.2f dBTP", cr.after.loudness.integrated, cr.after.truePeakDb));
        check("Refuses a silent file", refused(new Audio(rate * 8, rate)), "with a plain message");
        check("Refuses a very short file", refused(sine(440, -12, 2, rate, 0)), "with a plain message");
        Audio tail = music(10, rate);
        for (int i = tail.frames() - rate * 3; i < tail.frames(); i++) { tail.l[i] = 0; tail.r[i] = 0; }
        Engineer.Result tr = Engineer.master(Engineer.prepare(tail, Target.builtIn(Target.Style.POP), (x, y) -> { }), Engineer.Take.CLEAN, -14, (x, y) -> { });
        check("Silence at the end stays clean", finite(tr.master), "no invalid samples");
        System.out.println(failed == 0 ? "ALL_ENGINE_CHECKS_PASS" : failed + " CHECK(S) FAILED");
        if (failed != 0) System.exit(1);
    }
    static boolean refused(Audio a) {
        try { Engineer.prepare(a, Target.builtIn(Target.Style.COUNTRY), (x, y) -> { }); return false; }
        catch (IllegalArgumentException e) { return true; }
    }
    static boolean finite(Audio a) {
        for (int i = 0; i < a.frames(); i++) if (Float.isNaN(a.l[i]) || Float.isInfinite(a.l[i]) || Float.isNaN(a.r[i]) || Float.isInfinite(a.r[i])) return false;
        return true;
    }
    static double max(Audio a) { double m = 0; for (int i = 0; i < a.frames(); i++) m = Math.max(m, Math.abs(a.l[i])); return m; }
}
