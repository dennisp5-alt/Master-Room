package com.dennis.masterroom.desktop;

import com.dennis.masterroom.engine.Target;
import com.dennis.masterroom.engine.VocalTune;

import java.io.File;
import java.util.Locale;

/**
 * Command line, for checks and batch use:
 *   "Master Room CLI.exe" --master song.wav [--style COUNTRY] [--level -14] [--out folder] [--commercial] [--no-cleanup] [--no-mp3]
 *   "Master Room CLI.exe" --master --stems vocals.wav drums.wav bass.wav other.wav [same options] [--vocal-tune natural]   (or --stems stems.zip)
 *   "Master Room CLI.exe" --tune vocal.wav [--vocal-tune natural|firm|effect] [--out folder]   (a vocal on its own)
 * Stems use the auto balance, as the phone does unless adjusted by hand.
 */
final class Cli {
    private Cli() {}

    static int run(String[] a) {
        MasterJob.Settings s = new MasterJob.Settings();
        java.util.List<File> stems = new java.util.ArrayList<>();
        File tuneOnly = null;
        for (int i = 0; i < a.length; i++) {
            switch (a[i]) {
                case "--master" -> { if (i + 1 < a.length && !a[i + 1].startsWith("--")) s.song = new File(a[++i]); }
                case "--stems" -> { while (i + 1 < a.length && !a[i + 1].startsWith("--")) stems.add(new File(a[++i])); }
                case "--style" -> s.style = Target.Style.valueOf(a[++i].toUpperCase(Locale.ROOT));
                case "--level" -> s.lufs = Double.parseDouble(a[++i]);
                case "--out" -> s.outRoot = new File(a[++i]);
                case "--commercial" -> s.commercial = true;
                case "--no-cleanup" -> s.cleanUp = false;
                case "--no-mp3" -> s.mp3 = false;
                case "--vocal-tune" -> s.vocalTune = VocalTune.Mode.parse(a[++i]);
                case "--tune" -> tuneOnly = new File(a[++i]);
                default -> { }
            }
        }
        if (tuneOnly != null) {
            try {
                VocalTuneTool.Done d = VocalTuneTool.run(tuneOnly, s.vocalTune == VocalTune.Mode.OFF ? VocalTune.Mode.NATURAL : s.vocalTune, s.outRoot);
                System.out.println("TUNE " + d.result.words());
                System.out.println("TUNED " + (d.tuned == null ? "none" : d.tuned.getAbsolutePath()));
                System.out.println("OUT " + d.folder.getAbsolutePath());
                return 0;
            } catch (Exception e) { System.err.println("FAILED " + e); return 1; }
        }
        StemFiles.Loaded loaded = null;
        if (!stems.isEmpty()) {
            try {
                loaded = StemFiles.load(stems);
                s.use(loaded); s.stemAuto = true;
                System.out.println("STEMS " + loaded.count() + " " + loaded.title);
                for (String note : loaded.notes) System.out.println("PREP " + note);
            } catch (Exception e) { System.err.println("FAILED " + e.getMessage()); return 2; }
        } else if (s.song == null || !s.song.isFile()) { System.err.println("No song: --master <file>, or --master --stems <files or zip>"); return 2; }
        try {
            final int[] last = {-1};
            MasterJob.Outcome o = MasterJob.run(s, (step, p) -> {
                if (p / 10 != last[0]) { last[0] = p / 10; System.out.printf(Locale.US, "%3d%%  %s%n", p, step); }
            });
            System.out.printf(Locale.US, "IN %.2f LUFS TP %.2f dBTP%n", o.before.loudness.integrated, o.before.truePeakDb);
            for (MasterJob.Take t : o.takes)
                System.out.printf(Locale.US, "TAKE %s %.2f LUFS TP %.2f (ceiling %.1f) | %s | %s%n", t.label, t.lufs, t.truePeak, t.ceiling, t.verdict, t.streaming);
            System.out.println("OUT " + o.dir.getAbsolutePath());
            for (String note : o.stemNotes) System.out.println("STEM " + note);
            System.out.printf(Locale.US, "SECONDS %.1f%n", o.seconds);
            return 0;
        } catch (Exception e) {
            System.err.println("FAILED " + e);
            return 1;
        } finally {
            if (loaded != null) loaded.discard();
        }
    }
}
