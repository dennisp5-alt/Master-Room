package com.dennis.masterroom.ui;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

import com.dennis.masterroom.engine.Loudness;
import com.dennis.masterroom.engine.Stems;
import com.dennis.masterroom.engine.Wav;

import java.io.IOException;
import java.io.RandomAccessFile;

/** Plays the four stems live from their files, so the balance can be heard as it changes. */
final class StemPlayer {
    private static final int BLOCK = 2048, RECENT = 4;
    private final Stems.Set set;
    private final Runnable ended;
    private final int start;
    private volatile float[] wanted;
    private volatile boolean running = true;
    private volatile int jump = -1, at;
    private volatile float levelDb = -60, earlierDb = -60, lufs = Float.NaN;

    StemPlayer(Stems.Set set, float[] gains, double from, Runnable ended) {
        this.set = set; this.wanted = gains; this.ended = ended;
        start = (int) (Math.max(0, Math.min(0.999, from)) * set.frames);
        at = start;
    }

    void start() {
        Thread t = new Thread(this::run, "stems");
        t.setPriority(Thread.MAX_PRIORITY);
        t.start();
    }

    void stop() { running = false; }
    void setGains(float[] gains) { wanted = gains; }
    void seekTo(double fraction) { jump = (int) (Math.max(0, Math.min(0.999, fraction)) * set.frames); }
    double fraction() { return at / (double) set.frames; }
    float levelDb() { return levelDb; }
    float earlierDb() { return earlierDb; }
    /** Measured from what is actually playing, so it follows the balance. */
    float lufs() { return lufs; }

    private void run() {
        AudioTrack track = null;
        RandomAccessFile[] in = new RandomAccessFile[Stems.COUNT];
        boolean finished = false;
        try {
            int min = AudioTrack.getMinBufferSize(set.rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT);
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                            .setSampleRate(set.rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                    .setBufferSizeInBytes(Math.max(min, BLOCK * 8 * 4))
                    .setTransferMode(AudioTrack.MODE_STREAM).build();
            byte[][] raw = new byte[Stems.COUNT][];
            for (int s = 0; s < Stems.COUNT; s++) {
                in[s] = new RandomAccessFile(set.files[s], "r");
                raw[s] = new byte[BLOCK * set.headers[s].frameBytes()];
            }
            float[] l = new float[BLOCK], r = new float[BLOCK], out = new float[BLOCK * 2], now = wanted.clone(), recent = new float[RECENT];
            java.util.Arrays.fill(recent, -60);
            int pos = start, slot = 0;
            Loudness.Live meter = new Loudness.Live(set.rate);
            boolean place = true;
            track.play();
            while (running && pos < set.frames) {
                int to = jump;
                if (to >= 0) {
                    jump = -1;
                    pos = to;
                    place = true;
                    track.pause();
                    track.flush();
                    track.play();
                }
                if (place) {
                    for (int s = 0; s < Stems.COUNT; s++) in[s].seek(set.headers[s].dataStart + pos * (long) set.headers[s].frameBytes());
                    place = false;
                }
                int n = Math.min(BLOCK, set.frames - pos);
                java.util.Arrays.fill(out, 0, n * 2, 0);
                float[] aim = wanted;
                for (int s = 0; s < Stems.COUNT; s++) {
                    Wav.Header h = set.headers[s];
                    in[s].readFully(raw[s], 0, n * h.frameBytes());
                    Wav.decode(h, raw[s], n, l, r);
                    float gain = now[s], step = (aim[s] - gain) / n;
                    for (int i = 0; i < n; i++, gain += step) {
                        out[2 * i] += l[i] * gain;
                        out[2 * i + 1] += r[i] * gain;
                    }
                    now[s] = aim[s];
                }
                meter.add(out, n);
                lufs = (float) meter.lufs();
                double energy = 0;
                for (int i = 0; i < n * 2; i++) {
                    energy += out[i] * out[i];
                    out[i] = Math.max(-1f, Math.min(1f, out[i]));
                }
                track.write(out, 0, n * 2, AudioTrack.WRITE_BLOCKING);
                pos += n;
                at = pos;
                float db = (float) (10 * Math.log10(energy / (2.0 * n) + 1e-12));
                levelDb = db;
                earlierDb = recent[slot];
                recent[slot] = db;
                slot = (slot + 1) % RECENT;
            }
            finished = pos >= set.frames;
        } catch (IOException | RuntimeException e) {
            finished = true;
        } finally {
            for (RandomAccessFile f : in) {
                if (f != null) { try { f.close(); } catch (IOException ignored) { } }
            }
            if (track != null) {
                try { track.stop(); } catch (IllegalStateException ignored) { }
                track.release();
            }
        }
        if (finished && running) ended.run();
    }
}
