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

        // Distortion: gain alone never counts, real clipping does, on any level.
        Audio song = music(12, rate);
        check("Identical audio passes the distortion check", Distortion.check(song, song).spots.isEmpty(), "");
        check("Level changes are not distortion", Distortion.check(song, song.gained(-6)).spots.isEmpty(), "");
        double top = 0;
        for (int i = 0; i < song.frames(); i++) top = Math.max(top, Math.abs(song.l[i]));
        Audio crushed = song.copy();
        float cap = (float) (top * 0.25);
        for (int i = 0; i < crushed.frames(); i++) {
            crushed.l[i] = Math.max(-cap, Math.min(cap, crushed.l[i]));
            crushed.r[i] = Math.max(-cap, Math.min(cap, crushed.r[i]));
        }
        Distortion.Report bad = Distortion.check(song, crushed);
        check("Heavy clipping is caught", bad.audible > 0, bad.audible + " spots, worst " + Math.round(bad.worstDb) + " dB");

        // Genres: each its own curve, none far enough from the rest to force a big move.
        java.util.Set<String> curves = new java.util.HashSet<>();
        double furthest = 0;
        Target home = Target.builtIn(Target.Style.COUNTRY);
        for (Target.Style g : Target.Style.values()) {
            Target t = Target.builtIn(g);
            curves.add(java.util.Arrays.toString(t.midDb));
            for (int i = 0; i < 31; i++) if (Listen.BANDS[i] >= 60 && Listen.BANDS[i] <= 16000) furthest = Math.max(furthest, Math.abs(tilted(t, i) - tilted(home, i)));
        }
        check("Every genre has its own curve", curves.size() == Target.Style.values().length, curves.size() + " curves");
        check("Genre curves stay within reach", furthest < 5, String.format(Locale.US, "furthest from Country %.1f dB", furthest));
        check("Hip-Hop has more lows than Jazz, Electronic more top", band(Target.Style.HIP_HOP, 60) > band(Target.Style.JAZZ, 60) + 3 && band(Target.Style.ELECTRONIC, 12000) > band(Target.Style.JAZZ, 12000) + 3, "");

        // Commercial: firmer moves on every genre, bigger curves only where figures exist.
        Target soft = Target.builtIn(Target.Style.JAZZ), firm = Target.builtIn(Target.Style.JAZZ, true), plain = Target.builtIn(Target.Style.COUNTRY, true);
        check("Commercial closes more of the gap", firm.strength() > soft.strength() && firm.reach() == 3.0, "");
        check("Commercial keeps unsurveyed curves", java.util.Arrays.equals(plain.midDb, home.midDb), "");
        check("Commercial grows surveyed curves", Math.abs((band2(firm, 12000) - band2(Target.builtIn(Target.Style.COUNTRY), 12000)) ) > Math.abs(band2(soft, 12000) - band2(Target.builtIn(Target.Style.COUNTRY), 12000)), "");

        // Clean-up: leaves steady music alone, catches a ring, never touches the level.
        java.util.Random rnd = new java.util.Random(4);
        int r48 = 48000, sec = 20;
        Audio steady = new Audio(sec * r48, r48), ringing = new Audio(sec * r48, r48);
        double hp = 0;
        for (int i = 0; i < sec * r48; i++) {
            double w = rnd.nextGaussian() * 0.04;
            hp = 0.9 * hp + 0.1 * w;
            double sig = hp * 3 + 0.03 * Math.sin(2 * Math.PI * 220 * i / r48) + 0.02 * Math.sin(2 * Math.PI * 660 * i / r48);
            steady.l[i] = steady.r[i] = (float) sig;
            boolean on = (i / (r48 / 2)) % 8 == 3;
            ringing.l[i] = ringing.r[i] = (float) (sig + (on ? 0.25 * Math.sin(2 * Math.PI * 2500 * i / r48) : 0));
        }
        CleanUp.Outcome quietOut = new CleanUp.Outcome(), ringOut = new CleanUp.Outcome();
        Audio cleaned = CleanUp.apply(steady.copy(), quietOut);
        double e = 0, er = 0;
        for (int i = r48; i < sec * r48 - r48; i++) { double d = cleaned.l[i] - steady.l[i]; e += d * d; er += steady.l[i] * steady.l[i]; }
        double nullDb = 10 * Math.log10(Math.max(1e-20, e) / er);
        check("Clean-up leaves steady music alone", nullDb < -30, String.format(Locale.US, "difference %.1f dB", nullDb));
        Audio rung = CleanUp.apply(ringing.copy(), ringOut);
        check("Clean-up catches a ringing resonance", ringOut.resonanceMaxDb > 1.5, String.format(Locale.US, "cut %.1f dB, active %.0f%%", ringOut.resonanceMaxDb, ringOut.resonancePercent));
        check("Clean-up keeps the length", rung.frames() == ringing.frames(), "");

        // Noise: finds what is really there, and leaves the music alone.
        Noise.Finding hushed = Noise.find(noisy(0, 0));
        Audio hissy = noisy(0.0008, 0);
        Noise.Finding hissFound = Noise.find(hissy);
        Audio eased = hissy.copy();
        Noise.apply(eased, hissFound);
        check("Silence between phrases is not called hiss", !hushed.found(), hushed.describe());
        check("Hiss is found", hissFound.hiss, hissFound.describe());
        check("Hiss comes down in the quiet", edgeLevel(eased, 0, 2) < edgeLevel(hissy, 0, 2) - 3, "");
        check("Noise fix leaves loud music alone", Math.abs(edgeLevel(eased, 9, 13) - edgeLevel(hissy, 9, 13)) < 0.2, "");
        Noise.Finding whine = Noise.find(noisy(0, 0.002));
        check("A steady whine is found", whine.tonesHz.size() == 1 && Math.abs(whine.tonesHz.get(0) - 15625) < 15, whine.describe());
        Noise.Finding clearSong = Noise.find(steady);
        check("Steady music is not called noise", !clearSong.found(), clearSong.describe());

        // Sections: hears a clear verse and chorus, and leaves a flat song alone.
        int r441 = 44100, parts = 60;
        Audio shaped = new Audio(parts * r441, r441), flat = new Audio(parts * r441, r441);
        for (int i = 0; i < parts * r441; i++) {
            boolean big = (i / (12 * r441)) % 2 == 1;
            double n = rnd.nextGaussian() * 0.05;
            shaped.l[i] = (float) (n * (big ? 2.2 : 1)); shaped.r[i] = (float) (rnd.nextGaussian() * 0.05 * (big ? 2.2 : 1));
            flat.l[i] = (float) n; flat.r[i] = (float) (rnd.nextGaussian() * 0.05);
        }
        check("Sections are found", Sections.find(shaped).sections >= 3, Sections.find(shaped).sections + " sections");
        check("A flat song has no sections", !Sections.find(flat).active(), "");

        // Density: adds back exactly when asked for nothing, and lowers peaks when asked.
        Audio same = Density.apply(shaped, 0);
        double de = 0, dsum = 0;
        for (int i = 44100; i < shaped.frames() - 44100; i++) { double d = same.l[i] - shaped.l[i]; de += d * d; dsum += shaped.l[i] * shaped.l[i]; }
        check("Density's split adds back exactly", 10 * Math.log10(Math.max(1e-20, de) / dsum) < -80, "");
        check("Density lowers loud moments", Listen.punch(Density.apply(ringing, 2)) <= Listen.punch(ringing) + 0.05, "");

        // Streaming: the round trip really happens and comes back at the same level.
        Streaming.Report sr = Streaming.check(shaped);
        check("Streaming check runs MP3 both ways", sr.lines.size() >= 2 && Math.abs(sr.lines.get(0).lufsChange) < 0.5,
                sr.lines.isEmpty() ? "no result" : String.format(Locale.US, "%s, level change %.2f", sr.verdict(), sr.lines.get(0).lufsChange));

        // Lights: the bars and meter follow the real audio.
        float[] tone1k = new float[4096], lit = new float[Spectrum.BARS];
        for (int i = 0; i < 2048; i++) tone1k[2 * i] = tone1k[2 * i + 1] = (float) (0.3 * Math.sin(2 * Math.PI * 1000 * i / 48000));
        new Spectrum(48000).bars(tone1k, lit);
        int brightest = 0;
        for (int i = 0; i < lit.length; i++) if (lit[i] > lit[brightest]) brightest = i;
        double brightHz = 45 * Math.pow(16000 / 45.0, (brightest + 0.5) / Spectrum.BARS);
        check("EQ bars light up at the right pitch", brightHz > 800 && brightHz < 1250, String.format(Locale.US, "brightest bar %.0f Hz", brightHz));
        float[] fast = Spectrum.fastLevels(shaped, -14);
        double fastMean = 0;
        for (float v : fast) fastMean += v / fast.length;
        check("Meter lights line up with the measured level", Math.abs(fastMean + 14) < 0.5, String.format(Locale.US, "average %.1f", fastMean));

        // Previews: playback-only curves do what their names say.
        check("Phone preview loses deep bass", previewDb(Preview.Mode.PHONE, 60) < previewDb(Preview.Mode.PHONE, 1000) - 20, String.format(Locale.US, "60 Hz %.1f dB, 1 kHz %.1f dB", previewDb(Preview.Mode.PHONE, 60), previewDb(Preview.Mode.PHONE, 1000)));
        check("Phone preview keeps upper bass and top end", previewDb(Preview.Mode.PHONE, 250) > previewDb(Preview.Mode.PHONE, 1000) - 2 && previewDb(Preview.Mode.PHONE, 12000) > previewDb(Preview.Mode.PHONE, 1000) - 2,
                String.format(Locale.US, "120 Hz %.1f, 250 Hz %.1f, 1 kHz %.1f, 3 kHz %.1f, 12 kHz %.1f dB", previewDb(Preview.Mode.PHONE, 120), previewDb(Preview.Mode.PHONE, 250), previewDb(Preview.Mode.PHONE, 1000), previewDb(Preview.Mode.PHONE, 3000), previewDb(Preview.Mode.PHONE, 12000)));
        check("Small speaker preview loses sub bass", previewDb(Preview.Mode.SMALL, 40) < previewDb(Preview.Mode.SMALL, 130) - 10, "");
        double[] hz = {40, 100, 200, 500, 1000, 2000, 3000, 4000, 5000, 6000, 8000, 10000};
        double[] buds2 = {1.0, 1.2, 0.8, 0.2, -0.3, -0.4, -1.6, -3.4, -3.8, -1.5, 0.9, 1.1};
        double[] pro = {1.6, 0.8, 0.8, 0.2, -0.2, -1.0, 0.7, 1.2, 0.3, -0.8, -1.2, 0.2};
        check("Buds2 preview follows the measurements", curveMiss(Preview.Mode.BUDS2, null, hz, buds2) < 1.0, String.format(Locale.US, "worst miss %.2f dB", curveMiss(Preview.Mode.BUDS2, null, hz, buds2)));
        check("Buds2 Pro preview follows the measurements", curveMiss(Preview.Mode.BUDS2_PRO, null, hz, pro) < 1.0, String.format(Locale.US, "worst miss %.2f dB", curveMiss(Preview.Mode.BUDS2_PRO, null, hz, pro)));
        check("Buds2 preview adds nothing on Buds2", curveMiss(Preview.Mode.BUDS2, Preview.Mode.BUDS2, hz, new double[hz.length]) < 0.01 && Math.abs(previewDb(Preview.Mode.BUDS2, Preview.Mode.BUDS2, 1000)) < 0.01, "");
        double[] gap = new double[hz.length];
        for (int i = 0; i < hz.length; i++) gap[i] = relativeDb(Preview.Mode.BUDS2_PRO, null, hz[i]) - relativeDb(Preview.Mode.BUDS2, null, hz[i]);
        check("Buds2 Pro heard on Buds2 adds only the difference", curveMiss(Preview.Mode.BUDS2_PRO, Preview.Mode.BUDS2, hz, gap) < 0.05, String.format(Locale.US, "worst miss %.3f dB", curveMiss(Preview.Mode.BUDS2_PRO, Preview.Mode.BUDS2, hz, gap)));
        check("Buds are recognised by name", Preview.budsNamed("Galaxy Buds2 Pro (A1B2)") == Preview.Mode.BUDS2_PRO && Preview.budsNamed("Galaxy Buds2") == Preview.Mode.BUDS2
                && Preview.budsNamed("Dennis's Buds 2 Pro") == Preview.Mode.BUDS2_PRO && Preview.budsNamed("Galaxy Buds FE") == null && Preview.budsNamed("Galaxy Buds3 Pro") == null && Preview.budsNamed(null) == null, "");
        check("Full preview changes nothing", Math.abs(previewDb(Preview.Mode.FULL, 1000)) < 0.01, "");

        // Taste: nothing until there is enough to go on, then it leans towards your picks.
        java.util.List<String> log = new java.util.ArrayList<>();
        for (int i = 0; i < 7; i++) log.add(Taste.record("COUNTRY", "blind", "Warm"));
        check("No Yours take from too few picks", Taste.yours(log, "COUNTRY") == null, "");
        log.add(Taste.record("COUNTRY", "saved", "Punch"));
        Engineer.Recipe mine = Taste.yours(log, "COUNTRY");
        check("Yours take leans to what you pick", mine != null && Math.abs(mine.valve - 0.49) < 0.02, mine == null ? "none" : String.format(Locale.US, "valve %.2f", mine.valve));

        try { stems(); } catch (Exception problem) { check("Stems", false, String.valueOf(problem)); }
        System.out.println(failed == 0 ? "ALL_ENGINE_CHECKS_PASS" : failed + " CHECK(S) FAILED");
        if (failed != 0) System.exit(1);
    }
    /** A short song with gaps between phrases, plus any hiss and a 15.6 kHz whine. */
    static Audio noisy(double hiss, double whine) {
        int rate = 48000, n = 30 * rate;
        Audio a = new Audio(n, rate);
        java.util.Random q = new java.util.Random(9);
        for (int i = 0; i < n; i++) {
            double t = (double) i / rate, w = whine * Math.sin(2 * Math.PI * 15625 * t);
            double on = t > 3 && t < 27 && ((int) (t / 8)) % 4 != 3 ? 1 : 0;
            a.l[i] = (float) (on * 0.04 * q.nextGaussian() + hiss * q.nextGaussian() + w);
            a.r[i] = (float) (on * 0.04 * q.nextGaussian() + hiss * q.nextGaussian() + w);
        }
        return a;
    }
    static double edgeLevel(Audio a, double from, double to) {
        double e = 0;
        int x = (int) (from * a.rate), y = (int) (to * a.rate);
        for (int i = x; i < y; i++) e += a.l[i] * a.l[i];
        return 10 * Math.log10(e / (y - x) + 1e-20);
    }
    static double band2(Target t, double hz) {
        int k = 0;
        for (int i = 0; i < 31; i++) if (Math.abs(Listen.BANDS[i] - hz) < Math.abs(Listen.BANDS[k] - hz)) k = i;
        return tilted(t, k);
    }
    /** A curve's level at one band, lined up on 1 kHz. */
    static double tilted(Target t, int band) {
        int k = 0;
        for (int i = 0; i < 31; i++) if (Math.abs(Listen.BANDS[i] - 1000) < Math.abs(Listen.BANDS[k] - 1000)) k = i;
        return t.midDb[band] - t.midDb[k];
    }
    static double band(Target.Style s, double hz) {
        int k = 0;
        for (int i = 0; i < 31; i++) if (Math.abs(Listen.BANDS[i] - hz) < Math.abs(Listen.BANDS[k] - hz)) k = i;
        return tilted(Target.builtIn(s), k);
    }
    static double previewDb(Preview.Mode mode, double hz) { return previewDb(mode, null, hz); }
    static double relativeDb(Preview.Mode mode, Preview.Mode worn, double hz) { return previewDb(mode, worn, hz) - previewDb(mode, worn, 1000); }
    /** Worst miss against expected dB, with both sides lined up on their average. */
    static double curveMiss(Preview.Mode mode, Preview.Mode worn, double[] hz, double[] want) {
        double[] diff = new double[hz.length];
        double mean = 0;
        for (int i = 0; i < hz.length; i++) { diff[i] = previewDb(mode, worn, hz[i]) - want[i]; mean += diff[i] / hz.length; }
        double worst = 0;
        for (double d : diff) worst = Math.max(worst, Math.abs(d - mean));
        return worst;
    }
    static double previewDb(Preview.Mode mode, Preview.Mode worn, double hz) {
        int rate = 48000, n = rate;
        float[] io = new float[n * 2];
        for (int i = 0; i < n; i++) io[2 * i] = io[2 * i + 1] = (float) (0.1 * Math.sin(2 * Math.PI * hz * i / rate));
        new Preview(mode, rate, worn).process(io, n);
        double p = 0;
        for (int i = n; i < 2 * n; i++) p += io[i] * io[i];
        return 10 * Math.log10(p / n / 0.005);
    }

    /** Four stems from one run: exact sums, muting, levels, and treatment left where it belongs. */
    static void stems() throws Exception {
        check("Stem names", Stems.partOf("My_Song_Vocals_24bit.wav") == Stems.VOCALS && Stems.partOf("Other_Side_Drums_24bit.wav") == Stems.DRUMS
                && Stems.partOf("Bass_Line_Other_24bit.wav") == Stems.OTHER && Stems.partOf("x_Bass_24bit.wav") == Stems.BASS && Stems.partOf("song.wav") < 0, "");
        check("Stem title", "Other_Side".equals(Stems.titleOf("Other_Side_Drums_24bit.wav")) && "Stems".equals(Stems.titleOf("Vocals.wav")), "");
        java.io.File dir = java.nio.file.Files.createTempDirectory("stems").toFile();
        int rate = 48000, n = 8 * rate;
        Random q = new Random(9);
        Audio[] parts = new Audio[Stems.COUNT];
        java.io.File[] files = new java.io.File[Stems.COUNT];
        for (int p = 0; p < Stems.COUNT; p++) {
            parts[p] = new Audio(n, rate);
            double hz = 110 * (p + 1), amount = 0.1 + 0.03 * p;
            for (int i = 0; i < n; i++) {
                double w = amount * Math.sin(2 * Math.PI * hz * i / rate) * (1 + 0.5 * Math.sin(2 * Math.PI * 2 * i / rate));
                parts[p].l[i] = (float) (w + 0.01 * q.nextGaussian());
                parts[p].r[i] = (float) (w * 0.8 + 0.01 * q.nextGaussian());
            }
            files[p] = new java.io.File(dir, Stems.NAMES[p] + ".wav");
            Wav.write(files[p], parts[p], 24);
        }
        Stems.Set set = Stems.open(files);
        check("Stems line up", set.frames == n && set.rate == rate, set.frames + " frames");
        Audio flat = Stems.mix(set, null, null);
        double worst = 0;
        for (int i = 0; i < n; i++) worst = Math.max(worst, Math.abs(flat.l[i] - parts[0].l[i] - parts[1].l[i] - parts[2].l[i] - parts[3].l[i]));
        check("Flat stems sum exactly", worst < 3e-6, String.format(Locale.US, "off by %.1e", worst));
        double[] up = {6, 0, 0, 0};
        boolean[] none = new boolean[Stems.COUNT], mute = {false, true, false, false};
        Audio moved = Stems.mix(set, up, mute);
        worst = 0;
        double g = Math.pow(10, 6.0 / 20);
        for (int i = 0; i < n; i++) worst = Math.max(worst, Math.abs(moved.l[i] - parts[0].l[i] * g - parts[2].l[i] - parts[3].l[i]));
        check("Level and mute reach the mix", worst < 5e-6, String.format(Locale.US, "off by %.1e", worst));

        // The drums and bass must come through treatment untouched.
        java.util.List<String> notes = new java.util.ArrayList<>();
        boolean[] onlyDrumsBass = {true, false, false, true};
        Audio kept = Stems.render(set, new double[Stems.COUNT], onlyDrumsBass, true, dir, notes, (s, p) -> { });
        worst = 0;
        for (int i = 0; i < n; i++) worst = Math.max(worst, Math.abs(kept.l[i] - parts[1].l[i] - parts[2].l[i]));
        check("Drums and bass stay untreated", worst < 3e-6, String.format(Locale.US, "off by %.1e", worst));
        boolean refused = false;
        try { Stems.render(set, new double[Stems.COUNT], new boolean[]{true, true, true, true}, true, dir, notes, (s, p) -> { }); }
        catch (java.io.IOException e) { refused = true; }
        check("All muted is refused", refused, "");
        check("No temporary stems left", new java.io.File(dir, "stem0.raw").exists() == false && new java.io.File(dir, "stem3.raw").exists() == false, "");

        Audio[] shorter = {new Audio(n - rate, rate)};
        java.io.File wrong = new java.io.File(dir, "short.wav");
        Wav.write(wrong, shorter[0], 24);
        files = files.clone();
        files[2] = wrong;
        refused = false;
        try { Stems.open(files); } catch (java.io.IOException e) { refused = true; }
        check("Stems of different lengths are refused", refused, "");

        // The chain with stems skips only the stages already done on them.
        Engineer.Options o = new Engineer.Options();
        o.fromStems = true;
        notes.clear();
        Audio mixed = Stems.render(Stems.open(new java.io.File[]{new java.io.File(dir, "Vocals.wav"), new java.io.File(dir, "Drums.wav"), new java.io.File(dir, "Bass.wav"), new java.io.File(dir, "Other.wav")}),
                new double[Stems.COUNT], none, true, dir, o.stemNotes, (s, p) -> { });
        Audio[] hand = {mixed};
        Engineer.Session s = Engineer.prepare(hand, Target.builtIn(Target.Style.ROCK), -14, o, (x, y) -> { });
        boolean said = false, doubled = false;
        for (String line : s.notes) { said |= line.startsWith("Vocals stem:"); doubled |= line.startsWith("Harshness:") || line.startsWith("Resonance control"); }
        check("Stem notes reach the session, no second pass", said && !doubled, "");
        s.release();
        for (java.io.File f : dir.listFiles()) f.delete();
        dir.delete();

        // Live meter against the whole-file measure, on a steady tone.
        Audio tone = sine(1000, -20, 3, rate, 0);
        float[] both = new float[tone.frames() * 2];
        for (int i = 0; i < tone.frames(); i++) { both[2 * i] = tone.l[i]; both[2 * i + 1] = tone.r[i]; }
        Loudness.Live live = new Loudness.Live(rate);
        live.add(both, tone.frames());
        double wanted = Loudness.measure(tone).integrated;
        check("Live loudness matches the measure", Math.abs(live.lufs() - wanted) < 0.1, String.format(Locale.US, "%.2f vs %.2f", live.lufs(), wanted));
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
