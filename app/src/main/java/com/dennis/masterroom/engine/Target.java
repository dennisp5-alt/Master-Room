package com.dennis.masterroom.engine;

import java.util.List;

/**
 * What finished records of a style measure like. A studio engineer carries this in their
 * head from years of records and reference tracks; here it is either a built-in starting
 * curve or, better, measured from records Dennis loads. Only the numbers are kept.
 */
public final class Target {
    public final String name;
    public final double[] midDb;
    /** Side relative to mid per band, or null when no reference gives it. */
    public final double[] sideRelDb;
    public final boolean measured;
    /** Closes more of the gap, with a higher limit on each move. */
    public final boolean commercial;
    /** What the reference records measure, or NaN when they do not say. */
    public double punchDb = Double.NaN, widthDb = Double.NaN, lowEndDb = Double.NaN;

    Target(String name, double[] midDb, double[] sideRelDb, boolean measured) { this(name, midDb, sideRelDb, measured, false); }

    private Target(String name, double[] midDb, double[] sideRelDb, boolean measured, boolean commercial) {
        this.name = name; this.midDb = midDb; this.sideRelDb = sideRelDb; this.measured = measured; this.commercial = commercial;
    }

    /** What the session uses from a target's strength, in one place. */
    double strength() { return commercial ? 0.8 : measured ? 0.75 : 0.6; }
    double reach() { return commercial || measured ? 3.0 : 2.0; }

    /**
     * Offsets from the base curve: lows, low mids, presence, air, in dB. Country, Rock and Pop
     * are the original guesses. Folk, Jazz, R&B / Soul, Funk / Disco, Hip-Hop and Electronic
     * follow the genre differences in Pestana et al. at about half the size their spectral
     * slopes imply, because those figures also carry each genre's era. The rest come from
     * common mastering practice. All are starting points; reference records replace them.
     */
    public enum Style {
        // Country and roots
        COUNTRY("Country", -0.4, 0.0, 0.5, 0.0),
        CLASSIC_COUNTRY("Classic Country", -1.2, 0.0, 0.8, -1.0),
        COUNTRY_ROCK("Country Rock", 0.0, 0.2, 0.5, -0.1),
        BUSH_COUNTRY("Bush Country", -0.8, 0.1, 0.7, -0.6),
        ALT_COUNTRY("Alt Country", -0.4, 0.4, 0.2, -0.8),
        HONKY_TONK("Honky Tonk", -1.0, 0.2, 0.7, -0.8),
        BLUEGRASS("Bluegrass", -1.6, 0.0, 0.6, 0.0),
        FOLK("Folk / Americana", -1.0, 0.2, 0.3, -2.5),
        ACOUSTIC("Acoustic", -1.5, 0.3, 0.4, -1.0),
        SONGWRITER("Songwriter", -1.2, 0.2, 0.6, -0.6),
        BLUES("Blues", -0.3, 0.6, 0.2, -1.2),
        GOSPEL("Gospel", 0.2, 0.2, 0.4, -0.2),
        // Rock
        ROCK("Rock", 0.4, 0.3, 0.3, -0.3),
        CLASSIC_ROCK("Classic Rock", 0.0, 0.5, 0.2, -1.0),
        SOUTHERN_ROCK("Southern Rock", 0.1, 0.4, 0.4, -0.6),
        HARD_ROCK("Hard Rock", 0.4, -0.4, 1.2, 0.3),
        GLAM_METAL("Glam Metal", 0.2, -0.5, 1.0, 0.8),
        METAL("Metal", 0.6, -1.0, 1.6, 0.6),
        GRUNGE("Grunge", 0.6, 0.6, 0.5, -0.8),
        PUNK("Punk", 0.2, -0.2, 1.0, 0.0),
        INDIE("Indie", -0.3, 0.5, 0.0, -0.6),
        ALT_ROCK("Alt Rock", 0.3, 0.3, 0.4, -0.3),
        POP_ROCK("Pop Rock", 0.5, -0.2, 0.5, 0.4),
        SOFT_ROCK("Soft Rock", -0.2, 0.2, 0.2, 0.2),
        PROG_ROCK("Prog Rock", 0.1, 0.2, 0.1, 0.0),
        POWER_BALLAD("Power Ballad", 0.3, 0.0, 0.5, 0.6),
        // Pop and dance
        POP("Pop", 0.8, -0.4, 0.4, 0.8),
        EIGHTIES_POP("80s Pop", -0.8, -0.6, 0.8, 1.2),
        SYNTH_POP("Synth Pop", 0.6, -0.4, 0.3, 1.2),
        DANCE_POP("Dance Pop", 1.6, -0.5, 0.3, 1.4),
        EURODANCE("Eurodance", 1.8, -0.6, 0.4, 1.6, 45),
        FUNK_DISCO("Funk / Disco", 1.2, -0.3, 0.3, 1.9),
        ELECTRONIC("Electronic", 2.5, -0.6, -0.2, 2.5, 38),
        HOUSE("House", 2.2, -0.6, 0.0, 1.8, 40),
        TRANCE("Trance", 1.8, -0.7, 0.2, 2.0, 40),
        DRUM_BASS("Drum & Bass", 2.6, -0.8, 0.2, 1.6, 38),
        // Soul and urban
        RNB_SOUL("R&B / Soul", 0.0, 0.0, 0.0, -0.9),
        MOTOWN("Motown", -0.6, 0.3, 0.6, -1.0),
        HIP_HOP("Hip-Hop", 3.0, -0.8, 0.2, 1.8, 38),
        TRAP("Trap", 3.0, -1.0, 0.2, 2.0, 35),
        REGGAE("Reggae", 2.2, 0.3, -0.6, -1.0, 40),
        // Other
        JAZZ("Jazz", -1.5, 0.3, -0.2, -3.0),
        SWING("Swing", -1.4, 0.3, 0.0, -2.0),
        LATIN("Latin", 1.0, -0.2, 0.4, 0.6),
        LOFI("Lo-Fi", 0.4, 0.6, -1.0, -3.0),
        AMBIENT("Ambient", 0.2, 0.2, -0.6, 0.4),
        CINEMATIC("Cinematic", 0.6, -0.3, -0.4, 0.2),
        CLASSICAL("Classical", -0.6, 0.0, -0.4, -1.2);
        public final String label;
        final double low, lowMid, presence, air, subHz;
        Style(String label, double low, double lowMid, double presence, double air) {
            this(label, low, lowMid, presence, air, 55);
        }
        /** @param subHz where the curve starts falling away: lower for music built on sub bass */
        Style(String label, double low, double lowMid, double presence, double air, double subHz) {
            this.label = label; this.low = low; this.lowMid = lowMid; this.presence = presence; this.air = air; this.subHz = subHz;
        }
        /** The curves with published figures behind them, which Commercial takes further. */
        boolean surveyed() {
            return this == FOLK || this == JAZZ || this == RNB_SOUL || this == FUNK_DISCO || this == HIP_HOP || this == ELECTRONIC;
        }
    }

