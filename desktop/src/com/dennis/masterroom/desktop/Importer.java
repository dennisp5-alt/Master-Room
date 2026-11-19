package com.dennis.masterroom.desktop;

import com.dennis.masterroom.engine.Audio;
import com.dennis.masterroom.engine.Listen;
import com.dennis.masterroom.engine.Wav;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

/**
 * A master made elsewhere, brought in to compare (the phone's "Import Stem Lab master"): measured as the takes
 * are, and kept as a 24-bit WAV in the round's folder at the song's sample rate so it plays alongside them.
 */
final class Importer {
    private Importer() {}

    static MasterJob.Take take(File f, MasterJob.Outcome round) throws IOException {
        int rate = Wav.header(round.source).rate;
        Audio a = MasterJob.open(f);
        if (a.rate != rate) {
            if (!Ffmpeg.available()) throw new IOException("it is at " + a.rate + " Hz and the song is at " + rate + " Hz; FFmpeg is needed to convert it");
            a = Ffmpeg.decode(f, rate);
        }
        Listen l = Listen.to(a);
        String name = f.getName().replaceAll("\\.[^.]+$", "");
        File wav = new File(round.dir, "imported_" + MasterJob.safeName(name) + ".wav");
        Wav.write(wav, a, 24);
        MasterJob.Take t = new MasterJob.Take();
        t.kind = "imported";
        t.label = name.length() > 22 ? name.substring(0, 21) + "…" : name;
        t.about = "Imported master: " + f.getName();
        t.lufs = l.loudness.integrated; t.truePeak = l.truePeakDb; t.lra = l.loudness.lra; t.punch = l.punchDb;
        t.mid = l.midDb; t.side = l.sideDb;
        t.ceiling = Double.NaN;
        t.verdict = "Imported to compare";
        t.streaming = String.format(Locale.US, "Measured here: %.1f LUFS, %.1f dBTP", t.lufs, t.truePeak);
        t.files = new File[]{wav};
        return t;
    }
}
