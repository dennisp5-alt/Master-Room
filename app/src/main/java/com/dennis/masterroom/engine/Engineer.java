package com.dennis.masterroom.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The mastering session. Listens, makes its decisions once, runs the signal path and
 * hands back takes. It does not hold auditions or reject its own work: as in a studio,
 * the final call is made by ear, level-matched, by the person who owns the song.
 */
public final class Engineer {
    public enum Take {
        CLEAN("Clean", "Transparent path: no colour, light compression", 0.5, 0, 0, 0, 30, 1.0, 2.0, 0.6),
        WARM("Warm", "Transformer, tape and valve path, medium compression", 0.8, 0.5, 0.35, 0.5, 30, 1.0, 2.0, 0.6),
        PUNCH("Punch", "Slower attack lets drums through, more of the peaks go to the clipper", 1.0, 0.35, 0.2, 0.2, 45, 0.7, 2.5, 0.7);
        public final String label, about;
        private final double compressDb, valve, iron, tape, attackMs, rides, maxClipDb, clipPortion;
        public Recipe recipe() { return new Recipe(label, about, compressDb, valve, iron, tape, attackMs, rides, maxClipDb, clipPortion); }
        Take(String label, String about, double compressDb, double valve, double iron, double tape, double attackMs, double rides, double maxClipDb, double clipPortion) {
            this.label = label; this.about = about; this.compressDb = compressDb; this.valve = valve; this.iron = iron;
            this.tape = tape; this.attackMs = attackMs; this.rides = rides; this.maxClipDb = maxClipDb; this.clipPortion = clipPortion;
        }
    }

    /** The settings behind one take. */
    public static final class Recipe {
        public final String label, about;
        final double compressDb, valve, iron, tape, attackMs, rides, maxClipDb, clipPortion;
        Recipe(String label, String about, double compressDb, double valve, double iron, double tape, double attackMs, double rides, double maxClipDb, double clipPortion) {
            this.label = label; this.about = about; this.compressDb = compressDb; this.valve = valve; this.iron = iron; this.tape = tape;
            this.attackMs = attackMs; this.rides = rides; this.maxClipDb = maxClipDb; this.clipPortion = clipPortion;
        }
    }

    /** A take made from the settings you keep choosing, in proportion to how often. */
    public static Recipe blend(String label, double clean, double warm, double punch) {
        double t = Math.max(1e-9, clean + warm + punch);
        Recipe a = Take.CLEAN.recipe(), b = Take.WARM.recipe(), c = Take.PUNCH.recipe();
        double x = clean / t, y = warm / t, z = punch / t;
        return new Recipe(label, "Built from the takes you pick most",
                x * a.compressDb + y * b.compressDb + z * c.compressDb, x * a.valve + y * b.valve + z * c.valve,
                x * a.iron + y * b.iron + z * c.iron, x * a.tape + y * b.tape + z * c.tape,
                x * a.attackMs + y * b.attackMs + z * c.attackMs, x * a.rides + y * b.rides + z * c.rides,
                x * a.maxClipDb + y * b.maxClipDb + z * c.maxClipDb, x * a.clipPortion + y * b.clipPortion + z * c.clipPortion);
    }

    public interface Progress { void at(String step, int percent); }

    /** Thrown inside a render when you press Stop. */
    public static final class Stopped extends RuntimeException { }

    private static volatile boolean stop;
    public static void requestStop(boolean on) { stop = on; }
    static void checkStop() { if (stop) throw new Stopped(); }

    /** One line of the session sheet: the song as it left a stage. */
    public static final class Point {
        public final String stage; public final double lufs, lra, truePeak;
        Point(String stage, Audio a) {
            this.stage = stage;
            Loudness.Result r = Loudness.measure(a);
            lufs = r.integrated; lra = r.lra; truePeak = Oversample.truePeakDb(a);
        }
    }

