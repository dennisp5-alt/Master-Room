package com.dennis.masterroom.ui;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import com.dennis.masterroom.engine.Audio;
import com.dennis.masterroom.engine.Engineer;
import com.dennis.masterroom.engine.Listen;
import com.dennis.masterroom.engine.Loudness;
import com.dennis.masterroom.engine.Target;
import com.dennis.masterroom.engine.Wav;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One screen: load a song, master it, compare by ear, save the take you choose. */
public final class MainActivity extends Activity {
    private static final int PICK_SONG = 1, PICK_REFERENCE = 2, PICK_MASTER = 3;
    private static final int BG = 0xFF14161A, CARD = 0xFF1F2228, TEXT = 0xFFECE7DF, DIM = 0xFF9EA3AB, ACCENT = 0xFFE8A33D;
    private static final double[] LEVELS = {-14, -12, -11, -10};

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AbPlayer player = new AbPlayer();

    private LinearLayout column, styleRow, levelRow, versionRow;
    private TextView songLine, referenceLine, statusLine, sheetText;
    private Button masterButton, playButton, saveButton, importButton;
    private ProgressBar progress;
    private SeekBar seek;
    private CheckBox blind;

    private Target.Style style = Target.Style.COUNTRY;
    private double level = -14;
    private Uri songUri;
    private String songName = "song";
    private final List<Integer> order = new ArrayList<>();
    private int selected;
    private final List<Saved> saved = new ArrayList<>();
    private File sessionDir;
    private String sheet = "";

