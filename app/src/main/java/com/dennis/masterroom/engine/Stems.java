package com.dennis.masterroom.engine;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Four separated stems instead of one finished song. Each stem gets only the work that
 * suits it, then they are balanced and summed, and the sum goes down the normal chain.
 *
 * Fixing sibilance on the vocal alone leaves the cymbals open, and top-end clean-up never
 * touches a hit. Only one stem is ever in memory while it is being treated.
 */
public final class Stems {
    /** The first four are always there. Six-stem runs add the last two. */
    public static final String[] NAMES = {"Vocals", "Drums", "Bass", "Other", "Guitar", "Piano"};
    public static final int CORE = 4, COUNT = 6, VOCALS = 0, DRUMS = 1, BASS = 2, OTHER = 3, GUITAR = 4, PIANO = 5;
    public static final double REACH_DB = 6;
    private static final int BLOCK = 1 << 15;
    /** Stems from one run are the same length. This allows for a rounding difference. */
    private static final double LENGTH_SLACK_SECONDS = 0.1;
    private static final String[][] WORDS = {
            {"vocal", "vox", "voice"}, {"drum"}, {"bass"}, {"other", "instrument", "accomp", "melody"}, {"guitar"}, {"piano", "keys"}};

    /*
     * Where each part usually sits against the whole mix, in LU: mean and spread measured on the
     * pop and rock songs of the DSD100 multitracks (Ward et al, 2017). Inside one spread of the
     * mean a stem is left alone. Metal vocals sat in the bottom quarter there, so they aim lower.
     */
    private static final double[] MEAN_LU = {-3.6, -6.5, -8.1, -5.0}, SPREAD_LU = {1.3, 2.5, 3.0, 1.9};
    private static final double METAL_VOCAL_LU = -4.9;
    /** Held well inside the 6 dB where listeners start to hear separated stems (Wierstorf et al, 2017). */
    private static final double[] MOST_DB = {2.5, 2.0, 2.0, 2.0};
    private static final double RIDE_MOST_DB = 2, RIDE_SECONDS = 3, RIDE_EASE_SECONDS = 1;
    /** Quieter than this against the mix, there is no lead vocal to balance around. */
    private static final double NO_VOCAL_LU = -16;
    private static final int STEPS_PER_SECOND = 10;

    private Stems() {}

    /** Four checked stem files that belong together. */
    public static final class Set {
        public final File[] files;
        public final Wav.Header[] headers;
        public final int frames, rate;
        Set(File[] files, Wav.Header[] headers, int frames, int rate) {
            this.files = files; this.headers = headers; this.frames = frames; this.rate = rate;
        }
        public double seconds() { return frames / (double) rate; }
        public boolean has(int part) { return files[part] != null; }
    }

    /** How loud each stem is over the song, worked out without loading them. */
    public static final class Reading {
        final float[][] power = new float[COUNT][];
        double wholeLufs;
    }

    /** What the app decided: a level for each stem, and rides on the vocal. */
    public static final class Balance {
        public final double[] levelDb = new double[COUNT];
        /** Extra vocal level in dB, a value for every tenth of a second. */
        public float[] rideDb = new float[0];
        public double mostRideDb;
        public int ridePercent;
        public boolean noVocal;
        public String words() {
            if (noVocal) return "No lead vocal found, so the balance stays as it came";
            String levels = balance(levelDb, null);
            String rides = mostRideDb < 0.25 ? "" : String.format(Locale.US, "vocal lifted up to %.1f dB where the band covers it", mostRideDb);
            if (levels.isEmpty() && rides.isEmpty()) return "Already well balanced, so nothing was moved";
            return levels + (levels.isEmpty() || rides.isEmpty() ? "" : ", ") + rides;
        }
    }

    /** Which stem a file name means, going by the last word found, so a song title cannot mislead it. */
    public static int partOf(String name) {
        String low = name.toLowerCase(Locale.US);
        int best = -1, at = -1;
        for (int part = 0; part < COUNT; part++) {
            for (String w : WORDS[part]) {
                int p = low.lastIndexOf(w);
                if (p > at) { at = p; best = part; }
            }
        }
        return best;
    }