    /** Choices that are not part of the target. */
    public static final class Options {
        /** Resonance control and top-end clean-up. Off leaves those two stages out. */
        public boolean cleanUp = true;
    }

    public static final class Session {
        public Listen before;
        public int sourceFlatPeaks;
        public Target target;
        Eq.Moves moves;
        public final List<String> notes = new ArrayList<>();
        Audio prepared;
        Listen preparedTone;
        /** Frees the working copy once every take is rendered. */
        public void release() { prepared = null; }
    }

    public static final class Result {
        public Recipe take;
        public Audio master;
        public Listen after;
        public double askedLufs, ceilingDb;
        public double clipMaxDb, clippedPercent, limiterMaxDb, limiterMeanDb, over1Percent, over3Percent;
        public double compressMeanDb, compressMaxDb, rideUpDb, rideDownDb;
        public final List<Point> stages = new ArrayList<>();
        public final List<String> notes = new ArrayList<>();
        public double seconds;
        public Distortion.Report distortion;
        public String verdict = "";
        public Streaming.Report streaming;
    }

    /** Spotify asks for true peak below -2 dB on masters louder than -14 LUFS. */
    public static double ceilingFor(double lufs) { return lufs > -13.95 ? -2.0 : -1.0; }

    /** Everything every take shares: listening, tone and problem-solving. */
    public static Session prepare(Audio in, Target target, Progress p) {
        return prepare(in, target, -14, p);
    }

    /** @param askedLufs sets how hard kick and bass peaks are held: only loud masters need it */
    public static Session prepare(Audio in, Target target, double askedLufs, Progress p) {
        return prepare(in, target, askedLufs, new Options(), p);
    }

    public static Session prepare(Audio in, Target target, double askedLufs, Options options, Progress p) {
        if (in.seconds() < 5) throw new IllegalArgumentException("That file is too short to master");
        Session s = new Session();
        s.target = target;
        p.at("Listening to the whole song", 2);
        s.before = Listen.to(in);
        if (s.before.loudness.integrated < -60) throw new IllegalArgumentException("That file is silent or nearly silent");
        Eq.Moves m = Eq.decide(s.before, target, target.strength());
        s.moves = m;
        s.notes.add("Tone target: " + target.name);
        List<String> moves = m.describe();
        s.notes.add(moves.isEmpty() ? "Tone already close to target: no EQ" : "EQ: " + String.join(", ", moves));
        if (s.before.cutoffHz < 19000) s.notes.add(String.format(Locale.US, "Source stops near %.1f kHz, so nothing above that was boosted", s.before.cutoffHz / 1000));

        p.at("Checking for clicks and damage", 4);
        Repair.Outcome fixed = Repair.apply(in);
        if (fixed.clicks + fixed.dropouts > 0 || fixed.startBurst) {
            s.notes.add(String.format(Locale.US, "Repairs: %d click%s, %d dropout%s%s", fixed.clicks, fixed.clicks == 1 ? "" : "s",
                    fixed.dropouts, fixed.dropouts == 1 ? "" : "s", fixed.startBurst ? ", burst removed before the song" : ""));
        } else {
            s.notes.add("Repairs: no clicks or dropouts found");
        }

        int flat = Distortion.flatPeaks(in);
        if (flat > 0) s.notes.add("Source: " + flat + " flat-topped peaks already in the file. Mastering cannot undo these");
        s.sourceFlatPeaks = flat;
        double limit = target.reach() + 1.0;
        Audio clean = fixed.audio;
        if (options.cleanUp) {
            p.at("Cleaning up resonances and swirl", 5);
            CleanUp.Outcome c = new CleanUp.Outcome();
            clean = CleanUp.apply(clean, c);
            s.notes.add(String.format(Locale.US, "Resonance control: up to %.1f dB, active %.0f%% of the song. Top-end clean-up: up to %.1f dB, active %.0f%%",
                    c.resonanceMaxDb, c.resonancePercent, c.swirlMaxDb, c.swirlPercent));
        } else {
            s.notes.add("Resonance control and top-end clean-up: off");
        }
        p.at("Finding the verses and choruses", 7);
        Sections.Plan plan = Sections.find(clean);
        if (plan.active()) s.notes.add(String.format(Locale.US, "Sections: %d found, big ones %.0f%% of the song. Quieter: %s. Bigger: %s. Vocal lift in big sections %+.1f dB",
                plan.sections, plan.bigPercent, plan.quiet.describe(), plan.loud.describe(), plan.vocalDb));
        else s.notes.add("Sections: no clear difference to work with");
        double[] wanted = intended(m);
        p.at("Linear-phase EQ, mid and side", 8);
        final double lowCut = askedLufs <= -13.5 ? 0.8 : askedLufs <= -11.5 ? 1.5 : 2.5;
        Audio out = shape(clean, m, plan, lowCut, s.notes, p);
        Listen check = Listen.tone(out);
        for (int pass = 0; pass < 1; pass++) {
            double[] miss = Eq.missing(wanted, s.before.midDb, check.midDb, s.before.cutoffHz);
            if (Math.abs(miss[0]) < 0.3 && Math.abs(miss[1]) < 0.3 && Math.abs(miss[2]) < 0.3 && Math.abs(miss[3]) < 0.3) break;
            m.add(miss[0], miss[1], miss[2], miss[3], limit);
            p.at("Linear-phase EQ, checking the result", 11);
            out = shape(clean, m, plan, lowCut, null, p);
            check = Listen.tone(out);
        }
        s.notes.add("Applied after checking the result: " + String.join(", ", m.describe()));
        s.prepared = out;
        s.preparedTone = check;
        return s;
    }

