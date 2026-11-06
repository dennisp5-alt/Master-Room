import com.dennis.masterroom.engine.*;
import java.util.*;

/** Masters a 3:44 song on a tight memory limit, from one file or stems. Run with: java -Xmx300m -Xmn8m -XX:+UseSerialGC MemoryCheck -14 [stems] */
public final class MemoryCheck {
    public static void main(String[] a) throws Exception {
        int rate = 48000, n = 224 * rate;
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
            holder[0] = Stems.render(set, new double[]{1, 0, 0, -1}, new boolean[Stems.COUNT], true, dir, options.stemNotes, quiet);
            for (java.io.File f : files) f.delete();
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
}
