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

    public enum Style {
        COUNTRY("Country", -0.4, 0.0, 0.5, 0.0),
        ROCK("Rock", 0.4, 0.3, 0.3, -0.3),
        POP("Pop", 0.8, -0.4, 0.4, 0.8);
        final String label; final double low, lowMid, presence, air;
        Style(String label, double low, double lowMid, double presence, double air) {
            this.label = label; this.low = low; this.lowMid = lowMid; this.presence = presence; this.air = air;
        }
    }

    /**
     * Built-in starting curve. The base shape follows Pestana, Ma, Reiss et al. (AES 135,
     * 2013): about -5 dB per octave from 100 Hz to 4 kHz, steeper above, low-cut near 60 Hz.
     * The style offsets are a starting guess, not measurements.
     */
    public static Target builtIn(Style style) {
        double[] t = new double[31];
        for (int i = 0; i < 31; i++) {
            double f = Listen.BANDS[i], oct = log2(f / 100);
            double y = -5 * oct;
            if (f > 4000) y -= 2.5 * log2(f / 4000);
            if (f < 55) y -= 12 * log2(55 / f);
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
