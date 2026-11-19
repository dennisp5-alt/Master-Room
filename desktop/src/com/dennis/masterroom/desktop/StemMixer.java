package com.dennis.masterroom.desktop;

import com.dennis.masterroom.engine.Stems;
import com.dennis.masterroom.engine.Wav;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Arrays;
import java.util.List;

/**
 * Plays the stems live from their files, as the phone's StemPlayer does, so the balance is heard as it
 * changes. One gain per file (eased over one block), so each of Suno's stems can be set on its own; the
 * vocal rides go on every file of the Vocals stem. Playback only; nothing is written.
 */
final class StemMixer implements Feed {
    final Stems.Set set;
    private final List<StemFiles.Track> tracks;
    private final RandomAccessFile[] in;
    private final byte[][] raw;
    private final float[] l = new float[AbMixer.BLOCK], r = new float[AbMixer.BLOCK];
    private final float[] now;
    private volatile float[] wanted;
    private volatile float[] ride;
    private long position;

    StemMixer(StemFiles.Loaded loaded) throws IOException {
        set = loaded.set;
        tracks = loaded.tracks;
        int n = tracks.size();
        in = new RandomAccessFile[n];
        raw = new byte[n][];
        for (int t = 0; t < n; t++) {
            in[t] = new RandomAccessFile(tracks.get(t).file, "r");
            raw[t] = new byte[AbMixer.BLOCK * tracks.get(t).header.frameBytes()];
        }
        wanted = new float[n];
        Arrays.fill(wanted, 1f);
        now = wanted.clone();
    }

    /** Linear gain per file (0 for silent), and the vocal rides (dB every tenth of a second, may be null). */
    void setGains(float[] gains, float[] rideDb) { wanted = gains.clone(); ride = rideDb; }

    @Override public synchronized boolean next(float[] out) throws IOException {
        Arrays.fill(out, 0);
        int n = (int) Math.min(AbMixer.BLOCK, set.frames - position);
        if (n <= 0) { position = 0; return false; }
        float[] aim = wanted;
        float lift = Stems.rideGain(ride, position, set.rate);
        for (int t = 0; t < in.length; t++) {
            if (in[t] == null) continue;
            StemFiles.Track track = tracks.get(t);
            Wav.Header h = track.header;
            int have = (int) Math.max(0, Math.min(n, h.frames - position));
            float end = aim[t] * (track.part == Stems.VOCALS ? lift : 1), gain = now[t], step = have == 0 ? 0 : (end - gain) / have;
            now[t] = end;
            if (have == 0 || (gain == 0 && end == 0)) continue;
            in[t].seek(h.dataStart + position * (long) h.frameBytes());
            in[t].readFully(raw[t], 0, have * h.frameBytes());
            Wav.decode(h, raw[t], have, l, r);
            if (step == 0) {
                for (int i = 0; i < have; i++) { out[2 * i] += l[i] * gain; out[2 * i + 1] += r[i] * gain; }
            } else {
                for (int i = 0; i < have; i++, gain += step) { out[2 * i] += l[i] * gain; out[2 * i + 1] += r[i] * gain; }
            }
        }
        position += n;
        if (position >= set.frames) { position = 0; return false; }
        return true;
    }

    @Override public int rate() { return set.rate; }
    @Override public long frames() { return set.frames; }
    @Override public synchronized long position() { return position; }
    @Override public synchronized void seek(double fraction) { position = (long) (Math.max(0, Math.min(0.999, fraction)) * set.frames); }
    @Override public synchronized void rewind(long frames) { position = Math.max(0, position - frames); }

    synchronized void close() {
        for (RandomAccessFile f : in) { if (f != null) { try { f.close(); } catch (IOException ignored) { } } }
        Arrays.fill(in, null);
    }
}
