package com.dennis.masterroom.desktop;

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
        for (String[] r : rows) {
            MasterJob.Take t = new MasterJob.Take();
            t.label = r[0]; t.about = r[1]; t.lufs = Double.parseDouble(r[2]); t.truePeak = Double.parseDouble(r[3]); t.ceiling = -2.0;
            t.verdict = r[4]; t.streaming = r[5]; t.files = new File[0];
            o.takes.add(t);
        }
        o.seconds = 84;
        o.sheet = "Sample";
        return o;
    }
}
