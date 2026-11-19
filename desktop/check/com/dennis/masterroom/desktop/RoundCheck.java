package com.dennis.masterroom.desktop;

import com.dennis.masterroom.engine.Wav;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Build 6 checks: a round kept and brought back (numbers, settings, files), the round before kept beside a new
 * one of the same song, a master imported to compare (at another sample rate too), and files that have gone.
 */
public final class RoundCheck {
    private RoundCheck() {}

    private static int fails;
    private static void ok(boolean c, String m) { if (!c) fails++; System.out.println((c ? "PASS " : "FAIL ") + "rounds: " + m); }

    private static boolean same(double[] a, double[] b, double tol) {
        if (a == null || b == null) return a == b;
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) if (Math.abs(a[i] - b[i]) > tol) return false;
        return true;
    }

    public static int run(MasterJob.Outcome round) throws Exception {
        fails = 0;
        // Kept and brought back.
        RoundStore.save(round, List.of());
        RoundStore.Round back = RoundStore.load(round.dir);
        ok(back != null && back.outcome().takes.size() == round.takes.size() && back.extras().isEmpty(), "a round is kept and comes back with its " + round.takes.size() + " takes");
        boolean numbers = back != null;
        for (int i = 0; numbers && i < round.takes.size(); i++) {
            MasterJob.Take a = round.takes.get(i), b = back.outcome().takes.get(i);
            numbers = a.label.equals(b.label) && Math.abs(a.lufs - b.lufs) < 1e-5 && Math.abs(a.truePeak - b.truePeak) < 1e-5
                && Math.abs(a.ceiling - b.ceiling) < 1e-5 && same(a.mid, b.mid, 1e-3) && same(a.side, b.side, 1e-3)
                && a.files.length == b.files.length && a.files[0].equals(b.files[0]) && a.verdict.equals(b.verdict);
        }
        ok(numbers, "each take's name, loudness, peak, ceiling, tone, verdict and files are the same");
        ok(back != null && back.outcome().settings.equals(round.settings) && back.outcome().sourceKey.equals(round.sourceKey)
            && back.outcome().shortSettings.equals(round.shortSettings) && back.outcome().style == round.style
            && Math.abs(back.outcome().beforeLufs - round.beforeLufs) < 1e-5 && same(back.outcome().beforeMid, round.beforeMid, 1e-3)
            && same(back.outcome().targetMid, round.targetMid, 1e-3) && back.outcome().source.equals(round.source),
            "settings, the song, the before numbers and the target come back (\"" + round.shortSettings + "\")");

        // A second round of the same song keeps the first; another song does not.
        MasterJob.Outcome next = new MasterJob.Outcome();
        next.sourceKey = round.sourceKey;
        List<MasterJob.Take> kept = MainWindow.keptFrom(round, List.of(), next);
        boolean named = kept.size() == round.takes.size();
        for (int i = 0; named && i < kept.size(); i++)
            named = kept.get(i).kind.equals("earlier") && !kept.get(i).isTake() && kept.get(i).label.equals(round.takes.get(i).label + " · " + round.shortSettings)
                && kept.get(i).files[0].equals(round.takes.get(i).files[0]);
        ok(named, "re-mastering the same song keeps the round before, named with its settings (\"" + (kept.isEmpty() ? "" : kept.get(0).label) + "\")");
        MasterJob.Outcome other = new MasterJob.Outcome();
        other.sourceKey = round.sourceKey + ".other";
        ok(MainWindow.keptFrom(round, List.of(), other).isEmpty(), "another song starts clean");

        // A master made elsewhere, at the song's rate: measured as the takes are.
        MasterJob.Take take = round.takes.get(1);
        File elsewhere = Files.createTempDirectory("roundcheck").toFile();
        File same = new File(elsewhere, "Warm from elsewhere.wav");
        Files.copy(take.files[0].toPath(), same.toPath());
        MasterJob.Take in = Importer.take(same, round);
        ok(in.kind.equals("imported") && !in.isTake() && in.files.length == 1 && in.files[0].isFile() && in.files[0].getParentFile().equals(round.dir)
            && Math.abs(in.lufs - take.lufs) < 0.05 && Math.abs(in.truePeak - take.truePeak) < 0.05 && Double.isNaN(in.ceiling),
            String.format(java.util.Locale.US, "an imported master is measured like a take (%.2f LUFS vs %.2f) and kept in the round's folder", in.lufs, take.lufs));

        // At another sample rate (the 16-bit 44.1 kHz file): converted to the song's rate so it plays beside the takes.
        int songRate = Wav.header(round.source).rate, otherRate = Wav.header(take.files[1]).rate;
        MasterJob.Take converted = null;
        if (songRate != otherRate && Ffmpeg.available()) {
            File at = new File(elsewhere, "Warm 44k.wav");
            Files.copy(take.files[1].toPath(), at.toPath());
            converted = Importer.take(at, round);
            ok(Wav.header(converted.files[0]).rate == songRate && Math.abs(converted.lufs - take.lufs) < 0.3,
                String.format(java.util.Locale.US, "a master at %d Hz is converted to the song's %d Hz (%.2f LUFS vs %.2f)", otherRate, songRate, converted.lufs, take.lufs));
        } else {
            System.out.println("SKIP rounds: conversion of another sample rate (" + (songRate == otherRate ? "same rate" : "no FFmpeg") + ")");
        }

        // Kept with the earlier round and the import; imports carry into the next round.
        List<MasterJob.Take> extras = new ArrayList<>(kept);
        extras.add(in);
        if (converted != null) extras.add(converted);
        RoundStore.save(round, extras);
        back = RoundStore.load(round.dir);
        ok(back != null && back.outcome().takes.size() == round.takes.size() && back.extras().size() == extras.size()
            && back.extras().get(0).kind.equals("earlier") && back.extras().get(kept.size()).kind.equals("imported")
            && back.extras().get(kept.size()).label.equals(in.label),
            "the earlier takes and the import come back beside the round (" + extras.size() + " extras)");
        List<MasterJob.Take> carried = MainWindow.keptFrom(round, extras, next);
        ok(carried.size() == round.takes.size() + (extras.size() - kept.size()) && carried.get(carried.size() - 1).kind.equals("imported"),
            "the round after keeps this round's takes and the imports, not the round before that");

        // Files that have gone: an import deleted is left out; a round whose song copy has gone is not brought back.
        Files.delete(in.files[0].toPath());
        back = RoundStore.load(round.dir);
        ok(back != null && back.extras().size() == extras.size() - 1 && back.outcome().takes.size() == round.takes.size(),
            "a deleted import is left out, the rest still comes back");
        File gone = Files.createTempDirectory("roundgone").toFile();
        MasterJob.Outcome ghost = new MasterJob.Outcome();
        ghost.dir = gone; ghost.source = new File(gone, "source.wav"); ghost.takes.addAll(round.takes);
        ghost.settings = round.settings; ghost.sourceKey = round.sourceKey; ghost.shortSettings = round.shortSettings;
        RoundStore.save(ghost, List.of());
        ok(RoundStore.load(gone) == null && RoundStore.load(new File(gone, "nothing")) == null, "a round whose song copy has gone, or no round, opens nothing");

        // Leave the round as it was for the checks after.
        RoundStore.save(round, List.of());

        opensWhereLeft(round);
        return fails;
    }

    /** The window itself: stems balanced by hand and a round, closed, then the app opened again. */
    private static void opensWhereLeft(MasterJob.Outcome round) throws Exception {
        Look.load();
        MainWindow.PREFS.clear();
        MainWindow.PREFS.put("lastRound", round.dir.getAbsolutePath());
        File dir = Files.createTempDirectory("roundcheck-stems").toFile();
        List<File> four = List.of(Demo.writeStems(dir, "Porch", 6));
        MainWindow[] w = new MainWindow[2];
        javax.swing.SwingUtilities.invokeAndWait(() -> { w[0] = new MainWindow(); w[0].loadStems(four); });
        waitFor(() -> w[0].stems != null);
        double[][] left = new double[1][];
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            w[0].stemMute[1] = true;
            w[0].stemNudge(2, 1.5);
            left[0] = w[0].stemDb.clone();
        });
        // Opened again: a new window, as at start-up.
        javax.swing.SwingUtilities.invokeAndWait(() -> { w[1] = new MainWindow(); w[1].reopen(); });
        waitFor(() -> w[1].stems != null);
        boolean[] ok = new boolean[4];
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            MainWindow m = w[1];
            ok[0] = m.last != null && m.last.dir.equals(round.dir) && m.shown.size() == round.takes.size();
            ok[1] = m.stems.tracks.size() == 4 && m.stemManual && java.util.Arrays.equals(m.stemDb, left[0]);
            ok[2] = m.stemMute[1] && !m.stemMute[0] && !m.stemMute[2];
        });
        ok(ok[0], "the app opens on the last round, with its takes ready to play");
        ok(ok[1], String.format(java.util.Locale.US, "and the stems that were open, with the balance set by hand (Bass %+.1f dB)", left[0][2]));
        ok(ok[2], "a muted stem is still muted");
        // Stems gone from where they were: nothing is brought back, nothing breaks.
        for (File f : four) Files.delete(f.toPath());
        MainWindow[] m3 = new MainWindow[1];
        javax.swing.SwingUtilities.invokeAndWait(() -> { m3[0] = new MainWindow(); m3[0].restoreStems(); ok[3] = m3[0].stems == null; });
        ok(ok[3], "stems that have been moved or deleted are not brought back");
        MainWindow.PREFS.removeNode();
    }

    private static void waitFor(java.util.function.BooleanSupplier done) throws Exception {
        long until = System.currentTimeMillis() + 60_000;
        boolean[] d = new boolean[1];
        while (System.currentTimeMillis() < until) {
            javax.swing.SwingUtilities.invokeAndWait(() -> d[0] = done.getAsBoolean());
            if (d[0]) return;
            Thread.sleep(100);
        }
    }
}
