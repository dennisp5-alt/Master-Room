package com.dennis.masterroom.desktop;

import com.dennis.masterroom.engine.Engineer;
import com.dennis.masterroom.engine.Listen;
import com.dennis.masterroom.engine.Target;
import com.dennis.masterroom.engine.Taste;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What the app keeps between rounds, in the same line formats as the phone (so the files could move
 * between them): reference records per style, the album being matched, and the taste log. Only measured
 * numbers are kept, never audio. Lives in %APPDATA%\Master Room (elsewhere: ~/.masterroom), or the
 * folder in the masterroom.data property (used by the checks).
 */
final class Library {
    private Library() {}

    static File dir() {
        String forced = System.getProperty("masterroom.data");
        File d;
        if (forced != null && !forced.isEmpty()) d = new File(forced);
        else {
            String appData = System.getenv("APPDATA");
            d = appData != null && !appData.isEmpty() ? new File(appData, "Master Room") : new File(System.getProperty("user.home"), ".masterroom");
        }
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    private static List<String> lines(File f) {
        List<String> out = new ArrayList<>();
        if (!f.isFile()) return out;
        try {
            for (String l : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) if (!l.trim().isEmpty()) out.add(l);
        } catch (IOException ignored) { }
        return out;
    }

    private static void write(File f, List<String> lines) throws IOException {
        Files.write(f.toPath(), lines, StandardCharsets.UTF_8);
    }

    private static void append(File f, String line) throws IOException {
        Files.writeString(f.toPath(), line + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    static String csv(double[] d) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < d.length; i++) b.append(i == 0 ? "" : ",").append(String.format(Locale.US, "%.2f", d[i]));
        return b.toString();
    }

    static double[] numbers(String csv) {
        String[] p = csv.split(",");
        double[] d = new double[p.length];
        for (int i = 0; i < p.length; i++) d[i] = Double.parseDouble(p[i]);
        return d;
    }

    // ---------- Reference records, per style: "name|mid|side|punch" ----------

    static File shelf(Target.Style style) { return new File(dir(), "references_" + style.name() + ".txt"); }

    static List<String> shelfLines(Target.Style style) { return lines(shelf(style)); }

    static List<Listen> records(Target.Style style) {
        List<Listen> out = new ArrayList<>();
        for (String line : shelfLines(style)) {
            String[] p = line.split("\\|");
            try {
                if (p.length >= 3) out.add(Listen.fromBands(numbers(p[1]), numbers(p[2]), p.length > 3 ? Double.parseDouble(p[3]) : Double.NaN));
            } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    static void addRecord(Target.Style style, String name, Listen l) throws IOException {
        append(shelf(style), name.replace('|', ' ') + "|" + csv(l.midDb) + "|" + csv(l.sideDb) + "|" + String.format(Locale.US, "%.2f", l.punchDb));
    }

    static void removeRecord(Target.Style style, int index) throws IOException {
        List<String> all = shelfLines(style);
        if (index < 0 || index >= all.size()) return;
        all.remove(index);
        write(shelf(style), all);
    }

    // ---------- Album: first line the name, then "song|lufs|level|mid|side" per saved take ----------

    static File albumFile() { return new File(dir(), "album.txt"); }

    static List<String> albumLines() { return lines(albumFile()); }

    static boolean albumOn() { return !albumLines().isEmpty(); }

    static String albumName() { List<String> l = albumLines(); return l.isEmpty() ? "" : l.get(0); }

    static List<Listen> albumRecords() {
        List<Listen> out = new ArrayList<>();
        List<String> lines = albumLines();
        for (int i = 1; i < lines.size(); i++) {
            String[] p = lines.get(i).split("\\|");
            try { if (p.length == 5) out.add(Listen.fromBands(numbers(p[3]), numbers(p[4]))); } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    /** The loudness the album's first saved song was mastered to, or NaN. */
    static double albumLevel() {
        List<String> lines = albumLines();
        if (lines.size() < 2) return Double.NaN;
        try { return Double.parseDouble(lines.get(1).split("\\|")[2]); } catch (RuntimeException e) { return Double.NaN; }
    }

    static void startAlbum(String name) throws IOException {
        List<String> l = new ArrayList<>();
        l.add(name == null || name.isBlank() ? "Current album" : name.replace('|', ' ').trim());
        write(albumFile(), l);
    }

    static void endAlbum() { albumFile().delete(); }

    /** A saved take joins the album (replacing that song's earlier one). */
    static void addToAlbum(String song, double lufs, double level, double[] mid, double[] side) throws IOException {
        List<String> lines = albumLines();
        if (lines.isEmpty()) return;
        String key = song.replace('|', ' ');
        String entry = key + "|" + String.format(Locale.US, "%.1f", lufs) + "|" + String.format(Locale.US, "%.0f", level) + "|" + csv(mid) + "|" + csv(side);
        boolean replaced = false;
        for (int i = 1; i < lines.size(); i++) if (lines.get(i).startsWith(key + "|")) { lines.set(i, entry); replaced = true; }
        if (!replaced) lines.add(entry);
        write(albumFile(), lines);
    }

    /** The phone's album check for the session sheet: each take's lows and top end against the album so far. */
    static String albumCheck(List<MasterJob.Take> takes) {
        List<String> lines = albumLines();
        if (lines.size() < 2) return "";
        double[] mid = new double[31];
        int n = lines.size() - 1;
        for (int i = 1; i < lines.size(); i++) {
            double[] m = numbers(lines.get(i).split("\\|")[3]);
            for (int k = 0; k < 31; k++) mid[k] += m[k] / n;
        }
        StringBuilder b = new StringBuilder(String.format(Locale.US, "%n%nAgainst %s:%n", lines.get(0)));
        for (MasterJob.Take t : takes) {
            double[] gap = Console.Tone.againstTarget(t.mid, mid);
            b.append(String.format(Locale.US, "%s: lows %+.1f dB, top end %+.1f dB%n", t.label, band(gap, 40, 120), band(gap, 4000, 12000)));
        }
        return b.toString();
    }

    private static double band(double[] gap, double from, double to) {
        double sum = 0;
        int n = 0;
        for (int i = 0; i < 31; i++) if (Listen.BANDS[i] >= from && Listen.BANDS[i] <= to) { sum += gap[i]; n++; }
        return n == 0 ? 0 : sum / n;
    }

    // ---------- The target: album, then records, then the built-in curve (the phone's order) ----------

    static Target target(Target.Style style, boolean commercial) {
        List<Listen> album = albumRecords();
        if (!album.isEmpty()) return Target.fromReferences("Album " + albumName() + ", " + album.size() + " finished song" + (album.size() == 1 ? "" : "s"), album, commercial);
        List<Listen> refs = records(style);
        return refs.isEmpty() ? Target.builtIn(style, commercial) : Target.fromReferences(refs.size() + " reference records", refs, commercial);
    }

    /** In a few words, what sets the tone now (for the button). */
    static String toneShort(Target.Style style) {
        if (!albumRecords().isEmpty()) return "Album: " + albumName();
        int n = records(style).size();
        return n == 0 ? "Built-in curve" : n + " record" + (n == 1 ? "" : "s");
    }

    /** The phone's line about it. */
    static String toneLine(Target.Style style) {
        int n = records(style).size();
        boolean album = !albumRecords().isEmpty();
        String records = n + " reference record" + (n == 1 ? "" : "s");
        return album ? (n == 0 ? "Album match is setting the tone, not the genre." : records + ", but album match sets the tone.")
            : n == 0 ? "Starting curve. Add 3 to 5 records you want to sound like." : records + " for " + style.label + ".";
    }

    // ---------- Taste: "style|how|take" (blind picks and saved takes) ----------

    static File tasteFile() { return new File(dir(), "taste.txt"); }

    static List<String> taste() { return lines(tasteFile()); }

    static void remember(Target.Style style, String how, String take) throws IOException {
        if (style == null) return;
        append(tasteFile(), Taste.record(style.name(), how, take));
    }

    /** Build 3 and 4 kept blind picks beside the rounds; bring them in once. */
    static void adopt(File oldTaste) {
        try {
            if (oldTaste != null && oldTaste.isFile() && !tasteFile().isFile()) Files.copy(oldTaste.toPath(), tasteFile().toPath());
        } catch (IOException ignored) { }
    }

    /** The extra take built from your choices, or null until there are enough (8). */
    static Engineer.Recipe yours(Target.Style style) { return Taste.yours(taste(), style.name()); }
}
