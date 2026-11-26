import com.dennis.masterroom.engine.Audio;
import com.dennis.masterroom.engine.VocalTune;
import com.dennis.masterroom.engine.VocalTuneProbe;

import java.util.Locale;
import java.util.Random;

/**
 * Checks Vocal Tune on synthetic singing with known detuning: a harmonic voice with formants,
 * vibrato, breaths and gaps. Measured with the same pitch follower afterwards.
 *   java -cp classes;check VocalTuneCheck
 */
public final class VocalTuneCheck {
    private static int failed;

    public static void main(String[] a) throws Exception {
        int rate = 44100;
        // Notes: midi, cents out, seconds. A3, C4, E4, D4, A3 with a sharp, a flat and an in-tune note.
        double[][] off = {{57, 35, 1.2}, {60, -28, 1.0}, {64, 6, 1.0}, {62, 40, 1.1}, {57, -45, 1.2}};
        double[][] in = {{57, 3, 1.2}, {60, -4, 1.0}, {64, 5, 1.0}, {62, 2, 1.1}, {57, -3, 1.2}};

        // 1. Out-of-tune singing comes back closer to the notes, vibrato kept, length kept.
        Audio sung = voice(off, rate, 0.30, new Random(1));
        long t0 = System.nanoTime();
        VocalTune.Result r = VocalTune.tune(sung, VocalTune.Mode.NATURAL);
        double ms = (System.nanoTime() - t0) / 1e6;
        System.out.println(r.words());
        System.out.printf(Locale.US, "  took %.0f ms for %.1f s of audio%n", ms, sung.seconds());
        check("detuned vocal is changed", r.changed);
        check("not taken for a full mix", !r.looksLikeMix);
        check("same length", r.audio.frames() == sung.frames());
        double[] before = VocalTuneProbe.noteCents(sung, off), after = VocalTuneProbe.noteCents(r.audio, off);
        for (int i = 0; i < off.length; i++) {
            System.out.printf(Locale.US, "  note %d: %+.0f -> %+.0f cents%n", i + 1, before[i], after[i]);
            if (Math.abs(before[i]) > 20) check("note " + (i + 1) + " moved towards pitch by at least 40%", Math.abs(after[i]) <= 0.6 * Math.abs(before[i]));
            check("note " + (i + 1) + " not pushed past the note", Math.signum(after[i]) == Math.signum(before[i]) || Math.abs(after[i]) < 8);
        }
        check("in-tune share went up", r.inTuneAfter > r.inTuneBefore);
        System.out.printf(Locale.US, "  vibrato depth: %.1f -> %.1f cents%n", VocalTuneProbe.vibratoCents(sung, off), VocalTuneProbe.vibratoCents(r.audio, off));
        check("vibrato kept (depth within 35% of before)", Math.abs(VocalTuneProbe.vibratoCents(r.audio, off) - VocalTuneProbe.vibratoCents(sung, off)) <= 0.35 * VocalTuneProbe.vibratoCents(sung, off));
        check("peak not raised by more than 0.5 dB", VocalTuneProbe.peakDb(r.audio) <= VocalTuneProbe.peakDb(sung) + 0.5);
        check("formants kept (spectral centroid within 8%)", Math.abs(VocalTuneProbe.centroid(r.audio) / VocalTuneProbe.centroid(sung) - 1) < 0.08);

        // 2. Firm moves further than Natural.
        VocalTune.Result firm = VocalTune.tune(sung, VocalTune.Mode.FIRM);
        double[] fa = VocalTuneProbe.noteCents(firm.audio, off);
        double nat = 0, fir = 0;
        for (int i = 0; i < off.length; i++) { nat += Math.abs(after[i]); fir += Math.abs(fa[i]); }
        System.out.println(firm.words());
        check("Firm corrects more than Natural", fir < nat);

        // 3. Singing that is already in tune is left untouched: the very same samples.
        Audio good = voice(in, rate, 0.30, new Random(2));
        VocalTune.Result g = VocalTune.tune(good, VocalTune.Mode.NATURAL);
        System.out.println(g.words());
        check("in-tune vocal left untouched", !g.changed && g.audio == good);

        // 4. Stereo: the same move on both sides, so the image holds still.
        Audio wide = new Audio(sung.l.clone(), scaled(sung.r, 0.5f), rate);
        VocalTune.Result w = VocalTune.tune(wide, VocalTune.Mode.NATURAL);
        check("stereo balance kept (right stays half of left)", Math.abs(VocalTuneProbe.rms(w.audio.r) / VocalTuneProbe.rms(w.audio.l) - 0.5) < 0.02);

        // 5. A full mix (bass, drums, chords under the voice) is recognised and left alone.
        Audio mix = mixWithBand(sung, new Random(3));
        VocalTune.Result m = VocalTune.tune(mix, VocalTune.Mode.NATURAL);
        System.out.println(m.words());
        check("full mix recognised and left alone", m.looksLikeMix && !m.changed && m.audio == mix);

        // 5b. A harmony under the lead: the shared stretches are left alone rather than bent to suit the lead.
        Audio lead = voice(off, rate, 0.30, new Random(5));
        double[][] third = new double[off.length][];
        for (int i = 0; i < off.length; i++) third[i] = new double[]{off[i][0] - 4, 0, off[i][2]};
        Audio harm = voice(third, rate, 0.30, new Random(6));
        Audio duet = new Audio(sum(lead.l, harm.l, 0.8f), sum(lead.r, harm.r, 0.8f), rate);
        VocalTune.Result du = VocalTune.tune(duet, VocalTune.Mode.NATURAL);
        System.out.println(du.words());
        check("harmony: the duet is left as it came", !du.changed && du.audio == duet);

        // 6. Off leaves everything alone; 48 kHz works too; silence is safe.
        check("Off is untouched", VocalTune.tune(sung, VocalTune.Mode.OFF).audio == sung);
        Audio s48 = voice(off, 48000, 0.30, new Random(4));
        VocalTune.Result r48 = VocalTune.tune(s48, VocalTune.Mode.NATURAL);
        double[] b48 = VocalTuneProbe.noteCents(s48, off), a48 = VocalTuneProbe.noteCents(r48.audio, off);
        check("48 kHz: the sharpest note is pulled in", Math.abs(a48[4]) < 0.6 * Math.abs(b48[4]));
        check("silence is left alone", !VocalTune.tune(new Audio(rate * 3, rate), VocalTune.Mode.NATURAL).changed);

        // 7. The effect snaps hard to the key's notes and flattens vibrato.
        VocalTune.Result fx = VocalTune.tune(sung, VocalTune.Mode.EFFECT);
        System.out.println(fx.words());
        System.out.printf(Locale.US, "  hard tune vibrato: %.1f -> %.1f cents%n", VocalTuneProbe.vibratoCents(sung, off), VocalTuneProbe.vibratoCents(fx.audio, off));
        check("hard tune flattens vibrato", VocalTuneProbe.vibratoCents(fx.audio, off) < 0.5 * VocalTuneProbe.vibratoCents(sung, off));

        System.out.println(failed == 0 ? "VOCAL TUNE CHECK PASSED" : "VOCAL TUNE CHECK FAILED: " + failed);
        if (failed > 0) System.exit(1);
    }

