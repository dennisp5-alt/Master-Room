package com.dennis.masterroom.desktop;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import com.dennis.masterroom.engine.Loudness;
import com.dennis.masterroom.engine.Spectrum;

import java.util.Random;

/**
 * Plays the comparison mix to the computer's default output. 24-bit when the device takes it,
 * otherwise 16-bit with TPDF dither. The device buffer is four blocks (about 0.17 s at 48 kHz): short enough
 * that a switch is heard at once, long enough not to stutter on Windows.
 * Playback never changes any file. Each block is also measured for the console (spectrum, level,
 * momentary loudness) and stamped with its place in the device's timeline, so the console shows
 * what is being heard rather than what is queued.
 */
final class AbPlayer {
    /** What the console shows for one block of audio. */
    static final class Moment {
        final float[] bars = new float[Spectrum.BARS];
        /** Fast level, BS.1770 channel sum without the K filter (LUFS-like), for the meter lights. */
        float light = -70;
        /** Momentary loudness (last 400 ms, K-weighted), NaN until 400 ms have played. */
        double lufs = Double.NaN;
        long at;
        void copyTo(Moment o) { System.arraycopy(bars, 0, o.bars, 0, bars.length); o.light = light; o.lufs = lufs; o.at = at; }
    }

    /** Measures blocks as they go out. */
    static final class Analyser {
        private final int rate;
        private final Spectrum spectrum;
        private Loudness.Live live;
        Analyser(int rate) { this.rate = rate; spectrum = new Spectrum(rate); live = new Loudness.Live(rate); }
        void reset() { live = new Loudness.Live(rate); }
        /** @param block interleaved stereo, {@link AbMixer#BLOCK} frames (the spectrum's size) */
        void take(float[] block, Moment m) {
            spectrum.bars(block, m.bars);
            live.add(block, AbMixer.BLOCK);
            double e = 0;
            for (int i = 0; i < AbMixer.BLOCK * 2; i++) e += block[i] * block[i];
            m.light = (float) (-0.691 + 10 * Math.log10(e / AbMixer.BLOCK + 1e-12));
            m.lufs = live.lufs();
        }
    }

    private static final int KEPT = 24;
    private final Moment[] kept = new Moment[KEPT];
    private int keptCount;
    private long written;
    private Analyser analyser;
    {
        for (int i = 0; i < KEPT; i++) kept[i] = new Moment();
    }

    final AbMixer mix = new AbMixer();
    /** What plays: the takes, or the stems while balancing them. */
    private volatile Feed feed = mix;
    private volatile boolean playing, closed;
    private Thread thread;
    private SourceDataLine line;
    private int bytesPerSample;
    private Runnable onStop = () -> {};
    private String problem;

    void onStop(Runnable r) { onStop = r; }

    Feed feed() { return feed; }

    /** Switches what plays (pauses first). */
    void use(Feed f) { pause(); feed = f == null ? mix : f; }

    /** Fills out with the block being heard now. False when nothing is playing (or nothing heard yet). */
    boolean now(Moment out) {
        SourceDataLine l = line;
        if (!playing || l == null) return false;
        long heard = l.getLongFramePosition();
        synchronized (kept) {
            for (int back = 1; back <= Math.min(keptCount, KEPT); back++) {
                Moment m = kept[(keptCount - back) % KEPT];
                if (m.at <= heard) { m.copyTo(out); return true; }
            }
        }
        return false;
    }
    boolean isPlaying() { return playing; }
    String problem() { return problem; }

    synchronized void play() {
        Feed f = feed;
        if (playing || !f.ready() || closed) return;
        try {
            if (line == null || (int) line.getFormat().getSampleRate() != f.rate()) open(f.rate());
        } catch (LineUnavailableException | IllegalArgumentException e) {
            problem = "No audio output available: " + e.getMessage();
            return;
        }
        problem = null;
        playing = true;
        line.start();
        if (thread == null) { thread = new Thread(this::loop, "ab-player"); thread.setDaemon(true); thread.start(); }
    }

    /** Pauses where the listener is, not where the queued audio ends. */
    synchronized void pause() {
        playing = false;
        if (line != null) {
            line.stop();
            int queued = (line.getBufferSize() - line.available()) / line.getFormat().getFrameSize();
            line.flush();
            feed.rewind(Math.max(0, queued));
            synchronized (kept) { written = line.getLongFramePosition(); keptCount = 0; }
            if (analyser != null) analyser.reset();
        }
    }

    void close() {
        closed = true; playing = false;
        Thread t = thread;
        if (t != null) t.interrupt();
        synchronized (this) { if (line != null) { line.close(); line = null; } }
        mix.clear();
    }

    private void open(int rate) throws LineUnavailableException {
        if (line != null) { line.close(); line = null; }
        for (int bits : new int[]{24, 16}) {
            AudioFormat f = new AudioFormat(rate, bits, 2, true, false);
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, f);
            if (!AudioSystem.isLineSupported(info)) continue;
            SourceDataLine l = (SourceDataLine) AudioSystem.getLine(info);
            try {
                l.open(f, AbMixer.BLOCK * 4 * f.getFrameSize());
            } catch (LineUnavailableException | IllegalArgumentException e) {
                l.close();
                continue;
            }
            line = l;
            bytesPerSample = bits / 8;
            analyser = new Analyser(rate);
            synchronized (kept) { written = 0; keptCount = 0; }
            return;
        }
        throw new LineUnavailableException("the output device takes neither 24-bit nor 16-bit stereo at " + rate + " Hz");
    }

    private void loop() {
        float[] out = new float[AbMixer.BLOCK * 2];
        byte[] bytes = new byte[AbMixer.BLOCK * 2 * 3];
        Random dither = new Random(1);
        try {
            while (!closed) {
                if (!playing) { Thread.sleep(15); continue; }
                boolean more = feed.next(out);
                SourceDataLine l;
                int bps;
                Analyser an;
                synchronized (this) { l = line; bps = bytesPerSample; an = analyser; }
                if (l == null) { Thread.sleep(15); continue; }
                int n = toBytes(out, bytes, bps, dither);
                if (an != null) synchronized (kept) { Moment m = kept[keptCount % KEPT]; an.take(out, m); m.at = written; keptCount++; }
                if (playing) { l.write(bytes, 0, n); synchronized (kept) { written += AbMixer.BLOCK; } }
                if (!more) {
                    synchronized (this) { playing = false; if (line != null) line.drain(); if (line != null) line.stop(); }
                    onStop.run();
                }
            }
        } catch (InterruptedException ignored) {
        } catch (Exception e) {
            problem = "Playback stopped: " + e.getMessage();
            playing = false;
            onStop.run();
        }
    }

    /** Float to little-endian PCM. 16-bit gets TPDF dither; both clamp safely. */
    static int toBytes(float[] in, byte[] out, int bytesPerSample, Random dither) {
        int p = 0;
        for (float v : in) {
            double x = Math.max(-1, Math.min(1, v));
            if (bytesPerSample == 3) {
                int s = (int) Math.round(x * 8388607);
                out[p++] = (byte) s; out[p++] = (byte) (s >> 8); out[p++] = (byte) (s >> 16);
            } else {
                double d = (dither.nextDouble() - dither.nextDouble()) / 32768.0;
                int s = (int) Math.max(-32768, Math.min(32767, Math.round((x + d) * 32767)));
                out[p++] = (byte) s; out[p++] = (byte) (s >> 8);
            }
        }
        return p;
    }
}
