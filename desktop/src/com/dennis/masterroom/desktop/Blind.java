package com.dennis.masterroom.desktop;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * The phone's blind test: shuffle the versions, hide their names, force level matching, pick one by ear,
 * then reveal. Rows on screen map to versions through {@link #order}.
 */
final class Blind {
    enum State { OFF, ON, RESULTS }

    State state = State.OFF;
    /** Row on screen to version index. In order (0, 1, 2, …) when the test is off. */
    final List<Integer> order = new ArrayList<>();
    /** The row picked, during RESULTS. */
    int picked = -1;
    boolean matchedBefore = true;
    private final Random random;

    Blind(Random random) { this.random = random; }

    void reset(int versions) {
        order.clear();
        for (int i = 0; i < versions; i++) order.add(i);
        state = State.OFF; picked = -1;
    }

    int version(int row) { return order.get(row); }
    int row(int version) { return order.indexOf(version); }
    boolean hidden() { return state == State.ON; }

    /**
     * Start: shuffle (never the plain order, which would give it away). The highlighted row stays where it is
     * and now plays whatever was shuffled into it, as on the phone. Returns that version.
     */
    int start(int playingRow, boolean matchedNow) {
        matchedBefore = matchedNow;
        if (order.size() > 1) {
            List<Integer> plain = new ArrayList<>(order);
            Collections.sort(plain);
            do Collections.shuffle(order, random); while (order.equals(plain));
        }
        state = State.ON; picked = -1;
        return order.get(playingRow);
    }

    /** Pick the row being played. Returns the version picked. */
    int pick(int row) {
        picked = row; state = State.RESULTS;
        return order.get(row);
    }

    /** Back to the plain order. Returns the row now holding the version that was playing. */
    int finish(int playingVersion) {
        Collections.sort(order);
        state = State.OFF; picked = -1;
        return row(playingVersion);
    }

    /** The phone's taste record ("style|blind|take"), so blind picks can shape a Yours take later, as on the phone. */
    static void taste(File outRoot, com.dennis.masterroom.engine.Target.Style style, String take) throws IOException {
        if (style == null) return;
        File f = new File(outRoot, "taste.txt");
        Files.writeString(f.toPath(), com.dennis.masterroom.engine.Taste.record(style.name(), "blind", take) + System.lineSeparator(),
            StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** One line per blind pick, kept beside the rounds so the picks can be read (and used) later. */
    static File log(File outRoot, String song, String settings, String pickedTake, String versions) throws IOException {
        File f = new File(outRoot, "blind test picks.txt");
        if (!outRoot.isDirectory() && !outRoot.mkdirs()) throw new IOException("Could not create " + outRoot);
        String line = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) + "\t" + song + "\tpicked " + pickedTake
            + "\tfrom " + versions + "\t" + (settings == null ? "" : settings) + System.lineSeparator();
        Files.writeString(f.toPath(), line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        return f;
    }
}
