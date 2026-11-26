package com.dennis.masterroom.engine;

import java.util.Arrays;
import java.util.Locale;

/**
 * Vocal Tune: gentle, automatic pitch correction for a voice on its own (a vocal stem, or a dry
 * vocal WAV). It is not for a finished mix: moving the pitch of a mix moves every instrument too.
 *
 * 1. Follows the sung pitch (YIN, de Cheveigne and Kawahara 2002) on a mono copy at about 11 kHz.
 * 2. Finds each note's centre with vibrato averaged out, and the key from the notes sung.
 * 3. Natural and Firm pull a note's centre towards the nearest semitone only when it is more than
 *    the dead band out, so a deliberate blue note or slide is never moved to another note and
 *    vibrato stays. The hard-tune effect snaps every moment to the key's notes, as an effect.
 * 4. Moves the pitch with pitch-synchronous overlap-add, which keeps the voice's formants
 *    (no chipmunk), and applies the same moves to left and right so the stereo image holds still.
 *
 * A vocal that is already in tune comes back untouched (the same samples), and the result is
 * measured again afterwards rather than predicted.
 */
public final class VocalTune {
    public enum Mode {
        OFF("Off", 0, 0, true, 0),
        NATURAL("Natural", 0.6, 70, true, 12),
        FIRM("Firm", 0.85, 35, true, 8),
        EFFECT("Hard tune (effect)", 1.0, 0, false, 0);

        public final String label;
        final double strength, speedMs, deadCents;
        final boolean keepVibrato;
        Mode(String label, double strength, double speedMs, boolean keepVibrato, double deadCents) {
            this.label = label; this.strength = strength; this.speedMs = speedMs; this.keepVibrato = keepVibrato; this.deadCents = deadCents;
        }
        @Override public String toString() { return label; }
        public static Mode parse(String s) {
            for (Mode m : values()) if (m.name().equalsIgnoreCase(s) || m.label.equalsIgnoreCase(s)) return m;
            if ("hard".equalsIgnoreCase(s)) return EFFECT;
            throw new IllegalArgumentException("Vocal tune is one of: off, natural, firm, effect");
        }
    }

    /** What happened, in numbers and words. */
    public static final class Result {
        public Audio audio;
        public Mode mode;
        public boolean changed, looksLikeMix;
        public String reason = "", key = "";
        public double keyFit, sungSeconds, inTuneBefore, inTuneAfter = Double.NaN, typicalOffBefore, typicalOffAfter = Double.NaN;
        public double meanMoveCents, mostMoveCents, otherVoicesShare;
        public int notes, notesMoved;

        public String words() {
            String head = "Vocal Tune (" + mode.label + "): ";
            if (!changed) return head + reason;
            String others = otherVoicesShare >= 0.05 ? String.format(Locale.US, " Left alone where more than one voice sings (%.0f%% of sung time).", 100 * otherVoicesShare) : "";
            return head + String.format(Locale.US, "%d of %d notes nudged, typically %.0f cents (most %.0f). In tune (within 20 cents): %.0f%% of sung time before, %.0f%% after (measured).%s %s",
                    notesMoved, notes, meanMoveCents, mostMoveCents, 100 * inTuneBefore, 100 * inTuneAfter, others, key.isEmpty() ? "" : "Key " + key + ".").trim();
        }
    }

    /** One pass of pitch following. Frame k starts at k * hop samples of the full-rate audio. */
    static final class Track {
        int hop, rate;
        double[] midi;      // NaN where nothing is sung
        double[] clarity;   // YIN aperiodicity at the chosen lag: low = one clear voice
        double[] energy;
        boolean[] gated;    // loud enough to count
        double lowShare;    // energy below 100 Hz against all of it
        int frames() { return midi.length; }
    }

