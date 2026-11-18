package com.dennis.masterroom.desktop;

import com.dennis.masterroom.engine.Audio;
import com.dennis.masterroom.engine.DesktopAccess;
import com.dennis.masterroom.engine.Engineer;
import com.dennis.masterroom.engine.Listen;
import com.dennis.masterroom.engine.Release;
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
 * MainActivity.master() for a single song (stems arrive in a later build). Headless, so the window,
 * the command line and the checks all run exactly this.
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
    }

    public static final class Take {
        public String label, about, verdict, streaming, report;
        public double lufs, truePeak, ceiling;
        public File[] files;
    }

    public static final class Outcome {
        public File dir, source;
        public Listen before;
        public final List<Take> takes = new ArrayList<>();
        public String sheet;
        /** The settings this round used, in words, e.g. "Classic Rock · -14 LUFS · commercial reference · clean-up on · MP3 320". */
        public String settings;
        public double seconds;
    }

    public interface Listener { void progress(String step, int percent); }

    public static File defaultOutRoot() {
        File docs = new File(System.getProperty("user.home"), "Documents");
        return new File(docs.isDirectory() ? docs : new File(System.getProperty("user.home")), "Master Room");
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
            + " · clean-up " + (s.cleanUp ? "on" : "off") + (s.mp3 ? " · MP3 320" : "");
    }

    /** Asks a running round to stop at its next checkpoint. */
    public static void stop() { Engineer.requestStop(true); }

    public static Outcome run(Settings s, Listener listener) throws IOException {
        long t0 = System.nanoTime();
        Engineer.requestStop(false);
        if (Streaming.phoneCodec == null && Ffmpeg.available()) Streaming.phoneCodec = new Ffmpeg.Aac();
        String name = safeName(s.song.getName());
        File dir = new File(new File(s.outRoot, name), LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HHmm ss")));
        if (!dir.mkdirs() && !dir.isDirectory()) throw new IOException("Could not create " + dir);
        Outcome out = new Outcome();
        out.dir = dir;
        out.settings = describe(s);
        listener.progress("Opening the song", 0);
        Audio in = open(s.song);
        out.source = new File(dir, "source.wav");
        Wav.write(out.source, in, 24);

        Engineer.Options options = new Engineer.Options();
        options.cleanUp = s.cleanUp;
        options.fromStems = false;
        List<Engineer.Recipe> recipes = new ArrayList<>();
        for (Engineer.Take take : Engineer.Take.values()) recipes.add(take.recipe());
        final int[] takeIndex = {-1};
        final int takeCount = recipes.size();
        // The phone's progress formula: preparing is the first 15%, then the takes share the rest.
        Engineer.Progress bar = (step, p) -> {
            int overall = takeIndex[0] < 0 ? p : 15 + (int) ((takeIndex[0] + Math.max(0, p - 20) / 80.0) * 85 / takeCount);
            listener.progress(step, Math.min(100, Math.max(0, overall)));
        };
        Audio[] holder = {in};
        in = null;
        Target target = Target.builtIn(s.style, s.commercial);
        Engineer.Session session = Engineer.prepare(holder, target, s.lufs, options, bar);
        out.before = session.before;
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
