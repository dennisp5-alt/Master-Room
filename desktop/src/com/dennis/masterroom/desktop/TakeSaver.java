package com.dennis.masterroom.desktop;

import com.dennis.masterroom.engine.Release;
import com.dennis.masterroom.engine.Target;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * The phone's Save take: the chosen take as release files with the song's details written in (24-bit and
 * 16-bit 44.1 kHz WAV with a LIST INFO block, MP3 320 with tags), plus its session sheet. The take joins the
 * album being matched, and counts as a "saved" pick for the Yours take.
 */
final class TakeSaver {
    private TakeSaver() {}

    /** @return the files written, in &lt;outRoot&gt;\Saved */
    static File[] save(MasterJob.Take take, Release.Info info, File outRoot, String sheet, double level, Target.Style style) throws IOException {
        File dir = new File(outRoot, "Saved");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Could not create " + dir);
        String base = (info.title.isBlank() ? "Song" : info.title).replaceAll("[\\\\/:*?\"<>|]", "_").trim() + "_" + take.label;
        File wav24 = new File(dir, base + "_24bit.wav"), wav16 = new File(dir, base + "_16bit_44k.wav"), mp3 = new File(dir, base + "_320k.mp3"),
            notes = new File(dir, base + "_session.txt");
        try (OutputStream o = new BufferedOutputStream(new FileOutputStream(wav24), 1 << 16)) { Release.copyTagged(take.files[0], o, info); }
        try (OutputStream o = new BufferedOutputStream(new FileOutputStream(wav16), 1 << 16)) { Release.copyTagged(take.files[1], o, info); }
        Release.mp3(take.files[1], mp3, info);
        Files.writeString(notes.toPath(), sheet == null ? "" : sheet, StandardCharsets.UTF_8);
        Library.addToAlbum(info.title.isBlank() ? base : info.title, take.lufs, level, take.mid, take.side);
        Library.remember(style, "saved", take.label);
        return new File[]{wav24, wav16, mp3, notes};
    }
}