    private static final class Saved { String label; File[] files; }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        build();
        refreshReferences();
        main.postDelayed(this::tick, 250);
    }

    @Override protected void onDestroy() {
        player.close();
        worker.shutdownNow();
        super.onDestroy();
    }

    // Screen ------------------------------------------------------------------

    private void build() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        column.setPadding(pad, dp(28), pad, dp(40));
        scroll.addView(column);
        setContentView(scroll);

        TextView title = text("Master Room", 26, TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        column.addView(title);
        TextView sub = text("Trial. A studio's signal path, on your phone.", 14, DIM);
        column.addView(sub);

        heading("Song");
        songLine = text("No song loaded", 15, TEXT);
        column.addView(songLine);
        column.addView(button("Load song", v -> pick(PICK_SONG)));

        heading("Style");
        styleRow = row();
        column.addView(styleRow);
        referenceLine = text("", 14, DIM);
        column.addView(referenceLine);
        column.addView(button("Add a reference record", v -> pick(PICK_REFERENCE)));
        drawStyles();

        heading("Loudness");
        levelRow = row();
        column.addView(levelRow);
        drawLevels();

        masterButton = button("Master it", v -> master());
        masterButton.setEnabled(false);
        column.addView(masterButton);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.GONE);
        column.addView(progress);
        statusLine = text("", 14, DIM);
        column.addView(statusLine);

        heading("Compare, level-matched");
        versionRow = new LinearLayout(this);
        versionRow.setOrientation(LinearLayout.VERTICAL);
        column.addView(versionRow);
        seek = new SeekBar(this);
        seek.setMax(1000);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) { if (user) player.seek(p / 1000.0); }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        column.addView(seek);
        playButton = button("Play", v -> togglePlay());
        playButton.setEnabled(false);
        column.addView(playButton);
        blind = new CheckBox(this);
        blind.setText("Blind test: hide the names and shuffle");
        blind.setTextColor(TEXT);
        blind.setOnCheckedChangeListener((b, on) -> {
            if (on && !order.isEmpty()) { Collections.shuffle(order); player.select(order.get(selected)); }
            drawVersions();
        });
        column.addView(blind);
        importButton = button("Import another master (Stem Lab)", v -> pick(PICK_MASTER));
        importButton.setEnabled(false);
        column.addView(importButton);
        saveButton = button("Save the selected take", v -> saveSelected());
        saveButton.setEnabled(false);
        column.addView(saveButton);

        heading("Session sheet");
        sheetText = text("", 14, TEXT);
        column.addView(sheetText);
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(sp); t.setTextColor(color);
        t.setPadding(0, dp(2), 0, dp(2));
        return t;
    }

    private void heading(String s) {
        TextView t = text(s, 13, ACCENT);
        t.setAllCaps(true);
        t.setPadding(0, dp(22), 0, dp(4));
        column.addView(t);
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        return r;
    }

    private Button button(String label, View.OnClickListener click) {
        Button b = new Button(this);
        b.setText(label); b.setAllCaps(false); b.setTextColor(TEXT); b.setTextSize(15);
        b.setBackground(round(CARD));
        b.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private Button chip(String label, boolean on, View.OnClickListener click) {
        Button b = new Button(this);
        b.setText(label); b.setAllCaps(false); b.setTextSize(14);
        b.setTextColor(on ? BG : TEXT);
        b.setBackground(round(on ? ACCENT : CARD));
        b.setOnClickListener(click);
        b.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1);
        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        b.setLayoutParams(lp);
        return b;
    }

    private GradientDrawable round(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color); d.setCornerRadius(dp(10));
        return d;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void drawStyles() {
        styleRow.removeAllViews();
        for (Target.Style s : Target.Style.values()) {
            final Target.Style pick = s;
            styleRow.addView(chip(s.name().charAt(0) + s.name().substring(1).toLowerCase(Locale.US), s == style, v -> {
                style = pick; drawStyles(); refreshReferences();
            }));
        }
    }

    private void drawLevels() {
        levelRow.removeAllViews();
        for (double l : LEVELS) {
            final double pick = l;
            levelRow.addView(chip(String.format(Locale.US, "%.0f", l), l == level, v -> { level = pick; drawLevels(); }));
        }
    }

    private void drawVersions() {
        versionRow.removeAllViews();
        LinearLayout r = null;
        for (int i = 0; i < order.size(); i++) {
            if (i % 3 == 0) { r = row(); versionRow.addView(r); }
            final int shown = i, index = order.get(i);
            String name = blind.isChecked() ? "Version " + (i + 1) : player.get(index).name;
            r.addView(chip(name, shown == selected, v -> { selected = shown; player.select(index); drawVersions(); }));
        }
    }

    private void tick() {
        if (player.isPlaying()) seek.setProgress((int) (player.fraction() * 1000));
        else if (playButton.getText().toString().equals("Pause")) playButton.setText("Play");
        main.postDelayed(this::tick, 250);
    }

    private void togglePlay() {
        if (player.isPlaying()) { player.pause(); playButton.setText("Play"); }
        else { player.play(); playButton.setText("Pause"); }
    }

    private void pick(int code) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("audio/*");
        startActivityForResult(i, code);
    }

    @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (code == PICK_SONG) {
            songUri = uri; songName = nameOf(uri);
            songLine.setText(songName);
            masterButton.setEnabled(true);
        } else if (code == PICK_REFERENCE) {
            addReference(uri);
        } else if (code == PICK_MASTER) {
            importMaster(uri);
        }
    }

    private String nameOf(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) { String n = c.getString(i); int dot = n.lastIndexOf('.'); return dot > 0 ? n.substring(0, dot) : n; }
            }
        } catch (RuntimeException ignored) { }
        return "song";
    }

    private void status(String s) { main.post(() -> statusLine.setText(s)); }

    private void fail(Throwable e) {
        String why = e instanceof OutOfMemoryError ? "The phone ran out of memory for this song" : String.valueOf(e.getMessage());
        main.post(() -> { busy(false); statusLine.setText(why); });
    }

    private void busy(boolean on) {
        masterButton.setEnabled(!on && songUri != null);
        progress.setVisibility(on ? View.VISIBLE : View.GONE);
        if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    // Mastering ---------------------------------------------------------------

    private void master() {
        final double asked = level;
        final Target target = currentTarget();
        final Uri uri = songUri;
        final String name = songName;
        player.clear(); order.clear(); saved.clear(); selected = 0;
        drawVersions();
        playButton.setEnabled(false); saveButton.setEnabled(false); importButton.setEnabled(false);
        busy(true);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        final PowerManager.WakeLock lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "masterroom:render");
        lock.acquire(40 * 60 * 1000L);
        worker.execute(() -> {
            try {
                long t0 = System.nanoTime();
                File dir = new File(getExternalFilesDir(null), "session");
                deleteAll(dir);
                dir.mkdirs();
                sessionDir = dir;
                status("Opening the song");
                Audio in = Decoder.open(this, uri);
                File sourceFile = new File(dir, "source.wav");
                Wav.write(sourceFile, in, 24);
                Engineer.Progress bar = (step, p) -> main.post(() -> { statusLine.setText(step); progress.setProgress(p); });
                Engineer.Session session = Engineer.prepare(in, target, bar);
                player.add(new AbPlayer.Version("Source", sourceFile, session.before.loudness.integrated));
                in = null;
                List<Engineer.Result> results = new ArrayList<>();
                for (Engineer.Take take : Engineer.Take.values()) {
                    Engineer.Result r = Engineer.master(session, take, asked, bar);
                    File[] files = Engineer.deliver(r, dir, name + "_" + take.label);
                    r.master = null;
                    results.add(r);
                    Saved s = new Saved(); s.label = take.label; s.files = files;
                    saved.add(s);
                    player.add(new AbPlayer.Version(take.label, files[0], r.after.loudness.integrated));
                }
                session.release();
                final String text = Engineer.sheet(session, results)
                        + String.format(Locale.US, "%nFinished in %.1f minutes", (System.nanoTime() - t0) / 60e9);
                sheet = text;
                main.post(() -> {
                    busy(false);
                    statusLine.setText("Done. Compare them level-matched, then save the one you like.");
                    sheetText.setText(text);
                    for (int i = 0; i < player.size(); i++) order.add(i);
                    drawVersions();
                    playButton.setEnabled(true); saveButton.setEnabled(true); importButton.setEnabled(true);
                });
            } catch (Throwable e) {
                fail(e);
            } finally {
                if (lock.isHeld()) lock.release();
            }
        });
    }

    private void importMaster(Uri uri) {
        final String name = nameOf(uri);
        status("Measuring " + name);
        worker.execute(() -> {
            try {
                Audio a = Decoder.open(this, uri);
                File f = new File(sessionDir, "imported_" + System.currentTimeMillis() + ".wav");
                Wav.write(f, a, 24);
                double lufs = Loudness.measure(a).integrated;
                player.add(new AbPlayer.Version(name.length() > 14 ? name.substring(0, 14) : name, f, lufs));
                main.post(() -> {
                    order.add(player.size() - 1);
                    statusLine.setText(String.format(Locale.US, "Imported at %.1f LUFS. It is level-matched with the others.", lufs));
                    drawVersions();
                });
            } catch (Throwable e) {
                fail(e);
            }
        });
    }

    private void saveSelected() {
        if (order.isEmpty()) return;
        final String name = player.get(order.get(selected)).name;
        Saved pick = null;
        for (Saved s : saved) if (s.label.equals(name)) pick = s;
        if (pick == null) { statusLine.setText("Pick Clean, Warm or Punch to save"); return; }
        final Saved chosen = pick;
        worker.execute(() -> {
            try {
                for (File f : chosen.files) store(f, "audio/wav");
                File notes = new File(sessionDir, songName + "_session.txt");
                try (PrintWriter w = new PrintWriter(new FileWriter(notes))) { w.print(sheet); }
                store(notes, "text/plain");
                status("Saved to Download/Master Room");
            } catch (Throwable e) {
                fail(e);
            }
        });
    }

    private void store(File f, String mime) throws IOException {
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, f.getName());
        v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Master Room");
        Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new IOException("Could not save " + f.getName());
        try (OutputStream out = getContentResolver().openOutputStream(uri); InputStream in = new FileInputStream(f)) {
            if (out == null) throw new IOException("Could not save " + f.getName());
            byte[] b = new byte[1 << 16];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
        }
    }

    private static void deleteAll(File dir) {
        File[] list = dir.listFiles();
        if (list != null) for (File f : list) f.delete();
    }

    // Reference shelf: only the measured numbers are stored ---------------------

    private File shelf() { return new File(getFilesDir(), "references_" + style.name() + ".txt"); }

    private List<Listen> shelfRecords() {
        List<Listen> out = new ArrayList<>();
        File f = shelf();
        if (!f.exists()) return out;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\\|");
                if (p.length == 3) out.add(Listen.fromBands(numbers(p[1]), numbers(p[2])));
            }
        } catch (IOException | NumberFormatException ignored) { }
        return out;
    }

    private static double[] numbers(String csv) {
        String[] p = csv.split(",");
        double[] d = new double[p.length];
        for (int i = 0; i < p.length; i++) d[i] = Double.parseDouble(p[i]);
        return d;
    }

    private static String csv(double[] d) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < d.length; i++) b.append(i == 0 ? "" : ",").append(String.format(Locale.US, "%.2f", d[i]));
        return b.toString();
    }

    private Target currentTarget() {
        List<Listen> refs = shelfRecords();
        return refs.isEmpty() ? Target.builtIn(style) : Target.fromReferences(refs.size() + " reference records", refs);
    }

    private void refreshReferences() {
        int n = shelfRecords().size();
        referenceLine.setText(n == 0 ? "Using the starting curve. Add 3 to 5 records you want to sound like."
                : n + " reference record" + (n == 1 ? "" : "s") + " for this style. Only their measurements are kept.");
    }

    private void addReference(Uri uri) {
        final String name = nameOf(uri).replace('|', ' ');
        final File file = shelf();
        status("Measuring " + name);
        busy(true);
        worker.execute(() -> {
            try {
                Audio a = Decoder.open(this, uri);
                Listen l = Listen.to(a);
                try (PrintWriter w = new PrintWriter(new FileWriter(file, true))) {
                    w.println(name + "|" + csv(l.midDb) + "|" + csv(l.sideDb));
                }
                main.post(() -> { busy(false); statusLine.setText("Added " + name); refreshReferences(); });
            } catch (Throwable e) {
                fail(e);
            }
        });
    }
}
