package com.dennis.masterroom.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** A few broad, decisive moves instead of a curve match. */
final class Eq {
    static double bell(double f, double fc, double width, double db) {
        double o = Target.log2(Math.max(1, f) / fc) / width;
        return db * Math.exp(-0.5 * o * o);
    }
    static double lowShelf(double f, double fc, double db) {
        return db * 0.5 * (1 - Math.tanh(1.6 * Target.log2(Math.max(1, f) / fc)));
    }
    static double highShelf(double f, double fc, double db) {
        return db * 0.5 * (1 + Math.tanh(1.6 * Target.log2(Math.max(1, f) / fc)));
    }

    static final class Moves {
        double lowDb, lowMidDb, presenceDb, airDb;
        double sideAirDb, sideLowMidDb;
        double monoBelowHz;

        double midDb(double f) {
            return lowShelf(f, 90, lowDb) + bell(f, 280, 0.8, lowMidDb)
                    + bell(f, 3200, 0.8, presenceDb) + highShelf(f, 9000, airDb);
        }
        double sideDb(double f) {
            return midDb(f) + bell(f, 280, 0.8, sideLowMidDb) + highShelf(f, 9000, sideAirDb);
        }
        List<String> describe() {
            List<String> out = new ArrayList<>();
            add(out, "Lows (below 90 Hz)", lowDb);
            add(out, "Low mids (280 Hz)", lowMidDb);
            add(out, "Presence (3.2 kHz)", presenceDb);
            add(out, "Air (above 9 kHz)", airDb);
            add(out, "Sides, low mids", sideLowMidDb);
            add(out, "Sides, air", sideAirDb);
            if (monoBelowHz > 0) out.add(String.format(Locale.US, "Bass centred below %.0f Hz", monoBelowHz));
            return out;
        }
        private static void add(List<String> out, String name, double db) {
            if (Math.abs(db) >= 0.05) out.add(String.format(Locale.US, "%s %+.1f dB", name, db));
        }
    }

    /** Solves for the four mid moves that best close the gap, leaving 500 Hz-1 kHz alone. */
    static Moves decide(Listen song, Target target, double strength) {
        double[] gap = new double[31], weight = new double[31];
        double c = 0, cw = 0;
        for (int i = 0; i < 31; i++) {
            double f = Listen.BANDS[i];
            if (f >= 150 && f <= 3000) { c += target.midDb[i] - song.midDb[i]; cw++; }
        }
        c /= cw;
        for (int i = 0; i < 31; i++) {
            double f = Listen.BANDS[i];
            gap[i] = target.midDb[i] - song.midDb[i] - c;
            boolean live = f >= 40 && f <= Math.min(16000, song.cutoffHz * 0.85);
            weight[i] = live ? 1 : 0;
        }
        double[][] basis = new double[4][31];
        for (int i = 0; i < 31; i++) {
            double f = Listen.BANDS[i];
            basis[0][i] = lowShelf(f, 90, 1);
            basis[1][i] = bell(f, 280, 0.8, 1);
            basis[2][i] = bell(f, 3200, 0.8, 1);
            basis[3][i] = highShelf(f, 9000, 1);
        }
        double[] x = solve(basis, gap, weight);
        Moves m = new Moves();
        // A curve measured from real records earns bigger moves than the built-in guess.
        double reach = target.measured ? 3.0 : 2.0;
        m.lowDb = shape(x[0], strength, reach);
        m.lowMidDb = shape(x[1], strength, reach);
        m.presenceDb = shape(x[2], strength, reach);
        m.airDb = shape(x[3], strength, reach);
        if (target.sideRelDb != null) {
            double sum = 0, n = 0, low = 0, ln = 0;
            for (int i = 0; i < 31; i++) {
                double f = Listen.BANDS[i], want = target.sideRelDb[i] - (song.sideDb[i] - song.midDb[i]);
                if (f >= 8000 && f <= Math.min(16000, song.cutoffHz * 0.85)) { sum += want; n++; }
                if (f >= 180 && f <= 400) { low += want; ln++; }
            }
            if (n > 0) m.sideAirDb = shape(sum / n, strength, 2.0);
            if (ln > 0) m.sideLowMidDb = shape(low / ln, strength, 2.0);
        }
        if (song.lowSideDb > -14) m.monoBelowHz = 100;
        return m;
    }

    private static double shape(double x, double strength, double limit) {
        double v = x * strength;
        if (Math.abs(v) < 0.3) return 0;
        v = Math.max(-limit, Math.min(limit, v));
        return Math.round(v * 10) / 10.0;
    }

    /** Weighted least squares by the normal equations. */
    private static double[] solve(double[][] basis, double[] y, double[] w) {
        int n = basis.length;
        double[][] a = new double[n][n + 1];
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) for (int i = 0; i < y.length; i++) a[r][c] += w[i] * basis[r][i] * basis[c][i];
            for (int i = 0; i < y.length; i++) a[r][n] += w[i] * basis[r][i] * y[i];
            a[r][r] += 0.05;
        }
        for (int p = 0; p < n; p++) {
            int best = p;
            for (int r = p + 1; r < n; r++) if (Math.abs(a[r][p]) > Math.abs(a[best][p])) best = r;
            double[] t = a[p]; a[p] = a[best]; a[best] = t;
            for (int r = 0; r < n; r++) {
                if (r == p) continue;
                double f = a[r][p] / a[p][p];
                for (int c = p; c <= n; c++) a[r][c] -= f * a[p][c];
            }
        }
        double[] x = new double[n];
        for (int i = 0; i < n; i++) x[i] = a[i][n] / a[i][i];
        return x;
    }
}
