package com.dennis.masterroom.ui;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.dennis.masterroom.R;
import com.dennis.masterroom.engine.Audio;
import com.dennis.masterroom.engine.Engineer;
import com.dennis.masterroom.engine.Listen;
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

/** Session screen: load, choose, master. Compare screen: listen, decide, save. */
public final class MainActivity extends Activity {
    private static final int PICK_SONG = 1, PICK_REFERENCE = 2, PICK_MASTER = 3;
    private static final double[] LEVELS = {-14, -12, -11, -10};
    private static final String[] STAGES = {"EQ", "DYN", "COMP", "TONE", "CLIP", "LIMIT"};
    private static final int MATCH = LinearLayout.LayoutParams.MATCH_PARENT, WRAP = LinearLayout.LayoutParams.WRAP_CONTENT;

    private static final class Item {
        final String name; final double lufs, lra; final double[] mid; final File[] files;
        Item(String name, double lufs, double lra, double[] mid, File[] files) {
            this.name = name; this.lufs = lufs; this.lra = lra; this.mid = mid; this.files = files;
        }
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AbPlayer player = new AbPlayer();

    private ScrollView sessionScreen, compareScreen;
    private LinearLayout column, styleRow, levelRow, versionList;
    private TextView songName, songMeta, songAction, referenceLine, statusLine, sheetText, timeNow, timeEnd;
    private Button compareLink, masterButton, saveButton, importButton, sheetButton, waveTab, toneTab;
    private ImageButton playButton;
    private ProgressBar progress;
    private MeterView meter;
    private WaveView wave;
    private ToneView tone;
    private CheckBox blind;
    private final View[] leds = new View[STAGES.length];

