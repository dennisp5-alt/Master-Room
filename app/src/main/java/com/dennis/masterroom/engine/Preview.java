package com.dennis.masterroom.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Listening previews for the comparison player only. They never touch a file.
 *
 * Phone and Small are rough general curves. The two Galaxy Buds curves are built from
 * published measurements (oratory1990, crinacle, Rtings, Kazi, HypetheSonics, Super Review,
 * collected in the AutoEq database): each rig's difference from the Harman in-ear target,
 * aligned over 300 Hz to 2 kHz, then the median, then a few broad filters that land within
 * 1 dB of it from 35 Hz to 10 kHz. Rigs disagree by several dB above 8 kHz, so the top is
 * the least certain part.
 */
public final class Preview {
    public enum Mode {
        FULL("Full"), PHONE("Phone"), SMALL("Small"), MONO("Mono"), BUDS2("Buds2"), BUDS2_PRO("Buds2 Pro");
        public final String label;
        Mode(String label) { this.label = label; }
        public boolean buds() { return this == BUDS2 || this == BUDS2_PRO; }
    }

    /** Which of the two measured buds a Bluetooth device name means, or null. */
    public static Mode budsNamed(String name) {
        if (name == null) return null;
        String n = name.toLowerCase(Locale.ROOT).replaceAll("[\\s_-]+", "");
        if (n.contains("buds2pro")) return Mode.BUDS2_PRO;
        return n.contains("buds2") ? Mode.BUDS2 : null;
    }

    private static final class Biquad {
        final double b0, b1, b2, a1, a2;
        double z1L, z2L, z1R, z2R;
        Biquad(double b0, double b1, double b2, double a0, double a1, double a2) {
            this.b0 = b0 / a0; this.b1 = b1 / a0; this.b2 = b2 / a0; this.a1 = a1 / a0; this.a2 = a2 / a0;
        }
        float left(float x) { double y = b0 * x + z1L; z1L = b1 * x - a1 * y + z2L; z2L = b2 * x - a2 * y; return (float) y; }
        float right(float x) { double y = b0 * x + z1R; z1R = b1 * x - a1 * y + z2R; z2R = b2 * x - a2 * y; return (float) y; }
    }

    private static Biquad high(double f, double q, int rate) {
        double w = 2 * Math.PI * f / rate, c = Math.cos(w), al = Math.sin(w) / (2 * q);
        return new Biquad((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + al, -2 * c, 1 - al);
    }

    private static Biquad low(double f, double q, int rate) {
        double w = 2 * Math.PI * f / rate, c = Math.cos(w), al = Math.sin(w) / (2 * q);
        return new Biquad((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + al, -2 * c, 1 - al);
    }

    private static Biquad bell(double f, double q, double db, int rate) {
        double a = Math.pow(10, db / 40), w = 2 * Math.PI * f / rate, c = Math.cos(w), al = Math.sin(w) / (2 * q);
        return new Biquad(1 + al * a, -2 * c, 1 - al * a, 1 + al / a, -2 * c, 1 - al / a);
    }

    private static Biquad lowShelf(double f, double db, int rate) {
        double a = Math.pow(10, db / 40), w = 2 * Math.PI * f / rate, c = Math.cos(w), s = Math.sin(w), al = s / 2 * Math.sqrt(2), r = 2 * Math.sqrt(a) * al;
        return new Biquad(a * ((a + 1) - (a - 1) * c + r), 2 * a * ((a - 1) - (a + 1) * c), a * ((a + 1) - (a - 1) * c - r),
                (a + 1) + (a - 1) * c + r, -2 * ((a - 1) + (a + 1) * c), (a + 1) + (a - 1) * c - r);
    }

    private static Biquad highShelf(double f, double db, int rate) {
        double a = Math.pow(10, db / 40), w = 2 * Math.PI * f / rate, c = Math.cos(w), s = Math.sin(w), al = s / 2 * Math.sqrt(2), r = 2 * Math.sqrt(a) * al;
        return new Biquad(a * ((a + 1) + (a - 1) * c + r), -2 * a * ((a - 1) + (a + 1) * c), a * ((a + 1) + (a - 1) * c - r),
                (a + 1) - (a - 1) * c + r, 2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - r);
    }

    /** The tuning of one of the buds. A negative sign undoes it exactly. */
    private static List<Biquad> buds(Mode mode, double sign, int rate) {
        if (mode == Mode.BUDS2) return Arrays.asList(
                lowShelf(350, sign * 1.0, rate), bell(4500, 1.4, sign * -4.3, rate), highShelf(7000, sign * 1.3, rate));
        return Arrays.asList(
                lowShelf(35, sign * 1.6, rate), lowShelf(320, sign * 0.8, rate), bell(2000, 1.5, sign * -1.1, rate),
                bell(3900, 1.8, sign * 1.7, rate), bell(8000, 1.6, sign * -1.8, rate), highShelf(12000, sign * 3.0, rate));
    }

    /** Keeps a buds preview level with Full, with a little more room when two curves stack. */
    private static final float BUDS_GAIN = 0.95f, BUDS_DIFFERENCE_GAIN = 0.8f;

    private final Biquad[] chain;
    private final float width, gain;

    public Preview(Mode mode, int rate) { this(mode, rate, null); }

    /**
     * @param worn the buds actually playing this, or null. They are already adding their own
     *             tuning, so a buds preview adds only the difference, and nothing on the same buds.
     */
    public Preview(Mode mode, int rate, Mode worn) {
        if (mode.buds()) {
            List<Biquad> c = new ArrayList<>();
            if (mode != worn) {
                c.addAll(buds(mode, 1, rate));
                if (worn != null) c.addAll(buds(worn, -1, rate));
            }
            chain = c.toArray(new Biquad[0]);
            width = 1f; gain = c.isEmpty() ? 1f : worn != null ? BUDS_DIFFERENCE_GAIN : BUDS_GAIN;
            return;
        }
        switch (mode) {
            case PHONE:
                // A current flagship: real stereo and useful upper bass, only the deep lows missing.
                chain = new Biquad[]{high(150, 0.8, rate), high(150, 0.8, rate), bell(320, 1.0, 1.5, rate), bell(3000, 1.2, 1.5, rate), low(17000, 0.7, rate)};
                width = 0.7f; gain = 0.75f;
                break;
            case SMALL:
                chain = new Biquad[]{high(85, 0.7, rate), high(85, 0.7, rate), bell(130, 1.2, 5, rate), bell(900, 1.0, -2, rate), low(15000, 0.7, rate)};
                width = 0f; gain = 0.55f;
                break;
            case MONO:
                chain = new Biquad[0];
                width = 0f; gain = 1f;
                break;
            default:
                chain = new Biquad[0];
                width = 1f; gain = 1f;
        }
    }

    /** Processes interleaved stereo in place. */
    public void process(float[] io, int frames) {
        if (chain.length == 0 && width == 1f) return;
        for (int i = 0; i < frames; i++) {
            float l = io[2 * i], r = io[2 * i + 1];
            float m = (l + r) * 0.5f, s = (l - r) * 0.5f * width;
            l = m + s; r = m - s;
            for (Biquad b : chain) { l = b.left(l); r = b.right(r); }
            io[2 * i] = l * gain;
            io[2 * i + 1] = r * gain;
        }
    }
}
