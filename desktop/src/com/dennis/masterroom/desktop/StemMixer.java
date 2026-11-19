package com.dennis.masterroom.desktop;

import com.dennis.masterroom.engine.Stems;
import com.dennis.masterroom.engine.Wav;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Arrays;

/**
 * Plays the stems live from their files, as the phone's StemPlayer does, so the balance is heard as it
 * changes: a gain per stem (eased over one block), the vocal rides, mutes and solos. Playback only;
 * nothing is written.
 */
final class StemMixer implements Feed {
    final Stems.Set set;
    private final RandomAccessFile[] in = new RandomAccessFile[Stems.COUNT];
    private final byte[][] raw = new byte[Stems.COUNT][];
    private final float[] l = new float[AbMixer.BLOCK], r = new float[AbMixer.BLOCK];
    private final float[] now = new float[Stems.COUNT];
    private volatile float[] wanted = new float[Stems.COUNT];
    private volatile float[] ride;
    private long position;

    StemMixer(Stems.Set set) throws IOException {
        this.set = set;
        for (int s = 0; s < Stems.COUNT; s++) {
            if (!set.has(s)) continue;
            in[s] = new RandomAccessFile(set.files[s], "r");
            raw[s] = new byte[AbMixer.BLOCK * set.headers[s].frameBytes()];
        }
        Arrays.fill(wanted, 1f);
        System.arraycopy(wanted, 0, now, 0, now.length);
    }

    /** Linear gain per stem (0 for silent), and the vocal rides (dB every tenth of a second, may be null). */
    void setGains(float[] gains, float[] rideDb) { wanted = gains.clone(); ride = rideDb; }

    @Override public synchronized boolean next(float[] out) throws IOException {
        Arrays.fill(out, 0);
        int n = (int) Math.min(AbMixer.BLOCK, set.frames - position);
        if (n <= 0) { position = 0; return false; }
        float[] aim = wanted;
        float lift = Stems.rideGain(ride, position, set.rate);
        for (int s = 0; s < Stems.COUNT; s++) {
            if (in[s] == null) continue;
            Wav.Header h = set.headers[s];
            in[s].seek(h.dataStart + position * (long) h.frameBytes());
            in[s].readFully(raw[s], 0, n * h.frameBytes());
            Wav.decode(h, raw[s], n, l, r);
            float end = aim[s] * (s == Stems.VOCALS ? lift : 1), gain = now[s], step = (end - gain) / n;
            if (step == 0) {
                for (int i = 0; i < n; i++) { out[2 * i] += l[i] * gain; out[2 * i + 1] += r[i] * gain; }
            } else {
                for (int i = 0; i < n; i++, gain += step) { out[2 * i] += l[i] * gain; out[2 * i + 1] += r[i] * gain; }
            }
            now[s] = end;
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
