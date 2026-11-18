package com.dennis.masterroom.desktop;

import com.dennis.masterroom.engine.Audio;
import com.dennis.masterroom.engine.Loudness;
import com.dennis.masterroom.engine.Wav;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Build 2 checks, no sound card needed: the comparison player on a real mastering round.
 *  - level match follows the phone's rule, and matched versions measure the same loudness;
 *  - with matching off, playback is the file sample for sample;
 *  - a switch crossfades over one block, then plays the new version exactly;
 *  - the song ends and returns to the start; seeking lands where asked;
 *  - 24-bit and dithered 16-bit output; the blind test shuffles, hides, picks and restores;
 *  - the waveform overview matches the file's peak.
 */
public final class PlayerCheck {
    private PlayerCheck() {}

    private static int fails;
    private static void ok(boolean c, String m) { if (!c) fails++; System.out.println((c ? "PASS " : "FAIL ") + "player: " + m); }

    public static int run(MasterJob.Outcome o) throws Exception {
        fails = 0;
        AbMixer mix = new AbMixer();
        mix.add(new AbMixer.Version("Original", o.source, o.beforeLufs, o.beforePeak, false));
        for (MasterJob.Take t : o.takes) mix.add(new AbMixer.Version(t.label, t.files[0], t.lufs, t.truePeak, true));
        int n = mix.size();
        ok(n == 1 + o.takes.size(), "loads the original and " + o.takes.size() + " takes");

        // Level match: the phone's rule, by hand.
        double ref = Double.MAX_VALUE;
        for (MasterJob.Take t : o.takes) ref = Math.min(ref, t.lufs);
        for (int i = 0; i < n; i++) {
            AbMixer.Version v = mix.get(i);
            double want = Math.min(ref - v.lufs, Math.max(0, -0.1 - v.peakDb));
            ok(Math.abs(mix.trimDb(i) - want) < 0.01, String.format(Locale.US, "%s trim %+.2f dB (rule gives %+.2f)", v.name, mix.trimDb(i), want));
        }

        // Matched playback measures the same loudness (the original only when it had room to come up).
        double[] heard = new double[n];
        for (int i = 0; i < n; i++) heard[i] = Loudness.measure(render(mix, i)).integrated;
        for (int i = 1; i < n; i++)
            ok(Math.abs(heard[i] - ref) < 0.15, String.format(Locale.US, "%s plays at %.2f LUFS matched (masters meet at %.2f)", mix.get(i).name, heard[i], ref));
        double origWant = o.beforeLufs + mix.trimDb(0);
        ok(Math.abs(heard[0] - origWant) < 0.15, String.format(Locale.US, "Original plays at %.2f LUFS matched (%.2f expected%s)", heard[0], origWant,
            origWant < ref - 0.1 ? ", held back by its peak" : ""));

        // Matching off: exactly the file.
        mix.setMatched(false);
        Audio file = Wav.read(o.takes.get(0).files[0]), played = render(mix, 1);
        double worst = 0;
        for (int i = 0; i < file.l.length; i++) worst = Math.max(worst, Math.max(Math.abs(file.l[i] - played.l[i]), Math.abs(file.r[i] - played.r[i])));
        ok(played.l.length == file.l.length && worst == 0, "matching off plays the file sample for sample (worst " + worst + ")");
        ok(Math.abs(mix.trimDb(0)) < 1e-9 && Math.abs(mix.trimDb(2)) < 1e-9, "matching off: no trims");
        mix.setMatched(true);

        // Crossfade on a switch, then the new version exactly.
        Audio a = Wav.read(o.takes.get(0).files[0]), b = Wav.read(o.takes.get(1).files[0]);
        float ga = (float) Math.pow(10, mix.trimDb(1) / 20), gb = (float) Math.pow(10, mix.trimDb(2) / 20);
        float[] out = new float[AbMixer.BLOCK * 2];
        mix.seek(0); mix.select(1);
        for (int k = 0; k < 40; k++) mix.next(out);
        long at = mix.position();
        mix.select(2);
        mix.next(out);
        double fadeErr = 0, cleanErr = 0;
        for (int i = 0; i < AbMixer.BLOCK; i++) {
            float k = (float) i / AbMixer.BLOCK;
            float want = b.l[(int) at + i] * gb * k + a.l[(int) at + i] * ga * (1 - k);
            fadeErr = Math.max(fadeErr, Math.abs(out[2 * i] - want));
        }
        mix.next(out);
        for (int i = 0; i < AbMixer.BLOCK; i++) cleanErr = Math.max(cleanErr, Math.abs(out[2 * i] - b.l[(int) at + AbMixer.BLOCK + i] * gb));
        ok(fadeErr < 1e-6, String.format(Locale.US, "switch crossfades over one block (%.1f ms, error %.1e)", 1000.0 * AbMixer.BLOCK / mix.rate(), fadeErr));
        ok(cleanErr < 1e-6, "then plays the new take exactly, from the same place in the song");

        // Seek and the end of the song.
        mix.seek(0.5);
        ok(Math.abs(mix.fraction() - 0.5) < 1e-4, "seeks to the middle");
        mix.seek(1.0 - 1.5 * AbMixer.BLOCK / (double) mix.frames());
        boolean more = mix.next(out), last = mix.next(out);
        ok(more && !last && mix.position() == 0, "stops at the end and goes back to the start");

        // Output formats.
        Random rnd = new Random(1);
        byte[] bytes = new byte[6];
        AbPlayer.toBytes(new float[]{0.5f, -1.5f}, bytes, 3, rnd);
        int s0 = (bytes[0] & 255) | (bytes[1] & 255) << 8 | bytes[2] << 16, s1 = (bytes[3] & 255) | (bytes[4] & 255) << 8 | bytes[5] << 16;
        ok(s0 == 4194304 && s1 == -8388607, "24-bit output (" + s0 + ", " + s1 + ", clamped)");
        float[] quiet = new float[20000];
        for (int i = 0; i < quiet.length; i++) quiet[i] = 0.25f;
        byte[] b16 = new byte[quiet.length * 2];
        AbPlayer.toBytes(quiet, b16, 2, rnd);
        double mean = 0; int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (int i = 0; i < quiet.length; i++) { int v = (short) ((b16[2 * i] & 255) | b16[2 * i + 1] << 8); mean += v; lo = Math.min(lo, v); hi = Math.max(hi, v); }
        mean /= quiet.length;
        ok(Math.abs(mean - 0.25 * 32767) < 0.05 && hi - lo <= 2 && hi > lo, String.format(Locale.US, "16-bit output is dithered and unbiased (mean %.3f, spread %d)", mean, hi - lo));

        // Blind test.
        Blind blind = new Blind(new Random(5));
        blind.reset(n);
        int v = blind.start(2, true);
        List<Integer> plain = new ArrayList<>();
        for (int i = 0; i < n; i++) plain.add(i);
        ok(!blind.order.equals(plain) && blind.hidden() && v == blind.order.get(2), "blind test shuffles (never the usual order) and keeps the playing row");
        int picked = blind.pick(blind.row(v));
        ok(picked == v && blind.state == Blind.State.RESULTS && !blind.hidden(), "pick reveals the version that was playing");
        int row = blind.finish(v);
        ok(blind.order.equals(plain) && row == v && blind.state == Blind.State.OFF, "finish restores the usual order");
        File log = Blind.log(Files.createTempDirectory("blind").toFile(), "Check Song", "Classic Rock", "Warm", "Original, Clean, Warm, Punch");
        ok(Files.readString(log.toPath()).contains("picked Warm"), "blind pick saved to " + log.getName());

        // Waveform.
        float[] peaks = Overview.peaks(o.source, 800);
        Audio src = Wav.read(o.source);
        float max = 0, seen = 0;
        for (int i = 0; i < src.l.length; i++) max = Math.max(max, Math.max(Math.abs(src.l[i]), Math.abs(src.r[i])));
        for (float p : peaks) seen = Math.max(seen, p);
        ok(peaks.length == 800 && Math.abs(seen - max) < 1e-6, "waveform overview peak matches the file");

        mix.clear();
        return fails;
    }

    /** The whole song through the mixer, as one version. */
    private static Audio render(AbMixer mix, int version) throws Exception {
        int frames = (int) mix.frames();
        Audio a = new Audio(frames, mix.rate());
        float[] out = new float[AbMixer.BLOCK * 2];
        mix.seek(0); mix.select(version);
        mix.next(out); // settles any crossfade from the previous version
        mix.seek(0);
        int p = 0;
        boolean more = true;
        while (more) {
            more = mix.next(out);
            for (int i = 0; i < AbMixer.BLOCK && p < frames; i++, p++) { a.l[p] = out[2 * i]; a.r[p] = out[2 * i + 1]; }
        }
        return a;
    }
}
