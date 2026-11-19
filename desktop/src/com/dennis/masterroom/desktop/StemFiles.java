package com.dennis.masterroom.desktop;

import com.dennis.masterroom.engine.Audio;
import com.dennis.masterroom.engine.Stems;
import com.dennis.masterroom.engine.Wav;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Brings in stems: Stem Studio's (vocals, drums, bass, other, guitar, piano) and Suno's, whose download
 * numbers its files ("7 Vocals.wav", "6 Backing_Vocals.wav"), splits more finely (percussion, synth, FX,
 * keyboard, strings…) and includes the full mix as "0 Song Name.wav".
 * <ul>
 *   <li>Each file is placed by the stem word in its name (the phone's rule first, then the extra Suno names).</li>
 *   <li>The full mix is left out (it would double everything) and gives the song its title.</li>
 *   <li>Several files for one stem are added together, sample for sample, into one 32-bit float WAV:
 *       Vocals + Backing Vocals, Drums + Percussion, and synth, FX, strings and the rest into Other.</li>
 *   <li>A core stem the set does not have (no bass, an instrumental) is stood in for by silence, said so plainly.</li>
 * </ul>
 * Picked WAVs that need no combining are used where they are; anything unpacked or made goes in a
 * temporary folder that is deleted when the stems are cleared. The engine itself is unchanged.
 */
final class StemFiles {
    private StemFiles() {}

    /** Names for the extra stems Suno exports, beyond the engine's own words. */
    private static final String[][] EXTRA = {
        {"backing", "choir", "harmony", "harmonies", "adlib"},
        {"percussion", "perc", "tambourine", "shaker", "clap", "conga", "bongo"},
        {},
        {"synth", "string", "brass", "woodwind", "fx", "sfx", "sample", "pad", "orchestra", "horn", "effects", "lead"},
        {},
        {"keyboard", "organ", "rhodes"}};
    private static final Pattern NUMBERED = Pattern.compile("^\\d+[\\s_.-]+.*");

    static final class Loaded {
        final Stems.Set set;
        final String title;
        /** What went into each stem, in words ("Vocals + Backing Vocals"); null where there is none. */
        final String[] labels;
        /** Stems the set did not have, stood in for by silence. */
        final boolean[] silent;
        /** What was done to make the set, for the status line and the session sheet. */
        final List<String> notes;
        /** Unpacked or made files, deleted on {@link #discard()}; null when the picked WAVs are used as they are. */
        final File temp;
        Loaded(Stems.Set set, String title, String[] labels, boolean[] silent, List<String> notes, File temp) {
            this.set = set; this.title = title; this.labels = labels; this.silent = silent; this.notes = notes; this.temp = temp;
        }
        /** Stems with sound in them. */
        int count() { int n = 0; for (int i = 0; i < Stems.COUNT; i++) if (set.has(i) && !silent[i]) n++; return n; }
        void discard() { if (temp != null) deleteAll(temp); }
    }

    static boolean isZip(File f) { return f.getName().toLowerCase(Locale.ROOT).endsWith(".zip"); }

    /** True when these files look like stems rather than one song: several files, or a ZIP. */
    static boolean looksLikeStems(List<File> files) { return files.size() > 1 || (files.size() == 1 && isZip(files.get(0))); }

    /** Which stem a file name means: the phone's words first, then the extra Suno names. -1 when it says none. */
    static int partFor(String name) {
        int part = Stems.partOf(name);
        if (part >= 0) return part;
        String low = name.toLowerCase(Locale.US).replace('_', ' ');
        int best = -1, at = -1;
        for (int p = 0; p < EXTRA.length; p++)
            for (String w : EXTRA[p]) {
                int i = low.lastIndexOf(w);
                if (i > at) { at = i; best = p; }
            }
        return best;
    }

    /** "The Porch Light's On for Me Stems" → "The Porch Light's On for Me". */
    static String cleanTitle(String s) {
        String t = s.replaceAll("(?i)[\\s_-]*\\(?stems?\\)?$", "").replace('_', ' ').trim();
        return t.isEmpty() ? s : t;
    }

    /** "6 Backing_Vocals.wav" → "Backing Vocals"; "Song - Vocals.wav" → "Vocals". */
    static String label(String name, String title) {
        String s = name.replaceAll("(?i)\\.wav$", "").replace('_', ' ').replaceAll("^\\d+[\\s.-]+", "").trim();
        if (title != null && !title.isEmpty() && s.toLowerCase(Locale.US).startsWith(title.toLowerCase(Locale.US)))
            s = s.substring(title.length()).replaceAll("^[\\s()\\[\\]._-]+", "").replaceAll("[()\\[\\]]", "").trim();
        return s.isEmpty() ? name : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private record Item(File file, String name) {}

    static Loaded load(List<File> picked) throws IOException {
        File temp = null;
        try {
            List<Item> items = new ArrayList<>();
            String zipTitle = null, folderTitle = null;
            for (File f : picked) {
                if (isZip(f)) {
                    if (temp == null) temp = Files.createTempDirectory("masterroom-stems").toFile();
                    zipTitle = cleanTitle(f.getName().replaceAll("(?i)\\.zip$", ""));
                    items.addAll(unzip(f, temp));
                } else {
                    if (!f.getName().toLowerCase(Locale.ROOT).endsWith(".wav")) throw new IOException(f.getName() + " is not a WAV. Stems need to be WAV files (or a ZIP of them)");
                    items.add(new Item(f, f.getName()));
                    if (folderTitle == null && f.getParentFile() != null) folderTitle = cleanTitle(f.getParentFile().getName());
                }
            }
            if (items.isEmpty()) throw new IOException("No WAV stems found");

            // Suno numbers its files. Some downloads start with the full mix ("0 Song Name.wav"); others start
            // with a stem ("0 Lead Vocals.wav"). Only a number-0 file that names no stem is the full mix.
            boolean numbered = items.size() > 1 && items.stream().allMatch(i -> NUMBERED.matcher(i.name()).matches());
            String title = null;
            List<String> notes = new ArrayList<>();
            Map<Integer, List<Item>> parts = new LinkedHashMap<>();
            for (Item it : items) {
                if (numbered && it.name().matches("^0[\\s_.-]+.*") && partFor(it.name()) < 0) {
                    title = it.name().replaceAll("(?i)\\.wav$", "").replaceAll("^0[\\s_.-]+", "").trim();
                    notes.add("Left out the full mix (" + it.name() + "), so it is not added on top of the stems");
                    continue;
                }
                int part = partFor(it.name());
                if (part < 0) throw new IOException("Not sure which stem \"" + it.name() + "\" is. Rename it with vocals, drums, bass, other, guitar or piano in the name, or leave it out");
                parts.computeIfAbsent(part, k -> new ArrayList<>()).add(it);
            }
            if (parts.isEmpty()) throw new IOException("No stems found, only a full mix");
            // A numbered set's names hold no song title ("1 Backing Vocals"), so the ZIP or folder names it.
            if (title == null && !numbered) {
                for (List<Item> list : parts.values()) {
                    String t = Stems.titleOf(list.get(0).name()).replaceAll("^\\d+[\\s_.-]*", "").replaceAll("[\\s(\\[_.-]+$", "").trim();
                    if (!t.isEmpty() && !t.equals("Stems") && !t.matches("\\d+")) { title = t; break; }
                }
            }
            if (title == null) title = zipTitle != null && !zipTitle.isEmpty() ? zipTitle : folderTitle != null ? folderTitle : "Stems";

            File[] files = new File[Stems.COUNT];
            String[] labels = new String[Stems.COUNT];
            Wav.Header first = null;
            for (Map.Entry<Integer, List<Item>> e : parts.entrySet()) {
                int part = e.getKey();
                List<Item> list = e.getValue();
                StringBuilder what = new StringBuilder();
                for (Item it : list) what.append(what.length() == 0 ? "" : " + ").append(label(it.name(), title));
                labels[part] = what.toString();
                if (list.size() == 1) {
                    files[part] = list.get(0).file();
                } else {
                    if (temp == null) temp = Files.createTempDirectory("masterroom-stems").toFile();
                    files[part] = new File(temp, Stems.NAMES[part] + " (combined).wav");
                    combine(list, files[part]);
                    notes.add(Stems.NAMES[part] + " = " + labels[part]);
                }
                if (first == null) first = Wav.header(files[part]);
            }
            boolean[] silent = new boolean[Stems.COUNT];
            for (int part = 0; part < Stems.CORE; part++) {
                if (files[part] != null) continue;
                if (temp == null) temp = Files.createTempDirectory("masterroom-stems").toFile();
                files[part] = new File(temp, Stems.NAMES[part] + " (silent).wav");
                writeFloat(files[part], new Audio((int) first.frames, first.rate));
                silent[part] = true;
                notes.add("No " + Stems.NAMES[part] + " stem in this set, so silence stands in for it");
            }
            Stems.Set set = Stems.open(files);
            return new Loaded(set, title, labels, silent, notes, temp);
        } catch (IOException | RuntimeException e) {
            if (temp != null) deleteAll(temp);
            throw e;
        }
    }

    /**
     * Unpacks the WAVs. Uses the ZIP's central directory (ZipFile), which holds the true sizes: Suno's ZIPs
     * leave the sizes in front of each file at 0, which a streaming reader cannot get past.
     */
    private static List<Item> unzip(File zipFile, File into) throws IOException {
        List<Item> out = new ArrayList<>();
        try (ZipFile zip = new ZipFile(zipFile)) {
            Enumeration<? extends ZipEntry> all = zip.entries();
            int n = 0;
            while (all.hasMoreElements()) {
                ZipEntry entry = all.nextElement();
                String name = entry.getName();
                name = name.substring(name.lastIndexOf('/') + 1);
                if (entry.isDirectory() || name.startsWith("._") || !name.toLowerCase(Locale.ROOT).endsWith(".wav")) continue;
                File to = new File(into, (n++) + "_" + name.replaceAll("[\\\\/:*?\"<>|]", "_"));
                try (InputStream in = zip.getInputStream(entry)) { Files.copy(in, to.toPath(), StandardCopyOption.REPLACE_EXISTING); }
                out.add(new Item(to, name));
            }
        }
        return out;
    }

    /** Adds the files together sample for sample (in float, nothing clipped) and writes one 32-bit float WAV. */
    private static void combine(List<Item> list, File to) throws IOException {
        Audio sum = null;
        for (Item it : list) {
            Audio a = Wav.read(it.file());
            if (sum == null) { sum = new Audio(a.frames(), a.rate); }
            if (a.rate != sum.rate) throw new IOException(it.name() + " has a different sample rate from the other stems");
            if (a.frames() > sum.frames()) {
                Audio longer = new Audio(a.frames(), a.rate);
                System.arraycopy(sum.l, 0, longer.l, 0, sum.frames());
                System.arraycopy(sum.r, 0, longer.r, 0, sum.frames());
                sum = longer;
            }
            for (int i = 0; i < a.frames(); i++) { sum.l[i] += a.l[i]; sum.r[i] += a.r[i]; }
        }
        writeFloat(to, sum);
    }

    /** A 32-bit float stereo WAV (format 3), so a sum above 0 dBFS is kept as it is. */
    static void writeFloat(File f, Audio a) throws IOException {
        int frames = a.frames();
        long data = (long) frames * 8;
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(f), 1 << 16)) {
            ByteBuffer h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
            h.put("RIFF".getBytes()).putInt((int) (36 + data)).put("WAVE".getBytes());
            h.put("fmt ".getBytes()).putInt(16).putShort((short) 3).putShort((short) 2).putInt(a.rate).putInt(a.rate * 8).putShort((short) 8).putShort((short) 32);
            h.put("data".getBytes()).putInt((int) data);
            out.write(h.array());
            ByteBuffer b = ByteBuffer.allocate(8 * 8192).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < frames; i++) {
                b.putFloat(a.l[i]).putFloat(a.r[i]);
                if (!b.hasRemaining()) { out.write(b.array(), 0, b.position()); b.clear(); }
            }
            out.write(b.array(), 0, b.position());
        }
    }

    static void deleteAll(File f) {
        File[] in = f.listFiles();
        if (in != null) for (File c : in) deleteAll(c);
        f.delete();
    }
}