    static final double IN_TUNE_CENTS = 20;
    /** One vibrato cycle at about 5 Hz: averaging over it leaves the note's centre. */
    static final double VIBRATO_SECONDS = 0.2;
    /** Above this aperiodicity more than one voice (or a lot of breath) is sounding: no correction there. */
    static final double ONE_VOICE = 0.12;
    /** A note counts as nudged when its average move is at least this. */
    private static final double NUDGED_CENTS = 5;
    private static final double YIN_THRESHOLD = 0.15, APERIODIC = 0.25;
    private static final double LOW_HZ = 70, HIGH_HZ = 1000;
    private static final String[] NOTE = {"C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B"};
    // Krumhansl and Kessler (1982) key profiles.
    private static final double[] MAJOR = {6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88};
    private static final double[] MINOR = {6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17};

    private VocalTune() {}

    public static Result tune(Audio in, Mode mode) { return tune(in, mode, false); }

    /**
     * @param inPlace write the result into the given audio instead of a copy (the stems path and the phone, where
     *                memory is tight). The result's audio is then the same object, changed.
     */
    public static Result tune(Audio in, Mode mode, boolean inPlace) {
        Result res = new Result();
        res.mode = mode; res.audio = in;
        if (mode == Mode.OFF) { res.reason = "off"; return res; }
        Track t = follow(in);
        res.looksLikeMix = looksLikeMix(t);
        int sung = 0;
        for (double m : t.midi) if (!Double.isNaN(m)) sung++;
        res.sungSeconds = sung * (double) t.hop / t.rate;
        double[] centre = centres(t, mode.keepVibrato ? VIBRATO_SECONDS : 0.0);
        int[] keyFound = key(centre, t.energy);
        res.keyFit = keyFound[2] / 1000.0;
        res.key = keyFound[0] < 0 ? "" : NOTE[keyFound[0]] + (keyFound[1] == 0 ? " major" : " minor");
        double[] before = offsets(centre, null);
        res.inTuneBefore = inTune(before, t.energy);
        res.typicalOffBefore = typical(before, t.energy);
        if (res.looksLikeMix) {
            res.reason = "there is too much besides one voice in it (instruments, bleed or a full mix), so it was left as it came. Vocal Tune works on a vocal on its own.";
            return res;
        }
        if (res.sungSeconds < 2) { res.reason = "no clear singing found, so it was left as it came."; return res; }
        if (mode != Mode.EFFECT && res.typicalOffBefore < 10 && percentileOff(before, t.energy, 0.9) < 25) {
            res.reason = String.format(Locale.US, "already in tune (%.0f%% of sung time within 20 cents, typically %.0f cents out), so it was left untouched.", 100 * res.inTuneBefore, res.typicalOffBefore);
            return res;
        }
        int[] scale = mode == Mode.EFFECT && keyFound[0] >= 0 && res.keyFit >= 0.55 ? scaleOf(keyFound[0], keyFound[1]) : null;
        double[] move = moves(t, centre, mode, scale, res);
        if (res.notesMoved == 0) {
            res.reason = res.otherVoicesShare >= 0.5
                    ? String.format(Locale.US, "more than one voice sings through most of it (harmonies or doubles, %.0f%% of sung time), so it was left as it came.", 100 * res.otherVoicesShare)
                    : "every note was already inside the dead band, so it was left untouched.";
            return res;
        }
        res.audio = shift(in, t, move, inPlace);
        res.changed = true;
        Track after = follow(res.audio);
        double[] afterOff = offsets(centres(after, mode.keepVibrato ? VIBRATO_SECONDS : 0.0), scale);
        res.inTuneAfter = inTune(afterOff, after.energy);
        res.typicalOffAfter = typical(afterOff, after.energy);
        return res;
    }

    // ---------------------------------------------------------------- pitch following

