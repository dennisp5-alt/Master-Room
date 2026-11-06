import com.dennis.masterroom.engine.*;
import java.io.File;
import java.util.*;

/** Host runner: java Cli song.wav outDir lufs [style] */
public final class Cli {
    public static void main(String[] a) throws Exception {
        long t0 = System.nanoTime();
        File dir = new File(a[1]); dir.mkdirs();
        String stems = System.getProperty("stems");
        Engineer.Options o = new Engineer.Options();
        o.cleanUp = System.getProperty("cleanup") == null;
        Audio[] hand = {null};
        if (stems == null) hand[0] = Wav.read(new File(a[0]));
        else {
            // -Dstems=folder holding the four files, -Dlevels=v,d,b,o in dB, -Dmute=0,0,0,0
            File[] files = new File[Stems.COUNT];
            for (File f : new File(stems).listFiles()) { int p = Stems.partOf(f.getName()); if (p >= 0) files[p] = f; }
            double[] levels = new double[Stems.COUNT];
            boolean[] muted = new boolean[Stems.COUNT];
            String[] v = System.getProperty("levels", "0,0,0,0").split(","), m = System.getProperty("mute", "0,0,0,0").split(",");
            for (int i = 0; i < Stems.COUNT; i++) { levels[i] = Double.parseDouble(v[i]); muted[i] = m[i].equals("1"); }
            Stems.Set set = Stems.open(files);
            if (System.getProperty("flat") != null) {
                Wav.write(new File(dir, "flat.wav"), Stems.mix(set, null, null), 24);
            }
            o.fromStems = true;
            hand[0] = Stems.render(set, levels, muted, o.cleanUp, dir, o.stemNotes, (s, n) -> {});
        }
        double lufs = Double.parseDouble(a[2]);
        Target.Style style = a.length > 3 ? Target.Style.valueOf(a[3]) : Target.Style.COUNTRY;
        Engineer.Progress p = (s, n) -> {};
        Engineer.Session s = Engineer.prepare(hand, Target.builtIn(style, System.getProperty("commercial") != null), lufs, o, p);
        System.out.printf(Locale.US, "IN %.2f LUFS LRA %.1f TP %.2f crest %.1f cutoff %.0f lowSide %.1f%n",
                s.before.loudness.integrated, s.before.loudness.lra, s.before.truePeakDb, s.before.crestDb, s.before.cutoffHz, s.before.lowSideDb);
        for (String n : s.notes) System.out.println("  " + n);
        System.out.printf(Locale.US, "prepare %.1fs%n", (System.nanoTime() - t0) / 1e9);
        String only = System.getProperty("take");
        for (Engineer.Take take : Engineer.Take.values()) {
            if (only != null && !only.equals(take.name())) continue;
            Engineer.Result r = Engineer.master(s, take, lufs, p);
            File[] out = Engineer.deliver(r, dir, take.label.toLowerCase(Locale.US));
            if (System.getProperty("mp3") != null) {
                Release.Info i = new Release.Info(); i.title = "Test"; i.artist = "Dennis"; i.isrc = "AUABC2600001"; i.year = "2026"; i.track = "1";
                Release.mp3(out[1], new File(dir, take.label.toLowerCase(Locale.US) + ".mp3"), i);
            }
            System.out.printf(Locale.US, "TAKE %s %.2f LUFS LRA %.1f TP %.2f crest %.1f %.1fs%n", take.label, r.after.loudness.integrated, r.after.loudness.lra, r.after.truePeakDb, r.after.crestDb, r.seconds);
            for (Engineer.Point q : r.stages) System.out.printf(Locale.US, "   %-28s %7.2f LUFS LRA %4.1f TP %6.2f%n", q.stage, q.lufs, q.lra, q.truePeak);
            for (String n : r.notes) System.out.println("  " + n);
        }
    }
}
