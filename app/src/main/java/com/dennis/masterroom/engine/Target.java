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

    private Target(String name, double[] midDb, double[] sideRelDb, boolean measured) {
        this.name = name; this.midDb = midDb; this.sideRelDb = sideRelDb; this.measured = measured;
    }

    /**
     * Offsets from the base curve: lows, low mids, presence, air, in dB. Country, Rock and Pop
     * are the original guesses. Folk, Jazz, R&B / Soul, Funk / Disco, Hip-Hop and Electronic
     * follow the genre differences in Pestana et al. at about half the size their spectral
     * slopes imply, because those figures also carry each genre's era. The rest come from
     * common mastering practice. All are starting points; reference records replace them.
     */
    public enum Style {
        COUNTRY("Country", -0.4, 0.0, 0.5, 0.0),
        CLASSIC_COUNTRY("Classic Country", -1.2, 0.0, 0.8, -1.0),
        COUNTRY_ROCK("Country Rock", 0.0, 0.2, 0.5, -0.1),
        FOLK("Folk / Americana", -1.0, 0.2, 0.3, -2.5),
        ACOUSTIC("Acoustic", -1.5, 0.3, 0.4, -1.0),
        BLUES("Blues", -0.3, 0.6, 0.2, -1.2),
        ROCK("Rock", 0.4, 0.3, 0.3, -0.3),
        HARD_ROCK("Hard Rock", 0.4, -0.4, 1.2, 0.3),
        METAL("Metal", 0.6, -1.0, 1.6, 0.6),
        INDIE("Indie", -0.3, 0.5, 0.0, -0.6),
        POP("Pop", 0.8, -0.4, 0.4, 0.8),
        EIGHTIES_POP("80s Pop", -0.8, -0.6, 0.8, 1.2),
        FUNK_DISCO("Funk / Disco", 1.2, -0.3, 0.3, 1.9),
        RNB_SOUL("R&B / Soul", 0.0, 0.0, 0.0, -0.9),
        HIP_HOP("Hip-Hop", 3.0, -0.8, 0.2, 1.8, 38),
        ELECTRONIC("Electronic", 2.5, -0.6, -0.2, 2.5, 38),
        REGGAE("Reggae", 2.2, 0.3, -0.6, -1.0, 40),
        JAZZ("Jazz", -1.5, 0.3, -0.2, -3.0);
        public final String label;
        final double low, lowMid, presence, air, subHz;
        Style(String label, double low, double lowMid, double presence, double air) {
            this(label, low, lowMid, presence, air, 55);
        }
        /** @param subHz where the curve starts falling away: lower for music built on sub bass */
        Style(String label, double low, double lowMid, double presence, double air, double subHz) {
            this.label = label; this.low = low; this.lowMid = lowMid; this.presence = presence; this.air = air; this.subHz = subHz;
        }
    }

    /**
     * Built-in starting curve. The base shape follows Pestana, Ma, Reiss et al. (AES 135,
     * 2013): about -5 dB per octave from 100 Hz to 4 kHz, steeper above, low-cut near 60 Hz.
     * The style offsets are starting points, not measurements of each genre.
     */
    public static Target builtIn(Style style) {
        double[] t = new double[31];
        for (int i = 0; i < 31; i++) {
            double f = Listen.BANDS[i], oct = log2(f / 100);
            double y = -5 * oct;
            if (f > 4000) y -= 2.5 * log2(f / 4000);
            if (f < style.subHz) y -= 12 * log2(style.subHz / f);
            y += Eq.lowShelf(f, 90, style.low) + Eq.bell(f, 280, 0.8, style.lowMid)
                    + Eq.bell(f, 3200, 0.8, style.presence) + Eq.highShelf(f, 9000, style.air);
            t[i] = y;
        }
        return new Target(style.label + " (starting curve)", t, null, false);
    }

    /** Average of songs the user trusts. Each is aligned by its own 150 Hz-3 kHz level. */
    public static Target fromReferences(String name, List<Listen> references) {
        double[] mid = new double[31], side = new double[31];
        for (Listen l : references) {
            double centre = 0; int n = 0;
            for (int i = 0; i < 31; i++) if (Listen.BANDS[i] >= 150 && Listen.BANDS[i] <= 3000) { centre += l.midDb[i]; n++; }
            centre /= n;
            for (int i = 0; i < 31; i++) { mid[i] += l.midDb[i] - centre; side[i] += l.sideDb[i] - l.midDb[i]; }
        }
        for (int i = 0; i < 31; i++) { mid[i] /= references.size(); side[i] /= references.size(); }
        return new Target(name, mid, side, true);
    }

    static double log2(double x) { return Math.log(x) / Math.log(2); }
}
