package com.dennis.masterroom.desktop;

import com.dennis.masterroom.engine.Stems;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Brings in stems the way the phone does: the WAVs themselves, or Stem Studio's ZIP. Each file is placed by
 * the stem word in its name (the last one found, so a song title cannot mislead it). Picked WAVs are used
 * where they are; a ZIP is unpacked into a temporary folder that goes when the stems are cleared.
 */
final class StemFiles {
    private StemFiles() {}

    static final class Loaded {
        final Stems.Set set;
        final String title;
        /** The unpacked ZIP, deleted on {@link #discard()}; null for WAVs used in place. */
        final File temp;
        Loaded(Stems.Set set, String title, File temp) { this.set = set; this.title = title; this.temp = temp; }
        int count() { int n = 0; for (int i = 0; i < Stems.COUNT; i++) if (set.has(i)) n++; return n; }
        void discard() { if (temp != null) deleteAll(temp); }
    }

    static boolean isZip(File f) { return f.getName().toLowerCase(Locale.ROOT).endsWith(".zip"); }

    /** True when these files look like stems rather than one song: several files, or a ZIP. */
    static boolean looksLikeStems(List<File> files) { return files.size() > 1 || (files.size() == 1 && isZip(files.get(0))); }

    static Loaded load(List<File> picked) throws IOException {
        File[] files = new File[Stems.COUNT];
        String title = null;
        File temp = null;
        try {
            for (File f : picked) {
                if (isZip(f)) {
                    if (temp == null) temp = Files.createTempDirectory("masterroom-stems").toFile();
                    title = unzip(f, temp, files, title);
                } else {
                    String name = f.getName();
                    if (!name.toLowerCase(Locale.ROOT).endsWith(".wav")) throw new IOException(name + " is not a WAV. Stems need to be WAV files (or Stem Studio's ZIP)");
                    int part = Stems.partOf(name);
                    if (part < 0) throw new IOException(name + " does not say which stem it is (vocals, drums, bass, other, guitar or piano)");
                    if (files[part] != null && !files[part].equals(f)) throw new IOException("Two files look like the " + Stems.NAMES[part] + " stem: " + files[part].getName() + " and " + name);
                    files[part] = f;
                    if (title == null) title = Stems.titleOf(name);
                }
            }
            Stems.Set set = Stems.open(files);
            return new Loaded(set, title == null ? "Stems" : title, temp);
        } catch (IOException | RuntimeException e) {
            if (temp != null) deleteAll(temp);
            throw e;
        }
    }

    private static String unzip(File zipFile, File into, File[] files, String title) throws IOException {
        try (InputStream raw = new BufferedInputStream(new FileInputStream(zipFile), 1 << 16); ZipInputStream zip = new ZipInputStream(raw)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                name = name.substring(name.lastIndexOf('/') + 1);
                if (entry.isDirectory() || !name.toLowerCase(Locale.ROOT).endsWith(".wav")) continue;
                int part = Stems.partOf(name);
                if (part < 0) continue;
                File to = new File(into, Stems.NAMES[part] + ".wav");
                Files.copy(zip, to.toPath(), StandardCopyOption.REPLACE_EXISTING);
                files[part] = to;
                if (title == null) title = Stems.titleOf(name);
            }
        }
        return title;
    }

    static void deleteAll(File f) {
        File[] in = f.listFiles();
        if (in != null) for (File c : in) deleteAll(c);
        f.delete();
    }
}
