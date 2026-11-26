package com.dennis.masterroom.desktop;

import com.dennis.masterroom.engine.Audio;
import com.dennis.masterroom.engine.Listen;
import com.dennis.masterroom.engine.VocalTune;
import com.dennis.masterroom.engine.Wav;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
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

    /** Stereo 32-bit IEEE float WAV. */
    static void writeFloat(File f, Audio a) throws IOException {
        int frames = a.frames();
        long data = (long) frames * 8;
        try (DataOutputStream o = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(f), 1 << 16))) {
            o.writeBytes("RIFF"); le32(o, 36 + data); o.writeBytes("WAVE");
            o.writeBytes("fmt "); le32(o, 16); le16(o, 3); le16(o, 2); le32(o, a.rate); le32(o, (long) a.rate * 8); le16(o, 8); le16(o, 32);
            o.writeBytes("data"); le32(o, data);
            for (int i = 0; i < frames; i++) { le32(o, Float.floatToRawIntBits(a.l[i])); le32(o, Float.floatToRawIntBits(a.r[i])); }
        }
    }

    private static void le16(DataOutputStream o, int v) throws IOException { o.write(v & 255); o.write((v >> 8) & 255); }
    private static void le32(DataOutputStream o, long v) throws IOException { o.write((int) (v & 255)); o.write((int) ((v >> 8) & 255)); o.write((int) ((v >> 16) & 255)); o.write((int) ((v >> 24) & 255)); }
}