    private Target.Style style = Target.Style.COUNTRY;
    private double level = -14;
    private Uri songUri;
    private String song = "song";
    private final List<Item> items = new ArrayList<>();
    private final List<Integer> order = new ArrayList<>();
    private int selected;
    private double[] targetMid;
    private File sessionDir;
    private String sheet = "";
    private boolean working;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Look.load(this);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Look.BG);
        sessionScreen = buildSession();
        compareScreen = buildCompare();
        compareScreen.setVisibility(View.GONE);
        root.addView(sessionScreen);
        root.addView(compareScreen);
        setContentView(root);
        refreshReferences();
        main.postDelayed(this::tick, 100);
    }

    @Override protected void onDestroy() {
        player.close();
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (compareScreen.getVisibility() == View.VISIBLE) showCompare(false);
        else super.onBackPressed();
    }

    private void showCompare(boolean on) {
        if (!on) { player.pause(); }
        compareScreen.setVisibility(on ? View.VISIBLE : View.GONE);
        sessionScreen.setVisibility(on ? View.GONE : View.VISIBLE);
    }

    // Parts -------------------------------------------------------------------

    private ScrollView screen() {
        ScrollView s = new ScrollView(this);
        s.setBackgroundColor(Look.BG);
        s.setFillViewport(true);
        column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(Look.dp(16), Look.dp(16), Look.dp(16), Look.dp(28));
        s.addView(column);
        return s;
    }

    private LinearLayout.LayoutParams line(int height, int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(MATCH, height);
        p.topMargin = Look.dp(topDp);
        return p;
    }

    private TextView text(String s, Typeface face, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        Look.type(t, face, sp, color);
        return t;
    }

    private void heading(String s) {
        TextView t = text(s, Look.label, 15, Look.DIM);
        t.setLetterSpacing(0.12f);
        column.addView(t, line(WRAP, 16));
    }

    private Button button(String s, int fill, int ink, View.OnClickListener click) {
        Button b = new Button(this);
        Look.flat(b);
        b.setText(s);
        Look.type(b, Look.label, 17, ink);
        Look.surface(b, Look.shape(fill, fill == Look.PANEL ? Look.LINE : 0, 5));
        b.setOnClickListener(click);
        return b;
    }

    private Button bigButton(String s, int fill, View.OnClickListener click) {
        Button b = button(s, fill, Look.BG, click);
        Look.type(b, Look.title, 22, Look.BG);
        b.setLetterSpacing(0.12f);
        return b;
    }

    private Button chip(String s, boolean on, Typeface face, View.OnClickListener click) {
        Button b = button(s, on ? Look.AMBER : Look.PANEL, on ? Look.BG : Look.TEXT, click);
        Look.type(b, face, 16, on ? Look.BG : Look.TEXT);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, Look.dp(46), 1);
        p.setMargins(Look.dp(3), 0, Look.dp(3), 0);
        b.setLayoutParams(p);
        return b;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    private View grow() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1));
        return v;
    }

    // Session screen ----------------------------------------------------------

    private ScrollView buildSession() {
        ScrollView s = screen();
        LinearLayout header = row();
        ImageView mark = new ImageView(this);
        mark.setImageResource(R.drawable.ic_mark);
        mark.setContentDescription("Master Room");
        header.addView(mark, new LinearLayout.LayoutParams(Look.dp(30), Look.dp(30)));
        TextView name = text("MASTER ROOM", Look.title, 22, Look.TEXT);
        name.setLetterSpacing(0.14f);
        name.setPadding(Look.dp(10), 0, 0, 0);
        header.addView(name);
        header.addView(grow());
        compareLink = new Button(this);
        Look.flat(compareLink);
        compareLink.setText("COMPARE");
        Look.type(compareLink, Look.label, 16, Look.AMBER);
        compareLink.setLetterSpacing(0.08f);
        compareLink.setBackground(null);
        compareLink.setVisibility(View.GONE);
        compareLink.setOnClickListener(v -> showCompare(true));
        header.addView(compareLink, new LinearLayout.LayoutParams(WRAP, Look.dp(44)));
        column.addView(header, line(Look.dp(44), 0));

        LinearLayout card = row();
        card.setPadding(Look.dp(14), Look.dp(10), Look.dp(14), Look.dp(10));
        Look.surface(card, Look.shape(Look.PANEL, Look.LINE, 6));
        card.setClickable(true);
        card.setOnClickListener(v -> pick(PICK_SONG));
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        songName = text("No song loaded", Look.strong, 18, Look.TEXT);
        songName.setSingleLine(true);
        songMeta = text("WAV, or anything the phone can play", Look.mono, 12, Look.DIM);
        info.addView(songName);
        info.addView(songMeta);
        card.addView(info, new LinearLayout.LayoutParams(0, WRAP, 1));
        songAction = text("Load", Look.strong, 15, Look.AMBER);
        card.addView(songAction);
        column.addView(card, line(WRAP, 8));

        FrameLayout well = new FrameLayout(this);
        well.setPadding(Look.dp(10), Look.dp(10), Look.dp(10), Look.dp(10));
        well.setBackground(Look.shape(Look.WELL, Look.LINE, 6));
        meter = new MeterView(this);
        well.addView(meter);
        column.addView(well, line(WRAP, 12));

        heading("STYLE");
        styleRow = row();
        column.addView(styleRow, line(Look.dp(46), 6));
        LinearLayout refs = row();
        referenceLine = text("", Look.body, 13, Look.DIM);
        refs.addView(referenceLine, new LinearLayout.LayoutParams(0, WRAP, 1));
        Button add = new Button(this);
        Look.flat(add);
        add.setText("ADD RECORD");
        Look.type(add, Look.label, 15, Look.AMBER);
        add.setLetterSpacing(0.06f);
        add.setBackground(null);
        add.setOnClickListener(v -> pick(PICK_REFERENCE));
        refs.addView(add, new LinearLayout.LayoutParams(WRAP, Look.dp(44)));
        column.addView(refs, line(WRAP, 2));
        drawStyles();

        heading("LOUDNESS");
        levelRow = row();
        column.addView(levelRow, line(Look.dp(46), 6));
        drawLevels();

        LinearLayout path = new LinearLayout(this);
        path.setOrientation(LinearLayout.HORIZONTAL);
        path.setPadding(0, Look.dp(10), 0, Look.dp(10));
        GradientDrawable rule = Look.shape(Look.BG, Look.LINE, 0);
        path.setBackground(rule);
        path.setContentDescription("Signal path");
        for (int i = 0; i < STAGES.length; i++) {
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            View led = new View(this);
            led.setBackground(Look.shape(Look.AMBER, 0, 4));
            leds[i] = led;
            cell.addView(led, new LinearLayout.LayoutParams(Look.dp(8), Look.dp(8)));
            TextView t = text(STAGES[i], Look.label, 13, Look.MID);
            t.setPadding(0, Look.dp(6), 0, 0);
            cell.addView(t);
            path.addView(cell, new LinearLayout.LayoutParams(0, WRAP, 1));
        }
        column.addView(path, line(WRAP, 16));

        masterButton = bigButton("MASTER IT", Look.AMBER, v -> master());
        Look.enable(masterButton, false);
        column.addView(masterButton, line(Look.dp(56), 14));
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgressTintList(ColorStateList.valueOf(Look.AMBER));
        progress.setVisibility(View.GONE);
        column.addView(progress, line(WRAP, 8));
        statusLine = text("", Look.body, 14, Look.DIM);
        column.addView(statusLine, line(WRAP, 6));
        return s;
    }

    private void drawStyles() {
        styleRow.removeAllViews();
        for (Target.Style s : Target.Style.values()) {
            final Target.Style pick = s;
            String n = s.name().charAt(0) + s.name().substring(1).toLowerCase(Locale.US);
            styleRow.addView(chip(n, s == style, Look.strong, v -> { style = pick; drawStyles(); refreshReferences(); }));
        }
    }

    private void drawLevels() {
        levelRow.removeAllViews();
        for (double l : LEVELS) {
            final double pick = l;
            levelRow.addView(chip(String.format(Locale.US, "%.0f", l), l == level, Look.mono, v -> {
                level = pick; drawLevels(); meter.setValue(pick);
            }));
        }
    }

    /** Lights the signal path up to the stage now running; -1 lights all of it. */
    private void lightPath(int stage) {
        for (int i = 0; i < leds.length; i++) {
            leds[i].setBackground(Look.shape(stage < 0 || i <= stage ? Look.AMBER : Look.LINE, 0, 4));
        }
    }

    private static int stageOf(String step) {
        if (step.contains("EQ")) return 0;
        if (step.contains("harsh")) return 1;
        if (step.contains("riding") || step.contains("bus compressor")) return 2;
        if (step.contains("colour")) return 3;
        if (step.contains("level")) return 4;
        if (step.contains("clipper") || step.contains("checking")) return 5;
        return 0;
    }

    // Compare screen ----------------------------------------------------------

    private ScrollView buildCompare() {
        ScrollView s = screen();
        LinearLayout header = row();
        ImageButton back = new ImageButton(this);
        back.setImageResource(R.drawable.ic_back);
        back.setBackground(null);
        back.setContentDescription("Back");
        back.setOnClickListener(v -> showCompare(false));
        header.addView(back, new LinearLayout.LayoutParams(Look.dp(44), Look.dp(44)));
        TextView title = text("COMPARE", Look.title, 22, Look.TEXT);
        title.setLetterSpacing(0.14f);
        header.addView(title);
        header.addView(grow());
        TextView pill = text("LEVEL-MATCHED", Look.label, 14, Look.AMBER);
        pill.setLetterSpacing(0.08f);
        pill.setPadding(Look.dp(10), Look.dp(5), Look.dp(10), Look.dp(5));
        pill.setBackground(Look.shape(Look.BG, Look.AMBER, 4));
        header.addView(pill);
        column.addView(header, line(Look.dp(44), 0));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(Look.dp(12), Look.dp(8), Look.dp(12), Look.dp(12));
        card.setBackground(Look.shape(Look.WELL, Look.LINE, 6));
        LinearLayout tabs = row();
        waveTab = button("WAVE", Look.WELL, Look.TEXT, v -> showTone(false));
        toneTab = button("TONE", Look.WELL, Look.DIM, v -> showTone(true));
        waveTab.setLetterSpacing(0.08f);
        toneTab.setLetterSpacing(0.08f);
        tabs.addView(waveTab, new LinearLayout.LayoutParams(Look.dp(70), Look.dp(44)));
        tabs.addView(toneTab, new LinearLayout.LayoutParams(Look.dp(70), Look.dp(44)));
        tabs.addView(grow());
        card.addView(tabs);
        wave = new WaveView(this);
        wave.setSeek(player::seek);
        card.addView(wave, line(Look.dp(112), 4));
        tone = new ToneView(this);
        tone.setVisibility(View.GONE);
        card.addView(tone, line(Look.dp(190), 4));
        LinearLayout times = row();
        timeNow = text("0:00", Look.mono, 13, Look.DIM);
        timeEnd = text("0:00", Look.mono, 13, Look.DIM);
        times.addView(timeNow);
        times.addView(grow());
        times.addView(timeEnd);
        card.addView(times, line(WRAP, 8));
        column.addView(card, line(WRAP, 10));

        versionList = new LinearLayout(this);
        versionList.setOrientation(LinearLayout.VERTICAL);
        column.addView(versionList, line(WRAP, 6));

        importButton = button("Import Stem Lab master", Look.BG, Look.TEXT, v -> pick(PICK_MASTER));
        Look.type(importButton, Look.body, 15, Look.TEXT);
        GradientDrawable dash = Look.shape(Look.BG, 0, 6);
        dash.setStroke(Look.dp(1), Look.BAR, Look.dp(5), Look.dp(4));
        Look.surface(importButton, dash);
        column.addView(importButton, line(Look.dp(46), 8));

        LinearLayout blindRow = row();
        blindRow.addView(text("Blind test", Look.body, 16, Look.TEXT), new LinearLayout.LayoutParams(0, WRAP, 1));
        blind = new CheckBox(this);
        blind.setButtonTintList(ColorStateList.valueOf(Look.AMBER));
        blind.setContentDescription("Blind test: hide the names and shuffle");
        blind.setOnCheckedChangeListener((b, on) -> {
            if (on && !order.isEmpty()) { Collections.shuffle(order); player.select(order.get(selected)); }
            drawVersions();
        });
        blindRow.addView(blind);
        column.addView(blindRow, line(Look.dp(48), 4));

        LinearLayout controls = row();
        playButton = new ImageButton(this);
        playButton.setImageResource(R.drawable.ic_play);
        Look.surface(playButton, Look.shape(Look.TEXT, 0, 6));
        playButton.setContentDescription("Play");
        playButton.setOnClickListener(v -> togglePlay());
        controls.addView(playButton, new LinearLayout.LayoutParams(Look.dp(64), Look.dp(60)));
        saveButton = bigButton("SAVE", Look.AMBER, v -> saveSelected());
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, Look.dp(60), 1);
        sp.leftMargin = Look.dp(10);
        controls.addView(saveButton, sp);
        column.addView(controls, line(WRAP, 6));

        sheetButton = button("Session sheet", Look.PANEL, Look.TEXT, v ->
                sheetText.setVisibility(sheetText.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        Look.type(sheetButton, Look.body, 15, Look.TEXT);
        column.addView(sheetButton, line(Look.dp(46), 12));
        sheetText = text("", Look.body, 14, Look.MID);
        sheetText.setVisibility(View.GONE);
        column.addView(sheetText, line(WRAP, 10));
        return s;
    }

    private void showTone(boolean on) {
        wave.setVisibility(on ? View.GONE : View.VISIBLE);
        tone.setVisibility(on ? View.VISIBLE : View.GONE);
        waveTab.setTextColor(on ? Look.DIM : Look.TEXT);
        toneTab.setTextColor(on ? Look.TEXT : Look.DIM);
    }

    private void drawVersions() {
        versionList.removeAllViews();
        boolean hide = blind.isChecked();
        for (int i = 0; i < order.size(); i++) {
            final int shown = i, index = order.get(i);
            Item item = items.get(index);
            boolean on = i == selected;
            LinearLayout r = row();
            r.setPadding(Look.dp(12), 0, Look.dp(12), 0);
            Look.surface(r, Look.shape(on ? Look.RAISED : Look.PANEL, on ? Look.AMBER : Look.LINE, 6));
            r.setClickable(true);
            r.setOnClickListener(v -> { selected = shown; player.select(index); drawVersions(); });
            r.addView(text(hide ? "Version " + (i + 1) : item.name, Look.label, 21, Look.TEXT), new LinearLayout.LayoutParams(0, WRAP, 1));
            if (!hide) {
                TextView lufs = text(String.format(Locale.US, "%.1f LUFS", item.lufs), Look.mono, 13, Look.MID);
                r.addView(lufs, new LinearLayout.LayoutParams(Look.dp(82), WRAP));
                TextView lra = text(String.format(Locale.US, "%.1f LU", item.lra), Look.mono, 13, Look.DIM);
                r.addView(lra, new LinearLayout.LayoutParams(Look.dp(62), WRAP));
            }
            View dot = new View(this);
            dot.setBackground(Look.shape(on ? Look.AMBER : Look.LINE, 0, 6));
            r.addView(dot, new LinearLayout.LayoutParams(Look.dp(12), Look.dp(12)));
            versionList.addView(r, line(Look.dp(58), i == 0 ? 0 : 6));
        }
        if (order.isEmpty()) return;
        Item item = items.get(order.get(selected));
        boolean canSave = !hide && item.files != null;
        saveButton.setText(hide ? "BLIND TEST ON" : item.files != null ? "SAVE " + item.name.toUpperCase(Locale.US) : "PICK A TAKE");
        Look.enable(saveButton, canSave);
        double[] source = targetMid == null ? null : ToneView.againstTarget(items.get(0).mid, targetMid);
        double[] take = targetMid == null || item == items.get(0) || item.mid == null ? null : ToneView.againstTarget(item.mid, targetMid);
        tone.show(source, take, hide);
    }

    private static String clock(double seconds) {
        int s = (int) Math.max(0, seconds);
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    private void tick() {
        if (compareScreen.getVisibility() == View.VISIBLE && player.size() > 0) {
            double f = player.fraction();
            if (player.isPlaying()) wave.setFraction(f);
            double length = (double) player.get(0).header.frames / player.get(0).header.rate;
            timeNow.setText(clock(f * length));
            timeEnd.setText(clock(length));
            playButton.setImageResource(player.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
            playButton.setContentDescription(player.isPlaying() ? "Pause" : "Play");
        }
        main.postDelayed(this::tick, 100);
    }

    private void togglePlay() {
        if (player.isPlaying()) player.pause(); else player.play();
    }

    // Files -------------------------------------------------------------------

    private void pick(int code) {
        if (working) return;
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
            songUri = uri; song = nameOf(uri);
            songName.setText(song);
            songMeta.setText("Ready to master");
            songAction.setText("Change");
            Look.enable(masterButton, true);
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
        main.post(() -> {
            busy(false);
            lightPath(-1);
            statusLine.setText(why);
            if (compareScreen.getVisibility() == View.VISIBLE) Toast.makeText(this, why, Toast.LENGTH_LONG).show();
        });
    }

    private void busy(boolean on) {
        working = on;
        Look.enable(masterButton, !on && songUri != null);
        progress.setVisibility(on ? View.VISIBLE : View.GONE);
        if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    // Mastering ---------------------------------------------------------------

    private void master() {
        final double asked = level;
        final Target target = currentTarget();
        final Uri uri = songUri;
        final String name = song;
        player.clear(); order.clear(); items.clear(); selected = 0;
        compareLink.setVisibility(View.GONE);
        blind.setChecked(false);
        drawVersions();
        busy(true);
        lightPath(0);
        progress.setProgress(0);
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
                final int[] takeIndex = {-1};
                final int takeCount = Engineer.Take.values().length;
                Engineer.Progress bar = (step, p) -> {
                    int overall = takeIndex[0] < 0 ? p : 15 + (int) ((takeIndex[0] + Math.max(0, p - 20) / 80.0) * 85 / takeCount);
                    main.post(() -> { statusLine.setText(step); progress.setProgress(overall); lightPath(stageOf(step)); });
                };
                final String meta = String.format(Locale.US, "%s  %d kHz", clock(in.seconds()), Math.round(in.rate / 1000f));
                Engineer.Session session = Engineer.prepare(in, target, bar);
                final double sourceLufs = session.before.loudness.integrated;
                main.post(() -> songMeta.setText(String.format(Locale.US, "%s  %.1f LUFS", meta, sourceLufs)));
                player.add(new AbPlayer.Version("Source", sourceFile, sourceLufs));
                items.add(new Item("Source", sourceLufs, session.before.loudness.lra, session.before.midDb, null));
                in = null;
                targetMid = target.midDb;
                List<Engineer.Result> results = new ArrayList<>();
                float[] bars = null;
                for (Engineer.Take take : Engineer.Take.values()) {
                    takeIndex[0]++;
                    Engineer.Result r = Engineer.master(session, take, asked, bar);
                    if (bars == null) bars = WaveView.measure(r.master, 72);
                    File[] files = Engineer.deliver(r, dir, name + "_" + take.label);
                    r.master = null;
                    results.add(r);
                    player.add(new AbPlayer.Version(take.label, files[0], r.after.loudness.integrated));
                    items.add(new Item(take.label, r.after.loudness.integrated, r.after.loudness.lra, r.after.midDb, files));
                }
                session.release();
                final String text = Engineer.sheet(session, results)
                        + String.format(Locale.US, "%nFinished in %.1f minutes", (System.nanoTime() - t0) / 60e9);
                sheet = text;
                final float[] shape = bars;
                main.post(() -> {
                    busy(false);
                    lightPath(-1);
                    statusLine.setText("Done. Keep this screen open while you compare or master again.");
                    sheetText.setText(text);
                    wave.setBars(shape);
                    wave.setFraction(0);
                    for (int i = 0; i < player.size(); i++) order.add(i);
                    selected = 0;
                    player.select(0);
                    drawVersions();
                    compareLink.setVisibility(View.VISIBLE);
                    showCompare(true);
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
        importButton.setText("Measuring " + name);
        worker.execute(() -> {
            try {
                Audio a = Decoder.open(this, uri);
                File f = new File(sessionDir, "imported_" + System.currentTimeMillis() + ".wav");
                Wav.write(f, a, 24);
                Listen l = Listen.to(a);
                final String shortName = name.length() > 16 ? name.substring(0, 16) : name;
                player.add(new AbPlayer.Version(shortName, f, l.loudness.integrated));
                main.post(() -> {
                    items.add(new Item(shortName, l.loudness.integrated, l.loudness.lra, l.midDb, null));
                    order.add(player.size() - 1);
                    importButton.setText("Import Stem Lab master");
                    drawVersions();
                });
            } catch (Throwable e) {
                main.post(() -> importButton.setText("Import Stem Lab master"));
                fail(e);
            }
        });
    }

    private void saveSelected() {
        if (order.isEmpty()) return;
        final Item chosen = items.get(order.get(selected));
        if (chosen.files == null) return;
        Look.enable(saveButton, false);
        worker.execute(() -> {
            try {
                for (File f : chosen.files) store(f, "audio/wav");
                File notes = new File(sessionDir, song + "_session.txt");
                try (PrintWriter w = new PrintWriter(new FileWriter(notes))) { w.print(sheet); }
                store(notes, "text/plain");
                main.post(() -> {
                    Look.enable(saveButton, true);
                    Toast.makeText(this, "Saved to Download/Master Room", Toast.LENGTH_LONG).show();
                });
            } catch (Throwable e) {
                main.post(() -> Look.enable(saveButton, true));
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
        referenceLine.setText(n == 0 ? "Starting curve. Add 3 to 5 records you want to sound like."
                : n + " reference record" + (n == 1 ? "" : "s") + " for this style.");
    }

    private void addReference(Uri uri) {
        final String name = nameOf(uri).replace('|', ' ');
        final File file = shelf();
        statusLine.setText("Measuring " + name);
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
