package com.dennis.masterroom.desktop;

import com.dennis.masterroom.engine.Audio;
import com.dennis.masterroom.engine.Engineer;
import com.dennis.masterroom.engine.Listen;
import com.dennis.masterroom.engine.Release;
import com.dennis.masterroom.engine.Spectrum;
import com.dennis.masterroom.engine.Stems;
import com.dennis.masterroom.engine.Target;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.plaf.basic.BasicComboBoxUI;
import javax.swing.plaf.basic.BasicProgressBarUI;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.prefs.Preferences;

/**
 * Pick or drop a song, choose style and level, master the three takes, see each take's loudness,
 * true peak and checks. Build 2: play the original and the takes against each other, level-matched,
 * switching without a gap, with the phone's blind test. Build 3: the console beside it (the phone's
 * loudness arc, EQ lights and tone view), following what is being heard. Build 4: stems, as in the
 * phone's Stem Lab (auto balance and vocal rides, solo, mute, nudges), heard live and mastered.
 */
final class MainWindow {
    /** Where the app keeps its places and the last session (the checks use their own node). */
    static final Preferences PREFS = Preferences.userRoot().node(System.getProperty("masterroom.prefs", "/com/dennis/masterroom/desktop"));
    private static final double[] LEVELS = {-16, -14, -13, -12, -11, -10, -9, -8};

    final JPanel root = new JPanel(new BorderLayout());
    private final JLabel songName = text("No song yet", Look.size(Look.strong, 18), Look.TEXT);
    private final JLabel songFacts = text("Drop a song here, or choose Open song. WAV, MP3, FLAC, M4A, AAC, OGG, AIFF. For stems: drop the WAVs or Stem Studio's ZIP.", Look.size(Look.body, 14), Look.DIM);
    private final JComboBox<Target.Style> style = new JComboBox<>(Target.Style.values());
    private final JComboBox<String> level = new JComboBox<>();
    private final JCheckBox commercial = check("Commercial reference", "Aim the tone at current commercial releases rather than the gentler built-in curve");
    private final JCheckBox cleanUp = check("Clean-up", "Turns down narrow resonances while they ring and eases stray top-end specks");
    private final JCheckBox mp3 = check("MP3 320", "Also writes a tagged 320 kbps MP3 of each take");
    private final JButton open = button("Open song", false), openStems = button("Open stems", false), master = button("Master", true), stop = button("Stop", false);
    private final JButton folder = button("Open folder", false), sheet = button("Session sheet", false), saveTo = button("Change", false);
    private final JLabel saveLine = text("", Look.size(Look.body, 13), Look.DIM);
    private File outRoot;
    private final JProgressBar progress = new JProgressBar(0, 100);
    private final JLabel status = text(" ", Look.size(Look.body, 14), Look.MID);
    private final ResultsView results = new ResultsView();
    private final JLabel engineLine = text("", Look.size(Look.mono, 12), Look.DIM);
    // Listening (build 2)
    private final AbPlayer player = new AbPlayer();
    private final Blind blind = new Blind(new Random());
    private final JButton play = button("Play", false), blindTest = button("Blind test", false);
    private final JCheckBox match = check("Level match", "Plays every version at the masters' level, so you hear the difference in sound, not in loudness. Playback only: no file changes.");
    private final Wave wave = new Wave();
    /** One per version, in the player's order: Original, then the takes. */
    private List<Row> rows = List.of();
    private int playing;
    private boolean listening;
    // Console (build 3)
    private final Console.Meter meter = new Console.Meter();
    private final Console.Lights lights = new Console.Lights();
    private final Console.Tone tone = new Console.Tone();
    private final JButton lightsTab = segment("LIGHTS"), toneTab = segment("TONE");
    private final JPanel views = new JPanel(new CardLayout());
    private final JLabel consoleMode = text(" ", Look.size(Look.mono, 12), Look.AMBER_DIM);
    private final JLabel viewNote = text(" ", Look.size(Look.body, 12), Look.DIM);
    private final Tile readLufs = new Tile("INTEGRATED", "LUFS"), readPeak = new Tile("TRUE PEAK", "dBTP"), readRange = new Tile("RANGE", "LU");
    private final AbPlayer.Moment heard = new AbPlayer.Moment();
    // References, album and saving (build 5)
    private final JLabel toneFrom = text(" ", Look.size(Look.strong, 14), Look.TEXT);
    private final JButton refsBtn = button("Records…", false), saveTake = button("Save take", false);
    private final JLabel gapLine = text(" ", Look.size(Look.body, 12), Look.MID);
    private boolean heardOn, showingTone;
    /** Waveform overview per version, in the player's order. */
    private float[][] peaksByVersion;
    /** The round on screen (the demo has no folder). */
    private MasterJob.Outcome round;
    // Stems (build 4)
    private final StemsView stemsView = new StemsView();
    private final JPanel bench = new JPanel(new CardLayout());
    private final JButton stemsTab = segment("STEM BALANCE"), takesTab = segment("TAKES");
    private final JPanel benchSwitch = row();
    StemFiles.Loaded stems;
    private StemMixer stemMix;
    private Stems.Reading stemReading;
    private Stems.Balance stemAuto;
    boolean stemManual, onStems;
    /** Per file of the set (Suno's stems one by one): level, mute (also in the master) and solo (listening only). */
    double[] stemDb = new double[0];
    boolean[] stemMute = new boolean[0], stemSolo = new boolean[0];
    private float[] stemRide;
    private Listen stemListen;
    private float[] stemPeaks;
    // Rounds kept and compared (build 6)
    /** The round before (same song) kept to compare, and masters brought in from elsewhere. */
    List<MasterJob.Take> extras = new ArrayList<>();
    /** The takes on screen after the Original, in row order: this round's, then the extras. */
    List<MasterJob.Take> shown = List.of();
    private File song;
    MasterJob.Outcome last;
    private boolean busy;

