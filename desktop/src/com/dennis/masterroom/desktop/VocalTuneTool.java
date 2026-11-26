package com.dennis.masterroom.desktop;

import com.dennis.masterroom.engine.Audio;
import com.dennis.masterroom.engine.Listen;
import com.dennis.masterroom.engine.VocalTune;
import com.dennis.masterroom.engine.Wav;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Tune a vocal on its own (a vocal stem, or a dry vocal WAV): the tuned file, the original beside it
 * for listening, and a short report, in <outRoot>\<name>\Vocal Tune <date time>\.
 * The tuned file is 32-bit float WAV, so nothing is rounded or clipped on the way out.
 */
final class VocalTuneTool {
    private VocalTuneTool() {}

    static final class Done {
        File folder, original, tuned, report;
        VocalTune.Result result;
        double originalLufs, originalPeak, tunedLufs, tunedPeak;
    }

    static Done run(File in, VocalTune.Mode mode, File outRoot) throws IOException {
        if (mode == VocalTune.Mode.OFF) mode = VocalTune.Mode.NATURAL;
        boolean wav = Ffmpeg.lower(in).endsWith(".wav");
        Audio a = wav ? Wav.read(in) : Ffmpeg.decode(in);
        Done d = new Done();
        d.result = VocalTune.tune(a, mode);
        String base = MasterJob.safeName(in.getName());
        d.folder = new File(new File(outRoot, base), "Vocal Tune " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HHmm")));
        if (!d.folder.isDirectory() && !d.folder.mkdirs()) throw new IOException("Could not make " + d.folder);
        if (wav) d.original = in;
        else { d.original = new File(d.folder, base + " - original.wav"); writeFloat(d.original, a); }
        Listen o = Listen.to(a);
        d.originalLufs = o.loudness.integrated; d.originalPeak = o.truePeakDb;
        if (d.result.changed) {
            d.tuned = new File(d.folder, base + " - tuned (" + mode.name().toLowerCase(Locale.ROOT) + ").wav");
            writeFloat(d.tuned, d.result.audio);
            Listen t = Listen.to(d.result.audio);
            d.tunedLufs = t.loudness.integrated; d.tunedPeak = t.truePeakDb;
        }
        d.report = new File(d.folder, "Vocal Tune (" + mode.name().toLowerCase(Locale.ROOT) + ").txt");
        String text = "Vocal Tune · Master Room\r\n"
                + "Source: " + in.getAbsolutePath() + "\r\n"
                + "Setting: " + mode.label + "\r\n"
                + String.format(Locale.US, "Sung time found: %.1f s\r\n", d.result.sungSeconds)
                + d.result.words() + "\r\n"
                + (d.tuned != null ? "Tuned file: " + d.tuned.getName() + " (32-bit float WAV, same rate as the source)\r\n" : "No tuned file was written.\r\n")
                + "Judge it by ear: play Original and Tuned level-matched in Master Room before using it.\r\n";
        Files.writeString(d.report.toPath(), text, StandardCharsets.UTF_8);
        return d;
    }

    static void writeFloat(File f, Audio a) throws IOException { Wav.writeFloat(f, a); }
}
