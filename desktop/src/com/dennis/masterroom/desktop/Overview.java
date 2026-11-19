package com.dennis.masterroom.desktop;

import com.dennis.masterroom.engine.Wav;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/** A waveform overview (peak per column) read straight from a WAV, for the seek bar. */
final class Overview {
    private Overview() {}

    static float[] peaks(File wav, int columns) throws IOException {
        Wav.Header h = Wav.header(wav);
        float[] peaks = new float[columns];
        long frames = h.frames;
        if (frames <= 0) return peaks;
        int step = h.frameBytes(), chunk = 8192;
        byte[] raw = new byte[chunk * step];
        float[] l = new float[chunk], r = new float[chunk];
        try (InputStream in = new BufferedInputStream(new FileInputStream(wav), 1 << 16)) {
            in.skipNBytes(h.dataStart);
            long done = 0;
            while (done < frames) {
                int n = (int) Math.min(chunk, frames - done);
                in.readNBytes(raw, 0, n * step);
                Wav.decode(h, raw, n, l, r);
                for (int i = 0; i < n; i++) {
                    int c = (int) ((done + i) * columns / frames);
                    float a = Math.max(Math.abs(l[i]), Math.abs(r[i]));
                    if (a > peaks[c]) peaks[c] = a;
                }
                done += n;
            }
        }
        return peaks;
    }
}
