package com.dennis.masterroom.ui;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
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
import android.widget.EditText;
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
import com.dennis.masterroom.engine.Distortion;
import com.dennis.masterroom.engine.Engineer;
import com.dennis.masterroom.engine.Listen;
import com.dennis.masterroom.engine.Preview;
import com.dennis.masterroom.engine.Release;
import com.dennis.masterroom.engine.Streaming;
import com.dennis.masterroom.engine.Target;
import com.dennis.masterroom.engine.Taste;
import com.dennis.masterroom.engine.Wav;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
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
import java.util.function.Consumer;

/** Session screen: load, choose, master. Compare screen: listen, decide, save. */
public final class MainActivity extends Activity {
    private static final int PICK_SONG = 1, PICK_REFERENCE = 2, PICK_MASTER = 3;
    private static final int BARS = 120, BLIND_OFF = 0, BLIND_ON = 1, BLIND_RESULTS = 2;
    private static final double[] LEVELS = {-14, -12, -11, -10};
    private static final String[] STAGES = {"EQ", "DYN", "COMP", "TONE", "CLIP", "LIMIT"};

    /** One version in the comparison. Numbers are always measured, never estimated. */
    private static final class Item {
        String name;
        final String take; final double lufs, lra, truePeak, punch; final double[] mid, side; final float[] bars;
        File[] files;
        boolean saved, earlier;
        Distortion.Report report;
        String verdict = "", streaming = "", notes = "";
        Item(String name, Listen l, float[] bars, File[] files) {
            this(name, name, l.loudness.integrated, l.loudness.lra, l.truePeakDb, l.punchDb, l.midDb, l.sideDb, bars, files);
        }
        Item(String name, String take, double lufs, double lra, double truePeak, double punch, double[] mid, double[] side, float[] bars, File[] files) {
            this.name = name; this.take = take; this.lufs = lufs; this.lra = lra; this.truePeak = truePeak; this.punch = punch;
            this.mid = mid; this.side = side; this.bars = bars; this.files = files;
        }
        double width() { return Listen.fromBands(mid, side).widthDb(); }
        double lowEnd() { return Listen.fromBands(mid, side).lowEndDb(); }
    }

    // The session lives here rather than in the screen, so a render, a selection and
    // playback carry on if Android closes the screen while you are in another app.
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final AbPlayer player = new AbPlayer();
    private static MainActivity current;
    private static Context app;

    private Parts parts;

    private ScrollView sessionScreen, compareScreen;
    private LinearLayout column, styleRow, genreList, levelRow, versionList;
    private TextView songName, songMeta, songAction, measuredLufs, measuredPeak, measuredRange, ceilingLine;
    private TextView referenceLine, statusLine, sheetText, selectedName, timeNow, matchLine, matchState, blindLine, blindState, compareBadge;
    private Button compareLink, masterButton, takeButton, importButton, waveTab, toneTab, matchButton, blindButton;
    private ImageButton playButton;
    private ImageView sheetChevron;
    private ProgressBar progress;
    private MeterView meter;
    private WaveView wave;
    private ToneView tone;
    private final View[] leds = new View[STAGES.length];

    private TextView albumLine, tasteLine, previewNote;
    private int routeTick;
    private String lastRoute = "";
    private Button albumButton, manageButton;
    private LinearLayout dock, shelfList, previewRow, releasePanel, checkPanel, spotRows;
    private TextView checkLine, streamLine, gapLine;
    private LinearLayout strengthRow, gapPanel;
    private Button backToTakes;
    private ImageView releaseChevron;
    private EditText titleField, artistField, albumField, yearField, trackField, isrcField;

    private static final boolean[] stageDone = new boolean[STAGES.length];
    private static Target.Style style = Target.Style.COUNTRY;
    private static double level = -14;
    private static Uri songUri;
    private static String song = "song", metaNow = "", statusNow = "", sheet = "";
    private static Listen measuredNow;
    private static final List<Item> items = new ArrayList<>();
    private static final List<Integer> order = new ArrayList<>();
    private static int selected, blind = BLIND_OFF, pickedShown = -1, runningStage = -1, progressNow;
    private static boolean matchBeforeBlind = true, saving, working, rendering, stopping, importing, openCompareNext;
    private static double[] targetMid;
    private static File sessionDir;
    private static boolean commercial, cleanUpOn = true;
    /** The settings behind the newest takes, and the song they came from. */
    private static String roundLabel = "", masteredSong;
    private static double refPunch = Double.NaN, refWidth = Double.NaN, refLow = Double.NaN;
    private static Preview.Mode previewMode = Preview.Mode.FULL;
    private static volatile int measureToken;
    private static int sourceFlatPeaks;

