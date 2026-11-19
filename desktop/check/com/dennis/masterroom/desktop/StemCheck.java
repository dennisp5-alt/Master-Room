package com.dennis.masterroom.desktop;

import com.dennis.masterroom.engine.Audio;
import com.dennis.masterroom.engine.Engineer;
import com.dennis.masterroom.engine.Listen;
import com.dennis.masterroom.engine.Stems;
import com.dennis.masterroom.engine.Target;
import com.dennis.masterroom.engine.Wav;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Build 4 checks, on four synthetic stems:
 *  - WAVs and Stem Studio's ZIP are placed by the stem word in their names, with the song title;
 *  - the live stem player at flat gains is the engine's own stem sum, sample for sample; a mute takes effect after one eased block;
 *  - mastering from stems through the desktop gives byte-identical takes to the phone's sequence of engine calls
 *    (stems summed as the source, auto balance, stems treated and balanced, then the normal chain).
 */
public final class StemCheck {
    private StemCheck() {}

    private static int fails;
    private static void ok(boolean c, String m) { if (!c) fails++; System.out.println((c ? "PASS " : "FAIL ") + "stems: " + m); }

    /** @param zipOut where to leave a ZIP of the test stems for the packaged-app test, or null */
    public static int run(File zipOut) throws Exception {
        fails = 0;
        File dir = Files.createTempDirectory("stemcheck").toFile();
        File[] wavs = Demo.writeStems(dir, "Check Song", 20);
        File zip = new File(dir, "Check Song stems.zip");
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(zip))) {
            for (File f : wavs) { z.putNextEntry(new ZipEntry("Check Song/" + f.getName())); Files.copy(f.toPath(), z); z.closeEntry(); }
        }
        if (zipOut != null) Files.copy(zip.toPath(), zipOut.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        StemFiles.Loaded fromWavs = StemFiles.load(Arrays.asList(wavs[2], wavs[0], wavs[3], wavs[1]));
        boolean placed = fromWavs.count() == 4;
        for (int i = 0; i < 4; i++) placed &= fromWavs.set.files[i].equals(wavs[i]);
        ok(placed && fromWavs.title.equals("Check Song"), "WAVs placed by name in any order, title \"" + fromWavs.title + "\"");
        StemFiles.Loaded fromZip = StemFiles.load(List.of(zip));
        ok(fromZip.count() == 4 && fromZip.title.equals("Check Song") && fromZip.temp != null, "Stem Studio ZIP unpacked and placed, title \"" + fromZip.title + "\"");
        File unpacked = fromZip.temp;
        fromZip.discard();
        ok(!unpacked.exists(), "the unpacked ZIP is deleted when the stems are cleared");
        try { StemFiles.load(Arrays.asList(wavs[0], wavs[1], wavs[2])); ok(false, "a missing core stem is refused"); }
        catch (java.io.IOException e) { ok(e.getMessage().contains("Other"), "a missing core stem is refused: " + e.getMessage()); }

        // Live player at flat gains = the engine's stem sum.
        Stems.Set set = fromWavs.set;
        Audio sum = Stems.mix(set, null, null);
        StemMixer mix = new StemMixer(set);
        float[] out = new float[AbMixer.BLOCK * 2];
        double worst = 0;
        int p = 0;
        boolean more = true;
        while (more) {
            more = mix.next(out);
            for (int i = 0; i < AbMixer.BLOCK && p < set.frames; i++, p++)
                worst = Math.max(worst, Math.max(Math.abs(out[2 * i] - sum.l[p]), Math.abs(out[2 * i + 1] - sum.r[p])));
        }
        ok(p == set.frames && worst == 0, "live stem player at flat gains is the engine's stem sum sample for sample (worst " + worst + ")");
        // Mute the vocals: one eased block, then exactly the sum without them.
        boolean[] noVocal = new boolean[Stems.COUNT]; noVocal[Stems.VOCALS] = true;
        Audio without = Stems.mix(set, null, noVocal);
        float[] g = new float[Stems.COUNT]; Arrays.fill(g, 1f); g[Stems.VOCALS] = 0f;
        mix.seek(0.25);
        long at = mix.position();
        mix.setGains(g, null);
        mix.next(out);
        mix.next(out);
        double after = 0;
        for (int i = 0; i < AbMixer.BLOCK; i++) after = Math.max(after, Math.abs(out[2 * i] - without.l[(int) at + AbMixer.BLOCK + i]));
        ok(after < 1e-7, "a mute eases in over one block, then the vocals are gone exactly");
        mix.close();
        sum = null; without = null;

        // Mastering from stems: desktop job vs the phone's sequence of engine calls.
        Target.Style style = Target.Style.CLASSIC_ROCK;
        MasterJob.Settings s = new MasterJob.Settings();
        s.stems = set; s.stemTitle = fromWavs.title; s.style = style; s.lufs = -14; s.mp3 = false; s.cleanUp = true; s.stemAuto = true;
        s.outRoot = new File(dir, "desktop");
        MasterJob.Outcome app = MasterJob.run(s, (step, pc) -> { });
        File ref = new File(dir, "reference");
        ref.mkdirs();
        Audio in = Stems.mix(set, null, null);
        Listen asItCame = Listen.to(in);
        Engineer.Options options = new Engineer.Options();
        options.cleanUp = true;
        options.fromStems = true;
        Stems.Balance balance = Stems.decide(Stems.read(set), style);
        options.stemNotes.add("Auto balance: " + balance.words());
        in = Stems.render(set, balance.levelDb, balance.rideDb, new boolean[Stems.COUNT], true, ref, options.stemNotes, (step, pc) -> { });
        Audio[] holder = {in};
        in = null;
        Engineer.Session session = Engineer.prepare(holder, Target.builtIn(style, false), -14, options, (step, pc) -> { });
        boolean identical = app.takes.size() == Engineer.Take.values().length;
        int k = 0;
        for (Engineer.Take take : Engineer.Take.values()) {
            Engineer.Result r = Engineer.master(session, take.recipe(), -14, (step, pc) -> { });
            File[] files = Engineer.deliver(r, ref, "Check Song_" + take.recipe().label);
            r.master = null;
            MasterJob.Take t = app.takes.get(k++);
            boolean same24 = Arrays.equals(Files.readAllBytes(files[0].toPath()), Files.readAllBytes(t.files[0].toPath()));
            boolean same16 = Arrays.equals(Files.readAllBytes(files[1].toPath()), Files.readAllBytes(t.files[1].toPath()));
            ok(same24 && same16, String.format(Locale.US, "%s from stems: 24-bit and 16-bit takes byte-identical to the phone's engine sequence (%.2f LUFS, %.2f dBTP)", t.label, t.lufs, t.truePeak));
            identical &= same24 && same16;
        }
        session.release();
        ok(Math.abs(app.beforeLufs - asItCame.loudness.integrated) < 1e-9, String.format(Locale.US, "Original is the stems summed as they came (%.2f LUFS)", app.beforeLufs));
        ok(!app.stemNotes.isEmpty() && app.stemNotes.get(0).startsWith("Auto balance:") && app.sheet.contains("Auto balance:"),
            "the balance is written on the session sheet: " + (app.stemNotes.isEmpty() ? "" : app.stemNotes.get(0)));
        ok(app.settings.contains("from 4 stems, auto balance"), "settings line says it came from stems: " + app.settings);
        ok(app.dir.getParentFile().getName().equals("Check Song"), "the round is filed under the song title from the stems");
        StemFiles.deleteAll(dir);
        return fails;
    }
}