    /** The song's name, from a stem's file name. */
    public static String titleOf(String name) {
        String low = name.toLowerCase(Locale.US);
        int at = -1;
        for (String[] words : WORDS) for (String w : words) at = Math.max(at, low.lastIndexOf(w));
        String left = at > 0 ? name.substring(0, at) : "";
        left = left.replaceAll("[\\s_.\\-]+$", "");
        return left.isEmpty() ? "Stems" : left;
    }

    public static Set open(File[] files) throws IOException {
        File[] all = java.util.Arrays.copyOf(files, COUNT);
        Wav.Header[] headers = new Wav.Header[COUNT];
        int rate = 0;
        long shortest = Long.MAX_VALUE, longest = 0;
        for (int i = 0; i < COUNT; i++) {
            if (all[i] == null) {
                if (i < CORE) throw new IOException("The " + NAMES[i] + " stem is missing");
                continue;
            }
            Wav.Header h = Wav.header(all[i]);
            if (rate != 0 && h.rate != rate) throw new IOException("These stems have different sample rates");
            rate = h.rate;
            headers[i] = h;
            shortest = Math.min(shortest, h.frames);
            longest = Math.max(longest, h.frames);
        }
        if (shortest < rate * 5L) throw new IOException("Those stems are too short to master");
        if (longest - shortest > LENGTH_SLACK_SECONDS * rate) throw new IOException("These stems are different lengths, so they are not from one run");
        return new Set(all, headers, (int) shortest, rate);
    }

    /** Sums the stems as they came, at the given levels. Null levels and mutes mean flat. */
    public static Audio mix(Set set, double[] levelDb, boolean[] muted) throws IOException {
        Audio out = new Audio(set.frames, set.rate);
        for (int part = 0; part < COUNT; part++) {
            if (!set.has(part) || (muted != null && muted[part])) continue;
            addFile(set, part, gain(levelDb, part), out);
        }
        return out;
    }

    /**
     * Treats the vocals and the other instruments, then balances all four. The notes say what
     * happened. Clean-up off leaves resonances and swirl alone but still tames harshness and esses.
     */
    public static Audio render(Set set, double[] levelDb, float[] rideDb, boolean[] muted, boolean clean, File work, List<String> notes, Engineer.Progress p) throws IOException {
        boolean any = false;
        for (int part = 0; part < COUNT; part++) any |= set.has(part) && !muted[part];
        if (!any) throw new IOException("Every stem is muted");
        File[] done = new File[COUNT];
        int[] sizes = new int[COUNT];
        List<String> said = new ArrayList<>();
        try {
            for (int part : new int[]{VOCALS, OTHER, GUITAR, PIANO}) {
                if (!set.has(part) || muted[part]) continue;
                Engineer.checkStop();
                p.at("Cleaning the " + NAMES[part].toLowerCase(Locale.US) + " stem", part == VOCALS ? 1 : 3);
                Audio a = Wav.read(set.files[part]);
                a = treat(a, part, clean, said);
                sizes[part] = a.frames();
                done[part] = new File(work, "stem" + part + ".raw");
                a.store(done[part]);
            }
            Engineer.checkStop();
            p.at("Balancing the stems", 4);
            Audio out = new Audio(set.frames, set.rate);
            for (int part = 0; part < COUNT; part++) {
                if (!set.has(part) || muted[part]) continue;
                double g = gain(levelDb, part);
                if (done[part] == null) addFile(set, part, g, out);
                else {
                    Audio a = Audio.load(done[part], sizes[part], set.rate);
                    if (part == VOCALS) ride(a, rideDb);
                    add(a.l, a.r, 0, set.frames, g, out);
                }
            }
            if (!muted[DRUMS] || !muted[BASS]) said.add("Drums and bass stems: no treatment, so hits stay as they were");
            double most = 0;
            if (rideDb != null) for (float x : rideDb) most = Math.max(most, x);
            if (most >= 0.25 && !muted[VOCALS]) said.add(String.format(Locale.US, "Vocal rides: up to %.1f dB", most));
            String balance = balance(levelDb, muted);
            if (!balance.isEmpty()) said.add("Stem levels: " + balance);
            notes.addAll(said);
            return out;
        } finally {
            for (File f : done) if (f != null) f.delete();
        }
    }