    static Track follow(Audio a) {
        int rate = a.rate, n = a.frames();
        int down = Math.max(1, (int) Math.round(rate / 11025.0));
        int ar = rate / down;
        // Mono, low-passed and decimated for analysis.
        int taps = 31, half = taps / 2;
        double cut = 0.45 / down;
        double[] fir = new double[taps];
        double sum = 0;
        for (int i = 0; i < taps; i++) {
            int k = i - half;
            double sinc = k == 0 ? 2 * cut : Math.sin(2 * Math.PI * cut * k) / (Math.PI * k);
            fir[i] = sinc * (0.54 - 0.46 * Math.cos(2 * Math.PI * i / (taps - 1)));
            sum += fir[i];
        }
        for (int i = 0; i < taps; i++) fir[i] /= sum;
        int m = n / down;
        float[] x = new float[m];
        double low = 0, lowE = 0, allE = 0, lowA = 1 - Math.exp(-2 * Math.PI * 100.0 / rate);
        for (int j = 0; j < m; j++) {
            int c = j * down;
            double s = 0;
            for (int i = 0; i < taps; i++) {
                int p = c + i - half;
                if (p >= 0 && p < n) s += fir[i] * 0.5 * (a.l[p] + a.r[p]);
            }
            x[j] = (float) s;
        }
        for (int i = 0; i < n; i++) {
            double v = 0.5 * (a.l[i] + a.r[i]);
            low += lowA * (v - low);
            lowE += low * low; allE += v * v;
        }
        int hop = Math.max(1, (int) Math.round(0.005 * ar));
        int win = (int) Math.round(0.04 * ar);
        int tauMin = Math.max(2, (int) Math.floor(ar / HIGH_HZ)), tauMax = (int) Math.ceil(ar / LOW_HZ);
        int frames = Math.max(0, (m - win - tauMax) / hop + 1);
        Track t = new Track();
        t.hop = hop * down; t.rate = rate;
        t.midi = new double[frames];
        t.clarity = new double[frames];
        t.energy = new double[frames];
        t.gated = new boolean[frames];
        t.lowShare = allE > 0 ? lowE / allE : 0;
        double[] d = new double[tauMax + 1];
        for (int f = 0; f < frames; f++) {
            if ((f & 1023) == 0) Engineer.checkStop();
            int s0 = f * hop;
            double e = 0;
            for (int j = 0; j < win; j++) e += x[s0 + j] * x[s0 + j];
            t.energy[f] = e / win;
        }
        // Loud enough: within 40 dB of the loud singing (95th percentile).
        double[] sorted = t.energy.clone();
        Arrays.sort(sorted);
        double loud = sorted.length == 0 ? 0 : sorted[(int) (0.95 * (sorted.length - 1))];
        double gate = Math.max(loud * 1e-4, 1e-10);
        for (int f = 0; f < frames; f++) {
            t.midi[f] = Double.NaN;
            if (t.energy[f] < gate) continue;
            t.gated[f] = true;
            if ((f & 1023) == 0) Engineer.checkStop();
            int s0 = f * hop;
            for (int tau = 1; tau <= tauMax; tau++) {
                double acc = 0;
                for (int j = 0; j < win; j++) { double dd = x[s0 + j] - x[s0 + j + tau]; acc += dd * dd; }
                d[tau] = acc;
            }
            // Cumulative mean normalised difference.
            double run = 0;
            d[0] = 1;
            for (int tau = 1; tau <= tauMax; tau++) { run += d[tau]; d[tau] = run > 0 ? d[tau] * tau / run : 1; }
            int best = -1;
            for (int tau = tauMin; tau < tauMax; tau++) {
                if (d[tau] < YIN_THRESHOLD) {
                    while (tau + 1 < tauMax && d[tau + 1] < d[tau]) tau++;
                    best = tau; break;
                }
            }
            if (best < 0) {
                double lowest = Double.MAX_VALUE;
                for (int tau = tauMin; tau < tauMax; tau++) if (d[tau] < lowest) { lowest = d[tau]; best = tau; }
                if (lowest > APERIODIC) continue;
            }
            // Octave check: a strong second harmonic can dip first at half the period. If twice the
            // lag dips clearly lower, that is the real period.
            for (int twice = best * 2; twice + 1 < tauMax; ) {
                int at = twice;
                for (int k = Math.max(tauMin, twice - 2); k <= Math.min(tauMax - 1, twice + 2); k++) if (d[k] < d[at]) at = k;
                if (d[at] < d[best] - 0.03) { best = at; twice = best * 2; } else break;
            }
            double shiftTau = 0;
            if (best > 1 && best < tauMax) {
                double a0 = d[best - 1], b0 = d[best], c0 = d[best + 1], den = a0 - 2 * b0 + c0;
                if (Math.abs(den) > 1e-12) shiftTau = 0.5 * (a0 - c0) / den;
            }
            double f0 = ar / (best + Math.max(-0.5, Math.min(0.5, shiftTau)));
            t.midi[f] = 69 + 12 * Math.log(f0 / 440.0) / Math.log(2);
            t.clarity[f] = d[best];
        }
        clean(t);
        return t;
    }

