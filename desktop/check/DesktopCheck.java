import com.dennis.masterroom.desktop.MasterJob;
import com.dennis.masterroom.engine.*;

import java.io.File;
import java.nio.file.Files;
import java.util.*;

/**
 * Checks for the Windows app (run in CI on Windows, and anywhere with Java):
 *  1. Parity: the desktop app's mastering path writes byte-identical takes to the engine called
 *     directly, as the phone and tools/Cli.java call it. The window adds no processing.
 *  2. Each take meets its loudness and true-peak ceiling.
 *  3. Inputs: MP3 (engine decoder) and, when FFmpeg is present, FLAC (lossless round trip).
 *  4. The A/B player: level matching, crossfades, playback exactness, blind test (PlayerCheck).
 *  6. Library: records, album match, Save take with tags, the Yours take, a round aimed at an album (LibraryCheck).
 *  5. Stems: placing by name, the live stem player, mastering from stems = the phone's engine sequence (StemCheck).
 * Usage: java -cp <classes>:desktop/check DesktopCheck [seconds] [copy-of-test-song.wav]
 */
public final class DesktopCheck {
    static int fails = 0;
    static void ok(boolean c, String m) { if (!c) fails++; System.out.println((c ? "PASS " : "FAIL ") + m); }

    public static void main(String[] a) throws Exception {
        // The checks keep the app's places and last session in their own preferences, never the real ones.
        System.setProperty("masterroom.prefs", "/com/dennis/masterroom/check");
        int seconds = a.length > 0 ? Integer.parseInt(a[0]) : 40, rate = 48000, n = seconds * rate;
        File work = Files.createTempDirectory("desktopcheck").toFile();
        Audio song = new Audio(n, rate);
        Random r = new Random(7);
        double lowL = 0, lowR = 0;
        for (int i = 0; i < n; i++) {
            double big = (i / (8 * rate)) % 2 == 1 ? 1.8 : 1.0, beat = (i % (rate / 2)) / (double) (rate / 2);
            double kick = 0.4 * Math.exp(-beat * 12) * Math.sin(2 * Math.PI * 60 * i / rate);
            double tone = 0.05 * Math.sin(2 * Math.PI * 440 * i / rate) + 0.03 * Math.sin(2 * Math.PI * 3300 * i / rate);
            lowL += 0.05 * (r.nextGaussian() - lowL);
            lowR += 0.05 * (r.nextGaussian() - lowR);
            song.l[i] = (float) (big * (0.18 * lowL + 0.015 * r.nextGaussian() + tone) + kick);
            song.r[i] = (float) (big * (0.18 * lowR + 0.015 * r.nextGaussian() + tone) + kick);
        }
        File wav = new File(work, "Check Song.wav");
        Wav.write(wav, song, 24);
        if (a.length > 1) Files.copy(wav.toPath(), new File(a[1]).toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING); // for the packaged-app test

        // 1. The desktop app's path.
        MasterJob.Settings s = new MasterJob.Settings();
        s.song = wav; s.style = Target.Style.ROCK; s.lufs = -14; s.commercial = false; s.cleanUp = true; s.mp3 = true;
        s.outRoot = new File(work, "app");
        MasterJob.Outcome app = MasterJob.run(s, (step, p) -> {});

        // 2. The engine called directly, exactly as tools/Cli.java and the phone call it.
        File ref = new File(work, "reference"); ref.mkdirs();
        Engineer.Options o = new Engineer.Options(); o.cleanUp = true;
        // The song as first opened, exactly as the phone masters it on a first round (not the re-saved source.wav:
        // a second 24-bit save moves some samples by one step, about -138 dB).
        Audio[] hand = {Wav.read(wav)};
        Engineer.Session session = Engineer.prepare(hand, Target.builtIn(Target.Style.ROCK, false), -14, o, (x, y) -> {});
        int t = 0;
        for (Engineer.Take take : Engineer.Take.values()) {
            Engineer.Result res = Engineer.master(session, take.recipe(), -14, (x, y) -> {});
            File[] files = Engineer.deliver(res, ref, "Check Song_" + take.label);
            MasterJob.Take mine = app.takes.get(t++);
            ok(mine.label.equals(take.label), "take order " + take.label);
            for (int f = 0; f < 2; f++)
                ok(Arrays.equals(Files.readAllBytes(files[f].toPath()), Files.readAllBytes(mine.files[f].toPath())), "identical to the engine: " + mine.files[f].getName());
            ok(mine.files.length == 3 && mine.files[2].length() > 10_000, "MP3 written: " + (mine.files.length > 2 ? mine.files[2].getName() : "missing"));
            ok(Math.abs(mine.lufs - (-14)) <= 1.0, String.format(Locale.US, "%s loudness %.2f LUFS (asked -14)", take.label, mine.lufs));
            ok(mine.truePeak <= mine.ceiling + 0.05, String.format(Locale.US, "%s true peak %.2f dBTP within ceiling %.1f", take.label, mine.truePeak, mine.ceiling));
            res.master = null;
        }
        session.release();
        ok(new File(app.dir, "session sheet.txt").length() > 200, "session sheet written");

        // 3. Inputs.
        File cd = app.takes.get(0).files[1];
        Audio fromWav = Wav.read(cd);
        Audio fromMp3 = MasterJob.open(app.takes.get(0).files[2]);
        double wavLufs = Loudness.measure(fromWav).integrated, mp3Lufs = Loudness.measure(fromMp3).integrated;
        ok(fromMp3.l.length > 0.95 * fromWav.l.length && Math.abs(wavLufs - mp3Lufs) < 0.5,
            String.format(Locale.US, "MP3 opens with the engine decoder (%.2f vs %.2f LUFS)", mp3Lufs, wavLufs));
        String ffmpeg = com.dennis.masterroom.desktop.Ffmpeg.find();
        if (ffmpeg == null) System.out.println("SKIP FLAC: FFmpeg not found");
        else {
            File flac = new File(work, "check.flac");
            Process p = new ProcessBuilder(ffmpeg, "-hide_banner", "-v", "error", "-y", "-i", wav.getAbsolutePath(), "-c:a", "flac", flac.getAbsolutePath()).inheritIO().start();
            ok(p.waitFor() == 0, "FFmpeg wrote FLAC");
            Audio back = MasterJob.open(flac), orig = Wav.read(wav);
            double worst = 0;
            for (int i = 0; i < Math.min(back.l.length, orig.l.length); i++) worst = Math.max(worst, Math.max(Math.abs(back.l[i] - orig.l[i]), Math.abs(back.r[i] - orig.r[i])));
            ok(back.l.length == orig.l.length && back.rate == orig.rate && worst < 1e-6, String.format(Locale.US, "FLAC opens losslessly (worst difference %.2e)", worst));
            ok(app.takes.get(0).streaming.contains("AAC 256"), "AAC streaming check ran: " + app.takes.get(0).streaming);
        }
        // Where rounds go by default: the Documents folder Windows reports (it can be moved).
        ok(MasterJob.documents().isDirectory() && MasterJob.defaultOutRoot().getParentFile().equals(MasterJob.documents()),
            "rounds go to " + MasterJob.defaultOutRoot());

        // 4. Build 2: the comparison player on this round.
        fails += com.dennis.masterroom.desktop.PlayerCheck.run(app);

        // 6. Build 5: reference records, album match, Save take, the Yours take.
        fails += com.dennis.masterroom.desktop.LibraryCheck.run(app);

        // 7. Build 6: rounds kept and brought back, the round before kept, a master imported to compare.
        fails += com.dennis.masterroom.desktop.RoundCheck.run(app);

        // 5. Build 4: stems (placing, live player, mastering parity with the phone's sequence).
        fails += com.dennis.masterroom.desktop.StemCheck.run(a.length > 2 ? new File(a[2]) : null);

        System.out.printf(Locale.US, "Mastered %d s of audio in %.1f s%n", seconds, app.seconds);
        if (fails > 0) { System.out.println(fails + " desktop check(s) failed"); System.exit(1); }
        System.out.println("DESKTOP_CHECK_PASS");
    }
}