    /** Plain words for the balance, or nothing if it is flat. */
    public static String balance(double[] levelDb, boolean[] muted) {
        StringBuilder b = new StringBuilder();
        for (int part = 0; part < COUNT; part++) {
            String one = muted != null && muted[part] ? NAMES[part] + " muted"
                    : levelDb != null && Math.abs(levelDb[part]) > 0.01 ? String.format(Locale.US, "%s %+.1f dB", NAMES[part], levelDb[part]) : "";
            if (!one.isEmpty()) b.append(b.length() == 0 ? "" : ", ").append(one);
        }
        return b.toString();
    }

    /** Reads all the stems once. It streams, so it costs almost no memory. */
    public static Reading read(Set set) throws IOException {
        Reading out = new Reading();
        RandomAccessFile[] in = new RandomAccessFile[COUNT];
        Loudness.Steps[] steps = new Loudness.Steps[COUNT];
        Loudness.Steps whole = new Loudness.Steps(set.rate, set.frames);
        byte[][] raw = new byte[COUNT][];
        float[] l = new float[BLOCK], r = new float[BLOCK], allL = new float[BLOCK], allR = new float[BLOCK];
        try {
            for (int part = 0; part < COUNT; part++) {
                if (!set.has(part)) continue;
                in[part] = new RandomAccessFile(set.files[part], "r");
                in[part].seek(set.headers[part].dataStart);
                steps[part] = new Loudness.Steps(set.rate, set.frames);
                raw[part] = new byte[BLOCK * set.headers[part].frameBytes()];
            }
            for (int at = 0; at < set.frames; ) {
                Engineer.checkStop();
                int n = Math.min(BLOCK, set.frames - at);
                java.util.Arrays.fill(allL, 0, n, 0);
                java.util.Arrays.fill(allR, 0, n, 0);
                for (int part = 0; part < COUNT; part++) {
                    if (in[part] == null) continue;
                    in[part].readFully(raw[part], 0, n * set.headers[part].frameBytes());
                    Wav.decode(set.headers[part], raw[part], n, l, r);
                    steps[part].add(l, r, n);
                    for (int i = 0; i < n; i++) { allL[i] += l[i]; allR[i] += r[i]; }
                }
                whole.add(allL, allR, n);
                at += n;
            }
        } finally {
            for (RandomAccessFile f : in) if (f != null) f.close();
        }
        for (int part = 0; part < COUNT; part++) if (steps[part] != null) out.power[part] = steps[part].power;
        out.wholeLufs = Loudness.gatedLufs(whole.power);
        return out;
    }