    /** Removes single-frame octave slips and voiced runs too short to be a note. */
    private static void clean(Track t) {
        double[] m = t.midi;
        int n = m.length;
        double[] copy = m.clone();
        double[] w = new double[7];
        for (int i = 0; i < n; i++) {
            if (Double.isNaN(copy[i])) continue;
            int k = 0;
            for (int j = Math.max(0, i - 3); j <= Math.min(n - 1, i + 3); j++) if (!Double.isNaN(copy[j])) w[k++] = copy[j];
            Arrays.sort(w, 0, k);
            double med = w[k / 2];
            if (Math.abs(copy[i] - med) > 6) m[i] = Double.NaN;
        }
        int minRun = Math.max(1, (int) Math.round(0.04 * t.rate / t.hop));
        for (int i = 0; i < n; ) {
            if (Double.isNaN(m[i])) { i++; continue; }
            int j = i;
            while (j < n && !Double.isNaN(m[j])) j++;
            if (j - i < minRun) for (int k = i; k < j; k++) m[k] = Double.NaN;
            i = j;
        }
    }

    static boolean looksLikeMix(Track t) {
        int gated = 0, sung = 0;
        for (int f = 0; f < t.frames(); f++) if (t.gated[f]) { gated++; if (!Double.isNaN(t.midi[f])) sung++; }
        if (gated == 0) return false;
        double sungShare = sung / (double) gated;
        return t.lowShare > 0.12 || sungShare < 0.3;
    }

    // ---------------------------------------------------------------- notes and moves

    /** Pitch with vibrato averaged out over the given window (0 keeps every moment). */
    static double[] centres(Track t, double seconds) {
        int n = t.frames();
        double[] c = new double[n];
        Arrays.fill(c, Double.NaN);
        int half = (int) Math.round(seconds * t.rate / t.hop / 2);
        for (int i = 0; i < n; ) {
            if (Double.isNaN(t.midi[i])) { i++; continue; }
            int j = i;
            while (j < n && !Double.isNaN(t.midi[j])) j++;
            double[] pre = new double[j - i + 1];
            for (int k = i; k < j; k++) pre[k - i + 1] = pre[k - i] + t.midi[k];
            for (int k = i; k < j; k++) {
                int a = Math.max(i, k - half), b = Math.min(j - 1, k + half);
                c[k] = (pre[b - i + 1] - pre[a - i]) / (b - a + 1);
            }
            i = j;
        }
        return c;
    }

    /** Distance from the nearest allowed note, in semitones (NaN where nothing is sung). */
    static double[] offsets(double[] centre, int[] scale) {
        double[] off = new double[centre.length];
        for (int i = 0; i < centre.length; i++) off[i] = Double.isNaN(centre[i]) ? Double.NaN : nearest(centre[i], scale) - centre[i];
        return off;
    }

