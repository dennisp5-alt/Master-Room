package com.dennis.masterroom.ui;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

import com.dennis.masterroom.engine.Preview;
import com.dennis.masterroom.engine.Wav;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/**
 * Level-matched comparison. Every version plays from the same place and switching is
 * instant, with a short crossfade. Louder versions are turned down to the quietest one,
 * because the louder of two versions nearly always sounds better for that reason alone.
 */
final class AbPlayer {
    static final class Version {
        final String name; final File file; final double lufs, peakDb; final boolean master;
        final Wav.Header header; final RandomAccessFile raf; float gain = 1;
        /** @param master a take from the newest round: these play at their real level */
        Version(String name, File file, double lufs, double peakDb, boolean master) throws IOException {
            this.name = name; this.file = file; this.lufs = lufs; this.peakDb = peakDb; this.master = master;
            header = Wav.header(file);
            raf = new RandomAccessFile(file, "r");
        }
    }

    private static final int CHUNK = 2048;
    private final List<Version> versions = new ArrayList<>();
    private volatile int active;
    private volatile boolean playing, closed;
    private boolean matched = true;
    private volatile Preview preview;
    private volatile long position;
    private Thread thread;
    private AudioTrack track;

    synchronized void add(Version v) throws IOException {
        if (!versions.isEmpty() && versions.get(0).header.rate != v.header.rate) {
            v.raf.close();
            throw new IOException("Needs the same sample rate as the song (" + versions.get(0).header.rate + " Hz)");
        }
        versions.add(v);
        applyMatch();
    }

    /** Playback gain only: the files on disk are never touched. */
    private void applyMatch() {
        double reference = Double.MAX_VALUE, quietest = Double.MAX_VALUE;
        for (Version x : versions) {
            quietest = Math.min(quietest, x.lufs);
            if (x.master) reference = Math.min(reference, x.lufs);
        }
        // Everything meets the masters at their own level, so they sound as they will once
        // saved. Anything brought up stops short of clipping playback.
        if (reference == Double.MAX_VALUE) reference = quietest;
        for (Version x : versions) {
            double db = Math.min(reference - x.lufs, Math.max(0, -0.1 - x.peakDb));
            x.gain = matched ? (float) Math.pow(10, db / 20) : 1f;
        }
    }

    synchronized void setMatched(boolean on) {
        matched = on;
        applyMatch();
    }

    synchronized boolean isMatched() { return matched; }

    /** A listening preview on playback only. worn is the buds playing it, if we know them. */
    synchronized void setPreview(Preview.Mode mode, Preview.Mode worn) {
        preview = mode == Preview.Mode.FULL || versions.isEmpty() ? null : new Preview(mode, versions.get(0).header.rate, worn);
    }

    /** The playback trim on one version, in dB. */
    synchronized double trimDb(int i) {
        return 20 * Math.log10(Math.max(1e-6, versions.get(i).gain));
    }

    synchronized void clear() {
        pause();
        for (Version v : versions) { try { v.raf.close(); } catch (IOException ignored) { } }
        versions.clear();
        active = 0; position = 0;
    }

    synchronized int size() { return versions.size(); }
    synchronized Version get(int i) { return versions.get(i); }
    void select(int i) { active = i; }
    boolean isPlaying() { return playing; }

    synchronized double fraction() {
        if (versions.isEmpty()) return 0;
        return (double) position / Math.max(1, versions.get(0).header.frames);
    }

    synchronized void seek(double fraction) {
        if (!versions.isEmpty()) position = (long) (Math.max(0, Math.min(1, fraction)) * versions.get(0).header.frames);
    }

    void play() {
        if (playing || size() == 0) return;
        playing = true;
        if (thread == null) {
            int rate = get(0).header.rate;
            AudioFormat format = new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build();
            int min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT);
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(format).setBufferSizeInBytes(Math.max(min, CHUNK * 8 * 2)).build();
            track.play();
            thread = new Thread(this::loop, "ab-player");
            thread.start();
        } else {
            track.play();
        }
    }

    void pause() {
        playing = false;
        if (track != null) track.pause();
    }

    void close() {
        closed = true; playing = false;
        Thread t = thread;
        if (t != null) t.interrupt();
        clear();
        if (track != null) { track.release(); track = null; }
    }

    private boolean read(Version v, long at, byte[] raw, float[] l, float[] r) throws IOException {
        synchronized (v.raf) {
            int step = v.header.frameBytes();
            long left = v.header.frames - at;
            if (left <= 0) { java.util.Arrays.fill(l, 0); java.util.Arrays.fill(r, 0); return false; }
            int frames = (int) Math.min(CHUNK, left);
            v.raf.seek(v.header.dataStart + at * step);
            v.raf.readFully(raw, 0, frames * step);
            Wav.decode(v.header, raw, frames, l, r);
            for (int i = 0; i < frames; i++) { l[i] *= v.gain; r[i] *= v.gain; }
            for (int i = frames; i < CHUNK; i++) { l[i] = 0; r[i] = 0; }
            return true;
        }
    }

    private void loop() {
        byte[] raw = new byte[CHUNK * 8];
        float[] al = new float[CHUNK], ar = new float[CHUNK], bl = new float[CHUNK], br = new float[CHUNK];
        float[] out = new float[CHUNK * 2];
        int heard = active;
        try {
            while (!closed) {
                if (!playing) { Thread.sleep(25); continue; }
                Version now, was;
                int want = active;
                synchronized (this) {
                    if (versions.isEmpty() || want >= versions.size()) { Thread.sleep(25); continue; }
                    now = versions.get(want);
                    was = versions.get(Math.min(heard, versions.size() - 1));
                }
                long at = position;
                boolean more = read(now, at, raw, al, ar);
                if (was != now) {
                    read(was, at, raw, bl, br);
                    for (int i = 0; i < CHUNK; i++) {
                        float k = (float) i / CHUNK;
                        out[2 * i] = al[i] * k + bl[i] * (1 - k);
                        out[2 * i + 1] = ar[i] * k + br[i] * (1 - k);
                    }
                } else {
                    for (int i = 0; i < CHUNK; i++) { out[2 * i] = al[i]; out[2 * i + 1] = ar[i]; }
                }
                heard = want;
                Preview p = preview;
                if (p != null) p.process(out, CHUNK);
                track.write(out, 0, CHUNK * 2, AudioTrack.WRITE_BLOCKING);
                if (position == at) position = at + CHUNK;
                if (!more) { position = 0; pause(); }
            }
        } catch (InterruptedException ignored) {
        } catch (Exception e) {
            playing = false;
        }
    }
}
