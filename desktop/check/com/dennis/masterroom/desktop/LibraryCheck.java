package com.dennis.masterroom.desktop;

import com.dennis.masterroom.engine.Audio;
import com.dennis.masterroom.engine.Listen;
import com.dennis.masterroom.engine.Release;
import com.dennis.masterroom.engine.Target;
import com.dennis.masterroom.engine.Wav;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;

/**
 * Build 5 checks: reference records, album match, Save take with release details, the Yours take, and a
 * round aimed at records. Uses its own data folder, never the real one.
 */
public final class LibraryCheck {
    private LibraryCheck() {}

    private static int fails;
    private static void ok(boolean c, String m) { if (!c) fails++; System.out.println((c ? "PASS " : "FAIL ") + "library: " + m); }

    public static int run(MasterJob.Outcome round) throws Exception {
        fails = 0;
        File dir = Files.createTempDirectory("librarycheck").toFile();
        String before = System.getProperty("masterroom.data");
        System.setProperty("masterroom.data", new File(dir, "data").getAbsolutePath());
        try {
            Target.Style style = Target.Style.COUNTRY_ROCK;
            ok(Library.toneShort(style).equals("Built-in curve") && Library.target(style, false).name.equals(Target.builtIn(style, false).name),
                "nothing kept yet: the style's built-in curve");

            // Two "records": the round's source and its Warm take, measured as the app measures records.
            Audio a = Wav.read(round.source), b = Wav.read(round.takes.get(1).files[0]);
            Library.addRecord(style, "Record One", Listen.to(a));
            Library.addRecord(style, "Record | Two", Listen.to(b));
            Target t = Library.target(style, false);
            ok(Library.records(style).size() == 2 && t.name.equals("2 reference records") && Double.isFinite(t.widthDb) && Double.isFinite(t.lowEndDb),
                "two records set the tone: \"" + t.name + "\", width and low end to aim at");
            ok(Library.shelfLines(style).get(1).startsWith("Record   Two|") && Library.toneShort(style).equals("2 records"), "stored in the phone's line format, names kept safe");
            ok(Library.records(Target.Style.METAL).isEmpty(), "records are kept per style");
            Library.removeRecord(style, 0);
            ok(Library.records(style).size() == 1 && Library.shelfLines(style).get(0).startsWith("Record   Two"), "a record can be removed");

            // Taste: the Yours take arrives with the eighth pick.
            for (int i = 0; i < 7; i++) Library.remember(style, "blind", i < 4 ? "Warm" : "Clean");
            ok(Library.yours(style) == null, "no Yours take before 8 picks");
            Library.remember(style, "saved", "Punch");
            ok(Library.yours(style) != null && Library.yours(style).label.equals("Yours"), "a Yours take from the eighth pick: " + Library.yours(style).about);

            // Album match and Save take.
            Library.startAlbum("Porch | Album");
            ok(Library.albumOn() && Library.albumName().equals("Porch   Album") && Double.isNaN(Library.albumLevel()), "album started, waiting for a saved take");
            Release.Info info = new Release.Info();
            info.title = "Check Song"; info.artist = "Dennis Test"; info.album = "Porch Album"; info.year = "2026"; info.track = "3";
            MasterJob.Take warm = round.takes.get(1);
            File[] saved = TakeSaver.save(warm, info, new File(dir, "out"), round.sheet, -14, style);
            boolean exist = true;
            for (File f : saved) exist &= f.isFile() && f.length() > 0;
            ok(exist && saved[0].getName().equals("Check Song_Warm_24bit.wav"), "saved: " + saved[0].getName() + ", 16-bit, MP3 320 and the session sheet");
            String tail24 = tail(saved[0]);
            ok(tail24.contains("INAM") && tail24.contains("Check Song") && tail24.contains("Dennis Test") && tail24.contains("Porch Album"), "the WAV carries the song's details (LIST INFO)");
            Audio orig = Wav.read(warm.files[0]), tagged = Wav.read(saved[0]);
            boolean same = orig.frames() == tagged.frames();
            for (int i = 0; same && i < orig.frames(); i++) same = orig.l[i] == tagged.l[i] && orig.r[i] == tagged.r[i];
            ok(same, "the tagged WAV is the take sample for sample");
            byte[] mp3 = Files.readAllBytes(saved[2].toPath());
            String head = new String(mp3, 0, Math.min(mp3.length, 4096), StandardCharsets.ISO_8859_1);
            ok(head.startsWith("ID3") && head.contains("Check Song") && head.contains("Dennis Test"), "the MP3 carries the tags");
            ok(Library.albumLines().size() == 2 && Library.albumLevel() == -14 && Library.albumRecords().size() == 1, "the saved take joined the album at -14 LUFS");
            Target at = Library.target(style, false);
            ok(at.name.equals("Album Porch   Album, 1 finished song"), "the album now sets the tone: \"" + at.name + "\"");
            ok(Library.taste().get(Library.taste().size() - 1).equals("COUNTRY_ROCK|saved|Warm"), "the save counts as a pick for the Yours take");

            // A round aimed at the album, with the Yours take and the album check on the sheet.
            File shortSong = new File(dir, "short.wav");
            Audio cut = new Audio(Math.min(a.frames(), 14 * a.rate), a.rate);
            System.arraycopy(a.l, 0, cut.l, 0, cut.frames()); System.arraycopy(a.r, 0, cut.r, 0, cut.frames());
            Wav.write(shortSong, cut, 24);
            MasterJob.Settings s = new MasterJob.Settings();
            s.song = shortSong; s.style = style; s.lufs = -14; s.mp3 = false; s.outRoot = new File(dir, "rounds");
            s.target = Library.target(style, false); s.yours = Library.yours(style); s.albumCheck = Library.albumOn();
            MasterJob.Outcome o = MasterJob.run(s, (step, p) -> { });
            boolean good = o.takes.size() == 4 && o.takes.get(3).label.equals("Yours");
            for (MasterJob.Take k : o.takes) good &= Math.abs(k.lufs + 14) < 1 && k.truePeak <= k.ceiling + 0.05;
            ok(good, String.format(Locale.US, "aimed at the album: four takes with Yours, %.2f / %.2f / %.2f / %.2f LUFS", o.takes.get(0).lufs, o.takes.get(1).lufs, o.takes.get(2).lufs, o.takes.get(3).lufs));
            ok(o.settings.contains("tone from Album Porch   Album") && o.targetName.startsWith("Album") && Double.isFinite(o.targetWidth), "settings line and sheet say what set the tone");
            ok(o.sheet.contains("Against Porch   Album:") && o.sheet.contains("Yours: lows"), "the sheet checks each take against the album (as on the phone)");
            Library.endAlbum();
            ok(!Library.albumOn() && Library.target(style, false).name.equals("1 reference records"), "ending the album hands the tone back to the records");
        } finally {
            if (before == null) System.clearProperty("masterroom.data"); else System.setProperty("masterroom.data", before);
            StemFiles.deleteAll(dir);
        }
        return fails;
    }

    private static String tail(File f) throws Exception {
        byte[] all = Files.readAllBytes(f.toPath());
        int from = Math.max(0, all.length - 2048);
        return new String(all, from, all.length - from, StandardCharsets.ISO_8859_1);
    }
}
