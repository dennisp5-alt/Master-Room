package com.dennis.masterroom.engine;

/** Turns one band down only in the moments it jumps out. Other moments are untouched. */
final class DynamicBand {
    static final class Settings {
        String name;
        double fromHz, toHz, thresholdDb, slope, maxCutDb, minShareDb;
        /** True: judge the jump against what the whole mix is doing at that moment. */
        boolean relative;
        Settings(String name, double fromHz, double toHz, double thresholdDb, double slope, double maxCutDb, double minShareDb, boolean relative) {
            this.name = name; this.fromHz = fromHz; this.toHz = toHz; this.thresholdDb = thresholdDb;
            this.slope = slope; this.maxCutDb = maxCutDb; this.minShareDb = minShareDb; this.relative = relative;
        }
    }

    static final class Outcome { double maxCutDb, activePercent; }

    static Outcome apply(float[] mid, int rate, Settings s) {
        final double from = s.fromHz, to = s.toHz;
        FirFilter split = new FirFilter(hz -> {
            double lo = from <= 0 ? 1 : 0.5 * (1 + Math.tanh(4 * Target.log2(Math.max(1, hz) / from)));
            double hi = 0.5 * (1 - Math.tanh(4 * Target.log2(Math.max(1, hz) / to)));
            return lo * hi;
        }, 4096, rate);
        float[] band = split.apply(mid);
        int step = Math.max(1, rate / 1000);
        double[] bandDb = Control.levels(band, step), wideDb = Control.levels(mid, step);
        int fast = 5, slow = 900;
        double[] bandFast = Control.smoothPower(bandDb, fast), bandSlow = Control.smoothPower(bandDb, slow);
        double[] wideFast = Control.smoothPower(wideDb, fast), wideSlow = Control.smoothPower(wideDb, slow);
        double[] cut = new double[bandDb.length];
        for (int i = 0; i < cut.length; i++) {
            double jump = bandFast[i] - bandSlow[i];
            if (s.relative) jump -= Math.max(0, wideFast[i] - wideSlow[i]);
            boolean audible = wideFast[i] > -50 && bandFast[i] - wideFast[i] > s.minShareDb;
            if (audible) cut[i] = Math.min(s.maxCutDb, Math.max(0, (jump - s.thresholdDb) * s.slope));
        }
        cut = Control.box(Control.holdMax(cut, 4), 9);
        Outcome out = new Outcome();
        int active = 0;
        double[] gainDb = new double[cut.length];
        for (int i = 0; i < cut.length; i++) {
            gainDb[i] = -cut[i];
            if (cut[i] > out.maxCutDb) out.maxCutDb = cut[i];
            if (cut[i] > 0.25) active++;
        }
        out.activePercent = 100.0 * active / Math.max(1, cut.length);
        Control.applyDb(mid, band, gainDb, step);
        return out;
    }
}
