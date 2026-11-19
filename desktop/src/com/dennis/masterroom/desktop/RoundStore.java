package com.dennis.masterroom.desktop;

import com.dennis.masterroom.engine.Target;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Keeps a round with its takes ("round.properties" in the round's folder), so the app opens where it was left,
 * as the phone does: the numbers, the settings, and where the files are (this round's takes, the round before
 * kept to compare, imported masters). Audio is never copied; files that have gone are simply left out.
 */
final class RoundStore {
    private RoundStore() {}

    static final String FILE = "round.properties";

    record Round(MasterJob.Outcome outcome, List<MasterJob.Take> extras) {}

    static void save(MasterJob.Outcome o, List<MasterJob.Take> extras) throws IOException {
        Properties p = new Properties();
        p.setProperty("version", "1");
        p.setProperty("source", o.source.getAbsolutePath());
        p.setProperty("sourceKey", o.sourceKey);
        p.setProperty("settings", o.settings);
        p.setProperty("shortSettings", o.shortSettings);
        p.setProperty("seconds", d(o.seconds));
        p.setProperty("style", o.style == null ? "" : o.style.name());
        p.setProperty("targetLufs", d(o.targetLufs));
        p.setProperty("targetName", o.targetName == null ? "" : o.targetName);
        p.setProperty("targetPunch", d(o.targetPunch)); p.setProperty("targetWidth", d(o.targetWidth)); p.setProperty("targetLow", d(o.targetLow));
        p.setProperty("targetMid", csv(o.targetMid));
        p.setProperty("beforeLufs", d(o.beforeLufs)); p.setProperty("beforePeak", d(o.beforePeak)); p.setProperty("beforeLra", d(o.beforeLra));
        p.setProperty("beforePunch", d(o.beforePunch));
        p.setProperty("beforeMid", csv(o.beforeMid)); p.setProperty("beforeSide", csv(o.beforeSide));
        p.setProperty("stemNotes", String.join("\n", o.stemNotes));
        List<MasterJob.Take> all = new ArrayList<>(o.takes);
        all.addAll(extras);
        p.setProperty("takes", String.valueOf(all.size()));
        for (int i = 0; i < all.size(); i++) {
            MasterJob.Take t = all.get(i);
            String k = "take." + i + ".";
            p.setProperty(k + "kind", t.kind); p.setProperty(k + "label", t.label); p.setProperty(k + "about", t.about == null ? "" : t.about);
            p.setProperty(k + "verdict", t.verdict == null ? "" : t.verdict); p.setProperty(k + "streaming", t.streaming == null ? "" : t.streaming);
            p.setProperty(k + "report", t.report == null ? "" : t.report);
            p.setProperty(k + "lufs", d(t.lufs)); p.setProperty(k + "truePeak", d(t.truePeak)); p.setProperty(k + "ceiling", d(t.ceiling));
            p.setProperty(k + "lra", d(t.lra)); p.setProperty(k + "punch", d(t.punch));
            p.setProperty(k + "mid", csv(t.mid)); p.setProperty(k + "side", csv(t.side));
            StringBuilder files = new StringBuilder();
            for (File f : t.files) files.append(files.length() == 0 ? "" : "\n").append(f.getAbsolutePath());
            p.setProperty(k + "files", files.toString());
        }
        try (OutputStream out = new FileOutputStream(new File(o.dir, FILE))) { p.store(out, "Master Room round"); }
    }

    /** The round in this folder, or null when it is not there or its source has gone. */
    static Round load(File dir) {
        File f = new File(dir, FILE);
        if (!f.isFile()) return null;
        Properties p = new Properties();
        try (InputStream in = new FileInputStream(f)) { p.load(in); } catch (IOException e) { return null; }
        try {
            MasterJob.Outcome o = new MasterJob.Outcome();
            o.dir = dir;
            o.source = new File(p.getProperty("source"));
            if (!o.source.isFile()) return null;
            o.sourceKey = p.getProperty("sourceKey", "");
            o.settings = p.getProperty("settings", "");
            o.shortSettings = p.getProperty("shortSettings", "");
            o.seconds = n(p, "seconds");
            String st = p.getProperty("style", "");
            o.style = st.isEmpty() ? null : Target.Style.valueOf(st);
            o.targetLufs = n(p, "targetLufs");
            o.targetName = p.getProperty("targetName", "");
            o.targetPunch = n(p, "targetPunch"); o.targetWidth = n(p, "targetWidth"); o.targetLow = n(p, "targetLow");
            o.targetMid = arr(p, "targetMid");
            o.beforeLufs = n(p, "beforeLufs"); o.beforePeak = n(p, "beforePeak"); o.beforeLra = n(p, "beforeLra"); o.beforePunch = n(p, "beforePunch");
            o.beforeMid = arr(p, "beforeMid"); o.beforeSide = arr(p, "beforeSide");
            String notes = p.getProperty("stemNotes", "");
            if (!notes.isEmpty()) o.stemNotes.addAll(Arrays.asList(notes.split("\n")));
            File sheet = new File(dir, "session sheet.txt");
            o.sheet = sheet.isFile() ? Files.readString(sheet.toPath(), StandardCharsets.UTF_8) : "";
            List<MasterJob.Take> extras = new ArrayList<>();
            int count = Integer.parseInt(p.getProperty("takes", "0"));
            for (int i = 0; i < count; i++) {
                String k = "take." + i + ".";
                MasterJob.Take t = new MasterJob.Take();
                t.kind = p.getProperty(k + "kind", "take"); t.label = p.getProperty(k + "label", ""); t.about = p.getProperty(k + "about", "");
                t.verdict = p.getProperty(k + "verdict", ""); t.streaming = p.getProperty(k + "streaming", ""); t.report = p.getProperty(k + "report", "");
                t.lufs = n(p, k + "lufs"); t.truePeak = n(p, k + "truePeak"); t.ceiling = n(p, k + "ceiling"); t.lra = n(p, k + "lra"); t.punch = n(p, k + "punch");
                t.mid = arr(p, k + "mid"); t.side = arr(p, k + "side");
                String[] paths = p.getProperty(k + "files", "").split("\n");
                t.files = new File[paths.length];
                boolean there = true;
                for (int j = 0; j < paths.length; j++) { t.files[j] = new File(paths[j]); there &= t.files[j].isFile(); }
                if (!there) continue;
                if (t.isTake()) o.takes.add(t); else extras.add(t);
            }
            if (o.takes.isEmpty()) return null;
            return new Round(o, extras);
        } catch (RuntimeException | IOException e) {
            return null;
        }
    }

    private static String d(double v) { return String.format(Locale.US, "%.6f", v); }
    private static double n(Properties p, String k) {
        try { return Double.parseDouble(p.getProperty(k, "NaN")); } catch (NumberFormatException e) { return Double.NaN; }
    }
    private static String csv(double[] a) {
        if (a == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < a.length; i++) b.append(i == 0 ? "" : ",").append(String.format(Locale.US, "%.4f", a[i]));
        return b.toString();
    }
    private static double[] arr(Properties p, String k) {
        String v = p.getProperty(k, "");
        if (v.isEmpty()) return null;
        String[] parts = v.split(",");
        double[] out = new double[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = Double.parseDouble(parts[i]);
        return out;
    }
}