    private static double nearest(double midi, int[] scale) {
        if (scale == null) return Math.rint(midi);
        double best = Double.NaN, dist = Double.MAX_VALUE;
        for (int o = (int) Math.floor(midi) - 2; o <= (int) Math.ceil(midi) + 2; o++) {
            if (!inScale(o, scale)) continue;
            if (Math.abs(o - midi) < dist) { dist = Math.abs(o - midi); best = o; }
        }
        return best;
    }

    private static boolean inScale(int midi, int[] scale) {
        int pc = Math.floorMod(midi, 12);
        for (int s : scale) if (s == pc) return true;
        return false;
    }

    static int[] scaleOf(int tonic, int minor) {
        int[] steps = minor == 0 ? new int[]{0, 2, 4, 5, 7, 9, 11} : new int[]{0, 2, 3, 5, 7, 8, 10};
        int[] out = new int[7];
        for (int i = 0; i < 7; i++) out[i] = (tonic + steps[i]) % 12;
        return out;
    }

    /** Tonic, 0 major / 1 minor, and how well it fits (correlation x 1000); tonic -1 if unclear. */
    static int[] key(double[] centre, double[] energy) {
        double[] hist = new double[12];
        double total = 0;
        for (int i = 0; i < centre.length; i++) {
            if (Double.isNaN(centre[i])) continue;
            double w = Math.sqrt(energy[i]);
            hist[Math.floorMod((int) Math.rint(centre[i]), 12)] += w;
            total += w;
        }
        if (total <= 0) return new int[]{-1, 0, 0};
        double best = -2; int bt = -1, bm = 0;
        for (int mode = 0; mode < 2; mode++) {
            double[] prof = mode == 0 ? MAJOR : MINOR;
            for (int tonic = 0; tonic < 12; tonic++) {
                double[] rot = new double[12];
                for (int i = 0; i < 12; i++) rot[i] = prof[Math.floorMod(i - tonic, 12)];
                double r = corr(hist, rot);
                if (r > best) { best = r; bt = tonic; bm = mode; }
            }
        }
        return new int[]{bt, bm, (int) Math.round(best * 1000)};
    }

    private static double corr(double[] a, double[] b) {
        double ma = 0, mb = 0;
        for (int i = 0; i < 12; i++) { ma += a[i]; mb += b[i]; }
        ma /= 12; mb /= 12;
        double s = 0, sa = 0, sb = 0;
        for (int i = 0; i < 12; i++) { s += (a[i] - ma) * (b[i] - mb); sa += (a[i] - ma) * (a[i] - ma); sb += (b[i] - mb) * (b[i] - mb); }
        return sa > 0 && sb > 0 ? s / Math.sqrt(sa * sb) : 0;
    }

    static double inTune(double[] off, double[] energy) {
        double in = 0, all = 0;
        for (int i = 0; i < off.length; i++) {
            if (Double.isNaN(off[i])) continue;
            double w = Math.sqrt(energy[i]);
            all += w;
            if (Math.abs(off[i]) * 100 <= IN_TUNE_CENTS) in += w;
        }
        return all > 0 ? in / all : 1;
    }

    static double typical(double[] off, double[] energy) { return percentileOff(off, energy, 0.5); }

    static double percentileOff(double[] off, double[] energy, double q) {
        int k = 0;
        for (double o : off) if (!Double.isNaN(o)) k++;
        if (k == 0) return 0;
        double[] v = new double[k];
        k = 0;
        for (double o : off) if (!Double.isNaN(o)) v[k++] = Math.abs(o) * 100;
        Arrays.sort(v);
        return v[(int) Math.min(v.length - 1, Math.round(q * (v.length - 1)))];
    }