    /**
     * Decides the balance. A part outside the usual range is brought back to the edge of it, and
     * then the vocal is lifted wherever the band covers it. It is never pulled down in a section,
     * as a bare verse with a loud vocal is usually meant to sound that way.
     */
    public static Balance decide(Reading reading, Target.Style style) {
        Balance out = new Balance();
        int steps = reading.power[VOCALS].length;
        float[][] group = new float[CORE][steps];
        for (int part = 0; part < COUNT; part++) {
            float[] p = reading.power[part];
            if (p == null) continue;
            float[] to = group[Math.min(part, OTHER)];
            for (int i = 0; i < steps; i++) to[i] += p[i];
        }
        double[] own = new double[CORE], db = new double[CORE];
        for (int g = 0; g < CORE; g++) own[g] = Loudness.gatedLufs(group[g]);
        // Adding the stems as power misses how they really combine, so this ties it to the true sum.
        double offset = reading.wholeLufs - mixLufs(group, db);
        if (own[VOCALS] - reading.wholeLufs < NO_VOCAL_LU) { out.noVocal = true; return out; }

        boolean metal = style == Target.Style.METAL || style == Target.Style.GLAM_METAL;
        double[] low = new double[CORE], high = new double[CORE];
        for (int g = 0; g < CORE; g++) {
            double mean = g == VOCALS && metal ? METAL_VOCAL_LU : MEAN_LU[g];
            low[g] = mean - SPREAD_LU[g];
            high[g] = mean + SPREAD_LU[g];
        }
        // Moving one part shifts all the others against the mix, so this settles over a few rounds.
        for (int round = 0; round < 12; round++) {
            double mix = mixLufs(group, db) + offset;
            for (int g = 0; g < CORE; g++) {
                double now = own[g] + db[g] - mix;
                double move = now < low[g] ? low[g] - now : now > high[g] ? high[g] - now : 0;
                db[g] = clamp(db[g] + 0.5 * move, MOST_DB[g]);
            }
        }
        for (int part = 0; part < COUNT; part++) {
            if (reading.power[part] != null) out.levelDb[part] = Math.round(db[Math.min(part, OTHER)] * 2) / 2.0;
            db[Math.min(part, OTHER)] = out.levelDb[Math.min(part, OTHER)];
        }
        rides(group, db, low[VOCALS], out);
        return out;
    }

    /** Loudness of the groups together at these levels. Separated stems are close enough to unrelated to add as power. */
    private static double mixLufs(float[][] group, double[] db) {
        float[] all = new float[group[0].length];
        for (int g = 0; g < CORE; g++) {
            float gain = (float) Math.pow(10, db[g] / 10);
            for (int i = 0; i < all.length; i++) all[i] += group[g][i] * gain;
        }
        return Loudness.gatedLufs(all);
    }

    private static void rides(float[][] group, double[] db, double aim, Balance out) {
        int steps = group[VOCALS].length, window = (int) (RIDE_SECONDS * STEPS_PER_SECOND);
        double vocalGain = Math.pow(10, db[VOCALS] / 10);
        double[] backGain = new double[CORE];
        for (int g = 1; g < CORE; g++) backGain[g] = Math.pow(10, db[g] / 10);
        double present = Math.pow(10, (Loudness.gatedLufs(group[VOCALS]) + 0.691 - 10) / 10);
        float[] ride = new float[steps];
        double vocal = 0, back = 0;
        for (int i = 0; i < steps; i++) {
            vocal += group[VOCALS][i] * vocalGain;
            for (int g = 1; g < CORE; g++) back += group[g][i] * backGain[g];
            if (i >= window) {
                vocal -= group[VOCALS][i - window] * vocalGain;
                for (int g = 1; g < CORE; g++) back -= group[g][i - window] * backGain[g];
            }
            double v = Math.max(vocal, 1e-14) / window, b = Math.max(back, 1e-14) / window;
            float want = 0;
            if (v > present * vocalGain) {
                double now = 10 * Math.log10(v / (v + b));
                if (now < aim) want = (float) Math.min(RIDE_MOST_DB, (aim - now) / (b / (v + b)));
            }
            ride[Math.max(0, i - window / 2)] = want;
        }
        // Eased both ways, so the moves are slow and arrive ahead of the phrase.
        float keep = (float) Math.exp(-1 / (RIDE_EASE_SECONDS * STEPS_PER_SECOND));
        float run = 0;
        for (int i = 0; i < steps; i++) { run = keep * run + (1 - keep) * ride[i]; ride[i] = run; }
        run = 0;
        for (int i = steps - 1; i >= 0; i--) { run = keep * run + (1 - keep) * ride[i]; ride[i] = run; }
        int moved = 0;
        for (float x : ride) { out.mostRideDb = Math.max(out.mostRideDb, x); if (x >= 0.25f) moved++; }
        out.rideDb = ride;
        out.ridePercent = steps == 0 ? 0 : 100 * moved / steps;
    }

