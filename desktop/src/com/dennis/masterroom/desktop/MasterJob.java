package com.dennis.masterroom.desktop;

import com.dennis.masterroom.engine.Audio;
import com.dennis.masterroom.engine.DesktopAccess;
import com.dennis.masterroom.engine.Engineer;
import com.dennis.masterroom.engine.Listen;
import com.dennis.masterroom.engine.Release;
import com.dennis.masterroom.engine.Stems;
import com.dennis.masterroom.engine.Streaming;
import com.dennis.masterroom.engine.Target;
import com.dennis.masterroom.engine.Wav;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One mastering round on the laptop: the same engine calls, in the same order, as the phone app's
 * MainActivity.master(), for a song or for stems. Headless, so the window, the command line and the
 * checks all run exactly this.
 */
public final class MasterJob {
    public static final String[] READS = {"wav", "mp3", "flac", "m4a", "aac", "ogg", "opus", "aif", "aiff", "wma"};

    public static final class Settings {
        public File song;
        public Target.Style style = Target.Style.COUNTRY;
        public double lufs = -14;
        public boolean commercial = false, cleanUp = true, mp3 = true;
        /** Where rounds go: <outRoot>\<song>\<date time>\ */
        public File outRoot = defaultOutRoot();
        /** Stems instead of a song (build 4). The phone's choices: the app's own balance, or levels and mutes set by hand. */
        public Stems.Set stems;
        public String stemTitle;
        public boolean stemAuto = true;
        public double[] stemDb = new double[Stems.COUNT];
        public boolean[] stemMute = new boolean[Stems.COUNT];
        /** Vocal rides from the last auto balance (kept when adjusting by hand, as on the phone). */
        public float[] stemRide;
        /** The stems' reading, if already made while loading (saves reading them again). */
        public Stems.Reading stemReading;
        public int stemCount() { int n = 0; if (stems != null) for (int i = 0; i < Stems.COUNT; i++) if (stems.has(i)) n++; return n; }
    }

    public static final class Take {
        public String label, about, verdict, streaming, report;
        public double lufs, truePeak, ceiling, lra;
        /** Measured tone (31 third-octave bands, mid), for the console. */
        public double[] mid;
        public File[] files;
    }

    public static final class Outcome {
        public File dir, source;
        public Listen before;
        /** The song as it came in (from {@link #before}): integrated loudness and true peak, for the Original row and level matching. */
        public double beforeLufs, beforePeak, beforeLra;
        /** The song's tone as it came in, and the target tone the round aimed at (31 bands, mid). */
        public double[] beforeMid, targetMid;
        /** The loudness asked for, and the style. */
        public double targetLufs = -14;
        public Target.Style style;
        public final List<Take> takes = new ArrayList<>();
        public String sheet;
        /** The settings this round used, in words, e.g. "Classic Rock · -14 LUFS · commercial reference · clean-up on · MP3 320". */
        public String settings;
        public double seconds;
        /** For stems: what the balance did, in words (also on the session sheet). */
        public final List<String> stemNotes = new ArrayList<>();
    }

    public interface Listener { void progress(String step, int percent); }

    private static File documents;

    /** Master Room inside the user's real Documents folder (wherever Windows has it), unless they chose another. */
    public static File defaultOutRoot() {
        return new File(documents(), "Master Room");
    }

