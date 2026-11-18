package com.dennis.masterroom.desktop;

import com.dennis.masterroom.engine.Audio;
import com.dennis.masterroom.engine.Listen;
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
 * switching without a gap, with the phone's blind test. Meters and stems come in the next builds.
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
    private final JButton folder = button("Open folder", false), sheet = button("Session sheet", false);
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
        root.add(middle, BorderLayout.CENTER);

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
        play.addActionListener(e -> togglePlay());
        blindTest.addActionListener(e -> blindStep());
        match.setSelected(true);
        match.addActionListener(e -> matchChanged());
        player.onStop(() -> SwingUtilities.invokeLater(this::refreshTransport));
        setListening(false);
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
        JLabel s = text("   Windows trial · build 2 · same engine as the phone", Look.size(Look.label, 15), Look.DIM);
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
        for (AbstractButton b : new AbstractButton[]{play, blindTest, match, open, master, stop, folder, sheet, commercial, cleanUp, mp3}) b.setFocusable(false);
        return r;
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
        master.setEnabled(!on && song != null); open.setEnabled(!on); stop.setEnabled(on);
        style.setEnabled(!on); level.setEnabled(!on); commercial.setEnabled(!on); cleanUp.setEnabled(!on); mp3.setEnabled(!on);
    }

    // ---------- Listening ----------
    record Row(String name, String about, double lufs, double peak, double ceiling, String verdict, String streaming, boolean original) {}

    static List<Row> rowsFor(MasterJob.Outcome o) {
        List<Row> r = new ArrayList<>();
        r.add(new Row("Original", "The song as it came in, before mastering", o.beforeLufs, o.beforePeak, Double.NaN, "As it came in", "", true));
        for (MasterJob.Take t : o.takes) r.add(new Row(t.label, t.about, t.lufs, t.truePeak, t.ceiling, t.verdict, t.streaming, false));
        return r;
    }

    /** Playback trim per version (dB) with level match on: the same rule the player uses. */
    private double[] trims() {
        double[] lufs = new double[rows.size()], peak = new double[rows.size()];
        boolean[] master = new boolean[rows.size()];
        for (int i = 0; i < rows.size(); i++) { lufs[i] = rows.get(i).lufs; peak[i] = rows.get(i).peak; master[i] = !rows.get(i).original; }
        return AbMixer.matchDb(lufs, peak, master);
    }

    private void showRows(List<Row> r) {
        rows = r; blind.reset(r.size()); playing = 0;
        match.setSelected(true); match.setEnabled(true); blindTest.setText("Blind test");
        results.repaint();
    }

    /** Opens the original and the 24-bit takes for playback (files are only read). */
    private void loadListening(MasterJob.Outcome o) {
        showRows(rowsFor(o));
        status.setToolTipText(o.settings);
        new SwingWorker<float[], Void>() {
            @Override protected float[] doInBackground() throws Exception {
                player.mix.clear();
                player.mix.add(new AbMixer.Version("Original", o.source, o.beforeLufs, o.beforePeak, false));
                for (MasterJob.Take t : o.takes) player.mix.add(new AbMixer.Version(t.label, t.files[0], t.lufs, t.truePeak, true));
                player.mix.setMatched(true);
                player.mix.select(0);
                return Overview.peaks(o.source, 1600);
            }
            @Override protected void done() {
                try {
                    wave.peaks = get();
                    setListening(true);
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
        rows = List.of();
        blind.reset(0);
        setListening(false);
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
                    File f = Blind.log(last.dir.getParentFile().getParentFile(), last.dir.getParentFile().getName(), last.settings, rows.get(v).name, names.toString());
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
            setPreferredSize(new Dimension(900, h)); setMaximumSize(new Dimension(Integer.MAX_VALUE, h)); setMinimumSize(new Dimension(300, h));
            addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) { if (e.getY() >= TOP) selectRow((e.getY() - TOP) / ROW); }
            });
        }
        void show(MasterJob.Outcome o) { this.o = o; if (o == null) rows = List.of(); repaint(); }
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
            int w = getWidth(), h = getHeight();
            g.setColor(Look.PANEL); g.fillRoundRect(0, 0, w - 1, h - 1, 14, 14);
            g.setColor(Look.HAIR); g.drawRoundRect(0, 0, w - 1, h - 1, 14, 14);
            int[] x = {18, 172, 280, 388, 486, 736};
            g.setFont(Look.size(Look.label, 13)); g.setColor(Look.AMBER_DIM);
            String[] heads = {"VERSION", "LOUDNESS", "TRUE PEAK", "PLAYS AT", "CHECK", "STREAMING"};
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
                clip(g, about, x[0] + 20, y + 17, x[1] - x[0] - 30 + (hide || reveal ? 200 : 300), g.getColor());
                if (hide) continue;
                g.setFont(Look.size(Look.mono, 14)); g.setColor(Look.MID);
                g.drawString(String.format(Locale.US, "%.1f LUFS", t.lufs), x[1], y);
                g.setColor(t.original || t.peak <= t.ceiling + 0.05 ? Look.MID : Look.HOT);
                g.drawString(String.format(Locale.US, "%.1f dBTP", t.peak), x[2], y);
                g.setColor(matched ? Look.BLUE : Look.DIM);
                g.drawString(!matched ? "as is" : Math.abs(trim[v]) < 0.05 ? "0.0 dB" : String.format(Locale.US, "%+.1f dB", trim[v]), x[3], y);
                g.setFont(Look.size(Look.body, 14));
                clip(g, t.verdict, x[4], y, x[5] - x[4] - 16, t.original ? Look.DIM : t.verdict.toLowerCase(Locale.ROOT).contains("passed") ? Look.BLUE : Look.HOT);
                clip(g, t.streaming, x[5], y, w - x[5] - 18, t.streaming.startsWith("Safe") ? Look.BLUE : Look.HOT);
            }
            if (o.settings != null) {
                g.setColor(Look.HAIR); g.drawLine(12, h - 34, w - 12, h - 34);
                g.setFont(Look.size(Look.body, 13)); g.setColor(Look.MID);
                g.drawString("Mastered with: " + o.settings, x[0], h - 14);
            }
            g.dispose();
        }
    }

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
    static void screenshot(File png, int w, int h, MasterJob.Outcome demo, String songLabel, boolean busy, boolean blindOn) throws Exception {
        MainWindow m = new MainWindow();
        if (songLabel != null) { m.songName.setText(songLabel); m.songFacts.setText("As it came in: -18.2 LUFS, 6.4 LU range, -3.1 dBTP true peak"); m.master.setEnabled(true); }
        if (demo != null) {
            m.results.show(demo); m.showRows(rowsFor(demo)); m.progress.setValue(100); m.status.setText("Done in 1.4 minutes. Takes are in " + demo.dir); m.folder.setEnabled(true); m.sheet.setEnabled(true);
            float[] p = new float[1600];
            Random r = new Random(3);
            for (int i = 0; i < p.length; i++) { double env = i < 80 ? i / 80.0 : i > 1520 ? (1600 - i) / 80.0 : (i / 260) % 2 == 0 ? 0.55 : 0.85; p[i] = (float) (env * (0.75 + 0.25 * r.nextDouble())); }
            m.wave.peaks = p; m.wave.demoAt = 0.32; m.wave.demoLength = 214;
            m.listening = true; m.play.setEnabled(true); m.blindTest.setEnabled(true); m.match.setEnabled(true); m.play.setText("Pause");
            m.playing = 2;
            if (blindOn) {
                m.playing = m.blind.start(1, true);
                m.match.setEnabled(false); m.blindTest.setText("Pick this one");
                m.status.setText("Blind test: names hidden, order shuffled, levels matched. Listen with 1 to 4, then Pick this one.");
            }
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
        f.setMinimumSize(new Dimension(1040, 750));
        f.setSize(1180, 780);
        f.addWindowListener(new WindowAdapter() { @Override public void windowClosing(WindowEvent e) { player.close(); } });
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(e -> key(e, f));
        new Timer(40, e -> { if (listening) { wave.repaint(); if (play.getText().equals("Pause") != player.isPlaying()) refreshTransport(); } }).start();
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