    /** The pitch move for every frame, in semitones. Zero where nothing is sung. */
    static double[] moves(Track t, double[] centre, Mode mode, int[] scale, Result res) {
        int n = t.frames();
        double[] want = new double[n];
        double note = Double.NaN;
        int notes = 0, moved = 0, movedFrames = 0, crowded = 0, sung = 0;
        double noteMove = 0, moveSum = 0, most = 0;
        int noteFrames = 0;
        for (int i = 0; i <= n; i++) {
            double c = i == n ? Double.NaN : mode.keepVibrato ? centre[i] : t.midi[i];
            double target = Double.isNaN(c) ? Double.NaN : nearest(c, scale);
            // Hold the note unless the singer has clearly moved on (no flicking between two notes on a slide).
            if (!Double.isNaN(target) && !Double.isNaN(note) && note != target && Math.abs(c - note) < 0.65) target = note;
            boolean ends = !Double.isNaN(note) && (Double.isNaN(target) || target != note);
            if (ends) {
                notes++;
                double avg = noteFrames == 0 ? 0 : noteMove / noteFrames * 100;
                if (avg >= NUDGED_CENTS) { moved++; moveSum += avg; most = Math.max(most, avg); movedFrames++; }
                noteMove = 0; noteFrames = 0;
            }
            note = target;
            if (Double.isNaN(target)) continue;
            double off = target - c, cents = Math.abs(off) * 100;
            double k = mode.deadCents <= 0 ? 1 : Math.max(0, Math.min(1, (cents - mode.deadCents) / mode.deadCents));
            double m = Math.max(-1, Math.min(1, off * mode.strength * k));
            if (t.clarity[i] > ONE_VOICE) { m = 0; crowded++; }
            sung++;
            want[i] = m;
            noteMove += Math.abs(m); noteFrames++;
        }
        // Retune speed: glide into the move, and back out of it at the end of a note.
        double[] out = new double[n];
        double a = mode.speedMs <= 0 ? 1 : 1 - Math.exp(-(t.hop / (double) t.rate) / (mode.speedMs / 1000.0));
        double y = 0;
        for (int i = 0; i < n; i++) {
            if (Double.isNaN(t.midi[i])) { y = 0; out[i] = 0; continue; }
            y += a * (want[i] - y);
            out[i] = y;
        }
        res.notes = notes; res.notesMoved = moved;
        res.otherVoicesShare = sung == 0 ? 0 : crowded / (double) sung;
        res.meanMoveCents = movedFrames == 0 ? 0 : moveSum / movedFrames;
        res.mostMoveCents = most;
        return out;
    }

    // ---------------------------------------------------------------- pitch shifting (TD-PSOLA)

    static Audio shift(Audio in, Track t, double[] move, boolean inPlace) {
        float[] l = inPlace ? in.l : in.l.clone(), r = inPlace ? in.r : in.r.clone();
        int fades = Math.max(1, (int) Math.round(0.01 * in.rate));
        int frames = t.frames();
        for (int f = 0; f < frames; ) {
            if (Double.isNaN(t.midi[f])) { f++; continue; }
            int g = f;
            boolean moves = false;
            while (g < frames && !Double.isNaN(t.midi[g])) { if (move[g] != 0) moves = true; g++; }
            if (moves) {
                Engineer.checkStop();
                run(in.l, in.r, l, r, t, move, f, g, fades);
            }
            f = g;
        }
        return inPlace ? in : new Audio(l, r, in.rate);
    }

    private static double period(Track t, int sample) {
        int f = Math.max(0, Math.min(t.frames() - 1, sample / t.hop));
        double m = t.midi[f];
        if (Double.isNaN(m)) {
            for (int d = 1; d < 50; d++) {
                if (f - d >= 0 && !Double.isNaN(t.midi[f - d])) { m = t.midi[f - d]; break; }
                if (f + d < t.frames() && !Double.isNaN(t.midi[f + d])) { m = t.midi[f + d]; break; }
            }
        }
        double hz = 440 * Math.pow(2, (m - 69) / 12);
        return t.rate / hz;
    }