    /**
     * The Documents folder Explorer shows. Windows lets it move (OneDrive, another drive, a restored PC), so ask
     * Windows rather than assuming <home>\Documents. Falls back to <home>\Documents, then the home folder.
     */
    public static synchronized File documents() {
        if (documents != null) return documents;
        File home = new File(System.getProperty("user.home"));
        if (System.getProperty("os.name", "").startsWith("Windows")) {
            try {
                Process p = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command",
                    "[Console]::OutputEncoding=[Text.Encoding]::UTF8; [Environment]::GetFolderPath('MyDocuments')")
                    .redirectErrorStream(true).start();
                p.getOutputStream().close();
                String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).strip();
                if (p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0 && !out.isEmpty() && !out.contains("\n")) {
                    File d = new File(out);
                    if (d.isDirectory()) return documents = d;
                }
            } catch (Exception ignored) { }
        }
        File d = new File(home, "Documents");
        return documents = d.isDirectory() ? d : home;
    }

    public static String extension(File f) {
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        return dot < 0 ? "" : n.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public static boolean readable(File f) {
        String e = extension(f);
        for (String r : READS) if (r.equals(e)) return true;
        return false;
    }

    /** WAV and MP3 in plain Java (the engine's own readers); everything else through FFmpeg. */
    public static Audio open(File f) throws IOException {
        String e = extension(f);
        if (e.equals("wav") || e.equals("wave")) return Wav.read(f);
        if (e.equals("mp3")) return DesktopAccess.decodeMp3(f);
        return Ffmpeg.decode(f);
    }

    public static String safeName(String song) {
        String base = song.replaceAll("\\.[A-Za-z0-9]{2,5}$", "");
        return base.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    public static String describe(Settings s) {
        return s.style.label + " · " + String.format(Locale.US, "%.0f LUFS", s.lufs) + " · " + (s.commercial ? "commercial reference" : "built-in tone reference")
            + " · clean-up " + (s.cleanUp ? "on" : "off") + (s.mp3 ? " · MP3 320" : "")
            + (s.stems == null ? "" : " · from " + s.stemCount() + " stems, " + (s.stemAuto ? "auto balance" : "own balance"));
    }

    /** Asks a running round to stop at its next checkpoint. */
    public static void stop() { Engineer.requestStop(true); }

    public static Outcome run(Settings s, Listener listener) throws IOException {
        long t0 = System.nanoTime();
        Engineer.requestStop(false);
        if (Streaming.phoneCodec == null && Ffmpeg.available()) Streaming.phoneCodec = new Ffmpeg.Aac();
        String name = s.stems != null ? safeName(s.stemTitle == null ? "Stems" : s.stemTitle) : safeName(s.song.getName());
        File dir = new File(new File(s.outRoot, name), LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HHmm ss")));
        if (!dir.mkdirs() && !dir.isDirectory()) throw new IOException("Could not create " + dir);
        Outcome out = new Outcome();
        out.dir = dir;
        out.settings = describe(s);
        listener.progress(s.stems != null ? "Opening the stems" : "Opening the song", 0);
        // With stems, the source is the stems summed as they came (the song as it came), as on the phone.
        Audio in = s.stems != null ? Stems.mix(s.stems, null, null) : open(s.song);
        out.source = new File(dir, "source.wav");
        Wav.write(out.source, in, 24);

        Engineer.Options options = new Engineer.Options();
        options.cleanUp = s.cleanUp;
        options.fromStems = s.stems != null;
        List<Engineer.Recipe> recipes = new ArrayList<>();
        for (Engineer.Take take : Engineer.Take.values()) recipes.add(take.recipe());
        final int[] takeIndex = {-1};
        final int takeCount = recipes.size();
        // The phone's progress formula: preparing is the first 15%, then the takes share the rest.
        Engineer.Progress bar = (step, p) -> {
            int overall = takeIndex[0] < 0 ? p : 15 + (int) ((takeIndex[0] + Math.max(0, p - 20) / 80.0) * 85 / takeCount);
            listener.progress(step, Math.min(100, Math.max(0, overall)));
        };
        Listen asItCame = null;
        if (s.stems != null) {
            asItCame = Listen.to(in);
            in = null;
            double[] levels = s.stemDb;
            float[] rides = s.stemRide;
            if (s.stemAuto) {
                Stems.Balance balance = Stems.decide(s.stemReading != null ? s.stemReading : Stems.read(s.stems), s.style);
                levels = balance.levelDb;
                rides = balance.rideDb;
                options.stemNotes.add("Auto balance: " + balance.words());
            }
            in = Stems.render(s.stems, levels, rides, s.stemMute, options.cleanUp, dir, options.stemNotes, bar);
            out.stemNotes.addAll(options.stemNotes);
        }
        Audio[] holder = {in};
        in = null;
        Target target = Target.builtIn(s.style, s.commercial);
        Engineer.Session session = Engineer.prepare(holder, target, s.lufs, options, bar);
        // The phone keeps the song as it came as the source; the balanced mix is what gets mastered.
        out.before = asItCame != null ? asItCame : session.before;
        out.beforeLufs = out.before.loudness.integrated;
        out.beforePeak = out.before.truePeakDb;
        out.beforeLra = out.before.loudness.lra;
        out.beforeMid = out.before.midDb;
        out.targetMid = target.midDb;
        out.targetLufs = s.lufs;
        out.style = s.style;
        List<Engineer.Result> results = new ArrayList<>();
        for (Engineer.Recipe recipe : recipes) {
            takeIndex[0]++;
            Engineer.Result r = Engineer.master(session, recipe, s.lufs, bar);
            File[] files = Engineer.deliver(r, dir, name + "_" + recipe.label);
            if (s.mp3) {
                File mp3 = new File(dir, name + "_" + recipe.label + "_320k.mp3");
                Release.Info info = new Release.Info();
                info.title = name;
                Release.mp3(files[1], mp3, info);
                File[] all = {files[0], files[1], mp3};
                files = all;
            }
            r.master = null;
            results.add(r);
            Take t = new Take();
            t.label = recipe.label;
            t.about = recipe.about;
            t.lufs = r.after.loudness.integrated;
            t.truePeak = r.after.truePeakDb;
            t.lra = r.after.loudness.lra;
            t.mid = r.after.midDb;
            t.ceiling = r.ceilingDb;
            t.verdict = r.verdict;
            t.report = r.distortion == null ? "" : String.valueOf(r.distortion);
            t.streaming = r.streaming == null ? "" : r.streaming.verdict();
            t.files = files;
            out.takes.add(t);
        }
        session.release();
        out.seconds = (System.nanoTime() - t0) / 1e9;
        out.sheet = "Mastered with: " + out.settings + System.lineSeparator() + System.lineSeparator() + Engineer.sheet(session, results) + String.format(Locale.US, "%nFinished in %.1f minutes on this computer", out.seconds / 60);
        java.nio.file.Files.writeString(new File(dir, "session sheet.txt").toPath(), out.sheet);
        listener.progress("Done. Your takes are ready.", 100);
        return out;
    }
}
