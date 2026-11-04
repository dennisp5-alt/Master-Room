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
        // Repair: real damage is found and rebuilt, clean music is left alone.
        Audio clean = music(20, rate);
        Repair.Outcome none = Repair.apply(clean);
        check("Clean audio gets no repairs", none.clicks + none.dropouts == 0 && none.audio == clean, none.clicks + " clicks");
        Audio damaged = clean.copy();
        int[] spots = {rate * 3 + rate / 4, rate * 7 + rate / 3, rate * 11 + rate / 4, rate * 15 + rate / 3};
        for (int at : spots) { damaged.l[at] += 0.35f; damaged.r[at] -= 0.3f; }
        Repair.Outcome mended = Repair.apply(damaged);
        double left = 0;
        for (int at : spots) left = Math.max(left, Math.abs(mended.audio.l[at] - clean.l[at]));
        check("Clicks are found and rebuilt", mended.clicks >= spots.length && left < 0.05, String.format(Locale.US, "%d found, worst left %.3f of 0.35", mended.clicks, left));
        Audio burst = new Audio(rate * 8, rate);
        Random z = new Random(2);
        for (int i = 0; i < rate / 40; i++) burst.l[i] = burst.r[i] = (float) (0.2 * z.nextGaussian());
        for (int i = rate; i < burst.frames(); i++) burst.l[i] = burst.r[i] = (float) (0.2 * Math.sin(2 * Math.PI * 220 * i / rate));
        check("Stray burst before the song is removed", Repair.apply(burst).startBurst, "");

        // Clipper: a held note must reach the limiter unclipped, a drum hit must not.
        Audio held = sine(1000, -3, 3, rate, 0);
        Stack[] hs = new Stack[1];
        Audio heldOut = Oversample.run4x(held, r -> hs[0] = new Stack(r, 0, -3.5, 2, -6));
        double third = harmonic(heldOut.l, 1000, rate, 3);
        check("Clipper leaves a held note alone", hs[0].stats.clippedPercent() < 2 && third < -55, String.format(Locale.US, "clipped %.3f%%, 3rd harmonic %.1f dB", hs[0].stats.clippedPercent(), third));
        Audio hits = new Audio(rate * 3, rate);
        for (int i = 0; i < hits.frames(); i++) {
            double t = (i % (rate / 2)) / (double) rate;
            hits.l[i] = hits.r[i] = (float) (0.1 * Math.sin(2 * Math.PI * 220 * i / rate) + 0.9 * Math.exp(-t * 200) * Math.sin(2 * Math.PI * 180 * t));
        }
        Stack[] ds = new Stack[1];
        Oversample.run4x(hits, r -> ds[0] = new Stack(r, 0, -3.5, 2, -6));
        check("Clipper still shaves drum hits", ds[0].stats.clipMaxDb > 1, String.format(Locale.US, "up to %.1f dB", ds[0].stats.clipMaxDb));

        // Previews: playback-only curves do what their names say.
        check("Phone preview loses deep bass", previewDb(Preview.Mode.PHONE, 60) < previewDb(Preview.Mode.PHONE, 1000) - 20, String.format(Locale.US, "60 Hz %.1f dB, 1 kHz %.1f dB", previewDb(Preview.Mode.PHONE, 60), previewDb(Preview.Mode.PHONE, 1000)));
        check("Phone preview keeps upper bass and top end", previewDb(Preview.Mode.PHONE, 250) > previewDb(Preview.Mode.PHONE, 1000) - 2 && previewDb(Preview.Mode.PHONE, 12000) > previewDb(Preview.Mode.PHONE, 1000) - 2,
                String.format(Locale.US, "120 Hz %.1f, 250 Hz %.1f, 1 kHz %.1f, 3 kHz %.1f, 12 kHz %.1f dB", previewDb(Preview.Mode.PHONE, 120), previewDb(Preview.Mode.PHONE, 250), previewDb(Preview.Mode.PHONE, 1000), previewDb(Preview.Mode.PHONE, 3000), previewDb(Preview.Mode.PHONE, 12000)));
        check("Small speaker preview loses sub bass", previewDb(Preview.Mode.SMALL, 40) < previewDb(Preview.Mode.SMALL, 130) - 10, "");
        check("Full preview changes nothing", Math.abs(previewDb(Preview.Mode.FULL, 1000)) < 0.01, "");

        // Taste: nothing until there is enough to go on, then it leans towards your picks.
        java.util.List<String> log = new java.util.ArrayList<>();
        for (int i = 0; i < 7; i++) log.add(Taste.record("COUNTRY", "blind", "Warm"));
        check("No Yours take from too few picks", Taste.yours(log, "COUNTRY") == null, "");
        log.add(Taste.record("COUNTRY", "saved", "Punch"));
        Engineer.Recipe mine = Taste.yours(log, "COUNTRY");
        check("Yours take leans to what you pick", mine != null && Math.abs(mine.valve - 0.49) < 0.02, mine == null ? "none" : String.format(Locale.US, "valve %.2f", mine.valve));

        System.out.println(failed == 0 ? "ALL_ENGINE_CHECKS_PASS" : failed + " CHECK(S) FAILED");
        if (failed != 0) System.exit(1);
    }
    static double previewDb(Preview.Mode mode, double hz) {
        int rate = 48000, n = rate;
        float[] io = new float[n * 2];
        for (int i = 0; i < n; i++) io[2 * i] = io[2 * i + 1] = (float) (0.1 * Math.sin(2 * Math.PI * hz * i / rate));
        new Preview(mode, rate).process(io, n);
        double p = 0;
        for (int i = n; i < 2 * n; i++) p += io[i] * io[i];
        return 10 * Math.log10(p / n / 0.005);
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