    MainWindow() {
        root.setBackground(Look.BG);
        root.setBorder(new EmptyBorder(18, 22, 16, 22));
        root.add(header(), BorderLayout.NORTH);
        JPanel middle = column();
        middle.add(songPanel());
        middle.add(gap(14));
        middle.add(settingsPanel());
        middle.add(gap(14));
        middle.add(actionRow());
        middle.add(gap(10));
        middle.add(progress);
        middle.add(gap(6));
        middle.add(status);
        middle.add(gap(12));
        stemsTab.setPreferredSize(new Dimension(170, 30)); stemsTab.setMaximumSize(stemsTab.getPreferredSize());
        takesTab.setPreferredSize(new Dimension(130, 30)); takesTab.setMaximumSize(takesTab.getPreferredSize());
        stemsTab.setToolTipText("The stems and their balance, played live");
        takesTab.setToolTipText("The finished takes against the original");
        benchSwitch.add(stemsTab); benchSwitch.add(Box.createHorizontalStrut(6)); benchSwitch.add(takesTab); benchSwitch.add(Box.createHorizontalGlue());
        benchSwitch.setVisible(false);
        middle.add(benchSwitch);
        middle.add(gap(8));
        bench.setOpaque(false); bench.setAlignmentX(0);
        JScrollPane resultsScroll = new JScrollPane(results, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        resultsScroll.setBorder(BorderFactory.createEmptyBorder());
        resultsScroll.setOpaque(false); resultsScroll.getViewport().setOpaque(false);
        resultsScroll.getVerticalScrollBar().setUnitIncrement(20);
        resultsScroll.getVerticalScrollBar().setPreferredSize(new Dimension(10, 0));
        bench.add(resultsScroll, "takes"); bench.add(stemsView, "stems");
        int benchHeight = TOP + 5 * ROW5 + 40;
        bench.setPreferredSize(new Dimension(800, benchHeight)); bench.setMaximumSize(new Dimension(Integer.MAX_VALUE, benchHeight));
        middle.add(bench);
        middle.add(gap(10));
        middle.add(transport());
        middle.add(Box.createVerticalGlue());
        JPanel centre = new JPanel(new BorderLayout(16, 0));
        centre.setOpaque(false);
        centre.add(middle, BorderLayout.CENTER);
        centre.add(consolePanel(), BorderLayout.EAST);
        root.add(centre, BorderLayout.CENTER);

        for (double d : LEVELS) level.addItem(String.format(Locale.US, "%.0f LUFS", d));
        styleCombo(style); styleCombo(level);
        style.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean f) {
                super.getListCellRendererComponent(l, v instanceof Target.Style s ? s.label : v, i, sel, f);
                setBackground(sel ? Look.PANEL_TOP : Look.PANEL); setForeground(sel ? Look.AMBER : Look.TEXT);
                setFont(Look.size(Look.body, 14)); setBorder(new EmptyBorder(4, 8, 4, 8));
                return this;
            }
        });
        restore();
        progress.setUI(new BasicProgressBarUI() {
            @Override protected void paintDeterminate(Graphics g, JComponent c) {
                Graphics2D g2 = (Graphics2D) g; g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int w = c.getWidth(), h = c.getHeight(), fill = (int) (w * progressBar.getPercentComplete());
                g2.setColor(Look.WELL); g2.fillRoundRect(0, 0, w, h, h, h);
                g2.setPaint(new GradientPaint(0, 0, Look.GOLD_HI, 0, h, Look.GOLD_LO)); if (fill > 0) g2.fillRoundRect(0, 0, Math.max(fill, h), h, h, h);
            }
        });
        progress.setBorder(BorderFactory.createEmptyBorder()); progress.setBorderPainted(false); progress.setOpaque(false);
        progress.setPreferredSize(new Dimension(10, 8)); progress.setMaximumSize(new Dimension(Integer.MAX_VALUE, 8)); progress.setAlignmentX(0);
        stop.setEnabled(false); folder.setEnabled(false); sheet.setEnabled(false); master.setEnabled(false);
        open.addActionListener(e -> choose());
        openStems.addActionListener(e -> chooseStems());
        openStems.setToolTipText("Vocals, drums, bass and other (guitar and piano too), as WAVs or Stem Studio's ZIP");
        stemsTab.addActionListener(e -> showBench(true));
        takesTab.addActionListener(e -> showBench(false));
        style.addActionListener(e -> { refreshTone(); if (stems != null) { applyAuto(); refreshConsole(); } });
        refsBtn.addActionListener(e -> showReferences());
        saveTake.addActionListener(e -> showSaveTake());
        commercial.addActionListener(e -> refreshConsole());
        master.addActionListener(e -> masterNow());
        stop.addActionListener(e -> { MasterJob.stop(); status.setText("Stopping at the next safe point…"); });
        folder.addActionListener(e -> { if (last != null) openFolder(last.dir); });
        sheet.addActionListener(e -> showSheet());
        saveTo.addActionListener(e -> chooseOutRoot());
        saveTo.setToolTipText("Choose the folder rounds are saved in");
        play.addActionListener(e -> togglePlay());
        blindTest.addActionListener(e -> blindStep());
        match.setSelected(true);
        match.addActionListener(e -> matchChanged());
        player.onStop(() -> SwingUtilities.invokeLater(this::refreshTransport));
        meter.live = new Console.Live() {
            @Override public boolean on() { return heardOn; }
            @Override public float light() { return heard.light; }
            @Override public double lufs() { return heard.lufs; }
        };
        lights.source = out -> { if (!heardOn) return false; System.arraycopy(heard.bars, 0, out, 0, out.length); return true; };
        lightsTab.addActionListener(e -> showTone(false));
        toneTab.addActionListener(e -> showTone(true));
        level.addActionListener(e -> refreshConsole());
        setListening(false);
        showTone(PREFS.getBoolean("toneView", false));
        Library.adopt(new File(outRoot, "taste.txt"));
        Dimension tf = new Dimension(146, toneFrom.getPreferredSize().height);
        toneFrom.setPreferredSize(tf); toneFrom.setMaximumSize(tf); toneFrom.setMinimumSize(tf);
        refreshTone();
        engineLine.setText(Ffmpeg.available() ? "FFmpeg ready: FLAC, M4A, AAC and the AAC streaming check" : "FFmpeg not found: WAV and MP3 only");
        root.setTransferHandler(new TransferHandler() {
            @Override public boolean canImport(TransferSupport s) { return !busy && s.isDataFlavorSupported(DataFlavor.javaFileListFlavor); }
            @Override public boolean importData(TransferSupport s) {
                try {
                    @SuppressWarnings("unchecked") List<File> files = (List<File>) s.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                    if (StemFiles.looksLikeStems(files)) { loadStems(files); return true; }
                    if (!files.isEmpty()) { pick(files.get(0)); return true; }
                } catch (Exception ignored) { }
                return false;
            }
        });
    }

    // ---------- Layout ----------
    private JComponent header() {
        JPanel h = row();
        JLabel t = text("MASTER ROOM", Look.size(Look.title, 30), Look.AMBER);
        JLabel s = text("   Windows trial · build 6 · same engine as the phone", Look.size(Look.label, 15), Look.DIM);
        h.add(t); h.add(s); h.add(Box.createHorizontalGlue()); h.add(engineLine);
        h.setBorder(new EmptyBorder(0, 0, 14, 0));
        return h;
    }

    private JComponent songPanel() {
        JPanel p = panel();
        JPanel words = column(); words.setOpaque(false);
        words.add(text("SONG", Look.size(Look.label, 13), Look.AMBER_DIM));
        words.add(gap(4)); words.add(songName); words.add(gap(4)); words.add(songFacts);
        p.add(words, BorderLayout.CENTER);
        JPanel side = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0)); side.setOpaque(false); side.add(open); side.add(openStems);
        p.add(side, BorderLayout.EAST);
        return p;
    }

    private JComponent settingsPanel() {
        JPanel p = panel();
        JPanel grid = new JPanel(new GridBagLayout()); grid.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints(); c.anchor = GridBagConstraints.WEST; c.insets = new Insets(0, 0, 6, 18);
        c.gridx = 0; c.gridy = 0; grid.add(text("STYLE", Look.size(Look.label, 13), Look.AMBER_DIM), c);
        c.gridx = 1; grid.add(text("LOUDNESS", Look.size(Look.label, 13), Look.AMBER_DIM), c);
        c.gridx = 0; c.gridy = 1; style.setPreferredSize(new Dimension(230, 34)); grid.add(style, c);
        c.gridx = 1; level.setPreferredSize(new Dimension(130, 34)); grid.add(level, c);
        c.gridx = 2; grid.add(commercial, c); c.gridx = 3; grid.add(cleanUp, c); c.gridx = 4; grid.add(mp3, c);
        c.gridx = 5; c.weightx = 1; grid.add(Box.createHorizontalGlue(), c);
        p.add(grid, BorderLayout.CENTER);
        return p;
    }

    private JComponent actionRow() {
        JPanel r = row();
        master.setPreferredSize(new Dimension(170, 42)); r.add(master); r.add(Box.createHorizontalStrut(10)); r.add(stop);
        r.add(Box.createHorizontalStrut(22));
        r.add(saveLine); r.add(Box.createHorizontalStrut(8));
        saveTo.setPreferredSize(new Dimension(84, 34)); saveTo.setMaximumSize(saveTo.getPreferredSize()); r.add(saveTo);
        r.add(Box.createHorizontalGlue());
        r.add(folder); r.add(Box.createHorizontalStrut(10)); r.add(sheet);
        return r;
    }

    private JComponent transport() {
        JPanel r = row();
        play.setPreferredSize(new Dimension(110, 40)); play.setMaximumSize(play.getPreferredSize());
        blindTest.setPreferredSize(new Dimension(150, 40)); blindTest.setMaximumSize(blindTest.getPreferredSize());
        play.setToolTipText("Play or pause (Space). 1 to 4 switch versions without stopping.");
        blindTest.setToolTipText("Hides the names, shuffles the order and matches levels. Pick the one you like, then see which it was.");
        r.add(play); r.add(Box.createHorizontalStrut(12)); r.add(wave); r.add(Box.createHorizontalStrut(14));
        r.add(match); r.add(Box.createHorizontalStrut(12)); r.add(blindTest);
        saveTake.setPreferredSize(new Dimension(120, 40)); saveTake.setMaximumSize(saveTake.getPreferredSize());
        saveTake.setToolTipText("Save the selected take as release files, with the song's details written in");
        r.add(Box.createHorizontalStrut(10)); r.add(saveTake);
        for (AbstractButton b : new AbstractButton[]{play, blindTest, match, open, master, stop, folder, sheet, saveTo, commercial, cleanUp, mp3, refsBtn, saveTake}) b.setFocusable(false);
        return r;
    }

    private JComponent consolePanel() {
        JPanel p = panel();
        p.setPreferredSize(new Dimension(380, 600));
        JPanel c = column();
        JPanel title = row();
        title.add(text("CONSOLE", Look.size(Look.label, 13), Look.AMBER_DIM));
        title.add(Box.createHorizontalGlue());
        title.add(consoleMode);
        c.add(title);
        c.add(gap(8));
        JPanel well = new JPanel(new BorderLayout()) {
            @Override protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create(); g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(Look.WELL); g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
                g.setColor(Look.HAIR); g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12); g.dispose();
            }
            @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
        };
        well.setOpaque(false); well.setAlignmentX(0); well.add(meter, BorderLayout.CENTER);
        meter.setToolTipText("Loudness. At rest: the selected version as measured. Playing: what you hear now (momentary, 400 ms). The gold mark is the target.");
        c.add(well);
        c.add(gap(10));
        JPanel tiles = new JPanel(new GridLayout(1, 3, 8, 0)) { @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); } };
        tiles.setOpaque(false); tiles.setAlignmentX(0);
        tiles.add(readLufs); tiles.add(readPeak); tiles.add(readRange);
        c.add(tiles);
        c.add(gap(10));
        JPanel tgt = row();
        tgt.add(text("TONE FROM", Look.size(Look.label, 12), Look.AMBER_DIM));
        tgt.add(Box.createHorizontalStrut(10));
        tgt.add(toneFrom);
        tgt.add(Box.createHorizontalGlue());
        refsBtn.setPreferredSize(new Dimension(104, 30)); refsBtn.setMaximumSize(refsBtn.getPreferredSize());
        refsBtn.setToolTipText("Reference records you want to sound like, and album match");
        tgt.add(refsBtn);
        c.add(tgt);
        c.add(gap(4));
        c.add(gapLine);
        c.add(gap(8));
        JPanel tabs = new JPanel(new GridLayout(1, 2, 6, 0)) { @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); } };
        tabs.setOpaque(false); tabs.setAlignmentX(0);
        tabs.add(lightsTab); tabs.add(toneTab);
        lightsTab.setToolTipText("EQ lights: the spectrum of what you hear. At rest, the selected version's overall tone.");
        toneTab.setToolTipText("Tone: the original and the selected take against the style's target curve.");
        c.add(tabs);
        c.add(gap(8));
        views.setOpaque(false); views.setAlignmentX(0);
        views.add(lights, "lights"); views.add(tone, "tone");
        views.setPreferredSize(new Dimension(340, 170));
        views.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        c.add(views);
        c.add(gap(6));
        c.add(viewNote);
        p.add(c, BorderLayout.CENTER);
        return p;
    }

    private void showTone(boolean on) {
        showingTone = on;
        ((CardLayout) views.getLayout()).show(views, on ? "tone" : "lights");
        lightsTab.putClientProperty("on", !on); toneTab.putClientProperty("on", on);
        lightsTab.repaint(); toneTab.repaint();
        viewNote.setText("<html>" + (on
            ? "Lined up with the target from 150 Hz to 3 kHz.<br>Above the dashed line: more than the target."
            : "The spectrum of what you hear, live.<br>At rest: the overall tone of what is selected.") + "</html>");
        PREFS.putBoolean("toneView", on);
    }

    /** Everything the console shows for the selected version (numbers hidden during the blind test). */
    private void refreshConsole() {
        if (onStems && stems != null) { refreshStemConsole(); return; }
        boolean any = !rows.isEmpty() && playing < rows.size();
        boolean hide = blind.hidden();
        Row cur = any ? rows.get(playing) : null;
        meter.target = (float) (round != null && any ? round.targetLufs : LEVELS[Math.max(0, level.getSelectedIndex())]);
        meter.hidden = hide;
        meter.measured = cur == null ? Float.NaN : (float) cur.lufs;
        meter.restingCaption = cur == null ? "AWAITING A ROUND" : cur.name.toUpperCase(Locale.ROOT) + "  ·  LUFS";
        readLufs.value = cur == null || hide ? "--" : String.format(Locale.US, "%.1f", cur.lufs);
        readPeak.value = cur == null || hide ? "--" : String.format(Locale.US, "%.1f", cur.peak);
        readRange.value = cur == null || hide || Double.isNaN(cur.lra) ? "--" : String.format(Locale.US, "%.1f", cur.lra);
        readPeak.warn = cur != null && !hide && !cur.original && cur.peak > cur.ceiling + 0.05;
        lights.setResting(Spectrum.resting(!any ? null : hide ? rows.get(0).mid : cur.mid));
        double[] target = round == null ? null : round.targetMid;
        double[] src = !any ? null : Console.Tone.againstTarget(rows.get(0).mid, target);
        double[] take = !any || cur.original ? null : Console.Tone.againstTarget(cur.mid, target);
        tone.show(src, take, cur == null ? "" : cur.name, hide);
        if (peaksByVersion != null && any) wave.peaks = peaksByVersion[hide ? 0 : Math.min(playing, peaksByVersion.length - 1)];
        consoleMode.setText(!any ? " " : player.isPlaying() || meter.playing() ? "PLAYING  ·  AS HEARD" : hide ? "BLIND TEST" : "SELECTED  ·  " + cur.name.toUpperCase(Locale.ROOT));
        gapLine.setText(gapText(cur, hide));
        saveTake.setEnabled(any && !hide && !cur.original && last != null && !busy && !onStems
            && playing - 1 < shown.size() && shown.get(playing - 1).files.length >= 2);
        meter.repaint(); lights.repaint(); readLufs.repaint(); readPeak.repaint(); readRange.repaint(); wave.repaint();
    }

    /**
     * The phone's gap panel: the selected take against the records it was aimed at (punch, low end, width),
     * and which take comes closest. Only when the round aimed at records or an album.
     */
    private String gapText(Row cur, boolean hide) {
        if (cur == null || hide || cur.original || round == null || Double.isNaN(round.targetWidth) || cur.side == null) return " ";
        Row closest = null;
        double best = Double.MAX_VALUE;
        for (Row x : rows) {
            if (x.original || x.side == null) continue;
            Listen l = Listen.fromBands(x.mid, x.side);
            double miss = Math.abs(l.lowEndDb() - round.targetLow) + Math.abs(l.widthDb() - round.targetWidth)
                + (Double.isNaN(round.targetPunch) ? 0 : Math.abs(x.punch - round.targetPunch));
            if (miss < best) { best = miss; closest = x; }
        }
        Listen l = Listen.fromBands(cur.mid, cur.side);
        String punch = Double.isNaN(round.targetPunch) ? "" : String.format(Locale.US, "punch %+.1f, ", cur.punch - round.targetPunch);
        return String.format(Locale.US, "<html>Vs records: %slow end %+.1f, width %+.1f dB.<br>Closest to the records: %s</html>",
            punch, l.lowEndDb() - round.targetLow, l.widthDb() - round.targetWidth, closest == null ? "-" : closest.name);
    }

    /** What sets the tone, on the console; an album with saved songs also sets the loudness (as on the phone). */
    private void refreshTone() {
        Target.Style st = (Target.Style) style.getSelectedItem();
        toneFrom.setText(Library.toneShort(st));
        toneFrom.setToolTipText(Library.toneLine(st));
        double albumLevel = Library.albumLevel();
        if (!Double.isNaN(albumLevel)) {
            for (int i = 0; i < LEVELS.length; i++) if (Math.abs(LEVELS[i] - albumLevel) < 0.01 && level.getSelectedIndex() != i) level.setSelectedIndex(i);
        }
    }

    /** Reference records for the style, and album match: only measured numbers are kept, never audio. */
    private void showReferences() {
        JDialog d = new JDialog(SwingUtilities.getWindowAncestor(root), "Tone references", Dialog.ModalityType.APPLICATION_MODAL);
        JPanel body = column();
        body.setBorder(new EmptyBorder(18, 20, 16, 20));
        JPanel shell = new JPanel(new BorderLayout()); shell.setBackground(Look.BG); shell.add(body, BorderLayout.CENTER);
        d.setContentPane(shell);
        Runnable[] fill = new Runnable[1];
        JLabel working = text(" ", Look.size(Look.body, 13), Look.AMBER);
        fill[0] = () -> {
            body.removeAll();
            Target.Style st = (Target.Style) style.getSelectedItem();
            body.add(text("REFERENCE RECORDS  ·  " + st.label.toUpperCase(Locale.ROOT), Look.size(Look.label, 13), Look.AMBER_DIM));
            body.add(gap(6));
            body.add(text(Library.toneLine(st), Look.size(Look.body, 14), Look.MID));
            body.add(gap(8));
            List<String> recs = Library.shelfLines(st);
            for (int i = 0; i < recs.size(); i++) {
                final int index = i;
                JPanel r = row();
                r.add(text(recs.get(i).split("\\|")[0], Look.size(Look.body, 14), Look.TEXT));
                r.add(Box.createHorizontalGlue());
                JButton rm = button("Remove", false);
                rm.setPreferredSize(new Dimension(96, 28)); rm.setMaximumSize(rm.getPreferredSize());
                rm.addActionListener(e -> { try { Library.removeRecord(st, index); } catch (Exception x) { working.setText(rootMessage(x)); } fill[0].run(); refreshTone(); });
                r.add(rm);
                body.add(r);
                body.add(gap(4));
            }
            JPanel add = row();
            JButton addBtn = button("Add records…", false);
            addBtn.setPreferredSize(new Dimension(150, 34)); addBtn.setMaximumSize(addBtn.getPreferredSize());
            addBtn.addActionListener(e -> addRecords(d, st, working, fill[0]));
            add.add(addBtn); add.add(Box.createHorizontalStrut(12)); add.add(working); add.add(Box.createHorizontalGlue());
            body.add(gap(4));
            body.add(add);
            body.add(gap(18));
            body.add(text("ALBUM MATCH", Look.size(Look.label, 13), Look.AMBER_DIM));
            body.add(gap(6));
            List<String> album = Library.albumLines();
            if (album.isEmpty()) {
                body.add(text("Match a release in tone and loudness: each take you save joins the album.", Look.size(Look.body, 14), Look.MID));
                body.add(gap(8));
                JPanel start = row();
                JTextField name = field(PREFS.get("albumName", ""), 260);
                JButton go = button("Start album", false);
                go.setPreferredSize(new Dimension(140, 34)); go.setMaximumSize(go.getPreferredSize());
                go.addActionListener(e -> {
                    try { Library.startAlbum(name.getText()); PREFS.put("albumName", name.getText().trim()); } catch (Exception x) { working.setText(rootMessage(x)); }
                    fill[0].run(); refreshTone();
                });
                start.add(text("Name", Look.size(Look.body, 14), Look.MID)); start.add(Box.createHorizontalStrut(10));
                start.add(name); start.add(Box.createHorizontalStrut(10)); start.add(go); start.add(Box.createHorizontalGlue());
                body.add(start);
            } else {
                int songs = album.size() - 1;
                double lvl = Library.albumLevel();
                body.add(text(songs == 0 ? album.get(0) + ": save a take to set its sound."
                    : String.format(Locale.US, "%s: %d song%s, target %.0f LUFS. It sets the tone and loudness of the next songs.", album.get(0), songs, songs == 1 ? "" : "s", lvl),
                    Look.size(Look.body, 14), Look.MID));
                body.add(gap(6));
                for (int i = 1; i < album.size(); i++) {
                    String[] p = album.get(i).split("\\|");
                    body.add(text(p[0] + "   " + p[1] + " LUFS", Look.size(Look.body, 13), Look.TEXT));
                }
                body.add(gap(8));
                JButton end = button("End album", false);
                end.setPreferredSize(new Dimension(130, 34)); end.setMaximumSize(end.getPreferredSize());
                end.addActionListener(e -> {
                    if (JOptionPane.showConfirmDialog(d, "End the album \"" + album.get(0) + "\"? Its saved takes stay where they are; the app stops matching them.",
                        "End album", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
                    Library.endAlbum(); fill[0].run(); refreshTone();
                });
                body.add(end);
            }
            body.add(gap(18));
            body.add(text("<html>Only the measured numbers are kept, never the audio. Album first, then records, then the style's built-in curve.</html>", Look.size(Look.body, 12), Look.DIM));
            body.add(gap(12));
            JButton close = button("Close", false);
            close.setPreferredSize(new Dimension(110, 34)); close.setMaximumSize(close.getPreferredSize());
            close.addActionListener(e -> d.dispose());
            body.add(close);
            body.revalidate(); body.repaint();
            d.pack();
            d.setSize(Math.max(620, d.getWidth()), d.getHeight());
        };
        fill[0].run();
        d.setLocationRelativeTo(root);
        d.setVisible(true);
        refreshTone();
    }

    private void addRecords(Window owner, Target.Style st, JLabel working, Runnable refill) {
        JFileChooser fc = new JFileChooser(PREFS.get("refDir", PREFS.get("lastDir", System.getProperty("user.home"))));
        fc.setMultiSelectionEnabled(true);
        fc.setDialogTitle("Records you want " + st.label + " to sound like");
        if (fc.showOpenDialog(owner) != JFileChooser.APPROVE_OPTION) return;
        File[] picked = fc.getSelectedFiles();
        if (picked.length == 0) return;
        PREFS.put("refDir", picked[0].getParent() == null ? "" : picked[0].getParent());
        new SwingWorker<String, String>() {
            @Override protected String doInBackground() {
                int done = 0;
                List<String> failed = new ArrayList<>();
                for (File f : picked) {
                    publish("Measuring " + f.getName() + "…");
                    try {
                        Listen l = Listen.to(MasterJob.open(f));
                        Library.addRecord(st, f.getName().replaceAll("\\.[^.]+$", ""), l);
                        done++;
                    } catch (Exception e) { failed.add(f.getName()); }
                }
                return "Added " + done + (failed.isEmpty() ? "" : ". Could not read: " + String.join(", ", failed));
            }
            @Override protected void process(List<String> chunks) { working.setText(chunks.get(chunks.size() - 1)); }
            @Override protected void done() {
                try { working.setText(get()); } catch (Exception e) { working.setText(rootMessage(e)); }
                refill.run(); refreshTone();
            }
        }.execute();
    }

    /** Save take: the song's details (remembered: artist, album, year) and the selected take as release files. */
    private void showSaveTake() {
        if (last == null || rows.isEmpty() || playing <= 0 || playing > shown.size() || blind.hidden()) return;
        MasterJob.Take take = shown.get(playing - 1);
        if (take.files.length < 2) return;
        JDialog d = new JDialog(SwingUtilities.getWindowAncestor(root), "Save " + take.label, Dialog.ModalityType.APPLICATION_MODAL);
        JPanel body = column();
        body.setBorder(new EmptyBorder(18, 20, 16, 20));
        JPanel shell = new JPanel(new BorderLayout()); shell.setBackground(Look.BG); shell.add(body, BorderLayout.CENTER);
        d.setContentPane(shell);
        body.add(text("SAVE " + take.label.toUpperCase(Locale.ROOT), Look.size(Look.label, 13), Look.AMBER_DIM));
        body.add(gap(6));
        body.add(text("Written into the files: WAV (24-bit and 16-bit 44.1 kHz) and MP3 320, plus the session sheet.", Look.size(Look.body, 13), Look.MID));
        body.add(gap(12));
        String songTitle = last.dir.getParentFile().getName();
        JTextField title = field(songTitle, 320), artist = field(PREFS.get("artist", ""), 320), album = field(PREFS.get("album", ""), 320),
            year = field(PREFS.get("year", ""), 120), track = field("", 120), isrc = field("", 200);
        String[] labels = {"Title", "Artist", "Album", "Year", "Track", "ISRC"};
        JTextField[] fields = {title, artist, album, year, track, isrc};
        for (int i = 0; i < labels.length; i++) {
            JPanel r = row();
            JLabel l = text(labels[i], Look.size(Look.body, 14), Look.MID);
            Dimension w = new Dimension(70, l.getPreferredSize().height); l.setPreferredSize(w); l.setMaximumSize(w);
            r.add(l); r.add(fields[i]); r.add(Box.createHorizontalGlue());
            body.add(r); body.add(gap(6));
        }
        if (Library.albumOn()) { body.add(gap(4)); body.add(text("It also joins the album \"" + Library.albumName() + "\".", Look.size(Look.body, 13), Look.AMBER)); }
        body.add(gap(14));
        JLabel working = text(" ", Look.size(Look.body, 13), Look.AMBER);
        JPanel buttons = row();
        JButton ok = button("Save", true), cancel = button("Cancel", false);
        ok.setPreferredSize(new Dimension(120, 38)); ok.setMaximumSize(ok.getPreferredSize());
        cancel.setPreferredSize(new Dimension(110, 38)); cancel.setMaximumSize(cancel.getPreferredSize());
        buttons.add(ok); buttons.add(Box.createHorizontalStrut(10)); buttons.add(cancel); buttons.add(Box.createHorizontalStrut(12)); buttons.add(working);
        body.add(buttons);
        cancel.addActionListener(e -> d.dispose());
        ok.addActionListener(e -> {
            Release.Info info = new Release.Info();
            info.title = title.getText().trim().isEmpty() ? songTitle : title.getText().trim();
            info.artist = artist.getText().trim(); info.album = album.getText().trim(); info.year = year.getText().trim();
            info.track = track.getText().trim(); info.isrc = isrc.getText().trim().replace("-", "");
            PREFS.put("artist", info.artist); PREFS.put("album", info.album); PREFS.put("year", info.year);
            ok.setEnabled(false); cancel.setEnabled(false);
            working.setText("Saving…");
            final File saveRoot = last.dir.getParentFile().getParentFile();
            final MasterJob.Outcome o = last;
            new SwingWorker<File[], Void>() {
                @Override protected File[] doInBackground() throws Exception {
                    return TakeSaver.save(take, info, saveRoot, o.sheet, o.targetLufs, o.style);
                }
                @Override protected void done() {
                    try {
                        File[] files = get();
                        d.dispose();
                        status.setText("Saved " + take.label + " as \"" + info.title + "\" in " + files[0].getParent());
                        status.setToolTipText(files[0].getParent());
                        refreshTone();
                    } catch (Exception x) {
                        working.setText("Could not save: " + rootMessage(x));
                        ok.setEnabled(true); cancel.setEnabled(true);
                    }
                }
            }.execute();
        });
        d.pack();
        d.setSize(Math.max(560, d.getWidth()), d.getHeight());
        d.setLocationRelativeTo(root);
        d.setVisible(true);
    }

    private static JTextField field(String value, int width) {
        JTextField f = new JTextField(value);
        f.setFont(Look.size(Look.body, 14));
        f.setForeground(Look.TEXT); f.setBackground(Look.WELL); f.setCaretColor(Look.AMBER);
        f.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Look.LINE), new EmptyBorder(4, 8, 4, 8)));
        Dimension d = new Dimension(width, 32);
        f.setPreferredSize(d); f.setMaximumSize(d);
        return f;
    }

    /** While balancing stems: the stems summed as they came, at rest; the balance as heard, playing. */
    private void refreshStemConsole() {
        Listen l = stemListen;
        meter.target = (float) LEVELS[Math.max(0, level.getSelectedIndex())];
        meter.hidden = false;
        meter.measured = l == null ? Float.NaN : (float) l.loudness.integrated;
        meter.restingCaption = l == null ? "MEASURING THE STEMS" : "STEMS AS THEY CAME  ·  LUFS";
        readLufs.value = l == null ? "--" : String.format(Locale.US, "%.1f", l.loudness.integrated);
        readPeak.value = l == null ? "--" : String.format(Locale.US, "%.1f", l.truePeakDb);
        readRange.value = l == null ? "--" : String.format(Locale.US, "%.1f", l.loudness.lra);
        readPeak.warn = false;
        lights.setResting(Spectrum.resting(l == null ? null : l.midDb));
        double[] target = Target.builtIn((Target.Style) style.getSelectedItem(), commercial.isSelected()).midDb;
        tone.show(l == null ? null : Console.Tone.againstTarget(l.midDb, target), null, "", false);
        wave.peaks = stemPeaks;
        consoleMode.setText(player.isPlaying() || meter.playing() ? "PLAYING  ·  STEM BALANCE" : "STEMS");
        meter.repaint(); lights.repaint(); readLufs.repaint(); readPeak.repaint(); readRange.repaint(); wave.repaint();
    }

    // ---------- Actions ----------
    private void choose() {
        JFileChooser fc = new JFileChooser(PREFS.get("lastDir", System.getProperty("user.home")));
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Audio (" + String.join(", ", MasterJob.READS) + ")", MasterJob.READS));
        if (fc.showOpenDialog(root) == JFileChooser.APPROVE_OPTION) pick(fc.getSelectedFile());
    }

    void pick(File f) {
        if (busy) return;
        if (!MasterJob.readable(f)) { status.setText("That file type is not supported: " + f.getName()); return; }
        if (stems != null) clearStems();
        song = f;
        PREFS.put("lastDir", f.getParent() == null ? "" : f.getParent());
        songName.setText(f.getName());
        songFacts.setText("Listening to the whole song…");
        master.setEnabled(false);
        new SwingWorker<Listen, Void>() {
            @Override protected Listen doInBackground() throws Exception { Audio a = MasterJob.open(f); return Listen.to(a); }
            @Override protected void done() {
                try {
                    Listen l = get();
                    songFacts.setText(String.format(Locale.US, "As it came in: %.1f LUFS, %.1f LU range, %.1f dBTP true peak", l.loudness.integrated, l.loudness.lra, l.truePeakDb));
                    master.setEnabled(!busy);
                } catch (Exception e) {
                    songFacts.setText("Could not read it: " + rootMessage(e));
                }
            }
        }.execute();
    }

    private void masterNow() {
        if ((song == null && stems == null) || busy) return;
        save();
        MasterJob.Settings s = new MasterJob.Settings();
        s.song = song; s.style = (Target.Style) style.getSelectedItem(); s.lufs = LEVELS[level.getSelectedIndex()];
        s.commercial = commercial.isSelected(); s.cleanUp = cleanUp.isSelected(); s.mp3 = mp3.isSelected();
        s.outRoot = outRoot;
        boolean ownTone = Library.albumRecords().size() > 0 || !Library.records(s.style).isEmpty();
        s.target = ownTone ? Library.target(s.style, s.commercial) : null;
        s.yours = Library.yours(s.style);
        s.albumCheck = Library.albumOn();
        if (stems != null) {
            s.use(stems); s.stemAuto = !stemManual;
            s.trackDb = stemDb.clone(); s.trackMute = stemMute.clone(); s.stemRide = stemRide; s.stemReading = stemReading;
        }
        final MasterJob.Outcome before = last;
        final List<MasterJob.Take> beforeExtras = extras;
        player.use(null);
        stopListening();
        setBusy(true);
        results.show(null);
        new SwingWorker<MasterJob.Outcome, Object[]>() {
            @Override protected MasterJob.Outcome doInBackground() throws Exception { return MasterJob.run(s, (step, p) -> publish(new Object[]{step, p})); }
            @Override protected void process(List<Object[]> chunks) { Object[] c = chunks.get(chunks.size() - 1); status.setText((String) c[0]); progress.setValue((Integer) c[1]); }
            @Override protected void done() {
                setBusy(false);
                try {
                    last = get();
                    extras = keptFrom(before, beforeExtras, last);
                    results.show(last);
                    progress.setValue(100);
                    status.setText(String.format(Locale.US, "Done in %.1f minutes. Takes are in %s", last.seconds / 60, last.dir.getAbsolutePath()));
                    status.setToolTipText(last.settings);
                    folder.setEnabled(true); sheet.setEnabled(true);
                    showBench(false);
                    loadListening(last);
                    keepRound();
                } catch (Exception e) {
                    String m = rootMessage(e);
                    status.setText(m.contains("Stopped") ? "Stopped. Nothing was finished from this round." : "It did not finish: " + m);
                    progress.setValue(0);
                }
            }
        }.execute();
    }

    /**
     * As on the phone: a new round of the same song (or stems) keeps the round before's takes to compare,
     * named with their settings ("Warm · Country -14"), along with any imported masters. Another song starts clean.
     */
    static List<MasterJob.Take> keptFrom(MasterJob.Outcome before, List<MasterJob.Take> beforeExtras, MasterJob.Outcome now) {
        List<MasterJob.Take> kept = new ArrayList<>();
        if (before == null || !before.sourceKey.equals(now.sourceKey)) return kept;
        for (MasterJob.Take t : before.takes) {
            MasterJob.Take e = copy(t);
            e.kind = "earlier";
            e.label = t.label + " · " + before.shortSettings;
            e.about = "Earlier round, " + before.shortSettings + ": " + t.about;
            kept.add(e);
        }
        for (MasterJob.Take t : beforeExtras) if (t.kind.equals("imported")) kept.add(t);
        return kept;
    }

    private static MasterJob.Take copy(MasterJob.Take t) {
        MasterJob.Take c = new MasterJob.Take();
        c.label = t.label; c.about = t.about; c.verdict = t.verdict; c.streaming = t.streaming; c.report = t.report;
        c.lufs = t.lufs; c.truePeak = t.truePeak; c.ceiling = t.ceiling; c.lra = t.lra; c.punch = t.punch;
        c.mid = t.mid; c.side = t.side; c.files = t.files; c.kind = t.kind;
        return c;
    }

    /** Keeps the round (and what is compared with it) so the app opens here next time. */
    private void keepRound() {
        if (last == null) return;
        try {
            RoundStore.save(last, extras);
            PREFS.put("lastRound", last.dir.getAbsolutePath());
        } catch (Exception e) {
            status.setToolTipText("Could not keep this round for next time: " + rootMessage(e));
        }
    }

    /** Brings back the round left open last time, if its files are still there. */
    void restoreRound() {
        String at = PREFS.get("lastRound", "");
        if (at.isEmpty()) return;
        RoundStore.Round r = RoundStore.load(new File(at));
        if (r == null) return;
        last = r.outcome();
        extras = r.extras();
        if (!last.sourceKey.startsWith("stems:")) {
            File f = new File(last.sourceKey);
            if (f.isFile()) song = f;
            songName.setText(f.getName());
        } else {
            songName.setText(last.sourceKey.substring(6));
        }
        songFacts.setText(String.format(Locale.US, "As it came in%s: %.1f LUFS, %.1f LU range, %.1f dBTP true peak",
            last.sourceKey.startsWith("stems:") ? " (stems summed)" : "", last.beforeLufs, last.beforeLra, last.beforePeak));
        results.show(last);
        progress.setValue(100);
        status.setText("Your last round is back: " + last.settings + ". Takes are in " + last.dir.getAbsolutePath());
        folder.setEnabled(true); sheet.setEnabled(true);
        master.setEnabled(song != null || stems != null);
        loadListening(last);
    }

    /** A master made elsewhere (Stem Lab on the phone, another tool), to compare level-matched with the takes. */
    private void importMaster() {
        if (last == null || busy) return;
        JFileChooser fc = new JFileChooser(PREFS.get("importDir", PREFS.get("lastDir", System.getProperty("user.home"))));
        fc.setDialogTitle("A master to compare with " + last.dir.getParentFile().getName());
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Audio (" + String.join(", ", MasterJob.READS) + ")", MasterJob.READS));
        if (fc.showOpenDialog(root) != JFileChooser.APPROVE_OPTION) return;
        File f = fc.getSelectedFile();
        PREFS.put("importDir", f.getParent() == null ? "" : f.getParent());
        final MasterJob.Outcome o = last;
        status.setText("Measuring " + f.getName() + "…");
        new SwingWorker<MasterJob.Take, Void>() {
            @Override protected MasterJob.Take doInBackground() throws Exception { return Importer.take(f, o); }
            @Override protected void done() {
                try {
                    MasterJob.Take t = get();
                    if (o != last) return;
                    extras.add(t);
                    loadListening(last);
                    keepRound();
                    status.setText("Added " + f.getName() + " to compare. It plays level-matched like the takes.");
                } catch (Exception e) {
                    status.setText("Could not bring in that master: " + rootMessage(e));
                }
            }
        }.execute();
    }

    private void setBusy(boolean on) {
        busy = on;
        master.setEnabled(!on && (song != null || stems != null)); open.setEnabled(!on); openStems.setEnabled(!on); stop.setEnabled(on); saveTo.setEnabled(!on);
        stemsTab.setEnabled(!on); takesTab.setEnabled(!on);
        stemsView.draw();
        style.setEnabled(!on); level.setEnabled(!on); commercial.setEnabled(!on); cleanUp.setEnabled(!on); mp3.setEnabled(!on);
    }

    // ---------- Listening ----------
    record Row(String name, String about, double lufs, double peak, double ceiling, double lra, double[] mid, double[] side, double punch, String verdict, String streaming, boolean original) {}

    static List<Row> rowsFor(MasterJob.Outcome o) { return rowsFor(o, o.takes); }

    static List<Row> rowsFor(MasterJob.Outcome o, List<MasterJob.Take> takes) {
        List<Row> r = new ArrayList<>();
        r.add(new Row("Original", o.stemNotes.isEmpty() ? "The song as it came in, before mastering" : "The stems summed as they came, before mastering",
            o.beforeLufs, o.beforePeak, Double.NaN, o.beforeLra, o.beforeMid, o.beforeSide, o.beforePunch, "As it came in", "", true));
        for (MasterJob.Take t : takes) r.add(new Row(t.label, t.about, t.lufs, t.truePeak, t.ceiling, t.lra, t.mid, t.side, t.punch, t.verdict, t.streaming, false));
        return r;
    }

    private static List<MasterJob.Take> together(MasterJob.Outcome o, List<MasterJob.Take> extras) {
        List<MasterJob.Take> all = new ArrayList<>(o.takes);
        all.addAll(extras);
        return all;
    }

    /** Playback trim per version (dB) with level match on: the same rule the player uses. */
    private double[] trims() {
        double[] lufs = new double[rows.size()], peak = new double[rows.size()];
        boolean[] master = new boolean[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            lufs[i] = rows.get(i).lufs; peak[i] = rows.get(i).peak;
            master[i] = !rows.get(i).original && (i - 1 >= shown.size() || shown.get(i - 1).isTake());
        }
        return AbMixer.matchDb(lufs, peak, master);
    }

    private void showRows(List<Row> r, MasterJob.Outcome o) {
        rows = r; round = o; blind.reset(r.size()); playing = 0;
        match.setSelected(true); match.setEnabled(true); blindTest.setText("Blind test");
        results.repaint();
        refreshConsole();
    }

    /**
     * Opens the original and the 24-bit takes for playback (files are only read): this round's, then the round
     * before (kept to compare) and imported masters. Only this round's takes set the level-match reference.
     */
    private void loadListening(MasterJob.Outcome o) {
        final List<MasterJob.Take> takes = together(o, extras);
        shown = takes;
        showRows(rowsFor(o, takes), o);
        status.setToolTipText(o.settings);
        results.revalidate();
        new SwingWorker<float[][], Void>() {
            @Override protected float[][] doInBackground() throws Exception {
                player.mix.clear();
                player.mix.add(new AbMixer.Version("Original", o.source, o.beforeLufs, o.beforePeak, false));
                for (MasterJob.Take t : takes) player.mix.add(new AbMixer.Version(t.label, t.files[0], t.lufs, t.truePeak, t.isTake()));
                player.mix.setMatched(true);
                player.mix.select(0);
                float[][] peaks = new float[1 + takes.size()][];
                peaks[0] = Overview.peaks(o.source, 1600);
                for (int i = 0; i < takes.size(); i++) peaks[i + 1] = Overview.peaks(takes.get(i).files[0], 1600);
                return peaks;
            }
            @Override protected void done() {
                try {
                    peaksByVersion = get();
                    setListening(true);
                    refreshConsole();
                } catch (Exception e) {
                    player.mix.clear();
                    status.setText("Listening is not available for this round: " + rootMessage(e));
                }
            }
        }.execute();
    }

    private void stopListening() {
        player.pause();
        player.mix.clear();
        wave.peaks = null;
        peaksByVersion = null;
        rows = List.of();
        round = null;
        blind.reset(0);
        setListening(false);
        refreshConsole();
    }

    private void setListening(boolean on) {
        listening = on;
        refreshControls();
    }

    /** What can play now: the stems while balancing them, otherwise the takes. */
    private boolean canPlay() { return onStems ? stemMix != null : listening; }

    private void refreshControls() {
        boolean takes = !onStems && listening;
        play.setEnabled(canPlay()); blindTest.setEnabled(takes); match.setEnabled(takes && blind.state == Blind.State.OFF);
        wave.setEnabled(canPlay());
        refreshTransport();
    }

    private void refreshTransport() {
        play.setText(player.isPlaying() ? "Pause" : "Play");
        if (player.problem() != null) status.setText(player.problem());
        wave.repaint();
        refreshConsole();
    }

    private void togglePlay() {
        if (!canPlay()) return;
        if (player.isPlaying()) player.pause(); else player.play();
        refreshTransport();
    }

    /** A row on screen (0 = top). During the blind test rows hold shuffled versions. */
    private void selectRow(int r) {
        if (onStems || !listening || r < 0 || r >= rows.size()) return;
        playing = blind.version(r);
        player.mix.select(playing);
        results.repaint();
        refreshConsole();
    }

    private void seekBy(double seconds) {
        if (!canPlay()) return;
        Feed f = player.feed();
        double len = f.length();
        if (len > 0) f.seek((f.seconds() + seconds) / len);
        wave.repaint();
    }

    private void matchChanged() {
        if (blind.state != Blind.State.OFF) { match.setSelected(true); return; }
        player.mix.setMatched(match.isSelected());
        results.repaint();
    }

    private void blindStep() {
        if (!listening) return;
        switch (blind.state) {
            case OFF -> {
                playing = blind.start(blind.row(playing), match.isSelected());
                player.mix.select(playing);
                match.setSelected(true); match.setEnabled(false); player.mix.setMatched(true);
                blindTest.setText("Pick this one");
                status.setText("Blind test: names hidden, order shuffled, levels matched. Listen with 1 to " + rows.size() + ", then Pick this one.");
            }
            case ON -> {
                int row = blind.row(playing);
                int v = blind.pick(row);
                blindTest.setText("Finish");
                String note;
                try {
                    StringBuilder names = new StringBuilder();
                    for (Row x : rows) names.append(names.length() == 0 ? "" : ", ").append(x.name);
                    File outRootOfRound = last.dir.getParentFile().getParentFile();
                    File f = Blind.log(outRootOfRound, last.dir.getParentFile().getName(), last.settings, rows.get(v).name, names.toString());
                    Library.remember(last.style, "blind", rows.get(v).original ? "Source" : rows.get(v).name.split(" · ")[0]);
                    note = " Saved to " + f.getName() + ".";
                } catch (Exception e) {
                    note = " (Could not save the pick: " + rootMessage(e) + ")";
                }
                status.setText("You picked Version " + (row + 1) + ": " + rows.get(v).name + "." + note);
            }
            case RESULTS -> {
                blind.finish(playing);
                match.setSelected(blind.matchedBefore); match.setEnabled(true); player.mix.setMatched(blind.matchedBefore);
                blindTest.setText("Blind test");
                status.setText("Back in the usual order.");
            }
        }
        results.repaint();
        refreshConsole();
    }

    /** Space, 1 to 4, M, arrows: work wherever focus is in the window (not in dialogs or open lists). */
    private boolean key(KeyEvent e, Window frame) {
        if (e.getID() != KeyEvent.KEY_PRESSED || !canPlay() || e.isControlDown() || e.isAltDown()) return false;
        Component c = e.getComponent();
        Window w = c instanceof Window cw ? cw : SwingUtilities.getWindowAncestor(c);
        if (w != frame || style.isPopupVisible() || level.isPopupVisible()) return false;
        int k = e.getKeyCode();
        if (k == KeyEvent.VK_SPACE) { togglePlay(); return true; }
        if (k >= KeyEvent.VK_1 && k <= KeyEvent.VK_9) { selectRow(k - KeyEvent.VK_1); return true; }
        if (k >= KeyEvent.VK_NUMPAD1 && k <= KeyEvent.VK_NUMPAD9) { selectRow(k - KeyEvent.VK_NUMPAD1); return true; }
        if (k == KeyEvent.VK_M) { if (match.isEnabled()) match.doClick(); return true; }
        if (k == KeyEvent.VK_LEFT) { seekBy(-5); return true; }
        if (k == KeyEvent.VK_RIGHT) { seekBy(5); return true; }
        if (k == KeyEvent.VK_HOME) { player.feed().seek(0); wave.repaint(); return true; }
        return false;
    }

    private void chooseOutRoot() {
        JFileChooser fc = new JFileChooser(outRoot.isDirectory() ? outRoot : outRoot.getParentFile());
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fc.setDialogTitle("Save rounds in");
        fc.setApproveButtonText("Save here");
        if (fc.showOpenDialog(root) != JFileChooser.APPROVE_OPTION || fc.getSelectedFile() == null) return;
        setOutRoot(fc.getSelectedFile());
        PREFS.put("outRoot", outRoot.getAbsolutePath());
        status.setText("New rounds will be saved in " + outRoot.getAbsolutePath());
    }

    private void setOutRoot(File f) {
        outRoot = f;
        saveLine.setText("Saving to " + shortPath(f));
        saveLine.setToolTipText(f.getAbsolutePath());
    }

    /** C:\Users\denni\Documents\Master Room → Documents\Master Room when it is the usual place; long paths shortened in the middle. */
    static String shortPath(File f) {
        String p = f.getAbsolutePath(), docs = MasterJob.documents().getAbsolutePath();
        if (p.startsWith(docs + File.separator)) p = MasterJob.documents().getName() + p.substring(docs.length());
        return p.length() <= 46 ? p : p.substring(0, 18) + "…" + p.substring(p.length() - 26);
    }

    private void showSheet() {
        if (last == null) return;
        JTextArea t = new JTextArea(last.sheet, 30, 90);
        t.setEditable(false); t.setFont(Look.size(Look.mono, 13)); t.setBackground(Look.WELL); t.setForeground(Look.MID); t.setCaretColor(Look.AMBER);
        t.setBorder(new EmptyBorder(10, 12, 10, 12));
        JScrollPane sp = new JScrollPane(t); sp.setBorder(new LineBorder(Look.LINE));
        JOptionPane.showMessageDialog(root, sp, "Session sheet", JOptionPane.PLAIN_MESSAGE);
    }

    private static void openFolder(File dir) {
        try { Desktop.getDesktop().open(dir); } catch (Exception e) { JOptionPane.showMessageDialog(null, dir.getAbsolutePath(), "Takes folder", JOptionPane.INFORMATION_MESSAGE); }
    }

    private void restore() {
        try { style.setSelectedItem(Target.Style.valueOf(PREFS.get("style", "COUNTRY"))); } catch (Exception e) { style.setSelectedItem(Target.Style.COUNTRY); }
        double lv = PREFS.getDouble("level", -14);
        for (int i = 0; i < LEVELS.length; i++) if (LEVELS[i] == lv) level.setSelectedIndex(i);
        String chosen = PREFS.get("outRoot", "");
        setOutRoot(!chosen.isEmpty() && new File(chosen).isDirectory() ? new File(chosen) : MasterJob.defaultOutRoot());
        commercial.setSelected(PREFS.getBoolean("commercial", false)); cleanUp.setSelected(PREFS.getBoolean("cleanUp", true)); mp3.setSelected(PREFS.getBoolean("mp3", true));
    }

    private void save() {
        PREFS.put("style", ((Target.Style) style.getSelectedItem()).name()); PREFS.putDouble("level", LEVELS[level.getSelectedIndex()]);
        PREFS.putBoolean("commercial", commercial.isSelected()); PREFS.putBoolean("cleanUp", cleanUp.isSelected()); PREFS.putBoolean("mp3", mp3.isSelected());
    }

    static String rootMessage(Throwable e) {
        while (e.getCause() != null) e = e.getCause();
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    // ---------- Stems ----------
    private void chooseStems() {
        JFileChooser fc = new JFileChooser(PREFS.get("lastDir", System.getProperty("user.home")));
        fc.setMultiSelectionEnabled(true);
        fc.setDialogTitle("Open stems: select all the WAVs, or Stem Studio's ZIP");
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Stems (WAV, ZIP)", "wav", "zip"));
        if (fc.showOpenDialog(root) == JFileChooser.APPROVE_OPTION && fc.getSelectedFiles().length > 0) loadStems(List.of(fc.getSelectedFiles()));
    }

    void loadStems(List<File> files) { loadStems(files, false); }

    /** @param restoring bringing back the stems from last time: keep the round and the saved balance */
    private void loadStems(List<File> files, boolean restoring) {
        if (busy) return;
        PREFS.put("lastDir", files.get(0).getParent() == null ? "" : files.get(0).getParent());
        songFacts.setText("Bringing in the stems…");
        master.setEnabled(false);
        record Measured(StemFiles.Loaded loaded, Listen listen, float[] peaks, Stems.Reading reading) {}
        new SwingWorker<Measured, Void>() {
            @Override protected Measured doInBackground() throws Exception {
                StemFiles.Loaded l = StemFiles.load(files);
                try {
                    Audio a = Stems.mix(l.set, null, null);
                    Listen li = Listen.to(a);
                    float[] peaks = Overview.peaks(a, 1600);
                    a = null;
                    return new Measured(l, li, peaks, Stems.read(l.set));
                } catch (Exception e) { l.discard(); throw e; }
            }
            @Override protected void done() {
                try {
                    Measured m = get();
                    useStems(m.loaded(), m.listen(), m.peaks(), m.reading(), files, restoring);
                } catch (Exception e) {
                    songFacts.setText("Could not use those stems: " + rootMessage(e));
                    master.setEnabled(!busy && (song != null || stems != null));
                }
            }
        }.execute();
    }

    private void useStems(StemFiles.Loaded loaded, Listen listen, float[] peaks, Stems.Reading reading) throws java.io.IOException {
        useStems(loaded, listen, peaks, reading, null, false);
    }

    private void useStems(StemFiles.Loaded loaded, Listen listen, float[] peaks, Stems.Reading reading, List<File> picked, boolean restoring) throws java.io.IOException {
        // The balance as it was left (read before the auto balance below writes over it).
        double[] keptDb = restoring ? csvToDoubles(PREFS.get("stems.db", "")) : new double[0];
        String keptMute = restoring ? PREFS.get("stems.mute", "") : "";
        boolean keptManual = restoring && PREFS.getBoolean("stems.manual", false);
        clearStems();
        if (!restoring) stopListening();
        stems = loaded; stemListen = listen; stemPeaks = peaks; stemReading = reading;
        song = null;
        stemManual = false;
        int n = stems.tracks.size();
        stemDb = new double[n]; stemMute = new boolean[n]; stemSolo = new boolean[n];
        stemMix = new StemMixer(stems);
        applyAuto();
        if (picked != null) {
            StringBuilder b = new StringBuilder();
            for (File f : picked) b.append(b.length() == 0 ? "" : "\n").append(f.getAbsolutePath());
            PREFS.put("stems.files", b.toString());
        }
        if (keptManual && keptDb.length == n && keptMute.length() == n) {
            // Set by hand last time: the levels and mutes as they were left.
            stemManual = true;
            for (int t = 0; t < n; t++) { stemDb[t] = keptDb[t]; stemMute[t] = keptMute.charAt(t) == '1'; }
            stemsChanged();
        }
        songName.setText(stems.title + "  (" + stems.tracks.size() + " stems)");
        songFacts.setText(String.format(Locale.US, "As it came in (stems summed): %.1f LUFS, %.1f LU range, %.1f dBTP true peak",
            stemListen.loudness.integrated, stemListen.loudness.lra, stemListen.truePeakDb));
        benchSwitch.setVisible(true);
        stemsView.rebuild();
        showBench(!restoring || !PREFS.get("view", "stems").equals("takes") || last == null);
        master.setEnabled(!busy);
        status.setText(stems.notes.isEmpty() ? "Stems loaded. Listen to the balance, then press Master."
            : "Stems loaded. " + String.join(". ", stems.notes) + ".");
        status.setToolTipText(String.join("\n", stems.notes));
    }

    /** Back to working from a song. A ZIP's unpacked stems are deleted; picked WAVs are left where they are. */
    private void clearStems() {
        if (stems == null) return;
        if (onStems) player.use(null);
        if (stemMix != null) stemMix.close();
        stems.discard();
        stems = null; stemMix = null; stemReading = null; stemAuto = null; stemRide = null; stemListen = null; stemPeaks = null;
        stemManual = false; onStems = false;
        for (String k : new String[]{"stems.files", "stems.db", "stems.mute", "stems.manual"}) PREFS.remove(k);
        benchSwitch.setVisible(false);
        ((CardLayout) bench.getLayout()).show(bench, "takes");
        refreshControls();
    }

    private void showBench(boolean wantStems) {
        boolean st = wantStems && stems != null;
        onStems = st;
        if (stems != null) PREFS.put("view", st ? "stems" : "takes");
        player.use(st ? stemMix : null);
        ((CardLayout) bench.getLayout()).show(bench, st ? "stems" : "takes");
        stemsTab.putClientProperty("on", st); takesTab.putClientProperty("on", !st);
        stemsTab.repaint(); takesTab.repaint();
        if (!st) wave.peaks = peaksByVersion == null ? null : peaksByVersion[blind.hidden() ? 0 : Math.min(playing, peaksByVersion.length - 1)];
        refreshControls();
        refreshConsole();
    }

    /**
     * The phone's rule: the app's own balance unless Dennis has taken over. The engine decides a level per stem
     * (Vocals, Drums, Bass, Other…); every file of that stem shows it, so Suno's files can then be set one by one.
     */
    private void applyAuto() {
        if (stems == null) return;
        if (!stemManual && stemReading != null) {
            stemAuto = MasterJob.balanced(stemReading, (Target.Style) style.getSelectedItem(), stems.silent);
            for (int t = 0; t < stemDb.length; t++) stemDb[t] = stemAuto.levelDb[stems.tracks.get(t).part];
            java.util.Arrays.fill(stemMute, false);
            stemRide = stemAuto.rideDb;
        }
        stemsChanged();
    }

    /** What each file plays at now. A solo silences the rest, but only while listening. */
    private float[] stemGains() {
        boolean solo = false;
        for (boolean x : stemSolo) solo |= x;
        float[] g = new float[stemDb.length];
        for (int t = 0; t < g.length; t++) {
            boolean heard = solo ? stemSolo[t] : !stemMute[t];
            g[t] = heard ? (float) Math.pow(10, stemDb[t] / 20) : 0f;
        }
        return g;
    }

    private void stemsChanged() {
        if (stemMix != null) stemMix.setGains(stemGains(), stemRide);
        stemsView.draw();
        if (stems != null) {
            StringBuilder db = new StringBuilder(), mute = new StringBuilder();
            for (int t = 0; t < stemDb.length; t++) { db.append(t == 0 ? "" : ",").append(stemDb[t]); mute.append(stemMute[t] ? '1' : '0'); }
            PREFS.put("stems.db", db.toString()); PREFS.put("stems.mute", mute.toString()); PREFS.putBoolean("stems.manual", stemManual);
        }
    }

    private static double[] csvToDoubles(String s) {
        if (s == null || s.isEmpty()) return new double[0];
        try {
            String[] p = s.split(",");
            double[] d = new double[p.length];
            for (int i = 0; i < p.length; i++) d[i] = Double.parseDouble(p[i]);
            return d;
        } catch (NumberFormatException e) { return new double[0]; }
    }

    /** Brings back the stems that were open last time (the files are read again; a ZIP is unpacked again). */
    void restoreStems() {
        String list = PREFS.get("stems.files", "");
        if (list.isEmpty()) return;
        List<File> files = new ArrayList<>();
        for (String p : list.split("\n")) { File f = new File(p); if (!f.isFile()) return; files.add(f); }
        loadStems(files, true);
    }

    void stemNudge(int t, double db) {
        stemManual = true;
        stemDb[t] = Math.max(-Stems.REACH_DB, Math.min(Stems.REACH_DB, Math.round((stemDb[t] + db) * 2) / 2.0));
        stemsChanged();
    }

    /**
     * The stem balance: one row per file with solo, mute and level, and the auto balance in words. Up to six
     * files in one column (Stem Studio); more (Suno's up to twelve) in two.
     */
    final class StemsView extends JPanel {
        private final JLabel summary = text(" ", Look.size(Look.body, 13), Look.MID);
        private final JButton auto = button("Back to auto", false);
        private final JPanel lines = new JPanel();
        private final JLabel note = text(" ", Look.size(Look.body, 12), Look.DIM);
        private JLabel[] names = new JLabel[0], values = new JLabel[0];
        private JButton[] solos = new JButton[0], mutes = new JButton[0], downs = new JButton[0], ups = new JButton[0];

        StemsView() {
            super(new BorderLayout());
            setOpaque(false);
            setBorder(new EmptyBorder(12, 16, 10, 16));
            JPanel top = row();
            top.add(text("STEM BALANCE", Look.size(Look.label, 13), Look.AMBER_DIM));
            top.add(Box.createHorizontalStrut(14));
            top.add(summary);
            top.add(Box.createHorizontalGlue());
            auto.setPreferredSize(new Dimension(132, 30)); auto.setMaximumSize(auto.getPreferredSize());
            auto.setToolTipText("Forget your changes and use the app's own balance again");
            auto.addActionListener(e -> { stemManual = false; java.util.Arrays.fill(stemSolo, false); applyAuto(); });
            top.add(auto);
            add(top, BorderLayout.NORTH);
            lines.setOpaque(false);
            lines.setBorder(new EmptyBorder(8, 0, 4, 0));
            add(lines, BorderLayout.CENTER);
            add(note, BorderLayout.SOUTH);
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create(); g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Look.PANEL); g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 14, 14);
            g.setColor(Look.HAIR); g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 14, 14); g.dispose();
        }

        void rebuild() {
            lines.removeAll();
            int n = stems == null ? 0 : stems.tracks.size();
            names = new JLabel[n]; values = new JLabel[n];
            solos = new JButton[n]; mutes = new JButton[n]; downs = new JButton[n]; ups = new JButton[n];
            boolean two = n > 6;
            if (two) {
                int rows = (n + 1) / 2;
                lines.setLayout(new GridLayout(rows, 2, 22, 0));
                // GridLayout fills across, so place the first half down the left and the rest down the right.
                for (int r = 0; r < rows; r++)
                    for (int c = 0; c < 2; c++) {
                        int t = c * rows + r;
                        lines.add(t < n ? line(t, true) : Box.createHorizontalStrut(1));
                    }
            } else {
                lines.setLayout(new GridLayout(Math.max(1, n), 1, 0, 0));
                for (int t = 0; t < n; t++) lines.add(line(t, false));
            }
            note.setText(two ? "<html>Solo: listening only. Mute: also out of the master. Each file's stem is shown on the right; the stems are treated and balanced as the phone does.</html>"
                : "<html>Solo is for listening only. Mute also leaves the stem out of the master. Vocals and other instruments are cleaned on their own; drums and bass are left as they are.</html>");
            lines.revalidate(); lines.repaint();
            draw();
        }

        private JComponent line(int t, boolean compact) {
            StemFiles.Track track = stems.tracks.get(t);
            String name = track.label;
            JPanel r = row();
            names[t] = text(name, Look.size(Look.strong, compact ? 14 : 15), Look.TEXT);
            names[t].setToolTipText(name + " (" + Stems.NAMES[track.part] + " stem)");
            fix(names[t], compact ? 118 : 130);
            r.add(names[t]);
            solos[t] = key("S", name + " solo, for listening only", compact, () -> { stemSolo[t] = !stemSolo[t]; stemsChanged(); });
            mutes[t] = key("M", "Mute " + name + ", also in the master", compact, () -> { stemManual = true; stemMute[t] = !stemMute[t]; stemsChanged(); });
            downs[t] = key("\u2212", name + " down 0.5 dB", compact, () -> stemNudge(t, -0.5));
            ups[t] = key("+", name + " up 0.5 dB", compact, () -> stemNudge(t, 0.5));
            values[t] = text("+0.0 dB", Look.size(Look.mono, compact ? 13 : 14), Look.AMBER);
            values[t].setHorizontalAlignment(SwingConstants.CENTER);
            fix(values[t], compact ? 70 : 84);
            values[t].setToolTipText(name + " level. Click to set it back to 0 dB");
            values[t].setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            values[t].addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) { if (busy) return; stemManual = true; stemDb[t] = 0; stemsChanged(); }
            });
            r.add(solos[t]); r.add(Box.createHorizontalStrut(4)); r.add(mutes[t]); r.add(Box.createHorizontalStrut(compact ? 10 : 18));
            r.add(downs[t]); r.add(values[t]); r.add(ups[t]); r.add(Box.createHorizontalStrut(compact ? 10 : 18));
            int part = track.part;
            String treated = part == Stems.DRUMS || part == Stems.BASS ? "left as it is"
                : part == Stems.VOCALS ? "cleaned on its own, plus the rides" : "cleaned on its own";
            JLabel about = compact ? text(Stems.NAMES[part], Look.size(Look.body, 12), Look.DIM)
                : text(Stems.NAMES[part] + " stem: " + treated, Look.size(Look.body, 12), Look.DIM);
            about.setToolTipText(Stems.NAMES[part] + " stem: " + treated);
            r.add(about);
            r.add(Box.createHorizontalGlue());
            return r;
        }

        private void fix(JComponent c, int w) {
            Dimension d = new Dimension(w, c.getPreferredSize().height);
            c.setPreferredSize(d); c.setMinimumSize(d); c.setMaximumSize(d);
        }

        private JButton key(String label, String tip, boolean compact, Runnable action) {
            JButton b = segment(label);
            b.setFont(Look.size(Look.strong, compact ? 13 : 14));
            b.setToolTipText(tip);
            Dimension d = compact ? new Dimension(32, 24) : new Dimension(36, 28);
            b.setPreferredSize(d); b.setMinimumSize(d); b.setMaximumSize(d);
            b.addActionListener(e -> { if (!busy) action.run(); });
            return b;
        }

        void draw() {
            if (stems == null) { summary.setText(" "); auto.setEnabled(false); return; }
            boolean solo = false;
            for (boolean x : stemSolo) solo |= x;
            for (int t = 0; t < names.length && t < stemDb.length; t++) {
                if (names[t] == null) continue;
                boolean heard = solo ? stemSolo[t] : !stemMute[t];
                names[t].setForeground(heard ? Look.TEXT : Look.DIM);
                values[t].setText(String.format(Locale.US, "%+.1f dB", stemDb[t]));
                values[t].setForeground(stemMute[t] ? Look.DIM : Look.AMBER);
                solos[t].putClientProperty("on", stemSolo[t]); mutes[t].putClientProperty("on", stemMute[t]);
                for (JButton b : new JButton[]{solos[t], mutes[t], downs[t], ups[t]}) { b.setEnabled(!busy); b.repaint(); }
            }
            String words = stemManual ? "Your balance. Small moves work best: each stem holds traces of the others."
                : stemAuto == null ? "Working out the balance…" : "Auto balance: " + stemAuto.words() + ".";
            summary.setText(words);
            summary.setToolTipText(words);
            auto.setEnabled(stemManual && !busy);
        }
    }

    // ---------- Results ----------
    private static final int TOP = 46, ROW = 46, ROW5 = 40;

    /** Four rows (Original and three takes) at 46; with a Yours take, five at 40. */
    private int rowHeight() { return rows.size() > 4 ? ROW5 : ROW; }

    /** The results table. Past five rows (the round before, imported masters) it scrolls. */
    final class ResultsView extends JComponent implements Scrollable {
        private MasterJob.Outcome o;
        private static final int LINK = 210;
        ResultsView() {
            addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    if (e.getY() >= TOP) selectRow((e.getY() - TOP) / rowHeight());
                    else if (o != null && last == o && !blind.hidden() && e.getX() > getWidth() - LINK) importMaster();
                }
            });
            addMouseMotionListener(new MouseAdapter() {
                @Override public void mouseMoved(MouseEvent e) {
                    boolean link = e.getY() < TOP && o != null && last == o && !blind.hidden() && e.getX() > getWidth() - LINK;
                    setCursor(Cursor.getPredefinedCursor(link ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
                }
            });
            setToolTipText("");
        }
        private int fixed() { return TOP + 5 * ROW5 + 40; }
        @Override public Dimension getPreferredSize() { return new Dimension(800, Math.max(fixed(), TOP + rows.size() * rowHeight() + 40)); }
        @Override public Dimension getPreferredScrollableViewportSize() { return new Dimension(800, fixed()); }
        @Override public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return rowHeight() / 2; }
        @Override public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return r.height - rowHeight(); }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return getParent() != null && getParent().getHeight() > getPreferredSize().height; }
        @Override public String getToolTipText(MouseEvent e) {
            if (e.getY() < TOP && o != null && last == o && e.getX() > getWidth() - LINK)
                return "Bring in a master made elsewhere (Stem Lab on the phone, another tool) to compare, level-matched";
            int r = e.getY() < TOP ? -1 : (e.getY() - TOP) / rowHeight();
            if (o == null || r < 0 || r >= rows.size() || blind.hidden()) return null;
            Row t = rows.get(blind.version(r));
            return "<html><b>" + esc(t.name) + "</b><br>" + esc(t.about) + (t.original ? "" : "<br>" + esc(t.verdict) + "<br>" + esc(t.streaming)) + "</html>";
        }
        void show(MasterJob.Outcome o) { this.o = o; if (o == null) rows = List.of(); revalidate(); repaint(); }
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
            int w = getWidth(), h = getHeight();
            g.setColor(Look.PANEL); g.fillRoundRect(0, 0, w - 1, h - 1, 14, 14);
            g.setColor(Look.HAIR); g.drawRoundRect(0, 0, w - 1, h - 1, 14, 14);
            int[] x = {18, 192, 300, 408, 506};
            g.setFont(Look.size(Look.label, 13)); g.setColor(Look.AMBER_DIM);
            String[] heads = {"VERSION", "LOUDNESS", "TRUE PEAK", "PLAYS AT", "CHECK AND STREAMING"};
            for (int i = 0; i < heads.length; i++) g.drawString(heads[i], x[i], 28);
            if (o != null && last == o && !blind.hidden()) {
                String link = "+  COMPARE A MASTER";
                g.setColor(Look.AMBER);
                g.drawString(link, w - 18 - g.getFontMetrics().stringWidth(link), 28);
            }
            g.setColor(Look.HAIR); g.drawLine(12, 40, w - 12, 40);
            if (o == null || rows.isEmpty()) {
                g.setFont(Look.size(Look.body, 15)); g.setColor(Look.DIM);
                g.drawString("Your Clean, Warm and Punch takes appear here, ready to play against the original.", 18, 78);
                g.dispose(); return;
            }
            double[] trim = trims();
            boolean matched = match.isSelected(), hide = blind.hidden(), reveal = blind.state == Blind.State.RESULTS;
            final int ROW = rowHeight();
            for (int r = 0; r < rows.size(); r++) {
                int v = blind.version(r);
                Row t = rows.get(v);
                int top = TOP + r * ROW, y = top + 21;
                boolean sel = v == playing;
                if (sel) {
                    g.setColor(Look.PANEL_TOP); g.fillRoundRect(8, top, w - 16, ROW - 4, 10, 10);
                    g.setColor(Look.AMBER); g.fillRoundRect(8, top + 7, 3, ROW - 18, 3, 3);
                }
                g.setFont(Look.size(Look.mono, 13)); g.setColor(sel ? Look.AMBER : Look.DIM); g.drawString(String.valueOf(r + 1), x[0], y);
                g.setFont(Look.size(Look.strong, 17)); g.setColor(sel ? Look.AMBER : Look.TEXT);
                if (hide) g.drawString("Version " + (r + 1), x[0] + 20, y);
                else {
                    // An earlier round's take shows its name and a small EARLIER mark; its settings are in the line below.
                    int cut = t.name.indexOf(" · ");
                    String name = cut < 0 ? t.name : t.name.substring(0, cut);
                    int room = x[1] - x[0] - 30;
                    clip(g, name, x[0] + 20, y, room, g.getColor());
                    if (cut >= 0) {
                        int nw = g.getFontMetrics().stringWidth(name);
                        g.setFont(Look.size(Look.label, 11)); g.setColor(Look.AMBER_DIM);
                        if (nw + 8 + g.getFontMetrics().stringWidth("EARLIER") <= room) g.drawString("EARLIER", x[0] + 20 + nw + 8, y - 1);
                    }
                }
                String about = hide ? "Name hidden until you pick" : reveal ? (r == blind.picked ? "Version " + (r + 1) + ", your pick" : "Version " + (r + 1)) : t.about;
                g.setFont(Look.size(Look.body, 12)); g.setColor(reveal && r == blind.picked ? Look.AMBER : Look.DIM);
                clip(g, about, x[0] + 20, y + 17, x[4] - x[0] - 36, g.getColor());
                if (hide) continue;
                g.setFont(Look.size(Look.mono, 14)); g.setColor(Look.MID);
                g.drawString(String.format(Locale.US, "%.1f LUFS", t.lufs), x[1], y);
                boolean made = !t.original && Double.isNaN(t.ceiling); // imported: measured, not checked against our ceiling
                g.setColor(t.original || made || t.peak <= t.ceiling + 0.05 ? Look.MID : Look.HOT);
                g.drawString(String.format(Locale.US, "%.1f dBTP", t.peak), x[2], y);
                g.setColor(matched ? Look.BLUE : Look.DIM);
                g.drawString(!matched ? "as is" : Math.abs(trim[v]) < 0.05 ? "0.0 dB" : String.format(Locale.US, "%+.1f dB", trim[v]), x[3], y);
                g.setFont(Look.size(Look.body, 14));
                clip(g, t.verdict, x[4], y, w - x[4] - 16, t.original ? Look.DIM : made ? Look.MID : t.verdict.toLowerCase(Locale.ROOT).contains("passed") ? Look.BLUE : Look.HOT);
                g.setFont(Look.size(Look.body, 12));
                clip(g, t.streaming, x[4], y + 17, w - x[4] - 16, made ? Look.DIM : t.streaming.startsWith("Safe") ? Look.BLUE : Look.HOT);
            }
            if (o.settings != null) {
                g.setColor(Look.HAIR); g.drawLine(12, h - 34, w - 12, h - 34);
                g.setFont(Look.size(Look.body, 13)); g.setColor(Look.MID);
                g.drawString("Mastered with: " + o.settings, x[0], h - 14);
            }
            g.dispose();
        }
    }

    private static String esc(String s) { return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }

    private static void clip(Graphics2D g, String s, int x, int y, int width, Color c) {
        if (s == null) s = "";
        FontMetrics fm = g.getFontMetrics();
        String out = s;
        while (out.length() > 3 && fm.stringWidth(out) > width) out = out.substring(0, out.length() - 2);
        if (!out.equals(s)) out = out.substring(0, Math.max(0, out.length() - 1)) + "…";
        g.setColor(c); g.drawString(out, x, y);
    }

    /** The song's waveform with the play position; click or drag to move it. Time on the right. */
    final class Wave extends JComponent {
        float[] peaks;
        double demoAt = -1, demoLength;
        Wave() {
            setPreferredSize(new Dimension(400, 40)); setMaximumSize(new Dimension(Integer.MAX_VALUE, 40)); setMinimumSize(new Dimension(160, 40));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            MouseAdapter seek = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) { go(e); }
                @Override public void mouseDragged(MouseEvent e) { go(e); }
                private void go(MouseEvent e) { if (!canPlay()) return; player.feed().seek((e.getX() - 4) / (double) Math.max(1, area() - 8)); repaint(); }
            };
            addMouseListener(seek); addMouseMotionListener(seek);
            setToolTipText("Click or drag to move. Left and right arrows: 5 seconds.");
        }
        private int area() { return getWidth() - 112; }
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
            int w = area(), h = getHeight(), mid = h / 2;
            g.setColor(Look.WELL); g.fillRoundRect(0, 0, w, h - 1, 10, 10);
            g.setColor(Look.HAIR); g.drawRoundRect(0, 0, w, h - 1, 10, 10);
            double at = demoAt >= 0 ? demoAt : canPlay() ? player.feed().fraction() : 0;
            double len = demoAt >= 0 ? demoLength : canPlay() ? player.feed().length() : 0;
            int head = 4 + (int) Math.round(at * (w - 8));
            if (peaks == null) {
                g.setColor(Look.HAIR); g.drawLine(6, mid, w - 6, mid);
            } else {
                for (int px = 4; px < w - 4; px++) {
                    int a = (int) ((long) (px - 4) * peaks.length / (w - 8)), b = Math.max(a + 1, (int) ((long) (px - 3) * peaks.length / (w - 8)));
                    float p = 0;
                    for (int i = a; i < b && i < peaks.length; i++) p = Math.max(p, peaks[i]);
                    int half = Math.max(1, Math.round(Math.min(1f, p) * (mid - 4)));
                    g.setColor(px <= head ? Look.GOLD_LO : isEnabled() ? Look.LINE : Look.HAIR);
                    g.drawLine(px, mid - half, px, mid + half);
                }
            }
            if (isEnabled() || demoAt >= 0) { g.setColor(Look.TEXT); g.fillRect(head - 1, 3, 2, h - 6); }
            g.setFont(Look.size(Look.mono, 13)); g.setColor(len > 0 ? Look.MID : Look.DIM);
            String time = len > 0 ? clock(at * len) + " / " + clock(len) : "-:-- / -:--";
            FontMetrics fm = g.getFontMetrics();
            g.drawString(time, getWidth() - fm.stringWidth(time), (h + fm.getAscent() - fm.getDescent()) / 2);
            g.dispose();
        }
    }

    static String clock(double seconds) {
        int s = (int) Math.max(0, seconds);
        return s / 60 + ":" + String.format(Locale.US, "%02d", s % 60);
    }

    /** A reading on the console: title, value, unit. */
    private static final class Tile extends JComponent {
        final String title, unit;
        String value = "--";
        boolean warn;
        Tile(String title, String unit) { this.title = title; this.unit = unit; setPreferredSize(new Dimension(100, 60)); }
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
            g.setColor(Look.WELL); g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
            g.setColor(Look.HAIR); g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
            g.setFont(Look.size(Look.label, 11)); g.setColor(Look.AMBER_DIM); g.drawString(title, 10, 18);
            g.setFont(Look.size(Look.mono, 20)); g.setColor(value.equals("--") ? Look.DIM : warn ? Look.HOT : Look.TEXT); g.drawString(value, 10, 46);
            int vx = 10 + g.getFontMetrics().stringWidth(value) + 5;
            g.setFont(Look.size(Look.label, 11)); g.setColor(Look.DIM); g.drawString(unit, vx, 46);
            g.dispose();
        }
    }

    /** One half of the console's LIGHTS / TONE switch; gold when on. */
    private static JButton segment(String s) {
        JButton b = new JButton(s) {
            @Override protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create(); g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                boolean on = Boolean.TRUE.equals(getClientProperty("on"));
                int w = getWidth(), h = getHeight();
                if (on) g.setPaint(new GradientPaint(0, 0, Look.GOLD_HI, 0, h, Look.GOLD_LO)); else g.setColor(getModel().isRollover() ? Look.PANEL_TOP : Look.RAISED);
                g.fillRoundRect(0, 0, w - 1, h - 1, 10, 10);
                g.setColor(on ? Look.GOLD_HI : Look.LINE); g.drawRoundRect(0, 0, w - 1, h - 1, 10, 10);
                g.setFont(getFont()); FontMetrics fm = g.getFontMetrics();
                g.setColor(on ? Look.WELL : isEnabled() ? Look.TEXT : Look.DIM);
                g.drawString(getText(), (w - fm.stringWidth(getText())) / 2, (h + fm.getAscent() - fm.getDescent()) / 2);
                g.dispose();
            }
        };
        b.setFont(Look.size(Look.label, 14)); b.setContentAreaFilled(false); b.setBorderPainted(false); b.setFocusPainted(false); b.setFocusable(false);
        b.setRolloverEnabled(true); b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setPreferredSize(new Dimension(120, 32));
        return b;
    }

    // ---------- Small parts ----------
    private static JLabel text(String s, Font f, Color c) { JLabel l = new JLabel(s); l.setFont(f); l.setForeground(c); l.setAlignmentX(0); return l; }
    private static JPanel column() { JPanel p = new JPanel(); p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS)); p.setOpaque(false); return p; }
    private static JPanel row() {
        JPanel p = new JPanel() { @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); } };
        p.setLayout(new BoxLayout(p, BoxLayout.X_AXIS)); p.setOpaque(false); p.setAlignmentX(0); return p;
    }
    private static Component gap(int h) { return Box.createVerticalStrut(h); }
    private static JPanel panel() {
        JPanel p = new JPanel(new BorderLayout()) {
            @Override protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create(); g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setPaint(new GradientPaint(0, 0, Look.PANEL_TOP, 0, getHeight(), Look.PANEL)); g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 14, 14);
                g.setColor(Look.HAIR); g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 14, 14); g.dispose();
            }
            @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
        };
        p.setOpaque(false); p.setBorder(new EmptyBorder(14, 16, 14, 16)); p.setAlignmentX(0);
        return p;
    }
    private static JCheckBox check(String s, String tip) {
        JCheckBox b = new JCheckBox(s); b.setOpaque(false); b.setForeground(Look.TEXT); b.setFont(Look.size(Look.body, 14)); b.setToolTipText(tip);
        b.setFocusPainted(false); b.setIcon(new Tick(false, false)); b.setSelectedIcon(new Tick(true, false));
        b.setDisabledIcon(new Tick(false, true)); b.setDisabledSelectedIcon(new Tick(true, true)); b.setIconTextGap(8);
        return b;
    }
    private static JButton button(String s, boolean primary) {
        JButton b = new JButton(s) {
            @Override protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create(); g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                boolean on = isEnabled(), down = getModel().isPressed(), over = getModel().isRollover();
                int w = getWidth(), h = getHeight();
                if (primary) g.setPaint(on ? new GradientPaint(0, 0, down ? Look.GOLD_LO : Look.GOLD_HI, 0, h, Look.GOLD_LO) : new GradientPaint(0, 0, Look.AMBER_DIM, 0, h, Look.AMBER_DIM.darker()));
                else g.setColor(on ? (over ? Look.PANEL_TOP : Look.RAISED) : Look.PANEL);
                g.fillRoundRect(0, 0, w - 1, h - 1, 12, 12);
                g.setColor(primary ? Look.GOLD_HI : Look.LINE); g.drawRoundRect(0, 0, w - 1, h - 1, 12, 12);
                g.setFont(getFont()); FontMetrics fm = g.getFontMetrics();
                g.setColor(primary ? (on ? Look.WELL : Look.PANEL) : (on ? Look.TEXT : Look.DIM));
                g.drawString(getText(), (w - fm.stringWidth(getText())) / 2, (h + fm.getAscent() - fm.getDescent()) / 2);
                g.dispose();
            }
        };
        b.setFont(Look.size(primary ? Look.title : Look.strong, primary ? 19 : 14)); b.setContentAreaFilled(false); b.setBorderPainted(false); b.setFocusPainted(false);
        b.setRolloverEnabled(true); b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)); b.setPreferredSize(new Dimension(primary ? 170 : 132, primary ? 42 : 34));
        b.setMaximumSize(b.getPreferredSize());
        return b;
    }
    private static void styleCombo(JComboBox<?> c) {
        c.setFont(Look.size(Look.body, 14)); c.setBackground(Look.WELL); c.setForeground(Look.TEXT); c.setBorder(new LineBorder(Look.LINE, 1, true));
        c.setUI(new BasicComboBoxUI() {
            @Override protected JButton createArrowButton() {
                JButton b = new JButton() {
                    @Override protected void paintComponent(Graphics g0) {
                        Graphics2D g = (Graphics2D) g0.create(); g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                        g.setColor(Look.WELL); g.fillRect(0, 0, getWidth(), getHeight());
                        int cx = getWidth() / 2, cy = getHeight() / 2;
                        g.setColor(comboBox.isEnabled() ? Look.AMBER : Look.AMBER_DIM);
                        g.fillPolygon(new int[]{cx - 5, cx + 5, cx}, new int[]{cy - 2, cy - 2, cy + 4}, 3);
                        g.dispose();
                    }
                };
                b.setPreferredSize(new Dimension(28, 28)); b.setBorder(new EmptyBorder(0, 0, 0, 0)); b.setContentAreaFilled(false); b.setFocusPainted(false); return b;
            }
            @Override public void paintCurrentValueBackground(Graphics g, Rectangle r, boolean f) { g.setColor(Look.WELL); g.fillRect(r.x, r.y, r.width, r.height); }
        });
        if (!(c.getRenderer() instanceof DefaultListCellRenderer) || c.getItemCount() == 0 || !(c.getItemAt(0) instanceof Target.Style))
            c.setRenderer(new DefaultListCellRenderer() {
                @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean f) {
                    super.getListCellRendererComponent(l, v, i, sel, f);
                    setBackground(sel ? Look.PANEL_TOP : Look.PANEL); setForeground(sel ? Look.AMBER : Look.TEXT); setFont(Look.size(Look.body, 14)); setBorder(new EmptyBorder(4, 8, 4, 8));
                    return this;
                }
            });
    }
    /** A tick box; dimmed (not hidden) while a round is running and the settings are locked. */
    private record Tick(boolean on, boolean locked) implements Icon {
        public int getIconWidth() { return 18; } public int getIconHeight() { return 18; }
        public void paintIcon(Component c, Graphics g0, int x, int y) {
            Graphics2D g = (Graphics2D) g0.create(); g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Look.WELL); g.fillRoundRect(x, y, 17, 17, 5, 5);
            g.setColor(on ? (locked ? Look.AMBER_DIM : Look.AMBER) : Look.LINE); g.drawRoundRect(x, y, 17, 17, 5, 5);
            if (on) { g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)); g.drawPolyline(new int[]{x + 4, x + 7, x + 13}, new int[]{y + 9, y + 12, y + 5}, 3); }
            g.dispose();
        }
    }

    // ---------- Screenshot (checks) ----------
    /** Paints the window into a PNG without showing it, optionally with sample results. */
    static void screenshot(File png, int w, int h, MasterJob.Outcome demo, String songLabel, boolean busy, boolean blindOn, boolean toneView) throws Exception {
        screenshot(png, w, h, demo, songLabel, busy, blindOn, toneView, false);
    }

    static void screenshot(File png, int w, int h, MasterJob.Outcome demo, String songLabel, boolean busy, boolean blindOn, boolean toneView, boolean stemsOn) throws Exception {
        screenshot(png, w, h, demo, songLabel, busy, blindOn, toneView, stemsOn, false);
    }

    static void screenshot(File png, int w, int h, MasterJob.Outcome demo, String songLabel, boolean busy, boolean blindOn, boolean toneView, boolean stemsOn, boolean suno) throws Exception {
        screenshot(png, w, h, demo, songLabel, busy, blindOn, toneView, stemsOn, suno, false);
    }

    static void screenshot(File png, int w, int h, MasterJob.Outcome demo, String songLabel, boolean busy, boolean blindOn, boolean toneView, boolean stemsOn, boolean suno, boolean rounds) throws Exception {
        MainWindow m = new MainWindow();
        m.showTone(false);
        if (stemsOn) {
            File dir = java.nio.file.Files.createTempDirectory("masterroom-demo-stems").toFile();
            File[] four = Demo.writeStems(dir, "Dusty Road", 8);
            List<File> files = List.of(four);
            if (suno) {
                // Suno's layout, built from the four sample stems (a picture of the screen only).
                File sd = new File(dir, "Dusty Road Stems");
                sd.mkdirs();
                String[] names = {"0 Lead Vocals", "1 Backing Vocals", "2 Drums", "3 Bass", "4 Guitar", "5 Keyboard", "6 Percussion", "7 Strings", "8 Synth", "9 Brass", "10 Woodwinds"};
                int[] from = {0, 0, 1, 2, 3, 3, 1, 3, 3, 3, 3};
                List<File> sf = new java.util.ArrayList<>();
                for (int i = 0; i < names.length; i++) {
                    File f = new File(sd, names[i] + ".wav");
                    java.nio.file.Files.copy(four[from[i]].toPath(), f.toPath());
                    sf.add(f);
                }
                files = sf;
            }
            StemFiles.Loaded l = StemFiles.load(files);
            Audio a = Stems.mix(l.set, null, null);
            m.useStems(l, Listen.to(a), Overview.peaks(a, 1600), Stems.read(l.set));
            m.stemSolo[0] = false; m.stemNudge(2, 0.5);
            m.wave.demoAt = 0.41; m.wave.demoLength = 8;
            m.meter.demoLight = -15.2f; m.meter.demoLufs = -15.6;
            float[] bars = Spectrum.resting(m.stemListen.midDb);
            for (int i = 0; i < bars.length; i++) bars[i] = (float) Math.min(1, bars[i] * (1.1 + 0.3 * Math.sin(i * 1.3)));
            m.lights.demo = bars;
            m.play.setText("Pause");
            m.refreshConsole();
        }
        if (songLabel != null) { m.songName.setText(songLabel); m.songFacts.setText("As it came in: -18.2 LUFS, 6.4 LU range, -3.1 dBTP true peak"); m.master.setEnabled(true); }
        if (demo != null) {
            if (rounds) {
                // A second round of the same song: the first round's takes kept beside it, and an imported master.
                MasterJob.Outcome first = Demo.outcome();
                first.shortSettings = "Classic Rock -12";
                m.extras = keptFrom(first, List.of(Demo.imported()), demo);
                m.last = demo;
            }
            m.shown = together(demo, m.extras);
            m.results.show(demo); m.showRows(rowsFor(demo, m.shown), demo); m.progress.setValue(100); m.status.setText("Done in 1.4 minutes. Takes are in " + demo.dir); m.folder.setEnabled(true); m.sheet.setEnabled(true);
            float[] p = new float[1600];
            Random r = new Random(3);
            for (int i = 0; i < p.length; i++) { double env = i < 80 ? i / 80.0 : i > 1520 ? (1600 - i) / 80.0 : (i / 260) % 2 == 0 ? 0.55 : 0.85; p[i] = (float) (env * (0.75 + 0.25 * r.nextDouble())); }
            m.wave.peaks = p; m.wave.demoAt = 0.32; m.wave.demoLength = 214;
            m.listening = true; m.play.setEnabled(true); m.blindTest.setEnabled(true); m.match.setEnabled(true); m.play.setText("Pause");
            m.playing = 2;
            float[] bars = Spectrum.resting(demo.takes.get(1).mid);
            for (int i = 0; i < bars.length; i++) bars[i] = (float) Math.min(1, bars[i] * (1.15 + 0.35 * Math.sin(i * 1.7)));
            m.lights.demo = bars;
            m.meter.demoLight = -13.4f; m.meter.demoLufs = -14.2;
            if (blindOn) {
                m.playing = m.blind.start(1, true);
                m.match.setEnabled(false); m.blindTest.setText("Pick this one");
                m.status.setText("Blind test: names hidden, order shuffled, levels matched. Listen with 1 to 4, then Pick this one.");
            }
            m.refreshConsole();
            if (toneView) m.showTone(true);
        }
        if (busy) { m.song = new File("x.wav"); m.commercial.setSelected(true); m.cleanUp.setSelected(true); m.mp3.setSelected(false); m.setBusy(true); m.progress.setValue(78); m.status.setText("Punch: checking for distortion"); }
        m.root.setSize(w, h);
        layout(m.root);
        if (rounds && m.results.getParent() instanceof JViewport vp) {
            // Scrolled down to the earlier round and the imported master.
            m.playing = m.rows.size() - 1;
            vp.setViewPosition(new Point(0, Math.max(0, m.results.getPreferredSize().height - vp.getHeight())));
            layout(m.root);
        }
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        m.root.printAll(g);
        g.dispose();
        ImageIO.write(img, "png", png);
        System.out.println("FONTS title=" + Look.title.getFontName() + " strong=" + Look.strong.getFontName() + " body=" + Look.body.getFontName()
            + " label=" + Look.label.getFontName() + " mono=" + Look.mono.getFontName() + " | Master button=" + m.master.getFont().getFontName() + " " + m.master.getFont().getSize2D());
    }
    private static void layout(Component c) {
        c.doLayout();
        if (c instanceof Container k) for (Component ch : k.getComponents()) layout(ch);
    }

    /** Opens where it was left: the last round, then the stems (if they were open). */
    void reopen() {
        restoreRound();
        restoreStems();
    }

    void showFrame() {
        JFrame f = new JFrame("Master Room");
        f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        f.setIconImages(AppIcon.images());
        f.setContentPane(root);
        f.setMinimumSize(new Dimension(1300, 760));
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        f.setSize(Math.min(1440, screen.width), Math.min(820, screen.height));
        f.addWindowListener(new WindowAdapter() { @Override public void windowClosing(WindowEvent e) { player.close(); } });
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(e -> key(e, f));
        new Timer(40, e -> {
            boolean was = heardOn;
            heardOn = player.now(heard);
            if (canPlay()) { wave.repaint(); if (play.getText().equals("Pause") != player.isPlaying()) refreshTransport(); }
            if (heardOn || was || meter.settling()) meter.repaint();
            if (heardOn || was || lights.settling) lights.repaint();
        }).start();
        f.setLocationRelativeTo(null);
        f.setVisible(true);
    }

    static void tooltips() {
        UIManager.put("ComboBox.disabledBackground", Look.WELL); UIManager.put("ComboBox.disabledForeground", Look.DIM);
        UIManager.put("CheckBox.disabledText", Look.DIM);
        UIManager.put("ToolTip.background", Look.PANEL_TOP); UIManager.put("ToolTip.foreground", Look.TEXT);
        UIManager.put("ToolTip.border", new LineBorder(Look.LINE)); UIManager.put("ToolTip.font", Look.size(Look.body, 13));
        UIManager.put("ComboBox.selectionBackground", Look.PANEL_TOP); UIManager.put("ComboBox.selectionForeground", Look.AMBER);
        UIManager.put("OptionPane.background", Look.BG); UIManager.put("Panel.background", Look.BG); UIManager.put("OptionPane.messageForeground", Look.TEXT);
    }

    static MouseAdapter none() { return new MouseAdapter() { @Override public void mouseClicked(MouseEvent e) { } }; }
}
