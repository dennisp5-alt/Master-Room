package com.dennis.masterroom.engine;

import java.util.Arrays;

/**
 * Hears a song as quieter and bigger sections, the way an engineer rides a master.
 * Each kind of section gets a small tone correction towards the song as a whole, and if
 * the centre loses ground in the big sections, as a lead vocal does under wide guitars,
 * the centre gets a small lift there. Lead vocals are assumed to be in the centre.
 */
final class Sections {
    static final class Plan {
        /** 0 quiet to 1 big, every 10 ms. */
        float[] big;
        Eq.Moves quiet = new Eq.Moves(), loud = new Eq.Moves();
        double vocalDb, bigPercent;
        int sections;
        boolean active() {
            return big != null && (vocalDb != 0 || any(quiet) || any(loud));
        }
        private static boolean any(Eq.Moves m) { return m.lowDb != 0 || m.lowMidDb != 0 || m.presenceDb != 0 || m.airDb != 0; }
    }

    private static final double MOST_DB = 0.8, MOST_VOCAL_DB = 1.0, HOLD_SECONDS = 4, FADE_SECONDS = 1;

    private Sections() {}

    static Plan find(Audio a) {
        Plan plan = new Plan();
        Loudness.Result loud = Loudness.measure(a);
        double[] blocks = loud.blocks;
        double blockSeconds = (double) loud.segmentFrames / a.rate;
        int span = Math.max(1, (int) Math.round(3 / blockSeconds)), n = blocks.length;
        if (n < span * 4) return plan;
        // Three seconds of smoothing, so a fill or a held note does not count as a section.
        double[] smooth = new double[n], line = new double[2 * span + 1];
        for (int i = 0; i < n; i++) {
            int c = 0;
            for (int j = Math.max(0, i - span); j <= Math.min(n - 1, i + span); j++) line[c++] = blocks[j];
            Arrays.sort(line, 0, c);
            smooth[i] = line[c / 2];
        }
        double[] sorted = smooth.clone();
        Arrays.sort(sorted);
        double low = sorted[(int) (n * 0.2)], high = sorted[(int) (n * 0.9)];
        if (high - low < 1.5) return plan;
        double middle = low + (high - low) * 0.55;
        boolean[] big = new boolean[n];
        for (int i = 0; i < n; i++) big[i] = smooth[i] > middle;
        int hold = (int) Math.round(HOLD_SECONDS / blockSeconds);
        int start = 0;
        for (int i = 1; i <= n; i++) {
            if (i == n || big[i] != big[start]) {
                if (i - start < hold && start > 0 && i < n) { Arrays.fill(big, start, i, !big[start]); start = 0; i = 0; continue; }
                start = i;
            }
        }
        int bigBlocks = 0, changes = 0;
        for (int i = 0; i < n; i++) { if (big[i]) bigBlocks++; if (i > 0 && big[i] != big[i - 1]) changes++; }
        if (changes == 0 || bigBlocks < n * 0.15 || bigBlocks > n * 0.85) return plan;
        plan.sections = changes + 1;
        plan.bigPercent = 100.0 * bigBlocks / n;

        Listen whole = Listen.tone(a), bigTone = Listen.tone(gather(a, big, loud.segmentFrames, true)), quietTone = Listen.tone(gather(a, big, loud.segmentFrames, false));
        Target home = new Target("Whole song", whole.midDb, null, false);
        plan.quiet = gentle(Eq.decide(quietTone, home, 0.4));
        plan.loud = gentle(Eq.decide(bigTone, home, 0.4));
        double lost = centre(quietTone) - centre(bigTone);
        double lift = Math.max(0, Math.min(MOST_VOCAL_DB, lost * 0.5));
        plan.vocalDb = lift < 0.2 ? 0 : Math.round(lift * 10) / 10.0;

        int size = a.frames() / (a.rate / 100) + 2;
        float[] target = new float[size];
        for (int i = 0; i < size; i++) target[i] = big[Math.min(n - 1, (int) (i * 0.01 / blockSeconds))] ? 1 : 0;
        int fade = (int) (FADE_SECONDS * 100);
        float[] sum = new float[size + 1];
        for (int i = 0; i < size; i++) sum[i + 1] = sum[i] + target[i];
        plan.big = new float[size];
        for (int i = 0; i < size; i++) {
            int x = Math.max(0, i - fade / 2), y = Math.min(size, i + fade / 2 + 1);
            plan.big[i] = (sum[y] - sum[x]) / (y - x);
        }
        return plan;
    }

    private static Eq.Moves gentle(Eq.Moves m) {
        Eq.Moves out = new Eq.Moves();
        out.lowDb = small(m.lowDb); out.lowMidDb = small(m.lowMidDb);
        out.presenceDb = small(m.presenceDb); out.airDb = small(m.airDb);
        return out;
    }

    private static double small(double v) {
        double c = Math.max(-MOST_DB, Math.min(MOST_DB, v));
        return Math.abs(c) < 0.2 ? 0 : Math.round(c * 10) / 10.0;
    }

    /** Centre against sides where a lead vocal lives. */
    private static double centre(Listen l) {
        double sum = 0; int c = 0;
        for (int i = 0; i < 31; i++) if (Listen.BANDS[i] >= 1500 && Listen.BANDS[i] <= 4000) { sum += l.midDb[i] - l.sideDb[i]; c++; }
        return sum / Math.max(1, c);
    }

    private static Audio gather(Audio a, boolean[] big, int blockFrames, boolean want) {
        int blocks = 0;
        for (boolean b : big) if (b == want) blocks++;
        Audio out = new Audio(Math.min(a.frames(), blocks * blockFrames), a.rate);
        int to = 0;
        for (int b = 0; b < big.length; b++) {
            if (big[b] != want) continue;
            int from = b * blockFrames, len = Math.min(blockFrames, Math.min(a.frames() - from, out.frames() - to));
            if (len <= 0) break;
            System.arraycopy(a.l, from, out.l, to, len);
            System.arraycopy(a.r, from, out.r, to, len);
            to += len;
        }
        return out;
    }

    /** One channel through its quiet and big filters at once, blended as the song moves. */
    static float[] blend(FirFilter quiet, FirFilter big, float[] x, int rate, Plan p) {
        float[] out = new float[x.length];
        final int step = rate / 100;
        final float[] map = p.big;
        quiet.stream(big, x, (i, a, b) -> {
            float mix = map[Math.min(map.length - 1, i / step)];
            out[i] = (float) (a + (b - a) * mix);
        });
        return out;
    }
}
