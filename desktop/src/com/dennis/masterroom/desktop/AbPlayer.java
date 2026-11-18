package com.dennis.masterroom.desktop;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import java.util.Random;

/**
 * Plays the comparison mix to the computer's default output. 24-bit when the device takes it,
 * otherwise 16-bit with TPDF dither. The device buffer is four blocks (about 0.17 s at 48 kHz): short enough
 * that a switch is heard at once, long enough not to stutter on Windows.
 * Playback never changes any file.
 */
final class AbPlayer {
    final AbMixer mix = new AbMixer();
    private volatile boolean playing, closed;
    private Thread thread;
    private SourceDataLine line;
    private int bytesPerSample;
    private Runnable onStop = () -> {};
    private String problem;

    void onStop(Runnable r) { onStop = r; }
    boolean isPlaying() { return playing; }
    String problem() { return problem; }

    synchronized void play() {
        if (playing || mix.size() == 0 || closed) return;
        try {
            if (line == null || (int) line.getFormat().getSampleRate() != mix.rate()) open(mix.rate());
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
            mix.rewind(Math.max(0, queued));
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
                boolean more = mix.next(out);
                SourceDataLine l;
                int bps;
                synchronized (this) { l = line; bps = bytesPerSample; }
                if (l == null) { Thread.sleep(15); continue; }
                int n = toBytes(out, bytes, bps, dither);
                if (playing) l.write(bytes, 0, n);
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