    private static double[] intended(Eq.Moves m) {
        double[] wanted = new double[31];
        for (int i = 0; i < 31; i++) wanted[i] = m.midDb(Listen.BANDS[i]);
        return wanted;
    }

    /** EQ and the corrective dynamics, from the repaired audio. */
    private static Audio shape(Audio in, Eq.Moves m, Sections.Plan plan, double lowCut, List<String> notes, Progress p) {
        float[] mid = new float[in.frames()], side = new float[in.frames()];
        for (int i = 0; i < mid.length; i++) { mid[i] = (in.l[i] + in.r[i]) * 0.5f; side[i] = (in.l[i] - in.r[i]) * 0.5f; }
        final double subsonic = 22, mono = m.monoBelowHz;
        FirFilter.Curve midCurve = hz -> Math.pow(10, m.midDb(hz) / 20) * high(hz, subsonic);
        FirFilter.Curve sideCurve = hz -> Math.pow(10, m.sideDb(hz) / 20) * high(hz, subsonic)
                * (mono > 0 ? high(hz, mono) * 0.97 + 0.03 : 1);
        mid = new FirFilter(midCurve, 16384, in.rate).apply(mid);
        side = new FirFilter(sideCurve, 16384, in.rate).apply(side);
        Sections.apply(mid, side, in.rate, plan);

        p.at("Taming harsh and boomy moments", 14);
        DynamicBand.Outcome harsh = DynamicBand.apply(mid, in.rate, new DynamicBand.Settings("Harshness", 2500, 6000, 2.5, 0.6, 3.0, -22, true));
        DynamicBand.Outcome sib = DynamicBand.apply(mid, in.rate, new DynamicBand.Settings("Sibilance", 6000, 11000, 4.0, 0.6, 4.0, -26, true));
        DynamicBand.Outcome low = DynamicBand.apply(mid, in.rate, new DynamicBand.Settings("Low end", 0, 130, 3.0, 0.5, lowCut, -14, false));
        if (notes != null) notes.add(String.format(Locale.US, "Harshness: up to %.1f dB on %.1f%% of the song. Sibilance: up to %.1f dB on %.1f%%. Kick and bass peaks: up to %.1f dB on %.1f%%",
                harsh.maxCutDb, harsh.activePercent, sib.maxCutDb, sib.activePercent, low.maxCutDb, low.activePercent));
        Audio out = new Audio(in.frames(), in.rate);
        for (int i = 0; i < mid.length; i++) { out.l[i] = mid[i] + side[i]; out.r[i] = mid[i] - side[i]; }
        return out;
    }