    private static double ratioAt(Track t, double[] move, int sample) {
        int f = Math.max(0, Math.min(t.frames() - 1, sample / t.hop));
        return Math.pow(2, move[f] / 12);
    }

    /**
     * One sung phrase, frames [f, g): analysis marks on the pitch peaks, then grains re-spaced. Grains are read from a
     * copy of just this phrase (plus a period either side), so the result can be written straight back.
     */
    private static void run(float[] inL, float[] inR, float[] l, float[] r, Track t, double[] move, int f, int g, int fades) {
        int n = inL.length;
        int start = f * t.hop, end = Math.min(n, g * t.hop + t.hop);
        if (end - start < 4 * fades) return;
        int pad = (int) Math.ceil(t.rate / LOW_HZ) + 2;
        int from = Math.max(0, start - pad), to = Math.min(n, end + pad);
        float[] srcL = Arrays.copyOfRange(inL, from, to), srcR = Arrays.copyOfRange(inR, from, to);
        // A gently low-passed mono copy marks the pitch periods (peaks are steadier below 900 Hz).
        float[] mark = new float[to - from];
        double lp = 0, a = 1 - Math.exp(-2 * Math.PI * 900.0 / t.rate);
        for (int i = 0; i < mark.length; i++) { lp += a * (0.5 * (srcL[i] + srcR[i]) - lp); mark[i] = (float) lp; }
        // Analysis marks, in phrase-copy coordinates.
        int[] marks = new int[Math.max(16, (int) ((end - start) / (t.rate / HIGH_HZ)) + 4)];
        int count = 0;
        double p0 = period(t, start);
        marks[count++] = peak(mark, start - from, (int) Math.min(mark.length - 1, start - from + p0));
        while (true) {
            int last = marks[count - 1];
            double p = period(t, last + from);
            int lo = (int) (last + p - p / 4), hi = (int) (last + p + p / 4);
            if (hi + from >= end || hi >= mark.length) break;
            int next = peak(mark, Math.max(last + 1, lo), hi);
            if (count == marks.length) marks = Arrays.copyOf(marks, count * 2);
            marks[count++] = next;
        }
        if (count < 3) return;
        int len = end - start;
        float[] outL = new float[len], outR = new float[len];
        double[] weight = new double[len];
        double u = marks[0] + from;
        int k = 0;
        while (u < end) {
            int ui = (int) Math.round(u);
            while (k + 1 < count && Math.abs(marks[k + 1] + from - ui) <= Math.abs(marks[k] + from - ui)) k++;
            int c = marks[k];
            int half = (int) Math.round(period(t, c + from));
            for (int i = -half; i <= half; i++) {
                int src = c + i, dst = ui + i - start;
                if (src < 0 || src >= srcL.length || dst < 0 || dst >= len) continue;
                double w = 0.5 + 0.5 * Math.cos(Math.PI * i / (half + 1));
                outL[dst] += (float) (w * srcL[src]);
                outR[dst] += (float) (w * srcR[src]);
                weight[dst] += w;
            }
            u += period(t, ui) / ratioAt(t, move, ui);
        }
        // Blend back into the original at the phrase edges; normalise the overlap.
        for (int i = 0; i < len; i++) {
            double w = weight[i];
            if (w < 0.3) continue;
            double edge = Math.min(1, Math.min(i, len - 1 - i) / (double) fades);
            int at = start + i, s0 = at - from;
            l[at] = (float) (srcL[s0] + edge * (outL[i] / w - srcL[s0]));
            r[at] = (float) (srcR[s0] + edge * (outR[i] / w - srcR[s0]));
        }
    }

    private static int peak(float[] x, int from, int to) {
        int best = from;
        float v = -Float.MAX_VALUE;
        for (int i = from; i <= to && i < x.length; i++) if (x[i] > v) { v = x[i]; best = i; }
        return best;
    }
}
