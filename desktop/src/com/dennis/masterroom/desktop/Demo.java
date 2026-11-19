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
            {"Punch", "Slower attack lets drums through, more of the peaks go to the clipper", "-14.0", "-2.0", "Fixed 32 spots, passed", "Safe after encoding: MP3 320 -2.0 dBTP, MP3 128 -1.7 dBTP, AAC 256 -1.9 dBTP"}};
        double[] target = Target.builtIn(Target.Style.CLASSIC_ROCK, true).midDb;
        // Sample tone shapes relative to the target (dB per band): a darker, boomier source and three takes near the target.
        o.targetMid = target;
        o.beforeMid = shape(target, -0.55, 2.4, 0.4);
        double[][] takes = {shape(target, -0.10, 0.6, 0.15), shape(target, -0.22, 1.2, 0.2), shape(target, -0.05, 1.6, 0.35)};
        double[] lra = {5.2, 4.7, 4.9};
        int k = 0;
        for (String[] r : rows) {
            MasterJob.Take t = new MasterJob.Take();
            t.mid = takes[k]; t.lra = lra[k++];
            t.label = r[0]; t.about = r[1]; t.lufs = Double.parseDouble(r[2]); t.truePeak = Double.parseDouble(r[3]); t.ceiling = -2.0;
            t.verdict = r[4]; t.streaming = r[5]; t.files = new File[0];
            o.takes.add(t);
        }
        o.beforeLufs = -18.2; o.beforePeak = -3.1; o.beforeLra = 6.4;
        o.targetLufs = -14; o.style = Target.Style.CLASSIC_ROCK;
        o.seconds = 84;
        o.sheet = "Sample";
        o.settings = "Classic Rock · -14 LUFS · commercial reference · clean-up on · MP3 320";
        return o;
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