    private static double clamp(double value, double most) { return Math.max(-most, Math.min(most, value)); }

    /** Gain at a moment in the song, from values a tenth of a second apart. */
    public static float rideGain(float[] rideDb, long frame, int rate) {
        if (rideDb == null || rideDb.length == 0) return 1;
        double place = frame * (double) STEPS_PER_SECOND / rate;
        int at = (int) place;
        if (at >= rideDb.length - 1) return (float) Math.pow(10, rideDb[rideDb.length - 1] / 20);
        double f = place - at;
        return (float) Math.pow(10, (rideDb[at] * (1 - f) + rideDb[at + 1] * f) / 20);
    }

    private static void ride(Audio a, float[] rideDb) {
        if (rideDb == null || rideDb.length == 0) return;
        int hop = a.rate / STEPS_PER_SECOND;
        for (int s = 0; s < a.l.length; s += hop) {
            float from = rideGain(rideDb, s, a.rate), to = rideGain(rideDb, s + hop, a.rate);
            int n = Math.min(hop, a.l.length - s);
            for (int i = 0; i < n; i++) {
                float g = from + (to - from) * i / hop;
                a.l[s + i] *= g;
                a.r[s + i] *= g;
            }
        }
    }

    private static double gain(double[] levelDb, int part) {
        return levelDb == null ? 1 : Math.pow(10, levelDb[part] / 20);
    }

    private static Audio treat(Audio a, int part, boolean clean, List<String> said) {
        StringBuilder note = new StringBuilder(NAMES[part]).append(" stem: ");
        if (clean) {
            CleanUp.Outcome c = new CleanUp.Outcome();
            a = CleanUp.apply(a, c, part != VOCALS);
            if (part != VOCALS) note.append(String.format(Locale.US, "resonance up to %.1f dB on %.0f%%, ", c.resonanceMaxDb, c.resonancePercent));
            note.append(String.format(Locale.US, "top-end clean-up up to %.1f dB on %.0f%%. ", c.swirlMaxDb, c.swirlPercent));
        }
        float[] mid = a.l, side = a.r;
        for (int i = 0; i < mid.length; i++) {
            float l = mid[i], r = side[i];
            mid[i] = (l + r) * 0.5f;
            side[i] = (l - r) * 0.5f;
        }
        DynamicBand.Outcome harsh = DynamicBand.apply(mid, a.rate, DynamicBand.harshness());
        note.append(String.format(Locale.US, "Harshness up to %.1f dB on %.1f%%", harsh.maxCutDb, harsh.activePercent));
        if (part == VOCALS) {
            DynamicBand.Outcome sib = DynamicBand.apply(mid, a.rate, DynamicBand.sibilance());
            note.append(String.format(Locale.US, ", sibilance up to %.1f dB on %.1f%%", sib.maxCutDb, sib.activePercent));
        }
        for (int i = 0; i < mid.length; i++) {
            float centre = mid[i], edge = side[i];
            mid[i] = centre + edge;
            side[i] = centre - edge;
        }
        said.add(note.toString());
        return a;
    }

    private static void addFile(Set set, int part, double gain, Audio out) throws IOException {
        Wav.Header h = set.headers[part];
        byte[] raw = new byte[BLOCK * h.frameBytes()];
        float[] l = new float[BLOCK], r = new float[BLOCK];
        try (RandomAccessFile in = new RandomAccessFile(set.files[part], "r")) {
            in.seek(h.dataStart);
            int at = 0;
            while (at < set.frames) {
                int n = Math.min(BLOCK, set.frames - at);
                in.readFully(raw, 0, n * h.frameBytes());
                Wav.decode(h, raw, n, l, r);
                add(l, r, at, n, gain, out);
                at += n;
            }
        }
    }

    private static void add(float[] l, float[] r, int at, int n, double gain, Audio out) {
        float g = (float) gain;
        for (int i = 0; i < n; i++) {
            out.l[at + i] += l[i] * g;
            out.r[at + i] += r[i] * g;
        }
    }
}
