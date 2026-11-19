package com.dennis.masterroom.desktop;

import com.dennis.masterroom.engine.Target;

import java.io.File;
import java.util.Locale;

/**
 * Command line, for checks and batch use:
 *   "Master Room.exe" --master song.wav [--style COUNTRY] [--level -14] [--out folder] [--commercial] [--no-cleanup] [--no-mp3]
 */
final class Cli {
    private Cli() {}

    static int run(String[] a) {
        MasterJob.Settings s = new MasterJob.Settings();
        for (int i = 0; i < a.length; i++) {
            switch (a[i]) {
                case "--master" -> s.song = new File(a[++i]);
                case "--style" -> s.style = Target.Style.valueOf(a[++i].toUpperCase(Locale.ROOT));
                case "--level" -> s.lufs = Double.parseDouble(a[++i]);
                case "--out" -> s.outRoot = new File(a[++i]);
                case "--commercial" -> s.commercial = true;
                case "--no-cleanup" -> s.cleanUp = false;
                case "--no-mp3" -> s.mp3 = false;
                default -> { }
            }
        }
        if (s.song == null || !s.song.isFile()) { System.err.println("No song: --master <file>"); return 2; }
        try {
            final int[] last = {-1};
            MasterJob.Outcome o = MasterJob.run(s, (step, p) -> {
                if (p / 10 != last[0]) { last[0] = p / 10; System.out.printf(Locale.US, "%3d%%  %s%n", p, step); }
            });
            System.out.printf(Locale.US, "IN %.2f LUFS TP %.2f dBTP%n", o.before.loudness.integrated, o.before.truePeakDb);
            for (MasterJob.Take t : o.takes)
                System.out.printf(Locale.US, "TAKE %s %.2f LUFS TP %.2f (ceiling %.1f) | %s | %s%n", t.label, t.lufs, t.truePeak, t.ceiling, t.verdict, t.streaming);
            System.out.println("OUT " + o.dir.getAbsolutePath());
            System.out.printf(Locale.US, "SECONDS %.1f%n", o.seconds);
            return 0;
        } catch (Exception e) {
            System.err.println("FAILED " + e);
            return 1;
        }
    }
}