    static void check(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) failed++;
    }

    static float[] sum(float[] a, float[] b, float kb) {
        float[] y = new float[a.length];
        for (int i = 0; i < a.length; i++) y[i] = a[i] + kb * b[i];
        return y;
    }

    static float[] scaled(float[] x, float k) {
        float[] y = new float[x.length];
        for (int i = 0; i < x.length; i++) y[i] = x[i] * k;
        return y;
    }

    /** A sung line: glottal-like harmonics through three formants, vibrato, breath noise, gaps. */
    static Audio voice(double[][] notes, int rate, double gapSeconds, Random rnd) {
        double total = gapSeconds;
        for (double[] n : notes) total += n[2] + gapSeconds;
        int len = (int) (total * rate);
        float[] l = new float[len], r = new float[len];
        int at = (int) (gapSeconds * rate);
        double phase = 0;
        double[] formant = {700, 1220, 2600}, bw = {130, 70, 160};
        for (double[] n : notes) {
            int frames = (int) (n[2] * rate);
            for (int i = 0; i < frames; i++) {
                double t = i / (double) rate;
                double vib = 0.3 * Math.sin(2 * Math.PI * 5.5 * t) * Math.min(1, t / 0.3);
                double midi = n[0] + n[1] / 100 + vib;
                double f0 = 440 * Math.pow(2, (midi - 69) / 12);
                phase += 2 * Math.PI * f0 / rate;
                double s = 0;
                for (int h = 1; h * f0 < 5000; h++) {
                    double fh = h * f0, amp = 0;
                    for (int k = 0; k < 3; k++) amp += 1 / (1 + Math.pow((fh - formant[k]) / bw[k], 2)) * (k == 0 ? 1 : k == 1 ? 0.6 : 0.25);
                    s += amp / Math.sqrt(h) * Math.sin(h * phase);
                }
                double env = Math.min(1, Math.min(t / 0.04, (n[2] - t) / 0.06));
                double v = 0.12 * env * s + 0.002 * rnd.nextGaussian();
                l[at + i] = (float) v; r[at + i] = (float) v;
            }
            at += frames + (int) (gapSeconds * rate);
        }
        return new Audio(l, r, rate);
    }

    static Audio mixWithBand(Audio voice, Random rnd) {
        int n = voice.frames(), rate = voice.rate;
        float[] l = voice.l.clone(), r = voice.r.clone();
        double[] chord = {220.0 * Math.pow(2, 3 / 12.0), 220.0 * Math.pow(2, 7 / 12.0), 220.0 * Math.pow(2, 10 / 12.0)};
        for (int i = 0; i < n; i++) {
            double t = i / (double) rate;
            double bass = 0.25 * Math.sin(2 * Math.PI * 55 * t);
            double pad = 0;
            for (double f : chord) pad += 0.05 * Math.sin(2 * Math.PI * f * t) + 0.02 * Math.sin(4 * Math.PI * f * t);
            double beat = (t % 0.5) < 0.03 ? 0.3 * rnd.nextGaussian() * Math.exp(-((t % 0.5) / 0.01)) : 0;
            double hat = (t % 0.25) < 0.02 ? 0.05 * rnd.nextGaussian() : 0;
            l[i] += (float) (bass + pad + beat + hat); r[i] += (float) (bass + pad + beat + hat);
        }
        return new Audio(l, r, rate);
    }
}