    /** Runs on the screen that is showing now, if there is one. */
    private static void ui(Consumer<MainActivity> job) {
        main.post(() -> { if (current != null) job.accept(current); });
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            style = Target.Style.valueOf(getSharedPreferences("choices", MODE_PRIVATE).getString("style", style.name()));
        } catch (IllegalArgumentException ignored) { }
        commercial = getSharedPreferences("choices", MODE_PRIVATE).getBoolean("commercial", false);
        cleanUpOn = getSharedPreferences("choices", MODE_PRIVATE).getBoolean("cleanUp", true);
        if (Streaming.phoneCodec == null) Streaming.phoneCodec = new AacCodec();
        app = getApplicationContext();
        current = this;
        Look.load(this);
        parts = new Parts(this);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Look.BG);
        sessionScreen = buildSession();
        compareScreen = buildCompare();
        compareScreen.setVisibility(View.GONE);
        root.addView(sessionScreen);
        root.addView(compareScreen);
        // Status, progress and the main action share one solid dock, so nothing shows through.
        dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.VERTICAL);
        dock.setPadding(Look.dp(15), Look.dp(6), Look.dp(15), Look.dp(12));
        dock.setBackgroundColor(Look.BG);
        dock.addView(statusLine, Parts.line(Parts.WRAP, 0));
        dock.addView(progress, Parts.line(Parts.WRAP, 4));
        // Always one tap back to finished takes, wherever you have ended up.
        backToTakes = parts.button("BACK TO YOUR MASTERS", v -> showCompare(true));
        Look.surface(backToTakes, Look.shape(Look.BG, Look.AMBER, 7));
        Look.type(backToTakes, Look.title, 18, Look.AMBER);
        backToTakes.setLetterSpacing(0.1f);
        backToTakes.setVisibility(View.GONE);
        dock.addView(backToTakes, Parts.line(Look.dp(50), 6));
        dock.addView(masterButton, Parts.line(Look.dp(58), 6));
        root.addView(dock, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        setContentView(root);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 9);
        }
        refreshReferences();
        refreshAlbum();
        refreshCeiling();
        drawStrength();
        if (items.isEmpty() && !working) loadRound();
        restore();
        main.postDelayed(this::tick, 100);
    }

    /** Puts the screen back to wherever the session has got to. */
    private void restore() {
        if (songUri != null) {
            songName.setText(song);
            songMeta.setText(metaNow);
            songAction.setText("Change");
        }
        if (measuredNow != null) showMeasured(measuredNow);
        meter.setTarget(level);
        applyBusy();
        progress.setProgress(progressNow);
        statusLine.setText(statusNow);
        lightPath(runningStage);
        sheetText.setText(sheet);
        if (!items.isEmpty() && !order.isEmpty() && !rendering) {
            wave.setFraction(player.fraction());
            compareLink.setVisibility(View.VISIBLE);
        }
        drawPreview();
        refreshCompare();
        if (openCompareNext && !order.isEmpty()) { openCompareNext = false; showCompare(true); }
    }

    @Override protected void onDestroy() {
        if (current == this) current = null;
        if (isFinishing() && !working) player.pause();
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (compareScreen.getVisibility() == View.VISIBLE) showCompare(false);
        else super.onBackPressed();
    }

    /** Moving between screens leaves playback, the selected take and any render running. */
    private void showCompare(boolean on) {
        compareScreen.setVisibility(on ? View.VISIBLE : View.GONE);
        sessionScreen.setVisibility(on ? View.GONE : View.VISIBLE);
        dock.setVisibility(on ? View.GONE : View.VISIBLE);
    }

    private ScrollView screen() {
        ScrollView s = new ScrollView(this);
        s.setBackgroundColor(Look.BG);
        s.setFillViewport(true);
        column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(Look.dp(15), Look.dp(16), Look.dp(15), Look.dp(36));
        s.addView(column);
        return s;
    }

    // Session screen ----------------------------------------------------------

    private ScrollView buildSession() {
        ScrollView s = screen();
        // Reserve scroll space for the fixed dock, including on small phones.
        s.setPadding(0, 0, 0, Look.dp(116));
        s.setClipToPadding(false);
        LinearLayout header = parts.row();
        ImageView mark = new ImageView(this);
        mark.setImageResource(R.drawable.ic_mark);
        mark.setContentDescription("Master Room");
        header.addView(mark, new LinearLayout.LayoutParams(Look.dp(38), Look.dp(38)));
        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        brand.setPadding(Look.dp(10), 0, 0, 0);
        TextView name = parts.text("MASTER ROOM", Look.title, 24, Look.TEXT);
        name.setLetterSpacing(0.15f);
        brand.addView(name);
        TextView tagline = parts.text("AUDIO MASTERING STUDIO", Look.label, 10, Look.AMBER);
        tagline.setLetterSpacing(0.18f);
        brand.addView(tagline);
        header.addView(brand);
        header.addView(parts.grow());
        compareLink = parts.link("COMPARE", v -> showCompare(true));
        compareLink.setVisibility(View.GONE);
        header.addView(compareLink, new LinearLayout.LayoutParams(Parts.WRAP, Look.dp(48)));
        column.addView(header, Parts.line(Look.dp(56), 0));

        // The source strip is deliberately compact: project, file, and action on one line.
        LinearLayout songCard = parts.row();
        songCard.setPadding(Look.dp(10), Look.dp(7), Look.dp(12), Look.dp(7));
        songCard.setMinimumHeight(Look.dp(68));
        Look.surface(songCard, Look.metal(10));
        songCard.setClickable(true);
        songCard.setOnClickListener(v -> pick(PICK_SONG));
        TextView artwork = parts.text("♫", Look.strong, 25, Look.AMBER);
        artwork.setGravity(Gravity.CENTER);
        artwork.setBackground(Look.shape(Look.WELL, Look.AMBER_DIM, 8));
        artwork.setContentDescription("Track artwork placeholder");
        LinearLayout.LayoutParams cover = new LinearLayout.LayoutParams(Look.dp(49), Look.dp(49));
        cover.rightMargin = Look.dp(11);
        songCard.addView(artwork, cover);
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        TextView sourceLabel = parts.text("CURRENT SESSION", Look.label, 10, Look.AMBER);
        sourceLabel.setLetterSpacing(0.09f);
        info.addView(sourceLabel);
        songName = parts.text("No song loaded", Look.strong, 17, Look.TEXT);
        songName.setSingleLine(true);
        songName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(songName);
        songMeta = parts.text("Choose an audio file", Look.body, 11, Look.DIM);
        songMeta.setSingleLine(true);
        songMeta.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(songMeta);
        songCard.addView(info, new LinearLayout.LayoutParams(0, Parts.WRAP, 1));
        songAction = parts.text("Load", Look.label, 16, Look.AMBER);
        songAction.setGravity(Gravity.CENTER);
        songAction.setMinWidth(Look.dp(48));
        songCard.addView(songAction, new LinearLayout.LayoutParams(Look.dp(58), Look.dp(48)));
        column.addView(songCard, Parts.line(Parts.WRAP, 7));

        // One calibrated monitor with real numerical results, not three oversized cards.
        LinearLayout monitor = parts.panel(8, column);
        LinearLayout monitorTitle = parts.row();
        monitorTitle.addView(parts.label("MASTERING MONITOR"));
        monitorTitle.addView(parts.grow());
        TextView sourceMode = parts.text("SOURCE  /  I", Look.mono, 10, Look.AMBER);
        monitorTitle.addView(sourceMode);
        monitor.addView(monitorTitle);
        FrameLayout meterWell = new FrameLayout(this);
        meterWell.setBackground(Look.shape(Look.WELL, Look.HAIR, 7));
        meter = new MeterView(this);
        meterWell.addView(meter);
        monitor.addView(meterWell, Parts.line(Parts.WRAP, 7));
        LinearLayout tiles = parts.row();
        measuredLufs = parts.readout(tiles, "INTEGRATED", "LUFS", 0);
        measuredPeak = parts.readout(tiles, "TRUE PEAK", "dBTP", 5);
        measuredRange = parts.readout(tiles, "RANGE", "LU", 5);
        monitor.addView(tiles, Parts.line(Parts.WRAP, 7));

        // Profile, reference records and target share one compact control rack.
        LinearLayout controls = parts.panel(8, column);
        LinearLayout styleTitle = parts.row();
        styleTitle.addView(parts.label("MASTERING PROFILE"));
        styleTitle.addView(parts.grow());
        styleTitle.addView(parts.text("01  /  STYLE", Look.mono, 10, Look.AMBER_DIM));
        controls.addView(styleTitle);
        styleRow = parts.row();
        controls.addView(styleRow, Parts.line(Look.dp(48), 5));
        genreList = new LinearLayout(this);
        genreList.setOrientation(LinearLayout.VERTICAL);
        genreList.setVisibility(View.GONE);
        controls.addView(genreList, Parts.line(Parts.WRAP, 6));
        strengthRow = new LinearLayout(this);
        strengthRow.setOrientation(LinearLayout.VERTICAL);
        controls.addView(strengthRow, Parts.line(Parts.WRAP, 6));
        referenceLine = parts.text("", Look.body, 11, Look.DIM);
        referenceLine.setSingleLine(true);
        referenceLine.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout refRow = parts.row();
        refRow.addView(referenceLine, new LinearLayout.LayoutParams(0, Parts.WRAP, 1));
        manageButton = parts.link("MANAGE", v -> toggleShelf());
        Look.type(manageButton, Look.label, 12, Look.AMBER);
        refRow.addView(manageButton, new LinearLayout.LayoutParams(Parts.WRAP, Look.dp(48)));
        controls.addView(refRow, Parts.line(Parts.WRAP, 2));
        shelfList = new LinearLayout(this);
        shelfList.setOrientation(LinearLayout.VERTICAL);
        shelfList.setVisibility(View.GONE);
        controls.addView(shelfList, Parts.line(Parts.WRAP, 0));
        View divider = new View(this);
        divider.setBackgroundColor(Look.HAIR);
        controls.addView(divider, Parts.line(Look.dp(1), 0));

        LinearLayout targetTitle = parts.row();
        targetTitle.addView(parts.label("LOUDNESS TARGET"));
        targetTitle.addView(parts.grow());
        targetTitle.addView(parts.text("02  /  LUFS", Look.mono, 10, Look.AMBER_DIM));
        controls.addView(targetTitle, Parts.line(Parts.WRAP, 7));
        levelRow = parts.row();
        controls.addView(levelRow, Parts.line(Look.dp(48), 5));
        ceilingLine = parts.text("", Look.mono, 11, Look.DIM);
        ceilingLine.setSingleLine(true);
        ceilingLine.setEllipsize(android.text.TextUtils.TruncateAt.END);
        controls.addView(ceilingLine, Parts.line(Parts.WRAP, 7));
        View albumRule = new View(this);
        albumRule.setBackgroundColor(Look.HAIR);
        controls.addView(albumRule, Parts.line(Look.dp(1), 7));
        LinearLayout albumRow = parts.row();
        LinearLayout albumText = new LinearLayout(this);
        albumText.setOrientation(LinearLayout.VERTICAL);
        albumText.addView(parts.label("ALBUM MATCH"));
        albumLine = parts.text("", Look.body, 11, Look.DIM);
        albumText.addView(albumLine);
        albumRow.addView(albumText, new LinearLayout.LayoutParams(0, Parts.WRAP, 1));
        albumButton = parts.link("", v -> toggleAlbum());
        Look.type(albumButton, Look.label, 13, Look.AMBER);
        albumRow.addView(albumButton, new LinearLayout.LayoutParams(Parts.WRAP, Look.dp(48)));
        controls.addView(albumRow, Parts.line(Parts.WRAP, 2));
        drawStyles();
        drawLevels();

        // Six hardware-like modules: labels and actual activity LEDs, not false readouts.
        LinearLayout pathPanel = parts.panel(8, column);
        LinearLayout pathTitle = parts.row();
        pathTitle.addView(parts.label("SIGNAL CHAIN"));
        pathTitle.addView(parts.grow());
        pathTitle.addView(parts.text("06 MODULES", Look.mono, 10, Look.AMBER_DIM));
        pathPanel.addView(pathTitle);
        LinearLayout path = parts.row();
        for (int i = 0; i < STAGES.length; i++) {
            LinearLayout module = new LinearLayout(this);
            module.setOrientation(LinearLayout.VERTICAL);
            module.setGravity(Gravity.CENTER);
            module.setBackground(Look.shape(Look.WELL, Look.HAIR, 7));
            module.setPadding(0, Look.dp(4), 0, Look.dp(3));
            SignalIconView icon = new SignalIconView(this, i);
            module.addView(icon, new LinearLayout.LayoutParams(Parts.MATCH, Look.dp(24)));
            LinearLayout moduleFoot = parts.row();
            moduleFoot.setGravity(Gravity.CENTER);
            View led = new View(this);
            leds[i] = led;
            LinearLayout.LayoutParams dot = new LinearLayout.LayoutParams(Look.dp(5), Look.dp(5));
            dot.rightMargin = Look.dp(3);
            moduleFoot.addView(led, dot);
            TextView shortName = parts.text(STAGES[i], Look.label, 11, Look.MID);
            moduleFoot.addView(shortName);
            module.addView(moduleFoot);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, Look.dp(48), 1);
            if (i > 0) cp.leftMargin = Look.dp(3);
            path.addView(module, cp);
        }
        pathPanel.addView(path, Parts.line(Parts.WRAP, 5));

        masterButton = parts.gold("MASTER IT", v -> { if (rendering) stopRender(); else master(); });
        Look.enable(masterButton, false);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgressTintList(ColorStateList.valueOf(Look.AMBER));
        progress.setVisibility(View.GONE);
        statusLine = parts.text("", Look.body, 13, Look.DIM);
        statusLine.setSingleLine(true);
        statusLine.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return s;
    }

    private void drawStyles() {
        styleRow.removeAllViews();
        LinearLayout current = parts.row();
        current.setPadding(Look.dp(14), 0, Look.dp(6), 0);
        Look.surface(current, Look.shape(Look.RAISED, Look.AMBER, 9));
        current.setClickable(true);
        current.setContentDescription("Genre");
        current.setOnClickListener(v -> toggleGenres());
        current.addView(parts.text(style.label, Look.strong, 16, Look.TEXT), new LinearLayout.LayoutParams(0, Parts.WRAP, 1));
        ImageView chevron = new ImageView(this);
        chevron.setImageResource(R.drawable.ic_chevron);
        chevron.setRotation(genreList.getVisibility() == View.VISIBLE ? 180 : 0);
        current.addView(chevron, new LinearLayout.LayoutParams(Look.dp(32), Look.dp(32)));
        LinearLayout.LayoutParams wide = new LinearLayout.LayoutParams(0, Look.dp(48), 1);
        wide.setMargins(Look.dp(3), 0, Look.dp(3), 0);
        styleRow.addView(current, wide);
        Button ref = parts.link("ADD RECORD", v -> pick(PICK_REFERENCE));
        Look.type(ref, Look.label, 12, Look.AMBER);
        ref.setLetterSpacing(0.01f);
        styleRow.addView(ref, new LinearLayout.LayoutParams(Look.dp(81), Look.dp(48)));

        genreList.removeAllViews();
        Target.Style[] all = Target.Style.values();
        LinearLayout r = null;
        for (int i = 0; i < all.length; i++) {
            if (i % 2 == 0) { r = parts.row(); genreList.addView(r, Parts.line(Look.dp(48), i == 0 ? 0 : 6)); }
            final Target.Style pick = all[i];
            Button b = parts.segment(pick.label, pick == style, Look.strong, v -> {
                style = pick;
                getSharedPreferences("choices", MODE_PRIVATE).edit().putString("style", pick.name()).apply();
                genreList.setVisibility(View.GONE);
                drawStyles();
                refreshReferences();
            });
            b.setPadding(0, 0, 0, 0);
            r.addView(b);
        }
    }

    /** How firmly the tone moves, and whether the two clean-up stages run. */
    private void drawStrength() {
        strengthRow.removeAllViews();
        LinearLayout tone = parts.row(), clean = parts.row();
        tone.addView(parts.segment("Gentle", !commercial, Look.strong, v -> setChoice("commercial", false)));
        tone.addView(parts.segment("Commercial", commercial, Look.strong, v -> setChoice("commercial", true)));
        clean.addView(parts.segment("Clean-up on", cleanUpOn, Look.strong, v -> setChoice("cleanUp", true)));
        clean.addView(parts.segment("Clean-up off", !cleanUpOn, Look.strong, v -> setChoice("cleanUp", false)));
        strengthRow.addView(tone, Parts.line(Look.dp(48), 0));
        strengthRow.addView(clean, Parts.line(Look.dp(48), 6));
    }

    private void setChoice(String key, boolean on) {
        if (key.equals("commercial")) commercial = on; else cleanUpOn = on;
        getSharedPreferences("choices", MODE_PRIVATE).edit().putBoolean(key, on).apply();
        drawStrength();
    }

    /** Opens the list with the top of it in view, clear of the master button. */
    private void toggleGenres() {
        boolean open = genreList.getVisibility() != View.VISIBLE;
        genreList.setVisibility(open ? View.VISIBLE : View.GONE);
        drawStyles();
        if (open) main.postDelayed(this::showSettings, 80);
    }

    private void showSettings() {
        android.graphics.Rect at = new android.graphics.Rect();
        sessionScreen.offsetDescendantRectToMyCoords(styleRow, at);
        sessionScreen.smoothScrollTo(0, at.top - Look.dp(10));
    }

    /** Back to the settings with the song loaded. These takes stay until the new ones finish. */
    private void remaster() {
        showCompare(false);
        statusNow = "Change your settings, then press Master It. These takes stay until the new ones are done.";
        statusLine.setText(statusNow);
        main.postDelayed(this::showSettings, 80);
    }

    private void drawLevels() {
        levelRow.removeAllViews();
        for (double l : LEVELS) {
            final double pick = l;
            levelRow.addView(parts.segment(String.format(Locale.US, "%.0f", l), l == level, Look.mono, v -> {
                level = pick; drawLevels(); meter.setTarget(pick); refreshCeiling();
            }));
        }
    }

    private void refreshCeiling() {
        ceilingLine.setText(String.format(Locale.US, "Target %.1f LUFS, true peak ceiling %.1f dBTP", level, Engineer.ceilingFor(level)));
    }

    private void showMeasured(Listen l) {
        meter.setMeasured(l.loudness.integrated);
        measuredLufs.setText(String.format(Locale.US, "%.1f", l.loudness.integrated));
        measuredPeak.setText(String.format(Locale.US, "%.1f", l.truePeakDb));
        measuredRange.setText(String.format(Locale.US, "%.1f", l.loudness.lra));
    }

    private void clearMeasured() {
        meter.setMeasured(Double.NaN);
        measuredLufs.setText("--"); measuredPeak.setText("--"); measuredRange.setText("--");
    }

    /** Reads the real numbers in the background; Master it never waits for this. */
    private void measureSource(Uri uri) {
        final int token = ++measureToken;
        clearMeasured();
        measuredNow = null;
        Thread t = new Thread(() -> {
            try {
                Audio a = Decoder.open(app, uri);
                final String meta = trackMeta(a);
                Listen l = Listen.to(a);
                main.post(() -> {
                    if (token != measureToken) return;
                    setMeta(meta);
                    measuredNow = l;
                    ui(x -> x.showMeasured(l));
                });
            } catch (Throwable e) {
                main.post(() -> { if (token == measureToken) setMeta("Could not measure this file"); });
            }
        }, "measure");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private static String trackMeta(Audio a) {
        return String.format(Locale.US, "%s  %.1f kHz", clock(a.seconds()), a.rate / 1000.0);
    }

    /** Each LED shows only what has really run: off, done, or running now. */
    private void lightPath(int running) {
        for (int i = 0; i < leds.length; i++) {
            int color = i == running ? Look.GOLD_HI : stageDone[i] ? Look.AMBER_DIM : Look.LINE;
            leds[i].setBackground(Look.shape(color, 0, 5));
        }
    }

    private static void setMeta(String text) {
        metaNow = text;
        ui(a -> a.songMeta.setText(text));
    }

    private void resetPath(boolean done) {
        java.util.Arrays.fill(stageDone, done);
        runningStage = -1;
        lightPath(-1);
    }

    private static int stageOf(String step) {
        if (step.contains("EQ") || step.contains("Cleaning") || step.contains("verses")) return 0;
        if (step.contains("harsh")) return 1;
        if (step.contains("riding") || step.contains("bus compressor")) return 2;
        if (step.contains("colour")) return 3;
        if (step.contains("level") || step.contains("low end") || step.contains("easing") || step.contains("density")) return 4;
        if (step.contains("clipper") || step.contains("checking")) return 5;
        return 0;
    }

    // Compare screen ----------------------------------------------------------

    private ScrollView buildCompare() {
        ScrollView s = screen();
        LinearLayout header = parts.row();
        ImageButton back = new ImageButton(this);
        back.setImageResource(R.drawable.ic_back);
        back.setBackground(null);
        back.setContentDescription("Back");
        back.setOnClickListener(v -> showCompare(false));
        header.addView(back, new LinearLayout.LayoutParams(Look.dp(48), Look.dp(48)));
        LinearLayout compareBrand = new LinearLayout(this);
        compareBrand.setOrientation(LinearLayout.VERTICAL);
        TextView title = parts.text("COMPARE", Look.title, 24, Look.TEXT);
        title.setLetterSpacing(0.14f);
        compareBrand.addView(title);
        compareBrand.addView(parts.text("A/B MASTERING MONITOR", Look.label, 10, Look.AMBER_DIM));
        header.addView(compareBrand);
        header.addView(parts.grow());
        compareBadge = parts.text("LEVEL MATCHED", Look.label, 12, Look.AMBER);
        compareBadge.setGravity(Gravity.CENTER);
        compareBadge.setPadding(Look.dp(9), Look.dp(8), Look.dp(9), Look.dp(8));
        compareBadge.setBackground(Look.shape(Look.WELL, Look.AMBER, 6));
        header.addView(compareBadge);
        column.addView(header, Parts.line(Look.dp(58), 0));

        LinearLayout display = parts.panel(8, column);
        display.addView(parts.label("LISTENING ROOM"));
        LinearLayout tabs = parts.row();
        waveTab = parts.segment("WAVE", true, Look.label, v -> showTone(false));
        toneTab = parts.segment("TONE", false, Look.label, v -> showTone(true));
        tabs.addView(waveTab);
        tabs.addView(toneTab);
        display.addView(tabs, Parts.line(Look.dp(50), 9));
        FrameLayout well = new FrameLayout(this);
        well.setPadding(Look.dp(8), Look.dp(8), Look.dp(8), Look.dp(6));
        well.setBackground(Look.shape(Look.WELL, Look.HAIR, 6));
        wave = new WaveView(this);
        wave.setSeek(player::seek);
        well.addView(wave, new FrameLayout.LayoutParams(Parts.MATCH, Look.dp(185)));
        tone = new ToneView(this);
        tone.setVisibility(View.GONE);
        well.addView(tone, new FrameLayout.LayoutParams(Parts.MATCH, Look.dp(185)));
        display.addView(well, Parts.line(Parts.WRAP, 8));

        LinearLayout now = parts.row();
        LinearLayout nowText = new LinearLayout(this);
        nowText.setOrientation(LinearLayout.VERTICAL);
        nowText.addView(parts.label("SELECTED"));
        selectedName = parts.text("", Look.title, 24, Look.AMBER);
        nowText.addView(selectedName);
        now.addView(nowText, new LinearLayout.LayoutParams(0, Parts.WRAP, 1));
        timeNow = parts.text("0:00 / 0:00", Look.mono, 15, Look.MID);
        now.addView(timeNow);
        display.addView(now, Parts.line(Parts.WRAP, 8));

        LinearLayout controls = parts.row();
        Button back10 = parts.button("-10 s", v -> nudge(-10));
        Look.type(back10, Look.mono, 14, Look.TEXT);
        controls.addView(back10, Parts.share(Look.dp(56), 1, 0));
        playButton = new ImageButton(this);
        playButton.setImageResource(R.drawable.ic_play);
        Look.surface(playButton, Look.gold(36));
        playButton.setContentDescription("Play");
        playButton.setOnClickListener(v -> togglePlay());
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(Look.dp(72), Look.dp(72));
        pp.leftMargin = Look.dp(14);
        pp.rightMargin = Look.dp(14);
        controls.addView(playButton, pp);
        Button ahead10 = parts.button("+10 s", v -> nudge(10));
        Look.type(ahead10, Look.mono, 14, Look.TEXT);
        controls.addView(ahead10, Parts.share(Look.dp(56), 1, 0));
        display.addView(controls, Parts.line(Parts.WRAP, 10));

        LinearLayout match = parts.panel(10, column);
        LinearLayout matchTop = parts.row();
        matchTop.addView(parts.label("LEVEL MATCH"));
        matchTop.addView(parts.grow());
        matchState = parts.text("", Look.label, 15, Look.BG);
        matchState.setLetterSpacing(0.1f);
        matchState.setPadding(Look.dp(12), Look.dp(3), Look.dp(12), Look.dp(3));
        matchTop.addView(matchState);
        match.addView(matchTop);
        matchLine = parts.text("", Look.body, 13, Look.MID);
        match.addView(matchLine, Parts.line(Parts.WRAP, 6));
        matchButton = parts.button("", v -> toggleMatch());
        match.addView(matchButton, Parts.line(Look.dp(48), 8));
        match.addView(parts.label("LISTEN ON"), Parts.line(Parts.WRAP, 12));
        previewRow = new LinearLayout(this);
        previewRow.setOrientation(LinearLayout.VERTICAL);
        match.addView(previewRow, Parts.line(Parts.WRAP, 6));
        previewNote = parts.text("Rough preview of a typical device, not a measurement. Your files are not changed.", Look.body, 12, Look.DIM);
        previewNote.setVisibility(View.GONE);
        match.addView(previewNote, Parts.line(Parts.WRAP, 6));

        LinearLayout columns = parts.row();
        columns.setPadding(Look.dp(12), 0, Look.dp(12), 0);
        columns.addView(parts.label("MASTER VERSIONS"), new LinearLayout.LayoutParams(0, Parts.WRAP, 1));
        addColumn(columns, parts.label("LUFS"), 60);
        addColumn(columns, parts.label("dBTP"), 54);
        addColumn(columns, parts.label("LRA LU"), 58);
        column.addView(columns, Parts.line(Parts.WRAP, 14));
        versionList = new LinearLayout(this);
        versionList.setOrientation(LinearLayout.VERTICAL);
        column.addView(versionList, Parts.line(Parts.WRAP, 4));

        importButton = parts.button("Import Stem Lab master", v -> pick(PICK_MASTER));
        Look.type(importButton, Look.body, 15, Look.TEXT);
        GradientDrawable dash = Look.shape(Look.BG, 0, 8);
        dash.setStroke(Look.dp(1), Look.AMBER_DIM, Look.dp(5), Look.dp(4));
        Look.surface(importButton, dash);
        column.addView(importButton, Parts.line(Look.dp(52), 6));
        Button again = parts.button("REMASTER", v -> remaster());
        Look.surface(again, Look.shape(Look.BG, Look.AMBER, 7));
        Look.type(again, Look.title, 18, Look.AMBER);
        again.setLetterSpacing(0.1f);
        column.addView(again, Parts.line(Look.dp(52), 6));

        gapPanel = parts.panel(10, column);
        gapPanel.addView(parts.label("AGAINST YOUR RECORDS"));
        gapLine = parts.text("", Look.mono, 13, Look.TEXT);
        gapPanel.addView(gapLine, Parts.line(Parts.WRAP, 6));

        checkPanel = parts.panel(10, column);
        checkPanel.addView(parts.label("DISTORTION CHECK"));
        checkLine = parts.text("", Look.body, 14, Look.TEXT);
        checkPanel.addView(checkLine, Parts.line(Parts.WRAP, 6));
        spotRows = new LinearLayout(this);
        spotRows.setOrientation(LinearLayout.VERTICAL);
        checkPanel.addView(spotRows, Parts.line(Parts.WRAP, 4));
        streamLine = parts.text("", Look.body, 13, Look.DIM);
        checkPanel.addView(streamLine, Parts.line(Parts.WRAP, 8));

        LinearLayout blindPanel = parts.panel(10, column);
        LinearLayout blindTop = parts.row();
        blindTop.addView(parts.label("BLIND TEST"));
        blindTop.addView(parts.grow());
        blindState = parts.text("", Look.label, 15, Look.TEXT);
        blindState.setLetterSpacing(0.1f);
        blindState.setPadding(Look.dp(12), Look.dp(3), Look.dp(12), Look.dp(3));
        blindTop.addView(blindState);
        blindPanel.addView(blindTop);
        blindLine = parts.text("", Look.body, 13, Look.MID);
        blindPanel.addView(blindLine, Parts.line(Parts.WRAP, 6));
        blindButton = parts.button("", v -> blindStep());
        blindPanel.addView(blindButton, Parts.line(Look.dp(48), 8));
        tasteLine = parts.text("", Look.body, 12, Look.DIM);
        blindPanel.addView(tasteLine, Parts.line(Parts.WRAP, 8));

        LinearLayout releaseCard = parts.row();
        releaseCard.setPadding(Look.dp(14), 0, Look.dp(8), 0);
        releaseCard.setMinimumHeight(Look.dp(52));
        Look.surface(releaseCard, Look.metal(8));
        releaseCard.setClickable(true);
        releaseCard.setContentDescription("Release details");
        releaseCard.setOnClickListener(v -> toggleRelease());
        releaseCard.addView(parts.text("Release details", Look.strong, 16, Look.TEXT), new LinearLayout.LayoutParams(0, Parts.WRAP, 1));
        releaseChevron = new ImageView(this);
        releaseChevron.setImageResource(R.drawable.ic_chevron);
        releaseCard.addView(releaseChevron, new LinearLayout.LayoutParams(Look.dp(36), Look.dp(36)));
        column.addView(releaseCard, Parts.line(Parts.WRAP, 10));
        releasePanel = new LinearLayout(this);
        releasePanel.setOrientation(LinearLayout.VERTICAL);
        releasePanel.setPadding(Look.dp(12), Look.dp(4), Look.dp(12), Look.dp(12));
        releasePanel.setBackground(Look.metal(8));
        releasePanel.setVisibility(View.GONE);
        SharedPreferences prefs = getSharedPreferences("release", MODE_PRIVATE);
        titleField = parts.field(releasePanel, "TITLE", song, 8);
        artistField = parts.field(releasePanel, "ARTIST", prefs.getString("artist", ""), 8);
        albumField = parts.field(releasePanel, "ALBUM", prefs.getString("album", ""), 8);
        yearField = parts.field(releasePanel, "YEAR", prefs.getString("year", ""), 8);
        trackField = parts.field(releasePanel, "TRACK NUMBER", "", 8);
        isrcField = parts.field(releasePanel, "ISRC", "", 8);
        column.addView(releasePanel, Parts.line(Parts.WRAP, 6));

        takeButton = parts.gold("PICK A TAKE", v -> saveSelected());
        column.addView(takeButton, Parts.line(Look.dp(60), 14));

        LinearLayout sheetCard = parts.row();
        sheetCard.setPadding(Look.dp(14), 0, Look.dp(8), 0);
        sheetCard.setMinimumHeight(Look.dp(52));
        Look.surface(sheetCard, Look.metal(8));
        sheetCard.setClickable(true);
        sheetCard.setContentDescription("Session Sheet");
        sheetCard.setOnClickListener(v -> toggleSheet());
        sheetCard.addView(parts.text("Session Sheet", Look.strong, 16, Look.TEXT), new LinearLayout.LayoutParams(0, Parts.WRAP, 1));
        sheetChevron = new ImageView(this);
        sheetChevron.setImageResource(R.drawable.ic_chevron);
        sheetCard.addView(sheetChevron, new LinearLayout.LayoutParams(Look.dp(36), Look.dp(36)));
        column.addView(sheetCard, Parts.line(Parts.WRAP, 10));
        sheetText = parts.text("", Look.body, 14, Look.MID);
        sheetText.setPadding(Look.dp(14), Look.dp(12), Look.dp(14), Look.dp(12));
        sheetText.setBackground(Look.shape(Look.PANEL, Look.HAIR, 8));
        sheetText.setVisibility(View.GONE);
        column.addView(sheetText, Parts.line(Parts.WRAP, 6));
        return s;
    }

    private void addColumn(LinearLayout row, TextView t, int widthDp) {
        t.setGravity(Gravity.END);
        row.addView(t, new LinearLayout.LayoutParams(Look.dp(widthDp), Parts.WRAP));
    }

    private void toggleSheet() {
        boolean open = sheetText.getVisibility() != View.VISIBLE;
        sheetText.setVisibility(open ? View.VISIBLE : View.GONE);
        sheetChevron.setRotation(open ? 180 : 0);
    }

    private void toggleRelease() {
        boolean open = releasePanel.getVisibility() != View.VISIBLE;
        releasePanel.setVisibility(open ? View.VISIBLE : View.GONE);
        releaseChevron.setRotation(open ? 180 : 0);
    }

    private void drawPreview() {
        previewRow.removeAllViews();
        Preview.Mode[] modes = Preview.Mode.values();
        LinearLayout r = null;
        for (int i = 0; i < modes.length; i++) {
            if (i % 3 == 0) { r = parts.row(); previewRow.addView(r, Parts.line(Look.dp(48), i == 0 ? 0 : 6)); }
            final Preview.Mode pick = modes[i];
            Button b = parts.segment(pick.label, pick == previewMode, Look.strong, v -> {
                previewMode = pick;
                applyPreview();
                drawPreview();
            });
            Look.type(b, Look.strong, 14, pick == previewMode ? Look.BG : Look.TEXT);
            b.setPadding(0, 0, 0, 0);
            r.addView(b);
        }
        boolean speaker = onPhoneSpeaker();
        Preview.Mode worn = wornBuds();
        previewNote.setVisibility(previewMode == Preview.Mode.FULL ? View.GONE : View.VISIBLE);
        if (previewMode == Preview.Mode.PHONE && speaker) previewNote.setText("You are on the phone's speaker now, so this is the real thing. No preview added.");
        else if (previewMode.buds() && previewMode == worn) previewNote.setText("You are on your Galaxy " + worn.label + " now, so this is the real thing. No preview added.");
        else if (previewMode.buds() && worn != null) previewNote.setText("Adding only the difference between your " + worn.label + " and the " + previewMode.label + ". Your files are not changed.");
        else if (previewMode.buds() && speaker) previewNote.setText("Measured tuning of the Galaxy " + previewMode.label + ". Use headphones for this one: your phone's speaker changes the sound as well.");
        else if (previewMode.buds()) previewNote.setText("Measured tuning of the Galaxy " + previewMode.label + ", from published tests. Fit changes the bass. Your files are not changed.");
        else if (speaker && previewMode != Preview.Mode.MONO) previewNote.setText("Rough preview. Use headphones for this one: your phone's speaker changes the sound as well.");
        else previewNote.setText("Rough preview of a typical device, not a measurement. Your files are not changed.");
    }

    /** A preview of the thing you are already listening on would apply the same change twice. */
    private void applyPreview() {
        boolean real = previewMode == Preview.Mode.PHONE && onPhoneSpeaker();
        player.setPreview(real ? Preview.Mode.FULL : previewMode, wornBuds());
    }

    /** Galaxy Buds2 or Buds2 Pro when they are connected under their usual name, otherwise null. */
    private Preview.Mode wornBuds() {
        AudioManager audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        for (AudioDeviceInfo d : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            int t = d.getType();
            if (t != AudioDeviceInfo.TYPE_BLUETOOTH_A2DP && t != AudioDeviceInfo.TYPE_BLE_HEADSET) continue;
            Preview.Mode m = Preview.budsNamed(String.valueOf(d.getProductName()));
            if (m != null) return m;
        }
        return null;
    }

    private boolean onPhoneSpeaker() {
        AudioManager audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        for (AudioDeviceInfo d : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            switch (d.getType()) {
                case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
                case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                case AudioDeviceInfo.TYPE_USB_HEADSET:
                case AudioDeviceInfo.TYPE_USB_DEVICE:
                case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
                case AudioDeviceInfo.TYPE_BLE_HEADSET:
                case AudioDeviceInfo.TYPE_BLE_SPEAKER:
                    return false;
                default:
            }
        }
        return true;
    }

    private void showTone(boolean on) {
        wave.setVisibility(on ? View.GONE : View.VISIBLE);
        tone.setVisibility(on ? View.VISIBLE : View.GONE);
        Look.surface(waveTab, on ? Look.shape(Look.RAISED, Look.LINE, 5) : Look.gold(5));
        Look.surface(toneTab, on ? Look.gold(5) : Look.shape(Look.RAISED, Look.LINE, 5));
        waveTab.setTextColor(on ? Look.TEXT : Look.BG);
        toneTab.setTextColor(on ? Look.BG : Look.TEXT);
    }

    private void select(int shown) {
        selected = shown;
        player.select(order.get(shown));
        refreshCompare();
    }

    private void toggleMatch() {
        player.setMatched(!player.isMatched());
        refreshCompare();
    }

    /** Start, then reveal, then finish. Names and numbers stay hidden until the reveal. */
    private void blindStep() {
        if (order.isEmpty()) return;
        if (blind == BLIND_OFF) {
            matchBeforeBlind = player.isMatched();
            player.setMatched(true);
            blind = BLIND_ON;
            pickedShown = -1;
            Collections.shuffle(order);
            player.select(order.get(selected));
        } else if (blind == BLIND_ON) {
            pickedShown = selected;
            blind = BLIND_RESULTS;
            remember("blind", items.get(order.get(selected)).take);
        } else {
            int keep = order.get(selected);
            Collections.sort(order);
            selected = order.indexOf(keep);
            blind = BLIND_OFF;
            pickedShown = -1;
            player.setMatched(matchBeforeBlind);
        }
        refreshCompare();
    }

    private void drawVersions() {
        versionList.removeAllViews();
        boolean hide = blind == BLIND_ON;
        for (int i = 0; i < order.size(); i++) {
            final int shown = i;
            Item item = items.get(order.get(i));
            boolean on = i == selected;
            LinearLayout r = parts.row();
            r.setPadding(Look.dp(11), Look.dp(12), Look.dp(11), Look.dp(12));
            r.setMinimumHeight(Look.dp(74));
            GradientDrawable selection = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0xFF3A2C1D, 0xFF211D1A});
            selection.setCornerRadius(Look.dp(10));
            selection.setStroke(Look.dp(1), Look.AMBER);
            Look.surface(r, on ? selection : Look.metal(10));
            View indicator = new View(this);
            indicator.setBackground(Look.shape(on ? Look.AMBER : Look.LINE, 0, 10));
            LinearLayout.LayoutParams dot = new LinearLayout.LayoutParams(Look.dp(11), Look.dp(11));
            dot.rightMargin = Look.dp(11);
            r.addView(indicator, dot);
            r.setClickable(true);
            r.setOnClickListener(v -> select(shown));
            LinearLayout names = new LinearLayout(this);
            names.setOrientation(LinearLayout.VERTICAL);
            String blindName = "Version " + (i + 1);
            TextView versionTitle = parts.text(hide ? blindName : item.name, Look.label, 20, on ? Look.AMBER : Look.TEXT);
            versionTitle.setSingleLine(true);
            versionTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
            names.addView(versionTitle);
            if (!hide && blind != BLIND_RESULTS) {
                String detail = order.get(i) == 0 ? "ORIGINAL AUDIO"
                        : item.earlier ? "EARLIER ROUND"
                        : "Clean".equals(item.take) ? "TRANSPARENT / NEUTRAL"
                        : "Warm".equals(item.take) ? "ANALOGUE COLOUR"
                        : "Punch".equals(item.take) ? "TRANSIENT IMPACT"
                        : "Yours".equals(item.take) ? "BUILT FROM YOUR PICKS" : "IMPORTED MASTER";
                if (item.report != null) detail += item.report.passed() ? "  /  CLEAN" : "  /  CHECK";
                TextView versionDetail = parts.text(detail, Look.label, 10, item.report != null && !item.report.passed() ? Look.AMBER : Look.DIM);
                versionDetail.setSingleLine(true);
                versionDetail.setEllipsize(android.text.TextUtils.TruncateAt.END);
                names.addView(versionDetail);
            }
            if (blind == BLIND_RESULTS) {
                names.addView(parts.text(i == pickedShown ? blindName + ", your pick" : blindName, Look.body, 12, i == pickedShown ? Look.AMBER : Look.DIM));
            }
            r.addView(names, new LinearLayout.LayoutParams(0, Parts.WRAP, 1));
            addNumber(r, hide ? "" : number(item.lufs), 60, Look.TEXT);
            addNumber(r, hide ? "" : number(item.truePeak), 54, Look.MID);
            addNumber(r, hide ? "" : number(item.lra), 58, Look.MID);
            r.setContentDescription(hide ? blindName : item.name + ", " + number(item.lufs) + " LUFS, " + number(item.truePeak)
                    + " dBTP, " + number(item.lra) + " LU" + (on ? ", selected" : ""));
            versionList.addView(r, Parts.line(Parts.WRAP, i == 0 ? 0 : 8));
        }
    }

    /** Each take's automatic check, with a jump to any spot still worth a listen. */
    private void drawCheck(Item item, boolean hide) {
        checkPanel.setVisibility(hide || item == null ? View.GONE : View.VISIBLE);
        spotRows.removeAllViews();
        double total = length();
        Distortion.Report report = hide || item == null ? null : item.report;
        int count = report == null ? 0 : report.spots.size();
        double[] marks = new double[count];
        boolean[] serious = new boolean[count];
        for (int i = 0; i < count; i++) {
            marks[i] = total > 0 ? report.spots.get(i).startSeconds / total : 0;
            serious[i] = report.spots.get(i).audible;
        }
        wave.setMarks(marks, serious);
        drawGap(item, hide);
        streamLine.setVisibility(item == null || item.streaming.isEmpty() ? View.GONE : View.VISIBLE);
        if (item == null) return;
        streamLine.setText(item.streaming);
        if (order.get(selected) == 0) {
            checkLine.setText(sourceFlatPeaks > 0 ? sourceFlatPeaks + " flat-topped peaks are already in this file. Mastering cannot undo them."
                    : "No flat-topped peaks found in the source.");
        } else if (report == null) {
            checkLine.setText("Only takes made here can be checked, because this needs the audio from before the limiter.");
        } else if (count == 0) {
            checkLine.setText(item.verdict + ". Nothing likely to be heard.");
        } else {
            checkLine.setText(item.verdict + ". Marked on the waveform. Tap a time to go there.");
        }
        LinearLayout r = null;
        int shown = Math.min(6, count);
        for (int i = 0; i < shown; i++) {
            if (i % 3 == 0) { r = parts.row(); spotRows.addView(r, Parts.line(Look.dp(48), i == 0 ? 2 : 6)); }
            final double at = report.spots.get(i).startSeconds;
            Button b = parts.button(clock(at), v -> {
                player.seek(at / Math.max(0.001, length()));
                wave.setFraction(player.fraction());
            });
            Look.type(b, Look.mono, 14, serious[i] ? Look.HOT : Look.AMBER);
            r.addView(b, Parts.share(Look.dp(48), 1, i % 3 == 0 ? 0 : 6));
        }
    }

    /** The take against the records it was aimed at, on more than tone. */
    private void drawGap(Item item, boolean hide) {
        boolean show = !hide && item != null && !Double.isNaN(refWidth) && order.get(selected) != 0;
        gapPanel.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;
        StringBuilder b = new StringBuilder();
        if (!Double.isNaN(refPunch)) b.append(gap("Punch", item.punch, refPunch)).append('\n');
        b.append(gap("Low end", item.lowEnd(), refLow)).append('\n').append(gap("Width", item.width(), refWidth));
        Item closest = null;
        double best = Double.MAX_VALUE;
        for (Item i : items) {
            if (i.files == null || i.report == null) continue;
            double miss = Math.abs(i.lowEnd() - refLow) + Math.abs(i.width() - refWidth) + (Double.isNaN(refPunch) ? 0 : Math.abs(i.punch - refPunch));
            if (miss < best) { best = miss; closest = i; }
        }
        if (closest != null) b.append("\nClosest: ").append(closest.name);
        gapLine.setText(b.toString());
    }

    private static String gap(String name, double take, double records) {
        return String.format(Locale.US, "%-8s %+.1f dB vs records", name, take - records);
    }

    private static String number(double v) {
        return Double.isNaN(v) || Double.isInfinite(v) ? "--" : String.format(Locale.US, "%.1f", v);
    }

    private void addNumber(LinearLayout row, String value, int widthDp, int color) {
        TextView t = parts.text(value, Look.mono, 13, color);
        t.setGravity(Gravity.END);
        row.addView(t, new LinearLayout.LayoutParams(Look.dp(widthDp), Parts.WRAP));
    }

    /** Everything on this screen that follows the selection or the test state. */
    private void refreshCompare() {
        drawVersions();
        boolean hide = blind == BLIND_ON, any = !order.isEmpty();
        Item item = any ? items.get(order.get(selected)) : null;
        selectedName.setText(!any ? "Nothing yet" : hide ? "Version " + (selected + 1) : item.name);

        boolean matched = player.isMatched();
        matchState.setText(matched ? "ON" : "OFF");
        compareBadge.setText(matched ? "LEVEL MATCHED" : "UNMATCHED");
        compareBadge.setTextColor(matched ? Look.AMBER : Look.DIM);
        compareBadge.setBackground(Look.shape(Look.WELL, matched ? Look.AMBER : Look.LINE, 6));
        matchState.setTextColor(matched ? Look.BG : Look.TEXT);
        matchState.setBackground(matched ? Look.gold(4) : Look.shape(Look.WELL, Look.LINE, 4));
        matchButton.setText(matched ? "Turn level match off" : "Turn level match on");
        Look.enable(matchButton, !hide);
        if (hide) matchLine.setText("Held on during the blind test. Trims are hidden so they cannot give a version away.");
        else if (!any) matchLine.setText("Evens out playback level only. Your files are never changed.");
        else if (matched) {
            double trim = player.trimDb(order.get(selected));
            matchLine.setText(String.format(Locale.US, "Playback trim on %s: %.1f dB. Your files are not changed.", item.name, Math.abs(trim) < 0.05 ? 0.0 : trim));
        }
        else matchLine.setText("Off: each version plays at its real level, so louder ones may seem better.");

        blindState.setText(blind == BLIND_OFF ? "OFF" : blind == BLIND_ON ? "ON" : "RESULTS");
        blindState.setTextColor(blind == BLIND_OFF ? Look.TEXT : Look.BG);
        blindState.setBackground(blind == BLIND_OFF ? Look.shape(Look.WELL, Look.LINE, 4) : Look.gold(4));
        if (blind == BLIND_OFF) {
            blindLine.setText("Shuffles the versions and hides their names and numbers.");
            blindButton.setText("Start blind test");
        } else if (blind == BLIND_ON) {
            blindLine.setText("Listen, select the one you prefer, then reveal.");
            blindButton.setText("Reveal results");
        } else {
            blindLine.setText(pickedShown >= 0 && pickedShown < order.size()
                    ? String.format(Locale.US, "Your pick, Version %d, was %s.", pickedShown + 1, items.get(order.get(pickedShown)).name) : "");
            blindButton.setText("End blind test");
        }
        tasteLine.setText(Taste.summary(tasteLog()));
        tasteLine.setVisibility(hide ? View.GONE : View.VISIBLE);
        Look.enable(blindButton, any && !importing);
        Look.enable(importButton, any && !hide && !working && !importing);
        Look.enable(playButton, any);

        wave.setBars(!any ? null : hide ? items.get(0).bars : item.bars);
        double[] source = targetMid == null || !any ? null : ToneView.againstTarget(items.get(0).mid, targetMid);
        double[] take = targetMid == null || !any || order.get(selected) == 0 || item.mid == null ? null : ToneView.againstTarget(item.mid, targetMid);
        tone.show(source, take, item == null ? "" : item.name, hide);
        drawCheck(item, hide);
        refreshTake(item);
    }

    private void refreshTake(Item item) {
        boolean hide = blind == BLIND_ON;
        boolean ready = item != null && item.files != null && !hide && !saving && !item.saved;
        if (saving && item != null) takeButton.setText("SAVING " + item.name.toUpperCase(Locale.US));
        else if (hide) takeButton.setText("BLIND TEST ON");
        else if (item == null || item.files == null) takeButton.setText("PICK A TAKE");
        else if (item.saved) takeButton.setText(item.name.toUpperCase(Locale.US) + " SAVED");
        else takeButton.setText("SAVE " + item.name.toUpperCase(Locale.US));
        takeButton.setEnabled(ready);
        takeButton.setAlpha(1f);
        if (ready) {
            Look.surface(takeButton, Look.gold(7));
            takeButton.setTextColor(Look.BG);
        } else if (item != null && item.saved && !hide) {
            takeButton.setBackground(Look.shape(Look.BG, Look.AMBER, 7));
            takeButton.setTextColor(Look.AMBER);
        } else {
            takeButton.setBackground(Look.shape(Look.PANEL, Look.LINE, 7));
            takeButton.setTextColor(Look.DIM);
        }
    }

    private static String clock(double seconds) {
        int s = (int) Math.max(0, seconds);
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    private double length() {
        return player.size() == 0 ? 0 : (double) player.get(0).header.frames / player.get(0).header.rate;
    }

    private void nudge(double seconds) {
        double total = length();
        if (total <= 0) return;
        player.seek(player.fraction() + seconds / total);
        wave.setFraction(player.fraction());
    }

    private void tick() {
        if (current != this) return;
        if (compareScreen.getVisibility() == View.VISIBLE && player.size() > 0) {
            double f = player.fraction(), total = length();
            if (player.isPlaying()) wave.setFraction(f);
            wave.setLength(total);
            timeNow.setText(clock(f * total) + " / " + clock(total));
            boolean playing = player.isPlaying();
            playButton.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
            playButton.setContentDescription(playing ? "Pause" : "Play");
            // Follow headphones being connected or removed.
            if (++routeTick >= 10) {
                routeTick = 0;
                String route = onPhoneSpeaker() + " " + wornBuds();
                if (!route.equals(lastRoute)) { lastRoute = route; applyPreview(); drawPreview(); }
            }
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
            titleField.setText(song);
            setMeta("Measuring");
            songAction.setText("Change");
            Look.enable(masterButton, true);
            resetPath(false);
            measureSource(uri);
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
        String last = uri.getLastPathSegment();
        if (last == null || last.isEmpty()) return "song";
        last = last.substring(last.lastIndexOf('/') + 1);
        int dot = last.lastIndexOf('.');
        return dot > 0 ? last.substring(0, dot) : last;
    }

    private static void status(String s) {
        statusNow = s;
        ui(a -> a.statusLine.setText(s));
    }

    private static void fail(Throwable e) {
        String why = e instanceof OutOfMemoryError ? "The phone ran out of memory for this song" : String.valueOf(e.getMessage());
        main.post(() -> {
            working = false;
            rendering = false;
            stopping = false;
            statusNow = why;
            java.util.Arrays.fill(stageDone, false);
            runningStage = -1;
            ui(a -> {
                a.applyBusy();
                a.lightPath(-1);
                a.statusLine.setText(why);
                if (a.compareScreen.getVisibility() == View.VISIBLE) Toast.makeText(a, why, Toast.LENGTH_LONG).show();
            });
            RenderService.end(app, why);
        });
    }

    private void applyBusy() {
        if (rendering) {
            // While a render runs this is the way out of a wrong setting.
            masterButton.setText(stopping ? "STOPPING" : "STOP MASTERING");
            masterButton.setEnabled(!stopping);
            masterButton.setAlpha(1f);
            masterButton.setBackground(Look.shape(Look.PANEL, stopping ? Look.LINE : Look.AMBER, 7));
            masterButton.setTextColor(stopping ? Look.DIM : Look.AMBER);
        } else if (working) {
            masterButton.setText("WORKING");
            masterButton.setEnabled(false);
            masterButton.setAlpha(1f);
            masterButton.setBackground(Look.shape(Look.PANEL, Look.LINE, 7));
            masterButton.setTextColor(Look.DIM);
        } else {
            masterButton.setText("MASTER IT");
            Look.surface(masterButton, Look.gold(7));
            masterButton.setTextColor(Look.BG);
            Look.enable(masterButton, songUri != null);
        }
        progress.setVisibility(working ? View.VISIBLE : View.GONE);
        boolean takesReady = !order.isEmpty() && !rendering;
        backToTakes.setVisibility(takesReady ? View.VISIBLE : View.GONE);
        sessionScreen.setPadding(0, 0, 0, Look.dp(takesReady ? 172 : 116));
        if (working) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void busy(boolean on) {
        working = on;
        applyBusy();
    }

    private void stopRender() {
        stopping = true;
        Engineer.requestStop(true);
        applyBusy();
        status("Stopping");
    }

    /** Back to the session with the song still loaded, ready for the right settings. */
    private static void stopped() {
        working = false;
        rendering = false;
        stopping = false;
        Engineer.requestStop(false);
        java.util.Arrays.fill(stageDone, false);
        runningStage = -1;
        progressNow = 0;
        statusNow = items.isEmpty() ? "Stopped. Choose your settings and press Master It."
                : "Stopped. Your last takes are still here.";
        RenderService.end(app, null);
        ui(a -> {
            a.applyBusy(); a.lightPath(-1); a.statusLine.setText(statusNow);
            if (!order.isEmpty()) a.compareLink.setVisibility(View.VISIBLE);
        });
    }

    // Mastering ---------------------------------------------------------------

    private void master() {
        final double asked = level;
        final Target target = currentTarget();
        final Uri uri = songUri;
        final String name = song.replaceAll("[\\\\/:*?\"<>|]", "_");
        final String styleName = style.name();
        final List<String> log = tasteLog();
        final Engineer.Options options = new Engineer.Options();
        options.cleanUp = cleanUpOn;
        final boolean same = song.equals(masteredSong) && sessionDir != null && new File(sessionDir, "source.wav").exists();
        final String settings = settingsLabel();
        final List<Item> lastRound = new ArrayList<>();
        final String lastLabel = roundLabel;
        if (same) for (Item i : items) if (!i.earlier && i.report != null) lastRound.add(i);
        measureToken++;
        rendering = true;
        stopping = false;
        Engineer.requestStop(false);
        player.pause();
        blind = BLIND_OFF; pickedShown = -1;
        player.setMatched(true);
        previewMode = Preview.Mode.FULL;
        applyPreview();
        drawPreview();
        compareLink.setVisibility(View.GONE);
        busy(true);
        resetPath(false);
        progressNow = 0;
        progress.setProgress(0);
        RenderService.begin(app);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        final PowerManager.WakeLock lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "masterroom:render");
        lock.acquire(60 * 60 * 1000L);
        worker.execute(() -> {
            try {
                long t0 = System.nanoTime();
                File base = app.getExternalFilesDir(null), home = new File(base, "session");
                File dir = new File(base, "session_new");
                deleteAll(dir);
                dir.mkdirs();
                status("Opening the song");
                Audio in = same ? Wav.read(new File(sessionDir, "source.wav")) : Decoder.open(app, uri);
                File sourceFile = new File(dir, "source.wav");
                Wav.write(sourceFile, in, 24);
                List<Engineer.Recipe> recipes = new ArrayList<>();
                for (Engineer.Take take : Engineer.Take.values()) recipes.add(take.recipe());
                Engineer.Recipe mine = Taste.yours(log, styleName);
                if (mine != null) recipes.add(mine);
                final int[] takeIndex = {-1};
                final int takeCount = recipes.size();
                final long[] lastNote = {0};
                Engineer.Progress bar = (step, p) -> {
                    int overall = takeIndex[0] < 0 ? p : 15 + (int) ((takeIndex[0] + Math.max(0, p - 20) / 80.0) * 85 / takeCount);
                    long now = System.currentTimeMillis();
                    if (now - lastNote[0] > 1000) { lastNote[0] = now; RenderService.update(app, step, overall); }
                    main.post(() -> {
                        statusNow = step;
                        progressNow = overall;
                        boolean showing = current != null;
                        markStageQuiet(step);
                        if (showing) { current.statusLine.setText(step); current.progress.setProgress(overall); current.lightPath(runningStage); }
                    });
                };
                final String meta = trackMeta(in);
                float[] sourceBars = WaveView.measure(in, BARS);
                Audio[] holder = {in};
                in = null;
                Engineer.Session session = Engineer.prepare(holder, target, asked, options, bar);
                final Listen before = session.before;
                sourceFlatPeaks = session.sourceFlatPeaks;
                main.post(() -> { setMeta(meta); measuredNow = before; ui(a -> a.showMeasured(before)); });
                final List<Item> made = new ArrayList<>();
                Item sourceItem = new Item("Source", before, sourceBars, null);
                sourceItem.streaming = session.noise;
                made.add(sourceItem);
                List<Engineer.Result> results = new ArrayList<>();
                for (Engineer.Recipe take : recipes) {
                    takeIndex[0]++;
                    Engineer.Result r = Engineer.master(session, take, asked, bar);
                    float[] bars = WaveView.measure(r.master, BARS);
                    File[] files = Engineer.deliver(r, dir, name + "_" + take.label);
                    r.master = null;
                    results.add(r);
                    Item done = new Item(take.label, r.after, bars, files);
                    done.report = r.distortion;
                    done.verdict = r.verdict;
                    done.streaming = r.streaming.verdict();
                    made.add(done);
                }
                session.release();
                final String text = Engineer.sheet(session, results) + albumCheck(results)
                        + String.format(Locale.US, "%nFinished in %.1f minutes", (System.nanoTime() - t0) / 60e9);
                for (Item i : made) i.notes = text;
                // Only now do the old takes move aside, so stopping loses nothing.
                player.clear();
                File shelf = new File(base, "session_earlier");
                deleteAll(shelf);
                shelf.mkdirs();
                List<Item> kept = new ArrayList<>();
                for (Item i : lastRound) {
                    File[] moved = new File[i.files.length];
                    for (int f = 0; f < moved.length; f++) { moved[f] = new File(shelf, i.files[f].getName()); i.files[f].renameTo(moved[f]); }
                    i.files = moved;
                    i.earlier = true;
                    i.name = i.take + ", " + lastLabel;
                    kept.add(i);
                }
                deleteAll(home);
                home.mkdirs();
                File[] fresh = dir.listFiles();
                if (fresh != null) for (File f : fresh) f.renameTo(new File(home, f.getName()));
                dir.delete();
                for (Item i : made) if (i.files != null) for (int f = 0; f < i.files.length; f++) i.files[f] = new File(home, i.files[f].getName());
                List<Item> all = new ArrayList<>(made);
                all.addAll(kept);
                File playSource = new File(home, sourceFile.getName());
                for (Item i : all) player.add(new AbPlayer.Version(i.name, i.files == null ? playSource : i.files[0], i.lufs));
                sessionDir = home;
                sheet = text;
                main.post(() -> {
                    targetMid = target.midDb;
                    refPunch = target.punchDb; refWidth = target.widthDb; refLow = target.lowEndDb;
                    roundLabel = settings;
                    masteredSong = song;
                    items.clear(); order.clear(); selected = 0;
                    working = false;
                    rendering = false;
                    stopping = false;
                    Engineer.requestStop(false);
                    items.addAll(all);
                    for (int i = 0; i < player.size(); i++) order.add(i);
                    selected = 0;
                    player.select(0);
                    worker.execute(MainActivity::storeRound);
                    java.util.Arrays.fill(stageDone, true);
                    runningStage = -1;
                    statusNow = "Done. Your takes are ready to compare.";
                    openCompareNext = true;
                    RenderService.end(app, "Your takes are ready to compare");
                    ui(a -> { a.finishRender(text); });
                });
            } catch (Engineer.Stopped e) {
                deleteAll(new File(app.getExternalFilesDir(null), "session_new"));
                main.post(MainActivity::stopped);
            } catch (Throwable e) {
                fail(e);
            } finally {
                if (lock.isHeld()) lock.release();
            }
        });
    }

    private void finishRender(String text) {
        applyBusy();
        lightPath(-1);
        statusLine.setText(statusNow);
        sheetText.setText(text);
        wave.setFraction(0);
        refreshCompare();
        compareLink.setVisibility(View.VISIBLE);
        compareScreen.scrollTo(0, 0);
        openCompareNext = false;
        showCompare(true);
    }

    private static void markStageQuiet(String step) {
        if (step.contains("riding")) for (int i = 2; i < stageDone.length; i++) stageDone[i] = false;
        int stage = stageOf(step);
        stageDone[stage] = true;
        if (stage == 5) stageDone[4] = true;
        runningStage = stage;
    }

    private void importMaster(Uri uri) {
        final String name = nameOf(uri);
        importing = true;
        importButton.setText("Measuring master");
        refreshCompare();
        worker.execute(() -> {
            try {
                Audio a = Decoder.open(app, uri);
                File f = new File(sessionDir, "imported_" + System.currentTimeMillis() + ".wav");
                Wav.write(f, a, 24);
                Listen l = Listen.to(a);
                float[] bars = WaveView.measure(a, BARS);
                final String shortName = name.length() > 16 ? name.substring(0, 16) : name;
                player.add(new AbPlayer.Version(shortName, f, l.loudness.integrated));
                main.post(() -> {
                    items.add(new Item(shortName, l, bars, null));
                    order.add(player.size() - 1);
                    importing = false;
                    ui(x -> { x.importButton.setText("Import Stem Lab master"); x.refreshCompare(); });
                });
            } catch (Throwable e) {
                main.post(() -> {
                    importing = false;
                    ui(x -> { x.importButton.setText("Import Stem Lab master"); x.refreshCompare(); });
                });
                fail(e);
            }
        });
    }

    private Release.Info releaseInfo() {
        Release.Info i = new Release.Info();
        i.title = titleField.getText().toString().trim();
        if (i.title.isEmpty()) i.title = song;
        i.artist = artistField.getText().toString().trim();
        i.album = albumField.getText().toString().trim();
        i.year = yearField.getText().toString().trim();
        i.track = trackField.getText().toString().trim();
        i.isrc = isrcField.getText().toString().trim().replace("-", "");
        getSharedPreferences("release", MODE_PRIVATE).edit().putString("artist", i.artist).putString("album", i.album).putString("year", i.year).apply();
        return i;
    }

    private void saveSelected() {
        if (order.isEmpty()) return;
        final Item chosen = items.get(order.get(selected));
        if (chosen.files == null) return;
        final Release.Info info = releaseInfo();
        final String base = info.title.replaceAll("[\\\\/:*?\"<>|]", "_") + "_" + chosen.name;
        saving = true;
        refreshCompare();
        RenderService.begin(app);
        worker.execute(() -> {
            try {
                RenderService.update(app, "Saving " + chosen.name, -1);
                File mp3 = new File(sessionDir, base + "_320k.mp3");
                Release.mp3(chosen.files[1], mp3, info);
                storeWav(chosen.files[0], base + "_24bit.wav", info);
                storeWav(chosen.files[1], base + "_16bit_44k.wav", info);
                store(mp3, "audio/mpeg");
                File notes = new File(sessionDir, base + "_session.txt");
                try (PrintWriter w = new PrintWriter(new FileWriter(notes))) { w.print(chosen.notes.isEmpty() ? sheet : chosen.notes); }
                store(notes, "text/plain");
                addToAlbum(chosen);
                main.post(() -> {
                    saving = false;
                    chosen.saved = true;
                    worker.execute(MainActivity::storeRound);
                    remember("saved", chosen.take);
                    RenderService.end(app, "Saved to Download/Master Room");
                    ui(a -> {
                        a.refreshCompare();
                        a.refreshAlbum();
                        Toast.makeText(a, "Saved to Download/Master Room", Toast.LENGTH_LONG).show();
                    });
                });
            } catch (Throwable e) {
                main.post(() -> { saving = false; ui(MainActivity::refreshCompare); });
                fail(e);
            }
        });
    }

    private static Uri newDownload(String name, String mime) throws IOException {
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Master Room");
        Uri uri = app.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new IOException("Could not save " + name);
        return uri;
    }

    private static void storeWav(File f, String name, Release.Info info) throws IOException {
        Uri uri = newDownload(name, "audio/wav");
        try (OutputStream out = new java.io.BufferedOutputStream(app.getContentResolver().openOutputStream(uri), 1 << 16)) {
            Release.copyTagged(f, out, info);
        }
    }

    private static void store(File f, String mime) throws IOException {
        Uri uri = newDownload(f.getName(), mime);
        try (OutputStream out = app.getContentResolver().openOutputStream(uri); InputStream in = new FileInputStream(f)) {
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

    // Finished masters stay on the phone until new ones replace them -----------

    private static String settingsLabel() {
        return style.label + (commercial ? " Comm " : " ") + String.format(Locale.US, "%.0f", level);
    }

    private static File roundFile() { return new File(app.getExternalFilesDir(null), "masters.properties"); }

    private static void storeRound() {
        final List<Item> list = new ArrayList<>();
        for (Item i : items) if (i.files != null || "Source".equals(i.take)) list.add(i);
        java.util.Properties p = new java.util.Properties();
        p.setProperty("song", song == null ? "" : song);
        p.setProperty("uri", songUri == null ? "" : songUri.toString());
        p.setProperty("meta", metaNow == null ? "" : metaNow);
        p.setProperty("label", roundLabel);
        p.setProperty("sheet", sheet == null ? "" : sheet);
        p.setProperty("level", Double.toString(level));
        p.setProperty("count", Integer.toString(list.size()));
        p.setProperty("refs", refPunch + "," + refWidth + "," + refLow);
        if (targetMid != null) p.setProperty("target", csv(targetMid));
        p.setProperty("flat", Integer.toString(sourceFlatPeaks));
        for (int n = 0; n < list.size(); n++) {
            Item i = list.get(n);
            String k = "item." + n + ".";
            p.setProperty(k + "name", i.name);
            p.setProperty(k + "take", i.take);
            p.setProperty(k + "numbers", i.lufs + "," + i.lra + "," + i.truePeak + "," + i.punch);
            p.setProperty(k + "mid", csv(i.mid));
            p.setProperty(k + "side", csv(i.side));
            StringBuilder bars = new StringBuilder();
            for (float b : i.bars) bars.append(bars.length() == 0 ? "" : ",").append(b);
            p.setProperty(k + "bars", bars.toString());
            p.setProperty(k + "flags", i.saved + "," + i.earlier);
            p.setProperty(k + "verdict", i.verdict);
            p.setProperty(k + "streaming", i.streaming);
            p.setProperty(k + "notes", i.notes);
            if (i.files != null) p.setProperty(k + "files", i.files[0].getPath() + "|" + i.files[1].getPath());
            if (i.report != null) {
                StringBuilder spots = new StringBuilder();
                for (Distortion.Spot s : i.report.spots) spots.append(s.startSeconds).append(',').append(s.endSeconds).append(',').append(s.scoreDb).append(',').append(s.audible).append(';');
                p.setProperty(k + "report", i.report.audible + "," + i.report.borderline + "," + i.report.worstDb);
                p.setProperty(k + "spots", spots.toString());
            }
        }
        File next = new File(roundFile().getPath() + ".tmp");
        try (OutputStream out = new FileOutputStream(next)) {
            p.store(out, null);
        } catch (IOException e) {
            return;
        }
        next.renameTo(roundFile());
    }

    /** After the app has been closed: puts the last masters back, files and results. */
    private static void loadRound() {
        File f = roundFile();
        if (!f.exists()) return;
        java.util.Properties p = new java.util.Properties();
        try (InputStream in = new FileInputStream(f)) {
            p.load(in);
            File home = new File(app.getExternalFilesDir(null), "session");
            File source = new File(home, "source.wav");
            if (!source.exists()) return;
            List<Item> list = new ArrayList<>();
            int count = Integer.parseInt(p.getProperty("count", "0"));
            for (int n = 0; n < count; n++) {
                String k = "item." + n + ".";
                double[] v = numbers(p.getProperty(k + "numbers"));
                String[] barText = p.getProperty(k + "bars").split(",");
                float[] bars = new float[barText.length];
                for (int b = 0; b < bars.length; b++) bars[b] = Float.parseFloat(barText[b]);
                File[] files = null;
                String paths = p.getProperty(k + "files");
                if (paths != null) {
                    String[] two = paths.split("\\|");
                    files = new File[]{new File(two[0]), new File(two[1])};
                    if (!files[0].exists() || !files[1].exists()) continue;
                }
                Item i = new Item(p.getProperty(k + "name"), p.getProperty(k + "take"), v[0], v[1], v[2], v[3], numbers(p.getProperty(k + "mid")), numbers(p.getProperty(k + "side")), bars, files);
                String[] flags = p.getProperty(k + "flags").split(",");
                i.saved = Boolean.parseBoolean(flags[0]);
                i.earlier = Boolean.parseBoolean(flags[1]);
                i.verdict = p.getProperty(k + "verdict", "");
                i.streaming = p.getProperty(k + "streaming", "");
                i.notes = p.getProperty(k + "notes", "");
                String report = p.getProperty(k + "report");
                if (report != null) {
                    double[] r = numbers(report);
                    i.report = new Distortion.Report();
                    i.report.audible = (int) r[0]; i.report.borderline = (int) r[1]; i.report.worstDb = r[2];
                    for (String s : p.getProperty(k + "spots", "").split(";")) {
                        if (s.isEmpty()) continue;
                        String[] x = s.split(",");
                        Distortion.Spot spot = new Distortion.Spot();
                        spot.startSeconds = Double.parseDouble(x[0]); spot.endSeconds = Double.parseDouble(x[1]);
                        spot.scoreDb = Double.parseDouble(x[2]); spot.audible = Boolean.parseBoolean(x[3]);
                        i.report.spots.add(spot);
                    }
                }
                list.add(i);
            }
            if (list.isEmpty()) return;
            player.clear();
            for (Item i : list) player.add(new AbPlayer.Version(i.name, i.files == null ? source : i.files[0], i.lufs));
            items.clear(); items.addAll(list);
            order.clear();
            for (int n = 0; n < player.size(); n++) order.add(n);
            selected = 0;
            player.select(0);
            sessionDir = home;
            song = p.getProperty("song");
            masteredSong = song;
            String uri = p.getProperty("uri", "");
            songUri = uri.isEmpty() ? null : Uri.parse(uri);
            metaNow = p.getProperty("meta", "");
            roundLabel = p.getProperty("label", "");
            sheet = p.getProperty("sheet", "");
            level = Double.parseDouble(p.getProperty("level", "-14"));
            sourceFlatPeaks = Integer.parseInt(p.getProperty("flat", "0"));
            double[] refs = numbers(p.getProperty("refs", "NaN,NaN,NaN"));
            refPunch = refs[0]; refWidth = refs[1]; refLow = refs[2];
            String target = p.getProperty("target");
            targetMid = target == null ? null : numbers(target);
            statusNow = "Your masters from last time are still here.";
            openCompareNext = true;
        } catch (IOException | RuntimeException e) {
            player.clear(); items.clear(); order.clear();
        }
    }

    /** Stands in for Android closing the app, for the screen test. */
    public void forgetAndReload() {
        player.clear(); items.clear(); order.clear();
        songUri = null; song = null; masteredSong = null;
        loadRound();
        restore();
    }

    // Reference shelf: only the measured numbers are stored ---------------------

    private File shelf() { return new File(app.getFilesDir(), "references_" + style.name() + ".txt"); }

    private List<Listen> shelfRecords() {
        List<Listen> out = new ArrayList<>();
        File f = shelf();
        if (!f.exists()) return out;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\\|");
                if (p.length >= 3) out.add(Listen.fromBands(numbers(p[1]), numbers(p[2]), p.length > 3 ? Double.parseDouble(p[3]) : Double.NaN));
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
        List<Listen> album = albumRecords();
        if (!album.isEmpty()) return Target.fromReferences("Album " + albumName() + ", " + album.size() + " finished song" + (album.size() == 1 ? "" : "s"), album, commercial);
        List<Listen> refs = shelfRecords();
        return refs.isEmpty() ? Target.builtIn(style, commercial) : Target.fromReferences(refs.size() + " reference records", refs, commercial);
    }

    private void refreshReferences() {
        int n = shelfRecords().size();
        boolean album = !albumRecords().isEmpty();
        String records = n + " reference record" + (n == 1 ? "" : "s");
        referenceLine.setText(album ? (n == 0 ? "Album match is setting the tone, not the genre." : records + ", but album match sets the tone.")
                : n == 0 ? "Starting curve. Add 3 to 5 records you want to sound like."
                : records + " for " + style.label + ".");
        manageButton.setVisibility(n == 0 ? View.GONE : View.VISIBLE);
        if (n == 0) shelfList.setVisibility(View.GONE);
        drawShelf();
    }

    private List<String> shelfLines() {
        List<String> out = new ArrayList<>();
        File f = shelf();
        if (!f.exists()) return out;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) if (!line.trim().isEmpty()) out.add(line);
        } catch (IOException ignored) { }
        return out;
    }

    private void toggleShelf() {
        shelfList.setVisibility(shelfList.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
    }

    private void drawShelf() {
        shelfList.removeAllViews();
        List<String> lines = shelfLines();
        for (int i = 0; i < lines.size(); i++) {
            final int index = i;
            LinearLayout r = parts.row();
            TextView name = parts.text(lines.get(i).split("\\|")[0], Look.body, 14, Look.TEXT);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            r.addView(name, new LinearLayout.LayoutParams(0, Parts.WRAP, 1));
            Button remove = parts.link("REMOVE", v -> removeRecord(index));
            Look.type(remove, Look.label, 13, Look.AMBER);
            r.addView(remove, new LinearLayout.LayoutParams(Parts.WRAP, Look.dp(48)));
            shelfList.addView(r);
        }
    }

    private void removeRecord(int index) {
        List<String> lines = shelfLines();
        if (index >= lines.size()) return;
        lines.remove(index);
        try (PrintWriter w = new PrintWriter(new FileWriter(shelf()))) {
            for (String l : lines) w.println(l);
        } catch (IOException ignored) { }
        refreshReferences();
    }

    // Taste: which takes you choose ---------------------------------------------

    private static File tasteFile() { return new File(app.getFilesDir(), "taste.txt"); }

    private static List<String> tasteLog() {
        List<String> out = new ArrayList<>();
        File f = tasteFile();
        if (!f.exists()) return out;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) out.add(line);
        } catch (IOException ignored) { }
        return out;
    }

    private static void remember(String how, String take) {
        try (PrintWriter w = new PrintWriter(new FileWriter(tasteFile(), true))) {
            w.println(Taste.record(style.name(), how, take));
        } catch (IOException ignored) { }
    }

    // Album match: new songs aim at the album's finished tone -------------------

    private static File albumFile() { return new File(app.getFilesDir(), "album.txt"); }

    private static List<String> albumLines() {
        List<String> out = new ArrayList<>();
        File f = albumFile();
        if (!f.exists()) return out;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) if (!line.trim().isEmpty()) out.add(line);
        } catch (IOException ignored) { }
        return out;
    }

    private static String albumName() {
        List<String> lines = albumLines();
        return lines.isEmpty() ? "" : lines.get(0);
    }

    private List<Listen> albumRecords() {
        List<Listen> out = new ArrayList<>();
        List<String> lines = albumLines();
        for (int i = 1; i < lines.size(); i++) {
            String[] p = lines.get(i).split("\\|");
            try {
                if (p.length == 5) out.add(Listen.fromBands(numbers(p[3]), numbers(p[4])));
            } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    private void toggleAlbum() {
        if (albumFile().exists()) {
            albumFile().delete();
        } else {
            String name = albumField.getText().toString().trim();
            try (PrintWriter w = new PrintWriter(new FileWriter(albumFile()))) {
                w.println(name.isEmpty() ? "Current album" : name.replace("|", " "));
            } catch (IOException ignored) { }
        }
        refreshAlbum();
    }

    private void refreshAlbum() {
        List<String> lines = albumLines();
        boolean on = !lines.isEmpty();
        int songs = Math.max(0, lines.size() - 1);
        albumButton.setText(on ? "END ALBUM" : "START ALBUM");
        if (!on) albumLine.setText("Match a release in tone and loudness.");
        else if (songs == 0) albumLine.setText(lines.get(0) + ": save a take to set its sound.");
        else {
            String[] first = lines.get(1).split("\\|");
            albumLine.setText(String.format(Locale.US, "%s: %d song%s, target %s LUFS.", lines.get(0), songs, songs == 1 ? "" : "s", first[2]));
            try {
                double l = Double.parseDouble(first[2]);
                if (l != level) { level = l; drawLevels(); meter.setTarget(l); refreshCeiling(); }
            } catch (NumberFormatException ignored) { }
        }
        refreshReferences();
    }

    private static void addToAlbum(Item take) throws IOException {
        List<String> lines = albumLines();
        if (lines.isEmpty()) return;
        String key = song.replace("|", " ");
        String entry = key + "|" + String.format(Locale.US, "%.1f", take.lufs) + "|" + String.format(Locale.US, "%.0f", level) + "|" + csv(take.mid) + "|" + csv(take.side);
        boolean replaced = false;
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).startsWith(key + "|")) { lines.set(i, entry); replaced = true; }
        }
        if (!replaced) lines.add(entry);
        try (PrintWriter w = new PrintWriter(new FileWriter(albumFile()))) {
            for (String l : lines) w.println(l);
        }
    }

    /** How each take sits against the album so far, for the session sheet. */
    private String albumCheck(List<Engineer.Result> results) {
        List<String> lines = albumLines();
        if (lines.size() < 2) return "";
        double[] mid = new double[31];
        int n = lines.size() - 1;
        for (int i = 1; i < lines.size(); i++) {
            double[] m = numbers(lines.get(i).split("\\|")[3]);
            for (int k = 0; k < 31; k++) mid[k] += m[k] / n;
        }
        StringBuilder b = new StringBuilder(String.format(Locale.US, "%n%nAgainst %s:%n", lines.get(0)));
        for (Engineer.Result r : results) {
            double[] gap = ToneView.againstTarget(r.after.midDb, mid);
            b.append(String.format(Locale.US, "%s: lows %+.1f dB, top end %+.1f dB%n", r.take.label, band(gap, 40, 120), band(gap, 4000, 12000)));
        }
        return b.toString();
    }

    private static double band(double[] gap, double from, double to) {
        double sum = 0;
        int n = 0;
        for (int i = 0; i < 31; i++) if (Listen.BANDS[i] >= from && Listen.BANDS[i] <= to) { sum += gap[i]; n++; }
        return n == 0 ? 0 : sum / n;
    }

    private void addReference(Uri uri) {
        final String name = nameOf(uri).replace('|', ' ');
        final File file = shelf();
        statusLine.setText("Measuring " + name);
        statusNow = statusLine.getText().toString();
        busy(true);
        worker.execute(() -> {
            try {
                Audio a = Decoder.open(app, uri);
                Listen l = Listen.to(a);
                try (PrintWriter w = new PrintWriter(new FileWriter(file, true))) {
                    w.println(name + "|" + csv(l.midDb) + "|" + csv(l.sideDb) + "|" + String.format(Locale.US, "%.2f", l.punchDb));
                }
                main.post(() -> {
                    working = false;
                    statusNow = "Added " + name;
                    ui(x -> { x.applyBusy(); x.statusLine.setText(statusNow); x.refreshReferences(); });
                });
            } catch (Throwable e) {
                fail(e);
            }
        });
    }
}