    /**
     * Built-in starting curve. The base shape follows Pestana, Ma, Reiss et al. (AES 135,
     * 2013): about -5 dB per octave from 100 Hz to 4 kHz, steeper above, low-cut near 60 Hz.
     * The style offsets are starting points, not measurements of each genre.
     */
    public static Target builtIn(Style style) { return builtIn(style, false); }

    /** @param commercial the surveyed curves at three-quarters of their published size, and firmer moves on any genre */
    public static Target builtIn(Style style, boolean commercial) {
        double size = commercial && style.surveyed() ? 1.5 : 1;
        double[] t = new double[31];
        for (int i = 0; i < 31; i++) {
            double f = Listen.BANDS[i], oct = log2(f / 100);
            double y = -5 * oct;
            if (f > 4000) y -= 2.5 * log2(f / 4000);
            if (f < style.subHz) y -= 12 * log2(style.subHz / f);
            y += size * (Eq.lowShelf(f, 90, style.low) + Eq.bell(f, 280, 0.8, style.lowMid)
                    + Eq.bell(f, 3200, 0.8, style.presence) + Eq.highShelf(f, 9000, style.air));
            t[i] = y;
        }
        return new Target(style.label + (commercial ? " (starting curve, Commercial)" : " (starting curve)"), t, null, false, commercial);
    }

    /** Average of songs the user trusts. Each is aligned by its own 150 Hz-3 kHz level. */
    public static Target fromReferences(String name, List<Listen> references) { return fromReferences(name, references, false); }

    public static Target fromReferences(String name, List<Listen> references, boolean commercial) {
        double[] mid = new double[31], side = new double[31];
        for (Listen l : references) {
            double centre = 0; int n = 0;
            for (int i = 0; i < 31; i++) if (Listen.BANDS[i] >= 150 && Listen.BANDS[i] <= 3000) { centre += l.midDb[i]; n++; }
            centre /= n;
            for (int i = 0; i < 31; i++) { mid[i] += l.midDb[i] - centre; side[i] += l.sideDb[i] - l.midDb[i]; }
        }
        for (int i = 0; i < 31; i++) { mid[i] /= references.size(); side[i] /= references.size(); }
        Target out = new Target(name, mid, side, true, commercial);
        double punch = 0, width = 0, low = 0;
        int counted = 0;
        for (Listen l : references) {
            width += l.widthDb();
            low += l.lowEndDb();
            if (!Double.isNaN(l.punchDb)) { punch += l.punchDb; counted++; }
        }
        out.widthDb = width / references.size();
        out.lowEndDb = low / references.size();
        if (counted > 0) out.punchDb = punch / counted;
        return out;
    }

    static double log2(double x) { return Math.log(x) / Math.log(2); }
}
