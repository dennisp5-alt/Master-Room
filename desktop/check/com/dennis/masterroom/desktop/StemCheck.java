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
        StemFiles.Loaded three = StemFiles.load(Arrays.asList(wavs[0], wavs[1], wavs[2]));
        ok(three.silent[Stems.OTHER] && three.count() == 3 && three.notes.contains("No Other stem in this set, so silence stands in for it"),
            "a missing core stem is stood in for by silence, said plainly");
        three.discard();
        try { StemFiles.load(Arrays.asList(wavs[0], new File(dir, "Check Song - Take 3.wav"))); ok(false, "an unknown file name is refused"); }
        catch (java.io.IOException e) { ok(e.getMessage().startsWith("Not sure which stem"), "a file that names no stem is refused: " + e.getMessage()); }

        // Live player at flat gains = the engine's stem sum.
        Stems.Set set = fromWavs.set;
        Audio sum = Stems.mix(set, null, null);
        StemMixer mix = new StemMixer(fromWavs);
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
        float[] g = new float[fromWavs.tracks.size()]; Arrays.fill(g, 1f); g[0] = 0f;
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

        suno(dir, wavs);
        StemFiles.deleteAll(dir);
        return fails;
    }

    /**
     * A Suno-style download: numbered files, the full mix as "0 <title>.wav", extra stems (backing vocals,
     * percussion, FX, synth), 32-bit float, a MIDI file, and a ZIP whose local headers carry sizes of 0
     * (what stopped the first desktop build: "invalid entry size (expected 0 but got … bytes)").
     */
    private static void suno(File dir, File[] wavs) throws Exception {
        File sd = new File(dir, "suno");
        sd.mkdirs();
        Audio v = Wav.read(wavs[0]), d = Wav.read(wavs[1]), b = Wav.read(wavs[2]), o = Wav.read(wavs[3]);
        int n = v.frames(), rate = v.rate;
        Audio backing = new Audio(n, rate), perc = new Audio(n, rate), fx = new Audio(n, rate), synth = new Audio(n, rate), full = new Audio(n, rate);
        java.util.Random rnd = new java.util.Random(4);
        for (int i = 0; i < n; i++) {
            double t = i / (double) rate;
            backing.l[i] = 0.4f * v.l[Math.max(0, i - 2400)]; backing.r[i] = 0.4f * v.r[Math.max(0, i - 2400)];
            float shaker = (float) (((t * 4) % 1) < 0.05 ? 0.08 * rnd.nextGaussian() : 0);
            perc.l[i] = shaker; perc.r[i] = shaker * 0.8f;
            float swoosh = (float) (0.02 * rnd.nextGaussian() * Math.max(0, Math.sin(Math.PI * (t % 5) / 5)));
            fx.l[i] = swoosh; fx.r[i] = -swoosh;
            float lead = (float) (0.05 * Math.sin(2 * Math.PI * 523.3 * t) * (((t * 2) % 1) < 0.5 ? 1 : 0));
            synth.l[i] = lead * 0.7f; synth.r[i] = lead;
            full.l[i] = v.l[i] + backing.l[i] + d.l[i] + perc.l[i] + b.l[i] + o.l[i] + fx.l[i] + synth.l[i];
            full.r[i] = v.r[i] + backing.r[i] + d.r[i] + perc.r[i] + b.r[i] + o.r[i] + fx.r[i] + synth.r[i];
        }
        String[] names = {"0 Check Song.wav", "1 FX.wav", "2 Synth.wav", "3 Percussion.wav", "4 Bass.wav", "5 Drums.wav", "6 Backing_Vocals.wav", "7 Vocals.wav", "8 Sample.wav"};
        Audio[] audio = {full, fx, synth, perc, b, d, backing, v, o};
        List<File> files = new ArrayList<>();
        for (int i = 0; i < names.length; i++) { File f = new File(sd, names[i]); StemFiles.writeFloat(f, audio[i]); files.add(f); }
        File midi = new File(sd, "Check Song (Drums).mid");
        Files.write(midi.toPath(), new byte[]{'M', 'T', 'h', 'd', 0, 0, 0, 6, 0, 0, 0, 1, 0, 96});
        files.add(midi);
        File zip = new File(dir, "Check Song Suno.zip");
        zeroSizeZip(zip, files);
        try (java.util.zip.ZipInputStream z = new java.util.zip.ZipInputStream(new java.io.FileInputStream(zip))) {
            byte[] buf = new byte[1 << 16];
            while (z.getNextEntry() != null) while (z.read(buf) > 0) { }
            ok(false, "the test ZIP reproduces Suno's layout (a streaming reader should fail on it)");
        } catch (java.util.zip.ZipException e) {
            ok(e.getMessage().startsWith("invalid entry size"), "the test ZIP reproduces the error from Suno's ZIP: " + e.getMessage());
        }

        StemFiles.Loaded l = StemFiles.load(List.of(zip));
        ok(l.title.equals("Check Song"), "title from Suno's full mix file: \"" + l.title + "\"");
        ok(l.notes.get(0).startsWith("Left out the full mix (0 Check Song.wav)"), "the full mix is left out: " + l.notes.get(0));
        ok(l.count() == 4 && "Backing Vocals + Vocals".equals(l.labels[Stems.VOCALS]) && "Percussion + Drums".equals(l.labels[Stems.DRUMS])
            && "FX + Synth + Sample".equals(l.labels[Stems.OTHER]) && "Bass".equals(l.labels[Stems.BASS]),
            "Suno's stems placed: Vocals = " + l.labels[Stems.VOCALS] + ", Drums = " + l.labels[Stems.DRUMS] + ", Other = " + l.labels[Stems.OTHER]);
        Audio vocals = Wav.read(l.set.files[Stems.VOCALS]);
        double worst = 0;
        for (int i = 0; i < n; i++) worst = Math.max(worst, Math.abs(vocals.l[i] - (backing.l[i] + v.l[i])) + Math.abs(vocals.r[i] - (backing.r[i] + v.r[i])));
        ok(worst == 0, "combined stems are the files added sample for sample (float, worst " + worst + ")");
        Audio sum = Stems.mix(l.set, null, null);
        double vsFull = 0;
        for (int i = 0; i < n; i++) vsFull = Math.max(vsFull, Math.abs(sum.l[i] - full.l[i]));
        ok(vsFull < 1e-5, String.format(Locale.US, "the stems add up to Suno's full mix (worst %.1e), so nothing was lost or doubled", vsFull));
        l.discard();

        // Suno's other layout (Dennis's real download): no full mix, "0 Lead Vocals.wav" first, eleven stems.
        File porch = new File(dir, "porch");
        porch.mkdirs();
        String[] pn = {"0 Lead Vocals.wav", "1 Backing Vocals.wav", "2 Drums.wav", "3 Bass.wav", "4 Guitar.wav", "5 Keyboard.wav",
            "6 Percussion.wav", "7 Strings.wav", "8 Synth.wav", "9 Brass.wav", "10 Woodwinds.wav"};
        Audio[] pa = {v, backing, d, b, o, synth, perc, fx, synth, fx, o};
        List<File> pf = new ArrayList<>();
        // 16-bit like Suno's files (Wav.write), so single stems stay 16-bit and combined ones become float.
        for (int i = 0; i < pn.length; i++) { File f = new File(porch, pn[i]); Wav.write(f, pa[i], 16); pf.add(f); pa[i] = Wav.read(f); }
        File pz = new File(dir, "The Porch Light's On for Me Stems.zip");
        zeroSizeZip(pz, pf);
        StemFiles.Loaded pl = StemFiles.load(List.of(pz));
        ok(pl.title.equals("The Porch Light's On for Me"), "title from the ZIP's name when there is no full mix: \"" + pl.title + "\"");
        ok(pl.notes.stream().noneMatch(x -> x.contains("full mix")) && "Lead Vocals + Backing Vocals".equals(pl.labels[Stems.VOCALS]),
            "\"0 Lead Vocals.wav\" is kept as the lead vocal, not taken for a full mix: Vocals = " + pl.labels[Stems.VOCALS]);
        ok(pl.count() == 6 && "Drums + Percussion".equals(pl.labels[Stems.DRUMS]) && "Strings + Synth + Brass + Woodwinds".equals(pl.labels[Stems.OTHER])
            && "Keyboard".equals(pl.labels[Stems.PIANO]) && "Guitar".equals(pl.labels[Stems.GUITAR]) && "Bass".equals(pl.labels[Stems.BASS]),
            "all eleven placed: Drums = " + pl.labels[Stems.DRUMS] + ", Other = " + pl.labels[Stems.OTHER] + ", Piano = " + pl.labels[Stems.PIANO]);
        Audio all = Stems.mix(pl.set, null, null);
        double vsAll = 0;
        for (int i = 0; i < n; i++) {
            double want = 0;
            for (Audio x : pa) want += x.l[i];
            vsAll = Math.max(vsAll, Math.abs(all.l[i] - want));
        }
        ok(vsAll < 1e-5, String.format(Locale.US, "every one of the eleven files is in the mix once (worst %.1e)", vsAll));
        StemMixer live = new StemMixer(pl);
        float[] blk = new float[AbMixer.BLOCK * 2];
        double liveWorst = 0;
        int q = 0;
        boolean go = true;
        while (go) {
            go = live.next(blk);
            for (int i = 0; i < AbMixer.BLOCK && q < pl.set.frames; i++, q++) liveWorst = Math.max(liveWorst, Math.abs(blk[2 * i] - all.l[q]) + Math.abs(blk[2 * i + 1] - all.r[q]));
        }
        live.close();
        ok(q == pl.set.frames && liveWorst < 1e-6, String.format(Locale.US, "the live stem player plays all eleven files, each on its own (worst %.1e)", liveWorst));
        ok(pl.tracks.size() == 11 && pl.tracks.get(0).label.equals("Lead Vocals") && pl.tracks.get(1).label.equals("Backing Vocals"),
            "eleven files, eleven rows, in stem order: " + pl.tracks.get(0).label + ", " + pl.tracks.get(1).label + ", " + pl.tracks.get(2).label + "…");

        // Set by hand, file by file: Brass down 6 dB, Woodwinds muted, both Drums files muted.
        MasterJob.Settings hand = new MasterJob.Settings();
        hand.use(pl); hand.stemAuto = false;
        hand.trackDb = new double[11]; hand.trackMute = new boolean[11];
        int brass = -1, wood = -1;
        for (int t = 0; t < 11; t++) {
            String lab = pl.tracks.get(t).label;
            if (lab.equals("Brass")) { hand.trackDb[t] = -6; brass = t; }
            if (lab.equals("Woodwinds")) { hand.trackMute[t] = true; wood = t; }
            if (pl.tracks.get(t).part == Stems.DRUMS) hand.trackMute[t] = true;
        }
        File work = new File(dir, "work");
        work.mkdirs();
        MasterJob.Parts parts = MasterJob.parts(hand, work);
        Audio other = Wav.read(parts.set.files[Stems.OTHER]);
        float gb = (float) Math.pow(10, -6 / 20.0);
        double otherWorst = 0;
        for (int i = 0; i < n; i++) {
            float want = 0f + pa[7].l[i] + pa[8].l[i] + pa[9].l[i] * gb;
            otherWorst = Math.max(otherWorst, Math.abs(other.l[i] - want));
        }
        ok(brass >= 0 && wood >= 0 && otherWorst < 1e-6 && parts.levels[Stems.OTHER] == 0 && !parts.mute[Stems.OTHER],
            String.format(Locale.US, "by hand: Other = Strings + Synth + Brass at -6 dB, Woodwinds left out (worst %.1e)", otherWorst));
        ok(parts.mute[Stems.DRUMS] && !parts.mute[Stems.VOCALS] && parts.set.files[Stems.VOCALS].equals(pl.set.files[Stems.VOCALS]),
            "both drum files muted mutes the Drums stem; untouched stems stay as loaded");
        ok(parts.note != null && parts.note.contains("Brass -6.0 dB") && parts.note.contains("Woodwinds muted"), "the sheet lists the files set one by one: " + parts.note);
        for (File f : parts.made) f.delete();
        pl.discard();
        StemFiles.Loaded folder = StemFiles.load(pf);
        ok(folder.title.equals("porch") && folder.count() == 6, "picked from an unzipped folder: the folder names it (\"" + folder.title + "\")");
        folder.discard();

        // An instrumental-free-of-bass set: silence stands in, gets no level, and the round still masters.
        List<File> noBass = new ArrayList<>(files);
        noBass.removeIf(f -> f.getName().startsWith("4 "));
        File zip2 = new File(dir, "Check Song no bass.zip");
        zeroSizeZip(zip2, noBass);
        StemFiles.Loaded nb = StemFiles.load(List.of(zip2));
        ok(nb.silent[Stems.BASS] && nb.count() == 3 && nb.notes.stream().anyMatch(x -> x.startsWith("No Bass stem")), "a set with no bass: silence stands in, said plainly");
        Stems.Balance bal = MasterJob.balanced(Stems.read(nb.set), Target.Style.POP_ROCK, nb.silent);
        ok(bal.levelDb[Stems.BASS] == 0 && !bal.words().contains("Bass"), "the silent stand-in is never given a level: " + bal.words());
        MasterJob.Settings s = new MasterJob.Settings();
        s.use(nb); s.style = Target.Style.POP_ROCK; s.lufs = -14; s.mp3 = false; s.stemAuto = true;
        s.outRoot = new File(dir, "suno-rounds");
        MasterJob.Outcome o2 = MasterJob.run(s, (step, pc) -> { });
        boolean good = o2.takes.size() == 3;
        for (MasterJob.Take t : o2.takes) good &= Math.abs(t.lufs + 14) < 1 && t.truePeak <= t.ceiling + 0.05;
        ok(good, String.format(Locale.US, "Suno stems master: %.2f / %.2f / %.2f LUFS, ceilings held", o2.takes.get(0).lufs, o2.takes.get(1).lufs, o2.takes.get(2).lufs));
        ok(o2.sheet.contains("Stems: Left out the full mix") && o2.sheet.contains("Stems: Vocals = Backing Vocals + Vocals") && o2.sheet.contains("silence stands in"),
            "the session sheet says how the set was made");
        nb.discard();
    }

    /** A ZIP like Suno's: deflated entries whose local headers carry sizes of 0; the true sizes are only in the central directory. */
    private static void zeroSizeZip(File zip, List<File> files) throws Exception {
        java.io.ByteArrayOutputStream central = new java.io.ByteArrayOutputStream();
        try (java.io.OutputStream out = new java.io.BufferedOutputStream(new FileOutputStream(zip))) {
            long offset = 0;
            for (File f : files) {
                byte[] raw = Files.readAllBytes(f.toPath());
                java.util.zip.CRC32 crc = new java.util.zip.CRC32(); crc.update(raw);
                java.util.zip.Deflater def = new java.util.zip.Deflater(java.util.zip.Deflater.DEFAULT_COMPRESSION, true);
                def.setInput(raw); def.finish();
                java.io.ByteArrayOutputStream packed = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[1 << 16];
                while (!def.finished()) packed.write(buf, 0, def.deflate(buf));
                def.end();
                byte[] data = packed.toByteArray(), name = f.getName().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                java.nio.ByteBuffer lh = java.nio.ByteBuffer.allocate(30 + name.length).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                lh.putInt(0x04034b50).putShort((short) 20).putShort((short) 0).putShort((short) 8).putShort((short) 0).putShort((short) 0x21)
                  .putInt((int) crc.getValue()).putInt(0).putInt(0).putShort((short) name.length).putShort((short) 0).put(name);
                out.write(lh.array()); out.write(data);
                java.nio.ByteBuffer ch = java.nio.ByteBuffer.allocate(46 + name.length).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                ch.putInt(0x02014b50).putShort((short) 20).putShort((short) 20).putShort((short) 0).putShort((short) 8).putShort((short) 0).putShort((short) 0x21)
                  .putInt((int) crc.getValue()).putInt(data.length).putInt(raw.length).putShort((short) name.length).putShort((short) 0).putShort((short) 0)
                  .putShort((short) 0).putShort((short) 0).putInt(0).putInt((int) offset).put(name);
                central.write(ch.array());
                offset += lh.capacity() + data.length;
            }
            byte[] cd = central.toByteArray();
            out.write(cd);
            java.nio.ByteBuffer end = java.nio.ByteBuffer.allocate(22).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            end.putInt(0x06054b50).putShort((short) 0).putShort((short) 0).putShort((short) files.size()).putShort((short) files.size())
               .putInt(cd.length).putInt((int) offset).putShort((short) 0);
            out.write(end.array());
        }
    }
}
