package com.dennis.masterroom.engine;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Streaming services and download stores do not play your WAV: they play a lossy copy,
 * and encoding can push peaks higher. This encodes the song's loudest 12 seconds the way
 * they would, decodes it again and measures what actually comes back.
 */
public final class Streaming {
    /** Round-trips audio through a codec the phone provides, such as AAC. */
    public interface Codec {
        String name();
        Audio roundTrip(Audio in) throws IOException;
    }

    public static final class Line {
        public final String name; public final double truePeakDb, lufsChange;
        Line(String name, double truePeakDb, double lufsChange) { this.name = name; this.truePeakDb = truePeakDb; this.lufsChange = lufsChange; }
    }

    public static final class Report {
        public final List<Line> lines = new ArrayList<>();
        public double worstPeakDb = Double.NEGATIVE_INFINITY;
        public boolean safe() { return worstPeakDb <= 0; }
        public String verdict() {
            if (lines.isEmpty()) return "Could not be run on this phone";
            StringBuilder b = new StringBuilder();
            for (Line l : lines) b.append(b.length() == 0 ? "" : ", ").append(String.format(Locale.US, "%s %.1f dBTP", l.name, l.truePeakDb));
            return (safe() ? "Safe after encoding: " : "May clip after encoding: ") + b;
        }
    }

    /** Set by the app. Tests run without it. */
    public static volatile Codec phoneCodec;

    private static final int EXCERPT_SECONDS = 12;

    private Streaming() {}

    public static Report check(Audio master) {
        Report out = new Report();
        try {
            Audio piece = Resample.to(loudest(master), 44100);
            double lufs = Loudness.measure(piece).integrated;
            File wav = File.createTempFile("streaming", ".wav"), mp3 = File.createTempFile("streaming", ".mp3");
            try {
                Wav.write(wav, piece, 16);
                for (int kbps : new int[]{320, 128}) {
                    Engineer.checkStop();
                    Release.encode(wav, mp3, new Release.Info(), kbps);
                    add(out, "MP3 " + kbps, Release.decode(mp3), lufs);
                }
            } finally {
                wav.delete();
                mp3.delete();
            }
            Codec phone = phoneCodec;
            if (phone != null) {
                Engineer.checkStop();
                add(out, phone.name(), phone.roundTrip(piece), lufs);
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof Engineer.Stopped) throw (Engineer.Stopped) e;
        }
        return out;
    }

    private static void add(Report out, String name, Audio decoded, double lufs) {
        double peak = Oversample.truePeakDb(decoded);
        out.lines.add(new Line(name, peak, Loudness.measure(decoded).integrated - lufs));
        out.worstPeakDb = Math.max(out.worstPeakDb, peak);
    }

    private static Audio loudest(Audio a) {
        int want = Math.min(a.frames(), EXCERPT_SECONDS * a.rate);
        int step = a.rate;
        double[] energy = new double[a.frames() / step + 2];
        for (int i = 0; i < a.frames(); i++) energy[i / step] += a.l[i] * a.l[i] + a.r[i] * a.r[i];
        int span = want / step, best = 0;
        double sum = 0, top = -1;
        for (int b = 0; b < energy.length; b++) {
            sum += energy[b];
            if (b >= span) sum -= energy[b - span];
            if (b >= span - 1 && sum > top) { top = sum; best = b - span + 1; }
        }
        int start = Math.min(a.frames() - want, Math.max(0, best) * step);
        Audio out = new Audio(want, a.rate);
        System.arraycopy(a.l, start, out.l, 0, want);
        System.arraycopy(a.r, start, out.r, 0, want);
        return out;
    }
}