    private static double high(double hz, double fc) {
        if (hz <= 0) return 0;
        double r = fc / hz;
        return 1 / Math.sqrt(1 + r * r * r * r);
    }

    public static Result master(Session s, Take take, double askedLufs, Progress p) {
        return master(s, take.recipe(), askedLufs, p);
    }

    public static Result master(Session s, final Recipe take, double askedLufs, Progress p) {
        long t0 = System.nanoTime();
        Result r = new Result();
        r.take = take; r.askedLufs = askedLufs;
        r.ceilingDb = ceilingFor(askedLufs);
        Audio a = s.prepared.copy();
        r.stages.add(new Point("EQ and problem solving", a));

        p.at(take.label + ": riding sections", 20);
        Loudness.Result shape = Loudness.measure(a);
        // Only a song that really swings needs evening out; most need their shape kept.
        double even = take.rides * Math.max(0, Math.min(1, (shape.lra - 8) / 4));
        Rides.Outcome rides = Rides.apply(a, shape, even);
        r.rideUpDb = rides.upDb; r.rideDownDb = rides.downDb;

        // The level budget: what the clipper and limiter should not be asked to carry
        // goes to the compressor, as an engineer would lean on the bus compressor harder.
        double needed = askedLufs - Loudness.measure(a).integrated + robustPeak(a) - r.ceilingDb;
        double spare = needed - take.maxClipDb - 3.0;
        double compress = Math.min(2.5, take.compressDb + Math.max(0, spare) * 0.4);
        // Records that hit harder than this will end up get a lighter hand, denser ones a firmer one.
        double aim = s.target.punchDb - (Listen.punch(a) - 1.0);
        if (!Double.isNaN(aim)) {
            compress = Math.max(0, Math.min(2.5, compress * Math.max(0.5, Math.min(1.5, 1 - aim * 0.25))));
            r.notes.add(String.format(Locale.US, "Punch: bus compressor set to %.1f dB to suit your records", compress));
        }
        double cap = 2.0 + compress;
        p.at(take.label + ": bus compressor", 28);
        VariMu.Outcome vm = VariMu.apply(a, compress, cap, take.attackMs, 180, 1400);
        r.compressMeanDb = vm.meanDb; r.compressMaxDb = vm.maxDb;
        r.stages.add(new Point("Rides and bus compressor", a));

        if (take.valve > 0 || take.iron > 0 || take.tape > 0) {
            p.at(take.label + ": analog colour", 40);
            final double op = -18 - Loudness.measure(a).integrated; // 0 VU = -18 dBFS
            final Audio src = a;
            a = Oversample.run4x(src, rate -> new Tone(rate, op, take.valve, take.iron, take.tape));
            r.stages.add(new Point("Analog colour", a));
        }

        // The check below still compares against the audio from before this stage.
        Audio beforeDensity = a;
        double density = densityFor(askedLufs);
        if (density > 0) {
            p.at(take.label + ": adding density", 52);
            a = Density.apply(a, density);
            r.stages.add(new Point("Density", a));
            r.notes.add(String.format(Locale.US, "Density: top %.1f dB softened band by band", density));
        }

        p.at(take.label + ": finding the level", 60);
        Audio pre = a;
        double robust = robustPeak(pre);
        Loudness.Result preLoud = Loudness.measure(pre);
        Level level = search(pre, preLoud, askedLufs, r.ceilingDb, robust, take);
        double gain = level.gain;
        // The compressor, colour and clipper can all take low end away. Check, then put back.
        p.at(take.label + ": checking the low end", 66);
        Listen quick = Listen.tone(render(pre, preLoud, gain, robust, r.ceilingDb, take, false).audio);
        double[] noChange = new double[31];
        double[] miss = Eq.missing(noChange, s.preparedTone.midDb, quick.midDb, s.before.cutoffHz);
        double lowFix = Math.max(-1.2, Math.min(1.2, miss[0])), midFix = Math.max(-1.0, Math.min(1.0, miss[1]));
        if (Math.abs(lowFix) >= 0.3 || Math.abs(midFix) >= 0.3) {
            Eq.Moves fix = new Eq.Moves();
            fix.lowDb = lowFix; fix.lowMidDb = midFix;
            FirFilter.Curve curve = hz -> Math.pow(10, fix.midDb(hz) / 20);
            FirFilter f = new FirFilter(curve, 16384, pre.rate);
            pre = new Audio(f.apply(pre.l), f.apply(pre.r), pre.rate);
            beforeDensity = density > 0 ? new Audio(f.apply(beforeDensity.l), f.apply(beforeDensity.r), pre.rate) : pre;
            r.notes.add(String.format(Locale.US, "Low end put back after the chain: %+.1f dB lows, %+.1f dB low mids", lowFix, midFix));
            preLoud = Loudness.measure(pre);
            robust = robustPeak(pre);
            level = search(pre, preLoud, askedLufs, r.ceilingDb, robust, take);
            gain = level.gain;
        }
        double usedCeiling = r.ceilingDb - 0.05;
        Rendered best = null;
        double lastGain = 0, lastGot = 0;
        for (int attempt = 0; attempt < 4; attempt++) {
            p.at(take.label + ": clipper and limiter", 70 + attempt * 6);
            Rendered now = render(pre, preLoud, gain, robust, usedCeiling, take, true);
            double tp = Oversample.truePeakDb(now.audio);
            best = now;
            double err = askedLufs - now.lufs;
            boolean peakOk = tp <= r.ceilingDb;
            if (peakOk && Math.abs(err) <= 0.08) break;
            if (!peakOk) usedCeiling -= tp - r.ceilingDb + 0.02;
            double slope = attempt == 0 ? 0.6 : Math.max(0.15, Math.min(1, (now.lufs - lastGot) / Math.max(1e-3, gain - lastGain)));
            lastGain = gain; lastGot = now.lufs;
            gain = Math.min(level.most, gain + Math.max(-2, Math.min(2, err / slope)));
        }
        best = cleanUp(best, pre, beforeDensity, preLoud, gain, level.most, robust, usedCeiling, askedLufs, take, r, p);
        Audio master = best.audio;
        Repair.edges(master);
        double over = Oversample.truePeakDb(master) - r.ceilingDb;
        if (over > 0) master = master.gained(-over - 0.005);
        Stack.Stats st = best.stats;
        r.clipMaxDb = st.clipMaxDb; r.clippedPercent = st.clippedPercent();
        r.limiterMaxDb = st.limiterMaxDb; r.limiterMeanDb = st.limiterMeanDb();
        r.over1Percent = st.over1Percent(); r.over3Percent = st.over3Percent();
        r.master = master;
        r.stages.add(new Point("Clipper and limiter", master));
        p.at(take.label + ": checking the master", 96);
        r.after = Listen.to(master);
        r.notes.add(String.format(Locale.US, "Rides: up %.1f, down %.1f dB. Bus compressor: %.1f dB average, %.1f dB most", r.rideUpDb, r.rideDownDb, r.compressMeanDb, r.compressMaxDb));
        r.notes.add(String.format(Locale.US, "Clipper: %.1f dB on %.2f%% of the song. Limiter: %.2f dB average, %.1f dB most, over 1 dB %.1f%%, over 3 dB %.1f%%",
                r.clipMaxDb, r.clippedPercent, r.limiterMeanDb, r.limiterMaxDb, r.over1Percent, r.over3Percent));
        if (r.limiterMeanDb > 1.5 || r.over3Percent > 10)
            r.notes.add("This level is heavy work for this song. Compare it with a quieter version by ear");
        if (Math.abs(r.after.loudness.integrated - askedLufs) > 0.15)
            r.notes.add(String.format(Locale.US, "Asked for %.1f LUFS, stopped at %.1f to stay clean", askedLufs, r.after.loudness.integrated));
        p.at(take.label + ": checking for streaming", 98);
        r.streaming = Streaming.check(master);
        r.notes.add("Streaming check: " + r.streaming.verdict());
        r.seconds = (System.nanoTime() - t0) / 1e9;
        return r;
    }

