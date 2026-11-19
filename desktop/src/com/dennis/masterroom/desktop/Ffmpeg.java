package com.dennis.masterroom.desktop;

import com.dennis.masterroom.engine.Audio;
import com.dennis.masterroom.engine.Streaming;
import com.dennis.masterroom.engine.Wav;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * FFmpeg (LGPL build, shipped next to the app) for the formats Java cannot read on its own
 * (FLAC, M4A/AAC, OGG, Opus, AIFF, WMA) and for the AAC streaming check, which the phone does
 * with its built-in AAC encoder at 256 kbps. Audio is handed over as 24-bit WAV.
 */
public final class Ffmpeg {
    private Ffmpeg() {}

    /** The bundled copy first (app folder\ffmpeg\ffmpeg.exe), then a system property, then PATH. */
    public static String find() {
        String prop = System.getProperty("masterroom.ffmpeg");
        if (prop != null && new File(prop).canExecute()) return prop;
        try {
            File jar = new File(Ffmpeg.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            File dir = jar.isFile() ? jar.getParentFile() : jar;
            for (String n : new String[]{"ffmpeg/ffmpeg.exe", "ffmpeg/bin/ffmpeg.exe", "ffmpeg.exe", "ffmpeg/ffmpeg"}) {
                File f = new File(dir, n);
                if (f.canExecute()) return f.getAbsolutePath();
            }
        } catch (Exception ignored) { }
        for (String n : new String[]{"ffmpeg", "ffmpeg.exe"}) {
            try {
                Process p = new ProcessBuilder(n, "-hide_banner", "-version").redirectErrorStream(true).start();
                p.getInputStream().readAllBytes();
                if (p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0) return n;
            } catch (Exception ignored) { }
        }
        return null;
    }

    public static boolean available() { return find() != null; }

    static void run(List<String> args) throws IOException {
        String exe = find();
        if (exe == null) throw new IOException("This format needs FFmpeg, which was not found next to the app. WAV and MP3 work without it.");
        List<String> cmd = new ArrayList<>();
        cmd.add(exe);
        cmd.addAll(List.of("-hide_banner", "-nostdin", "-v", "error", "-y"));
        cmd.addAll(args);
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        try {
            if (!p.waitFor(10, TimeUnit.MINUTES)) { p.destroyForcibly(); throw new IOException("FFmpeg took too long"); }
        } catch (InterruptedException e) { p.destroyForcibly(); Thread.currentThread().interrupt(); throw new IOException("Stopped"); }
        if (p.exitValue() != 0) throw new IOException("FFmpeg could not read the file" + (out.isEmpty() ? "" : ": " + out.lines().reduce((a, b) -> b).orElse("")));
    }

    /** Any audio file FFmpeg reads, at its own sample rate, as the engine's Audio. */
    public static Audio decode(File in) throws IOException {
        File wav = File.createTempFile("masterroom-in", ".wav");
        try {
            run(List.of("-i", in.getAbsolutePath(), "-vn", "-ac", "2", "-c:a", "pcm_s24le", wav.getAbsolutePath()));
            return Wav.read(wav);
        } finally {
            wav.delete();
        }
    }

    /** The same, converted to the given sample rate (for a master to compare against a song at another rate). */
    public static Audio decode(File in, int rate) throws IOException {
        File wav = File.createTempFile("masterroom-in", ".wav");
        try {
            run(List.of("-i", in.getAbsolutePath(), "-vn", "-ac", "2", "-ar", String.valueOf(rate), "-c:a", "pcm_s24le", wav.getAbsolutePath()));
            return Wav.read(wav);
        } finally {
            wav.delete();
        }
    }

    /** The streaming check's AAC line, as on the phone: AAC-LC at 256 kbps, then decoded again. */
    public static final class Aac implements Streaming.Codec {
        @Override public String name() { return "AAC 256"; }
        @Override public Audio roundTrip(Audio in) throws IOException {
            File wav = File.createTempFile("masterroom-aac", ".wav"), m4a = File.createTempFile("masterroom-aac", ".m4a");
            try {
                Wav.write(wav, in, 24);
                run(List.of("-i", wav.getAbsolutePath(), "-c:a", "aac", "-b:a", "256k", m4a.getAbsolutePath()));
                return decode(m4a);
            } finally {
                wav.delete();
                m4a.delete();
            }
        }
    }

    static String lower(File f) { return f.getName().toLowerCase(Locale.ROOT); }
}
