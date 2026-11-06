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
    public static final String[] NAMES = {"Vocals", "Drums", "Bass", "Other"};
    public static final int COUNT = 4, VOCALS = 0, DRUMS = 1, BASS = 2, OTHER = 3;
    public static final double REACH_DB = 6;
    private static final int BLOCK = 1 << 15;
    /** Stems from one run are the same length. This allows for a rounding difference. */
    private static final double LENGTH_SLACK_SECONDS = 0.1;
    private static final String[][] WORDS = {
            {"vocal", "vox", "voice"}, {"drum"}, {"bass"}, {"other", "instrument", "accomp", "melody"}};

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
        if (files.length != COUNT) throw new IOException("Four stems are needed");
        Wav.Header[] headers = new Wav.Header[COUNT];
        int rate = 0;
        long shortest = Long.MAX_VALUE, longest = 0;
        for (int i = 0; i < COUNT; i++) {
            if (files[i] == null) throw new IOException("The " + NAMES[i] + " stem is missing");
            Wav.Header h = Wav.header(files[i]);
            if (rate != 0 && h.rate != rate) throw new IOException("These stems have different sample rates");
            rate = h.rate;
            headers[i] = h;
            shortest = Math.min(shortest, h.frames);
            longest = Math.max(longest, h.frames);
        }
        if (shortest < rate * 5L) throw new IOException("Those stems are too short to master");
        if (longest - shortest > LENGTH_SLACK_SECONDS * rate) throw new IOException("These stems are different lengths, so they are not from one run");
        return new Set(files, headers, (int) shortest, rate);
    }

    /** Sums the stems as they came, at the given levels. Null levels and mutes mean flat. */
    public static Audio mix(Set set, double[] levelDb, boolean[] muted) throws IOException {
        Audio out = new Audio(set.frames, set.rate);
        for (int part = 0; part < COUNT; part++) {
            if (muted != null && muted[part]) continue;
            addFile(set, part, gain(levelDb, part), out);
        }
        return out;
    }

    /**
     * Treats the vocals and the other instruments, then balances all four. The notes say what
     * happened. Clean-up off leaves resonances and swirl alone but still tames harshness and esses.
     */
    public static Audio render(Set set, double[] levelDb, boolean[] muted, boolean clean, File work, List<String> notes, Engineer.Progress p) throws IOException {
        boolean any = false;
        for (boolean m : muted) any |= !m;
        if (!any) throw new IOException("Every stem is muted");
        File[] done = new File[COUNT];
        int[] sizes = new int[COUNT];
        List<String> said = new ArrayList<>();
        try {
            for (int part : new int[]{VOCALS, OTHER}) {
                if (muted[part]) continue;
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
                if (muted[part]) continue;
                double g = gain(levelDb, part);
                if (done[part] == null) addFile(set, part, g, out);
                else {
                    Audio a = Audio.load(done[part], sizes[part], set.rate);
                    add(a.l, a.r, 0, set.frames, g, out);
                }
            }
            if (!muted[DRUMS] || !muted[BASS]) said.add("Drums and bass stems: no treatment, so hits stay as they were");
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
