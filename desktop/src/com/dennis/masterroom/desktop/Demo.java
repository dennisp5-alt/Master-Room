package com.dennis.masterroom.desktop;

import com.dennis.masterroom.engine.Listen;
import com.dennis.masterroom.engine.Target;

import java.io.File;

/** Sample results for the screenshot check only (never shown in the app). */
final class Demo {
    private Demo() {}

    static MasterJob.Outcome outcome() {
        MasterJob.Outcome o = new MasterJob.Outcome();
        o.dir = new File(MasterJob.defaultOutRoot(), "Dusty Road (final mix)");
        String[][] rows = {
            {"Clean", "Transparent path: no colour, light compression", "-14.0", "-2.2", "Fixed 29 spots, passed", "Safe after encoding: MP3 320 -2.0 dBTP, MP3 128 -2.0 dBTP, AAC 256 -2.0 dBTP"},
            {"Warm", "Transformer, tape and valve path, medium compression", "-14.0", "-2.0", "Fixed 28 spots, passed", "Safe after encoding: MP3 320 -2.1 dBTP, MP3 128 -1.9 dBTP, AAC 256 -1.0 dBTP"},
            {"Punch", "Slower attack lets drums through, more of the peaks go to the clipper", "-14.0", "-2.0", "Fixed 32 spots, passed", "Safe after encoding: MP3 320 -2.0 dBTP, MP3 128 -1.7 dBTP, AAC 256 -1.9 dBTP"},
            {"Yours", "Blend of your picks: Clean 2.0, Warm 5.5, Punch 1.5", "-14.0", "-2.0", "Fixed 30 spots, passed", "Safe after encoding: MP3 320 -2.0 dBTP, MP3 128 -1.8 dBTP, AAC 256 -1.9 dBTP"}};
        double[] target = Target.builtIn(Target.Style.CLASSIC_ROCK, true).midDb;
        // Sample tone shapes relative to the target (dB per band): a darker, boomier source and three takes near the target.
        o.targetMid = target;
        o.beforeMid = shape(target, -0.55, 2.4, 0.4);
        double[][] takes = {shape(target, -0.10, 0.6, 0.15), shape(target, -0.22, 1.2, 0.2), shape(target, -0.05, 1.6, 0.35), shape(target, -0.16, 1.1, 0.22)};
        double[] lra = {5.2, 4.7, 4.9, 4.8}, punch = {9.1, 8.4, 10.2, 8.9};
        // Sample side (width) shapes, and a round aimed at reference records, so the records gap shows.
        o.beforeSide = shape(target, -0.4, -6, 0.3);
        o.beforePunch = 11.0;
        o.targetName = "4 reference records";
        o.targetPunch = 9.0; o.targetWidth = -7.5; o.targetLow = 1.2;
        int k = 0;
        for (String[] r : rows) {
            MasterJob.Take t = new MasterJob.Take();
            t.mid = takes[k]; t.side = shape(target, -0.3, -5.5 + k * 0.3, 0.25); t.punch = punch[k]; t.lra = lra[k++];
            t.label = r[0]; t.about = r[1]; t.lufs = Double.parseDouble(r[2]); t.truePeak = Double.parseDouble(r[3]); t.ceiling = -2.0;
            t.verdict = r[4]; t.streaming = r[5]; t.files = new File[0];
            o.takes.add(t);
        }
        Listen near = Listen.fromBands(o.takes.get(0).mid, o.takes.get(0).side);
        o.targetLow = near.lowEndDb() + 0.6; o.targetWidth = near.widthDb() - 0.4;
        o.beforeLufs = -18.2; o.beforePeak = -3.1; o.beforeLra = 6.4;
        o.targetLufs = -14; o.style = Target.Style.CLASSIC_ROCK;
        o.seconds = 84;
        o.sheet = "Sample";
        o.settings = "Classic Rock · -14 LUFS · commercial reference · clean-up on · MP3 320 · tone from 4 reference records";
        return o;
    }

    /**
     * Four synthetic stems (sung tone with phrases and vibrato, kick and snare, bass, chord pad) for
     * screenshots and checks, so no real song is ever needed or committed.
     */
    static File[] writeStems(File dir, String title, int seconds) throws java.io.IOException {
        int rate = 48000, n = seconds * rate;
        String[] parts = {"Vocals", "Drums", "Bass", "Other"};
        File[] out = new File[4];
        java.util.Random rnd = new java.util.Random(11);
        for (int p = 0; p < 4; p++) {
            com.dennis.masterroom.engine.Audio a = new com.dennis.masterroom.engine.Audio(n, rate);
            double phase = 0;
            for (int i = 0; i < n; i++) {
                double t = i / (double) rate, v;
                switch (p) {
                    case 0 -> {
                        double phrase = Math.max(0, Math.sin(Math.PI * (t % 2.0) / 2.0));
                        phase += 2 * Math.PI * (220 + 4 * Math.sin(2 * Math.PI * 5.5 * t)) * (1 + 0.12 * Math.floor((t % 8) / 2)) / rate;
                        v = phrase * (0.18 * Math.sin(phase) + 0.07 * Math.sin(2 * phase) + 0.04 * Math.sin(3 * phase)) + 0.004 * rnd.nextGaussian() * phrase;
                    }
                    case 1 -> {
                        double k = t % 0.5, sn = (t + 0.5) % 1.0;
                        v = 0.32 * Math.exp(-k * 30) * Math.sin(2 * Math.PI * (55 + 60 * Math.exp(-k * 40)) * k)
                            + (sn < 0.12 ? 0.16 * Math.exp(-sn * 35) * rnd.nextGaussian() : 0);
                    }
                    case 2 -> {
                        double f = (t % 4) < 2 ? 55 : 73.4;
                        v = 0.22 * Math.sin(2 * Math.PI * f * t) + 0.05 * Math.sin(4 * Math.PI * f * t);
                    }
                    default -> v = 0.06 * (Math.sin(2 * Math.PI * 261.6 * t) + Math.sin(2 * Math.PI * 329.6 * t) + Math.sin(2 * Math.PI * 392 * t)) + 0.006 * rnd.nextGaussian();
                }
                double pan = p == 3 ? 0.15 * Math.sin(2 * Math.PI * 0.2 * t) : 0;
                a.l[i] = (float) (v * (1 - pan));
                a.r[i] = (float) (v * (1 + pan));
            }
            out[p] = new File(dir, title + " - " + parts[p] + ".wav");
            com.dennis.masterroom.engine.Wav.write(out[p], a, 24);
        }
        return out;
    }

    /** target + a tilt (dB per band above 1 kHz), a low-end lift (dB below 150 Hz) and a gentle ripple. */
    private static double[] shape(double[] target, double tiltPerBand, double lowLift, double ripple) {
        double[] out = new double[31];
        for (int i = 0; i < 31; i++) {
            double f = Listen.BANDS[i];
            out[i] = target[i] + (f > 1000 ? tiltPerBand * (i - 17) : 0) + (f < 150 ? lowLift * (150 - f) / 130 : 0) + ripple * Math.sin(i * 0.9);
        }
        return out;
    }
}