    /** Writes the two delivery files and returns them: source-rate 24-bit, and 44.1 kHz 16-bit. */
    public static java.io.File[] deliver(Result r, java.io.File folder, String name) throws java.io.IOException {
        java.io.File hi = new java.io.File(folder, name + "_24bit.wav");
        java.io.File cd = new java.io.File(folder, name + "_16bit_44k.wav");
        Wav.write(hi, r.master, 24);
        Audio low = Resample.to(r.master, 44100);
        double over = Oversample.truePeakDb(low) - r.ceilingDb;
        if (over > 0) low = low.gained(-over - 0.005);
        Wav.write(cd, low, 16);
        return new java.io.File[]{hi, cd};
    }

    /** The session sheet, in plain words. */
    public static String sheet(Session s, List<Result> results) {
        StringBuilder b = new StringBuilder();
        Listen in = s.before;
        b.append(String.format(Locale.US, "As it came in: %.1f LUFS, %.1f LU range, %.1f dBTP%n", in.loudness.integrated, in.loudness.lra, in.truePeakDb));
        for (String n : s.notes) b.append(n).append('\n');
        for (Result r : results) {
            Listen a = r.after;
            b.append(String.format(Locale.US, "%n%s: %.1f LUFS, %.1f LU range, %.1f dBTP%n", r.take.label, a.loudness.integrated, a.loudness.lra, a.truePeakDb));
            for (String n : r.notes) b.append(n).append('\n');
        }
        return b.toString();
    }

