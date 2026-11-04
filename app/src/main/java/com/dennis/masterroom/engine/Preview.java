package com.dennis.masterroom.engine;

/**
 * Rough listening previews for the comparison player only. They suggest how a small
 * device tends to change a master. They are general curves, not measurements of any
 * product, and they never touch a file.
 */
public final class Preview {
    public enum Mode {
        FULL("Full"), PHONE("Phone"), BUDS("Buds"), SMALL("Small"), MONO("Mono");
        public final String label;
        Mode(String label) { this.label = label; }
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

    private final Biquad[] chain;
    private final float width, gain;

    public Preview(Mode mode, int rate) {
        switch (mode) {
            case PHONE:
                chain = new Biquad[]{high(320, 0.7, rate), high(320, 0.7, rate), bell(2800, 1.0, 4, rate), low(13000, 0.7, rate)};
                width = 0.25f; gain = 0.6f;
                break;
            case BUDS:
                chain = new Biquad[]{lowShelf(90, 4, rate), bell(450, 1.0, -1.5, rate), highShelf(7000, 2, rate)};
                width = 1f; gain = 0.6f;
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
