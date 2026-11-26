import com.dennis.masterroom.engine.*;
import java.util.*;

/**
 * Masters a 3:44 song on a tight memory limit, from one file or stems (with Vocal Tune on), or tunes a
 * 3:44 sung vocal the way the phone does. Run with: java -Xmx300m -Xmn8m -XX:+UseSerialGC MemoryCheck -14 [stems|tune]
 */
public final class MemoryCheck {
    public static void main(String[] a) throws Exception {
        int rate = 48000, n = 224 * rate;
        if (a.length > 1 && a[1].equals("tune")) { tune(rate, n); return; }
        Audio song = new Audio(n, rate);
        Random r = new Random(5);
        double lowL = 0, lowR = 0;
        for (int i = 0; i < n; i++) {
            double big = (i / (24 * rate)) % 2 == 1 ? 2.0 : 1.0, beat = (i % (rate / 2)) / (double) (rate / 2);
            double kick = 0.5 * Math.exp(-beat * 12) * Math.sin(2 * Math.PI * 60 * i / rate);
            lowL += 0.05 * (r.nextGaussian() - lowL);
            lowR += 0.05 * (r.nextGaussian() - lowR);
            song.l[i] = (float) (big * (0.25 * lowL + 0.02 * r.nextGaussian()) + kick);
            song.r[i] = (float) (big * (0.25 * lowR + 0.02 * r.nextGaussian()) + kick);
        }
        Engineer.Progress quiet = (s, p) -> {};
        Engineer.Options options = new Engineer.Options();
        Audio[] holder = {song};
        if (a.length > 1) {
            for (int i = 0; i < n; i++) { song.l[i] *= 0.25f; song.r[i] *= 0.25f; }
            java.io.File dir = java.nio.file.Files.createTempDirectory("stems").toFile();
            java.io.File[] files = new java.io.File[Stems.COUNT];
            for (int p = 0; p < Stems.COUNT; p++) {
                files[p] = new java.io.File(dir, Stems.NAMES[p] + ".wav");
                Wav.write(files[p], song, 24);
            }
            holder[0] = null;
            song = null;
            Stems.Set set = Stems.open(files);
            Audio flat = Stems.mix(set, null, null);
            Listen.to(flat);
            flat = null;
            options.fromStems = true;
            Stems.Balance b = Stems.decide(Stems.read(set), Target.Style.ROCK);
            holder[0] = Stems.render(set, b.levelDb, b.rideDb, new boolean[Stems.COUNT], true, VocalTune.Mode.NATURAL, dir, options.stemNotes, quiet);
            for (java.io.File f : files) if (f != null) f.delete();
            dir.delete();
        }
        song = null;
        double lufs = Double.parseDouble(a[0]);
        Engineer.Session s = Engineer.prepare(holder, Target.builtIn(Target.Style.ROCK, true), lufs, options, quiet);
        for (Engineer.Take t : Engineer.Take.values()) {
            Engineer.Result out = Engineer.master(s, t, lufs, quiet);
            System.out.printf(Locale.US, "%s %.2f LUFS: %s%n", t.label, out.after.loudness.integrated, out.verdict);
            out.master = null;
        }
        s.release();
        System.out.println("MEMORY_CHECK_PASS");
    }

    /** A 3:44 sung line, a little out of tune, tuned in place and written out as float WAV, as the phone does. */
    static void tune(int rate, int n) throws Exception {
        Audio v = new Audio(n, rate);
        Random r = new Random(7);
        int[] scale = {57, 60, 62, 64, 67, 69};
        double phase = 0, midi = 60;
        for (int i = 0; i < n; i++) {
            int note = i / (rate * 9 / 10);
            int inNote = i % (rate * 9 / 10);
            if (inNote == 0) midi = scale[r.nextInt(scale.length)] + (r.nextDouble() - 0.5) * 0.7;
            boolean gap = inNote > rate * 3 / 4;
            double t = inNote / (double) rate;
            double f0 = 440 * Math.pow(2, (midi + 0.3 * Math.sin(2 * Math.PI * 5.5 * t) * Math.min(1, t / 0.3) - 69) / 12);
            phase += 2 * Math.PI * f0 / rate;
            double s = 0;
            for (int h = 1; h <= 8; h++) s += Math.sin(h * phase) / h;
            float y = gap ? (float) (0.002 * r.nextGaussian()) : (float) (0.1 * s * Math.min(1, t / 0.04));
            v.l[i] = y; v.r[i] = y;
        }
        VocalTune.Result t = VocalTune.tune(v, VocalTune.Mode.NATURAL, true);
        System.out.println(t.words());
        if (!t.changed || t.audio != v) throw new IllegalStateException("the vocal should have been tuned in place");
        java.io.File f = java.io.File.createTempFile("tuned", ".wav");
        Wav.writeFloat(f, t.audio);
        f.delete();
        System.out.println("MEMORY_CHECK_PASS");
    }
}
