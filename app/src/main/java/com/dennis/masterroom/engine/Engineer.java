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
        final double compressDb, valve, iron, tape, attackMs, rides, maxClipDb, clipPortion;
        Take(String label, String about, double compressDb, double valve, double iron, double tape, double attackMs, double rides, double maxClipDb, double clipPortion) {
            this.label = label; this.about = about; this.compressDb = compressDb; this.valve = valve; this.iron = iron;
            this.tape = tape; this.attackMs = attackMs; this.rides = rides; this.maxClipDb = maxClipDb; this.clipPortion = clipPortion;
        }
    }

    public interface Progress { void at(String step, int percent); }

    /** One line of the session sheet: the song as it left a stage. */
    public static final class Point {
        public final String stage; public final double lufs, lra, truePeak;
        Point(String stage, Audio a) {
            this.stage = stage;
            Loudness.Result r = Loudness.measure(a);
            lufs = r.integrated; lra = r.lra; truePeak = Oversample.truePeakDb(a);
        }
    }

    public static final class Session {
        public Listen before;
        public Target target;
        Eq.Moves moves;
        public final List<String> notes = new ArrayList<>();
        Audio prepared;
        /** Frees the working copy once every take is rendered. */
        public void release() { prepared = null; }
    }

    public static final class Result {
        public Take take;
        public Audio master;
        public Listen after;
        public double askedLufs, ceilingDb;
        public double clipMaxDb, clippedPercent, limiterMaxDb, limiterMeanDb, over1Percent, over3Percent;
        public double compressMeanDb, compressMaxDb, rideUpDb, rideDownDb;
        public final List<Point> stages = new ArrayList<>();
        public final List<String> notes = new ArrayList<>();
        public double seconds;
    }

    /** Spotify asks for true peak below -2 dB on masters louder than -14 LUFS. */
    public static double ceilingFor(double lufs) { return lufs > -13.95 ? -2.0 : -1.0; }

    /** Everything every take shares: listening, tone and problem-solving. */
    public static Session prepare(Audio in, Target target, Progress p) {
        if (in.seconds() < 5) throw new IllegalArgumentException("That file is too short to master");
        Session s = new Session();
        s.target = target;
        p.at("Listening to the whole song", 2);
        s.before = Listen.to(in);
        if (s.before.loudness.integrated < -60) throw new IllegalArgumentException("That file is silent or nearly silent");
        double strength = target.measured ? 0.75 : 0.6;
        Eq.Moves m = Eq.decide(s.before, target, strength);
        s.moves = m;
        s.notes.add("Tone target: " + target.name);
        List<String> moves = m.describe();
        s.notes.add(moves.isEmpty() ? "Tone already close to target: no EQ" : "EQ: " + String.join(", ", moves));
        if (s.before.cutoffHz < 19000) s.notes.add(String.format(Locale.US, "Source stops near %.1f kHz, so nothing above that was boosted", s.before.cutoffHz / 1000));

        p.at("Linear-phase EQ, mid and side", 8);
        float[] mid = new float[in.frames()], side = new float[in.frames()];
        for (int i = 0; i < mid.length; i++) { mid[i] = (in.l[i] + in.r[i]) * 0.5f; side[i] = (in.l[i] - in.r[i]) * 0.5f; }
        final double subsonic = 22, mono = m.monoBelowHz;
        FirFilter.Curve midCurve = hz -> Math.pow(10, m.midDb(hz) / 20) * high(hz, subsonic);
        FirFilter.Curve sideCurve = hz -> Math.pow(10, m.sideDb(hz) / 20) * high(hz, subsonic)
                * (mono > 0 ? high(hz, mono) * 0.97 + 0.03 : 1);
        mid = new FirFilter(midCurve, 16384, in.rate).apply(mid);
        side = new FirFilter(sideCurve, 16384, in.rate).apply(side);

        p.at("Taming harsh and boomy moments", 14);
        DynamicBand.Outcome harsh = DynamicBand.apply(mid, in.rate, new DynamicBand.Settings("Harshness", 2500, 6000, 2.5, 0.6, 3.0, -22, true));
        DynamicBand.Outcome sib = DynamicBand.apply(mid, in.rate, new DynamicBand.Settings("Sibilance", 6000, 11000, 4.0, 0.6, 4.0, -26, true));
        DynamicBand.Outcome low = DynamicBand.apply(mid, in.rate, new DynamicBand.Settings("Low end", 0, 130, 3.0, 0.5, 2.5, -14, false));
        s.notes.add(String.format(Locale.US, "Harshness: up to %.1f dB on %.1f%% of the song. Sibilance: up to %.1f dB on %.1f%%. Kick and bass peaks: up to %.1f dB on %.1f%%",
                harsh.maxCutDb, harsh.activePercent, sib.maxCutDb, sib.activePercent, low.maxCutDb, low.activePercent));
        Audio out = new Audio(in.frames(), in.rate);
        for (int i = 0; i < mid.length; i++) { out.l[i] = mid[i] + side[i]; out.r[i] = mid[i] - side[i]; }
        s.prepared = out;
        return s;
    }

    private static double high(double hz, double fc) {
        if (hz <= 0) return 0;
        double r = fc / hz;
        return 1 / Math.sqrt(1 + r * r * r * r);
    }

    public static Result master(Session s, Take take, double askedLufs, Progress p) {
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

        p.at(take.label + ": finding the level", 60);
        final Audio pre = a;
        double robust = robustPeak(pre);
        final Loudness.Result preLoud = Loudness.measure(pre);
        double gain = search(pre, preLoud, askedLufs, r.ceilingDb, robust, take);
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
            gain += Math.max(-2, Math.min(2, err / slope));
        }
        Audio master = best.audio;
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
            r.notes.add(String.format(Locale.US, "Asked for %.1f LUFS, reached %.1f", askedLufs, r.after.loudness.integrated));
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

    private static Stack stack(int rate, double gainDb, double robustDb, double ceilingDb, Take take) {
        double peak = robustDb + gainDb;
        double excess = Math.max(0, peak - ceilingDb);
        double clipShare = Math.min(take.maxClipDb, take.clipPortion * excess);
        return new Stack(rate, gainDb, Math.max(ceilingDb, peak - clipShare), clipShare, ceilingDb);
    }

    private static final class Rendered { Audio audio; Stack.Stats stats; double lufs; }

    private static Rendered render(Audio pre, Loudness.Result preLoud, double gain, double robust, double ceiling, Take take, boolean full) {
        Stack[] holder = new Stack[1];
        Oversample.Factory f = rate -> holder[0] = stack(rate, gain, robust, ceiling, take);
        Audio out = full ? Oversample.run4x(pre, f) : Oversample.run1x(pre, f);
        Rendered r = new Rendered();
        r.audio = out; r.stats = holder[0].stats;
        r.lufs = Loudness.measure(out).integrated;
        return r;
    }

    /** Quick 1x renders to find the gain; the real 4x render then only needs a nudge. */
    private static double search(Audio pre, Loudness.Result preLoud, double asked, double ceiling, double robust, Take take) {
        double gain = asked - preLoud.integrated, last = 0, lastGot = 0;
        for (int i = 0; i < 6; i++) {
            double got = render(pre, preLoud, gain, robust, ceiling, take, false).lufs;
            double err = asked - got;
            if (Math.abs(err) < 0.05) break;
            double slope = i == 0 ? 0.6 : Math.max(0.15, Math.min(1.0, (got - lastGot) / Math.max(1e-3, gain - last)));
            last = gain; lastGot = got;
            gain += Math.max(-3, Math.min(3, err / slope));
        }
        return gain;
    }
}
