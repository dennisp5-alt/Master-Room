package com.dennis.masterroom.desktop;

import com.dennis.masterroom.engine.Wav;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The comparison mix, without any audio device (so it can be checked): the phone's AbPlayer logic.
 * Every version plays from the same place; switching crossfades over one block; with level match on,
 * every version is turned to the masters' level (the quietest master), because the louder of two
 * versions nearly always sounds better for that reason alone. Playback gain only: files are never touched.
 */
final class AbMixer implements Feed {
    static final int BLOCK = 2048;

    static final class Version {
        final String name;
        final File file;
        final double lufs, peakDb;
        final boolean master;
        final Wav.Header header;
        final RandomAccessFile raf;
        volatile float gain = 1;

        /** @param master a take from this round: these play at their real level */
        Version(String name, File file, double lufs, double peakDb, boolean master) throws IOException {
            this.name = name; this.file = file; this.lufs = lufs; this.peakDb = peakDb; this.master = master;
            header = Wav.header(file);
            raf = new RandomAccessFile(file, "r");
        }
    }

    private final List<Version> versions = new ArrayList<>();
    private boolean matched = true;
    private volatile int active, heard = -1;
    private volatile long position;
    private final byte[] raw = new byte[BLOCK * 32];
    private final float[] al = new float[BLOCK], ar = new float[BLOCK], bl = new float[BLOCK], br = new float[BLOCK];

    synchronized void add(Version v) throws IOException {
        if (!versions.isEmpty() && versions.get(0).header.rate != v.header.rate) {
            v.raf.close();
            throw new IOException("Needs the same sample rate as the song (" + versions.get(0).header.rate + " Hz)");
        }
        versions.add(v);
        applyMatch();
    }

    /**
     * Exactly the phone's rule, in dB per version: everything meets the quietest master's level
     * (the quietest version when there are no masters); anything turned up stops 0.1 dB short of clipping.
     */
    static double[] matchDb(double[] lufs, double[] peakDb, boolean[] master) {
        double reference = Double.MAX_VALUE, quietest = Double.MAX_VALUE;
        for (int i = 0; i < lufs.length; i++) {
            quietest = Math.min(quietest, lufs[i]);
            if (master[i]) reference = Math.min(reference, lufs[i]);
        }
        if (reference == Double.MAX_VALUE) reference = quietest;
        double[] db = new double[lufs.length];
        for (int i = 0; i < lufs.length; i++) db[i] = Math.min(reference - lufs[i], Math.max(0, -0.1 - peakDb[i]));
        return db;
    }

    private void applyMatch() {
        int n = versions.size();
        double[] lufs = new double[n], peak = new double[n];
        boolean[] master = new boolean[n];
        for (int i = 0; i < n; i++) { Version x = versions.get(i); lufs[i] = x.lufs; peak[i] = x.peakDb; master[i] = x.master; }
        double[] db = matchDb(lufs, peak, master);
        for (int i = 0; i < n; i++) versions.get(i).gain = matched ? (float) Math.pow(10, db[i] / 20) : 1f;
    }

    synchronized void setMatched(boolean on) { matched = on; applyMatch(); }
    synchronized boolean isMatched() { return matched; }
    synchronized double trimDb(int i) { return 20 * Math.log10(Math.max(1e-6, versions.get(i).gain)); }
    synchronized int size() { return versions.size(); }
    synchronized Version get(int i) { return versions.get(i); }
    @Override public synchronized int rate() { return versions.isEmpty() ? 48000 : versions.get(0).header.rate; }
    @Override public synchronized long frames() { return versions.isEmpty() ? 0 : versions.get(0).header.frames; }
    void select(int i) { active = i; }
    int active() { return active; }
    @Override public long position() { return position; }
    @Override public double seconds() { return position / (double) rate(); }
    @Override public double length() { return frames() / (double) rate(); }
    @Override public synchronized double fraction() { return versions.isEmpty() ? 0 : (double) position / Math.max(1, frames()); }
    @Override public synchronized void rewind(long frames) { position = Math.max(0, position - frames); }
    @Override public synchronized void seek(double fraction) { if (!versions.isEmpty()) position = (long) (Math.max(0, Math.min(1, fraction)) * frames()); }

    synchronized void clear() {
        for (Version v : versions) { try { v.raf.close(); } catch (IOException ignored) { } }
        versions.clear();
        active = 0; heard = -1; position = 0;
    }

    private boolean read(Version v, long at, float[] l, float[] r) throws IOException {
        int step = v.header.frameBytes();
        long left = v.header.frames - at;
        if (left <= 0) { Arrays.fill(l, 0); Arrays.fill(r, 0); return false; }
        int frames = (int) Math.min(BLOCK, left);
        v.raf.seek(v.header.dataStart + at * step);
        v.raf.readFully(raw, 0, frames * step);
        Wav.decode(v.header, raw, frames, l, r);
        float g = v.gain;
        for (int i = 0; i < frames; i++) { l[i] *= g; r[i] *= g; }
        for (int i = frames; i < BLOCK; i++) { l[i] = 0; r[i] = 0; }
        return true;
    }

    /**
     * The next block, interleaved stereo, into out (2 * BLOCK). Crossfades when the selection changed.
     * Returns false at the end of the song (and goes back to the start).
     */
    @Override public synchronized boolean next(float[] out) throws IOException {
        if (versions.isEmpty()) { Arrays.fill(out, 0); return false; }
        int want = Math.min(active, versions.size() - 1);
        Version now = versions.get(want), was = versions.get(heard < 0 ? want : Math.min(heard, versions.size() - 1));
        long at = position;
        boolean more = read(now, at, al, ar);
        if (was != now) {
            read(was, at, bl, br);
            for (int i = 0; i < BLOCK; i++) {
                float k = (float) i / BLOCK;
                out[2 * i] = al[i] * k + bl[i] * (1 - k);
                out[2 * i + 1] = ar[i] * k + br[i] * (1 - k);
            }
        } else {
            for (int i = 0; i < BLOCK; i++) { out[2 * i] = al[i]; out[2 * i + 1] = ar[i]; }
        }
        heard = want;
        if (position == at) position = at + BLOCK;
        if (!more || position >= frames()) { position = 0; return false; }
        return true;
    }
}