    private static double robustPeak(Audio a) {
        int block = a.rate / 100, n = a.frames() / block, i = 0;
        double[] peaks = new double[Math.max(1, n)];
        for (int b = 0; b < n; b++) {
            double q = 0;
            for (int k = 0; k < block; k++, i++) q = Math.max(q, Math.max(Math.abs(a.l[i]), Math.abs(a.r[i])));
            peaks[b] = q;
        }
        return Listen.db(Control.percentile(peaks, n, 0.995));
    }

    private static Stack stack(int rate, double gainDb, double robustDb, double ceilingDb, Recipe take, float[] calm) {
        double peak = robustDb + gainDb;
        double excess = Math.max(0, peak - ceilingDb);
        double clipShare = Math.min(take.maxClipDb, take.clipPortion * excess);
        return new Stack(rate, gainDb, Math.max(ceilingDb, peak - clipShare), clipShare, ceilingDb, calm);
    }

    private static final class Rendered { Audio audio; Stack.Stats stats; double lufs; }

    /**
     * The automatic check. If added distortion is likely to be heard, the clipper stands
     * back at those moments and the limiter takes over. If that is not enough, the take
     * comes down a little in level, because a clean master matters more than the number.
     */
    private static Rendered cleanUp(Rendered first, Audio pre, Audio reference, Loudness.Result preLoud, double startGain, double most, double robust, double ceiling,
                                    double asked, Recipe take, Result r, Progress p) {
        p.at(take.label + ": checking for distortion", 88);
        Rendered best = first;
        double gain = startGain;
        Distortion.Report report = Distortion.check(reference.gained(gain), best.audio);
        final int found = report.audible;
        float[] calm = null;
        for (int round = 0; round < 3 && !report.passed(); round++) {
            p.at(take.label + ": easing clipping", 90 + round * 2);
            calm = Distortion.calm(report, calm, pre.seconds());
            double tryGain = round == 0 ? gain : gain - 0.4;
            Rendered now = render(pre, preLoud, tryGain, robust, ceiling, take, true, calm);
            if (round == 0 && asked - now.lufs > 0.08) {
                tryGain = Math.min(most, tryGain + Math.min(0.6, (asked - now.lufs) / 0.6));
                now = render(pre, preLoud, tryGain, robust, ceiling, take, true, calm);
            }
            Distortion.Report again = Distortion.check(reference.gained(tryGain), now.audio);
            // Giving up level is only worth it if it actually removes spots.
            if (again.audible >= report.audible && round > 0) break;
            best = now;
            gain = tryGain;
            report = again;
        }
        r.distortion = report;
        int fixed = Math.max(0, found - report.audible);
        if (found == 0) r.verdict = report.borderline == 0 ? "Passed" : "Passed, " + report.borderline + " borderline";
        else if (report.passed()) r.verdict = "Fixed " + fixed + " spot" + (fixed == 1 ? "" : "s") + ", passed";
        else r.verdict = report.audible + " spot" + (report.audible == 1 ? "" : "s") + " may be audible";
        r.notes.add("Distortion check: " + r.verdict);
        return best;
    }

