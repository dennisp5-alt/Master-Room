package com.dennis.masterroom.desktop;

import com.dennis.masterroom.engine.Audio;
import com.dennis.masterroom.engine.Listen;
import com.dennis.masterroom.engine.Spectrum;
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
 * loudness arc, EQ lights and tone view), following what is being heard. Stems come next.
 */
final class MainWindow {
    private static final Preferences PREFS = Preferences.userNodeForPackage(MainWindow.class);
    private static final double[] LEVELS = {-16, -14, -13, -12, -11, -10, -9, -8};

    final JPanel root = new JPanel(new BorderLayout());
    private final JLabel songName = text("No song yet", Look.size(Look.strong, 18), Look.TEXT);
    private final JLabel songFacts = text("Drop a song here, or choose Open song. WAV, MP3, FLAC, M4A, AAC, OGG, AIFF.", Look.size(Look.body, 14), Look.DIM);
    private final JComboBox<Target.Style> style = new JComboBox<>(Target.Style.values());
    private final JComboBox<String> level = new JComboBox<>();
    private final JCheckBox commercial = check("Commercial reference", "Aim the tone at current commercial releases rather than the gentler built-in curve");
    private final JCheckBox cleanUp = check("Clean-up", "Turns down narrow resonances while they ring and eases stray top-end specks");
    private final JCheckBox mp3 = check("MP3 320", "Also writes a tagged 320 kbps MP3 of each take");
    private final JButton open = button("Open song", false), master = button("Master", true), stop = button("Stop", false);
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
    private boolean heardOn, showingTone;
    /** Waveform overview per version, in the player's order. */
    private float[][] peaksByVersion;
    /** The round on screen (the demo has no folder). */
    private MasterJob.Outcome round;
    private File song;
    private MasterJob.Outcome last;
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
        results.setAlignmentX(0);
        middle.add(results);
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
        engineLine.setText(Ffmpeg.available() ? "FFmpeg ready: FLAC, M4A, AAC and the AAC streaming check" : "FFmpeg not found: WAV and MP3 only");
        root.setTransferHandler(new TransferHandler() {
            @Override public boolean canImport(TransferSupport s) { return !busy && s.isDataFlavorSupported(DataFlavor.javaFileListFlavor); }
            @Override public boolean importData(TransferSupport s) {
                try {
                    @SuppressWarnings("unchecked") List<File> files = (List<File>) s.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
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
        JLabel s = text("   Windows trial · build 3 · same engine as the phone", Look.size(Look.label, 15), Look.DIM);
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
        JPanel side = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0)); side.setOpaque(false); side.add(open);
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
        for (AbstractButton b : new AbstractButton[]{play, blindTest, match, open, master, stop, folder, sheet, saveTo, commercial, cleanUp, mp3}) b.setFocusable(false);
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
        c.add(gap(12));
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
        viewNote.setText("<html><div style='width:300px'>" + (on
            ? "Each curve is lined up with the target from 150 Hz to 3 kHz. Above the dashed line means more than the target there."
            : "The spectrum of what you hear, live. When nothing is playing: the selected version's overall tone.") + "</div></html>");
        PREFS.putBoolean("toneView", on);
    }

    /** Everything the console shows for the selected version (numbers hidden during the blind test). */
    private void refreshConsole() {
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
        meter.repaint(); lights.repaint(); readLufs.repaint(); readPeak.repaint(); readRange.repaint(); wave.repaint();
    }

    // ---------- Actions ----------
    private void choose() {
        JFileChooser fc = new JFileChooser(PREFS.get("lastDir", System.getProperty("user.home")));
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Audio (" + String.join(", ", MasterJob.READS) + ")", MasterJob.READS));
        if (fc.showOpenDialog(root) == JFileChooser.APPROVE_OPTION) pick(fc.getSelectedFile());
    }

    void pick(File f) {
        if (!MasterJob.readable(f)) { status.setText("That file type is not supported: " + f.getName()); return; }
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
        if (song == null || busy) return;
        save();
        MasterJob.Settings s = new MasterJob.Settings();
        s.song = song; s.style = (Target.Style) style.getSelectedItem(); s.lufs = LEVELS[level.getSelectedIndex()];
        s.commercial = commercial.isSelected(); s.cleanUp = cleanUp.isSelected(); s.mp3 = mp3.isSelected();
        s.outRoot = outRoot;
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
                    results.show(last);
                    progress.setValue(100);
                    status.setText(String.format(Locale.US, "Done in %.1f minutes. Takes are in %s", last.seconds / 60, last.dir.getAbsolutePath()));
                    status.setToolTipText(last.settings);
                    folder.setEnabled(true); sheet.setEnabled(true);
                    loadListening(last);
                } catch (Exception e) {
                    String m = rootMessage(e);
                    status.setText(m.contains("Stopped") ? "Stopped. Nothing was finished from this round." : "It did not finish: " + m);
                    progress.setValue(0);
                }
            }
        }.execute();
    }

    private void setBusy(boolean on) {
        busy = on;
        master.setEnabled(!on && song != null); open.setEnabled(!on); stop.setEnabled(on); saveTo.setEnabled(!on);
        style.setEnabled(!on); level.setEnabled(!on); commercial.setEnabled(!on); cleanUp.setEnabled(!on); mp3.setEnabled(!on);
    }

    // ---------- Listening ----------
    record Row(String name, String about, double lufs, double peak, double ceiling, double lra, double[] mid, String verdict, String streaming, boolean original) {}

    static List<Row> rowsFor(MasterJob.Outcome o) {
        List<Row> r = new ArrayList<>();
        r.add(new Row("Original", "The song as it came in, before mastering", o.beforeLufs, o.beforePeak, Double.NaN, o.beforeLra, o.beforeMid, "As it came in", "", true));
        for (MasterJob.Take t : o.takes) r.add(new Row(t.label, t.about, t.lufs, t.truePeak, t.ceiling, t.lra, t.mid, t.verdict, t.streaming, false));
        return r;
    }

    /** Playback trim per version (dB) with level match on: the same rule the player uses. */
    private double[] trims() {
        double[] lufs = new double[rows.size()], peak = new double[rows.size()];
        boolean[] master = new boolean[rows.size()];
        for (int i = 0; i < rows.size(); i++) { lufs[i] = rows.get(i).lufs; peak[i] = rows.get(i).peak; master[i] = !rows.get(i).original; }
        return AbMixer.matchDb(lufs, peak, master);
    }

    private void showRows(List<Row> r, MasterJob.Outcome o) {
        rows = r; round = o; blind.reset(r.size()); playing = 0;
        match.setSelected(true); match.setEnabled(true); blindTest.setText("Blind test");
        results.repaint();
        refreshConsole();
    }

    /** Opens the original and the 24-bit takes for playback (files are only read). */
    private void loadListening(MasterJob.Outcome o) {
        showRows(rowsFor(o), o);
        status.setToolTipText(o.settings);
        new SwingWorker<float[][], Void>() {
            @Override protected float[][] doInBackground() throws Exception {
                player.mix.clear();
                player.mix.add(new AbMixer.Version("Original", o.source, o.beforeLufs, o.beforePeak, false));
                for (MasterJob.Take t : o.takes) player.mix.add(new AbMixer.Version(t.label, t.files[0], t.lufs, t.truePeak, true));
                player.mix.setMatched(true);
                player.mix.select(0);
                float[][] peaks = new float[1 + o.takes.size()][];
                peaks[0] = Overview.peaks(o.source, 1600);
                for (int i = 0; i < o.takes.size(); i++) peaks[i + 1] = Overview.peaks(o.takes.get(i).files[0], 1600);
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
        play.setEnabled(on); blindTest.setEnabled(on); match.setEnabled(on && blind.state == Blind.State.OFF);
        wave.setEnabled(on);
        refreshTransport();
    }

    private void refreshTransport() {
        play.setText(player.isPlaying() ? "Pause" : "Play");
        if (player.problem() != null) status.setText(player.problem());
        wave.repaint();
        refreshConsole();
    }

    private void togglePlay() {
        if (!listening) return;
        if (player.isPlaying()) player.pause(); else player.play();
        refreshTransport();
    }

    /** A row on screen (0 = top). During the blind test rows hold shuffled versions. */
    private void selectRow(int r) {
        if (!listening || r < 0 || r >= rows.size()) return;
        playing = blind.version(r);
        player.mix.select(playing);
        results.repaint();
        refreshConsole();
    }

    private void seekBy(double seconds) {
        if (!listening) return;
        double len = player.mix.length();
        if (len > 0) player.mix.seek((player.mix.seconds() + seconds) / len);
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
                    Blind.taste(outRootOfRound, last.style, rows.get(v).original ? "Source" : rows.get(v).name);
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
        if (e.getID() != KeyEvent.KEY_PRESSED || !listening || e.isControlDown() || e.isAltDown()) return false;
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
        if (k == KeyEvent.VK_HOME) { player.mix.seek(0); wave.repaint(); return true; }
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

    // ---------- Results ----------
    private static final int TOP = 46, ROW = 46;

    final class ResultsView extends JComponent {
        private MasterJob.Outcome o;
        ResultsView() {
            int h = TOP + 4 * ROW + 40;
            setPreferredSize(new Dimension(800, h)); setMaximumSize(new Dimension(Integer.MAX_VALUE, h)); setMinimumSize(new Dimension(300, h));
            addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) { if (e.getY() >= TOP) selectRow((e.getY() - TOP) / ROW); }
            });
            setToolTipText("");
        }
        @Override public String getToolTipText(MouseEvent e) {
            int r = e.getY() < TOP ? -1 : (e.getY() - TOP) / ROW;
            if (o == null || r < 0 || r >= rows.size() || r >= 4 || blind.hidden()) return null;
            Row t = rows.get(blind.version(r));
            return "<html><b>" + esc(t.name) + "</b><br>" + esc(t.about) + (t.original ? "" : "<br>" + esc(t.verdict) + "<br>" + esc(t.streaming)) + "</html>";
        }
        void show(MasterJob.Outcome o) { this.o = o; if (o == null) rows = List.of(); repaint(); }
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
            g.setColor(Look.HAIR); g.drawLine(12, 40, w - 12, 40);
            if (o == null || rows.isEmpty()) {
                g.setFont(Look.size(Look.body, 15)); g.setColor(Look.DIM);
                g.drawString("Your Clean, Warm and Punch takes appear here, ready to play against the original.", 18, 78);
                g.dispose(); return;
            }
            double[] trim = trims();
            boolean matched = match.isSelected(), hide = blind.hidden(), reveal = blind.state == Blind.State.RESULTS;
            for (int r = 0; r < rows.size() && r < 4; r++) {
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
                g.drawString(hide ? "Version " + (r + 1) : t.name, x[0] + 20, y);
                String about = hide ? "Name hidden until you pick" : reveal ? (r == blind.picked ? "Version " + (r + 1) + ", your pick" : "Version " + (r + 1)) : t.about;
                g.setFont(Look.size(Look.body, 12)); g.setColor(reveal && r == blind.picked ? Look.AMBER : Look.DIM);
                clip(g, about, x[0] + 20, y + 17, x[4] - x[0] - 36, g.getColor());
                if (hide) continue;
                g.setFont(Look.size(Look.mono, 14)); g.setColor(Look.MID);
                g.drawString(String.format(Locale.US, "%.1f LUFS", t.lufs), x[1], y);
                g.setColor(t.original || t.peak <= t.ceiling + 0.05 ? Look.MID : Look.HOT);
                g.drawString(String.format(Locale.US, "%.1f dBTP", t.peak), x[2], y);
                g.setColor(matched ? Look.BLUE : Look.DIM);
                g.drawString(!matched ? "as is" : Math.abs(trim[v]) < 0.05 ? "0.0 dB" : String.format(Locale.US, "%+.1f dB", trim[v]), x[3], y);
                g.setFont(Look.size(Look.body, 14));
                clip(g, t.verdict, x[4], y, w - x[4] - 16, t.original ? Look.DIM : t.verdict.toLowerCase(Locale.ROOT).contains("passed") ? Look.BLUE : Look.HOT);
                g.setFont(Look.size(Look.body, 12));
                clip(g, t.streaming, x[4], y + 17, w - x[4] - 16, t.streaming.startsWith("Safe") ? Look.BLUE : Look.HOT);
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
                private void go(MouseEvent e) { if (!listening) return; player.mix.seek((e.getX() - 4) / (double) Math.max(1, area() - 8)); repaint(); }
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
            double at = demoAt >= 0 ? demoAt : listening ? player.mix.fraction() : 0;
            double len = demoAt >= 0 ? demoLength : listening ? player.mix.length() : 0;
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
                g.setColor(on ? Look.WELL : Look.TEXT);
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
        MainWindow m = new MainWindow();
        m.showTone(false);
        if (songLabel != null) { m.songName.setText(songLabel); m.songFacts.setText("As it came in: -18.2 LUFS, 6.4 LU range, -3.1 dBTP true peak"); m.master.setEnabled(true); }
        if (demo != null) {
            m.results.show(demo); m.showRows(rowsFor(demo), demo); m.progress.setValue(100); m.status.setText("Done in 1.4 minutes. Takes are in " + demo.dir); m.folder.setEnabled(true); m.sheet.setEnabled(true);
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

    void showFrame() {
        JFrame f = new JFrame("Master Room");
        f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        f.setContentPane(root);
        f.setMinimumSize(new Dimension(1300, 760));
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        f.setSize(Math.min(1440, screen.width), Math.min(820, screen.height));
        f.addWindowListener(new WindowAdapter() { @Override public void windowClosing(WindowEvent e) { player.close(); } });
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(e -> key(e, f));
        new Timer(40, e -> {
            boolean was = heardOn;
            heardOn = player.now(heard);
            if (listening) { wave.repaint(); if (play.getText().equals("Pause") != player.isPlaying()) refreshTransport(); }
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
