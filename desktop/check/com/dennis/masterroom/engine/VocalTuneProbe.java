package com.dennis.masterroom.engine;

/** Test-only measurements for VocalTuneCheck, using the same pitch follower as Vocal Tune. */
public final class VocalTuneProbe {
    private VocalTuneProbe() {}
    static final double GAP = 0.30;

    /** Each note's average pitch against its intended note, in cents (middle 60% of the note). */
    public static double[] noteCents(Audio a, double[][] notes) {
        VocalTune.Track t = VocalTune.follow(a);
        double[] out = new double[notes.length];
        double at = GAP;
        for (int k = 0; k < notes.length; k++) {
            double s = 0; int c = 0;
            for (int f = frame(t, at + 0.2 * notes[k][2]); f < frame(t, at + 0.8 * notes[k][2]) && f < t.frames(); f++)
                if (!Double.isNaN(t.midi[f])) { s += t.midi[f]; c++; }
            out[k] = c == 0 ? Double.NaN : (s / c - notes[k][0]) * 100;
            at += notes[k][2] + GAP;
        }
        return out;
    }

    /** Average vibrato depth in cents (RMS of pitch around a 150 ms average, middle of each note). */
    public static double vibratoCents(Audio a, double[][] notes) {
        VocalTune.Track t = VocalTune.follow(a);
        double[] c = VocalTune.centres(t, VocalTune.VIBRATO_SECONDS);
        double s = 0; int n = 0, at0 = 0;
        double at = GAP;
        for (double[] note : notes) {
            for (int f = frame(t, at + 0.3 * note[2]); f < frame(t, at + 0.7 * note[2]) && f < t.frames(); f++)
                if (!Double.isNaN(t.midi[f])) { double d = t.midi[f] - c[f]; s += d * d; n++; }
            at += note[2] + GAP;
        }
        return n == 0 ? 0 : Math.sqrt(s / n) * 100;
    }

    static int frame(VocalTune.Track t, double seconds) { return (int) (seconds * t.rate / t.hop); }

    public static double peakDb(Audio a) {
        double p = 0;
        for (int i = 0; i < a.frames(); i++) p = Math.max(p, Math.max(Math.abs(a.l[i]), Math.abs(a.r[i])));
        return 20 * Math.log10(Math.max(p, 1e-9));
    }
    public static double rms(float[] x) { double s = 0; for (float v : x) s += v * v; return Math.sqrt(s / x.length); }
    /** Brightness proxy: RMS of the first difference over RMS (rises with the spectral centroid). */
    public static double centroid(Audio a) {
        double s = 0, d = 0;
        for (int i = 1; i < a.frames(); i++) { s += a.l[i] * a.l[i]; double x = a.l[i] - a.l[i - 1]; d += x * x; }
        return Math.sqrt(d / Math.max(s, 1e-12));
    }
}