    private static Rendered render(Audio pre, Loudness.Result preLoud, double gain, double robust, double ceiling, Recipe take, boolean full) {
        return render(pre, preLoud, gain, robust, ceiling, take, full, null);
    }

    private static Rendered render(Audio pre, Loudness.Result preLoud, double gain, double robust, double ceiling, Recipe take, boolean full, float[] calm) {
        Stack[] holder = new Stack[1];
        Oversample.Factory f = rate -> holder[0] = stack(rate, gain, robust, ceiling, take, calm);
        Audio out = full ? Oversample.run4x(pre, f) : Oversample.run1x(pre, f);
        Rendered r = new Rendered();
        r.audio = out; r.stats = holder[0].stats;
        r.lufs = Loudness.measure(out).integrated;
        return r;
    }

    private static double densityFor(double askedLufs) {
        String test = System.getProperty("density");
        if (test != null) return Double.parseDouble(test);
        return askedLufs < -11.5 ? 0 : askedLufs < -10.5 ? 1.5 : 2.0;
    }

    /** Past this average, limiting flattens the song, so a take stops short of its target. */
    private static final double MOST_LIMITING_DB = 1.5;

    private static final class Level { double gain, most = Double.MAX_VALUE; }

    /** Quick 1x renders to find the gain; the real 4x render then only needs a nudge. */
    private static Level search(Audio pre, Loudness.Result preLoud, double asked, double ceiling, double robust, Recipe take) {
        Level out = new Level();
        double gain = asked - preLoud.integrated, last = 0, lastGot = 0, safe = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < 9; i++) {
            Rendered now = render(pre, preLoud, gain, robust, ceiling, take, false);
            if (now.stats.limiterMeanDb() > MOST_LIMITING_DB) {
                out.most = Math.min(out.most, gain);
                gain = safe > Double.NEGATIVE_INFINITY ? (safe + out.most) / 2 : gain - 1.5;
                if (out.most - safe < 0.15) { gain = safe; break; }
                continue;
            }
            safe = Math.max(safe, gain);
            double err = asked - now.lufs;
            if (Math.abs(err) < 0.05) break;
            double slope = i == 0 ? 0.6 : Math.max(0.15, Math.min(1.0, (now.lufs - lastGot) / Math.max(1e-3, gain - last)));
            last = gain; lastGot = now.lufs;
            double next = gain + Math.max(-3, Math.min(3, err / slope));
            if (next >= out.most) next = (gain + out.most) / 2;
            if (out.most - gain < 0.15 && err > 0) break;
            gain = next;
        }
        out.gain = safe > Double.NEGATIVE_INFINITY && gain > safe ? safe : gain;
        return out;
    }
}
